#include "audio_sink.h"

#include "audio_device.h"

#include <windows.h>
#include <audioclient.h>
#include <mmdeviceapi.h>
#include <functiondiscoverykeys_devpkey.h>
#include <wrl/client.h>

#include <algorithm>
#include <cctype>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>

using Microsoft::WRL::ComPtr;

namespace cineduct::host {

namespace {
/** 单调毫秒（欠载抑制窗口用；steady_clock 不会因为改系统时间而跳）。 */
uint64_t steadyNowMs() {
    return static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count());
}

/**
 * 欠载判定线：写入时设备缓冲里剩下的不足这么多毫秒，就算"设备已经见底"。
 *
 * <p>为什么不卡死在 0：设备待播读数本身会抖（见 playedFrames() 里记的那些坑），
 * 卡死会漏报。Java 侧（进程内引擎）的等价容差约 43ms，这里取 25ms 更保守。
 */
constexpr double kUnderrunDrainMs = 25.0;

/** 抑制窗口长度：flush / 恢复 / 起播之后这么久内不记欠载。 */
constexpr uint64_t kUnderrunGraceMs = 400;
} // namespace

namespace {

// 环形缓冲容量：约 1 秒。太大则暂停/重连的响应变慢，太小则网络抖动直接变成欠载。
// 直通时这是**链路**的 1 秒（DD+ 768KB/s、TrueHD 3MB/s），同样是约 1 秒的余量。
constexpr int kRingSeconds = 1;

std::string hrText(const char* what, HRESULT hr) {
    char buf[256];
    std::snprintf(buf, sizeof(buf), "%s (hr=0x%08lx)", what, static_cast<unsigned long>(hr));
    return buf;
}

inline int16_t clamp16(float v) {
    if (v > 32767.0f) return 32767;
    if (v < -32768.0f) return -32768;
    return static_cast<int16_t>(v);
}

/** 按描述填一块 WAVEFORMATEXTENSIBLE（缓冲区给 64 字节，两种 cbSize 都放得下）。 */
void buildWaveFormat(const AudioFormat& f, BYTE* raw) {
    std::memset(raw, 0, 64);
    auto* wf = reinterpret_cast<WAVEFORMATEXTENSIBLE*>(raw);
    wf->Format.wFormatTag = WAVE_FORMAT_EXTENSIBLE;
    wf->Format.nChannels = f.channels;
    wf->Format.nSamplesPerSec = f.rate;
    wf->Format.wBitsPerSample = f.containerBits;
    wf->Format.nBlockAlign = f.blockAlign;
    wf->Format.nAvgBytesPerSec = f.rate * f.blockAlign;
    wf->Format.cbSize = f.iec61937Struct ? 34 : 22;
    wf->Samples.wValidBitsPerSample = f.validBits;
    wf->dwChannelMask = f.channelMask;
    static_assert(sizeof(GUID) == sizeof(FormatGuid), "FormatGuid 必须与 Windows GUID 同尺寸");
    std::memcpy(&wf->SubFormat, &f.subFormat, sizeof(GUID));
    if (f.iec61937Struct) {
        // WAVEFORMATEXTENSIBLE_IEC61937 的三个追加字段：解码后的真实采样率/声道数/码率
        auto* p = reinterpret_cast<DWORD*>(raw + 40);
        p[0] = f.encodedRate;
        p[1] = f.encodedChannels;
        p[2] = 0;
    }
}

std::string utf8Of(const wchar_t* w) {
    if (w == nullptr) return {};
    char buf[512];
    const int n = WideCharToMultiByte(CP_UTF8, 0, w, -1, buf, sizeof(buf), nullptr, nullptr);
    return n > 0 ? std::string(buf) : std::string();
}

std::string friendlyName(IMMDevice* dev) {
    ComPtr<IPropertyStore> props;
    if (FAILED(dev->OpenPropertyStore(STGM_READ, &props))) return "(无名)";
    PROPVARIANT v;
    PropVariantInit(&v);
    std::string name = "(无名)";
    if (SUCCEEDED(props->GetValue(PKEY_Device_FriendlyName, &v)) && v.vt == VT_LPWSTR) {
        name = utf8Of(v.pwszVal);
    }
    PropVariantClear(&v);
    return name;
}

/** 名字子串匹配（大小写不敏感，只对 ASCII 折叠，中文原名照常匹配）。 */
bool nameContains(const std::string& haystack, const std::string& needle) {
    if (needle.empty()) return true;
    std::string a = haystack;
    std::string b = needle;
    for (char& c : a) c = static_cast<char>(::tolower(static_cast<unsigned char>(c)));
    for (char& c : b) c = static_cast<char>(::tolower(static_cast<unsigned char>(c)));
    return a.find(b) != std::string::npos;
}

} // namespace

const char* AudioSink::modeName() const {
    if (this->passthrough) return "IEC61937直通";
    if (this->exclusive) return "独占PCM";
    return "共享模式";
}

bool AudioSink::resolveDevice(const std::string& selector, void** outDevice, std::string& error) {
    *outDevice = nullptr;

    ComPtr<IMMDeviceEnumerator> enumerator;
    HRESULT hr = CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL,
                                  __uuidof(IMMDeviceEnumerator), &enumerator);
    if (FAILED(hr)) {
        error = hrText("创建 MMDeviceEnumerator 失败", hr);
        return false;
    }

    if (selector.empty()) {
        ComPtr<IMMDevice> dev;
        const HRESULT defaultHr = enumerator->GetDefaultAudioEndpoint(eRender, eConsole, &dev);
        if (FAILED(defaultHr)) {
            error = hrText("取默认播放设备失败", defaultHr);
            return false;
        }
        *outDevice = dev.Detach();
        return true;
    }

    ComPtr<IMMDeviceCollection> devices;
    HRESULT hr2 = enumerator->EnumAudioEndpoints(eRender, DEVICE_STATE_ACTIVE, &devices);
    if (FAILED(hr2)) {
        error = hrText("枚举播放设备失败", hr2);
        return false;
    }
    UINT count = 0;
    devices->GetCount(&count);

    // "@3" = --list-audio 里的编号；其它按设备名子串匹配
    if (selector.size() > 1 && selector[0] == '@') {
        const int index = std::atoi(selector.c_str() + 1);
        if (index < 0 || static_cast<UINT>(index) >= count) {
            error = "设备编号超范围（见 --list-audio）";
            return false;
        }
        ComPtr<IMMDevice> dev;
        if (FAILED(devices->Item(static_cast<UINT>(index), &dev))) {
            error = "按编号取设备失败";
            return false;
        }
        *outDevice = dev.Detach();
        return true;
    }

    // 名字可能重名（同一显卡多个 HDMI 口都叫 "NVIDIA High Definition Audio"），
    // 先收集所有命中的，只有一个才用，避免"选错设备却以为成功了"。
    std::vector<ComPtr<IMMDevice>> hits;
    std::vector<std::string> hitNames;
    for (UINT i = 0; i < count; ++i) {
        ComPtr<IMMDevice> dev;
        if (FAILED(devices->Item(i, &dev))) continue;
        const std::string name = friendlyName(dev.Get());
        if (nameContains(name, selector)) {
            hits.push_back(dev);
            hitNames.push_back(name);
        }
    }
    if (hits.empty()) {
        error = "找不到名字里含 \"" + selector + "\" 的播放设备";
        return false;
    }
    if (hits.size() > 1) {
        // 允许用 "名字#序号" 精确指定（例如 "NVIDIA High Definition Audio#1"）
        const auto hash = selector.rfind('#');
        if (hash != std::string::npos) {
            const int nth = std::atoi(selector.c_str() + hash + 1);
            const std::string base = selector.substr(0, hash);
            int seen = 0;
            for (size_t i = 0; i < hits.size(); ++i) {
                if (!nameContains(hitNames[i], base)) continue;
                if (seen++ == nth) {
                    *outDevice = hits[i].Detach();
                    return true;
                }
            }
        }
        error = "有 " + std::to_string(hits.size()) + " 个设备名字都含 \"" + selector
                + "\"，请用 \"名字#序号\" 或 \"@编号\" 精确指定（见 --list-audio）";
        return false;
    }
    *outDevice = hits[0].Detach();
    return true;
}

bool AudioSink::open(const AudioOpenOptions& options, std::string& error) {
    // 允许"关了再开"（直通失败 → 退回 PCM 时会走一遍）：形态与计数全部复位
    this->exclusive = false;
    this->passthrough = false;
    this->copyVerbatim = false;
    this->deviceIsFloat = true;
    this->written.store(0);
    this->paused.store(false);
    this->failed.store(false);
    this->gain.store(1.0f);
    this->contentRateValue = 48000;

    HRESULT hr = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    const bool comOwned = SUCCEEDED(hr);
    (void) comOwned;

    ComPtr<IMMDeviceEnumerator> enumerator;
    hr = CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL,
                          __uuidof(IMMDeviceEnumerator), &enumerator);
    if (FAILED(hr)) {
        error = hrText("创建 MMDeviceEnumerator 失败", hr);
        return false;
    }

    void* rawDevice = nullptr;
    if (!resolveDevice(options.deviceSelector, &rawDevice, error)) return false;
    ComPtr<IMMDevice> device;
    device.Attach(static_cast<IMMDevice*>(rawDevice));
    this->deviceFriendly = friendlyName(device.Get());

    /*
     * 顺手问一句"空间音效（Dolby Atmos 那套）到底有没有在跑"。
     *
     * 走 WinRT 的 SpatialAudioDeviceConfiguration，能直接拿到**当前激活的格式名**
     * （例如 "Dolby Atmos for home theater"）。拿到之后由上层在游戏聊天框里只给本机玩家播报一次 ——
     * 用户没法靠耳朵可靠区分"真 Atmos"和"普通多声道混音"，而这个字段能给出确定答案。
     */
    {
        const SpatialAudioInfo spatial = querySpatialAudio(device.Get());
        if (spatial.queried) {
            const std::string& name = !spatial.defaultFormatName.empty()
                    ? spatial.defaultFormatName : spatial.activeFormatName;
            this->spatialFormatText = name;
            if (!spatial.supported) {
                this->spatialState = 3;          // 端点不支持空间音效
            } else if (!name.empty()) {
                this->spatialState = 1;          // 已启用（用户选的或系统在跑的）
            } else {
                this->spatialState = 0;          // 支持，但没启用
            }
        } else {
            // 兜底：端点属性里的空间音效标记（多数端点不上报，命中率低）
            bool spatialKnown = false;
            const bool spatialActive = querySpatialAudioActive(device.Get(), spatialKnown);
            this->spatialState = !spatialKnown ? 2 : (spatialActive ? 1 : 0);
            this->spatialFormatText = spatialKnown && spatialActive
                    ? "(端点属性上报空间音效已激活)" : "";
            if (!spatial.error.empty()) {
                std::printf("[host] 空间音效查询失败: %s\n", spatial.error.c_str());
            }
        }
    }

    ComPtr<IAudioClient> audioClient;
    hr = device->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr, &audioClient);
    if (FAILED(hr)) {
        error = hrText("激活 IAudioClient 失败", hr);
        return false;
    }

    WAVEFORMATEX* mix = nullptr;                 // 共享模式：系统的混音格式（负责释放）
    BYTE exclusiveFormat[64];                    // 独占/直通：我们指定的格式（栈上，不需释放）

    if (options.mode == AudioOutputMode::Shared) {
        hr = audioClient->GetMixFormat(&mix);
        if (FAILED(hr)) {
            error = hrText("GetMixFormat 失败", hr);
            return false;
        }
        this->rate = static_cast<int>(mix->nSamplesPerSec);
        this->channelCount = static_cast<int>(mix->nChannels);
        this->contentRateValue = this->rate;
        this->deviceIsFloat = mix->wBitsPerSample == 32;
        if (mix->wBitsPerSample != 32 && mix->wBitsPerSample != 16) {
            error = "设备混音格式既不是 32 位浮点也不是 16 位整数，暂不支持";
            CoTaskMemFree(mix);
            return false;
        }
        // 事件回调模式；缓冲时长取 200ms（共享模式下系统还会再加一层）
        const REFERENCE_TIME bufferDuration = 2000000;   // 100ns 单位 → 200ms
        hr = audioClient->Initialize(AUDCLNT_SHAREMODE_SHARED, AUDCLNT_STREAMFLAGS_EVENTCALLBACK,
                                     bufferDuration, 0, mix, nullptr);
        if (FAILED(hr)) {
            error = hrText("IAudioClient::Initialize 失败", hr);
            CoTaskMemFree(mix);
            return false;
        }
        this->logDeviceLatency(audioClient.Get());
    } else {
        // ---------- 独占 PCM / IEC 61937 直通 ----------
        buildWaveFormat(options.format, exclusiveFormat);
        auto* wf = reinterpret_cast<WAVEFORMATEX*>(exclusiveFormat);
        WAVEFORMATEX* closest = nullptr;
        const HRESULT supported = audioClient->IsFormatSupported(AUDCLNT_SHAREMODE_EXCLUSIVE, wf,
                                                                 &closest);
        if (supported != S_OK) {
            char msg[256];
            std::snprintf(msg, sizeof(msg), "设备不接受该独占格式（hr=0x%08lx，%uch %lukHz %ubit）",
                          static_cast<unsigned long>(supported), wf->nChannels,
                          wf->nSamplesPerSec / 1000, wf->wBitsPerSample);
            error = msg;
            if (closest != nullptr) CoTaskMemFree(closest);
            return false;
        }
        if (closest != nullptr) CoTaskMemFree(closest);

        REFERENCE_TIME defaultPeriod = 0;
        REFERENCE_TIME minPeriod = 0;
        audioClient->GetDevicePeriod(&defaultPeriod, &minPeriod);
        if (defaultPeriod <= 0) defaultPeriod = 100000;      // 兜底 10ms

        /*
         * 顺手把"设备自己报的流延迟"记下来（100ns 单位 → 毫秒）。
         *
         * 注意它**只包含声卡/驱动这一层**（共享模式下就是音频引擎的缓冲周期），
         * 不含 HDMI → 功放/回音壁那一大段处理延迟 —— 而后者才是"画面跑在声音前面"的主因。
         * 所以这个数字只用来看量级：真正的补偿量得靠 {@code /cineduct av <ms>} 一边看一边调。
         */
        this->logDeviceLatency(audioClient.Get());

        /*
         * ★ hnsPeriodicity 必须**等于** hnsBufferDuration（而不是 0）。
         *
         * 官方文档 IAudioClient::Initialize 明确写着：
         *   "If the AUDCLNT_STREAMFLAGS_EVENTCALLBACK stream flag is set and
         *    AUDCLNT_SHAREMODE_EXCLUSIVE is set as the ShareMode, then hnsPeriodicity
         *    must be nonzero and equal to hnsBufferDuration."
         * 传 0 的后果是 AUDCLNT_E_BUFFER_SIZE_ERROR (0x88890016) —— 而它的名字看起来像
         * "缓冲时长不合法"，于是很容易去疯狂调整时长（我们试过 10/20/40/50/80/100ms，全一样失败），
         * 真凶却在 periodicity 这个参数上。共享模式反过来要求它是 0。
         */
        /*
         * 缓冲时长阶梯。
         *
         * **直通（IEC 61937）的缓冲必须取成"整数个数据突发"**：
         *   * DD+/AC-3 的突发是 24576 / 6144 字节（= 32ms 链路时间），TrueHD 的 MAT 帧是 61440 字节（20ms）；
         *   * 缓冲小于一个突发时，驱动拿不到完整突发就**一个字节都不送**
         *     （Initialize 成功、Start 成功、设备时钟永远 0、待播恒等于满缓冲）；
         *   * 缓冲不是突发整数倍时更糟：实测取 100ms（= 3.16 个 DD+ 突发），
         *     每轮写/取都停在突发中间，Windows 音频引擎 audiokse.dll 直接访问违例崩溃。
         * 所以这里按"突发时长 × N"生成候选（N = 2…6），全都对齐到整突发。
         */
        std::vector<REFERENCE_TIME> passthroughDurations;
        const unsigned long burstMs = options.format.burstMs > 0 ? options.format.burstMs : 32;
        for (int multiple = 2; multiple <= 6; ++multiple) {
            passthroughDurations.push_back(
                    static_cast<REFERENCE_TIME>(burstMs) * multiple * 10000);   // ms → 100ns 单位
        }
        const REFERENCE_TIME pcmDurations[] = {
                defaultPeriod, defaultPeriod * 4, defaultPeriod * 8,
                200000, 500000, 1000000,          // 20ms / 50ms / 100ms
        };
        const REFERENCE_TIME* durationList = options.format.passthrough
                ? passthroughDurations.data() : pcmDurations;
        const size_t durationCount = options.format.passthrough
                ? passthroughDurations.size()
                : sizeof(pcmDurations) / sizeof(pcmDurations[0]);

        /*
         * 独占模式默认走**推模式（poll）**，而不是事件回调。
         *
         * 起因是一个查了很久的"独占流不推进"：Initialize 成功、Start 成功，
         * 但设备时钟永远 0、GetCurrentPadding 恒等于满缓冲 —— 事件驱动的独占流在
         * NVIDIA HDMI 端点上根本收不到事件（同一份代码在 USB 耳机端点上完全正常）。
         * 换成推模式后，同样的端点、同样的格式立刻正常推进（PCM 与 IEC 61937 直通都是）。
         *
         * 为什么推模式在这里完全够用：电影/视频播放对延迟不敏感（缓冲本来就是几十毫秒起），
         * 而画面节奏由音频时钟驱动，多几毫秒完全无感；代价只是一条 5ms 的轮询循环。
         * 想强制回事件驱动（延迟优先的场景）就设 MEDIAFIX_AUDIO_POLL=0。
         */
        if (const char* env = std::getenv("MEDIAFIX_AUDIO_POLL"); env != nullptr) {
            this->polling = env[0] != '0' && env[0] != '\0';
        } else {
            this->polling = true;
        }
        if (this->polling) {
            std::printf("[host] 独占流使用推模式（poll）：部分显卡 HDMI 驱动在事件驱动独占流上不推进\n");
        }
        const DWORD exclusiveFlags = this->polling ? 0u : AUDCLNT_STREAMFLAGS_EVENTCALLBACK;
        // 事件驱动时 periodicity 必须等于 bufferDuration；推模式则必须为 0
        const auto periodicityFor = [this](REFERENCE_TIME duration) -> REFERENCE_TIME {
            return this->polling ? 0 : duration;
        };
        REFERENCE_TIME chosen = 0;
        hr = E_FAIL;
        std::string attempts;
        for (size_t i = 0; i < durationCount; ++i) {
            const REFERENCE_TIME duration = durationList[i];
            hr = audioClient->Initialize(AUDCLNT_SHAREMODE_EXCLUSIVE, exclusiveFlags,
                                         duration, periodicityFor(duration), wf, nullptr);
            if (SUCCEEDED(hr)) {
                chosen = duration;
                break;
            }
            char note[96];
            std::snprintf(note, sizeof(note), " %.0fms→0x%08lx", duration / 10000.0,
                          static_cast<unsigned long>(hr));
            attempts += note;
            if (hr == AUDCLNT_E_BUFFER_SIZE_NOT_ALIGNED) {
                UINT32 alignedFrames = 0;
                if (SUCCEEDED(audioClient->GetBufferSize(&alignedFrames)) && alignedFrames > 0) {
                    const REFERENCE_TIME aligned = static_cast<REFERENCE_TIME>(
                            10000.0 * 1000 * alignedFrames / wf->nSamplesPerSec + 0.5);
                    hr = audioClient->Initialize(AUDCLNT_SHAREMODE_EXCLUSIVE,
                                                 exclusiveFlags, aligned, periodicityFor(aligned),
                                                 wf, nullptr);
                    if (SUCCEEDED(hr)) {
                        chosen = aligned;
                        break;
                    }
                }
            }
        }
        if (FAILED(hr)) {
            /*
             * 失败要能一眼看出是"端点整体不给独占"还是"只是我们这组格式不行"：
             * 除了把每次尝试的时长/结果打出来，再拿**设备自己的混音格式**试一次独占 ——
             * 那组参数是系统自己认的，它要是也失败，就说明端点/驱动层面就不支持独占。
             */
            std::printf("[host] 独占失败诊断: 设备周期 default=%.1fms min=%.1fms，尝试:%s\n",
                        defaultPeriod / 10000.0, minPeriod / 10000.0, attempts.c_str());
            WAVEFORMATEX* mixFormat = nullptr;
            if (SUCCEEDED(audioClient->GetMixFormat(&mixFormat)) && mixFormat != nullptr) {
                const HRESULT mixHr = audioClient->Initialize(AUDCLNT_SHAREMODE_EXCLUSIVE,
                                                              AUDCLNT_STREAMFLAGS_EVENTCALLBACK,
                                                              defaultPeriod, 0, mixFormat, nullptr);
                std::printf("[host] 对照试验（独占 + 设备混音格式 %lukHz/%uch/%ubit）: %s\n",
                            mixFormat->nSamplesPerSec / 1000, mixFormat->nChannels,
                            mixFormat->wBitsPerSample,
                            SUCCEEDED(mixHr) ? "✔ 成功 → 说明是这个格式/时长的问题"
                                             : hrText("✘ 也失败 → 端点层面就不给独占", mixHr).c_str());
                CoTaskMemFree(mixFormat);
            }
            error = hrText("独占模式 Initialize 失败（已试多种缓冲时长）", hr);
            return false;
        }
        std::printf("[host] 独占缓冲时长: %.0fms（设备周期 %.1fms）\n",
                    chosen / 10000.0, defaultPeriod / 10000.0);

        this->rate = static_cast<int>(options.format.rate);
        this->channelCount = static_cast<int>(options.format.channels);
        this->bytesPerFrame = static_cast<int>(options.format.blockAlign);
        this->validBits = options.format.validBits;
        this->contentRateValue = options.format.encodedRate > 0
                ? static_cast<int>(options.format.encodedRate) : this->rate;
        this->deviceIsFloat = false;
        this->exclusive = true;
        this->passthrough = options.format.passthrough;
        this->copyVerbatim = options.format.passthrough;
        if (options.format.passthrough && options.format.burstMs > 0) {
            // 一个突发 = 链路字节率 × 突发时长（DD+ 48k：768000 B/s × 32ms = 24576 字节）
            this->burstBytes = static_cast<int>(
                    static_cast<double>(this->rate) * this->bytesPerFrame
                    * static_cast<double>(options.format.burstMs) / 1000.0);
        }
    }

    // 轮询模式（MEDIAFIX_AUDIO_POLL=1）不需要事件句柄
    HANDLE evt = nullptr;
    if (!this->polling) {
        evt = CreateEventW(nullptr, FALSE, FALSE, nullptr);
        hr = audioClient->SetEventHandle(evt);
        if (FAILED(hr)) {
            error = hrText("SetEventHandle 失败", hr);
            CloseHandle(evt);
            if (mix != nullptr) CoTaskMemFree(mix);
            return false;
        }
    }

    ComPtr<IAudioRenderClient> renderClient;
    hr = audioClient->GetService(__uuidof(IAudioRenderClient), &renderClient);
    if (FAILED(hr)) {
        error = hrText("取 IAudioRenderClient 失败", hr);
        CloseHandle(evt);
        if (mix != nullptr) CoTaskMemFree(mix);
        return false;
    }

    UINT32 bufferFrameCount = 0;
    audioClient->GetBufferSize(&bufferFrameCount);
    this->bufferFrames = bufferFrameCount;
    if (!this->exclusive) {
        this->bytesPerFrame = this->channelCount * 2;
    }
    if (this->bytesPerFrame <= 0) this->bytesPerFrame = 4;

    if (this->passthrough) {
        std::printf("[host] WASAPI 输出: %s —— %dHz %dch %ubit 链路（%d 字节/帧，设备缓冲 %u 帧 / %.0fms）\n",
                    this->modeName(), this->rate, this->channelCount, this->validBits,
                    this->bytesPerFrame, this->bufferFrames,
                    this->bufferFrames * 1000.0 / this->rate);
        std::printf("[host]   注意：直通模式下码流不可修改 —— 音量/静音命令会被忽略（由接收端控音量）\n");
    } else if (this->exclusive) {
        std::printf("[host] WASAPI 输出: %s —— %dHz %dch %ubit（%d 字节/帧，设备缓冲 %u 帧 / %.0fms）\n",
                    this->modeName(), this->rate, this->channelCount, this->validBits,
                    this->bytesPerFrame, this->bufferFrames,
                    this->bufferFrames * 1000.0 / this->rate);
    } else {
        std::printf("[host] WASAPI 输出: %dHz %dch %s，设备缓冲 %u 帧 (%.0fms)\n", this->rate,
                    this->channelCount, this->deviceIsFloat ? "32-bit float" : "16-bit int",
                    this->bufferFrames, this->bufferFrames * 1000.0 / this->rate);
    }
    std::printf("[host] 端点: %s%s\n", this->deviceFriendly.c_str(),
                this->polling ? "（推模式/poll）" : "");
    std::printf("[host] 空间音效（Dolby Atmos 等）: %s\n",
                this->spatialState == 1
                        ? ("★ 已启用 —— " + this->spatialFormatText).c_str()
                : this->spatialState == 0 ? "未启用（该端点支持空间音效，但当前没选任何格式）"
                : this->spatialState == 3 ? "该端点不支持 Windows 空间音效"
                                          : "查不到（系统/端点未上报）");

    /*
     * 先填一段静音再启动，避免起播瞬间的爆音。
     *
     * **只在共享模式做**：
     *   * AUDCLNT_BUFFERFLAGS_SILENT 是"告诉音频引擎这块是静音、别去读数据"，独占模式下没有
     *     音频引擎、缓冲就是硬件缓冲，实测这么预填会让整条流永远不推进；
     *   * 直通模式更不能预填 —— 那会把一整段**全零字节**塞进 IEC 61937 链路，
     *     既不是合法突发（没有前导），也实测会把 Windows 音频引擎搞崩（audiokse.dll 访问违例）。
     *     什么都不写、等第一段真实码流到达即可。
     */
    if (!this->exclusive && !this->passthrough) {
        BYTE* startBuf = nullptr;
        if (SUCCEEDED(renderClient->GetBuffer(bufferFrameCount, &startBuf)) && startBuf != nullptr) {
            std::memset(startBuf, 0, static_cast<size_t>(bufferFrameCount) * this->bytesPerFrame);
            renderClient->ReleaseBuffer(bufferFrameCount, AUDCLNT_BUFFERFLAGS_SILENT);
        }
    }
    hr = audioClient->Start();
    if (FAILED(hr)) {
        error = hrText("IAudioClient::Start 失败", hr);
        CloseHandle(evt);
        if (mix != nullptr) CoTaskMemFree(mix);
        return false;
    }
    // 起播阶段设备必然是空的（还没喂过数据）：抑制窗口内不记欠载
    this->underrunLatched = false;
    this->graceUntilMs.store(steadyNowMs() + kUnderrunGraceMs);
    if (mix != nullptr) CoTaskMemFree(mix);

    this->ring.assign(static_cast<size_t>(this->rate) * this->bytesPerFrame * kRingSeconds, 0);
    this->ringRead = 0;
    this->ringUsed = 0;
    this->client = audioClient.Detach();
    this->render = renderClient.Detach();
    this->eventHandle = evt;
    this->opened = true;

    /*
     * 独占模式下 Initialize/Start 返回 S_OK **不代表引擎真的开始跑了**（"伪成功"）：
     * 立刻 GetBuffer 有几率拿到空指针，直接写进去就是访问违例。
     * 给引擎一小段时间完成内部就绪（只做一次，几十毫秒，对播放无感）。
     */
    if (this->exclusive) {
        std::this_thread::sleep_for(std::chrono::milliseconds(30));
    }

    this->quit.store(false);
    this->writer = std::thread([this] { this->writerLoop(); });
    return true;
}

int AudioSink::write(const uint8_t* data, int bytes) {
    if (!this->opened || data == nullptr || bytes <= 0) return 0;
    /*
     * 必须写满再返回。
     *
     * 曾经这里只写入"环形缓冲当时装得下的那部分"就返回，而调用方（解码线程）不看返回值 ——
     * 于是超出的样本被**静默丢弃**：10 秒的音轨只喂进了 5.23 秒，音频时钟永久冻在 5230ms
     * （表现是"打样时视频莫名其妙跑到 1.8 倍速 / 音频半途没了"）。
     * 正确做法就是背压：装不下就等消费端让出空间。
     */
    size_t pushed = 0;
    const uint64_t epoch = this->ringEpoch.load();
    while (pushed < static_cast<size_t>(bytes)) {
        std::unique_lock<std::mutex> lock(this->ringMutex);
        this->ringNotFull.wait(lock, [this, epoch] {
            return this->quit.load() || this->failed.load() || this->ringUsed < this->ring.size()
                    || this->ringEpoch.load() != epoch;
        });
        // 关闭、声卡已放弃、或被 interruptWrites() 打断：**立刻返回**，绝不在这里无限期挂着
        if (this->quit.load() || this->failed.load() || this->ringEpoch.load() != epoch) {
            return static_cast<int>(pushed);
        }

        const size_t space = this->ring.size() - this->ringUsed;
        const size_t n = std::min(space, static_cast<size_t>(bytes) - pushed);
        this->pushRing(data + pushed, n);
        pushed += n;
        lock.unlock();
        this->ringNotEmpty.notify_one();
    }
    return static_cast<int>(pushed);
}

void AudioSink::pushRing(const uint8_t* data, size_t bytes) {
    size_t writePos = (this->ringRead + this->ringUsed) % this->ring.size();
    size_t left = bytes;
    while (left > 0) {
        const size_t chunk = std::min(left, this->ring.size() - writePos);
        std::memcpy(this->ring.data() + writePos, data, chunk);
        data += chunk;
        left -= chunk;
        writePos = (writePos + chunk) % this->ring.size();
    }
    this->ringUsed += bytes;
}

size_t AudioSink::popRing(uint8_t* dst, size_t maxBytes) {
    const size_t n = std::min(maxBytes, this->ringUsed);
    size_t left = n;
    while (left > 0) {
        const size_t chunk = std::min(left, this->ring.size() - this->ringRead);
        std::memcpy(dst, this->ring.data() + this->ringRead, chunk);
        dst += chunk;
        left -= chunk;
        this->ringRead = (this->ringRead + chunk) % this->ring.size();
    }
    this->ringUsed -= n;
    return n;
}

void AudioSink::writerLoop() {
    /*
     * ★ 每个会调用 WASAPI 接口的线程都必须先加入 COM 公寓。
     *
     * 以前这条线程**没有** CoInitializeEx —— 于是它是在"未初始化公寓的线程上调用别处
     * （主线程的 MTA）拿到的接口指针"，属于未定义行为：是 Use-After-Free / 访问违例
     * （0xC0000005）的经典来源之一，且只在特定驱动上稳定复现。
     * IAudioClient / IAudioRenderClient 都是自由线程对象，只要所有调用线程都在同一个 MTA 里，
     * 跨线程调用才是合法的 —— 关键就是这一句。
     */
    const HRESULT comHr = CoInitializeEx(nullptr, COINIT_MULTITHREADED);

    auto* client = static_cast<IAudioClient*>(this->client);
    auto* render = static_cast<IAudioRenderClient*>(this->render);
    const HANDLE evt = static_cast<HANDLE>(this->eventHandle);
    const bool pollMode = this->polling || evt == nullptr;
    /** 等下一次"可以写"的时机：事件驱动等事件，推模式就小睡一下轮询。 */
    auto waitTick = [&]() {
        if (pollMode) {
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
        } else {
            WaitForSingleObject(evt, 20);
        }
    };

    std::vector<uint8_t> staging;
    int failureStreak = 0;
    while (!this->quit.load()) {
        UINT32 padding = 0;
        client->GetCurrentPadding(&padding);
        const uint32_t available = this->bufferFrames > padding ? this->bufferFrames - padding : 0;
        if (available == 0) {
            waitTick();
            continue;
        }

        size_t bytesWanted = static_cast<size_t>(available) * this->bytesPerFrame;
        {
            std::unique_lock<std::mutex> lock(this->ringMutex);
            /*
             * 免锁水位快照：还没播出去的总时长（环形 + 设备）。
             * 上层把它当"音频缓冲水位"显示/判定 —— 必须在这里顺手算，不能让每帧的状态更新
             * 去抢 ringMutex（那会和这条写入线程抢锁）。
             */
            {
                const int fb = this->bytesPerFrame > 0 ? this->bytesPerFrame : 4;
                const double ringFrames = static_cast<double>(this->ringUsed) / fb;
                const double totalMs =
                        (ringFrames + static_cast<double>(padding)) * 1000.0 / (this->rate > 0 ? this->rate : 48000);
                this->queuedMsX10.store(static_cast<uint32_t>(totalMs < 0 ? 0 : totalMs * 10.0));
            }
            if (this->ringUsed == 0) {
                lock.unlock();
                waitTick();
                continue;
            }
            bytesWanted = std::min(bytesWanted, this->ringUsed);
            /*
             * 只取整数帧：GetBuffer 以"帧"为单位，送半帧进去就是错位。
             * 直通时码流长度未必是 blockAlign 的整数倍（TrueHD 的 blockAlign 是 16），
             * 所以这里不能假设生产者给的一定对齐 —— 取整后剩下的会留在环形缓冲里等下一轮。
             */
            bytesWanted -= bytesWanted % static_cast<size_t>(this->bytesPerFrame);
            /*
             * ★ 直通还要再对齐到**整数个数据突发**。
             *
             * IEC 61937 是"突发"为单位的数据流，驱动按突发处理；把一次写入停在突发中间
             * （缓冲区边界/环形缓冲边界都会造成）属于非法输入 —— 实测 Windows 音频引擎
             * （audiokse.dll）会直接访问违例崩溃，进程整个没了。
             * 环形缓冲是字节流，所以这里按突发长度取整：取不满一个突发就等下一轮再写。
             */
            if (this->burstBytes > 0) {
                bytesWanted -= bytesWanted % static_cast<size_t>(this->burstBytes);
            }
            if (bytesWanted == 0) {
                lock.unlock();
                waitTick();
                continue;
            }
            staging.resize(bytesWanted);
            this->popRing(staging.data(), bytesWanted);
        }
        this->ringNotFull.notify_one();

        const uint32_t frames = static_cast<uint32_t>(bytesWanted / this->bytesPerFrame);
        BYTE* deviceBuf = nullptr;
        const HRESULT bufHr = render->GetBuffer(frames, &deviceBuf);
        if (FAILED(bufHr) || deviceBuf == nullptr) {
            /*
             * ★ 返回 S_OK 也要查指针：驱动/引擎在内部还没就绪时可能给出空指针，
             * 直接 memcpy 进去就是访问违例（"伪成功码"陷阱）。
             *
             * 另外写入线程绝不能因为一次 GetBuffer 失败就退出：
             * 线程一退环形缓冲再也没人排空 → **生产端（音频解码/直通泵）会永久阻塞在 write() 里**。
             * 于是改成"连续失败够久才判定声卡废掉"，期间继续重试；判定废掉后 write() 立刻返回，
             * 上层改走挂钟节奏继续出画面（音频坏了绝不牵连画面）。
             */
            if (bufHr == AUDCLNT_E_DEVICE_INVALIDATED) {
                // 设备被拔出/禁用/音频服务重启：这是终态，没有重试的意义
                std::printf("[host] 音频设备已失效（被拔出/禁用/服务重启），停止输出\n");
                this->failed.store(true);
                this->ringNotFull.notify_all();
                this->ringNotEmpty.notify_all();
                break;
            }
            if (++failureStreak == 1) {
                std::printf("[host] 声卡 GetBuffer 失败（设备被抢占/拔出？），继续重试…\n");
            }
            if (failureStreak > 600) {          // 约 3 秒（每轮 ~5ms）
                std::printf("[host] 声卡连续失败 %d 次，判定输出不可用（上层会改用挂钟节奏）\n",
                            failureStreak);
                this->failed.store(true);
                this->ringNotFull.notify_all();
                this->ringNotEmpty.notify_all();
                break;
            }
            waitTick();
            continue;
        }
        failureStreak = 0;

        if (this->copyVerbatim) {
            // 直通：压缩码流一个字节都不能改（改了就解不出来，更别说全景声）
            std::memcpy(deviceBuf, staging.data(), bytesWanted);
        } else {
            // S16 交错 → 设备格式（float 或 S16），顺便应用增益
            const float g = this->gain.load();
            const int samples = static_cast<int>(frames) * this->channelCount;
            const auto* src = reinterpret_cast<const int16_t*>(staging.data());
            if (this->deviceIsFloat) {
                auto* dst = reinterpret_cast<float*>(deviceBuf);
                for (int i = 0; i < samples; ++i) {
                    dst[i] = static_cast<float>(src[i]) / 32768.0f * g;
                }
            } else if (g == 1.0f) {
                std::memcpy(deviceBuf, staging.data(), bytesWanted);
            } else {
                auto* dst = reinterpret_cast<int16_t*>(deviceBuf);
                for (int i = 0; i < samples; ++i) dst[i] = clamp16(src[i] * g);
            }
        }
        render->ReleaseBuffer(frames, 0);
        this->written.fetch_add(frames);

        /*
         * ★ 欠载判定：**写这一块的时候设备里几乎已经空了** —— 那就是耳朵听到的那一下静音。
         *
         * <p>判据与进程内引擎（Java 侧 AudioSink）对齐："写入前设备缓冲几乎是空的"。
         * 注意不能改成"环形缓冲空了"：环形缓冲空但设备还满着（比如刚 seek 完立刻回填）
         * 并不代表有断音；反过来设备空了就是真的断了。这条实测踩过（第一次写成 ring==0，
         * 结果 `--debug-audio-lag` 那种"清空后又马上回填"的场景一次都测不出来）。
         *
         * <p>为什么用 25ms 而不是"完全为 0"：设备待播读数本身有抖动（见 playedFrames() 里
         * 记的那些坑），卡死在 0 会漏报。Java 那边的等价容差约 43ms，这里取 25ms 更保守
         * —— 宁可少报，也不要拿一个会误报的计数去当"声音有没有断"的证据。
         *
         * <p>三次抑制：① 还没进入正常播放（warm=false，起播阶段设备本来就空）；
         * ② 刚 flush / 刚 Start（graceUntilMs，seek 会故意丢掉设备里那 200ms）；
         * ③ 一次空档只记一次（underrunLatched，设备重新攒起来才复位）。
         */
        const double pendingMs = this->rate > 0 ? padding * 1000.0 / this->rate : 0.0;
        if (pendingMs > kUnderrunDrainMs) {
            this->underrunLatched = false;         // 设备又攒起来了 → 本段空档结束
        } else if (this->warm.load() && !this->underrunLatched
                   && steadyNowMs() >= this->graceUntilMs.load()) {
            this->underrunLatched = true;
            const uint32_t total = this->underruns.fetch_add(1) + 1;
            std::printf("[host] 声卡欠载：写入时设备缓冲只剩 %.0fms（累计 %u 次）"
                        "—— 声音会断一下\n", pendingMs, total);
        }

        if (!this->warm.load() && this->written.load() >= this->rate) {
            this->warm.store(true);   // 写够约 1 秒才算"正常播放中"，此后才统计欠载
        }

        waitTick();
    }
    if (SUCCEEDED(comHr)) CoUninitialize();
}

/**
 * 打印设备自己报的延迟。
 *
 * <p>两条路（共享 / 独占）都要打：共享模式的初始化路径不经过独占那段，早先只写在独占分支里，
 * 于是最常用的共享模式反而看不到这个数字（实测踩过）。
 *
 * <p>这个值**只覆盖声卡/驱动这一层**，不含 HDMI → 功放/回音壁的处理延迟（那才是"画面领先"的主因），
 * 所以它只用来判断量级；真正的补偿量得靠 {@code /cineduct av <ms>} 现场调。
 */
void AudioSink::logDeviceLatency(void* audioClient) {
    /*
     * 必须把 client 传进来：调用点在 Initialize 之后、但 this->client 还没赋值（那个在更后面），
     * 早先用 this->client 判断就必然被空指针保护吞掉 —— 日志里一行都不会出现（踩过）。
     */
    if (audioClient == nullptr) return;
    auto* client = static_cast<IAudioClient*>(audioClient);
    REFERENCE_TIME latency = 0;
    if (SUCCEEDED(client->GetStreamLatency(&latency)) && latency > 0) {
        this->reportedLatencyMs = static_cast<int>(latency / 10000);
    }
    REFERENCE_TIME defaultPeriod = 0;
    REFERENCE_TIME minPeriod = 0;
    if (FAILED(client->GetDevicePeriod(&defaultPeriod, &minPeriod)) || defaultPeriod <= 0) {
        defaultPeriod = 100000;   // 兜底 10ms
    }
    std::printf("[host] 设备报的流延迟: %dms（引擎周期 %.1fms；不含 HDMI/功放那段）\n",
                this->reportedLatencyMs, defaultPeriod / 10000.0);
}

uint32_t AudioSink::pendingFrames() {    if (!this->opened) return 0;
    UINT32 padding = 0;
    static_cast<IAudioClient*>(this->client)->GetCurrentPadding(&padding);
    return padding;
}

double AudioSink::queuedDurationMs() {
    if (!this->opened) return 0.0;
    const int frameBytes = this->bytesPerFrame > 0 ? this->bytesPerFrame : 4;
    size_t ringBytes = 0;
    {
        std::lock_guard<std::mutex> lock(this->ringMutex);
        ringBytes = this->ringUsed;
    }
    const double ringFrames = static_cast<double>(ringBytes) / frameBytes;
    const double total = ringFrames + static_cast<double>(this->pendingFrames());
    const double rate = this->rate > 0 ? this->rate : 48000.0;
    return total * 1000.0 / rate;
}

long long AudioSink::playedFrames() {
    if (!this->opened) return 0;
    /*
     * ★ 音频时钟必须**单调、且不许凭空前跳**。
     *
     * <p>两个坑都在这里：
     *   ① `GetCurrentPadding()` 会**瞬时返回 0**（老版本注释里记过：按它放行会让画面跑成 1.8 倍速），
     *      或者在一次暂停之后短暂返回 0 —— 于是 `写入 − 丢弃 − 待播` 会突然多出一整个设备缓冲，
     *      "已播位置"凭空前进 200ms。画面为了追这条时钟会丢帧前跳，
     *      而声卡里那段**没播**的音频恢复后照旧播出来 → 音画错开一段且永不纠正。
     *   ② 暂停期间设备是停的，待播数不可信，所以直接用暂停那一刻钉住的值。
     *
     * <p>判据：单位时间内的前进量不能超过挂钟流逝量（+100ms 容差）。
     * 超了就认为是"待播读数抖动"，本次不采信，等下一次真实推进。
     */
    if (this->paused.load() && this->hasFrozenPlayed) return this->frozenPlayed;
    const long long computed = this->written.load() - this->discarded.load()
            - static_cast<long long>(this->pendingFrames());
    const auto now = std::chrono::steady_clock::now();
    if (!this->hasPlayedSample) {
        this->hasPlayedSample = true;
        this->lastPlayedFrames = computed;
        this->lastPlayedAt = now;
        return computed;
    }
    if (computed < this->lastPlayedFrames) {
        // 单调性：绝不往回走（往回走会让画面重复画旧帧）
        return this->lastPlayedFrames;
    }
    const double wallAdvanceMs =
            std::chrono::duration<double, std::milli>(now - this->lastPlayedAt).count();
    const double advanceMs = static_cast<double>(computed - this->lastPlayedFrames) * 1000.0
            / (this->rate > 0 ? this->rate : 48000);
    if (advanceMs > wallAdvanceMs + 100.0) {
        // 前跳超过挂钟流逝 + 100ms → 认定是待播读数抖动，不采信（并留痕，便于排查）
        if (this->jitterRejects.fetch_add(1) % 50 == 0) {
            std::printf("[host] 音频时钟拒绝了 %d 次前跳（本次 %.0fms / 挂钟 %.0fms）——"
                        "设备待播读数抖动，按当前值继续\n",
                        this->jitterRejects.load(), advanceMs, wallAdvanceMs);
        }
        return this->lastPlayedFrames;
    }
    if (this->hasFrozenPlayed && computed < this->frozenPlayed) {
        return this->frozenPlayed;   // 刚恢复：设备还在播队列里那一段，别提前跳过去
    }
    this->lastPlayedFrames = computed;
    this->lastPlayedAt = now;
    return computed;
}

double AudioSink::playedMs() {
    if (!this->opened) return 0.0;
    const long long played = this->playedFrames();
    return played <= 0 ? 0.0 : played * 1000.0 / this->rate;
}

void AudioSink::setPaused(bool newPaused) {
    if (!this->opened) return;
    if (newPaused == this->paused.load()) return;
    auto* client = static_cast<IAudioClient*>(this->client);
    /*
     * ★ 暂停/恢复要把**记账数字**打进日志。
     *
     * <p>为什么值得专门打一行：暂停会让声卡 Stop，而 `GetCurrentPadding()` 在停止状态下的返回值
     * 并不保证与"设备缓冲里还没播出去的帧数"一致 —— 一旦它变成 0，
     * `playedFrames = 写入 − 丢弃 − 待播` 就会**凭空前跳一个设备缓冲**（最多 200ms）：
     * 画面为了追上这条时钟会丢帧前跳，而声卡里那段没播的音频恢复后照旧播出来 ——
     * 结果就是"**暂停之后音画错开一段，而且再也不纠正**"（不随时间漂移，因为它是一次性的）。
     * 有这几个数字，一眼就能看出是不是这个病。
     */
    const long long written = this->written.load();
    const long long discarded = this->discarded.load();
    const uint32_t pendingBefore = this->pendingFrames();
    const long long playedBefore = written - discarded - static_cast<long long>(pendingBefore);
    if (newPaused) {
        client->Stop();
    } else {
        client->Start();
        // 恢复播放：设备刚 Start，先空一下是正常的 —— 抑制窗口内不记欠载
        this->underrunLatched = false;
        this->graceUntilMs.store(steadyNowMs() + kUnderrunGraceMs);
    }
    this->paused.store(newPaused);
    const uint32_t pendingAfter = this->pendingFrames();
    const long long playedAfter = written - discarded - static_cast<long long>(pendingAfter);
    std::printf("[host] %s：写入=%lld 丢弃=%lld 待播 %u→%u 帧，已播 %lld→%lld（跳变 %lldms）\n",
                newPaused ? "暂停" : "恢复", written, discarded, pendingBefore, pendingAfter,
                playedBefore, playedAfter,
                static_cast<long long>((playedAfter - playedBefore) * 1000.0 / (this->rate > 0 ? this->rate : 48000)));
    /*
     * ★ 恢复时**只能**钉在"恢复前算出来的那个值"上，绝不能用 Start() 之后再读一次的读数。
     *
     * <p>为什么（这就是"暂停之后起播时音画错开、且不随时间变化"的第二个入口）：
     * 暂停期间设备里没有东西被播出去，所以"已播帧数"在恢复这一刻**必须保持不变**。
     * 但 `Start()` 之后立刻读 `GetCurrentPadding()`，某些驱动/引擎会**返回 0**
     * （缓冲区被重建或还没被重新认领）—— 于是 `写入 − 丢弃 − 0` 比真值多出整整一个设备缓冲
     * （最多 200ms）：时钟凭空前跳 → 画面为了追它丢帧前跳 → 而那段"没播"的音频恢复后照旧播出来
     * → **声音永久落在画面后面**，且不漂移。
     *
     * <p>原来的写法取 `max(恢复前, 恢复后)`，正好把这个假读数**当成真值保留下来**，属于火上浇油。
     * 现在固定用恢复前的读数，并且把 Start() 后的读数只当**诊断信息**打出来
     * （`待播 →` 那一段：如果它变成 0，日志会直接显示"跳变 200ms"，一眼就能确认是这个病）。
     */
    this->frozenPlayed = playedBefore;
    this->hasFrozenPlayed = true;
}

void AudioSink::flush() {
    if (!this->opened) return;
    const bool wasPaused = this->paused.load();
    auto* client = static_cast<IAudioClient*>(this->client);
    /*
     * 先记下"设备缓冲里还有多少帧没播"。
     *
     * Stop() 会把它们丢掉，而 written 是**累计写入**、不会减少 —— 不减掉就等于把被丢弃的帧
     * 算成"已经播过了"：音频时钟会凭空前跳（最多一个设备缓冲 200ms），上层按时钟对齐的视频
     * 也跟着提前，于是**每 seek 一次音画就错开一点**（实测症状）。这里记账到 discarded 里。
     */
    const long long pendingBefore = static_cast<long long>(this->pendingFrames());
    client->Stop();
    if (pendingBefore > 0) this->discarded.fetch_add(pendingBefore);
    /*
     * ★ 必须再叫一次 Reset()，否则**账记了、数据还在**。
     *
     * <p>实测：`Stop()` 并不会清空设备缓冲（暂停前后 `GetCurrentPadding` 都是 9600，一点没变）——
     * 也就是说"把待播记进 discarded"这一步只是让**时钟前跳了 200ms**，
     * 而那 200ms 的音频数据仍在缓冲里，恢复/起播之后会**原样放出来**：
     * 于是画面跟着前跳后的时钟走、声音却还压着一段旧内容 → **音画错开一段且再也不纠正**
     * （这正是用户报的"暂停之后音画不同步、不随时间变化"）。
     * 诊断证据：`playedFrames` 一度算出**负数**（-4320），因为同一批待播帧被算了两遍。
     *
     * <p>`IAudioClient::Reset()` 才是真正丢弃缓冲的调用（它顺便把流置为停止态，
     * 所以后面要按原状态决定要不要重新 Start）。
     */
    client->Reset();
    {
        std::unique_lock<std::mutex> lock(this->ringMutex);
        this->ringRead = 0;
        this->ringUsed = 0;
    }
    this->ringNotFull.notify_all();
    if (!wasPaused) client->Start();
    // 刚把设备缓冲清空又重开：这段时间设备必然是空的，不能记成欠载
    this->underrunLatched = false;
    this->graceUntilMs.store(steadyNowMs() + kUnderrunGraceMs);
}

void AudioSink::interruptWrites() {
    this->ringEpoch.fetch_add(1);
    this->ringNotFull.notify_all();
}

void AudioSink::abortWrites() {
    this->quit.store(true);
    this->ringNotFull.notify_all();
    this->ringNotEmpty.notify_all();
}

void AudioSink::close() {
    if (!this->opened) return;
    this->quit.store(true);
    this->ringNotFull.notify_all();
    this->ringNotEmpty.notify_all();
    if (this->writer.joinable()) this->writer.join();
    if (this->client != nullptr) {
        static_cast<IAudioClient*>(this->client)->Stop();
        static_cast<IAudioClient*>(this->client)->Release();
        this->client = nullptr;
    }
    if (this->render != nullptr) {
        static_cast<IAudioRenderClient*>(this->render)->Release();
        this->render = nullptr;
    }
    if (this->eventHandle != nullptr) {
        CloseHandle(static_cast<HANDLE>(this->eventHandle));
        this->eventHandle = nullptr;
    }
    this->opened = false;
}

} // namespace cineduct::host

#include "audio_device.h"

#include <windows.h>
#include <audioclient.h>
#include <mmdeviceapi.h>
#include <functiondiscoverykeys_devpkey.h>
#include <roapi.h>
#include <winstring.h>
#include <wrl/client.h>

#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

using Microsoft::WRL::ComPtr;

namespace cineduct::host {

namespace {

// ---------------------------------------------------------------------------
// IEC 61937 SubFormat GUID
//
// 值取自 ksmedia.h（见微软文档 "Representing Formats for IEC 61937 Transmissions"）。
// 这里自己写死，既省掉对 SDK 版本的依赖，也让"我们到底在试探哪些格式"一目了然。
// ---------------------------------------------------------------------------
const GUID kSubPcm            = {0x00000001, 0x0000, 0x0010, {0x80, 0x00, 0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71}};
const GUID kSubAc3            = {0x00000092, 0x0000, 0x0010, {0x80, 0x00, 0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71}};
const GUID kSubDts            = {0x00000008, 0x0000, 0x0010, {0x80, 0x00, 0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71}};
const GUID kSubDdPlus         = {0x0000000a, 0x0cea, 0x0010, {0x80, 0x00, 0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71}};
const GUID kSubDdPlusAtmos    = {0x0000010a, 0x0cea, 0x0010, {0x80, 0x00, 0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71}};
const GUID kSubDtsHd          = {0x0000000b, 0x0cea, 0x0010, {0x80, 0x00, 0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71}};
const GUID kSubMlp            = {0x0000000c, 0x0cea, 0x0010, {0x80, 0x00, 0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71}};
const GUID kSubMat20          = {0x0000010c, 0x0cea, 0x0010, {0x80, 0x00, 0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71}};
const GUID kSubMat21          = {0x0000030c, 0x0cea, 0x0010, {0x80, 0x00, 0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71}};
const GUID kSubMpeg1          = {0x00000003, 0x0cea, 0x0010, {0x80, 0x00, 0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71}};

constexpr DWORD kMaskStereo  = 0x3;      // FL|FR
constexpr DWORD kMask51      = 0x3F;     // KSAUDIO_SPEAKER_5POINT1
constexpr DWORD kMask71      = 0x63F;    // KSAUDIO_SPEAKER_7POINT1

/**
 * 一个候探格式。
 *
 * 直通格式的"链路"参数（channels/rate/bits）描述的是**传输容器**，
 * encoded* 才是解码后的真实内容 —— 这正是 WAVEFORMATEXTENSIBLE_IEC61937 存在的理由。
 */
struct Candidate {
    const char* label;
    WORD channels;
    DWORD rate;
    WORD bits;            // 链路位宽（IEC 61937 恒为 16）
    WORD blockAlign;
    DWORD mask;
    const GUID* sub;      // nullptr → PCM
    bool iec61937Struct;  // true → cbSize=34 并填 encoded 字段；false → 普通 WAVEFORMATEXTENSIBLE
    DWORD encodedRate;    // 仅 iec61937Struct 用
    WORD encodedChannels;
};

std::vector<Candidate> candidates() {
    return {
        // ---------------- 独占模式 PCM（"直出"但不直通，多声道 PCM 走 HDMI）----------------
        {"PCM 48k/16/2ch",          2, 48000,  16,  4, kMaskStereo, &kSubPcm, false, 0, 0},
        {"PCM 48k/24/2ch",          2, 48000,  24,  6, kMaskStereo, &kSubPcm, false, 0, 0},
        {"PCM 48k/32(24)/2ch",      2, 48000,  32,  8, kMaskStereo, &kSubPcm, false, 0, 0},
        {"PCM 96k/32(24)/2ch",      2, 96000,  32,  8, kMaskStereo, &kSubPcm, false, 0, 0},
        {"PCM 192k/32(24)/2ch",     2, 192000, 32,  8, kMaskStereo, &kSubPcm, false, 0, 0},
        {"PCM 48k/16/6ch",          6, 48000,  16, 12, kMask51,     &kSubPcm, false, 0, 0},
        {"PCM 48k/32(24)/8ch",      8, 48000,  32, 32, kMask71,     &kSubPcm, false, 0, 0},
        {"PCM 96k/32(24)/8ch",      8, 96000,  32, 32, kMask71,     &kSubPcm, false, 0, 0},
        {"PCM 192k/32(24)/8ch",     8, 192000, 32, 32, kMask71,     &kSubPcm, false, 0, 0},

        // ---------------- IEC 61937 直通：链路 = 4× 内容采样率 ----------------
        // Dolby Digital (AC-3)
        {"AC-3 @192k",              2, 192000, 16,  4, 0,           &kSubAc3,        false, 0, 0},
        {"AC-3 @176.4k",            2, 176400, 16,  4, 0,           &kSubAc3,        false, 0, 0},
        // Dolby Digital Plus（含 Atmos：同样码流，靠 JOC 元数据，接收端决定解出几声道）
        {"DD+ @192k",               2, 192000, 16,  4, 0,           &kSubDdPlus,     false, 0, 0},
        {"DD+ 5.1mask @192k",       2, 192000, 16,  4, kMask51,     &kSubDdPlus,     false, 0, 0},
        {"DD+ @176.4k",             2, 176400, 16,  4, 0,           &kSubDdPlus,     false, 0, 0},
        {"DD+ Atmos @192k",         2, 192000, 16,  4, 0,           &kSubDdPlusAtmos, false, 0, 0},
        {"DD+ Atmos 5.1mask @192k", 2, 192000, 16,  4, kMask51,     &kSubDdPlusAtmos, false, 0, 0},
        {"DD+ Atmos @176.4k",       2, 176400, 16,  4, 0,           &kSubDdPlusAtmos, false, 0, 0},
        // DTS / DTS-HD
        {"DTS @192k",               2, 192000, 16,  4, 0,           &kSubDts,        false, 0, 0},
        {"DTS-HD @192k",            2, 192000, 16,  4, 0,           &kSubDtsHd,      false, 0, 0},
        // Dolby TrueHD / MAT（8 声道链路，块对齐 16 字节）
        {"TrueHD/MLP @192k/8ch",    8, 192000, 16, 16, kMask71,     &kSubMlp,        false, 0, 0},
        {"TrueHD/MLP @176.4k/8ch",  8, 176400, 16, 16, kMask71,     &kSubMlp,        false, 0, 0},
        {"MAT2.0 @192k/8ch",        8, 192000, 16, 16, kMask71,     &kSubMat20,      false, 0, 0},
        {"MAT2.1 @192k/8ch",        8, 192000, 16, 16, kMask71,     &kSubMat21,      false, 0, 0},
        // 带 encoded 字段的 WAVEFORMATEXTENSIBLE_IEC61937（cbSize=34）
        {"DD+ Atmos @192k [IEC结构]", 2, 192000, 16, 4, kMask51,     &kSubDdPlusAtmos, true, 48000, 6},
        {"TrueHD @192k/8ch [IEC结构]", 8, 192000, 16, 16, kMask71,   &kSubMlp,        true, 96000, 8},
        // MPEG-1 Layer II（部分电视/家庭影院只认它）
        {"MP2 @192k",               2, 192000, 16,  4, 0,           &kSubMpeg1,      false, 0, 0},
    };
}

/**
 * 按候选格式填一块 WAVEFORMATEXTENSIBLE 缓冲区。
 *
 * cbSize=22 → 普通 WAVEFORMATEXTENSIBLE（总 40 字节）
 * cbSize=34 → WAVEFORMATEXTENSIBLE_IEC61937（总 52 字节，尾部多三个 encoded 字段）
 * 缓冲区给 64 字节，两种都放得下。
 */
void buildFormat(const Candidate& c, BYTE* raw) {
    std::memset(raw, 0, 64);
    auto* wf = reinterpret_cast<WAVEFORMATEXTENSIBLE*>(raw);
    wf->Format.wFormatTag = WAVE_FORMAT_EXTENSIBLE;
    wf->Format.nChannels = c.channels;
    wf->Format.nSamplesPerSec = c.rate;
    wf->Format.wBitsPerSample = c.bits;
    wf->Format.nBlockAlign = c.blockAlign;
    wf->Format.nAvgBytesPerSec = c.rate * c.blockAlign;
    wf->Format.cbSize = c.iec61937Struct ? 34 : 22;
    wf->Samples.wValidBitsPerSample = c.bits >= 32 ? 24 : c.bits;
    wf->dwChannelMask = c.mask;
    wf->SubFormat = *c.sub;
    if (c.iec61937Struct) {
        auto* p = reinterpret_cast<DWORD*>(raw + 40);
        p[0] = c.encodedRate;             // dwEncodedSamplesPerSec
        p[1] = c.encodedChannels;         // dwEncodedChannelCount
        p[2] = 0;                         // dwAverageBytesPerSec（该格式下被忽略）
    }
}

const char* stateName(DWORD state) {
    switch (state) {
        case DEVICE_STATE_ACTIVE: return "在线";
        case DEVICE_STATE_DISABLED: return "已禁用";
        case DEVICE_STATE_NOTPRESENT: return "未接入";
        case DEVICE_STATE_UNPLUGGED: return "未插好";
        default: return "未知";
    }
}

std::string formatText(const WAVEFORMATEX* wf) {
    char buf[160];
    std::snprintf(buf, sizeof(buf), "%luHz %uch %u-bit", wf->nSamplesPerSec, wf->nChannels,
                  wf->wBitsPerSample);
    return buf;
}

std::string deviceIdOf(IMMDevice* dev) {
    LPWSTR id = nullptr;
    if (FAILED(dev->GetId(&id)) || id == nullptr) return "?";
    char buf[512];
    const int n = WideCharToMultiByte(CP_UTF8, 0, id, -1, buf, sizeof(buf), nullptr, nullptr);
    CoTaskMemFree(id);
    return n > 0 ? std::string(buf) : "?";
}

std::string friendlyNameOf(IMMDevice* dev) {
    ComPtr<IPropertyStore> props;
    if (FAILED(dev->OpenPropertyStore(STGM_READ, &props))) return "(无名)";
    PROPVARIANT v;
    PropVariantInit(&v);
    std::string name = "(无名)";
    if (SUCCEEDED(props->GetValue(PKEY_Device_FriendlyName, &v)) && v.vt == VT_LPWSTR) {
        char buf[512];
        if (WideCharToMultiByte(CP_UTF8, 0, v.pwszVal, -1, buf, sizeof(buf), nullptr, nullptr) > 0) {
            name = buf;
        }
    }
    PropVariantClear(&v);
    return name;
}

/** 探测一台设备的独占 PCM 与直通能力。 */
void probeDevice(IMMDevice* dev, std::string& out) {
    ComPtr<IAudioClient> client;
    HRESULT hr = dev->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr, &client);
    if (FAILED(hr)) {
        out += "    无法激活 IAudioClient\n";
        return;
    }

    char line[512];
    WAVEFORMATEX* mix = nullptr;
    if (SUCCEEDED(client->GetMixFormat(&mix)) && mix != nullptr) {
        std::snprintf(line, sizeof(line), "    混音格式（共享模式）: %s", formatText(mix).c_str());
        out += line;
        out += "\n";
        CoTaskMemFree(mix);
    }

    out += "    独占 PCM:";
    std::string passthrough;
    for (const Candidate& c : candidates()) {
        BYTE raw[64];
        buildFormat(c, raw);
        WAVEFORMATEX* closest = nullptr;
        const HRESULT r = client->IsFormatSupported(AUDCLNT_SHAREMODE_EXCLUSIVE,
                                                    reinterpret_cast<WAVEFORMATEX*>(raw), &closest);
        char item[256];
        std::snprintf(item, sizeof(item), " %s%s", r == S_OK ? "[OK]" : "[--]", c.label);
        if (r == S_OK) {
            if (c.sub == &kSubPcm) {
                out += item;
            } else {
                passthrough += item;
            }
        }
        if (closest != nullptr) CoTaskMemFree(closest);
    }
    out += "\n";
    out += "    IEC61937 直通:";
    out += passthrough.empty() ? " 该设备不接受任何压缩码流直通（或未开启独占控制）" : passthrough;
    out += "\n";

    // 空间音效（Dolby Atmos 等）：用户选了什么、系统现在实际在跑什么
    const SpatialAudioInfo spatial = querySpatialAudio(dev);
    out += "    空间音效（Dolby Atmos 等）: ";
    if (spatial.queried) {
        out += spatial.supported ? "设备支持" : "**设备不支持**";
        out += " | 系统当前在跑: ";
        out += spatial.activeFormatName.empty() ? "无（未激活）" : spatial.activeFormatName;
        out += " | 用户选择: ";
        out += spatial.defaultFormatName.empty() ? "无" : spatial.defaultFormatName;
        if (spatial.defaultFormatName.find("Atmos") != std::string::npos
            && !spatial.activeFormat.empty()) {
            out += "  ★ 杜比全景声正在生效";
        } else if (spatial.defaultFormatName.find("Atmos") != std::string::npos) {
            out += "  ⚠ 选了 Atmos，但系统当前没有激活它";
        }
        out += "\n";
    } else {
        out += "查不到（" + spatial.error + "）\n";
    }
}

} // namespace

/**
 * PKEY_AudioEnvironment_SpatialAudioActive
 * = {4AFB7B88-A653-44A5-99DB-687FD74AF0BB}, 2（见 Windows SDK 的 audioengineextensionapo.h）。
 */
const PROPERTYKEY kPkeySpatialAudioActive = {
        {0x4AFB7B88, 0xA653, 0x44A5, {0x99, 0xDB, 0x68, 0x7F, 0xD7, 0x4A, 0xF0, 0xBB}}, 2};

bool querySpatialAudioActive(void* devicePtr, bool& known) {
    known = false;
    auto* device = static_cast<IMMDevice*>(devicePtr);
    if (device == nullptr) return false;
    ComPtr<IPropertyStore> props;
    if (FAILED(device->OpenPropertyStore(STGM_READ, &props))) return false;
    PROPVARIANT value;
    PropVariantInit(&value);
    const HRESULT hr = props->GetValue(kPkeySpatialAudioActive, &value);
    if (FAILED(hr)) {
        PropVariantClear(&value);
        return false;                       // 端点没上报这个属性 → 无法判断
    }
    bool active = false;
    switch (value.vt) {
        case VT_BOOL: active = value.boolVal != VARIANT_FALSE; known = true; break;
        case VT_UI4: case VT_UINT: active = value.ulVal != 0; known = true; break;
        case VT_I4: case VT_INT: active = value.lVal != 0; known = true; break;
        case VT_UI2: active = value.uiVal != 0; known = true; break;
        case VT_UI1: active = value.bVal != 0; known = true; break;
        default: known = false; break;       // VT_EMPTY 等：属性在但没值
    }
    PropVariantClear(&value);
    return known && active;
}

// ---------------------------------------------------------------------------
// 空间音效状态：走 WinRT 的 Windows.Media.Audio.SpatialAudioDeviceConfiguration
//
// 这是唯一能**直接读出"当前激活的空间音效格式名"**的官方接口
// （例如 "Dolby Atmos for home theater"）。用户靠耳朵分不清"真 Atmos"和"普通多声道混音"，
// 但这里能给出确定答案，所以我们把它读出来告诉他。
//
// 这里手写最小 ABI 而**不引入 C++/WinRT**：整个子进程只有这一处需要用 WinRT，
// 为此拉进一整套 C++/WinRT 头文件不值得。IID 与虚表顺序取自 SDK 的
// winrt/windows.media.audio.h（MIDL_INTERFACE 与接口定义），改动风险很低。
// ---------------------------------------------------------------------------
namespace {

const GUID kIidSpatialConfig =
        {0xee830034, 0x61cf, 0x5749, {0x9d, 0xa4, 0x10, 0xf0, 0xfe, 0x02, 0x81, 0x99}};
const GUID kIidSpatialConfigStatics =
        {0x3ec37f7b, 0x936d, 0x4e04, {0x97, 0x28, 0x28, 0x27, 0xd9, 0xf7, 0x58, 0xc4}};

/** ABI::Windows::Media::Audio::ISpatialAudioDeviceConfiguration（顺序即虚表顺序）。 */
struct ISpatialAudioDeviceConfiguration : public IInspectable {
    virtual HRESULT STDMETHODCALLTYPE get_DeviceId(HSTRING* value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_IsSpatialAudioSupported(boolean* value) = 0;
    virtual HRESULT STDMETHODCALLTYPE IsSpatialAudioFormatSupported(HSTRING format, boolean* value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_ActiveSpatialAudioFormat(HSTRING* value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_DefaultSpatialAudioFormat(HSTRING* value) = 0;
    virtual HRESULT STDMETHODCALLTYPE SetDefaultSpatialAudioFormatAsync(HSTRING format, void** operation) = 0;
    virtual HRESULT STDMETHODCALLTYPE add_ConfigurationChanged(void* handler, void** token) = 0;
    virtual HRESULT STDMETHODCALLTYPE remove_ConfigurationChanged(void* token) = 0;
};

/** ABI::Windows::Media::Audio::ISpatialAudioDeviceConfigurationStatics。 */
struct ISpatialAudioDeviceConfigurationStatics : public IInspectable {
    virtual HRESULT STDMETHODCALLTYPE GetForDeviceId(HSTRING deviceId,
                                                     ISpatialAudioDeviceConfiguration** result) = 0;
};

/**
 * ABI::Windows::Media::Audio::ISpatialAudioFormatSubtypeStatics。
 *
 * <p>它提供各种空间音效格式的 **GUID 常量**（Windows Sonic / Dolby Atmos 家庭影院 / 耳机 …）。
 * 有了它就不用把 GUID 硬编码进来：读出来的激活格式跟这些常量一比，就能说出人话的名字。
 */
struct ISpatialAudioFormatSubtypeStatics : public IInspectable {
    virtual HRESULT STDMETHODCALLTYPE get_WindowsSonic(HSTRING* value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_DolbyAtmosForHeadphones(HSTRING* value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_DolbyAtmosForHomeTheater(HSTRING* value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_DolbyAtmosForSpeakers(HSTRING* value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_DTSHeadphoneX(HSTRING* value) = 0;
};

/** 宽字符 → UTF-8（日志/状态汇报用）。 */
std::string wideToUtf8(const wchar_t* text) {
    if (text == nullptr) return {};
    char buffer[512];
    const int written = WideCharToMultiByte(CP_UTF8, 0, text, -1, buffer, sizeof(buffer),
                                            nullptr, nullptr);
    return written > 0 ? std::string(buffer) : std::string();
}

/** HSTRING → UTF-8（取不到或为空则返回空串）。 */
std::string hstringToUtf8(HSTRING value) {
    if (value == nullptr) return {};
    UINT32 length = 0;
    const wchar_t* buffer = WindowsGetStringRawBuffer(value, &length);
    return (buffer != nullptr && length > 0) ? wideToUtf8(buffer) : std::string();
}

/**
 * 把"空间音效格式 GUID"翻成人话。
 *
 * <p>名字不硬编码：GUID 常量由 {@code SpatialAudioFormatSubtype} 的静态属性给出，
 * 这里读出来做映射；认不出来的就原样返回 GUID（至少不撒谎）。
 */
std::string friendlySpatialFormat(const std::string& guid) {
    if (guid.empty()) return {};
    const GUID kSubtypeStatics =
            {0xb3de8a47, 0x83ee, 0x4266, {0xa9, 0x45, 0xbe, 0xdf, 0x50, 0x7a, 0xfe, 0xed}};
    const wchar_t* kClassName = L"Windows.Media.Audio.SpatialAudioFormatSubtype";
    HSTRING className = nullptr;
    if (FAILED(WindowsCreateString(kClassName, static_cast<UINT32>(wcslen(kClassName)), &className))) {
        return guid;
    }
    void* factoryRaw = nullptr;
    const HRESULT hr = RoGetActivationFactory(className, kSubtypeStatics, &factoryRaw);
    WindowsDeleteString(className);
    if (FAILED(hr) || factoryRaw == nullptr) return guid;
    ComPtr<ISpatialAudioFormatSubtypeStatics> factory;
    factory.Attach(static_cast<ISpatialAudioFormatSubtypeStatics*>(factoryRaw));

    // 逐个比对系统给的常量；对上就返回人话名字
    using Getter = HRESULT (STDMETHODCALLTYPE ISpatialAudioFormatSubtypeStatics::*)(HSTRING*);
    const std::pair<Getter, const char*> kTable[] = {
            {&ISpatialAudioFormatSubtypeStatics::get_DolbyAtmosForHomeTheater,
             "Dolby Atmos for home theater（杜比全景声·家庭影院）"},
            {&ISpatialAudioFormatSubtypeStatics::get_DolbyAtmosForHeadphones,
             "Dolby Atmos for headphones（杜比全景声·耳机）"},
            {&ISpatialAudioFormatSubtypeStatics::get_DolbyAtmosForSpeakers,
             "Dolby Atmos for speakers（杜比全景声·扬声器）"},
            {&ISpatialAudioFormatSubtypeStatics::get_WindowsSonic, "Windows Sonic"},
            {&ISpatialAudioFormatSubtypeStatics::get_DTSHeadphoneX, "DTS Headphone:X"},
    };
    for (const auto& entry : kTable) {
        HSTRING value = nullptr;
        if (FAILED((factory.Get()->*entry.first)(&value)) || value == nullptr) continue;
        const std::string text = hstringToUtf8(value);
        WindowsDeleteString(value);
        if (!text.empty() && text == guid) return entry.second;
    }
    return guid;   // 认不出来就原样给 GUID，至少不撒谎
}

} // namespace

SpatialAudioInfo querySpatialAudio(void* devicePtr) {
    SpatialAudioInfo info;
    auto* device = static_cast<IMMDevice*>(devicePtr);
    if (device == nullptr) return info;

    LPWSTR deviceIdRaw = nullptr;
    if (FAILED(device->GetId(&deviceIdRaw)) || deviceIdRaw == nullptr) {
        info.error = "取端点 ID 失败";
        return info;
    }

    // WinRT 需要线程已在公寓里；已经有 MTA 时 RoInitialize 返回 S_FALSE，也算成功
    const HRESULT roHr = RoInitialize(RO_INIT_MULTITHREADED);
    if (FAILED(roHr) && roHr != RPC_E_CHANGED_MODE) {
        info.error = "RoInitialize 失败（这个进程里 WinRT 不可用）";
        CoTaskMemFree(deviceIdRaw);
        return info;
    }

    const wchar_t* kClassName = L"Windows.Media.Audio.SpatialAudioDeviceConfiguration";
    HSTRING className = nullptr;
    if (FAILED(WindowsCreateString(kClassName, static_cast<UINT32>(wcslen(kClassName)), &className))) {
        info.error = "创建类名失败";
        CoTaskMemFree(deviceIdRaw);
        return info;
    }
    void* factoryRaw = nullptr;
    const HRESULT factoryHr = RoGetActivationFactory(className, kIidSpatialConfigStatics, &factoryRaw);
    WindowsDeleteString(className);
    if (FAILED(factoryHr) || factoryRaw == nullptr) {
        char note[96];
        std::snprintf(note, sizeof(note), "这套系统上没有空间音效配置接口（hr=0x%08lx）",
                      static_cast<unsigned long>(factoryHr));
        info.error = note;
        CoTaskMemFree(deviceIdRaw);
        return info;
    }
    ComPtr<ISpatialAudioDeviceConfigurationStatics> factory;
    factory.Attach(static_cast<ISpatialAudioDeviceConfigurationStatics*>(factoryRaw));

    HSTRING deviceId = nullptr;
    if (FAILED(WindowsCreateString(deviceIdRaw, static_cast<UINT32>(wcslen(deviceIdRaw)), &deviceId))
        || deviceId == nullptr) {
        info.error = "创建设备 ID 字符串失败";
        CoTaskMemFree(deviceIdRaw);
        return info;
    }
    CoTaskMemFree(deviceIdRaw);

    ComPtr<ISpatialAudioDeviceConfiguration> config;
    const HRESULT getHr = factory->GetForDeviceId(deviceId, config.GetAddressOf());
    WindowsDeleteString(deviceId);
    if (FAILED(getHr) || config.Get() == nullptr) {
        char note[96];
        std::snprintf(note, sizeof(note), "取该端点的空间音效配置失败（hr=0x%08lx）",
                      static_cast<unsigned long>(getHr));
        info.error = note;
        return info;
    }

    info.queried = true;
    boolean supported = FALSE;
    if (SUCCEEDED(config->get_IsSpatialAudioSupported(&supported))) {
        info.supported = supported != FALSE;
    }
    HSTRING active = nullptr;
    if (SUCCEEDED(config->get_ActiveSpatialAudioFormat(&active)) && active != nullptr) {
        UINT32 length = 0;
        const wchar_t* buffer = WindowsGetStringRawBuffer(active, &length);
        if (buffer != nullptr && length > 0) info.activeFormat = wideToUtf8(buffer);
        WindowsDeleteString(active);
    }
    HSTRING defaultFormat = nullptr;
    if (SUCCEEDED(config->get_DefaultSpatialAudioFormat(&defaultFormat)) && defaultFormat != nullptr) {
        UINT32 length = 0;
        const wchar_t* buffer = WindowsGetStringRawBuffer(defaultFormat, &length);
        if (buffer != nullptr && length > 0) info.defaultFormat = wideToUtf8(buffer);
        WindowsDeleteString(defaultFormat);
    }
    // 全零 GUID 是"没有空间音效"的表示（未激活时 API 就返回它）
    const std::string kNone = "{00000000-0000-0000-0000-000000000000}";
    if (info.activeFormat == kNone) info.activeFormat.clear();
    if (info.defaultFormat == kNone) info.defaultFormat.clear();
    // 翻成人话（认不出来就保留 GUID）
    if (!info.activeFormat.empty()) {
        info.activeFormatName = friendlySpatialFormat(info.activeFormat);
    }
    if (!info.defaultFormat.empty()) {
        info.defaultFormatName = friendlySpatialFormat(info.defaultFormat);
    }
    return info;
}

std::string listAudioDevices() {
    std::string out;
    const HRESULT coHr = CoInitializeEx(nullptr, COINIT_MULTITHREADED);

    ComPtr<IMMDeviceEnumerator> enumerator;
    HRESULT hr = CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL,
                                  __uuidof(IMMDeviceEnumerator), &enumerator);
    if (FAILED(hr)) {
        out += "创建 MMDeviceEnumerator 失败\n";
        return out;
    }

    ComPtr<IMMDevice> defaultDev;
    if (SUCCEEDED(enumerator->GetDefaultAudioEndpoint(eRender, eConsole, &defaultDev))) {
        out += "默认播放设备（eConsole）: " + friendlyNameOf(defaultDev.Get()) + "\n";
    } else {
        out += "没有默认播放设备\n";
    }

    ComPtr<IMMDeviceCollection> devices;
    hr = enumerator->EnumAudioEndpoints(eRender, DEVICE_STATE_ACTIVE | DEVICE_STATE_DISABLED
                                        | DEVICE_STATE_NOTPRESENT | DEVICE_STATE_UNPLUGGED, &devices);
    if (FAILED(hr)) {
        out += "枚举播放设备失败\n";
        return out;
    }

    UINT count = 0;
    devices->GetCount(&count);
    out += "共 " + std::to_string(count) + " 个播放端点\n";

    std::string defaultId = defaultDev ? deviceIdOf(defaultDev.Get()) : std::string();
    for (UINT i = 0; i < count; ++i) {
        ComPtr<IMMDevice> dev;
        if (FAILED(devices->Item(i, &dev))) continue;
        DWORD state = 0;
        dev->GetState(&state);

        char head[512];
        const std::string id = deviceIdOf(dev.Get());
        std::snprintf(head, sizeof(head), "\n[%u]%s %s — %s\n", i,
                      id == defaultId ? " ★默认" : "", friendlyNameOf(dev.Get()).c_str(),
                      stateName(state));
        out += head;
        out += "    设备ID: " + id + "\n";
        if (state == DEVICE_STATE_ACTIVE) {
            probeDevice(dev.Get(), out);
        } else {
            out += "    （设备不在线，跳过能力探测）\n";
        }
    }
    (void) coHr;
    return out;
}

} // namespace cineduct::host

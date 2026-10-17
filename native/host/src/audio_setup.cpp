#include "audio_setup.h"

#include <cstdio>
#include <vector>

#include "audio_source.h"
#include "audio_spdif.h"

namespace cineduct::host {

namespace {

/** IEC 61937 SubFormat GUID（取值见微软文档 "Representing Formats for IEC 61937 Transmissions"）。 */
const FormatGuid kSubPcm = {0x00000001, 0x0000, 0x0010, {0x80, 0x00, 0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71}};
const FormatGuid kSubDolbyDigital = {0x00000092, 0x0000, 0x0010, {0x80, 0x00, 0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71}};
const FormatGuid kSubDolbyDigitalPlus = {0x0000000a, 0x0000, 0x0010, {0x80, 0x00, 0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71}};
const FormatGuid kSubDolbyMlp = {0x0000000c, 0x0000, 0x0010, {0x80, 0x00, 0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71}};
const FormatGuid kSubDts = {0x00000008, 0x0000, 0x0010, {0x80, 0x00, 0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71}};

std::string ffError(const FfmpegApi& api, int code) {
    char buf[256];
    if (api.av_strerror != nullptr && api.av_strerror(code, buf, sizeof(buf)) == 0) {
        return std::string(buf) + " (" + std::to_string(code) + ")";
    }
    return "code " + std::to_string(code);
}

struct AudioProbe {
    bool ok = false;
    AVCodecID codecId = AV_CODEC_ID_NONE;
    std::string codecName = "?";
    int rate = 48000;
    int channels = 2;
    std::string error;
};

/**
 * 轻量探测音频链：只为了拿到"编码 / 采样率 / 声道数"，问完就关。
 *
 * 为什么要多开一次：DASH 的音轨是独立一条链，只有打开它才知道是不是 E-AC-3。
 * 而"要不要直通"必须在**开声卡之前**定下来（声卡格式一旦按 PCM 打开就晚了）。
 */
AudioProbe probeAudio(const FfmpegApi& api, const std::string& url, const std::string& headers,
                      const std::string& userAgent) {
    AudioProbe probe;
    AVFormatContext* fmt = nullptr;
    AVDictionary* opts = nullptr;
    if (!headers.empty()) api.av_dict_set(&opts, "headers", headers.c_str(), 0);
    /*
     * ★ UA 必须给：这里是**独立开的一路输入**。漏了 UA 时，点播看不出问题，
     * 直播则直接 403 → "音频不可用，改为纯视频" → **有画面没声音**（实测踩过）。
     */
    if (!userAgent.empty()) api.av_dict_set(&opts, "user_agent", userAgent.c_str(), 0);
    api.av_dict_set(&opts, "rw_timeout", "15000000", 0);
    int r = api.avformat_open_input(&fmt, url.c_str(), nullptr, &opts);
    if (opts != nullptr) api.av_dict_free(&opts);
    if (r < 0) {
        probe.error = "打不开音频链";
        return probe;
    }
    r = api.avformat_find_stream_info(fmt, nullptr);
    const int idx = r < 0 ? -1 : api.av_find_best_stream(fmt, AVMEDIA_TYPE_AUDIO, -1, -1, nullptr, 0);
    if (idx < 0) {
        probe.error = r < 0 ? "读取音频流信息失败" : "音频链里没有音频流";
        api.avformat_close_input(&fmt);
        return probe;
    }
    AVStream* stream = fmt->streams[idx];
    probe.ok = true;
    probe.codecId = stream->codecpar->codec_id;
    probe.codecName = api.avcodec_get_name(probe.codecId);
    probe.rate = stream->codecpar->sample_rate > 0 ? stream->codecpar->sample_rate : 48000;
    probe.channels = stream->codecpar->ch_layout.nb_channels > 0
            ? stream->codecpar->ch_layout.nb_channels : 2;
    api.avformat_close_input(&fmt);
    return probe;
}

/** 声道数 → KSAUDIO_SPEAKER 掩码（直通与独占 PCM 都要用它描述链路）。 */
unsigned long speakerMaskFor(int channels) {
    switch (channels) {
        case 1: return 0x4;        // FC
        case 2: return 0x3;        // FL|FR
        case 4: return 0x33;       // 3.0+sub 的常见四声道
        case 6: return 0x3F;       // 5.1
        case 8: return 0x63F;      // 7.1
        default: return 0;
    }
}

/**
 * 按音轨编码推出"直通链路格式"。
 *
 * 链路速率不是随便定的 —— 它必须与 FFmpeg spdif 复用器的突发周期吻合，否则声音会快/慢好几倍：
 *   * E-AC-3(DD+)：突发 24576 字节、内容 32ms → 链路 4× 内容采样率（48k → 192kHz，768000 B/s）；
 *   * AC-3       ：突发 6144 字节、内容 32ms → 链路就是内容采样率（192000 B/s）；
 *   * TrueHD/MAT ：MAT 帧 61440 字节 → 链路 192kHz/8ch/16bit（3072000 B/s）。
 */
bool buildPassthroughFormat(AVCodecID codecId, const std::string& codecName, int contentRate,
                            int contentChannels, AudioFormat& format, std::string& why) {
    format = AudioFormat{};
    format.passthrough = true;
    format.containerBits = 16;
    format.validBits = 16;
    format.encodedRate = static_cast<unsigned long>(contentRate);
    format.encodedChannels = static_cast<unsigned short>(contentChannels > 8 ? 8 : contentChannels);
    switch (codecId) {
        case AV_CODEC_ID_EAC3:
            // Atmos（JOC）就在同一条 E-AC-3 码流里，不需要专门的 Atmos subformat ——
            // 接收端看到 JOC 元数据就切换到全景声解码。
            format.channels = 2;
            format.rate = static_cast<unsigned long>(contentRate) * 4;
            format.blockAlign = 4;
            format.channelMask = 0;
            format.burstMs = 32;             // 6 个 block = 1536 样本 @48k
            format.subFormat = kSubDolbyDigitalPlus;
            why = "Dolby Digital Plus（含 Atmos）码流直通";
            return true;
        case AV_CODEC_ID_AC3:
            format.channels = 2;
            format.rate = static_cast<unsigned long>(contentRate);
            format.blockAlign = 4;
            format.channelMask = 0;
            format.burstMs = 32;             // 1536 样本 @48k
            format.subFormat = kSubDolbyDigital;
            why = "Dolby Digital (AC-3) 码流直通";
            return true;
        case AV_CODEC_ID_TRUEHD:
        case AV_CODEC_ID_MLP:
            format.channels = 8;
            format.rate = (contentRate % 44100 == 0) ? 176400u : 192000u;
            format.blockAlign = 16;
            format.channelMask = 0x63F;
            format.burstMs = 20;             // MAT 帧 61440 字节 @ 3072000 B/s
            format.subFormat = kSubDolbyMlp;
            format.iec61937Struct = true;    // 文档推荐用带 encoded 字段的结构描述 TrueHD
            why = "Dolby TrueHD / Atmos（MAT）码流直通";
            return true;
        case AV_CODEC_ID_DTS:
            format.channels = 2;
            format.rate = static_cast<unsigned long>(contentRate);
            format.blockAlign = 4;
            format.channelMask = 0;
            format.burstMs = 32;
            format.subFormat = kSubDts;
            why = "DTS 码流直通";
            return true;
        default:
            why = "音轨是 " + codecName + "（不是 Dolby/DTS 压缩码流），没有可直通的东西";
            return false;
    }
}

AudioOpenOptions sharedOptions(const std::string& device) {
    AudioOpenOptions o;
    o.mode = AudioOutputMode::Shared;
    o.deviceSelector = device;
    o.applyGain = true;
    return o;
}

AudioOpenOptions exclusiveOptions(const std::string& device, int channels, int rate) {
    AudioOpenOptions o;
    o.mode = AudioOutputMode::Exclusive;
    o.deviceSelector = device;
    o.applyGain = true;
    o.format.passthrough = false;
    o.format.channels = static_cast<unsigned short>(channels);
    o.format.rate = static_cast<unsigned long>(rate);
    o.format.containerBits = 16;
    o.format.validBits = 16;
    o.format.blockAlign = static_cast<unsigned short>(channels * 2);
    o.format.channelMask = speakerMaskFor(channels);
    o.format.subFormat = kSubPcm;
    return o;
}

AudioOpenOptions passthroughOptions(const std::string& device, const AudioFormat& format) {
    AudioOpenOptions o;
    o.mode = AudioOutputMode::Passthrough;
    o.deviceSelector = device;
    o.applyGain = false;      // 直通：码流上不能加增益
    o.format = format;
    return o;
}

/** 依次尝试一组候选 → 声卡打开成功即返回；全部失败则给出最后一次的真实原因。 */
bool openSinkFromLadder(AudioSink& sink, const std::vector<AudioOpenOptions>& ladder,
                        std::string& error) {
    for (const AudioOpenOptions& option : ladder) {
        std::string attemptError;
        if (sink.open(option, attemptError)) return true;
        error = attemptError;
        std::printf("[host] 音频输出候选不可用（%s）: %s\n",
                    option.mode == AudioOutputMode::Exclusive ? "独占PCM"
                    : option.mode == AudioOutputMode::Passthrough ? "直通" : "共享",
                    attemptError.c_str());
    }
    return false;
}

} // namespace

AudioModePreference parseAudioMode(const std::string& text) {
    if (text == "shared") return AudioModePreference::Shared;
    if (text == "exclusive") return AudioModePreference::Exclusive;
    if (text == "passthrough") return AudioModePreference::Passthrough;
    return AudioModePreference::Auto;
}

std::unique_ptr<AudioChain> setupAudio(const FfmpegApi& api, const AudioSetupRequest& request,
                                       AudioSink& sink, std::string& label, std::string& error) {
    const AudioProbe probe = probeAudio(api, request.audioUrl, request.headers, request.userAgent);
    if (!probe.ok) {
        error = probe.error;
        return nullptr;
    }
    std::printf("[host] 音轨: %s %dch %dHz\n", probe.codecName.c_str(), probe.channels, probe.rate);

    // ---- 1) 直通优先：这是"硬件解码全景声"的唯一路径 ----
    if (request.mode == AudioModePreference::Auto || request.mode == AudioModePreference::Passthrough) {
        AudioFormat passFormat{};
        std::string passWhy;
        if (buildPassthroughFormat(probe.codecId, probe.codecName, probe.rate, probe.channels,
                                   passFormat, passWhy)) {
            std::string sinkError;
            if (sink.open(passthroughOptions(request.deviceSelector, passFormat), sinkError)) {
                auto bitstream = std::make_unique<AudioBitstream>();
                std::string chainError;
                if (bitstream->open(api, sink, request.audioUrl, request.headers, request.userAgent,
                                    chainError)) {
                    label = "IEC61937 直通：" + passWhy;
                    return bitstream;
                }
                std::printf("[host] 码流封装不可用：%s → 退回解码输出\n", chainError.c_str());
                sink.close();
            } else {
                std::printf("[host] 端点不接受直通格式（%s）→ 退回解码输出\n", sinkError.c_str());
            }
        } else {
            std::printf("[host] 不直通：%s\n", passWhy.c_str());
        }
    } else {
        std::printf("[host] 音频输出形态被显式指定，不做直通\n");
    }

    // ---- 2) 解码输出：独占 PCM（绕过系统混音器）→ 共享（永远可用） ----
    /*
     * ★ auto 模式下**不主动用独占 PCM**。
     *
     * 独占会独占整个端点：期间该设备上其它声音（包括 Minecraft 自己的音效）全部被静音。
     * 对普通音轨（AAC 等，直通不了）而言，独占只换来"位完美"，代价却是游戏没声音 ——
     * 这笔交易在游戏里不划算，所以只有**显式**要求 `exclusive` 时才走这条路。
     * 直通是另一回事：能直通才是值得独占的理由（全景声只能这么拿）。
     */
    std::vector<AudioOpenOptions> ladder;
    const bool wantExclusive = request.mode == AudioModePreference::Exclusive;
    if (wantExclusive) {
        const int maxCh = probe.channels > 8 ? 8 : (probe.channels < 1 ? 2 : probe.channels);
        const int rates[2] = {probe.rate, 48000};
        const int channelLadder[3] = {maxCh, 6, 2};
        for (int rate : rates) {
            for (int ch : channelLadder) {
                if (ch < 1 || ch > 8) continue;
                if (rate <= 0) continue;
                bool duplicate = false;
                for (const AudioOpenOptions& existing : ladder) {
                    if (existing.format.channels == static_cast<unsigned short>(ch)
                        && existing.format.rate == static_cast<unsigned long>(rate)) {
                        duplicate = true;
                        break;
                    }
                }
                if (!duplicate) ladder.push_back(exclusiveOptions(request.deviceSelector, ch, rate));
            }
        }
    }
    ladder.push_back(sharedOptions(request.deviceSelector));

    if (!openSinkFromLadder(sink, ladder, error)) {
        error = "所有音频输出格式都不可用: " + error;
        return nullptr;
    }
    label = sink.isExclusive() ? ("独占 PCM（位完美，绕过系统混音器，" + std::to_string(sink.channels())
                                  + "ch " + std::to_string(sink.sampleRate()) + "Hz）")
                              : "共享模式（走系统混音器）";

    auto source = std::make_unique<AudioSource>();
    std::string chainError;
    if (!source->open(api, sink, request.audioUrl, request.headers, request.userAgent, chainError)) {
        error = chainError;
        sink.close();
        return nullptr;
    }
    return source;
}

} // namespace cineduct::host

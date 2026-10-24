// ============================================================================
// cineduct-host.exe —— 媒体子进程
//
// 这个文件只做四件事（播放本身全在 video_pipeline.cpp 里）：
//   1. 解析命令行（**参数与语义与老版本逐字一致**，Java 侧 HostSession 照旧传）
//   2. 加载 FFmpeg（动态符号表）、建 D3D11 设备、装崩溃转储与父进程看护
//   3. 跑三条自检形态：--list-adapters / --list-audio / --audio-only / --spdif-dump
//   4. 起 VideoPipeline 并等它收工，把退出码返回给上层
//
// 为什么播放循环被搬走了：老版本是一条 1700 行的主循环，读包、解码、转换、发布、
// 等音频时钟全挤在一起（还用 2ms 轮询等时钟），任何一个环节慢都会拖住整条链。
// 现在是 demux / 解码 / 呈现三条线程 + 有界队列 + 主时钟（对照 CicadaPlayer 的分层），
// 细节见 video_pipeline.h 顶部的说明。
// ============================================================================

#include "audio_device.h"
#include "audio_setup.h"
#include "audio_sink.h"
#include "audio_spdif.h"
#include "ffmpeg_dyn.h"
#include "gpu.h"
#include "host_log.h"
#include "video_pipeline.h"

#include <windows.h>
#include <dbghelp.h>

#include <atomic>
#include <cctype>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <random>
#include <string>
#include <thread>
#include <vector>

using namespace cineduct::host;

namespace {

// ============================================================================
// 子进程身份（v9，见 docs/host-uuid.md）
//
// 两个 UUID 文本：sessionUuid = 逻辑会话级（Java 传，重启不变）；
// instanceUuid = 进程实例级（每次 spawn 一个）。Java 不传时自己生成，保证独立可跑。
//
// 为什么不用 CoCreateGuid / UuidCreate：本工程没链 ole32 / rpcrt4（见 native/CMakeLists.txt），
// 而身份只要"格式合法 + 随机"就够（Java 侧按字符串收下，不要求真 GUID 语义），
// 所以用 std::random_device 填 16 字节、按 v4 置版本/变体位。
// ============================================================================

/** UUID 文本（小写带连字符，36 字符）。 */
using UuidText = std::string;

UuidText newUuid() {
    static std::random_device device;
    std::mt19937_64 rng(static_cast<uint64_t>(device()) ^ static_cast<uint64_t>(
            std::chrono::steady_clock::now().time_since_epoch().count()));
    unsigned char b[16];
    for (unsigned char& byte : b) byte = static_cast<unsigned char>(rng() & 0xFF);
    b[6] = static_cast<unsigned char>((b[6] & 0x0F) | 0x40);   // version 4
    b[8] = static_cast<unsigned char>((b[8] & 0x3F) | 0x80);   // variant RFC4122
    char out[37];
    std::snprintf(out, sizeof(out),
                  "%02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x",
                  b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7], b[8], b[9], b[10], b[11],
                  b[12], b[13], b[14], b[15]);
    return std::string(out);
}

/** 是否是"看起来像 UUID"的文本（36 字符、连字符位置正确、其余为十六进制）。 */
bool looksLikeUuid(const std::string& text) {
    if (text.size() != 36) return false;
    for (size_t i = 0; i < text.size(); ++i) {
        const char c = text[i];
        if (i == 8 || i == 13 || i == 18 || i == 23) {
            if (c != '-') return false;
        } else if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
            return false;
        }
    }
    return true;
}

/**
 * 短标签：去掉连字符后的前 8 位（日志文件名 / dump 名用）。
 *
 * <p>★ 输入不像 UUID 就返回空串 —— 不能把"格式不对的原始参数"里那几个合法字符
 * 拿去当文件名标签（实测就这么把 `-xxx-` 混进过日志名）。
 */
std::string shortUuidTag(const std::string& text) {
    if (!looksLikeUuid(text)) return {};
    std::string tag;
    for (char c : text) {
        if (c == '-') continue;
        tag.push_back(static_cast<char>(std::tolower(static_cast<unsigned char>(c))));
        if (tag.size() == 8) break;
    }
    return tag;
}

/*
 * 身份存在全局里（而不是只放 Args）：
 *   * 崩溃处理器（crashHandler）要用它给 minidump 命名，而它在任何地方都可能被触发；
 *   * host_log 的文件名在 parseArgs **之前**就要定（hostLogStart 早于 runHost）。
 * 所以 main() 里先预扫描一次，parseArgs 只负责"接受并校验"这两个参数。
 */
UuidText g_sessionUuid;
UuidText g_instanceUuid;

/** 从命令行预扫描 `--session-uuid` / `--instance-uuid`（缺省为空，不生成）。 */
void preScanUuids(int argc, char** argv) {
    for (int i = 1; i < argc; ++i) {
        const std::string k = argv[i];
        if ((k == "--session-uuid" || k == "--instance-uuid") && i + 1 < argc) {
            const std::string v = argv[++i];
            if (k == "--session-uuid") g_sessionUuid = v;
            else g_instanceUuid = v;
        }
    }
}

/*
 * ★ 默认 User-Agent：必须和 Java 侧 FfmpegVideoSource.UA 一模一样。
 *
 * 为什么这是个"必须有"的参数：FFmpeg 自己的 UA 是 `Lavf/8.0.1`，而 B 站的**直播** CDN
 * （live-bvc）不认这个 UA，直接回 403 Forbidden。实测（2026-09-26 23:35 的 runClient 会话）：
 * 同一条直播直链、同一份 Referer/Cookie —— Java 进程内引擎（设了 Chrome UA）打开成功，
 * 子进程（默认 Lavf UA）报 `打开输入失败: Server returned 403 Forbidden`，
 * 于是"子进程启动失败 → 重启 → 再失败 → 回退进程内引擎"，玩家看到的就是屏幕反复黑/闪。
 *
 * 点播 CDN（bilivideo/mcdn）不挑 UA，所以这个坑只在直播上暴露出来。
 */
constexpr const char* kDefaultUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/120.0.0.0 Safari/537.36";

struct Args {
    std::wstring ffmpegDir;
    uint64_t adapterLuid = 0;
    std::string url;
    std::string headers;
    std::string userAgent = kDefaultUserAgent;
    int frames = 0;            // <=0 = 不限
    double seconds = 0;        // >0 = 跑这么久就收工
    bool listAdapters = false;
    bool listAudio = false;
    bool verbose = false;

    // ---- 画面输出（共享纹理）----
    std::string outputBase;
    std::string statusName = "cineduct_host_status";
    uint32_t slots = 3;
    uint32_t outWidth = 0;
    uint32_t outHeight = 0;
    double fitAspect = 0.0;
    int avOffsetMs = 0;
    std::string dumpFrame;
    /** HDR→SDR 色调映射路径：driver（默认）/ cpu / off。 */
    std::string hdrToneMap = "driver";
    /** 诊断：起播后故意把音频拖后这么多毫秒。 */
    int debugAudioLagMs = 0;
    /** 显式打开音画偏移自动纠正（默认只测量、不纠正，见 PipelineConfig::avCorrect）。 */
    bool avCorrect = false;
    /** 只打音画偏移明细，不注入不同步（排查用）。 */
    bool avDebug = false;
    bool loop = false;
    bool pace = true;
    double holdSeconds = 0;

    // ---- 缓冲与"等缓存"闸门（docs/PLAN-preload-buffer.md）----
    /** 目标缓冲水位（毫秒）：包队列按这个 PTS 跨度限深 = 提前缓存几秒。 */
    int bufferTargetMs = 4000;
    /** 起播预缓冲（毫秒）：攒够这么多再放第一帧。 */
    int prerollMs = 1500;
    /** 客户端卡顿多久后冻时钟（毫秒，0 = 关）。 */
    int stallGateMs = 1500;
    /** 供给见底多久后冻时钟（毫秒，0 = 关）。 */
    int supplyGateMs = 800;
    /** 诊断：注入一次假卡顿。 */
    int stallInjectMs = 0;

    // ---- 音频 ----
    bool audio = true;
    bool audioOnly = false;
    std::string audioUrl;
    int volume = 100;
    std::string audioMode = "auto";
    std::string audioDevice;
    std::string spdifDump;

    // ---- 控制通道 ----
    std::string pipeName;
    uint32_t parentPid = 0;
};

void startParentWatchdog(uint32_t parentPid) {
    std::thread([parentPid] {
        HANDLE parent = OpenProcess(SYNCHRONIZE, FALSE, parentPid);
        if (parent == nullptr) {
            g_parentGone.store(true);
            return;
        }
        WaitForSingleObject(parent, INFINITE);
        CloseHandle(parent);
        std::printf("[host] 父进程（游戏）已退出，子进程自行收尾退出\n");
        g_parentGone.store(true);
    }).detach();
}

std::wstring utf8ToWide(const std::string& s) {
    if (s.empty()) return {};
    const int n = MultiByteToWideChar(CP_UTF8, 0, s.c_str(), -1, nullptr, 0);
    if (n <= 1) return {};
    std::wstring w(static_cast<size_t>(n), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, s.c_str(), -1, w.data(), n);
    w.pop_back();
    return w;
}

bool parseArgs(int argc, char** argv, Args& a) {
    for (int i = 1; i < argc; ++i) {
        const std::string k = argv[i];
        auto next = [&](std::string& out) { if (i + 1 < argc) { out = argv[++i]; return true; } return false; };
        std::string v;
        if (k == "--list-adapters") { a.listAdapters = true; }
        else if (k == "--list-audio") { a.listAudio = true; }
        else if (k == "--verbose") { a.verbose = true; }
        else if (k == "--ffmpeg-dir") { if (!next(v)) return false; a.ffmpegDir = utf8ToWide(v); }
        else if (k == "--url") { if (!next(v)) return false; a.url = v; }
        else if (k == "--headers") { if (!next(v)) return false; a.headers = v; }
        else if (k == "--user-agent") { if (!next(v)) return false; a.userAgent = v; }
        else if (k == "--frames") { if (!next(v)) return false; a.frames = std::atoi(v.c_str()); }
        else if (k == "--seconds") { if (!next(v)) return false; a.seconds = std::atof(v.c_str()); }
        else if (k == "--output-textures") { if (!next(v)) return false; a.outputBase = v; }
        else if (k == "--status") { if (!next(v)) return false; a.statusName = v; }
        else if (k == "--slots") { if (!next(v)) return false; a.slots = static_cast<uint32_t>(std::atoi(v.c_str())); }
        else if (k == "--out-width") { if (!next(v)) return false; a.outWidth = static_cast<uint32_t>(std::atoi(v.c_str())); }
        else if (k == "--out-height") { if (!next(v)) return false; a.outHeight = static_cast<uint32_t>(std::atoi(v.c_str())); }
        else if (k == "--fit-aspect") { if (!next(v)) return false; a.fitAspect = std::atof(v.c_str()); }
        else if (k == "--av-offset-ms") { if (!next(v)) return false; a.avOffsetMs = std::atoi(v.c_str()); }
        else if (k == "--dump-frame") { if (!next(v)) return false; a.dumpFrame = v; }
        else if (k == "--hdr-tonemap") { if (!next(v)) return false; a.hdrToneMap = v; }
        else if (k == "--debug-audio-lag") { if (!next(v)) return false; a.debugAudioLagMs = std::atoi(v.c_str()); }
        else if (k == "--av-correct") { a.avCorrect = true; }
        else if (k == "--av-debug") { a.avDebug = true; }
        else if (k == "--no-hdr-tonemap") { a.hdrToneMap = "off"; }
        else if (k == "--loop") { a.loop = true; }
        else if (k == "--no-pace") { a.pace = false; }
        else if (k == "--hold") { if (!next(v)) return false; a.holdSeconds = std::atof(v.c_str()); }
        else if (k == "--buffer-target-ms") { if (!next(v)) return false; a.bufferTargetMs = std::atoi(v.c_str()); }
        else if (k == "--preroll-ms") { if (!next(v)) return false; a.prerollMs = std::atoi(v.c_str()); }
        else if (k == "--stall-gate-ms") { if (!next(v)) return false; a.stallGateMs = std::atoi(v.c_str()); }
        else if (k == "--no-buffer-gate") { a.stallGateMs = 0; a.supplyGateMs = 0; }
        else if (k == "--stall-inject") { if (!next(v)) return false; a.stallInjectMs = std::atoi(v.c_str()); }
        else if (k == "--no-audio") { a.audio = false; }
        else if (k == "--audio-only") { a.audioOnly = true; a.audio = true; }
        else if (k == "--audio-url") { if (!next(v)) return false; a.audioUrl = v; }
        else if (k == "--volume") { if (!next(v)) return false; a.volume = std::atoi(v.c_str()); }
        else if (k == "--audio-mode") { if (!next(v)) return false; a.audioMode = v; }
        else if (k == "--audio-device") { if (!next(v)) return false; a.audioDevice = v; }
        else if (k == "--spdif-dump") { if (!next(v)) return false; a.spdifDump = v; }
        else if (k == "--pipe") { if (!next(v)) return false; a.pipeName = v; }
        /*
         * 身份参数：值已经在 main() 的 preScanUuids 里取过（host_log 的文件名要用它），
         * 这里只负责"消费掉"，否则会被下面那句"未知参数"判死、直接退出 2。
         */
        else if (k == "--session-uuid" || k == "--instance-uuid") { if (!next(v)) return false; }
        else if (k == "--parent-pid") { if (!next(v)) return false; a.parentPid = static_cast<uint32_t>(std::atoi(v.c_str())); }
        /*
         * 调试日志相关（见 host_log.h）：真正生效的是 hostLogConfigure 的**预扫描** ——
         * 它必须早于本函数（参数写错时也要能落盘）。这里只是"接受"这些参数，
         * 否则会被下面那句"未知参数"判死、直接退出 2。
         */
        else if (k == "--console" || k == "--debug-console" || k == "--debug"
                 || k == "--no-console" || k == "--no-log-file") { /* 由 hostLogConfigure 处理 */ }
        else if (k == "--log-dir") { if (!next(v)) return false; }
        else if (k == "--log-file") { if (!next(v)) return false; }
        else if (k == "--log-keep") { if (!next(v)) return false; }
        else if (k == "--adapter-luid") {
            if (!next(v)) return false;
            unsigned hi = 0, lo = 0;
            if (std::sscanf(v.c_str(), "%x:%x", &hi, &lo) != 2) return false;
            a.adapterLuid = (static_cast<uint64_t>(hi) << 32) | lo;
        } else {
            std::fprintf(stderr, "[host] 未知参数: %s\n", k.c_str());
            return false;
        }
    }
    return a.listAdapters || a.listAudio || !a.url.empty();
}

/**
 * 崩溃时把现场写成 minidump（放在 exe 同目录，文件名带 pid）。
 *
 * <p>为什么值得写这段：目前遇到的最难查的问题都是"崩在系统音频模块里"
 * （例如 `audiokse.dll` 访问违例 0xC0000005）。Windows 事件日志只给模块名与偏移，
 * 拿不到调用栈；有了 dump 才能用 WinDbg 执行 `!analyze -v` 看清到底是谁在什么时候踩了内存。
 */
LONG WINAPI crashHandler(EXCEPTION_POINTERS* info) {
    /*
     * 先把日志处理掉：崩溃现场最值钱的就是"最后几行说了什么"，而 stdout/stderr
     * 本来是无缓冲的，所以此刻应该已经全在文件里了 —— 这里补的是"最后那一口气"
     * （未换行的半行 + 把最后几行投影到窗口/Java 管道，免得窗口在崩溃时就停在几百毫秒前）。
     * hostLogDrain 内部是 try_lock：镜像线程正拿着锁时直接放弃，绝不在崩溃处理里死等。
     */
    std::fflush(nullptr);
    hostLogDrain();

    char modulePath[MAX_PATH] = {0};
    GetModuleFileNameA(nullptr, modulePath, MAX_PATH);
    std::string dumpPath = modulePath;
    const size_t dot = dumpPath.rfind('.');
    if (dot != std::string::npos) dumpPath = dumpPath.substr(0, dot);
    dumpPath += "-crash-";
    /*
     * 短 UUID 进 dump 名（见 docs/host-uuid.md §5）：pid 会复用，UUID 不会，
     * 于是"这个 dump 属于哪一次进程实例"一眼可读。身份还没定下来时退回纯 pid 命名。
     */
    const std::string tag = shortUuidTag(g_instanceUuid);
    if (!tag.empty()) dumpPath += tag + "-";
    dumpPath += std::to_string(GetCurrentProcessId()) + ".dmp";

    bool written = false;
    HANDLE file = CreateFileA(dumpPath.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS,
                              FILE_ATTRIBUTE_NORMAL, nullptr);
    if (file != INVALID_HANDLE_VALUE) {
        MINIDUMP_EXCEPTION_INFORMATION mei{};
        mei.ThreadId = GetCurrentThreadId();
        mei.ExceptionPointers = info;
        mei.ClientPointers = FALSE;
        written = MiniDumpWriteDump(GetCurrentProcess(), GetCurrentProcessId(), file,
                                    static_cast<MINIDUMP_TYPE>(MiniDumpNormal | MiniDumpWithThreadInfo),
                                    &mei, nullptr, nullptr) != FALSE;
        CloseHandle(file);
    }
    const DWORD code = (info != nullptr && info->ExceptionRecord != nullptr)
            ? info->ExceptionRecord->ExceptionCode : 0;
    const void* address = (info != nullptr && info->ExceptionRecord != nullptr)
            ? info->ExceptionRecord->ExceptionAddress : nullptr;
    std::fprintf(stderr, "[host] 崩溃: code=0x%08lx 地址=%p；minidump: %s（%s）\n", code, address,
                 dumpPath.c_str(), written ? "已写入，可用 WinDbg !analyze -v 看栈" : "写入失败");
    std::fflush(stderr);
    hostLogDrain();
    return EXCEPTION_EXECUTE_HANDLER;
}

// ===========================================================================
// --spdif-dump 的自校验
//
// 直通这条路最难验证的地方是"封装出来的字节流对不对"，而它偏偏只能在真正的功放上听出来。
// 这里用**不需要任何硬件**的办法把它证明掉：
//   ① 把封装结果写进文件；
//   ② 用 FFmpeg 的 spdif 解复用器把文件解回压缩帧（它按 IEC 61937 规则反解字节序与前导）；
//   ③ 解码这些帧，与"直接解码原始音轨"比样本数。
// 两者一致 = 突发封装、字节序、零填充周期全部正确（ATMOS 元数据是码流的一部分，自然一起对）。
// ===========================================================================
struct DecodeStats {
    bool ok = false;
    long long samples = 0;
    long long packets = 0;
    int rate = 0;
    int channels = 0;
    std::string codec = "?";
    std::string error;
};

DecodeStats decodeCount(const FfmpegApi& api, const std::string& path, const std::string& headers,
                        const char* forcedFormat, const std::string& userAgent = std::string()) {
    DecodeStats stats;
    AVFormatContext* fmt = nullptr;
    AVDictionary* opts = nullptr;
    if (!headers.empty()) api.av_dict_set(&opts, "headers", headers.c_str(), 0);
    if (!userAgent.empty()) api.av_dict_set(&opts, "user_agent", userAgent.c_str(), 0);
    const AVInputFormat* inputFormat = forcedFormat != nullptr
            ? api.av_find_input_format(forcedFormat) : nullptr;
    if (forcedFormat != nullptr && inputFormat == nullptr) {
        stats.error = std::string("这套 FFmpeg 没有 ") + forcedFormat + " 解复用器";
        if (opts != nullptr) api.av_dict_free(&opts);
        return stats;
    }
    int r = api.avformat_open_input(&fmt, path.c_str(), inputFormat, &opts);
    if (opts != nullptr) api.av_dict_free(&opts);
    if (r < 0) {
        stats.error = "打开失败";
        return stats;
    }
    r = api.avformat_find_stream_info(fmt, nullptr);
    const AVCodec* decoder = nullptr;
    const int idx = r < 0 ? -1 : api.av_find_best_stream(fmt, AVMEDIA_TYPE_AUDIO, -1, -1, &decoder, 0);
    if (idx < 0 || decoder == nullptr) {
        stats.error = "没有可解码的音频流";
        api.avformat_close_input(&fmt);
        return stats;
    }
    AVStream* stream = fmt->streams[idx];
    stats.rate = stream->codecpar->sample_rate;
    stats.channels = stream->codecpar->ch_layout.nb_channels;
    stats.codec = api.avcodec_get_name(stream->codecpar->codec_id);
    AVCodecContext* ctx = api.avcodec_alloc_context3(decoder);
    api.avcodec_parameters_to_context(ctx, stream->codecpar);
    if (api.avcodec_open2(ctx, decoder, nullptr) < 0) {
        stats.error = "解码器打不开";
        api.avcodec_free_context(&ctx);
        api.avformat_close_input(&fmt);
        return stats;
    }
    AVPacket* pkt = api.av_packet_alloc();
    AVFrame* frame = api.av_frame_alloc();
    while (api.av_read_frame(fmt, pkt) >= 0) {
        if (pkt->stream_index == idx) {
            stats.packets++;
            if (api.avcodec_send_packet(ctx, pkt) >= 0) {
                while (api.avcodec_receive_frame(ctx, frame) >= 0) {
                    stats.samples += frame->nb_samples;
                    api.av_frame_unref(frame);
                }
            }
        }
        api.av_packet_unref(pkt);
    }
    api.avcodec_send_packet(ctx, nullptr);
    while (api.avcodec_receive_frame(ctx, frame) >= 0) {
        stats.samples += frame->nb_samples;
        api.av_frame_unref(frame);
    }
    stats.ok = stats.samples > 0;
    if (!stats.ok) stats.error = "一帧都没解出来";
    api.av_frame_free(&frame);
    api.av_packet_free(&pkt);
    api.avcodec_free_context(&ctx);
    api.avformat_close_input(&fmt);
    return stats;
}

/** 执行 --spdif-dump：封装落盘 + 自校验。返回进程退出码。 */
int runSpdifDump(const FfmpegApi& api, const Args& args) {
    const std::string audioUrl = args.audioUrl.empty() ? args.url : args.audioUrl;
    std::FILE* out = std::fopen(args.spdifDump.c_str(), "wb");
    if (out == nullptr) {
        std::fprintf(stderr, "[host] 打不开落盘文件: %s\n", args.spdifDump.c_str());
        return 9;
    }
    AudioBitstream bitstream;
    std::string error;
    const bool opened = bitstream.openWithWriter(api, [out](const uint8_t* data, int bytes) {
        return static_cast<int>(std::fwrite(data, 1, static_cast<size_t>(bytes), out));
    }, audioUrl, args.headers, args.userAgent, error);
    if (!opened) {
        std::fprintf(stderr, "[host] 码流封装失败: %s\n", error.c_str());
        std::fclose(out);
        return 9;
    }
    bitstream.start();
    const auto start = std::chrono::steady_clock::now();
    while (!bitstream.finished() && !bitstream.failed()) {
        std::this_thread::sleep_for(std::chrono::milliseconds(50));
        if (args.seconds > 0
            && std::chrono::duration<double>(std::chrono::steady_clock::now() - start).count()
               > args.seconds) {
            break;
        }
    }
    bitstream.close();
    std::fclose(out);
    const long long bytes = bitstream.bytesWritten();
    const auto fileSize = [&]() -> long long {
        std::FILE* f = std::fopen(args.spdifDump.c_str(), "rb");
        if (f == nullptr) return 0;
        std::fseek(f, 0, SEEK_END);
        const long size = std::ftell(f);
        std::fclose(f);
        return size;
    }();
    std::printf("[host] 已写出 IEC 61937 码流 %lld 字节（文件 %lld 字节），源编码 %s\n",
                bytes, fileSize, bitstream.codecName().c_str());

    // ---- 自校验：解开 → 解码 → 与直接解码原始音轨对比 ----
    const DecodeStats viaSpdif = decodeCount(api, args.spdifDump, std::string(), "spdif");
    const DecodeStats direct = decodeCount(api, audioUrl, args.headers, nullptr, args.userAgent);
    if (!viaSpdif.ok || !direct.ok) {
        std::printf("[host] 自校验无法进行: 经 spdif=%s / 直接=%s\n",
                    viaSpdif.ok ? "ok" : viaSpdif.error.c_str(),
                    direct.ok ? "ok" : direct.error.c_str());
        return 1;
    }
    const double viaMs = viaSpdif.rate > 0 ? viaSpdif.samples * 1000.0 / viaSpdif.rate : 0.0;
    const double directMs = direct.rate > 0 ? direct.samples * 1000.0 / direct.rate : 0.0;
    const double diffMs = viaMs - directMs;
    std::printf("[host] 经 spdif 解回: %s %dch %dHz，%lld 帧 → %.0fms\n", viaSpdif.codec.c_str(),
                viaSpdif.channels, viaSpdif.rate, viaSpdif.samples, viaMs);
    std::printf("[host] 直接解原始音轨: %s %dch %dHz，%lld 帧 → %.0fms\n", direct.codec.c_str(),
                direct.channels, direct.rate, direct.samples, directMs);
    const bool pass = std::abs(diffMs) <= 100.0;
    std::printf("[host] 结论: %s（差值 %.0fms，容差 100ms）\n",
                pass ? "✔ 码流封装通过 —— 突发/字节序/填充都正确" : "✘ 码流封装有问题", diffMs);
    return pass ? 0 : 1;
}

/** M3 音频自检：不出画面，只验证 WASAPI 输出与音频时钟（`--audio-only`）。 */
int runAudioOnly(const FfmpegApi& api, const Args& args) {
    const std::string audioUrl = args.audioUrl.empty() ? args.url : args.audioUrl;
    AudioSink sink;
    std::string label;
    std::string error;
    AudioSetupRequest request;
    request.audioUrl = audioUrl;
    request.headers = args.headers;
    request.userAgent = args.userAgent;
    request.mode = parseAudioMode(args.audioMode);
    request.deviceSelector = args.audioDevice;
    std::unique_ptr<AudioChain> chain = setupAudio(api, request, sink, label, error);
    if (chain == nullptr) {
        std::fprintf(stderr, "[host] 音频打开失败: %s\n", error.c_str());
        return 8;
    }
    std::printf("[host] 音频输出: %s（%s 链路）\n", label.c_str(), chain->kind());
    if (sink.volumeLocked()) {
        std::printf("[host] 直通模式：码流不可修改，音量/静音命令无效（由接收端控制）\n");
    } else {
        sink.setGain(args.volume / 100.0f);
    }
    chain->start();

    const double runSeconds = args.seconds > 0 ? args.seconds : 10.0;
    const auto t0 = std::chrono::steady_clock::now();
    std::printf("[host] 播放音频 %.0f 秒…\n", runSeconds);
    double writtenMs = 0.0;
    double wallMs = 0.0;
    while (true) {
        wallMs = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count() * 1000.0;
        if (wallMs / 1000.0 >= runSeconds || g_parentGone.load()) break;
        std::this_thread::sleep_for(std::chrono::milliseconds(500));
        writtenMs = sink.framesWritten() * 1000.0 / sink.sampleRate();
        std::printf("[host] 已播 %.0fms（设备时钟 %.0fms；链路已写 %.0fms；设备待播 %u 帧；结束=%d）\n",
                    writtenMs, sink.playedMs(), writtenMs, sink.pendingFrames(),
                    chain->finished() ? 1 : 0);
        if (sink.framesWritten() == 0 && wallMs > 1500.0) {
            std::printf("[host] ⚠ 独占/直通流运行了 1.5 秒仍没有任何推进（待播恒为满缓冲）：\n"
                        "[host]    常见原因：① 端点/驱动实际不支持该独占格式（HDMI 接收端不认）；\n"
                        "[host]    ② 系统声音设置里关掉了「允许应用独占控制此设备」；\n"
                        "[host]    ③ 该端点被其它独占程序占用。可先用 --audio-mode shared 验证播放正常。\n");
        }
    }
    writtenMs = sink.framesWritten() * 1000.0 / sink.sampleRate();
    wallMs = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count() * 1000.0;
    const bool realtime = wallMs > 1000.0
            && std::abs(writtenMs - wallMs) <= wallMs * 0.05 + 300.0;
    const bool audioOk = sink.framesWritten() > 0 && realtime;
    std::printf("[host] 码流/音频共写出 %lld 帧（%.0fms），挂钟 %.0fms，形态=%s\n",
                static_cast<long long>(sink.framesWritten()), writtenMs, wallMs, sink.modeName());
    if (sink.isPassthrough()) {
        std::printf("[host] 直通链路: %dHz %dch %d 字节/帧 → %.0f B/s（压缩码流按此速率实时送出）\n",
                    sink.sampleRate(), sink.channels(), sink.frameBytes(),
                    sink.sampleRate() * static_cast<double>(sink.frameBytes()));
    }
    std::printf("[host] 结论: %s\n", audioOk
            ? "✔ M3/P4 通过 —— 音频按实时节奏输出，且速率与挂钟一致"
            : "✘ 写出速率与挂钟不符（声卡没吃下数据，或封装的突发周期不对）");
    sink.abortWrites();
    chain->close();
    sink.close();
    return audioOk ? 0 : 1;
}

} // namespace

/**
 * 真正的入口（原来的 main 函数体）。
 *
 * <p>为什么拆一层：调试日志的收尾（{@link hostLogStop}）必须在**任何** return 之前跑 ——
 * 里面有"把最后一段投影给窗口与 Java 管道"的动作，漏了它，cmd 窗口会在最后一行之前就关掉
 * （手动跑 `--list-adapters` 这类一次性模式时最明显）。
 *
 * <p>注意 {@code setvbuf(stdout, …, _IONBF)} **已经挪到 main 里了**：setvbuf 只允许在
 * 流上还没有任何 I/O 时调用，而 hostLogStart 会往 stdout 打日志头并做重定向，
 * 所以缓冲策略必须在那之前定好（无缓冲 = 每行立刻落盘，崩溃也保得住最后一行）。
 */
int runHost(int argc, char** argv) {
    SetConsoleOutputCP(CP_UTF8);
    // 崩溃留证：minidump 比"事件日志里的模块名+偏移"有用得多（见 crashHandler 注释）
    SetUnhandledExceptionFilter(crashHandler);

    Args args;
    if (!parseArgs(argc, argv, args)) {
        std::fprintf(stderr,
                     "用法: cineduct-host.exe --ffmpeg-dir <DLL目录> [--adapter-luid hi:lo] "
                     "--url <地址> [--headers \"Referer: x\\r\\nUser-Agent: y\\r\\n\"] [--user-agent <UA>] "
                     "[--frames N] [--fit-aspect A] [--av-offset-ms N]\n"
                     "      [--buffer-target-ms N] [--preroll-ms N] [--stall-gate-ms N] [--no-buffer-gate]\n"
                     "      [--session-uuid <uuid>] [--instance-uuid <uuid>]\n"
                     "      cineduct-host.exe --list-adapters | --list-audio\n"
                     "调试日志: [--console] [--log-dir <目录>] [--log-file <文件>] [--log-keep N]\n"
                     "          也可以设 CINEDUCT_HOST_DEBUG=1，或在当前目录/exe 同目录放一个\n"
                     "          cineduct-host-debug.flag 空文件（这三个都不需要改 Java 侧）\n");
        return 2;
    }
    /*
     * v9：身份校验 + 缺省生成（见 docs/host-uuid.md §2）。
     *
     * 规矩：**格式不对不致命** —— 打一条 warning 然后自己生成一个，
     * 绝不能让一个新参数把本来能播的进程弄得起不来。
     */
    if (!g_sessionUuid.empty() && !looksLikeUuid(g_sessionUuid)) {
        std::printf("[host] --session-uuid 格式不对（%s），改为自动生成\n", g_sessionUuid.c_str());
        g_sessionUuid.clear();
    }
    if (!g_instanceUuid.empty() && !looksLikeUuid(g_instanceUuid)) {
        std::printf("[host] --instance-uuid 格式不对（%s），改为自动生成\n", g_instanceUuid.c_str());
        g_instanceUuid.clear();
    }
    if (g_sessionUuid.empty()) g_sessionUuid = newUuid();
    if (g_instanceUuid.empty()) g_instanceUuid = newUuid();
    std::printf("[host] 身份: session=%s instance=%s pid=%lu\n", g_sessionUuid.c_str(),
                g_instanceUuid.c_str(), static_cast<unsigned long>(GetCurrentProcessId()));
    if (args.parentPid != 0) {
        startParentWatchdog(args.parentPid);
    }
    if (args.avOffsetMs != 0) {
        std::printf("[host] 音画偏移: %s %dms（运行期可用控制通道的 av-offset 动词改）\n",
                    args.avOffsetMs > 0 ? "画面提前" : "画面延后",
                    args.avOffsetMs < 0 ? -args.avOffsetMs : args.avOffsetMs);
    }

    // ---------- 0) 只枚举，不播放 ----------
    if (args.listAdapters) {
        std::fputs(listAdapters().c_str(), stdout);
        if (args.url.empty()) return 0;
    }
    if (args.listAudio) {
        std::fputs(listAudioDevices().c_str(), stdout);
        if (args.url.empty()) return 0;
    }

    // ---------- 1) FFmpeg ----------
    FfmpegApi api;
    std::string error;
    if (!api.load(args.ffmpegDir, error)) {
        std::fprintf(stderr, "[host] FFmpeg 加载失败: %s\n", error.c_str());
        return 3;
    }
    std::printf("[host] FFmpeg %s 已加载（%zu 个 DLL）\n", api.av_version_info(), api.loadedModules());
    for (const std::string& m : api.moduleNames()) std::printf("[host]   %s\n", m.c_str());
    api.av_log_set_level(args.verbose ? AV_LOG_INFO : AV_LOG_WARNING);
    api.avformat_network_init();

    // ---------- 2) 自检形态（不需要画面/不需要声卡）----------
    if (!args.spdifDump.empty()) {
        return runSpdifDump(api, args);
    }
    if (args.audioOnly) {
        return runAudioOnly(api, args);
    }

    // ---------- 3) D3D11 设备 ----------
    GpuDevice gpu;
    if (!gpu.create(args.adapterLuid, error)) {
        std::fprintf(stderr, "[host] 建 D3D11 设备失败: %s\n", error.c_str());
        return 4;
    }
    std::printf("[host] D3D11 设备: %s (luid=%08x:%08x)\n", gpu.description.c_str(),
                static_cast<uint32_t>(gpu.luid >> 32), static_cast<uint32_t>(gpu.luid));

    // ---------- 4) 播放 ----------
    PipelineConfig config;
    config.url = args.url;
    config.headers = args.headers;
    config.userAgent = args.userAgent;
    config.outputBase = args.outputBase;
    config.statusName = args.statusName;
    config.slots = args.slots;
    config.outWidth = args.outWidth;
    config.outHeight = args.outHeight;
    config.fitAspect = args.fitAspect;
    config.dumpFrame = args.dumpFrame;
    config.debugAudioLagMs = args.debugAudioLagMs;
    config.avCorrect = args.avCorrect;
    config.avDebug = args.avDebug;
    if (args.hdrToneMap == "cpu") {
        config.hdrToneMap = HdrToneMapMode::Cpu;
    } else if (args.hdrToneMap == "off" || args.hdrToneMap == "none") {
        config.hdrToneMap = HdrToneMapMode::Off;
    } else {
        if (args.hdrToneMap != "driver") {
            std::printf("[host] 未知的 --hdr-tonemap=%s，按 driver 处理\n", args.hdrToneMap.c_str());
        }
        config.hdrToneMap = HdrToneMapMode::Driver;
    }
    config.avOffsetMs = args.avOffsetMs;
    config.volume = args.volume;
    config.loop = args.loop;
    config.pace = args.pace;
    config.frames = args.frames;
    config.seconds = args.seconds;
    config.holdSeconds = args.holdSeconds;
    config.bufferTargetMs = args.bufferTargetMs;
    config.prerollMs = args.prerollMs;
    config.stallGateMs = args.stallGateMs;
    config.supplyGateMs = args.supplyGateMs;
    config.stallInjectMs = args.stallInjectMs;
    config.audio = args.audio;
    config.audioUrl = args.audioUrl;
    config.audioMode = parseAudioMode(args.audioMode);
    config.audioDevice = args.audioDevice;
    config.pipeName = args.pipeName;
    // v9：身份随管道与状态环一起交出去（见 docs/host-uuid.md）
    config.sessionUuid = g_sessionUuid;
    config.instanceUuid = g_instanceUuid;

    VideoPipeline pipeline(api, gpu, std::move(config));
    if (!pipeline.start(error)) {
        std::fprintf(stderr, "[host] 启动失败: %s\n", error.c_str());
        return 5;
    }
    const int code = pipeline.run();
    gpu.destroy();
    return code;
}

int main(int argc, char** argv) {
    /*
     * ★ 顺序在这里很关键，三条都不能动：
     *   ① setvbuf 必须在**任何** stdout 输出之前 —— 它只允许在流上还没有 I/O 时调用，
     *      而 hostLogStart 会往 stdout 打日志头；
     *   ② hostLogConfigure 必须早于 parseArgs —— 参数写错也要能落盘；
     *   ③ hostLogStart 必须早于**所有** printf —— 之后每行都会同时进文件、cmd 窗口
     *      与 Java 那条管道（见 host_log.h 里的数据流图）。
     * 不开调试模式时这三个调用什么都不做，正常播放的路径与以前完全一致。
     */
    setvbuf(stdout, nullptr, _IONBF, 0);
    /*
     * 身份要在 hostLogStart **之前**定下来（日志文件名里要带短 UUID），
     * 也要在 runHost 之前生成好（崩溃时 minidump 名要用它）。
     * 三个调用都不阻塞、不碰文件系统。
     */
    preScanUuids(argc, argv);
    hostLogConfigure(argc, argv);
    hostLogSetInstanceTag(shortUuidTag(g_instanceUuid));
    hostLogStart();

    const int code = runHost(argc, argv);
    hostLogStop();
    return code;
}

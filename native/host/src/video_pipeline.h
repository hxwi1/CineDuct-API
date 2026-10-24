// ============================================================================
// 视频流水线（P2 · v2：按 CicadaPlayer 的分层结构重写）
//
// 老版本是一条"什么都干"的主循环：读包 → 送解码器 → 收帧 → 转换 → 发布 → 等时钟，
// 而且"等音频时钟"是在这条循环里用 2ms 轮询实现的 —— 于是
//   * 网络抖一下（av_read_frame 阻塞）画面立刻停摆；
//   * seek 只能排在队尾，还得在同一个循环里把四件事（demuxer/解码器/声卡/时钟）一口气做完；
//   * 解码、转换、呈现、等待全在同一线程，任何一个环节慢都会直接变成顿挫；
//   * 落后了也没有丢帧策略，只能"一帧一帧慢慢挪"或者"狂发帧追"。
//
// 现在按成熟播放器的分层来（对照 CicadaPlayer）：
//
//   [demux 线程]  av_read_frame ──► 包队列（有界 60 包/8MB）
//        ▲                              │  等待可被 seek/停止打断
//        │ interrupt_callback           ▼
//   [解码线程]  avcodec_send/receive ──► 帧队列（有界 3 帧，满了就等，不丢）
//        ▲                              │
//        │                              ▼
//   [呈现线程 = 调用 run() 的线程]  主时钟判定（等 / 丢）──► 转换 ──► 共享纹理发布
//
// 三条线程各管一段，谁都不等谁超过一个队列的深度；seek 变成**三阶段握手**
// （demux 定位 → 解码器 flush → 呈现侧重锚时钟 + 丢弃前导帧），每个阶段由各自的线程执行，
// 靠一个递增的"世代号"对齐（见 SeekState）。控制命令只改原子量，**永不阻塞**。
//
// 与 Java 侧的契约**一个字都没变**：命令行参数、控制管道协议、共享状态块布局与语义、
// 片尾待命/循环/暂停冻结/seek 后继续播等行为都与老版本一致（eof-test.ps1 逐条覆盖）。
// ============================================================================
#pragma once

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <thread>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/frame.h>
}

#include "audio_chain.h"
#include "audio_setup.h"
#include "audio_sink.h"
#include "convert.h"
#include "ffmpeg_dyn.h"
#include "frame_queue.h"
#include "gpu.h"
#include "ipc.h"
#include "media_clock.h"
#include "packet_queue.h"
#include "shared_tex.h"
#include "sw_scale.h"
#include "video_decoder.h"

namespace cineduct::host {

/** 父进程（游戏）是否已经退出 —— 由 main.cpp 的看护线程置位。 */
extern std::atomic<bool> g_parentGone;

/** HDR → SDR 色调映射走哪条路。 */
enum class HdrToneMapMode {
    /**
     * 交给 D3D11 驱动（默认）：把输入声明成 G2084/P2020、输出 G22/P709，驱动自己做色调映射。
     * 零额外开销，**这是修"HDR 发灰"的主路径**。
     */
    Driver,
    /**
     * 在 CPU 上做：硬解帧回拷 CPU → swscale 转 16 位 RGB → 自己算色调映射。
     * 曲线可控、结果更接近标准色调映射实现，代价是每帧一次 GPU→CPU 拷贝（1080p 还好，4K 会很贵）。
     * 当驱动的映射看着偏暗/偏灰时的兜底。
     */
    Cpu,
    /** 关掉（诊断用：复现"HDR 发灰"）。 */
    Off,
};

/** 流水线的全部输入（命令行解析的结果；语义与老版本逐条对应）。 */
struct PipelineConfig {
    // ---- 输入 ----
    std::string url;
    std::string headers;
    std::string userAgent;

    // ---- 输出（共享纹理）----
    std::string outputBase;                 // 空 = 不出画面（纯解码自检）
    std::string statusName = "cineduct_host_status";
    uint32_t slots = 3;
    uint32_t outWidth = 0;
    uint32_t outHeight = 0;
    double fitAspect = 0.0;
    std::string dumpFrame;                  // 非空 = 首帧落盘 PPM（诊断黑边/取景）
    /**
     * HDR → SDR 色调映射走哪条路（默认 Driver，见 HdrToneMapMode）。
     *
     * <p>`--hdr-tonemap driver|cpu|off`；`--no-hdr-tonemap` 等价于 off（诊断对比用）。
     */
    HdrToneMapMode hdrToneMap = HdrToneMapMode::Driver;
    /**
     * **诊断专用**：起播 2 秒后故意把音频拖后这么多毫秒（人为制造"画面领先声音"）。
     *
     * <p>用来验证音画偏移纠正那条路真的在工作（见 `native/av-sync-test.ps1`）。
     * 生产环境不要用 —— 它就是在人为制造不同步。
     */
    int debugAudioLagMs = 0;

    /**
     * 音画偏移**自动纠正**（默认关）。
     *
     * <p>为什么要默认关：纠正的动作是"把音频 seek 到画面位置"，一旦触发错就是**听得出来的跳音**；
     * 而"可听内容位置"的推算在 seek/清缓冲附近还有约 1 秒的系统偏差（来源未完全钉死，
     * 疑似音频链那次"首帧对表 seek"没有真正改变内容位置，见 DEV-WORKFLOW §8.5）。
     * 所以默认只**测量并记录**，纠正留给 `--av-correct` 显式打开 —— 等证据齐了再决定是否默认开。
     */
    bool avCorrect = false;

    /**
     * **诊断专用**：每 500ms 打一行音画偏移明细（含排队/可听内容/已写已播帧数）。
     *
     * <p>排查"暂停之后音画错开"这类问题时把它打开，日志里就有可对照的数字。
     * `--av-debug` 打开它（与 `--debug-audio-lag` 的区别：那个会**注入**不同步，这个只观察）。
     */
    bool avDebug = false;

    // ---- 缓冲与"等缓存"闸门（见 docs/PLAN-preload-buffer.md）----
    /**
     * **目标缓冲水位**（毫秒）：包队列里首尾 PTS 的跨度上限。
     *
     * <p>这就是"提前缓存几秒"的总开关。以前按条数（60 包）限深，网络侧最多领先 1 秒，
     * 一次抖动就抽干；现在按媒体时长限深，下载线程会在播放位置**前面**停住。
     * <= 0 表示退回旧的"只按条数/字节"行为。
     */
    int bufferTargetMs = 4000;
    /**
     * **起播预缓冲**（毫秒）：第一帧到达后先攒够这么多再开播（同时放声卡）。
     *
     * <p>为什么需要：以前第一帧一到就锚时钟开播，此时缓冲里几乎是空的 —— 网络稍抖，
     * 起播那几秒就"卡一下、跳一段"，最坏直接黑屏等下一下。
     * 攒一小段再开，代价是起播晚最多这么久。<= 0 关闭。
     */
    int prerollMs = 1500;
    /**
     * **卡顿闸门**（毫秒）：消费者（游戏进程）连续这么久没取帧，就把时钟+声卡冻住等它。
     *
     * <p>这是"客户端卡顿不要变成丢帧"的核心：卡顿期间位置不再前进，
     * 恢复后接着从冻住的位置放，而不是把积压的帧全丢掉去追时钟。
     * <= 0 关闭（只保留水位观测）。
     */
    int stallGateMs = 1500;
    /**
     * 供给闸门：水位低于低水位线且队列里没有帧可出时，冻住等数据（毫秒为单位的低水位按
     * bufferTargetMs 推算，不单独配）。<= 0 关闭。
     */
    int supplyGateMs = 800;
    /**
     * **诊断专用**：每打一次卡顿就注入一次这么长的假停顿（模拟客户端卡死）。
     *
     * <p>`--stall-inject N` 用；生产不要开。
     */
    int stallInjectMs = 0;

    // ---- 播放控制 ----
    int avOffsetMs = 0;                     // > 0 = 画面提前
    int volume = 100;
    bool loop = false;
    bool pace = true;                       // 无音频时按 PTS 节奏出帧
    int frames = 0;                         // <= 0 = 不限
    double seconds = 0;                     // <= 0 = 不限
    double holdSeconds = 0;                 // 收尾后保活（测试用）

    // ---- 音频 ----
    bool audio = true;
    std::string audioUrl;                   // DASH 独立音轨；空 = 与视频同一条链
    AudioModePreference audioMode = AudioModePreference::Auto;
    std::string audioDevice;

    // ---- IPC ----
    std::string pipeName;                   // 非空即开控制通道

    /*
     * 子进程身份（v9，见 docs/host-uuid.md）：
     *   sessionUuid  逻辑会话级，重启不变；instanceUuid 每次 spawn 一个。
     * 两者都由 main.cpp 在解析参数时定下（缺省自己生成），这里只负责往状态环与 hello 回执里带。
     */
    std::string sessionUuid;
    std::string instanceUuid;
};

class VideoPipeline {
public:
    VideoPipeline(const FfmpegApi& api, GpuDevice& gpu, PipelineConfig config);
    ~VideoPipeline();

    VideoPipeline(const VideoPipeline&) = delete;
    VideoPipeline& operator=(const VideoPipeline&) = delete;

    /** 打开输入/解码器/音频/控制通道，并起 demux 与解码线程。 */
    bool start(std::string& error);

    /**
     * 跑呈现循环（**就在调用者线程上**）。返回进程退出码。
     *
     * <p>为什么呈现跑在调用者线程而不是又开一条：它就是"主循环"，让它用 main 线程能省一次
     * 线程切换，也让"主线程退出"这件事天然等价于"播放结束"。
     */
    int run();

    /** 控制通道的一条命令（线程安全；与老版本的回执格式逐字一致）。 */
    std::string handleCommand(const std::string& command);

    // ---- 供 main.cpp 汇报用的统计 ----
    int publishedFrames() const { return this->publishedCount.load(); }
    int decodedFrames() const { return this->decodedCount.load(); }
    int hardwareFrames() const { return this->hardwareFrameCount.load(); }
    uint64_t droppedLateFrames() const { return this->droppedLateCount.load(); }
    long long firstPtsMs() const { return this->firstPtsMsValue.load(); }
    long long lastPtsMs() const { return this->lastPtsMsValue.load(); }
    bool outputEnabled() const { return !this->config.outputBase.empty(); }

private:
    // ---- 三个线程体 ----
    void demuxLoop();
    void decodeLoop();
    void renderLoop();

    // ---- seek 的三个阶段（每阶段只被自己的线程执行）----
    void demuxSeekStage();
    void decodeSeekStage();
    void renderSeekStage();
    /** 定位到指定容器时间戳（只由 demux 线程调用；返回 av_seek_frame 的结果）。 */
    int seekContainerTo(long long containerMs);
    /**
     * 定位没落地时的自愈阶梯（由**呈现线程**调用，见 handleEndOfStream）。
     *
     * <p>阶梯：① 同目标换单点定位 → ② 退回目标前 2 秒 → ③ 回起点 → ④ 放弃（认片尾）。
     *
     * @return true = 已经重新请求定位（世代 +1，三阶段重来），调用方应回主循环
     */
    bool requestBadSeekRetry();

    // ---- 解码线程内部 ----
    /**
     * 收帧直到 EAGAIN/EOF；返回是否收到了至少一帧。
     *
     * @param generation 产出这些帧的**包**所在的世代（帧继承包的世代，见实现里的★说明）
     */
    bool drainDecoderFrames(uint64_t generation);
    /** 硬解连续出错 → 换软解重开（并请求回到最近的关键帧，避免花屏）。 */
    void requestSoftwareFallback();

    // ---- 呈现线程内部 ----
    /** 出画面（含懒初始化转换器）；返回是否成功产出这一帧。 */
    bool presentFrame(AVFrame* frame);
    /** 懒初始化：输出几何 + 输出纹理 + 硬件/软件转换路径 + 共享纹理。 */
    bool ensureOutput(AVFrame* frame, std::string& error);
    /** 纪元开始（起播 / seek 之后的第一帧）：锚定时钟、必要时起播音频。 */
    void beginEpoch(long long mediaPtsMs);
    /** 片尾处理：循环 / 待命 / 收工。 */
    void handleEndOfStream();    /** 待命：保持最后一帧，继续响应控制（片尾不退出进程，见 DEV-WORKFLOW §4.6）。 */
    void idleAfterEnd();
    /** 把一个 seek 请求发布出去（三个线程各自看到新世代）。 */
    void requestSeek(long long targetMs);
    /** 音频 seek 的容器时间戳：老版本直接用目标毫秒，直播（PTS 不从 0 起）会差一个原点。 */
    long long audioSeekTargetMs(long long mediaTargetMs) const;
    /** 时钟状态迁移的日志与补救（恢复时把音频拉去追画面）。 */
    void handleClockTransition(MediaClock::Transition transition);
    /**
     * 音画偏移检测 + **纠正**（每 500ms 评估一次，只由呈现线程调用）。
     *
     * <p>为什么必须有这一步：音频落后画面这一侧以前**没有任何自动纠正** ——
     * 暂停、直播重连、或时钟估计偏高都会让"声音落在画面后面一段"，
     * 而画面是按时钟走的，于是这个偏差**永远保持**（用户报的"暂停之后音画不同步、且不随时间变化"）。
     * 纠正手段是把**音频** seek 到画面位置（音频能 seek，且这条路是非阻塞请求），
     * 与 clock 恢复路径里那套补救一致。
     */
    void checkAudioVideoOffset();
    /** 诊断：故意把音频拖后（`--debug-audio-lag`），只做一次。 */
    void injectDebugAudioLag();
    /** 音频链是否已经不可用（声卡失败 / 链路出错）→ 改用挂钟，画面照走。 */
    void checkAudioHealth();
    /**
     * 音频僵死看门狗：内容位置连续若干秒不前进就主动修一次（seek / 重开输入）。
     *
     * <p>2026-10-17 事故的对症措施：音频链把读超时当片尾后静默停摆 26 秒，
     * 而"切挂钟"这个状态本身会**禁用** AV 偏移纠正，所以必须有独立于时钟的修复路径。
     */
    void maybeRepairAudio();
    /** 音频 seek 的收尾：等它真的跳过去了，再清一次声卡缓冲（见 AudioChain::seekAppliedCount）。 */
    void settleAudioSeek();
    /** 把位置/时长/音频形态写进共享状态块（每帧 + 空转时定期）。 */
    void updateStatus();
    /** 采集吞吐统计（producerFpsX100 以前恒为 0：那个接口从来没人调）。 */
    void updateThroughput();
    /** 唤醒呈现线程（暂停/seek/停止/退出时调用）。 */
    void wakeRender();

    // ---- 缓冲水位与"等缓存"闸门（docs/PLAN-preload-buffer.md 的 L0/L1/L3）----
    /** 包队列里首尾 PTS 的跨度（毫秒）= 网络侧还有多少秒没解码。 */
    int packetBufferMs() const;
    /** 已解码未呈现的帧折算成的时长（毫秒）。 */
    int frameBufferMs() const;
    /** 总水位 = 包队列 + 帧队列。 */
    int bufferLevelMs() const;
    /**
     * 采样消费者（游戏进程）的取帧进度，维护 "连续多久没取帧"。
     *
     * <p>判据是**共享状态块里的槽位状态**：消费者每拷完一帧会把该槽标成 SHOWN。
     * 所有槽都停在 READY（没人来拷）就说明游戏侧停了 —— 这就是"客户端卡顿"的客观信号，
     * 不需要跨进程 IPC。
     */
    void pollConsumerProgress();
    /**
     * 每轮呈现循环评估一次：这一轮要不要"等"（不取帧、不推进位置）。
     *
     * <p>三种"等"：① 起播预缓冲（还没有第一帧）；② 供给见底（水位低于低水位且没帧可出）；
     * ③ 消费者卡住（游戏进程连续没取帧）。②③ 会进闸门（冻时钟 + 停声卡）。
     *
     * @return true = 本轮应当空转等待（调用方 continue）
     */
    bool evaluateBufferGate();
    /** 进闸门：冻住时钟与声卡，位置就此停住（"等缓存"）。 */
    void enterGate(const char* reason);
    /**
     * 退闸门：从冻住的那个位置接着放。
     *
     * @param restorePlayback true = 把时钟与声卡放回播放（正常退出闸门）；
     *                        false = 只解除闸门状态，**不动**时钟/声卡（因为紧接着要暂停、
     *                        定位或收工，这时去"恢复播放"会把用户刚按下的暂停又顶开）
     */
    void leaveGate(bool restorePlayback = true);
    bool stopNow() const { return this->stopRequested.load() || g_parentGone.load(); }
    /** av_read_frame 的中断回调（seek/停止时打断阻塞读）。 */
    static int interruptCallback(void* opaque);

    const FfmpegApi& api;
    GpuDevice& gpu;
    PipelineConfig config;

    // ---- 输入 ----
    AVFormatContext* format = nullptr;
    AVStream* videoStream = nullptr;
    int videoStreamIndex = -1;
    long long durationMsHint = -1;
    /** 时间轴原点 = 流首帧的原始 PTS（直播的 PTS 不从 0 起，见 DEV-WORKFLOW §4.5）。 */
    std::atomic<long long> ptsBaseMs{-1};

    // ---- 队列与线程 ----
    PacketQueue packets;
    FrameQueue frames;
    std::thread demuxThread;
    std::thread decodeThread;
    std::atomic<bool> stopRequested{false};

    // ---- seek（三阶段握手）----
    struct SeekState {
        std::atomic<long long> targetMs{0};
        std::atomic<uint64_t> generation{0};
        std::atomic<uint64_t> demuxDone{0};
        std::atomic<uint64_t> decodeDone{0};
        std::atomic<uint64_t> renderDone{0};
        /** 最近一次视频定位的返回值（INT_MIN = 本世代还没执行）—— 只用于日志。 */
        std::atomic<int> videoResult{-2147483647};
        std::atomic<int> videoResultGeneration{0};
        /**
         * demux 线程正在执行定位。
         *
         * <p>中断回调要放它一马：`av_seek_frame` 自己也会查中断回调，若此时又来了一个新 seek
         * （"seek 风暴"：上层连点几下进度条），回调返回 1 会把**正在做的定位**打断，
         * 日志里出现"视频定位失败"，状态也白折腾一遍。改为"让当前这次做完"，
         * 新世代紧接着会在下一轮被处理（定位本身有 rw_timeout 兜着，不会无限期）。
         */
        std::atomic<bool> demuxSeeking{false};
    } seek;
    /**
     * seek 自愈的已用次数（"定位之后一帧都没出来就 EOF" → 阶梯重试，最多 3 次）。
     *
     * <p>为什么需要：某些流的定位会"看起来成功、实际读不下去" ——
     * 实测（`native/testdata/bbb-livepts.ts`，PTS 从 2345 秒起的 TS）连 ffmpeg 自己的
     * `-ss` 都解不出帧。此时若直接把 EOF 当片尾上报，上层看到的就是"点了跳转 → 播放结束"。
     * 阶梯：换单点定位 → 退回 2 秒 → 回起点；由**用户**发起的定位会把配额重新给满。
     */
    std::atomic<int> seekRecoveryAttempts{0};
    /** 最近读到的视频包在媒体时间轴上的位置（毫秒；-1 = 还没读到）—— 只用于日志。 */
    std::atomic<long long> lastPacketPtsMs{-1};
    /** 连续"被中断"的读数（防止在极端情况下空转）。 */
    std::atomic<int> interruptRetries{0};
    /** 定位自愈累计次数（进状态块，见 spike_status 的 v4 字段）。 */
    std::atomic<uint32_t> seekHealCount{0};
    /**
     * 当前解码器是不是硬解（d3d11va）。
     *
     * <p>由解码线程在 open/软解重开时写入 —— 呈现线程要把它写进状态块（上层据此显示
     * "现在跑的是硬解还是软解"），直接读 `decoder` 的成员会跨线程，所以用原子量转发。
     */
    std::atomic<bool> decoderIsHardware{false};
    /** 呈现线程是否没有帧可出（等数据/缓冲中）—— 进状态块。 */
    std::atomic<bool> buffering{false};

    // ---- 缓冲闸门（v11；只由呈现线程改，控制通道只读）----
    /** 闸门是否生效：时钟与声卡已冻住，位置不再前进（"等缓存"）。 */
    std::atomic<bool> gateActive{false};
    /** 累计进闸门次数 / 累计冻住时长（进状态块，上层据此判断"这次卡顿有没有被兜住"）。 */
    std::atomic<uint32_t> gateCount{0};
    std::atomic<uint64_t> gateTotalMs{0};
    /** 本次进闸门的时刻（算 gateTotalMs 用）。 */
    std::chrono::steady_clock::time_point gateSince{};
    /** 上次退闸门的时刻（防抖：别一秒进三次闸门，声卡会"咔咔"响）。 */
    std::chrono::steady_clock::time_point lastGateExitAt{};
    /** 消费者（游戏进程）连续多久没取帧（毫秒，0 = 正常）。 */
    std::atomic<uint32_t> consumerStallMs{0};
    /** 上一次看到的"消费者已取走"的帧号（UINT32_MAX 的前缀 = 还没见过）。 */
    uint32_t lastConsumedGeneration = 0xFFFFFFFFu;
    /** 消费者最后一次取帧的时刻。 */
    std::chrono::steady_clock::time_point lastConsumerProgressAt{};
    /** 消费者是否至少取过一帧（没过就一直不判"卡顿"—— 没人看的屏幕本来就没人取帧）。 */
    bool consumerEverSeen = false;
    /** 起播预缓冲实际等了多久（进状态块，0 = 没等）。 */
    std::atomic<uint32_t> prerollWaitedMs{0};
    /** 起播预缓冲的起始时刻（0 = 还没开始等）。 */
    std::chrono::steady_clock::time_point prerollSince{};
    /** 起播预缓冲已经判定完成（只判一次，避免每轮重复打日志）。 */
    bool prerollDone = false;
    /** 诊断用的假卡顿是否已经注入过。 */
    bool stallInjected = false;
    /** 最近一帧是不是"硬解帧回拷 CPU 再转"（设备没有可用 VideoProcessor 时的兜底）。 */
    bool lastFrameUsedCpuCopy = false;
    /**
     * 音画偏移检测/纠正（见 checkAudioVideoOffset）。
     *
     * <p>`avOffsetMs` = 最近发布帧的 PTS − 音频时钟：正数 = **画面领先声音**。
     * 正常情况它只在一个帧间隔内摆动；持续为正且较大，说明音频内容落在了画面后面
     * （暂停/重连/时钟估计偏高都可能造成），而"落后"这一侧**没有任何自动机制会去纠正** ——
     * 这正是"暂停之后音画错开、再也不回来"的来源。
     */
    std::atomic<long long> measuredAvOffsetMs{0};
    /** 连续多少次采样都判定偏移过大（用来滤掉单帧抖动）。 */
    int avOffsetStreak = 0;
    /** 上一次做偏移纠正的时刻（限流，别反复 seek）。 */
    std::chrono::steady_clock::time_point lastAvCorrectionAt{};
    /** 上一次评估偏移的时刻（每 500ms 看一次就够）。 */
    std::chrono::steady_clock::time_point lastAvCheckAt{};
    /** 累计纠正次数（诊断/状态汇报）。 */
    std::atomic<int> avCorrectionCount{0};
    /**
     * 默认生效的"无痕"纠正：把**时钟**重新锚定到实测的音频内容位置。
     *
     * <p>和 `avCorrectionCount`（`--av-correct`：把音频 seek 到画面位置，会听见跳音）
     * 区分开：这一侧的动作只是**改时钟与内容的对应关系**，声卡一个字节都不动，
     * 因此不会有任何爆音；代价是画面会跳一下（落后就等、领先就丢帧追上），
     * 但同步关系一定回来。默认开启 —— 用户报的就是"错开了而且不纠正"。
     */
    std::atomic<int> clockHealCount{0};
    /** 上一次"时钟重锚"的时刻（限流：至少间隔 3 秒，最多 8 次）。 */
    std::chrono::steady_clock::time_point lastClockHealAt{};
    /** 最近算出的"可听内容位置"（毫秒，诊断用）。 */
    std::atomic<long long> measuredAudibleContentMs{-1};
    /** 是否每次评估都打一行偏移明细（诊断模式下自动打开）。 */
    bool verboseOffset = false;
    /**
     * 最近一次"音频不连续"（seek / 清声卡缓冲 / 首帧对表）的时刻。
     *
     * <p>偏移判定要等它过去一段时间再做：不连续点附近，"可听内容位置"的推算会被
     * "已丢弃但仍在计数里的队列内容"污染（实测能差一秒多），此时去纠正就是**追自己的假象**。
     */
    std::chrono::steady_clock::time_point lastAudioDiscontinuityAt{};
    /** 上一次采样的偏移（用来要求偏移**稳定**，不是抖动或正在收敛）。 */
    long long prevAvOffsetMs = 0;
    bool hasPrevAvOffset = false;
    /** 偏移日志限流（每 5 秒最多一行）。 */
    std::chrono::steady_clock::time_point lastAvLogAt{};
    /** 诊断用的"故意拖后音频"只做一次。 */
    bool debugAudioLagDone = false;
    /**
     * demux 刚定位完、还没读到第一个包（用于识别"坏定位"）。
     *
     * <p>两种坏法都要认：① 一个包都读不到（解复用器落在空处）；
     * ② 读到了包但 PTS 比目标晚一大截（落在文件末尾 —— 实测 TS 就是这样）。
     */
    std::atomic<bool> awaitingFirstPacketAfterSeek{false};
    /** 下次定位强制用单点 av_seek_frame（而不是带时间窗的 avformat_seek_file）。 */
    std::atomic<bool> forcePlainSeek{false};

    // ---- 解码线程（只有它碰 decoder）----
    VideoDecoder decoder;
    std::atomic<bool> wantSoftwareFallback{false};
    /** 解码线程正在重开解码器（日志/诊断）。 */
    std::atomic<bool> reopenedSoftware{false};

    // ---- 呈现线程（只有它碰这些）----
    SharedTextureSet shared;
    VideoConverter converter;
    SwScaler scaler;
    bool outputReady = false;
    /** 软解转换器是否已经按这一帧的格式初始化过（格式变了要重建）。 */
    bool scalerInitialised = false;
    AVPixelFormat scalerSourceFormat = AV_PIX_FMT_NONE;
    /** 软解转换器初始化时用的色彩信息（HDR 换标签时要重建，见 presentFrame）。 */
    VideoColorInfo scalerColor{};
    /** 首帧读到的色彩信息（初始化 VideoProcessor 用；日志里也会打出来）。 */
    VideoColorInfo frameColor{};
    LetterboxPlan letterbox{};
    AVFrame* cpuFrame = nullptr;         // 硬解帧回拷（诊断 + 无 VideoProcessor 时的兜底）
    bool checkedCpuTransfer = false;
    bool dumpedFirstFrame = false;
    /** 定位点之前的前导帧阈值（-1 = 不丢）：见 renderSeekStage 的说明。 */
    long long prerollTargetMs = -1;
    /** 当前纪元还没锚定时钟（起播或 seek 之后的第一帧负责锚定）。 */
    bool epochStartPending = true;
    /**
     * 当前这个还没出画面的纪元是不是 seek 造成的。
     *
     * <p>用来区分两种情况：① 正常播到片尾（早出过画面了，epochStartPending 已是 false）；
     * ② seek 之后一帧都没出来就 EOF（= 定位没落地，要自愈，见 handleEndOfStream）。
     * 只由呈现线程读写。
     */
    bool epochPendingIsFromSeek = false;

    // ---- 音频 ----
    AudioSink audioSink;
    std::unique_ptr<AudioChain> audio;
    std::atomic<bool> audioActive{false};
    /*
     * 音频"僵死"看门狗（见 maybeRepairAudio）。
     *
     * <p>为什么需要它：音频链会停在"片尾待命 / 读失败待命"里，**只有 seek 或重开能唤醒**。
     * 一旦它停了，时钟会切到挂钟，而 AV 偏移纠正又要求 `clock.audioMaster()`（见
     * checkAudioVideoOffset 的早退），于是谁都不会去救它 —— 画面继续、声音永缺、
     * 偏移无限增长（2026-10-17 现场：+26.3 秒）。所以必须有人主动修。
     */
    double lastAudioContentMs = -1.0;
    std::chrono::steady_clock::time_point audioStuckSince{};
    std::chrono::steady_clock::time_point lastAudioRepairAt{};
    std::atomic<int> audioRepairCount{0};
    /** 音频链是否已经 start()。 */
    bool audioStarted = false;
    /** 首帧对表是否已经做过（点播只在起播时做一次，seek 之后的音频由 seek 阶段负责）。 */
    bool firstEpochAligned = false;
    std::string audioLabel = "无音频";
    MediaClock clock;
    /** 音频 seek 收尾：请求计数与时间。 */
    uint64_t audioSeekCountAtRequest = 0;
    std::chrono::steady_clock::time_point audioSeekRequestedAt{};
    bool audioSeekSettlePending = false;

    // ---- 控制与统计 ----
    ControlPipe control;
    std::atomic<bool> paused{false};
    std::atomic<bool> loopWanted{false};
    std::atomic<int> avOffsetMs{0};
    std::atomic<int> volumePercent{100};
    std::atomic<int> publishedCount{0};
    std::atomic<int> decodedCount{0};
    std::atomic<int> hardwareFrameCount{0};
    std::atomic<uint64_t> droppedLateCount{0};
    std::atomic<uint64_t> droppedConvertCount{0};
    std::atomic<long long> mediaPositionMs{0};
    /**
     * 暂停那一刻的位置（毫秒）。
     *
     * <p>为什么不能"暂停时直接读时钟"：声卡的 Stop() 要花几毫秒才真正停下，
     * 设备待播数还会抖一下，于是时钟会**慢慢爬**几毫秒 —— 上层的进度条就会
     * 在暂停期间一点点挪（EOF 回归测试里这被判成"暂停没冻住"）。
     * 暂停位置在按下的那一刻钉死，恢复时才由时钟继续接手。
     */
    std::atomic<long long> pausedPositionMs{0};
    std::atomic<long long> lastPublishedPtsMs{-1};
    std::atomic<long long> firstPtsMsValue{-1};
    std::atomic<long long> lastPtsMsValue{-1};
    std::atomic<bool> demuxAtEof{false};
    std::atomic<int> demuxError{0};

    std::mutex wakeMutex;
    std::condition_variable wakeSignal;

    /** `--seconds` 的截止时刻（配置为 0 时未定义）。 */
    std::chrono::steady_clock::time_point deadline{};
    /** 吞吐统计的滑动基准（每秒算一次）。 */
    std::chrono::steady_clock::time_point throughputBase{};
    int throughputBaseCount = 0;

    /** 退出时的统一收尾（幂等）。 */
    void stopThreadsAndCleanup();
    bool cleanedUp = false;
};

} // namespace cineduct::host

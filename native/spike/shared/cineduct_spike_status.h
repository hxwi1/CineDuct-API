// ============================================================================
// 生产者 ↔ 消费者 的共享内存状态块（P1 打样用）
//
// 这是计划文档 4.3 节「共享内存状态环」的最小原型：生产者写，消费者只读
// （槽位的 SHOWN 标记由消费者回写），用来回答三个问题而完全不产生每帧 IPC：
//   * 现在有没有新帧可采样（槽位 state + generation）
//   * 生产者到底跑了多少帧、丢了多少帧、加锁等了多久
//   * 生产者用的适配器 LUID 是不是消费者 GL 上下文所在的那个
//
// 布局是**跨语言契约**：Java 侧按固定偏移读（见 SpikeConsumer.StatusView），
// 改动这里必须同步改 Java 侧，并递增 kVersion。
// ============================================================================
#pragma once

#include <cstddef>
#include <cstdint>

namespace cineduct::spike {

// "MFXP"
inline constexpr uint32_t kMagic = 0x4D465850u;
inline constexpr uint32_t kVersion = 11;

// 槽位状态机（计划文档 3.3 节）
//   EMPTY   : 生产者可写
//   WRITING : 生产者正在写（消费者别碰）
//   READY   : 已写好一帧，消费者可以锁来采样
//   SHOWN   : 消费者已经采样过，生产者可复用
inline constexpr uint32_t kSlotEmpty = 0;
inline constexpr uint32_t kSlotWriting = 1;
inline constexpr uint32_t kSlotReady = 2;
inline constexpr uint32_t kSlotShown = 3;

inline constexpr uint32_t kMaxSlots = 8;

// 帧色标编码：R = gen & 0xFF, G = (gen >> 8) & 0xFF, B = kStampBlue
// 消费者据此判断：帧确实在更新、通道没有错位（B 通道必须恒为 kStampBlue）
inline constexpr float kStampBlue = 0xA5 / 255.0f;

#pragma pack(push, 8)
struct SlotStatus {
    uint32_t state;       // kSlot*
    uint32_t generation;  // 该槽最后一次写入的帧号
    uint32_t stampR;      // 该帧色标的 R（0..255）
    uint32_t stampG;      // 该帧色标的 G（0..255）
};

struct Status {
    uint32_t magic;             // 0
    uint32_t version;           // 4
    uint32_t width;             // 8
    uint32_t height;            // 12
    uint32_t slots;             // 16
    uint32_t reserved0;         // 20
    uint64_t framesPublished;   // 24
    uint64_t framesDropped;     // 32
    uint32_t producerFpsX100;   // 40
    uint32_t lastAcquireUs;     // 44
    uint32_t maxAcquireUs;      // 48
    uint32_t adapterLuidHigh;   // 52
    uint32_t adapterLuidLow;    // 56
    uint32_t producerPid;       // 60
    SlotStatus slot[kMaxSlots]; // 64 起，每槽 16 字节（到 191 结束）

    /*
     * v2 追加：播放位置与时长。
     *
     * 为什么放进共享内存而不是走管道：客户端**每帧**都要读播放位置（进度对齐、UI、附属 mod 的
     * 状态面板），而管道是一次系统调用往返 —— 按帧查会让渲染线程被 IPC 拖住。
     * 放进状态环后，读位置就是读一块内存，零系统调用。
     */
    uint64_t mediaPositionMs;   // 192：媒体时间轴上的播放位置（音频时钟换算）
    uint64_t mediaDurationMs;   // 200：时长；未知为 0
    uint32_t audioSampleRate;   // 208
    uint32_t paused;            // 212
    uint64_t lastSeekTargetMs;  // 216：最近一次 seek 目标（-1 = 没 seek 过）

    /*
     * v3 追加：音频输出形态。
     *
     * 客户端需要知道这件事才能正确显示/限制控制项：直通（IEC 61937）模式下
     * 码流不可修改 → 音量/静音命令无效，UI 不该装作可调。
     */
    uint32_t audioMode;         // 224：0=共享 1=独占PCM 2=IEC61937直通
    uint32_t audioChannels;     // 228：输出声道数（直通时是链路声道数，如 DD+ 的 2、TrueHD 的 8）
    uint32_t audioContentRate;  // 232：解码后的内容采样率（直通时才有意义）

    /*
     * v3 追加：播放是否已到片尾。
     *
     * 子进程到片尾后**不再退出**（保持最后一帧待命），所以客户端不能再用"进程还在不在"
     * 判断播放结束 —— 否则会把"放完"当成"崩溃"而触发自动重启，一个视频就会无限重播。
     */
    uint32_t ended;             // 236：1 = 已播到片尾（未循环）

    /*
     * v3 追加：端点的空间音效（Dolby Atmos 等）状态。
     *
     * 数据来自 WinRT 的 SpatialAudioDeviceConfiguration（DefaultSpatialAudioFormat = 用户选的、
     * ActiveSpatialAudioFormat = 系统当前在跑的）—— 这是"到底有没有在输出 Atmos"唯一能
     * 程序化拿到的官方信号。客户端据此在聊天框里给本机玩家播报一次。
     */
    uint32_t spatialAudio;      // 240：0=支持但未启用 1=已启用 2=查不到 3=端点不支持
    /*
     * 空间音效格式名（UTF-8，NUL 结尾；空 = 未启用）。
     * 放在共享内存里是为了让客户端零 IPC 就能显示"到底是不是 Dolby Atmos"。
     */
    char spatialFormat[64];     // 244（紧接 spatialAudio 之后，char 数组不需要额外对齐）
    /** 实际打开的音频端点名（UTF-8，NUL 结尾）—— 让客户端零 IPC 就能显示"声音去了哪个设备"。 */
    char audioDeviceName[64];   // 308

    /*
     * v4 追加：诊断信息（**只追加在末尾**，既有偏移一个都没动）。
     *
     * <p>为什么需要：上层一直没法从零 IPC 的通道里知道这三件事，出了问题时只能翻子进程日志：
     *   * **现在到底是硬解还是软解** —— 硬解不可用时子进程会自动回退软解（画面照出，但 CPU 上去了），
     *     上层应该能显示出来，否则"为什么这台机器风扇突然狂转"没法解释；
     *   * **有没有在丢帧、丢了多少** —— 主时钟判定的"迟到丢帧"是正常追赶行为，
     *     但数量和速率能区分"正常追赶"与"解码跟不上"；
     *   * **定位自愈发生了多少次** —— 有些流根本不能定位（连 ffmpeg 的 -ss 都解不出帧），
     *     子进程会退一步继续播；上层知道发生过这件事，才能在 UI 上给个提示而不是莫名其妙。
     */
    uint32_t decodeMode;        // 372：0=未知 1=硬解(d3d11va) 2=软解(CPU) 3=硬解帧回拷 CPU
    uint32_t buffering;         // 376：1 = 当前没有新帧可出（等数据/缓冲中）
    uint32_t seekHealCount;     // 380：累计"定位自愈"次数（坏定位退回重试的次数）
    uint64_t droppedLate;       // 384：累计**迟到丢弃**的帧数（主时钟追赶，正常现象）

    /*
     * v5 追加：**最近发布的那一帧在媒体时间轴上的位置**。
     *
     * <p>为什么必须单独给这个：`mediaPositionMs` 是**音频时钟**的位置，只有把它和"视频帧的 PTS"
     * 摆在一起才能算出**音画偏移**（`videoPtsMs - mediaPositionMs`）。
     * 之前没有这个字段，"暂停之后音画错开一段再也没纠正"这类问题只能靠人眼看着说，
     * 没法量化、也没法做回归断言。有了它，上层可以直接把偏移显示出来、并在异常时报警。
     */
    uint64_t videoPtsMs;        // 392：最近发布帧的媒体时间（毫秒）；0 = 还没有帧

    /*
     * v6 追加：**音画偏移自愈次数**（重锚时钟）。
     *
     * <p>子进程默认会自己纠正音画偏移：动作是"把时钟重锚到实测的可听内容位置"，
     * 声卡一个字节都不动（所以没有跳音），代价是画面会跳一下追上/等一等。
     * 正常播放永远是 0；一旦大于 0，就说明**这次播放真的错开过**——
     * 上层据此可以在日志里给出一条明确的事件（否则用户只能看到"好像不同步了"却无从判断）。
     */
    uint32_t clockHealCount;    // 400：累计"重锚时钟"次数（一次播放内的上限是 8）
    uint32_t reserved1;         // 404：对齐占位

    /*
     * v7 追加：**NT 句柄直投**（内存对象路线用；只追加，既有偏移一个都没动）。
     *
     * <p>为什么句柄必须在共享内存里交换：GL 侧的 {@code glImportMemoryWin32HandleEXT} 要的是
     * **调用进程内有效的内核句柄值**，而"按名打开"只有 D3D 侧才有
     * （{@code OpenSharedResourceByName}）—— GL 没有这一手。所以句柄只能由生产者用
     * {@code OpenProcess(PROCESS_DUP_HANDLE) + DuplicateHandle} 复制进消费者进程，
     * 再把"消费者进程里的句柄值"传回来。控制管道在打样阶段还不存在，而且交换必须发生在
     * "消费者已就绪"之后，所以直接放在状态环里。
     *
     * <p>握手顺序：
     * <pre>
     *   ① 消费者写 consumerPid + consumerWantDup = 1
     *   ② 生产者 DuplicateHandle 每个槽的纹理句柄与 fence 句柄，
     *      写 texHandleDup[] / fenceHandleDup，最后置 dupReady = 1（release 语义）
     *   ③ 消费者看到 dupReady 才去 glImport*
     * </pre>
     */
    uint32_t consumerPid;               // 408：消费者写的自己的 pid（0 = 还没写）
    uint32_t consumerWantDup;           // 412：消费者请求"把 NT 句柄复制进我的进程"
    uint32_t dupReady;                  // 416：生产者已完成 DuplicateHandle
    uint32_t moSlotsReady;              // 420：消费者已用句柄建好 GL 纹理的槽位数（诊断用）
    uint64_t fenceHandleDup;            // 424：复制到消费者进程的 fence 句柄值
    uint64_t texHandleDup[kMaxSlots];   // 432：每槽复制到消费者进程的纹理句柄值（到 495）
    /*
     * 帧同步用的一对值（同一个共享 fence 上的单调时间线）：
     *   生产者写完槽 i → Signal(slotFenceValue[i])，消费者等这个值再采样；
     *   消费者采完槽 i → glSignalSemaphoreEXT(slotConsumerDone[i])，生产者复用该槽前等它。
     * 生产者侧是**有界的 CPU 探测**（GetCompletedValue + 超时等待），绝不会排一个
     * "对端已经死了、永远不会满足"的 GPU Wait —— 那会把 GPU 队列永久挂住。
     */
    uint64_t slotFenceValue[kMaxSlots];      // 496：生产者写完该槽后 Signal 的值
    uint64_t slotConsumerDone[kMaxSlots];    // 560：消费者读完该槽后 Signal 的值（0 = 还没读过）

    /*
     * v8 追加：**音频健康**（只追加，既有偏移一个都没动）。
     *
     * <p>为什么必须由子进程上报：子进程模式下**音频整条链都在子进程里**（WASAPI），
     * 游戏进程既没有音频缓冲、也没有声卡，所以"音频够不够、有没有断"
     * 上层除了问子进程没有第二个来源。以前状态环里只有"音频形态/声道/采样率"三个静态描述，
     * 于是音频真的断了、或者时钟已经因为音频停滞改成挂钟了，上层完全看不见。
     */
    uint32_t audioUnderruns;    // 624：累计声卡欠载次数（见 AudioSink::underrunCount）
    uint32_t audioQueuedMs;     // 628：还没播出去的音频时长（环形+设备）= 音频缓冲水位
    uint32_t audioStalled;      // 632：1 = 时钟已判定音频停滞、画面改走挂钟（声音可能已中断）
    uint32_t audioFailed;       // 636：1 = 音频链已失败/未参与（画面照走，但没有声音）
    uint32_t audioJitterRejects; // 640：播放位置读数抖动被拒次数（诊断用）
    uint32_t reserved2;         // 644：对齐占位

    /*
     * v9 追加：**子进程身份**（见 docs/host-uuid.md；只追加，既有偏移一个都没动）。
     *
     * <p>为什么需要它：原来的身份是"自增 sessionId + 只用于命名的随机 token + pid"，
     * 而 pid 会复用、sessionId 跨重启从 1 重来、token 也从不回传给子进程 ——
     * 于是事后无法确定"这份日志 / 这个 minidump / 这条状态环属于哪一次子进程实例"。
     *
     * <ul>
     *   <li>{@code sessionUuid} —— 逻辑会话级：自愈重启、换输出尺寸重建之后**保持不变**；</li>
     *   <li>{@code instanceUuid} —— 进程实例级：**每次 spawn 一个**，重启即换。</li>
     * </ul>
     *
     * <p>都是 37 字节（36 个字符 + NUL），小写带连字符的 UUID 文本。
     * Java 在 {@code awaitRing} 之后校验 instanceUuid 与自己 spawn 时传的那个一致，
     * 防止 pid 复用 / 旧段残留导致"映射到了别人的环"；
     * **老 exe 不写这两个字段 → 读出来是空串，Java 必须按"空 = 跳过校验"处理**。
     */
    char sessionUuid[37];       // 648
    char instanceUuid[37];      // 685
    uint8_t reserved3[6];       // 722：补齐到 728（保持整体 8 字节对齐）

    /*
     * v10 追加：**音频链健康**（只追加）。
     *
     * <p>v8 那组（欠载/水位）说的是"设备有没有东西播"，是**结果**；这三项说的是
     * "音频链本身还在不在干活"，是**原因**。2026-10-17 的事故里，音频链把读超时当片尾、
     * 静默停摆 26 秒，而上层只能看到"欠载"—— 完全看不出"链已经死了"。
     */
    uint32_t audioReadErrors;   // 728：累计读失败次数（网络中断 / rw_timeout 超时 —— 不是片尾）
    uint32_t audioReconnects;   // 732：累计音频链重连次数（重开输入并回到原位置）
    uint32_t audioRepairs;      // 736：上层"音频僵死自动修复"次数
    uint32_t reserved4;         // 740：对齐占位（结构到 744）

    /*
     * v11 追加：**缓冲水位与"等缓存"状态**（只追加）。
     *
     * <p>为什么需要：以前状态环只能回答"此刻有没有帧可出"（buffering@376），
     * 回答不了"还有多少秒的存货" —— 于是上层没法判断"这次卡顿是网络不够深还是客户端没在取帧"，
     * 也无法显示"正在预缓冲 x/y 秒"。
     *
     * <ul>
     *   <li>{@code packetBufferMs} —— 包队列里首尾 PTS 的跨度，就是"网络侧还有几秒没解码"；</li>
     *   <li>{@code frameBufferMs} —— 已解码没呈现的帧折算成时长；</li>
     *   <li>{@code bufferLevelMs} —— 上面两者之和，**唯一的缓冲水位**；</li>
     *   <li>{@code bufferTargetMs} —— 当前生效的目标水位（自适应，见 L4）；</li>
     *   <li>{@code gateActive} —— 1 = 正在"等缓存"（时钟与声卡已冻住，位置不再前进）；</li>
     *   <li>{@code gateCount}/{@code gateTotalMs} —— 进入次数与累计冻住时长；</li>
     *   <li>{@code consumerStallMs} —— 消费者（游戏进程）连续多久没取帧，0 = 正常；</li>
     *   <li>{@code prerollMs} —— 本次起播实际用的预缓冲门限（0 = 没等直接起播）。</li>
     * </ul>
     */
    uint32_t packetBufferMs;    // 744：包队列 PTS 跨度（毫秒，网络侧存货）
    uint32_t frameBufferMs;     // 748：解码后未呈现帧的时长（毫秒）
    uint32_t bufferLevelMs;     // 752：总水位 = 上两者之和
    uint32_t bufferTargetMs;    // 756：当前目标水位（自适应）
    uint32_t gateActive;        // 760：1 = 正在等缓存/等客户端取帧（时钟冻结）
    uint32_t gateCount;         // 764：累计进入闸门次数
    uint32_t consumerStallMs;   // 768：消费者连续未取帧时长（0 = 正常）
    uint32_t prerollMs;         // 772：起播预缓冲实际用它等了多久（等待时长，0 = 没等）
    uint64_t gateTotalMs;       // 776：累计被闸门冻住的时长
    uint32_t reserved5;         // 784：对齐占位
    uint32_t reserved6;         // 788：对齐占位（结构到 792）
};
#pragma pack(pop)

/** decodeMode 取值。 */
inline constexpr uint32_t kDecodeUnknown = 0;
inline constexpr uint32_t kDecodeHardware = 1;
inline constexpr uint32_t kDecodeSoftware = 2;
/** 硬解帧回拷 CPU 再转（设备没有可用的 VideoProcessor 时的兜底）。 */
inline constexpr uint32_t kDecodeHardwareCopy = 3;

// Java 侧按此偏移读
inline constexpr uint32_t kOffsetMagic = 0;
inline constexpr uint32_t kOffsetVersion = 4;
inline constexpr uint32_t kOffsetWidth = 8;
inline constexpr uint32_t kOffsetHeight = 12;
inline constexpr uint32_t kOffsetSlots = 16;
inline constexpr uint32_t kOffsetFramesPublished = 24;
inline constexpr uint32_t kOffsetFramesDropped = 32;
inline constexpr uint32_t kOffsetProducerFpsX100 = 40;
inline constexpr uint32_t kOffsetLastAcquireUs = 44;
inline constexpr uint32_t kOffsetMaxAcquireUs = 48;
inline constexpr uint32_t kOffsetAdapterLuidHigh = 52;
inline constexpr uint32_t kOffsetAdapterLuidLow = 56;
inline constexpr uint32_t kOffsetProducerPid = 60;
inline constexpr uint32_t kOffsetSlots0 = 64;
inline constexpr uint32_t kSlotStride = 16;
inline constexpr uint32_t kOffsetMediaPositionMs = 192;
inline constexpr uint32_t kOffsetMediaDurationMs = 200;
inline constexpr uint32_t kOffsetAudioSampleRate = 208;
inline constexpr uint32_t kOffsetPaused = 212;
inline constexpr uint32_t kOffsetLastSeekTargetMs = 216;
inline constexpr uint32_t kOffsetAudioMode = 224;
inline constexpr uint32_t kOffsetAudioChannels = 228;
inline constexpr uint32_t kOffsetAudioContentRate = 232;
inline constexpr uint32_t kOffsetEnded = 236;
inline constexpr uint32_t kOffsetSpatialAudio = 240;
inline constexpr uint32_t kOffsetSpatialFormat = 244;
inline constexpr uint32_t kSpatialFormatBytes = 64;
inline constexpr uint32_t kOffsetAudioDeviceName = 308;
inline constexpr uint32_t kAudioDeviceNameBytes = 64;
// v4 追加（**只在末尾加，既有偏移不变**：老客户端读老偏移依然正确）
inline constexpr uint32_t kOffsetDecodeMode = 372;
inline constexpr uint32_t kOffsetBuffering = 376;
inline constexpr uint32_t kOffsetSeekHealCount = 380;
inline constexpr uint32_t kOffsetDroppedLate = 384;
inline constexpr uint32_t kOffsetVideoPtsMs = 392;
// v6 追加（同样只在末尾加）
inline constexpr uint32_t kOffsetClockHealCount = 400;
// v7 追加：NT 句柄直投（内存对象路线）
inline constexpr uint32_t kOffsetConsumerPid = 408;
inline constexpr uint32_t kOffsetConsumerWantDup = 412;
inline constexpr uint32_t kOffsetDupReady = 416;
inline constexpr uint32_t kOffsetMoSlotsReady = 420;
inline constexpr uint32_t kOffsetFenceHandleDup = 424;
inline constexpr uint32_t kOffsetTexHandleDup = 432;
inline constexpr uint32_t kOffsetSlotFenceValue = 496;
inline constexpr uint32_t kOffsetSlotConsumerDone = 560;
// v8 追加：音频健康（子进程模式音频整条链都在子进程里，上层只能从这里知道）
inline constexpr uint32_t kOffsetAudioUnderruns = 624;
inline constexpr uint32_t kOffsetAudioQueuedMs = 628;
inline constexpr uint32_t kOffsetAudioStalled = 632;
inline constexpr uint32_t kOffsetAudioFailed = 636;
inline constexpr uint32_t kOffsetAudioJitterRejects = 640;
// v9 追加：子进程身份（两个 37 字节的小写 UUID 文本，NUL 结尾）
inline constexpr uint32_t kOffsetSessionUuid = 648;
inline constexpr uint32_t kOffsetInstanceUuid = 685;
inline constexpr uint32_t kUuidBytes = 37;
// v10 追加：音频链健康（读失败/重连/上层修复次数）
inline constexpr uint32_t kOffsetAudioReadErrors = 728;
inline constexpr uint32_t kOffsetAudioReconnects = 732;
inline constexpr uint32_t kOffsetAudioRepairs = 736;
// v11 追加：缓冲水位与"等缓存"状态
inline constexpr uint32_t kOffsetPacketBufferMs = 744;
inline constexpr uint32_t kOffsetFrameBufferMs = 748;
inline constexpr uint32_t kOffsetBufferLevelMs = 752;
inline constexpr uint32_t kOffsetBufferTargetMs = 756;
inline constexpr uint32_t kOffsetGateActive = 760;
inline constexpr uint32_t kOffsetGateCount = 764;
inline constexpr uint32_t kOffsetConsumerStallMs = 768;
inline constexpr uint32_t kOffsetPrerollMs = 772;
inline constexpr uint32_t kOffsetGateTotalMs = 776;

/** audioMode 取值。 */
inline constexpr uint32_t kAudioModeShared = 0;
inline constexpr uint32_t kAudioModeExclusive = 1;
inline constexpr uint32_t kAudioModePassthrough = 2;

inline constexpr uint32_t kMappingBytes = 4096;

static_assert(sizeof(SlotStatus) == 16, "SlotStatus 布局必须是 16 字节");
static_assert(offsetof(Status, slot) == 64, "slot 必须从 64 开始");
static_assert(offsetof(Status, mediaPositionMs) == kOffsetMediaPositionMs,
              "mediaPositionMs 偏移与 Java 侧不一致");
static_assert(offsetof(Status, lastSeekTargetMs) == kOffsetLastSeekTargetMs,
              "lastSeekTargetMs 偏移与 Java 侧不一致");
static_assert(offsetof(Status, audioMode) == kOffsetAudioMode, "audioMode 偏移与 Java 侧不一致");
static_assert(offsetof(Status, audioChannels) == kOffsetAudioChannels,
              "audioChannels 偏移与 Java 侧不一致");
static_assert(offsetof(Status, audioContentRate) == kOffsetAudioContentRate,
              "audioContentRate 偏移与 Java 侧不一致");
static_assert(offsetof(Status, ended) == kOffsetEnded, "ended 偏移与 Java 侧不一致");
static_assert(offsetof(Status, spatialAudio) == kOffsetSpatialAudio,
              "spatialAudio 偏移与 Java 侧不一致");
static_assert(offsetof(Status, spatialFormat) == kOffsetSpatialFormat,
              "spatialFormat 偏移与 Java 侧不一致");
static_assert(offsetof(Status, audioDeviceName) == kOffsetAudioDeviceName,
              "audioDeviceName 偏移与 Java 侧不一致");
static_assert(offsetof(Status, decodeMode) == kOffsetDecodeMode, "decodeMode 偏移与 Java 侧不一致");
static_assert(offsetof(Status, buffering) == kOffsetBuffering, "buffering 偏移与 Java 侧不一致");
static_assert(offsetof(Status, seekHealCount) == kOffsetSeekHealCount,
              "seekHealCount 偏移与 Java 侧不一致");
static_assert(offsetof(Status, droppedLate) == kOffsetDroppedLate,
              "droppedLate 偏移与 Java 侧不一致");
static_assert(offsetof(Status, videoPtsMs) == kOffsetVideoPtsMs,
              "videoPtsMs 偏移与 Java 侧不一致");
static_assert(offsetof(Status, clockHealCount) == kOffsetClockHealCount,
              "clockHealCount 偏移与 Java 侧不一致");
static_assert(offsetof(Status, consumerPid) == kOffsetConsumerPid,
              "consumerPid 偏移与 Java 侧不一致");
static_assert(offsetof(Status, dupReady) == kOffsetDupReady, "dupReady 偏移与 Java 侧不一致");
static_assert(offsetof(Status, fenceHandleDup) == kOffsetFenceHandleDup,
              "fenceHandleDup 偏移与 Java 侧不一致");
static_assert(offsetof(Status, texHandleDup) == kOffsetTexHandleDup,
              "texHandleDup 偏移与 Java 侧不一致");
static_assert(offsetof(Status, slotFenceValue) == kOffsetSlotFenceValue,
              "slotFenceValue 偏移与 Java 侧不一致");
static_assert(offsetof(Status, slotConsumerDone) == kOffsetSlotConsumerDone,
              "slotConsumerDone 偏移与 Java 侧不一致");
static_assert(sizeof(Status) <= kMappingBytes, "状态块不能超过映射大小");
static_assert(offsetof(Status, sessionUuid) == kOffsetSessionUuid,
              "sessionUuid 偏移与 Java 侧不一致");
static_assert(offsetof(Status, instanceUuid) == kOffsetInstanceUuid,
              "instanceUuid 偏移与 Java 侧不一致");
static_assert(sizeof(((Status*)0)->sessionUuid) == kUuidBytes, "UUID 字段必须是 37 字节");
static_assert(offsetof(Status, audioReadErrors) == kOffsetAudioReadErrors,
              "audioReadErrors 偏移与 Java 侧不一致");
static_assert(offsetof(Status, audioReconnects) == kOffsetAudioReconnects,
              "audioReconnects 偏移与 Java 侧不一致");
static_assert(offsetof(Status, audioRepairs) == kOffsetAudioRepairs,
              "audioRepairs 偏移与 Java 侧不一致");
static_assert(offsetof(Status, audioUnderruns) == kOffsetAudioUnderruns,
              "audioUnderruns 偏移与 Java 侧不一致");
static_assert(offsetof(Status, audioQueuedMs) == kOffsetAudioQueuedMs,
              "audioQueuedMs 偏移与 Java 侧不一致");
static_assert(offsetof(Status, audioStalled) == kOffsetAudioStalled,
              "audioStalled 偏移与 Java 侧不一致");
static_assert(offsetof(Status, audioFailed) == kOffsetAudioFailed,
              "audioFailed 偏移与 Java 侧不一致");
static_assert(offsetof(Status, packetBufferMs) == kOffsetPacketBufferMs,
              "packetBufferMs 偏移与 Java 侧不一致");
static_assert(offsetof(Status, bufferLevelMs) == kOffsetBufferLevelMs,
              "bufferLevelMs 偏移与 Java 侧不一致");
static_assert(offsetof(Status, gateActive) == kOffsetGateActive,
              "gateActive 偏移与 Java 侧不一致");
static_assert(offsetof(Status, consumerStallMs) == kOffsetConsumerStallMs,
              "consumerStallMs 偏移与 Java 侧不一致");
static_assert(offsetof(Status, gateTotalMs) == kOffsetGateTotalMs,
              "gateTotalMs 偏移与 Java 侧不一致");

} // namespace cineduct::spike

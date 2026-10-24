// ============================================================================
// WASAPI 音频输出（P2 · M3；P4 · 直通/独占）
//
// 为什么音频必须在子进程：音画同步的基准是"音频可听位置"。如果音频留在游戏进程、
// 画面在子进程，每次对齐都要跨进程校准时钟，误差与复杂度都不可接受。
// 放在同一个进程里，视频线程直接读这个时钟即可（见 main.cpp 的 pace）。
//
// **三种输出形态**（越往下越"直"，代价是控制能力越弱）：
//
//   1. Shared（默认，兜底）
//      共享模式 + 事件回调。设备格式由系统决定（常见 32-bit float / 48kHz），
//      我们统一以 S16 喂进去，这里负责 S16 → 设备格式的转换与增益。
//      代价：声音要经过系统混音器（可能重采样、按声道矩阵上混/下混）。
//
//   2. Exclusive（独占 PCM）
//      独占模式，格式由我们指定（16/24/32 位、多声道、44.1~192kHz）。
//      绕过系统混音器 = 位完美（bit-perfect）；但**仍然是解码后的 PCM**，
//      杜比全景声（对象音频）在这一步已经被我们解成声道了，不可能还原。
//
//   3. Passthrough（IEC 61937 码流直通 —— 硬件解码杜比全景声的唯一路径）
//      不碰音频解码器：把 E-AC-3 / AC-3 / TrueHD 的**压缩帧**按 IEC 61937 封装后
//      原样送进声卡，接收端（功放/回音壁/电视）用它的硬件解码器解出全景声。
//      格式取值规则见微软文档 "Representing Formats for IEC 61937 Transmissions"：
//      链路速率 = 内容采样率 × 4（48k → 192kHz），每样本恒 16 位。
//      代价：**码流不能改** —— 音量/静音/距离衰减全部失效（由功放控音量）。
//      总结：FUCK Microsoft
// ============================================================================
#pragma once

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace cineduct::host {

/**
 * 16 字节格式 GUID（对应 WAVEFORMATEXTENSIBLE.SubFormat）。
 *
 * 自己声明而不用 <guiddef.h>/<windows.h>：这个头文件被别处广泛包含，
 * 没必要因为一个格式标识就把整套 Windows 头拉进来（也便于将来移植）。
 */
struct FormatGuid {
    unsigned long data1;
    unsigned short data2;
    unsigned short data3;
    unsigned char data4[8];
};

/** 输出形态。 */
enum class AudioOutputMode {
    Shared,
    Exclusive,
    Passthrough,
};

/**
 * 一条音频链要设备以什么格式打开。
 *
 * 直通时这是**传输链路**的描述（2ch/192k/16bit 装着压缩码流），
 * 不是解码后的音频格式；PCM 模式（Shared/Exclusive）时它是解码输出格式。
 */
struct AudioFormat {
    bool passthrough = false;
    unsigned short channels = 2;
    unsigned long rate = 48000;
    unsigned short containerBits = 16;   // 容器位宽
    unsigned short validBits = 16;
    unsigned short blockAlign = 4;
    unsigned long channelMask = 0x3;     // KSAUDIO_SPEAKER_STEREO
    FormatGuid subFormat{};              // PCM 用 KSDATAFORMAT_SUBTYPE_PCM
    bool iec61937Struct = false;         // true → cbSize=34（WAVEFORMATEXTENSIBLE_IEC61937）
    unsigned long encodedRate = 0;       // 解码后的真实采样率（仅 iec61937Struct）
    unsigned short encodedChannels = 0;  // 解码后的真实声道数（仅 iec61937Struct）
    /**
     * 直通时"一个数据突发"的时长（毫秒）：AC-3 / E-AC-3 是 32ms，TrueHD(MAT) 是 20ms。
     *
     * <p>用途：独占缓冲要取成**整数个突发**。实测把缓冲取成 100ms（= 3.16 个 DD+ 突发）时，
     * 每轮 GetBuffer/ReleaseBuffer 都停在突发中间，Windows 音频引擎（audiokse.dll）
     * 会直接访问违例崩溃；取成整数个突发既符合驱动预期，也避免了这种边界。
     */
    unsigned long burstMs = 0;
};

struct AudioOpenOptions {
    AudioOutputMode mode = AudioOutputMode::Shared;
    /** 空 = 系统默认设备；"@3" = 第 3 个端点（--list-audio 的编号）；其它 = 名字子串。 */
    std::string deviceSelector;
    /** Exclusive / Passthrough 用（Shared 忽略，由系统混音格式决定）。 */
    AudioFormat format{};
    /** 直通时必须为 false：码流上不能施加增益。 */
    bool applyGain = true;
};

struct AudioSink {
    bool open(const AudioOpenOptions& options, std::string& error);
    void close();

    /** 设备要求的输出格式（open 之后有效）。 */
    int sampleRate() const { return this->rate; }
    int channels() const { return this->channelCount; }
    /** 每个"帧"的字节数（直通时 = blockAlign：2ch/16bit 为 4，TrueHD 8ch 为 16）。 */
    int frameBytes() const { return this->bytesPerFrame; }
    /** 解码后的内容采样率（直通时链路是 4× 内容或 192k，这个字段才是"音轨本身"的采样率）。 */
    int contentRate() const { return this->contentRateValue; }

    bool isPassthrough() const { return this->passthrough; }
    bool isExclusive() const { return this->exclusive; }
    /** 输出形态名（日志/状态汇报用）。 */
    const char* modeName() const;
    /** 实际打开的端点名（UTF-8）。 */
    const std::string& deviceName() const { return this->deviceFriendly; }
    /** 直通时音量控制不可用 —— 上层据此拒绝 volume 命令而不是假装成功。 */
    bool volumeLocked() const { return this->passthrough; }

    /**
     * 声卡输出是否已经彻底不可用（写入线程连续失败到放弃）。
     *
     * <p>上层看到它必须**改回挂钟节奏**继续出画面 —— 音频坏了绝不能把画面一起拖死。
     * （名字不叫 failed() 是因为 AudioSink 里已有同名的原子标志。）
     */
    bool outputFailed() const { return this->failed.load(); }

    /**
     * 端点的空间音效状态：1 = 已激活（Windows 正在做空间渲染/编码，即 Dolby Atmos 那套），
     * 0 = 未激活（普通多声道/立体声），2 = 查不到（系统/端点未上报）。
     */
    int spatialAudioState() const { return this->spatialState; }

    /** 当前激活的空间音效格式名（如 "Dolby Atmos for home theater"）；未激活为空串。 */
    const std::string& spatialAudioFormat() const { return this->spatialFormatText; }

    /**
     * 写入 S16 交错 PCM（Shared/Exclusive）或**原始码流字节**（Passthrough）。
     *
     * 环形缓冲满时**阻塞**直到有空间（音频不能丢样本，丢了就是爆音）。
     * 直通时这个阻塞就是整条链的限速器：声卡按链路速率取走数据，
     * 于是 demuxer 读多快就取决于声卡播多快。
     * 返回实际写入的字节数；关闭后返回 0。
     */
    int write(const uint8_t* data, int bytes);

    /** 已写出的帧数（单调递增；直通时 = 链路帧数，其时间推进与真实时间等价）。 */
    long long framesWritten() const { return this->written.load(); }

    /**
     * 真正**播出去**的帧数 = 累计写入 − 被 flush 丢掉的 − 还在设备缓冲里没播的。
     *
     * <p>音频时钟（main.cpp 的 audioMediaMs / anchorClock）必须用它，不能用
     * {@code framesWritten() - pendingFrames()}：后者把 flush 丢掉的那一段也算成"已经播过了"，
     * 于是每次 seek 音频时钟都会凭空前跳（最多一个设备缓冲）→ 按时钟对齐的视频跟着提前 → 音画错开。
     */
    long long playedFrames();

    /** 当前设备缓冲里还没播出去的帧数。 */
    /** 打印设备报的流延迟（共享/独占两条路都调；见 .cpp 里的说明）。 */
    void logDeviceLatency(void* audioClient);

    uint32_t pendingFrames();

    /**
     * **还没播出去的总时长**（毫秒）= 环形缓冲里的 + 设备缓冲里的。
     *
     * <p>★ 为什么不能用 `framesWritten() - playedFrames()` 代替：那个差值是
     * `丢弃累计 + 设备待播`，`discarded` 会**跨 seek 一直累加**（每次 flush 都把设备里
     * 那段没播的记进去），所以它根本不是"排队时长"，会越用越离谱。
     * 实测拿它去算"可听内容位置"，算出来的偏移直接错了一秒多（还带错符号），
     * 于是本该触发的纠正没触发、或者往反方向修。
     */
    double queuedDurationMs();

    /** **音频可听位置**（毫秒）—— 音画同步的基准时钟。 */
    double playedMs();

    /** 设备自己报的流延迟（毫秒；不含 HDMI/功放那段，只用于看量级）。 */
    int streamLatencyMs() const { return this->reportedLatencyMs; }

    /**
     * 累计"声卡欠载"次数 —— **设备缓冲已经见底、而我们这一刻也没东西可喂**，
     * 也就是耳朵听到的那一下静音空档。
     *
     * <p>判据与进程内引擎（Java 侧 `AudioSink.underruns`）对齐：
     * <ul>
     *   <li>只在"已经在正常播放中"才统计（写出不足 1 秒、暂停、刚 flush，缓冲本来就该是空的，不算）；</li>
     *   <li>必须**环形缓冲真的为 0** —— 缓冲里明明还有数据却断了，那是"写声卡的线程被 CPU 饿着"
     *       或设备被抢占，属于另一类问题（看 writeStalls / outputFailed），不能混进这个数；</li>
     *   <li>一次静音空档只记一次：喂上数据之前不再重复计数（否则 5ms 一轮的循环会把一次断音记成几百次）。</li>
     * </ul>
     */
    uint32_t underrunCount() const { return this->underruns.load(); }

    /**
     * "还没播出去的总时长"（毫秒）= 环形缓冲 + 设备缓冲 —— **音频缓冲水位**。
     *
     * <p>由写入线程每次循环更新成原子快照，读它不加锁（状态环每帧都要读，不能去抢 ringMutex）。
     */
    uint32_t queuedMsSnapshot() const { return this->queuedMsX10.load() / 10u; }

    /** 累计"播放位置读数抖动被拒"次数（诊断：>0 说明设备待播读数确实在抖）。 */
    int jitterRejectCount() const { return this->jitterRejects.load(); }

    void setGain(float value) { this->gain.store(value); }
    float gainValue() const { return this->gain.load(); }

    /**
     * 暂停/继续设备输出。
     *
     * 停掉设备后 {@link #playedMs()} 随之冻结 —— 于是"以音频时钟为准"的画面节奏
     * 天然一起停住，不需要额外处理视频侧的暂停。
     */
    void setPaused(bool paused);
    bool isPaused() const { return this->paused.load(); }

    /**
     * 清空待播音频（seek 用）。
     *
     * 必须连**设备缓冲里还没播出去的那一段**一起丢掉：那里最多有 200ms 的"seek 之前"的音频，
     * 不清就会在跳转后先放一段旧声音，音画立刻错位。
     * 做法是 Stop() → 清环形缓冲 → Start()。
     */
    void flush();

    /**
     * 让所有**当前阻塞在 write() 里**的线程立刻返回（不动设备、不丢数据）。
     *
     * <p>★ 为什么必须有它：链路要重启（`AudioChain::restart()` → `close()` → join 解码线程）时，
     * 解码线程可能正卡在 write() 的背压等待里（环形缓冲满，而**设备恰好停着**——暂停、或我们
     * 特意把设备按住等首帧）。此时 join 就是无限等待 —— 实测整条子进程直接卡死。
     * 收尾顺序必须是：`interruptWrites()` → 链路 `close()`（join 得掉）→ 需要的话再重建。
     *
     * <p>与 {@link #abortWrites()} 的区别：那个会把声卡标记成"永久停写"（收尾用），
     * 这个只打断**当前这一次**等待，之后声卡照常可用。
     */
    void interruptWrites();

    /**
     * 让所有阻塞在 {@link #write} 里的线程立刻返回（收尾第一步，**不关设备**）。
     *
     * <p>为什么收尾要先做这一步：音频解码线程可能正卡在 write() 的背压等待里
     * （环形缓冲满、而声卡又不消费），而 {@code AudioChain::close()} 要 join 那条线程 ——
     * 若先 join 后关声卡，就是"等一个永远等不到的线程"（退出时挂死）。
     * 顺序必须是：abortWrites() → 链路 close()（join 得掉）→ 声卡 close()。
     */
    void abortWrites();

    bool isOpen() const { return this->opened; }

private:
    void writerLoop();
    void pushRing(const uint8_t* data, size_t bytes);
    size_t popRing(uint8_t* dst, size_t maxBytes);
    /** 解析设备选择串并把 IMMDevice* 交出来（调用方负责 Release；失败置 0）。 */
    bool resolveDevice(const std::string& selector, void** outDevice, std::string& error);

    int rate = 48000;
    int channelCount = 2;
    int bytesPerFrame = 4;          // S16 交错
    int contentRateValue = 48000;   // 解码后的内容采样率
    int spatialState = 2;           // 空间音效状态：1=已激活 0=未激活 2=未知
    std::string spatialFormatText;  // 激活的空间音效格式名（如 "Dolby Atmos for home theater"）
    bool deviceIsFloat = true;      // 设备混音格式是不是 32-bit float
    bool exclusive = false;
    bool passthrough = false;
    bool copyVerbatim = false;      // 直通：字节原样进设备缓冲，不做任何转换/增益
    bool polling = false;           // 推模式（关掉事件回调）：部分驱动不支持事件驱动的独占流
    int burstBytes = 0;             // 直通时一个数据突发的字节数（0 = 不按突发对齐）
    unsigned short validBits = 16;
    bool opened = false;
    std::string deviceFriendly;

    std::vector<uint8_t> ring;
    size_t ringRead = 0;
    size_t ringUsed = 0;
    std::mutex ringMutex;
    std::condition_variable ringNotFull;
    std::condition_variable ringNotEmpty;

    std::thread writer;
    std::atomic<bool> quit{false};
    std::atomic<bool> failed{false};
    std::atomic<long long> written{0};
    /** 环形缓冲的"打断世代"：`interruptWrites()` 加一，阻塞中的 write() 立刻返回（见那里的说明）。 */
    std::atomic<uint64_t> ringEpoch{0};
    /** 被 flush()（设备 Stop 时缓冲里没播的部分）丢掉的帧数，见 playedFrames()。 */
    std::atomic<long long> discarded{0};
    /**
     * `playedFrames()` 的单调性/防前跳状态（见实现里的★说明）。
     *
     * <p>`frozenPlayed`：暂停那一刻钉住的已播帧数 —— 暂停期间设备是停的，
     * 待播读数（`GetCurrentPadding`）不可信，不能拿它重算；恢复后它又充当"不许前跳"的下限。
     */
    long long frozenPlayed = 0;
    bool hasFrozenPlayed = false;
    long long lastPlayedFrames = 0;
    bool hasPlayedSample = false;
    std::chrono::steady_clock::time_point lastPlayedAt{};
    /** 累计拒绝了多次"凭空前跳"（诊断：>0 说明设备待播读数确实在抖）。 */
    std::atomic<int> jitterRejects{0};

    /*
     * 欠载检测（见 underrunCount() 的判据）：
     *   warm              已经写够约 1 秒 → 才算"正常播放中"
     *   underruns         累计欠载次数（一次静音空档记一次）
     *   underrunLatched   本次空档已经记过账，喂上数据之前不再重复计
     */
    std::atomic<bool> warm{false};
    std::atomic<uint32_t> underruns{0};
    bool underrunLatched = false;
    /**
     * 欠载抑制窗口：这段时间内即使设备见底也不记账（刚 flush / 刚 Start / 刚 open）。
     *
     * <p>必须要有：seek 的 flush 会**故意**把设备里那 200ms 丢掉、暂停恢复也会让设备先空一下
     * —— 那不是"欠载"，是预期行为。不抑制的话计数会全是噪声。
     */
    std::atomic<uint64_t> graceUntilMs{0};
    /** 写入线程维护的免锁水位快照（×10 存整数，避免浮点原子）。 */
    std::atomic<uint32_t> queuedMsX10{0};

    std::atomic<float> gain{1.0f};
    int reportedLatencyMs = 0;
    std::atomic<bool> paused{false};

    // WASAPI 对象（用 void* 避免在头文件里拉进 Windows 头）
    void* client = nullptr;
    void* render = nullptr;
    void* eventHandle = nullptr;
    uint32_t bufferFrames = 0;
};

} // namespace cineduct::host

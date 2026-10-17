// ============================================================================
// 主时钟（音画同步的基准）
//
// 参考 CicadaPlayer 的做法：**音频在播时以音频为基准，音频不在时视频自己当基准**。
//   * CicadaPlayer：`mMasterClock = 首帧 pts(startTime) + 漂移修正(deltaTime) + 设备已播位置`
//     （`SuperMediaPlayer.cpp:3524-3537`，设备位置来自 `device_get_position()`），
//     并用"偏差 >100ms 就直接跳到参考值"来兜住抖动（`system_refer_clock.cpp:16-25`）。
//   * 这里：`playedFrames`（= 累计写入 − flush 丢弃 − 设备待播）直接就是"设备已播的采样数"，
//     与 CicadaPlayer 的 device_get_position() 是同一个量，所以公式一一对应。
//
// ★ 三条血泪教训原样保留（都不是理论问题，是踩过的）：
//   1. **必须用 playedFrames()，不能用 framesWritten() - pendingFrames()** ——
//      后者把 seek 时被 Stop() 丢掉的那一段算成"播过了"，音频时钟凭空前跳 → 画面跟着提前。
//   2. **暂停不算停滞**：暂停会把声卡 Stop 掉，时钟不动是预期行为。老版本没检查暂停状态，
//      暂停 3 秒后就被误判成"音频链死了"并永久切到挂钟。
//   3. **一次性降级必须配恢复路径**：时钟卡一下又回来时，要能回到"以音频为准"，
//      否则一次卡顿 = 永久音画不同步。
// ============================================================================
#pragma once

#include <chrono>
#include <cstdint>

#include "audio_sink.h"

namespace cineduct::host {

class MediaClock {
public:
    /** 时钟基准迁移（由 tick() 返回，上层据此打日志 / 拉音频追画面）。 */
    enum class Transition {
        None,
        ToWallClock,     // 音频时钟停滞太久 → 改用挂钟（画面继续）
        BackToAudio,     // 音频时钟又走起来了 → 回到音频基准
    };

    /**
     * ★ 构造时把"上次推进时刻"设成现在，别留默认值。
     *
     * <p>默认构造的 `steady_clock::time_point` 是时钟纪元（相当于几十年没推进），
     * 于是**第一次 tick() 就会判定"音频时钟停滞 3 秒"**并切到挂钟 ——
     * 实测症状：起播瞬间打出一行"音频时钟停滞超过 3 秒"，随后画面永久走挂钟、
     * 与音频再也对不上。这类"用默认值当时间戳"的坑很隐蔽，所以在这里显式初始化。
     */
    MediaClock();

    /** 音频是否参与（没音频就纯挂钟）。 */
    void attachAudio(AudioSink* sink);
    /** 音频彻底不可用（声卡失败/链路出错）：改用挂钟，并保持当前值不跳变。 */
    void dropAudioForGood();

    bool audioMaster() const;
    bool stalled() const { return this->audioStalled; }

    /** 把"媒体时间 mediaMs"锚到当前的设备已播位置（音频基准）。 */
    void anchor(double mediaMs);
    /** 把挂钟基准对到 mediaMs（无音频时用；也可用于音频恢复后对齐）。 */
    void anchorWall(double mediaMs);
    /** 标记"当前纪元尚未锚定"（seek/回源之后调用）：在锚定之前 now() 返回上次的值。 */
    void invalidate();
    bool anchored() const { return this->isAnchored; }

    void setPaused(bool paused);

    /** 主时钟当前位置（毫秒）。每帧可多次调用，无锁无系统调用。 */
    double now() const;
    /** 音频时钟的原始值（诊断用，不受停滞降级影响）。 */
    double audioNow() const;

    /** 每帧调一次：推进停滞/恢复判定。 */
    Transition tick();

    void reset();

private:
    double wallValue() const;
    bool audioUsable() const;

    AudioSink* sink = nullptr;
    bool hasAudio = false;
    bool audioStalled = false;
    bool isAnchored = false;
    bool paused = false;

    /** 音频锚点：某一刻的"设备已播帧数" ↔ 它对应的媒体时间。 */
    long long anchorFrames = 0;
    double anchorMediaMs = 0.0;
    /** 停滞判定用的游标。 */
    double stalledValue = 0.0;
    double lastAudioValue = 0.0;
    double lastValue = 0.0;
    std::chrono::steady_clock::time_point lastAdvance{};

    /** 挂钟基准：wallMediaMs 对应 wallBase 时刻。 */
    std::chrono::steady_clock::time_point wallBase{};
    double wallMediaMs = 0.0;
    bool wallValid = false;
    /** 暂停时挂钟要冻住（音频那条路由声卡自己冻）。 */
    double pausedValue = 0.0;
};

} // namespace cineduct::host

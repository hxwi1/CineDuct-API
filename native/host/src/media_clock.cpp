#include "media_clock.h"

namespace cineduct::host {

namespace {

/** 音频时钟多久不推进就判定"这条链已经不响了"。与老版本一致（3 秒）。 */
constexpr double kStallSeconds = 3.0;
/** 判定"推进了"的最小步进（毫秒）：小于它就是采样抖动，不算推进。 */
constexpr double kAdvanceEpsilonMs = 0.5;
/** 恢复判定：停滞之后又往前走了这么多毫秒，才认为音频真的回来了。 */
constexpr double kRecoverMs = 250.0;

} // namespace

MediaClock::MediaClock() {
    const auto now = std::chrono::steady_clock::now();
    this->lastAdvance = now;
    this->wallBase = now;
}

void MediaClock::attachAudio(AudioSink* audioSink) {
    this->sink = audioSink;
    this->hasAudio = audioSink != nullptr;
    this->audioStalled = false;
    this->lastAdvance = std::chrono::steady_clock::now();
    this->lastAudioValue = this->audioNow();
}

void MediaClock::dropAudioForGood() {
    if (!this->hasAudio) return;
    const double keep = this->now();
    this->hasAudio = false;
    this->audioStalled = false;
    this->anchorWall(keep);
    this->isAnchored = true;
}

bool MediaClock::audioUsable() const {
    return this->hasAudio && this->sink != nullptr && !this->audioStalled && this->sink->isOpen();
}

bool MediaClock::audioMaster() const {
    return this->audioUsable();
}

double MediaClock::audioNow() const {
    if (!this->hasAudio || this->sink == nullptr) return this->wallValue();
    /*
     * ★ 用 playedFrames()（已扣掉 flush 丢弃的那一段）。见头文件教训 1。
     */
    const long long played = this->sink->playedFrames();
    const double rate = this->sink->sampleRate() > 0 ? this->sink->sampleRate() : 48000.0;
    return this->anchorMediaMs + static_cast<double>(played - this->anchorFrames) * 1000.0 / rate;
}

double MediaClock::wallValue() const {
    if (!this->wallValid) return this->anchored() ? this->anchorMediaMs : 0.0;
    if (this->paused) return this->pausedValue;
    const auto now = std::chrono::steady_clock::now();
    return this->wallMediaMs
            + std::chrono::duration<double, std::milli>(now - this->wallBase).count();
}

double MediaClock::now() const {
    if (!this->isAnchored) return this->lastValue;
    if (this->audioUsable()) return this->audioNow();
    return this->wallValue();
}

void MediaClock::anchor(double mediaMs) {
    if (this->sink != nullptr) this->anchorFrames = this->sink->playedFrames();
    this->anchorMediaMs = mediaMs;
    this->isAnchored = true;
    /*
     * 注意：这里**不清 audioStalled**。
     *
     * 重新锚定并不能证明"音频又在走了"（seek 之后的锚定只是把坐标对上）。
     * 真回来了由 tick() 的恢复分支判定（见头文件教训 3）；清掉反而会让
     * "已经判定死掉的音频链"再骗我们 3 秒的挂钟时间。
     */
    this->lastValue = mediaMs;
    this->lastAudioValue = this->audioNow();
    this->stalledValue = this->lastAudioValue;
    this->lastAdvance = std::chrono::steady_clock::now();
    // 挂钟也对到同一位置：音频中途失效时切过去不会跳变
    this->anchorWall(mediaMs);
}

void MediaClock::anchorWall(double mediaMs) {
    this->wallBase = std::chrono::steady_clock::now();
    this->wallMediaMs = mediaMs;
    this->wallValid = true;
    this->pausedValue = mediaMs;
    /*
     * ★ 必须置 isAnchored：now() 在"未锚定"时返回上一次的值（那是给 seek 之后、
     * 首帧到达之前用的占位）。曾经漏了这一句，结果纯挂钟模式（--no-audio）下
     * 时钟永远停在 0 —— 每帧都判定"太早"，画面一帧一帧地等掉，
     * 实测 4 秒只出 1 帧、解码 26 帧（帧队列被卡满的连锁反应）。
     */
    this->isAnchored = true;
}

void MediaClock::invalidate() {
    this->isAnchored = false;
    this->audioStalled = false;
}

void MediaClock::setPaused(bool newPaused) {
    if (newPaused == this->paused) return;
    if (newPaused) {
        this->pausedValue = this->wallValue();
        this->paused = true;
    } else {
        // 恢复：挂钟从冻住的那个值继续
        this->paused = false;
        this->anchorWall(this->pausedValue);
        /*
         * ★ 恢复时**必须把停滞判定的计时器重新计起**。
         *
         * <p>暂停期间 tick() 直接返回（暂停不算停滞 ✓），但 `lastAdvance` 停在了"暂停前那一刻"。
         * 于是暂停一久（>3 秒），恢复后的**第一次 tick 就会判定"音频时钟停滞 3 秒"**，
         * 立刻切到挂钟节奏 —— 而挂钟一旦接手，画面就按挂钟走、声音按自己的位置走，
         * 两边从恢复那一刻起就靠不住地对齐（这正是"恢复播放时音画错开"的一个入口）。
         */
        this->lastAdvance = std::chrono::steady_clock::now();
        this->lastAudioValue = this->audioNow();
    }
}

MediaClock::Transition MediaClock::tick() {
    this->lastValue = this->now();
    if (!this->hasAudio || this->sink == nullptr || this->sink->isOpen() == false) {
        return Transition::None;
    }
    /*
     * ★ 教训 2：暂停时时钟本来就不该走 —— 这不是"停滞"，绝不能因此把音频判死。
     */
    if (this->paused) return Transition::None;

    const double value = this->audioNow();
    if (value > this->lastAudioValue + kAdvanceEpsilonMs) {
        this->lastAudioValue = value;
        this->lastAdvance = std::chrono::steady_clock::now();
    }

    if (!this->audioStalled) {
        const double idle = std::chrono::duration<double>(
                std::chrono::steady_clock::now() - this->lastAdvance).count();
        if (idle > kStallSeconds) {
            this->audioStalled = true;
            this->stalledValue = value;
            this->anchorWall(value);   // 挂钟从这里接着走，位置不跳
            return Transition::ToWallClock;
        }
        return Transition::None;
    }

    /*
     * ★ 教训 3：恢复路径。音频又走了（比停滞那一刻前进 >250ms）就回到音频基准，
     * 并把挂钟重新对准它 —— 否则一次卡顿 = 永久音画不同步。
     */
    if (value > this->stalledValue + kRecoverMs) {
        this->audioStalled = false;
        this->lastAudioValue = value;
        this->lastAdvance = std::chrono::steady_clock::now();
        this->anchorWall(value);
        return Transition::BackToAudio;
    }
    return Transition::None;
}

void MediaClock::reset() {
    this->isAnchored = false;
    this->audioStalled = false;
    this->paused = false;
    this->wallValid = false;
    this->anchorFrames = 0;
    this->anchorMediaMs = 0.0;
    this->lastValue = 0.0;
    this->lastAudioValue = 0.0;
    this->stalledValue = 0.0;
    this->pausedValue = 0.0;
}

} // namespace cineduct::host

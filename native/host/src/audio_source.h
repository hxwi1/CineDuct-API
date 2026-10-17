// ============================================================================
// 音频解码源（P2 · M3）
//
// 一路音频链 → FFmpeg 解码 → swr 重采样成"设备要的格式"（S16 交错）→ 写进 WASAPI 环形缓冲。
//
// 线程模型：本类自带一条解码线程，写 sink 时靠 sink 的背压（满则阻塞）自然限速；
// 视频线程不碰这里，只读 sink 的"可听位置"当时钟 —— 与 Java 兜底引擎的模型一致。
// ============================================================================
#pragma once

#include <atomic>
#include <cstdint>
#include <string>
#include <thread>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libswresample/swresample.h>
}

#include "audio_chain.h"
#include "audio_sink.h"
#include "ffmpeg_dyn.h"

namespace cineduct::host {

struct AudioSource : public AudioChain {
    bool open(const FfmpegApi& api, AudioSink& sink, const std::string& url,
              const std::string& headers, const std::string& userAgent,
              std::string& error) override;
    void start() override;
    /** 重头再放（循环播放用）：关掉当前解码线程后重新打开同一条链。 */
    bool restart(std::string& error) override;
    /**
     * 定位到指定媒体时间（毫秒）。
     *
     * <p><b>非阻塞</b>：这里只登记请求，真正的定位由**解码线程自己**在下一轮开头执行。
     * 为什么必须这样：seek 从视频线程发起，而 FFmpeg 的 demuxer/解码器上下文不是线程安全的；
     * 更要命的是解码线程可能正卡在声卡背压里（暂停时环形缓冲不再排空），
     * 视频线程一旦去等它，整个子进程就死锁 —— 表现正是"没声音 + 画面停在 1~2 帧，游戏却完全正常"。
     */
    bool seek(long long ms, std::string& error) override;
    void close() override;

    long long durationMs() const override { return this->duration; }
    bool finished() const override { return this->ended.load(); }
    /** 解码线程是否出错退出。 */
    bool failed() const override { return this->errorFlag.load(); }
    uint64_t seekAppliedCount() const override { return this->seekApplied.load(); }
    /**
     * 最新写出样本的内容时间（毫秒）。见 AudioChain::writtenContentMs 的说明 ——
     * 它是"能不能发现音画不同步"的关键。
     */
    double writtenContentMs() const override { return this->lastContentMs.load(); }
    const char* kind() const override { return "decode"; }

    uint32_t readErrorCount() const override { return this->readErrors.load(); }
    uint32_t reconnectCount() const override { return this->reconnects.load(); }
    void requestReopen() override { this->reopenRequested.store(true); }

    int sourceChannels() const { return this->srcChannels; }
    int sourceSampleRate() const { return this->srcRate; }
    const std::string& codecName() const { return this->codec; }

private:
    void decodeLoop();
    /** 解码线程执行实际定位（只有它允许碰 FFmpeg 上下文）。 */
    void applySeek(long long ms);
    /** 打开输入 + 找流 + 解码器 + 重采样器（open 与"重连"共用；不含线程）。 */
    bool openPath(std::string& error);
    /** 释放 FFmpeg 原生对象（不碰线程）。 */
    void closeNative();
    /**
     * 重连：关掉原生对象 → 重新打开 → **seek 回上次喂到的内容位置**。
     *
     * <p>只在解码线程里调用。返回 false 时对象可能处于"已关闭"状态，调用方按失败处理。
     */
    bool reconnect(std::string& error);

    const FfmpegApi* api = nullptr;
    AudioSink* sink = nullptr;

    AVFormatContext* fmt = nullptr;
    AVCodecContext* codecCtx = nullptr;
    SwrContext* swr = nullptr;
    AVPacket* packet = nullptr;
    AVFrame* frame = nullptr;
    int streamIndex = -1;

    long long duration = -1;
    std::string url;
    std::string headers;
    /** 见 AudioChain::open —— 音频链另开一路输入，直播 CDN 必须给 UA，否则 403。 */
    std::string userAgent;
    int srcChannels = 2;
    int srcRate = 48000;
    std::string codec = "?";

    std::thread worker;
    std::atomic<bool> quit{false};
    std::atomic<bool> ended{false};
    std::atomic<bool> errorFlag{false};
    /** 待执行的定位请求（-1 = 无）；只由解码线程消费。 */
    std::atomic<long long> requestSeekMs{-1};
    /**
     * 已执行的定位请求数（见 AudioChain::seekAppliedCount）。
     *
     * <p>顺带承担另一个作用：只要它落后于 requestSeekMs，解码线程就**不往声卡写**——
     * 那些样本是"seek 之前"的位置解出来的，写进去就是在新的时间点上放旧声音。
     */
    std::atomic<uint64_t> seekApplied{0};
    /**
     * 最新写进声卡的那个样本的内容时间（媒体时间轴，毫秒）。
     *
     * <p>每解出一帧就把它推到"该帧 PTS + 帧时长"—— 即"我已经把内容喂到哪儿了"。
     * 上层拿它减去排队未播的时长，就得到"耳朵此刻听到的内容位置"。
     */
    std::atomic<double> lastContentMs{-1.0};

    /** 上一次成功的定位目标（重连时的回退位置；{@link #lastContentMs} 未知时用它）。 */
    long long lastSeekTargetMs = 0;
    /** 读失败 / 重连计数（上报状态环，见 AudioChain::readErrorCount）。 */
    std::atomic<uint32_t> readErrors{0};
    std::atomic<uint32_t> reconnects{0};
    /** 上层请求"重开输入"（片尾待命或读失败僵死时）。 */
    std::atomic<bool> reopenRequested{false};
};

} // namespace cineduct::host

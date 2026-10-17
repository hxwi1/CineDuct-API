// ============================================================================
// 视频帧队列（解码线程 → 呈现线程）
//
// 与包队列同构（有界 + 等待可打断 + 世代标记），但**容量单位是帧**，而且刻意很小（默认 3）。
//
// 为什么这么小：这一段的延迟直接变成"操控手感"。队列越深，暂停/seek 之后要清掉的
// 陈旧帧就越多，画面也越"迟"。CicadaPlayer 的渲染器也是同一个量级
// （`GLRender` 的 MAX_IN_SIZE = 3）。
//
// ★ 满队列时**不丢帧**，而是让解码线程等（可被打断）。
//   "丢帧"这件事只在呈现线程按主时钟判定（late 太多才丢，见 video_pipeline.cpp）——
//   否则暂停时队列一满就开始丢，恢复播放后画面会缺一段（一次暂停 = 一次丢帧）。
// ============================================================================
#pragma once

#include <condition_variable>
#include <cstdint>
#include <deque>
#include <functional>
#include <mutex>

extern "C" {
#include <libavutil/frame.h>
}

#include "ffmpeg_dyn.h"

namespace cineduct::host {

class FrameQueue {
public:
    enum class PushResult {
        Ok,
        Yielded,
        Aborted,
    };
    enum class PopResult {
        Ok,
        Yielded,   // 呈现侧有事要办（seek 待处理 / 要停了）
        Empty,     // 暂时没帧。**不是 EOF**：解码线程还在跑，等一会儿就有
        Eof,       // 没帧了，而且上游（包队列）已经到片尾
        Aborted,
    };

    /** @param ffmpeg 动态符号表（析构时要用 av_frame_free 放残留的帧）。 */
    explicit FrameQueue(const FfmpegApi& ffmpeg, size_t maxFrames = 3);
    ~FrameQueue();

    FrameQueue(const FrameQueue&) = delete;
    FrameQueue& operator=(const FrameQueue&) = delete;

    void setYieldPredicates(std::function<bool()> producerYield,
                            std::function<bool()> consumerYield);

    /** 入队；Ok 时帧的引用被 move 走。 */
    PushResult push(AVFrame* frame, uint64_t generation);

    /** 出队；自动丢弃 generation < minGeneration 的陈旧帧。 */
    PopResult pop(AVFrame* out, uint64_t minGeneration, uint64_t* generation);

    /**
     * 等一小会儿新帧（呈现线程空转用，避免 2ms 轮询烧 CPU）。
     *
     * @return true = 可能有帧了（或已到片尾/被终止），调用方应重新 pop
     */
    bool waitForData(int timeoutMs);

    void flush();
    void setEof();
    /**
     * 清掉"片尾"标记（seek 之后必须清）。
     *
     * <p>不清的后果很具体：seek 之后队列会瞬间"空 + 片尾"，呈现侧立刻当成又放完了 ——
     * 开着循环（loop 1）时就是无限 seek 0 的死循环。
     */
    void clearEof();
    void abort();

    size_t size() const;

private:
    struct Entry {
        AVFrame* frame = nullptr;
        uint64_t generation = 0;
    };

    /** 动态符号表（析构时要靠它放残留的帧）。 */
    const FfmpegApi* api = nullptr;
    mutable std::mutex mutex;
    std::condition_variable notEmpty;
    std::condition_variable notFull;
    std::deque<Entry> entries;
    size_t maxFrames;
    bool eofFlag = false;
    bool aborted = false;
    std::function<bool()> producerYield;
    std::function<bool()> consumerYield;
};

} // namespace cineduct::host

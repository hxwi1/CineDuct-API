// ============================================================================
// 视频包队列（demux 线程 → 解码线程）
//
// 参考 CicadaPlayer 的两层做法（`avFormatDemuxer` 的 MAX_QUEUE_SIZE=60 阻塞读线程、
// `media_packet_queue` 的"取走时唤醒"），但补上它没做的三件事：
//
//   1. **有界**：包数与字节数双上限。demux 线程填满就睡，绝不让"网络比解码快"变成内存爆掉
//      （老版本是单线程串行，没有队列，也就没有这个风险 —— 拆线程之后这是必须补的第一道闸）。
//   2. **等待可被打断**：所有阻塞等待都能被 seek / 停止唤醒。这一条是**必须**的，不是优化：
//      seek 要在三个线程上各做一段（demux 定位 / 解码器 flush / 呈现侧清帧），
//      若队列等待不可打断，就会出现"暂停时队列满 → 解码线程卡在 push 里 → 谁也不去执行 seek"。
//   3. **世代标记（generation）**：每个包带上"它是第几次 seek 之后读出来的"。
//      消费者只认自己已完成到的那一代，跨代的包直接丢 —— 于是"seek 之后先吐出来的几个旧包"
//      不可能被解成画面（老版本靠 skipUntilPtsMs 猜时间，遇到 B 帧重排就会漏）。
// ============================================================================
#pragma once

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <functional>
#include <mutex>
#include <string>

extern "C" {
#include <libavcodec/packet.h>
}

#include "ffmpeg_dyn.h"

namespace cineduct::host {

class PacketQueue {
public:
    /** push 的结果。 */
    enum class PushResult {
        Ok,        // 已入队
        Yielded,   // 生产侧有事要办（seek 待处理 / 要停了）—— 调用方应立刻返回主循环
        Aborted,   // 队列已终止
    };
    /** pop 的结果。 */
    enum class PopResult {
        Ok,        // 拿到了一个包（调用方负责 av_packet_unref）
        Yielded,   // 消费侧有事要办（seek 待处理 / 要停了）
        Eof,       // 队列已空且 demux 已到片尾
        Aborted,   // 队列已终止
    };

    /**
     * @param ffmpeg     动态符号表：**队列自己也持有它**（析构时要用 av_packet_free 放残留的包，
     *                   而 FFmpeg 是 LoadLibrary 来的，不能直接调）
     * @param maxPackets 包数上限（默认 60，与 CicadaPlayer 的 demux 队列一致 ≈ 2 秒）
     * @param maxBytes   字节上限（默认 8MB）—— 4K 的关键帧能到几百 KB，只按包数限不够
     */
    explicit PacketQueue(const FfmpegApi& ffmpeg, size_t maxPackets = 60,
                         size_t maxBytes = 8u * 1024u * 1024u);
    ~PacketQueue();

    PacketQueue(const PacketQueue&) = delete;
    PacketQueue& operator=(const PacketQueue&) = delete;

    /**
     * 设置两侧的"该让路了"判据。
     *
     * <p>由流水线提供：生产侧是"demux 阶段有 seek 待处理"，消费侧是"解码阶段有 seek 待处理"。
     * 都在停止时返回 true。
     */
    void setYieldPredicates(std::function<bool()> producerYield,
                            std::function<bool()> consumerYield);

    /** 入队（Ok 时包的引用被 move 走，调用方不必 unref）。 */
    PushResult push(AVPacket* packet, uint64_t generation);

    /** 出队：自动丢弃 generation < minGeneration 的陈旧包。 */
    PopResult pop(AVPacket* out, uint64_t minGeneration, uint64_t* generation);

    /** 清空（seek 时用），并复位 EOF 标记。 */
    void flush();

    /** demux 到达片尾。 */
    void setEof();

    /** 终止所有等待（退出时用）。 */
    void abort();

    size_t size() const;
    size_t bytes() const;
    bool eof() const;

private:
    struct Entry {
        AVPacket* packet = nullptr;
        uint64_t generation = 0;
    };

    bool producerShouldYield() const;
    bool consumerShouldYield() const;
    const FfmpegApi* api = nullptr;

    mutable std::mutex mutex;
    std::condition_variable notEmpty;
    std::condition_variable notFull;
    std::deque<Entry> entries;
    size_t maxPackets;
    size_t maxBytes;
    size_t usedBytes = 0;
    bool eofFlag = false;
    bool aborted = false;
    std::function<bool()> producerYield;
    std::function<bool()> consumerYield;
};

} // namespace cineduct::host

#include "packet_queue.h"

#include <chrono>

namespace cineduct::host {

namespace {

/**
 * 队列等待的"心跳"间隔。
 *
 * <p>为什么不用纯谓词等待（wait + predicate）：谓词只在 notify 时被重新求值，
 * 而"该让路了"这件事是**另一个线程拿原子量改的**，没有人会为它 notify。
 * 20ms 的轮询把 seek 的响应延迟压到人感觉不到的量级，同时等待期间不烧 CPU。
 */
constexpr auto kWaitTick = std::chrono::milliseconds(20);

} // namespace

PacketQueue::PacketQueue(const FfmpegApi& ffmpeg, size_t maxPacketCount, size_t maxByteCount,
                         int maxSpan)
    : api(&ffmpeg),
      maxPackets(maxPacketCount == 0 ? 1 : maxPacketCount),
      maxBytes(maxByteCount == 0 ? 1 : maxByteCount),
      maxSpanMs(maxSpan) {}

PacketQueue::~PacketQueue() {
    // 析构时把残留的包放掉：队列持有的是**所有权**，不是借用
    std::lock_guard<std::mutex> lock(this->mutex);
    for (Entry& e : this->entries) {
        if (e.packet != nullptr && this->api != nullptr) this->api->av_packet_free(&e.packet);
    }
    this->entries.clear();
}

void PacketQueue::setYieldPredicates(std::function<bool()> producer,
                                     std::function<bool()> consumer) {
    std::lock_guard<std::mutex> lock(this->mutex);
    this->producerYield = std::move(producer);
    this->consumerYield = std::move(consumer);
}

bool PacketQueue::producerShouldYield() const {
    return this->aborted || (this->producerYield && this->producerYield());
}

bool PacketQueue::consumerShouldYield() const {
    return this->aborted || (this->consumerYield && this->consumerYield());
}

void PacketQueue::setSpanTargetMs(int ms) {
    std::lock_guard<std::mutex> lock(this->mutex);
    this->maxSpanMs = ms;
    this->notFull.notify_all();
}

int PacketQueue::spanTargetMs() const {
    std::lock_guard<std::mutex> lock(this->mutex);
    return this->maxSpanMs;
}

int PacketQueue::spanMs() const {
    std::lock_guard<std::mutex> lock(this->mutex);
    if (this->entries.empty() || this->frontPtsMs < 0 || this->lastPushedPtsMs < 0) return 0;
    const long long span = this->lastPushedPtsMs - this->frontPtsMs;
    if (span <= 0) return 0;
    return span > 600000 ? 600000 : static_cast<int>(span);
}

PacketQueue::PushResult PacketQueue::push(AVPacket* packet, uint64_t generation, long long ptsMs) {
    if (packet == nullptr || this->api == nullptr) return PushResult::Aborted;
    const size_t bytes = static_cast<size_t>(packet->size > 0 ? packet->size : 0);

    std::unique_lock<std::mutex> lock(this->mutex);
    for (;;) {
        if (this->aborted) return PushResult::Aborted;
        // 让路优先于入队：seek 要执行时必须先让 demux 线程走开（它拿着 AVFormatContext）
        if (this->producerYield && this->producerYield()) return PushResult::Yielded;
        const bool byteRoom = this->usedBytes + bytes <= this->maxBytes || this->entries.empty();
        bool spanRoom = true;
        if (this->maxSpanMs > 0 && !this->entries.empty()
            && this->frontPtsMs >= 0 && this->lastPushedPtsMs >= 0 && ptsMs >= 0) {
            /*
             * 跨度上限：**这才是"提前缓存 N 秒"的开关**。
             *
             * <p>以前只按"60 包 / 8MB"限深，于是网络侧最多领先播放位置 1 秒左右 ——
             * 一次网络抖动或解码停顿就直接抽干，画面卡住等数据。
             * 现在按**媒体时长**限深：队列里首尾 PTS 差到 maxSpanMs 才算满，
             * demux 停下等消费，于是"已经下到本地、还没解码"的数据天然有这么长。
             *
             * <p>注意允许"新包让跨度略微超出"（用 max 推进的 lastPushedPtsMs 才是准的），
             * 所以判定用 lastPushedPtsMs 与外层 ptsMs 的较大值，避免关键帧被卡在门外。
             */
            const long long newest = this->lastPushedPtsMs > ptsMs ? this->lastPushedPtsMs : ptsMs;
            spanRoom = (newest - this->frontPtsMs) <= this->maxSpanMs;
        }
        const bool room = this->entries.size() < this->maxPackets && byteRoom && spanRoom;
        if (room) break;
        this->notFull.wait_for(lock, kWaitTick);
    }

    // 进队列的是"拿所有权"：把引用 move 进我们自己的包对象里
    AVPacket* owned = this->api->av_packet_alloc();
    if (owned == nullptr) {
        this->api->av_packet_unref(packet);
        return PushResult::Aborted;
    }
    this->api->av_packet_move_ref(owned, packet);
    /*
     * 记录跨度端点（必须在 push_back 之前判断"队列原本是不是空"）。
     *
     * <p>队首 PTS 只在队列原本为空时取；否则保持不动（它就是队首那个包的时间）。
     * lastPushed 单调取大：B 帧会让 PTS 轻微回退，用 max 才不会把跨度算短。
     */
    if (this->entries.empty()) this->frontPtsMs = ptsMs;
    if (ptsMs >= 0 && ptsMs > this->lastPushedPtsMs) this->lastPushedPtsMs = ptsMs;
    this->entries.push_back(Entry{owned, generation, ptsMs});
    this->usedBytes += bytes;
    lock.unlock();
    this->notEmpty.notify_one();
    return PushResult::Ok;
}

PacketQueue::PopResult PacketQueue::pop(AVPacket* out, uint64_t minGeneration,
                                        uint64_t* generation) {
    if (this->api == nullptr) return PopResult::Aborted;
    std::unique_lock<std::mutex> lock(this->mutex);
    for (;;) {
        if (this->aborted) return PopResult::Aborted;
        if (this->consumerYield && this->consumerYield()) return PopResult::Yielded;
        if (this->entries.empty()) {
            if (this->eofFlag) return PopResult::Eof;
            this->notEmpty.wait_for(lock, kWaitTick);
            continue;
        }
        Entry entry = this->entries.front();
        this->entries.pop_front();
        this->usedBytes -= static_cast<size_t>(entry.packet->size > 0 ? entry.packet->size : 0);
        /*
         * 维护跨度端点：队首换人了，跨度就从新队首算起。
         *
         * <p>这一步是"水位读数准不准"的关键 —— 不更新的话 `spanMs()` 会一直报
         * 最初那个包的 PTS，水位只涨不落，闸门就永远退不出去。
         */
        if (this->entries.empty()) {
            this->frontPtsMs = -1;
            this->lastPushedPtsMs = -1;
        } else {
            this->frontPtsMs = this->entries.front().ptsMs;
            if (this->frontPtsMs < 0) {
                // 队首没有 PTS（少见）：往后找一个有的，免得整个水位断掉
                for (const Entry& e : this->entries) {
                    if (e.ptsMs >= 0) {
                        this->frontPtsMs = e.ptsMs;
                        break;
                    }
                }
            }
        }
        /*
         * 陈旧的包（seek 之前读出来的）在这里丢掉。
         *
         * 为什么必须在**取**的时候丢、而不是靠"seek 时清队列"：清队列与解码线程的
         * flush 之间存在窗口 —— demux 已经定位完、解码器还没 flush 时，队列里可能同时
         * 有新老两代的包。靠世代判定就不会把老包解成画面。
         */
        if (entry.generation < minGeneration) {
            this->api->av_packet_free(&entry.packet);
            continue;
        }
        this->api->av_packet_move_ref(out, entry.packet);
        this->api->av_packet_free(&entry.packet);
        if (generation != nullptr) *generation = entry.generation;
        lock.unlock();
        this->notFull.notify_one();
        return PopResult::Ok;
    }
}

void PacketQueue::flush() {
    std::deque<Entry> dropped;
    {
        std::lock_guard<std::mutex> lock(this->mutex);
        dropped.swap(this->entries);
        this->usedBytes = 0;
        this->frontPtsMs = -1;
        this->lastPushedPtsMs = -1;
        this->eofFlag = false;
    }
    if (this->api != nullptr) {
        for (Entry& e : dropped) {
            if (e.packet != nullptr) this->api->av_packet_free(&e.packet);
        }
    }
    this->notFull.notify_all();
}

void PacketQueue::setEof() {
    std::lock_guard<std::mutex> lock(this->mutex);
    this->eofFlag = true;
    this->notEmpty.notify_all();
}

void PacketQueue::abort() {
    std::lock_guard<std::mutex> lock(this->mutex);
    this->aborted = true;
    this->notEmpty.notify_all();
    this->notFull.notify_all();
}

size_t PacketQueue::size() const {
    std::lock_guard<std::mutex> lock(this->mutex);
    return this->entries.size();
}

size_t PacketQueue::bytes() const {
    std::lock_guard<std::mutex> lock(this->mutex);
    return this->usedBytes;
}

bool PacketQueue::eof() const {
    std::lock_guard<std::mutex> lock(this->mutex);
    return this->eofFlag;
}

} // namespace cineduct::host

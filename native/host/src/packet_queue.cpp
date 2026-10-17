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

PacketQueue::PacketQueue(const FfmpegApi& ffmpeg, size_t maxPacketCount, size_t maxByteCount)
    : api(&ffmpeg),
      maxPackets(maxPacketCount == 0 ? 1 : maxPacketCount),
      maxBytes(maxByteCount == 0 ? 1 : maxByteCount) {}

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

PacketQueue::PushResult PacketQueue::push(AVPacket* packet, uint64_t generation) {
    if (packet == nullptr || this->api == nullptr) return PushResult::Aborted;
    const size_t bytes = static_cast<size_t>(packet->size > 0 ? packet->size : 0);

    std::unique_lock<std::mutex> lock(this->mutex);
    for (;;) {
        if (this->aborted) return PushResult::Aborted;
        // 让路优先于入队：seek 要执行时必须先让 demux 线程走开（它拿着 AVFormatContext）
        if (this->producerYield && this->producerYield()) return PushResult::Yielded;
        const bool room = this->entries.size() < this->maxPackets
                && (this->usedBytes + bytes <= this->maxBytes || this->entries.empty());
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
    this->entries.push_back(Entry{owned, generation});
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

#include "frame_queue.h"

#include <chrono>

namespace cineduct::host {

namespace {

constexpr auto kWaitTick = std::chrono::milliseconds(20);

} // namespace

FrameQueue::FrameQueue(const FfmpegApi& ffmpeg, size_t capacity)
    : api(&ffmpeg), maxFrames(capacity == 0 ? 1 : capacity) {}

FrameQueue::~FrameQueue() {
    std::lock_guard<std::mutex> lock(this->mutex);
    for (Entry& e : this->entries) {
        if (e.frame != nullptr && this->api != nullptr) this->api->av_frame_free(&e.frame);
    }
    this->entries.clear();
}

void FrameQueue::setYieldPredicates(std::function<bool()> producer,
                                    std::function<bool()> consumer) {
    std::lock_guard<std::mutex> lock(this->mutex);
    this->producerYield = std::move(producer);
    this->consumerYield = std::move(consumer);
}

FrameQueue::PushResult FrameQueue::push(AVFrame* frame, uint64_t generation) {
    if (frame == nullptr || this->api == nullptr) return PushResult::Aborted;

    std::unique_lock<std::mutex> lock(this->mutex);
    for (;;) {
        if (this->aborted) return PushResult::Aborted;
        if (this->producerYield && this->producerYield()) return PushResult::Yielded;
        if (this->entries.size() < this->maxFrames) break;
        this->notFull.wait_for(lock, kWaitTick);
    }

    AVFrame* owned = this->api->av_frame_alloc();
    if (owned == nullptr) {
        this->api->av_frame_unref(frame);
        return PushResult::Aborted;
    }
    this->api->av_frame_move_ref(owned, frame);
    this->entries.push_back(Entry{owned, generation});
    lock.unlock();
    this->notEmpty.notify_one();
    return PushResult::Ok;
}

FrameQueue::PopResult FrameQueue::pop(AVFrame* out, uint64_t minGeneration, uint64_t* generation) {
    if (this->api == nullptr) return PopResult::Aborted;
    std::unique_lock<std::mutex> lock(this->mutex);
    for (;;) {
        if (this->aborted) return PopResult::Aborted;
        if (this->consumerYield && this->consumerYield()) return PopResult::Yielded;
        if (this->entries.empty()) {
            if (this->eofFlag) return PopResult::Eof;
            return PopResult::Empty;
        }
        Entry entry = this->entries.front();
        this->entries.pop_front();
        if (entry.generation < minGeneration) {
            // seek 之前解出来的帧：丢掉（否则画面会先闪一下旧内容）
            this->api->av_frame_free(&entry.frame);
            continue;
        }
        this->api->av_frame_move_ref(out, entry.frame);
        this->api->av_frame_free(&entry.frame);
        if (generation != nullptr) *generation = entry.generation;
        lock.unlock();
        this->notFull.notify_one();
        return PopResult::Ok;
    }
}

bool FrameQueue::waitForData(int timeoutMs) {
    std::unique_lock<std::mutex> lock(this->mutex);
    if (this->aborted || this->eofFlag || !this->entries.empty()) return true;
    this->notEmpty.wait_for(lock, std::chrono::milliseconds(timeoutMs));
    return true;
}

void FrameQueue::flush() {
    std::deque<Entry> dropped;
    {
        std::lock_guard<std::mutex> lock(this->mutex);
        dropped.swap(this->entries);
        this->eofFlag = false;
    }
    if (this->api != nullptr) {
        for (Entry& e : dropped) {
            if (e.frame != nullptr) this->api->av_frame_free(&e.frame);
        }
    }
    this->notFull.notify_all();
}

void FrameQueue::setEof() {
    std::lock_guard<std::mutex> lock(this->mutex);
    this->eofFlag = true;
    this->notEmpty.notify_all();
}

void FrameQueue::clearEof() {
    std::lock_guard<std::mutex> lock(this->mutex);
    this->eofFlag = false;
}

void FrameQueue::abort() {
    std::lock_guard<std::mutex> lock(this->mutex);
    this->aborted = true;
    this->notEmpty.notify_all();
    this->notFull.notify_all();
}

size_t FrameQueue::size() const {
    std::lock_guard<std::mutex> lock(this->mutex);
    return this->entries.size();
}

} // namespace cineduct::host

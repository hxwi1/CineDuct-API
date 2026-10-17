// ============================================================================
// 码流直通（IEC 61937）—— 硬件解码杜比全景声的唯一路径
//
// 思路：**完全不碰音频解码器**。Demuxer 读出的 E-AC-3 / AC-3 / TrueHD / DTS 压缩帧，
// 经 FFmpeg 的 `spdif` 复用器封装成 IEC 61937 数据突发（burst）后原样送进声卡，
// 由接收端（功放/回音壁）用自己的硬件解码器解出 Atmos / DTS:X。
//
// 为什么用 FFmpeg 的 spdif 复用器而不是自己写封装（读源码后的结论，见 spdifenc.c）：
//   1. 它按数据突发周期**精确补齐零填充**：
//        * AC-3   : 每帧 1536 样本 → 6144 字节（48kHz 链路 = 192000 B/s → 正好 32ms）
//        * DD+    : 6 个 block（1536 样本）→ 24576 字节（192kHz 链路 = 768000 B/s → 正好 32ms）
//        * TrueHD : MAT 帧 61440 字节（192kHz/8ch 链路 = 3072000 B/s）
//      **于是输出的字节速率天然等于链路速率** —— 我们不需要任何额外的节流或补零，
//      声卡取数据的速度就是码流的正确播放速度（写满环形缓冲即背压，见 AudioSink::write）。
//   2. 它写入 0xF872/0x4E1F 前导并在**第 16 位字内做字节交换**（MSB-first），
//      这正是 IEC 60958 链路要求的内存表示 —— 自己实现最容易错的就是这一处。
//   3. 缺哪个符号会报名字（见 ffmpeg_dyn.h），不会链接期炸。
//
// 代价（设计上就无法避免）：**码流不能被修改** —— 音量/静音/距离衰减全部失效，
// 音量只能由功放控制。见 AudioSink::volumeLocked()。
// ============================================================================
#pragma once

#include <atomic>
#include <cstdint>
#include <functional>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

extern "C" {
#include <libavformat/avformat.h>
}

#include "audio_chain.h"
#include "audio_sink.h"
#include "ffmpeg_dyn.h"

namespace cineduct::host {

struct AudioBitstream : public AudioChain {
    /** 字节去处：声卡（生产）或文件（诊断）。返回实际写出的字节数。 */
    using Writer = std::function<int(const uint8_t*, int)>;

    bool open(const FfmpegApi& api, AudioSink& sink, const std::string& url,
              const std::string& headers, const std::string& userAgent,
              std::string& error) override;
    /**
     * 同上，但直接给一个字节去处。
     *
     * 用于诊断/落盘（`--spdif-dump`）：没有声卡就没有背压，整个文件会被**瞬间**写完 ——
     * 所以那条路只能用来检查"封装出来的字节流对不对"，不能用来听。
     */
    bool openWithWriter(const FfmpegApi& api, const Writer& writer, const std::string& url,
                        const std::string& headers, const std::string& userAgent,
                        std::string& error);

    void start() override;
    bool restart(std::string& error) override;
    /**
     * 定位到指定媒体时间（毫秒）。
     *
     * <p><b>非阻塞</b>：只登记请求，由泵线程自己执行（含重建复用器）。
     * 泵线程可能正阻塞在声卡背压里（暂停时环形缓冲不再排空）—— 视频线程绝不能去等它，
     * 否则整个子进程死锁（没声音 + 画面停在 1~2 帧）。
     */
    bool seek(long long ms, std::string& error) override;
    void close() override;

    long long durationMs() const override { return this->duration; }
    bool finished() const override { return this->ended.load(); }
    bool failed() const override { return this->errorFlag.load(); }
    uint64_t seekAppliedCount() const override { return this->seekApplied.load(); }
    /** 最新写出码流对应的内容时间（毫秒）。见 AudioChain::writtenContentMs 的说明。 */
    double writtenContentMs() const override { return this->lastContentMs.load(); }
    const char* kind() const override { return "passthrough"; }

    const std::string& codecName() const { return this->codec; }
    int sourceRate() const { return this->srcRate; }
    int sourceChannels() const { return this->srcChannels; }
    long long bytesWritten() const { return this->writtenBytes.load(); }

    /** 已写出的码流对应的媒体时长（毫秒）—— 由字节数/链路速率换算，等于真实播放时间。 */
    double linkDurationMs(double linkBytesPerSecond) const;

private:
    static int writeThunk(void* opaque, const uint8_t* buf, int size);
    void pump();
    /** 泵线程执行实际定位（只有它允许碰 demuxer 与复用器）。 */
    bool applySeek(long long ms, std::string& error);
    bool openInput(std::string& error);
    bool setupMuxer(std::string& error);
    void teardownMuxer();

    const FfmpegApi* api = nullptr;
    Writer writer;
    /**
     * 线程约定：**demuxer 与复用器只由泵线程访问**（打开在 start 之前，关闭在 join 之后）。
     * 所以这里不需要互斥 —— 反过来，正是"seek 从视频线程直接改复用器"这个做法
     * 会造成"一边 free 一边用"，甚至把视频线程锁死。
     */
    AVFormatContext* in = nullptr;
    AVFormatContext* mux = nullptr;
    AVIOContext* avio = nullptr;
    AVPacket* pkt = nullptr;
    uint8_t* ioBuffer = nullptr;
    int streamIndex = -1;
    /** 复用器里的输出流下标（写包前必须把 pkt->stream_index 改成它）。 */
    int outStreamIndex = -1;

    long long duration = -1;
    int srcRate = 48000;
    int srcChannels = 2;
    std::string codec = "?";
    std::string url;
    std::string headers;
    /** 见 AudioChain::open —— 直通链同样是"另开一路输入"，直播 CDN 缺 UA 就是 403。 */
    std::string userAgent;

    std::atomic<long long> writtenBytes{0};
    /** 待执行的定位请求（-1 = 无）；只由泵线程消费。 */
    std::atomic<long long> requestSeekMs{-1};
    /** 已执行的定位请求数（见 AudioChain::seekAppliedCount）。 */
    std::atomic<uint64_t> seekApplied{0};
    /** 最新写出码流对应的内容时间（媒体时间轴，毫秒，-1 = 未知）。 */
    std::atomic<double> lastContentMs{-1.0};
    std::atomic<bool> quit{false};
    std::atomic<bool> ended{false};
    std::atomic<bool> errorFlag{false};
    std::thread worker;
};

} // namespace cineduct::host

#include "audio_spdif.h"

#include <cstdio>
#include <cstring>

namespace cineduct::host {

namespace {

std::string ffErr(const FfmpegApi& api, int code) {
    char buf[256];
    if (api.av_strerror != nullptr && api.av_strerror(code, buf, sizeof(buf)) == 0) {
        return std::string(buf) + " (" + std::to_string(code) + ")";
    }
    return "code " + std::to_string(code);
}

/** IO 缓冲：够装下几个最大突发（TrueHD 的 MAT 帧是 61440 字节）。 */
constexpr int kIoBufferBytes = 256 * 1024;

} // namespace

int AudioBitstream::writeThunk(void* opaque, const uint8_t* buf, int size) {
    auto* self = static_cast<AudioBitstream*>(opaque);
    if (size <= 0) return 0;
    const int written = self->writer(buf, size);
    if (written > 0) self->writtenBytes.fetch_add(written);
    // 写给 FFmpeg 的约定：返回实际字节数；<=0 用负的 AVERROR 表示失败
    return written > 0 ? written : AVERROR(EPIPE);
}

bool AudioBitstream::openInput(std::string& error) {
    AVDictionary* opts = nullptr;
    if (!this->headers.empty()) {
        this->api->av_dict_set(&opts, "headers", this->headers.c_str(), 0);
    }
    if (!this->userAgent.empty()) {
        this->api->av_dict_set(&opts, "user_agent", this->userAgent.c_str(), 0);
    }
    this->api->av_dict_set(&opts, "rw_timeout", "15000000", 0);
    int r = this->api->avformat_open_input(&this->in, this->url.c_str(), nullptr, &opts);
    if (opts != nullptr) this->api->av_dict_free(&opts);
    if (r < 0) {
        error = "打开音频输入失败: " + ffErr(*this->api, r);
        return false;
    }
    r = this->api->avformat_find_stream_info(this->in, nullptr);
    if (r < 0) {
        error = "读取音频流信息失败: " + ffErr(*this->api, r);
        return false;
    }
    const AVCodec* decoder = nullptr;
    this->streamIndex = this->api->av_find_best_stream(this->in, AVMEDIA_TYPE_AUDIO, -1, -1,
                                                       &decoder, 0);
    if (this->streamIndex < 0) {
        error = "没有音频流（码流直通需要 AC-3 / E-AC-3 / TrueHD / DTS 音轨）";
        return false;
    }
    AVStream* stream = this->in->streams[this->streamIndex];
    this->srcRate = stream->codecpar->sample_rate > 0 ? stream->codecpar->sample_rate : 48000;
    this->srcChannels = stream->codecpar->ch_layout.nb_channels > 0
            ? stream->codecpar->ch_layout.nb_channels : 2;
    this->codec = this->api->avcodec_get_name(stream->codecpar->codec_id);
    this->duration = stream->duration > 0 && stream->time_base.den > 0
            ? static_cast<long long>(stream->duration * 1000.0 * stream->time_base.num
                                     / stream->time_base.den)
            : -1;
    return true;
}

bool AudioBitstream::setupMuxer(std::string& error) {
    AVStream* inStream = this->in->streams[this->streamIndex];
    if (this->api->avformat_alloc_output_context2(&this->mux, nullptr, "spdif", nullptr) < 0
        || this->mux == nullptr) {
        error = "这套 FFmpeg 里没有 spdif 复用器（无法封装 IEC 61937 码流）";
        return false;
    }

    this->ioBuffer = static_cast<uint8_t*>(this->api->av_malloc(kIoBufferBytes));
    if (this->ioBuffer == nullptr) {
        error = "分配码流 IO 缓冲失败";
        return false;
    }
    this->avio = this->api->avio_alloc_context(this->ioBuffer, kIoBufferBytes, 1, this,
                                               nullptr, &AudioBitstream::writeThunk, nullptr);
    if (this->avio == nullptr) {
        error = "创建码流 IO 上下文失败";
        return false;
    }
    this->mux->pb = this->avio;
    this->mux->flags |= AVFMT_FLAG_CUSTOM_IO;

    AVStream* outStream = this->api->avformat_new_stream(this->mux, nullptr);
    if (outStream == nullptr) {
        error = "创建输出流失败";
        return false;
    }
    /*
     * ★ 记住输出流下标，写包时要改用它。
     *
     * demuxer 给我们的包带着**输入流下标**（真实影片里音频几乎总是 1：视频是 0）。
     * 而复用器里只有一个输出流，下标是 0 —— 直接把输入下标原样递进去会得到
     * `Invalid packet stream index: 1`(EINVAL)，音频链当场死掉（没声音）。
     * 之所以之前没暴露：独立音轨的测试文件里音频恰好就是流 0，蒙对了。
     */
    this->outStreamIndex = outStream->index;
    int r = this->api->avcodec_parameters_copy(outStream->codecpar, inStream->codecpar);
    if (r < 0) {
        error = "复制音频参数失败: " + ffErr(*this->api, r);
        return false;
    }
    outStream->time_base = inStream->time_base;

    r = this->api->avformat_write_header(this->mux, nullptr);
    if (r < 0) {
        /*
         * 常见于音轨不是可直通的编码（spdif 复用器只认 AC-3/E-AC-3/MP1-3/DTS/AAC/TrueHD）。
         * 这里必须报出真实原因，上层据此回退到解码路径（而不是"没声音"了事）。
         */
        error = "spdif 封装不支持该音轨（" + this->codec + "）: " + ffErr(*this->api, r);
        return false;
    }
    return true;
}

void AudioBitstream::teardownMuxer() {
    if (this->mux != nullptr) {
        this->api->avformat_free_context(this->mux);
        this->mux = nullptr;
    }
    if (this->avio != nullptr) {
        // 缓冲是我们自己 av_malloc 的，avio_context_free 只释放上下文本身
        this->api->avio_context_free(&this->avio);
        this->avio = nullptr;
    }
    if (this->ioBuffer != nullptr) {
        this->api->av_free(this->ioBuffer);
        this->ioBuffer = nullptr;
    }
}

bool AudioBitstream::openWithWriter(const FfmpegApi& a, const Writer& w, const std::string& u,
                                    const std::string& h, const std::string& ua,
                                    std::string& error) {
    this->api = &a;
    this->writer = w;
    this->url = u;
    this->headers = h;
    this->userAgent = ua;

    if (!this->openInput(error)) return false;
    if (!this->setupMuxer(error)) {
        this->api->avformat_close_input(&this->in);
        return false;
    }
    std::printf("[host] 码流直通就绪: %s %dch %dHz 时长=%lldms → spdif(IEC61937) 封装\n",
                this->codec.c_str(), this->srcChannels, this->srcRate, this->duration);
    return true;
}

bool AudioBitstream::open(const FfmpegApi& a, AudioSink& sink, const std::string& u,
                          const std::string& h, const std::string& ua, std::string& error) {
    AudioSink* target = &sink;
    return this->openWithWriter(a, [target](const uint8_t* data, int bytes) {
        return target->write(data, bytes);
    }, u, h, ua, error);
}

void AudioBitstream::start() {
    this->quit.store(false);
    this->ended.store(false);
    // restart 会复用同一个对象：上一轮的包要先放掉，否则每重启一次泄漏一个包
    if (this->pkt != nullptr) this->api->av_packet_free(&this->pkt);
    this->pkt = this->api->av_packet_alloc();
    this->worker = std::thread([this] { this->pump(); });
}

void AudioBitstream::pump() {
    const FfmpegApi& a = *this->api;
    while (!this->quit.load()) {
        /*
         * 每轮开头消化定位请求。
         *
         * 放在这里（而不是让视频线程直接做）是**关键**：泵线程只有在 write 返回之后才可能
         * 走到这一行，而 write 的阻塞来自声卡背压 —— 上层 seek 紧接着会 flush 声卡缓冲，
         * 阻塞立刻解除，定位随即执行。视频线程全程不等，于是永远不会死锁。
         */
        const long long wantSeek = this->requestSeekMs.exchange(-1);
        if (wantSeek >= 0) {
            std::string seekError;
            if (!this->applySeek(wantSeek, seekError)) {
                std::fprintf(stderr, "[host] %s\n", seekError.c_str());
            }
        }
        if (this->in == nullptr || this->mux == nullptr) return;
        const int r = a.av_read_frame(this->in, this->pkt);
        if (r < 0) {
            this->ended.store(true);
            return;
        }
        if (this->pkt->stream_index != this->streamIndex) {
            a.av_packet_unref(this->pkt);
            continue;
        }
        /*
         * 时间戳不用管：spdif 复用器带 AVFMT_NOTIMESTAMPS 标志，只按包序把突发补齐，
         * 每个突发的长度就代表它的播放时长（见文件头注释）。
         * 但**流下标必须改成输出流的**（见 setupMuxer 里的说明）。
         * av_interleaved_write_frame 会接管这个包的引用（写完自行 unref）。
         *
         * 这一步会阻塞在声卡背压上（直通的限速手段），但**只阻塞泵线程**：
         * 谁也不能在视频线程上等它。
         */
        this->pkt->stream_index = this->outStreamIndex;
        // 记下"内容喂到哪儿"（见 AudioChain::writtenContentMs）：本包 PTS + 时长
        if (this->pkt->pts != AV_NOPTS_VALUE && this->in->streams[this->streamIndex]->time_base.den > 0
            && this->pkt->duration > 0) {
            const double ptsMs = a.av_rescale_q(this->pkt->pts,
                                                this->in->streams[this->streamIndex]->time_base,
                                                AVRational{1, 1000});
            const double durMs = a.av_rescale_q(this->pkt->duration,
                                                this->in->streams[this->streamIndex]->time_base,
                                                AVRational{1, 1000});
            this->lastContentMs.store(ptsMs + durMs);
        }
        const int w = a.av_interleaved_write_frame(this->mux, this->pkt);
        a.av_packet_unref(this->pkt);
        if (w < 0) {
            std::fprintf(stderr, "[host] 码流封装失败: %s\n", ffErr(a, w).c_str());
            this->errorFlag.store(true);
            this->ended.store(true);
            return;
        }
        if (this->quit.load()) return;
    }
}

/** 登记定位请求（**非阻塞**）；真正的活由泵线程在 {@link #applySeek} 里干。 */
bool AudioBitstream::seek(long long ms, std::string& error) {
    if (this->in == nullptr) {
        error = "码流链未打开";
        return false;
    }
    this->requestSeekMs.store(ms < 0 ? 0 : ms);
    return true;
}

/** 泵线程：执行实际定位（重新定位输入 + 重建复用器）。 */
bool AudioBitstream::applySeek(long long ms, std::string& error) {
    if (this->in == nullptr) return false;
    const int64_t ts = this->api->av_rescale_q(ms, AVRational{1, 1000},
                                               this->in->streams[this->streamIndex]->time_base);
    const int r = this->api->av_seek_frame(this->in, this->streamIndex, ts, AVSEEK_FLAG_BACKWARD);
    if (r < 0) {
        error = "音频链定位失败: " + ffErr(*this->api, r);
        return false;
    }
    /*
     * 复用器要重建：E-AC-3 的突发是"攒够 repeat 个 block 才发"的，
     * 定位时里面可能正压着半个突发 —— 留着它会让跳转后的第一个突发混入旧数据。
     */
    this->teardownMuxer();
    if (!this->setupMuxer(error)) return false;
    this->ended.store(false);
    // 通知上层"请求已落地"（见 AudioChain::seekAppliedCount）
    this->seekApplied.fetch_add(1);
    std::printf("[host] 码流链已定位到 %lldms\n", ms);
    return true;
}

bool AudioBitstream::restart(std::string& error) {
    if (this->in == nullptr) return false;
    /*
     * ★ 必须重新起一条泵线程。
     *
     * 泵线程在片尾（或出错）时会直接 return 退出；只 seek 是拉不回来的 ——
     * 于是"循环播放"和"片尾后 seek"在直通模式下会变成**只有画面没有声音**。
     * 这里先停旧线程再重建，避免两条线程抢同一个复用器。
     */
    this->quit.store(true);
    if (this->worker.joinable()) this->worker.join();
    this->quit.store(false);
    this->ended.store(false);
    this->errorFlag.store(false);
    this->requestSeekMs.store(0);   // 新泵线程的第一件事就是定位回起点
    this->start();
    return true;
}

double AudioBitstream::linkDurationMs(double linkBytesPerSecond) const {
    if (linkBytesPerSecond <= 0.0) return 0.0;
    return static_cast<double>(this->writtenBytes.load()) * 1000.0 / linkBytesPerSecond;
}

void AudioBitstream::close() {
    this->quit.store(true);
    // 停线程之前不能让声卡关掉（writer 回调还在里面阻塞），上层负责顺序：先 close 链路再 close 声卡
    if (this->worker.joinable()) this->worker.join();
    if (this->mux != nullptr) {
        this->api->av_write_trailer(this->mux);
    }
    this->teardownMuxer();
    if (this->in != nullptr) {
        this->api->avformat_close_input(&this->in);
    }
    if (this->pkt != nullptr) {
        this->api->av_packet_free(&this->pkt);
    }
}

} // namespace cineduct::host

#include "audio_source.h"

#include <cstdio>
#include <vector>

namespace cineduct::host {

namespace {

constexpr int kOutSamples = 2048;   // 每次产出的样本数（交错后 = samples × channels）

std::string ffError(const FfmpegApi& api, int code) {
    char buf[256];
    if (api.av_strerror != nullptr && api.av_strerror(code, buf, sizeof(buf)) == 0) {
        return std::string(buf) + " (" + std::to_string(code) + ")";
    }
    return "code " + std::to_string(code);
}

/**
 * 按声道数给出标准声道布局掩码。
 *
 * 必须给对：swr 要求 layout 与声道数一致，否则直接报 "Output channel layout is invalid"。
 * （实测踩过：系统默认设备是 **8 声道 7.1**，而当时只写了 mono/stereo 两种掩码。）
 */
uint64_t channelMaskFor(int channels) {
    switch (channels) {
        case 1: return AV_CH_LAYOUT_MONO;
        case 2: return AV_CH_LAYOUT_STEREO;
        case 3: return AV_CH_LAYOUT_2POINT1;
        case 4: return AV_CH_LAYOUT_QUAD;
        case 5: return AV_CH_LAYOUT_5POINT0;
        case 6: return AV_CH_LAYOUT_5POINT1;
        case 7: return AV_CH_LAYOUT_6POINT1;
        case 8: return AV_CH_LAYOUT_7POINT1;
        default: return 0;
    }
}

/**
 * 一帧解码出来的音频，其**内容结束**在媒体时间轴上的位置（毫秒）。
 *
 * <p>用来记"内容已经喂到哪儿"（见 AudioChain::writtenContentMs）：
 * 帧 PTS + 这一帧的时长。取不到 PTS 就返回 -1（上层会跳过偏移判定，不会误判）。
 */
double frameContentEndMs(const FfmpegApi& ffmpeg, const AVFrame* frame, AVFormatContext* fmt,
                         int streamIndex) {
    if (frame == nullptr || frame->pts == AV_NOPTS_VALUE || fmt == nullptr) return -1.0;
    const AVStream* stream = fmt->streams[streamIndex];
    const double ptsMs = ffmpeg.av_rescale_q(frame->pts, stream->time_base, AVRational{1, 1000});
    const double durationMs = frame->sample_rate > 0
            ? frame->nb_samples * 1000.0 / frame->sample_rate : 0.0;
    return ptsMs + durationMs;
}

} // namespace

bool AudioSource::open(const FfmpegApi& ffmpeg, AudioSink& target, const std::string& url,
                       const std::string& headers, const std::string& userAgent,
                       std::string& error) {
    this->api = &ffmpeg;
    this->sink = &target;
    this->url = url;
    this->headers = headers;
    this->userAgent = userAgent;

    if (!this->openPath(error)) return false;
    std::printf("[host] 音频源就绪: %s %dch %dHz 时长=%lldms → 输出 %dch %dHz S16\n",
                this->codec.c_str(), this->srcChannels, this->srcRate, this->duration,
                target.channels(), target.sampleRate());
    if (this->srcChannels > target.channels()) {
        std::printf("[host] 多声道下混: %dch → %dch（按标准矩阵，不是丢声道）\n",
                    this->srcChannels, target.channels());
    }
    return true;
}

bool AudioSource::openPath(std::string& error) {
    const FfmpegApi& ffmpeg = *this->api;
    AudioSink& target = *this->sink;

    AVDictionary* opts = nullptr;
    if (!this->headers.empty()) ffmpeg.av_dict_set(&opts, "headers", this->headers.c_str(), 0);
    if (!this->userAgent.empty()) ffmpeg.av_dict_set(&opts, "user_agent", this->userAgent.c_str(), 0);
    /*
     * ★ 15 秒的读超时（rw_timeout）。
     *
     * <p>它的作用只是"别永久卡住"，**失败本身必须被当成错误而不是片尾**
     * —— 见 decodeLoop 里对 av_read_frame 返回值的分支。DD 那次事故（2026-10-17）
     * 就是把这个超时当片尾，声音永久消失。
     */
    ffmpeg.av_dict_set(&opts, "rw_timeout", "15000000", 0);
    int r = ffmpeg.avformat_open_input(&this->fmt, this->url.c_str(), nullptr, &opts);
    if (opts != nullptr) ffmpeg.av_dict_free(&opts);
    if (r < 0) {
        error = "打开音频输入失败: " + ffError(ffmpeg, r);
        return false;
    }
    r = ffmpeg.avformat_find_stream_info(this->fmt, nullptr);
    if (r < 0) {
        error = "读取音频流信息失败: " + ffError(ffmpeg, r);
        return false;
    }
    const AVCodec* decoder = nullptr;
    this->streamIndex = ffmpeg.av_find_best_stream(this->fmt, AVMEDIA_TYPE_AUDIO, -1, -1, &decoder, 0);
    if (this->streamIndex < 0 || decoder == nullptr) {
        error = "没有可用音频流";
        return false;
    }
    AVStream* stream = this->fmt->streams[this->streamIndex];
    this->srcChannels = stream->codecpar->ch_layout.nb_channels > 0
            ? stream->codecpar->ch_layout.nb_channels : 2;
    this->srcRate = stream->codecpar->sample_rate > 0 ? stream->codecpar->sample_rate : 48000;
    this->codec = ffmpeg.avcodec_get_name(stream->codecpar->codec_id);
    this->duration = stream->duration > 0 && stream->time_base.den > 0
            ? static_cast<long long>(stream->duration * 1000.0 * stream->time_base.num / stream->time_base.den)
            : -1;

    this->codecCtx = ffmpeg.avcodec_alloc_context3(decoder);
    r = ffmpeg.avcodec_parameters_to_context(this->codecCtx, stream->codecpar);
    if (r < 0) {
        error = "音频参数转上下文失败";
        return false;
    }
    r = ffmpeg.avcodec_open2(this->codecCtx, decoder, nullptr);
    if (r < 0) {
        error = "打开音频解码器失败: " + ffError(ffmpeg, r);
        return false;
    }

    // 重采样目标：设备要的采样率与声道数，格式统一 S16 交错（sink 再转设备格式）
    AVChannelLayout outLayout{};
    const uint64_t mask = channelMaskFor(target.channels());
    if (mask != 0) {
        outLayout.order = AV_CHANNEL_ORDER_NATIVE;
        outLayout.nb_channels = target.channels();
        outLayout.u.mask = mask;
    } else {
        // 非标准声道数：交给 swr 按"未指定顺序"处理（极少见）
        outLayout.order = AV_CHANNEL_ORDER_UNSPEC;
        outLayout.nb_channels = target.channels();
    }

    r = ffmpeg.swr_alloc_set_opts2(&this->swr, &outLayout, AV_SAMPLE_FMT_S16, target.sampleRate(),
                                   &this->codecCtx->ch_layout, this->codecCtx->sample_fmt,
                                   this->codecCtx->sample_rate, 0, nullptr);
    if (r < 0 || this->swr == nullptr) {
        error = "创建重采样器失败: " + ffError(ffmpeg, r);
        return false;
    }
    r = ffmpeg.swr_init(this->swr);
    if (r < 0) {
        error = "初始化重采样器失败: " + ffError(ffmpeg, r);
        return false;
    }

    this->packet = ffmpeg.av_packet_alloc();
    this->frame = ffmpeg.av_frame_alloc();
    if (this->packet == nullptr || this->frame == nullptr) {
        error = "分配音频包/帧失败";
        return false;
    }
    return true;
}

void AudioSource::closeNative() {
    if (this->api == nullptr) return;
    const FfmpegApi& ffmpeg = *this->api;
    if (this->frame != nullptr) ffmpeg.av_frame_free(&this->frame);
    if (this->packet != nullptr) ffmpeg.av_packet_free(&this->packet);
    if (this->swr != nullptr) ffmpeg.swr_free(&this->swr);
    if (this->codecCtx != nullptr) ffmpeg.avcodec_free_context(&this->codecCtx);
    if (this->fmt != nullptr) ffmpeg.avformat_close_input(&this->fmt);
    this->streamIndex = -1;
}

bool AudioSource::reconnect(std::string& error) {
    if (this->api == nullptr || this->sink == nullptr) {
        error = "音频源未初始化";
        return false;
    }
    /*
     * 回到"上次喂到的内容位置"继续 —— 不这么做就等于从头开始放，
     * 音画会立刻错开一整个已播时长。
     */
    const double last = this->lastContentMs.load();
    const long long resumeMs = last > 0.0 ? static_cast<long long>(last) : this->lastSeekTargetMs;
    this->closeNative();
    if (!this->openPath(error)) return false;
    this->reconnects.fetch_add(1);
    this->reopenRequested.store(false);
    this->ended.store(false);
    // applySeek 会打印"音频已定位到…"并推进 seekApplied，上层据此重排声卡缓冲
    this->applySeek(resumeMs < 0 ? 0 : resumeMs);
    return true;
}

void AudioSource::start() {
    this->quit.store(false);
    this->requestSeekMs.store(-1);
    this->worker = std::thread([this] { this->decodeLoop(); });
}

/**
 * 登记一个定位请求（**非阻塞**）。
 *
 * <p>真正的定位在 {@link #applySeek} 里、由解码线程执行。这样视频线程永远不会
 * 等在音频链上 —— 哪怕解码线程正卡在声卡背压里（暂停时就是这种情况）。
 * 上层在调用之后会清声卡缓冲（flush），所以这里不需要关心"刚好已经写进环形缓冲的那一点旧音频"。
 */
bool AudioSource::seek(long long ms, std::string& error) {
    if (this->api == nullptr || this->fmt == nullptr || this->streamIndex < 0) {
        error = "音频源未打开";
        return false;
    }
    this->requestSeekMs.store(ms < 0 ? 0 : ms);
    return true;
}

/** 解码线程：执行真正的定位。只有它碰 demuxer/解码器/重采样器。 */
void AudioSource::applySeek(long long ms) {
    const FfmpegApi& ffmpeg = *this->api;
    AVStream* stream = this->fmt->streams[this->streamIndex];
    const int64_t timestamp = ffmpeg.av_rescale_q(ms, AVRational{1, 1000}, stream->time_base);
    const int r = ffmpeg.av_seek_frame(this->fmt, this->streamIndex, timestamp, AVSEEK_FLAG_BACKWARD);
    if (r < 0) {
        std::fprintf(stderr, "[host] 音频定位失败: %s\n", ffError(ffmpeg, r).c_str());
        return;
    }
    this->lastSeekTargetMs = ms;
    ffmpeg.avcodec_flush_buffers(this->codecCtx);
    this->ended.store(false);
    // 通知上层"请求已落地"：它可以据此再清一次声卡缓冲，见 AudioChain::seekAppliedCount
    this->seekApplied.fetch_add(1);
    std::printf("[host] 音频已定位到 %lldms\n", ms);
}

void AudioSource::decodeLoop() {
    const FfmpegApi& ffmpeg = *this->api;
    const int outChannels = this->sink->channels();
    std::vector<uint8_t> out(static_cast<size_t>(kOutSamples) * outChannels * 2);

    /*
     * 有 seek 请求待执行时**一个字节都不写声卡**。
     *
     * <p>理由：请求是在解码线程的下一轮开头才执行的，而这一轮可能已经把"seek 之前"的位置
     * 解出的一大段样本写进环形缓冲了 —— 上层虽然马上 flush 了声卡，但**环形缓冲里那段**
     * 与后续写入的样本会接着播，听起来就是"跳转之后先响了一段旧声音"（音画错位）。
     * 挡掉写入比事后清缓冲干净：宁可静几毫秒，也不要放错位置的声音。
     */
    auto writeUnlessSeeking = [&](int bytes, double contentEndMs) {
        if (bytes <= 0) return;
        if (this->requestSeekMs.load() >= 0) return;   // 等下一轮开头 applySeek
        this->sink->write(out.data(), bytes);
        // 记下"内容已经喂到哪儿"——上层据此算"耳朵此刻听到的内容位置"（见 AudioChain::writtenContentMs）
        if (contentEndMs >= 0) this->lastContentMs.store(contentEndMs);
    };

    /*
     * 没有活干时"停在这里等"，而不是退出线程（片尾/读失败僵死两种状态都走这里）。
     *
     * <p>能被三种事唤醒：seek 请求、上层请求"重开输入"、退出。
     */
    auto parkUntilWork = [&]() {
        while (!this->quit.load() && this->requestSeekMs.load() < 0
               && !this->reopenRequested.load()) {
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
        }
    };

    while (!this->quit.load()) {
        // 每轮开头先消化"重开输入"请求（上层判定链路僵死时会发）
        if (this->reopenRequested.exchange(false)) {   // ★ 先清标志：失败时否则会空转狂连
            std::string reopenError;
            if (this->reconnect(reopenError)) {
                std::printf("[host] 音频链已按上层请求重连（第 %u 次）\n", this->reconnects.load());
                continue;
            }
            std::fprintf(stderr, "[host] 音频链重连失败: %s\n", reopenError.c_str());
            /*
             * ★ 失败后**必须停下来等下一次请求**，不能 continue 回循环头：
             * 那时 fmt 已经是空的，直接 av_read_frame 是空指针；而如果只是想"再试一次"，
             * 就会变成没有间隔的疯狂重连（实测 5 秒内打出十几条"重连失败"）。
             * 重试节奏交给上层看门狗（5 秒一次）就够了。
             */
            this->ended.store(true);
            parkUntilWork();
            if (this->quit.load()) return;
            continue;
        }
        if (this->fmt == nullptr) {   // 兜底：没有输入上下文就绝不读
            this->ended.store(true);
            parkUntilWork();
            if (this->quit.load()) return;
            continue;
        }
        // 每轮开头先消化定位请求：此时它一定不是"正卡在 write 里"的状态
        const long long wantSeek = this->requestSeekMs.exchange(-1);
        if (wantSeek >= 0) {
            this->applySeek(wantSeek);
        }
        const int readResult = ffmpeg.av_read_frame(this->fmt, this->packet);
        if (readResult < 0) {
            /*
             * ★★ 这里以前只有一个分支：**任何**读失败都当片尾。
             *
             * <p>2026-10-17 的现场（`cineduct-host-2026-10-17_20-44-51-7eed7ce7-p33200.log`）：
             * 音频内容停在 271360ms（总长 339263ms），之后 26 秒一个字节都不再产出，而画面
             * 还在 30fps 出帧、音画偏移一路涨到 +26.3 秒。根因就是这一处：
             * `av_read_frame` 返回负数**不等于** EOF —— 读超时（`rw_timeout=15s`）和网络中断
             * 都走同一个返回值；被当成片尾之后，链路进"待命"，只有 seek 才能唤醒它，
             * 而当时没人会去 seek。于是「15 秒的网络抖动」= **永久静音**。
             *
             * <p>现在的处理：EOF 才当片尾；其它错误先就地重试（多数抖动一次就好），
             * 还不行就**重连**（重开输入 + 回到上次喂到的位置），并如实记进状态环。
             */
            if (readResult == AVERROR_EOF) {
                // 片尾：把解码器里剩下的帧排空
                ffmpeg.avcodec_send_packet(this->codecCtx, nullptr);
                for (;;) {
                    const int rr = ffmpeg.avcodec_receive_frame(this->codecCtx, this->frame);
                    if (rr < 0) break;
                    uint8_t* outPtr = out.data();
                    const int produced = ffmpeg.swr_convert(this->swr, &outPtr, kOutSamples,
                            const_cast<const uint8_t**>(this->frame->extended_data),
                            this->frame->nb_samples);
                    if (produced > 0) {
                        writeUnlessSeeking(produced * outChannels * 2,
                                           frameContentEndMs(ffmpeg, this->frame, this->fmt,
                                                             this->streamIndex));
                    }
                    ffmpeg.av_frame_unref(this->frame);
                }
                this->ended.store(true);
                /*
                 * ★ 片尾之后**不要退出线程**，留在这里等命令（seek / 重开 / quit）。
                 *
                 * <p>为什么（实测过的 bug）：以前这里直接 `return`，线程就没了 —— 而
                 * {@link #seek} 只是往 {@code requestSeekMs} 里登记一个请求，等解码线程下一轮
                 * 开头去执行。线程一退出，请求就永久没人执行：{@code 音频已定位到 …} 再也不打印、
                 * 音频一个字节都不再产出 … 上层等音频时钟的循环于是卡住 3 秒，被判成"音频链已死"
                 * → **画面永久改成挂钟节奏**，声音却还停在片尾那个位置 ——
                 * 表现就是"音画不同步"（用户报的正是这个）。
                 *
                 * <p>片尾是常态（放完、跳到结尾、循环前一刻都会到），所以这里必须等到有活干再走。
                 */
                parkUntilWork();
                if (this->quit.load()) return;
                continue;
            }

            // ---- 读失败（超时 / 网络中断 / 服务端断开）：**不是片尾** ----
            ffmpeg.av_packet_unref(this->packet);
            const uint32_t errorCount = this->readErrors.fetch_add(1) + 1;
            if (errorCount <= 5 || errorCount % 20 == 1) {   // 别刷屏
                std::printf("[host] 音频读失败（%s，累计 %u 次）—— 不是片尾，先就地重试\n",
                            ffError(ffmpeg, readResult).c_str(), errorCount);
            }
            bool recovered = false;
            for (int attempt = 0; attempt < 3 && !this->quit.load(); ++attempt) {
                std::this_thread::sleep_for(std::chrono::milliseconds(150 * (attempt + 1)));
                if (this->requestSeekMs.load() >= 0 || this->reopenRequested.load()) break;
                if (ffmpeg.av_read_frame(this->fmt, this->packet) >= 0) {
                    recovered = true;
                    break;
                }
                ffmpeg.av_packet_unref(this->packet);
            }
            if (!recovered && !this->quit.load()) {
                std::string reopenError;
                if (this->reconnect(reopenError)) {
                    std::printf("[host] 音频链已重连（第 %u 次），从内容 %.0fms 继续\n",
                                this->reconnects.load(), this->lastContentMs.load());
                    recovered = true;
                } else {
                    std::fprintf(stderr, "[host] 音频链重连失败: %s\n", reopenError.c_str());
                }
            }
            if (!recovered) {
                /*
                 * 实在救不回来：同样进"待命"（能被 seek / 重开唤醒），但**不假装是片尾** ——
                 * 上层会从读失败/重连计数看出这里发生过什么，并可通过重开输入再试。
                 */
                this->ended.store(true);
                parkUntilWork();
                if (this->quit.load()) return;
            }
            continue;
        }
        if (this->packet->stream_index != this->streamIndex) {
            ffmpeg.av_packet_unref(this->packet);
            continue;
        }
        const int sent = ffmpeg.avcodec_send_packet(this->codecCtx, this->packet);
        ffmpeg.av_packet_unref(this->packet);
        if (sent < 0) continue;

        for (;;) {
            const int rr = ffmpeg.avcodec_receive_frame(this->codecCtx, this->frame);
            if (rr == AVERROR(EAGAIN) || rr == AVERROR_EOF) break;
            if (rr < 0) break;
            uint8_t* outPtr = out.data();
            const int produced = ffmpeg.swr_convert(this->swr, &outPtr, kOutSamples,
                    const_cast<const uint8_t**>(this->frame->extended_data),
                    this->frame->nb_samples);
            if (produced > 0) {
                // 背压在这里：sink 写满会阻塞，解码线程就自然跟着音频节奏走
                writeUnlessSeeking(produced * outChannels * 2,
                                   frameContentEndMs(ffmpeg, this->frame, this->fmt,
                                                     this->streamIndex));
            }
            ffmpeg.av_frame_unref(this->frame);
        }
    }
}

bool AudioSource::restart(std::string& error) {
    if (this->api == nullptr || this->sink == nullptr) {
        error = "音频源未初始化";
        return false;
    }
    const FfmpegApi& ffmpeg = *this->api;
    AudioSink& target = *this->sink;
    const std::string keepUrl = this->url;
    const std::string keepHeaders = this->headers;
    const std::string keepUserAgent = this->userAgent;

    this->close();          // 先收掉旧解码线程与原生对象（join 后 api/sink 指针仍有效）
    this->quit.store(false);
    this->ended.store(false);
    this->errorFlag.store(false);
    this->requestSeekMs.store(-1);
    if (!this->open(ffmpeg, target, keepUrl, keepHeaders, keepUserAgent, error)) return false;
    this->start();
    return true;
}

void AudioSource::close() {
    /*
     * ★ 先打断可能卡在声卡背压里的 write()，再 join 解码线程。
     *
     * <p>不这么做就会死锁：重启链路时设备可能正停着（暂停 / 我们特意按住等首帧），
     * 环形缓冲满 → 解码线程卡在 write() 里 → close() 的 join 永远等不到（实测整个子进程卡死）。
     */
    if (this->sink != nullptr) this->sink->interruptWrites();
    this->quit.store(true);
    if (this->worker.joinable()) this->worker.join();
    this->closeNative();
}

} // namespace cineduct::host

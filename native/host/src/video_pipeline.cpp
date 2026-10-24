#include "video_pipeline.h"

#include <windows.h>
#include <d3d11.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

// hwcontext_d3d11va.h 会 #include <d3d11.h>：D3D 头必须先在外面进来（见 video_decoder.cpp 的★说明）
extern "C" {
#include <libavutil/hwcontext_d3d11va.h>
#include <libavutil/pixdesc.h>
}

namespace cineduct::host {

std::atomic<bool> g_parentGone{false};

namespace {

/**
 * 出帧判定与定位的阈值。
 *
 * <p>出帧那三个是数值取向对照 CicadaPlayer 的 `RenderVideo`：早于 -10ms 只等不丢、
 * 晚到一定程度才丢（它用 500ms 触发"回关键帧重解"那种重手段）、另外要有
 * "距上一帧太久就无条件渲染"的防饿死规则。
 */
/** 比主时钟早这么多以内就出帧（等于"不提前"，交给时钟自己走）。 */
constexpr double kEarlyGraceMs = 10.0;
/** 晚这么多、**而且队列里还有更新的帧**时才丢这一帧。 */
constexpr double kLateDropMs = 100.0;
/** 单次等待片长：够小才能让 tick()/暂停/seek 及时被看到，够大才不空转。 */
constexpr double kWaitSliceMs = 20.0;
/** 每一帧最多为"等到点"花这么久；超过就强行出帧（宁可早，不可饿死画面）。 */
constexpr int kMaxPaceWaitMs = 2000;
/** 空转（没帧/暂停）时状态块的刷新间隔。 */
constexpr int kStatusIdleIntervalMs = 200;
/**
 * 画面领先声音这么多（毫秒）就认为"音频落后了"，需要纠正（见 checkAudioVideoOffset）。
 *
 * <p>300ms 是个保守值：正常播放时这个偏移只在一个帧间隔内摆动（25fps 约 40ms、60fps 约 17ms），
 * 超过 300ms 已经明显能听出来，而且不会是时钟抖动造成的。
 */
constexpr double kAudioLagCorrectMs = 300.0;

/**
 * 一次播放里"重锚时钟"最多做几次。
 *
 * <p>正常播放一次都不该触发（偏移只在一个帧间隔内摆动）。给上限是为了防止极端素材
 * （比如音频内容反复跳动）把时钟拽来拽去 —— 那样画面会一直抖，比错位更难看。
 * 到上限后仍然照实把偏移打进日志（每 5 秒一行），交给上层/用户判断。
 */
constexpr int kMaxClockHeals = 8;

/*
 * 音频僵死看门狗（见 maybeRepairAudio）。
 *
 * <p>为什么是 3 秒：与 MediaClock 的"音频停滞"判据同量级 —— 那个 3 秒决定"改挂钟"，
 * 这个 3 秒决定"去修它"。判僵死不需要更长的窗口：正常播放里内容位置每 20ms 都会前进。
 */
constexpr int kAudioStuckSeconds = 3;
/** 连续修复的最小间隔（一次 seek 落地 + 声卡重填至少几百毫秒，间隔太短只会互相踩）。 */
constexpr int kAudioRepairIntervalSeconds = 5;
/** 一次播放里最多自动修几次；到上限后只记账不再动（避免把坏链变成无限 seek 循环）。 */
constexpr int kMaxAudioRepairs = 8;

std::string ffError(const FfmpegApi& api, int code) {
    char buf[256];
    if (api.av_strerror != nullptr && api.av_strerror(code, buf, sizeof(buf)) == 0) {
        return std::string(buf) + " (" + std::to_string(code) + ")";
    }
    return "code " + std::to_string(code);
}

/**
 * 把一张 GPU 纹理读回并写成 PPM（P6）—— `--dump-frame` 的诊断手段。
 *
 * <p>为什么要这么个诊断：信箱化"到底有没有生效"光看日志说不清（dest rect 设了也可能被驱动忽略），
 * 而 PPM 是**零依赖**的裸像素格式 —— 落盘之后用 PowerShell 就能数出"上下有没有整行纯黑"。
 * 只在首帧调一次（Map 会强制 GPU 同步，代价不小）。
 */
bool dumpTextureToPpm(ID3D11Device* device, ID3D11DeviceContext* context, ID3D11Texture2D* tex,
                      const std::string& path, std::string& error) {
    if (device == nullptr || context == nullptr || tex == nullptr) {
        error = "参数为空";
        return false;
    }
    D3D11_TEXTURE2D_DESC desc{};
    tex->GetDesc(&desc);
    D3D11_TEXTURE2D_DESC staging = desc;
    staging.Usage = D3D11_USAGE_STAGING;
    staging.BindFlags = 0;
    staging.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
    staging.MiscFlags = 0;
    ID3D11Texture2D* copy = nullptr;
    if (FAILED(device->CreateTexture2D(&staging, nullptr, &copy)) || copy == nullptr) {
        error = "创建 staging 纹理失败";
        return false;
    }
    context->CopyResource(copy, tex);
    D3D11_MAPPED_SUBRESOURCE mapped{};
    if (FAILED(context->Map(copy, 0, D3D11_MAP_READ, 0, &mapped))) {
        copy->Release();
        error = "Map staging 失败";
        return false;
    }
    std::FILE* out = std::fopen(path.c_str(), "wb");
    if (out == nullptr) {
        context->Unmap(copy, 0);
        copy->Release();
        error = "打不开输出文件";
        return false;
    }
    std::fprintf(out, "P6\n%u %u\n255\n", desc.Width, desc.Height);
    const auto* base = static_cast<const uint8_t*>(mapped.pData);
    std::vector<uint8_t> row(static_cast<size_t>(desc.Width) * 3u);
    /*
     * 顺手统计亮度（BT.709 权重）：这一行数字是"画面有没有发灰"最直接的证据 ——
     * HDR 帧如果被当成 SDR 直通，**高光会被压死**（实测 YMAX 只有 128，正确映射是 235）。
     * 有了它，native/hdr-test.ps1 就能不依赖任何外部工具做成常驻断言。
     */
    uint64_t lumaSum = 0;
    uint64_t pixelCount = 0;
    int lumaMin = 255;
    int lumaMax = 0;
    for (UINT y = 0; y < desc.Height; ++y) {
        const uint8_t* src = base + static_cast<size_t>(y) * mapped.RowPitch;
        for (UINT x = 0; x < desc.Width; ++x) {
            // 输出是 BGRA8：PPM 要 RGB
            const uint8_t r = src[x * 4 + 2];
            const uint8_t g = src[x * 4 + 1];
            const uint8_t b = src[x * 4 + 0];
            row[x * 3 + 0] = r;
            row[x * 3 + 1] = g;
            row[x * 3 + 2] = b;
            const int luma = (2126 * r + 7152 * g + 722 * b) / 10000;
            lumaSum += static_cast<uint64_t>(luma);
            ++pixelCount;
            if (luma < lumaMin) lumaMin = luma;
            if (luma > lumaMax) lumaMax = luma;
        }
        std::fwrite(row.data(), 1, row.size(), out);
    }
    std::fclose(out);
    context->Unmap(copy, 0);
    copy->Release();
    if (pixelCount > 0) {
        std::printf("[host] 首帧亮度统计: 平均=%.1f 最暗=%d 最亮=%d（BT.709 加权；发灰时最亮会被压在 130 上下）\n",
                    static_cast<double>(lumaSum) / static_cast<double>(pixelCount), lumaMin, lumaMax);
    }
    return true;
}

/** `--audio-mode` 之外的形态名（状态汇报用）。 */
const char* audioModeName(const AudioSink& sink) {
    if (!sink.isOpen()) return "none";
    return sink.modeName();
}

} // namespace

VideoPipeline::VideoPipeline(const FfmpegApi& api, GpuDevice& gpu, PipelineConfig config)
    : api(api), gpu(gpu), packets(api), frames(api), config(std::move(config)) {
    this->avOffsetMs.store(this->config.avOffsetMs);
    this->volumePercent.store(this->config.volume);
    this->loopWanted.store(this->config.loop);
    this->cpuFrame = api.av_frame_alloc();
    /*
     * 缓冲深度：按**媒体时长**限深，而不是按包数。
     *
     * <p>见 docs/PLAN-preload-buffer.md 的 L1：60 包 ≈ 1 秒，网络一抖就抽干；
     * 改成"首尾 PTS 差到 bufferTargetMs 才停读"，下载侧才真正领先播放位置若干秒。
     */
    this->packets.setSpanTargetMs(this->config.bufferTargetMs);
}

VideoPipeline::~VideoPipeline() {
    this->stopThreadsAndCleanup();
}

// ===========================================================================
// 启动
// ===========================================================================

bool VideoPipeline::start(std::string& error) {
    // ---------- 1) 打开输入 ----------
    AVDictionary* opts = nullptr;
    if (!this->config.headers.empty()) {
        this->api.av_dict_set(&opts, "headers", this->config.headers.c_str(), 0);
    }
    // UA：直播 CDN 认它（不给就是 Lavf/…，会被 403，见 main.cpp 的 kDefaultUserAgent 说明）
    if (!this->config.userAgent.empty()) {
        this->api.av_dict_set(&opts, "user_agent", this->config.userAgent.c_str(), 0);
    }
    /*
     * 直播断流自愈：CDN 抖一下、换个节点，FFmpeg 自己重连，别让整条链直接判死。
     * 与 Java 侧 FfmpegVideoSource 的选项保持一致。
     */
    this->api.av_dict_set(&opts, "reconnect", "1", 0);
    this->api.av_dict_set(&opts, "reconnect_streamed", "1", 0);
    this->api.av_dict_set(&opts, "reconnect_delay_max", "5", 0);
    this->api.av_dict_set(&opts, "rw_timeout", "15000000", 0);   // 15 秒（微秒）
    int r = this->api.avformat_open_input(&this->format, this->config.url.c_str(), nullptr, &opts);
    if (opts != nullptr) this->api.av_dict_free(&opts);
    if (r < 0) {
        error = "打开输入失败: " + ffError(this->api, r);
        return false;
    }
    r = this->api.avformat_find_stream_info(this->format, nullptr);
    if (r < 0) {
        error = "读取流信息失败: " + ffError(this->api, r);
        return false;
    }

    const AVCodec* decoderHint = nullptr;
    this->videoStreamIndex = this->api.av_find_best_stream(this->format, AVMEDIA_TYPE_VIDEO, -1, -1,
                                                           &decoderHint, 0);
    if (this->videoStreamIndex < 0 || decoderHint == nullptr) {
        error = "没有可用视频流";
        return false;
    }
    this->videoStream = this->format->streams[this->videoStreamIndex];
    const double fps = this->videoStream->avg_frame_rate.den != 0
            ? static_cast<double>(this->videoStream->avg_frame_rate.num)
              / this->videoStream->avg_frame_rate.den
            : 0.0;
    this->durationMsHint = this->videoStream->duration > 0 && this->videoStream->time_base.den > 0
            ? static_cast<long long>(this->videoStream->duration * 1000.0
                                     * this->videoStream->time_base.num / this->videoStream->time_base.den)
            : -1;
    std::printf("[host] 视频流 #%d: %s %dx%d %.3ffps 时长=%lldms 码率=%lldkbps\n",
                this->videoStreamIndex,
                this->api.avcodec_get_name(this->videoStream->codecpar->codec_id),
                this->videoStream->codecpar->width, this->videoStream->codecpar->height, fps,
                this->durationMsHint,
                this->videoStream->codecpar->bit_rate > 0
                        ? this->videoStream->codecpar->bit_rate / 1000 : -1);

    /*
     * ★ 挂中断回调：**seek 要能打断正在阻塞的网络读**。
     *
     * <p>不挂的话，seek 请求只能等 av_read_frame 自己返回（rw_timeout 最长 15 秒），
     * 表现就是"点了跳转，十秒后才动"。回调只在"有 seek 待处理 / 要退出"时返回 1。
     */
    this->format->interrupt_callback = AVIOInterruptCB{&VideoPipeline::interruptCallback, this};

    // ---------- 2) 解码器（这里开，之后只有解码线程碰它）----------
    std::string decoderError;
    if (!this->decoder.open(this->api, this->format, this->videoStreamIndex, &this->gpu, true,
                            decoderError)) {
        error = decoderError;
        return false;
    }
    this->decoderIsHardware.store(this->decoder.hardware());

    // ---------- 3) 音频 ----------
    if (this->config.audio) {
        const std::string audioUrl = this->config.audioUrl.empty() ? this->config.url
                                                                   : this->config.audioUrl;
        AudioSetupRequest request;
        request.audioUrl = audioUrl;
        request.headers = this->config.headers;
        request.userAgent = this->config.userAgent;
        request.mode = this->config.audioMode;
        request.deviceSelector = this->config.audioDevice;
        std::string setupError;
        this->audio = setupAudio(this->api, request, this->audioSink, this->audioLabel, setupError);
        if (this->audio == nullptr) {
            std::printf("[host] 音频不可用，改为纯视频: %s\n", setupError.c_str());
            this->audioLabel = "无音频";
        } else {
            /*
             * 直通模式下码流不能被改 —— 音量只能由功放控制。
             * 这里连增益都不设（设了也没用），同时把这个事实通过状态环告诉 Java 侧。
             */
            if (!this->audioSink.volumeLocked()) {
                this->audioSink.setGain(this->volumePercent.load() / 100.0f);
            }
            this->audioActive.store(true);
            this->clock.attachAudio(&this->audioSink);
            /*
             * 音频链**立刻起播**（与老版本一致）。
             *
             * <p>试过"等首帧再起播"，那样音画内容天然对齐，但**直播会出问题**：链路的连接
             * 开好之后一直没人读，等首帧（可能要几百毫秒到几秒）期间连接可能被 CDN 断掉，
             * 起来就报错 → 直接没声音。所以维持"立刻起播"，内容错位改用**首帧对表**处理
             * （见 beginEpoch：点播在首帧处把音频 seek 回同一起点，直播不动它）。
             */
            this->audio->start();
            this->audioStarted = true;
            /*
             * ★ **先把声卡按住不动，等首帧画面就绪再放**（起播对齐的关键）。
             *
             * <p>以前这里直接开声卡：从"打开音频链"到"第一帧画面出来"要 1 秒上下
             * （加载 FFmpeg / 打开输入 / 建解码器 / 建共享纹理），这段时间声音**已经在播了** ——
             * 实测起播时"可听内容"比画面领先约 1.2 秒（见 checkAudioVideoOffset 的明细）。
             * 那个偏差正好落在"没有声音可对"的窗口里，怎么对都对不上，只能靠后续纠正去追。
             * 现在改成：链路照常解码填缓冲，但设备停着；首帧就绪时再清一次缓冲、起播、锚定时钟。
             */
            this->audioSink.setPaused(true);
            std::printf("[host] 音频输出: %s（%s 链路；设备先按住，等首帧就绪再起播）\n",
                        this->audioLabel.c_str(), this->audio->kind());
        }
    }

    // ---------- 4) 控制通道 ----------
    if (!this->config.pipeName.empty()) {
        std::string pipeError;
        if (this->control.start(this->config.pipeName,
                                [this](const std::string& command) {
                                    return this->handleCommand(command);
                                },
                                pipeError)) {
            std::printf("[host] 控制通道已开启: %s\n", this->config.pipeName.c_str());
        } else {
            std::printf("[host] 控制通道开启失败（继续无控制运行）: %s\n", pipeError.c_str());
        }
    }

    // ---------- 5) 队列的"让路"判据 ----------
    /*
     * 生产者（demux）在有 seek 待处理时让路：它得去执行 av_seek_frame。
     * 消费者（解码）同理：它得去 avcodec_flush_buffers。
     * 两侧都看 stopRequested —— 停止时任何阻塞等待都要立刻返回。
     */
    this->packets.setYieldPredicates(
            [this] {
                return this->stopRequested.load() || g_parentGone.load()
                        || this->seek.generation.load() != this->seek.demuxDone.load();
            },
            [this] {
                return this->stopRequested.load() || g_parentGone.load()
                        || this->seek.generation.load() != this->seek.decodeDone.load();
            });
    this->frames.setYieldPredicates(
            [this] {
                return this->stopRequested.load() || g_parentGone.load()
                        || this->seek.generation.load() != this->seek.decodeDone.load();
            },
            [this] {
                return this->stopRequested.load() || g_parentGone.load()
                        || this->seek.generation.load() != this->seek.renderDone.load();
            });

    // ---------- 6) 起线程 ----------
    this->demuxThread = std::thread([this] { this->demuxLoop(); });
    this->decodeThread = std::thread([this] { this->decodeLoop(); });
    return true;
}

// ===========================================================================
// demux 线程
// ===========================================================================

int VideoPipeline::interruptCallback(void* opaque) {
    const auto* self = static_cast<const VideoPipeline*>(opaque);
    if (self->stopRequested.load() || g_parentGone.load()) return 1;
    // 正在做定位就别打断它自己（见 SeekState::demuxSeeking 的说明）
    if (self->seek.demuxSeeking.load()) return 0;
    return self->seek.generation.load() != self->seek.demuxDone.load() ? 1 : 0;
}

void VideoPipeline::demuxLoop() {
    AVPacket* packet = this->api.av_packet_alloc();
    while (!this->stopNow()) {
        // 有 seek 待处理：先执行定位（只有本线程能碰 AVFormatContext）
        if (this->seek.generation.load() != this->seek.demuxDone.load()) {
            this->demuxSeekStage();
            continue;
        }

        const int readResult = this->api.av_read_frame(this->format, packet);
        if (readResult < 0) {
            if (this->stopNow()) break;
            /*
             * ★ 顺序很重要：**先看"是不是被打断"，再看"是不是坏定位"**。
             *
             * <p>踩过一次：把坏定位判定放到前面，于是"读被新的 seek 打断"（AVERROR_EXIT）
             * 也被当成坏定位 —— 连点进度条时每一次打断都会触发一次多余的重定位，
             * 结果最后的落点不是用户点的那个位置（实测 seek 6000 之后跑到了 4000）。
             */
            if (this->seek.generation.load() != this->seek.demuxDone.load()) continue;
            if (readResult == AVERROR_EXIT) {
                // 被中断打断、又没有待处理的 seek（罕见）：小睡一下重读，别自旋
                this->interruptRetries.fetch_add(1);
                if (this->interruptRetries.load() > 2000) {
                    std::fprintf(stderr, "[host] 连续被中断 %d 次，按片尾处理\n",
                                 this->interruptRetries.load());
                } else {
                    std::this_thread::sleep_for(std::chrono::milliseconds(5));
                    continue;
                }
            }
            this->interruptRetries.store(0);
            /*
             * 注意：**坏定位的判定不在这里做**。
             *
             * <p>试过在 demux 侧判（"刚 seek 完就 EOF，而且读到的位置离片尾还远"），
             * 结果连点进度条时**每一次打断都像是一次坏定位** —— 多余的重定位会把落点
             * 改成"目标 − 2 秒"，用户点了 6000 却停在 4000。
             * 现在判定放在呈现侧（见 handleEndOfStream）：判据是"这个 seek 纪元连一帧
             * 都没放出来就结束了"—— 那个信息只有呈现侧才有，而且不会误判。
             */
            /*
             * 片尾（或读失败）。
             *
             * ★ 这里**绝不退出线程**：seek 请求是"登记式"的，执行者也必须是本线程 ——
             * 线程一退，"片尾之后还能不能 seek"就永久坏了（DEV-WORKFLOW §6 记着这个坑：
             * 音频侧踩过一次，症状是音画永久不同步）。
             */
            this->demuxAtEof.store(true);
            this->demuxError.store(readResult);
            if (readResult != AVERROR_EOF && readResult != AVERROR_EXIT) {
                std::printf("[host] 读包结束: %s（按片尾处理）\n",
                            ffError(this->api, readResult).c_str());
            } else {
                std::printf("[host] 已读到片尾（等控制命令：seek / loop / quit）\n");
            }
            this->packets.setEof();
            while (!this->stopNow()
                   && this->seek.generation.load() == this->seek.demuxDone.load()) {
                std::this_thread::sleep_for(std::chrono::milliseconds(10));
            }
            continue;
        }
        this->demuxAtEof.store(false);

        if (packet->stream_index != this->videoStreamIndex) {
            this->api.av_packet_unref(packet);
            continue;
        }
        /*
         * 定位之后记一下"读到哪儿了"（只用于日志与诊断）。
         *
         * <p>判定"这次定位是不是坏的"放在呈现侧（见 handleEndOfStream → requestBadSeekRetry），
         * 因为只有那里才知道"这个纪元到底出没出过画面"。demux 侧判不了：
         * 它看见的 EOF/落点都可能被下一次 seek 的正常打断污染。
         */
        if (this->awaitingFirstPacketAfterSeek.load()) {
            const long long base = this->ptsBaseMs.load();
            if (packet->pts != AV_NOPTS_VALUE && base >= 0) {
                this->lastPacketPtsMs.store(
                        this->api.av_rescale_q(packet->pts, this->videoStream->time_base,
                                               AVRational{1, 1000}) - base);
            }
            this->awaitingFirstPacketAfterSeek.store(false);
        }
        /*
         * 给包队列一个"这个包在时间轴上的位置"：队列靠它算 PTS 跨度，
         * 也就是"网络侧已经提前缓存了几秒"。用容器原始毫秒即可（跨度只看差值，
         * 减不减 ptsBaseMs 结果一样）。
         */
        const long long pktPtsMs = packet->pts == AV_NOPTS_VALUE
                ? -1
                : this->api.av_rescale_q(packet->pts, this->videoStream->time_base,
                                         AVRational{1, 1000});
        const PacketQueue::PushResult pushed =
                this->packets.push(packet, this->seek.demuxDone.load(), pktPtsMs);
        if (pushed == PacketQueue::PushResult::Aborted) {
            this->api.av_packet_unref(packet);
            break;
        }
        if (pushed == PacketQueue::PushResult::Yielded) {
            // 队列没吃下这个包（有 seek 要办）：包还在我们手里，放掉它回去执行定位
            this->api.av_packet_unref(packet);
            continue;
        }
        // 读到包了：坏定位自愈的判定窗口到此为止；同时记下"读到哪儿了"
        const long long baseForPts = this->ptsBaseMs.load();
        if (packet->pts != AV_NOPTS_VALUE && baseForPts >= 0) {
            this->lastPacketPtsMs.store(
                    this->api.av_rescale_q(packet->pts, this->videoStream->time_base,
                                           AVRational{1, 1000}) - baseForPts);
        }
        this->awaitingFirstPacketAfterSeek.store(false);
    }
    this->api.av_packet_free(&packet);
}

void VideoPipeline::demuxSeekStage() {
    const uint64_t generation = this->seek.generation.load();
    const long long target = this->seek.targetMs.load();
    this->seek.demuxSeeking.store(true);
    // 清队列里的旧包（世代标记之外的第二道保险）
    this->packets.flush();
    this->demuxAtEof.store(false);

    const int seekResult = this->seekContainerTo(
            target + (this->ptsBaseMs.load() > 0 ? this->ptsBaseMs.load() : 0));
    this->seek.videoResult.store(seekResult);
    this->seek.videoResultGeneration.store(static_cast<uint64_t>(generation));
    if (seekResult < 0) {
        std::fprintf(stderr, "[host] 视频定位失败: %s（继续按原位置读）\n",
                     ffError(this->api, seekResult).c_str());
        this->awaitingFirstPacketAfterSeek.store(false);
    } else {
        // 记下"刚定位完、还没读到包"，好吃出坏定位（见 requestBadSeekRetry）
        this->awaitingFirstPacketAfterSeek.store(true);
    }
    this->seek.demuxDone.store(generation);
    this->seek.demuxSeeking.store(false);
}

int VideoPipeline::seekContainerTo(long long containerMs) {
    const int64_t timestamp = this->api.av_rescale_q(containerMs, AVRational{1, 1000},
                                                     this->videoStream->time_base);
    /*
     * 默认用 avformat_seek_file 而不是 av_seek_frame：对 TS / 直播切片这类**没有索引**的流，
     * 前者允许给一个时间窗口，行为比"单点定位 + BACKWARD"稳（CicadaPlayer 也是这么调的：
     * 它给的是 `timestamp ± 2s` 的窗口）。过冲重试时会强制走单点定位 —— 两种方法在
     * 不同流上的表现不一样，换一种试是唯一能做的补救。
     */
    const bool plain = this->forcePlainSeek.exchange(false);
    const int64_t jitter = this->api.av_rescale_q(2000, AVRational{1, 1000},
                                                  this->videoStream->time_base);
    if (!plain && this->api.avformat_seek_file != nullptr) {
        const int r = this->api.avformat_seek_file(this->format, this->videoStreamIndex,
                                                   timestamp - jitter, timestamp, timestamp,
                                                   AVSEEK_FLAG_BACKWARD);
        if (r >= 0) return r;
    }
    return this->api.av_seek_frame(this->format, this->videoStreamIndex, timestamp,
                                   AVSEEK_FLAG_BACKWARD);
}

bool VideoPipeline::requestBadSeekRetry() {
    const int attempt = this->seekRecoveryAttempts.fetch_add(1) + 1;
    const long long target = this->seek.targetMs.load();
    long long retryMs = 0;
    const char* what = nullptr;
    if (attempt == 1) {
        // 换一种定位方法（带时间窗 <-> 单点），不同流的脾气不一样
        retryMs = target;
        what = "换单点定位重试";
        this->forcePlainSeek.store(true);
    } else if (attempt == 2) {
        retryMs = target > 2000 ? target - 2000 : 0;
        what = "退回 2 秒重试";
    } else if (attempt == 3) {
        retryMs = 0;
        what = "回到起点";
    } else {
        std::fprintf(stderr,
                     "[host] 定位自愈到此为止（试了 3 次都没出画面）—— 按片尾处理"
                     "（这个流多半是**根本不能定位**的：连 ffmpeg 自己的 -ss 都解不出帧）\n");
        return false;
    }
    std::fprintf(stderr,
                 "[host] ⚠ 定位之后一帧都没放出来就 EOF（目标 %lldms，最后读到 %lldms）"
                 "—— %s：%lldms\n",
                 target, this->lastPacketPtsMs.load(), what, retryMs);
    this->seekHealCount.fetch_add(1);
    this->mediaPositionMs.store(retryMs);
    if (this->shared.status != nullptr) {
        this->shared.status->lastSeekTargetMs = static_cast<uint64_t>(retryMs);
        this->shared.status->mediaPositionMs = static_cast<uint64_t>(retryMs);
        this->shared.status->ended = 0;
    }
    // 走正常的 seek 三阶段：世代 +1，三个线程各自把自己那一段做完
    this->requestSeek(retryMs);
    return true;
}

// ===========================================================================
// 解码线程
// ===========================================================================

/**
 * 收帧直到 EAGAIN/EOF。
 *
 * @param generation 产出这些帧的**包**所在的世代（见下面 decodeLoop 的说明）
 * @return 是否收到了至少一帧
 */
bool VideoPipeline::drainDecoderFrames(uint64_t generation) {
    bool got = false;
    AVFrame* frame = this->api.av_frame_alloc();
    if (frame == nullptr) return false;
    for (;;) {
        const VideoDecoder::ReceiveResult result = this->decoder.receiveFrame(frame);
        if (result == VideoDecoder::ReceiveResult::Again
            || result == VideoDecoder::ReceiveResult::Eof) {
            break;
        }
        if (result == VideoDecoder::ReceiveResult::Error) break;
        got = true;
        this->decodedCount.fetch_add(1);
        if (frame->format == AV_PIX_FMT_D3D11) this->hardwareFrameCount.fetch_add(1);
        const FrameQueue::PushResult pushed = this->frames.push(frame, generation);
        if (pushed != FrameQueue::PushResult::Ok) {
            // Yielded（有 seek 要办）/ Aborted（要停了）：帧还在我们手里，放掉再走
            this->api.av_frame_unref(frame);
            break;
        }
        if (this->seek.generation.load() != this->seek.decodeDone.load()) break;
    }
    this->api.av_frame_free(&frame);
    return got;
}

void VideoPipeline::decodeSeekStage() {
    const uint64_t generation = this->seek.generation.load();
    /*
     * 只清解码器，**不清帧队列**。
     *
     * <p>帧队列里的陈旧帧由"世代标记"在 pop 时丢掉（见 FrameQueue::pop），
     * 清起来反而会误伤"已经按新位置解出来的那几帧"（它们可能正是定位点之后的画面）。
     * 解码器则必须清：留着旧参考帧去解新 GOP 就是花屏/闪旧画面。
     */
    this->decoder.flush();
    /*
     * 清掉"片尾"标记：否则 seek 之后帧队列会一直报 Eof（空队列 + 片尾标记），
     * 呈现侧就会立刻当成"又放完了" —— 开着循环时这会变成无限 seek 0。
     */
    this->frames.clearEof();
    this->seek.decodeDone.store(generation);
}

void VideoPipeline::requestSoftwareFallback() {
    if (this->reopenedSoftware.exchange(true)) return;
    std::printf("[host] 硬解连续报错 → 换软解重开（画面继续，CPU 占用会上去）\n");
    /*
     * 重开解码器必须落在关键帧上，否则解码器会一直等 IDR（最长可能等一整个 GOP）。
     * 手法与 CicadaPlayer 的"回到最近关键帧"一致：请求 seek 回退 2 秒（BACKWARD 定位
     * 一定落在关键帧上），由 demux 阶段执行，其余阶段照常走。
     */
    const long long current = this->mediaPositionMs.load();
    this->requestSeek(current > 2000 ? current - 2000 : 0);
}

void VideoPipeline::decodeLoop() {
    AVPacket* packet = this->api.av_packet_alloc();
    while (!this->stopNow()) {
        // 有 seek 待处理：先清解码器
        if (this->seek.generation.load() != this->seek.decodeDone.load()) {
            this->decodeSeekStage();
            continue;
        }
        // 硬解烂了：重开成软解（与 seek 阶段同样的顺序：先 flush 再重开）
        if (this->wantSoftwareFallback.exchange(false) && this->decoder.hardware()) {
            std::string reopenError;
            const long long keepBase = this->ptsBaseMs.load();
            if (this->decoder.open(this->api, this->format, this->videoStreamIndex, &this->gpu,
                                   false, reopenError)) {
                this->decoderIsHardware.store(false);
                std::printf("[host] 解码器已换成软解重开（时间轴原点保持 %lldms）\n", keepBase);
            } else {
                std::fprintf(stderr, "[host] 换软解失败: %s（继续用硬解）\n", reopenError.c_str());
            }
        }

        uint64_t generation = this->seek.decodeDone.load();
        /*
         * ★ 帧的世代要**从包头继承**，不能就地取 decodeDone。
         *
         * <p>踩过的坑：demux 定位完立刻把新包推进队列，而解码线程可能还没执行自己的
         * "清解码器"阶段（decodeDone 还停在上一代）—— 那些包确实是定位之后的**新内容**，
         * 就地取世代会把它们标成旧的一代，于是呈现侧一过滤就全丢掉了。
         * 表现：seek 之后画面再也出不来（位置停在旧值、然后直接判片尾），
         * 而日志里一切"正常"（定位 ok、包也读了）。
         */
        const PacketQueue::PopResult popped =
                this->packets.pop(packet, generation, &generation);
        if (popped == PacketQueue::PopResult::Yielded) continue;
        if (popped == PacketQueue::PopResult::Aborted) break;
        if (popped == PacketQueue::PopResult::Eof) {
            /*
             * 片尾：送 EOS 把解码器里剩下的帧排空推下去，标好帧队列的 EOF，然后**等命令**。
             * （理由同 demux 线程：线程退了就再也没有人执行 seek 了。）
             *
             * EOS 也可能被回 EAGAIN（解码器还满着）：那就先收帧再重试，直到它收下为止。
             */
            const uint64_t eosGeneration = this->seek.decodeDone.load();
            for (int attempt = 0; attempt < 1000 && !this->stopNow(); ++attempt) {
                const VideoDecoder::SendResult eos = this->decoder.sendPacket(nullptr);
                if (eos != VideoDecoder::SendResult::Again) break;
                if (!this->drainDecoderFrames(eosGeneration)) {
                    std::this_thread::sleep_for(std::chrono::milliseconds(1));
                }
            }
            this->drainDecoderFrames(eosGeneration);
            this->frames.setEof();
            while (!this->stopNow()
                   && this->seek.generation.load() == this->seek.decodeDone.load()) {
                std::this_thread::sleep_for(std::chrono::milliseconds(10));
            }
            continue;
        }

        /*
         * ★ 正确写法：EAGAIN 时**保留这个包**，先把帧收掉再重发。
         *
         * <p>老版本是 `if (r < 0) continue;` —— EAGAIN 也是负数，于是那个包被丢掉、
         * 永远解不出对应画面（硬解缓冲吃紧时成片丢帧）。
         */
        bool keepTrying = true;
        while (keepTrying && !this->stopNow()) {
            if (this->seek.generation.load() != this->seek.decodeDone.load()) break;
            const VideoDecoder::SendResult sent = this->decoder.sendPacket(packet);
            if (sent == VideoDecoder::SendResult::Ok) {
                this->api.av_packet_unref(packet);
                keepTrying = false;
                break;
            }
            if (sent == VideoDecoder::SendResult::Again) {
                // 解码器已满：收帧腾地方，然后重发同一个包
                if (!this->drainDecoderFrames(generation)) {
                    std::this_thread::sleep_for(std::chrono::milliseconds(1));
                }
                continue;
            }
            // Error / Eof：这个包没用了
            this->api.av_packet_unref(packet);
            keepTrying = false;
        }
        if (keepTrying) this->api.av_packet_unref(packet);   // 被 seek 打断：放掉它

        this->drainDecoderFrames(generation);
        if (this->decoder.shouldFallbackToSoftware()) {
            this->wantSoftwareFallback.store(true);
            this->requestSoftwareFallback();
        }
    }
    this->api.av_packet_free(&packet);
}

// ===========================================================================
// 呈现线程（run() 就是它）
// ===========================================================================

void VideoPipeline::wakeRender() {
    std::lock_guard<std::mutex> lock(this->wakeMutex);
    this->wakeSignal.notify_all();
}

std::string VideoPipeline::handleCommand(const std::string& command) {
    std::string verb = command;
    std::string arg;
    const auto space = command.find(' ');
    if (space != std::string::npos) {
        verb = command.substr(0, space);
        arg = command.substr(space + 1);
    }

    if (verb == "hello") {
        std::string reply = "ok 1 protocol=1 ffmpeg=" + std::string(this->api.av_version_info())
                + " transport=dxgi-interop audio="
                + (this->audioActive.load() ? std::string(this->audioSink.modeName())
                                            : std::string("none"))
                + " volume_locked=" + (this->audioSink.volumeLocked() ? "1" : "0");
        /*
         * v9：身份回显（见 docs/host-uuid.md §4）。
         *
         * 追加在**末尾**，`protocol` 保持 1 —— 老解析方只看前缀 `ok` 就够，属于向后兼容的附加信息。
         * 空值时干脆不追（手动跑 exe 时可能是空串），这样对"没有身份的旧行为"零影响。
         */
        if (!this->config.instanceUuid.empty()) {
            reply += " instance=" + this->config.instanceUuid;
        }
        if (!this->config.sessionUuid.empty()) {
            reply += " session=" + this->config.sessionUuid;
        }
        return reply;
    }
    if (verb == "status") {
        char buf[768];
        /*
         * pos = 媒体时间轴上的位置（**seek 后仍然正确**，进度对齐/UI 该用的值）；
         * sinkClock = 声卡原始计数（从起播到现在的已播帧数），seek 后它与 pos 不再相等，
         * 只作为诊断参考 —— 以前只有一个 audioClock 字段，定位后看起来就像"没跳"。
         */
        std::snprintf(buf, sizeof(buf),
                      "ok state=%s pos=%lldms dur=%lldms published=%d dropped=%llu "
                      "sinkClock=%.0fms mediaClock=%.0fms audioRate=%d volume=%d paused=%d "
                      "ended=%d lastSeek=%lld audioMode=%s audioLabel=%s\n",
                      this->paused.load() ? "PAUSED" : "PLAYING",
                      this->mediaPositionMs.load(), this->durationMsHint,
                      this->publishedCount.load(),
                      static_cast<unsigned long long>(
                              this->shared.status != nullptr ? this->shared.dropped : 0),
                      this->audioActive.load() ? this->audioSink.playedMs() : 0.0,
                      this->clock.now(),
                      this->audioActive.load() ? this->audioSink.sampleRate() : 0,
                      this->volumePercent.load(), this->paused.load() ? 1 : 0,
                      this->shared.status != nullptr ? this->shared.status->ended : 0,
                      this->shared.status != nullptr
                              ? static_cast<long long>(this->shared.status->lastSeekTargetMs) : -1LL,
                      this->audioActive.load() ? this->audioSink.modeName() : "none",
                      this->audioLabel.c_str());
        return buf;
    }
    if (verb == "pause") {
        const bool want = arg == "1" || arg == "true";
        if (want && !this->paused.load()) {
            this->pausedPositionMs.store(static_cast<long long>(this->clock.now()));
        }
        this->paused.store(want);
        this->clock.setPaused(want);
        if (this->audioActive.load()) this->audioSink.setPaused(want);
        if (!want) {
            /*
             * 恢复：把"音频不连续"的时刻刷新到当下。
             *
             * <p>偏移判定要求"离不连续点 ≥4 秒"（见 checkAudioVideoOffset）。暂停期间
             * 本来就是不计判的，但**恢复后的头几秒**同样是收敛期（声卡重新 Start、时钟
             * 停滞计时器重置），拿暂停前那一秒的样本去判会误判。刷新之后，恢复后的
             * 头 4 秒只测量不动作 —— 真错位了也有后面几秒兜住。
             */
            this->lastAudioDiscontinuityAt = std::chrono::steady_clock::now();
            this->hasPrevAvOffset = false;
            this->avOffsetStreak = 0;
        }
        if (this->shared.status != nullptr) this->shared.status->paused = want ? 1u : 0u;
        this->wakeRender();
        return want ? "ok paused" : "ok resumed";
    }
    if (verb == "seek") {
        // 真正的定位由三个线程各做一段（要动 demuxer + 解码器 + 声卡缓冲 + 时钟锚点）
        long long ms = std::atoll(arg.c_str());
        if (ms < 0) ms = 0;
        // 用户发起的定位：把自愈配额重新给满（内部重试最多 3 次，见 requestBadSeekRetry）
        this->seekRecoveryAttempts.store(0);
        this->requestSeek(ms);
        return "ok seek=" + std::to_string(ms);
    }
    if (verb == "volume") {
        int v = std::atoi(arg.c_str());
        v = v < 0 ? 0 : (v > 100 ? 100 : v);
        this->volumePercent.store(v);
        /*
         * 直通模式下码流不能被修改 —— 音量由功放控制。
         * 这里仍回 "ok"（否则客户端每帧推音量会刷满日志），但带上 locked=passthrough
         * 让上层知道这个数字**没有生效**，UI 该把它显示成不可调。
         */
        if (this->audioSink.volumeLocked()) {
            return "ok volume=" + std::to_string(v) + " locked=passthrough";
        }
        if (this->audioActive.load()) this->audioSink.setGain(v / 100.0f);
        return "ok volume=" + std::to_string(v);
    }
    if (verb == "loop") {
        const bool want = arg == "1" || arg == "true";
        this->loopWanted.store(want);
        return want ? "ok loop=1" : "ok loop=0";
    }
    if (verb == "av-offset") {
        /*
         * 音画偏移现场调：正数 = 画面提前。
         *
         * 为什么做成运行期动词而不是启动参数：这条值只能**看着画面调**（物理延迟谁也测不准），
         * 每次改都重启子进程会让"调"这件事变得没法做。改完立刻生效，画面最多晚一帧。
         */
        int ms = std::atoi(arg.c_str());
        ms = ms < -5000 ? -5000 : (ms > 5000 ? 5000 : ms);
        this->avOffsetMs.store(ms);
        std::printf("[host] 音画偏移: %s %dms\n", ms > 0 ? "画面提前" : (ms < 0 ? "画面延后" : "关闭"),
                    ms < 0 ? -ms : ms);
        this->wakeRender();
        return "ok av-offset=" + std::to_string(ms);
    }
    if (verb == "quit") {
        this->stopRequested.store(true);
        this->packets.abort();
        this->frames.abort();
        this->wakeRender();
        return "ok bye";
    }
    return "err unsupported: " + verb;
}

void VideoPipeline::requestSeek(long long targetMs) {
    const long long clamped = targetMs < 0 ? 0 : targetMs;
    this->seek.targetMs.store(clamped);
    // 一次发布：三线程各自与自己的 done 比较，看到不等于就知道"轮到我做那一段了"
    this->seek.generation.fetch_add(1);
    // 立刻反映到位置/状态：上层不该在看到 seek 回执之后还读到旧进度
    this->mediaPositionMs.store(clamped);
    this->pausedPositionMs.store(clamped);
    if (this->shared.status != nullptr) {
        this->shared.status->lastSeekTargetMs = static_cast<uint64_t>(clamped);
        this->shared.status->mediaPositionMs = static_cast<uint64_t>(clamped);
        // 从"待命"里被 seek 回来：清掉片尾标记，客户端不该再看到 ENDED
        this->shared.status->ended = 0;
    }
    this->wakeRender();
}

long long VideoPipeline::audioSeekTargetMs(long long mediaTargetMs) const {
    /*
     * 音频链的容器时间轴与视频同源时，"媒体毫秒"要加回原点才是容器时间戳。
     * 老版本直接用目标毫秒：点播（原点 0）没问题，**直播**（PTS 从开播算起，实测 39 分钟）
     * 会跳到文件开头 —— 那条路本来就没被验证过。这里补上原点，点播行为完全不变。
     */
    const long long base = this->ptsBaseMs.load();
    return mediaTargetMs + (base > 0 ? base : 0);
}

void VideoPipeline::renderSeekStage() {
    const uint64_t generation = this->seek.generation.load();
    const long long target = this->seek.targetMs.load();

    /*
     * 定位优先于闸门：seek 是用户的明确意图，必须先把"等缓存"收掉（不还原播放状态，
     * 由下面的流程与 beginEpoch 决定声卡什么时候起）。
     */
    if (this->gateActive.load()) this->leaveGate(false);

    // 时钟先作废：新纪元的第一帧到达时才重新锚定（见 beginEpoch）
    this->clock.invalidate();
    this->epochStartPending = true;
    this->epochPendingIsFromSeek = true;    // 这个纪元是 seek 来的（供"定位没落地"判定）
    this->prerollTargetMs = target;

    const char* audioText = "无音频";
    if (this->audioActive.load() && this->audio != nullptr) {
        std::string seekError;
        bool ok = false;
        /*
         * ★ 先记下"已执行计数"，**再**发请求。
         *
         * <p>反过来的话有个必现的假告警：链路线程可能在我们读完计数之前就把这次定位执行完了，
         * 于是"等它超过记下的值"永远不成立 → 每次都白等 1 秒并打一行"定位没有落地"，
         * 而且那第二次清缓冲也不会发生（旧位置的残余样本就会在新位置上播出来）。
         */
        const uint64_t appliedBefore = this->audio->seekAppliedCount();
        if (this->audio->finished()) {
            /*
             * ★ 音频链已经播到片尾时，seek 之前必须把它**重新拉起来**。
             *
             * <p>片尾一到，直通链的泵线程就收工退出了，此时只 seek demuxer 没有任何用 ——
             * "音频可听位置"这个时钟从此永远冻在片尾值，画面节奏跟着它走就再也不动。
             */
            if (this->audio->restart(seekError)) {
                std::printf("[host] 音频链已在片尾退出，seek 前已重新拉起\n");
                ok = this->audio->seek(this->audioSeekTargetMs(target), seekError);
            }
        } else {
            ok = this->audio->seek(this->audioSeekTargetMs(target), seekError);
        }
        audioText = ok ? "ok" : "失败";
        if (!ok && !seekError.empty()) {
            std::printf("[host] 音频定位请求失败: %s\n", seekError.c_str());
        }
        /*
         * 清声卡缓冲：**必须连设备缓冲里还没播出去的那一段一起丢**（最多 200ms 的旧声音），
         * 不清就会在跳转后先放一段"seek 之前"的内容，音画立刻错位。
         */
        this->audioSink.flush();
        this->lastAudioDiscontinuityAt = std::chrono::steady_clock::now();
        // 收尾：等链路真的跳过去了再清一次（它可能还有几毫秒旧样本在飞，见 settleAudioSeek）
        if (ok) {
            this->audioSeekCountAtRequest = appliedBefore;
            this->audioSeekRequestedAt = std::chrono::steady_clock::now();
            this->audioSeekSettlePending = true;
        }
    }

    /*
     * 等一小会儿 demux 阶段的结果，好打出一行完整的日志。
     *
     * <p>三个阶段是并行的（各自在自己的线程上跑），所以这里拿到 generation 时 demux 可能还没执行完。
     * 至多等 150ms：这是 seek 期间，多等这一点点看不出来，但日志里"视频=ok/失败"才是有用的信息
     * （对诊断"跳转后没画面"很关键）。等不到就照实写"进行中"。
     */
    {
        const auto waitStart = std::chrono::steady_clock::now();
        while (this->seek.demuxDone.load() < generation
               && std::chrono::steady_clock::now() - waitStart < std::chrono::milliseconds(150)) {
            std::this_thread::sleep_for(std::chrono::milliseconds(2));
        }
    }
    const int videoResult = this->seek.videoResultGeneration.load() == generation
            ? this->seek.videoResult.load() : -2147483647;
    const char* videoText = videoResult == -2147483647 ? "进行中"
            : (videoResult >= 0 ? "ok" : "失败");
    std::printf("[host] seek -> %lldms（视频=%s 音频=%s）\n", target, videoText, audioText);

    this->seek.renderDone.store(generation);
}

void VideoPipeline::settleAudioSeek() {
    if (!this->audioSeekSettlePending || this->audio == nullptr) return;
    if (this->audio->seekAppliedCount() > this->audioSeekCountAtRequest) {
        // 链路已经跳过去了：此刻它写出的样本都是新位置的，再清一次把"在飞的旧样本"丢掉
        this->audioSink.flush();
        // 时钟锚点保持在原处（flush 的记账让 playedFrames 连续，见 AudioSink::playedFrames）
        this->audioSeekSettlePending = false;
        return;
    }
    const double waited = std::chrono::duration<double>(
            std::chrono::steady_clock::now() - this->audioSeekRequestedAt).count();
    if (waited > 1.0) {
        std::printf("[host] 音频定位在 1 秒内没有落地（时钟按当前值继续）\n");
        this->audioSeekSettlePending = false;
    }
}

void VideoPipeline::beginEpoch(long long mediaPtsMs) {
    if (this->audioActive.load() && this->audio != nullptr) {
        if (!this->audioStarted) {
            this->audio->start();
            this->audioStarted = true;
        }
        /*
         * ★ 首帧对表（只做一次，而且只对**点播**做）。
         *
         * <p>音频链是提前起播的：等第一帧画面解出来时，声音往往已经播了 100~1000ms
         * （环形缓冲 + 声卡缓冲），于是"画面内容 0"配着"声音内容 0.3 秒"，开局就错位。
         * 点播可以直接把音频 seek 回同一起点来解决（同一份文件，定位又快又准）。
         *
         * <p>**直播不能这么干**：直播的时间轴没有"起点"可回，seek 要么失败要么跳到
         * DVR 窗口开头，反而把声音甩到几十秒之外。所以直播保持老行为（锚定时钟、不动音频），
         * 那点内容偏差属于物理现实，靠 av-offset 那个旋钮补。
         */
        const bool vod = (this->format != nullptr && this->format->duration > 0)
                || this->durationMsHint > 0;
        if (!this->firstEpochAligned) {
            this->firstEpochAligned = true;
            std::string seekError;
            // 纪元起点在容器时间轴上就是"首帧 PTS 减去归一化原点"，首帧处即 ptsBase
            const long long containerStart = this->ptsBaseMs.load() > 0 ? this->ptsBaseMs.load() : 0;
            const uint64_t appliedBefore = this->audio->seekAppliedCount();   // 先记再发（见 renderSeekStage）
            if (vod) {
                /*
                 * ★ 点播用**重启链路**做对表，而不是"请求一个异步 seek 回到起点"。
                 *
                 * <p>为什么：异步 seek 只是"登记请求"，等链路线程下一轮开头才执行；而链路在我们的
                 * flush 之后会继续把**它当前解码位置**的内容写满整个环形缓冲（1 秒），
                 * 于是"回到起点"这件事在效果上被冲掉 —— 实测起播后"可听内容"一直比画面领先约 1.2 秒
                 * （见 checkAudioVideoOffset 打出的明细），画面跟着时钟走，就永久落后。
                 * 重启链路是**确定性**的：它从内容 0 重新开始解码，正好和首帧对齐（代价是一次重开输入，
                 * 本地点播可以忽略；DASH 也只是多一次快速请求，只在起播做一次）。
                 */
                /*
                 * ★ 重启之前必须先**打断可能卡在声卡背压里的写入**。
                 *
                 * <p>此时设备正被我们按住（暂停），环形缓冲多半是满的 → 链路解码线程卡在
                 * `sink->write()` 里 → `restart()` 内部的 join 永远等不到 → **整个子进程卡死**
                 * （实测踩过：跑了 10 分钟没退出）。`interruptWrites()` 只打断当前这次等待，
                 * 不会把声卡标记成永久停写。
                 */
                this->audioSink.interruptWrites();
                if (this->audio->restart(seekError)) {
                    std::printf("[host] 首帧对表：音频链已重头开始（与首帧对齐）\n");                } else {
                    std::printf("[host] 首帧对表（重启链路）失败，退回异步定位: %s\n", seekError.c_str());
                    if (!this->audio->seek(containerStart, seekError)) {
                        std::printf("[host] 首帧对表失败（保持原状继续）: %s\n", seekError.c_str());
                    }
                }
                this->audioSeekCountAtRequest = appliedBefore;
                this->audioSeekRequestedAt = std::chrono::steady_clock::now();
                this->audioSeekSettlePending = true;
            }
            /*
             * 无论点播还是直播，都要**清一次缓冲**再放行设备：
             * 首帧之前声卡一直停着，环形缓冲里攒的那一段（最多 1 秒）是"早于画面"的内容，
             * 不清就会在新起播时一股脑放出来 —— 正是"起播音画错位"的来源。
             */
            this->audioSink.flush();
            this->audioSink.setPaused(false);
            this->lastAudioDiscontinuityAt = std::chrono::steady_clock::now();
            std::printf("[host] 首帧就绪：声卡起播，时钟锚定在 %lldms\n", mediaPtsMs);
        }
        this->clock.anchor(static_cast<double>(mediaPtsMs));
    } else {
        this->clock.anchorWall(static_cast<double>(mediaPtsMs));
    }
}

bool VideoPipeline::ensureOutput(AVFrame* frame, std::string& error) {
    if (this->outputReady) return true;
    if (this->config.outputBase.empty()) {
        this->outputReady = true;
        return true;   // 纯解码自检：不出画面
    }

    const uint32_t visibleW = frame->width > 0 ? static_cast<uint32_t>(frame->width) : 0u;
    const uint32_t visibleH = frame->height > 0 ? static_cast<uint32_t>(frame->height) : 0u;
    if (visibleW == 0 || visibleH == 0) {
        error = "首帧没有有效尺寸";
        return false;
    }
    uint32_t codedW = visibleW;
    uint32_t codedH = visibleH;
    if (frame->format == AV_PIX_FMT_D3D11) {
        auto* texture = reinterpret_cast<ID3D11Texture2D*>(frame->data[0]);
        D3D11_TEXTURE2D_DESC desc{};
        texture->GetDesc(&desc);
        codedW = desc.Width;
        codedH = desc.Height;
    }

    this->letterbox = planOutput(visibleW, visibleH, this->config.fitAspect, this->config.outWidth,
                                 this->config.outHeight);
    if (this->letterbox.destRect.right > this->letterbox.destRect.left
        && this->letterbox.destRect.bottom > this->letterbox.destRect.top) {
        const uint32_t drawW = this->letterbox.destRect.right - this->letterbox.destRect.left;
        const uint32_t drawH = this->letterbox.destRect.bottom - this->letterbox.destRect.top;
        std::printf("[host] 信箱化开启：显示面比例 %.4f，视频 %.4f → 输出 %ux%u，画面 %ux%u @ %ld,%ld\n",
                    this->config.fitAspect, this->letterbox.videoAspect, this->letterbox.outWidth,
                    this->letterbox.outHeight, drawW, drawH, this->letterbox.destRect.left,
                    this->letterbox.destRect.top);
    }

    if (!this->converter.initOutput(this->gpu.device, this->gpu.context, this->letterbox, error)) {
        return false;
    }
    if (frame->format == AV_PIX_FMT_D3D11) {
        this->frameColor = describeVideoColor(*frame);
        if (this->frameColor.hdr()) {
            std::printf("[host] 检测到 HDR 内容: %s（将交给驱动做 HDR→SDR 色调映射）\n",
                        this->frameColor.describe().c_str());
        }
        std::string processorError;
        if (!this->converter.initProcessor(this->gpu.device, this->gpu.context, codedW, codedH,
                                           visibleW, visibleH, this->frameColor,
                                           this->config.hdrToneMap == HdrToneMapMode::Driver, processorError)) {
            /*
             * 设备没有可用的 VideoProcessor：**不是致命**。
             * 硬解帧会被回拷到 CPU 再走软解转换路径（见 presentFrame），画面照出。
             */
            std::printf("[host] VideoProcessor 不可用（%s）→ 硬解帧改为回拷 CPU 再转 BGRA\n",
                        processorError.c_str());
        }
    }
    if (!this->shared.create(this->gpu.device, this->letterbox.outWidth, this->letterbox.outHeight,
                             this->config.slots, this->gpu.luid,
                             std::wstring(this->config.outputBase.begin(),
                                          this->config.outputBase.end()),
                             std::wstring(this->config.statusName.begin(),
                                          this->config.statusName.end()),
                             this->config.sessionUuid, this->config.instanceUuid,
                             error)) {
        return false;
    }
    std::printf("[host] 共享纹理就绪: %u 槽 %ux%u（解码纹理 %ux%u），状态段 %s\n", this->config.slots,
                this->letterbox.outWidth, this->letterbox.outHeight, codedW, codedH,
                this->config.statusName.c_str());
    for (const std::wstring& name : this->shared.names) {
        std::wprintf(L"[host]   %ls\n", name.c_str());   // 便于消费者/诊断对齐
    }
    this->outputReady = true;
    return true;
}

bool VideoPipeline::presentFrame(AVFrame* frame) {
    if (this->config.outputBase.empty()) {
        // 不出画面的自检形态：只统计
        this->publishedCount.fetch_add(1);
        return true;
    }
    std::string error;
    if (!this->ensureOutput(frame, error)) {
        std::fprintf(stderr, "[host] 输出初始化失败: %s\n", error.c_str());
        this->droppedConvertCount.fetch_add(1);
        return false;
    }

    bool converted = false;
    const bool hardwareFrame = frame->format == AV_PIX_FMT_D3D11;
    /*
     * `--hdr-tonemap cpu`：HDR 内容强行走 CPU 路径（回拷 + 自己算色调映射），
     * 用来替代驱动那条曲线（驱动没有亮度旋钮，映射偏暗时只能换路）。
     */
    const VideoColorInfo frameColorNow = describeVideoColor(*frame);
    const bool forceCpuToneMap = frameColorNow.hdr()
            && this->config.hdrToneMap == HdrToneMapMode::Cpu;
    if (hardwareFrame && this->converter.hardwareReady() && !forceCpuToneMap) {
        /*
         * 流中途换色彩（广告插播 HDR、切清晰度换了色彩标签）时要重新声明一次 ——
         * applyColorInfo 内部做了去重，没变就不会白设。
         */
        this->converter.applyColorInfo(frameColorNow);
        auto* texture = reinterpret_cast<ID3D11Texture2D*>(frame->data[0]);
        const auto arrayIndex = static_cast<uint32_t>(reinterpret_cast<intptr_t>(frame->data[1]));
        converted = this->converter.convertHardware(texture, arrayIndex, error);
        this->lastFrameUsedCpuCopy = false;
    } else {
        /*
         * 软解帧（或硬解帧的**回拷兜底**）：先转 BGRA，再上传进同一张输出纹理。
         * 这条路是"硬解不可用也能出画面"的保障 —— 没有它，硬解失败就只剩进程内兜底引擎。
         */
        const AVFrame* source = frame;
        if (hardwareFrame) {
            // 设备没有可用的 VideoProcessor：把硬解帧读回 CPU 再走软解转换
            const int transferred = this->api.av_hwframe_transfer_data(this->cpuFrame, frame, 0);
            this->lastFrameUsedCpuCopy = transferred >= 0;
            if (transferred < 0) {
                error = "硬解帧回拷 CPU 失败: " + ffError(this->api, transferred);
                source = nullptr;
            } else {
                source = this->cpuFrame;
            }
        }
        if (source != nullptr) {
            const auto sourceFormat = static_cast<AVPixelFormat>(source->format);
            /*
             * ★ 色彩信息要**从硬解帧读**：`av_hwframe_transfer_data` 出来的 CPU 帧只保证像素，
             * 色彩标签不保证带过来（实测 p010le 回拷帧的 color_trc 丢了，于是 HDR 被当 SDR，
             * 表现就是"色调映射开关形同不存在、画面还是发灰"）。位深则按 CPU 帧的格式取。
             */
            VideoColorInfo color = hardwareFrame ? frameColorNow : describeVideoColor(*source);
            color.bitDepth = bitDepthOfFormat(sourceFormat);
            if (!this->scalerInitialised || sourceFormat != this->scalerSourceFormat
                || color != this->scalerColor) {
                if (!this->scaler.init(this->api, source->width, source->height, sourceFormat,
                                       color, this->letterbox,
                                       this->config.hdrToneMap != HdrToneMapMode::Off, error)) {
                    this->droppedConvertCount.fetch_add(1);
                    if (this->publishedCount.load() == 0) {
                        std::fprintf(stderr, "[host] 软解转换初始化失败: %s\n", error.c_str());
                    }
                    return false;
                }
                this->scalerInitialised = true;
                this->scalerSourceFormat = sourceFormat;
                this->scalerColor = color;
            }
            converted = this->scaler.convert(this->api, source, error)
                    && this->converter.uploadBgra(this->scaler.data(), this->scaler.pitch(), error);
        }
    }
    if (!converted) {
        this->droppedConvertCount.fetch_add(1);
        if (this->publishedCount.load() == 0) {
            std::fprintf(stderr, "[host] 帧转换失败: %s\n", error.c_str());
        }
        return false;
    }

    if (!this->shared.publish(this->converter.outputTexture2D(), error)) {
        // 消费者没取走帧（或拿不到锁）：丢这一帧。**绝不停手** —— 发帧进度不能依赖消费者
        return false;
    }
    const int published = this->publishedCount.fetch_add(1) + 1;

    if (!this->config.dumpFrame.empty() && !this->dumpedFirstFrame && published == 1) {
        std::string dumpError;
        if (dumpTextureToPpm(this->gpu.device, this->gpu.context, this->converter.outputTexture2D(),
                             this->config.dumpFrame, dumpError)) {
            std::printf("[host] 首帧已落盘（PPM）: %s\n", this->config.dumpFrame.c_str());
        } else {
            std::fprintf(stderr, "[host] 首帧落盘失败: %s\n", dumpError.c_str());
        }
        this->dumpedFirstFrame = true;
    }
    if (published % 60 == 0) {
        std::printf("[host] 已发布 %d 帧（丢弃 %llu，锁等待 last=%uus max=%uus）\n", published,
                    static_cast<unsigned long long>(this->shared.dropped),
                    this->shared.status != nullptr ? this->shared.status->lastAcquireUs : 0,
                    this->shared.status != nullptr ? this->shared.status->maxAcquireUs : 0);
    }
    return true;
}

void VideoPipeline::updateStatus() {
    if (this->shared.status == nullptr) return;
    // 暂停期间位置钉死在按下的那一刻（见 pausedPositionMs 的说明）
    const double position = this->paused.load() ? static_cast<double>(this->pausedPositionMs.load())
                                                : this->clock.now();
    const long long clamped = position < 0 ? 0 : static_cast<long long>(position);
    this->mediaPositionMs.store(clamped);
    this->shared.status->mediaPositionMs = static_cast<uint64_t>(clamped);
    this->shared.status->mediaDurationMs = this->durationMsHint > 0
            ? static_cast<uint64_t>(this->durationMsHint) : 0;
    this->shared.status->audioSampleRate = this->audioActive.load()
            ? static_cast<uint32_t>(this->audioSink.sampleRate()) : 0;
    // P4：把输出形态写进共享内存 —— 客户端据此显示"直通/独占/共享"并判断音量是否真的可调
    this->shared.status->audioMode = !this->audioActive.load() ? 0u
            : this->audioSink.isPassthrough() ? cineduct::spike::kAudioModePassthrough
            : this->audioSink.isExclusive() ? cineduct::spike::kAudioModeExclusive
            : cineduct::spike::kAudioModeShared;
    this->shared.status->audioChannels = this->audioActive.load()
            ? static_cast<uint32_t>(this->audioSink.channels()) : 0u;
    this->shared.status->audioContentRate = this->audioActive.load()
            ? static_cast<uint32_t>(this->audioSink.contentRate()) : 0u;
    this->shared.status->spatialAudio = static_cast<uint32_t>(this->audioSink.spatialAudioState());
    {
        const std::string& format = this->audioSink.spatialAudioFormat();
        std::memset(this->shared.status->spatialFormat, 0, sizeof(this->shared.status->spatialFormat));
        const size_t copy = std::min(format.size(), sizeof(this->shared.status->spatialFormat) - 1);
        std::memcpy(this->shared.status->spatialFormat, format.data(), copy);

        const std::string& device = this->audioSink.deviceName();
        std::memset(this->shared.status->audioDeviceName, 0,
                    sizeof(this->shared.status->audioDeviceName));
        const size_t deviceCopy = std::min(device.size(),
                                           sizeof(this->shared.status->audioDeviceName) - 1);
        std::memcpy(this->shared.status->audioDeviceName, device.data(), deviceCopy);
    }
    this->shared.status->paused = this->paused.load() ? 1u : 0u;

    /*
     * v4：把"现在是什么解码路径 / 有没有在缓冲 / 丢了多少 / 自愈过几次"写进状态块。
     *
     * <p>上层零 IPC 就能读到 —— 这三件事以前只能翻子进程日志才知道：
     *   * 软解回退是**自动**发生的（硬解打不开、或运行期连续报错），上层必须能显示出来，
     *     否则"风扇突然狂转 / 掉帧"没有解释；
     *   * 迟到丢帧是主时钟追赶的正常行为，但数量能区分"正常追赶"与"解码跟不上"；
     *   * 定位自愈（有些流连 ffmpeg 的 -ss 都解不出帧）发生过就要让上层能提示。
     */
    {
        const bool hard = this->decoderIsHardware.load();
        uint32_t mode = cineduct::spike::kDecodeUnknown;
        if (hard) {
            mode = this->lastFrameUsedCpuCopy ? cineduct::spike::kDecodeHardwareCopy
                                              : cineduct::spike::kDecodeHardware;
        } else if (!this->config.outputBase.empty() || this->publishedCount.load() > 0) {
            mode = cineduct::spike::kDecodeSoftware;
        }
        this->shared.status->decodeMode = mode;
        this->shared.status->buffering = this->buffering.load() ? 1u : 0u;
        /*
         * v11：缓冲水位与"等缓存"状态。
         *
         * <p>上层据此能做两件以前做不到的事：① 显示"预缓冲 1.2/3.0 秒"而不是一个不知道
         * 会不会好的转圈；② 区分"网络没供上"（packetBufferMs 低）与"游戏没在取帧"
         * （consumerStallMs 高）—— 这两种卡顿的处理方式完全不同。
         */
        this->shared.status->packetBufferMs = static_cast<uint32_t>(
                std::max(0, this->packetBufferMs()));
        this->shared.status->frameBufferMs = static_cast<uint32_t>(
                std::max(0, this->frameBufferMs()));
        this->shared.status->bufferLevelMs = static_cast<uint32_t>(
                std::max(0, this->bufferLevelMs()));
        this->shared.status->bufferTargetMs = static_cast<uint32_t>(
                std::max(0, this->config.bufferTargetMs));
        this->shared.status->gateActive = this->gateActive.load() ? 1u : 0u;
        this->shared.status->gateCount = this->gateCount.load();
        this->shared.status->consumerStallMs = this->consumerStallMs.load();
        this->shared.status->prerollMs = this->prerollWaitedMs.load();
        this->shared.status->gateTotalMs = this->gateTotalMs.load();
        this->shared.status->seekHealCount = this->seekHealCount.load();
        this->shared.status->droppedLate = static_cast<uint64_t>(this->droppedLateCount.load());
        /*
         * v5：最近发布帧的 PTS —— 与 mediaPositionMs（音频时钟）一起就能算出**音画偏移**。
         * 上层据此能显示/告警"暂停之后音画错开一段"这类问题（没这个字段只能靠人眼判断）。
         */
        const long long videoPts = this->lastPublishedPtsMs.load();
        this->shared.status->videoPtsMs = videoPts > 0 ? static_cast<uint64_t>(videoPts) : 0u;
        /*
         * v6：音画偏移自愈次数（重锚时钟）。正常播放恒为 0；>0 说明这次播放真的错开过，
         * 上层据此能给出明确事件，而不是只有一个"好像不同步"的模糊印象。
         */
        this->shared.status->clockHealCount = static_cast<uint32_t>(this->clockHealCount.load());

        /*
         * v8：音频健康。
         *
         * <p>子进程模式下音频整条链都在这里（WASAPI），游戏进程没有第二个来源能知道
         * "音频够不够、有没有断、时钟是不是已经改走挂钟了" —— 这三件事以前只打在子进程日志里，
         * 上层完全看不到（用户看到的只是"声音偶尔断一下"却无从判断）。
         */
        this->shared.status->audioUnderruns = this->audioSink.underrunCount();
        this->shared.status->audioQueuedMs = this->audioSink.queuedMsSnapshot();
        this->shared.status->audioStalled = this->clock.stalled() ? 1u : 0u;
        this->shared.status->audioFailed = this->audioActive.load() ? 0u : 1u;
        this->shared.status->audioJitterRejects =
                static_cast<uint32_t>(this->audioSink.jitterRejectCount());
        /*
         * v10：**音频链本身还活着吗**（性质不同：v8 那组是"设备有没有东西播"，是结果；
         * 这三项是"链有没有在干活"，是原因）。2026-10-17 那次静默停摆 26 秒，
         * 上层只能看到"欠载"，根本看不出链已经死了。
         */
        this->shared.status->audioReadErrors =
                this->audio != nullptr ? this->audio->readErrorCount() : 0u;
        this->shared.status->audioReconnects =
                this->audio != nullptr ? this->audio->reconnectCount() : 0u;
        this->shared.status->audioRepairs =
                static_cast<uint32_t>(this->audioRepairCount.load());
    }
}
void VideoPipeline::updateThroughput() {
    if (this->shared.status == nullptr) return;
    const auto now = std::chrono::steady_clock::now();
    const int published = this->publishedCount.load();
    const double elapsed = std::chrono::duration<double>(now - this->throughputBase).count();
    if (elapsed >= 1.0) {
        const double fps = (published - this->throughputBaseCount) / elapsed;
        this->shared.reportThroughput(static_cast<uint32_t>(fps * 100.0),
                                      this->shared.status->lastAcquireUs,
                                      this->shared.status->maxAcquireUs);
        this->throughputBase = now;
        this->throughputBaseCount = published;
    }
}

void VideoPipeline::injectDebugAudioLag() {
    if (this->config.debugAudioLagMs <= 0 || this->debugAudioLagDone) return;
    if (this->audio == nullptr || this->paused.load()) return;
    // 等播放稳下来（约 2 秒）再动手，免得把起播阶段的对表搞乱
    if (this->publishedCount.load() < 45) return;
    this->debugAudioLagDone = true;
    const long long here = this->lastPublishedPtsMs.load();
    const long long behind = here > this->config.debugAudioLagMs ? here - this->config.debugAudioLagMs : 0;
    std::string error;
    const uint64_t appliedBefore = this->audio->seekAppliedCount();
    if (this->audio->seek(this->audioSeekTargetMs(behind), error)) {
        this->audioSink.flush();
        this->audioSeekCountAtRequest = appliedBefore;
        this->audioSeekRequestedAt = std::chrono::steady_clock::now();
        this->audioSeekSettlePending = true;
        this->lastAudioDiscontinuityAt = this->audioSeekRequestedAt;
        std::fprintf(stderr,
                     "[host] 诊断：已把音频故意拖后 %dms（音频到 %lldms，画面在 %lldms）"
                     "—— 接下来应该看到「音画偏移 … 已把音频追到」\n",
                     this->config.debugAudioLagMs, behind, here);
    }
}

void VideoPipeline::checkAudioVideoOffset() {
    if (!this->audioActive.load() || this->audio == nullptr) return;   // 没音频就无所谓音画
    if (this->paused.load()) return;
    /*
     * 闸门期间不判偏移：这是一次**受控的暂停**（位置被冻住），
     * 期间的读数都在收敛，此时去"纠正"就是追自己的假象。
     */
    if (this->gateActive.load()) return;
    const auto now = std::chrono::steady_clock::now();
    if (now - this->lastAvCheckAt < std::chrono::milliseconds(500)) return;
    this->lastAvCheckAt = now;

    const long long videoPts = this->lastPublishedPtsMs.load();
    if (videoPts < 0) return;
    /*
     * ★ 偏移要拿**真正可听的内容位置**去比，不能拿时钟比。
     *
     * <p>音频时钟（clock.now()）是从 `playedFrames`（写入 − 丢弃 − 设备待播）算的，
     * 它只反映"设备的节拍"，**对内容位置一无所知**：音频链一旦把内容往回跳一段
     * （重连、seek 落地、链路重建），时钟照样平滑前进，于是"画面 vs 时钟"永远约等于 0 ——
     * 不同步就永远发现不了，也就永远纠正不了（用户报的就是这个）。
     *
     * <p>可听内容 = 最新写出样本的内容时间 − (写入 − 已播) / 采样率。
     */
    double offset = 0.0;
    bool contentBased = false;
    const double writtenContent = this->audio->writtenContentMs();
    if (writtenContent >= 0.0) {
        /*
         * 排队时长必须用"环形缓冲 + 设备待播"的真实值（见 AudioSink::queuedDurationMs 的★说明）：
         * 拿 `写入 − 已播` 会混进累计丢弃量，偏移能错一秒多。
         */
        const double queuedMs = this->audioSink.queuedDurationMs();
        double audibleContentMs = writtenContent - queuedMs;
        /*
         * ★★ 换算到"和画面同一条时间轴"上（2026-10-06 修，直播实测踩到）。
         *
         * 音频链上报的内容时间是**容器原始 PTS**，而画面用的是**减掉时间轴原点之后**的媒体时间
         * （见播放循环里的 ptsBaseMs）。点播两条都是 0 起点，看不出问题；**直播**（PTS 从开播算起）
         * 就直接差一整个开播时长 —— 实测：首帧 PTS=12923816ms、可听内容 12925382ms、画面 167ms，
         * 偏移恒为 -12925215ms，于是时钟被一次次重锚到 12.9M ms：位置显示成几小时、
         * 每 9 秒一条"音画偏移已自动纠正"的告警，音画对齐逻辑彻底失效。
         * 减去同一个原点之后，两条时间轴才可比（起播时音频领先约 1.2 秒是**预期**状态，
         * 由下面的"离不连续点 ≥4 秒"守卫负责不误判）。
         */
        const long long base = this->ptsBaseMs.load();
        if (base > 0) audibleContentMs -= static_cast<double>(base);
        offset = static_cast<double>(videoPts) - audibleContentMs;
        contentBased = true;
        this->measuredAudibleContentMs.store(static_cast<long long>(audibleContentMs));
    } else {
        // 拿不到内容位置（链路没上报）：退回"画面 vs 时钟"，只能发现时钟级别的错位
        offset = static_cast<double>(videoPts) - this->clock.now();
    }
    this->measuredAvOffsetMs.store(static_cast<long long>(offset));
    // 偏移明显时**照实记一行**（每 5 秒最多一次），这样出问题时日志里能直接看到数字
    if (std::llabs(static_cast<long long>(offset)) > kAudioLagCorrectMs
        && (this->lastAvLogAt == std::chrono::steady_clock::time_point{}
            || now - this->lastAvLogAt > std::chrono::seconds(5))) {
        this->lastAvLogAt = now;
        std::printf("[host] 音画偏移 %+.0fms（%s%s；排队 %.0fms，可听内容 %lldms，画面 %lldms，"
                    "已写帧 %lld，已播帧 %lld）\n",
                    offset, offset > 0 ? "画面领先声音" : "声音领先画面",
                    this->config.avCorrect ? "（纠正方式：把音频 seek 到画面）"
                                           : "（纠正方式：重锚时钟，不动声卡）",
                    this->audioSink.queuedDurationMs(), this->measuredAudibleContentMs.load(),
                    videoPts, static_cast<long long>(this->audioSink.framesWritten()),
                    static_cast<long long>(this->audioSink.playedFrames()));
    }
    if (this->config.avDebug || this->config.debugAudioLagMs > 0) this->verboseOffset = true;
    if (this->verboseOffset) {
        const uint32_t pending = this->audioSink.pendingFrames();
        const double queued = this->audioSink.queuedDurationMs();
        const int rate = this->audioSink.sampleRate() > 0 ? this->audioSink.sampleRate() : 48000;
        const double pendingMs = pending * 1000.0 / rate;
        std::printf("[host] AV明细: 写出内容=%.0f 排队=%.0f（环形=%.0f 设备=%.0f）可听=%.0f "
                    "画面=%.0f 已写帧=%lld 已播帧=%lld 时钟=%.0f\n",
                    writtenContent, queued, queued - pendingMs, pendingMs,
                    static_cast<double>(this->measuredAudibleContentMs.load()),
                    static_cast<double>(videoPts),
                    static_cast<long long>(this->audioSink.framesWritten()),
                    static_cast<long long>(this->audioSink.playedFrames()),
                    this->clock.now());
    }

    /*
     * 判据：
     *   * 偏移绝对值 ≥ 300ms —— 这一个帧间隔的量级（25fps≈40ms、60fps≈17ms）翻了好几倍，人耳能听出来；
     *   * **离最近一次音频不连续 ≥ 4 秒** —— seek/清缓冲之后，推算出来的"可听内容位置"会被
     *     "已丢弃但仍在计数里的队列内容"污染（实测差一秒多），那时去纠正就是追自己的假象；
     *   * 偏移**稳定**（相邻两次差 ≤ 120ms）—— 说明不是正在收敛的过程；
     *   * 连续 4 次采样（约 2 秒）都成立 —— 滤掉单帧抖动；
     *   * 距上次纠正 ≥ 5 秒 —— 限流，别反复 seek（每次都要清声卡缓冲，会听出小顿挫）。
     *
     * 两个方向都要纠正：
     *   * 偏移 > 0：画面领先声音（声音落在后面）—— 以前**完全没有机制**处理这一侧，是用户报的那个病；
     *   * 偏移 < 0：声音领先画面 —— 同样得把音频拽回来，否则也是永久的错位。
     */
    const auto steadySince = this->lastAudioDiscontinuityAt;
    const bool settled = steadySince == std::chrono::steady_clock::time_point{}
            || now - steadySince > std::chrono::seconds(4);
    const long long nowOffset = static_cast<long long>(offset);
    const bool stable = !this->hasPrevAvOffset
            || std::llabs(nowOffset - this->prevAvOffsetMs) <= 120;
    this->prevAvOffsetMs = nowOffset;
    this->hasPrevAvOffset = true;
    if (!settled || !stable) {
        // 还在不连续点的尾巴上，或偏移正在变化（收敛中）：这一轮不参与判定
        this->avOffsetStreak = 0;
        return;
    }
    if (offset > kAudioLagCorrectMs || offset < -kAudioLagCorrectMs) {
        this->avOffsetStreak++;
    } else {
        if (this->avOffsetStreak >= 2) {
            std::printf("[host] 音画偏移已回到 %.0fms（画面-音频）\n", offset);
        }
        this->avOffsetStreak = 0;
        return;
    }
    if (this->avOffsetStreak < 4) return;
    if (!this->config.avCorrect) {
        /*
         * ★ 默认路线：**重锚时钟**，不动声卡（无痕纠正）。
         *
         * <p>为什么这条才该是默认：`offset = 画面内容 − 可听内容`，它度量的就是
         * "耳朵听到的位置"和"眼睛看到的位置"的差。既然音频是主时钟，正确的做法是让
         * **时钟去承认音频内容的真实位置**（`MediaClock::anchor`），而不是去 seek 音频：
         *   * 重新锚定不动声卡缓冲一个字节 → 没有跳音、没有重放、也不需要网络；
         *   * 锚定之后 `clock.now() == 可听内容`，呈现线程自然会把画面拉回同一位置
         *     （落后就等一会儿，领先就把排队的帧丢掉追上来），同步关系必然回来；
         *   * 这也覆盖了"时钟自己跳了"这一侧（实测来源：设备待播读数在暂停/恢复附近
         *     瞬时塌成 0，见 AudioSink::setPaused 的 ★），因为锚定把时钟拉回真实内容位置。
         *
         * <p>只测量不动作是用户报的那个病：**错开了而且不纠正**。所以这里默认就动，
         * 但仍保留全部保守判据（稳定、连续 4 次、离不连续点 ≥4 秒、限流、有次数上限）。
         */
        if (!contentBased) return;                       // 拿不到内容位置就不敢动
        if (!this->clock.audioMaster()) return;          // 音频已判死：这条偏移没有意义
        if (this->seek.generation.load() != this->seek.renderDone.load()) return;   // 正在定位，别掺和
        if (this->clockHealCount.load() >= kMaxClockHeals) return;
        if (this->lastClockHealAt != std::chrono::steady_clock::time_point{}
            && now - this->lastClockHealAt < std::chrono::seconds(3)) {
            return;                                      // 限流：刚纠正过，等它生效再看
        }
        const double healTarget = static_cast<double>(this->measuredAudibleContentMs.load());
        if (healTarget < 0.0) return;
        this->lastClockHealAt = now;
        this->clockHealCount.fetch_add(1);
        this->avOffsetStreak = 0;
        this->prevAvOffsetMs = 0;
        this->hasPrevAvOffset = false;                   // 重新计稳定窗口
        this->clock.anchor(healTarget);
        /*
         * 记成一次"不连续"：锚定之后那几秒的偏移读数正在收敛，
         * 不该马上参与下一次判定（否则会连环纠正）。
         */
        this->lastAudioDiscontinuityAt = now;
        std::printf("[host] ⚠ 音画偏移 %+.0fms（%s）→ 已把**时钟**重锚到音频内容 %.0fms"
                    "（声卡不动，画面自己追上去；第 %d 次）\n",
                    offset, offset > 0 ? "画面领先声音" : "声音领先画面", healTarget,
                    this->clockHealCount.load());
        return;
    }
    if (this->lastAvCorrectionAt != std::chrono::steady_clock::time_point{}
        && now - this->lastAvCorrectionAt < std::chrono::seconds(5)) {
        return;
    }

    /*
     * 纠正：把**音频**追到画面位置。
     *
     * <p>为什么动音频而不是动画面：画面是按这条时钟走的，把画面往回拽会立刻卡顿；
     * 而音频本来就能 seek（而且这条路是非阻塞请求），追上去之后两边内容重新对齐。
     * 注意 seek 不改 `playedFrames`（写入计数是连续的），所以**时钟本身不会跳变** ——
     * 变的是"声音的内容位置"，这正是我们要修的。
     */
    std::string seekError;
    const uint64_t appliedBefore = this->audio->seekAppliedCount();
    if (!this->audio->seek(this->audioSeekTargetMs(videoPts), seekError)) {
        std::printf("[host] 音画偏移纠正失败（音频定位请求被拒）: %s\n", seekError.c_str());
        this->avOffsetStreak = 0;
        return;
    }
    this->lastAvCorrectionAt = now;
    this->avCorrectionCount.fetch_add(1);
    this->avOffsetStreak = 0;
    this->audioSink.flush();
    this->audioSeekCountAtRequest = appliedBefore;
    this->audioSeekRequestedAt = now;
    this->audioSeekSettlePending = true;
    this->lastAudioDiscontinuityAt = now;
    this->clock.anchor(static_cast<double>(videoPts));
    std::printf("[host] ⚠ 音画偏移 %+.0fms（%s）→ 已把音频对齐到 %lldms（第 %d 次纠正）\n",
                offset, offset > 0 ? "画面领先声音" : "声音领先画面", videoPts,
                this->avCorrectionCount.load());
}

void VideoPipeline::handleClockTransition(MediaClock::Transition transition) {
    if (transition == MediaClock::Transition::ToWallClock) {
        std::printf("[host] 音频时钟停滞超过 3 秒，已改为挂钟节奏（画面继续；声音可能已中断）\n");
        return;
    }
    if (transition != MediaClock::Transition::BackToAudio) return;
    /*
     * ★ 音频时钟恢复：重新对表，别让一次卡顿变成永久错位（DEV-WORKFLOW §6 的教训）。
     * 画面已经跑到音频前面（>300ms）就把**音频**追上来 —— 音频能 seek，而且那是非阻塞请求。
     */
    const double nowClock = this->clock.audioNow();
    const double videoMs = static_cast<double>(this->lastPublishedPtsMs.load());
    if (videoMs > 0 && this->audio != nullptr && videoMs - nowClock > 300.0) {
        std::string seekError;
        if (this->audio->seek(this->audioSeekTargetMs(static_cast<long long>(videoMs)), seekError)) {
            this->audioSink.flush();
            this->clock.anchor(videoMs);
            std::printf("[host] 音频时钟已恢复：音频落后画面 %.0fms，已请求音频定位到 %lldms（音画重新对齐）\n",
                        videoMs - nowClock, static_cast<long long>(videoMs));
        } else {
            std::printf("[host] 音频时钟已恢复，但音频定位请求失败：%s\n", seekError.c_str());
        }
        return;
    }
    std::printf("[host] 音频时钟已恢复（%.0fms），继续以音频为准\n", nowClock);
}

void VideoPipeline::checkAudioHealth() {
    if (!this->audioActive.load()) return;
    // 先看"链还在不在干活"（僵死修复），再看"是不是彻底废了"
    this->maybeRepairAudio();
    const bool sinkFailed = this->audioSink.outputFailed();
    const bool chainFailed = this->audio != nullptr && this->audio->failed();
    if (!sinkFailed && !chainFailed) return;
    /*
     * 音频坏了**绝不能把画面一起拖死**：改挂钟节奏继续出帧（此路无声音）。
     */
    std::printf("[host] 音频链已失败（%s），改为挂钟节奏继续出画面（此路无声音）\n",
                sinkFailed ? "声卡输出不可用" : "音频链出错");
    this->audioActive.store(false);
    this->clock.dropAudioForGood();
}

/**
 * 音频僵死看门狗（2026-10-17 事故的对症措施）。
 *
 * <p><b>那次的现场</b>：音频链把 `av_read_frame` 的**读超时**当成片尾，进"待命"后 26 秒
 * 一个字节都不再产出；时钟 3 秒后切挂钟（画面照走），而 `checkAudioVideoOffset` 里
 * `if (!clock.audioMaster()) return;` 把 AV 偏移纠正**整个禁掉** —— 于是没人去救音频，
 * 偏移从 +340ms 一路涨到 +26.3 秒，声音永久消失、画面却一直正常。
 *
 * <p>判据刻意简单：**"内容位置连续 N 秒没有前进"** 就是僵死（不管它是片尾待命、读失败、
 * 还是别的原因）。修法是让音频**追到画面当前位置**：链已 finished 就重开输入，
 * 否则发一个 seek —— 两个动作都是非阻塞的，只登记请求。
 *
 * <p>保守处：暂停不算僵死（设备本来就停）；正在 seek 不掺和（三阶段握手自己会重排）；
 * 限流 5 秒一次、总数上限 8 次 —— 别把一个坏掉的链变成无限循环。
 */
void VideoPipeline::maybeRepairAudio() {
    if (!this->audioActive.load() || this->audio == nullptr) return;
    if (this->paused.load()) {              // 暂停期间不产出是正常的
        this->audioStuckSince = std::chrono::steady_clock::time_point{};
        return;
    }
    /*
     * 闸门（等缓存/等客户端取帧）期间声卡是停着的，内容位置不前进是**预期**行为，
     * 不能当成"音频僵死"去修 —— 那会在每次卡顿时都发一次 seek，把缓冲全清掉。
     */
    if (this->gateActive.load()) {
        this->audioStuckSince = std::chrono::steady_clock::time_point{};
        return;
    }
    /*
     * ★★ 片尾待命时**绝不修**（2026-10-18 现场，玩家看到的"有规律地重新播放/重启"）。
     *
     * <p>未循环放完时的链路状态是：视频链 EOF → {@link #idleAfterEnd} 把状态环的 ended 置 1
     * 并停在最后一帧；音频链也播到片尾、自然不再产出。**这是正常终态**，不是僵死。
     *
     * <p>而这里以前当成僵死，动作是"seek 回当前时钟"——片尾时那个时钟就等于片尾，
     * 于是：seek → 播放被重新拉起 → 又到片尾 → 再判僵死 → 再 seek …… 周期性地重播最后几秒。
     * 更糟的是**那个 seek 会把 ended 位清掉**，Java 侧"片尾不算卡死"的豁免随之失效
     * （见 {@code MediaSession.checkHostHealth} 里的 endedByStatus 判据），于是 Java 8 秒后
     * 重建子进程 —— 玩家看到的就是周期性重启。
     */
    if (this->shared.status != nullptr && this->shared.status->ended != 0u) {
        this->audioStuckSince = std::chrono::steady_clock::time_point{};
        return;
    }
    // 视频链已经放到（或非常接近）片尾：同理，音频不产出是正常的
    if (this->durationMsHint > 0
        && this->mediaPositionMs.load() >= this->durationMsHint - 1500) {
        this->audioStuckSince = std::chrono::steady_clock::time_point{};
        return;
    }
    const double content = this->audio->writtenContentMs();
    if (content < 0.0) {                    // 拿不到内容位置：不判
        this->audioStuckSince = std::chrono::steady_clock::time_point{};
        return;
    }
    const auto now = std::chrono::steady_clock::now();
    if (content > this->lastAudioContentMs + 1.0) {   // 在前进（1ms 容差防浮点抖动）
        this->lastAudioContentMs = content;
        this->audioStuckSince = std::chrono::steady_clock::time_point{};
        return;
    }
    if (this->audioStuckSince == std::chrono::steady_clock::time_point{}) {
        this->audioStuckSince = now;                  // 刚开始停：先记账
        return;
    }
    if (now - this->audioStuckSince < std::chrono::seconds(kAudioStuckSeconds)) return;
    if (this->seek.generation.load() != this->seek.renderDone.load()) return;   // 正在定位
    if (this->lastAudioRepairAt != std::chrono::steady_clock::time_point{}
        && now - this->lastAudioRepairAt < std::chrono::seconds(kAudioRepairIntervalSeconds)) {
        return;
    }
    if (this->audioRepairCount.load() >= kMaxAudioRepairs) return;

    const int attempt = this->audioRepairCount.fetch_add(1) + 1;
    this->lastAudioRepairAt = now;
    this->audioStuckSince = now;            // 修完重新记账，别连环触发
    const long long target = static_cast<long long>(this->clock.now());
    const long long seekTarget = this->audioSeekTargetMs(target);
    const bool chainFinished = this->audio->finished();
    std::string repairError;
    bool requested = false;
    if (chainFinished) {
        /*
         * 片尾待命 / 重连失败后停在待命：**只请求重开输入**。
         *
         * <p>此时 `seek()` 会直接回"音频源未打开"（demuxer 已经关了）—— 那是噪音，
         * 不是问题：重开之后 {@code reconnect()} 内部会自己 seek 回上次喂到的位置。
         */
        this->audio->requestReopen();
        requested = true;
    }
    if (this->audio->seek(seekTarget, repairError)) {
        requested = true;
    } else if (!chainFinished) {
        std::printf("[host] ⚠ 音频僵死修复失败（音频定位请求被拒）: %s\n", repairError.c_str());
    }
    if (!requested) return;
    this->audioSink.flush();
    this->lastAudioDiscontinuityAt = now;   // 修复后那几秒的 AV 偏移读数在收敛，别拿去判定
    std::printf("[host] ⚠ 音频链 %.0f 秒没有产出（读失败 %u 次 / 重连 %u 次 / %s）"
                "→ 已自动修复第 %d 次（目标 %lldms）\n",
                std::chrono::duration<double>(now - this->audioStuckSince).count() + kAudioStuckSeconds,
                this->audio->readErrorCount(), this->audio->reconnectCount(),
                chainFinished ? "链已到片尾/待命，已请求重开" : "链未到片尾，直接定位",
                attempt, seekTarget);
}

void VideoPipeline::handleEndOfStream() {
    /*
     * ★ 先判"这是不是一次没落地的定位"，再决定要不要认片尾。
     *
     * <p>判据：这个 seek 纪元**连一帧都没放出来**就到了 EOF（`epochStartPending` 还是 true，
     * 而且这个纪元是 seek 产生的）。只有呈现侧知道这件事，也只有它不会被
     * "读被下一次 seek 打断"这种正常现象污染。
     *
     * <p>实测 TS 就是这个病：定位接口全都返回成功、第一个包的 PTS 也正好落在目标上，
     * 但接着读很快就 EOF（读指针被扔到文件末尾）。老代码直接认"播放结束"，
     * 用户看到的是"点了跳转 → 片尾卡住"；这里退一步（或回起点）就能继续放。
     */
    if (this->epochPendingIsFromSeek && this->epochStartPending
        && this->paused.load() == false
        && this->seekRecoveryAttempts.load() < 3) {
        if (this->requestBadSeekRetry()) return;   // 已经重新请求定位，回主循环走三阶段
    }

    const bool timeLeft = this->config.seconds <= 0
            || std::chrono::steady_clock::now() < this->deadline;
    const bool frameBudget = this->config.frames <= 0
            || this->publishedCount.load() < this->config.frames;
    if (this->loopWanted.load() && timeLeft && frameBudget) {
        // 循环播放 = "请求 seek 回起点"（三阶段握手会把音频、解码器、时钟一起重锚）
        std::printf("[host] 回卷重播：请求回到起点（音频与媒体时钟随之重锚）\n");
        this->seekRecoveryAttempts.store(0);
        this->requestSeek(0);
        return;
    }
    /*
     * ★ 播放结束（没开循环）**不直接退出进程**（生产形态）。
     *
     * 理由：进程一退出，共享纹理与状态段随之消失 —— 屏幕立刻变黑；更要命的是客户端会
     * 把它当成**崩溃**（进程没了）而触发自动重启，于是一个放完的视频会无限重播。
     * 所以转入"待命"：保持最后一帧可被反复取走，IPC 继续可用（暂停/seek/换源/开 loop）。
     * 测试形态（--seconds / 没有共享纹理）保持原来的"放完就退"，免得改动测试行为。
     */
    if (this->config.seconds <= 0 && !this->config.outputBase.empty()) {
        this->idleAfterEnd();
        return;
    }
    this->stopRequested.store(true);
}

void VideoPipeline::idleAfterEnd() {
    if (this->shared.status != nullptr) {
        this->shared.status->ended = 1;
        this->shared.status->paused = this->paused.load() ? 1u : 0u;
    }
    this->mediaPositionMs.store(this->durationMsHint > 0 ? this->durationMsHint
                                                        : this->mediaPositionMs.load());
    std::printf("[host] 播放结束（未循环）：保持最后一帧并继续待命"
                "（上层可继续 seek / 换源 / 打开 loop）\n");
    auto lastStatus = std::chrono::steady_clock::now();
    while (!this->stopNow()) {
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
        if (this->seek.generation.load() != this->seek.renderDone.load()) return;   // 要跳走
        if (this->paused.load()) continue;
        if (this->converter.outputTexture2D() != nullptr) {
            std::string error;
            /*
             * 重复发布**同一张**已转好的输出纹理：消费者每次取帧后会标记 SHOWN 把槽位还回来，
             * 之后不再有新帧填进去 —— 屏幕就会变黑。这里以 10Hz 重新发布一次，画面稳定停在最后一帧
             * （几乎没有开销：不解码、不转换，只做一次显存内拷贝）。
             */
            this->shared.publish(this->converter.outputTexture2D(), error);
        }
        if (std::chrono::steady_clock::now() - lastStatus
            > std::chrono::milliseconds(kStatusIdleIntervalMs)) {
            this->updateStatus();
            lastStatus = std::chrono::steady_clock::now();
        }
    }
}

int VideoPipeline::run() {
    const auto runStart = std::chrono::steady_clock::now();
    this->throughputBase = runStart;
    this->throughputBaseCount = 0;
    this->deadline = this->config.seconds > 0
            ? runStart + std::chrono::milliseconds(static_cast<long long>(this->config.seconds * 1000.0))
            : runStart;

    this->renderLoop();

    // ---------- 收尾 ----------
    const auto runMs = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - runStart).count();
    const int decoded = this->decodedCount.load();
    const int hardwareFrames = this->hardwareFrameCount.load();
    std::printf("[host] 解码完成: %d 帧（其中硬解帧 %d），PTS %lldms → %lldms，用时 %lldms（%.1f fps）\n",
                decoded, hardwareFrames, this->firstPtsMsValue.load(), this->lastPtsMsValue.load(),
                static_cast<long long>(runMs),
                runMs > 0 ? decoded * 1000.0 / static_cast<double>(runMs) : 0.0);
    if (this->droppedLateCount.load() > 0 || this->droppedConvertCount.load() > 0) {
        std::printf("[host] 丢帧统计: 迟到丢弃 %llu，转换失败 %llu（迟到丢弃是正常追赶行为）\n",
                    static_cast<unsigned long long>(this->droppedLateCount.load()),
                    static_cast<unsigned long long>(this->droppedConvertCount.load()));
    }

    const int published = this->publishedCount.load();
    const bool ok = decoded > 0 && published > 0;
    std::printf("[host] 结论: %s\n", !ok ? "✘ 没能解出帧"
            : hardwareFrames > 0 ? "✔ 硬解链路通过"
                                 : "✔ 软解回退链路通过（该流/该驱动用不了 d3d11va）");
    if (!this->config.outputBase.empty()) {
        std::printf("[host] M2 共享纹理: 发布 %d 帧，丢弃 %llu，锁等待 last=%uus max=%uus\n",
                    published, static_cast<unsigned long long>(this->shared.dropped),
                    this->shared.status != nullptr ? this->shared.status->lastAcquireUs : 0,
                    this->shared.status != nullptr ? this->shared.status->maxAcquireUs : 0);
        std::printf("[host] 结论: %s\n", published > 0
                ? "✔ M2 通过 —— 解码帧已发布到共享纹理，游戏进程可用 wglDXRegisterObjectNV 取用"
                : "✘ 没有发布任何帧");
    }

    if (this->audioActive.load()) {
        std::printf("[host] 音频: 共写出 %lld 帧，音频时钟停在 %.0fms\n",
                    static_cast<long long>(this->audioSink.framesWritten()),
                    this->audioSink.playedMs());
    }

    if (this->config.holdSeconds > 0 && !this->config.outputBase.empty()) {
        // 发布完不要立刻退出：共享纹理/状态段随进程消失，消费者需要时间接管
        std::printf("[host] 保活 %.1f 秒，等消费者接管…\n", this->config.holdSeconds);
        std::this_thread::sleep_for(std::chrono::milliseconds(
                static_cast<long long>(this->config.holdSeconds * 1000.0)));
    }

    this->stopThreadsAndCleanup();
    return ok ? 0 : 1;
}

// ===========================================================================
// 缓冲水位与"等缓存"闸门（v11，见 docs/PLAN-preload-buffer.md）
// ===========================================================================

int VideoPipeline::packetBufferMs() const {
    return this->packets.spanMs();
}

int VideoPipeline::frameBufferMs() const {
    /*
     * 已解码未呈现的帧折算成时长。
     *
     * <p>用"帧数 × 帧间隔"估就够（呈现侧只需要一个量级）：帧队列容量只有 3，
     * 这项最多贡献 ~50~120ms，真正的水位在包队列那侧。
     */
    const size_t queued = this->frames.size();
    if (queued == 0) return 0;
    double fps = this->videoStream != nullptr ? av_q2d(this->videoStream->avg_frame_rate) : 0.0;
    if (!(fps > 1.0) || fps > 240.0) fps = 24.0;
    const int perFrameMs = static_cast<int>(1000.0 / fps);
    const int total = perFrameMs * static_cast<int>(queued);
    return total > 5000 ? 5000 : total;
}

int VideoPipeline::bufferLevelMs() const {
    return this->packetBufferMs() + this->frameBufferMs();
}

void VideoPipeline::pollConsumerProgress() {
    using namespace cineduct::spike;
    if (this->shared.status == nullptr || this->shared.slotCount == 0) return;
    const auto now = std::chrono::steady_clock::now();
    bool progressed = false;
    for (uint32_t i = 0; i < this->shared.slotCount; ++i) {
        Status* st = this->shared.status;
        const uint32_t state = reinterpret_cast<std::atomic<uint32_t>*>(&st->slot[i].state)
                                       ->load(std::memory_order_acquire);
        if (state != kSlotShown) continue;
        const uint32_t gen = st->slot[i].generation;
        if (gen == this->lastConsumedGeneration) continue;
        /*
         * 消费者把某个槽标成 SHOWN 且帧号变了 = 它刚取走一帧。
         *
         * <p>这是**唯一**能客观判断"游戏侧还在不在取帧"的信号（跨进程，零 IPC）：
         * 所有槽都停在 READY 就说明没人来拷 —— 那正是"客户端卡顿"。
         */
        this->lastConsumedGeneration = gen;
        progressed = true;
    }
    if (progressed) {
        this->lastConsumerProgressAt = now;
        this->consumerEverSeen = true;
        this->consumerStallMs.store(0);
        return;
    }
    if (!this->consumerEverSeen) {
        // 还没人取过帧（没人在看这块屏）：不判卡顿，否则会一直冻着
        this->consumerStallMs.store(0);
        return;
    }
    const auto stalled = std::chrono::duration_cast<std::chrono::milliseconds>(
            now - this->lastConsumerProgressAt).count();
    const uint32_t clamped = stalled < 0 ? 0u : (stalled > 600000 ? 600000u
                                                                  : static_cast<uint32_t>(stalled));
    this->consumerStallMs.store(clamped);
}

bool VideoPipeline::evaluateBufferGate() {
    using clock = std::chrono::steady_clock;
    const auto now = clock::now();

    // 暂停 / 片尾 / 停止：不闸门（暂停本来就冻着，片尾要停在最后一帧）
    const bool ended = this->shared.status != nullptr && this->shared.status->ended != 0u;
    if (this->paused.load() || this->stopNow() || ended) {
        /*
         * ★ 这里**不能**用 leaveGate() 的默认行为。
         *
         * <p>默认会 "clock.setPaused(false) + audioSink.setPaused(false)"，
         * 而此刻用户刚按下暂停（声卡已经停了）—— 那样等于把暂停又顶开，声音会继续响。
         * 所以只解除闸门状态、不碰播放。
         */
        if (this->gateActive.load()) this->leaveGate(false);
        return false;
    }

    const int target = this->config.bufferTargetMs > 0 ? this->config.bufferTargetMs : 0;
    const int level = this->bufferLevelMs();
    const int supplyLow = target > 0 ? std::max(600, target / 3) : 600;
    const int supplyHigh = target > 0 ? std::max(supplyLow + 400, target * 3 / 4) : 1200;

    /*
     * ---- ① 起播预缓冲 ----
     *
     * 还没出过任何画面时，先让队列攒够 prerollMs 再放第一帧。
     * 这一步**不动时钟**（此时时钟还没锚定，声卡也还按着），只是"先不取帧"。
     *
     * <p>两个条件都要满足：水位够 **且** 已经有一帧解码好了 —— 只看水位会在
     * "包全下完了、解码线程还没出帧"的那几轮里反复判定（实测会连打四行日志）。
     */
    if (!this->prerollDone && this->publishedCount.load() == 0 && this->config.prerollMs > 0
        && !this->gateActive.load() && !this->demuxAtEof.load()) {
        if (this->prerollSince == clock::time_point{}) this->prerollSince = now;
        /*
         * 预缓冲门限不能超过"能存下的水位"。
         *
         * <p>实测陷阱：`--buffer-target-ms 400` 而 preroll 还是默认 1500 时，
         * 队列永远攒不到 1500ms（跨度上限就在 400），于是一直等到兜底上限
         * （1500+2500=4 秒）才起播 —— 白等 4 秒。
         */
        const int prerollTarget = target > 0
                ? std::min(this->config.prerollMs, std::max(300, target * 2 / 3))
                : this->config.prerollMs;
        const long long waitedMs = std::chrono::duration_cast<std::chrono::milliseconds>(
                now - this->prerollSince).count();
        // 上限：宁可少攒也不能让起播无限推迟
        const int waitCapMs = prerollTarget + 2500;
        const bool enough = level >= prerollTarget && this->frames.size() > 0;
        if (!enough && waitedMs < waitCapMs) {
            this->buffering.store(true);
            this->prerollWaitedMs.store(static_cast<uint32_t>(waitedMs < 0 ? 0 : waitedMs));
            return true;
        }
        this->prerollDone = true;
        this->prerollWaitedMs.store(static_cast<uint32_t>(waitedMs < 0 ? 0 : waitedMs));
        std::printf("[host] 起播预缓冲完成: 水位 %dms（目标 %dms，等待 %lldms）\n",
                    level, prerollTarget, waitedMs);
    }

    // ---- ② 闸门进行中：判断能不能退 ----
    if (this->gateActive.load()) {
        const uint32_t stall = this->consumerStallMs.load();
        const long long gatedMs = std::chrono::duration_cast<std::chrono::milliseconds>(
                now - this->gateSince).count();
        /*
         * 供给侧的退出条件。
         *
         * <p>`frames.size() >= 2` 也算"够了"：闸门的目的是"别让位置跑在没有数据的前面"，
         * 一旦已经解出两帧可出，继续冻着只是白白拖延。慢速网（水位永远到不了高水位）
         * 靠这一条才不会每次都冻满 6 秒。
         */
        const bool supplyOk = level >= supplyHigh || this->frames.size() >= 2
                || this->demuxAtEof.load();
        const bool consumerOk = this->config.stallGateMs <= 0
                || stall < static_cast<uint32_t>(std::max(200, this->config.stallGateMs / 3));
        /*
         * 兜底：最多冻这么久。
         *
         * <p>没有它的话，一个读不到数据的坏源、或一块没人看的屏会把位置永久冻住 ——
         * 上层只会看到"一直缓冲中"，比卡顿更糟。到点强制恢复，让上层自己去处理。
         *
         * <p>为什么是 6 秒而不是更长：Java 侧 `MediaSession.checkHostHealth` 的判据是
         * "8 秒没有新帧就重启子进程"（`STALL_TIMEOUT_MS`）。冻住期间发布帧数不涨，
         * 一旦冻超过 8 秒就会被判成"卡死"并重建 —— 那比卡顿严重得多。留 2 秒余量。
         */
        const bool timeout = gatedMs > 6000;
        if ((supplyOk && consumerOk) || timeout) {
            if (timeout) {
                std::printf("[host] ⚠ 等缓存超过 6 秒（水位 %dms）—— 强制恢复播放，不再等\n", level);
            }
            this->leaveGate();
        } else {
            this->buffering.store(true);
            return true;
        }
    }

    // 退防抖：刚退出闸门不久就别再进（否则声卡一秒停三次，听得见"咔咔"）
    const bool canEnter = this->lastGateExitAt == clock::time_point{}
            || now - this->lastGateExitAt > std::chrono::milliseconds(1200);

    /*
     * ---- ③ 供给见底 ----
     *
     * 队列里一帧都没有，而且网络侧存货低于低水位 → 冻住等数据。
     * 这就是"不会因为服务端进度往前走就把客户端丢下"的那道闸。
     */
    if (canEnter && this->config.supplyGateMs > 0 && !this->demuxAtEof.load()
        && this->frames.size() == 0 && level < supplyLow) {
        this->enterGate("等数据");
        return true;
    }

    /*
     * ---- ④ 消费者卡住（客户端卡顿）----
     *
     * 游戏进程连续 stallGateMs 没来取帧 → 冻住。恢复后从同一位置接着放，
     * 而不是让时钟继续走、把积压的帧全判成"迟到"丢掉。
     */
    if (canEnter && this->config.stallGateMs > 0 && this->consumerEverSeen
        && !this->demuxAtEof.load()
        && this->consumerStallMs.load() >= static_cast<uint32_t>(this->config.stallGateMs)) {
        this->enterGate("客户端卡顿");
        return true;
    }

    return false;
}

void VideoPipeline::enterGate(const char* reason) {
    if (this->gateActive.load()) return;
    this->gateActive.store(true);
    this->gateSince = std::chrono::steady_clock::now();
    this->gateCount.fetch_add(1);
    /*
     * 冻住时钟与声卡。
     *
     * <p>时钟在音频为主时会跟着 playedFrames 停（声卡停了计数就不动），
     * 纯挂钟模式靠 clock.setPaused 冻住。两者都做才算"位置真的不再前进"。
     */
    this->clock.setPaused(true);
    if (this->audioActive.load()) this->audioSink.setPaused(true);
    /*
     * 记一次"音频不连续"：闸门相当于一次暂停，退出后那几秒的偏移读数在收敛，
     * 不该马上参与音画纠正判定（否则一次卡顿触发一次误纠正）。
     */
    this->lastAudioDiscontinuityAt = std::chrono::steady_clock::now();
    this->buffering.store(true);
    std::printf("[host] ⏸ 进入等缓存（%s）：水位 %dms，消费者停 %ums，位置 %.0fms\n",
                reason, this->bufferLevelMs(), this->consumerStallMs.load(), this->clock.now());
}

void VideoPipeline::leaveGate(bool restorePlayback) {
    if (!this->gateActive.load()) return;
    this->gateActive.store(false);
    const auto now = std::chrono::steady_clock::now();
    const long long gatedMs = std::chrono::duration_cast<std::chrono::milliseconds>(
            now - this->gateSince).count();
    if (gatedMs > 0) this->gateTotalMs.fetch_add(static_cast<uint64_t>(gatedMs));
    this->lastGateExitAt = now;
    if (restorePlayback) {
        this->clock.setPaused(false);
        if (this->audioActive.load()) this->audioSink.setPaused(false);
        /*
         * 与"暂停恢复"同样处理：把不连续点刷新到当下、丢掉旧的偏移样本，
         * 让恢复后的头几秒只测量不动作（见 handleCommand 的 pause 分支）。
         */
        this->lastAudioDiscontinuityAt = now;
        this->hasPrevAvOffset = false;
        this->avOffsetStreak = 0;
    }
    this->buffering.store(false);
    std::printf("[host] ▶ 退出等缓存（冻住 %lldms%s，累计 %llu ms / %u 次）：位置 %.0fms\n",
                gatedMs, restorePlayback ? "" : "（交由暂停/定位处理）",
                static_cast<unsigned long long>(this->gateTotalMs.load()),
                this->gateCount.load(), this->clock.now());
}

void VideoPipeline::renderLoop() {
    AVFrame* frame = this->api.av_frame_alloc();
    auto lastStatusAt = std::chrono::steady_clock::now();

    while (!this->stopNow()) {
        // ---- 1) seek 阶段（**暂停时也要执行**：暂停中 seek 必须先跳过去再等）----
        if (this->seek.generation.load() != this->seek.renderDone.load()) {
            this->renderSeekStage();
            continue;
        }
        // ---- 2) 音频健康 / 时钟迁移 / 音画偏移纠正 ----
        this->checkAudioHealth();
        this->handleClockTransition(this->clock.tick());
        this->checkAudioVideoOffset();
        this->settleAudioSeek();
        this->injectDebugAudioLag();

        // ---- 2.5) 缓冲闸门（起播预缓冲 / 等数据 / 等客户端取帧）----
        /*
         * 诊断：`--stall-inject N` 在最开始造一次 N 毫秒的"客户端卡死"，
         * 用来验证闸门真的把位置冻住了（stall-test.ps1 用）。
         */
        if (this->config.stallInjectMs > 0 && !this->stallInjected) {
            if (this->publishedCount.load() >= 5) {
                this->stallInjected = true;
                std::printf("[host] 诊断：注入 %dms 假卡顿（验证闸门）\n", this->config.stallInjectMs);
                std::this_thread::sleep_for(std::chrono::milliseconds(this->config.stallInjectMs));
            }
        }
        this->pollConsumerProgress();
        if (this->evaluateBufferGate()) {
            this->updateStatus();
            this->updateThroughput();
            std::unique_lock<std::mutex> lock(this->wakeMutex);
            this->wakeSignal.wait_for(lock, std::chrono::milliseconds(10));
            continue;
        }

        // ---- 3) 时长/帧数上限 ----
        if (this->config.frames > 0 && this->publishedCount.load() >= this->config.frames) {
            this->stopRequested.store(true);
            break;
        }
        if (this->config.seconds > 0 && std::chrono::steady_clock::now() >= this->deadline) {
            this->stopRequested.store(true);
            break;
        }

        // ---- 4) 暂停：不取帧、不发布，但保持状态块新鲜（位置冻结）----
        /*
         * 例外：`epochStartPending`（刚 seek 完、还没放出新纪元的第一帧）时**继续往下走**，
         * 把定位点的第一帧放出去 —— 也就是"暂停中 seek 也能看到画面跳过去"。
         * 老版本做不到这一点（暂停中 seek 要等恢复播放才生效），用户会以为 seek 没响应。
         * 放完这一帧后 epochStartPending 归 false，自然又回到"暂停不动"。
         */
        if (this->paused.load() && !this->epochStartPending) {
            this->updateStatus();
            this->updateThroughput();
            std::unique_lock<std::mutex> lock(this->wakeMutex);
            this->wakeSignal.wait_for(lock, std::chrono::milliseconds(20));
            continue;
        }

        // ---- 5) 取帧 ----
        uint64_t generation = this->seek.renderDone.load();
        const FrameQueue::PopResult popped =
                this->frames.pop(frame, generation, &generation);
        if (popped == FrameQueue::PopResult::Yielded) continue;
        if (popped == FrameQueue::PopResult::Aborted) break;
        if (popped == FrameQueue::PopResult::Empty) {
            // 没帧可出 = 上层要显示的"缓冲中/等数据"（进状态块 v4）
            this->buffering.store(true);
            this->updateStatus();
            this->updateThroughput();
            this->frames.waitForData(5);
            continue;
        }
        if (popped == FrameQueue::PopResult::Eof) {
            this->handleEndOfStream();
            continue;
        }

        /*
         * ★ 暂停检查放在这里（出画面之前）。
         *
         * <p>渲染线程可能在"暂停命令到达"的同一瞬间已经取到一帧（或者正卡在等时钟的循环里）——
         * 若不管它照发，上层就会看到"暂停之后画面还在动"（EOF 回归测试里这条是精确比较的）。
         * 暂停中唯一允许出帧的例外是 **seek 之后的第一帧**：那时必须让画面跳到新位置
         * （否则暂停中 seek 会一直停在旧画面上，用户以为没生效）。
         */
        if (this->paused.load() && !this->epochStartPending) {
            this->api.av_frame_unref(frame);
            continue;
        }

        // ---- 6) 时间轴原点与位置 ----
        const long long ptsMs = frame->pts == AV_NOPTS_VALUE ? -1
                : this->api.av_rescale_q(frame->pts, this->videoStream->time_base, AVRational{1, 1000});
        if (this->ptsBaseMs.load() < 0 && ptsMs >= 0) {
            this->ptsBaseMs.store(ptsMs);
            if (ptsMs != 0) {
                std::printf("[host] 时间轴原点: 首帧 PTS=%lldms（播放在此归零；直播/非零起点流必需）\n",
                            ptsMs);
            }
        }
        if (this->firstPtsMsValue.load() < 0) this->firstPtsMsValue.store(ptsMs);
        this->lastPtsMsValue.store(ptsMs);
        const long long base = this->ptsBaseMs.load();
        const long long mediaPtsMs = (ptsMs >= 0 && base >= 0) ? ptsMs - base : ptsMs;

        // 硬解帧回拷一次做自证（"硬解链路真的出画面"这件事值得留一条证据）
        if (!this->checkedCpuTransfer && frame->format == AV_PIX_FMT_D3D11) {
            const int transferred = this->api.av_hwframe_transfer_data(this->cpuFrame, frame, 0);
            if (transferred == 0) {
                const char* name = this->api.av_get_pix_fmt_name(
                        static_cast<AVPixelFormat>(this->cpuFrame->format));
                std::printf("[host] 首帧: %dx%d 硬解帧格式=%s → CPU 回读=%s（验证硬解链路）\n",
                            frame->width, frame->height,
                            this->api.av_get_pix_fmt_name(static_cast<AVPixelFormat>(frame->format)),
                            name != nullptr ? name : "?");
            } else {
                std::printf("[host] 首帧 CPU 回读失败: %s\n", ffError(this->api, transferred).c_str());
            }
            this->checkedCpuTransfer = true;
        }

        // ---- 7) seek 前导帧丢弃（定位点是"目标之前的关键帧"）----
        if (this->prerollTargetMs >= 0 && mediaPtsMs >= 0 && mediaPtsMs < this->prerollTargetMs) {
            this->api.av_frame_unref(frame);
            continue;
        }
        if (this->prerollTargetMs >= 0 && mediaPtsMs >= this->prerollTargetMs) {
            this->prerollTargetMs = -1;
        }

        // ---- 8) 纪元开始：锚定时钟（起播 / seek 之后的第一帧，无条件渲染）----
        const bool firstFrameOfEpoch = this->epochStartPending;
        if (firstFrameOfEpoch) {
            this->beginEpoch(mediaPtsMs >= 0 ? mediaPtsMs : 0);
            this->epochStartPending = false;
            this->epochPendingIsFromSeek = false;   // 出过画面了：这个纪元以后按正常片尾处理
        }

        // ---- 9) 主时钟判定：等 / 丢 / 出 ----
        if (!firstFrameOfEpoch && mediaPtsMs >= 0) {
            /*
             * 音画偏移：正数 = 画面提前（目标更小 → 更快放出去）。
             * 减掉的是"设备把声音播出去"到"耳朵听到"之间的物理延迟补偿，
             * 所以它只作用于**以音频为准**的这条路径（挂钟路径没有声音可对）。
             */
            const double targetMs = static_cast<double>(mediaPtsMs) - this->avOffsetMs.load();
            const bool paced = this->clock.audioMaster() || this->config.pace;
            if (paced) {
                /*
                 * ★ 等"到点"，但**绝不因为等待而丢帧**。
                 *
                 * <p>这里改过两版才定下来：
                 *   ① 老版本是 `for(;;){ if (now >= target) break; sleep(2ms); }`，把整条
                 *      流水线一起堵住（读取/解码/转换全在同一个线程里）；
                 *   ② 中间那版"等一小段就把这一帧扔掉"是**错的** —— 只要时钟因为任何原因落后
                 *      （音频链还没出声、刚判过停滞、挂钟没锚上），画面就会一帧接一帧地被扔掉，
                 *      实测 4 秒只出 1 帧、解码卡在 26 帧。丢帧只能由下面那条"迟到"判据决定。
                 *
                 * <p>每次最多等 20ms：这样 clock.tick() 能持续跑（停滞/恢复判定才生效），
                 * 暂停/seek/退出也能在 20ms 内被看到。累计等满 2 秒就强行出帧 ——
                 * 时钟真出问题时画面照走，绝不饿死。
                 */
                const auto waitStart = std::chrono::steady_clock::now();
                for (;;) {
                    // 暂停/停止/seek 要立刻打断等待（否则会在这儿空等满 2 秒再发一帧 —— 见上面的★）
                    if (this->paused.load() || this->stopNow()
                        || this->seek.generation.load() != this->seek.renderDone.load()) {
                        break;
                    }
                    const double lateMs = this->clock.now() - targetMs;
                    if (lateMs >= -kEarlyGraceMs) break;
                    if (std::chrono::steady_clock::now() - waitStart
                        > std::chrono::milliseconds(kMaxPaceWaitMs)) {
                        std::printf("[host] 画面等待超过 2 秒（时钟没走到位？）—— 先出帧，避免画面饿死\n");
                        break;
                    }
                    this->handleClockTransition(this->clock.tick());
                    const double sliceMs = std::min(-lateMs + 1.0, kWaitSliceMs);
                    std::unique_lock<std::mutex> lock(this->wakeMutex);
                    this->wakeSignal.wait_for(
                            lock, std::chrono::milliseconds(static_cast<long long>(sliceMs) + 1));
                }
                /*
                 * ★ 迟到太多：丢这一帧（追赶），但**只在后面还有更新的帧时**才丢 ——
                 * 队列空着还丢就等于画面饿死（CicadaPlayer 也是这个规则：宁晚不空）。
                 */
                const double lateAfterWait = this->clock.now() - targetMs;
                if (lateAfterWait > kLateDropMs && this->frames.size() > 0) {
                    this->droppedLateCount.fetch_add(1);
                    this->api.av_frame_unref(frame);
                    continue;
                }
            }
        }

        // ---- 10) 出画面 ----
        if (this->presentFrame(frame)) {
            if (mediaPtsMs >= 0) this->lastPublishedPtsMs.store(mediaPtsMs);
            this->buffering.store(false);
        }
        this->api.av_frame_unref(frame);
        this->updateStatus();
        this->updateThroughput();

        const auto now = std::chrono::steady_clock::now();
        if (now - lastStatusAt > std::chrono::milliseconds(kStatusIdleIntervalMs)) {
            lastStatusAt = now;
        }
    }
    this->api.av_frame_free(&frame);
}

void VideoPipeline::stopThreadsAndCleanup() {
    if (this->cleanedUp) return;
    this->cleanedUp = true;

    // ---- 1) 广播停止：先让所有阻塞等待醒过来（队列 / 时钟等待 / 阻塞读）----
    this->stopRequested.store(true);
    this->packets.abort();
    this->frames.abort();
    this->wakeRender();

    // ---- 2) join 生产者（顺序：先消费者（就是本线程）不在 join 范围 → 解码 → demux）----
    if (this->decodeThread.joinable()) this->decodeThread.join();
    if (this->demuxThread.joinable()) this->demuxThread.join();

    // ---- 3) 控制通道（它可能还在回执）----
    this->control.stop();

    // ---- 4) 音频：abortWrites → 链路 close（join 得掉）→ 声卡 close ----
    this->audioSink.abortWrites();
    if (this->audio != nullptr) {
        this->audio->close();
        this->audio.reset();
    }
    this->audioSink.close();

    // ---- 5) GPU 侧 ----
    this->shared.destroy();
    this->scaler.destroy();
    this->converter.destroy();
    if (this->cpuFrame != nullptr) this->api.av_frame_free(&this->cpuFrame);
    this->decoder.close();
    if (this->format != nullptr) this->api.avformat_close_input(&this->format);
}

} // namespace cineduct::host

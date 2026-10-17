#include "video_decoder.h"

#include <chrono>
#include <cstdio>
#include <cstring>

/*
 * ★ D3D 头必须在 FFmpeg 的 hwcontext_d3d11va.h **之前**、且在 extern "C" 之外包含。
 *
 * hwcontext_d3d11va.h 自己会 #include <d3d11.h>，而 d3d11.h 里有 C++ 的 operator== 重载 ——
 * 一旦它是从 extern "C" 块里被拉进来的，就会报一堆
 *   error C2733: “==”: 无法重载具有外部 "C" 链接的函数
 * 老版本的 main.cpp 侥幸没事，只是因为它在最前面先包含了 <d3d11.h>（include guard 挡住了第二次）。
 * 依赖"谁先包含谁"是运气，所以这里显式写在前面。
 */
#include <windows.h>
#include <d3d11.h>

extern "C" {
#include <libavutil/hwcontext_d3d11va.h>
#include <libavutil/pixdesc.h>
}

namespace cineduct::host {

namespace {

/** 连续错误到这个数就认为"硬解这条链烂了"，换软解重开。 */
constexpr int kMaxConsecutiveErrors = 5;

std::string ffError(const FfmpegApi& api, int code) {
    char buf[256];
    if (api.av_strerror != nullptr && api.av_strerror(code, buf, sizeof(buf)) == 0) {
        return std::string(buf) + " (" + std::to_string(code) + ")";
    }
    return "code " + std::to_string(code);
}

/**
 * 硬解的像素格式协商回调。
 *
 * <p>必须给：不给的话 FFmpeg 用默认协商，拿到的可能不是 D3D11 纹理（在 hw_device_ctx
 * 已注入的情况下通常仍会给 D3D11，但我们只认 D3D11 —— 认不出就退回软解，绝不出错格式的帧）。
 */
AVPixelFormat pickHwFormat(AVCodecContext* context, const AVPixelFormat* formats) {
    (void) context;
    for (const AVPixelFormat* p = formats; p != nullptr && *p != AV_PIX_FMT_NONE; ++p) {
        if (*p == AV_PIX_FMT_D3D11) return *p;
    }
    return AV_PIX_FMT_NONE;
}

/** 用我们自己的 D3D11 设备建 d3d11va 硬件设备上下文（官方文档推荐的做法）。 */
AVBufferRef* createHwDevice(const FfmpegApi& ffmpeg, GpuDevice& gpu, std::string& error) {
    AVBufferRef* ref = ffmpeg.av_hwdevice_ctx_alloc(AV_HWDEVICE_TYPE_D3D11VA);
    if (ref == nullptr) {
        error = "av_hwdevice_ctx_alloc(D3D11VA) 失败";
        return nullptr;
    }
    auto* devCtx = reinterpret_cast<AVHWDeviceContext*>(ref->data);
    auto* d3d11 = reinterpret_cast<AVD3D11VADeviceContext*>(devCtx->hwctx);
    // 硬件上下文释放时会 Release 这个设备，所以先给一份引用
    gpu.device->AddRef();
    d3d11->device = gpu.device;
    const int r = ffmpeg.av_hwdevice_ctx_init(ref);
    if (r < 0) {
        error = "av_hwdevice_ctx_init 失败: " + ffError(ffmpeg, r);
        ffmpeg.av_buffer_unref(&ref);
        return nullptr;
    }
    return ref;
}

/** 这个解码器有没有 D3D11VA 的硬件配置（没有就别白试一次）。 */
bool supportsD3d11va(const FfmpegApi& ffmpeg, const AVCodec* decoder) {
    if (decoder == nullptr || ffmpeg.avcodec_get_hw_config == nullptr) return false;
    for (int i = 0;; ++i) {
        const AVCodecHWConfig* config = ffmpeg.avcodec_get_hw_config(decoder, i);
        if (config == nullptr) break;
        if (config->device_type == AV_HWDEVICE_TYPE_D3D11VA) return true;
    }
    return false;
}

} // namespace

bool VideoDecoder::open(const FfmpegApi& ffmpeg, AVFormatContext* format, int streamIndex,
                        GpuDevice* gpu, bool preferHardware, std::string& error) {
    this->close();
    this->api = &ffmpeg;

    std::string hwError;
    if (preferHardware && gpu != nullptr) {
        if (this->openInternal(ffmpeg, format, streamIndex, gpu, true, hwError)) return true;
        /*
         * ★ 硬解失败 → 软解重试（CicadaPlayer 的播放器层也是这么兜的：
         * `CreateVideoDecoder(bHW=false)` 再试一次，见 SuperMediaPlayer.cpp:3786-3801）。
         * 以前这里是直接 return 6 让整个子进程退出，代价是整条快路径都没了。
         */
        this->fallbackReasonText = hwError;
        std::printf("[host] 硬解不可用（%s）→ 改用软解（画质一致，CPU 占用更高）\n", hwError.c_str());
    }
    if (!this->openInternal(ffmpeg, format, streamIndex, gpu, false, error)) {
        if (!hwError.empty()) error = "软解也打不开: " + error + "（硬解先失败: " + hwError + "）";
        return false;
    }
    return true;
}

bool VideoDecoder::openInternal(const FfmpegApi& ffmpeg, AVFormatContext* format, int streamIndex,
                                GpuDevice* gpu, bool useHardware, std::string& error) {
    AVStream* stream = format->streams[streamIndex];
    const AVCodec* decoder = ffmpeg.avcodec_find_decoder(stream->codecpar->codec_id);
    if (decoder == nullptr) {
        error = std::string("找不到解码器: ") + ffmpeg.avcodec_get_name(stream->codecpar->codec_id);
        return false;
    }

    const bool wantHardware = useHardware && gpu != nullptr && gpu->device != nullptr
            && supportsD3d11va(ffmpeg, decoder);
    if (useHardware && !wantHardware) {
        error = std::string(ffmpeg.avcodec_get_name(stream->codecpar->codec_id))
                + " 没有 d3d11va 配置";
        return false;
    }

    AVCodecContext* context = ffmpeg.avcodec_alloc_context3(decoder);
    if (context == nullptr) {
        error = "avcodec_alloc_context3 失败";
        return false;
    }
    int r = ffmpeg.avcodec_parameters_to_context(context, stream->codecpar);
    if (r < 0) {
        error = "参数转解码上下文失败: " + ffError(ffmpeg, r);
        ffmpeg.avcodec_free_context(&context);
        return false;
    }
    // 解码器线程数交回 FFmpeg 自己决定（软解时会按 CPU 核数起帧级并行）
    context->thread_count = 0;

    AVBufferRef* hwDevice = nullptr;
    if (wantHardware) {
        std::string hwError;
        hwDevice = createHwDevice(ffmpeg, *gpu, hwError);
        if (hwDevice == nullptr) {
            error = hwError;
            ffmpeg.avcodec_free_context(&context);
            return false;
        }
        context->hw_device_ctx = ffmpeg.av_buffer_ref(hwDevice);
        context->get_format = pickHwFormat;
    }

    const auto openStart = std::chrono::steady_clock::now();
    r = ffmpeg.avcodec_open2(context, decoder, nullptr);
    const auto openMs = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - openStart).count();
    if (r < 0) {
        error = "avcodec_open2(" + std::string(decoder->name) + ") 失败: " + ffError(ffmpeg, r);
        if (hwDevice != nullptr) ffmpeg.av_buffer_unref(&hwDevice);
        ffmpeg.avcodec_free_context(&context);
        return false;
    }

    this->codecCtx = context;
    this->hwDeviceRef = hwDevice;      // 自己持有一份（codecCtx 是另一份引用）
    this->usingHardware = wantHardware;
    this->errorStreak = 0;
    this->visibleWidth = context->width > 0 ? context->width : stream->codecpar->width;
    this->visibleHeight = context->height > 0 ? context->height : stream->codecpar->height;
    this->codecNameText = decoder->name;
    std::printf("[host] 解码器就绪: %s，%s（耗时 %lldms）\n", decoder->name,
                wantHardware ? "硬解=d3d11va" : "软解=CPU", static_cast<long long>(openMs));
    return true;
}

VideoDecoder::SendResult VideoDecoder::sendPacket(const AVPacket* packet) {
    if (this->codecCtx == nullptr || this->api == nullptr) return SendResult::Error;
    const int r = this->api->avcodec_send_packet(this->codecCtx, packet);
    if (r == 0) return SendResult::Ok;
    if (r == AVERROR(EAGAIN)) return SendResult::Again;    // ★ 包必须保留，先收帧
    if (r == AVERROR_EOF) return SendResult::Eof;
    std::fprintf(stderr, "[host] 送包给解码器失败: %s\n", ffError(*this->api, r).c_str());
    this->errorStreak++;
    return SendResult::Error;
}

VideoDecoder::ReceiveResult VideoDecoder::receiveFrame(AVFrame* out) {
    if (this->codecCtx == nullptr || this->api == nullptr) return ReceiveResult::Error;
    const int r = this->api->avcodec_receive_frame(this->codecCtx, out);
    if (r == 0) {
        this->errorStreak = 0;      // 出帧即证明这条链还在正常工作
        const int w = out->width > 0 ? out->width : this->codecCtx->width;
        const int h = out->height > 0 ? out->height : this->codecCtx->height;
        if (w > 0 && h > 0) {
            this->visibleWidth = w;
            this->visibleHeight = h;
        }
        return ReceiveResult::Got;
    }
    if (r == AVERROR(EAGAIN)) return ReceiveResult::Again;
    if (r == AVERROR_EOF) return ReceiveResult::Eof;
    /*
     * 坏帧（`FF_DECODE_ERROR_INVALID_BITSTREAM`）也走这里：FFmpeg 会带着
     * decode_error_flags 继续给帧，不算致命 —— 所以这里只计数，不上报错误。
     */
    std::fprintf(stderr, "[host] 解码取帧出错: %s\n", ffError(*this->api, r).c_str());
    this->errorStreak++;
    return ReceiveResult::Error;
}

bool VideoDecoder::shouldFallbackToSoftware() const {
    return this->usingHardware && this->errorStreak >= kMaxConsecutiveErrors;
}

void VideoDecoder::flush() {
    if (this->codecCtx == nullptr || this->api == nullptr) return;
    this->api->avcodec_flush_buffers(this->codecCtx);
    this->errorStreak = 0;
}

void VideoDecoder::close() {
    if (this->api != nullptr) {
        if (this->codecCtx != nullptr) this->api->avcodec_free_context(&this->codecCtx);
        if (this->hwDeviceRef != nullptr) this->api->av_buffer_unref(&this->hwDeviceRef);
    } else {
        this->codecCtx = nullptr;
        this->hwDeviceRef = nullptr;
    }
    this->usingHardware = false;
    this->errorStreak = 0;
}

} // namespace cineduct::host

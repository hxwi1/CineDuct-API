#include "sw_scale.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>

namespace cineduct::host {

namespace {

/** 色调映射的目标峰值：把 HDR 内容压到 SDR 白点（100 nits 的显示亮度是行业惯例）。 */
constexpr double kHdrPeakNits = 1000.0;
constexpr double kSdrWhiteNits = 100.0;
/** 线性域的定点化刻度（16 位够用：8bit 输出只需要几位精度）。 */
constexpr double kLinearScale = 65535.0;

/** PQ（ST 2084）反 EOTF：0..1 的编码值 → 绝对亮度（nits，1.0 = 10000 nits）。 */
double pqToNits(double encoded) {
    constexpr double m1 = 0.1593017578125;
    constexpr double m2 = 78.84375;
    constexpr double c1 = 0.8359375;
    constexpr double c2 = 18.8515625;
    constexpr double c3 = 18.6875;
    const double e = std::pow(std::max(0.0, std::min(1.0, encoded)), 1.0 / m2);
    const double num = std::max(e - c1, 0.0);
    const double den = c2 - c3 * e;
    if (den <= 0.0) return 0.0;
    return 10000.0 * std::pow(num / den, 1.0 / m1);
}

/** HLG 反 OETF（近似：把 0..1 编码值还原成 0..1 相对亮度）。 */
double hlgToRelative(double encoded) {
    constexpr double a = 0.17883277;
    constexpr double b = 0.28466892;
    constexpr double c = 0.55991073;
    const double e = std::max(0.0, std::min(1.0, encoded));
    if (e <= 0.5) return (e * e) / 3.0;
    return (std::exp((e - c) / a) + b) / 12.0;
}

/**
 * 色调曲线：线性亮度（0..1，1 = kHdrPeakNits）→ SDR 线性（0..1）。
 *
 * <p>用 Reinhard 带肩部（`x*(1+x/white^2)/(1+x)`）—— 比纯 Reinhard 保留一点高光，
 * 又比"硬裁剪"自然。这不是调色级的算法，但足以让 HDR 内容在 SDR 屏上**不发灰、不过曝**。
 */
double toneCurve(double linear, double whiteSquared) {
    return linear * (1.0 + linear / whiteSquared) / (1.0 + linear);
}

/** 线性（0..1）→ BT.709 gamma 8bit。 */
uint8_t encodeSdr(double linear) {
    const double clamped = std::max(0.0, std::min(1.0, linear));
    const double gamma = std::pow(clamped, 1.0 / 2.2);
    return static_cast<uint8_t>(std::lround(gamma * 255.0));
}

} // namespace

bool SwScaler::init(const FfmpegApi& ffmpeg, int srcWidth, int srcHeight, AVPixelFormat srcFormat,
                    const VideoColorInfo& frameColor, const LetterboxPlan& plan, bool toneMapHdr,
                    std::string& error) {
    this->destroy();
    this->api = &ffmpeg;
    this->color = frameColor;
    if (srcWidth <= 0 || srcHeight <= 0 || plan.outWidth == 0 || plan.outHeight == 0) {
        error = "SwScaler 参数非法";
        return false;
    }
    if (!ffmpeg.swscaleReady()) {
        error = "这套 FFmpeg 没有 swscale（软解回退不可用）";
        return false;
    }

    // 画面落点：空矩形 = 铺满输出
    this->destRect = plan.destRect;
    if (this->destRect.right <= this->destRect.left || this->destRect.bottom <= this->destRect.top) {
        this->destRect = D3D11_RECT{0, 0, static_cast<LONG>(plan.outWidth),
                                    static_cast<LONG>(plan.outHeight)};
    }
    this->drawWidth = this->destRect.right - this->destRect.left;
    this->drawHeight = this->destRect.bottom - this->destRect.top;
    this->outputPitch = plan.outWidth * 4u;
    this->buffer.assign(static_cast<size_t>(this->outputPitch) * plan.outHeight, 0);

    /*
     * HDR 时中间必须是 **16 位 RGB**：PQ 在暗部极其非线性，8 位中间量会把暗部阶梯化。
     * 而且色域矩阵（BT.2020 → BT.709）要在**线性光**下做，所以中间结果保留 16 位精度。
     */
    this->toneMapActive = frameColor.hdr() && toneMapHdr;
    const AVPixelFormat dstFormat = this->toneMapActive ? AV_PIX_FMT_RGB48LE : AV_PIX_FMT_BGRA;
    const int dstWidth = this->toneMapActive ? srcWidth : this->drawWidth;
    const int dstHeight = this->toneMapActive ? srcHeight : this->drawHeight;

    this->sws = ffmpeg.sws_getContext(srcWidth, srcHeight, srcFormat, dstWidth, dstHeight,
                                      dstFormat, SWS_BILINEAR, nullptr, nullptr, nullptr);
    if (this->sws == nullptr) {
        error = "sws_getContext 失败（源格式不支持？）";
        return false;
    }
    /*
     * 告诉 swscale 源是 BT.2020/有限范围（YUV→RGB 的矩阵与范围必须对，否则颜色本身就偏）。
     *
     * ★ 两张系数表都要给全：`sws_setColorspaceDetails` 的 `table` 是**目标**系数表，
     * 传 nullptr 会让 swscale 内部拿空指针去索引 → 访问违例（实测崩过一次，
     * 0xC0000005，minidump 就落在 native/build/bin/）。缺符号就干脆不调，走 swscale 默认。
     */
    if (ffmpeg.sws_getCoefficients != nullptr && ffmpeg.sws_setColorspaceDetails != nullptr) {
        const int* srcTable = (this->color.bt2020 || this->color.hdr())
                ? ffmpeg.sws_getCoefficients(SWS_CS_BT2020)
                : ffmpeg.sws_getCoefficients(SWS_CS_ITU709);
        const int* dstTable = ffmpeg.sws_getCoefficients(SWS_CS_ITU709);
        if (srcTable != nullptr && dstTable != nullptr) {
            ffmpeg.sws_setColorspaceDetails(this->sws, srcTable, this->color.fullRange ? 1 : 0,
                                            dstTable, 1, 0, 1 << 16, 1 << 16);
        }
    }
    if (this->toneMapActive) {
        this->rgba48.assign(static_cast<size_t>(srcWidth) * srcHeight * 3u, 0);
        this->buildToneMapTables();
        std::printf("[host] 软解路径启用 CPU 色调映射: %s → SDR(峰值 %.0fnits → 白点 %.0fnits)\n",
                    this->color.describe().c_str(), kHdrPeakNits, kSdrWhiteNits);
    }
    const char* formatName = ffmpeg.av_get_pix_fmt_name(srcFormat);
    std::printf("[host] 软解转换就绪: %dx%d %s → %dx%d %s（输出 %ux%u）\n", srcWidth, srcHeight,
                formatName != nullptr ? formatName : "?", dstWidth, dstHeight,
                this->toneMapActive ? "RGB48(色调映射)" : "BGRA", plan.outWidth, plan.outHeight);
    return true;
}

void SwScaler::buildToneMapTables() {
    const bool pq = this->color.transfer == VideoTransfer::Pq;
    /*
     * 峰值取多少**直接决定画面亮度**：
     *   * 内容自报的 MaxCLL / mastering 峰值最准（HDR10 一般都有，1000 或 4000 nit）；
     *   * 没元数据才退回 1000 nit（业界常用的 HDR10 默认值）。
     * 取错的后果很直观：把"内容其实只到 100 nit"当成 1000 nit 去压，画面就会明显偏暗；
     * 把 10000 nit 的内容按 1000 nit 压同样会过暗（实测素材 zscale 标到了 10000 nit，
     * 用默认 1000 压出来平均亮度只有 12，改用内容自报峰值后就正常了）。
     */
    double peak = pq ? kHdrPeakNits : 12.0;
    if (pq) {
        if (this->color.maxContentLightLevel >= 100) {
            peak = this->color.maxContentLightLevel;
        } else if (this->color.masteringMaxLuminanceNits >= 100) {
            peak = this->color.masteringMaxLuminanceNits;
        }
    }
    const double whiteSquared = (peak / kSdrWhiteNits) * (peak / kSdrWhiteNits);
    this->linearLut.assign(4096, 0);
    this->gammaLut.assign(4096, 0);
    for (int i = 0; i < 4096; ++i) {
        // 16 位输入值 → 0..1 编码值（P010 的 10 位有效位左移 6 位，与 RGB48 的刻度对齐）
        const double encoded = static_cast<double>(i) / 4095.0;
        double nits = 0.0;
        if (pq) {
            nits = pqToNits(encoded);
        } else {
            nits = hlgToRelative(encoded) * 1000.0;    // HLG 相对亮度 → 参考 1000 nits 显示
        }
        this->linearLut[i] = static_cast<uint16_t>(
                std::lround(std::min(1.0, nits / peak) * kLinearScale));
    }
    for (int i = 0; i < 4096; ++i) {
        const double linear = static_cast<double>(i) / 4095.0;
        this->gammaLut[i] = encodeSdr(toneCurve(linear, whiteSquared));
    }
}

void SwScaler::toneMapRows(const uint16_t* quad, int quadPitchBytes, int rows) {
    const size_t quadStride = static_cast<size_t>(quadPitchBytes) / sizeof(uint16_t);
    /*
     * BT.2020 → BT.709 的线性光色域矩阵（行归一化，行和 ≈ 1）。
     * 不做它的话，BT.2020 的绿色会被当成 BT.709 的绿色 → 整幅偏绿/偏艳。
     */
    constexpr double m00 = 1.6605, m01 = -0.5876, m02 = -0.0728;
    constexpr double m10 = -0.1246, m11 = 1.1329, m12 = -0.0083;
    constexpr double m20 = -0.0182, m21 = -0.1006, m22 = 1.1187;
    const int top = std::max(0, static_cast<int>(this->destRect.top));
    const int left = std::max(0, static_cast<int>(this->destRect.left));
    const int drawW = std::min(this->drawWidth, static_cast<int>(this->outputPitch / 4) - left);
    const int drawH = std::min(this->drawHeight, rows - top);
    for (int y = 0; y < drawH; ++y) {
        const uint16_t* src = quad + static_cast<size_t>(y) * quadStride;
        uint8_t* dst = this->buffer.data() + static_cast<size_t>(top + y) * this->outputPitch
                + static_cast<size_t>(left) * 4u;
        for (int x = 0; x < drawW; ++x) {
            // RGB48 → 线性（查表；每个通道独立反 EOTF）
            const double r = this->linearLut[src[x * 3 + 0] >> 4] / kLinearScale;
            const double g = this->linearLut[src[x * 3 + 1] >> 4] / kLinearScale;
            const double b = this->linearLut[src[x * 3 + 2] >> 4] / kLinearScale;
            // 色域矩阵（线性光）
            double rr = m00 * r + m01 * g + m02 * b;
            double gg = m10 * r + m11 * g + m12 * b;
            double bb = m20 * r + m21 * g + m22 * b;
            rr = std::max(0.0, std::min(1.0, rr));
            gg = std::max(0.0, std::min(1.0, gg));
            bb = std::max(0.0, std::min(1.0, bb));
            // 色调曲线 + BT.709 gamma 编码（查表）
            dst[x * 4 + 0] = this->gammaLut[static_cast<int>(bb * 4095.0)];
            dst[x * 4 + 1] = this->gammaLut[static_cast<int>(gg * 4095.0)];
            dst[x * 4 + 2] = this->gammaLut[static_cast<int>(rr * 4095.0)];
            dst[x * 4 + 3] = 255;
        }
    }
}

void SwScaler::clearBorders() {
    if (this->buffer.empty()) return;
    const size_t pitch = this->outputPitch;
    const int height = static_cast<int>(this->buffer.size() / pitch);
    const int left = this->destRect.left;
    const int right = this->destRect.right;
    const int top = this->destRect.top;
    const int bottom = this->destRect.bottom;
    if (left == 0 && top == 0 && right == static_cast<int>(pitch / 4) && bottom == height) {
        return;   // 铺满：不用清（软解路径的常见情况）
    }
    // 上/下整条
    for (int y = 0; y < top && y < height; ++y) {
        std::memset(this->buffer.data() + static_cast<size_t>(y) * pitch, 0, pitch);
    }
    for (int y = std::max(bottom, 0); y < height; ++y) {
        std::memset(this->buffer.data() + static_cast<size_t>(y) * pitch, 0, pitch);
    }
    // 中间行的左右黑边
    for (int y = std::max(top, 0); y < std::min(bottom, height); ++y) {
        uint8_t* row = this->buffer.data() + static_cast<size_t>(y) * pitch;
        if (left > 0) std::memset(row, 0, static_cast<size_t>(left) * 4u);
        const int rightStart = std::min(right, static_cast<int>(pitch / 4));
        if (rightStart < static_cast<int>(pitch / 4)) {
            std::memset(row + static_cast<size_t>(rightStart) * 4u, 0,
                        (pitch / 4 - static_cast<size_t>(rightStart)) * 4u);
        }
    }
}

bool SwScaler::convert(const FfmpegApi& ffmpeg, const AVFrame* frame, std::string& error) {
    if (this->sws == nullptr || frame == nullptr) {
        error = "SwScaler 未就绪";
        return false;
    }
    this->clearBorders();

    if (this->toneMapActive) {
        // HDR：先转 16 位 RGB（BT.2020 矩阵），再自己走色调映射写进 BGRA 输出缓冲
        uint8_t* dst48 = reinterpret_cast<uint8_t*>(this->rgba48.data());
        uint8_t* dstData[4] = {dst48, nullptr, nullptr, nullptr};
        int dstStride[4] = {frame->width * 6, 0, 0, 0};
        const uint8_t* const* srcData = const_cast<const uint8_t* const*>(frame->data);
        const int srcStride[4] = {frame->linesize[0], frame->linesize[1], frame->linesize[2],
                                  frame->linesize[3]};
        const int scaled = ffmpeg.sws_scale(this->sws, srcData, srcStride, 0, frame->height,
                                            dstData, dstStride);
        if (scaled <= 0) {
            error = "sws_scale（HDR → RGB48）失败";
            return false;
        }
        this->toneMapRows(this->rgba48.data(), dstStride[0], frame->height);
        return true;
    }

    /*
     * 只把画面写进落点那一块：把目标指针偏到 destRect 的左上角、行距仍用整张输出的行距，
     * sws 就会把 drawWidth×drawHeight 的图像逐行写进去，黑边原样保留。
     */
    uint8_t* dstData[4] = {
            this->buffer.data() + static_cast<size_t>(this->destRect.top) * this->outputPitch
                    + static_cast<size_t>(this->destRect.left) * 4u,
            nullptr, nullptr, nullptr};
    int dstStride[4] = {static_cast<int>(this->outputPitch), 0, 0, 0};
    const uint8_t* const* srcData = const_cast<const uint8_t* const*>(frame->data);
    const int srcStride[4] = {frame->linesize[0], frame->linesize[1], frame->linesize[2],
                              frame->linesize[3]};
    const int scaled = ffmpeg.sws_scale(this->sws, srcData, srcStride, 0, frame->height, dstData,
                                        dstStride);
    if (scaled <= 0) {
        error = "sws_scale 失败";
        return false;
    }
    return true;
}

void SwScaler::destroy() {
    if (this->sws != nullptr) {
        if (this->api != nullptr) {
            this->api->sws_freeContext(this->sws);
        }
        this->sws = nullptr;
    }
    this->buffer.clear();
    this->buffer.shrink_to_fit();
    this->rgba48.clear();
    this->rgba48.shrink_to_fit();
    this->linearLut.clear();
    this->gammaLut.clear();
    this->toneMapActive = false;
    this->outputPitch = 0;
    this->drawWidth = 0;
    this->drawHeight = 0;
    this->destRect = D3D11_RECT{0, 0, 0, 0};
}

} // namespace cineduct::host

// ============================================================================
// 帧的色彩信息（SDR / HDR10-PQ / HLG、BT.2020、范围、位深）
//
// **为什么需要它**：D3D11 的 VideoProcessor 默认按"输入是 BT.709 有限范围 SDR"处理。
// 遇到 HDR 内容（PQ/BT.2020）时，如果不显式告诉它"这是 G2084 + P2020"，
// 它就把 PQ 编码值当成 SDR 直接线性映射出去 —— 表现就是**高光被压死、画面发灰发闷**。
// 本机实测（`native/testdata/bbb-hdr10.mp4`，与 bbb.mp4 同内容的真 HDR10 版）：
//   * 正确色调映射后的 SDR 帧：YAVG≈110 / YMAX≈234
//   * 我们修之前的输出：      YAVG≈85  / YMAX≈127   ← 与"不做色调映射的朴素转换"一模一样
// 修法见 convert.cpp：用 ID3D11VideoContext1 的 *1 色彩空间接口把输入声明成 G2084/P2020、
// 输出声明成 G22/P709，驱动就会自己做 HDR→SDR 的色调映射（这正是 Windows 视频管线的做法）。
//
// 这里只负责**从 AVFrame 读事实**（解码器已经把码流里的 color_trc/color_primaries/
// color_space/color_range 与 HDR10 元数据挂在帧上），不认识 D3D。
// ============================================================================
#pragma once

#include <cstdint>
#include <string>

extern "C" {
#include <libavutil/frame.h>
#include <libavutil/pixfmt.h>
}

namespace cineduct::host {

/** 传输函数（决定要不要做 HDR 色调映射）。 */
enum class VideoTransfer {
    Sdr,   // BT.709 / BT.601 gamma
    Pq,    // SMPTE ST 2084（HDR10、Dolby Vision 的基本层）
    Hlg,   // ARIB STD-B67（HLG）
};

struct VideoColorInfo {
    VideoTransfer transfer = VideoTransfer::Sdr;
    /** 原色/矩阵是 BT.2020（HDR 基本都是；4K SDR 也可能是）。 */
    bool bt2020 = false;
    /** JPEG/PC 全范围（否则是 MPEG/studio 有限范围）。 */
    bool fullRange = false;
    /** 每个分量多少位（P010 = 10）。 */
    int bitDepth = 8;
    /** HDR10 元数据（可能没有；有的话交给驱动，色调映射会更准）。 */
    bool hasMasteringDisplay = false;
    uint16_t maxContentLightLevel = 0;
    uint16_t maxFrameAverageLightLevel = 0;
    uint16_t masteringMaxLuminanceNits = 0;
    uint16_t masteringMinLuminanceHundredths = 0;

    bool hdr() const { return this->transfer != VideoTransfer::Sdr; }
    bool operator==(const VideoColorInfo& other) const;
    bool operator!=(const VideoColorInfo& other) const { return !(*this == other); }

    /** 人话描述（日志用），如 "HDR10(PQ) BT.2020 10bit 有限范围"。 */
    std::string describe() const;
};

/** 从解码帧里读色彩信息（读不到就按 SDR/BT.709 保守处理）。 */
VideoColorInfo describeVideoColor(const AVFrame& frame);

/** 8bit / 10bit 的判定（P010/P016 是 10/16 位，NV12 是 8 位）。 */
int bitDepthOfFormat(int avPixelFormat);

} // namespace cineduct::host

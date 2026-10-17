#include "video_color.h"

#include <cstdio>

extern "C" {
#include <libavutil/mastering_display_metadata.h>
}

using cineduct::host::VideoColorInfo;
using cineduct::host::VideoTransfer;

namespace cineduct::host {

int bitDepthOfFormat(int avPixelFormat) {
    switch (static_cast<AVPixelFormat>(avPixelFormat)) {
        case AV_PIX_FMT_P010LE:
        case AV_PIX_FMT_P010BE:
            return 10;
        case AV_PIX_FMT_P016LE:
        case AV_PIX_FMT_P016BE:
            return 16;
        case AV_PIX_FMT_YUV420P10LE:
        case AV_PIX_FMT_YUV420P10BE:
        case AV_PIX_FMT_YUV422P10LE:
        case AV_PIX_FMT_YUV444P10LE:
        case AV_PIX_FMT_GBRP10LE:
            return 10;
        case AV_PIX_FMT_YUV420P12LE:
        case AV_PIX_FMT_YUV444P12LE:
            return 12;
        default:
            return 8;
    }
}

bool VideoColorInfo::operator==(const VideoColorInfo& other) const {
    return this->transfer == other.transfer && this->bt2020 == other.bt2020
            && this->fullRange == other.fullRange && this->bitDepth == other.bitDepth
            && this->maxContentLightLevel == other.maxContentLightLevel
            && this->maxFrameAverageLightLevel == other.maxFrameAverageLightLevel
            && this->masteringMaxLuminanceNits == other.masteringMaxLuminanceNits;
}

std::string VideoColorInfo::describe() const {
    std::string text;
    switch (this->transfer) {
        case VideoTransfer::Pq: text = "HDR10(PQ)"; break;
        case VideoTransfer::Hlg: text = "HLG"; break;
        default: text = "SDR"; break;
    }
    text += this->bt2020 ? " BT.2020" : " BT.709";
    text += " " + std::to_string(this->bitDepth) + "bit";
    text += this->fullRange ? " 全范围" : " 有限范围";
    if (this->maxContentLightLevel > 0) {
        char buf[64];
        std::snprintf(buf, sizeof(buf), " MaxCLL=%u", this->maxContentLightLevel);
        text += buf;
    }
    return text;
}

VideoColorInfo describeVideoColor(const AVFrame& frame) {
    VideoColorInfo info;
    switch (frame.color_trc) {
        case AVCOL_TRC_SMPTE2084: info.transfer = VideoTransfer::Pq; break;
        case AVCOL_TRC_ARIB_STD_B67: info.transfer = VideoTransfer::Hlg; break;
        default: info.transfer = VideoTransfer::Sdr; break;
    }
    info.bt2020 = frame.color_primaries == AVCOL_PRI_BT2020
            || frame.colorspace == AVCOL_SPC_BT2020_NCL
            || frame.colorspace == AVCOL_SPC_BT2020_CL;
    info.fullRange = frame.color_range == AVCOL_RANGE_JPEG;
    info.bitDepth = bitDepthOfFormat(frame.format);

    // 注意：这里直接读 frame 的 side data 数组（不用 av_frame_get_side_data，少一个动态符号）
    for (int i = 0; i < frame.nb_side_data; ++i) {
        const AVFrameSideData* sidePtr = frame.side_data[i];
        if (sidePtr == nullptr) continue;
        const AVFrameSideData& side = *sidePtr;
        if (side.type == AV_FRAME_DATA_MASTERING_DISPLAY_METADATA && side.data != nullptr) {
            const auto* mastering = reinterpret_cast<const AVMasteringDisplayMetadata*>(side.data);
            if (mastering->has_luminance) {
                info.hasMasteringDisplay = true;
                // AVUI 的 max_luminance 是 0.0001 cd/m² 为单位
                const int64_t maxLum = mastering->max_luminance.num
                        / (mastering->max_luminance.den != 0 ? mastering->max_luminance.den : 1);
                const int64_t minLum = mastering->min_luminance.num
                        / (mastering->min_luminance.den != 0 ? mastering->min_luminance.den : 1);
                info.masteringMaxLuminanceNits = static_cast<uint16_t>(
                        maxLum / 10000 > 65535 ? 65535 : (maxLum / 10000 < 0 ? 0 : maxLum / 10000));
                info.masteringMinLuminanceHundredths = static_cast<uint16_t>(
                        minLum / 100 > 65535 ? 65535 : (minLum / 100 < 0 ? 0 : minLum / 100));
            }
        } else if (side.type == AV_FRAME_DATA_CONTENT_LIGHT_LEVEL && side.data != nullptr) {
            const auto* light = reinterpret_cast<const AVContentLightMetadata*>(side.data);
            info.maxContentLightLevel = light->MaxCLL > 65535 ? 65535
                    : static_cast<uint16_t>(light->MaxCLL);
            info.maxFrameAverageLightLevel = light->MaxFALL > 65535 ? 65535
                    : static_cast<uint16_t>(light->MaxFALL);
        }
    }
    return info;
}

} // namespace cineduct::host

// ============================================================================
// 软件解码路径的色度转换（CPU：任意像素格式 → BGRA，带信箱化黑边）
//
// 为什么需要它：硬解（d3d11va）给的是 GPU 纹理，走 ID3D11VideoProcessor；
// 而**软解给的是 CPU 帧**（yuv420p / nv12 / yuv444p…），那条路没有 VideoProcessor 可用，
// 只能用 swscale 在 CPU 上转成 BGRA，再 UpdateSubresource 进输出纹理。
//
// 这是"硬解失败也能出画面"的兜底路：没有它，d3d11va 打不开的流（驱动太老、编码不支持）
// 就只能让整个子进程失败，上层退回到进程内引擎 —— 那正是老版本的行为。
//
// ★ HDR：软解路径同样要处理 PQ/HLG。硬解那条路是把色彩空间声明给驱动、由驱动做色调映射；
// 这里没有驱动可依赖，只能自己算：swscale 先按 BT.2020 矩阵转出 16 位 RGB，再走
// "PQ 反 EOTF → BT.2020→BT.709 色域矩阵 → 色调曲线 → BT.709 gamma" 得到 8 位 BGRA。
// 不做这一步的话，10bit HDR 素材在软解路径上会**明显发灰**（高光被压死）。
// ============================================================================
#pragma once

#include <cstdint>
#include <string>
#include <vector>

extern "C" {
#include <libavutil/frame.h>
#include <libavutil/pixfmt.h>
#include <libswscale/swscale.h>
}

#include "convert.h"
#include "ffmpeg_dyn.h"
#include "video_color.h"

namespace cineduct::host {

class SwScaler {
public:
    /**
     * @param srcFormat 解码帧的像素格式（由第一帧给出）
     * @param color     帧的色彩信息（HDR 时启用 CPU 色调映射）
     * @param plan      输出几何（信箱化的落点也在这里）
     * @param toneMapHdr 是否允许色调映射（`--no-hdr-tonemap` 关掉，仅诊断）
     */
    bool init(const FfmpegApi& api, int srcWidth, int srcHeight, AVPixelFormat srcFormat,
              const VideoColorInfo& color, const LetterboxPlan& plan, bool toneMapHdr,
              std::string& error);

    /** 转一帧到内部缓冲（含黑边）；结果用 data()/pitch() 取出。 */
    bool convert(const FfmpegApi& api, const AVFrame* frame, std::string& error);

    const uint8_t* data() const { return this->buffer.data(); }
    uint32_t pitch() const { return this->outputPitch; }
    /** 当前是否在跑 CPU 色调映射（日志/诊断用）。 */
    bool toneMapping() const { return this->toneMapActive; }

    void destroy();

private:
    /** 只清"画面落点之外"的区域：铺满输出时它是空操作（软解路径的每帧开销）。 */
    void clearBorders();
    /** HDR 帧：从 16 位 PQ 编码 RGB 走色调映射写进 BGRA 缓冲（含信箱化与 alpha）。 */
    void toneMapRows(const uint16_t* quad, int quadPitchBytes, int rows);
    /** 预计算 LUT：PQ(10bit) → 线性(0..1 归一化到 kHdrPeakNits) 与 线性 → 8bit BT.709。 */
    void buildToneMapTables();

    /** 释放 SwsContext 要用动态符号表，所以 init 时记下来（它的生命周期比本对象长）。 */
    const FfmpegApi* api = nullptr;
    SwsContext* sws = nullptr;
    std::vector<uint8_t> buffer;
    std::vector<uint16_t> rgba48;      // HDR：swscale 的中间结果（RGB48）
    uint32_t outputPitch = 0;
    int drawWidth = 0;
    int drawHeight = 0;
    D3D11_RECT destRect{0, 0, 0, 0};

    // ---- HDR 色调映射 ----
    bool toneMapActive = false;
    VideoColorInfo color{};
    /** 16 位线性 RGB 暂存（每个通道按 kLinearScale 定点化）。 */
    std::vector<uint16_t> linearLut;   // 4096 → 线性（0..65535）
    std::vector<uint8_t> gammaLut;     // 4096 → 8bit BT.709 gamma
};

} // namespace cineduct::host

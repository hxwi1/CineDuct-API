// ============================================================================
// 帧 → BGRA 输出纹理（P2 · M2；v2 拆成"输出"+"两条转换路径"）
//
// 为什么必须转：GL 侧采样不了 NV12，而 NV_DX_interop 只接受 BGRA8 这类格式
// （见 docs/PLAN-standalone-gpu.md §3.1 实测结论）。
//
// **两条路径，产出同一张输出纹理**：
//   1. 硬解路径（d3d11va 帧，NV12/P010）：走驱动里的 ID3D11VideoProcessor，
//      既快又顺手完成缩放，NV12 → RGB 的取整与色彩空间转换都由驱动保证；
//   2. 软解路径（CPU 帧，yuv420p 等）：swscale 转 BGRA（见 sw_scale.h），
//      再 UpdateSubresource 灌进同一张输出纹理。
//
// ★ 为什么这次要拆开输出与转换：以前 init() 把"建输出纹理"和"建 VideoProcessor"
// 绑在一起，于是**没有 ID3D11VideoDevice 的设备连软解都出不了画面** —— 而软解恰恰是
// 硬解失败时的兜底路。现在输出纹理独立于 VP，两条路都能用。
// ============================================================================
#pragma once

#include <d3d11.h>

#include <cstdint>
#include <string>

#include "video_color.h"

namespace cineduct::host {

/**
 * 输出几何：输出尺寸 + 画面落点（信箱化）。
 *
 * <p>当 {@code outWidth:outHeight != 可见画面比例} 时，画面**只写进 destRect**，其余部分每帧清成
 * 黑色，于是"黑边"是烘焙在纹理里的真实黑边（调用方可以把整张纹理铺满自己的显示面，不会拉伸）。
 * 空矩形（{0,0,0,0}）表示"铺满整个输出"——也就是拉伸。
 */
struct LetterboxPlan {
    uint32_t outWidth = 0;
    uint32_t outHeight = 0;
    D3D11_RECT destRect{0, 0, 0, 0};
    double videoAspect = 0.0;
};

/**
 * 按"显示面比例"算输出几何（与旧 main.cpp 里的内联算法逐字一致，只是搬了家）。
 *
 * <p>规则：长边不变、短边补黑（不放大，避免变糊），对齐到 2 的倍数，4K 封顶。
 *
 * @param fitAspect >0.01 才生效；<=0 或显式给了输出尺寸时输出就是画面尺寸、不做信箱化
 */
LetterboxPlan planOutput(uint32_t visibleW, uint32_t visibleH, double fitAspect,
                         uint32_t forcedWidth, uint32_t forcedHeight);

struct VideoConverter {
    /** BGRA8 输出纹理（两条路径共用）与它的落点。 */
    bool initOutput(ID3D11Device* device, ID3D11DeviceContext* context, const LetterboxPlan& plan,
                    std::string& error);

    /**
     * 硬解路径的 VideoProcessor。
     *
     * @param codedW/H   解码纹理的真实尺寸（NV12 纹理常按 16 对齐，比可见尺寸大）
     * @param visibleW/H 可见画面尺寸（去掉对齐填充）—— 当取景矩形用，否则画面底部会有脏边
     * @param color      帧的色彩信息（SDR / HDR10-PQ / HLG、BT.2020、范围、位深）
     * @param toneMapHdr true = 允许驱动做 HDR→SDR 色调映射（`--no-hdr-tonemap` 时为 false，仅诊断用）
     */
    bool initProcessor(ID3D11Device* device, ID3D11DeviceContext* context, uint32_t codedW,
                       uint32_t codedH, uint32_t visibleW, uint32_t visibleH,
                       const VideoColorInfo& color, bool toneMapHdr, std::string& error);

    /**
     * 按帧的色彩信息声明输入/输出色彩空间（流中途变了也能改，不必重建 VideoProcessor）。
     *
     * <p>这就是修"**HDR 画面变灰**"的地方：不声明时驱动按 BT.709 SDR 处理 PQ 数据，
     * 高光被压死、画面发灰；声明成 G2084/P2020 → 输出 G22/P709 之后，
     * 驱动会自己做 HDR→SDR 的色调映射（Windows 视频管线也是这么干的）。
     */
    void applyColorInfo(const VideoColorInfo& color);

    /** 把某一帧的 NV12 解码纹理转成 BGRA，结果在 outputTexture()。 */
    bool convertHardware(ID3D11Texture2D* nv12Texture, uint32_t arrayIndex, std::string& error);

    /**
     * 软解路径：把 CPU 上已经转好的 BGRA 灌进输出纹理。
     *
     * <p>信箱化的黑边由调用方（SwScaler）负责填 —— 这里只做上传，不猜画面落点。
     */
    bool uploadBgra(const uint8_t* data, uint32_t pitch, std::string& error);

    ID3D11Texture2D* outputTexture2D() const { return this->outputTexture; }

    /** 输出纹理是否已经就绪（没就绪就别谈发布）。 */
    bool outputReady() const { return this->outputTexture != nullptr; }

    /** 硬解路径是否可用（设备没有 ID3D11VideoDevice 时为 false，软解不受影响）。 */
    bool hardwareReady() const { return this->processor != nullptr; }

    /** 当前是否已经把 HDR 输入声明给驱动（日志/诊断用）。 */
    bool hdrInputDeclared() const { return this->hdrDeclared; }

    void destroy();

private:
    ID3D11VideoDevice* videoDevice = nullptr;
    ID3D11VideoContext* videoContext = nullptr;
    ID3D11VideoProcessorEnumerator* enumerator = nullptr;
    ID3D11VideoProcessor* processor = nullptr;
    ID3D11VideoProcessorInputView* inputView = nullptr;   // 跟随每帧的 NV12 解码纹理重建
    ID3D11VideoProcessorOutputView* outputView = nullptr;
    ID3D11Texture2D* outputTexture = nullptr;             // BGRA8，转换结果
    ID3D11RenderTargetView* outputRtv = nullptr;          // 只用于"每帧清黑"（信箱化的黑边底色）
    /**
     * 普通设备上下文（借用 + AddRef）。
     *
     * <p>清黑与上传必须用它：{@code ID3D11VideoContext} 里**没有** ClearView/ClearRenderTargetView
     * 也没有 UpdateSubresource，视频处理器只负责 Blt。
     */
    ID3D11DeviceContext* deviceContext = nullptr;

    uint32_t codedWidth = 0;
    uint32_t codedHeight = 0;
    LetterboxPlan plan{};
    /** 上次声明过的色彩信息（变了才重新声明，避免每帧白设一遍）。 */
    VideoColorInfo appliedColor{};
    bool hasAppliedColor = false;
    bool hdrDeclared = false;
    /** 驱动是否支持"HDR 输入 → SDR 输出"的转换（不支持时只能降级并告警）。 */
    bool hdrConversionSupported = false;
    bool toneMapHdr = true;
};

} // namespace cineduct::host

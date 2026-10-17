#include "convert.h"

#include <d3d11_1.h>
#include <d3d11_4.h>
#include <dxgi1_5.h>
#include <wrl/client.h>

#include <algorithm>
#include <cmath>
#include <cstdio>

namespace cineduct::host {

namespace {

std::string hrText(const char* what, HRESULT hr) {
    char buf[256];
    std::snprintf(buf, sizeof(buf), "%s (hr=0x%08lx)", what, static_cast<unsigned long>(hr));
    return buf;
}

/**
 * 把"帧的色彩信息"翻译成 DXGI 色彩空间（输入侧）。
 *
 * <p>★ 这一步就是"**HDR 变灰**"的根因所在：
 *   * PQ（HDR10）→ `YCBCR_STUDIO_G2084_TOPLEFT_P2020`；
 *   * HLG        → `YCBCR_STUDIO_GHLG_TOPLEFT_P2020`；
 *   * 其它        → G22 + 对应原色（BT.2020 或 BT.709）、对应范围。
 *
 * <p>不声明时驱动一律当 BT.709 有限范围 SDR 处理：PQ 编码值被直接当 SDR 映射，
 * 高光全部压在一起 → 画面发灰发闷（本机实测 YMAX 只有 127，而正确结果是 234）。
 */
DXGI_COLOR_SPACE_TYPE inputColorSpaceOf(const VideoColorInfo& color) {
    if (color.transfer == VideoTransfer::Pq) {
        // P2020 的 G2084 只有 TOPLEFT 变体（这也是 Windows HDR 视频管线的标准取值）
        return DXGI_COLOR_SPACE_YCBCR_STUDIO_G2084_TOPLEFT_P2020;
    }
    if (color.transfer == VideoTransfer::Hlg) {
        return DXGI_COLOR_SPACE_YCBCR_STUDIO_GHLG_TOPLEFT_P2020;
    }
    if (color.bt2020) {
        return color.fullRange ? DXGI_COLOR_SPACE_YCBCR_FULL_G22_LEFT_P2020
                               : DXGI_COLOR_SPACE_YCBCR_STUDIO_G22_LEFT_P2020;
    }
    return color.fullRange ? DXGI_COLOR_SPACE_YCBCR_FULL_G22_LEFT_P709
                           : DXGI_COLOR_SPACE_YCBCR_STUDIO_G22_LEFT_P709;
}

/**
 * 输出侧固定为 **SDR 全范围 BT.709**。
 *
 * <p>我们的输出纹理是 BGRA8，最后被 Minecraft 的 GL 渲染器当普通 SDR 贴图画进 LDR 帧缓冲 ——
 * 所以"输出 HDR"是没有意义的（会被游戏再当 SDR 解释一遍，还是灰）。正确做法就是让驱动
 * 把 HDR 色调映射到 SDR：输入声明成 G2084/P2020、输出声明成 G22/P709，驱动就自己做这件事。
 */
constexpr DXGI_COLOR_SPACE_TYPE kOutputColorSpace = DXGI_COLOR_SPACE_RGB_FULL_G22_NONE_P709;

} // namespace

LetterboxPlan planOutput(uint32_t visibleW, uint32_t visibleH, double fitAspect,
                         uint32_t forcedWidth, uint32_t forcedHeight) {
    LetterboxPlan plan;
    plan.outWidth = forcedWidth > 0 ? forcedWidth : visibleW;
    plan.outHeight = forcedHeight > 0 ? forcedHeight : visibleH;
    plan.videoAspect = visibleH > 0 ? static_cast<double>(visibleW) / static_cast<double>(visibleH) : 0.0;
    if (visibleW == 0 || visibleH == 0) return plan;

    // 显式给了输出尺寸就照办（上层自己知道要什么），不再做信箱化
    if (!(fitAspect > 0.01 && forcedWidth == 0 && forcedHeight == 0)) return plan;

    const double want = fitAspect;
    const double videoAspect = plan.videoAspect > 0.0 ? plan.videoAspect : want;
    uint32_t boxW = visibleW;
    uint32_t boxH = visibleH;
    if (videoAspect > want) {
        boxW = visibleW;
        boxH = static_cast<uint32_t>(std::llround(static_cast<double>(visibleW) / want));
    } else {
        boxH = visibleH;
        boxW = static_cast<uint32_t>(std::llround(static_cast<double>(visibleH) * want));
    }
    boxW = std::max(2u, boxW & ~1u);
    boxH = std::max(2u, boxH & ~1u);
    // 上限：别为一个离谱的显示面比例分配超大纹理（4K 封顶）
    const uint32_t kMaxW = 4096;
    const uint32_t kMaxH = 4096;
    if (boxW > kMaxW || boxH > kMaxH) {
        const double scale = std::min(static_cast<double>(kMaxW) / boxW,
                                      static_cast<double>(kMaxH) / boxH);
        boxW = std::max(2u, static_cast<uint32_t>(std::llround(boxW * scale)) & ~1u);
        boxH = std::max(2u, static_cast<uint32_t>(std::llround(boxH * scale)) & ~1u);
    }
    plan.outWidth = boxW;
    plan.outHeight = boxH;
    // 画面等比居中落进输出框：宽高都按比例缩，剩下的是黑边
    const double fitScale = std::min(static_cast<double>(boxW) / visibleW,
                                     static_cast<double>(boxH) / visibleH);
    const uint32_t drawW = std::max(2u, static_cast<uint32_t>(std::llround(visibleW * fitScale)) & ~1u);
    const uint32_t drawH = std::max(2u, static_cast<uint32_t>(std::llround(visibleH * fitScale)) & ~1u);
    const uint32_t x = (boxW - drawW) / 2;
    const uint32_t y = (boxH - drawH) / 2;
    plan.destRect = D3D11_RECT{static_cast<LONG>(x), static_cast<LONG>(y),
                               static_cast<LONG>(x + drawW), static_cast<LONG>(y + drawH)};
    return plan;
}

bool VideoConverter::initOutput(ID3D11Device* device, ID3D11DeviceContext* context,
                               const LetterboxPlan& outputPlan, std::string& error) {
    if (device == nullptr || context == nullptr || outputPlan.outWidth == 0 || outputPlan.outHeight == 0) {
        error = "VideoConverter 输出参数非法";
        return false;
    }
    this->plan = outputPlan;
    // 借用 + 计数，destroy 里还回去（本类可能比调用方活得久）
    this->deviceContext = context;
    this->deviceContext->AddRef();

    D3D11_TEXTURE2D_DESC outDesc{};
    outDesc.Width = outputPlan.outWidth;
    outDesc.Height = outputPlan.outHeight;
    outDesc.MipLevels = 1;
    outDesc.ArraySize = 1;
    outDesc.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
    outDesc.SampleDesc.Count = 1;
    outDesc.Usage = D3D11_USAGE_DEFAULT;
    outDesc.BindFlags = D3D11_BIND_RENDER_TARGET | D3D11_BIND_SHADER_RESOURCE;
    const HRESULT hr = device->CreateTexture2D(&outDesc, nullptr, &this->outputTexture);
    if (FAILED(hr)) {
        error = hrText("创建 BGRA 输出纹理失败", hr);
        return false;
    }
    // 黑边底色：清黑用的 RTV（每帧 Blt 之前清一次，见 convertHardware）
    if (FAILED(device->CreateRenderTargetView(this->outputTexture, nullptr, &this->outputRtv))) {
        // 不用它做别的，只是清屏；失败也无所谓（Blt 仍能工作，黑边可能残留上一帧内容）
        this->outputRtv = nullptr;
    }
    std::printf("[host] 输出纹理就绪: %ux%u BGRA8%s\n", outputPlan.outWidth, outputPlan.outHeight,
                this->plan.destRect.right > this->plan.destRect.left ? "（信箱化）" : "");
    return true;
}

bool VideoConverter::initProcessor(ID3D11Device* device, ID3D11DeviceContext* context,
                                   uint32_t codedW, uint32_t codedH, uint32_t visibleW,
                                   uint32_t visibleH, const VideoColorInfo& color, bool toneMapHdr,
                                   std::string& error) {
    if (device == nullptr || context == nullptr || this->outputTexture == nullptr) {
        // 注意判的是**输出纹理**不是 outputView：输出视图恰恰要等 enumerator 建好才能建，
        // 在这里判定"outputView 为空"会把整条硬解路径误判成不可用（实测踩过：
        // 日志里出现"VideoProcessor 不可用 → 回拷 CPU"，白白丢掉零拷贝快路径）。
        error = "VideoConverter 未初始化输出（先调 initOutput）";
        return false;
    }
    if (codedW == 0 || codedH == 0) {
        error = "VideoConverter 输入尺寸非法";
        return false;
    }
    if (visibleW == 0 || visibleH == 0) {
        visibleW = codedW;
        visibleH = codedH;
    }
    this->codedWidth = codedW;
    this->codedHeight = codedH;
    this->toneMapHdr = toneMapHdr;

    HRESULT hr = device->QueryInterface(__uuidof(ID3D11VideoDevice),
                                        reinterpret_cast<void**>(&this->videoDevice));
    if (FAILED(hr)) {
        error = hrText("设备不支持 ID3D11VideoDevice", hr);
        return false;
    }
    hr = context->QueryInterface(__uuidof(ID3D11VideoContext),
                                 reinterpret_cast<void**>(&this->videoContext));
    if (FAILED(hr)) {
        error = hrText("上下文不支持 ID3D11VideoContext", hr);
        return false;
    }

    D3D11_VIDEO_PROCESSOR_CONTENT_DESC content{};
    content.InputFrameFormat = D3D11_VIDEO_FRAME_FORMAT_PROGRESSIVE;
    content.InputWidth = codedW;
    content.InputHeight = codedH;
    content.OutputWidth = this->plan.outWidth;
    content.OutputHeight = this->plan.outHeight;
    content.Usage = D3D11_VIDEO_USAGE_PLAYBACK_NORMAL;
    // 帧率只影响去隔行/帧率转换的启发式，给个常用值即可
    content.InputFrameRate.Numerator = 60;
    content.InputFrameRate.Denominator = 1;
    content.OutputFrameRate.Numerator = 60;
    content.OutputFrameRate.Denominator = 1;

    hr = this->videoDevice->CreateVideoProcessorEnumerator(&content, &this->enumerator);
    if (FAILED(hr)) {
        error = hrText("CreateVideoProcessorEnumerator 失败", hr);
        return false;
    }

    // 报告一下格式支持情况：BGRA8 能不能当输出视图，因驱动而异，早点知道比以后瞎猜好
    UINT flags = 0;
    if (SUCCEEDED(this->enumerator->CheckVideoProcessorFormat(DXGI_FORMAT_B8G8R8A8_UNORM, &flags))) {
        std::printf("[host] VideoProcessor 对 BGRA8 的支持: %s%s%s\n",
                    (flags & D3D11_VIDEO_PROCESSOR_FORMAT_SUPPORT_INPUT) ? "输入 " : "",
                    (flags & D3D11_VIDEO_PROCESSOR_FORMAT_SUPPORT_OUTPUT) ? "输出 " : "",
                    flags == 0 ? "（不支持）" : "");
    }

    hr = this->videoDevice->CreateVideoProcessor(this->enumerator, 0, &this->processor);
    if (FAILED(hr)) {
        error = hrText("CreateVideoProcessor 失败", hr);
        return false;
    }

    D3D11_VIDEO_PROCESSOR_OUTPUT_VIEW_DESC ov{};
    ov.ViewDimension = D3D11_VPOV_DIMENSION_TEXTURE2D;
    ov.Texture2D.MipSlice = 0;
    hr = this->videoDevice->CreateVideoProcessorOutputView(this->outputTexture, this->enumerator, &ov,
                                                           &this->outputView);
    if (FAILED(hr)) {
        error = hrText("CreateVideoProcessorOutputView(BGRA) 失败（该格式可能不支持输出）", hr);
        return false;
    }

    /*
     * 取景矩形 = 解码纹理里**真正是画面**的那一块。
     *
     * 为什么必须显式设：NV12 纹理常按 16 对齐（1920×1080 的视频拿到 1920×1088 的纹理），
     * 不设 source rect 时驱动会把那 8 行**对齐填充**也当画面缩进输出 —— 表现为画面被轻微拉伸/底部有条脏边。
     */
    RECT srcRect{0, 0, static_cast<LONG>(visibleW), static_cast<LONG>(visibleH)};
    this->videoContext->VideoProcessorSetStreamSourceRect(this->processor, 0, TRUE, &srcRect);

    /*
     * ★ 色彩空间：按帧的**真实**色彩信息声明 —— 这就是"HDR 画面变灰"的修法
     *（见 inputColorSpaceOf 的说明：不声明时驱动一律按 BT.709 SDR 处理 PQ 数据）。
     */
    this->applyColorInfo(color);

    /*
     * ★ 信箱化的关键一步：告诉驱动"画面只写这个框"，框外用黑边。
     *
     * 不设它时 VideoProcessorBlt **总是把画面拉满整个输出** —— 这正是"视频被拉伸"的根因。
     */
    if (this->plan.destRect.right > this->plan.destRect.left
        && this->plan.destRect.bottom > this->plan.destRect.top) {
        this->videoContext->VideoProcessorSetStreamDestRect(this->processor, 0, TRUE, &this->plan.destRect);
        std::printf("[host] 信箱化: 画面落点 %ld,%ld %ldx%ld（输出 %ux%u，其余为黑边）\n",
                    this->plan.destRect.left, this->plan.destRect.top,
                    this->plan.destRect.right - this->plan.destRect.left,
                    this->plan.destRect.bottom - this->plan.destRect.top,
                    this->plan.outWidth, this->plan.outHeight);
    }

    std::printf("[host] VideoProcessor 就绪: 输入 %ux%u(NV12，可见 %ux%u) → 输出 %ux%u(BGRA8)\n",
                codedW, codedH, visibleW, visibleH, this->plan.outWidth, this->plan.outHeight);
    return true;
}

void VideoConverter::applyColorInfo(const VideoColorInfo& color) {
    if (this->processor == nullptr || this->videoContext == nullptr) return;
    if (this->hasAppliedColor && this->appliedColor == color) return;   // 没变就不重复设
    this->appliedColor = color;
    this->hasAppliedColor = true;

    VideoColorInfo effective = color;
    if (effective.hdr() && !this->toneMapHdr) {
        // 诊断开关（--no-hdr-tonemap）：故意按 SDR 解释 HDR，用来复现"变灰"
        effective.transfer = VideoTransfer::Sdr;
    }

    /*
     * 优先走 ID3D11VideoContext1 的 *1 接口：那是唯一能表达"HDR 输入"的通道。
     * 老驱动/老系统上拿不到这个接口，就只能退回旧的 D3D11_VIDEO_PROCESSOR_COLOR_SPACE
     * （它只会表达 BT.601/709，表达不了 BT.2020/PQ）—— 那种情况下 HDR 内容没法正确映射，
     * 只能明确告警，别让用户以为"画面发灰是正常的"。
     */
    Microsoft::WRL::ComPtr<ID3D11VideoContext1> context1;
    Microsoft::WRL::ComPtr<ID3D11VideoProcessorEnumerator1> enumerator1;
    const HRESULT hrCtx = this->videoContext->QueryInterface(IID_PPV_ARGS(&context1));
    const HRESULT hrEnum = this->enumerator->QueryInterface(IID_PPV_ARGS(&enumerator1));

    const DXGI_COLOR_SPACE_TYPE inputSpace = inputColorSpaceOf(effective);
    if (SUCCEEDED(hrCtx) && SUCCEEDED(hrEnum)) {
        // 先问驱动"这个输入→输出组合支不支持"（HDR→SDR 的色调映射能力就靠它判定）
        BOOL supported = FALSE;
        const HRESULT hrCheck = enumerator1->CheckVideoProcessorFormatConversion(
                DXGI_FORMAT_NV12, inputSpace, DXGI_FORMAT_B8G8R8A8_UNORM, kOutputColorSpace,
                &supported);
        this->hdrConversionSupported = SUCCEEDED(hrCheck) && supported != FALSE;
        if (effective.hdr() && !this->hdrConversionSupported) {
            std::fprintf(stderr,
                         "[host] ⚠ 驱动未确认支持 %s 输入 → SDR 输出（CheckVideoProcessorFormatConversion：%s）："
                         "HDR 内容可能仍然偏灰偏暗\n",
                         effective.transfer == VideoTransfer::Pq ? "HDR10(PQ)" : "HLG",
                         SUCCEEDED(hrCheck) ? "不支持" : "调用失败");
        }
        context1->VideoProcessorSetStreamColorSpace1(this->processor, 0, inputSpace);
        context1->VideoProcessorSetOutputColorSpace1(this->processor, kOutputColorSpace);
        this->hdrDeclared = effective.hdr();
        if (effective.hdr()) {
            std::printf("[host] HDR 输入已声明给驱动: %s → 输出 SDR(BT.709 全范围)，由驱动做色调映射\n",
                        effective.describe().c_str());
        }
        // HDR10 元数据（有就交给驱动，它的色调曲线会更贴合这条内容）
        if (effective.hdr() && effective.maxContentLightLevel > 0) {
            Microsoft::WRL::ComPtr<ID3D11VideoContext2> context2;
            if (SUCCEEDED(this->videoContext->QueryInterface(IID_PPV_ARGS(&context2)))) {
                DXGI_HDR_METADATA_HDR10 meta{};
                meta.MaxContentLightLevel = effective.maxContentLightLevel;
                meta.MaxFrameAverageLightLevel = effective.maxFrameAverageLightLevel;
                meta.MaxMasteringLuminance = effective.masteringMaxLuminanceNits > 0
                        ? static_cast<UINT>(effective.masteringMaxLuminanceNits) * 10000u : 0u;
                meta.MinMasteringLuminance = effective.masteringMinLuminanceHundredths > 0
                        ? static_cast<UINT>(effective.masteringMinLuminanceHundredths) * 100u : 0u;
                context2->VideoProcessorSetStreamHDRMetaData(
                        this->processor, 0, DXGI_HDR_METADATA_TYPE_HDR10, sizeof(meta), &meta);
                std::printf("[host] HDR10 元数据已交给驱动: MaxCLL=%u MaxFALL=%u 峰值=%unit\n",
                            static_cast<unsigned>(meta.MaxContentLightLevel),
                            static_cast<unsigned>(meta.MaxFrameAverageLightLevel),
                            effective.masteringMaxLuminanceNits);
            }
        }
        return;
    }

    // ---- 退路：老接口（表达能力有限，BT.2020/PQ 表达不了）----
    D3D11_VIDEO_PROCESSOR_COLOR_SPACE inSpace{};
    inSpace.Usage = 0;
    inSpace.RGB_Range = 0;                                  // 输入是 YCbCr：有限范围
    inSpace.YCbCr_Matrix = 1;                               // 1 = BT.709（老接口没有 BT.2020）
    inSpace.YCbCr_xvYCC = 0;
    inSpace.Nominal_Range = effective.fullRange ? D3D11_VIDEO_PROCESSOR_NOMINAL_RANGE_0_255
                                                : D3D11_VIDEO_PROCESSOR_NOMINAL_RANGE_16_235;
    this->videoContext->VideoProcessorSetStreamColorSpace(this->processor, 0, &inSpace);

    D3D11_VIDEO_PROCESSOR_COLOR_SPACE outSpace{};
    outSpace.Usage = 0;
    outSpace.RGB_Range = 0;
    outSpace.Nominal_Range = D3D11_VIDEO_PROCESSOR_NOMINAL_RANGE_0_255;   // 输出 BGRA 全范围
    this->videoContext->VideoProcessorSetOutputColorSpace(this->processor, &outSpace);
    if (effective.hdr()) {
        std::fprintf(stderr,
                     "[host] ⚠ 拿不到 ID3D11VideoContext1（系统/驱动太老），%s 只能按 BT.709 SDR 处理："
                     "画面会偏灰偏暗，且没有 HDR→SDR 色调映射\n",
                     effective.transfer == VideoTransfer::Pq ? "HDR10(PQ)" : "HLG");
    }
}

bool VideoConverter::convertHardware(ID3D11Texture2D* nv12Texture, uint32_t arrayIndex,
                                    std::string& error) {
    if (this->processor == nullptr || nv12Texture == nullptr) {
        error = "硬解转换器未就绪";
        return false;
    }

    /*
     * 先清黑。
     *
     * Blt 只写 dest rect 那一块，框外的像素**不会被碰** —— 不清的话那里是上一帧的内容
     * （或者干脆是未初始化的显存），"黑边"就会变成花屏/拖影。
     */
    if (this->outputRtv != nullptr && this->deviceContext != nullptr) {
        const float black[4] = {0.0f, 0.0f, 0.0f, 1.0f};
        this->deviceContext->ClearRenderTargetView(this->outputRtv, black);
    }

    // 输入视图跟着每一帧的解码纹理走（解码器会轮转纹理，缓存住会读到旧帧）
    if (this->inputView != nullptr) {
        this->inputView->Release();
        this->inputView = nullptr;
    }
    D3D11_VIDEO_PROCESSOR_INPUT_VIEW_DESC iv{};
    iv.FourCC = 0;
    iv.ViewDimension = D3D11_VPIV_DIMENSION_TEXTURE2D;
    iv.Texture2D.MipSlice = 0;
    iv.Texture2D.ArraySlice = arrayIndex;
    HRESULT hr = this->videoDevice->CreateVideoProcessorInputView(nv12Texture, this->enumerator, &iv,
                                                                 &this->inputView);
    if (FAILED(hr)) {
        error = hrText("CreateVideoProcessorInputView 失败", hr);
        return false;
    }

    D3D11_VIDEO_PROCESSOR_STREAM stream{};
    stream.Enable = TRUE;
    stream.OutputIndex = 0;
    stream.InputFrameOrField = 0;
    stream.PastFrames = 0;
    stream.FutureFrames = 0;
    stream.pInputSurface = this->inputView;

    hr = this->videoContext->VideoProcessorBlt(this->processor, this->outputView, 0, 1, &stream);
    if (FAILED(hr)) {
        error = hrText("VideoProcessorBlt 失败", hr);
        return false;
    }
    return true;
}

bool VideoConverter::uploadBgra(const uint8_t* data, uint32_t pitch, std::string& error) {
    if (this->outputTexture == nullptr || this->deviceContext == nullptr) {
        error = "输出纹理未就绪";
        return false;
    }
    if (data == nullptr || pitch == 0) {
        error = "上传参数为空";
        return false;
    }
    /*
     * 软解路径（swscale 已经把画面（含黑边）铺满整块缓冲）：
     * DEFAULT 纹理允许 UpdateSubresource，行距由我们给，不需要 staging 往返。
     */
    this->deviceContext->UpdateSubresource(this->outputTexture, 0, nullptr, data, pitch, 0);
    return true;
}

void VideoConverter::destroy() {
    if (this->outputRtv != nullptr) this->outputRtv->Release();
    if (this->deviceContext != nullptr) this->deviceContext->Release();
    if (this->inputView != nullptr) this->inputView->Release();
    if (this->outputView != nullptr) this->outputView->Release();
    if (this->outputTexture != nullptr) this->outputTexture->Release();
    if (this->processor != nullptr) this->processor->Release();
    if (this->enumerator != nullptr) this->enumerator->Release();
    if (this->videoContext != nullptr) this->videoContext->Release();
    if (this->videoDevice != nullptr) this->videoDevice->Release();
    this->outputRtv = nullptr;
    this->deviceContext = nullptr;
    this->inputView = nullptr;
    this->outputView = nullptr;
    this->outputTexture = nullptr;
    this->processor = nullptr;
    this->enumerator = nullptr;
    this->videoContext = nullptr;
    this->videoDevice = nullptr;
    this->codedWidth = 0;
    this->codedHeight = 0;
}

} // namespace cineduct::host

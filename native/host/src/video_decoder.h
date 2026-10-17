// ============================================================================
// 视频解码器封装（P2 · M1 起就有硬解；v2 补上软解回退与正确的 EAGAIN 处理）
//
// ★ 这里修掉的两个真问题（都是老版本 main.cpp 里的写法造成的）：
//
//   1. **`avcodec_send_packet` 返回 EAGAIN 时直接丢包**。
//      老代码：`r = send_packet(...); if (r < 0) continue;` —— EAGAIN 也是负数，
//      于是那个包被 unref 掉、永远解不出来（丢帧，且在硬解缓冲吃紧时成片丢）。
//      正确写法（与 CicadaPlayer 的 `ActiveDecoder::enqueue_decoder` 一致）：
//      EAGAIN = "先把帧吐出来再重发这个包"，包必须**保留**，先 drain 再重发。
//
//   2. **硬解打不开就整个子进程退出**。
//      老代码 `avcodec_open2` 失败 → `return 6`，上层只能回退进程内引擎（慢、还多一块声卡）。
//      CicadaPlayer 在播放器层做了"硬解失败 → 用软解重试一次"（`SuperMediaPlayer.cpp:3786-3801`），
//      这里照做：先试 d3d11va，失败（或运行期连续出错）改用软解，画面照出。
//
// 线程约定：**只有解码线程碰这个对象**（FFmpeg 的编解码上下文不是线程安全的）。
// ============================================================================
#pragma once

#include <string>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
}

#include "ffmpeg_dyn.h"
#include "gpu.h"

namespace cineduct::host {

class VideoDecoder {
public:
    /** sendPacket 的结果。 */
    enum class SendResult {
        Ok,      // 包已被解码器接收
        Again,   // 解码器满（EAGAIN）：调用方**必须保留这个包**，先收帧再重发
        Eof,     // 解码器已进入排空状态（我们送了 EOS）
        Error,   // 真的出错（包可以丢了）
    };
    /** receiveFrame 的结果。 */
    enum class ReceiveResult {
        Got,     // 拿到一帧
        Again,   // 需要更多输入
        Eof,
        Error,
    };

    /**
     * @param preferHardware true = 先试 d3d11va，失败自动退回软解
     */
    bool open(const FfmpegApi& api, AVFormatContext* format, int streamIndex, GpuDevice* gpu,
              bool preferHardware, std::string& error);
    void close();

    bool opened() const { return this->codecCtx != nullptr; }
    /** 当前跑的是不是硬解（d3d11va）。 */
    bool hardware() const { return this->usingHardware; }
    /** 曾经请求过硬解但没成功（日志/诊断用）。 */
    const std::string& fallbackReason() const { return this->fallbackReasonText; }

    SendResult sendPacket(const AVPacket* packet);
    ReceiveResult receiveFrame(AVFrame* out);

    /** seek 之后必须调：清解码器缓冲（否则会拿旧参考帧解新 GOP → 花屏/闪旧帧）。 */
    void flush();

    /**
     * 连续解码错误数。
     *
     * <p>硬解跑着跑着开始连续报错（驱动/纹理池问题）时，上层据此把它换掉重开成软解 ——
     * 这与 CicadaPlayer 的 `MAX_DECODE_ERROR_FRAME` 思路一致，只是阈值小得多
     * （我们是有兜底路径的，不必等到 1000 帧）。
     */
    int consecutiveErrors() const { return this->errorStreak; }
    bool shouldFallbackToSoftware() const;

    int width() const { return this->visibleWidth; }
    int height() const { return this->visibleHeight; }
    const char* codecName() const { return this->codecNameText; }

private:
    bool openInternal(const FfmpegApi& api, AVFormatContext* format, int streamIndex,
                      GpuDevice* gpu, bool useHardware, std::string& error);

    const FfmpegApi* api = nullptr;
    AVCodecContext* codecCtx = nullptr;
    AVBufferRef* hwDeviceRef = nullptr;
    bool usingHardware = false;
    int errorStreak = 0;
    int visibleWidth = 0;
    int visibleHeight = 0;
    const char* codecNameText = "?";
    std::string fallbackReasonText;
};

} // namespace cineduct::host

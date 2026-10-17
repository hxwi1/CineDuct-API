// ============================================================================
// FFmpeg 的动态加载（P2 子进程用）
//
// 为什么不用导入库链接，而全部走 LoadLibrary + GetProcAddress：
//   1. BtbN / gyan 这些共享构建给的是 MinGW 的 .dll.a，喂给 MSVC 的 link.exe 并不可靠；
//   2. **运行时用的 DLL 就是 mod 里那套 javacpp FFmpeg natives**（FfmpegRuntime 解压出来的），
//      子进程直接加载同一批文件即可 —— 于是 Java 兜底路径与 C++ 快路径用的是同一个 FFmpeg 构建，
//      不会出现"同一个视频两条路径行为不一致"；
//   3. 缺哪个符号能报出明确名字，而不是链接期一句 unresolved external。
//
// 头文件只用来拿**类型与签名**（decltype(&av_xxx)），编译期完全不链接 FFmpeg。
// ============================================================================
#pragma once

#include <string>
#include <vector>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/avutil.h>
#include <libavutil/hwcontext.h>
#include <libavutil/opt.h>
#include <libavutil/pixdesc.h>
#include <libswresample/swresample.h>
#include <libswscale/swscale.h>
}

namespace cineduct::host {

/**
 * 需要从 DLL 里解析出来的函数表。
 *
 * 每一项都用 decltype(&函数名) 拿签名 —— 签名写错编译期就报，不需要手抄。
 */
#define MEDIAFIX_FFMPEG_FUNCS(X)                     \
    X(av_version_info)                               \
    X(avutil_version)                                \
    X(avcodec_version)                               \
    X(av_log_set_level)                              \
    X(av_strerror)                                   \
    X(avformat_network_init)                         \
    X(avformat_open_input)                           \
    X(avformat_find_stream_info)                     \
    X(avformat_close_input)                          \
    X(avformat_alloc_context)                        \
    X(avformat_free_context)                         \
    X(avformat_alloc_output_context2)                \
    X(av_find_input_format)                          \
    X(av_malloc)                                     \
    X(av_free)                                       \
    X(avformat_new_stream)                           \
    X(avformat_write_header)                         \
    X(av_interleaved_write_frame)                    \
    X(av_write_trailer)                              \
    X(avio_alloc_context)                            \
    X(avio_context_free)                             \
    X(avio_flush)                                    \
    X(avcodec_parameters_copy)                       \
    X(av_packet_ref)                                 \
    X(av_find_best_stream)                           \
    X(av_read_frame)                                 \
    X(av_seek_frame)                                 \
    X(avformat_seek_file)                            \
    X(avformat_flush)                                \
    X(avcodec_alloc_context3)                        \
    X(avcodec_free_context)                          \
    X(avcodec_parameters_to_context)                 \
    X(avcodec_open2)                                 \
    X(avcodec_send_packet)                           \
    X(avcodec_receive_frame)                         \
    X(avcodec_flush_buffers)                         \
    X(avcodec_get_name)                              \
    X(avcodec_find_decoder)                          \
    X(avcodec_get_hw_config)                         \
    X(av_frame_alloc)                                \
    X(av_frame_free)                                 \
    X(av_frame_unref)                                \
    X(av_frame_move_ref)                             \
    X(av_frame_clone)                                \
    X(av_packet_alloc)                               \
    X(av_packet_free)                                \
    X(av_packet_unref)                               \
    X(av_packet_move_ref)                            \
    X(av_hwdevice_ctx_alloc)                         \
    X(av_hwdevice_ctx_init)                          \
    X(av_hwdevice_ctx_create)                        \
    X(av_buffer_ref)                                 \
    X(av_buffer_unref)                               \
    X(av_hwframe_transfer_data)                      \
    X(av_dict_set)                                   \
    X(av_dict_free)                                  \
    X(av_rescale_q)                                  \
    X(av_get_pix_fmt_name)                           \
    X(swr_alloc_set_opts2)                           \
    X(swr_init)                                      \
    X(swr_convert)                                   \
    X(swr_free)

/**
 * **可选**符号：缺了只丢掉一条兜底路，不致命。
 *
 * <p>swscale 只被"软解回退"用到（硬解失败时把 CPU 帧缩成 BGRA，见 sw_scale.h）。
 * 如果某个 FFmpeg 包里没有 swscale-9.dll，硬解路径必须照常可用 —— 所以这些符号
 * 不参与"缺符号即失败"的判定，只在缺的时候把能力标记成不可用。
 */
#define MEDIAFIX_FFMPEG_OPTIONAL_FUNCS(X)            \
    X(sws_getContext)                                \
    X(sws_scale)                                     \
    X(sws_freeContext)                               \
    X(sws_getCoefficients)                           \
    X(sws_setColorspaceDetails)

struct FfmpegApi {
#define MEDIAFIX_DECL(name) decltype(&name) name = nullptr;
    MEDIAFIX_FFMPEG_FUNCS(MEDIAFIX_DECL)
    MEDIAFIX_FFMPEG_OPTIONAL_FUNCS(MEDIAFIX_DECL)
#undef MEDIAFIX_DECL

    /** 从目录里加载 FFmpeg 的 DLL 并解析全部符号。 */
    bool load(const std::wstring& dir, std::string& error);

    /** swscale 是否可用（软解回退路径的前提；硬解路径不依赖它）。 */
    bool swscaleReady() const {
        return this->sws_getContext != nullptr && this->sws_scale != nullptr
                && this->sws_freeContext != nullptr;
    }

    /** 已加载的 DLL 句柄数（诊断用）。 */
    size_t loadedModules() const { return this->modules.size(); }

    /** 实际加载成功的 DLL 文件名（诊断用，带版本后缀，能直接看出用的是哪套 FFmpeg）。 */
    const std::vector<std::string>& moduleNames() const { return this->names; }

private:
    std::vector<void*> modules;
    std::vector<std::string> names;
};

/** 把 std::string 的错误信息补上 Win32 错误码文本。 */
std::string win32Error(const char* what);

} // namespace cineduct::host

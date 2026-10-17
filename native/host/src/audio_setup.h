// ============================================================================
// 音频输出的规划与打开（P4：共享 / 独占 PCM / IEC 61937 码流直通）
//
// 目标：**用子进程直出声卡，让功放/回音壁的硬件解码器去解杜比全景声**。
// 这件事能不能做，取决于三件互相独立的事，缺一不可：
//   ① 音轨本身是不是压缩码流（AC-3 / E-AC-3(DD+) / TrueHD / DTS）—— 是 PCM/AAC 就没得直通；
//   ② 端点认不认那种 IEC 61937 链路格式（显卡驱动 + HDMI 接收端共同决定，只能问设备）；
//   ③ FFmpeg 的 spdif 封装能不能吃这个码流（见 audio_spdif.h）。
// 任一不成立就退回解码输出：独占 PCM（位完美但全景声已被我们自己解掉）→ 共享模式（兜底）。
//
// 这一段是从 main.cpp 原样搬出来的（只把 `Args` 换成 `AudioSetupRequest`）：
// 输出形态的选取规则全是实测结论（见 docs/AUDIO-passthrough.md），一个字都不该改。
// ============================================================================
#pragma once

#include <memory>
#include <string>

#include "audio_chain.h"
#include "audio_sink.h"
#include "ffmpeg_dyn.h"

namespace cineduct::host {

/** 音频输出形态偏好（命令行 `--audio-mode`）。 */
enum class AudioModePreference {
    Auto,          // 能直通就直通，否则共享（默认）
    Shared,        // 强制共享
    Exclusive,     // 强制独占 PCM
    Passthrough,   // 强制直通（不成就失败）
};

struct AudioSetupRequest {
    std::string audioUrl;
    std::string headers;
    /** 见 AudioChain::open —— 音频链另开一路输入，直播 CDN 缺 UA 就是 403。 */
    std::string userAgent;
    AudioModePreference mode = AudioModePreference::Auto;
    /** 端点选择：空 = 系统默认；"@3" = --list-audio 的编号；其它 = 名字子串。 */
    std::string deviceSelector;
};

/**
 * 打开声卡 + 建好音频链（解码链或直通链）。
 *
 * @param label 成功时填入人类可读的形态描述（进日志与状态环）
 * @return 链路对象；失败返回 nullptr 并把原因写进 error（此时声卡已经关掉了）
 */
std::unique_ptr<AudioChain> setupAudio(const FfmpegApi& api, const AudioSetupRequest& request,
                                       AudioSink& sink, std::string& label, std::string& error);

/** 解析 `--audio-mode` 的取值（未知值按 auto 处理，与老版本一致）。 */
AudioModePreference parseAudioMode(const std::string& text);

} // namespace cineduct::host

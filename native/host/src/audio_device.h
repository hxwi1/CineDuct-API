// ============================================================================
// 音频设备枚举与能力探测（WASAPI）
//
// 用途：把"这台机器到底能不能直通杜比全景声"变成可观测的事实，而不是猜。
//
//   * 列出全部播放设备 + 混音格式；
//   * 对每台设备用 IAudioClient::IsFormatSupported 逐一试探候选格式：
//       - 独占模式 PCM（16/24/32 位、2/6/8 声道、48/96/192kHz）
//       - **IEC 61937 直通**（AC-3 / DD+ / DD+ Atmos / TrueHD-MLP / MAT2.0-2.1 / DTS / DTS-HD）
//     直通格式的取值规则见微软文档 "Representing Formats for IEC 61937 Transmissions"：
//     链路速率是内容采样率的 4 倍（48k 内容 → 192kHz 链路），每样本恒为 16 位。
//
// 为什么必须探测而不是照着文档写死：能不能直通取决于**显卡驱动 + HDMI 接收端**，
// 文档只规定应用该怎么描述格式；真正的答案是设备报出来的。
// ============================================================================
#pragma once

#include <string>

namespace cineduct::host {

/** 人类可读的设备能力报告（--list-audio 直接打印）。 */
std::string listAudioDevices();

/**
 * 端点的空间音效（Dolby Atmos 那套）状态。
 *
 * <p>数据来自 Windows 官方 WinRT 接口
 * {@code Windows.Media.Audio.SpatialAudioDeviceConfiguration} —— 它能直接给出
 * **当前激活的空间音效格式名**（例如 "Dolby Atmos for home theater"），
 * 这是"到底有没有在输出 Atmos"最权威的程序化答案：
 * 用户没法靠耳朵可靠区分"真 Atmos"与"普通多声道混音"，但这里能直接读出来。
 */
struct SpatialAudioInfo {
    /** 是否真的问到了（系统上有这个接口且调用成功）。 */
    bool queried = false;
    /** 端点是否支持空间音效。 */
    bool supported = false;
    /**
     * 系统**当前实际在跑**的空间音效格式；空 = 没有激活（官方文档：可能被系统改掉，
     * 未激活时返回全零 GUID）。
     */
    std::string activeFormat;
    /** **用户选择**的空间音效格式（官方文档：这就是用户在设置里选的那个）。 */
    std::string defaultFormat;
    /** 上面两个的人类可读名（认不出来时就等于对应的 GUID 串）。 */
    std::string activeFormatName;
    std::string defaultFormatName;
    /** 失败原因（诊断用）。 */
    std::string error;
};

/** 查询端点的空间音效状态（device = IMMDevice*）。 */
SpatialAudioInfo querySpatialAudio(void* device);

/**
 * 端点属性 {@code PKEY_AudioEnvironment_SpatialAudioActive} 的兜底查询。
 *
 * <p>这是"音频环境 APO 通知"那套 API 用的属性，由 APO 写进端点属性库；
 * 多数端点并不上报它，所以命中率低于上面的 WinRT 接口 —— 只在 WinRT 不可用时当兜底。
 *
 * @param known 输出：端点是否真的给出了这个属性
 */
bool querySpatialAudioActive(void* device, bool& known);

} // namespace cineduct::host

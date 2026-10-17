// ============================================================================
// 音频链路的共同接口
//
// 两种实现，上层（video_pipeline.cpp）只认这个接口：
//   * AudioSource    —— 解码 → swr → S16 PCM 进声卡（共享/独占模式）
//   * AudioBitstream —— **不解码**，压缩帧封装 IEC 61937 后直通声卡
//
// 之所以要抽这层：视频侧只关心"音频时钟走到哪了、放完没有、能不能 seek"，
// 而"声音是怎么到声卡的"对它毫无影响 —— 抽取之后上层的同步逻辑一行都不用改。
// ============================================================================
#pragma once

#include <cstdint>
#include <string>

#include "audio_sink.h"
#include "ffmpeg_dyn.h"

namespace cineduct::host {

struct AudioChain {
    virtual ~AudioChain() = default;

    /**
     * @param headers    HTTP 请求头（Referer/Cookie…）
     * @param userAgent  给 FFmpeg 的 user_agent。**必须传**：音频链是**另开一路输入**去拉同一条
     *                   流的，直播 CDN（live-bvc）不认 FFmpeg 自带的 `Lavf/…`，会回 403 ——
     *                   症状就是"直播有画面没声音"（`音频不可用，改为纯视频: 打不开音频链`）。
     */
    virtual bool open(const FfmpegApi& api, AudioSink& sink, const std::string& url,
                      const std::string& headers, const std::string& userAgent,
                      std::string& error) = 0;
    virtual void start() = 0;
    /** 重头再放（循环播放用）：关掉当前线程后重新打开同一条链。 */
    virtual bool restart(std::string& error) = 0;
    /**
     * 定位到指定媒体时间（毫秒）。
     *
     * <p>**非阻塞**：只登记请求，由链路自己的线程执行。调用方还要负责清声卡缓冲
     * （AudioSink::flush）并重设时钟锚点 —— 那是"音画同步"的事，与链路实现无关。
     */
    virtual bool seek(long long ms, std::string& error) = 0;
    virtual void close() = 0;

    virtual long long durationMs() const = 0;
    virtual bool finished() const = 0;
    virtual bool failed() const = 0;

    /**
     * 已**执行**完的定位请求数（每执行一次 +1）。
     *
     * <p>为什么需要它：seek 是异步的（定位只能由链路的线程做，它才拿着 FFmpeg 上下文）。
     * 上层在 seek 之后清了声卡缓冲、重锚了时钟，而链路线程可能还在把"seek 之前解出来的"
     * 样本往声卡里写 —— 于是新位置上会先响一小段旧声音（几十到几百毫秒的音画错位）。
     * 有了这个计数，上层就能"等它真的跳过去了再清一次缓冲"（见视频流水线的 seek settle）。
     */
    virtual uint64_t seekAppliedCount() const = 0;

    /**
     * **最新写出的那个样本**在媒体时间轴上的位置（毫秒）；未知返回 -1。
     *
     * <p>为什么需要它（这是"音画不同步"能不能被**发现**的关键）：音频时钟是
     * `playedFrames`（写入 − 丢弃 − 设备待播）算出来的，它只反映"设备的节拍"，
     * **对内容位置一无所知** —— 音频链一旦把内容往回/往前跳一段（重连、seek 落地、
     * 链路重建），时钟照样平滑前进，于是"画面 vs 时钟"永远约等于 0，
     * 不同步就永远发现不了、也就永远纠正不了。
     *
     * <p>有了这个值，"**真正可听的内容位置**"就能算出来：
     * <pre>可听内容 = 最新写出样本的内容时间 − (写入 − 已播) / 采样率</pre>
     * 把它和视频帧的 PTS 一减，才是真正的音画偏移（见 video_pipeline 的 checkAudioVideoOffset）。
     */
    virtual double writtenContentMs() const { return -1.0; }

    /*
     * 音频链健康（v10，见 docs 与 video_pipeline 的 maybeRepairAudio）。
     *
     * ★ 为什么这三个计数必须有：DD 前面那次事故（2026-10-17）里，音频链把
     * "读超时/网络中断"当成"片尾"，进待命后**永远不产出**，而状态环里只有"欠载次数"
     * （那是"设备没数据"的后果，不是原因）—— 上层完全看不出"音频还活着吗"。
     */
    /** 累计读失败次数（网络中断 / rw_timeout 超时 —— **不是片尾**）。 */
    virtual uint32_t readErrorCount() const { return 0; }
    /** 累计"重开输入"次数（读失败后重连并回到原位置）。 */
    virtual uint32_t reconnectCount() const { return 0; }
    /** 请求"重开输入"（链路僵死/片尾待命时由上层发起）。非阻塞、可重复调用。 */
    virtual void requestReopen() {}

    /** "decode" / "passthrough" —— 日志与状态汇报用。 */
    virtual const char* kind() const = 0;
};

} // namespace cineduct::host

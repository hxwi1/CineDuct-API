package dev.mediafix.api.client;

import dev.mediafix.api.PlaybackState;

import java.util.UUID;

/**
 * 一块屏幕**客户端实际**的播放状态。
 *
 * <p>服务端权威值（{@code ScreenInfo}）说明"应该播到哪、音量多少"；
 * 这里说明"实际播成了什么样"：真实传输方式、子进程发布/丢弃了多少帧、
 * 取帧时等锁等了多久、有没有出错。附属 mod 做调试面板/统计时用它。
 *
 * @param screenId           屏幕 UUID
 * @param state              客户端播放状态
 * @param positionMs         客户端实际位置（毫秒）
 * @param durationMs         时长；未知 -1（直播恒为 -1）
 * @param transport          实际传输方式：{@code dxgi-interop}（子进程零拷贝）/ {@code java}（进程内兜底）
 * @param live               是否直播
 * @param publishedFrames    子进程已发布帧数（Java 兜底路径恒为 0）
 * @param droppedFrames      子进程丢帧数（拿不到槽位锁时丢弃）
 * @param lastAcquireUs      子进程最近一次等槽位锁的耗时（微秒）
 * @param error              最近一次错误（正常为空串）
 * @param audioMode          音频输出形态：{@code IEC61937直通} / {@code 独占PCM} / {@code 共享模式}
 * @param volumeLocked       音量是否由接收端控制（直通模式下码流不可改，音量命令无效）
 * @param videoWidth         实际视频宽度（子进程路径才有；未知 0）
 * @param videoHeight        实际视频高度（未知 0）
 * @param liveLatencyMs      直播实时延迟（毫秒）；非直播或未锚定为 -1
 * @param spatialAudioState  空间音效（杜比全景声等）状态文本
 * @param audioDeviceName    实际在用的音频端点名
 */
public record ClientScreenStatus(UUID screenId, PlaybackState state, long positionMs, long durationMs,
                                 String transport, boolean live, long publishedFrames,
                                 long droppedFrames, int lastAcquireUs, String error,
                                 String audioMode, boolean volumeLocked,
                                 int videoWidth, int videoHeight, long liveLatencyMs,
                                 String spatialAudioState, String audioDeviceName) {

    /** 是否在用零拷贝（子进程）路径。 */
    public boolean zeroCopy() {
        return "dxgi-interop".equals(this.transport);
    }

    /** 音频是否走"压缩码流直通"（功放硬件解码，例如杜比全景声）。 */
    public boolean passthroughAudio() {
        return "IEC61937直通".equals(this.audioMode);
    }

    /** 实际视频分辨率文本（未知时 "?"）。 */
    public String resolutionText() {
        return this.videoWidth > 0 && this.videoHeight > 0
                ? this.videoWidth + "x" + this.videoHeight : "?";
    }

    /** 转成通用的播放快照（写 UI/日志时方便）。 */
    public dev.mediafix.api.PlaybackStatus playback() {
        return new dev.mediafix.api.PlaybackStatus(this.state, this.positionMs, this.durationMs,
                this.live, this.liveLatencyMs, 0, this.transport, this.error);
    }
}

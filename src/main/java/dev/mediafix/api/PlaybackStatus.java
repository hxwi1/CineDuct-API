package dev.mediafix.api;

/**
 * 一次播放的只读快照（给附属 mod / 指令 / 诊断看）。
 *
 * @param state           播放状态
 * @param positionMs      当前播放位置（毫秒）
 * @param durationMs      时长；未知为 -1（直播恒为 -1）
 * @param live            是否直播
 * @param liveLatencyMs   直播实时延迟（毫秒）；未锚定为 -1
 * @param volumePercent   音量（0~100）
 * @param transport       实际在用的帧传输方式：{@code dxgi-interop} / {@code shm} / {@code java}
 * @param errorText       最后一次错误（正常时为空串）
 */
public record PlaybackStatus(PlaybackState state, long positionMs, long durationMs, boolean live,
                             long liveLatencyMs, int volumePercent, String transport, String errorText) {
}

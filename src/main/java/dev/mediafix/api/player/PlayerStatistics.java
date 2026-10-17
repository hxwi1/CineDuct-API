package dev.mediafix.api.player;

/**
 * 一条独立播放会话的运行统计（{@link CinePlayer#statistics()}）。
 *
 * <p>给附属 mod 做自己的诊断界面用 —— {@code transport()} 只告诉你"走的哪条路"，
 * 这里给的是"这条路跑得怎么样"：出了多少帧、丢了多少、取帧时等锁等了多久、子进程活了多久。
 *
 * <p><b>哪些字段只在零拷贝路径有意义</b>：帧计数、等锁耗时、进程 pid/存活时长都来自子进程的状态环；
 * 走进程内引擎时它们恒为 0（那时"发布/丢弃"这个概念不存在）。用 {@link #zeroCopy()} 判断。
 *
 * @param transport      实际传输方式：{@code dxgi-interop}（零拷贝）/ {@code java}（进程内）/ {@code (解析中)}
 * @param zeroCopy       是否真的在走零拷贝
 * @param publishedFrames 子进程已发布帧数（进程内恒 0）
 * @param droppedFrames  子进程丢帧数（拿不到槽位锁时丢弃）
 * @param lastAcquireUs  最近一次等槽位锁的耗时（微秒）
 * @param maxAcquireUs   等槽位锁的最坏耗时（微秒）—— 明显偏大说明渲染线程被别的活堵住了
 * @param slots          子进程共享纹理槽位数（进程内恒 0）
 * @param hostPid        子进程 pid（没有子进程时 0）
 * @param hostUptimeMs   子进程已运行时长；进程内路径下是"本会话已存在时长"
 * @param videoWidth     视频宽（未知 0）
 * @param videoHeight    视频高（未知 0）
 * @param positionMs     当前播放位置
 * @param durationMs     时长（直播/未知 -1）
 * @param live           是否直播
 * @param liveLatencyMs  直播实时延迟（毫秒）；非直播或未知 -1
 * @param audioMode      音频输出形态人话（{@code IEC61937直通} / {@code 独占PCM} / {@code 共享模式}）
 * @param volumeLocked   音量是否由接收端控制（直通时为 true）
 * @param restarts       本会话自动重启子进程的次数
 * @param error          最近一次错误（正常为空串）
 */
public record PlayerStatistics(String transport, boolean zeroCopy, long publishedFrames,
                               long droppedFrames, int lastAcquireUs, int maxAcquireUs, int slots,
                               long hostPid, long hostUptimeMs, int videoWidth, int videoHeight,
                               long positionMs, long durationMs, boolean live, long liveLatencyMs,
                               String audioMode, boolean volumeLocked, int restarts, String error) {

    /** 帧率估算用的"已出帧数"（进程内路径为 0，注意别用它算 fps）。 */
    public boolean hasFrameCounters() {
        return this.zeroCopy;
    }
}

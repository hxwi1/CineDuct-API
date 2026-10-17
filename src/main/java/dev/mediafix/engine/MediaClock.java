package dev.mediafix.engine;

import dev.mediafix.CineDuct;

/**
 * 播放主时钟。自研，替代 VLC 的时间轴。
 *
 * <p><b>模型</b>：{@code time = ptsDrift + now}，即"最近一次校准点 + 之后流逝的墙钟"。
 * 拿到权威时间（音频可听位置）时只做速率修正，绝不随测量值瞬移。
 *
 * <p><b>为什么要分这么多层</b>：音频"可听位置"的测量本身是抖的（设备已播帧数按周期更新），
 * 一抖就瞬移会让画面一顿一顿、还会反复点着 waterframes 的进度纠偏。
 * 流媒体播放器的通行做法是分层处理（WebRTC NetEq / GStreamer / VLC 都类似）：
 * <ol>
 *   <li><b>抖动缓冲</b>：先攒够再播 —— 对应音频环形缓冲（8 秒）与设备缓冲，见 {@link AudioSink}；</li>
 *   <li><b>时钟漂移补偿</b>：小偏差靠"微调速率"慢慢消化，而不是跳时间；</li>
 *   <li><b>时间戳平滑：死区 + 阈值</b> —— 偏差在容差内直接隐藏（GStreamer 的
 *       {@code drift_tolerance} 就是这个意思），超过容差才缓慢纠偏；</li>
 *   <li><b>离群剔除</b>：单次异常测量（时间戳被重打之类）不许拽走整条时间轴，
 *       要连续多次都这么偏才认定为"真的换了位置"。</li>
 * </ol>
 */
public final class MediaClock {

    // ---- 第 3 层：死区与速率修正 ----
    /** 偏差死区（秒）：在这以内一律按 1.0 倍速走，不去追测量噪声。 */
    private static final double DEADBAND = 0.040;
    /** 超出死区后的最大速率偏差（±5%）：听不出来，但能把偏差慢慢消化掉。 */
    private static final double MAX_SLEW = 0.05;
    /** 偏差转速率修正的比例。 */
    private static final double SLEW_GAIN = 0.6;
    /** 速率本身的变化也要限速（每秒最多 3%），否则时钟会"抽搐"。 */
    private static final double MAX_SLEW_RATE = 0.03;

    // ---- 第 4 层：离群剔除 ----
    /** 单次偏差超过它就不采信（秒）。 */
    private static final double OUTLIER = 1.5;
    /** 连续多少次都这么偏，才认定是真的换位置（seek / 断流）。 */
    private static final int OUTLIER_STREAK = 3;
    /** 残差指数平滑系数（轻量版卡尔曼：够用且没有数值调参负担）。 */
    private static final double RESIDUAL_EMA = 0.25;

    private volatile double ptsDrift;
    private volatile double lastUpdate;
    private volatile double frozenPts;
    private volatile boolean running;
    private volatile double speed = 1.0;

    /** 平滑后的测量残差（毫秒）：正值 = 音频比时钟快。诊断用。 */
    private volatile double smoothedResidualMs;
    private int outlierStreak;
    private long lastLogAt;

    public MediaClock() {
        reset();
    }

    private static double wallclock() {
        return System.nanoTime() / 1_000_000_000.0;
    }

    /** 当前时间（秒）：运行中按墙钟外推，暂停/未开始时冻结。 */
    public double time() {
        if (!this.running) return this.frozenPts;
        double now = wallclock();
        double elapsed = now - this.lastUpdate;
        return this.ptsDrift + now - elapsed * (1.0 - this.speed);
    }

    public long timeMs() {
        return (long) (time() * 1000.0);
    }

    public boolean isRunning() {
        return this.running;
    }

    /** 当前速率（1.0 = 正常）。诊断用。 */
    public double speed() {
        return this.speed;
    }

    /** 平滑后的测量残差（毫秒）。诊断用。 */
    public long residualMs() {
        return (long) this.smoothedResidualMs;
    }

    /** 启动：从当前位置开始按墙钟推进。 */
    public void start() {
        this.ptsDrift = this.frozenPts - wallclock();
        this.lastUpdate = wallclock();
        this.speed = 1.0;
        this.smoothedResidualMs = 0.0;
        this.outlierStreak = 0;
        this.running = true;
    }

    /** 暂停：把当前时间冻住。 */
    public void pause() {
        if (this.running) {
            this.frozenPts = time();
            this.running = false;
        }
    }

    /** 跳转：强制把时间设为给定值（seek 时调用）。 */
    public void seekTo(double seconds) {
        this.frozenPts = Math.max(0.0, seconds);
        this.ptsDrift = this.frozenPts - wallclock();
        this.lastUpdate = wallclock();
        this.speed = 1.0;
        this.smoothedResidualMs = 0.0;
        this.outlierStreak = 0;
    }

    /** 回到初始状态。 */
    public void reset() {
        this.frozenPts = 0.0;
        this.ptsDrift = -wallclock();
        this.lastUpdate = wallclock();
        this.running = false;
        this.speed = 1.0;
        this.smoothedResidualMs = 0.0;
        this.outlierStreak = 0;
    }

    /**
     * 用权威时间校准（通常来自音频可听位置）。
     *
     * @param ptsSec 权威时间（秒）
     * @param force  强制对齐（seek、起播、缓冲结束）
     */
    public void update(double ptsSec, boolean force) {
        if (!this.running) {
            this.frozenPts = ptsSec;
            return;
        }
        double now = wallclock();
        double residual = ptsSec - time();

        if (force) {
            rebase(now);
            this.ptsDrift = ptsSec - now;
            this.lastUpdate = now;
            this.speed = 1.0;
            this.smoothedResidualMs = 0.0;
            this.outlierStreak = 0;
            return;
        }

        // ---- 第 4 层：离群剔除 ----
        if (Math.abs(residual) > OUTLIER) {
            if (++this.outlierStreak < OUTLIER_STREAK) {
                logRateLimited("[CineDuct] 时钟收到一次异常测量（偏差 {}ms）：先不采信，等后续确认",
                        residual * 1000.0);
                return;   // 时钟不动：单次异常绝不许拽走时间轴
            }
            // 连续多次都偏这么多 → 认定真的换了位置（seek / 断流），硬对齐
            double before = time();
            rebase(now);
            this.ptsDrift = ptsSec - now;
            this.lastUpdate = now;
            this.speed = 1.0;
            this.smoothedResidualMs = 0.0;
            this.outlierStreak = 0;
            logRateLimited("[CineDuct] 时钟硬重同步（连续 {} 次测量一致）：{}ms -> {}ms",
                    OUTLIER_STREAK, before * 1000.0, ptsSec * 1000.0);
            return;
        }
        this.outlierStreak = 0;

        // ---- 第 3 层：残差平滑 + 死区 ----
        this.smoothedResidualMs += RESIDUAL_EMA * (residual * 1000.0 - this.smoothedResidualMs);
        double smoothed = this.smoothedResidualMs / 1000.0;

        double target;
        if (Math.abs(smoothed) < DEADBAND) {
            target = 1.0;                        // 容差内：完全不动，把微小漂移隐藏掉
        } else {
            target = 1.0 + Math.max(-MAX_SLEW, Math.min(MAX_SLEW, smoothed * SLEW_GAIN));
        }

        // ---- 第 2 层：速率变化限速 ----
        double dt = Math.max(1.0e-3, now - this.lastUpdate);
        double maxDelta = MAX_SLEW_RATE * dt;
        if (target > this.speed + maxDelta) target = this.speed + maxDelta;
        else if (target < this.speed - maxDelta) target = this.speed - maxDelta;

        if (Math.abs(target - this.speed) > 5.0e-4) {
            rebase(now);
            this.speed = target;
        } else {
            this.lastUpdate = now;
        }
    }

    /** 把当前时间固化进基准，保证改速率时时间连续（不跳变）。 */
    private void rebase(double now) {
        double current = time();
        this.ptsDrift = current - now;
        this.lastUpdate = now;
    }

    private void logRateLimited(String fmt, double a) {
        long now = System.currentTimeMillis();
        if (now - this.lastLogAt < 1000L) return;
        this.lastLogAt = now;
        CineDuct.LOGGER.warn(fmt, a);
    }

    private void logRateLimited(String fmt, double a, double b) {
        long now = System.currentTimeMillis();
        if (now - this.lastLogAt < 1000L) return;
        this.lastLogAt = now;
        CineDuct.LOGGER.warn(fmt, a, b);
    }

    private void logRateLimited(String fmt, int a, double b, double c) {
        long now = System.currentTimeMillis();
        if (now - this.lastLogAt < 1000L) return;
        this.lastLogAt = now;
        CineDuct.LOGGER.warn(fmt, a, b, c);
    }

    /** 变速（预留：目前播放器只跑 1.0x）。 */
    public void setSpeed(double speed) {
        this.frozenPts = time();
        this.speed = Math.max(0.25, Math.min(4.0, speed));
        this.ptsDrift = this.frozenPts - wallclock();
        this.lastUpdate = wallclock();
    }
}

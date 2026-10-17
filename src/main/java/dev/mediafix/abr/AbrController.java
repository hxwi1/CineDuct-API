package dev.mediafix.abr;

import dev.mediafix.CineDuct;

/**
 * 自适应码率控制器（ABR）。
 *
 * <p>按业界通行做法（HLS.js / Shaka / ExoPlayer 的混合策略）实现三层：
 * <ol>
 *   <li><b>带宽估计</b>：两个指数加权移动平均（快 + 慢）。快 EWMA 对带宽骤降反应快，
 *       慢 EWMA 给出稳定的长期水平，取两者较小者作为保守估计。</li>
 *   <li><b>安全系数</b>：向下切换用 {@link #DOWN_FACTOR}，向上切换用更保守的
 *       {@link #UP_FACTOR}（资料里 HLS.js 的 abrBandWidthFactor=0.95 / abrBandWidthUpFactor=0.7）。</li>
 *   <li><b>缓冲水位</b>：缓冲在涨说明网络有余量，可以升；缓冲在掉说明网络吃紧，
 *       即使测速看起来够也要压住 —— 这就是 dash.js DYNAMIC 那种"低水位用速率、高水位用缓冲"的混合思路。</li>
 * </ol>
 *
 * <p>切换有滞回（{@link #MIN_DWELL_MS}）：刚切完一段时间内不再切，避免在阈值上反复横跳。
 * 我们没有分片，所以换档 = 用新 url 重新打开同一位置（音频链不动）。
 */
public final class AbrController {

    /** 快 EWMA 权重（对骤降敏感）。 */
    private static final double FAST_ALPHA = 0.5;
    /** 慢 EWMA 权重（长期水平）。 */
    private static final double SLOW_ALPHA = 0.12;
    /** 向下切换的安全系数。 */
    private static final double DOWN_FACTOR = 0.90;
    /** 向上切换的安全系数（更保守，防止高估带宽导致卡顿）。 */
    private static final double UP_FACTOR = 0.70;
    /** 两次切换之间的最小间隔。 */
    private static final long MIN_DWELL_MS = 20_000L;
    /** 向上切换前，需要连续观察到"网络有余量"的时长。 */
    private static final long UP_STABLE_MS = 30_000L;

    private static volatile double fastBps;
    private static volatile double slowBps;
    private static volatile long lastSwitchAt;
    private static volatile long healthySince;

    private AbrController() {
    }

    /** 记录一次吞吐采样（只在"网络确实是瓶颈"时调用，见 FfmpegVideoSource.readRate）。 */
    public static void noteThroughput(long bps) {
        if (bps <= 0) return;
        double v = bps;
        fastBps = fastBps <= 0 ? v : fastBps * (1 - FAST_ALPHA) + v * FAST_ALPHA;
        slowBps = slowBps <= 0 ? v : slowBps * (1 - SLOW_ALPHA) + v * SLOW_ALPHA;
    }

    /** 保守的可用带宽估计：取快慢两者的较小值。 */
    public static long estimateBps() {
        double f = fastBps, s = slowBps;
        if (f <= 0) return (long) s;
        if (s <= 0) return (long) f;
        return (long) Math.min(f, s);
    }

    public static void reset() {
        fastBps = 0;
        slowBps = 0;
        lastSwitchAt = 0;
        healthySince = 0;
    }

    /** 决策结果：-1 降档、+1 升档、0 不动。 */
    public static int decide(long bufferedBytes, long bufferedHighBytes, boolean recentlyRebuffered) {
        AbrLadder.Candidate cur = AbrLadder.current();
        if (cur == null) return 0;
        long now = System.currentTimeMillis();
        long bitrate = cur.bandwidthBps();
        long est = estimateBps();

        // ---- 向下切换：实测带宽撑不住当前码率，或者刚发生过因缓冲见底而停顿 ----
        boolean byBandwidth = est > 0 && est * DOWN_FACTOR < bitrate;
        if (recentlyRebuffered || byBandwidth) {
            if (AbrLadder.lower() != null && now - lastSwitchAt > MIN_DWELL_MS) {
                lastSwitchAt = now;
                healthySince = 0;
                // 两种原因的措辞要分开：以前共用一句"估计带宽 X 撑不住 Y"，
                // 结果日志里出现"52Mbps 撑不住 2Mbps"这种读起来像 bug 的记录
                if (byBandwidth) {
                    CineDuct.LOGGER.warn("[CineDuct] ABR 降档：估计带宽 {}kbps 低于当前档位 {}kbps 的安全线",
                            est / 1000, bitrate / 1000);
                } else {
                    CineDuct.LOGGER.warn("[CineDuct] ABR 降档：最近发生过缓冲见底（与实测带宽无关，估计带宽 {}kbps）",
                            est / 1000);
                }
                return -1;
            }
        }

        // ---- 向上切换：缓冲健康持续 30 秒，且带宽估计明显高于上一档所需 ----
        boolean bufferHealthy = bufferedBytes >= bufferedHighBytes;
        if (bufferHealthy) {
            if (healthySince == 0) healthySince = now;
        } else {
            healthySince = 0;
        }
        AbrLadder.Candidate up = AbrLadder.higher();
        if (up != null && healthySince > 0 && now - healthySince > UP_STABLE_MS
                && now - lastSwitchAt > MIN_DWELL_MS
                && est > 0 && up.bandwidthBps() <= est * UP_FACTOR) {
            lastSwitchAt = now;
            healthySince = 0;
            CineDuct.LOGGER.info("[CineDuct] ABR 升档：估计带宽 {}kbps 足以支撑 {}kbps",
                    est / 1000, up.bandwidthBps() / 1000);
            return 1;
        }
        return 0;
    }
}

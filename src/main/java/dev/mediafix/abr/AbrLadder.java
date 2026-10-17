package dev.mediafix.abr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 自适应码率（ABR）的"多码率清单"。
 *
 * <p>流媒体播放器通常要读一个 Manifest 才知道有哪些码率可选；B 站不需要 ——
 * {@code playurl} 一次就把**所有清晰度的直链**都返回了，每条带 {@code bandwidth}（bps）。
 * 所以这里直接把那份 {@code dash.video} 数组收成一条升降有序的阶梯，就是 ABR 的输入。
 *
 * <p>切换语义：我们没有分片，一条 url 就是一个完整表示，所以"换档"= 用新 url 重新打开
 * 同一位置的视频轨（等价于播放器在分片边界切表示），音频链不动。
 */
public final class AbrLadder {

    /**
     * 一个可选清晰度。
     * @param qn            B 站清晰度编号（120=4K、127=8K …）
     * @param bandwidthBps  码率（bps），ABR 的决策依据
     * @param url           直链
     * @param label         展示名（清晰度 + 编码）
     * @param width         流的真实宽度（用来识别"标称 4K 实为 1080p"的假 4K）
     * @param height        流的真实高度
     */
    public record Candidate(int qn, long bandwidthBps, String url, String label, int width, int height) {

        /** 真实分辨率文本，如 {@code 3840x2160}。 */
        public String resolution() {
            return width > 0 && height > 0 ? width + "x" + height : "?";
        }
    }

    private static volatile List<Candidate> ladder = List.of();
    private static volatile int currentIndex = -1;

    private AbrLadder() {
    }

    /** 按码率升序保存阶梯（DashResolver 解析时调用）。 */
    public static void set(List<Candidate> candidates, int index) {
        List<Candidate> sorted = new ArrayList<>(candidates);
        sorted.sort((a, b) -> Long.compare(a.bandwidthBps(), b.bandwidthBps()));
        ladder = Collections.unmodifiableList(sorted);
        currentIndex = Math.max(0, Math.min(sorted.size() - 1, index));
    }

    public static List<Candidate> all() {
        return ladder;
    }

    public static int currentIndex() {
        return currentIndex;
    }

    public static void setCurrentIndex(int index) {
        currentIndex = index;
    }

    public static Candidate current() {
        List<Candidate> l = ladder;
        int i = currentIndex;
        return (i >= 0 && i < l.size()) ? l.get(i) : null;
    }

    /** 当前表示的码率（bps）；未知时返回 0。 */
    public static long currentBitrate() {
        Candidate c = current();
        return c == null ? 0L : c.bandwidthBps();
    }

    /** 下一个更低的档位；已是最低返回 null。 */
    public static Candidate lower() {
        List<Candidate> l = ladder;
        int i = currentIndex - 1;
        return (i >= 0 && i < l.size()) ? l.get(i) : null;
    }

    /** 下一个更高的档位；已是最高返回 null。 */
    public static Candidate higher() {
        List<Candidate> l = ladder;
        int i = currentIndex + 1;
        return (i >= 0 && i < l.size()) ? l.get(i) : null;
    }

    public static void clear() {
        ladder = List.of();
        currentIndex = -1;
    }

    /** 按可用带宽挑一个档位（不超过 带宽 × factor 的最高档）。 */
    public static int pickForBandwidth(long availableBps, double factor, int minIndex, int maxIndex) {
        List<Candidate> l = ladder;
        long budget = (long) (availableBps * factor);
        int best = minIndex;
        for (int i = minIndex; i <= maxIndex && i < l.size(); i++) {
            if (l.get(i).bandwidthBps() <= budget) best = i;
        }
        return best;
    }
}

package dev.mediafix.api.player;

/**
 * {@link CinePlayerApi#open} 的参数包（不可变值对象；{@link Builder} 每一项都有默认值）。
 *
 * <pre>{@code
 * PlayerOptions opts = PlayerOptions.builder()
 *         .volume(80)
 *         .loop(true)
 *         .audioMode("passthrough")
 *         .build();
 * }</pre>
 */
public final class PlayerOptions {

    private static final PlayerOptions DEFAULTS = builder().build();

    private final String audioMode;
    private final int volume;
    private final boolean muted;
    private final boolean repeat;
    private final boolean paused;
    private final long startAtMs;
    private final int qualityCap;
    private final float displayAspect;
    private final Transport transport;

    private PlayerOptions(Builder b) {
        this.audioMode = b.audioMode;
        this.volume = b.volume;
        this.muted = b.muted;
        this.repeat = b.repeat;
        this.paused = b.paused;
        this.startAtMs = b.startAtMs;
        this.qualityCap = b.qualityCap;
        this.displayAspect = b.displayAspect;
        this.transport = b.transport;
    }

    /** 全默认（音量 100、不静音、不循环、不暂停、从 0 起播、清晰度自动）。 */
    public static PlayerOptions defaults() {
        return DEFAULTS;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 每屏音频形态：{@code ""}（跟随全局配置）/ {@code auto} / {@code shared} / {@code exclusive} / {@code passthrough}。 */
    public String audioMode() {
        return this.audioMode;
    }

    public int volume() {
        return this.volume;
    }

    public boolean muted() {
        return this.muted;
    }

    public boolean repeat() {
        return this.repeat;
    }

    public boolean paused() {
        return this.paused;
    }

    /** 起播位置（毫秒）。 */
    public long startAtMs() {
        return this.startAtMs;
    }

    /** 清晰度上限：-1 自动 / 0 账号内最高 / 否则 B 站 qn（80=1080P、116=1080P60、120=4K、127=8K）。 */
    public int qualityCap() {
        return this.qualityCap;
    }

    /** 显示面宽高比（宽/高）；{@code 0} = 不做信箱化。 */
    public float displayAspect() {
        return this.displayAspect;
    }

    /** 帧传输方式偏好（默认 {@link Transport#AUTO}）。 */
    public Transport transport() {
        return this.transport;
    }

    /** 可变构造器。 */
    public static final class Builder {

        private String audioMode = "";
        private int volume = 100;
        private boolean muted;
        private boolean repeat;
        private boolean paused;
        private long startAtMs;
        private int qualityCap = -1;
        private float displayAspect;
        private Transport transport = Transport.AUTO;

        /** 音频输出形态覆盖；空串 = 跟随全局配置（{@code cineduct-ffmpeg.json} 的 audioMode）。 */
        public Builder audioMode(String value) {
            this.audioMode = value == null ? "" : value;
            return this;
        }

        /** 音量 0~100（会被夹到这个范围）。 */
        public Builder volume(int percent) {
            this.volume = Math.max(0, Math.min(100, percent));
            return this;
        }

        public Builder muted(boolean value) {
            this.muted = value;
            return this;
        }

        public Builder loop(boolean value) {
            this.repeat = value;
            return this;
        }

        public Builder paused(boolean value) {
            this.paused = value;
            return this;
        }

        /** 起播位置（毫秒，负数当 0）。 */
        public Builder startAt(long ms) {
            this.startAtMs = Math.max(0L, ms);
            return this;
        }

        public Builder qualityCap(int qn) {
            this.qualityCap = qn;
            return this;
        }

        /**
         * 显示面宽高比（宽/高），例如 16:9 传 {@code 16f/9f}。
         *
         * <p><b>开启后黑边会烘焙进纹理</b>：画面等比放进"比例 = 本值"的输出框，框外是真黑边。
         * 调用方把整张纹理铺满自己的显示面即可，**不会拉伸、也不用自己再加边**
         * （此时 {@link CinePlayer#drawAspect()} 返回的就是这张纹理自己的比例）。
         *
         * <p>传 {@code <= 0}（默认）= 不做信箱化：纹理就是视频原始尺寸，比例由调用方自己处理。
         */
        public Builder displayAspect(float aspect) {
            this.displayAspect = Float.isFinite(aspect) && aspect > 0.01f ? aspect : 0f;
            return this;
        }

        /**
         * 帧传输方式偏好：{@link Transport#AUTO}（默认）/ {@link Transport#ZERO_COPY} / {@link Transport#IN_PROCESS}。
         *
         * <p>用途：排障（怀疑零拷贝在某个驱动上出问题 → 强制进程内引擎），
         * 或者反过来"我就要零拷贝那条路（子进程 + WASAPI 直出）"。
         * 注意 {@code ZERO_COPY} 在"探不到同卡 GL 适配器"时仍会退回进程内引擎 —— 那条路的前提不成立。
         */
        public Builder transport(Transport value) {
            this.transport = value == null ? Transport.AUTO : value;
            return this;
        }

        public PlayerOptions build() {
            return new PlayerOptions(this);
        }
    }
}

package dev.mediafix.api;

import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * 一块屏幕的全量参数（建屏时用；也可以拿它当"参数包"批量套用）。
 *
 * <pre>{@code
 * ScreenOptions opts = ScreenOptions.builder()
 *         .position(new Vec3(100, 65, 200))
 *         .rotation(180f, 0f)          // 朝北
 *         .size(8f, 4.5f)              // 影院幕布
 *         .source("https://www.bilibili.com/video/BV1xx411c7mD")
 *         .volume(80).range(2f, 40f)
 *         .loop(true)
 *         .brightness(0.9f).fit(ScreenFit.LETTERBOX)
 *         .audioMode("passthrough")
 *         .build();
 * ScreenHandle screen = CineDuctApi.get().createScreen(serverLevel, opts);
 * }</pre>
 *
 * <p>这是一个不可变值对象；{@link Builder} 的每一项都有默认值（和实体默认值一致）。
 */
public final class ScreenOptions {

    private final Vec3 position;
    private final float yaw;
    private final float pitch;
    private final float width;
    private final float height;
    private final String source;
    private final int volume;
    private final float rangeMin;
    private final float rangeMax;
    private final boolean muted;
    private final boolean loop;
    private final boolean paused;
    private final int qualityCap;
    private final float alpha;
    private final float brightness;
    private final ScreenFit fit;
    private final boolean doubleSided;
    private final String audioMode;
    private final long startAtMs;

    private ScreenOptions(Builder b) {
        this.position = b.position;
        this.yaw = b.yaw;
        this.pitch = b.pitch;
        this.width = b.width;
        this.height = b.height;
        this.source = b.source;
        this.volume = b.volume;
        this.rangeMin = b.rangeMin;
        this.rangeMax = b.rangeMax;
        this.muted = b.muted;
        this.loop = b.loop;
        this.paused = b.paused;
        this.qualityCap = b.qualityCap;
        this.alpha = b.alpha;
        this.brightness = b.brightness;
        this.fit = b.fit;
        this.doubleSided = b.doubleSided;
        this.audioMode = b.audioMode;
        this.startAtMs = b.startAtMs;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 从当前位置/朝向起的默认参数（位置必须给）。 */
    public static Builder builder(Vec3 position) {
        return new Builder().position(position);
    }

    public Vec3 position() {
        return this.position;
    }

    public float yaw() {
        return this.yaw;
    }

    public float pitch() {
        return this.pitch;
    }

    public float width() {
        return this.width;
    }

    public float height() {
        return this.height;
    }

    public String source() {
        return this.source;
    }

    public int volume() {
        return this.volume;
    }

    public float rangeMin() {
        return this.rangeMin;
    }

    public float rangeMax() {
        return this.rangeMax;
    }

    public boolean muted() {
        return this.muted;
    }

    public boolean loop() {
        return this.loop;
    }

    public boolean paused() {
        return this.paused;
    }

    public int qualityCap() {
        return this.qualityCap;
    }

    public float alpha() {
        return this.alpha;
    }

    public float brightness() {
        return this.brightness;
    }

    public ScreenFit fit() {
        return this.fit;
    }

    public boolean doubleSided() {
        return this.doubleSided;
    }

    public String audioMode() {
        return this.audioMode;
    }

    /** 建屏后的起始播放位置（毫秒）。 */
    public long startAtMs() {
        return this.startAtMs;
    }

    /** 可变构造器；所有项都有默认值。 */
    public static final class Builder {

        private Vec3 position = Vec3.ZERO;
        private float yaw;
        private float pitch;
        private float width = 2.0f;
        private float height = 1.125f;      // 16:9
        private String source = "";
        private int volume = 100;
        private float rangeMin = 1.0f;
        private float rangeMax = 24.0f;
        private boolean muted;
        private boolean loop;
        private boolean paused;
        private int qualityCap = -1;
        private float alpha = 1.0f;
        private float brightness = 1.0f;
        private ScreenFit fit = ScreenFit.LETTERBOX;
        private boolean doubleSided = true;
        private String audioMode = "";
        private long startAtMs;

        /** 屏幕**中心点**（不是底边）。 */
        public Builder position(Vec3 pos) {
            if (pos != null) this.position = pos;
            return this;
        }

        public Builder position(double x, double y, double z) {
            return position(new Vec3(x, y, z));
        }

        /** 朝向：yaw 0 = 朝南（+Z）；pitch 正数朝下。 */
        public Builder rotation(float yaw, float pitch) {
            this.yaw = yaw;
            this.pitch = pitch;
            return this;
        }

        /** 宽×高（世界单位，0.25~64）。 */
        public Builder size(float width, float height) {
            this.width = width;
            this.height = height;
            return this;
        }

        public Builder source(String uri) {
            this.source = uri == null ? "" : uri;
            return this;
        }

        public Builder volume(int percent) {
            this.volume = percent;
            return this;
        }

        public Builder range(float min, float max) {
            this.rangeMin = min;
            this.rangeMax = max;
            return this;
        }

        public Builder muted(boolean value) {
            this.muted = value;
            return this;
        }

        public Builder loop(boolean value) {
            this.loop = value;
            return this;
        }

        public Builder paused(boolean value) {
            this.paused = value;
            return this;
        }

        /** 清晰度上限：-1 自动 / 0 账号内最高 / 否则 B 站 qn（80=1080P、116=1080P60、120=4K、127=8K）。 */
        public Builder qualityCap(int qn) {
            this.qualityCap = qn;
            return this;
        }

        public Builder alpha(float value) {
            this.alpha = value;
            return this;
        }

        /** 亮度倍率 0.05~1（调暗用；屏幕已是满亮自发光，没有"更亮"这一档）。 */
        public Builder brightness(float value) {
            this.brightness = value;
            return this;
        }

        public Builder fit(ScreenFit value) {
            if (value != null) this.fit = value;
            return this;
        }

        public Builder doubleSided(boolean value) {
            this.doubleSided = value;
            return this;
        }

        /** 每屏音频形态：{@code auto} / {@code shared} / {@code exclusive} / {@code passthrough}；空 = 跟随配置。 */
        public Builder audioMode(String value) {
            this.audioMode = value == null ? "" : value;
            return this;
        }

        /** 建屏后从这个位置开始播（毫秒）。 */
        public Builder startAt(long ms) {
            this.startAtMs = Math.max(0L, ms);
            return this;
        }

        public ScreenOptions build() {
            return new ScreenOptions(this);
        }
    }
}

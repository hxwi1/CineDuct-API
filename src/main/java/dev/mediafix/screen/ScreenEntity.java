package dev.mediafix.screen;

import dev.mediafix.CineDuct;
import dev.mediafix.api.ResolvedStream;
import dev.mediafix.player.MediaSession;
import dev.mediafix.player.Sessions;
import dev.mediafix.source.SourceResolvers;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.net.URI;

/**
 * 屏幕实体 —— CineDuct 唯一的显示载体。
 *
 * <p><b>为什么是实体而不是方块</b>：实体可以直接放在任意位置/朝向、能做非整格的尺寸与悬挂角度，
 * 而且不需要方块实体那套同步与渲染注册；一切操作走指令（{@code /cineduct screen ...}），
 * 附属 mod 通过 {@code dev.mediafix.api} 建/控，不需要 GUI 与物品。
 *
 * <p><b>服务端权威进度</b>：实体内只存"锚点"（{@link #positionMsAtAnchor()} + 锚点代次），
 * 两端都从"看到锚点的那一刻"起用**本端真实时间**推进（见 {@link #projectedPositionMs()}）。
 * 不用游戏刻是有原因的：媒体时间是真实时间，而游戏刻随 TPS 走 —— 服务端 TPS 一低，
 * 权威位置就比真实时间慢，客户端会不停把播放往回拉（已修复，见 docs/PLAN-standalone-gpu.md §5.1）。
 *
 * <p>客户端侧的解析与播放见 {@code dev.mediafix.client.ScreenClientState}：
 * 服务端只负责"放什么、从哪一刻开始、暂停没有"，不碰解码。
 */
public class ScreenEntity extends Entity {

    private static final String TAG_SOURCE = "Source";
    private static final String TAG_WIDTH = "Width";
    private static final String TAG_HEIGHT = "Height";
    private static final String TAG_PAUSED = "Paused";
    private static final String TAG_LOOP = "Loop";
    private static final String TAG_MUTED = "Muted";
    private static final String TAG_VOLUME = "Volume";
    private static final String TAG_RANGE_MIN = "RangeMin";
    private static final String TAG_RANGE_MAX = "RangeMax";
    private static final String TAG_POS_AT_ANCHOR = "PosAtAnchor";
    private static final String TAG_ANCHOR_TICK = "AnchorTick";
    private static final String TAG_QUALITY = "Quality";

    /** 原始链接（B 站页面/直播間/直链），客户端据此解析。 */
    private static final EntityDataAccessor<String> DATA_SOURCE =
            SynchedEntityData.defineId(ScreenEntity.class, EntityDataSerializers.STRING);
    /** 屏幕尺寸（世界单位，宽×高）。 */
    private static final EntityDataAccessor<Float> DATA_WIDTH =
            SynchedEntityData.defineId(ScreenEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_HEIGHT =
            SynchedEntityData.defineId(ScreenEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Boolean> DATA_PAUSED =
            SynchedEntityData.defineId(ScreenEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DATA_LOOP =
            SynchedEntityData.defineId(ScreenEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DATA_MUTED =
            SynchedEntityData.defineId(ScreenEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> DATA_VOLUME =
            SynchedEntityData.defineId(ScreenEntity.class, EntityDataSerializers.INT);
    /** 距离衰减范围：近处满音量，超出远端静音（与旧版水帧的音量距离设置等价）。 */
    private static final EntityDataAccessor<Float> DATA_RANGE_MIN =
            SynchedEntityData.defineId(ScreenEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_RANGE_MAX =
            SynchedEntityData.defineId(ScreenEntity.class, EntityDataSerializers.FLOAT);
    /** 锚点：服务端记下"播到了哪一毫秒"，客户端收到后从**那一刻的真实时间**开始推进。 */
    private static final EntityDataAccessor<Long> DATA_POS_AT_ANCHOR =
            SynchedEntityData.defineId(ScreenEntity.class, EntityDataSerializers.LONG);
    /**
     * 锚点代次：每次钉新锚点（换源/seek/暂停/继续/循环回卷）都 +1。
     *
     * <p>客户端靠它识别"锚点变了 → 该对齐一次"，而不是每秒去比对位置
     * （后者在服务端 TPS 低的时候会把画面一直往回拉）。
     *
     * <p>旧版这里还同步了一个"锚点游戏刻"（{@code DATA_ANCHOR_TICK}）。**它已经被弃用**：
     * 游戏刻随 TPS 走，TPS 一低权威位置就比真实时间慢，客户端于是不停把播放拉回去。
     * 媒体时间本来就是真实时间，所以改成用本端挂钟推进（字段保留只为兼容旧存档的 NBT）。
     */
    private static final EntityDataAccessor<Integer> DATA_ANCHOR_GEN =
            SynchedEntityData.defineId(ScreenEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Long> DATA_ANCHOR_TICK =
            SynchedEntityData.defineId(ScreenEntity.class, EntityDataSerializers.LONG);
    /** 清晰度上限（qn；-1 = 跟随全局配置）。 */
    private static final EntityDataAccessor<Integer> DATA_QUALITY =
            SynchedEntityData.defineId(ScreenEntity.class, EntityDataSerializers.INT);
    /**
     * 不透明度（0.05~1）。1 = 完全不透明；做淡入淡出/半透明幕布时用。
     */
    private static final EntityDataAccessor<Float> DATA_ALPHA =
            SynchedEntityData.defineId(ScreenEntity.class, EntityDataSerializers.FLOAT);
    /**
     * 亮度倍率（0.05~1，只能调暗，见 setBrightness 的说明）。
     *
     * <p>屏幕是自发光（顶点格式里没有光照贴图），所以"亮度"就是直接乘在颜色上：
     * 小于 1 变暗（夜里不想太刺眼），大于 1 会过曝成光污染式的亮。
     */
    private static final EntityDataAccessor<Float> DATA_BRIGHTNESS =
            SynchedEntityData.defineId(ScreenEntity.class, EntityDataSerializers.FLOAT);
    /** 画面铺法（{@link dev.mediafix.api.ScreenFit} 的 id）。 */
    private static final EntityDataAccessor<Integer> DATA_FIT =
            SynchedEntityData.defineId(ScreenEntity.class, EntityDataSerializers.INT);
    /** 是否双面可见（true = 背面也画一张镜像；false = 只有正面）。 */
    private static final EntityDataAccessor<Boolean> DATA_DOUBLE_SIDED =
            SynchedEntityData.defineId(ScreenEntity.class, EntityDataSerializers.BOOLEAN);
    /**
     * 每块屏幕的音频输出形态覆盖（空串 = 跟随全局配置）。
     *
     * <p>取值与子进程的 {@code --audio-mode} 一致：{@code auto} / {@code shared} /
     * {@code exclusive} / {@code passthrough}。改这个会让该屏幕下次开会话时生效
     * （已经在播的会话要换源/重开才会用上新值）。
     */
    private static final EntityDataAccessor<String> DATA_AUDIO_MODE =
            SynchedEntityData.defineId(ScreenEntity.class, EntityDataSerializers.STRING);

    /** 本端（服务端或客户端）看到当前锚点的时刻，毫秒；不参与同步。 */
    private long anchorWallClockMs;

    public ScreenEntity(EntityType<? extends ScreenEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_SOURCE, "");
        builder.define(DATA_WIDTH, 2.0f);
        builder.define(DATA_HEIGHT, 1.125f);   // 默认 16:9
        builder.define(DATA_PAUSED, false);
        builder.define(DATA_LOOP, false);
        builder.define(DATA_MUTED, false);
        builder.define(DATA_VOLUME, 100);
        builder.define(DATA_RANGE_MIN, 1.0f);
        builder.define(DATA_RANGE_MAX, 24.0f);
        builder.define(DATA_POS_AT_ANCHOR, 0L);
        builder.define(DATA_ANCHOR_GEN, 0);
        builder.define(DATA_ANCHOR_TICK, 0L);
        builder.define(DATA_QUALITY, -1);
        builder.define(DATA_ALPHA, 1.0f);
        builder.define(DATA_BRIGHTNESS, 1.0f);
        builder.define(DATA_FIT, dev.mediafix.api.ScreenFit.LETTERBOX.id());
        builder.define(DATA_DOUBLE_SIDED, true);
        builder.define(DATA_AUDIO_MODE, "");
    }

    // =====================================================================
    // 状态读写（服务端权威；客户端这些 setter 只在本地生效，随后被同步覆盖）
    // =====================================================================

    public String sourceUri() {
        return this.entityData.get(DATA_SOURCE);
    }

    /** 设置播放源（原始链接）。会重置进度锚点，播放从头开始；服务端侧会发源变更事件。 */
    public void setSourceUri(String uri) {
        String next = uri == null ? "" : uri;
        String old = sourceUri();
        this.entityData.set(DATA_SOURCE, next);
        setAnchor(0L);
        if (!next.equals(old) && this.level() instanceof ServerLevel serverLevel) {
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(
                    new dev.mediafix.api.event.ScreenSourceChangedEvent(serverLevel,
                            dev.mediafix.screen.ScreenApi.handleFor(serverLevel, this),
                            dev.mediafix.screen.ScreenApi.infoOf(this), old, next));
        }
    }

    public float screenWidth() {
        return this.entityData.get(DATA_WIDTH);
    }

    public float screenHeight() {
        return this.entityData.get(DATA_HEIGHT);
    }

    public void setScreenSize(float width, float height) {
        final float w = Mth.clamp(width, 0.25f, 64.0f);
        final float h = Mth.clamp(height, 0.25f, 64.0f);
        if (Math.abs(w - screenWidth()) < 1.0e-4f && Math.abs(h - screenHeight()) < 1.0e-4f) return;
        this.entityData.set(DATA_WIDTH, w);
        this.entityData.set(DATA_HEIGHT, h);
        postChange(dev.mediafix.api.event.ScreenPlaybackChangedEvent.Change.SIZE,
                String.format("尺寸 %.2f×%.2f", w, h));
    }

    /** 屏幕自身的宽高比（渲染与信箱化都用它）。 */
    public float aspect() {
        float h = screenHeight();
        return h <= 0.001f ? 16f / 9f : screenWidth() / h;
    }

    public boolean isPaused() {
        return this.entityData.get(DATA_PAUSED);
    }

    /** 暂停/继续：同时把锚点钉在当前进度上。 */
    public void setPaused(boolean paused) {
        if (paused == isPaused()) return;
        // 无论暂停还是继续，都"以此刻的进度为新锚点"：暂停时它就冻住，继续时从它按真实时间推进
        setAnchor(this.projectedPositionMs());
        this.entityData.set(DATA_PAUSED, paused);
        postChange(dev.mediafix.api.event.ScreenPlaybackChangedEvent.Change.PAUSE,
                paused ? "已暂停" : "已继续");
    }

    /**
     * 钉一个新锚点：记下"此刻应播到哪"，并用**本端真实时间**作为推进基准。
     *
     * <p>服务端调用它来发起锚点变更；客户端由 {@link #onSyncedDataUpdated} 在数据到达时
     * 就地打时间戳 —— 两边都用真实时间推进，于是**与 TPS 无关**：
     * 服务端卡到 5 TPS，媒体时间依然按秒走，客户端不会因为"权威位置走得慢"而往回拉。
     */
    private void setAnchor(long positionMs) {
        this.entityData.set(DATA_POS_AT_ANCHOR, positionMs);
        this.entityData.set(DATA_ANCHOR_GEN, anchorGeneration() + 1);
        this.anchorWallClockMs = System.currentTimeMillis();
    }

    /** 锚点代次（变了就说明服务端改了锚点：seek/暂停/继续/换源/循环回卷）。 */
    public int anchorGeneration() {
        return this.entityData.get(DATA_ANCHOR_GEN);
    }

    public boolean isLoop() {
        return this.entityData.get(DATA_LOOP);
    }

    public void setLoop(boolean loop) {
        if (loop == isLoop()) return;
        this.entityData.set(DATA_LOOP, loop);
        postChange(dev.mediafix.api.event.ScreenPlaybackChangedEvent.Change.LOOP,
                loop ? "循环已开" : "循环已关");
    }

    public boolean isMuted() {
        return this.entityData.get(DATA_MUTED);
    }

    public void setMuted(boolean muted) {
        if (muted == isMuted()) return;
        this.entityData.set(DATA_MUTED, muted);
        postChange(dev.mediafix.api.event.ScreenPlaybackChangedEvent.Change.MUTE,
                muted ? "已静音" : "已取消静音");
    }

    public int volume() {
        return this.entityData.get(DATA_VOLUME);
    }

    public void setVolume(int percent) {
        final int clamped = Mth.clamp(percent, 0, 100);
        if (clamped == volume()) return;
        this.entityData.set(DATA_VOLUME, clamped);
        postChange(dev.mediafix.api.event.ScreenPlaybackChangedEvent.Change.VOLUME,
                "音量 " + clamped + "%");
    }

    public float rangeMin() {
        return this.entityData.get(DATA_RANGE_MIN);
    }

    public float rangeMax() {
        return this.entityData.get(DATA_RANGE_MAX);
    }

    public void setRange(float min, float max) {
        float lo = Math.max(0.0f, min);
        float hi = Math.max(lo + 0.5f, max);
        if (Math.abs(lo - rangeMin()) < 1.0e-4f && Math.abs(hi - rangeMax()) < 1.0e-4f) return;
        this.entityData.set(DATA_RANGE_MIN, lo);
        this.entityData.set(DATA_RANGE_MAX, hi);
        postChange(dev.mediafix.api.event.ScreenPlaybackChangedEvent.Change.RANGE,
                String.format("音量距离范围 %.1f~%.1f", lo, hi));
    }

    public int qualityCap() {
        return this.entityData.get(DATA_QUALITY);
    }

    public void setQualityCap(int qn) {
        if (qn == qualityCap()) return;
        this.entityData.set(DATA_QUALITY, qn);
        postChange(dev.mediafix.api.event.ScreenPlaybackChangedEvent.Change.QUALITY,
                qn < 0 ? "清晰度上限：自动" : ("清晰度上限：qn" + qn));
    }

    // ---- 画面参数（alpha / 亮度 / 铺法 / 单双面） ----

    public float alpha() {
        return this.entityData.get(DATA_ALPHA);
    }

    /** 不透明度 0.05~1（1 = 完全不透明）。 */
    public void setAlpha(float value) {
        final float v = Mth.clamp(value, 0.05f, 1.0f);
        if (Math.abs(v - alpha()) < 1.0e-4f) return;
        this.entityData.set(DATA_ALPHA, v);
        postChange(dev.mediafix.api.event.ScreenPlaybackChangedEvent.Change.ALPHA,
                String.format("不透明度 %.2f", v));
    }

    public float brightness() {
        return this.entityData.get(DATA_BRIGHTNESS);
    }

    /**
     * 亮度倍率 0.05~1（调暗用）。
     *
     * <p><b>为什么没有"更亮"</b>：屏幕已经是满亮自发光，而且画面是**走 RenderType 画**的
     * （光影与着色器只认这条路径），顶点色是 8 位归一化值 —— 上限就是 1.0，写 2.0 也会被夹回 1.0。
     * 以前用 {@code RenderSystem.setShaderColor}（float 乘子）时能过曝，但那条路光影认不出来，
     * "能被光影正确绘制"比"能过曝"重要得多。想要更亮就让光影包调曝光/自发光。
     */
    public void setBrightness(float value) {
        final float v = Mth.clamp(value, 0.05f, 1.0f);
        if (Math.abs(v - brightness()) < 1.0e-4f) return;
        this.entityData.set(DATA_BRIGHTNESS, v);
        postChange(dev.mediafix.api.event.ScreenPlaybackChangedEvent.Change.BRIGHTNESS,
                String.format("亮度 %.2f×", v));
    }

    public dev.mediafix.api.ScreenFit fit() {
        return dev.mediafix.api.ScreenFit.byId(this.entityData.get(DATA_FIT));
    }

    /** 画面铺法（等比加黑边 / 裁切铺满 / 拉伸）。 */
    public void setFit(dev.mediafix.api.ScreenFit fit) {
        final dev.mediafix.api.ScreenFit v = fit == null
                ? dev.mediafix.api.ScreenFit.LETTERBOX : fit;
        if (v == fit()) return;
        this.entityData.set(DATA_FIT, v.id());
        postChange(dev.mediafix.api.event.ScreenPlaybackChangedEvent.Change.FIT,
                "画面铺法：" + v);
    }

    public boolean isDoubleSided() {
        return this.entityData.get(DATA_DOUBLE_SIDED);
    }

    /** 双面可见（背面镜像）；false = 只有正面可见。 */
    public void setDoubleSided(boolean value) {
        if (value == isDoubleSided()) return;
        this.entityData.set(DATA_DOUBLE_SIDED, value);
        postChange(dev.mediafix.api.event.ScreenPlaybackChangedEvent.Change.DOUBLE_SIDED,
                value ? "双面可见" : "单面（只看正面）");
    }

    public String audioModeOverride() {
        return this.entityData.get(DATA_AUDIO_MODE);
    }

    /**
     * 每块屏幕的音频输出形态覆盖；空串/null = 跟随全局配置。
     *
     * <p>取值：{@code auto}（能直通就直通）/ {@code shared}（系统混音器，默认）/
     * {@code exclusive}（独占 PCM）/ {@code passthrough}（强制 IEC 61937 直通）。
     * 对**已经在播**的会话不会立刻生效（音频形态是开声卡时定下的）：换源或重开会话后生效。
     */
    public void setAudioModeOverride(String mode) {
        final String v = mode == null ? "" : mode.trim().toLowerCase(java.util.Locale.ROOT);
        if (v.equals(audioModeOverride())) return;
        this.entityData.set(DATA_AUDIO_MODE, v);
        postChange(dev.mediafix.api.event.ScreenPlaybackChangedEvent.Change.AUDIO_MODE,
                v.isEmpty() ? "音频输出：跟随全局配置" : ("音频输出：" + v));
    }

    // ---- 位置与角度 ----

    /** 屏幕中心点。 */
    public Vec3 center() {
        return new Vec3(this.getX(), this.getY(), this.getZ());
    }

    /**
     * 直接放到指定位置（中心点）。
     *
     * <p>用 {@code moveTo} 是为了让客户端立刻得到位置更新（{@code setPos} 在同步上更"懒"）。
     */
    public void setCenter(Vec3 pos) {
        if (pos == null) return;
        this.moveTo(pos.x, pos.y, pos.z, this.getYRot(), this.getXRot());
        postChange(dev.mediafix.api.event.ScreenPlaybackChangedEvent.Change.POSITION,
                String.format("位置 %.2f, %.2f, %.2f", pos.x, pos.y, pos.z));
    }

    /** 相对位移。 */
    public void translate(double dx, double dy, double dz) {
        this.setCenter(this.center().add(dx, dy, dz));
    }

    /** 设置朝向：yaw 0 = 朝南（+Z），pitch = 俯仰。 */
    public void setRotation(float yaw, float pitch) {
        this.setYRot(yaw);
        this.setXRot(Mth.clamp(pitch, -90.0f, 90.0f));
        postChange(dev.mediafix.api.event.ScreenPlaybackChangedEvent.Change.ROTATION,
                String.format("朝向 yaw=%.1f° pitch=%.1f°", yaw, pitch));
    }

    /**
     * 让屏幕**画面正确的那一面**转向某个点（例如"让屏幕看着玩家/讲台"）。
     *
     * <p>约定：{@code yaw} 描述的是"放置屏幕时你**看**的方向"，画面正确的那一面朝**你**
     * （也就是 -yaw 侧；见 {@code ScreenStageRenderer} 的 {@code 180 - yaw}）。
     * 所以要让画面正对 target，yaw 取"target → 屏幕"的水平方向 —— 这里已经加上了那 180°，
     * 于是 {@code lookAt(玩家位置)} 得到的朝向与玩家自己放一块屏幕时完全一致。
     */
    public void lookAt(Vec3 target) {
        if (target == null) return;
        final Vec3 from = this.center();
        final double dx = target.x - from.x;
        final double dy = target.y - from.y;
        final double dz = target.z - from.z;
        final double flat = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Mth.atan2(-dx, dz) * (180.0 / Math.PI)) + 180.0f;
        yaw = Mth.wrapDegrees(yaw);
        final float pitch = (float) (-(Mth.atan2(dy, flat) * (180.0 / Math.PI)));
        this.setRotation(yaw, pitch);
    }

    public long positionMsAtAnchor() {
        return this.entityData.get(DATA_POS_AT_ANCHOR);
    }    /** @deprecated 锚点不再用游戏刻（TPS 一低就会让进度变慢）；保留只为旧存档兼容。 */
    @Deprecated
    public long anchorGameTime() {
        return this.entityData.get(DATA_ANCHOR_TICK);
    }

    /** 跳到指定位置（服务端权威：所有人都从这一刻重新对齐）。 */
    public void seekTo(long ms) {
        final long target = Math.max(0L, ms);
        setAnchor(target);
        postChange(dev.mediafix.api.event.ScreenPlaybackChangedEvent.Change.SEEK,
                "跳到 " + target + "ms");
    }

    /**
     * 发一条"播放参数变更"事件。
     *
     * <p>只在服务端、且**不是**在读存档时发 —— 否则每次读档都会刷一屏事件。
     */
    private void postChange(dev.mediafix.api.event.ScreenPlaybackChangedEvent.Change change,
                            String detail) {
        if (this.loading || this.level() == null || this.level().isClientSide()) return;
        if (!(this.level() instanceof ServerLevel serverLevel)) return;
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(
                new dev.mediafix.api.event.ScreenPlaybackChangedEvent(serverLevel,
                        dev.mediafix.screen.ScreenApi.handleFor(serverLevel, this),
                        dev.mediafix.screen.ScreenApi.infoOf(this), change, detail));
    }

    /** 读存档期间置位：这期间所有 setter 都不发事件。 */
    private transient boolean loading;

    /**
     * 批量设置期间静音（不发"播放参数变更"事件）。
     *
     * <p>建屏（{@code ScreenOptions} 一次设十来个值）与读档时用：
     * 那些 setter 会在实体**进入世界之前**触发，监听方拿到的句柄此刻还找不到实体
     * （`handle.info()` 会是 null），平白多出十几条对不上的事件。
     * 一句话总结：先静音把参数设好，再 {@code addFreshEntity} + 发 `ScreenCreatedEvent`（它带完整快照）。
     */
    public void setEventSilent(boolean silent) {
        this.loading = silent;
    }

    /**
     * 此刻"按服务端权威"应该播到的位置（毫秒）。
     *
     * <p>暂停时不推进；播放时按**真实时间**推进（锚点代次 + 本端挂钟）。
     *
     * <p><b>为什么不用游戏刻</b>：媒体时间是真实时间，而游戏刻随 TPS 走。服务端 TPS 一低
     * （比如 10 TPS）权威位置就以半速前进，而本地播放是实时的 —— 于是客户端每隔一会儿发现
     * "我跑太快了"，就 seek 回去，看起来就是画面/声音一直被往回拉。
     * 改成真实时间后，客户端只在**锚点变化时**（seek/暂停/继续/换源）对齐一次，平时各跑各的，
     * 与 TPS 完全无关。
     */
    public long projectedPositionMs() {
        final long base = positionMsAtAnchor();
        if (isPaused()) return base;
        long anchorMs = this.anchorWallClockMs;
        if (anchorMs <= 0L) {
            // 还没打过时间戳（刚构造出来）：从现在开始算，别返回一个巨大的值
            this.anchorWallClockMs = anchorMs = System.currentTimeMillis();
            return base;
        }
        return base + Math.max(0L, System.currentTimeMillis() - anchorMs);
    }

    /** 到实体中心的距离（用于音量距离衰减）。 */
    public double distanceTo(Vec3 listener) {
        return Math.sqrt(this.distanceToSqr(listener));
    }

    /**
     * 距离衰减后的音量百分比（0~100）。
     *
     * <p>与旧版水帧的语义一致：{@code rangeMin} 以内满音量，之后线性衰减，{@code rangeMax} 以外静音。
     * 主音量（MC 的 MASTER）由数字到增益时再乘一次，见 {@code MediaSession} 与 {@code MasterVolume}。
     */
    public int rangedVolume(double distance) {
        if (isMuted()) return 0;
        float lo = rangeMin();
        float hi = rangeMax();
        int vol = volume();
        if (distance <= lo) return vol;
        if (distance >= hi) return 0;
        float t = (float) ((distance - lo) / (hi - lo));
        return Math.round(vol * (1.0f - t));
    }

    /**
     * 服务端解析入口（仅供指令/API 在服务端预解析用；客户端播放走自己那条）。
     */
    public ResolvedStream resolveNow() {
        String src = sourceUri();
        if (src.isEmpty()) return null;
        try {
            return SourceResolvers.resolve(URI.create(src));
        } catch (Throwable t) {
            CineDuct.LOGGER.warn("[CineDuct] 屏幕 {} 解析失败: {}", this.getUUID(), src, t);
            return null;
        }
    }

    // =====================================================================
    // 生命周期
    // =====================================================================

    @Override
    public void tick() {
        super.tick();
        if (this.level() instanceof ServerLevel && isLoop()) {
            // 循环：播到片尾就从头接上（具体时长由客户端引擎知道，这里用一个宽松的兜底时长）
            long dur = this.durationHintMs;
            if (dur > 0 && projectedPositionMs() > dur) {
                seekTo(projectedPositionMs() % dur);
            }
        }
    }

    /** 客户端上报的时长（仅本端内存，用于循环判断的兜底；不参与同步）。 */
    private long durationHintMs = -1L;

    public void setDurationHint(long ms) {
        this.durationHintMs = ms;
    }

    public long durationHint() {
        return this.durationHintMs;
    }

    /**
     * 屏幕是纯显示物：**不参与任何碰撞/推挤/命中判定**。
     *
     * <p>下面这一组开关必须全关 —— 只把 {@code isPickable} 设成 false 是不够的：
     * 实体默认可被推、可被弹射物命中、可被攻击，玩家走过还会被它的包围盒顶开，
     * 于是"看起来有个隐形碰撞箱"。
     */
    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public void push(Entity entity) {
        // 不让任何东西推动屏幕（活塞、玩家、其它实体都不行）
    }

    @Override
    public void push(double x, double y, double z) {
        // 同上：不响应外力位移
    }

    @Override
    public boolean isPushedByFluid() {
        return false;
    }

    @Override
    public boolean canBeHitByProjectile() {
        return false;
    }

    @Override
    public boolean isAttackable() {
        return false;
    }

    @Override
    public boolean isIgnoringBlockTriggers() {
        return true;
    }

    /**
     * 包围盒贴合屏幕实际尺寸。
     *
     * <p>实体类型上的 2×1.5 只是注册期的占位值；屏幕尺寸可以任意调，
     * 视锥剔除与调试用的命中框都该跟着走（以前固定 2×1.5，放大后包围盒完全不对）。
     */
    @Override
    public EntityDimensions getDimensions(Pose pose) {
        return EntityDimensions.scalable(Math.max(0.1f, screenWidth()),
                Math.max(0.1f, screenHeight()));
    }

    /** 同步数据到达时：尺寸要重算包围盒；锚点要就地打时间戳（真实时间推进的基准）。 */
    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (DATA_WIDTH.equals(key) || DATA_HEIGHT.equals(key)) {
            this.refreshDimensions();
        }
        /*
         * ★ 锚点数据到达：从**此刻**开始按真实时间推进。
         * 关键点是"本端打时间戳"而不是用服务端游戏刻 —— 后者随 TPS 走，
         * 服务端一卡，客户端就会一直把播放往回拉（这就是那个 bug）。
         */
        if (DATA_POS_AT_ANCHOR.equals(key) || DATA_PAUSED.equals(key)
                || DATA_ANCHOR_GEN.equals(key)) {
            this.anchorWallClockMs = System.currentTimeMillis();
        }
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distanceSqr) {
        return distanceSqr < 192.0 * 192.0;
    }

    @Override
    public BlockPos blockPosition() {
        return BlockPos.containing(this.position());
    }

    // =====================================================================
    // 持久化
    // =====================================================================

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        // 读档期间不发任何"变更"事件（否则每次进世界都会刷一屏）
        this.loading = true;
        try {
            readSaveData(tag);
        } finally {
            this.loading = false;
        }
    }

    private void readSaveData(CompoundTag tag) {
        // 注意：这里直接写同步数据，不走 setSourceUri —— 否则每次读档都会发一次"源变更"事件
        this.entityData.set(DATA_SOURCE, tag.getString(TAG_SOURCE));
        setScreenSize(tag.contains(TAG_WIDTH) ? tag.getFloat(TAG_WIDTH) : 2.0f,
                tag.contains(TAG_HEIGHT) ? tag.getFloat(TAG_HEIGHT) : 1.125f);
        this.entityData.set(DATA_PAUSED, tag.getBoolean(TAG_PAUSED));
        this.entityData.set(DATA_LOOP, tag.getBoolean(TAG_LOOP));
        this.entityData.set(DATA_MUTED, tag.getBoolean(TAG_MUTED));
        setVolume(tag.contains(TAG_VOLUME) ? tag.getInt(TAG_VOLUME) : 100);
        setRange(tag.contains(TAG_RANGE_MIN) ? tag.getFloat(TAG_RANGE_MIN) : 1.0f,
                tag.contains(TAG_RANGE_MAX) ? tag.getFloat(TAG_RANGE_MAX) : 24.0f);
        this.entityData.set(DATA_POS_AT_ANCHOR, tag.getLong(TAG_POS_AT_ANCHOR));
        this.entityData.set(DATA_ANCHOR_TICK, tag.getLong(TAG_ANCHOR_TICK));
        setQualityCap(tag.contains(TAG_QUALITY) ? tag.getInt(TAG_QUALITY) : -1);
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putString(TAG_SOURCE, sourceUri());
        tag.putFloat(TAG_WIDTH, screenWidth());
        tag.putFloat(TAG_HEIGHT, screenHeight());
        tag.putBoolean(TAG_PAUSED, isPaused());
        tag.putBoolean(TAG_LOOP, isLoop());
        tag.putBoolean(TAG_MUTED, isMuted());
        tag.putInt(TAG_VOLUME, volume());
        tag.putFloat(TAG_RANGE_MIN, rangeMin());
        tag.putFloat(TAG_RANGE_MAX, rangeMax());
        tag.putLong(TAG_POS_AT_ANCHOR, positionMsAtAnchor());
        tag.putLong(TAG_ANCHOR_TICK, anchorGameTime());
        tag.putInt(TAG_QUALITY, qualityCap());
    }

    /**
     * 关掉这块屏幕时：客户端回收会话与纹理，服务端发"屏幕被删除"事件。
     */
    @Override
    public void remove(RemovalReason reason) {
        boolean real = reason.shouldDestroy() && this.level() instanceof ServerLevel;
        ServerLevel serverLevel = real ? (ServerLevel) this.level() : null;
        // 快照必须在 super.remove 之前取（之后就取不到同步数据了）
        dev.mediafix.api.ScreenInfo snapshot = real ? dev.mediafix.screen.ScreenApi.infoOf(this) : null;
        super.remove(reason);
        // 必须判 isClientSide：ScreenClientState 是纯客户端类，服务端加载它会 NoClassDefFound
        if (this.level().isClientSide()) {
            dev.mediafix.client.ScreenClientState.forget(this.getId());
        }
        if (serverLevel != null) {
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(
                    new dev.mediafix.api.event.ScreenRemovedEvent(serverLevel,
                            dev.mediafix.screen.ScreenApi.handleFor(serverLevel, this), snapshot));
        }
    }

    /** 供指令/API 查询：这块屏幕当前用的会话（仅客户端有效）。 */
    public MediaSession clientSession() {
        if (!this.level().isClientSide()) return null;
        return Sessions.of(dev.mediafix.client.ScreenClientState.sessionIdOf(this.getId()));
    }
}

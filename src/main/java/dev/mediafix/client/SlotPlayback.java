package dev.mediafix.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.mediafix.CineDuct;
import dev.mediafix.api.player.CinePlayer;
import dev.mediafix.api.player.CinePlayerApi;
import dev.mediafix.api.player.PlayerOptions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个"服务端下发的 slot"在客户端的全部状态：几何 + 进度锚点 + 播放器 + 纹理。
 *
 * <p>与 {@link ScreenClientState} 解决同一类问题（把一条播放画到世界里），但驱动源不同：
 * 那个由**服务端实体**驱动（{@code cineduct:screen}），这个由 **Paper 插件下发的报文**驱动。
 * 共同的设计取舍照抄过来：
 * <ul>
 *   <li>只在"锚点变化"时对齐，不每帧纠偏（否则画面会一直被往回拉）；</li>
 *   <li>纹理壳（{@link ScreenVideoTexture}）转发真实 GL id，两种传输方式对渲染侧透明。</li>
 * </ul>
 *
 * <p><b>距离门控</b>：进入 {@link #ENTER} 格内才开播，超出 {@link #EXIT} 格就关掉并释放资源。
 * 两个阈值必须不同 —— 每次重开都要重新解析直链（B 站要发请求），同一个阈值会在边界上变成
 * "走走停停反复重开"，比不播更糟。
 */
public final class SlotPlayback {

    /** 进范围的门限（格）。 */
    public static final double ENTER = 48.0;
    /** 出范围的门限（格）。留滞回，见类注释。 */
    public static final double EXIT = 64.0;

    private static final Map<String, SlotPlayback> SLOTS = new LinkedHashMap<>();

    private final String slot;

    private SlotScreen screen;
    private CinePlayer player;

    // ---- 进度锚点（服务端权威）----
    private String anchorUrl;
    private long anchorPositionMs;
    private long anchorWallMs;
    private boolean anchorPaused;
    private boolean anchorLive;
    private boolean hasAnchor;

    // ---- 纹理 ----
    private DynamicTexture upload;
    private ScreenVideoTexture shell;
    private ResourceLocation location;
    private int uploadTextureId = -1;
    private boolean allocated;
    /** 已经上报过时长的 url（同一视频只报一次）。 */
    private String reportedUrl;

    private SlotPlayback(String slot) {
        this.slot = slot;
    }

    // =====================================================================
    // 入口（由 dev.mediafix.net 的报文分派调用；都在客户端主线程）
    // =====================================================================

    /** 处理 {@code place}：下发/更新几何。 */
    public static synchronized void applyPlace(SlotScreen screen) {
        final SlotPlayback playback = SLOTS.computeIfAbsent(screen.slot(), SlotPlayback::new);
        playback.screen = screen;
        CineDuct.LOGGER.info("[CineDuct] 虚拟屏 slot={} 中心={} 半径≈{}格 alpha={}",
                screen.slot(), fmt(screen.center()),
                Math.round(screen.radius() * 100.0) / 100.0, screen.alpha());
    }

    /** 处理 {@code anchor}：进度权威变化（换源 / seek / 暂停 / 播放列表推进都走这里）。 */
    public static synchronized void applyAnchor(String slot, String url, long positionMs, long serverTimeMs,
                                                boolean paused, boolean live) {
        final SlotPlayback playback = SLOTS.computeIfAbsent(slot, SlotPlayback::new);
        final boolean urlChanged = url != null && !url.equals(playback.anchorUrl);
        playback.anchorUrl = url;
        playback.anchorPositionMs = Math.max(0L, positionMs);
        /*
         * 服务端时刻换成本机时刻：两边时钟不同源，只能按"报文到达"近似。
         * serverTimeMs 目前不做补偿 —— 差几百毫秒对影院没意义，而补偿要额外估网络往返。
         */
        playback.anchorWallMs = System.currentTimeMillis();
        playback.anchorPaused = paused;
        playback.anchorLive = live;
        playback.hasAnchor = url != null && !url.isBlank();

        if (!playback.hasAnchor) {
            playback.closePlayer("服务端撤下播放");
            return;
        }
        if (playback.player == null) return;   // 还没进范围：等 tick 里开播

        if (urlChanged) {
            playback.closePlayer("换源");
            return;                            // 下一次 tick 按新锚点重开
        }
        playback.player.pause(paused);
        if (!paused && !live) {
            final long target = playback.targetPositionMs();
            if (Math.abs(playback.player.positionMs() - target) > 3000L) playback.player.seek(target);
        }
        CineDuct.LOGGER.info("[CineDuct] slot={} 锚点更新：{}ms{}（当前应播 {}ms）",
                slot, positionMs, paused ? " 暂停" : "", playback.targetPositionMs());
    }

    /** 退服/换服/停止：全部清掉（协议 §7）。 */
    public static synchronized void closeAll(String why) {
        if (SLOTS.isEmpty()) return;
        final int count = SLOTS.size();
        for (SlotPlayback playback : SLOTS.values()) playback.closePlayer(why);
        SLOTS.clear();
        CineDuct.LOGGER.info("[CineDuct] 已清理 {} 个服务端虚拟屏（{}）", count, why);
    }

    // =====================================================================
    // 每 tick：距离门控
    // =====================================================================

    /** 客户端每 tick 调一次（主线程）。 */
    public static synchronized void tick() {
        if (SLOTS.isEmpty()) return;
        final Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        final Vec3 eye = mc.player.position();

        for (SlotPlayback playback : SLOTS.values()) {
            final SlotScreen screen = playback.screen;
            if (screen == null) continue;
            if (!screen.world().equals(mc.level.dimension())) {
                // 不在这个维度：把播放（与声音）关掉，几何留着
                if (playback.player != null) playback.closePlayer("离开了这个维度");
                continue;
            }
            playback.reportDurationOnce();
            final double dist = screen.center().distanceTo(eye);
            if (playback.player == null) {
                if (dist <= ENTER && playback.hasAnchor) playback.openPlayer();
            } else if (dist >= EXIT) {
                playback.closePlayer("超出 " + (int) EXIT + " 格");
            }
        }
    }

    // =====================================================================
    // 播放器
    // =====================================================================

    /**
     * 起播后上报一次时长（上行唯一用途）。
     *
     * <p>为什么由客户端报：时长只有真放起来才知道，而且它天然带着"非大会员只能试看 6 分钟"
     * 这类截断 —— 服务端查 API 只能拿到总时长，算不对。
     */
    private void reportDurationOnce() {
        final CinePlayer p = this.player;
        if (p == null || this.anchorUrl == null || this.anchorUrl.equals(this.reportedUrl)) return;
        final long durationMs = p.durationMs();
        final boolean live = p.isLive();
        if (!live && durationMs <= 0) return;          // 还没拿到时长：下一 tick 再报
        dev.mediafix.net.CineControlHandler.reportDuration(this.slot, this.anchorUrl, durationMs, live);
        this.reportedUrl = this.anchorUrl;
    }

    private void openPlayer() {
        if (!CinePlayerApi.available() || this.anchorUrl == null) return;
        final long target = targetPositionMs();
        try {
            this.player = CinePlayerApi.get().open(this.anchorUrl,
                    PlayerOptions.builder()
                            .startAt(target)
                            .paused(this.anchorPaused)
                            .build());
            CineDuct.LOGGER.info("[CineDuct] slot={} 进入范围：开始播放（从 {}ms 起，player #{}）",
                    this.slot, target, this.player.id());
        } catch (Throwable t) {
            CineDuct.LOGGER.warn("[CineDuct] slot={} 开播失败", this.slot, t);
            this.player = null;
        }
    }

    private void closePlayer(String why) {
        final CinePlayer p = this.player;
        this.player = null;
        this.allocated = false;
        if (p != null) {
            p.close();
            CineDuct.LOGGER.info("[CineDuct] slot={} 已停止播放（{}）", this.slot, why);
        }
    }

    /** 服务端锚点换算到"现在应该播到哪"。 */
    private long targetPositionMs() {
        if (this.anchorPaused || this.anchorLive) return this.anchorPositionMs;
        return this.anchorPositionMs + Math.max(0L, System.currentTimeMillis() - this.anchorWallMs);
    }

    // =====================================================================
    // 控制（pause / seek / volume / mute / close）
    // =====================================================================

    /**
     * 把一条控制报文落到虚拟屏的播放器上。
     *
     * <p>为什么需要它：虚拟屏的播放器是**距离门控**建的，不在 {@code CineControlHandler} 的 slot 表里，
     * 所以 `pause main` 这类指令如果只查那张表就是空操作 —— 表现为"指令回了已发送，画面没停"。
     *
     * @return true = 这条 op 已经由虚拟屏处理（调用方不要再走普通 slot 那条路）
     */
    public static synchronized boolean handleControl(String op, String slot, Boolean paused,
                                                     Long positionMs, Integer volume, Boolean muted) {
        final SlotPlayback playback = SLOTS.get(slot);
        if (playback == null) return false;          // 不是虚拟屏，交给普通 slot
        final CinePlayer player = playback.player;
        switch (op) {
            case "pause" -> {
                playback.anchorPaused = paused != null && paused;
                if (player != null && paused != null) player.pause(paused);
            }
            case "seek" -> {
                if (positionMs == null) return true;
                playback.anchorPositionMs = Math.max(0L, positionMs);
                playback.anchorWallMs = System.currentTimeMillis();
                if (player != null && !player.isLive()) player.seek(positionMs);
            }
            case "volume" -> {
                if (player != null && volume != null) player.setVolumePercent(volume);
            }
            case "mute" -> {
                if (player != null && muted != null) player.setMuted(muted);
            }
            case "close" -> {
                playback.closePlayer("服务端关闭");
                playback.screen = null;      // 几何也清掉：删屏立刻生效，不用重进服
            }
            default -> {
                return false;                        // open/rebuild/... 不归这里管
            }
        }
        return true;
    }

    // =====================================================================
    // 渲染侧
    // =====================================================================

    SlotScreen screen() {
        return this.screen;
    }

    ResourceLocation textureLocation() {
        return this.location;
    }

    float alpha() {
        return this.screen == null ? 1.0f : this.screen.alpha();
    }

    float brightness() {
        return this.screen == null ? 1.0f : this.screen.brightness();
    }

    /** 取这一帧可采样的纹理（渲染线程）。返回 &lt;=0 表示这一帧没画面。 */
    int acquire() {
        final CinePlayer p = this.player;
        if (p == null) return -1;
        ensureTexture();
        if (this.uploadTextureId <= 0) return -1;
        final int tex = p.acquireDrawTexture(this.uploadTextureId, !this.allocated, 0.0f);
        if (tex > 0) {
            if (this.shell != null) this.shell.setGlId(tex);
            this.allocated = true;
        }
        return tex;
    }

    /** 与 {@link #acquire()} 成对；零拷贝模式下这是"解锁互操作对象"。 */
    void release() {
        final CinePlayer p = this.player;
        if (p != null) p.releaseDrawTexture();
    }

    /** 懒建纹理：一张给进程内引擎上传用，一张是登记进纹理管理器的"id 转发壳"。 */
    private void ensureTexture() {
        if (this.uploadTextureId > 0) return;
        final Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        if (this.location == null) {
            this.location = ResourceLocation.fromNamespaceAndPath(CineDuct.MODID, "slot/" + this.slot);
            this.shell = new ScreenVideoTexture();
            mc.getTextureManager().register(this.location, this.shell);
        }
        if (this.upload == null) {
            this.upload = new DynamicTexture(16, 16, false);
            this.uploadTextureId = this.upload.getId();
        }
    }

    // ---- 渲染器用的遍历 ----

    /** 当前维度里所有可画的 slot（渲染线程）。 */
    static List<SlotPlayback> visible() {
        if (SLOTS.isEmpty()) return List.of();
        final Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return List.of();
        final List<SlotPlayback> out = new ArrayList<>(SLOTS.size());
        for (SlotPlayback playback : SLOTS.values()) {
            if (playback.screen == null || playback.player == null) continue;
            if (!playback.screen.world().equals(mc.level.dimension())) continue;
            out.add(playback);
        }
        return out;
    }

    /** 按四个角点画一个四边形（QUADS 模式：环序写四个顶点）。 */
    /** 诊断：每块屏第一次真的出画时打一条（排查"有几何没画面"用）。 */
    private static final java.util.Set<String> DRAWN_ONCE = java.util.concurrent.ConcurrentHashMap.newKeySet();

    static void draw(SlotPlayback playback, Vec3 camera, PoseStack pose,
                     MultiBufferSource.BufferSource source) {
        final SlotScreen screen = playback.screen;
        if (screen == null) return;
        final int texture = playback.acquire();
        if (texture <= 0) return;
        try {
            final boolean opaque = playback.alpha() >= 0.999f;
            final var renderType = ScreenQuad.renderType(playback.textureLocation(), opaque);
            final VertexConsumer consumer = source.getBuffer(renderType);
            pose.pushPose();
            try {
                pose.translate(-camera.x, -camera.y, -camera.z);
                final PoseStack.Pose last = pose.last();
                // 信箱化：视频比例 ≠ 屏幕比例时缩视频、留真黑边（不拉伸）
                final Vec3[] quad = letterboxed(screen.corners(), playback.videoAspect());
                final float brightness = playback.brightness();
                final float alpha = playback.alpha();
                // 环序：左上 → 右上 → 右下 → 左下
                ScreenQuad.emit3(consumer, last, quad[0], 0.0f, 1.0f, brightness, alpha);
                ScreenQuad.emit3(consumer, last, quad[1], 1.0f, 1.0f, brightness, alpha);
                ScreenQuad.emit3(consumer, last, quad[2], 1.0f, 0.0f, brightness, alpha);
                ScreenQuad.emit3(consumer, last, quad[3], 0.0f, 0.0f, brightness, alpha);
            } finally {
                pose.popPose();
            }
            source.endBatch(renderType);
            if (DRAWN_ONCE.add(playback.slot)) {
                final Vec3[] cs = screen.corners();
                CineDuct.LOGGER.info("[CineDuct] 虚拟屏 {} 首次出画：纹理={} alpha={} 视频比例={} 四角={} {} {} {}",
                        playback.slot, texture, playback.alpha(),
                        String.format(java.util.Locale.ROOT, "%.3f", playback.videoAspect()),
                        fmt(cs[0]), fmt(cs[1]), fmt(cs[2]), fmt(cs[3]));
            }
        } finally {
            playback.release();
        }
    }

    /** 视频宽高比（未知时 0）。 */
    private float videoAspect() {
        final CinePlayer p = this.player;
        if (p == null) return 0.0f;
        final int w = p.videoWidth();
        final int h = p.videoHeight();
        if (w > 0 && h > 0) return (float) w / h;
        return p.drawAspect() > 0.01f ? p.drawAspect() : 0.0f;
    }

    /**
     * 信箱化：把视频按它的宽高比放进给定的屏幕矩形，多出来的地方**不画**（露出来的就是世界里/黑边）。
     *
     * <p>为什么必须在这里做：这四个角点是屏幕的物理面，视频比例和它不一致时
     * 拉满就是"人变胖"。与屏幕实体那条路（{@code ScreenQuad.corners} 的 LETTERBOX）同一语义，
     * 只是那边是本地矩形、这边是世界坐标四角，所以另外算一遍。
     */
    private static Vec3[] letterboxed(Vec3[] corners, float videoAspect) {
        if (videoAspect <= 0.01f) return corners;          // 比例未知：先铺满，等有画面再说
        final Vec3 leftTop = corners[0];
        final Vec3 rightTop = corners[1];
        final Vec3 leftBottom = corners[3];
        final double screenWidth = leftTop.distanceTo(rightTop);
        final double screenHeight = leftTop.distanceTo(leftBottom);
        if (screenWidth <= 1e-4 || screenHeight <= 1e-4) return corners;
        final double screenAspect = screenWidth / screenHeight;
        if (Math.abs(screenAspect - videoAspect) < 0.01) return corners;

        final Vec3 center = leftTop.add(rightTop).add(corners[2]).add(leftBottom).scale(0.25);
        final Vec3 halfWidth = rightTop.subtract(leftTop).scale(0.5);
        final Vec3 halfHeight = leftBottom.subtract(leftTop).scale(0.5);
        if (videoAspect > screenAspect) {
            // 视频更宽 → 压高度（上下留空）
            final Vec3 v = halfHeight.scale(screenAspect / videoAspect);
            return new Vec3[]{
                    center.subtract(halfWidth).add(v),
                    center.add(halfWidth).add(v),
                    center.add(halfWidth).subtract(v),
                    center.subtract(halfWidth).subtract(v),
            };
        }
        // 视频更高 → 压宽度（左右留空）
        final Vec3 h = halfWidth.scale(videoAspect / screenAspect);
        return new Vec3[]{
                center.subtract(h).add(halfHeight),
                center.add(h).add(halfHeight),
                center.add(h).subtract(halfHeight),
                center.subtract(h).subtract(halfHeight),
        };
    }

    private static String fmt(Vec3 v) {
        return String.format(java.util.Locale.ROOT, "%.1f,%.1f,%.1f", v.x, v.y, v.z);
    }
}

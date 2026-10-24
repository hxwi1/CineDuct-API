package dev.mediafix.client;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import dev.mediafix.CineDuct;
import dev.mediafix.player.MediaSession;
import dev.mediafix.screen.ScreenEntity;

/**
 * 在世界里画屏幕画面 —— **在"方块实体之后"这个阶段自己提交**，而不是走 {@code EntityRenderer}。
 *
 * <p><b>为什么要换挂点（2026-10-18 现场）</b>：原来用 {@code EntityRenderer<ScreenEntity>} +
 * {@code RenderType.entitySolid}，在装了 Sodium 的实例里**画面全黑**（几何在、纹理是黑的）。
 * 对照同类实现（waterframes 的 {@code DisplayRenderer}，闭源，只看字节码可得的事实）：
 * 它在**方块实体通道**画，并且 RenderType 用的是 {@code rendertype_translucent} 那一族着色器。
 * 这两点与我们原本的"实体通道 + 实体着色器"不同，而它在该环境下正常 —— 于是这里改为
 * 复刻这两个条件（自己写的实现，没有照搬它的代码）：
 * <ul>
 *   <li>{@link RenderLevelStageEvent.Stage#AFTER_BLOCK_ENTITIES}：方块实体之后的阶段；</li>
 *   <li>自建 RenderType（{@link ScreenQuad#renderType}），着色器为 {@code rendertype_translucent}；</li>
 *   <li>顶点用 {@code POSITION_COLOR_TEX_LIGHTMAP}，写完**立刻 {@code endBatch}** ——
 *       这样"设状态 → 采样"发生在同一段 GL 流程里，不会像走 {@code MultiBufferSource}
 *       那样等到通道末尾才提交（Sodium 会在中间操作 GL 状态）。</li>
 * </ul>
 *
 * <p>为什么自己不注册 {@code EntityRenderer} 了：两条路都画会重影；而实体那条路正是出问题的那条。
 * 屏幕仍然是一个实体（同步/交互/生命周期不变），只是**不再由实体渲染器绘制**。
 *
 * <p><b>注册方式</b>：不用 {@code @EventBusSubscriber} —— 那一版**实测没生效**（见
 * {@code CineDuctClientSetup} 里的说明），改为 {@code CineDuctClientSetup} 显式
 * {@code NeoForge.EVENT_BUS.addListener(ScreenStageRenderer::onRenderLevelStage)}。
 */
public final class ScreenStageRenderer {

    /** 复用的顶点缓冲（每种 RenderType 1536 字节是 vanilla 的惯例取法）。 */
    private static final ByteBufferBuilder BUFFER = new ByteBufferBuilder(1536);

    /*
     * 诊断（临时，排查"看不到屏幕"用）：
     *   ① 回调只挂上一次，打一条"已挂上"——没这条就说明事件根本没注册成功；
     *   ② 每 5 秒汇总一行：扫到多少实体、其中多少屏幕、画出几块、几块没画面；
     *   ③ 一块屏第一次被画出时打一条。
     * 这三条能一次分清"事件没跑 / 没扫到实体 / 有实体但没画面 / 有画面但几何画丢了"。
     */
    private static boolean diagHookedLogged;
    private static long diagLastAt;
    private static int diagScanned;
    private static int diagScreens;
    private static int diagDrawn;
    private static int diagNoFrame;
    private static final java.util.Set<Integer> diagDrawnIds = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 平方（剔除用，少敲几个字符也比 Math.pow 清楚）。 */
    private static double square(double v) {
        return v * v;
    }

    private ScreenStageRenderer() {
    }

    /** 由 {@code CineDuctClientSetup} 显式注册到 {@code NeoForge.EVENT_BUS}（不用注解扫描）。 */
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) return;
        if (!diagHookedLogged) {
            diagHookedLogged = true;
            CineDuct.LOGGER.info("[CineDuct] 阶段渲染已挂上（AFTER_BLOCK_ENTITIES）—— 屏幕画面由这里提交");
        }
        // 光影的阴影 pass 里不画：自发光的显示面按实体重画会投出"画面形状"的阴影
        if (IrisCompat.inShadowPass()) return;

        final Minecraft mc = Minecraft.getInstance();
        final ClientLevel level = mc.level;
        if (level == null) return;
        final Vec3 camera = event.getCamera().getPosition();
        final PoseStack pose = event.getPoseStack();
        if (pose == null) return;

        /*
         * ★ 必须自己提交：{@code RenderLevelStageEvent} 不提供 MultiBufferSource（NeoForge 1.21.1
         * 只给 PoseStack/Camera/Frustum 这些）。所以每帧建一个 immediate 源、画完立刻 endBatch。
         *
         * 为什么"立刻"很关键：immediate 源的 endBatch 会走 RenderType 的 setupRenderState →
         * 绑定纹理 → 画 → clearRenderState，全过程紧挨着，中间不会被别的绘制插入。
         */
        final MultiBufferSource.BufferSource source = MultiBufferSource.immediate(BUFFER);
        try {
            /*
             * ★ 先把 MC 的纹理绑定缓存"打脏"。
             *
             * <p>{@code GlStateManager._bindTexture(id)} 在 id 与缓存相同时**直接跳过** {@code glBindTexture}
             * （1.21.1 源码就是这么写的）。而 Sodium 等模组会绕过这个缓存直接操作 GL —— 于是缓存可能与
             * 实际绑定不符，等到我们的 RenderType 去"绑定"时被跳过，采样到的就是别人的纹理（黑屏）。
             * 这里绑一次 0，保证紧接着的那次绑定是真的 glBindTexture。
             */
            com.mojang.blaze3d.platform.GlStateManager._bindTexture(0);
            diagScanned = 0;
            diagScreens = 0;
            diagDrawn = 0;
            diagNoFrame = 0;
            for (Entity entity : level.entitiesForRendering()) {
                diagScanned++;
                if (!(entity instanceof ScreenEntity screen) || screen.isRemoved()) continue;
                diagScreens++;
                drawOne(screen, camera, pose, source, event);
            }
            /*
             * 服务端（Paper 插件）下发的**虚拟屏**：没有实体，只有四个角点。
             * 走同一个阶段、同一套 RenderType —— 所以光影/Sodium 那两条坑都不用重新踩。
             */
            final java.util.List<dev.mediafix.client.SlotPlayback> slots = dev.mediafix.client.SlotPlayback.visible();
            if (!slots.isEmpty() && System.currentTimeMillis() - diagLastAt >= 5000L) {
                CineDuct.LOGGER.info("[CineDuct] 阶段渲染: 服务端虚拟屏 {} 块待画（玩家/纹理缺失的不算）", slots.size());
            }
            for (dev.mediafix.client.SlotPlayback slot : slots) {
                final var screen = slot.screen();
                if (screen == null) continue;
                // 粗剔除：以中心为球心、半径 = 到最远角点（四边形用球剔除够用）
                if (camera.distanceToSqr(screen.center()) > square(192.0 + screen.radius())) continue;
                dev.mediafix.client.SlotPlayback.draw(slot, camera, pose, source);
            }
            final long now = System.currentTimeMillis();
            if (now - diagLastAt >= 5000L) {
                diagLastAt = now;
                CineDuct.LOGGER.info("[CineDuct] 阶段渲染: 扫描 {} 个实体，其中屏幕 {} 个 → 画出 {} 块，"
                        + "无画面 {} 块（累计画出过 {} 块）",
                        diagScanned, diagScreens, diagDrawn, diagNoFrame, diagDrawnIds.size());
            }
        } finally {
            source.endBatch();
        }
    }

    private static void drawOne(ScreenEntity entity, Vec3 camera, PoseStack pose,
                                MultiBufferSource.BufferSource source, RenderLevelStageEvent event) {        // 距离剔除沿用实体自己的判据（192 格），再叠一层视锥判定
        final Vec3 eye = camera;
        if (!entity.shouldRenderAtSqrDistance(entity.position().distanceToSqr(eye))) return;
        if (!event.getFrustum().isVisible(entity.getBoundingBoxForCulling())) return;

        final ScreenClientState state = ScreenClientState.get(entity);
        state.update(entity);

        float alpha = 1.0f;
        try {
            final int texture = state.present(entity);
            if (texture <= 0) {
                diagNoFrame++;
                state.warnOnce(entity);   // 还没有画面：限流提示，不画
                return;
            }
            final ResourceLocation videoLocation = state.textureLocation();
            if (videoLocation == null) return;

            // 音量：实体音量 × 距离衰减 × 主音量，每帧推给播放侧（内部按批应用）
            final MediaSession session = state.session();
            if (session != null) {
                final Vec3 listener = Minecraft.getInstance().player == null
                        ? Vec3.ZERO : Minecraft.getInstance().player.position();
                state.applyVolume(entity, Math.sqrt(entity.distanceToSqr(listener)), session);
            }

            alpha = entity.alpha();
            final float brightness = entity.brightness();
            final float width = entity.screenWidth();
            final float height = entity.screenHeight();
            final float entityAspect = height <= 0.001f ? 16f / 9f : width / height;
            final float texAspect = state.drawAspect() > 0.01f ? state.drawAspect() : entityAspect;
            final boolean opaque = alpha >= 0.999f;
            final RenderType renderType = ScreenQuad.renderType(videoLocation, opaque);
            final float[][] corners = ScreenQuad.corners(width, height, entityAspect, texAspect,
                    entity.fit());

            pose.pushPose();
            try {
                // 平移成"相机相对坐标"（这个阶段的 PoseStack 已经带上了相机朝向）
                pose.translate(entity.getX() - eye.x, entity.getY() - eye.y, entity.getZ() - eye.z);
                /*
                 * ★ 朝向必须是 180 - yaw（不是 -yaw）：屏幕要正对观察者。
                 * 用 -yaw 会让法线指向实体自己的前方，玩家看到的是背面 —— 画面左右镜像（实测踩过）。
                 */
                pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(180.0f - entity.getYRot()));
                pose.mulPose(com.mojang.math.Axis.XP.rotationDegrees(entity.getXRot()));
                final PoseStack.Pose last = pose.last();

                final VertexConsumer consumer = source.getBuffer(renderType);
                /*
                 * 永远先画**反序**那一份（正面，法线 +Z）；双面时再补一份正序的。
                 * 本 RenderType 开了 NO_CULL（不剔除背面），所以顺序不再影响可见性，
                 * 但保持"正面优先"能让半透明混合的顺序与画面朝向一致。
                 */
                for (int i = corners.length - 1; i >= 0; i--) {
                    ScreenQuad.emit(consumer, last, corners[i], brightness, alpha);
                }
                if (entity.isDoubleSided()) {
                    for (float[] corner : corners) {
                        ScreenQuad.emit(consumer, last, corner, brightness, alpha);
                    }
                }
            } finally {
                pose.popPose();
            }
            // 立刻提交这一块屏（见类注释：设状态与采样要连着）
            source.endBatch(renderType);
            diagDrawn++;
            if (diagDrawnIds.add(entity.getId())) {
                CineDuct.LOGGER.info("[CineDuct] 屏幕 #{} 已由阶段渲染画出（位置 {} {} {}，尺寸 {}x{}，"
                                + "纹理 {}，opaque={}）",
                        entity.getId(), String.format(java.util.Locale.ROOT, "%.1f", entity.getX()),
                        String.format(java.util.Locale.ROOT, "%.1f", entity.getY()),
                        String.format(java.util.Locale.ROOT, "%.1f", entity.getZ()),
                        String.format(java.util.Locale.ROOT, "%.2f", width),
                        String.format(java.util.Locale.ROOT, "%.2f", height), texture, opaque);
            }
        } finally {
            // 子进程模式下这是解锁互操作对象 —— 不解锁会影响下一帧
            state.release();
        }
    }
}

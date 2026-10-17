package dev.mediafix.client;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

import dev.mediafix.screen.ScreenEntity;

/**
 * 屏幕实体的**空渲染器** —— 它一个顶点都不画，只用来满足 vanilla 的断言。
 *
 * <p><b>为什么必须有它（2026-10-18 崩溃现场）</b>：画面改由 {@link ScreenStageRenderer} 在
 * "方块实体之后"提交之后，我一度把这个渲染器的注册整个删掉，结果 `/cineduct screen create`
 * 一放屏幕就崩：
 * <pre>
 * NullPointerException: Cannot invoke "EntityRenderer.shouldRender(...)" because "entityrenderer" is null
 *   at EntityRenderDispatcher.shouldRender(EntityRenderDispatcher.java:136)
 *   at LevelRenderer.renderLevel(LevelRenderer.java:1003)
 * </pre>
 * vanilla 的 {@code EntityRenderDispatcher.shouldRender} 假定**每个实体类型都注册过渲染器**，
 * 拿不到就无条件解引用 —— 所以"没有渲染器"不等于"不画"，而是**崩游戏**。
 *
 * <p>现在这个类只做一件事：让 dispatcher 拿到一个非空对象。真正的绘制在阶段渲染里，
 * 这里 {@link #render} 是空的，所以不会出现两条路都画（重影）的问题。
 */
public final class ScreenNoopRenderer extends EntityRenderer<ScreenEntity> {

    /** 永远用不到（本渲染器不采样任何纹理），只为满足接口。 */
    private static final ResourceLocation UNUSED =
            ResourceLocation.withDefaultNamespace("textures/misc/white.png");

    public ScreenNoopRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(ScreenEntity entity, float entityYaw, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int packedLight) {
        // 故意留空：画面由 ScreenStageRenderer 在 AFTER_BLOCK_ENTITIES 提交（见类注释）
    }

    @Override
    public ResourceLocation getTextureLocation(ScreenEntity entity) {
        return UNUSED;
    }
}

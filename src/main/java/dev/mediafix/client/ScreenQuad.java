package dev.mediafix.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 屏幕画面的"自建 RenderType + 顶点写入"。
 *
 * <p><b>为什么不直接用 vanilla 的 {@code RenderType.entitySolid}（原来的做法）</b>：
 * 装了 Sodium 之后屏幕会**黑屏**——几何在、纹理是黑的。对照同类实现（waterframes 的
 * {@code DisplayRenderer}，闭源，只能看字节码）后能确定的差异只有两点：它用**方块实体通道**
 * 绘制，且 RenderType 的着色器是 {@code rendertype_translucent} 那一族（不是实体族）。
 * 所以这里照这两点**重新实现**（没有照搬代码，只是采用同样的技术路线）：
 * <ul>
 *   <li>着色器 {@code RENDERTYPE_TRANSLUCENT_SHADER}（{@code rendertype_translucent}）；
 *   <li>顶点格式 {@code POSITION_COLOR_TEX_LIGHTMAP}（位置/颜色/UV/光照贴图）；
 *   <li>绘制时机放到方块实体之后（见 {@link ScreenStageRenderer}）。
 * </ul>
 *
 * <p>为什么顶点里**必须**有光照贴图、且恒为满亮：光影包的程序普遍 {@code color *= lightmap}，
 * 不写这一项会拿到 0 → 屏幕被算成"零光照"发黑。这是自发光屏幕的关键（老代码的注释同样记着这条）。
 */
final class ScreenQuad {

    /** 顶点格式：位置 + 颜色 + UV0 + 光照贴图（uv2）。与 translucent 着色器完全对应。 */
    static final VertexFormat FORMAT = DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP;

    /** 每种"不透明/半透明 + 纹理"的组合缓存一个 RenderType（RenderType 本身不可变，可以复用）。 */
    private static final Map<String, RenderType> CACHE = new ConcurrentHashMap<>();

    private ScreenQuad() {
    }

    /**
     * 取一个指向某张屏幕纹理的 RenderType。
     *
     * @param texture 纹理位置（{@code ScreenVideoTexture} 登记在 TextureManager 的那个）
     * @param opaque  true = 不透明（禁混合、写深度）；false = 半透明（混合、不写深度）
     */
    static RenderType renderType(ResourceLocation texture, boolean opaque) {
        final String key = texture + (opaque ? "|o" : "|t");
        return CACHE.computeIfAbsent(key, k -> RenderType.create(
                "cineduct_screen",
                FORMAT,
                VertexFormat.Mode.QUADS,
                /* bufferSize */ 1536,
                /* affectsCrumbling */ false,
                /* sortOnUpload */ false,
                RenderType.CompositeState.builder()
                        .setShaderState(RenderStateShard.RENDERTYPE_TRANSLUCENT_SHADER)
                        .setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
                        .setTransparencyState(opaque ? RenderStateShard.NO_TRANSPARENCY
                                                     : RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                        .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                        .setWriteMaskState(opaque ? RenderStateShard.COLOR_DEPTH_WRITE
                                                  : RenderStateShard.COLOR_WRITE)
                        .setLightmapState(RenderStateShard.LIGHTMAP)
                        .setOverlayState(RenderStateShard.NO_OVERLAY)
                        .setCullState(RenderStateShard.NO_CULL)
                        .createCompositeState(true)));
    }

    /**
     * 算出一个屏幕四边形的四个角（模型空间，已按 fit 处理过黑边/裁切）。
     *
     * @return 4 个 {@code {x, y, u, v}}，顺序是 左上→右上→右下→左下
     */
    static float[][] corners(float width, float height, float entityAspect, float texAspect,
                             dev.mediafix.api.ScreenFit fit) {
        float quadWidth = width;
        float quadHeight = height;
        float u0 = 0.0f;
        float u1 = 1.0f;
        float v0 = 0.0f;
        float v1 = 1.0f;
        if (fit == dev.mediafix.api.ScreenFit.FILL) {
            final float texOverEntity = texAspect / entityAspect;
            if (texOverEntity >= 1.0f) {
                // 视频比屏幕宽：左右裁掉，取中间 1/texOverEntity
                final float keep = 1.0f / texOverEntity;
                u0 = (1.0f - keep) / 2.0f;
                u1 = u0 + keep;
            } else {
                // 视频比屏幕高：上下裁掉
                final float keep = texOverEntity;
                v0 = (1.0f - keep) / 2.0f;
                v1 = v0 + keep;
            }
        } else if (fit == dev.mediafix.api.ScreenFit.LETTERBOX) {
            if (texAspect > entityAspect * 1.01f) {
                quadHeight = width / texAspect;          // 视频比屏幕宽 → 上下黑边
            } else if (texAspect < entityAspect * 0.99f) {
                quadWidth = height * texAspect;          // 视频比屏幕高 → 左右黑边
            }
        }
        final float x0 = -quadWidth / 2.0f;
        final float x1 = quadWidth / 2.0f;
        final float y0 = (height - quadHeight) / 2.0f;
        final float y1 = y0 + quadHeight;
        return new float[][] {
                {x0, y1, u0, v0},
                {x1, y1, u1, v0},
                {x1, y0, u1, v1},
                {x0, y0, u0, v1},
        };
    }

    /**
     * 写一个顶点。
     *
     * <p>顺序与分量都不能省：位置（矩阵变换过）、颜色（亮度/透明度都乘在这里）、UV、
     * 光照贴图（恒满亮，见类注释）。
     */
    static void emit(VertexConsumer consumer, PoseStack.Pose pose, float[] corner,
                     float brightness, float alpha) {
        consumer.addVertex(pose, corner[0], corner[1], 0.0f)
                .setColor(brightness, brightness, brightness, alpha)
                .setUv(corner[2], corner[3])
                .setLight(LightTexture.FULL_BRIGHT);
    }

    /**
     * 写一个**世界坐标**顶点（用于虚拟屏：几何由服务端下发，不是本地矩形）。
     *
     * <p>与 {@link #emit} 的区别只有一个：z 由参数决定，而不是恒为 0。
     * 调用前把"相机相对偏移"压进 PoseStack（见 {@code SlotPlayback.draw}）。
     */
    static void emit3(VertexConsumer consumer, PoseStack.Pose pose, net.minecraft.world.phys.Vec3 p,
                      float u, float v, float brightness, float alpha) {
        consumer.addVertex(pose, (float) p.x, (float) p.y, (float) p.z)
                .setColor(brightness, brightness, brightness, alpha)
                .setUv(u, v)
                .setLight(LightTexture.FULL_BRIGHT);
    }
}

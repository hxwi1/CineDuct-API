package dev.mediafix.render;

import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;

/**
 * 把一块 RGBA 内存上传到指定纹理。
 *
 * <p><b>为什么不用 glGetInteger 保存/恢复状态</b>：{@code glGetInteger} 是同步点 ——
 * 驱动必须把命令队列整个冲刷、等 GPU 追上来才回答。每帧查 5 次就等于每帧把渲染管线掐停，
 * 实测让游戏从 30fps 掉到 6~13fps（表现就是"画面跟不上"）。
 * 改成 MC 自己的状态缓存（{@code GlStateManager._bindTexture/_pixelStore}）后零查询；
 * 因为缓存被同步成了真实状态，上传结束后不需要再恢复。
 *
 * <p><b>信箱化</b>：这里走 {@link Letterbox#prepare}，按屏幕实体自己的宽高比加黑边，
 * 非 16:9 的屏幕上画面不被拉伸。
 */
public final class GlSafeUpload {

    /** 上次上传的纹理与尺寸：用来判断要不要重新分配纹理存储（glTexImage2D / glTexSubImage2D）。 */
    private static int lastTex = -1;
    private static int lastW = -1;
    private static int lastH = -1;

    private GlSafeUpload() {
    }

    /** 视频帧上传入口（引擎路径）。 */
    public static void uploadFrame(ByteBuffer rgba, int textureId, int width, int height) {
        uploadFrame(rgba, textureId, width, height, false, 0f);
    }

    /**
     * 视频帧上传入口（引擎路径）。
     *
     * <p><b>{@code forceAllocate} 必须由"每个屏幕实例"决定，不能只靠下面的静态记录。</b>
     * 原因：屏幕被重建/销毁时 GL 会把纹理 id 回收给下一块屏幕 —— 此时静态记录里
     * "这个 id 已经分配过 3840x2160"，于是我们只调 {@code glTexSubImage2D}，写入一块根本没有
     * 存储的纹理：**画面全黑、声音正常**。调用方跟踪自己这块屏幕的纹理 id，
     * 一旦发现换过纹理就要求重新分配。
     *
     * @param targetAspect 屏幕实体宽高比（用于信箱化）；&lt;=0 表示不加黑边
     */
    public static void uploadFrame(ByteBuffer rgba, int textureId, int width, int height,
                                   boolean forceAllocate, float targetAspect) {
        if (rgba == null || textureId <= 0 || width <= 0 || height <= 0) return;

        Letterbox.Canvas canvas = Letterbox.prepare(rgba, width, height, targetAspect);
        ByteBuffer data = canvas == null ? rgba : canvas.buf;
        int uw = canvas == null ? width : canvas.w;
        int uh = canvas == null ? height : canvas.h;

        boolean first = forceAllocate || textureId != lastTex || uw != lastW || uh != lastH;
        upload(data, textureId, uw, uh, first);
        lastTex = textureId;
        lastW = uw;
        lastH = uh;
    }

    /**
     * @param rgba      direct buffer，布局为 R,G,B,A 每像素 4 字节
     * @param first     true = 需要重新分配纹理存储（首帧或尺寸变化）
     * @param textureId 目标纹理
     */
    public static void upload(ByteBuffer rgba, int textureId, int width, int height, boolean first) {
        if (rgba == null || textureId <= 0 || width <= 0 || height <= 0) return;
        if (rgba.capacity() < width * height * 4) return;

        rgba.clear();

        // 走 MC 的缓存：不会产生任何 glGet* 查询
        GlStateManager._bindTexture(textureId);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);

        if (first) {
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, rgba);
        } else {
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, width, height,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, rgba);
        }
    }
}

package dev.mediafix.client;

import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * 一块屏幕在 {@code TextureManager} 里登记的"纹理"——它自己不持有 GL 纹理。
 *
 * <p><b>为什么需要这么个壳</b>：要把画面画进 {@code RenderType}（这是光影/着色器能认出来的唯一画法），
 * 就必须先有一个 {@code ResourceLocation} 指向 MC 纹理管理器里的东西。
 * 而我们的画面有两种来源，且**都不该被复制**：
 * <ul>
 *   <li>子进程零拷贝路径：GL 纹理是 {@code wglDXRegisterObjectNV} 导入来的，GL id 属于会话；</li>
 *   <li>进程内引擎路径：帧上传进 {@link net.minecraft.client.renderer.texture.DynamicTexture}，GL id 属于那张图。</li>
 * </ul>
 * 所以这里做成"id 转发壳"：{@link #getId()} 直接回答当前该采样的那个 GL id，
 * 谁都不多占一张纹理，也不做任何上传/释放（GL id 的生命周期由会话自己管）。
 * 换传输方式（子进程失败回退到进程内引擎）时只是换一个 id，对渲染侧透明。
 *
 * <p>{@link #close()} / {@link #releaseId()} 必须是空实现：MC 释放纹理时会调它们，
 * 而这张 GL 纹理不是我们这儿分配的 —— 在这里删掉会把正在播的画面删没。
 */
final class ScreenVideoTexture extends AbstractTexture {

    /** 当前该采样的 GL 纹理 id；&lt;=0 表示还没有画面。 */
    private volatile int glId = -1;

    void setGlId(int id) {
        this.glId = id;
    }

    @Override
    public int getId() {
        return this.glId;
    }

    @Override
    public void load(ResourceManager resourceManager) {
        // 画面由播放会话提供，不走资源包加载
    }

    @Override
    public void releaseId() {
        // GL 纹理属于会话：见类注释
    }

    @Override
    public void close() {
        // GL 纹理属于会话：见类注释
    }
}

package dev.mediafix.client;

import dev.mediafix.CineDuct;
import dev.mediafix.CineDuctRegistry;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.common.NeoForge;

/** 客户端专用注册（渲染器 + 阶段绘制）。用注解 + Dist.CLIENT 限定，服务端不会加载这个类。 */
@EventBusSubscriber(modid = CineDuct.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class CineDuctClientSetup {

    /**
     * 客户端注册入口。两件事：
     *
     * <p><b>① 给屏幕实体注册一个空渲染器</b>（{@link ScreenNoopRenderer}）。
     * <b>这一步不能省</b>：vanilla 的 {@code EntityRenderDispatcher.shouldRender} 假定每个实体类型
     * 都有渲染器，拿不到就直接 NPE 崩游戏（2026-10-18 现场：`/cineduct screen create` 后立刻崩）。
     * 空渲染器的 {@code render} 什么都不画，所以不会与我们自己的绘制重影。
     *
     * <p><b>② 显式注册阶段绘制的监听器</b>。原先用 {@code @EventBusSubscriber(bus = GAME)} 声明式注册，
     * 实测不生效（世界里放了屏幕但回调一次都没进来）；本项目其它游戏总线监听器都是
     * {@code NeoForge.EVENT_BUS.addListener(...)}（见 {@code CineDuct} 的构造器），这里跟同一约定。
     */
    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(CineDuctRegistry.SCREEN.get(), ScreenNoopRenderer::new);
        NeoForge.EVENT_BUS.addListener(ScreenStageRenderer::onRenderLevelStage);
        CineDuct.LOGGER.info("[CineDuct] 屏幕渲染已就绪：空实体渲染器 + 阶段绘制"
                + "（AFTER_BLOCK_ENTITIES，已注册到 NeoForge.EVENT_BUS）");
    }

    private CineDuctClientSetup() {
    }
}

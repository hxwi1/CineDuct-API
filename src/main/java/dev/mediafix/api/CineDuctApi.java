package dev.mediafix.api;

import dev.mediafix.api.event.ScreenCreatedEvent;
import dev.mediafix.api.event.ScreenRemovedEvent;
import dev.mediafix.api.event.ScreenSourceChangedEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * CineDuct 对附属 mod 的开放门面。
 *
 * <pre>{@code
 * CineDuctApi api = CineDuctApi.get();
 * ScreenHandle screen = api.createScreen(serverLevel, pos, yaw, pitch, 3f, 1.6875f,
 *         "https://www.bilibili.com/video/BV1xx411c7mD");
 * screen.volume(70);
 * screen.range(2f, 24f);
 * }</pre>
 *
 * <p><b>兼容承诺</b>：只有 {@code dev.mediafix.api} 这个包是稳定的（
 * {@link #API_VERSION} 变更即意味着不兼容）。{@code dev.mediafix.engine} / {@code .player} /
 * {@code .screen} / {@code .proxy} 等都是内部实现，随时可能改，附属 mod 不要依赖。
 *
 * <p><b>线程</b>：改动类方法内部会自己调度到服务端线程，从任意线程调用都安全；
 * 但 {@link ScreenHandle#info()} 属于快速读取，别在渲染线程高频调用。
 *
 * <p>事件（都发在 NeoForge 游戏事件总线上，服务端侧）：{@link ScreenCreatedEvent}、
 * {@link ScreenRemovedEvent}、{@link ScreenSourceChangedEvent}。
 */
public interface CineDuctApi {

    /** API 版本；不兼容改动时 +1。 */
    int API_VERSION = 2;

    /** 取门面实例；CineDuct 没加载时抛异常（附属 mod 应当把它当成硬依赖来声明）。 */
    static CineDuctApi get() {
        CineDuctApi api = Holder.INSTANCE;
        if (api == null) {
            throw new IllegalStateException("CineDuct 未加载（CineDuctApi.get() 不可用）");
        }
        return api;
    }

    /** 是否可用（不想因缺少 CineDuct 而崩的附属 mod 可以先问这个）。 */
    static boolean available() {
        return Holder.INSTANCE != null;
    }

    /** 由 CineDuct 自己在初始化时调用（附属 mod 不要调）。 */
    static void install(CineDuctApi api) {
        Holder.INSTANCE = api;
    }

    // =====================================================================

    /**
     * 创建一块屏幕（简版）。
     *
     * @param level  服务端世界
     * @param pos    屏幕**中心点**的位置
     * @param yaw    朝向（玩家视角习惯：0 = 朝南 +Z）
     * @param pitch  俯仰（一般 0）
     * @param width  宽（世界单位，0.25~64）
     * @param height 高（世界单位，0.25~64）
     * @param source 播放源（原始链接；空串 = 先建一块空屏，之后再 {@link ScreenHandle#source}）
     */
    ScreenHandle createScreen(ServerLevel level, Vec3 pos, float yaw, float pitch,
                              float width, float height, String source);

    /**
     * 创建一块屏幕（全参数版）。
     *
     * <p>能一次把尺寸/朝向/音量/距离衰减/循环/清晰度/不透明度/亮度/铺法/单双面/音频形态
     * 全部设好，不用建完再一个个调 —— 参数含义见 {@link ScreenOptions}。
     */
    ScreenHandle createScreen(ServerLevel level, ScreenOptions options);

    /** 按 UUID 找屏幕（已不存在/Hybrid 卸载则 empty）。 */
    Optional<ScreenHandle> screen(UUID id);

    /** 某个维度里所有已加载的屏幕。 */
    List<ScreenHandle> screens(Level level);

    /** 用实体对象换一个句柄（判断类型后返回 empty）。 */
    Optional<ScreenHandle> handleOf(Entity entity);

    /** 注册一个源解析器（排在最后；想抢优先级用 {@code SourceResolvers.registerFirst}）。 */
    void addResolver(MediaSourceResolver resolver);

    /** 当前已注册的解析器名字（按优先级顺序）。 */
    List<String> resolverNames();

    /** 内部持有者。 */
    final class Holder {
        static volatile CineDuctApi INSTANCE;

        private Holder() {
        }
    }
}

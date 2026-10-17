package dev.mediafix.api.client;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 客户端侧 API 门面（附属 mod 想在客户端读"实际播放状态"时用它）。
 *
 * <pre>{@code
 * if (CineDuctClientApi.available()) {
 *     CineDuctClientApi api = CineDuctClientApi.get();
 *     api.status(screenId).ifPresent(st -> {
 *         // st.transport() == "dxgi-interop" 说明走的是零拷贝子进程
 *         System.out.println(st.state() + " " + st.positionMs() + "ms via " + st.transport());
 *     });
 * }
 * }</pre>
 *
 * <p><b>线程</b>：这些方法读的是客户端状态，请在客户端线程调用（渲染/主线程）。
 * 从别的线程读不会崩，但可能读到上一帧的值。
 */
public interface CineDuctClientApi {

    int API_VERSION = 2;

    /** 取门面；CineDuct 未加载（或不在客户端）时抛异常。 */
    static CineDuctClientApi get() {
        CineDuctClientApi api = Holder.INSTANCE;
        if (api == null) {
            throw new IllegalStateException("CineDuct 客户端 API 不可用（未加载，或运行在服务端）");
        }
        return api;
    }

    static boolean available() {
        return Holder.INSTANCE != null;
    }

    /** 由 CineDuct 自己在客户端初始化时调用。 */
    static void install(CineDuctClientApi api) {
        Holder.INSTANCE = api;
    }

    /** 某块屏幕的客户端实际状态；屏幕不存在/还没开始播则 empty。 */
    Optional<ClientScreenStatus> status(UUID screenId);

    /** 当前客户端已建立的屏幕会话（屏幕 UUID）。 */
    List<UUID> activeScreens();

    /** 本次游戏会话里零拷贝（D3D11 共享纹理 → GL）是否可用。 */
    boolean zeroCopyAvailable();

    /** 传输方式诊断（为什么可用/不可用、GL 适配器是谁）。 */
    String transportDiagnostics();

    /**
     * 光影（Iris）诊断：是否装了 Iris、光影是否启用、屏幕在光影下是怎么画的。
     *
     * <p>装了光影时 CineDuct 的屏幕是**自发光直绘**（不参与光影的 gbuffer/阴影/反射），
     * 阴影贴图 pass 里会跳过屏幕。需要给玩家提示/做适配的附属 mod 可以读这个字符串。
     */
    String shaderDiagnostics();

    /** 光影包是否正在使用（没装 Iris 或没开光影都是 false）。 */
    boolean shadersInUse();

    // =====================================================================
    // 玩家提示（聊天栏那些 [CineDuct] 消息）
    // =====================================================================

    /** 当前是否在发聊天栏提示。 */
    boolean noticesEnabled();

    /**
     * 开关聊天栏提示（解析结果、"回退原生播放"、屏幕播放失败之类）。
     *
     * <p><b>关是粘滞的</b>：调一次 {@code false} 之后立即生效，而且**本局游戏内不会被再打开** ——
     * 提示是全客户端共享的一条通道，一块屏可能同时被好几个附属 mod 驱动，
     * 只要有任何一个调用方希望它安静（自己画了 UI、或在做无头测试），就该听那个"关"，
     * 否则后调用的 mod 一开、前面那位又被刷屏。
     *
     * <p>要恢复只有一条路：{@code /cineduct notice reset}（给排障用，正式调用方别调）。
     *
     * @param enabled false = 关闭（粘滞）/ true = 打开（仅在没人关过时生效）
     * @return 调用之后**实际**的状态（被拒绝时返回当前真实值，而不是你请求的值）
     */
    boolean setNoticesEnabled(boolean enabled);

    final class Holder {
        static volatile CineDuctClientApi INSTANCE;

        private Holder() {
        }
    }
}

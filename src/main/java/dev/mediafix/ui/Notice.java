package dev.mediafix.ui;

import dev.mediafix.CineDuct;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * 玩家提示：解析结果/失败原因直接显示在聊天栏，不让玩家对着转圈干等。
 *
 * <p>线程安全：可从任意工作线程调用，UI 更新调度到渲染线程执行。
 *
 * <p><b>可以被关掉</b>（见 {@link #setEnabled(boolean)}）：附属 mod 接管了提示、或者不想被刷屏时，
 * 调一次就不再有任何 {@code [CineDuct]} 聊天消息。**关是粘滞的** —— 多个调用方同时要求时听"关"的那个，
 * 本局游戏内不会被别人再打开（想恢复见 {@link #reset()}）。
 *
 * <p>（原先这里还有一条"下载进度条"通道，随本地缓存下载路径一起移除了 ——
 * 本模组只做流式播放，没有下载可言。）
 */
public final class Notice {

    /** 当前是否允许发提示。 */
    private static volatile boolean enabled = true;
    /**
     * 粘滞关闭位：一旦有人要求关，就不再接受"打开"。
     *
     * <p>为什么这么定：一块屏可能同时被好几个附属 mod 驱动，提示是全客户端共享的一条通道。
     * 只要有**任何一个**调用方希望它安静（例如自己已经画了 UI、或者在做无头测试），
     * 就该听那个"关" —— 否则后调用的 mod 一开，前面那位又会被刷屏。
     */
    private static volatile boolean disabledByCaller;
    /** 只提示一次的日志标志（避免每帧/每次调用都刷）。 */
    private static volatile boolean loggedIgnoredEnable;
    private static volatile boolean loggedDisabled;

    private Notice() {
    }

    /** 当前是否在发提示（附属 mod 可以据此判断要不要自己补一条）。 */
    public static boolean enabled() {
        return enabled;
    }

    /**
     * 开关玩家提示。
     *
     * @param want {@code false} = 关闭（立即生效，并且**本局游戏内粘滞**：
     *             之后任何 {@code true} 都会被忽略，日志里会说明原因）；
     *             {@code true} = 打开，但只有在没人关过的情况下才生效
     * @return 调用之后**实际**的开关状态（被拒绝时返回当前真实值，而不是你请求的值）
     */
    public static boolean setEnabled(boolean want) {
        if (!want) {
            final boolean first = !disabledByCaller;
            enabled = false;
            disabledByCaller = true;
            if (first && !loggedDisabled) {
                loggedDisabled = true;
                CineDuct.LOGGER.info("[CineDuct] 玩家提示已被调用方关闭（本局游戏内粘滞）");
            }
            return false;
        }
        if (disabledByCaller) {
            if (!loggedIgnoredEnable) {
                loggedIgnoredEnable = true;
                CineDuct.LOGGER.info("[CineDuct] 忽略了打开提示的请求：已有调用方关闭过（要恢复用 /cineduct notice reset）");
            }
            return false;
        }
        enabled = true;
        return true;
    }

    /**
     * 清掉粘滞位并恢复提示。
     *
     * <p>给排障用（{@code /cineduct notice reset}），**正式调用方不要用** ——
     * 它会推翻别的 mod 关掉提示的意愿。
     */
    public static void reset() {
        disabledByCaller = false;
        enabled = true;
        loggedIgnoredEnable = false;
        loggedDisabled = false;
        CineDuct.LOGGER.info("[CineDuct] 玩家提示已恢复（粘滞位已清除）");
    }

    /** 聊天栏提示（保留在聊天记录里）。提示被关掉时这里是空操作。 */
    public static void message(String text) {
        if (!enabled) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }
        mc.execute(() -> {
            if (!enabled) return;   // 排队期间被关掉的情况
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal("[CineDuct] " + text), false);
            }
        });
    }
}

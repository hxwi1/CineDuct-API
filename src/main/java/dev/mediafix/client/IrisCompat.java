package dev.mediafix.client;

import dev.mediafix.CineDuct;

import java.lang.reflect.Method;

/**
 * Iris（光影加载器）兼容层 —— **纯反射、软依赖**：Iris 不在时全部返回"没有光影"。
 *
 * <p><b>为什么需要它</b>：CineDuct 的屏幕是"自己拿 GL 直绘一个四边形"（不走 RenderType），
 * 这在原版与 Sodium 下都没问题，但装了 Iris 之后有两个具体后果：
 * <ol>
 *   <li><b>阴影贴图里会把屏幕也画一遍</b>：Iris 的 shadow pass 会重跑实体渲染，
 *       于是屏幕内容会被当成"会挡光的东西"投出阴影（一块发光的屏幕投出画面形状的阴影，很怪）。
 *       Iris 官方给的判据就是 {@code IrisApi.isRenderingShadowPass()}，这里据此跳过。</li>
 *   <li><b>光照/后处理</b>：我们的顶点格式只有 position+texcoord（没有法线、没有光照贴图），
 *       所以屏幕恒为"自发光"。Iris 会用它的 core shader（等价 position_tex）画它，
 *       通常在画面里是正常的，但**会受该光影包的 tonemapping/曝光影响**；
 *       若某个光影包要求实体程序必须有法线，可能出现偏暗/发光异常。</li>
 * </ol>
 *
 * <p>结论写在 {@link #diagnostics()} 里，也通过客户端 API 暴露出去（附属 mod 可以据此提示玩家）。
 * 真正"把屏幕接进光影管线"（拿到 Iris 的 gbuffer、参与阴影/反射）需要 Iris 的编译期 API，
 * 目前**没有做**。详见 {@code docs/API.md} 的"光影（Iris）"一节。
 */
public final class IrisCompat {

    private static boolean probed;
    private static boolean irisPresent;
    private static Method isShaderPackInUse;
    private static Method isRenderingShadowPass;
    private static Method getInstance;

    private IrisCompat() {
    }

    private static synchronized void probe() {
        if (probed) return;
        probed = true;
        try {
            final Class<?> apiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            getInstance = apiClass.getMethod("getInstance");
            final Class<?> instanceClass = getInstance.getReturnType();
            isShaderPackInUse = instanceClass.getMethod("isShaderPackInUse");
            isRenderingShadowPass = instanceClass.getMethod("isRenderingShadowPass");
            irisPresent = true;
            CineDuct.LOGGER.info("[CineDuct] 检测到 Iris（光影加载器）：屏幕绘制已按它的 shadow pass 判据做跳过");
        } catch (Throwable t) {
            irisPresent = false;
        }
    }

    /** Iris 是否在这个游戏里。 */
    public static boolean present() {
        probe();
        return irisPresent;
    }

    /** 光影包是否正在使用（Iris 不在、或没开光影都返回 false）。 */
    public static boolean shadersInUse() {
        probe();
        if (!irisPresent) return false;
        try {
            return Boolean.TRUE.equals(isShaderPackInUse.invoke(getInstance.invoke(null)));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 当前是否在渲染阴影贴图（Iris 的 shadow pass）。
     *
     * <p>这个 pass 里**不画屏幕**：屏幕是不透明的显示面，让它投出画面形状的阴影既不对也难看。
     * 想让它投影（例如想给一块"实体幕布"投个影），把这里改成 false 即可。
     */
    public static boolean inShadowPass() {
        probe();
        if (!irisPresent) return false;
        try {
            return Boolean.TRUE.equals(isRenderingShadowPass.invoke(getInstance.invoke(null)));
        } catch (Throwable t) {
            return false;
        }
    }

    /** 一行人类可读的诊断（进日志 / 客户端 API）。 */
    public static String diagnostics() {
        probe();
        if (!irisPresent) return "未检测到 Iris";
        return shadersInUse()
                ? "Iris 已加载且光影启用（屏幕按自发光直绘，未接入光影管线）"
                : "Iris 已加载，光影未启用";
    }
}

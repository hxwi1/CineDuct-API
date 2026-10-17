package dev.mediafix.api;

import java.net.URI;

/**
 * 「链接 → 直链」解析器扩展点（SPI）。
 *
 * <p>CineDuct 内置一个 B 站解析器（走这条接口注册，没有特殊待遇）；附属 mod 想要支持别的站点，
 * 实现本接口并调用 {@code dev.mediafix.source.SourceResolvers.register(...)} 即可 ——
 * 不需要碰 CineDuct 的任何内部类。
 *
 * <p><b>线程</b>：{@link #resolve(URI)} 可能发网络请求，**绝不能在渲染线程/主线程调用**。
 *
 * <p><b>失败约定</b>：返回 {@code null} 表示"我处理不了 / 解析失败"，上层会继续尝试下一个解析器，
 * 最后回退成"把原链接当直链"（玩家能看到明确的失败画面，而不是黑屏转圈）。
 * 抛异常同样会被上层捕获并回退。
 */
public interface MediaSourceResolver {

    /** 解析器名字（日志/诊断用）。 */
    String name();

    /** 是否由本解析器处理这个链接。实现必须只看链接本身，不要发请求。 */
    boolean canHandle(URI uri);

    /**
     * 解析出可播放的直链。
     *
     * @return 解析结果；{@code null} = 处理不了或失败（上层回退）
     * @throws Exception 网络/接口异常（上层捕获并回退，不会让它冒泡到调用者）
     */
    ResolvedStream resolve(URI uri) throws Exception;
}

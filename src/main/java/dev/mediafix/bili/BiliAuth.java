package dev.mediafix.bili;

import dev.mediafix.CineDuct;
import net.neoforged.fml.loading.FMLPaths;

import java.io.InputStream;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * B 站登录态（Cookie）读取。
 *
 * <p>自己去读 bilibili_media 用的那份文件——格式是 Java Properties，键名 {@code BILIBILI_COOKIE}——
 * 这样玩家已有的登录态可以直接沿用，不需要重新扫码；同时支持在 mod 配置里手填覆盖。
 * 本模组不实现扫码登录，只消费登录态。
 */
public final class BiliAuth {

    private static final String COOKIE_KEY = "BILIBILI_COOKIE";
    private static final Path HOME_COOKIE = Path.of(System.getProperty("user.home"), ".bilimedia", "cookie.properties");

    private static volatile String cached;
    private static volatile long cachedAt;

    private BiliAuth() {
    }

    private static Path gameCookie() {
        try {
            return FMLPaths.GAMEDIR.get().resolve(".bilimedia").resolve("cookie.properties");
        } catch (Throwable t) {
            return null;
        }
    }

    /** 读取登录 Cookie；没有则返回 null。结果缓存 60 秒，避免每次请求都读盘。 */
    public static String cookie() {
        long now = System.currentTimeMillis();
        String c = cached;
        if (c != null && now - cachedAt < 60_000L) {
            return c.isBlank() ? null : c;
        }
        String value = read();
        cached = value == null ? "" : value;
        cachedAt = now;
        return value;
    }

    /** 登录/登出后立即失效，避免还要等 60 秒缓存。 */
    public static void invalidate() {
        cached = null;
        cachedAt = 0;
    }

    public static boolean hasCookie() {
        String c = cookie();
        return c != null && !c.isBlank();
    }

    /** 把 Cookie 挂到请求上（没有登录态则原样返回）。 */
    public static HttpRequest.Builder withCookie(HttpRequest.Builder builder) {
        String c = cookie();
        if (c != null && !c.isBlank()) {
            builder.header("Cookie", c);
        }
        return builder;
    }

    private static String read() {
        // 1) 配置里手填优先
        try {
            String cfg = dev.mediafix.config.BiliConfig.cookie;
            if (cfg != null && !cfg.isBlank()) return cfg.trim();
        } catch (Throwable ignored) {
        }
        // 2) 玩家主目录（bilibili_media 的默认位置）
        String v = readFile(HOME_COOKIE);
        if (v != null) return v;
        // 3) 游戏目录
        Path g = gameCookie();
        if (g != null) {
            v = readFile(g);
            if (v != null) return v;
        }
        return null;
    }

    private static String readFile(Path path) {
        try {
            if (path == null || !Files.isRegularFile(path)) return null;
            Properties props = new Properties();
            try (InputStream in = Files.newInputStream(path)) {
                props.load(in);
            }
            String v = props.getProperty(COOKIE_KEY, "").trim();
            if (v.isEmpty()) return null;
            CineDuct.LOGGER.info("[CineDuct] 已读取 B 站登录态: {}", path);
            return v;
        } catch (Throwable t) {
            return null;
        }
    }
}

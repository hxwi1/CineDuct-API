package dev.mediafix.ffmpeg;

import dev.mediafix.CineDuct;
import dev.mediafix.config.FfmpegConfig;

import java.net.URI;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 播放器实例 → FFmpeg 数据源 的映射。
 *
 * <p>为什么用弱引用表：watermedia 的 BasePlayer 生命周期由 waterframes 的方块实体掌管，
 * 方块被拆/区块卸载时播放器对象会被丢弃。用 WeakHashMap 保证我们不持有它，避免阻止回收。
 */
public final class FfmpegSources {

    private static final Map<Object, FfmpegVideoSource> SOURCES = java.util.Collections.synchronizedMap(new WeakHashMap<>());

    private FfmpegSources() {
    }

    /** 为某个播放器创建并启动 FFmpeg 数据源（已存在则先关闭旧的）。 */
    public static FfmpegVideoSource attach(Object player, URI resolvedUri) {
        if (player == null || resolvedUri == null) return null;
        detach(player);
        try {
            FfmpegVideoSource src = new FfmpegVideoSource(resolvedUri, buildHeaders(resolvedUri));
            src.start();
            SOURCES.put(player, src);
            CineDuct.LOGGER.info("[CineDuct] 已为该播放器接管视频解码: {}", resolvedUri);
            return src;
        } catch (Throwable t) {
            CineDuct.LOGGER.error("[CineDuct] 创建 FFmpeg 数据源失败", t);
            return null;
        }
    }

    public static FfmpegVideoSource of(Object player) {
        if (player == null) return null;
        return SOURCES.get(player);
    }

    public static void detach(Object player) {
        FfmpegVideoSource old = SOURCES.remove(player);
        if (old != null) {
            try {
                old.close();
            } catch (Throwable t) {
                CineDuct.LOGGER.warn("[CineDuct] 关闭 FFmpeg 数据源异常", t);
            }
        }
    }

    /**
     * 组装给 FFmpeg 的请求头。B 站 CDN 需要 Referer，高清直链还需要登录 Cookie；
     * Cookie 优先从 bilibili_media 的存储里取（反射，缺失就跳过——不在编译期硬依赖它）。
     */
    public static String buildHeaders(URI uri) {
        StringBuilder sb = new StringBuilder();
        sb.append("Referer: https://www.bilibili.com/\r\n");
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.ROOT);
        if (host.endsWith("bilivideo.com") || host.endsWith("bilivideo.cn") || host.endsWith("hdslb.com")
                || host.endsWith("bilibili.com")) {
            String cookie = biliCookie();
            if (cookie != null && !cookie.isBlank()) {
                sb.append("Cookie: ").append(cookie).append("\r\n");
            }
        }
        return sb.toString();
    }

    private static String biliCookie() {
        try {
            return dev.mediafix.bili.BiliAuth.cookie();
        } catch (Throwable ignored) {
            return null;
        }
    }
}

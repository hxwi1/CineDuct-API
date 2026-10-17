package dev.mediafix.source;

import dev.mediafix.CineDuct;
import dev.mediafix.api.MediaSourceResolver;
import dev.mediafix.api.ResolvedStream;
import dev.mediafix.bili.BiliUrls;
import dev.mediafix.config.StreamConfig;
import dev.mediafix.proxy.DashResolver;

import java.net.URI;

/**
 * B 站解析器：把 {@code bilibili.com} / {@code b23.tv} / 直播间链接解析成两条 DASH 直链。
 *
 * <p>以前这一层是"实现 watermedia 的 {@code AbstractPatch} 并注册进它的 {@code NetworkAPI}"，
 * 也就是必须装着 watermedia 才能解析。现在换成实现 CineDuct 自己的
 * {@link MediaSourceResolver}：前置 mod 一个都不需要。
 *
 * <p>{@link #canHandle(URI)} 只认 B 站域名，绝不会抢别的链接。
 */
public final class BiliSourceResolver implements MediaSourceResolver {

    @Override
    public String name() {
        return "bilibili";
    }

    @Override
    public boolean canHandle(URI uri) {
        return BiliUrls.isBilibili(uri);
    }

    @Override
    public ResolvedStream resolve(URI uri) {
        // 高清链路关闭时不做解析：直接把链接当直链，让引擎自己试
        if (!StreamConfig.highres) {
            return ResolvedStream.direct(uri);
        }
        /*
         * B 站的页面链接不是可播放直链：没有 FFmpeg（DASH 解析/解码的唯一实现）时，
         * 解析必然失败。这里明确说清楚，而不是让它退化成"拿网页当流打开"再报一个
         * 看不懂的"无法打开输入"—— 排查方向会被完全带偏（实测踩过）。
         */
        if (!dev.mediafix.ffmpeg.FfmpegRuntime.available()) {
            dev.mediafix.ui.Notice.message(
                    "FFmpeg 原生库不可用，无法解析/播放 B 站内容（执行 /cineduct status 可查看原因并重试）");
            CineDuct.LOGGER.warn("[CineDuct] 拒绝解析 {}：FFmpeg 原生库不可用（{}）",
                    CineDuct.LOGGER.url(uri), dev.mediafix.ffmpeg.FfmpegRuntime.status());
            return null;
        }
        URI longUri = BiliUrls.expandShortLink(uri);
        ResolvedStream out = DashResolver.resolve(longUri);
        if (out == null) {
            CineDuct.LOGGER.warn("[CineDuct] B 站解析失败（无可用流），回退为直连尝试: {}",
                    CineDuct.LOGGER.url(longUri));
            return ResolvedStream.direct(uri);
        }
        return out;
    }
}

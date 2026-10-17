package dev.mediafix.bili;

import dev.mediafix.CineDuct;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B 站链接解析（自研）：主机判定、BV 号、分P、番剧 ep/ss、b23 短链展开。
 * 不依赖任何外部模组。
 */
public final class BiliUrls {

    private static final Pattern BVID = Pattern.compile("(BV[0-9A-Za-z]{10})");
    private static final Pattern PAGE = Pattern.compile("[?&]p=([0-9]+)");
    private static final Pattern EP = Pattern.compile("/ep([0-9]+)");
    private static final Pattern SS = Pattern.compile("/ss([0-9]+)");
    /** 直播间：live.bilibili.com/<房间号>，也兼容 /blanc/、/h5/ 前缀。 */
    private static final Pattern LIVE_ROOM = Pattern.compile("live\\.bilibili\\.com/(?:blanc/|h5/)?([0-9]+)");
    /** 直播间：?room_id=<房间号>（部分分享链接是这种形式）。 */
    private static final Pattern LIVE_ROOM_Q = Pattern.compile("[?&]room_id=([0-9]+)");

    private static final String[] HOSTS = {
            "bilibili.com", "www.bilibili.com", "m.bilibili.com", "live.bilibili.com", "b23.tv"
    };

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private BiliUrls() {
    }

    /** 是否归我们处理（主机白名单）。 */
    public static boolean isBilibili(URI uri) {
        if (uri == null || uri.getHost() == null) return false;
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        for (String h : HOSTS) {
            if (host.equals(h) || host.endsWith("." + h)) return true;
        }
        return false;
    }

    public static String bvid(String url) {
        Matcher m = BVID.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    public static int page(String url) {
        Matcher m = PAGE.matcher(url);
        if (m.find()) {
            try {
                return Math.max(1, Integer.parseInt(m.group(1)));
            } catch (NumberFormatException ignored) {
            }
        }
        return 1;
    }

    /**
     * 直播间链接 → 房间号；非直播间链接返回 null。
     * 直播和点播是两套完全不同的接口，这里只负责把房间号拿出来。
     */
    public static Long liveRoomId(String url) {
        if (url == null) return null;
        Long id = matchNumber(LIVE_ROOM, url);
        return id != null ? id : matchNumber(LIVE_ROOM_Q, url);
    }

    public static Long epId(String url) {
        return matchNumber(EP, url);
    }

    public static Long seasonId(String url) {
        return matchNumber(SS, url);
    }

    private static Long matchNumber(Pattern p, String url) {
        Matcher m = p.matcher(url);
        if (!m.find()) return null;
        try {
            return Long.parseLong(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 展开 b23.tv 短链（跟随重定向）。失败时原样返回，让后续流程自己判断。
     */
    public static URI expandShortLink(URI uri) {
        try {
            if (uri.getHost() == null || !uri.getHost().toLowerCase(Locale.ROOT).endsWith("b23.tv")) {
                return uri;
            }
            HttpRequest req = HttpRequest.newBuilder(uri)
                    .header("User-Agent", UA)
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<Void> resp = CLIENT.send(req, HttpResponse.BodyHandlers.discarding());
            URI finalUri = resp.uri();
            if (finalUri != null && finalUri.getHost() != null) {
                CineDuct.LOGGER.info("[CineDuct] 短链展开: {} -> {}", uri, finalUri);
                return finalUri;
            }
        } catch (Throwable t) {
            CineDuct.LOGGER.warn("[CineDuct] 短链展开失败: {}", uri);
        }
        return uri;
    }

    public static String userAgent() {
        return UA;
    }
}

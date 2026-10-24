package dev.mediafix.net;

import dev.mediafix.CineDuct;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/**
 * 安全闸门（{@code docs/paper-control-payload.md} §5）。
 *
 * <p>顺序固定：{@code enabled} → 服务器白名单 → 令牌 → op 白名单 → URL 域名白名单 → 频率。
 * **顺序不能换**：白名单之外的一切都不该消耗频率配额，也不该先解析 URL。
 *
 * <p>全部判定都是纯字符串/计数操作，跑在网络线程上也不阻塞。
 */
public final class CineGate {

    /** 判定结果。{@code reason} 只用于日志与回执，不区分"给谁看"。 */
    public record Verdict(boolean allow, String reason) {
        public static Verdict ok() {
            return new Verdict(true, "");
        }

        public static Verdict deny(String reason) {
            return new Verdict(false, reason);
        }
    }

    /** 频率窗口（毫秒）。固定 1 秒滑窗，比令牌桶好解释。 */
    private static final long WINDOW_MS = 1000L;

    /** 最近一次判定用的服务器地址（换服就重置计数）。 */
    private static String windowServer = "";
    private static final Deque<Long> recent = new ArrayDeque<>();

    private CineGate() {
    }

    /**
     * 做一次完整判定。
     *
     * @param packet     已解析的报文
     * @param serverAddr 当前服务器地址（{@code ip} 或 {@code host:port}；单人游戏为 {@code singleplayer}）
     */
    public static synchronized Verdict check(CineControlPacket packet, String serverAddr) {
        if (!CinePacketConfig.enabled) {
            return Verdict.deny("通道未启用（cineduct-packet.json 里 enabled=false）");
        }

        if (!serverAllowed(serverAddr)) {
            return Verdict.deny("服务器不在白名单: " + serverAddr);
        }

        final String want = CinePacketConfig.token;
        if (want != null && !want.isBlank()) {
            if (!want.equals(packet.token)) {
                return Verdict.deny("令牌不匹配");
            }
        }

        if (CinePacketConfig.disabledOps.contains(packet.op)) {
            return Verdict.deny("该 op 已被禁用: " + packet.op);
        }

        if ("open".equals(packet.op)) {
            final Verdict url = checkUrl(packet.url);
            if (!url.allow()) return url;
        }
        if ("sync".equals(packet.op)) {
            for (CineControlPacket.Entry e : packet.entries) {
                final Verdict url = checkUrl(str(e.raw().get("url")));
                if (!url.allow()) return url;
            }
        }

        return rate(serverAddr);
    }

    /** 白名单匹配：空 = 全拒；否则地址里包含任一项即可（这样 {@code mc.example.com} 能匹配带端口的地址）。 */
    private static boolean serverAllowed(String serverAddr) {
        if (CinePacketConfig.servers == null || CinePacketConfig.servers.isEmpty()) return false;
        final String addr = serverAddr == null ? "" : serverAddr.toLowerCase(Locale.ROOT);
        for (String s : CinePacketConfig.servers) {
            if (s == null || s.isBlank()) continue;
            if (addr.contains(s.trim().toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    /** `open` 的 URL 只允许白名单 host（后缀匹配：`x.bilibili.com` 命中 `bilibili.com`）。 */
    private static Verdict checkUrl(String url) {
        if (url == null || url.isBlank()) return Verdict.deny("open 缺少 url");
        final URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (Exception e) {
            return Verdict.deny("url 不是合法 URI");
        }
        final String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return Verdict.deny("url 只允许 http/https: " + scheme);
        }
        final String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (host.isEmpty()) return Verdict.deny("url 没有 host");
        for (String allowed : CinePacketConfig.allowedHosts) {
            if (allowed == null || allowed.isBlank()) continue;
            final String a = allowed.trim().toLowerCase(Locale.ROOT);
            if (host.equals(a) || host.endsWith("." + a)) return Verdict.ok();
        }
        return Verdict.deny("url 域名不在白名单: " + host);
    }

    /** 1 秒滑窗。换服重置。 */
    private static Verdict rate(String serverAddr) {
        final long now = System.currentTimeMillis();
        if (!serverAddr.equals(windowServer)) {
            windowServer = serverAddr;
            recent.clear();
        }
        while (!recent.isEmpty() && now - recent.peekFirst() > WINDOW_MS) {
            recent.pollFirst();
        }
        if (recent.size() >= Math.max(1, CinePacketConfig.maxPerSecond)) {
            return Verdict.deny("rate_limited（超过 " + CinePacketConfig.maxPerSecond + " 条/秒）");
        }
        recent.addLast(now);
        return Verdict.ok();
    }

    /** 换服/退服时由上层调一次，清掉计数与地址缓存。 */
    public static synchronized void resetWindow() {
        windowServer = "";
        recent.clear();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** 一句话现状（`/cineduct packet status` 用）。 */
    public static String summary() {
        if (!CinePacketConfig.enabled) return "关（cineduct-packet.json 的 enabled=false）";
        return "开  白名单=" + CinePacketConfig.servers
                + "  令牌=" + (CinePacketConfig.token == null || CinePacketConfig.token.isBlank() ? "无" : "有")
                + "  禁用op=" + CinePacketConfig.disabledOps
                + "  域名=" + CinePacketConfig.allowedHosts
                + "  限速=" + CinePacketConfig.maxPerSecond + "/秒";
    }

    /** 供日志使用：不打印 URL 的查询串（可能带凭证）。 */
    public static String safeUrl(String url) {
        if (url == null) return "null";
        final int q = url.indexOf('?');
        final String head = q < 0 ? url : url.substring(0, q);
        return head.length() > 96 ? head.substring(0, 96) + "…" : head;
    }

    /** 调试：把拒绝原因也打进日志（CineDuct 的日志级别下默认可见）。 */
    static void logDeny(String op, String slot, String reason) {
        CineDuct.LOGGER.info("[CineDuct] 服务端控制被拒（op={} slot={}）：{}", op, slot, reason);
    }
}

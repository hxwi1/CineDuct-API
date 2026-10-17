package dev.mediafix.bili;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mediafix.CineDuct;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;

/**
 * B 站账号权限：清晰度与音轨能开到哪一档。
 *
 * <p>B 站的授权是按账号状态分层的，超出权限的清晰度/音轨**根本拉不到**（接口不会给直链）：
 * <ul>
 *   <li><b>未登录</b>：最高 720P（qn 64）；</li>
 *   <li><b>已登录普通用户</b>：最高 1080P（qn 80），1080P60 / 4K / 8K 要大会员；</li>
 *   <li><b>大会员</b>：全部开放（含 8K），杜比全景声 / Hi-Res 无损音轨也只有大会员能拿。</li>
 * </ul>
 * 所以设置界面不能只是"看起来能选"——不但要在界面上把越权档位收起来，解析时还要再夹一次，
 * 否则手改配置文件或 ABR 自动升档仍会去要一条账号拿不到的流（结果是解析失败或悄悄降档）。
 *
 * <h2>为什么要缓存 + 异步</h2>
 * 权限要查 {@code nav} 接口（网络），而设置界面重建时是**同步**读权限的（不能卡主线程）。
 * 所以这里做一层缓存：{@link #current()} 同步返回上一次查到的结果，过期了就顺手起个后台线程刷新。
 * 缓存未知时按"已登录普通用户"保守处理（1080P），宁可少开一档，也不给玩家一个点了会失败的选项。
 *
 * <p>缓存失效点：登录成功 / 退出登录（见 {@link BiliLogin#saveCookie}、{@link BiliLogin#logout}）。
 */
public final class BiliEntitlements {

    /** 账号权限档位。 */
    public enum Tier {
        /** 未登录（或 cookie 已失效）。 */
        ANONYMOUS,
        /** 已登录、非大会员。 */
        NORMAL,
        /** 大会员。 */
        VIP
    }

    /** 各档位的清晰度上限（B 站 qn）：720P / 1080P / 8K。 */
    public static final int QN_720P = 64;
    public static final int QN_1080P = 80;
    public static final int QN_MAX = 127;

    /** 权限缓存有效期：过期后重新查，但先用旧值顶着显示。 */
    private static final long CACHE_TTL_MS = 5 * 60_000L;
    /** 查询失败后的退避时间：避免界面每帧都去重试一次失败的网络请求。 */
    private static final long FAIL_RETRY_MS = 60_000L;

    private static final String NAV = "https://api.bilibili.com/x/web-interface/nav";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** 上次查到的权限；null = 还没查成功过。 */
    private static volatile Tier cached;
    private static volatile long cachedAtMs;
    /** 后台刷新是否在进行（去重）。 */
    private static volatile boolean refreshing;
    /** 下次允许重试的时间（失败退避，防止界面每帧都发请求）。 */
    private static volatile long nextRetryAtMs;

    private BiliEntitlements() {
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /**
     * 当前账号权限（同步、只读缓存）。
     *
     * <p>没有 cookie 时直接判未登录，不浪费一次网络请求。
     * 缓存过期时返回旧值并触发一次后台刷新 —— 本次界面可能还是旧上限，
     * 下次打开视频设置时会用上新值（Sodium 每次重建界面都会重新取 allowedValues）。
     */
    public static Tier current() {
        if (!BiliAuth.hasCookie()) {
            return Tier.ANONYMOUS;
        }
        Tier value = cached;
        long now = System.currentTimeMillis();
        if (value != null && now - cachedAtMs < CACHE_TTL_MS) {
            return value;
        }
        refreshAsync();
        // 还没查到过：按"已登录普通用户"保守处理（不会把大会员选项误开给普通用户）
        return value == null ? Tier.NORMAL : value;
    }

    /** 清掉缓存（登录/退出后调用），下次 {@link #current()} 会重新查。 */
    public static void invalidate() {
        cached = null;
        cachedAtMs = 0;
        nextRetryAtMs = 0;
    }

    /** 后台查一次权限；已在查、或刚失败还在退避期时忽略。没有登录态时直接跳过。 */
    public static void refreshAsync() {
        long now = System.currentTimeMillis();
        if (refreshing || now < nextRetryAtMs || !BiliAuth.hasCookie()) {
            return;
        }
        refreshing = true;
        Thread worker = new Thread(BiliEntitlements::fetch, "cineduct-entitlements");
        worker.setDaemon(true);
        worker.start();
    }

    private static void fetch() {
        boolean ok = false;
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(NAV))
                    .header("User-Agent", BiliLogin.UA)
                    .header("Referer", "https://www.bilibili.com/")
                    .timeout(Duration.ofSeconds(15))
                    .GET();
            BiliAuth.withCookie(builder);
            HttpResponse<String> resp = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
            JsonObject data = root.has("data") && root.get("data").isJsonObject()
                    ? root.getAsJsonObject("data") : new JsonObject();

            boolean loggedIn = data.has("isLogin") && data.get("isLogin").getAsBoolean();
            Tier tier;
            if (!loggedIn) {
                tier = Tier.ANONYMOUS;
            } else if (data.has("vipStatus") && data.get("vipStatus").getAsInt() == 1) {
                tier = Tier.VIP;
            } else {
                tier = Tier.NORMAL;
            }

            cached = tier;
            cachedAtMs = System.currentTimeMillis();
            ok = true;
            CineDuct.LOGGER.info("[CineDuct] 账号权限: {}（清晰度上限 {}）", label(tier), maxQnCap(tier));
        } catch (Throwable e) {
            // 查不到不影响播放：按保守上限走，60 秒后再试
            CineDuct.LOGGER.warn("[CineDuct] 查询账号权限失败（本次按保守上限）", e);
        } finally {
            refreshing = false;
            if (!ok) {
                nextRetryAtMs = System.currentTimeMillis() + FAIL_RETRY_MS;
            }
        }
    }

    // ------------------------------------------------------------------
    // 权限 → 上限
    // ------------------------------------------------------------------

    /** 当前账号能拿到的最高清晰度（qn）。 */
    public static int maxQnCap() {
        return maxQnCap(current());
    }

    /** 指定档位能拿到的最高清晰度（qn）。 */
    public static int maxQnCap(Tier tier) {
        return switch (tier) {
            case ANONYMOUS -> QN_720P;
            case NORMAL -> QN_1080P;
            case VIP -> QN_MAX;
        };
    }

    /**
     * 把配置里的清晰度夹到权限内。
     *
     * @param qn {@code -1} = 自动、{@code 0} = 不限、其它 = 具体 qn
     * @return 自动仍是 {@code -1}（ABR 阶梯会单独按权限裁剪）；其余返回不超过权限上限的 qn
     */
    public static int clampQn(int qn) {
        int cap = maxQnCap();
        if (qn < 0) {
            return -1;
        }
        return Math.min(qn == 0 ? cap : qn, cap);
    }

    /**
     * 把音轨偏好夹到权限内：杜比全景声 / Hi-Res 无损只有大会员能拿，
     * 非大会员一律回落到"普通音轨里码率最高的一条"。
     */
    public static String clampAudioPreference(String pref) {
        String normalized = pref == null || pref.isBlank()
                ? "dolby" : pref.trim().toLowerCase(Locale.ROOT);
        return current() == Tier.VIP ? normalized : "best";
    }

    /** 日志用的人话档位名（不需要 i18n：只进日志）。 */
    private static String label(Tier tier) {
        return switch (tier) {
            case ANONYMOUS -> "未登录";
            case NORMAL -> "普通用户";
            case VIP -> "大会员";
        };
    }
}

// SPDX-License-Identifier: MIT
// Copyright (c) 2026 hxwi1
package dev.cineduct.paper;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 查视频时长（服务端自己查，不需要客户端回报，也不需要登录）。
 *
 * <p>为什么必须有它：单向下行意味着"这一条放完了"只能靠{@code 时长}判断，
 * 而插件手里只有一串 URL。让人手填秒数是最差的做法（填错就整段跳过/卡住），
 * 所以这里直接问 B 站的公开接口：
 * <ul>
 *   <li>普通投稿：{@code /x/web-interface/view?bvid=BV1xx…} → {@code data.duration}（秒）；</li>
 *   <li>番剧/影视：{@code /pgc/view/web/season?ep_id=…} → 对应分集的 {@code duration}（**毫秒**）；</li>
 *   <li>直播：没有时长（返回 0，不参与自动推进）。</li>
 * </ul>
 *
 * <p>这些接口**不需要 Cookie**（要的是公开信息），所以插件不需要你的账号。
 * 请求带浏览器 UA 与 Referer：B 站对默认 UA 会回 403（模组那边也踩过同样的坑）。
 *
 * <p>全部走异步 + 内存缓存：查一次就记住，失败不重试（失败就当成"时长未知"，不自动推进）。
 */
final class DurationLookup {

    private static final Pattern BV = Pattern.compile("(BV[0-9A-Za-z]{10})");
    private static final Pattern AV = Pattern.compile("[?&/]av(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern EP = Pattern.compile("[?&/]ep(\\d+)", Pattern.CASE_INSENSITIVE);
    /** B 站的 UA 白名单：默认 Java UA 会被 403。 */
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /** url → 查询结果（0 秒 = 查过但没拿到，别再查）。 */
    private static final ConcurrentHashMap<String, Info> CACHE = new ConcurrentHashMap<>();

    /**
     * 查询结果。
     *
     * @param seconds 内容总时长（秒）；0 = 未知
     * @param premium 是否需要大会员（付费/充电专属）。**很重要**：非大会员只能试看，
     *                实际可播时长远小于总时长，所以调用方要按 {@code premiumPreviewSeconds} 截断
     */
    record Info(int seconds, boolean premium) {
    }

    private final JavaPlugin plugin;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    DurationLookup(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 异步查时长；查到了在主线程回调 {@code (url, seconds)}。
     *
     * <p>缓存命中时**同步**回调（同一个 tick 内就能拿到），避免"第一次慢、后面快"的行为差异。
     */
    void fetch(String url, BiConsumer<String, Info> onMain) {
        if (url == null || url.isBlank()) return;
        final Info cached = CACHE.get(url);
        if (cached != null) {
            if (cached.seconds() > 0) onMain.accept(url, cached);
            return;
        }
        this.plugin.getServer().getScheduler().runTaskAsynchronously(this.plugin, () -> {
            final Info info = query(url);
            if (info != null && info.seconds() > 0) CACHE.put(url, info);
            if (info == null || info.seconds() <= 0) {
                this.plugin.getLogger().info("查不到时长（不自动推进）：" + Screens.safeUrl(url));
                return;
            }
            this.plugin.getServer().getScheduler().runTask(this.plugin, () -> onMain.accept(url, info));
        });
    }

    // =====================================================================

    /** 同步查询（只在异步线程调用）。返回 null = 未知。 */
    private Info query(String url) {
        try {
            final String bv = group(BV, url);
            if (bv != null) return videoDuration("https://api.bilibili.com/x/web-interface/view?bvid=" + bv);
            final String av = group(AV, url);
            if (av != null) return videoDuration("https://api.bilibili.com/x/web-interface/view?aid=" + av);
            final String ep = group(EP, url);
            if (ep != null) return bangumiDuration(ep);
        } catch (Exception e) {
            this.plugin.getLogger().fine("查时长失败: " + e.getMessage());
        }
        return null;
    }

    /** 普通投稿：{@code data.duration} 就是秒；{@code rights.pay} / {@code is_upower_exclusive} 表示付费内容。 */
    private Info videoDuration(String api) throws Exception {
        final JsonObject root = getJson(api);
        if (root == null) return null;
        if (root.has("code") && root.get("code").getAsInt() != 0) return null;
        if (!(root.get("data") instanceof JsonObject data)) return null;
        final int seconds = data.has("duration") ? Math.max(0, data.get("duration").getAsInt()) : 0;
        if (seconds <= 0) return null;
        boolean premium = data.has("is_upower_exclusive") && data.get("is_upower_exclusive").getAsBoolean();
        if (data.get("rights") instanceof JsonObject rights && rights.has("pay")) {
            premium = premium || rights.get("pay").getAsInt() != 0;
        }
        return new Info(seconds, premium);
    }

    /** 番剧：在 {@code result.episodes[]} 里找 {@code id == ep_id}，它的 duration 是**毫秒**。 */
    private Info bangumiDuration(String epId) throws Exception {
        final JsonObject root = getJson("https://api.bilibili.com/pgc/view/web/season?ep_id=" + epId);
        if (root == null) return null;
        if (!(root.get("result") instanceof JsonObject result)) return null;
        // 整季的付费标记：payment 非 0 = 需要大会员/付费
        boolean seasonPremium = result.has("payment") && result.get("payment").getAsInt() != 0;
        if (!(result.get("episodes") instanceof JsonArray episodes)) return null;
        for (JsonElement e : episodes) {
            if (!(e instanceof JsonObject ep)) continue;
            if (!ep.has("id") || !String.valueOf(ep.get("id").getAsInt()).equals(epId)) continue;
            if (!ep.has("duration")) return null;
            final long ms = ep.get("duration").getAsLong();
            final int seconds = ms <= 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, ms / 1000L);
            if (seconds <= 0) return null;
            boolean premium = seasonPremium || (ep.has("pay") && ep.get("pay").getAsInt() != 0);
            return new Info(seconds, premium);
        }
        return null;
    }

    private JsonObject getJson(String api) throws Exception {
        final HttpRequest request = HttpRequest.newBuilder(URI.create(api))
                .timeout(Duration.ofSeconds(8))
                .header("User-Agent", UA)
                .header("Referer", "https://www.bilibili.com/")
                .header("Accept", "application/json")
                .GET()
                .build();
        final HttpResponse<String> response = this.http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) return null;
        final JsonElement root = JsonParser.parseString(response.body());
        return root != null && root.isJsonObject() ? root.getAsJsonObject() : null;
    }

    private static String group(Pattern pattern, String text) {
        final Matcher m = pattern.matcher(text == null ? "" : text);
        return m.find() ? m.group(1) : null;
    }

    /** 手动覆盖（手填秒数时记一下，避免下次又去查）。 */
    static void remember(String url, int seconds) {
        if (url != null && seconds > 0) CACHE.put(url, new Info(seconds, false));
    }

    static Optional<Info> cached(String url) {
        return Optional.ofNullable(CACHE.get(url)).filter(v -> v.seconds() > 0);
    }
}

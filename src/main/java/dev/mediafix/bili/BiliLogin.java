package dev.mediafix.bili;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mediafix.CineDuct;
import net.neoforged.fml.loading.FMLPaths;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Consumer;

/**
 * B 站扫码登录（自己的实现）。
 *
 * <p>为什么必须有：本模组用空壳替掉了 bilibili_media，于是它自带的登录指令也一起没了 ——
 * 而高清直链（4K/杜比）必须带登录态才拿得到。这里按 B 站公开的扫码登录协议自己实现一遍：
 * <ol>
 *   <li>请求二维码：{@code /x/passport-login/web/qrcode/generate} → 拿到
 *       {@code data.url}（二维码内容）与 {@code data.qrcode_key}；</li>
 *   <li>把 {@code data.url} 交给玩家（浏览器打开即等同于"扫码"：本机浏览器若已登录 B 站，
 *       点一下确认即可；也可以用手机扫这个链接生成的二维码）；</li>
 *   <li>轮询 {@code /qrcode/poll} 直到确认（86101 未扫码 / 86090 已扫码待确认 / 86038 已过期 / 0 成功）；</li>
 *   <li>成功后把 Cookie 写进 {@code ~/.bilimedia/cookie.properties}，键名 {@code BILIBILI_COOKIE}
 *       —— 与 bilibili_media 完全一致，所以已有的登录态、以及我们自己的读取侧都不用改。</li>
 * </ol>
 */
public final class BiliLogin {

    /** 请求用的 UA：B 站对没有 UA 的请求会直接拒；{@link BiliEntitlements} 共用同一个。 */
    static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final String GENERATE =
            "https://passport.bilibili.com/x/passport-login/web/qrcode/generate?source=main-fe-header";
    private static final String POLL =
            "https://passport.bilibili.com/x/passport-login/web/qrcode/poll?source=main-fe-header&qrcode_key=";
    private static final String NAV = "https://api.bilibili.com/x/web-interface/nav";
    private static final String COOKIE_KEY = "BILIBILI_COOKIE";

    /** 扫码轮询上限：B 站二维码约 3 分钟失效。 */
    private static final long POLL_TIMEOUT_MS = 180_000L;
    private static final long POLL_INTERVAL_MS = 2_000L;

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static volatile boolean polling;

    private BiliLogin() {
    }

    /**
     * 发起扫码登录。
     * @param report 进度回调（可能在工作线程上被调用，回调内部应自行切到主线程）
     * @return 二维码内容 URL；失败返回 null
     */
    public static String start(Consumer<String> report) {
        return start(report, null, null);
    }

    /**
     * 发起扫码登录，并在这一次扫码会话结束时收到一次通知。
     *
     * <p>为什么需要它：图形界面（{@link BiliLoginScreen}）要在"二维码还能扫"和"这次会话已经结束"
     * 之间切换显示 —— 会话结束可能是登录成功，也可能是过期/超时/被取消。让调用方去匹配
     * 中文提示文本来猜状态太脆，所以这里直接给一个结束回调。
     *
     * @param report       进度回调（工作线程上调用）
     * @param onSessionEnd 轮询结束时回调一次（成功/过期/超时/取消/异常都会走到；工作线程上调用，
     *                     回调内部自行切到主线程）；不需要可传 null
     * @return 二维码内容 URL；失败返回 null（此时不会触发 onSessionEnd，因为会话根本没开始）
     */
    public static String start(Consumer<String> report, Runnable onSessionEnd) {
        return start(report, null, onSessionEnd);
    }

    /**
     * 发起扫码登录，并把关键阶段以枚举形式报给调用方。
     *
     * <p>为什么要多这条通道：{@code report} 给的是**中文人话**（聊天栏指令用），
     * 而图形界面要按玩家当前语言显示 —— 中文句子直接塞进英文界面就破了 i18n。
     * 所以界面侧监听 {@link Phase} 自己出文案，指令侧继续用 {@code report}。
     *
     * @param report       中文进度回调（工作线程上调用，回调内部自行切主线程）；不需要可传 null
     * @param phase        阶段回调（工作线程上调用）；不需要可传 null
     * @param onSessionEnd 轮询结束回调（工作线程上调用）；不需要可传 null
     * @return 二维码内容 URL；失败返回 null（此时只会触发 phase，不会触发 onSessionEnd）
     */
    public static String start(Consumer<String> report, Consumer<Phase> phase, Runnable onSessionEnd) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(GENERATE))
                    .header("User-Agent", UA)
                    .header("Referer", "https://www.bilibili.com/")
                    .timeout(Duration.ofSeconds(15))
                    .GET().build();
            HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
            if (root.get("code").getAsInt() != 0) {
                emitReport(report, "请求二维码失败：code=" + root.get("code"));
                emitPhase(phase, Phase.FAILED);
                return null;
            }
            JsonObject data = root.getAsJsonObject("data");
            String url = data.get("url").getAsString();
            String key = data.get("qrcode_key").getAsString();

            polling = true;
            Thread t = new Thread(() -> poll(key, report, phase, onSessionEnd), "cineduct-bili-login");
            t.setDaemon(true);
            t.start();
            return url;
        } catch (Throwable e) {
            CineDuct.LOGGER.warn("[CineDuct] 请求登录二维码失败", e);
            emitReport(report, "请求二维码失败：" + e.getMessage());
            emitPhase(phase, Phase.FAILED);
            return null;
        }
    }

    private static void poll(String key, Consumer<String> report, Consumer<Phase> phase, Runnable onSessionEnd) {
        long deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS;
        boolean reportedScan = false;
        try {
            while (polling && System.currentTimeMillis() < deadline) {
                Thread.sleep(POLL_INTERVAL_MS);
                HttpRequest req = HttpRequest.newBuilder(URI.create(POLL + key))
                        .header("User-Agent", UA)
                        .header("Referer", "https://www.bilibili.com/")
                        .timeout(Duration.ofSeconds(15))
                        .GET().build();
                HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
                JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
                JsonObject data = root.has("data") && root.get("data").isJsonObject()
                        ? root.getAsJsonObject("data") : new JsonObject();
                int code = data.has("code") ? data.get("code").getAsInt() : -1;
                switch (code) {
                    case 0 -> {
                        // 成功：优先用响应头里的 Set-Cookie，拿不到再退回 data.url 的查询串
                        String cookie = cookieFromHeaders(resp);
                        if (cookie == null && data.has("url")) {
                            cookie = cookieFromRedirect(data.get("url").getAsString());
                        }
                        if (cookie == null || cookie.isBlank()) {
                            emitReport(report, "登录已确认，但没能从响应里取到 Cookie");
                            emitPhase(phase, Phase.FAILED);
                            return;
                        }
                        saveCookie(cookie);
                        emitReport(report, "登录成功！已保存登录态" + describe(cookie));
                        emitPhase(phase, Phase.SUCCESS);
                        return;
                    }
                    case 86090 -> {
                        if (!reportedScan) {
                            reportedScan = true;
                            emitReport(report, "已扫码，请在手机上点击确认…");
                            emitPhase(phase, Phase.SCANNED);
                        }
                    }
                    case 86038 -> {
                        emitReport(report, "二维码已过期，请重新执行 /cineduct login");
                        emitPhase(phase, Phase.EXPIRED);
                        return;
                    }
                    default -> {
                        // 86101 = 未扫码：继续等
                    }
                }
            }
            if (polling) {
                emitReport(report, "扫码等待超时（3 分钟），请重新执行 /cineduct login");
                emitPhase(phase, Phase.TIMEOUT);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable e) {
            CineDuct.LOGGER.warn("[CineDuct] 扫码轮询异常", e);
            emitReport(report, "扫码轮询异常：" + e.getMessage());
            emitPhase(phase, Phase.FAILED);
        } finally {
            polling = false;
            if (onSessionEnd != null) {
                try {
                    onSessionEnd.run();
                } catch (Throwable e) {
                    CineDuct.LOGGER.warn("[CineDuct] 扫码会话结束回调异常", e);
                }
            }
        }
    }

    /**
     * 扫码流程的关键阶段，给图形界面做本地化提示用。
     *
     * <p>指令侧不需要它（{@code /cineduct login} 直接显示中文 {@code report}）。
     */
    public enum Phase {
        /** 已扫码，等手机确认。 */
        SCANNED,
        /** 登录成功，凭证已保存。 */
        SUCCESS,
        /** 二维码过期。 */
        EXPIRED,
        /** 等待超时。 */
        TIMEOUT,
        /** 请求 / 轮询 / 取 Cookie 失败。 */
        FAILED
    }

    /** 中文进度回调可能为 null（界面侧只要 Phase），所以统一走这里。 */
    private static void emitReport(Consumer<String> report, String message) {
        if (report == null) {
            return;
        }
        try {
            report.accept(message);
        } catch (Throwable t) {
            CineDuct.LOGGER.warn("[CineDuct] 扫码进度回调异常", t);
        }
    }

    /** 阶段回调可能为 null（指令侧不关心），异常也不该打断轮询。 */
    private static void emitPhase(Consumer<Phase> phase, Phase value) {
        if (phase == null) {
            return;
        }
        try {
            phase.accept(value);
        } catch (Throwable t) {
            CineDuct.LOGGER.warn("[CineDuct] 扫码阶段回调异常", t);
        }
    }

    /**
     * 把二维码内容画成 PNG（用 ZXing），返回文件路径；失败返回 null。
     * 为什么不在聊天栏里画：MC 的聊天字体不是等宽，用字符拼二维码会变形扫不出来。
     */
    public static Path renderQrPng(String content) {
        try {
            com.google.zxing.common.BitMatrix m = new com.google.zxing.qrcode.QRCodeWriter()
                    .encode(content, com.google.zxing.BarcodeFormat.QR_CODE, 640, 640);
            int w = m.getWidth();
            int h = m.getHeight();
            java.awt.image.BufferedImage img =
                    new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    img.setRGB(x, y, m.get(x, y) ? 0x000000 : 0xFFFFFF);
                }
            }
            Path dir = FMLPaths.GAMEDIR.get().resolve("cineductlogs");
            Files.createDirectories(dir);
            Path out = dir.resolve("login-qr.png");
            javax.imageio.ImageIO.write(img, "png", out.toFile());
            CineDuct.LOGGER.info("[CineDuct] 登录二维码已生成: {}", out);
            return out;
        } catch (Throwable e) {
            CineDuct.LOGGER.warn("[CineDuct] 生成登录二维码失败", e);
            return null;
        }
    }

    /** 取消进行中的轮询。 */
    public static void cancel() {
        polling = false;
    }

    private static String cookieFromHeaders(HttpResponse<String> resp) {
        List<String> pairs = new ArrayList<>();
        for (String h : resp.headers().allValues("set-cookie")) {
            String first = h.split(";", 2)[0].trim();
            if (first.contains("=")) pairs.add(first);
        }
        return pairs.isEmpty() ? null : String.join("; ", pairs);
    }

    /** 从轮询成功时返回的跳转 URL 里取出 Cookie（SESSDATA / bili_jct / DedeUserID …）。 */
    private static String cookieFromRedirect(String url) {
        int q = url.indexOf('?');
        if (q < 0) return null;
        List<String> pairs = new ArrayList<>();
        for (String kv : url.substring(q + 1).split("&")) {
            if (kv.isBlank()) continue;
            String k = kv.split("=", 2)[0];
            if (k.startsWith("SESSDATA") || k.startsWith("bili_jct") || k.startsWith("DedeUserID")
                    || k.startsWith("sid") || k.startsWith("buvid")) {
                pairs.add(kv);
            }
        }
        return pairs.isEmpty() ? null : String.join("; ", pairs);
    }

    /**
     * 保存登录态：写 {@code ~/.bilimedia/cookie.properties}，键名与 bilibili_media 一致，
     * 并把文件权限收紧到仅当前用户可读写。
     */
    public static void saveCookie(String cookie) {
        try {
            Path path = Path.of(System.getProperty("user.home"), ".bilimedia", "cookie.properties");
            Files.createDirectories(path.getParent());
            Properties props = new Properties();
            if (Files.isRegularFile(path)) {
                try (InputStream in = Files.newInputStream(path)) {
                    props.load(in);
                }
            }
            props.setProperty(COOKIE_KEY, cookie);
            try (OutputStream out = Files.newOutputStream(path)) {
                props.store(out, "BiliBili Login (mediafix)");
            }
            try {
                dev.mediafix.config.CookieHarden.harden();
            } catch (Throwable ignored) {
            }
            BiliAuth.invalidate();
            // 登录态变了，清晰度/音轨的权限上限也跟着变（设置界面下次重建就会用新上限）
            BiliEntitlements.invalidate();
            BiliEntitlements.refreshAsync();
            CineDuct.LOGGER.info("[CineDuct] 已保存 B 站登录态 -> {}", path);
        } catch (Throwable e) {
            CineDuct.LOGGER.warn("[CineDuct] 保存登录态失败", e);
        }
    }

    /** 只显示 uid，别把凭证写进日志/聊天。 */
    private static String describe(String cookie) {
        for (String kv : cookie.split(";")) {
            String s = kv.trim();
            if (s.startsWith("DedeUserID=")) return "（uid " + s.substring("DedeUserID=".length()) + "）";
        }
        return "";
    }

    /**
     * 当前账号的信息。
     *
     * @param name    昵称
     * @param uid     UID
     * @param premium 是否大会员（{@code nav.vipStatus == 1}）
     */
    public record Account(String name, String uid, boolean premium) {
    }

    /**
     * 查询当前账号（昵称 / UID / 是否大会员）；未登录或查询失败返回 null。
     *
     * <p>界面侧要拿<b>结构化</b>数据自己按语言拼文案，所以不能再像 {@link #status()} 那样返回
     * 拼好的中文串 —— 那会让英文界面里突然出现「，大会员」。
     */
    public static Account account() {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(NAV))
                    .header("User-Agent", UA)
                    .header("Referer", "https://www.bilibili.com/")
                    .timeout(Duration.ofSeconds(15))
                    .GET();
            BiliAuth.withCookie(b);
            HttpResponse<String> resp = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
            JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
            JsonObject data = root.has("data") && root.get("data").isJsonObject()
                    ? root.getAsJsonObject("data") : new JsonObject();
            boolean isLogin = data.has("isLogin") && data.get("isLogin").getAsBoolean();
            if (!isLogin) return null;
            String name = data.has("uname") ? data.get("uname").getAsString() : "?";
            String mid = data.has("mid") ? data.get("mid").getAsString() : "?";
            boolean premium = data.has("vipStatus") && data.get("vipStatus").getAsInt() == 1;
            return new Account(name, mid, premium);
        } catch (Throwable e) {
            CineDuct.LOGGER.warn("[CineDuct] 查询登录态失败", e);
            return null;
        }
    }

    /** 查询当前登录态（聊天栏指令用）：已登录返回"昵称 (uid …，大会员)"，未登录返回 null。 */
    public static String status() {
        Account account = account();
        if (account == null) {
            return null;
        }
        return account.name() + " (uid " + account.uid() + (account.premium() ? "，大会员" : "") + ")";
    }

    /** 清除登录态（同时清掉配置里手填的那份）。 */
    public static void logout() {
        try {
            dev.mediafix.config.BiliConfig.cookie = "";
            dev.mediafix.config.BiliConfig.save();
            for (Path p : new Path[]{
                    Path.of(System.getProperty("user.home"), ".bilimedia", "cookie.properties"),
                    FMLPaths.GAMEDIR.get().resolve(".bilimedia").resolve("cookie.properties")}) {
                Files.deleteIfExists(p);
            }
            BiliAuth.invalidate();
            // 退出后权限立刻回到"未登录"档（清晰度 720P、音轨只有普通 AAC）
            BiliEntitlements.invalidate();
        } catch (Throwable e) {
            CineDuct.LOGGER.warn("[CineDuct] 清除登录态失败", e);
        }
    }
}

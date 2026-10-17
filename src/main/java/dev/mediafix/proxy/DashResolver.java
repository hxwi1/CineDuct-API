package dev.mediafix.proxy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mediafix.CineDuct;
import dev.mediafix.abr.AbrController;
import dev.mediafix.abr.AbrLadder;
import dev.mediafix.config.FfmpegConfig;
import dev.mediafix.config.StreamConfig;
import dev.mediafix.ffmpeg.FfmpegRuntime;
import dev.mediafix.bili.BiliAuth;
import dev.mediafix.bili.BiliEntitlements;
import dev.mediafix.bili.BiliLive;
import dev.mediafix.bili.BiliUrls;
import dev.mediafix.api.ResolvedStream;
import dev.mediafix.ui.Notice;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.TreeMap;

/**
 * 高清(DASH)缓存播放：用 wbi 签名请求 DASH playurl，从返回的 dash.video / dash.audio
 * 中挑登录账号授权内、配置上限（{@link dev.mediafix.config.StreamConfig#maxQn}）内
 * 最高清晰度（4K/8K/杜比；**HDR 被硬编码排除**，见 {@link #isHdrVideo}），
 * 音视频两条链直接交给自研引擎边下边播
 * （BiliBiliMediaFiles），再以 9095 本地服务器 URL 交给播放器
 * （音频轨由 BasePlayerDiagMixin 的 input-slave 音频桥挂给 VLC）。
 *
 * <p>与前置 durl 链路(qn=116&platform=html5，封顶约1080p)不同，本链路：
 * <ul>
 *   <li>不做 {@code platform=html5}（否则封 1080p）；</li>
 *   <li>请求带 {@code fnval}=DASH 档位，从而能得到 DASH 音视频流；</li>
 *   <li>做 wbi 参数签名（B站 playurl 现在要求），并带登录 cookie 换取高清晰度。</li>
 * </ul>
 * 不落盘：整条「先下载到本地缓存再播」的路径已移除。
 * 调用失败返回 {@code null}，由上层回退到前置原生逻辑，保证始终可播。
 */
public final class DashResolver {

    /** B站 wbi 重排表（固定算法）。 */
    private static final int[] MIXIN_TAB = {
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
            27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
            37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
            22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52};
    private static final String CHROME_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private static final HttpClient client = HttpClient.newHttpClient();

    /** 缓存的 wbi 密钥。 */
    private static String mixinKey;

    private DashResolver() {
    }

    /**
     * 为给定 B站链接解析 DASH 高清流。支持普通视频(BV号)与番剧/大会员内容
     * (bangumi/play/epXXX)。
     * @return 成功返回 Result(本地缓存视频 + audioUrl=本地缓存音频)；失败/无权限返回 null。
     */
    /** 最近一次解析的原始页面 URI（直链过期后据此重新解析）。 */
    private static volatile URI lastResolvedUri;

    /** 最近一次选中的音频轨摘要，供 /cineduct audio 展示（编码/声道/码率）。 */
    private static volatile String lastAudioSummary = "尚未解析";

    public static String lastAudioSummary() {
        return lastAudioSummary;
    }

    /** 重新解析时是否保持当前档位（true = 只换 URL，不重置 ABR 档位）。 */
    private static volatile boolean refreshingSession;

    public static ResolvedStream resolve(URI uri) {
        lastResolvedUri = uri;
        try {
            URI longUri = BiliUrls.expandShortLink(uri);
            // 直播：live.bilibili.com/<房间号> —— 和点播是完全不同的接口与播放模型
            Long liveRoom = BiliUrls.liveRoomId(longUri.toString());
            if (liveRoom != null) {
                return resolveLive(liveRoom);
            }
            // 番剧/大会员内容：ep 链接走 pgc 接口
            Long epId = parseEpId(longUri.toString());
            if (epId != null) {
                return resolveBangumi(epId);
            }
            String bvid = BiliUrls.bvid(longUri.toString());
            if (bvid == null) {
                return null;
            }
            int page = BiliUrls.page(longUri.toString());
            Long cid = fetchCid(bvid, page);
            if (cid == null || cid == 0L) {
                return null;
            }

            JsonObject data = requestDash(bvid, cid);
            if (data == null) {
                return null;
            }
            // 注意：dolby / flac 在 dash 里面（data.dash.dolby），不是 data 的直接子节点
            return finishResolve(data, data.getAsJsonObject("dash"));
        } catch (Throwable t) {
            CineDuct.LOGGER.warn("[CineDuct] DASH 解析失败，回退 durl", t);
            Notice.message("DASH 解析异常，回退原生播放: " + t.getMessage());
            return null;
        }
    }

    /**
     * B 站直播：取 FLV 直链（原画），video 与 audio 都指向同一条流。
     *
     * <p>直播是"音视频复用"的单流（实测 FLV 里 aac + h264 共用一根时间轴），
     * 而我们引擎的两条链本来就可以指向同一个 URI（音频源自己会挑音频轨），
     * 所以这里两条链给同一个直链即可，不需要另找音轨接口。
     *
     * <p>时间轴用系统时间锚定，见 {@link dev.mediafix.engine.MediaEngine} 的直播锚点：
     * 直播流的时间戳是服务器侧的固定偏移且与系统时间 1:1 同步，
     * 于是"原始 PTS + 锚点"就能换算出实时延迟，断流重连也能无缝接上。
     */
    private static ResolvedStream resolveLive(long roomId) {
        BiliLive.Stream stream = BiliLive.resolve(roomId);
        if (stream == null) {
            // 未开播 / 付费直播 / 接口异常
            Notice.message("B站直播解析失败（未开播或需要权限）");
            return null;
        }
        CineDuct.LOGGER.info("[CineDuct] B站直播流式直连: 房间 {} qn={}（音视频同一条流，时间轴用系统时间锚定）",
                roomId, stream.qn());
        if (!refreshingSession) {
            Notice.message("B站直播流式播放: 房间 " + roomId);
        }
        // 直播的音视频复用同一根时间轴（FLV 里 aac + h264）：两条链都指向它，
        // 引擎本来就允许两链同源，音频源会自己去挑音轨。
        return ResolvedStream.live(URI.create(stream.url()), "直播间 " + roomId);
    }

    /**
     * 自己调 {@code x/web-interface/view} 拿 cid（支持分P）。
     * 这是原先由 bilibili_media 的 {@code BilibiliPatch#getCid} 提供的最后一块能力。
     */
    public static Long fetchCid(String bvid, int page) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(
                            "https://api.bilibili.com/x/web-interface/view?bvid=" + bvid))
                    .header("User-Agent", BiliUrls.userAgent())
                    .header("Referer", "https://www.bilibili.com/")
                    .header("Accept", "application/json")
                    .timeout(java.time.Duration.ofSeconds(15));
            BiliAuth.withCookie(builder);
            HttpResponse<String> resp = client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
            JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
            if (root.get("code").getAsInt() != 0) {
                CineDuct.LOGGER.warn("[CineDuct] view 接口返回 code={}", root.get("code").getAsInt());
                return null;
            }
            JsonObject data = root.getAsJsonObject("data");
            JsonArray pages = data.getAsJsonArray("pages");
            Long cid;
            if (pages != null && pages.size() > 1) {
                int idx = Math.max(0, Math.min(page - 1, pages.size() - 1));
                cid = pages.get(idx).getAsJsonObject().get("cid").getAsLong();
            } else {
                cid = data.get("cid").getAsLong();
            }
            CineDuct.LOGGER.info("[CineDuct] 解析 {} 第{}P -> cid={}", bvid, page, cid);
            return cid;
        } catch (Throwable t) {
            CineDuct.LOGGER.warn("[CineDuct] 获取 cid 失败: {}", bvid, t);
            return null;
        }
    }

    /** 从番剧/ep 链接提取 ep 号；非番剧链接返回 null。 */
    public static Long parseEpId(String url) {
        int i = url.indexOf("bangumi/play/ep");
        if (i < 0) {
            return null;
        }
        int start = i + "bangumi/play/ep".length();
        int end = start;
        while (end < url.length() && Character.isDigit(url.charAt(end))) {
            end++;
        }
        if (end == start) {
            return null;
        }
        try {
            return Long.parseLong(url.substring(start, end));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 番剧/大会员内容解析：pgc/player/web/playurl 接口直接吃 ep_id 并返回 DASH
     * （结构与普通视频一致：dash.video / dash.audio / dolby），带登录 Cookie 换取
     * 会员清晰度与杜比。仅返回试看(未登录/无权限)或 DRM 内容时回退原生逻辑。
     */
    private static ResolvedStream resolveBangumi(long epId) throws Exception {
        JsonObject result = null;
        try {
            result = requestPgc(epId);
        } catch (Exception e) {
            CineDuct.LOGGER.warn("[CineDuct] 番剧 playurl 请求失败 ep={}", epId, e);
        }
        if (result == null) {
            return null; // 已在 requestPgc 里给玩家提示
        }
        if (intOf(result, "is_drm") == 1) {
            Notice.message("该番剧为 DRM 内容，无法流式播放，回退原生");
            return null;
        }
        JsonObject dash = result.has("dash") && result.get("dash").isJsonObject()
                ? result.getAsJsonObject("dash") : null;
        if (dash == null) {
            Notice.message("番剧未返回DASH流(试看/无权限)，回退原生播放");
            CineDuct.LOGGER.warn("[CineDuct] 番剧无DASH流 ep={} quality={} preview={}",
                    epId, intOf(result, "quality"), intOf(result, "is_preview"));
            return null;
        }
        CineDuct.LOGGER.info("[CineDuct] 番剧DASH解析: ep={} quality={} preview={}",
                epId, intOf(result, "quality"), intOf(result, "is_preview"));
        return finishResolve(result, dash);
    }

    /** 请求番剧 playurl（pgc 接口，无需 wbi 签名），返回 result JSON；试看态返回 null。 */
    private static JsonObject requestPgc(long epId) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("https://api.bilibili.com/pgc/player/web/playurl"
                        + "?ep_id=" + epId + "&fnval=4048&fourk=1&platform=pc&qn=0"))
                .header("User-Agent", CHROME_UA)
                .header("Referer", "https://www.bilibili.com/")
                .header("Origin", "https://www.bilibili.com")
                .header("Accept", "application/json");
        BiliAuth.withCookie(builder);
        HttpResponse<String> resp = client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
        JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
        if (root.get("code").getAsInt() == 0 && root.has("result")) {
            JsonObject result = root.getAsJsonObject("result");
            if (intOf(result, "is_preview") == 1) {
                Notice.message("番剧仅可试看(登录态/大会员校验未过)，回退原生播放");
                CineDuct.LOGGER.warn("[CineDuct] 番剧返回试看流 ep={} has_paid={}",
                        epId, intOf(result, "has_paid"));
                return null;
            }
            return result;
        }
        CineDuct.LOGGER.warn("[CineDuct] 番剧 playurl 返回异常 code={}: {}",
                root.has("code") ? root.get("code").getAsInt() : -1,
                resp.body().length() > 300 ? resp.body().substring(0, 300) : resp.body());
        return null;
    }

    /**
     * 选流 + 构造 Result 的公共流程（普通视频与番剧共用）。
     *
     * <p><b>只做流式直连、绝不落盘</b>：B 站的 DASH 没有 MPD 清单，就是两条独立 fMP4 直链。
     * 以前这里还有一条"先下载到本地缓存再播"的路径，已经整条移除 ——
     * 把 B 站视频下载到本地属于灰色地带，本模组只做边下边播的流式播放。
     *
     * @param dashObj DASH 对象（dash.video / dash.audio）
     * @param data  playurl 的 data（pgc 接口传 result）
     * @param dashObj DASH 对象（dash.video / dash.audio / dash.dolby / dash.flac）
     */
    private static ResolvedStream finishResolve(JsonObject data, JsonObject dashObj) throws Exception {
        JsonElement videoEl = pickBest(dashObj == null ? null : dashObj.getAsJsonArray("video"));
        AudioPick pick = pickAudioTrack(data, dashObj);
        JsonObject audio = pick == null ? null : pick.obj();
        boolean audioFromDolby = pick != null && pick.kind().equals("dolby");
        if (videoEl == null || videoEl.isJsonNull() || !videoEl.isJsonObject()) {
            Notice.message("DASH 无可用视频流，回退原生播放");
            return null;
        }
        JsonObject video = videoEl.getAsJsonObject();
        if (audio == null) {
            audio = new JsonObject(); // 无音轨时保持为空对象，避免下方判空
        }

        String videoCodec = str(video, "codecs");
        int qualityId = intOf(video, "id");
        CineDuct.LOGGER.info("[CineDuct] 选中视频流: qn={}({}) {}x{} {}kbps codec={}",
                qualityId, describeQn(qualityId), intOf(video, "width"), intOf(video, "height"),
                longOf(video, "bandwidth") / 1000, videoCodec);
        String videoUrl = firstUrl(video);
        if (videoUrl == null) {
            Notice.message("DASH 视频直链缺失，回退原生播放");
            return null;
        }
        String audioCodec = str(audio, "codecs");
        String audioUrl = firstUrl(audio);
        // 音频【永远取最高音质】：优先杜比全景声块，否则 dash.audio 里码率最高的一条。
        // 它不属于 ABR —— 清晰度自适应只作用于视频链，音频不参与换挡。
        if (audioUrl != null) {
            CineDuct.LOGGER.info("[CineDuct] 音频链: {} {}kbps (id={}{})",
                    pick == null ? "普通音轨" : pick.label(),
                    audioBandwidth(audio) / 1000, intOf(audio, "id"),
                    audioCodec.isEmpty() ? "" : ", " + audioCodec);
            lastAudioSummary = (pick == null ? "普通音轨" : pick.label()) + " "
                    + intOf(audio, "id") + " " + (audioCodec.isEmpty() ? "?" : audioCodec)
                    + " " + (audioBandwidth(audio) / 1000) + "kbps";
        }
        if (audioUrl == null && dashObj != null) {
            // 诊断：打出 dash 结构与首条音轨字段名，定位接口字段差异
            JsonArray audioArr = dashObj.getAsJsonArray("audio");
            String firstAudioKeys = "-";
            if (audioArr != null && audioArr.size() > 0 && audioArr.get(0).isJsonObject()) {
                firstAudioKeys = String.valueOf(audioArr.get(0).getAsJsonObject().keySet());
            }
            CineDuct.LOGGER.warn("[CineDuct] 未找到独立音频轨: dash.audio={}条 首条音轨键={} dash键={}",
                    audioArr == null ? "缺失" : String.valueOf(audioArr.size()),
                    firstAudioKeys, dashObj.keySet());
        }

        // ---- 流式直连（不下载）----
        // 视频交给 FFmpeg 解、音频交给自研引擎，两边各自边下边播。
        // 要求：开关打开 + FFmpeg 已启用且原生库可用 + 音频链存在。
        if (StreamConfig.dashStreaming && audioUrl != null && FfmpegRuntime.available()) {
            CineDuct.LOGGER.info("[CineDuct] DASH 流式直连: 音视频两条链各自边下边播 (qn={}, {})",
                    qualityId, describeQn(qualityId));
            if (!refreshingSession) Notice.message("DASH 流式直连(不下载): " + describeQn(qualityId));
            return new ResolvedStream(URI.create(videoUrl), URI.create(audioUrl), false, null,
                    describeQn(qualityId), -1L);
        }

        /*
         * 走到这里说明流式直连不可用：要么 /cineduct-stream streaming 关着，
         * 要么 FFmpeg 引擎/原生库不可用，要么这条视频没有独立音频链。
         *
         * 以前这里会退到"先下载到本地缓存再播"，那条路径已整条移除 ——
         * 把 B 站视频下载到本地属于灰色地带，本模组只做流式播放。
         * 所以这里返回 null，由调用方（BiliSourceResolver）回退成"当直链试一把"。
         */
        CineDuct.LOGGER.warn("[CineDuct] DASH 流式直连不可用（开关={} 原生库={} 有音频链={}），无法解析",
                StreamConfig.dashStreaming, FfmpegRuntime.available(), audioUrl != null);
        return null;
    }

    /** 请求 DASH playurl 并返回其中的 data JSON；签名被风控(-412/v_voucher)时刷新 wbi key 重试一次。 */
    private static JsonObject requestDash(String bvid, long cid) throws Exception {
        JsonObject data = tryRequestDash(bvid, cid);
        if (data == null) {
            // wbi key 每日更替，跨天缓存的 key 会失效：刷新后重试一次
            CineDuct.LOGGER.info("[CineDuct] playurl 请求失败，刷新 wbi key 后重试");
            mixinKey = null;
            data = tryRequestDash(bvid, cid);
        }
        return data;
    }

    private static JsonObject tryRequestDash(String bvid, long cid) throws Exception {
        TreeMap<String, String> params = new TreeMap<>();
        params.put("bvid", bvid);
        params.put("cid", String.valueOf(cid));
        params.put("qn", "0");
        params.put("fnver", "0");
        params.put("fnval", "4048"); // DASH + 4K/HDR/Dolby 档位
        params.put("fourk", "1");
        params.put("platform", "pc");

        signWbi(params);
        // wbi 密钥只参与签名，最终请求用排好序且 URL 编码后的参数
        StringBuilder sb = new StringBuilder("https://api.bilibili.com/x/player/wbi/playurl?");
        for (var e : params.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append('&');
        }
        sb.setLength(sb.length() - 1);
        String url = sb.toString();

        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(url))
                .header("User-Agent", CHROME_UA)
                .header("Referer", "https://www.bilibili.com/")
                .header("Origin", "https://www.bilibili.com")
                .header("Accept", "application/json");
        BiliAuth.withCookie(builder);
        HttpResponse<String> resp = client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
        JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
        if (root.get("code").getAsInt() == 0 && root.has("data")) {
            return root.getAsJsonObject("data");
        }
        // -412 / v_voucher = wbi 签名被风控拒绝（key 过期等）
        CineDuct.LOGGER.warn("[CineDuct] playurl 返回异常 code={}: {}", root.get("code").getAsInt(),
                resp.body().length() > 500 ? resp.body().substring(0, 500) : resp.body());
        return null;
    }

    /** CDN 直链过期后的重解析：重新请求 playurl，按与首次相同的规则挑视频/音频流 URL。 */
    private static String refreshUrl(String bvid, long cid, boolean audio) {
        try {
            JsonObject data = requestDash(bvid, cid);
            if (data == null) {
                return null;
            }
            JsonObject dashObj = data.getAsJsonObject("dash");
            if (dashObj == null) {
                return null;
            }
            JsonElement el;
            if (audio) {
                // 与首次选取严格同一套规则（否则直链过期换链后音频会悄悄降级回 AAC）
                AudioPick pick = pickAudioTrack(data, dashObj);
                el = pick == null ? null : pick.obj();
            } else {
                el = pickBest(dashObj.getAsJsonArray("video"));
            }
            if (el == null || !el.isJsonObject()) {
                return null;
            }
            return firstUrl(el.getAsJsonObject());
        } catch (Exception e) {
            CineDuct.LOGGER.warn("[CineDuct] 重解析直链失败 bvid={} cid={}", bvid, cid, e);
            return null;
        }
    }

    /** wbi 签名：加 wts，按 key 排序后对(编码拼接+mixinKey)取 MD5，追加 w_rid。 */
    private static String signWbi(TreeMap<String, String> params) throws Exception {
        long wts = System.currentTimeMillis() / 1000;
        params.put("wts", String.valueOf(wts));
        String key = getMixinKey();

        StringBuilder raw = new StringBuilder();
        for (var e : params.entrySet()) {
            raw.append(e.getKey()).append('=').append(encode(e.getValue())).append('&');
        }
        raw.setLength(raw.length() - 1);

        String toHash = raw + key;
        String wrid = md5Hex(toHash);
        // 把已编码的值回填（供最终 URL 使用），并追加 w_rid
        // params 里当前已是原始值，这里统一改成编码值以组成最终 URL
        for (var e : params.entrySet()) {
            e.setValue(encode(e.getValue()));
        }
        params.put("w_rid", wrid);
        return wrid;
    }

    /**
     * 从直链里取出 CDN 给的有效期（毫秒时间戳，0 = 未知）。
     *
     * <p>点播直链带 {@code deadline=<秒>}，直播直链带 {@code expires=<秒>}（离线实测确认是复数形式），
     * 含义相同 —— 三个都认，直播才能在到期前主动换链（长直播必然撞上 URL 过期）。
     */
    public static long urlDeadlineMs(String url) {
        if (url == null) return 0L;
        for (String key : new String[]{"deadline=", "expires=", "expire="}) {
            int i = url.indexOf(key);
            if (i < 0) continue;
            int start = i + key.length();
            int end = start;
            while (end < url.length() && Character.isDigit(url.charAt(end))) end++;
            if (end == start) continue;
            try {
                long sec = Long.parseLong(url.substring(start, end));
                if (sec > 0) return sec * 1000L;
            } catch (Throwable ignored) {
                // 试下一个 key
            }
        }
        return 0L;
    }

    /**
     * 超长会话续期：重新解析当前媒体（保持当前清晰度档位），拿到新鲜的两条直链。
     *
     * <p>为什么需要：B 站直链带 {@code deadline}，通常两小时左右失效；失效后拉流会 403，
     * 表现为画面/声音突然停住。这里在到期前主动换链（无缝换链见 MediaEngine.reloadVideoSource），
     * 播放不会被察觉地中断。
     *
     * @return {@code {videoUrl, audioUrl}}；失败返回 null
     */
    public static String[] reResolveUrls() {
        URI orig = lastResolvedUri;
        if (orig == null) return null;
        try {
            refreshingSession = true;
            ResolvedStream result = resolve(orig);
            String video = result != null && result.video() != null ? result.video().toString() : null;
            String audio = result != null && result.audio() != null ? result.audio().toString() : null;
            if (video == null || audio == null) {
                CineDuct.LOGGER.warn("[CineDuct] 直链续期：重新解析没拿到两条链（video={} audio={}）",
                        video != null, audio != null);
                return null;
            }
            CineDuct.LOGGER.info("[CineDuct] 直链续期：已拿到新鲜的两条链");
            return new String[]{video, audio};
        } catch (Throwable t) {
            CineDuct.LOGGER.warn("[CineDuct] 直链续期失败", t);
            return null;
        } finally {
            refreshingSession = false;
        }
    }

    /** B站 wbi 参数编码：URLEncoder 后转 %20，B站按此计算与实际发送一致。 */
    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("%7E", "~");
    }

    private static String getMixinKey() throws Exception {
        if (mixinKey != null) {
            return mixinKey;
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("https://api.bilibili.com/x/web-interface/nav"))
                .header("User-Agent", CHROME_UA)
                .header("Referer", "https://www.bilibili.com/");
        BiliAuth.withCookie(builder);
        HttpResponse<String> resp = client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
        JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
        // nav 未登录也能返回 wbi_img
        JsonObject wbi = root.getAsJsonObject("data").getAsJsonObject("wbi_img");
        String imgKey = baseName(wbi.get("img_url").getAsString());
        String subKey = baseName(wbi.get("sub_url").getAsString());
        mixinKey = mixinKey(imgKey, subKey);
        CineDuct.LOGGER.debug("[CineDuct] wbi mixinKey 已缓存");
        return mixinKey;
    }

    /** 从 URL 取文件名去扩展名。 */
    private static String baseName(String url) {
        int slash = url.lastIndexOf('/');
        String file = slash >= 0 ? url.substring(slash + 1) : url;
        int dot = file.lastIndexOf('.');
        return dot > 0 ? file.substring(0, dot) : file;
    }

    /** 按 B站公开的重排表生成 32 位 mixinKey。 */
    private static String mixinKey(String imgKey, String subKey) {
        String s = imgKey + subKey;
        StringBuilder out = new StringBuilder();
        for (int i : MIXIN_TAB) {
            out.append(s.charAt(i));
        }
        return out.substring(0, 32);
    }

    private static String md5Hex(String input) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    /**
     * 从 dash.video 里挑一条流。
     *
     * <p>{@code StreamConfig.maxQn == -1}（即 /cineduct-stream quality auto）时走 <b>ABR</b>：
     * 把数组里所有清晰度收成阶梯存进 {@link AbrLadder}，并按"保守起步"的原则选初始档
     * （有历史测速就按 带宽×0.7 挑，没有就从中档起步）—— 之后由 {@link AbrController}
     * 依据实测带宽与缓冲水位升降档。
     * 其余情况仍按固定上限挑最高档（手动指定清晰度时不去猜）。
     */
    private static JsonElement pickBest(JsonArray videos) {
        if (videos == null || videos.size() == 0) {
            return null;
        }
        /*
         * ★ 唯一的选流入口：先**硬编码剔掉 HDR/杜比视界**，下面两条路径（手动档位 / ABR）都吃这份结果。
         *
         * <p>为什么做成硬编码而不是配置项：HDR 不是"画质更高"那么简单的开关 —— 它要求整条
         * 色彩链路（真实色彩空间声明 → 驱动/CPU 色调映射 → 输出 BT.709）都对，任何一环错位
         * 的表现都是"画面发灰、高光压死"，而这类问题只能靠专业播放器逐帧比对去查。
         * 作者明确不处理 HDR，所以直接**永不主动选**它：宁可少一档清晰度，也不要这条链路。
         *
         * <p>只有在整条视频**只提供 HDR** 时才不得不退回选它（并明确打日志）：那种情况下
         * "什么都不放"比"放一条 HDR"更糟，画面由子进程已有的色调映射兜着。
         */
        JsonArray sdr = withoutHdr(videos);
        final boolean hdrOnly = sdr.size() == 0;
        if (hdrOnly) {
            CineDuct.LOGGER.warn("[CineDuct] 这条视频只提供 HDR/杜比视界流（共 {} 条），"
                    + "没有可用的 SDR 档位：已退回选它（本模组不处理 HDR，画面靠子进程的色调映射兜着；"
                    + "想彻底避开请换片源）", videos.size());
        } else if (sdr.size() < videos.size()) {
            CineDuct.LOGGER.info("[CineDuct] 已排除 {} 条 HDR/杜比视界流（本模组硬编码不拉 HDR），"
                    + "候选 {} → {} 档", videos.size() - sdr.size(), videos.size(), sdr.size());
        }
        final JsonArray usable = hdrOnly ? videos : sdr;
        if (StreamConfig.maxQn < 0) {
            return pickAuto(usable);
        }
        /*
         * 账号权限夹紧：配置里可能存着超出当前账号的档位（大会员设了 4K 之后退出登录），
         * 也可能被手改过 —— 这里再夹一次，别去要一条账号根本拿不到的流。
         * 0（不限）按"账号内最高"理解，同样夹到权限上限。
         */
        int configured = StreamConfig.maxQn > 0 ? StreamConfig.maxQn : 0;
        int cap = BiliEntitlements.clampQn(configured);
        if (configured > 0 && cap < configured) {
            CineDuct.LOGGER.info("[CineDuct] 账号权限限制：清晰度上限 qn{} → qn{}（不会请求越权档位）",
                    configured, cap);
        }
        JsonElement best = null;
        int bestId = -1;
        int bestRank = Integer.MAX_VALUE;
        for (JsonElement e : usable) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            int id = intOf(o, "id");
            if (id > cap) continue; // 超过用户设定的清晰度上限
            int rank = codecRank(o);
            // 清晰度优先；同一清晰度内按编码优先级挑（默认避开 AV1 —— 4K AV1 只能软解，跑不动）
            if (id > bestId || (id == bestId && rank < bestRank)) {
                bestId = id;
                bestRank = rank;
                best = e;
            }
        }
        /*
         * 显式选了 8K 就把"代价"说清楚。
         *
         * <p>B 站的 8K（qn=127）基本只有 AV1 一种编码，而 {@code avoidAv1} 默认是开的
         * （因为多数显卡没有 AV1 硬解）。但挑选规则是**清晰度优先**，所以显式指定 8K 时
         * 那条 AV1 流照样会被选中 —— 这是调用方的明确选择，不该被静默降级；
         * 只是把风险打在日志里：没有 AV1 硬解就会退回软解，8K 软解跑不动。
         */
        if (best != null && best.isJsonObject()) {
            JsonObject chosen = best.getAsJsonObject();
            if (bestId == 127 && isAv1(chosen) && StreamConfig.avoidAv1) {
                CineDuct.LOGGER.warn("[CineDuct] 8K 只有 AV1 一种编码，已按你选的清晰度选中它："
                        + "需要显卡 AV1 硬解（RTX 40/50、Arc 等），没有的话会退回软解，8K 软解跑不动"
                        + "（想避免就选 4k，或把 cineduct-stream.json 的 avoidAv1 保持默认）");
            }
        }
        return best;
    }

    /** ABR：收集阶梯 + 保守选初始档。 */
    private static JsonElement pickAuto(JsonArray videos) {
        /*
         * 同一个清晰度会有多条编码（H.264 / HEVC / AV1），必须先去重再建阶梯：
         * 曾经把 18 条全当独立档位放进阶梯（日志里出现 qn16/qn16/qn32/qn32…），
         * 结果"档位"名不副实、换档频繁，而且某个 4K 档恰好挑中 AV1 —— 显卡没 AV1 硬解，
         * 4K AV1 只能软解，画面掉到 3~4fps。这里按清晰度归并，同一清晰度内按编码优先级取一条。
         */
        java.util.List<AbrLadder.Candidate> list = new java.util.ArrayList<>();
        java.util.Map<Integer, JsonObject> byQn = new java.util.LinkedHashMap<>();
        java.util.Map<Integer, Integer> rankByQn = new java.util.HashMap<>();
        for (JsonElement e : videos) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            String url = firstUrl(o);
            if (url == null) continue;
            int id = intOf(o, "id");
            long bw = longOf(o, "bandwidth");
            if (bw <= 0) continue;
            Integer prevRank = rankByQn.get(id);
            int rank = codecRank(o);
            if (prevRank != null && prevRank <= rank) continue;   // 已有更优编码
            rankByQn.put(id, rank);
            byQn.put(id, o);
        }
        int droppedForPermission = 0;
        for (var en : byQn.entrySet()) {
            /*
             * 账号权限夹紧：超出权限的档位不能进阶梯。
             * 否则 ABR 自动升档时会去换一条账号拿不到的 url —— 表现为换档失败/反复重连。
             */
            if (en.getKey() > BiliEntitlements.maxQnCap()) {
                droppedForPermission++;
                continue;
            }
            JsonObject o = en.getValue();
            String url = firstUrl(o);
            int w = intOf(o, "width");
            int h = intOf(o, "height");
            list.add(new AbrLadder.Candidate(en.getKey(), longOf(o, "bandwidth"), url,
                    describeQn(en.getKey()) + "(" + codecShort(o) + ")", w, h));
        }
        if (droppedForPermission > 0) {
            CineDuct.LOGGER.info("[CineDuct] 账号权限限制：ABR 阶梯裁掉 {} 个越权档位（上限 qn{}）",
                    droppedForPermission, BiliEntitlements.maxQnCap());
        }
        if (list.isEmpty()) return null;
        list.sort(java.util.Comparator.comparingLong(AbrLadder.Candidate::bandwidthBps));

        // 起步档：续期刷新时保持当前档位（只换 URL，不动 ABR 状态）；
        // 首次解析则保守起步 —— 有历史测速按 0.7 挑，否则从中间档开始靠 ABR 爬。
        long est = AbrController.estimateBps();
        int start = -1;
        AbrLadder.Candidate keep = refreshingSession ? AbrLadder.current() : null;
        if (keep != null) {
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).qn() == keep.qn()) {
                    start = i;
                    break;
                }
            }
        }
        if (start < 0) {
            start = est > 0 ? AbrLadder.pickForBandwidth(est, 0.7, 0, list.size() - 1)
                    : list.size() / 2;
        }
        AbrLadder.Candidate chosen = list.get(start);
        AbrLadder.set(list, start);
        CineDuct.LOGGER.info("[CineDuct] ABR 模式：{} 档可选 {}，起步 {}（{}kbps {}）{}",
                list.size(),
                list.stream().map(AbrLadder.Candidate::label).collect(java.util.stream.Collectors.joining("/")),
                chosen.label(), chosen.bandwidthBps() / 1000, chosen.resolution(),
                est > 0 ? "，历史带宽估计 " + (est / 1000) + "kbps" : "，暂无带宽估计");
        // 顺手点出"标称 4K 但分辨率不到 2160p"的假 4K，免得又靠肉眼看
        for (AbrLadder.Candidate c : list) {
            if (c.qn() >= 120 && c.height() > 0 && c.height() < 2000) {
                CineDuct.LOGGER.warn("[CineDuct] 注意：这条流标称 {} 但真实分辨率只有 {}（假 4K）",
                        c.label(), c.resolution());
            }
        }
        return byQn.get(chosen.qn());
    }

    // 本地缓存/下载相关的方法（isCached / downloadWithRetry / download）已整条移除：
    // 本模组只做流式播放，不把 B 站视频落到本地。

    /**
     * 编码优先级（越小越优先）：H.264/其它 1 < HEVC 2 < AV1 3。
     * AV1 排在最后是因为多数显卡无法硬解它，4K 软解根本跑不动；
     * {@link StreamConfig#avoidAv1} 打开时 AV1 直接排除。
     */
    private static int codecRank(JsonObject o) {
        boolean av1 = isAv1(o);
        if (av1) return StreamConfig.avoidAv1 ? Integer.MAX_VALUE : 3;
        int cid = intOf(o, "codecid");
        String codec = str(o, "codecs").toLowerCase();
        if (cid == 12 || codec.startsWith("hev") || codec.startsWith("hvc")) return 2;
        return 1;
    }

    private static boolean isAv1(JsonObject o) {
        if (intOf(o, "codecid") == 13) return true;
        return str(o, "codecs").toLowerCase().startsWith("av01");
    }

    private static String codecShort(JsonObject o) {
        int cid = intOf(o, "codecid");
        if (cid == 13 || isAv1(o)) return "av1";
        if (cid == 12) return "hevc";
        if (cid == 7) return "h264";
        String c = str(o, "codecs");
        if (c.isEmpty()) return "?";
        int dot = c.indexOf('.');
        return dot > 0 ? c.substring(0, dot) : c;
    }

    /** 判断是否为杜比视界视频流（id=126 或 codec 以 dvh 开头）。 */
    private static boolean isDolbyVision(JsonObject o) {
        if (intOf(o, "id") == 126) {
            return true;
        }
        String codec = str(o, "codecs").toLowerCase();
        return codec.startsWith("dvh") || codec.contains("dolby_v");
    }

    /**
     * 判断是否为 HDR 系视频流（HDR10/HLG 的 qn=125，或杜比视界）。
     *
     * <p>★ 本模组的**硬编码策略**：HDR 与杜比视界一律不主动拉（见 {@link #pickBest} 的 ★ 说明）。
     * B 站给 HDR 的标记就是清晰度 id=125（杜比视界是 126），没有别的字段可判断；
     * 这里再兜一层 codec 名（{@code dvh}/{@code dolby_v}、{@code hdr}），防止接口改字段。
     */
    private static boolean isHdrVideo(JsonObject o) {
        if (intOf(o, "id") == 125) {
            return true;
        }
        if (isDolbyVision(o)) {
            return true;
        }
        String codec = str(o, "codecs").toLowerCase();
        return codec.contains("hdr") || codec.startsWith("hlg");
    }

    /**
     * 剔掉 HDR/杜比视界的那几条流。
     *
     * <p>返回值**可能是空数组**（这条视频只有 HDR）—— 调用方必须处理这种情况：
     * 空不等于"没得播"，只等于"没有 SDR 档位"（见 {@link #pickBest}）。
     */
    private static JsonArray withoutHdr(JsonArray videos) {
        JsonArray out = new JsonArray();
        if (videos == null) {
            return out;
        }
        for (JsonElement e : videos) {
            if (!e.isJsonObject()) continue;
            if (isHdrVideo(e.getAsJsonObject())) continue;
            out.add(e);
        }
        return out;
    }

    /** 选中的音频轨：对象 + 来源类别。 */
    private record AudioPick(JsonObject obj, String kind, String label) {
    }

    private static String kindLabel(String kind) {
        return switch (kind) {
            case "dolby" -> "杜比全景声";
            case "flac" -> "Hi-Res 无损";
            default -> "普通音轨";
        };
    }

    /**
     * 取 {@code dolby} / {@code flac} 这两个专用块里的音轨数组。
     *
     * <p><b>层级是关键</b>：B 站 playurl 把这两个块放在 {@code data.dash} 里面
     * （即 {@code data.dash.dolby.audio}），{@code data} 的直接子节点里<b>没有</b> dolby。
     * 早先按 {@code data.dolby} 取永远拿到 null，于是杜比全景声一直选不中、悄悄回退到
     * 192K 的 AAC —— 这就是"没有杜比全景声"的真正原因。pgc(番剧) 两种层级都出现过，所以两层都查。
     */
    private static JsonArray specialAudio(JsonObject data, JsonObject dash, String key) {
        for (JsonObject o : new JsonObject[]{dash, data}) {
            if (o == null || !o.has(key) || !o.get(key).isJsonObject()) {
                continue;
            }
            JsonObject block = o.getAsJsonObject(key);
            if (block.has("audio") && block.get("audio").isJsonArray()
                    && block.getAsJsonArray("audio").size() > 0) {
                return block.getAsJsonArray("audio");
            }
        }
        return null;
    }

    /** 专用块的存在状态（诊断用）：无 / 有音频N条 / 有但audio为空 / 有但audio=null。 */
    private static String specialState(JsonObject data, JsonObject dash, String key) {
        for (JsonObject o : new JsonObject[]{dash, data}) {
            if (o == null || !o.has(key) || !o.get(key).isJsonObject()) {
                continue;
            }
            JsonObject block = o.getAsJsonObject(key);
            if (!block.has("audio")) {
                return "有(无audio字段)";
            }
            JsonElement a = block.get("audio");
            if (a.isJsonArray()) {
                return "有(" + a.getAsJsonArray().size() + "条)";
            }
            if (a.isJsonNull()) {
                return "有(audio=null，账号/视频无此档)";
            }
            return "有(audio非数组)";
        }
        return "无";
    }

    /**
     * 按 {@link StreamConfig#audioPreference} 选音频轨。
     * 默认顺序：杜比全景声 → Hi-Res 无损 → 普通音轨(码率最高)。
     * 任何一档缺失都自动回退，保证始终有声音。
     */
    private static AudioPick pickAudioTrack(JsonObject data, JsonObject dash) {
        JsonArray dolby = specialAudio(data, dash, "dolby");
        JsonArray flac = specialAudio(data, dash, "flac");
        JsonArray normal = dash == null ? null : dash.getAsJsonArray("audio");

        // 账号权限夹紧：杜比全景声 / Hi-Res 无损只有大会员能拿，非大会员一律按普通音轨处理
        // （只放宽到"普通音轨里码率最高的一条"，不会因为偏好写成 dolby 就去请求越权音轨）
        String pref = BiliEntitlements.clampAudioPreference(StreamConfig.audioPreference);
        String[] order = switch (pref) {
            case "best" -> new String[]{"normal", "dolby", "flac"};
            case "hires" -> new String[]{"flac", "dolby", "normal"};
            default -> new String[]{"dolby", "flac", "normal"};
        };

        AudioPick pick = null;
        for (String kind : order) {
            JsonArray arr = switch (kind) {
                case "dolby" -> dolby;
                case "flac" -> flac;
                default -> normal;
            };
            JsonElement e = pickBestAudio(arr);
            if (e != null && e.isJsonObject()) {
                pick = new AudioPick(e.getAsJsonObject(), kind, kindLabel(kind));
                break;
            }
        }
        CineDuct.LOGGER.info("[CineDuct] 音频块: 杜比={} Hi-Res={} 普通={}条 | 偏好={} → {}",
                specialState(data, dash, "dolby"), specialState(data, dash, "flac"),
                normal == null ? 0 : normal.size(), pref,
                pick == null ? "无可用音轨" : pick.label());
        return pick;
    }

    /** 从 dash.audio 里挑 bandWidth 最高的那条。 */
    private static JsonElement pickBestAudio(JsonArray audios) {
        if (audios == null || audios.size() == 0) {
            return null;
        }
        JsonElement best = null;
        int bestBw = -1;
        for (JsonElement e : audios) {
            if (!e.isJsonObject()) continue;
            // B站 DASH JSON 码率字段是小写 "bandwidth"，兼容两种写法
            JsonObject o = e.getAsJsonObject();
            int bw = intOf(o, "bandwidth");
            if (bw == 0) {
                bw = intOf(o, "bandWidth");
            }
            if (bw > bestBw) {
                bestBw = bw;
                best = e;
            }
        }
        return best;
    }

    /** 取流地址：兼容 baseUrl/base_url(字符串) 与 backupUrl/backup_url(数组) 四种命名
     *  （普通视频接口用驼峰，番剧 pgc 接口两种并存）。 */
    private static String firstUrl(JsonObject o) {
        for (String key : new String[]{"baseUrl", "base_url"}) {
            if (o.has(key) && o.get(key).isJsonPrimitive()) {
                return o.get(key).getAsString();
            }
        }
        for (String key : new String[]{"backupUrl", "backup_url"}) {
            if (o.has(key) && o.get(key).isJsonArray()) {
                JsonArray arr = o.getAsJsonArray(key);
                if (arr.size() > 0 && arr.get(0).isJsonPrimitive()) {
                    return arr.get(0).getAsString();
                }
            }
        }
        return null;
    }

    /** 清晰度 id -> 人类可读。 */
    private static String describeQn(int id) {
        return switch (id) {
            case 127 -> "8K";
            case 126 -> "杜比视界";
            case 125 -> "HDR";
            case 120 -> "4K";
            case 112 -> "1080P60";
            case 116 -> "1080P60+";
            case 80 -> "1080P";
            case 74 -> "720P60";
            case 64 -> "720P";
            default -> "qn" + id;
        };
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static long longOf(JsonObject o, String key) {
        try {
            return o.has(key) ? o.get(key).getAsLong() : 0L;
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static int intOf(JsonObject o, String key) {
        try {
            return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsInt() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 音频码率：B站字段为小写 bandwidth，兼容 bandWidth。 */
    private static int audioBandwidth(JsonObject o) {
        int bw = intOf(o, "bandwidth");
        return bw != 0 ? bw : intOf(o, "bandWidth");
    }
}
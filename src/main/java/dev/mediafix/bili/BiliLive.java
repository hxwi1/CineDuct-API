package dev.mediafix.bili;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mediafix.CineDuct;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * B 站直播解析（自研）。
 *
 * <p><b>直播和点播是两套接口</b>：点播走 {@code x/player/wbi/playurl}，返回 video / audio 两条独立链；
 * 直播走 {@code xlive/web-room/v2/index/getRoomPlayInfo}，而且**音视频复用在同一条流里**
 * ——离线实测 FLV 里是 aac(2ch 48k) + h264(1080p60) 两路，共用一根时间轴，
 * 所以交给引擎时 video = audio = 同一个直链（引擎本来就允许两条链指向同一个 URI）。
 *
 * <p><b>时间轴</b>：直播流的时间戳是服务器侧的一个固定偏移（实测等于"本地零点起的毫秒数"），
 * 且与系统时间 1:1 同步：8 秒窗口内 {@code PTS − 系统时间} 只在 ±50ms 内浮动（约 6ppm）；
 * 断流后重新取链，两段流的该偏移也只差 0.45s。因此引擎侧不需要为直播做任何特殊时间轴处理，
 * 只要用系统时间把原始 PTS 折算成"开播以来的秒数"，就能得到可读的位置与**实时延迟**
 * —— 见 {@link dev.mediafix.engine.MediaEngine} 里的直播锚点。
 *
 * <p>清晰度固定取 qn=10000（原画）。直播不做 ABR：切档必须断流重连，体验反而更差。
 */
public final class BiliLive {

    /** 解析结果：直播间号 + 可直接交给 FFmpeg 的 FLV 直链 + 实际档位。 */
    public record Stream(long roomId, String url, int qn) {
    }

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /** protocol=0(http_stream) / format=0(flv) / codec=0(avc) / qn=10000(原画)。 */
    private static final String API =
            "https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo"
                    + "?room_id=%d&protocol=0&format=0&codec=0&qn=10000&platform=web&ptype=8";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private BiliLive() {
    }

    /**
     * 取直播间直链。失败（未开播 / 接口异常 / 没有可用组合）返回 null，调用方应回退前置模组。
     * 会发起网络请求，务必在后台线程调用。
     */
    public static Stream resolve(long roomId) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(String.format(API, roomId)))
                    .header("User-Agent", UA)
                    .header("Referer", "https://live.bilibili.com/")
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(15));
            BiliAuth.withCookie(builder);
            HttpResponse<String> resp = CLIENT.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
            JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
            int code = root.has("code") ? root.get("code").getAsInt() : -1;
            if (code != 0 || !root.has("data") || !root.get("data").isJsonObject()) {
                CineDuct.LOGGER.warn("[CineDuct] 直播接口返回异常 code={}: {}", code,
                        resp.body().length() > 300 ? resp.body().substring(0, 300) : resp.body());
                return null;
            }
            JsonObject data = root.getAsJsonObject("data");
            int liveStatus = intOf(data, "live_status");
            if (liveStatus != 1) {
                CineDuct.LOGGER.warn("[CineDuct] 直播间 {} 当前未开播（live_status={}）", roomId, liveStatus);
                return null;
            }
            if (!data.has("playurl_info") || !data.get("playurl_info").isJsonObject()) {
                // 付费/加密直播间等会走到这里
                CineDuct.LOGGER.warn("[CineDuct] 直播间 {} 没给出 playurl_info（可能是付费/加密直播）", roomId);
                return null;
            }
            JsonObject playurl = data.getAsJsonObject("playurl_info").getAsJsonObject("playurl");
            JsonArray streams = playurl.getAsJsonArray("stream");
            if (streams == null) {
                CineDuct.LOGGER.warn("[CineDuct] 直播间 {} 的 playurl 里没有 stream[]", roomId);
                return null;
            }
            for (JsonElement se : streams) {
                JsonObject stream = se.getAsJsonObject();
                if (!"http_stream".equals(str(stream, "protocol_name"))) continue;
                JsonArray formats = stream.getAsJsonArray("format");
                if (formats == null) continue;
                for (JsonElement fe : formats) {
                    JsonObject format = fe.getAsJsonObject();
                    if (!"flv".equals(str(format, "format_name"))) continue;
                    JsonArray codecs = format.getAsJsonArray("codec");
                    if (codecs == null) continue;
                    for (JsonElement ce : codecs) {
                        JsonObject codec = ce.getAsJsonObject();
                        if (!"avc".equals(str(codec, "codec_name"))) continue;
                        JsonArray infos = codec.getAsJsonArray("url_info");
                        if (infos == null || infos.isEmpty()) continue;
                        JsonObject info = infos.get(0).getAsJsonObject();
                        String url = str(info, "host") + str(codec, "base_url") + str(info, "extra");
                        if (url.length() < 20) continue;
                        int qn = intOf(codec, "current_qn");
                        CineDuct.LOGGER.info("[CineDuct] 直播解析: 房间 {} 档位=qn{} 可选={} 直链={}",
                                roomId, qn, String.valueOf(codec.get("accept_qn")), CineDuct.LOGGER.url(url));
                        return new Stream(roomId, url, qn);
                    }
                }
            }
            CineDuct.LOGGER.warn("[CineDuct] 直播间 {} 没有 http_stream/flv/avc 组合可用", roomId);
            return null;
        } catch (Throwable t) {
            CineDuct.LOGGER.warn("[CineDuct] 直播间 {} 解析失败", roomId, t);
            return null;
        }
    }

    private static String str(JsonObject o, String key) {
        if (o == null || !o.has(key) || !o.get(key).isJsonPrimitive()) return "";
        try {
            return o.get(key).getAsString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static int intOf(JsonObject o, String key) {
        if (o == null || !o.has(key) || !o.get(key).isJsonPrimitive()) return -1;
        try {
            return o.get(key).getAsInt();
        } catch (Throwable t) {
            return -1;
        }
    }
}

package dev.mediafix.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.mediafix.CineDuct;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 流式播放配置：B 站视频走 DASH 高清链路（4K/高码率/杜比，带 wbi 签名），
 * 音视频两条链**边下边播、不落盘**。
 *
 * <p>本模组只做流式播放：以前那条"先下载到本地缓存再播"的路径已整条移除
 * （把 B 站视频下载到本地属于灰色地带）。
 *
 * <p>清晰度上限 {@link #maxQn} 可通过 /cineduct-stream quality 调节，
 * 0 = 不限（拿账号授权内最高，如 4K/8K）。
 * <p>配置存于游戏目录 cineduct-stream.json。
 */
public final class StreamConfig {
    /** true = 优先 DASH(4K/8K/杜比) 流式直连；false = 交回前置模组原生逻辑。 */
    public static boolean highres = true;
    /**
     * 清晰度：-1 = 自动（ABR，按实测网速自适应选档）；
     * 16=360P 32=480P 64=720P 74=720P60 80=1080P 116=1080P60 120=4K 127=8K；0=不限（账号内最高）。
     */
    public static int maxQn = -1;   // 默认：自动（按网速自适应）

    /**
     * 是否避开 AV1。
     *
     * <p>B 站的同一个清晰度会提供多条编码（H.264 / HEVC / AV1）。AV1 压缩率最高，但
     * **大多数显卡没有 AV1 硬解**，只能 libaom 软解 —— 实测 4K AV1 软解只能跑到 3~4fps，
     * 是"幻灯片感"的直接来源。默认避开，优先 H.264 / HEVC（这两者能走 d3d11va 硬解）。
     * 将来显卡支持 AV1 硬解了，把这里设成 false 即可。
     */
    public static boolean avoidAv1 = true;

    /** 是否处于"自动清晰度"（ABR）模式。 */
    public static boolean autoQuality() {
        return maxQn < 0;
    }

    /**
     * DASH 流式直连（不下载）：B 站的视频/音频是两条独立 fMP4 直链，
     * 打开本开关后直接把视频链交给 FFmpeg、音频链交给 VLC，边下边播、不落盘。
     * 需要 FFmpeg 原生库可用；不满足则交回前置模组处理。
     */
    public static boolean dashStreaming = true;

    /** 流式模式的音画时间偏移（毫秒）：画面相对音频提前为正、滞后为负。默认 0，用于微调对齐。 */
    public static int streamOffsetMs = 0;

    /**
     * "就地快进"的最大跨度（毫秒）。
     *
     * <p>waterframes 每 tick 会拿服务端 tick 进度对我们的播放位置纠偏（阈值 2000ms）：
     * 我们因为缓冲/暂停落后一点，它就来一次 seek。按真 seek 处理的话要重开两条 CDN 连接、
     * 清空约 2.5 秒音频缓冲 → 又落后 → 它又纠偏，形成"seek → 卡顿 → 更落后 → 再 seek"的死循环。
     * 向前跳在这段跨度内改为"在已预读的码流里丢数据"：连接与缓冲原封不动，代价只有几十毫秒。
     */
    public static int catchUpMaxMs = 8000;

    /**
     * "原地 seek"容差（毫秒）：目标与当前进度之差小于该值的 seek 直接忽略。
     *
     * <p>对网络流来说，seek 的代价极高 —— 重开两条 CDN 连接、清空约 2.5 秒音频缓冲、
     * 必然进一次 BUFFERING；而"回到当前位置"这种 seek 画面和声音完全没变化。
     * （以前这个值挂在 cineduct-seekguard.json 上，那份配置是给 waterframes 的进度 bug
     * 打补丁用的；现在前置 mod 去掉了，它作为引擎自己的参数搬到这里。）
     */
    public static int noopSeekToleranceMs = 1500;

    /**
     * 音频轨偏好。
     * <ul>
     *   <li>{@code dolby}（默认）：优先杜比全景声(30250, E-AC-3/ec-3)，其次 Hi-Res 无损(30251, FLAC)，
     *       最后普通音轨里码率最高的一条(30280)；</li>
     *   <li>{@code hires}：优先 Hi-Res 无损，其次杜比，最后普通；</li>
     *   <li>{@code best} ：只用普通音轨里码率最高的一条。</li>
     * </ul>
     * 杜比/无损都需要大会员账号，且只有部分视频提供；没有时自动回退，不会没声音。
     */
    public static String audioPreference = "dolby";

    private static final Path CFG = FMLPaths.GAMEDIR.get().resolve("cineduct-stream.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private StreamConfig() {
    }

    public static void load() {
        try {
            if (Files.exists(CFG)) {
                Map<String, Object> map = GSON.fromJson(
                        new String(Files.readAllBytes(CFG), StandardCharsets.UTF_8),
                        new TypeToken<Map<String, Object>>() {}.getType());
                if (map != null) {
                    if (map.get("highres") instanceof Boolean h) highres = h;
                    if (map.get("maxQn") instanceof Number n) maxQn = n.intValue();
                    if (map.get("dashStreaming") instanceof Boolean b) dashStreaming = b;
                    if (map.get("avoidAv1") instanceof Boolean b) avoidAv1 = b;
                    if (map.get("streamOffsetMs") instanceof Number n) streamOffsetMs = n.intValue();
                    if (map.get("audioPreference") instanceof String s) audioPreference = s;
                    if (map.get("catchUpMaxMs") instanceof Number n) catchUpMaxMs = n.intValue();
                    if (map.get("noopSeekToleranceMs") instanceof Number n) noopSeekToleranceMs = Math.max(0, n.intValue());
                }
            }
        } catch (Exception e) {
            CineDuct.LOGGER.warn("[CineDuct] 读取流式播放配置失败", e);
        }
        save();
    }

    public static void save() {
        try {
            Map<String, Object> map = new HashMap<>();
            map.put("highres", highres);
            map.put("maxQn", maxQn);
            map.put("dashStreaming", dashStreaming);
            map.put("avoidAv1", avoidAv1);
            map.put("streamOffsetMs", streamOffsetMs);
            map.put("audioPreference", audioPreference);
            map.put("catchUpMaxMs", catchUpMaxMs);
            map.put("noopSeekToleranceMs", noopSeekToleranceMs);
            Files.write(CFG, GSON.toJson(map).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            CineDuct.LOGGER.warn("[CineDuct] 保存流式播放配置失败", e);
        }
    }
}

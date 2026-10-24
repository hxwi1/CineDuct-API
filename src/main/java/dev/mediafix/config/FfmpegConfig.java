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
 * 自研引擎（FFmpeg）的参数。配置存于游戏目录 cineduct-ffmpeg.json。
 *
 * <p><b>没有总开关</b>：引擎是本模组的核心，关掉它就没法播 B 站内容（前置模组的能力已经被
 * DASH 直连取代），所以它常开 —— 只保留这些调优参数。
 */
public final class FfmpegConfig {

    /** 解码线程数；0 = 自动（CPU 核心数的一半，至少 1）。 */
    public static int threads = 0;

    /** 环形帧槽数量（同时也是能容忍的抖动深度）。 */
    public static int ringSize = 4;

    /** 让 VLC 不解视频轨（追加 :no-video 媒体选项）。 */
    public static boolean disableVlcVideo = true;

    /** 原生库（FFmpeg DLL/so）压缩包路径；空 = 自动在 mods/ 与 config/cineduct/ 下寻找。 */
    public static String nativesPath = "";

    /** 是否把 FFmpeg 的详细日志写进游戏日志。 */
    public static boolean verbose = false;

    /** 音画偏移（毫秒）：正数 = 画面提前。用于两条独立流起播时间不同时的对齐。 */
    public static int avOffsetMs = 0;

    /**
     * 硬件解码（D3D11VA / DXVA2）的<b>偏好</b>：配置文件与设置界面里保存的是这一个。
     *
     * <p>为什么要和 {@link #hwAccel} 分开：驱动不支持硬解时 {@code FfmpegVideoSource}
     * 会把"本次会话"的 {@link #hwAccel} 置成 false（它不能在每次开播前都重试一遍坏驱动）。
     * 如果界面直接读写 {@link #hwAccel}，那么一次自动降级就会被当成玩家的选择写进配置文件，
     * 下次启动也永远软解了。所以：偏好只由玩家改，运行时状态每次启动都从偏好重新开始。
     */
    public static boolean hwAccelPreferred = true;

    /**
     * **本次会话**实际是否走硬件解码：启动时从 {@link #hwAccelPreferred} 取初值，
     * 硬解初始化失败会被自动置 false（只在本局有效，不会写回配置文件）。
     */
    public static boolean hwAccel = true;

    /** 开播前先缓冲的音频毫秒数：缓够了才开始播，避免一上来就卡。 */
    public static int prebufferMs = 1500;

    /** 音频输出缓冲深度（毫秒）：网络抖动靠它吸收。 */
    public static int audioBufferMs = 2500;

    /**
     * 视频码流预读预算（MB）：队列里缓存的是【压缩数据】，不是解码后的帧。
     * 4K 约 15Mbps → 48MB ≈ 25 秒；1080p 约 5Mbps → 48MB ≈ 75 秒。
     */
    public static int videoReadAheadMb = 48;

    /** 音频码流预读预算（MB）：音频码率低，8MB 就够好几分钟。 */
    public static int audioReadAheadMb = 8;

    /**
     * 色彩转换（NV12→RGBA）的并行线程数。0 = 自动（按核数取 1~4）。
     * 分带独立转换，离线验证过与整帧转换【逐字节一致】，零画质损失。
     */
    public static int convertThreads = 0;

    /**
     * 解码后帧缓冲的内存预算（MB）。参考流媒体播放器的水位线思想：
     * 缓冲该按"能撑住多少秒"来算，而不是固定几帧。
     * 4K 一帧 RGBA 是 33MB，256MB 只能放 7 帧（约 0.3 秒）；1080p 同预算能放 30 帧（约 1.2 秒）。
     * 设得越大抗抖动越强，代价是显存外的原生内存占用。
     */
    public static int videoFrameBufferMb = 256;


    /**
     * 关闭时是否释放 FFmpeg 原生上下文。
     * 默认 true；设为 false 可以"只泄漏不释放"，用来判断退出世界时的崩溃是否来自原生释放路径。
     */
    public static boolean freeNativeOnClose = true;

    // ---- C++ 子进程播放（零拷贝 + WASAPI）----

    /**
     * 是否启用 C++ 子进程播放（零拷贝 + WASAPI）。
     *
     * <p><b>默认关</b>（2026-10-06 改）：默认播放方式是**进程内 Java 引擎** ——
     * 它是本模组的主力路径（谁都能跑、不挑显卡驱动、直播还带"开播以来/实时延迟"那套系统时间锚定），
     * 也是子进程出问题时本来就用的那条兜底路。C++ 子进程（画面经 DXGI 共享纹理零拷贝进 GL、
     * 音频走 WASAPI）改成**显式开启**：游戏里 {@code /cineduct subprocess on}，
     * 它会写进本文件，**永久生效**（{@code /cineduct subprocess off} 关回去）。
     *
     * <p>开着的时候，任何一步不可用（找不到 exe、驱动不支持 NV_DX_interop、子进程起不来）
     * 仍然会**自动降级**回进程内引擎，玩家始终有画面。
     */
    public static boolean gpuHostEnabled = false;

    /** 子进程输出纹理槽数：越多抗抖动越强，显存也越多（4K 每槽约 33MB，1080p 约 8MB）。 */
    public static int hostSlots = 3;

    /** cineduct-host.exe 路径；空 = 自动在 &lt;游戏目录&gt;/cineduct-host/ 等处查找。 */
    public static String hostPath = "";

    // ---- 缓冲与"等缓存"闸门（子进程侧的钱；见 docs/PLAN-preload-buffer.md）----

    /**
     * 目标缓冲水位（毫秒）：子进程包队列里"首尾 PTS 跨度"的上限 = **提前缓存几秒**。
     *
     * <p>它的意义不是"多占内存"（压缩数据很小：1080p 8Mbps 约 4MB/4 秒，
     * 4K 25Mbps 约 12MB/4 秒），而是"网络抖一下还有存货，不用立刻停下等"。
     * 设得越大抗抖动越强，代价是 seek 之后要重新攒这么长的缓冲、以及内存线性增长。
     *
     * <p>0 = 交给子进程默认（4000）；设成负数没有意义，这里按 0 处理。
     */
    public static int bufferTargetMs = 4000;

    /**
     * 起播预缓冲（毫秒）：第一帧解出来后先攒够这么多再开播（同时放声卡）。
     *
     * <p>以前首帧一到就开播，此时缓冲里几乎是空的 —— 开头几秒最容易卡。
     * 代价是起播晚最多这么久；网络慢时子进程会自己按水位上限与超时兜底，
     * 不会真的卡死等满。0 = 不起播预缓冲（立刻播）。
     */
    public static int prerollMs = 1500;

    /**
     * 卡顿闸门（毫秒）：**游戏进程连续这么久没来取画面**，子进程就把时钟与声卡冻住等它，
     * 恢复后从同一位置接着放 —— 而不是让位置继续往前走、把积压的帧全按"迟到"丢掉。
     *
     * <p>这就是"客户端卡顿时等缓存、不丢帧追赶"的开关。设成 0 = 关掉这条判据
     * （只保留"供给见底才等"那条）。想彻底关掉闸门请用 {@link #bufferGate}。
     */
    public static int stallGateMs = 1500;

    /**
     * 缓冲闸门总开关（默认开）。
     *
     * <p>关掉之后行为回到"位置永远按主时钟走"：缓冲只影响"卡不卡"，不影响"跳不跳"。
     * 怀疑"卡一下之后声音停了一下"是闸门造成的话，把它关掉做对照实验。
     */
    public static boolean bufferGate = true;

    // ---- 音频输出形态（子进程声卡直出 / 杜比全景声直通）----

    /**
     * 音频输出形态：
     * <ul>
     *   <li>{@code shared}（**默认**）：共享模式 —— 经过系统混音器，兼容性最好、不独占设备。
     *       本机实测的结论是："独占/直通"这条路在这类显卡 HDMI 端点上会让 Windows 音频引擎
     *       （audiokse.dll）崩溃，所以默认不再去试它。详见 docs/AUDIO-passthrough.md。</li>
     *   <li>{@code auto}：能直通就直通（需要音轨是 Dolby/DTS 码流且端点认那种 IEC 61937 格式），
     *       否则共享模式。<b>会尝试独占</b>，慎用。</li>
     *   <li>{@code passthrough}：只走 IEC 61937 码流直通（功放硬件解码全景声）。</li>
     *   <li>{@code exclusive}：只走独占 PCM（位完美，但会独占整个端点）。</li>
     * </ul>
     */
    public static String audioMode = "shared";

    /** 输出端点选择：空 = 系统默认；"@3" = /cineduct audio 列表里的编号；其它 = 设备名子串。 */
    public static String audioDevice = "";

    private static final Path CFG = FMLPaths.GAMEDIR.get().resolve("cineduct-ffmpeg.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private FfmpegConfig() {
    }

    public static void load() {
        try {
            if (Files.exists(CFG)) {
                Map<String, Object> map = GSON.fromJson(
                        new String(Files.readAllBytes(CFG), StandardCharsets.UTF_8),
                        new TypeToken<Map<String, Object>>() {}.getType());
                if (map != null) {
                                if (map.get("threads") instanceof Number n) threads = n.intValue();
                    if (map.get("ringSize") instanceof Number n) ringSize = Math.max(2, n.intValue());
                    if (map.get("disableVlcVideo") instanceof Boolean b) disableVlcVideo = b;
                    if (map.get("nativesPath") instanceof String s) nativesPath = s;
                    if (map.get("verbose") instanceof Boolean b) verbose = b;
                    if (map.get("avOffsetMs") instanceof Number n) avOffsetMs = n.intValue();
                    if (map.get("hwAccel") instanceof Boolean b) hwAccelPreferred = b;
                    if (map.get("prebufferMs") instanceof Number n) prebufferMs = Math.max(0, n.intValue());
                    if (map.get("audioBufferMs") instanceof Number n) audioBufferMs = Math.max(250, n.intValue());
                    if (map.get("videoReadAheadMb") instanceof Number n) videoReadAheadMb = Math.max(4, n.intValue());
                    if (map.get("audioReadAheadMb") instanceof Number n) audioReadAheadMb = Math.max(1, n.intValue());
        if (map.get("convertThreads") instanceof Number n) convertThreads = Math.max(0, n.intValue());
        if (map.get("videoFrameBufferMb") instanceof Number n) videoFrameBufferMb = Math.max(32, n.intValue());
        if (map.get("freeNativeOnClose") instanceof Boolean b) freeNativeOnClose = b;
        if (map.get("gpuHostEnabled") instanceof Boolean b) gpuHostEnabled = b;
        if (map.get("hostSlots") instanceof Number n) hostSlots = Math.max(2, Math.min(8, n.intValue()));
        if (map.get("hostPath") instanceof String s) hostPath = s;
        if (map.get("bufferTargetMs") instanceof Number n) bufferTargetMs = Math.max(0, n.intValue());
        if (map.get("prerollMs") instanceof Number n) prerollMs = Math.max(0, n.intValue());
        if (map.get("stallGateMs") instanceof Number n) stallGateMs = Math.max(0, n.intValue());
        if (map.get("bufferGate") instanceof Boolean b) bufferGate = b;
        if (map.get("audioMode") instanceof String s) audioMode = s;
        if (map.get("audioDevice") instanceof String s) audioDevice = s;
                }
            }
        } catch (Exception e) {
            CineDuct.LOGGER.warn("[CineDuct] 读取 FFmpeg 配置失败", e);
        }
        // 运行时状态每次启动都从偏好重新开始（见 hwAccelPreferred 的说明）
        hwAccel = hwAccelPreferred;
        save();
    }

    public static void save() {
        try {
            Map<String, Object> map = new HashMap<>();
                map.put("threads", threads);
            map.put("ringSize", ringSize);
            map.put("disableVlcVideo", disableVlcVideo);
            map.put("nativesPath", nativesPath);
            map.put("verbose", verbose);
            map.put("avOffsetMs", avOffsetMs);
            map.put("hwAccel", hwAccelPreferred);
            map.put("prebufferMs", prebufferMs);
            map.put("audioBufferMs", audioBufferMs);
            map.put("videoReadAheadMb", videoReadAheadMb);
            map.put("audioReadAheadMb", audioReadAheadMb);
        map.put("convertThreads", convertThreads);
        map.put("videoFrameBufferMb", videoFrameBufferMb);
        map.put("freeNativeOnClose", freeNativeOnClose);
        map.put("gpuHostEnabled", gpuHostEnabled);
        map.put("hostSlots", hostSlots);
        map.put("hostPath", hostPath);
        map.put("bufferTargetMs", bufferTargetMs);
        map.put("prerollMs", prerollMs);
        map.put("stallGateMs", stallGateMs);
        map.put("bufferGate", bufferGate);
        map.put("audioMode", audioMode);
        map.put("audioDevice", audioDevice);
            Files.write(CFG, GSON.toJson(map).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            CineDuct.LOGGER.warn("[CineDuct] 保存 FFmpeg 配置失败", e);
        }
    }
}

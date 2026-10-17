package dev.mediafix.host;

import dev.mediafix.CineDuct;
import dev.mediafix.api.ResolvedStream;
import dev.mediafix.config.FfmpegConfig;
import dev.mediafix.ffmpeg.FfmpegRuntime;
import dev.mediafix.gpu.NativeD3D;
import net.neoforged.fml.loading.FMLPaths;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一条「C++ 子进程」播放会话的客户端（P2 · M4）。
 *
 * <p><b>分工</b>：子进程负责解码（d3d11va 硬解）、转换（NV12→BGRA）、音频（WASAPI）、
 * 时钟（音频可听位置）；本类只负责：
 * <ul>
 *   <li>启动/停止子进程，把两条直链、请求头、适配器 LUID、输出纹理名传过去；</li>
 *   <li>连它的控制管道（暂停/音量/退出）；</li>
 *   <li>读它的共享内存状态环（位置/时长/槽位就绪标志）—— 每帧只读内存，没有 IPC 往返。</li>
 * </ul>
 * 真正把画面搬进 GL 的是 {@code GlInterop}（渲染线程）。
 *
 * <p><b>失败即降级</b>：找不到 exe、进程起不来、状态环没出现、互操作不可用 ——
 * 任何一步失败都返回 null / FAILED，调用方回退到进程内 Java 引擎，
 * 玩家始终有画面（这条兜底链路本来就完好）。
 */
public final class HostSession implements AutoCloseable {

    // ---- 状态环布局（必须与 native/spike/shared/cineduct_spike_status.h 一致）----
    private static final int OFF_MAGIC = 0;
    private static final int OFF_WIDTH = 8;
    private static final int OFF_HEIGHT = 12;
    private static final int OFF_SLOTS = 16;
    private static final int OFF_PUBLISHED = 24;
    private static final int OFF_DROPPED = 32;
    private static final int OFF_LAST_ACQUIRE_US = 44;
    private static final int OFF_MAX_ACQUIRE_US = 48;
    private static final int OFF_LUID_HIGH = 52;
    private static final int OFF_LUID_LOW = 56;
    private static final int OFF_PID = 60;
    private static final int OFF_SLOTS0 = 64;
    private static final int SLOT_STRIDE = 16;
    // v2 追加（与 C++ 侧同名常量必须一致）
    private static final int OFF_MEDIA_POSITION_MS = 192;
    private static final int OFF_MEDIA_DURATION_MS = 200;
    private static final int OFF_AUDIO_SAMPLE_RATE = 208;
    private static final int OFF_PAUSED = 212;
    private static final int OFF_LAST_SEEK_TARGET_MS = 216;
    // v3 追加：音频输出形态（与 C++ 侧同名常量必须一致）
    private static final int OFF_AUDIO_MODE = 224;
    private static final int OFF_AUDIO_CHANNELS = 228;
    private static final int OFF_AUDIO_CONTENT_RATE = 232;
    private static final int OFF_ENDED = 236;
    private static final int OFF_SPATIAL_AUDIO = 240;
    private static final int OFF_SPATIAL_FORMAT = 244;
    private static final int SPATIAL_FORMAT_BYTES = 64;
    private static final int OFF_AUDIO_DEVICE_NAME = 308;
    private static final int AUDIO_DEVICE_NAME_BYTES = 64;
    // v4 追加（**只在末尾加，既有偏移一个都没动**；老 jar 读老偏移依然正确，
    // 所以这里不做版本号强校验 —— 见 cineduct_spike_status.h 的 kVersion 说明）
    private static final int OFF_DECODE_MODE = 372;
    private static final int OFF_BUFFERING = 376;
    private static final int OFF_SEEK_HEAL_COUNT = 380;
    private static final int OFF_DROPPED_LATE = 384;
    // v5 追加：最近发布帧的 PTS（与 mediaPositionMs 一减就是**音画偏移**，见 videoPtsMs()）
    private static final int OFF_VIDEO_PTS_MS = 392;
    // v6 追加：音画偏移自愈次数（重锚时钟，见 clockHealCount()）
    private static final int OFF_CLOCK_HEAL_COUNT = 400;
    // v8 追加：音频健康（子进程模式音频整条链都在子进程里，Java 只能从这里知道）
    private static final int OFF_AUDIO_UNDERRUNS = 624;
    private static final int OFF_AUDIO_QUEUED_MS = 628;
    private static final int OFF_AUDIO_STALLED = 632;
    private static final int OFF_AUDIO_FAILED = 636;
    private static final int OFF_AUDIO_JITTER_REJECTS = 640;
    // v9 追加：子进程身份（两个 37 字节的小写 UUID 文本；老 exe 不写 → 读到空串）
    private static final int OFF_SESSION_UUID = 648;
    private static final int OFF_INSTANCE_UUID = 685;
    private static final int UUID_BYTES = 37;
    /*
     * v10 追加：音频链健康（老 exe 不写 → 读到 0，语义上等价于"没发生过"，安全）。
     *
     * 与 v8 那组（欠载/水位）的区别：v8 说的是"设备有没有东西播"（结果），
     * 这三项说的是"音频链本身还在不在干活"（原因）。2026-10-17 的事故里音频链把读超时
     * 当片尾、静默停摆 26 秒，而 Java 侧当时只能看到"欠载"—— 完全看不出链已经死了。
     */
    private static final int OFF_AUDIO_READ_ERRORS = 728;
    private static final int OFF_AUDIO_RECONNECTS = 732;
    private static final int OFF_AUDIO_REPAIRS = 736;
    private static final int MAGIC = 0x4D465850;      // "MFXP"
    private static final int SLOT_READY = 2;
    private static final int SLOT_SHOWN = 3;
    private static final int MAPPING_BYTES = 4096;

    /**
     * 调试模式开关（放在游戏目录里）：**空文件或同名文件夹都算**。
     * 建文件夹的用法是"日志就放在这个文件夹里"（见 {@link #start} 里 --log-dir 的取值）。
     */
    private static final String DEBUG_FLAG = "cineduct-host-debug.flag";

    public enum State {
        /** 进程已启动，等状态环出现。 */
        STARTING,
        /** 状态环就绪，等共享纹理可导入（子进程要解出第一帧才建）。 */
        RING_READY,
        /** 纹理已导入，可以取帧了。 */
        READY,
        FAILED,
        CLOSED
    }

    private final long sessionId;
    private final String textureBase;
    private final String statusName;
    private final String pipeName;
    private final long adapterLuid;
    /** 身份（v9）：逻辑会话 UUID 文本 + 本次进程实例 UUID 文本，见 docs/host-uuid.md。 */
    private final String sessionUuid;
    private final String instanceUuid;

    private volatile State state = State.STARTING;
    private volatile String lastError = "";

    private ByteBuffer status;          // 共享内存状态环（直接缓冲，零拷贝）
    private long pipe;                  // 控制管道句柄
    private long job;                   // Job Object 句柄（KILL_ON_JOB_CLOSE）
    private int slotCount;
    private int width;
    private int height;
    private final long startedAt = System.currentTimeMillis();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile boolean paused;
    private volatile int volumePercent = 100;

    /**
     * 子进程句柄。
     *
     * <p>★ 从"start 时就有"改成了"后台 spawn() 才有"，所以它可能为 null：
     * {@code CreateProcess} + {@code AssignProcessToJobObject} 是**阻塞调用**
     * （实测几十毫秒，杀软扫描时更久），以前这一步是在**渲染线程**上做的
     * （会话第一帧取帧时触发），就是"起屏/换源会顿一下"的来源。
     */
    private volatile Process process;

    /** 启动命令行与 exe 名（start 时准备好，spawn() 在后台线程用它 CreateProcess）。 */
    private final List<String> command;
    private final String exeName;

    private HostSession(long sessionId, String textureBase, String statusName, String pipeName,
                        List<String> command, String exeName, long adapterLuid,
                        String sessionUuid, String instanceUuid) {
        this.sessionId = sessionId;
        this.textureBase = textureBase;
        this.statusName = statusName;
        this.pipeName = pipeName;
        this.command = command;
        this.exeName = exeName;
        this.adapterLuid = adapterLuid;
        this.sessionUuid = sessionUuid;
        this.instanceUuid = instanceUuid;
    }

    /** 逻辑会话 UUID 文本（跨进程重启不变）。 */
    public String sessionUuid() {
        return this.sessionUuid;
    }

    /** **本次进程实例**的 UUID 文本（每次 spawn 新生成）。 */
    public String instanceUuid() {
        return this.instanceUuid;
    }

    /** 实例 UUID 的短标签（8 位，日志前缀/文件名用）；异常情况返回空串。 */
    public String instanceShort() {
        final String hex = this.instanceUuid.replace("-", "");
        return hex.length() >= 8 ? hex.substring(0, 8) : hex;
    }

    // =====================================================================
    // 启动
    // =====================================================================

    /**
     * 启动子进程（不阻塞）。
     *
     * @param adapterLuid GL 上下文所在适配器（由 GlInterop 探测得到；两边必须同一块卡）
     * @return 会话；找不到可执行文件/启动失败返回 null（调用方降级到 Java 引擎）
     */
    public static HostSession start(long sessionId, ResolvedStream source, long adapterLuid) {
        return start(sessionId, source, adapterLuid, false, null, 0f, UUID.randomUUID());
    }

    /**
     * 启动子进程（不阻塞）。
     *
     * @param adapterLuid GL 上下文所在适配器（由 GlInterop 探测得到；两边必须同一块卡）
     * @param loop        是否循环播放（屏幕的 loop 属性，重启后要沿用）
     * @param audioModeOverride 覆盖配置里的音频形态（null = 用 FfmpegConfig.audioMode）；
     *                          用于"这个端点的独占会崩 → 本次会话改用共享模式"
     * @param fitAspect   显示面宽高比（宽/高）；{@code > 0} 时子进程把画面**等比**放进这个比例的
     *                    输出框、框外烘焙成真黑边（见 {@code --fit-aspect}）；{@code <= 0} = 关
     * @param sessionUuid 逻辑会话 UUID（**跨重启不变**，由 {@code MediaSession} 持有）。
     *                    与"本次进程实例"的 instanceUuid 不同：那个每次 spawn 新生成。
     * @return 会话；找不到可执行文件/启动失败返回 null（调用方降级到 Java 引擎）
     */
    public static HostSession start(long sessionId, ResolvedStream source, long adapterLuid,
                                    boolean loop, String audioModeOverride, float fitAspect,
                                    UUID sessionUuid) {
        if (!FfmpegConfig.gpuHostEnabled) {
            CineDuct.LOGGER.info("[CineDuct] 子进程播放已在配置里关闭，使用进程内引擎");
            return null;
        }
        final Path exe = resolveExecutable();
        if (exe == null) {
            CineDuct.LOGGER.info("[CineDuct] 找不到 cineduct-host.exe（放 <游戏目录>/cineduct-host/ 下即可启用子进程播放），"
                    + "使用进程内引擎");
            return null;
        }
        final Path ffmpegDir = FfmpegRuntime.nativesDir();
        /*
         * 身份（见 docs/host-uuid.md）：
         *   instanceUuid 每次 spawn 新生成，**内核对象命名也用它**（替掉原来的随机 token）——
         *   于是共享纹理名 / 状态段名 / 管道名都能从同一个 UUID 反推，日志和 dump 也对得上。
         */
        final String instanceUuid = UUID.randomUUID().toString();
        final String token = instanceUuid.replace("-", "").substring(0, 12);
        final String base = "cineduct_s" + sessionId + "_" + token;
        final String textureBase = base + "_tex";
        final String statusName = base + "_status";
        final String pipeName = "cineduct-host-" + token;

        final List<String> cmd = new ArrayList<>();
        cmd.add(exe.toAbsolutePath().toString());
        if (ffmpegDir != null) {
            cmd.add("--ffmpeg-dir");
            cmd.add(ffmpegDir.toAbsolutePath().toString());
        }
        cmd.add("--adapter-luid");
        cmd.add(String.format(Locale.ROOT, "%08x:%08x", (int) (adapterLuid >>> 32), (int) adapterLuid));
        final String mainUrl = source.video() != null ? source.video().toString() : source.audio().toString();
        cmd.add("--url");
        cmd.add(mainUrl);
        if (source.audio() != null && !source.audio().toString().equals(mainUrl)) {
            cmd.add("--audio-url");
            cmd.add(source.audio().toString());
        }
        final String headers = dev.mediafix.ffmpeg.FfmpegSources.buildHeaders(source.mainUri());
        if (headers != null && !headers.isBlank()) {
            cmd.add("--headers");
            cmd.add(headers);
        }
        /*
         * ★ UA 必须显式传：子进程里 FFmpeg 的默认 UA 是 `Lavf/x.y`，B 站**直播** CDN
         * （live-bvc）会回 403 Forbidden —— 实测 2026-09-26 23:35：同一条直链 + 同一份
         * Referer/Cookie，进程内引擎（有 Chrome UA）能打开，子进程直接 403 退出，
         * 于是"启动失败→重启→再失败→回退进程内引擎"，屏幕反复黑一下白一下。
         * 这里和进程内引擎用同一个常量，两条路彻底一致。
         */
        cmd.add("--user-agent");
        cmd.add(dev.mediafix.ffmpeg.FfmpegVideoSource.UA);
        cmd.add("--output-textures");
        cmd.add(textureBase);
        /*
         * 子进程调试模式（--console 弹窗 + 落盘）。
         *
         * <p>★ 为什么由 Java 决定、而不是让子进程自己去找标记文件：**只有 Java 知道游戏目录**
         * （FMLPaths.GAMEDIR）。子进程能看到的"自己的工作目录"是启动器给的，
         * 实测既不是版本目录也不是 .minecraft 根 —— 于是"文件明明放在了游戏目录里，
         * 子进程却一个候选都没命中，整条调试链路静默失效"（2026-10-06 踩的坑）。
         * 现在 Java 直接判、直接把绝对路径传下去，子进程那边的同名查找只当兜底。
         */
        if (hostDebugRequested()) {
            try {
                final Path flag = FMLPaths.GAMEDIR.get().resolve(DEBUG_FLAG);
                /*
                 * 日志放哪：标记是**文件夹**就写进那个文件夹里（玩家建个文件夹当开关，
                 * 直觉就是"日志放这里面"，实测就是这么用的）；标记是**文件**才退到
                 * <游戏目录>\cineductlogs\host。
                 */
                final Path hostLogDir = Files.isDirectory(flag)
                        ? flag
                        : FMLPaths.GAMEDIR.get().resolve(dev.mediafix.config.LogConfig.dir).resolve("host");
                cmd.add("--console");
                cmd.add("--log-dir");
                cmd.add(hostLogDir.toAbsolutePath().toString());
                CineDuct.LOGGER.info("[CineDuct] 子进程调试模式: 开（弹窗 + 日志落到 {}）", hostLogDir);
            } catch (Throwable t) {
                CineDuct.LOGGER.warn("[CineDuct] 取游戏目录失败，调试模式交给子进程自己找标记文件", t);
                cmd.add("--console");
            }
        }
        /*
         * 信箱化：把"显示面宽高比"交给子进程 —— 输出框按这个比例算，画面等比放进去，其余烘焙成真黑边。
         * 必须在这里给（而不是每帧在客户端加边）：零拷贝那条路纹理是子进程直接发布的，
         * 客户端只能在几何上缩四边形，那会让屏幕外露出世界而不是黑边。
         */
        if (fitAspect > 0.01f) {
            cmd.add("--fit-aspect");
            cmd.add(String.format(Locale.ROOT, "%.6f", fitAspect));
        }
        /*
         * 音画偏移（正 = 画面提前）：画面是"音频设备把这段播出去"那一刻放出去的，
         * 而声音到耳朵还要过 HDMI → 功放/回音壁（几十到两三百毫秒），于是看起来画面领先。
         * 这是物理延迟，只能靠这个偏移补偿；值在配置里（FfmpegConfig.avOffsetMs），
         * 也可以在游戏里用 /cineduct av <ms> 现场调（走控制通道的 av-offset 动词）。
         */
        if (FfmpegConfig.avOffsetMs != 0) {
            cmd.add("--av-offset-ms");
            cmd.add(String.valueOf(FfmpegConfig.avOffsetMs));
        }        cmd.add("--status");
        cmd.add(statusName);
        cmd.add("--slots");
        cmd.add(String.valueOf(FfmpegConfig.hostSlots));
        cmd.add("--pipe");
        cmd.add(pipeName);
        if (loop) cmd.add("--loop");
        // 父进程看护：游戏一退出（哪怕被强杀），子进程自己收尾退出
        cmd.add("--parent-pid");
        cmd.add(String.valueOf(ProcessHandle.current().pid()));
        // 音频输出形态：auto 让子进程按音轨与端点能力自己选（能直通就直通 = 硬件解码全景声）
        final String requestedMode = audioModeOverride != null ? audioModeOverride
                : FfmpegConfig.audioMode;
        if (requestedMode != null && !requestedMode.isBlank()) {
            cmd.add("--audio-mode");
            cmd.add(requestedMode);
        }
        if (FfmpegConfig.audioDevice != null && !FfmpegConfig.audioDevice.isBlank()) {
            cmd.add("--audio-device");
            cmd.add(FfmpegConfig.audioDevice);
        }
        // 身份（v9，见 docs/host-uuid.md）：子进程据此写状态环、打印横幅、回显 hello
        cmd.add("--session-uuid");
        cmd.add(sessionUuid.toString());
        cmd.add("--instance-uuid");
        cmd.add(instanceUuid);

        try {
            /*
             * ★ 这里**不再 CreateProcess**：只把命令行准备好，真正的创建在后台线程的
             * {@link #spawn()} 里做。
             *
             * <p>为什么必须拆开：本方法会在**渲染线程**上被调到（会话第一帧取帧时才知道显示面比例，
             * 那时才决定要不要起子进程；见 MediaSession.acquireDrawTexture）。而
             * `ProcessBuilder.start()` + `AssignProcessToJobObject` 都是阻塞调用 ——
             * 实测在渲染线程上就是"起播那一下顿一下"。
             * 拆开之后渲染线程只做"拼一个命令行"（纯内存操作），
             * 失败降级也照旧（后台线程 spawn 失败 → 回退进程内引擎）。
             */
            return new HostSession(sessionId, textureBase, statusName, pipeName, cmd,
                    exe.getFileName().toString(), adapterLuid, sessionUuid.toString(), instanceUuid);
        } catch (Throwable t) {
            CineDuct.LOGGER.warn("[CineDuct] 准备子进程命令行失败，使用进程内引擎", t);
            return null;
        }
    }

    /**
     * 真正创建子进程。**只许在后台线程调**（CreateProcess 会阻塞）。
     *
     * @return true = 进程起来了（后续还要等状态环）；false = 启动失败（调用方降级到进程内引擎）
     */
    public boolean spawn() {
        if (this.closed.get()) return false;
        try {
            ProcessBuilder pb = new ProcessBuilder(this.command);
            pb.redirectErrorStream(true);
            final Process started = pb.start();
            this.process = started;
            this.attachToJob(started);
            drainOutput(this, started);
            CineDuct.LOGGER.info("[CineDuct] 子进程已启动: pid={} ({})", started.pid(), this.exeName);
            return true;
        } catch (Throwable t) {
            CineDuct.LOGGER.warn("[CineDuct] 启动子进程失败，使用进程内引擎", t);
            this.lastError = "CreateProcess 失败: " + t.getMessage();
            this.state = State.FAILED;
            return false;
        }
    }

    /**
     * 第二道保险：把子进程放进 KILL_ON_JOB_CLOSE 的 Job。
     *
     * <p>子进程自己也有"父进程看护"（{@code --parent-pid}），但那只在子进程还能跑的时候有效；
     * 万一它卡死，Job 句柄随游戏进程退出而关闭，Windows 会直接把它连根拔掉。
     */
    private void attachToJob(Process process) {
        try {
            if (!NativeD3D.available()) return;
            final long job = NativeD3D.jobCreate();
            if (job == 0L) {
                CineDuct.LOGGER.info("[CineDuct] 建 Job 失败（不影响播放）: {}", NativeD3D.lastError());
                return;
            }
            if (NativeD3D.jobAssign(job, (int) process.pid())) {
                this.job = job;
            } else {
                CineDuct.LOGGER.info("[CineDuct] 子进程未能加入 Job（{}）；父进程看护仍然有效",
                        NativeD3D.lastError());
                NativeD3D.jobClose(job);
            }
        } catch (Throwable t) {
            CineDuct.LOGGER.warn("[CineDuct] 加入 Job 异常（不影响播放）", t);
        }
    }

    /**
     * 子进程调试模式是否打开。
     *
     * <p>三种开法（任一命中即开）：游戏目录下放一个空的 {@code cineduct-host-debug.flag}；
     * 加 JVM 参数 {@code -Dcineduct.hostDebug=true}；或直接改这里。
     * 关掉就是把那个文件删掉。
     */
    private static boolean hostDebugRequested() {
        try {
            if (Boolean.getBoolean("cineduct.hostDebug")) return true;
            /*
             * 用 exists 而不是 isRegularFile：**玩家把它建成文件夹也要认**。
             * 实测（2026-10-06）：有人顺手在资源管理器里建了个**同名文件夹**（并且本意就是
             * "日志放这里面"），而两边都按"必须是文件"判，于是调试模式静默失效 ——
             * 更坑的是 Test-Path 对目录照样返回真，连排查时都会被骗。
             */
            return Files.exists(FMLPaths.GAMEDIR.get().resolve(DEBUG_FLAG));
        } catch (Throwable t) {
            return false;
        }
    }

    /** 可执行文件位置：系统属性 cineduct.host > jar 释放出来的那份 > <游戏目录>/ 下几处。 */
    private static Path resolveExecutable() {        List<Path> candidates = new ArrayList<>();
        final String explicit = System.getProperty("cineduct.host");
        if (explicit != null && !explicit.isBlank()) {
            candidates.add(Path.of(explicit));
        }
        if (FfmpegConfig.hostPath != null && !FfmpegConfig.hostPath.isBlank()) {
            candidates.add(Path.of(FfmpegConfig.hostPath));
        }
        // jar 里打包的（NativeFiles 会按大小判断是否需要重新释放）
        final Path released = dev.mediafix.NativeFiles.file("cineduct-host.exe");
        if (released != null) {
            candidates.add(released);
        }
        try {
            final Path gameDir = FMLPaths.GAMEDIR.get();
            candidates.add(gameDir.resolve("cineduct-native").resolve("cineduct-host.exe"));
            candidates.add(gameDir.resolve("cineduct-host").resolve("cineduct-host.exe"));
            candidates.add(gameDir.resolve("cineduct").resolve("cineduct-host.exe"));
        } catch (Throwable ignored) {
            // 开发环境早期阶段拿不到 GAMEDIR 也无所谓
        }
        for (Path p : candidates) {
            try {
                if (Files.isRegularFile(p)) return p;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 把子进程的 stdout 收进本模组日志（它的诊断很有用，且没人接的话管道会堵）。 */
    private static void drainOutput(HostSession session, Process process) {
        Thread t = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    CineDuct.LOGGER.diag("[host#{}/{}] {}", session.sessionId, session.instanceShort(), line);
                }
            } catch (Throwable ignored) {
                // 进程退出/管道关闭是正常路径
            }
        }, "cineduct-host-drain-" + session.sessionId);
        t.setDaemon(true);
        t.start();
    }

    // =====================================================================
    // 就绪流程（状态环 → 控制管道）
    // =====================================================================

    /**
     * 等状态环出现（子进程解出第一帧才创建共享纹理与状态段）。
     *
     * <p>在**后台线程**调用；拿到环之后，纹理导入由渲染线程按帧尝试（见 {@link #imported()}）。
     *
     * @return 是否在超时前拿到
     */
    public boolean awaitRing(long timeoutMs) {
        if (this.state == State.FAILED || this.state == State.CLOSED) return false;
        final long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            final Process p = this.process;
            if (p == null || !p.isAlive()) {
                this.state = State.FAILED;
                this.lastError = "子进程提前退出（见日志里的 [host#...] 行）";
                return false;
            }
            final ByteBuffer raw = NativeD3D.openSharedSection(this.statusName, MAPPING_BYTES);
            if (raw != null) {
                final ByteBuffer view = raw.order(ByteOrder.LITTLE_ENDIAN);
                if (view.getInt(OFF_MAGIC) == MAGIC) {
                    this.status = view;
                    this.width = view.getInt(OFF_WIDTH);
                    this.height = view.getInt(OFF_HEIGHT);
                    this.slotCount = view.getInt(OFF_SLOTS);
                    final long producerLuid = ((long) view.getInt(OFF_LUID_HIGH) << 32)
                            | (view.getInt(OFF_LUID_LOW) & 0xFFFFFFFFL);
                    if (producerLuid != this.adapterLuid) {
                        this.state = State.FAILED;
                        this.lastError = String.format(Locale.ROOT,
                                "子进程适配器 %08x:%08x 与游戏 GL 适配器 %08x:%08x 不一致（跨卡无法共享纹理）",
                                (int) (producerLuid >>> 32), (int) producerLuid,
                                (int) (this.adapterLuid >>> 32), (int) this.adapterLuid);
                        CineDuct.LOGGER.warn("[CineDuct] {}", this.lastError);
                        return false;
                    }
                    /*
                     * v9 身份校验（见 docs/host-uuid.md §3）。
                     *
                     * <p>为什么要校验：状态段是**命名**共享内存，pid 会复用、旧段也可能残留 ——
                     * 只凭 magic 无法排除"映射到了别人/上一轮进程的环"。子进程把自己收到的
                     * instanceUuid 写进环里，对不上就说明这块环不是我们起的那个进程的。
                     *
                     * <p>**空值一律跳过校验**：老 exe（阶段 A 之前）根本不写这两个字段，
                     * 读出来就是空串，此时不能判失败（向前兼容）。
                     */
                    final String ringInstance = this.readUuidField(OFF_INSTANCE_UUID);
                    if (!ringInstance.isEmpty() && !ringInstance.equals(this.instanceUuid)) {
                        this.state = State.FAILED;
                        this.lastError = "状态环里的 instanceUuid 与本次启动的不一致（"
                                + ringInstance + " != " + this.instanceUuid + "），可能是旧段残留";
                        CineDuct.LOGGER.warn("[CineDuct] {}", this.lastError);
                        NativeD3D.closeSharedSection(raw);
                        return false;
                    }
                    this.state = State.RING_READY;
                    CineDuct.LOGGER.info("[CineDuct] 子进程状态环就绪: {}x{} {} 槽 (pid={} instance={})",
                            this.width, this.height, this.slotCount, view.getInt(OFF_PID),
                            ringInstance.isEmpty() ? "旧版不报" : ringInstance);
                    return true;
                }
                NativeD3D.closeSharedSection(raw);
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        this.state = State.FAILED;
        this.lastError = "等待子进程状态环超时";
        return false;
    }

    /** 连控制管道（尽力而为：连不上也能播，只是不能控制）。 */
    public void connectPipe() {
        if (this.pipe != 0L) return;
        this.pipe = NativeD3D.pipeConnect(this.pipeName, 3000);
        if (this.pipe == 0L) {
            CineDuct.LOGGER.info("[CineDuct] 子进程控制通道未连上（不影响播放，只是不能暂停/调音量）");
            return;
        }
        final String hello = this.send("hello 1");
        CineDuct.LOGGER.info("[CineDuct] 子进程握手: {}", hello);
    }

    /** 渲染线程：所有槽位都导入成功就算就绪。 */
    public void markReady() {
        this.state = State.READY;
    }

    // =====================================================================
    // 状态读取（零 IPC）
    // =====================================================================

    public State state() {
        return this.state;
    }

    public String lastError() {
        return this.lastError;
    }

    public boolean isReady() {
        return this.state == State.READY;
    }

    public int slotCount() {
        return this.slotCount;
    }

    public int width() {
        return this.width;
    }

    public int height() {
        return this.height;
    }

    public String textureName(int slot) {
        return this.textureBase + "_" + slot;
    }

    /** 已就绪（READY）且帧号最新的槽位；没有则 -1。 */
    public int pickReadySlot() {
        final ByteBuffer s = this.status;
        if (s == null) return -1;
        int best = -1;
        int bestGen = -1;
        for (int i = 0; i < this.slotCount; i++) {
            final int base = OFF_SLOTS0 + i * SLOT_STRIDE;
            if (s.getInt(base) == SLOT_READY) {
                final int gen = s.getInt(base + 4);
                if (gen > bestGen) {
                    bestGen = gen;
                    best = i;
                }
            }
        }
        return best;
    }

    /** 告诉子进程这个槽位已经取用过了（它可以复用）。 */
    public void markShown(int slot) {
        final ByteBuffer s = this.status;
        if (s == null || slot < 0 || slot >= this.slotCount) return;
        s.putInt(OFF_SLOTS0 + slot * SLOT_STRIDE, SLOT_SHOWN);
    }

    /**
     * 这个槽位当前的帧号（子进程每发布一帧 +1）。
     *
     * <p>用于消费者侧判断"是不是拿到了比上一帧更旧的画面" —— 帧号回退就是画面顺序错乱
     * （会闪一下旧内容）。只读诊断用，不参与任何同步决策。
     */
    public int slotGeneration(int slot) {
        final ByteBuffer s = this.status;
        if (s == null || slot < 0 || slot >= this.slotCount) return -1;
        return s.getInt(OFF_SLOTS0 + slot * SLOT_STRIDE + 4);
    }

    /**
     * 把帧号**不大于** {@code gen} 的 READY 槽位全部标成"已取用"。
     *
     * <h2>为什么必须这么做（2026-09-27 实测的"画面闪旧内容"根因）</h2>
     * <p>消费者画得比生产者发得快时（实测：显示器 60fps 以上的客户端 × 视频 59.94fps），
     * 会出现这种序列：
     *
     * <pre>
     *   槽 1 = READY(帧 10)   槽 2 = READY(帧 9)      ← 生产者的两次发布都还没被取走
     *   消费者挑最高帧号 → 画槽 1（帧 10）✓，把槽 1 标成 SHOWN
     *   生产者还没发下一帧；
     *   消费者下一帧再挑 → 槽 1 已 SHOWN 不算，只剩槽 2 = READY(帧 9)
     *   → 于是它**画了比刚才更旧的一帧**（帧 9）
     * </pre>
     *
     * <p>现场日志（{@code logs/latest.log}）：25 秒里 200 次
     * {@code 帧号回退 #N：槽 2 拿到帧号 9，上次已是 10} —— 玩家看到的正是"画面往回跳一下、闪"。
     *
     * <p>所以画完最新一帧之后，把所有比它旧的 READY 槽位一起作废：它们是**已经被时间甩掉**的帧，
     * 保留它们唯一的作用就是让消费者有机会往回画。
     */
    public void dropStaleSlots(int gen) {
        final ByteBuffer s = this.status;
        if (s == null || gen < 0) return;
        for (int i = 0; i < this.slotCount; i++) {
            final int base = OFF_SLOTS0 + i * SLOT_STRIDE;
            if (s.getInt(base) == SLOT_READY && s.getInt(base + 4) <= gen) {
                s.putInt(base, SLOT_SHOWN);
            }
        }
    }

    public long publishedFrames() {
        return this.status == null ? 0 : this.status.getLong(OFF_PUBLISHED);
    }

    public long droppedFrames() {
        return this.status == null ? 0 : this.status.getLong(OFF_DROPPED);
    }

    public int lastAcquireUs() {
        return this.status == null ? 0 : this.status.getInt(OFF_LAST_ACQUIRE_US);
    }

    public int maxAcquireUs() {
        return this.status == null ? 0 : this.status.getInt(OFF_MAX_ACQUIRE_US);
    }

    /**
     * 播放位置（媒体时间轴，毫秒）—— **读共享内存，零 IPC**。
     *
     * <p>为什么放在状态环里：客户端每帧都要读它（进度对齐、UI、附属 mod 的状态面板），
     * 走管道的话每帧一次系统调用往返，会把渲染线程拖住。
     */
    public long mediaPositionMs() {
        return this.status == null ? 0L : this.status.getLong(OFF_MEDIA_POSITION_MS);
    }

    /** 时长（毫秒）；未知返回 -1。 */
    public long mediaDurationMs() {
        if (this.status == null) return -1L;
        final long value = this.status.getLong(OFF_MEDIA_DURATION_MS);
        return value <= 0 ? -1L : value;
    }

    public int audioSampleRate() {
        return this.status == null ? 0 : this.status.getInt(OFF_AUDIO_SAMPLE_RATE);
    }

    /** 子进程汇报的暂停状态。 */
    public boolean pausedByStatus() {
        return this.status != null && this.status.getInt(OFF_PAUSED) != 0;
    }

    /** 最近一次 seek 的目标；没 seek 过返回 -1。 */
    public long lastSeekTargetMs() {
        if (this.status == null) return -1L;
        return this.status.getLong(OFF_LAST_SEEK_TARGET_MS);
    }

    /** 音频输出形态：0=共享 1=独占PCM 2=IEC61937直通。 */
    public int audioModeId() {
        return this.status == null ? 0 : this.status.getInt(OFF_AUDIO_MODE);
    }

    /** 输出形态名（日志/UI 用）。 */
    public String audioModeName() {
        return switch (audioModeId()) {
            case 2 -> "IEC61937直通";
            case 1 -> "独占PCM";
            default -> "共享模式";
        };
    }

    /**
     * 直通模式下音量是否不可调。
     *
     * <p>码流不能被修改 —— 音量由功放控制。客户端据此**停止每帧推音量**（推了也没用，
     * 而且是一帧一次管道往返），UI 也不该把音量显示成可调。
     */
    public boolean volumeLocked() {
        return audioModeId() == 2;
    }

    /** 输出声道数（直通时是链路声道数：DD+ 为 2、TrueHD 为 8）。 */
    public int audioChannels() {
        return this.status == null ? 0 : this.status.getInt(OFF_AUDIO_CHANNELS);
    }

    /** 解码后的内容采样率（直通时链路是 4× 内容或 192k，这个字段才是音轨本身的）。 */
    public int audioContentRate() {
        return this.status == null ? 0 : this.status.getInt(OFF_AUDIO_CONTENT_RATE);
    }

    /**
     * 是否已播到片尾（未循环）。
     *
     * <p>片尾后子进程**不退出**（保持最后一帧待命），所以判断"播完了"必须看这个标记 ——
     * 看"进程还在不在"会把正常结束误判成崩溃。
     */
    public boolean endedByStatus() {
        return this.status != null && this.status.getInt(OFF_ENDED) != 0;
    }

    /**
     * 端点空间音效（Dolby Atmos 等）状态：0 = 端点支持但未启用，1 = 已启用，
     * 2 = 查不到（系统未上报），3 = 端点不支持 Windows 空间音效。
     *
     * <p>数据来自子进程对 WinRT {@code SpatialAudioDeviceConfiguration} 的查询
     * （用户选择 = DefaultSpatialAudioFormat、系统当前在跑 = ActiveSpatialAudioFormat）——
     * 这是"到底有没有在输出 Atmos"唯一能程序化拿到的官方信号。玩家没法靠耳朵可靠区分
     * "真 Atmos" 与 "普通多声道"，所以客户端用它在聊天框里给本机玩家播报一次。
     */
    public int spatialAudioState() {
        return this.status == null ? 2 : this.status.getInt(OFF_SPATIAL_AUDIO);
    }

    /**
     * 空间音效格式名（已翻成人话，如 {@code Dolby Atmos for home theater（杜比全景声·家庭影院）}）；
     * 未启用或查不到返回空串。
     *
     * <p>来自子进程对 WinRT 的查询 —— 零 IPC，直接读共享内存。
     */
    public String spatialAudioFormat() {
        if (this.status == null) return "";
        return readUtf8(OFF_SPATIAL_FORMAT, SPATIAL_FORMAT_BYTES);
    }

    /** 实际打开的音频端点名（如 "COLOGCS-1 (NVIDIA High Definition Audio)"）；未知返回空串。 */
    public String audioDeviceName() {
        if (this.status == null) return "";
        return readUtf8(OFF_AUDIO_DEVICE_NAME, AUDIO_DEVICE_NAME_BYTES);
    }

    // =====================================================================
    // v4：解码路径 / 缓冲 / 丢帧 / 定位自愈（全是子进程 v2 流水线新增的诊断）
    // =====================================================================

    /** 解码路径：0=未知 1=硬解(d3d11va) 2=软解(CPU) 3=硬解帧回拷 CPU。 */
    public int decodeModeId() {
        return this.status == null ? 0 : this.status.getInt(OFF_DECODE_MODE);
    }

    /**
     * 解码路径的人话名。
     *
     * <p>为什么值得显示出来：硬解不可用时子进程会**自动**回退软解（画面照出、CPU 上去）、
     * 设备没有可用 VideoProcessor 时会回拷 CPU 再转 —— 这两件事上层以前完全看不见，
     * 玩家只会感觉"风扇突然狂转 / 掉帧"。有了它，`/cineduct status` 就能给个解释。
     */
    public String decodeModeName() {
        return switch (decodeModeId()) {
            case 1 -> "硬解(d3d11va)";
            case 2 -> "软解(CPU)";
            case 3 -> "硬解+CPU回拷";
            default -> "未知";
        };
    }

    /** 是否正在"缓冲中/等数据"（呈现侧没有新帧可出）。 */
    public boolean buffering() {
        return this.status != null && this.status.getInt(OFF_BUFFERING) != 0;
    }

    /**
     * 累计"迟到丢弃"的帧数。
     *
     * <p>主时钟判定里，晚于 100ms 且队列里已有更新帧时才会丢这一帧（正常追赶行为）；
     * 数量持续快速增长才说明**解码跟不上**（比如 4K 软解）。
     */
    public long droppedLateFrames() {
        return this.status == null ? 0L : this.status.getLong(OFF_DROPPED_LATE);
    }

    /**
     * 累计"定位自愈"次数。
     *
     * <p>有些流根本不能定位（连 ffmpeg 自己的 `-ss` 都解不出帧）：子进程会退回 2 秒 / 回起点
     * 继续播，而不是把 EOF 当片尾（老版本的表现是"点了跳转 → 播放结束"）。
     * 发生过就该让玩家知道"这次跳转没能到你要的位置"。
     */
    public long seekHealCount() {
        return this.status == null ? 0L : this.status.getInt(OFF_SEEK_HEAL_COUNT) & 0xFFFFFFFFL;
    }

    /**
     * 最近发布的那一帧在媒体时间轴上的位置（毫秒）；还没有帧时返回 0。
     *
     * <p>它与 {@link #mediaPositionMs()}（音频时钟上的位置）**一减就是音画偏移**：
     * 正数 = 画面领先声音。以前没有这个字段，"暂停之后音画错开"这类问题只能靠人眼判断，
     * 既没法量化也没法做回归断言。
     */
    public long videoPtsMs() {
        return this.status == null ? 0L : this.status.getLong(OFF_VIDEO_PTS_MS);
    }

    /**
     * 累计"音画偏移自愈"次数（重锚时钟）—— 正常播放恒为 0。
     *
     * <p>子进程默认会自己纠正音画偏移：动作是把**时钟**重锚到实测的可听内容位置，
     * 声卡一个字节都不动（所以不会有跳音），代价是画面跳一下追上去/等一等。
     * 因此这个数大于 0 就等于"这次播放真的错开过" —— 上层该把这件事作为一条事件报出来，
     * 而不是让用户只留下"好像不同步了"的模糊印象。
     */
    public long clockHealCount() {
        return this.status == null ? 0L : this.status.getInt(OFF_CLOCK_HEAL_COUNT) & 0xFFFFFFFFL;
    }

    // =====================================================================
    // v8：音频健康
    //
    // ★ 为什么必须由子进程上报：子进程模式下**音频整条链都在子进程里**（WASAPI 解码+输出），
    // 游戏进程既没有音频缓冲也没有声卡 —— "音频够不够、有没有断、时钟是不是已经改走挂钟"
    // 除了问子进程没有第二个来源。以前状态环里只有"音频形态/声道/采样率"三个静态描述，
    // 于是音频真的断了上层完全看不见。
    // =====================================================================

    /** 累计声卡欠载次数（设备缓冲见底且无数据可喂 = 耳朵听到的那一下静音）。 */
    public long audioUnderruns() {
        return this.status == null ? 0L : this.status.getInt(OFF_AUDIO_UNDERRUNS) & 0xFFFFFFFFL;
    }

    /**
     * 音频缓冲水位（毫秒）= 环形缓冲 + 设备缓冲里**还没播出去**的量。
     *
     * <p>这就是"音频还能撑多久"。它贴近 0 就意味着下一拍就要断音 ——
     * 与视频侧那个 {@link #buffering()} 位合起来才是完整的"够不够播"。
     */
    public int audioQueuedMs() {
        return this.status == null ? 0 : this.status.getInt(OFF_AUDIO_QUEUED_MS);
    }

    /** 时钟是否已判定"音频停滞"并改走挂钟（画面继续走，但声音很可能已经中断）。 */
    public boolean audioStalled() {
        return this.status != null && this.status.getInt(OFF_AUDIO_STALLED) != 0;
    }

    /** 音频链是否已经失败/未参与（画面照走，但**这一路没有声音**）。 */
    public boolean audioFailed() {
        return this.status != null && this.status.getInt(OFF_AUDIO_FAILED) != 0;
    }

    /** 播放位置读数抖动被拒次数（诊断：>0 说明设备待播读数确实在抖）。 */
    public long audioJitterRejects() {
        return this.status == null ? 0L : this.status.getInt(OFF_AUDIO_JITTER_REJECTS) & 0xFFFFFFFFL;
    }

    /** 从状态环里读一段 NUL 结尾的 UTF-8 文本。 */
    private String readUtf8(int offset, int maxBytes) {
        final byte[] raw = new byte[maxBytes];
        for (int i = 0; i < maxBytes; i++) {
            raw[i] = this.status.get(offset + i);
        }
        int length = 0;
        while (length < maxBytes && raw[length] != 0) length++;
        return length == 0 ? "" : new String(raw, 0, length, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 读一个 v9 身份字段（37 字节 UUID 文本）；没映射/老 exe 时返回空串。 */
    private String readUuidField(int offset) {
        if (this.status == null) return "";
        return readUtf8(offset, UUID_BYTES);
    }

    /** v10：音频读失败次数（网络中断/超时，**不是片尾**）。 */
    public long audioReadErrors() {
        return this.status == null ? 0L : this.status.getInt(OFF_AUDIO_READ_ERRORS) & 0xFFFFFFFFL;
    }

    /** v10：音频链重连次数（重开输入并回到原位置）。 */
    public long audioReconnects() {
        return this.status == null ? 0L : this.status.getInt(OFF_AUDIO_RECONNECTS) & 0xFFFFFFFFL;
    }

    /** v10：宿主自动修复"音频僵死"的次数（见子进程的 maybeRepairAudio）。 */
    public long audioRepairs() {
        return this.status == null ? 0L : this.status.getInt(OFF_AUDIO_REPAIRS) & 0xFFFFFFFFL;
    }

    /** 状态环里由**子进程**写的会话 UUID（老 exe 为空串）—— 诊断用，正常应与 {@link #sessionUuid()} 相同。 */
    public String sessionUuidFromRing() {
        return readUuidField(OFF_SESSION_UUID);
    }

    // =====================================================================
    // 控制
    // =====================================================================

    /** 发一条命令，返回回执文本（失败返回 null）。 */
    public synchronized String send(String command) {
        // 已关闭的会话不再说话：管道句柄这时已经交给后台收尾线程了，用了就是竞态
        if (this.closed.get() || this.pipe == 0L) return null;
        final String reply = NativeD3D.pipeRequest(this.pipe, command);
        if (reply == null) {
            CineDuct.LOGGER.warn("[CineDuct] 控制命令失败（子进程可能已退出）: {}", command);
        }
        return reply;
    }

    public void setPaused(boolean wantPaused) {
        this.paused = wantPaused;
        this.send(wantPaused ? "pause 1" : "pause 0");
    }

    /**
     * 跳转到指定位置（毫秒）。
     *
     * <p>子进程侧会把四样东西一起跳：视频 demuxer 定位 + 解码器清缓冲、
     * 音频 demuxer 定位 + 解码器清缓冲 + **声卡缓冲清空**、音频时钟重锚、丢掉定位点之前的前导帧。
     */
    public boolean seek(long ms) {
        final String reply = this.send("seek " + Math.max(0L, ms));
        return reply != null && reply.startsWith("ok");
    }

    public boolean isPaused() {
        return this.paused;
    }

    /** 循环播放开关（运行期可改：子进程放完后回卷重播）。 */
    public void setLoop(boolean wantLoop) {
        this.send(wantLoop ? "loop 1" : "loop 0");
    }

    /**
     * 音画偏移（毫秒，运行期可改）：<b>正 = 画面提前</b>，负 = 画面延后。
     *
     * <p>用来补偿"音频设备 → 功放/回音壁 → 耳朵"那段物理延迟（画面看起来领先时给负值）。
     * 改完立刻生效，不用重启子进程（这正是做成动词的原因：这条值只能看着画面调）。
     */
    public void setAvOffsetMs(int ms) {
        this.send("av-offset " + ms);
    }

    public void setVolumePercent(int percent) {
        this.volumePercent = Math.max(0, Math.min(100, percent));
        this.send("volume " + this.volumePercent);
    }

    public int volumePercent() {
        return this.volumePercent;
    }

    /** 子进程自己报的位置（音频时钟 / 画面 PTS）。 */
    public String statusLine() {
        return this.send("status");
    }

    /**
     * 进程是否还活着。
     *
     * <p>★ 还没 spawn 出来时返回 **false**，调用方必须自己看状态：
     * {@code State.STARTING} 期间"alive=false"是正常的（命令行都还没执行），
     * 拿它判"崩了"就会把刚建好的会话立刻判死（见 MediaSession.checkHostHealth 的 STARTING 早退）。
     */
    public boolean alive() {
        final Process p = this.process;
        return !this.closed.get() && p != null && p.isAlive();
    }

    /** 子进程 pid（诊断/日志用）；还没起来或已关闭返回 -1。 */
    public long pid() {
        final Process p = this.process;
        return p == null ? -1L : p.pid();
    }

    /**
     * 子进程是否**异常退出**（崩溃，或非 0 退出码）。
     *
     * <p>用来区分"崩了"与"我们主动关的"：崩溃需要换一种音频形态再试，
     * 而不是原样重启（原样重启会在同一个端点上反复把音频引擎搞崩）。
     */
    public boolean abnormalExit() {
        final Process p = this.process;
        if (p == null || p.isAlive()) return false;   // 还没被 spawn 出来的进程不算"异常退出"
        try {
            return p.exitValue() != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    public long uptimeMs() {
        return System.currentTimeMillis() - this.startedAt;
    }

    // =====================================================================
    // 关闭
    // =====================================================================

    @Override
    public void close() {
        this.shutdown(true);
    }

    /**
     * 放弃这条子进程：**不等它优雅退出**，直接强杀。
     *
     * <p>为什么需要另一条关闭路径：优雅关闭最多要等 4 秒（2s 等 quit + 2s 等 destroy），
     * 而"回退到进程内引擎"这条路上的调用者可能是**渲染线程**（互操作建立失败时）——
     * 在那儿堵 4 秒就是整个游戏画面卡住 4 秒。回退本来就意味着"这条进程我们已经不要了"，
     * 所以要的是尽快把音频设备/共享纹理还回来，而不是等它自己收拾干净：
     * `destroyForcibly()` 之后进程立刻消失，WASAPI 端点和共享纹理都由系统回收。
     *
     * <p>代价：子进程来不及打它的收尾日志（比如最终位置、丢帧统计）——回退场景下无所谓，
     * 因为位置我们在调用前已经自己记下来了（见 MediaSession.failHost 的 ★）。
     */
    public void abandon() {
        this.shutdown(false);
    }

    /**
     * 关闭会话 —— <b>承诺不阻塞调用线程</b>。
     *
     * <p>★ 老实现把三件**阻塞的事**都做在调用线程上，而调用线程可能是渲染线程 / 服务端线程 /
     * 客户端主线程（关屏、退出世界、回退到进程内引擎、`/cineduct` 指令）：
     * <ol>
     *   <li>{@code send("quit")} —— 它是**同步读写命名管道并等回执**的，
     *       子进程卡死时这一步自己就能无限期挂住；</li>
     *   <li>{@code waitFor(2s)} + {@code destroy()} + {@code waitFor(2s)} —— 最坏 4 秒；</li>
     *   <li>{@code abandon()} 路径的 {@code waitFor(800ms)} —— 0.8 秒。</li>
     * </ol>
     * 实测症状就是"开屏/关屏、退出世界时会顿一下"。
     *
     * <p>现在拆成两半：
     * <ul>
     *   <li><b>调用线程</b>只做必须在这里做、且**不阻塞**的事：还掉状态环映射（调用方就是唯一读者）、
     *       非优雅路径立刻 {@code destroyForcibly()}（内核调用，不等它死）、把进程/Job/管道
     *       交给一次性守护线程；</li>
     *   <li><b>后台线程</b>负责发 quit、等退出、必要时强杀、回收 Job 句柄 —— 慢一点完全不影响游戏，
     *       而且 Job 是 KILL_ON_JOB_CLOSE：真到了游戏退出那一刻，句柄一关内核就会把它连根拔掉。</li>
     * </ul>
     */
    private void shutdown(boolean graceful) {
        if (!this.closed.compareAndSet(false, true)) return;
        this.state = State.CLOSED;

        final Process process = this.process;
        final long pipe = this.pipe;
        final long job = this.job;
        this.pipe = 0L;    // 其它线程的 send() 立刻停手（管道交给后台线程去关）
        this.job = 0L;

        /*
         * ★ 统计数字必须在**关掉状态环之前**读。
         *
         * <p>它们都是从状态环（共享内存）里读的，而下面马上就要 UnmapViewOfFile ——
         * 老代码把日志写在关闭之后，于是收尾日志永远是「发布 0 帧，丢弃 0」
         * （现场日志里每条都是 0，明明播了很久）。纯日志问题，但会误导排查。
         */
        final long published = publishedFrames();
        final long dropped = droppedFrames();
        final long uptime = uptimeMs();

        // 状态环（共享内存）由调用线程关：它就是唯一的读者，非阻塞
        if (this.status != null) {
            NativeD3D.closeSharedSection(this.status);
            this.status = null;
        }

        // 非优雅 = 我们已经不要它了：立刻杀（TerminateProcess，不等它变僵尸）
        if (!graceful && process != null) {
            try {
                process.destroyForcibly();
            } catch (Throwable ignored) {
            }
        }

        final long sessionId = this.sessionId;
        Thread reaper = new Thread(
                () -> reap(pipe, job, process, graceful, sessionId, published, dropped, uptime),
                "cineduct-host-reap-" + sessionId);
        reaper.setDaemon(true);
        reaper.start();
    }

    /**
     * 后台收尾：让子进程自己退（优雅）→ 必要时强杀 → 回收 Job 句柄与日志。
     *
     * <p>它做的事和老实现完全一样，区别只是**不在调用线程上**。
     */
    private static void reap(long pipe, long job, Process process, boolean graceful, long sessionId,
                             long published, long dropped, long uptime) {
        /*
         * 看门狗：`send("quit")` 是**同步读管道**，子进程要是彻底卡死（不读管道也不退出），
         * 这一步就能一直挂着 —— 那 Job 句柄就永远收不回来了。
         * 这条线程保证"到点就强杀"；孩子一死，它那端的管道句柄关闭，
         * 正在 ReadFile 的收尾线程会立刻返回，于是 jobClose 照样会执行。
         * （只强杀、不碰 Job：两个线程同时 jobClose 同一个句柄是危险的。）
         */
        final java.util.concurrent.atomic.AtomicBoolean finished =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(5000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!finished.get() && process != null) {
                CineDuct.LOGGER.warn("[CineDuct] 子进程会话 #{} 收尾超过 5 秒未完成（卡死？），强制结束",
                        sessionId);
                process.destroyForcibly();
            }
        }, "cineduct-host-watchdog-" + sessionId);
        watchdog.setDaemon(true);
        watchdog.start();

        try {
            if (pipe != 0L) {
                if (graceful) {
                    // 让子进程自己收尾（写最终统计、正常关 WASAPI）。回执在这里等，卡的是这条线程。
                    NativeD3D.pipeRequest(pipe, "quit");
                }
                NativeD3D.pipeClose(pipe);
            }
        } catch (Throwable ignored) {
        }
        if (process != null) {
            try {
                if (graceful) {
                    if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                        process.destroy();
                        if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                            process.destroyForcibly();
                        }
                    }
                } else {
                    process.waitFor(800, java.util.concurrent.TimeUnit.MILLISECONDS);
                }
            } catch (Throwable ignored) {
                process.destroyForcibly();
            }
        }
        finished.set(true);
        // 释放 Job 句柄：此刻若子进程还活着（卡死没响应 quit/destroy），
        // KILL_ON_JOB_CLOSE 会立刻把它杀掉 —— 不留孤儿
        if (job != 0L) {
            try {
                NativeD3D.jobClose(job);
            } catch (Throwable ignored) {
            }
        }
        CineDuct.LOGGER.info("[CineDuct] 子进程会话 #{} 已结束（运行 {}ms，发布 {} 帧，丢弃 {}）",
                sessionId, uptime, published, dropped);
    }
}

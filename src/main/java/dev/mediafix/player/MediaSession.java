package dev.mediafix.player;

import dev.mediafix.CineDuct;
import dev.mediafix.api.PlaybackState;
import dev.mediafix.api.PlaybackStatus;
import dev.mediafix.api.ResolvedStream;
import dev.mediafix.engine.MediaEngine;
import dev.mediafix.ffmpeg.FfmpegSources;
import dev.mediafix.gpu.GlInterop;
import dev.mediafix.host.HostSession;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一条播放会话：一块屏幕（或共用同一源的几块屏幕）背后的播放器。
 *
 * <p><b>两种传输方式，同一个 API</b>：
 * <ol>
 *   <li><b>dxgi-interop（优先）</b>：画面与声音由 C++ 子进程产出（d3d11va 硬解 + WASAPI），
 *       帧经 DXGI 共享纹理进来，GL 采样本进程导入的那张纹理 —— 不经过 CPU，
 *       解码崩了也只死子进程。见 {@link HostSession} 与 {@link GlInterop}。</li>
 *   <li><b>java（兜底，永远可用）</b>：进程内自研 FFmpeg 引擎（{@link MediaEngine}），
 *       画面 CPU 上传、声音走自研声卡。子进程缺失/驱动不支持互操作/子进程崩了都自动落回这条。</li>
 * </ol>
 * 上层（屏幕渲染、指令、API）只看 {@link #acquireDrawTexture} —— 不关心是哪一种。
 *
 * <p><b>线程</b>：控制方法任意线程可调；{@code acquireDrawTexture/releaseDrawTexture}
 * 必须在渲染线程（GL 上下文当前）调用。
 */
public final class MediaSession implements AutoCloseable {

    public static final String TRANSPORT_INTEROP = "dxgi-interop";
    public static final String TRANSPORT_JAVA = "java";

    private enum Mode {
        /** 已决定走子进程，等它就绪。 */
        HOST,
        /** 进程内引擎。 */
        JAVA
    }

    private final long id;
    private final ResolvedStream source;
    /** 逻辑会话 UUID（跨重启唯一；见构造器里的说明与 docs/host-uuid.md）。 */
    private final java.util.UUID sessionUuid;
    private final AtomicInteger refs = new AtomicInteger(1);
    private volatile boolean closed;

    private volatile Mode mode;
    /** 子进程会话（mode == HOST 时非空）。 */
    private volatile HostSession host;
    /** 互操作对象（渲染线程建立；仅 HOST 模式用）。 */
    private volatile GlInterop interop;
    /** 兜底引擎（懒创建：只有真的需要时才开，避免白白多占两条 CDN 连接）。 */
    private volatile MediaEngine engine;
    private volatile String lastError = "";
    private volatile int volumePercent = 100;
    private volatile boolean muted;
    private volatile boolean repeat;
    private volatile boolean pauseWanted;
    private volatile long pendingSeekMs = -1L;
    /** 重启子进程后要续播到的位置（-1 = 不续播）。 */
    private volatile long resumeSeekMs = -1L;

    // ---- 子进程自愈（崩溃/卡死自动重启）----
    /** 单次窗口内允许的重启次数上限；超了就落回进程内引擎。 */
    private static final int RECOVER_MAX = 3;
    /** 上述窗口长度。 */
    private static final long RECOVER_WINDOW_MS = 60_000L;
    /** 连续健康这么久就把重启计数清零（避免"很久以前崩过两次"一直卡在临界）。 */
    private static final long RECOVER_HEALTHY_MS = 60_000L;
    /** 判定"卡死"的无进展时长：不暂停、没到片尾，却这么久没有新帧。 */
    private static final long STALL_TIMEOUT_MS = 8_000L;
    /**
     * seek 之后的宽限期：这期间不算卡死（定位本身要时间）。
     *
     * <p><b>为什么必须有上限</b>：卡死判定里原本写的是"pendingSeekMs >= 0 就跳过"，
     * 而 pendingSeekMs 只有在子进程**真的播到目标位置**之后才会清掉 ——
     * 于是"seek 之后子进程死锁"这种情况永远被跳过、永远不会自愈
     * （现场表现：没声音、画面停在 1~2 帧，游戏却完全正常）。
     * 现在改成"只有宽限期内才跳过"，超时后照常判定，8 秒后自动重启。
     */
    private static final long SEEK_GRACE_MS = 20_000L;

    private volatile boolean recovering;
    private volatile int recoverAttempts;
    private volatile int recoverCount;
    private volatile long recoverWindowStart;
    private volatile long healthySince;
    private volatile long lastProgressAt;
    private volatile long lastPublishedFrames = -1L;
    private volatile long lastSeekRequestAt;

    /**
     * 宿主报"没有新帧可出"要**连续**这么久，才当成真的进了缓冲（见 {@link #hostBuffering()}）。
     *
     * <p>为什么必须有迟滞：宿主那个 {@code buffering} 位是"呈现侧此刻有没有新帧"，
     * 正常播放里为了等时钟一两帧就会翻一次。直接拿它当状态用，{@code PlaybackState} 会逐帧抖，
     * 附属 mod 的加载提示也会跟着闪。400ms 取自进程内引擎同一条判据
     * （{@code MediaEngine}：视频判定饥饿超过 400ms 才进 BUFFERING），两条路语义对齐。
     */
    private static final long HOST_BUFFERING_MS = 400L;

    /** 宿主开始报"没有新帧"的时刻；0 = 当前不在缓冲。渲染线程与 API 线程都可能读写。 */
    private volatile long hostBufferingSince;

    /**
     * 音频水位低于低水位线的判定线（毫秒）。
     *
     * <p>取 300ms：设备缓冲本身约 200ms（实测 9600 帧 @48k），低于这个数就意味着
     * "下一拍就要断音"。进程内引擎看的是同一个量级（音频环形缓冲见底）。
     */
    private static final int HOST_AUDIO_LOW_MS = 300;

    /** 音频开始"饿着"的时刻；0 = 当前不饿。 */
    private volatile long hostAudioStarvedSince;

    /** 上次见到的欠载累计值（-1 = 还没对齐基线）；用于把"刚断了一下"变成一条事件。 */
    private volatile long lastAudioUnderruns = -1L;
    /** v10：音频链读失败 / 重连 / 自动修复的基线（-1 = 还没对齐）。 */
    private volatile long lastAudioReadErrors = -1L;
    private volatile long lastAudioReconnects = -1L;
    private volatile long lastAudioRepairs = -1L;
    /** 音频"饿着"的警告是否已经打过（进入/退出各一条，别刷屏）。 */
    private volatile boolean audioStarvedLogged;

    /** 上一次对外报出的播放状态（只为打转换日志，见 {@link #noteStateTransition}）。 */
    private volatile PlaybackState lastReportedState;

    /**
     * 音频形态被降级成共享模式（该端点的独占/直通会把 Windows 音频引擎搞崩）。
     *
     * <p>为什么需要它：`auto` 会给 Dolby 音轨选直通（IEC 61937 = 独占模式），
     * 而某些显卡 HDMI 端点在独占模式下会让音频引擎崩（实测 audiokse.dll 访问违例，
     * 子进程 2~5 秒后整个消失）。原样重启只会在同一端点上反复崩 ——
     * 所以一旦发现"崩在独占/直通音频上"，本次会话就改用共享模式（有声音、没有全景声直通）。
     */
    private volatile boolean audioDowngraded;

    /** 本次取帧用的槽位（渲染线程独占：取帧时设定，release 时解锁）。 */
    private int drawSlot = -1;
    /** 需要在本线程（渲染线程）重建互操作对象（子进程重建后置位）。 */
    private volatile boolean needsGlReset;
    /** 本帧要画的纹理所代表的比例（HOST 模式是视频自身比例，JAVA 模式已信箱化到屏幕比例）。 */
    private volatile float drawAspect;
    /** Java 模式：是否已经往屏幕纹理上传过至少一帧（决定"没新帧时能不能保持上一帧"）。 */
    private boolean javaFrameUploaded;

    /**
     * 每块屏幕要求的音频输出形态覆盖（null = 跟随全局配置）。
     *
     * <p>来自 {@code ScreenEntity.audioModeOverride()}：影院那块屏可以强制直通（全景声），
     * 旁边的背景屏照样走共享模式。值为 {@code auto/shared/exclusive/passthrough}。
     */
    private volatile String audioModeOverride;

    /** 调用方要求的帧传输方式（AUTO/ZERO_COPY/IN_PROCESS）；也参与会话复用判定。 */
    private volatile dev.mediafix.api.player.Transport transportPref =
            dev.mediafix.api.player.Transport.AUTO;

    /** 真正要传给子进程的音频形态：播放中降级过就强制共享，否则用屏幕自己的要求。 */
    private String requestedAudioMode() {
        return this.audioDowngraded ? "shared" : this.audioModeOverride;
    }

    /**
     * 显示面宽高比（宽/高）；{@code <= 0} = 不做信箱化（默认）。
     *
     * <p>开启后**两条链路都会把黑边烘焙进纹理**：
     * <ul>
     *   <li>子进程（零拷贝）：交给 {@code --fit-aspect}，子进程按这个比例算输出框、画面等比放进去、
     *       框外清成黑色 —— 纹理本身就是"带黑边的一整幅画面"；</li>
     *   <li>进程内引擎：上传前按这个比例做信箱化（{@code Letterbox}）。</li>
     * </ul>
     * 于是调用方可以放心把整张纹理铺满自己的显示面（不会拉伸），黑边也是真黑边。
     * 此时 {@link #drawAspect()} 返回的是**这张纹理自己的比例**（= 显示面比例）。
     *
     * <p>改这个值：进程内引擎下一帧就生效；零拷贝要重开子进程（输出尺寸是开进程时定的），
     * 所以 {@link #setDisplayAspect(float)} 会走一次 {@link #rebuild()}（位置自动续播）。
     */
    private volatile float displayAspect;

    /**
     * 最近一次调用方来取帧（{@link #acquireDrawTexture}）的时刻。
     *
     * <p>用来区分"没人看"与"子进程卡死"：子进程发帧靠消费者交还槽位（键控互斥乒乓），
     * 没人取帧时它本来就会停下 —— 那是正常的，不该被判成卡死并重启。
     */
    private volatile long lastDrawRequestAt;

    /**
     * 子进程诊断字段的上一次取值（-1 = 还没读到过）。
     *
     * <p>仅在渲染线程读写（见 {@link #reportHostDiagnostics}）：值变了才打日志，
     * 避免"解码路径/自愈次数"这种一次性信息每帧刷屏。
     */
    private int lastDecodeModeId = -1;
    private long lastSeekHealCount;
    /** 音画偏移自愈（重锚时钟）次数上一次取值 —— 变了就报一条事件日志。 */
    private long lastClockHealCount;
    /** 音画偏移日志限流（每 5 秒最多一行）。 */
    private long lastAvOffsetLogAt;
    /*
     * v11：子进程"等缓存"（闸门）的上一次取值。
     *
     * 为什么要在这里记事件而不是每帧打：闸门一次进出就是一对日志，
     * 玩家看日志时"卡了一下"与"等缓存 N 毫秒"能直接对上；
     * 每帧打的话就淹没在噪声里了（而且状态环是每帧更新的）。
     */
    private long lastGateCount;
    private boolean lastGateActive;

    /*
     * ★ 子进程"推迟启动"。
     *
     * 现场（2026-09-27 日志实证）：附属模组是在**显示面第一次渲染**时才算比例并调
     * setDisplayAspect 的（compat 的 DisplayRendererMixin），那已经是"会话创建后 2~3 秒"。
     * 而零拷贝要把黑边烘进子进程的输出纹理里 —— 输出尺寸只有开进程时能定，
     * 所以旧流程是：立刻起子进程 → 比例姗姗来迟 → setDisplayAspect 只能**杀掉重开**。
     * 结果**每开一块屏都白起一次子进程**（首帧白白多等 ~2.5 秒），日志里就是
     * "会话 #N 已创建 → 2~7 秒后 → 会话 #N 显示面比例改为 1.7727 → 重开子进程"。
     *
     * 现在改成：创建时**先不起**，等"比例已知 / 首次取帧 / 兜底超时"三者里最先到的那一个。
     * 正常情况是"首次取帧"赢，而比例在同一帧里更早设好（mixin 先 setDisplayAspect 再画），
     * 于是子进程一开始就用对的比例，**一次都不用重开**。
     * 兜底超时是为了"屏幕被剔除/一直没人取帧"时不至于永远不出声。
     */
    private volatile boolean hostSpawnPending;
    private volatile long hostSpawnDeadlineMs;
    /** 兜底：这么久还没人取帧也把子进程起了（保证音频照常起播）。 */
    private static final long HOST_SPAWN_FALLBACK_MS = 1500L;

    /**
     * 上一次画出去的帧号（子进程每发布一帧 +1）。
     *
     * <p>纯诊断：帧号回退 = 画面顺序错乱（闪一下旧内容）。换子进程后帧号从 1 重新开始，
     * 所以重建时要重置成 -1，否则会被误判成"回退"。
     */
    private int lastDrawnGen = -1;
    /** 因为"只有更旧的帧可选"而保持上一帧的次数（正常情况下应该是 0 —— 上游已经作废旧槽位）。 */
    private volatile int staleHolds;

    public float displayAspect() {
        return this.displayAspect;
    }

    /** 要传给子进程的比例（<=0 表示不带这个参数）。 */
    private float requestedFitAspect() {
        return this.displayAspect > 0.01f ? this.displayAspect : 0f;
    }

    /**
     * 设置显示面比例（宽/高）；{@code <= 0} = 关掉信箱化。
     *
     * <p>零拷贝路径需要重开子进程才能换输出尺寸，所以这里会触发一次重建（画面会闪一下、位置续播）；
     * 同一比例重复设置是无操作。
     */
    public void setDisplayAspect(float aspect) {
        final float want = Float.isFinite(aspect) && aspect > 0.01f ? aspect : 0f;
        /*
         * ★ 阈值放宽到 1%（原来固定 0.005）。
         *
         * 附属模组**每帧**都会算一次比例（compat 的 mixin 就是这么写的），算出来的值会抖：
         * 16:9 的 1.7778 和脸面的 1.7727 只差 0.0051 —— 刚好越过原来的 0.005，
         * 于是两个来源来回设置就**来回重开子进程**（日志里能看到同一块屏反复重开）。
         * 1% 以内当噪声忽略：真换形状（4:3↔16:9 之类）差得远，不受影响。
         */
        final float cur = this.displayAspect;
        if (Math.abs(want - cur) <= Math.max(0.005f, cur * 0.01f)) return;
        this.displayAspect = want;
        if (this.hostSpawnPending) {
            // 子进程还没起（推迟启动中）：比例正好赶上了，直接用新比例起，不需要重开
            CineDuct.LOGGER.info("[CineDuct] 会话 #{} 显示面比例设为 {}（子进程尚未启动，随启动一并生效）",
                    this.id, want <= 0f ? "关（不做信箱化）" : String.format("%.4f", want));
            return;
        }
        if (this.mode == Mode.HOST && this.host != null) {
            CineDuct.LOGGER.info("[CineDuct] 会话 #{} 显示面比例改为 {} → 重开子进程以换输出尺寸",
                    this.id, want <= 0f ? "关（不做信箱化）" : String.format("%.4f", want));
            // 重建会保住播放位置（resumeSeekMs），并且渲染线程会重新导入互操作纹理
            try {
                rebuild();
            } catch (Throwable t) {
                CineDuct.LOGGER.warn("[CineDuct] 会话 #{} 因比例变化重建失败", this.id, t);
            }
        }
    }

    private MediaSession(long id, ResolvedStream source, Mode mode) {
        this.id = id;
        this.source = source;
        this.mode = mode;
        /*
         * ★ 逻辑会话 UUID：**这一刻生成，此后永不变**。
         *
         * <p>它与 {@code id} 的区别是"跨重启也唯一"：{@code id} 是进程内自增，
         * 重开游戏又从 1 开始；而这个 UUID 会进子进程命令行、进状态环、进日志文件名，
         * 是"事后能把某份日志/某个崩溃 dump 对回哪次播放"的唯一依据（见 docs/host-uuid.md）。
         */
        this.sessionUuid = java.util.UUID.randomUUID();
    }

    /**
     * 建会话。
     *
     * @param preferHost        true = 先试 C++ 子进程（需要已探测到可用的 GL 适配器 LUID）
     * @param audioModeOverride 该屏幕要求的音频输出形态（null = 跟随全局配置）
     */
    public static MediaSession create(long id, ResolvedStream source, boolean preferHost,
                                      String audioModeOverride,
                                      dev.mediafix.api.player.Transport transportPref) {
        MediaSession session = new MediaSession(id, source, preferHost ? Mode.HOST : Mode.JAVA);
        session.audioModeOverride = audioModeOverride;
        session.transportPref = transportPref;
        if (preferHost) {
            /*
             * ★ 不在这里起子进程，交给 spawnHostNow（比例已知 / 首次取帧 / 兜底超时）。
             * 原因见 hostSpawnPending 的注释：附属模组的显示面比例要等第一次渲染才算出来，
             * 立刻起进程只会导致"起来就为换输出尺寸再重开一次"。
             */
            session.hostSpawnPending = true;
            session.hostSpawnDeadlineMs = System.currentTimeMillis() + HOST_SPAWN_FALLBACK_MS;
            /*
             * ★ 兜底必须自带线程，不能指望 tick。
             *
             * 实测 Sessions.tickHealth() 只在"退出世界那一 tick"被调用（CineDuct.onClientTick
             * 里的 if (levelWasLoaded && !inWorld)），所以任何"每 tick 检查一次"的假设都会落空 ——
             * 屏幕被视锥剔除、一直没人取帧时，会话就会永远不出声（连音频都不起）。
             * 这里用一次性守护线程，风格和 startHostWait 的 cineduct-host-wait-N 一致。
             */
            final MediaSession self = session;
            Thread fallback = new Thread(() -> {
                final long wait = self.hostSpawnDeadlineMs - System.currentTimeMillis();
                if (wait > 0) {
                    try {
                        Thread.sleep(wait);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                self.tickDeferredSpawn();
            }, "cineduct-host-defer-" + id);
            fallback.setDaemon(true);
            fallback.start();
        } else {
            session.ensureEngine();
        }
        CineDuct.LOGGER.info("[CineDuct] 会话 #{} 已创建: transport={} video={} live={}", id,
                session.transport(), CineDuct.LOGGER.url(source.video()), source.live());
        return session;
    }

    /**
     * 真正把子进程起起来。
     *
     * @param why 触发原因（首次取帧 / 比例设定 / 兜底超时），写进日志便于对齐现象
     */
    private synchronized void spawnHostNow(String why) {
        if (!this.hostSpawnPending || this.closed) return;
        this.hostSpawnPending = false;
        /*
         * 防御：万一这条会话上已经有进程内引擎在跑（旧版本留下的 / 模式切换的异常路径），
         * 起子进程之前先把它关掉 —— 否则两条链同时出声（现场："两条音频一起播放"）。
         */
        final MediaEngine leftover = this.engine;
        if (leftover != null) {
            this.engine = null;
            CineDuct.LOGGER.warn("[CineDuct] 会话 #{} 启动子进程前关掉已存在的进程内引擎（避免两条链同时出声）",
                    this.id);
            try {
                leftover.close();
            } catch (Throwable t) {
                CineDuct.LOGGER.warn("[CineDuct] 会话 #{} 关闭多余引擎失败", this.id, t);
            }
        }
        /*
         * ★ 整条启动链都丢到后台线程：**连"准备命令行"都不在调用线程做**。
         *
         * <p>为什么连准备都不能留在这里：`HostSession.start()` 里会
         *   ① `resolveExecutable()` → `NativeFiles.file()`（按 CRC 校验并按需**解压 30MB 原生库**）、
         *   ② `FfmpegRuntime.nativesDir()`（同样可能触发解压 / 建目录）、
         *   ③ `FfmpegSources.buildHeaders()`（读 Cookie 等本地文件）、
         *   ④ 构造 ProcessBuilder 的字符串与列表。
         * 这些都是文件系统 I/O，首次启动（或换版本后第一次）能到**几百毫秒甚至更久**。
         * 而这条线程可能是**渲染线程**（会话第一帧取帧时才知道显示面比例 → 才决定起子进程），
         * 表现就是"开屏/换源那一下顿一下"。
         *
         * <p>所以渲染线程只留一个标记：准备 → CreateProcess → 等状态环 → 连管道
         * 全部由 `cineduct-host-wait-N` 做。失败照样降级到进程内引擎（在后台线程里切 mode）。
         */
        final String whyText = why;
        Thread t = new Thread(() -> startHostInBackground(whyText), "cineduct-host-wait-" + this.id);
        t.setDaemon(true);
        t.start();
    }

    /**
     * 后台完成"准备命令行 → CreateProcess → 等状态环 → 连管道"。
     *
     * <p>这条线程上做的都是慢活（文件 I/O、CreateProcess、最多 12 秒等就绪），
     * 所以它**绝不能**是渲染线程 —— 见 {@link #spawnHostNow} 的说明。
     */
    private void startHostInBackground(String why) {
        final HostSession candidate = HostSession.start(this.id, this.source,
                dev.mediafix.gpu.GpuContext.adapterLuid(), false, this.requestedAudioMode(),
                this.requestedFitAspect(), this.sessionUuid);
        if (candidate == null) {
            CineDuct.LOGGER.info("[CineDuct] 子进程不可用，会话 #{} 使用进程内引擎", this.id);
            synchronized (this) {
                if (this.closed) return;
                this.mode = Mode.JAVA;
                ensureEngine();
            }
            return;
        }
        synchronized (this) {
            if (this.closed) {   // 准备期间会话被关了：别留野进程
                candidate.close();
                return;
            }
            this.host = candidate;
            this.lastProgressAt = System.currentTimeMillis();
        }
        CineDuct.LOGGER.info("[CineDuct] 会话 #{} 正在后台启动子进程（{}）: 显示面比例 {}",
                this.id, why, this.displayAspect > 0.01f
                        ? String.format("%.4f", this.displayAspect) : "关（不做信箱化）");
        startHostWait(candidate);
    }

    /**
     * 客户端 tick：推迟启动的兜底。
     *
     * <p>没有它，"屏幕被视锥剔除 / 一直没人取帧"就会让会话永远不出声。
     */
    public void tickDeferredSpawn() {
        if (!this.hostSpawnPending || this.closed) return;
        if (System.currentTimeMillis() >= this.hostSpawnDeadlineMs) spawnHostNow("兜底超时");
    }

    /** 后台 spawn + 等状态环 + 连控制管道；就绪后由渲染线程导入纹理。 */
    private void startHostWait(HostSession candidate) {
        Thread t = new Thread(() -> {
            /*
             * ★ CreateProcess 在这里做，不在调用线程上做。
             *
             * <p>调用链可能是渲染线程（会话第一帧取帧时才知道显示面比例 → 才决定起子进程），
             * 而 `ProcessBuilder.start()` + `AssignProcessToJobObject` 都是阻塞调用 ——
             * 以前就是在渲染线程上做的，表现是"起屏/换源那一下顿一下"。
             * 现在渲染线程只拼命令行，创建、等环、连管道全在这条线程上。
             */
            if (!candidate.spawn()) {
                CineDuct.LOGGER.warn("[CineDuct] 子进程创建失败（{}），会话 #{} 改用进程内引擎",
                        candidate.lastError(), this.id);
                candidate.close();
                this.host = null;
                synchronized (this) {
                    this.mode = Mode.JAVA;
                    ensureEngine();
                }
                return;
            }
            if (!candidate.awaitRing(12_000L)) {
                CineDuct.LOGGER.warn("[CineDuct] 子进程没能在超时内就绪（{}），会话 #{} 改用进程内引擎",
                        candidate.lastError(), this.id);
                candidate.close();
                this.host = null;
                synchronized (this) {
                    this.mode = Mode.JAVA;
                    ensureEngine();
                }
                return;
            }
            candidate.connectPipe();
            // 新进程不知道我们这边的音量/暂停/循环/续播位置 —— 必须补推一次
            pushStateToHost(candidate);
            this.healthySince = System.currentTimeMillis();
            this.lastProgressAt = this.healthySince;
        }, "cineduct-host-wait-" + this.id);
        t.setDaemon(true);
        t.start();
    }

    /**
     * 把当前会话状态补推给一条**新起的**子进程。
     *
     * <p>为什么必须显式补推：{@code setVolumePercent/setMuted} 都做了去重（值没变就不发命令），
     * 但那只对"同一个进程"有意义 —— 新进程手里是默认值（音量 100、未暂停、不循环），
     * 不补推就会出现"重启后音量突然变满、暂停的屏幕自己动起来"。
     */
    private void pushStateToHost(HostSession h) {
        if (h == null) return;
        h.setVolumePercent(this.muted ? 0 : this.volumePercent);
        h.setPaused(this.pauseWanted);
        h.setLoop(this.repeat);
        final long resume = this.resumeSeekMs >= 0 ? this.resumeSeekMs : this.pendingSeekMs;
        if (resume > 0) {
            h.seek(resume);
            this.pendingSeekMs = resume;   // 报告位置先乐观地贴住目标，追上后自动清掉
            this.resumeSeekMs = -1L;
        }
    }

    /**
     * 渲染线程：把子进程状态环里的**诊断字段**变成日志（每帧调用，只有值真的变了才打日志）。
     *
     * <p>为什么值得每帧看一眼：子进程 v2 流水线有三件事是**自动发生、玩家看不见**的 ——
     *   * 硬解不可用时自动回退软解（画面照出、CPU 上去），或者设备没有可用 VideoProcessor
     *     时把硬解帧回拷 CPU 再转；
     *   * 主时钟判定里的"迟到丢帧"（正常追赶，但数量疯长就说明解码跟不上）；
     *   * 定位自愈（有些流连 ffmpeg 的 `-ss` 都解不出帧 → 子进程退回 2 秒/回起点继续播，
     *     而不是把 EOF 当片尾 —— 老版本的表现是"点了跳转 → 播放结束"）。
     * 这三件事以前只能翻 `[host#…]` 原始日志，现在在模组日志里就能一眼看到。
     */
    private void reportHostDiagnostics(HostSession h) {
        /*
         * v11：等缓存（闸门）事件。
         *
         * <p>这三行是"客户端卡顿时等缓存"这条机制**唯一的外部证据**：
         * 有"进入等缓存"就说明闸门真的触发了；只有"起播预缓冲完成"而没有进出，
         * 说明这次卡顿是靠水位兜住的，不是靠闸门。老 exe 不写这些字段 → gateActive 恒 false，
         * 一行业务日志都不会多出来（不会误导）。
         */
        final boolean gateNow = h.gateActive();
        final long gateCount = h.gateCount();
        if (gateNow && !this.lastGateActive) {
            CineDuct.LOGGER.info("[CineDuct] 会话 #{} 子进程进入**等缓存**（第 {} 次）：{}"
                            + "；位置暂停前进，恢复后从同一位置继续",
                    this.id, gateCount, h.bufferSummary());
        } else if (!gateNow && this.lastGateActive) {
            final long total = h.gateTotalMs();
            CineDuct.LOGGER.info("[CineDuct] 会话 #{} 等缓存结束（累计冻住 {}ms / {} 次）",
                    this.id, total, gateCount);
        }
        if (gateCount > this.lastGateCount) this.lastGateCount = gateCount;
        this.lastGateActive = gateNow;

        final int mode = h.decodeModeId();
        if (mode != this.lastDecodeModeId) {
            final int previous = this.lastDecodeModeId;
            this.lastDecodeModeId = mode;
            if (mode == 2) {
                CineDuct.LOGGER.warn("[CineDuct] 会话 #{} 子进程改用**软解**（硬解不可用或连续报错）："
                        + "画面正常，CPU 占用会明显上升", this.id);
            } else if (mode == 3) {
                CineDuct.LOGGER.warn("[CineDuct] 会话 #{} 硬解帧回拷 CPU 再转（该设备没有可用的"
                        + " VideoProcessor）：不再是零拷贝快路径", this.id);
            } else if (mode == 1 && previous <= 0) {
                CineDuct.LOGGER.info("[CineDuct] 会话 #{} 解码路径: 硬解(d3d11va)", this.id);
            }
        }
        final long healed = h.seekHealCount();
        if (healed > this.lastSeekHealCount) {
            this.lastSeekHealCount = healed;
            CineDuct.LOGGER.warn("[CineDuct] 会话 #{} 定位自愈第 {} 次：这个流不能精确跳转，"
                    + "子进程已退回继续播（位置以子进程汇报为准）", this.id, healed);
        }
        /*
         * 音画偏移自愈（v6 状态字段）：子进程默认会自己纠正 —— 动作是把**时钟**重锚到
         * 实测的可听内容位置（声卡一个字节都不动，所以没有跳音；代价是画面跳一下追上去
         * 或者等一等）。因此这个数大于 0 就等于"这次播放真的错开过"，
         * 必须作为一条明确事件报出来，而不能让玩家只留下"好像不同步"的模糊印象。
         */
        final long clockHeals = h.clockHealCount();
        if (clockHeals > this.lastClockHealCount) {
            this.lastClockHealCount = clockHeals;
            CineDuct.LOGGER.warn("[CineDuct] 会话 #{} 音画偏移已自动纠正第 {} 次"
                    + "（子进程重锚时钟，声卡未动 → 不会有跳音）；具体偏移数字见 cineductlogs "
                    + "里 host 的『重锚 / AV明细』行", this.id, clockHeals);
        }
        /*
         * 音画偏移：视频帧 PTS 与音频时钟位置之差（正 = 画面领先声音）。
         *
         * ★ 注意这一条**只能**发现"时钟级别"的错位：画面本来就是被这条音频时钟调速的，
         * 所以正常情况下它恒等于 0 —— 内容级别的错位（真正能听出来的那种）在子进程侧
         * 用"可听内容 = 写出内容 − 排队"算，只有 host 日志里有。这里保留它只是兜底。
         */
        final long videoPts = h.videoPtsMs();
        final long audioPos = h.mediaPositionMs();
        if (videoPts > 0 && audioPos > 0) {
            final long offset = videoPts - audioPos;
            final long now = System.currentTimeMillis();
            if (Math.abs(offset) > 400 && now - this.lastAvOffsetLogAt > 5000) {
                this.lastAvOffsetLogAt = now;
                CineDuct.LOGGER.warn("[CineDuct] 会话 #{} 音画偏移 {}{}ms（画面 {}ms / 音频 {}ms）"
                        + "—— 若持续存在请把日志发给作者", this.id, offset > 0 ? "+" : "", offset,
                        videoPts, audioPos);
            }
        }
    }

    /** 渲染线程：把子进程的共享纹理导入成 GL 纹理（子进程要解出第一帧才建，所以要按帧重试）。 */
    public boolean importHostTextures() {
        final HostSession h = this.host;
        if (h == null) return false;
        if (h.state() == HostSession.State.FAILED) {
            failHost(h.lastError());
            return false;
        }
        if (h.state() != HostSession.State.RING_READY) return false;
        GlInterop io = this.interop;
        if (io == null) {
            io = GlInterop.probe(h.width(), h.height());
            if (io == null) {
                failHost("互操作建立失败（" + dev.mediafix.gpu.GpuContext.reason() + "）");
                return false;
            }
            this.interop = io;
        }
        for (int i = 0; i < h.slotCount(); i++) {
            if (!io.importSlot(i, h.textureName(i))) {
                return false;   // 子进程还没建出纹理，下一帧再试
            }
        }
        h.markReady();
        CineDuct.LOGGER.info("[CineDuct] 会话 #{} 零拷贝就绪: {} 槽 {}x{}（transport={}）",
                this.id, h.slotCount(), h.width(), h.height(), TRANSPORT_INTEROP);
        return true;
    }

    private void failHost(String why) {
        CineDuct.LOGGER.warn("[CineDuct] 子进程播放失败（{}），会话 #{} 改用进程内引擎", why, this.id);
        final HostSession h;
        /*
         * ★ 第一件事是在锁里**先把模式切成 JAVA**，再去收进程。
         *
         * <p>顺序反过来会留一个窗口：重启线程（doRecover）正在起新子进程，我们这边先把
         * `host` 清成 null、几秒后再切模式 —— 那个新进程会在这几秒里被挂上来，
         * 于是"引擎在放 + 子进程也在放"，两块声卡同时出声（2026-09-27 那个病的翻版）。
         * 现在 doRecover 挂新进程时也要拿同一把锁并检查 mode，模式一旦是 JAVA 就再也不会被挂上。
         */
        synchronized (this) {
            this.mode = Mode.JAVA;
            h = this.host;
            this.host = null;
        }
        /*
         * ★ 回退的第一步：把"播到哪儿了"记下来 —— 这就是"能不能正常回退"的关键。
         *
         * <p>进程内引擎是**重新打开**两条链的，它不知道子进程播到哪了；不把位置喂给它，
         * 玩家看到的就是"崩一下之后视频跳回开头"（进度条归零、直播以外全都如此）。
         * 位置必须在关闭之前读：`close()/abandon()` 会释放状态环（共享内存），之后就读不到了。
         *
         * <p>直播例外：直播没有可跳位置（引擎的 seek 也会忽略），这里不去记，
         * 免得白白打一行"忽略 seek"（直播靠引擎自己的时间轴锚定，见 MediaEngine.liveAnchorMs）。
         */
        if (h != null && !isLive() && this.resumeSeekMs < 0) {
            final long pos = h.mediaPositionMs();
            if (pos > 0) {
                this.resumeSeekMs = pos;
                CineDuct.LOGGER.info("[CineDuct] 会话 #{} 回退前记下位置 {}ms（进程内引擎从这里续播）",
                        this.id, pos);
            }
        }
        /*
         * 放弃这条进程用 `abandon()` 而不是 `close()`：abandon 会**立刻** TerminateProcess
         * （回退意味着这条进程我们已经不要了，音频端点/共享纹理随进程回收，越快越好），
         * 而 close 走的是"先发 quit 让它自己收尾"的优雅路径。
         *
         * ★ 两条路现在都**不阻塞调用线程**了（等回执/等退出/强杀都在后台收尾线程里），
         * 所以就算 failHost 是在渲染线程上被调用（互操作建立失败），也不会再卡住画面。
         */
        if (h != null) {
            try {
                h.abandon();
            } catch (Throwable t) {
                CineDuct.LOGGER.warn("[CineDuct] 会话 #{} 放弃子进程时异常", this.id, t);
            }
        }
        /*
         * ★ 互操作对象**只能在渲染线程**释放（GlInterop 的线程约定：close() 里是真 GL 调用
         * `glDeleteTextures` + `wglDXCloseDeviceNV`）。
         *
         * <p>failHost 有三个调用点在**后台重启线程**里（doRecover 的失败分支），在那里直接
         * `interop.close()` 等于在没有当前 GL 上下文的线程上删纹理 —— 轻则纹理泄漏，
         * 重则驱动里出错。所以这里只打标记，交给渲染线程下一帧处理
         * （和 `rebuild()` / `doRecover()` / `close()` 完全一致的做法）。
         */
        this.needsGlReset = true;
        this.drawSlot = -1;
        this.lastDrawnGen = -1;
        ensureEngine();
    }

    /**
     * 诊断入口（`/cineduct refresh fallback`）：**立刻**放弃子进程、切到进程内引擎。
     *
     * <p>为什么值得专门给一个命令：回退这条路只有在子进程真的坏掉时才会走，平时永远看不到；
     * 而它恰恰是"最需要在需要时可靠"的一条路（位置有没有接住、暂停/音量有没有丢、
     * 有没有出现两条链同时出声）。手动触发一次就能在几秒内当场验证，
     * 和"想验证重启就直接 `taskkill`"是同一个思路（见 `/cineduct status` 的提示）。
     *
     * @return 是否已经跑在进程内引擎上
     */
    public boolean fallbackToEngineNow() {
        return fallbackToEngineNow("/cineduct refresh fallback");
    }

    /**
     * 同 {@link #fallbackToEngineNow()}，但把"谁触发的"写进日志（`/cineduct fallback` 与
     * `/cineduct refresh fallback` 走同一套交接，日志里要分得清是哪条命令）。
     */
    public boolean fallbackToEngineNow(String why) {
        if (this.closed) return false;
        if (this.mode == Mode.JAVA) {
            ensureEngine();
            return this.engine != null;
        }
        failHost("手动触发（" + why + "）");
        return this.engine != null;
    }

    /**
     * 建进程内引擎。
     *
     * <p>★ **只允许在 {@link Mode#JAVA} 下建**（不变式）。HOST 模式下误建一条引擎的后果不是"多占点内存"，
     * 而是**两块声卡同时出声**：现场（2026-09-27 09:18）"子进程启动中"的那 0.3 秒里，
     * 取帧路径掉进 Java 兜底建了引擎 → 玩家听到"两条音频一起播放，其中一条不受控制"。
     * 所以这里直接挡死，任何调用方都别想绕过去。
     */
    private synchronized MediaEngine ensureEngine() {
        if (this.engine != null || this.closed) return this.engine;
        if (this.source == null) return null;
        if (this.mode != Mode.JAVA) {
            // HOST 模式：宁可不建。真要降级请走 failHost()，那条路会先把 mode 切成 JAVA。
            return null;
        }
        final URI video = this.source.video();
        final URI audio = effectiveAudio(this.source);
        final MediaEngine created = new MediaEngine(video, audio, FfmpegSources.buildHeaders(video),
                this.source.live());
        this.engine = created;
        created.setVolumePercent(this.volumePercent);
        created.setMuted(this.muted);
        created.setRepeat(this.repeat);
        created.start();
        /*
         * ★ 建引擎时必须把**状态交接**做全，否则"回退"就不是回退、而是重播：
         *   * 位置：引擎是重新打开的，不喂位置就从 0 开始（玩家看到进度条归零）。
         *     `resumeSeekMs`（失败/重建时记下的续播点）优先，其次才是还没落地的 seek 目标。
         *     seek 是异步的：引擎还没 boot 时它会自己挂起，等两条链就绪再落（MediaEngine.seek）。
         *   * 暂停：以前只交接了音量/静音/循环，**没交暂停** —— 于是"暂停中崩了"的屏幕
         *     回退后会自己出声放起来，而 UI 还显示暂停（玩家下一步只会觉得"见鬼了"）。
         */
        final long resume = this.resumeSeekMs >= 0 ? this.resumeSeekMs : this.pendingSeekMs;
        if (resume > 0 && !isLive()) {
            this.resumeSeekMs = -1L;
            /*
             * 同时把它记成"未落地的 seek 目标"：
             * ① 引擎还在 boot 时 `timeMs()` 会返回 0，靠 pendingSeekMs 让上层先看到正确位置
             *    （否则回退瞬间进度条归零、还可能被上层当成"跳到开头"再下发一次 seek）；
             * ② lastSeekRequestAt 要一起更新，那个乐观窗口有 8 秒上限（见 timeMs()）。
             */
            this.pendingSeekMs = resume;
            this.lastSeekRequestAt = System.currentTimeMillis();
            created.seek(resume);
            CineDuct.LOGGER.info("[CineDuct] 会话 #{} 进程内引擎将从 {}ms 续播（回退不从头开始）",
                    this.id, resume);
        }
        if (this.pauseWanted) {
            created.pause(true);
            CineDuct.LOGGER.info("[CineDuct] 会话 #{} 进程内引擎按暂停状态启动（回退不会突然出声）",
                    this.id);
        }
        CineDuct.LOGGER.info("[CineDuct] 会话 #{} 进程内引擎已启动", this.id);
        return created;
    }

    /**
     * 音频链兜底：没有独立音轨时用视频链本身；本地成对素材自动认旁边的音频文件。
     */
    private static URI effectiveAudio(ResolvedStream source) {
        if (source.audio() != null) return source.audio();
        URI video = source.video();
        if (video == null) return null;
        try {
            if ("file".equalsIgnoreCase(video.getScheme()) && video.getPath() != null) {
                String path = video.getPath();
                if (path.toLowerCase(Locale.ROOT).endsWith("_v.mp4")) {
                    Path sibling = Path.of(path.substring(0, path.length() - "_v.mp4".length()) + "_a.m4a");
                    if (Files.exists(sibling)) return sibling.toUri();
                }
            }
        } catch (Throwable ignored) {
        }
        return video;
    }

    // =====================================================================
    // 身份 / 引用计数
    // =====================================================================

    public long id() {
        return this.id;
    }

    /** 逻辑会话 UUID（跨子进程重启不变）。 */
    public java.util.UUID sessionUuid() {
        return this.sessionUuid;
    }

    /**
     * 当前这次子进程实例的 UUID 文本；**没有/还没起子进程时为空串**。
     *
     * <p>注意与 {@link #sessionUuid()} 的区别：那个一条会话一个、重启不变；
     * 这个每个进程一个，自愈重启/换输出尺寸重建都会换新的。
     */
    public String hostInstanceUuid() {
        final HostSession h = this.host;
        return h == null ? "" : h.instanceUuid();
    }

    public ResolvedStream source() {
        return this.source;
    }

    boolean matches(ResolvedStream other, String wantAudioMode,
                    dev.mediafix.api.player.Transport wantTransport) {
        if (this.closed || other == null) return false;
        if (this.source == null) return false;
        if (!java.util.Objects.equals(this.audioModeOverride, wantAudioMode)) return false;
        // 传输偏好不同也不复用：一块屏要求进程内、另一块要求零拷贝时，共用一条会话必然违背其中一个
        if (this.transportPref != (wantTransport == null ? dev.mediafix.api.player.Transport.AUTO : wantTransport)) {
            return false;
        }
        final String a = this.source.video() == null ? "" : this.source.video().toString();
        final String b = other.video() == null ? "" : other.video().toString();
        final boolean sameVideo = a.equals(b);
        final String aa = this.source.audio() == null ? "" : this.source.audio().toString();
        final String bb = other.audio() == null ? "" : other.audio().toString();
        return sameVideo && aa.equals(bb);
    }

    void addRef() {
        this.refs.incrementAndGet();
    }

    int releaseRef() {
        return this.refs.decrementAndGet();
    }

    int refs() {
        return this.refs.get();
    }

    /** 实际在用的帧传输方式。 */
    public String transport() {
        final HostSession h = this.host;
        if (this.mode == Mode.HOST && h != null && h.isReady()) return TRANSPORT_INTEROP;
        return this.mode == Mode.HOST ? "(子进程启动中)" : TRANSPORT_JAVA;
    }

    /** 名字里带下划线的版本给指令/API 显示用。 */
    public String transportId() {
        final HostSession h = this.host;
        if (this.mode == Mode.HOST && h != null && h.isReady()) return TRANSPORT_INTEROP;
        return TRANSPORT_JAVA;
    }

    public HostSession hostSession() {
        return this.host;
    }

    // =====================================================================
    // 控制
    // =====================================================================

    public void pause(boolean paused) {
        /*
         * 去重：调用方（渲染器）**每帧**都会推一次期望的暂停状态，
         * 而子进程模式下每次推送都是一次同步管道往返 —— 值没变就不要再发。
         * （和 setVolumePercent 同样的道理，见那里的注释。）
         */
        if (paused == this.pauseWanted) return;
        this.pauseWanted = paused;
        final HostSession h = this.host;
        if (this.mode == Mode.HOST && h != null) {
            h.setPaused(paused);
            return;
        }
        final MediaEngine e = this.engine;
        if (e != null) e.pause(paused);
    }

    /** 期望的暂停状态（客户端刚推过来、尚未确认的也算）。 */
    public boolean isPauseWanted() {
        return this.pauseWanted;
    }

    public void seek(long ms) {
        final long target = Math.max(0L, ms);
        this.pendingSeekMs = target;
        this.lastSeekRequestAt = System.currentTimeMillis();
        final HostSession h = this.host;
        if (this.mode == Mode.HOST && h != null) {
            if (!h.seek(target)) {
                CineDuct.LOGGER.warn("[CineDuct] 子进程 seek 命令失败（位置仍以子进程汇报为准）");
            }
            return;
        }
        final MediaEngine e = this.engine;
        if (e != null) e.seek(target);
    }

    public void setVolumePercent(int percent) {
        final int clamped = Math.max(0, Math.min(100, percent));
        /*
         * 值没变就别发命令。
         *
         * 渲染器**每帧**都会按"实体音量 × 距离衰减 × 主音量"推一次音量，而子进程模式下
         * 每次推送都是一次同步管道往返（写+读）。单屏无感，但 N 块屏同时播放就是每帧 N 次
         * 系统调用 —— 纯浪费。去重之后只有音量真的变化时才过一次管道。
         */
        if (clamped == this.volumePercent) return;
        this.volumePercent = clamped;
        final HostSession h = this.host;
        if (this.mode == Mode.HOST && h != null) {
            h.setVolumePercent(this.volumePercent);
            return;
        }
        final MediaEngine e = this.engine;
        if (e != null) e.setVolumePercent(this.volumePercent);
    }

    /**
     * 音量是否由接收端（功放）控制、我们改不了。
     *
     * <p>IEC 61937 直通时码流一个字节都不能改，所以音量/静音命令是无效的 ——
     * 客户端据此跳过每帧推音量。
     */
    public boolean volumeLocked() {
        final HostSession h = this.host;
        return this.mode == Mode.HOST && h != null && h.volumeLocked();
    }

    /** 音频输出形态名（"IEC61937直通"/"独占PCM"/"共享模式"）。 */
    public String audioModeName() {
        final HostSession h = this.host;
        if (this.mode == Mode.HOST && h != null && h.isReady()) return h.audioModeName();
        final MediaEngine e = this.engine;
        return e == null ? "尚未启动" : "进程内引擎";
    }

    public void setMuted(boolean wantMuted) {
        // 同样去重（调用方每帧都会推一次），并且直通时改不了码流、发了也白发
        if (wantMuted == this.muted) return;
        this.muted = wantMuted;
        final MediaEngine e = this.engine;
        if (e != null) {
            e.setMuted(wantMuted);
        } else if (this.mode == Mode.HOST && this.host != null) {
            if (!this.host.volumeLocked()) {
                this.host.setVolumePercent(wantMuted ? 0 : this.volumePercent);
            }
        }
    }

    public void setRepeat(boolean wantRepeat) {
        this.repeat = wantRepeat;
        final HostSession h = this.host;
        if (this.mode == Mode.HOST && h != null) h.setLoop(wantRepeat);
        final MediaEngine e = this.engine;
        if (e != null) e.setRepeat(wantRepeat);
    }

    /**
     * 音画偏移（毫秒，运行期可改）：<b>正 = 画面提前</b>，负 = 画面延后。
     *
     * <p>两条链路都支持：零拷贝走控制通道的 {@code av-offset} 动词（子进程立刻按新偏移出帧）；
     * 进程内引擎每帧读 {@code FfmpegConfig.avOffsetMs}，改完下一帧生效。
     * 用途：补偿 HDMI → 功放/回音壁那段物理延迟（画面看着领先就给负值）。
     */
    public void setAvOffsetMs(int ms) {
        final int clamped = Math.max(-5000, Math.min(5000, ms));
        dev.mediafix.config.FfmpegConfig.avOffsetMs = clamped;
        final HostSession h = this.host;
        if (this.mode == Mode.HOST && h != null) h.setAvOffsetMs(clamped);
    }

    // =====================================================================
    // 子进程自愈（客户端 tick 调用；判定很便宜，真正的重启在后台线程）
    // =====================================================================

    /** 渲染线程每 tick 调用一次：发现子进程死了/卡了就安排重启。 */
    public void checkHostHealth() {
        if (this.closed || this.recovering || this.mode != Mode.HOST) return;
        final HostSession h = this.host;
        if (h == null) return;
        final HostSession.State state = h.state();
        if (state == HostSession.State.CLOSED || state == HostSession.State.STARTING) return;
        if (state == HostSession.State.FAILED) {
            scheduleRecover("子进程启动失败：" + h.lastError());
            return;
        }
        if (!h.alive()) {
            scheduleRecover("子进程已退出（pid " + h.pid() + "）");
            return;
        }
        if (state != HostSession.State.READY) return;

        final long now = System.currentTimeMillis();
        /*
         * ★ 最近根本没人来取帧时，不该判"卡死"。
         *
         * 子进程发帧靠"消费者交还槽位 + 键控互斥乒乓"：没人取帧就没有交还，
         * 它自然会停下（丢掉产不出去的新帧）。这是**正常且省资源**的行为 ——
         * 屏幕被视锥剔除、玩家看向别处、UI 挡住时都会这样。
         * 以前这里只看"发布帧数有没有涨"，于是这种正常情况会被判成"卡死"并触发重启
         * （8 秒一次、最多 3 次，最后还会被降级到进程内引擎）。
         */
        if (now - this.lastDrawRequestAt > 2000L) {
            this.lastProgressAt = now;
            return;
        }
        // 片尾待命 / 暂停中：本来就不该有新帧，不算卡死
        if (h.endedByStatus() || h.pausedByStatus() || this.pauseWanted) {
            this.lastProgressAt = now;
            return;
        }
        /*
         * ★ 子进程正在"等缓存"（v11 的 gateActive）时，发布帧数**本来就不该涨**。
         *
         * <p>闸门是子进程**主动**把时钟与声卡冻住（等数据 / 等游戏进程来取帧），
         * 位置停在原地、一帧都不发 —— 这是正常状态，不是卡死。
         * 以前没有这一条时，靠子进程自己 6 秒的硬上限绕开 8 秒的重启判据；
         * 现在有了它，把上限放宽也不会误重启（两边都留着，属于双保险）。
         */
        if (h.gateActive()) {
            this.lastProgressAt = now;
            this.lastPublishedFrames = h.publishedFrames();
            return;
        }
        /*
         * seek 之后的宽限：定位期间本来就不会有新帧，但**只宽限一小段** ——
         * 无限宽限会让"seek 触发的死锁"永远不被发现（这正是这次踩到的坑）。
         */
        if (this.pendingSeekMs >= 0 && now - this.lastSeekRequestAt < SEEK_GRACE_MS) {
            this.lastProgressAt = now;
            return;
        }
        final long published = h.publishedFrames();
        if (published != this.lastPublishedFrames) {
            this.lastPublishedFrames = published;
            this.lastProgressAt = now;
            return;
        }
        if (now - this.lastProgressAt > STALL_TIMEOUT_MS) {
            scheduleRecover("子进程卡死（" + (STALL_TIMEOUT_MS / 1000) + " 秒没有新帧）");
        }
    }

    /**
     * 安排一次重启（限流 + 超限降级）。
     *
     * <p>为什么要限流：如果素材本身就会让子进程崩（比如某个编码组合），
     * 无限重启会变成"崩溃-重启-崩溃"的抽搐循环，比直接回退到进程内引擎更糟。
     * 60 秒内最多 3 次，超了就用 Java 引擎兜底（那条路本来就在），并且不再回头。
     */
    private void scheduleRecover(String reason) {
        final long now = System.currentTimeMillis();
        if (this.healthySince > 0 && now - this.healthySince > RECOVER_HEALTHY_MS) {
            // 上次重启之后一直健康：计数作废
            this.recoverAttempts = 0;
            this.healthySince = now;
        }
        if (now - this.recoverWindowStart > RECOVER_WINDOW_MS) {
            this.recoverWindowStart = now;
            this.recoverAttempts = 0;
        }
        if (this.recoverAttempts >= RECOVER_MAX) {
            CineDuct.LOGGER.warn("[CineDuct] 会话 #{} {}，且已重启 {} 次 —— 改用进程内引擎兜底",
                    this.id, reason, this.recoverAttempts);
            failHost(reason + "（已达重启上限）");
            return;
        }
        this.recoverAttempts++;
        this.recoverCount++;
        this.recovering = true;
        this.resumeSeekMs = Math.max(0L, timeMs());
        CineDuct.LOGGER.warn("[CineDuct] 会话 #{} {} → 第 {} 次自动重启（从 {}ms 续播）",
                this.id, reason, this.recoverAttempts, this.resumeSeekMs);
        Thread t = new Thread(this::doRecover, "cineduct-recover-" + this.id);
        t.setDaemon(true);
        t.start();
    }

    /** 后台线程：换掉旧子进程、起新的、把状态补回去（阻塞操作都在这里做，不占渲染线程）。 */
    private void doRecover() {
        try {
            final HostSession old = this.host;
            this.host = null;
            /*
             * ★ 崩在"独占/直通音频"上时，下一次改用共享模式。
             *
             * 现场：某些显卡 HDMI 端点（实测 NVIDIA）在 WASAPI **独占模式**下，
             * Windows 音频引擎（audiokse.dll）会在 2~5 秒后访问违例崩溃，把子进程整个带走 ——
             * 直通（IEC 61937）必须独占，所以在这类端点上直通是走不通的。
             * 原样重启只会在同一个端点上反复崩，所以这里降级：有声音、没有全景声直通，
             * 并给玩家一条一次性提示（说明为什么没点亮 Atmos、以及怎么手动重试）。
             */
            if (old != null && old.abnormalExit() && !this.audioDowngraded && exclusiveAudioWanted()) {
                this.audioDowngraded = true;
                CineDuct.LOGGER.warn("[CineDuct] 会话 #{} 子进程在独占/直通音频下崩溃（exit={}），"
                        + "本次会话改用共享模式音频（不再弹提示，见日志）", this.id,
                        old.abnormalExit() ? "非0" : "?");
            }
            // 旧进程：现在 close() 不阻塞（发 quit / 等退出 / 强杀 全在后台收尾线程），放哪都行
            if (old != null) old.close();
            /*
             * ★ 关掉旧进程之后**必须重新确认这次重启还有没有必要**。
             *
             * <p>为什么：从"安排重启"到"关掉旧进程"之间是几秒的窗口，这期间同一会话完全可能
             * 已经被判死并回退到进程内引擎（`failHost()` 把 mode 切成 JAVA 并建了引擎）。
             * 老代码不看 mode 就继续往下起新子进程 → 结果是**引擎在放 + 新子进程也在放**，
             * 两块声卡同时出声（就是 2026-09-27 那次"两条音频一起播放"的同一个病），
             * 而且取帧路径走 JAVA（画引擎的帧），子进程白跑一份。
             */
            if (this.closed || this.mode != Mode.HOST) {
                CineDuct.LOGGER.info("[CineDuct] 会话 #{} 这次重启已作废（{}）", this.id,
                        this.closed ? "会话已关闭" : "已回退到进程内引擎");
                return;
            }
            // 互操作对象指向已关闭的纹理，必须在渲染线程重建（见 acquireDrawTexture）
            this.needsGlReset = true;
            this.drawSlot = -1;

            final HostSession fresh = HostSession.start(this.id, this.source,
                    dev.mediafix.gpu.GpuContext.adapterLuid(), this.repeat,
                    this.requestedAudioMode(), this.requestedFitAspect(), this.sessionUuid);
            if (fresh == null) {
                failHost("重启子进程失败");
                return;
            }
            if (this.closed) {   // 会话在这期间被关了：别留下野进程
                fresh.close();
                return;
            }
            // 这条线程就是后台重启线程，CreateProcess 放这里没问题（渲染线程才不会顿）
            if (!fresh.spawn()) {
                failHost("重启子进程失败");
                return;
            }
            if (!fresh.awaitRing(12_000L)) {
                CineDuct.LOGGER.warn("[CineDuct] 会话 #{} 重启后没能就绪（{}）", this.id, fresh.lastError());
                fresh.close();
                this.host = null;
                failHost("重启后子进程没能就绪");
                return;
            }
            /*
             * 挂上新进程这一步和 failHost 的"切模式"要**互斥**：awaitRing 又等了最多 12 秒，
             * 期间同样可能已经回退。这一句和 failHost 里的 synchronized(this) 一起，
             * 保证"要么这次重启生效、要么它彻底作废"，不会两个播放器并存。
             */
            synchronized (this) {
                if (this.closed || this.mode != Mode.HOST) {
                    CineDuct.LOGGER.info("[CineDuct] 会话 #{} 重启好的子进程已作废（{}）", this.id,
                            this.closed ? "会话已关闭" : "已回退到进程内引擎");
                    fresh.abandon();
                    return;
                }
                this.host = fresh;
            }
            fresh.connectPipe();
            pushStateToHost(fresh);
            this.healthySince = System.currentTimeMillis();
            this.lastProgressAt = this.healthySince;
            this.lastPublishedFrames = -1L;   // 让下一轮把新进程的计数当作"有进展"
            CineDuct.LOGGER.info("[CineDuct] 会话 #{} 子进程已重启并就绪（累计重启 {} 次）",
                    this.id, this.recoverCount);
        } catch (Throwable t) {
            CineDuct.LOGGER.warn("[CineDuct] 会话 #{} 重启子进程异常", this.id, t);
            failHost("重启异常: " + t);
        } finally {
            this.recovering = false;
        }
    }

    /** 累计自动重启次数（诊断用）。 */
    public int recoverCount() {
        return this.recoverCount;
    }

    /** 是否希望用独占/直通音频（auto 或 passthrough/exclusive）。 */
    private boolean exclusiveAudioWanted() {
        final String mode = dev.mediafix.config.FfmpegConfig.audioMode;
        return mode == null || mode.isBlank() || mode.equalsIgnoreCase("auto")
                || mode.equalsIgnoreCase("passthrough") || mode.equalsIgnoreCase("exclusive");
    }

    /** 音频形态是否已被降级为共享模式（诊断/状态显示用）。 */
    public boolean audioDowngraded() {
        return this.audioDowngraded;
    }

    /*
     * 空间音效（杜比全景声）的检测结果仍然会写进状态环（HostSession.spatialAudioState /
     * spatialAudioFormat），`/cineduct audio` 与 `--list-audio` 都能看到；
     * 但**游戏内不再弹任何音频提示**（用户要求），所以这里不再有播报逻辑。
     */

    public boolean isRepeat() {
        return this.repeat;
    }

    /** 手动刷新：重新解析直链 + 无缝换链。发网络请求，务必在后台线程调用。 */
    public boolean refresh() {
        final MediaEngine e = this.engine;
        return e != null && e.refreshManually();
    }

    // =====================================================================
    // 状态
    // =====================================================================

    private static long parseMs(String line, String key) {
        if (line == null) return -1;
        final int at = line.indexOf(key);
        if (at < 0) return -1;
        int i = at + key.length();
        final StringBuilder sb = new StringBuilder();
        while (i < line.length() && (Character.isDigit(line.charAt(i)) || line.charAt(i) == '-')) {
            sb.append(line.charAt(i++));
        }
        try {
            return sb.length() == 0 ? -1 : Long.parseLong(sb.toString());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 子进程报的位置（音频时钟推进到哪）。 */
    public long hostPositionMs() {
        final HostSession h = this.host;
        if (h == null) return -1;
        return parseMs(h.statusLine(), "pos=");
    }

    public long timeMs() {
        final HostSession h = this.host;
        if (this.mode == Mode.HOST && h != null && h.isReady()) {
            final long reported = h.mediaPositionMs();
            final long pending = this.pendingSeekMs;
            if (pending >= 0) {
                // seek 刚发出去、子进程还没播到目标：先乐观地报目标位置，
                // 免得上层看到"位置没动"又反复下 seek；追上后就清掉。
                if (Math.abs(reported - pending) <= 500L) {
                    this.pendingSeekMs = -1L;
                } else {
                    return pending;
                }
            }
            return Math.max(0L, reported);
        }
        final MediaEngine e = this.engine;
        if (e == null) return 0L;
        final long reported = e.timeMs();
        /*
         * ★ Java 模式也要有"乐观报位置"这一段（和上面 HOST 分支同一个手法）。
         *
         * <p>回退/重建之后引擎要重新打开两条链（网络素材 1~2 秒），这期间 `engine.timeMs()` 是 0；
         * 直接报上去有两个后果：进度条先归零再跳回来，以及上层可能把"位置倒退"当成一次
         * 用户跳转而再下发一次 seek。所以只要还没追上目标（±500ms）就先报目标位置，
         * 追上后自动清掉 —— 但**最多信 8 秒**：引擎真跳不过去（不可定位的流）时不能永远骗自己。
         */
        final long pending = this.pendingSeekMs;
        if (pending >= 0) {
            if (Math.abs(reported - pending) <= 500L) {
                this.pendingSeekMs = -1L;
            } else if (System.currentTimeMillis() - this.lastSeekRequestAt < 8000L) {
                return pending;
            } else {
                this.pendingSeekMs = -1L;
            }
        }
        return Math.max(0L, reported);
    }

    public long durationMs() {
        final HostSession h = this.host;
        if (this.mode == Mode.HOST && h != null && h.isReady()) {
            return h.mediaDurationMs();
        }
        final MediaEngine e = this.engine;
        return e == null ? -1L : e.durationMs();
    }

    public boolean isLive() {
        return this.source != null && this.source.live();
    }

    public long liveLatencyMs() {
        final MediaEngine e = this.engine;
        return e == null ? -1L : e.liveLatencyMs();
    }

    public long liveElapsedMs() {
        final MediaEngine e = this.engine;
        return e == null ? 0L : e.liveElapsedMs();
    }

    /** 画面是否可以显示（子进程模式：纹理导入完就是就绪）。 */
    public boolean readyForDisplay() {
        final HostSession h = this.host;
        if (this.mode == Mode.HOST && h != null) return h.isReady();
        final MediaEngine e = this.engine;
        return e != null && e.readyForDisplay();
    }

    /**
     * 子进程是否正在**主动"等缓存"**（时钟与声卡已冻住，位置不再前进）。
     *
     * <p>为什么要单独暴露它、而不是复用 {@code state() == BUFFERING}：
     * <ul>
     *   <li>{@code state()} 那条路有 400ms 迟滞（防抖），而上层"要不要去追服务端进度"
     *       需要**立刻**知道 —— 晚 400ms 就可能已经发出一次 seek 了；</li>
     *   <li>语义也不同：BUFFERING 是"现在没有新帧"（等时钟也会翻），这条是"我们**决定**停下等"。</li>
     * </ul>
     *
     * <p>Java 引擎（进程内那条路）没有这个概念 → 恒 false。
     */
    public boolean hostWaitingForBuffer() {
        final HostSession h = this.host;
        return this.mode == Mode.HOST && h != null && h.isReady() && h.gateActive();
    }

    public boolean hasVideo() {
        return true;
    }

    public boolean hasAudio() {
        return true;
    }

    public boolean isBroken() {
        final MediaEngine e = this.engine;
        return e != null && e.isBroken();
    }

    public boolean isClosed() {
        return this.closed;
    }

    public int videoWidth() {
        final HostSession h = this.host;
        if (this.mode == Mode.HOST && h != null && h.width() > 0) return h.width();
        final MediaEngine e = this.engine;
        return e == null ? 0 : e.videoWidth();
    }

    public int videoHeight() {
        final HostSession h = this.host;
        if (this.mode == Mode.HOST && h != null && h.height() > 0) return h.height();
        final MediaEngine e = this.engine;
        return e == null ? 0 : e.videoHeight();
    }

    public int volumePercent() {
        return this.volumePercent;
    }

    public boolean isMuted() {
        return this.muted;
    }

    public String audioSummary() {
        final HostSession h = this.host;
        if (this.mode == Mode.HOST && h != null && h.isReady()) return "WASAPI(子进程)";
        final MediaEngine e = this.engine;
        return e == null ? "尚未启动" : e.audioSummary();
    }

    public String lastError() {
        final HostSession h = this.host;
        if (h != null && h.lastError() != null && !h.lastError().isEmpty()) return h.lastError();
        return this.lastError;
    }

    public int seekCount() {
        final MediaEngine e = this.engine;
        return e == null ? 0 : e.seekCount();
    }

    public int ignoredSeekCount() {
        final MediaEngine e = this.engine;
        return e == null ? 0 : e.ignoredSeekCount();
    }

    public PlaybackState state() {
        final HostSession h = this.host;
        if (this.mode == Mode.HOST && h != null) {
            noteAudioUnderruns(h);   // 先看有没有"刚断了一下"（事件），再算状态
            noteHostAudio(h);        // 音频链健康（v10）：如实记录，但不翻播放状态
            final PlaybackState s = hostState(h);
            noteStateTransition(s, h);
            return s;
        }
        final MediaEngine e = this.engine;
        if (e == null) return PlaybackState.LOADING;
        return switch (e.state()) {
            case LOADING -> PlaybackState.LOADING;
            case PLAYING -> PlaybackState.PLAYING;
            case PAUSED -> PlaybackState.PAUSED;
            case BUFFERING -> PlaybackState.BUFFERING;
            case ENDED -> PlaybackState.ENDED;
            case ERROR -> PlaybackState.ERROR;
        };
    }

    /**
     * 子进程路径的状态。
     *
     * <p><b>★ 这里是之前修坏的地方</b>：原实现只看 {@link HostSession.State#READY} ——
     * 就绪前一律 {@code LOADING}、就绪后一律 {@code PLAYING}，
     * 于是宿主每帧写进状态环的那个 {@code buffering} 位（"呈现侧此刻没有新帧可出"）
     * <b>从来没进过状态机</b>，只在 {@code /cineduct status} 的诊断字符串里被读了一眼。后果有三条：
     * <ol>
     *   <li>{@code PlaybackState.BUFFERING} 在子进程路径上**不可能出现** ——
     *       {@code CinePlayer.isBuffering()} 恒为 false，中途卡顿上层完全看不见；</li>
     *   <li>没有"缓冲完成"这个转换：只有 {@code markReady()} 那一刻的 LOADING→PLAYING 一次跳变，
     *       缓冲起落既不进状态、也不打日志、也不发事件；</li>
     *   <li>附属 mod 按 {@code isLoading()/isBuffering()} 做的加载提示会一直显示"起播中"，
     *       或者干脆永远看不到缓冲提示。</li>
     * </ol>
     *
     * <p>现在把宿主那个位接上，并加 400ms 迟滞（见 {@link #hostBuffering()}）——
     * 迟滞是必需的：宿主那个位是"此刻有没有新帧"，正常播放里为了等时钟一两帧就会翻一次，
     * 直接当状态用会让 {@code PlaybackState} 逐帧抖动。400ms 与进程内引擎的判据
     * （{@code MediaEngine}："视频判定饥饿超过 400ms 就进入 BUFFERING"）保持一致，
     * 于是两条路的语义对齐、上层不用分支。
     */
    private PlaybackState hostState(HostSession h) {
        if (h.state() == HostSession.State.FAILED) {
            this.hostBufferingSince = 0L;
            this.hostAudioStarvedSince = 0L;
            return PlaybackState.ERROR;
        }
        if (!h.isReady()) {
            this.hostBufferingSince = 0L;
            this.hostAudioStarvedSince = 0L;
            return PlaybackState.LOADING;
        }
        // 片尾待命（子进程还活着、停在最后一帧）——别报成 PLAYING，否则"放完了"看不出来
        if (h.endedByStatus() && !this.repeat) {
            this.hostBufferingSince = 0L;
            this.hostAudioStarvedSince = 0L;
            return PlaybackState.ENDED;
        }
        if (h.isPaused()) {
            this.hostBufferingSince = 0L;
            this.hostAudioStarvedSince = 0L;
            return PlaybackState.PAUSED;
        }
        /*
         * ★★ 只有**视频侧**饿着才算 BUFFERING（音频侧不再参与）。
         *
         * <p>为什么改（2026-10-17 现场，`cineduct-2026-10-17_20-44-25.log`）：
         * 那次音频链把读超时当片尾、静默停摆，宿主**画面一直正常**（已发布 630→1490 帧、约 30fps），
         * 而这里因为"音频水位见底"把整个会话报成 `BUFFERING` —— 整整 32 秒。
         * 上层看到"缓冲中"会以为"等会儿就有"，实际它永远不会自己好（只有 seek/重开能救）。
         * 用户看到的就是"画面声音都没了"，其实画面一直在跑。
         *
         * <p>现在的分工：
         *   * 视频饿着（宿主报"没有新帧"）→ 确实该等，报 BUFFERING；
         *   * 音频饿着而画面在跑 → **保持 PLAYING**，只打警告（见 {@link #noteHostAudio}），
         *     并把"音频链读失败/重连/修复"次数如实报给上层 — 那是"这一路没声音"，不是"在缓冲"。
         *   * 若音频饿到把时钟拖停、宿主连帧都发不出来，上面那条视频判据自然会接管。
         */
        return hostBuffering() ? PlaybackState.BUFFERING : PlaybackState.PLAYING;
    }

    /**
     * 宿主的"没有新帧"带上迟滞：**连续** {@link #HOST_BUFFERING_MS} 才算真的进了缓冲。
     *
     * <p>恢复是无条件的：只要宿主报"有新帧了"就立刻退出缓冲 —— 那正是要找的"缓冲完成"。
     */
    private boolean hostBuffering() {
        final HostSession h = this.host;
        if (h == null || !h.buffering()) {
            this.hostBufferingSince = 0L;
            return false;
        }
        final long now = System.currentTimeMillis();
        final long since = this.hostBufferingSince;
        if (since == 0L) {
            this.hostBufferingSince = now;   // 刚开始饿：先记账，还不翻状态
            return false;
        }
        return now - since >= HOST_BUFFERING_MS;
    }

    /**
     * 音频侧"饿着"：宿主报的音频水位**持续**低于低水位线。
     *
     * <p>★ 它**不再影响 {@link PlaybackState}**（见 {@link #hostState} 的 ★★ 说明）——
     * "音频没声音"和"整个会话在缓冲"是两件事，混在一起会让画面正常时也显示"缓冲中"。
     * 现在它只喂 {@link #noteHostAudio} 的警告与诊断。</p>
     *
     * <p>三种情况不判：没音频（水位读不到，纯视频源）、暂停、音频链已经失败
     * （那时候没有"在缓冲"这回事，该报的是"这一路没有声音"，见 {@code audioFailed()}）。
     */
    private boolean hostAudioStarved() {
        final HostSession h = this.host;
        if (h == null || h.isPaused() || h.audioFailed()) {
            this.hostAudioStarvedSince = 0L;
            return false;
        }
        final int queued = h.audioQueuedMs();
        // 水位 0ms 也可能是"刚起播、还没喂进去"，所以只有**已经在出帧**之后才认这个判据
        if (queued < 0 || queued >= HOST_AUDIO_LOW_MS || h.publishedFrames() <= 0) {
            this.hostAudioStarvedSince = 0L;
            return false;
        }
        final long now = System.currentTimeMillis();
        final long since = this.hostAudioStarvedSince;
        if (since == 0L) {
            this.hostAudioStarvedSince = now;
            return false;
        }
        return now - since >= HOST_BUFFERING_MS;
    }

    /**
     * 收到子进程的"声卡欠载"事件就打一条警告（**这是 Java 以前完全收不到的信号**）。
     *
     * <p>用法是"看计数增量"：宿主那个数是单调递增的累计值，只有变大了才说明刚刚又断了一下；
     * 每帧比对两个 int，代价可以忽略。
     */
    private void noteAudioUnderruns(HostSession h) {
        final long now = h.audioUnderruns();
        if (this.lastAudioUnderruns < 0L) {
            this.lastAudioUnderruns = now;   // 首次只对齐基线
            return;
        }
        if (now > this.lastAudioUnderruns) {
            CineDuct.LOGGER.warn("[CineDuct] 子进程声卡欠载 +{} 次（累计 {}）—— 音频缓冲见底、"
                            + "声音会断一下；当前音频水位 {}ms（抖动拒绝 {} 次）",
                    now - this.lastAudioUnderruns, now, h.audioQueuedMs(), h.audioJitterRejects());
            this.lastAudioUnderruns = now;
        }
    }

    /**
     * 音频链健康看门狗（v10）—— 把"声音没了"这件事**如实说出来**，但不动播放状态。
     *
     * <p>为什么必须单独一条：以前音频死掉只表现为"水位见底"，而水位那个判据被我用来
     * 翻 {@code BUFFERING}，结果画面正常时也显示"缓冲中"（2026-10-17 现场 32 秒）。
     * 现在改成：状态不翻，但日志里明确写"音频链读失败/重连/自动修复"，
     * 玩家反馈"没声音"时一眼能看出是**链路**问题还是**声卡**问题。
     *
     * @return 当前是否处于"音频饿着"状态（供诊断展示；0 = 正常）
     */
    public long audioStarvedForMs() {
        final long since = this.hostAudioStarvedSince;
        return since == 0L ? 0L : System.currentTimeMillis() - since;
    }

    private void noteHostAudio(HostSession h) {
        // 1) 读失败 / 重连 / 自动修复：只看增量（都是单调累计值）
        final long reads = h.audioReadErrors();
        final long reconnects = h.audioReconnects();
        final long repairs = h.audioRepairs();
        if (this.lastAudioReadErrors < 0L) {                 // 首次只对齐基线
            this.lastAudioReadErrors = reads;
            this.lastAudioReconnects = reconnects;
            this.lastAudioRepairs = repairs;
        } else if (reads > this.lastAudioReadErrors || reconnects > this.lastAudioReconnects
                || repairs > this.lastAudioRepairs) {
            CineDuct.LOGGER.warn("[CineDuct] 子进程音频链出过问题：读失败 {} 次 → 累计 {}、"
                            + "重连 {} 次 → 累计 {}、自动修复 {} 次 → 累计 {}（水位 {}ms）",
                    reads - this.lastAudioReadErrors, reads,
                    reconnects - this.lastAudioReconnects, reconnects,
                    repairs - this.lastAudioRepairs, repairs, h.audioQueuedMs());
            this.lastAudioReadErrors = reads;
            this.lastAudioReconnects = reconnects;
            this.lastAudioRepairs = repairs;
        }
        // 2) 水位见底：进入/退出各打一条，不翻状态
        final boolean starved = hostAudioStarved();
        if (starved && !this.audioStarvedLogged) {
            this.audioStarvedLogged = true;
            CineDuct.LOGGER.warn("[CineDuct] 子进程音频中断：水位低到 {}ms 已超过 {}ms（画面继续，"
                            + "状态保持 PLAYING；宿主会自己 seek/重开修 —— 修不好就看读失败/重连计数）",
                    h.audioQueuedMs(), HOST_BUFFERING_MS);
        } else if (!starved && this.audioStarvedLogged) {
            this.audioStarvedLogged = false;
            CineDuct.LOGGER.info("[CineDuct] 子进程音频已恢复（水位 {}ms）", h.audioQueuedMs());
        }
    }

    /**
     * 状态转换时记一条日志。
     *
     * <p>子进程路径以前**一条状态日志都没有**（进程内引擎每条转换都有 {@code [状态] X -> Y}，
     * 一出问题只能靠对比两条路的日志猜）。补上之后，"起播完成 / 开始缓冲 / 缓冲完成"
     * 三件事在 Java 日志里都能直接看到，`/cineduct status` 里的 {@code 状态=} 也才会跟着动。
     */
    private void noteStateTransition(PlaybackState now, HostSession h) {
        final PlaybackState prev = this.lastReportedState;
        if (prev == now) return;
        this.lastReportedState = now;
        if (prev == null) return;   // 首次只是初始化，别多打一行噪声
        /*
         * v11：把缓冲水位一起打出来。
         *
         * <p>"卡了一下"到底是因为网络没供上（水位低）还是游戏没来取帧（消费者停），
         * 这两件事以前只能翻子进程日志；现在一行 Java 日志就够。
         * 老 exe 会打"水位=未知"，不会误导。
         */
        CineDuct.LOGGER.info("[CineDuct][状态] {} -> {} (子进程) | 已发布={} 帧 丢弃={} {}",
                prev, now, h.publishedFrames(), h.droppedFrames(), h.bufferSummary());
    }

    public PlaybackStatus status() {
        return new PlaybackStatus(state(), timeMs(), durationMs(), isLive(), liveLatencyMs(),
                volumePercent(), transportId(), lastError());
    }

    // =====================================================================
    // 出帧（渲染线程）
    // =====================================================================

    /**
     * 取"这一帧要画的 GL 纹理"。
     *
     * <p>子进程模式：把共享纹理拷进本地纹理并加锁，返回它的 GL 纹理 id；
     * Java 模式：把引擎那一帧上传到 {@code fallbackTextureId} 并返回它。
     *
     * @param fallbackTextureId Java 模式下的目标纹理（子进程模式不用）
     * @param forceAllocate     Java 模式下是否强制重新分配纹理存储
     * @param screenAspect      屏幕自身宽高比（用于 Java 模式的信箱化）
     * @return GL 纹理 id；&lt;=0 = 这一帧没有可画的东西
     */
    public int acquireDrawTexture(int fallbackTextureId, boolean forceAllocate, float screenAspect) {
        this.lastDrawRequestAt = System.currentTimeMillis();
        if (this.closed) return -1;
        /*
         * 推迟启动的触发点：**第一次有人来取帧**就起。
         *
         * （曾经想等"第二帧"以躲开附属模组的比例晚到 —— 实测没用：它是等**会话绑定**之后才推比例的，
         * 比第一帧晚好几秒，等两帧毫无收益，反而让"host 还没起"的窗口变大。别再改回去了。）
         */
        if (this.hostSpawnPending) spawnHostNow("取帧");
        if (this.needsGlReset) {
            // 重建过子进程：旧的互操作对象指向已关闭的纹理，必须在本线程（渲染线程）重建
            this.needsGlReset = false;
            releaseGlResources();
            this.drawSlot = -1;
            this.lastDrawnGen = -1;   // 新子进程的帧号从 1 重新开始，别把重启当成"回退"
        }

        final HostSession h = this.host;
        /*
         * ★ HOST 模式**绝不许落到下面的 Java 兜底**。
         *
         * 现场（2026-09-27 09:18 日志）："会话 #1 已创建（子进程启动中）" → 0.3 秒后
         * "会话 #1 进程内引擎已启动" → 再 0.6 秒"子进程已启动" —— 于是**两条链同时在出声**：
         * 视频那条走子进程，另一条进程内引擎自己开了一块声卡，音量/暂停指令却全发给子进程，
         * 所以玩家听到"两条音频一起播放，其中一条不受控制"。
         * 原因就是这里：`h == null`（子进程推迟启动还没起）时直接掉进了 Java 兜底，
         * 而那句兜底会 `ensureEngine()` **把进程内引擎真的建起来**。
         *
         * 现在：HOST 模式下没有可用子进程就返回 -1（这一帧没东西画），等它起来；
         * 只有 mode 真的被切成 JAVA（子进程不可用/失败时的正规降级路径）才走引擎。
         */
        if (this.mode == Mode.HOST) {
            if (h == null || !h.isReady()) {
                if (h != null && !h.isReady() && importHostTextures()) {
                    // 这一拍刚好把互操作纹理导好了，继续往下画
                } else {
                    return -1;
                }
            }
            final GlInterop io = this.interop;
            if (io == null) return -1;

            reportHostDiagnostics(h);
            final int slot = h.pickReadySlot();
            if (slot >= 0) {
                final int gen = h.slotGeneration(slot);
                /*
                 * ★ 单调性守卫：绝不往回画（2026-09-27 现场实证的"画面闪旧内容"根因）。
                 *
                 * 消费者画得比生产者发得快时，挑到的可能是**比上一帧更旧**的槽位残留：
                 *   槽1=READY(帧10) 槽2=READY(帧9) → 画了 10 并把槽1 标 SHOWN
                 *   → 生产者还没发新帧 → 下一帧只剩槽2(帧9) 可选 → **画了帧 9**（往回跳）
                 * 实测日志：25 秒 200 次 `帧号回退 #N`。玩家看到的就是"闪一下旧内容"。
                 *
                 * 处理：① 画完最新一帧就把比它旧的 READY 槽位一起作废（dropStaleSlots，
                 * 从根本上不给"往回画"的机会）；② 万一还是拿到旧帧（竞态），**保持上一帧**
                 * （上一槽位的本地纹理是一份快照，生产者只写共享纹理，不会动它）而不是往回画。
                 */
                if (this.lastDrawnGen >= 0 && gen >= 0 && gen <= this.lastDrawnGen && this.drawSlot >= 0) {
                    this.staleHolds++;
                    h.dropStaleSlots(this.lastDrawnGen);
                    if (this.staleHolds <= 3 || this.staleHolds % 1000 == 0) {
                        CineDuct.LOGGER.info("[CineDuct] 会话 #{} 保持上一帧第 {} 次：候选槽 {} 只有帧号 {}，"
                                        + "已画到 {}（不往回画）",
                                this.id, this.staleHolds, slot, gen, this.lastDrawnGen);
                    }
                } else if (io.copyInto(io.slot(slot))) {
                    h.markShown(slot);          // 拷完就告诉子进程"这个槽可以复用了"
                    /*
                     * 画了最新这一帧之后，把**比它旧的** READY 槽位一起作废 ——
                     * 它们已经被时间甩掉了，留着只会让下一次取帧挑到更旧的一帧（见上面的注释）。
                     */
                    if (gen >= 0) {
                        h.dropStaleSlots(gen);
                        this.lastDrawnGen = gen;
                    }
                    this.drawSlot = slot;
                    this.drawAspect = h.width() > 0 && h.height() > 0
                            ? (float) h.width() / (float) h.height() : 16f / 9f;
                }
            }
            if (this.drawSlot < 0) return -1;
            // ★ 采样互操作纹理必须在加锁状态下进行（不加锁读到的是垃圾，实测过）
            if (!io.lock(io.slot(this.drawSlot))) return -1;
            return io.slot(this.drawSlot).glTexture();
        }

        // Java 兜底：把引擎那一帧上传到屏幕自己的纹理
        MediaEngine e = this.engine;
        if (e == null) {
            e = ensureEngine();
            if (e == null) return -1;
        }
        this.drawSlot = -1;
        /*
         * 信箱化：会话要求了显示面比例就用它（两条链路行为一致），否则退回调用方每次传的 targetAspect。
         * 会话级取值意味着调用方不必每帧都传对 —— 开播前设一次即可。
         */
        final float uploadAspect = this.displayAspect > 0.01f ? this.displayAspect : screenAspect;
        this.drawAspect = uploadAspect;
        final int uploaded = uploadJavaFrame(e, fallbackTextureId, forceAllocate, uploadAspect);
        if (uploaded > 0) {
            this.javaFrameUploaded = true;
            return uploaded;
        }
        /*
         * ★ 拿不到新帧时**保持上一帧**，不要返回 -1。
         *
         * 返回 -1 会让渲染器这一帧什么都不画 —— 玩家看到的就是"屏幕一闪一闪"（直播尤其明显：
         * 缓冲稍薄就会反复进出 BUFFERING，每次进出都掉几帧空画面）。
         * 屏幕纹理里还留着上一帧的内容，直接把它当作本帧的纹理画出去，画面就是连续的了。
         * 只有"从头到现在一帧都还没上传过"才真的没得画（那时确实该留空）。
         */
        return this.javaFrameUploaded ? fallbackTextureId : -1;
    }

    private int uploadJavaFrame(MediaEngine e, int textureId, boolean forceAllocate, float aspect) {
        final dev.mediafix.ffmpeg.FfmpegVideoSource.Frame frame = e.acquireVideoFrame();
        /*
         * ★ "这条 Java 链路还一帧都没上传过"时，暂停中也要补一帧。
         *
         * <p>为什么：`acquireVideoFrame()` 在暂停时恒返回 null（不空转上传），而
         * `acquireFrameForPausedDisplay()` 以前只在 `forceAllocate`（纹理要重新分配）时才调 ——
         * 于是**暂停中回退到 Java 引擎**这条路上，屏幕会一直黑着：
         * 引擎是新的、屏幕纹理还是空的，而它又永远不会去要那一帧。
         * 现在只要还没成功上传过一帧，就照样去要（这条路径会临时解冻、取到暂停位置那一帧再冻回去）。
         */
        final boolean needFirstFrame = !this.javaFrameUploaded;
        final dev.mediafix.ffmpeg.FfmpegVideoSource.Frame use = frame != null ? frame
                : ((forceAllocate || needFirstFrame) ? e.acquireFrameForPausedDisplay() : null);
        if (use == null) return -1;
        try {
            dev.mediafix.render.GlSafeUpload.uploadFrame(use.buf, textureId, use.width, use.height,
                    forceAllocate, aspect);
            return textureId;
        } finally {
            e.releaseVideoFrame(use);
        }
    }

    /** 画完归还（子进程模式：解锁互操作对象）。 */
    public void releaseDrawTexture() {
        final GlInterop io = this.interop;
        if (io != null && this.drawSlot >= 0) {
            io.unlock(io.slot(this.drawSlot));
        }
    }

    /** 本次返回的纹理自身代表的比例（子进程模式是视频比例，Java 模式已信箱化到屏幕比例）。 */
    public float drawAspect() {
        return this.drawAspect;
    }

    // =====================================================================
    // 生命周期
    // =====================================================================

    /** 渲染线程：会话所在的屏幕被移除时释放互操作对象（GL 资源必须在渲染线程删）。 */
    public void releaseGlResources() {
        final GlInterop io = this.interop;
        this.interop = null;
        if (io != null) io.close();
    }

    /**
     * 强制重建（/cineduct refresh full）：丢掉全部缓冲重新开始，位置尽量保留。
     *
     * <p>子进程模式下 = 换一个新子进程；互操作对象要在渲染线程重建，所以这里只打标记，
     * 由 {@link #acquireDrawTexture} 在渲染线程完成清理与重新导入。
     */
    public synchronized boolean rebuild() {
        if (this.closed) return false;
        final long pos = Math.max(0L, timeMs());
        if (this.mode == Mode.HOST) {
            final HostSession old = this.host;
            this.host = null;
            this.hostSpawnPending = false;   // 重建本来就会起新进程，推迟标记作废
            if (old != null) old.close();
            this.needsGlReset = true;
            // 位置要保住：新子进程从 0 起播，靠 pushStateToHost 里的续播 seek 拉回原处
            this.resumeSeekMs = pos;
            final HostSession candidate = HostSession.start(this.id, this.source,
                    dev.mediafix.gpu.GpuContext.adapterLuid(), this.repeat,
                    this.requestedAudioMode(), this.requestedFitAspect(), this.sessionUuid);
            if (candidate != null) {
                this.host = candidate;
                startHostWait(candidate);
                CineDuct.LOGGER.info("[CineDuct] 会话 #{} 子进程已重建（互操作对象将在渲染线程重导）", this.id);
                return true;
            }
            CineDuct.LOGGER.warn("[CineDuct] 子进程重建失败，会话 #{} 改用进程内引擎", this.id);
            this.mode = Mode.JAVA;
        }
        final MediaEngine old = this.engine;
        this.engine = null;
        if (old != null) {
            try {
                old.close();
            } catch (Throwable ignored) {
            }
        }
        final MediaEngine fresh = ensureEngine();
        if (fresh != null && pos > 0 && !isLive()) {
            fresh.seek(pos);
        }
        CineDuct.LOGGER.info("[CineDuct] 会话 #{} 引擎已重建（位置 {}ms）", this.id, pos);
        return fresh != null;
    }

    @Override
    public void close() {
        if (this.closed) return;
        this.closed = true;
        // 互操作导入的 GL 纹理必须由渲染线程释放 —— 这里只排队（见 Sessions.drainGlRelease）
        if (this.interop != null) {
            Sessions.enqueueGlRelease(this);
        }
        final HostSession h = this.host;
        this.host = null;
        if (h != null) {
            try {
                h.close();
            } catch (Throwable t) {
                CineDuct.LOGGER.warn("[CineDuct] 关闭子进程会话异常", t);
            }
        }
        final MediaEngine e = this.engine;
        this.engine = null;
        if (e != null) {
            try {
                e.close();
            } catch (Throwable t) {
                CineDuct.LOGGER.warn("[CineDuct] 关闭会话 #{} 引擎异常", this.id, t);
            }
        }
        CineDuct.LOGGER.info("[CineDuct] 会话 #{} 已关闭（传输方式 {}）", this.id, transportId());
    }

    /** 直接拿引擎（仅限诊断/内部使用）。 */
    public MediaEngine engineForDiagnostics() {
        return this.engine;
    }
}

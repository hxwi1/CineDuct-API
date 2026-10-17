package dev.mediafix.player;

import dev.mediafix.CineDuct;
import dev.mediafix.api.ResolvedStream;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 会话注册表。
 *
 * <p><b>为什么比原来那套简单这么多</b>：以前播放器对象是 waterframes 管的（随时新建/释放/被 GC），
 * 我们只能靠 {@code WeakHashMap + ReferenceQueue + 引用计数 + 孤儿宽限 + 回收线程}去猜"这个引擎还有人用吗"
 * —— 那套机制本身也制造过"引擎被误关 → 永久黑屏"的事故。
 * 现在屏幕实体是我们自己的，生命周期完全可控：谁开的谁关，同一源复用。
 * 于是弱引用表、孤儿暂存区、回收线程全部删掉。
 */
public final class Sessions {

    private static final Map<Long, MediaSession> BY_ID = new ConcurrentHashMap<>();
    private static final AtomicLong NEXT_ID = new AtomicLong(1);

    private Sessions() {
    }

    /**
     * 打开一条会话；**同一条源（视频链+音频链一致）直接复用**。
     *
     * <p>影院里 N 块屏放同一个视频时，只解一份、只占一份音频设备 —— 这是显存与 CPU 的关键优化。
     *
     * @param audioModeOverride 该屏幕要求的音频输出形态覆盖（null/空 = 跟随全局配置）。
     *                          复用要求"源 + 音频形态"都一样 —— 不然一块屏要直通、一块屏要共享，
     *                          共用同一条会话就没法各自满足了。
     */
    public static MediaSession open(ResolvedStream source, String audioModeOverride) {
        return open(source, audioModeOverride, dev.mediafix.api.player.Transport.AUTO);
    }

    /**
     * 同 {@link #open(ResolvedStream, String)}，但可以指定帧传输方式偏好。
     *
     * @param transportPref {@code AUTO}（默认）/ {@code ZERO_COPY}（尽量用子进程）/ {@code IN_PROCESS}（不起子进程）
     */
    public static MediaSession open(ResolvedStream source, String audioModeOverride,
                                    dev.mediafix.api.player.Transport transportPref) {
        if (source == null) return null;
        final String wantMode = audioModeOverride == null || audioModeOverride.isBlank()
                ? null : audioModeOverride.trim().toLowerCase(java.util.Locale.ROOT);
        final dev.mediafix.api.player.Transport wantTransport = transportPref == null
                ? dev.mediafix.api.player.Transport.AUTO : transportPref;
        /*
         * 运行时强制（`/cineduct fallback`）：本局游戏内一律走进程内引擎。
         *
         * <p>★ 必须在这里生效，而不是"只切现在正在跑的会话" —— 否则回退之后再开一块屏幕又会起
         * 子进程，玩家看到的就是"这开关没用"。开关只在内存里（见 forceInProcess 的说明）。
         */
        final boolean forced = forceInProcess && !wantTransport.forcesInProcess();
        final dev.mediafix.api.player.Transport effectiveTransport =
                forced ? dev.mediafix.api.player.Transport.IN_PROCESS : wantTransport;
        if (forced) {
            CineDuct.LOGGER.info("[CineDuct] 手动回退开关生效：这条会话走进程内引擎（调用方偏好 {}）", wantTransport);
        }
        for (MediaSession s : BY_ID.values()) {
            if (s.matches(source, wantMode, effectiveTransport)) {
                s.addRef();
                CineDuct.LOGGER.info("[CineDuct] 复用会话 #{}（同一条源，引用数 -> {}）",
                        s.id(), s.refs());
                return s;
            }
        }
        /*
         * 优先 C++ 子进程（零拷贝 + WASAPI）；它不可用时 MediaSession 内部会自动转进程内引擎。
         *
         * ★ 这里必须连"探到 GL 适配器了吗"一起判：零拷贝要求子进程和游戏在**同一块卡**上，
         * 而那块卡只能靠渲染线程探出来。探不到时（后台线程误调、没有当前 GL 上下文……）
         * **老实走进程内引擎**，而不是带着 luid=0 去起子进程 —— 后者会起在默认适配器上，
         * 之后互操作注册不上，白折腾一轮再回退（GpuContext.reason() 里写明的就是这个约定）。
         */
        final boolean preferHost = !effectiveTransport.forcesInProcess()
                && dev.mediafix.config.FfmpegConfig.gpuHostEnabled
                && dev.mediafix.gpu.GpuContext.available();
        if (wantTransport.prefersZeroCopy() && !preferHost) {
            CineDuct.LOGGER.warn("[CineDuct] 调用方要求零拷贝，但当前拿不到同卡 GL 适配器（{}）→ 退回进程内引擎",
                    dev.mediafix.gpu.GpuContext.reason());
        }
        MediaSession created = MediaSession.create(NEXT_ID.getAndIncrement(), source, preferHost,
                wantMode, effectiveTransport);
        BY_ID.put(created.id(), created);
        return created;
    }

    /**
     * 本局游戏内"一律走进程内引擎（Java 播放）"的运行时开关（`/cineduct fallback`）。
     *
     * <p>★ **只在内存里**：不写配置文件、不落盘，**重启游戏即失效**。这正是它要的语义 ——
     * 临时把零拷贝那条路整体关掉（对比画质/排查子进程问题/被驱动的机器上保稳），
     * 而不是去改玩家的长期偏好（那个在 {@code cineduct-ffmpeg.json} 里）。
     */
    private static volatile boolean forceInProcess;

    /** 设置/取消"本局一律用进程内引擎"。 */
    public static void setForceInProcess(boolean on) {
        forceInProcess = on;
        CineDuct.LOGGER.info("[CineDuct] 手动回退开关: {}（只在本局游戏生效，重启即失效）", on ? "开" : "关");
    }

    /** 开关现状。 */
    public static boolean forceInProcess() {
        return forceInProcess;
    }

    /**
     * 把**当前所有**会话立刻切到进程内引擎（`/cineduct fallback`）。
     *
     * <p>位置/暂停/音量会按 {@code failHost → ensureEngine} 那套交接搬过去（见 MediaSession 里的说明），
     * 所以画面应当停在原位置继续，而不是从头重播。
     *
     * @return 成功切过去的会话数
     */
    public static int fallbackAllNow() {
        int ok = 0;
        for (MediaSession s : new ArrayList<>(BY_ID.values())) {
            try {
                if (s.fallbackToEngineNow("/cineduct fallback")) ok++;
            } catch (Throwable t) {
                CineDuct.LOGGER.warn("[CineDuct] 会话 #{} 手动回退失败", s.id(), t);
            }
        }
        return ok;
    }

    /** 同 {@link #open(ResolvedStream, String)}，音频形态跟随全局配置。 */
    public static MediaSession open(ResolvedStream source) {
        return open(source, null);
    }

    /** 按 id 取（已关闭返回 null）。 */
    public static MediaSession of(long id) {
        MediaSession s = BY_ID.get(id);
        if (s == null) return null;
        if (s.isClosed()) {
            BY_ID.remove(id);
            return null;
        }
        return s;
    }

    /** 最近创建的会话（诊断用）。 */
    public static MediaSession last() {
        MediaSession best = null;
        for (MediaSession s : BY_ID.values()) {
            if (best == null || s.id() > best.id()) best = s;
        }
        return best;
    }

    /** 强制重建"最近那条会话"的引擎（位置尽量保留）。 */
    public static boolean rebuildLast() {
        MediaSession s = last();
        return s != null && s.rebuild();
    }

    /**
     * 手动把"最近那条会话"切到进程内引擎（`/cineduct refresh fallback`）。
     *
     * <p>用来**当场验证回退这条路**：正常播放时它平时永远走不到，而"崩了之后能不能接住"
     * 恰恰是最需要可靠的（位置/暂停/音量交接，以及有没有两条链同时出声）。
     */
    public static boolean fallbackLast() {
        MediaSession s = last();
        return s != null && s.fallbackToEngineNow();
    }

    /** 释放一次引用；没人用了才真正关闭。 */
    public static void release(long id) {
        MediaSession s = BY_ID.get(id);
        if (s == null) return;
        if (s.releaseRef() <= 0) {
            BY_ID.remove(id);
            CineDuct.LOGGER.info("[CineDuct] 会话 #{} 无人使用，关闭", id);
            s.close();
        } else {
            CineDuct.LOGGER.info("[CineDuct] 会话 #{} 仍被 {} 块屏幕使用，保持播放", id, s.refs());
        }
    }

    public static void closeAll() {
        List<MediaSession> all = new ArrayList<>(BY_ID.values());
        BY_ID.clear();
        for (MediaSession s : all) {
            try {
                s.close();
            } catch (Throwable t) {
                CineDuct.LOGGER.warn("[CineDuct] 关闭会话 #{} 失败", s.id(), t);
            }
        }
        if (!all.isEmpty()) {
            CineDuct.LOGGER.info("[CineDuct] 已关闭 {} 条播放会话（退出世界）", all.size());
        }
    }

    public static List<MediaSession> all() {
        return List.copyOf(BY_ID.values());
    }

    // =====================================================================
    // 子进程实例视图与寻址（/cineduct host ...）
    //
    // ★ 为什么是"现算"而不是维护一张 Map<UUID, HostInstance> 索引：
    //   索引会有"会话已关但索引忘了删"的过期问题，而这个列表只用于诊断/管理命令
    //   （每秒最多被调几次，BY_ID 里通常 1~4 条），现算的成本可以忽略，却能保证**永不陈旧**。
    //   这与 docs/host-uuid.md §6 里"加索引"的写法是同一目标的更简实现，文档已同步。
    // =====================================================================

    /** 一条子进程实例的只读快照（给列表/诊断命令用）。 */
    public record HostView(long sessionId, String sessionUuid, String instanceUuid, int pid,
                           boolean alive, String state, long published, long dropped,
                           String transport) {
        /** 短标签（8 位）或空串。 */
        public String instanceShort() {
            final String hex = instanceUuid.replace("-", "");
            return hex.length() >= 8 ? hex.substring(0, 8) : hex;
        }
    }

    /** 当前所有会话的子进程快照（没有起子进程的会话不在里面）。 */
    public static List<HostView> hostViews() {
        final List<HostView> out = new ArrayList<>();
        for (MediaSession s : BY_ID.values()) {
            try {
                final dev.mediafix.host.HostSession h = s.hostSession();
                if (h == null) continue;
                out.add(new HostView(s.id(), s.sessionUuid().toString(), h.instanceUuid(),
                        (int) h.pid(), h.alive(), h.state().name(),
                        h.publishedFrames(), h.droppedFrames(), s.transport()));
            } catch (Throwable ignored) {
                // 单条会话的状态读失败不影响其它会话
            }
        }
        out.sort(java.util.Comparator.comparingLong(HostView::sessionId));
        return out;
    }

    /**
     * 按"寻址串"找一条**有子进程**的会话。
     *
     * <p>三种写法都认（见 docs/host-uuid.md §6）：
     * <ul>
     *   <li>完整 UUID（session 或 instance，大小写不敏感）；</li>
     *   <li>前 8 位短 UUID（同上，允许带不带连字符）；</li>
     *   <li>{@code #3} —— 会话 id。</li>
     * </ul>
     * 找不到返回 null（调用方给一句人话）。
     */
    public static MediaSession resolveHostTarget(String spec) {
        if (spec == null) return null;
        final String want = spec.trim();
        if (want.isEmpty()) return null;
        if (want.startsWith("#")) {
            try {
                final long id = Long.parseLong(want.substring(1));
                final MediaSession s = of(id);
                return s != null && s.hostSession() != null ? s : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        final String norm = want.replace("-", "").toLowerCase(java.util.Locale.ROOT);
        for (MediaSession s : BY_ID.values()) {
            final dev.mediafix.host.HostSession h = s.hostSession();
            if (h == null) continue;
            final String sess = s.sessionUuid().toString().replace("-", "");
            final String inst = h.instanceUuid().replace("-", "");
            if (sess.equals(norm) || inst.equals(norm)) return s;
            if (norm.length() >= 8 && (sess.startsWith(norm) || inst.startsWith(norm))) return s;
        }
        return null;
    }

    public static int count() {
        return BY_ID.size();
    }

    /*
     * 待释放的 GL 资源（互操作导入的纹理 / 注册对象）。
     *
     * GL 资源只能在渲染线程删，而"屏幕被移除"可能发生在任意线程（实体移除、区块卸载）。
     * 所以这里排队，由客户端 tick（渲染线程）统一排空 —— 否则每次拆屏幕都会泄漏
     * 几张纹理 + 几个 interop 对象 + 对应的 D3D 本地纹理。
     */
    private static final java.util.Queue<MediaSession> GL_RELEASE =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    static void enqueueGlRelease(MediaSession session) {
        if (session != null) GL_RELEASE.add(session);
    }

    /** 渲染线程调用：把排队的 GL 资源真正释放掉。 */
    public static void drainGlRelease() {
        MediaSession session;
        while ((session = GL_RELEASE.poll()) != null) {
            try {
                session.releaseGlResources();
            } catch (Throwable t) {
                CineDuct.LOGGER.warn("[CineDuct] 释放屏幕 GL 资源失败", t);
            }
        }
    }

    /**
     * 客户端 tick：给每个会话一次自愈机会（子进程崩了/卡死了就自动重启）。
     *
     * <p>判定本身很便宜（读几个 atomic / 一次 isAlive），真正的重启在各自的后台线程里做 ——
     * 关掉一个僵死的进程最多要等几秒，绝不能堵在渲染线程上。
     */
    public static void tickHealth() {
        for (MediaSession s : BY_ID.values()) {
            try {
                // 推迟启动的兜底：屏幕被剔除 / 一直没人取帧时，到点也得把子进程起了（否则不出声）
                s.tickDeferredSpawn();
                s.checkHostHealth();
            } catch (Throwable t) {
                CineDuct.LOGGER.warn("[CineDuct] 检查会话 #{} 子进程健康状态失败", s.id(), t);
            }
        }
    }

}

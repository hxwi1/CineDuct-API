package dev.mediafix.player;

import dev.mediafix.CineDuct;
import dev.mediafix.api.PlaybackState;
import dev.mediafix.api.ResolvedStream;
import dev.mediafix.api.player.CinePlayer;
import dev.mediafix.api.player.CinePlayerApi;
import dev.mediafix.api.player.PlayerOptions;
import dev.mediafix.api.player.PlayerStatistics;
import dev.mediafix.gpu.GpuContext;
import dev.mediafix.host.HostSession;
import dev.mediafix.source.SourceResolvers;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link CinePlayerApi} 的实现（内部类）：把"独立播放器"接到现成的会话层
 * （{@link Sessions} / {@link MediaSession} / {@link SourceResolvers}）上。
 *
 * <p>与 {@code ScreenClientState} 的差别只有一处：那边的"源"来自屏幕实体的同步数据，
 * 这边的"源"来自调用方直接传进来的链接；其余（后台解析、会话复用、取帧、自愈）完全复用同一条链路，
 * 所以两种用法在传输方式、音频形态、子进程崩溃自愈上的行为是一致的。
 *
 * <p>挂载方式：在 {@code CineDuct} 构造函数的客户端分支里加一行
 * <pre>{@code
 * dev.mediafix.api.player.CinePlayerApi.install(new dev.mediafix.player.CinePlayerApiImpl());
 * }</pre>
 */
public final class CinePlayerApiImpl implements CinePlayerApi {

    private static final Map<Long, StandalonePlayer> PLAYERS = new ConcurrentHashMap<>();
    private static final AtomicLong NEXT_ID = new AtomicLong(1L);

    @Override
    public CinePlayer open(String sourceUri, PlayerOptions options) {
        if (sourceUri == null || sourceUri.isBlank()) {
            throw new IllegalArgumentException("sourceUri 不能为空");
        }
        /*
         * ★ 渲染线程预探测，放在 open() **本体**里（写法与 ScreenClientState.update() 一致）。
         *
         * 为什么必须在这一刻探：零拷贝要求子进程和游戏在同一块卡上，而"GL 上下文在哪块卡上"
         * 只能靠调 GL 函数问出来 —— 也就是只有**调用方当前所在线程就是渲染线程**时才探得到。
         * 附属 mod 调 open() 的时机通常正是渲染中（拿到链接就开播），所以这里探一次最稳；
         * 真正建会话在后台线程，那时已经探不到了。
         *
         * 这里**故意不判 RenderSystem.isOnRenderThread()**：从后台线程调 open 也只是"这次探不到"，
         * GpuContext.probe() 自己会吞掉"没有当前上下文"的失败（LWJGL 那种情况是抛
         * IllegalStateException，不是返回 null），结果是老实用进程内引擎，而不是抛异常。
         * 加条件反而会让"从后台线程开播"这条路径永远探不到，把坑留给下一个调用方。
         */
        if (dev.mediafix.config.FfmpegConfig.gpuHostEnabled) {
            GpuContext.probe();
        }
        final StandalonePlayer player = new StandalonePlayer(NEXT_ID.getAndIncrement(), sourceUri,
                options == null ? PlayerOptions.defaults() : options);
        PLAYERS.put(player.id(), player);
        player.beginResolve();
        return player;
    }

    @Override
    public int openPlayerCount() {
        return PLAYERS.size();
    }

    @Override
    public java.util.List<CinePlayer> players() {
        return java.util.List.copyOf(PLAYERS.values());
    }

    @Override
    public java.util.Optional<CinePlayer> player(long id) {
        return java.util.Optional.ofNullable(PLAYERS.get(id));
    }

    /**
     * 只看链接、不发请求：这个链接值不值得当视频处理。
     *
     * <p>先问已注册的解析器（内置 B 站解析器只看 host，不发网络请求），都不是再看扩展名。
     * 这里刻意**不做任何 IO** —— 调用方是在"决定要不要建屏/开会话"的时刻问的，不能让它等网络。
     */
    @Override
    public boolean canHandle(String sourceUri) {
        if (sourceUri == null || sourceUri.isBlank()) return false;
        final URI uri;
        try {
            uri = URI.create(sourceUri.trim());
        } catch (Throwable t) {
            return false;
        }
        for (dev.mediafix.api.MediaSourceResolver resolver : SourceResolvers.all()) {
            try {
                if (resolver.canHandle(uri)) return true;
            } catch (Throwable ignored) {
                // 解析器自己的判断出错不能影响结论
            }
        }
        return looksLikeMedia(uri);
    }

    /** 扩展名启发式：http/https/file + 常见容器/流后缀。 */
    private static boolean looksLikeMedia(URI uri) {
        final String scheme = uri.getScheme() == null ? ""
                : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https") && !scheme.equals("file")) return false;
        final String path = uri.getPath() == null ? ""
                : uri.getPath().toLowerCase(java.util.Locale.ROOT);
        final int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) return false;
        return switch (path.substring(dot + 1)) {
            case "mp4", "m4v", "mkv", "webm", "mov", "avi", "flv", "ts", "m2ts", "mpg", "mpeg",
                 "m3u8", "mpd", "mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma" -> true;
            default -> false;
        };
    }

    @Override
    public String diagnostics() {
        return "独立播放器=" + PLAYERS.size() + " 会话=" + Sessions.count()
                + " 零拷贝=" + (GpuContext.available() ? "可用(" + GpuContext.adapterName() + ")" : "不可用")
                + " 原因=" + GpuContext.reason();
    }

    /** 句柄从注册表里摘掉（close 时调）。 */
    static void forget(long id) {
        PLAYERS.remove(id);
    }

    /**
     * 一个独立播放器句柄。
     *
     * <p>解析与开会话放在后台线程 —— 那是发网络请求的活（B 站还要换链），
     * 绝不能堵住渲染线程；返回给调用方的句柄先用 volatile 字段暴露"还没好"的状态。
     */
    private static final class StandalonePlayer implements CinePlayer {

        private final long id;
        private final String uri;
        private final PlayerOptions options;

        private volatile long sessionId = -1L;
        private volatile boolean closed;
        private volatile boolean failed;
        private volatile String lastError = "";
        private volatile boolean resolving;

        /**
         * 会话还没起来时，先把调用方推过来的控制意图攒着。
         *
         * <p>为什么必须攒：调用方的习惯是"先把音量/暂停/循环设好，最后才 open/起播"，
         * 那时会话还在后台解析。丢掉这些意图就会出现"明明设了静音，起播第一秒还是有声"。
         */
        private volatile int wantVolume;
        private volatile boolean wantMuted;
        private volatile boolean wantRepeat;
        private volatile boolean wantPaused;
        private volatile long wantSeekMs = -1L;
        /** 调用方要求的显示面比例（0 = 不做信箱化）；会话建好后补推。 */
        private volatile float wantDisplayAspect;

        StandalonePlayer(long id, String uri, PlayerOptions options) {
            this.id = id;
            this.uri = uri;
            this.options = options;
            this.wantVolume = options.volume();
            this.wantMuted = options.muted();
            this.wantRepeat = options.repeat();
            this.wantPaused = options.paused();
            this.wantSeekMs = options.startAtMs();
            this.wantDisplayAspect = options.displayAspect();
        }

        // =====================================================================
        // 解析 / 绑定
        // =====================================================================

        void beginResolve() {
            this.resolving = true;
            Thread t = new Thread(this::resolveAndOpen, "cineduct-player-resolve-" + this.id);
            t.setDaemon(true);
            t.start();
        }

        private void resolveAndOpen() {
            try {
                /*
                 * 注意：适配器 LUID 的探测**不在这里** —— 这里已经在后台线程上了，探不到。
                 * 由 {@link CinePlayerApiImpl#open} 在调用方线程（通常是渲染线程）先探并缓存。
                 */
                final URI parsed = URI.create(this.uri);
                final ResolvedStream stream = SourceResolvers.resolve(parsed);
                if (stream == null) {
                    fail("无法解析这个链接");
                    return;
                }
                final MediaSession session = Sessions.open(stream, audioMode(), this.options.transport());
                if (session == null) {
                    fail("无法建立播放会话");
                    return;
                }
                if (this.closed) {          // 会话还没绑上就被关了：别把引用漏掉
                    Sessions.release(session.id());
                    return;
                }
                pushPendingState(session);
                this.sessionId = session.id();
                CineDuct.LOGGER.info("[CineDuct] 独立播放器 #{} 已绑定会话 #{}（{}）",
                        this.id, session.id(), stream.qualityLabel());
            } catch (Throwable t) {
                fail(t.getMessage() == null ? String.valueOf(t) : t.getMessage());
                CineDuct.LOGGER.warn("[CineDuct] 独立播放器 #{} 解析失败: {}", this.id, this.uri, t);
            } finally {
                this.resolving = false;
            }
        }

        /** 把攒着的控制意图一次性推给新会话。 */
        private void pushPendingState(MediaSession session) {
            session.setVolumePercent(this.wantMuted ? 0 : this.wantVolume);
            session.setMuted(this.wantMuted);
            session.setRepeat(this.wantRepeat);
            session.setDisplayAspect(this.wantDisplayAspect);
            session.pause(this.wantPaused);
            if (this.wantSeekMs > 0L && !session.isLive()) {
                session.seek(this.wantSeekMs);
            }
            this.wantSeekMs = -1L;
        }

        private String audioMode() {
            final String mode = this.options.audioMode();
            return mode == null || mode.isBlank() ? null : mode.trim().toLowerCase(java.util.Locale.ROOT);
        }

        private void fail(String why) {
            this.failed = true;
            this.lastError = why == null ? "" : why;
        }

        private MediaSession session() {
            final long id = this.sessionId;
            return id < 0L ? null : Sessions.of(id);
        }

        // =====================================================================
        // 身份
        // =====================================================================

        @Override
        public long id() {
            return this.id;
        }

        @Override
        public String sourceUri() {
            return this.uri;
        }

        @Override
        public boolean isAlive() {
            return !this.closed;
        }

        // =====================================================================
        // 状态
        // =====================================================================

        @Override
        public PlaybackState state() {
            if (this.closed) return PlaybackState.ENDED;
            if (this.failed) return PlaybackState.ERROR;
            final MediaSession s = session();
            return s == null ? PlaybackState.LOADING : s.state();
        }

        @Override
        public boolean isReady() {
            final MediaSession s = session();
            return s != null && s.readyForDisplay();
        }

        /** 起播中（还在解析 / 首帧还没出来）；播放中偶尔的缓冲**不算**，那是 {@link #isBuffering()}。 */
        @Override
        public boolean isLoading() {
            return state() == PlaybackState.LOADING;
        }

        @Override
        public boolean isBuffering() {
            return state() == PlaybackState.BUFFERING;
        }

        @Override
        public boolean isBroken() {
            return this.failed || state() == PlaybackState.ERROR;
        }

        @Override
        public boolean isLive() {
            final MediaSession s = session();
            return s != null && s.isLive();
        }

        @Override
        public boolean isSeekable() {
            return !isLive() && durationMs() > 0L;
        }

        @Override
        public long positionMs() {
            final MediaSession s = session();
            return s == null ? 0L : Math.max(0L, s.timeMs());
        }

        @Override
        public long durationMs() {
            final MediaSession s = session();
            return s == null ? -1L : s.durationMs();
        }

        @Override
        public int videoWidth() {
            final MediaSession s = session();
            return s == null ? 0 : s.videoWidth();
        }

        @Override
        public int videoHeight() {
            final MediaSession s = session();
            return s == null ? 0 : s.videoHeight();
        }

        @Override
        public String transport() {
            final MediaSession s = session();
            return s == null ? "(解析中)" : s.transport();
        }

        @Override
        public String lastError() {
            if (this.failed) return this.lastError;
            final MediaSession s = session();
            final String err = s == null ? "" : s.lastError();
            return err == null ? "" : err;
        }

        // =====================================================================
        // 出帧（渲染线程）
        // =====================================================================

        @Override
        public int acquireDrawTexture(int fallbackTextureId, boolean forceAllocate, float targetAspect) {
            final MediaSession s = session();
            if (s == null) return -1;
            return s.acquireDrawTexture(fallbackTextureId, forceAllocate, targetAspect);
        }

        @Override
        public void releaseDrawTexture() {
            final MediaSession s = session();
            if (s != null) s.releaseDrawTexture();
        }

        @Override
        public float drawAspect() {
            final MediaSession s = session();
            if (s == null) return 0f;
            final float aspect = s.drawAspect();
            if (aspect > 0.01f) return aspect;
            final int w = s.videoWidth();
            final int h = s.videoHeight();
            return w > 0 && h > 0 ? (float) w / (float) h : 0f;
        }

        @Override
        public float displayAspect() {
            final MediaSession s = session();
            return s == null ? this.wantDisplayAspect : s.displayAspect();
        }

        @Override
        public void setDisplayAspect(float aspect) {
            final float want = Float.isFinite(aspect) && aspect > 0.01f ? aspect : 0f;
            if (Math.abs(want - this.wantDisplayAspect) < 0.005f) return;
            this.wantDisplayAspect = want;
            final MediaSession s = session();
            if (s != null) {
                /*
                 * 会话是**共用**的（同源 + 同音频形态的多个播放器共享一条），输出框也只有一个，
                 * 所以这个比例本质上是会话语义：同一会话上的多个播放器共享它（文档里写明了）。
                 */
                s.setDisplayAspect(want);
            }
        }

        // =====================================================================
        // 控制
        // =====================================================================

        @Override
        public void pause(boolean paused) {
            this.wantPaused = paused;
            final MediaSession s = session();
            if (s != null) s.pause(paused);
        }

        @Override
        public boolean isPaused() {
            final MediaSession s = session();
            return s == null ? this.wantPaused : s.isPauseWanted();
        }

        @Override
        public void seek(long positionMs) {
            final long target = Math.max(0L, positionMs);
            final MediaSession s = session();
            if (s == null) {
                this.wantSeekMs = target;
                return;
            }
            if (s.isLive()) return;
            s.seek(target);
        }

        @Override
        public void setVolumePercent(int percent) {
            this.wantVolume = Math.max(0, Math.min(100, percent));
            final MediaSession s = session();
            // 直通（IEC 61937）时码流不能改，音量由功放控制 —— 发了也白发，直接跳过
            if (s != null && !s.volumeLocked()) s.setVolumePercent(this.wantVolume);
        }

        @Override
        public int volumePercent() {
            final MediaSession s = session();
            return s == null ? this.wantVolume : s.volumePercent();
        }

        @Override
        public void setMuted(boolean muted) {
            this.wantMuted = muted;
            final MediaSession s = session();
            if (s != null) s.setMuted(muted);
        }

        @Override
        public boolean isMuted() {
            final MediaSession s = session();
            return s == null ? this.wantMuted : s.isMuted();
        }

        @Override
        public void setRepeat(boolean repeat) {
            this.wantRepeat = repeat;
            final MediaSession s = session();
            if (s != null) s.setRepeat(repeat);
        }

        @Override
        public boolean isRepeat() {
            final MediaSession s = session();
            return s == null ? this.wantRepeat : s.isRepeat();
        }

        @Override
        public boolean volumeLocked() {
            final MediaSession s = session();
            return s != null && s.volumeLocked();
        }

        @Override
        public PlayerStatistics statistics() {
            final MediaSession s = session();
            if (s == null) {
                return new PlayerStatistics(this.failed ? "（失败）" : "（解析中）", false,
                        0L, 0L, 0, 0, 0, 0L, 0L, 0, 0, 0L, -1L, false, -1L, "", false, 0,
                        lastError());
            }
            final HostSession host = s.hostSession();
            final boolean zeroCopy = host != null && "dxgi-interop".equals(s.transportId());
            return new PlayerStatistics(
                    s.transportId(),
                    zeroCopy,
                    host != null ? host.publishedFrames() : 0L,
                    host != null ? host.droppedFrames() : 0L,
                    host != null ? host.lastAcquireUs() : 0,
                    host != null ? host.maxAcquireUs() : 0,
                    host != null ? host.slotCount() : 0,
                    host != null ? host.pid() : 0L,
                    host != null ? host.uptimeMs() : 0L,
                    s.videoWidth(),
                    s.videoHeight(),
                    s.timeMs(),
                    s.durationMs(),
                    s.isLive(),
                    s.liveLatencyMs(),
                    s.audioModeName(),
                    s.volumeLocked(),
                    s.recoverCount(),
                    lastError());
        }

        /**
         * 重建这条会话（丢缓冲重开，位置尽量保留）。
         *
         * <p>直接转发到 {@code MediaSession.rebuild()} —— 那是屏幕那边 {@code /cineduct refresh full}
         * 走的同一条正规路径：保留会话 id / 同源引用计数不受影响 / 零拷贝的互操作纹理由渲染线程自动重导。
         * 与"close 掉再 open 一个"相比，同源多屏时前者才真的会换新子进程（后者会被会话复用接回去，等于空转）。
         */
        @Override
        public void rebuild() {
            final MediaSession s = session();
            if (s == null) {
                // 还没建好（解析中/失败）：没有会话可重建，交给解析线程按新会话起
                CineDuct.LOGGER.info("[CineDuct] 独立播放器 #{} 还没有会话可重建（状态 {}），忽略",
                        this.id, state());
                return;
            }
            try {
                if (s.rebuild()) {
                    CineDuct.LOGGER.info("[CineDuct] 独立播放器 #{} 已重建会话 #{}", this.id, s.id());
                } else {
                    CineDuct.LOGGER.warn("[CineDuct] 独立播放器 #{} 重建会话 #{} 失败", this.id, s.id());
                }
            } catch (Throwable t) {
                CineDuct.LOGGER.warn("[CineDuct] 独立播放器 #{} 重建异常", this.id, t);
            }
        }

        // =====================================================================
        // 生命周期
        // =====================================================================

        @Override
        public void close() {
            if (this.closed) return;
            this.closed = true;
            final long id = this.sessionId;
            this.sessionId = -1L;
            forget(this.id);
            if (id >= 0L) {
                Sessions.release(id);
            } else if (this.resolving) {
                // 解析线程还在跑：那边发现 closed 会自己把引用放掉（见 resolveAndOpen）
                CineDuct.LOGGER.info("[CineDuct] 独立播放器 #{} 在解析途中被关闭，交给解析线程收尾", this.id);
            }
        }
    }
}

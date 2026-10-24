package dev.mediafix.client;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.mediafix.CineDuct;
import dev.mediafix.api.ResolvedStream;
import dev.mediafix.player.MasterVolume;
import dev.mediafix.player.MediaSession;
import dev.mediafix.player.Sessions;
import dev.mediafix.screen.ScreenEntity;
import dev.mediafix.source.SourceResolvers;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一块屏幕在**客户端**的全部状态：解析出来的播放会话 + 它用的 GL 纹理。
 *
 * <p>职责边界刻意划在这里：
 * <ul>
 *   <li><b>服务端</b>只说"放什么、从哪一刻开始、暂停没有"（{@link ScreenEntity} 的同步数据）；</li>
 *   <li><b>客户端</b>自己解析直链、自己开播放会话、自己上传帧、自己按服务端锚点对齐进度。</li>
 * </ul>
 * 这也是旧版水帧的架构，但把"进度对齐"从"每 tick 问服务端 + 双闸阈值 + 打补丁"换成了
 * "服务端只给锚点，客户端算目标位置" —— 后者天然不会出现"进度随机跳回开头"那种问题。
 *
 * <p><b>线程</b>：{@link #update} / {@link #present} 只能渲染线程调；
 * 解析在后台线程做（发网络请求），结果通过 volatile 字段交回。
 */
public final class ScreenClientState {

    private static final Map<Integer, ScreenClientState> STATES = new ConcurrentHashMap<>();

    /** 进度偏差超过它就向服务端锚点对齐（毫秒）。引擎自己还有 1.5 秒的原地 seek 容差。 */
    /** 服务端改锚点后，偏差小于这个值就不必 seek（避免无意义的跳转）。 */
    private static final long ANCHOR_ALIGN_MIN_DIFF_MS = 1000L;
    /**
     * 安全网：本地与权威位置偏差超过这么大才纠一次。
     * 正常播放永远到不了这个量级（锚点与本地都按真实时间走），
     * 它的作用只是兜住"本地卡了很久又恢复"这种极端情况。
     */
    private static final long DRIFT_NET_MS = 5000L;
    /** 安全网两次纠正之间的最小间隔（防止反复 seek）。 */
    private static final long DRIFT_NET_MIN_INTERVAL_MS = 10_000L;

    private final int entityId;

    /** 已绑定的会话 id（-1 = 还没有）。 */
    private volatile long sessionId = -1L;
    /** 已经绑定到会话上的源（原始链接）；与实体不同就要重建。 */
    private volatile String boundUri = "";
    /** 正在后台解析的源。 */
    private volatile String resolvingUri = "";
    private volatile String lastError = "";

    private int textureId = -1;
    private boolean textureAllocated;
    private ResourceLocation textureLocation;
    /** 进程内引擎上传用的真实 GL 纹理（不进纹理管理器，由 close() 删）。 */
    private DynamicTexture texture;
    /** 登记在纹理管理器里的"id 转发壳"（渲染侧与光影看到的就是它）。 */
    private ScreenVideoTexture videoTexture;

    private long lastSyncAt;
    private long lastWarnAt;
    /** 从"第一次发现没有画面"起算的时刻（0 = 现在有画面）。 */
    private long noFrameSince;
    /** 已经提醒过几次"没有画面"（正常起播不该刷屏）。 */
    private int warnCount;
    private boolean everPresented;
    private volatile float drawAspect;

    /** 上一次已对齐过的锚点代次（-1 = 还没对齐过）。 */
    private int lastAnchorGen = -1;

    private ScreenClientState(int entityId) {
        this.entityId = entityId;
    }

    /** 取（或建）某块屏幕的客户端状态。 */
    public static ScreenClientState get(ScreenEntity entity) {
        return STATES.computeIfAbsent(entity.getId(), ScreenClientState::new);
    }

    /** 按实体 id 取会话 id（没有则 -1）。 */
    public static long sessionIdOf(int entityId) {
        ScreenClientState st = STATES.get(entityId);
        return st == null ? -1L : st.sessionId;
    }

    /** 当前已建立客户端状态的屏幕实体 id（给附属 mod 的客户端 API 用）。 */
    public static java.util.Set<Integer> knownScreenIds() {
        return java.util.Set.copyOf(STATES.keySet());
    }

    /** 屏幕被移除/卸载：释放会话与纹理。 */
    public static void forget(int entityId) {
        ScreenClientState st = STATES.remove(entityId);
        if (st != null) st.close();
    }

    /** 客户端 tick：把已经不在世界里的屏幕状态清掉（防止会话泄漏）。 */
    public static void prune() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null) {
            // 离开世界：全部收掉
            if (!STATES.isEmpty()) {
                STATES.values().forEach(ScreenClientState::close);
                STATES.clear();
            }
            return;
        }
        STATES.entrySet().removeIf(e -> {
            if (mc.level.getEntity(e.getKey()) instanceof ScreenEntity) return false;
            e.getValue().close();
            return true;
        });
    }

    // =====================================================================
    // 会话绑定
    // =====================================================================

    /**
     * 每帧（渲染前）调一次：保证"实体要求的源"与"正在播的会话"一致。
     *
     * <p>源变化/首次出现时在后台线程解析；解析失败会记错误并在画面上给出提示，
     * 不会静默黑屏。
     */
    public void update(ScreenEntity entity) {
        // 本方法在渲染线程被调用 —— 顺手把"GL 上下文在哪块卡上"探一次并缓存。
        // 必须在渲染线程做（要调 GL 函数），而且要在下面的解析线程创建会话之前完成：
        // 子进程启动时需要这个 LUID 才能保证和游戏同卡。
        if (dev.mediafix.config.FfmpegConfig.gpuHostEnabled) {
            dev.mediafix.gpu.GpuContext.probe();
        }
        String wanted = entity.sourceUri();
        if (wanted.isEmpty()) {
            if (this.sessionId >= 0) {
                releaseSession();
                this.boundUri = "";
            }
            return;
        }
        if (wanted.equals(this.boundUri) && this.sessionId >= 0) return;
        if (wanted.equals(this.resolvingUri)) return;   // 已经在解析了

        // 源换了：先把旧的放掉
        if (this.sessionId >= 0 && !wanted.equals(this.boundUri)) {
            releaseSession();
        }
        this.resolvingUri = wanted;
        this.lastError = "";
        final String uri = wanted;
        // 音频形态在"开会话"时就定下了（开声卡那一刻），所以在这里取一次快照带进后台线程
        final String audioMode = entity.audioModeOverride();
        Thread t = new Thread(() -> resolveAndOpen(uri, audioMode), "cineduct-screen-resolve");
        t.setDaemon(true);
        t.start();
    }

    private void resolveAndOpen(String uriText, String audioModeOverride) {
        try {
            URI uri = URI.create(uriText);
            ResolvedStream stream = SourceResolvers.resolve(uri);
            if (stream == null) {
                fail("无法解析这个链接");
                return;
            }
            MediaSession session = Sessions.open(stream, audioModeOverride);
            if (session == null) {
                fail("无法建立播放会话");
                return;
            }
            this.sessionId = session.id();
            this.boundUri = uriText;
            CineDuct.LOGGER.info("[CineDuct] 屏幕 #{} 已绑定会话 #{}（{}）",
                    this.entityId, session.id(), stream.qualityLabel());
        } catch (Throwable t) {
            CineDuct.LOGGER.warn("[CineDuct] 屏幕 #{} 解析链接失败: {}", this.entityId, uriText, t);
            fail(String.valueOf(t.getMessage() == null ? t : t.getMessage()));
        } finally {
            this.resolvingUri = "";
        }
    }

    private void fail(String message) {
        this.lastError = message;
    }

    private void releaseSession() {
        long id = this.sessionId;
        this.sessionId = -1L;
        if (id >= 0) Sessions.release(id);
    }

    // =====================================================================
    // 每帧：进度对齐 + 上传帧
    // =====================================================================

    /**
     * 渲染线程：取"这一帧要画的 GL 纹理"。
     *
     * <p>两种传输方式在 {@code MediaSession} 内部统一了：子进程模式返回互操作导入的纹理，
     * Java 模式把引擎那一帧上传到本屏幕的纹理再返回。调用方画完必须 {@link #release()}
     * （子进程模式下那是解锁互操作对象，不解锁会影响下一帧）。
     *
     * @return GL 纹理 id；&lt;=0 表示这一帧没有可画的东西
     */
    public int present(ScreenEntity entity) {
        MediaSession session = session();
        if (session == null) return -1;

        ensureTexture();
        if (this.textureId <= 0) return -1;

        // 先决定"该不该暂停"（含服务端时钟停住的情况），再谈进度对齐
        applyPause(entity, session);
        syncProgress(entity, session);
        final int texture = session.acquireDrawTexture(this.textureId, !this.textureAllocated,
                entity.aspect());
        if (texture <= 0) return -1;
        // 让纹理管理器里的"壳"指向这一帧真正要采样的 GL 纹理（RenderType 按 ResourceLocation 取它）
        if (this.videoTexture != null) this.videoTexture.setGlId(texture);
        this.textureAllocated = true;
        this.everPresented = true;
        // 有画面了：把"没画面"的计时与提醒次数清零（下次卡顿重新计）
        this.noFrameSince = 0L;
        this.warnCount = 0;
        this.drawAspect = session.drawAspect();
        entity.setDurationHint(session.durationMs());
        return texture;
    }

    /** 画完归还。 */
    public void release() {
        MediaSession session = session();
        if (session != null) session.releaseDrawTexture();
    }

    /** 本次纹理自身代表的比例（子进程模式=视频比例，需要按它加黑边；Java 模式已信箱化）。 */
    public float drawAspect() {
        return this.drawAspect;
    }

    /**
     * 把"该不该暂停"推给播放侧。
     *
     * <p>以前客户端从来没调用过 {@code session.pause(...)}，于是实体的 paused 只影响
     * "服务端权威位置"的计算，本地却照样播 —— 而进度校准又把画面拽回去，
     * 表现就是"暂停之后画面和声音一直在原地重播"。
     *
     * <p>两种暂停都算：① 实体自己暂停（指令/API）；② **单人游戏按 ESC** 打开暂停菜单
     * （集成服务器停摆，此时视频一起停才不会显得"游戏都停了视频还在放"）。
     * 多人服里开菜单**不算**（服务器还在跑，不该影响播放）。
     */
    private void applyPause(ScreenEntity entity, MediaSession session) {
        session.pause(entity.isPaused() || isGamePaused());
    }

    /** 单人游戏且打开了暂停菜单（多人服不算 —— 那边开菜单时世界照常运行）。 */
    private static boolean isGamePaused() {
        final Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.screen == null) return false;
        if (!(mc.screen instanceof net.minecraft.client.gui.screens.PauseScreen)) return false;
        return mc.hasSingleplayerServer();
    }

    /**
     * 只在**锚点变化时**对齐一次；平时不比对、不干预。
     *
     * <p><b>为什么不做"每秒比对一次"</b>：权威位置以前按游戏刻推进，服务端 TPS 一低它就走得比
     * 真实时间慢，于是本地明明是正常实时播放，却被判定成"跑快了"而反复 seek 回去 ——
     * 玩家看到的就是画面/声音一直被往回拉。现在锚点用真实时间推进（见
     * {@code ScreenEntity.projectedPositionMs}），两边速率一致，唯一需要对齐的时机就是
     * **服务端主动改锚点**（seek / 暂停继续 / 换源 / 循环回卷），它由锚点代次精确告知。
     *
     * <p>另外留一张安全网：只有当偏差大到 {@link #DRIFT_NET_MS}（5 秒，正常播放永远不会到这个量级）
     * 时才纠一次，用来兜住"本地卡了很久再恢复"这种情况。
     */
    private void syncProgress(ScreenEntity entity, MediaSession session) {
        if (session.isLive()) return;

        final int generation = entity.anchorGeneration();
        if (generation != this.lastAnchorGen) {
            if (!session.readyForDisplay()) return;   // 会话还没起播：等它起来再对齐（不消费代次）
            this.lastAnchorGen = generation;
            final long target = entity.projectedPositionMs();
            final long current = session.timeMs();
            if (Math.abs(target - current) < ANCHOR_ALIGN_MIN_DIFF_MS) return;
            CineDuct.LOGGER.info("[CineDuct] 屏幕 #{} 服务端改锚点 → 对齐：目标 {}ms，当前 {}ms",
                    this.entityId, target, current);
            session.seek(target);
            return;
        }

        if (entity.isPaused() || session.isPauseWanted() || !session.readyForDisplay()) return;
        /*
         * ★ 子进程正在"等缓存"时，**不许**用安全网去追服务端。
         *
         * <p>闸门就是"本地位置主动停住、等数据/等游戏来取帧"。这段时间里本地位置必然
         * 落后于服务端权威位置 —— 而这正是安全网（DRIFT_NET_MS）想修的那种偏差。
         * 于是会出现"闸门刚要起作用 → 安全网判它落后 → seek 回去 → 缓冲全清、重连 CDN"，
         * 一次卡顿换来一次真 seek，比原来的丢帧更糟。
         *
         * <p>所以闸门期间直接跳过判定；闸门结束后照常判定（滞后真超过 {@link #DRIFT_NET_MS}
         * 还是要纠，否则多人同屏会永久错开）。
         */
        if (session.hostWaitingForBuffer()) {
            this.lastSyncAt = System.currentTimeMillis();   // 别让限流窗口在闸门期间空转
            return;
        }
        final long target = entity.projectedPositionMs();
        final long diff = target - session.timeMs();
        if (Math.abs(diff) <= DRIFT_NET_MS) return;
        final long now = System.currentTimeMillis();
        if (now - this.lastSyncAt < DRIFT_NET_MIN_INTERVAL_MS) return;
        this.lastSyncAt = now;
        CineDuct.LOGGER.warn("[CineDuct] 屏幕 #{} 本地偏离权威位置 {}ms（超过安全网 {}ms）→ 纠一次",
                this.entityId, diff, DRIFT_NET_MS);
        session.seek(target);
    }

    /** 音量：实体自己的 0~100 × 距离衰减 × MC 主音量，最后交给引擎。 */
    public void applyVolume(ScreenEntity entity, double distance, MediaSession session) {
        /*
         * 直通（IEC 61937）模式下码流不可修改，音量由功放控制：这里直接跳过，
         * 免得每帧都推一次注定被忽略的音量（那是一次同步管道往返）。
         * UI/状态里会把该会话标成"音量由接收端控制"。
         */
        if (session.volumeLocked()) return;
        int ranged = entity.rangedVolume(distance);
        float master = MasterVolume.gain();
        int percent = Math.round(ranged * master);
        // setVolumePercent/setMuted 内部已做去重：只有真的变了才会发命令
        session.setVolumePercent(percent);
        session.setMuted(ranged <= 0);
    }

    // =====================================================================
    // GL 纹理
    // =====================================================================

    /**
     * 拿到一块"MC 管着"的 GL 纹理：用 {@link DynamicTexture} 注册进纹理管理器，
     * 然后直接往它的 id 上传我们自己的帧。
     *
     * <p>为什么要绕 MC 这一层：渲染时 {@code RenderType} 是按 {@code ResourceLocation} 取纹理的，
     * 必须有东西登记在 {@code TextureManager} 里。而 1.21.1 的 {@code AbstractTexture.textureId}
     * 是私有的（1.20.1 还是 protected），自己继承去塞 id 已经不行了。
     * {@code DynamicTexture} 自带一张 16×16 的占位 NativeImage（我们从不使用它），
     * 第一次上传时 {@code glTexImage2D} 会把存储换成真实尺寸。
     */
    /**
     * 注册本屏幕的画面纹理。
     *
     * <p>登记的**不是画面本身，而是一个"id 转发壳"**（{@link ScreenVideoTexture}）：
     * 渲染侧要的是 {@code ResourceLocation}（{@code RenderType} 按它取纹理，光影/着色器也只认这条路径），
     * 而真正的 GL 纹理由会话提供（子进程导入的互操作纹理 / 进程内引擎上传的目标纹理）。
     * 运行时传什么 id，壳就回答什么 id —— 传输方式切换（子进程挂掉回退进程内引擎）对渲染侧透明。
     *
     * <p>另外**另备一张** {@code DynamicTexture} 给进程内引擎上传用（{@code GlSafeUpload} 需要一个
     * 真实可写的 GL 纹理 id）；它不进纹理管理器，所以由 {@link #close()} 负责删。
     */
    private void ensureTexture() {
        if (this.textureId > 0) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;

        this.textureLocation = ResourceLocation.fromNamespaceAndPath(CineDuct.MODID, "screen/" + this.entityId);
        this.videoTexture = new ScreenVideoTexture();
        mc.getTextureManager().register(this.textureLocation, this.videoTexture);
        // 进程内引擎的落点：自带 16×16 占位图，第一次上传时换成真实尺寸
        this.texture = new DynamicTexture(16, 16, false);
        this.textureId = this.texture.getId();
        this.textureAllocated = false;
        CineDuct.LOGGER.info("[CineDuct] 屏幕 #{} 纹理已创建: upload_tex={} loc={}",
                this.entityId, this.textureId, this.textureLocation);
    }

    public ResourceLocation textureLocation() {
        return this.textureLocation;
    }

    /** 本屏幕当前绑定的会话（没有则 null）。 */
    public MediaSession session() {
        return Sessions.of(this.sessionId);
    }

    public boolean hasTexture() {
        return this.textureId > 0 && this.textureLocation != null;
    }

    public boolean everPresented() {
        return this.everPresented;
    }

    /** 最近一次错误（空串 = 正常）。 */
    public String lastError() {
        return this.lastError;
    }

    /** 在屏幕上给出可读的错误提示（限流，避免刷日志）。 */
    public void warnOnce(ScreenEntity entity) {
        final long now = System.currentTimeMillis();
        if (this.noFrameSince == 0L) this.noFrameSince = now;
        final boolean realError = !this.lastError.isEmpty();
        /*
         * ★ 没错误时不要每 5 秒喊一次"正在解析…"。
         *
         * 起播那几秒、直播偶尔缓冲一下，本来就没有可画的帧 —— 那是正常的，
         * 但每 5 秒一条聊天框消息会让人以为"模组在反复重新拉流"（用户就是这么理解的）。
         * 现在：真错误立刻报；只是"还没画面"就先给 15 秒宽限，宽限过了最多再提醒两次。
         */
        if (!realError && now - this.noFrameSince < 15_000L) return;
        if (!realError && this.warnCount >= 2) return;
        if (now - this.lastWarnAt < 5000L) return;
        this.lastWarnAt = now;
        this.warnCount++;
        String msg = realError ? this.lastError : "还没有画面（起播慢或持续缓冲）";
        dev.mediafix.ui.Notice.message("屏幕播放失败：" + msg + "（源: " + entity.sourceUri() + "）");
    }

    private void close() {
        releaseSession();
        Minecraft mc = Minecraft.getInstance();
        if (this.textureLocation != null && mc != null) {
            // 壳的 close() 是空实现，所以这里删不到画面纹理（那属于会话，由会话自己收）
            mc.getTextureManager().release(this.textureLocation);
        }
        if (mc == null && this.textureId > 0) {
            GlStateManager._deleteTexture(this.textureId);
        }
        // 上传目标纹理是我们自己分配的，必须自己删（它没进纹理管理器）
        if (this.texture != null) {
            try {
                this.texture.close();
            } catch (Throwable t) {
                CineDuct.LOGGER.warn("[CineDuct] 屏幕 #{} 释放上传纹理失败", this.entityId, t);
            }
        }
        this.textureId = -1;
        this.textureLocation = null;
        this.texture = null;
        this.videoTexture = null;
        this.textureAllocated = false;
        this.boundUri = "";
    }
}

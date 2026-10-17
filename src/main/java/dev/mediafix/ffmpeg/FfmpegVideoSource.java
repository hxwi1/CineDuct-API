package dev.mediafix.ffmpeg;

import dev.mediafix.CineDuct;
import dev.mediafix.config.FfmpegConfig;
import dev.mediafix.engine.PacketBuffer;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVCodecHWConfig;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVIOInterruptCB;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVRational;
import org.bytedeco.ffmpeg.avutil.AVBufferRef;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.PointerPointer;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avformat.*;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.swscale.*;

/**
 * 用 FFmpeg 解码一路视频轨，产出 RGBA 帧供渲染线程上传。
 *
 * <p><b>定位</b>：这是"只换解码器"方案的数据源，不承担播放器职责。时钟、暂停、
 * 直播判定、音频、多人同步全部留在原来的 VLC 播放器上；本类只回答三个问题：
 * 「现在该显示哪一帧」「画面多大」「跳到哪去了」。
 *
 * <p><b>线程模型</b>：一条解码线程（open + demux + decode + sws），一个固定槽数的
 * 环形缓冲。解码线程生产，渲染线程消费（{@link #present}）。环满即背压，不丢帧；
 * 消费者落后时按时间戳丢弃过期帧。
 *
 * <p>所有原生对象（AVFormatContext / AVCodecContext / AVPacket / AVFrame / SwsContext）
 * 都在 {@link #close()} 里显式释放，不依赖 GC —— JavaCPP 的包装对象被回收时不会自动
 * 释放其原生内存。
 */
public final class FfmpegVideoSource implements AutoCloseable {

    private static final int ST_FREE = 0;
    private static final int ST_WRITING = 1;
    private static final int ST_READY = 2;
    private static final int ST_SHOWING = 3;

    /*
     * 这里曾经有三个"兜底"常量：提前显示容差、槽位泄漏回收、快路径回收。
     * 它们的本意是治"帧环整体跑到时钟前面"导致的 3~4fps 饥饿，但实测反而制造了
     * 更严重、且必现的问题（抢走渲染线程正在上传的槽位并复用其缓冲区 →
     * 画面反复"卡一下 → 倍速追赶 → 再卡"）。已全部移除，帧环时序只走取帧/归还这一条链。
     */

    /*
     * 帧选取这块曾经被反复改过（解码领先量限流、提前显示容差、槽位回收、丢帧判据改成
     * "过期"/"已显示位置"……），每一次都让情况更糟：先是画面冻死，后来变成必现的
     * "卡一下 → 倍速追赶 → 再卡"和稳定 5fps。现已全部回滚到当初"几乎稳定"的那版：
     *   取帧 = 在【已到点（pts ≤ 时钟+8ms）】的帧里挑最新的一帧；
     *   丢帧 = 丢掉比它更早的就绪帧（掉帧保时序）。
     * 未解决的遗留问题（从头起播时会长时间卡在个位数帧率）先记录在此，不再盲改。
     */

    public static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /** 交给渲染线程的一帧。上传完必须调用 {@link FfmpegVideoSource#release(Frame)} 归还槽位。 */
    public static final class Frame {
        public final ByteBuffer buf;
        public final int width;
        public final int height;
        /** 该帧的显示时间（毫秒，相对媒体起点）。 */
        public final long ptsMs;
        private final Slot slot;
        /**
         * 产出这一帧的源。
         *
         * <p><b>为什么让帧自己记着源</b>：归还时必须回到"真正产出它的那条源"。
         * 引擎侧原来是用当前的 {@code this.video} 去归还 —— 一旦视频源在取帧与归还之间被换掉
         * （无缝换链 / 直链续期 / 引擎关闭置空），归还就落到了空处或另一条源上，
         * 于是原源的槽位永远停在 SHOWING：实测"取走=550 归还=90"，帧环被占满、画面卡住。
         * 帧自带源引用之后，这条路径与引擎的字段状态彻底解耦。
         */
        private final FfmpegVideoSource source;

        Frame(FfmpegVideoSource source, Slot slot, int width, int height, long ptsMs) {
            this.source = source;
            this.slot = slot;
            this.buf = slot.buf;
            this.width = width;
            this.height = height;
            this.ptsMs = ptsMs;
        }

        /** 归还到产出它的那条源（渲染线程上传完调用）。 */
        public void releaseToSource() {
            this.source.release(this);
        }
    }

    private final String url;
    private final String rawHeaders;

    // ---- 原生状态（仅解码线程与 close() 触碰） ----
    private AVFormatContext fmt;
    private AVCodecContext dec;
    private SwsContext sws;
    private AVPacket pkt;
    private AVFrame frame;
    /** CPU 侧目标帧：硬件解码时帧先搬到它上面再做 sws 转换。 */
    private AVFrame swFrame;
    /** 硬件解码设备（null = 软件解码）。 */
    private AVBufferRef hwDevice;
    private int hwPixFmt = AV_PIX_FMT_NONE;
    private int hwTransferFails;
    /** seek 目标（毫秒，-1 = 无进行中的精确寻址）。 */
    private volatile long seekTargetMs = -1L;
    /** "就地快进"的目标时间戳：早于它的帧直接丢弃（-1 = 不丢弃）。 */
    private volatile long skipBeforeMs = -1L;
    /** 是否是直播流（直播没有可跳位置，禁止任何 seek 自愈）。 */
    private volatile boolean liveStream;

    /** 渲染线程最近一次取帧时用的时钟（毫秒）。解码线程用它限制"解码领先量"。 */
    private volatile long lastClockMs;
    /** 最近一次取帧的时间（用来判断还有没有人在渲染）。 */
    private volatile long lastAcquireAt;
    /** 因"比选中的帧更早"被丢弃的过期帧数（诊断）。 */
    private volatile long framesDropped;
    /** 因为"解码领先太多"而等待时钟的次数（诊断）。 */
    private volatile long leadWaits;
    /** 渲染线程真正归还过多少次槽位（诊断：应当与"取走帧的次数"逐步持平）。 */
    private volatile long framesReleased;

    /** 取走帧的次数（= framesPresented，单独留一份便于日志对齐）。 */
    public long framesReleased() {
        return this.framesReleased;
    }

    /** 因长时间未归还/未就绪而被强制回收的槽位数（诊断：正常情况下应当一直是 0）。 */
    private volatile long reclaimedSlots;
    private volatile long lastLeakLogAt;
    /** 本次快进已丢弃的帧数（日志用）。 */
    private volatile long framesSkipped;
    private int seekDropped;
    private int vIdx = -1;
    private double timeBase = 1.0 / 1_000_000.0;
    private int swsW = -1;
    private int swsH = -1;
    private int swsFmt = -1;

    // ---- 对外可见状态 ----
    private volatile boolean opened;
    private volatile boolean failed;
    private volatile boolean ended;
    private volatile boolean closed;
    private volatile boolean paused;
    private volatile String lastError = "";
    /** 中断回调必须持有强引用：交给 native 的是函数指针，被 GC 掉会变成悬空指针。 */
    private org.bytedeco.ffmpeg.avformat.AVIOInterruptCB.Callback_Pointer interruptCallback;
    private volatile int width = 1;
    private volatile int height = 1;
    private volatile long durationMs = -1;
    /** 诊断计数：解出的帧数 / 当前可读帧数。 */
    private volatile long framesProduced;
    private volatile long framesPresented;

    // ---- 跨线程请求 ----
    private volatile long seekRequestMs = -1L;
    private volatile boolean seekRequested;

    private final Object lock = new Object();
    private Slot[] ring;
    /** 每帧的字节数（RGBA），用于按内存预算决定槽数。 */
    private long ringBytes = -1;
    private Thread worker;
    /** 压缩数据预读队列（demux 线程 → 解码线程）。 */
    private PacketBuffer packets;
    private Thread decodeThread;

    /*
     * 并行分带转换。做法是"每条带当作独立图像"：各自的 SwsContext（高度=带高）、
     * 各自的源平面指针（按行偏移）、各自的目标指针（按行偏移）。
     * 离线已验证：这样转出来的结果与整帧一次转换【逐字节完全一致】（diffBytes=0），
     * 所以既能并行提速，又没有任何画质损失。
     * （曾试过依赖 sws_scale 的 srcSliceY 切片语义，结果整片数据错位，故弃用。）
     */
    private SwsContext[] bandSws;
    private PointerPointer<BytePointer>[] bandSrc;
    private PointerPointer<BytePointer>[] bandDst;
    private IntPointer bandDstLines;
    private int[] bandY0;
    private int[] bandBh;
    private int bandPlanes;
    private int bandChromaH;
    private int bandW = -1;
    private int bandFullH = -1;
    private int bandFmt = -1;
    private java.util.concurrent.ExecutorService convertPool;

    public FfmpegVideoSource(URI uri, String headers) {
        this.url = uri.toString();
        this.rawHeaders = headers;
        // 环形缓冲的槽数要等第一帧出来才知道该放几帧（按内存预算算），这里先放一个占位
        this.ring = new Slot[]{new Slot()};
        this.ringBytes = -1;
    }

    // =====================================================================
    // 对外 API
    // =====================================================================

    public void start() {
        // 预读预算：缓的是压缩码流，不是解码后的帧（4K 一帧 33MB，缓不了几十秒）
        this.packets = new PacketBuffer((long) FfmpegConfig.videoReadAheadMb * 1024L * 1024L);
        Thread t = new Thread(this::run, "cineduct-ffmpeg-demux");
        t.setDaemon(true);
        this.worker = t;
        t.start();
    }

    private void startDecodeThread() {
        Thread t = new Thread(this::decodeMain, "cineduct-ffmpeg-decode");
        t.setDaemon(true);
        this.decodeThread = t;
        t.start();
    }

    public boolean isReady() {
        return this.opened && !this.failed && !this.closed;
    }

    public boolean isBroken() {
        return this.failed;
    }

    public boolean isEnded() {
        return this.ended;
    }

    /**
     * 解复用是不是**已经读到流尾**。
     *
     * <p>比 {@link #isEnded()} 早得多：`isEnded()` 要等解码线程把重排序窗口排空才算数，
     * 而片尾那一刻预读就见底了。上层用它把"流读完了"和"网络卡了"分开 ——
     * 分不开的话，片尾会被当成卡顿（引擎那边 8 秒的 starvedAtEnd 兜底就是在给这个擦屁股）。
     */
    public boolean inputEnded() {
        return this.packets != null && this.packets.endOfFile();
    }

    public String lastError() {
        return this.lastError;
    }

    public long framesProduced() {
        return this.framesProduced;
    }

    public long framesPresented() {
        return this.framesPresented;
    }

    /** 被当作"过期帧"丢弃的帧数（诊断：健康时应当接近 0）。 */
    public long framesDropped() {
        return this.framesDropped;
    }

    /** 因解码领先过多而等待时钟的次数（诊断）。 */
    public long leadWaits() {
        return this.leadWaits;
    }

    /** 最后一次交给渲染线程的帧的时间戳（诊断：和时钟对比就能看出画面是否跟得上）。 */
    public long lastPresentedPtsMs() {
        return this.lastPresentedPts;
    }

    private volatile long lastPresentedPts = -1L;

    /** 最近一次成功交出一帧的墙钟时间（用于判断"拿走的帧还会不会归还"）。 */
    private volatile long lastPresentAtMs;

    /** 解码后帧缓冲的总字节数（诊断/内存足迹）。 */
    public long ringBytesTotal() {
        synchronized (this.lock) {
            return this.ringBytes < 0 ? 0L : this.ringBytes * this.ring.length;
        }
    }

    /** 解码帧缓冲的槽数（诊断）。 */
    public int ringSlotCount() {
        synchronized (this.lock) {
            return this.ring.length == 1 && this.ringBytes < 0 ? 0 : this.ring.length;
        }
    }

    /**
     * 最近一秒的下载吞吐（字节/秒）；0 表示"队列是满的"（网络有余量，测不出来）。
     * ABR 用它做带宽估计。
     */
    public long readBytesPerSec() {
        return this.readBps;
    }

    private volatile long readBps;
    private long windowBytes;
    private long windowStart = System.currentTimeMillis();
    private boolean windowSaturated;

    /** 当前预读的压缩字节数（诊断）。 */
    public long bufferedBytes() {
        return this.packets == null ? 0 : this.packets.bufferedBytes();
    }

    public int queuedFrames() {
        synchronized (this.lock) {
            return occupiedLocked();
        }
    }

    /**
     * 已占用的槽数（正在写 / 待显示 / 正在显示）。
     * 刻意"数状态"而不是维护一个计数器：之前 {@code count} 在 publish 里 ++、
     * 却忘了在渲染线程归还帧时 --，于是它只增不减，涨到槽数上限后 {@link #takeFreeSlot}
     * 永久阻塞、解码线程直接死掉 —— 表现就是画面定住不动、预读涨满。
     */
    private int occupiedLocked() {
        int n = 0;
        for (Slot s : this.ring) {
            if (s.state != ST_FREE) n++;
        }
        return n;
    }

    /** 槽位状态分布（诊断）：R=就绪 W=写入中 S=显示中 F=空闲。 */
    private String slotBreakdown() {
        synchronized (this.lock) {
            int r = 0;
            int w = 0;
            int s = 0;
            int f = 0;
            for (Slot sl : this.ring) {
                switch (sl.state) {
                    case ST_READY -> r++;
                    case ST_WRITING -> w++;
                    case ST_SHOWING -> s++;
                    default -> f++;
                }
            }
            return "R" + r + "/W" + w + "/S" + s + "/F" + f;
        }
    }

    private Slot firstFreeLocked() {
        for (Slot s : this.ring) {
            if (s.state == ST_FREE) return s;
        }
        return null;
    }

    public int width() {
        return this.width;
    }

    public int height() {
        return this.height;
    }

    public long durationMs() {
        return this.durationMs;
    }

    /** 请求跳转到指定位置（毫秒）。由渲染线程或控制线程调用，实际 seek 在解码线程执行。 */
    public void requestSeek(long ms) {
        this.seekRequestMs = Math.max(0L, ms);
        this.lastRequestedSeekMs = this.seekRequestMs;
        this.seekRequested = true;
        this.lastSeekAtMs = System.currentTimeMillis();
        // 同音频源：立刻作废队列里已有的帧，避免旧位置的帧被当成 seek 之后的内容显示出来
        synchronized (this.lock) {
            for (Slot s : this.ring) {
                if (s.state != ST_SHOWING) s.state = ST_FREE;
            }
            this.lock.notifyAll();
        }
    }

    /**
     * 请求"就地快进"：不重开连接、不动缓冲，解码到目标时间戳之前的帧全部丢弃。
     *
     * <p>用于 waterframes 的小幅进度纠偏（见 {@code StreamConfig.catchUpMaxMs}）：
     * 真 seek 的代价是重开 CDN 连接 + 清空预读，一次 2~3 秒卡顿；而向前跳本来就可以
     * 直接在已经读进来的码流上丢掉一段 —— 代价只是解码器快进。
     */
    public void requestSkipTo(long ms) {
        this.skipBeforeMs = Math.max(0L, ms);
        this.framesSkipped = 0;
    }

    /** 渲染线程设置暂停意图：暂停时解码线程等待，不跑帧。 */
    /** 标记这是一条直播流（由引擎在建源时告知）：关闭 seek 相关的自愈与对齐逻辑。 */
    public void setLive(boolean live) {
        this.liveStream = live;
    }

    public void setPaused(boolean paused) {
        if (this.paused == paused) return;
        this.paused = paused;
        synchronized (this.lock) {
            this.lock.notifyAll();
        }
    }

    /**
     * 渲染线程每帧调用：按播放时钟取一帧。
     * @param clockMs 播放器时钟（毫秒）。<=0 或直播时表示"没有可靠时间轴"，取最新帧。
     * @return 待上传的帧；null 表示本轮没有可显示的帧。非 null 时必须调用 {@link #release(Frame)}。
     */
    public Frame acquire(long clockMs) {
        this.acquireCalls++;
        // 记给解码线程：它就是"解码领先量"的基准（见 publishFrame）
        this.lastClockMs = clockMs;
        this.lastAcquireAt = System.currentTimeMillis();
        if (!isReady()) {
            diagNull("源未就绪", clockMs, -1L, -1L);
            return null;
        }

        Slot chosen = null;
        long minPts = Long.MAX_VALUE;
        long maxPts = Long.MIN_VALUE;
        boolean future = false;
        boolean empty = false;
        Slot presented = null;
        synchronized (this.lock) {
            /*
             * ★ 关键：在【已经到显示时间】的帧里挑最新的一帧，而不是"先挑最新的、再问它到点没有"。
             *
             * 后者的致命问题：帧环塞满时（4 帧 ≈ 167ms），最新的那一帧永远领先时钟 167ms，
             * 于是每次都判"没到点"而不显示；等时钟终于追到它，才显示 1 帧、把其余 3 帧丢掉 ——
             * 结果就是每 167ms 只出一帧，24fps 的片子被放成 6fps。日志里"解出 24fps / 显示 6.5fps /
             * 帧队列满 / 预读满"就是这一幕。
             *
             * 开源播放器（mpv / ExoPlayer / LibVLC）的掉帧逻辑都是这个顺序：
             * 先把所有"presentation time 已到"的帧筛出来，取其中最新的一帧显示，其余丢弃。
             */
            for (Slot s : this.ring) {
                if (s.state != ST_READY) continue;
                if (s.ptsMs < minPts) minPts = s.ptsMs;
                if (s.ptsMs > maxPts) maxPts = s.ptsMs;
                // 还没到显示时间的帧不参与选择（时钟 <=0 表示没有可靠时间轴，那就全都算到点）
                if (clockMs > 0 && s.ptsMs > clockMs + 8L) continue;
                if (chosen == null || s.ptsMs >= chosen.ptsMs) chosen = s;
            }
            if (chosen == null) {
                // 环里有帧但都没到点 = 正常等下一轮；环里根本没帧 = 真的饥饿
                if (maxPts != Long.MIN_VALUE) future = true;
                else empty = true;

            }
            if (chosen != null) {
                /*
                 * ★ 只丢【比 chosen 更早】的过期帧（掉帧保时序）。
                 *
                 * 这里曾经写成"丢弃除 chosen 之外的所有就绪帧"，把【未来的帧】也一起扔了：
                 * 帧环 4 帧先被丢掉 3 帧，只剩一帧在未来 → 只能干等时钟追上它 →
                 * 每 167ms 才出一帧（24fps 的片子放成 6fps）。日志里"解出 24fps / 显示 6fps"
                 * 就是这么来的 —— 跟游戏帧率、跟网络、跟缓存都无关。
                 */
                for (Slot s : this.ring) {
                    if (s.state == ST_READY && s != chosen && s.ptsMs < chosen.ptsMs) {
                        s.state = ST_FREE;
                        this.framesDropped++;
                    }
                }
                chosen.state = ST_SHOWING;
                chosen.stateAtMs = System.currentTimeMillis();
                this.lastPresentAtMs = chosen.stateAtMs;
                presented = chosen;
                this.framesPresented++;
                this.lastPresentedPts = chosen.ptsMs;
                this.lock.notifyAll();
            }
        }
        evalDesync(clockMs, minPts, presented);
        if (empty) diagNull("帧环空", clockMs, -1L, -1L);
        else if (future) diagNull("帧未到显示时间", clockMs, minPts, maxPts);
        if (empty || future) return null;
        return new Frame(this, chosen, chosen.w, chosen.h, chosen.ptsMs);
    }

    /**
     * 取帧失败的诊断（每秒最多一条）。
     * "画面不动"只有两种可能：帧环空（解码没跟上）或帧一直在时钟前面（时间轴对不上），
     * 这条日志把两者和当时的时钟/帧 PTS 一起打出来，一眼就能分清。
     */
    private void diagNull(String reason, long clockMs, long minPts, long maxPts) {
        long now = System.currentTimeMillis();
        if (now - this.lastAcqLogAt < 1000L) return;
        this.lastAcqLogAt = now;
        synchronized (this.lock) {
            if ("帧环空".equals(reason)) this.nullEmpty++;
            else this.nullFuture++;
        }
        CineDuct.LOGGER.diag("[CineDuct][取帧] 空({}) 时钟={}ms 环内={}帧 槽位={} 取={} 还={} pts=[{}..{}] 解出={} 已显示={} 丢帧={} 等时钟={} 回收={} 预读={}KB 空/未到点={}/{}",
                reason, clockMs, queuedFrames(), slotBreakdown(),
                this.framesPresented, this.framesReleased,
                minPts == Long.MAX_VALUE ? "-" : minPts,
                maxPts == Long.MIN_VALUE ? "-" : maxPts,
                this.framesProduced, this.framesPresented, this.framesDropped, this.leadWaits,
                this.reclaimedSlots,
                bufferedBytes() / 1024,
                this.nullEmpty, this.nullFuture);
    }

    /**
     * 位置与时钟脱节时的自愈。**必须非常保守**，这是踩过坑换来的：
     *
     * <p>曾经用"偏差超过 1 秒就立刻重新对齐"，结果每 2 秒触发一次，而每次都会清空压缩预读
     * （日志里 预读 48MB → 0MB）和帧环，恢复又要 1~2 秒 —— 期间视频必然落后，于是又触发下一次，
     * 形成正反馈：预读永远填不满、帧环一直空、22 秒只显示 261 帧。看着就像"缓存太小"。
     *
     * <p>所以现在三个条件同时满足才动手：
     * <ol>
     *   <li>偏差超过 5 秒（丢帧能处理的量级一律不管）；</li>
     *   <li>这种偏差【持续 3 秒以上】（瞬时的都是 seek/缓冲恢复的正常现象）；</li>
     *   <li>最近 3 秒内没有 seek，且 10 秒内没有自愈过。</li>
     * </ol>
     */
    private void evalDesync(long clockMs, long minPts, Slot presented) {
        if (clockMs <= 0) return;
        /*
         * 直播不做"按时钟重新对齐"。
         *
         * 这条自愈是给点播准备的：脱节时 seek 回时钟位置就恢复了。但直播没有可跳位置 ——
         * 真去 avformat_seek_file 只会把直播流拉断。直播落后时正确做法是丢帧往前追
         * （acquire 的"只丢过期帧"已经在做），真断了由引擎的断流重连接管。
         */
        if (this.liveStream) return;
        long now = System.currentTimeMillis();

        int sign = 0;
        long amount = 0;
        if (minPts != Long.MAX_VALUE && minPts - clockMs > 5000L) {
            // 环里最早的帧都比时钟晚 5 秒以上：视频远远超前
            sign = 1;
            amount = minPts - clockMs;
        } else if (presented != null && clockMs - presented.ptsMs > 5000L) {
            // 正在显示的画面落后时钟 5 秒以上
            sign = -1;
            amount = clockMs - presented.ptsMs;
        }

        if (sign != this.desyncSign) {
            this.desyncSign = sign;
            this.desyncSince = now;
            return;
        }
        if (sign == 0) return;
        /*
         * ★ seek 之后的"过渡期"必须放过去：这段时间视频和声卡的落点天然不同步。
         *
         * 实测（cineduct-2026-09-24_11-04-30.log）：
         *   11:06:32.678 waterframes 要求对齐到 87650ms（我们因缓冲落后了 21 秒）
         *   11:06:32.692 视频/音频都发起 seek，视频缓冲见底，要等网络
         *   11:06:36.457 自愈判定"视频超前时钟 21322ms"，把视频 seek 回 66303ms ← 服务端的对齐被撤销
         *   11:06:38.727 音频缓冲回填，时钟才跳到 87530ms
         * 原因是声卡的"已播出位置"要等缓冲回填并真正播出去才会跟着 seek 跳（这次花了 6 秒），
         * 而视频解码 1 秒内就落到新位置了。拿这个"还没醒过来"的旧时钟去纠正视频，
         * 等于把刚做的对齐整个抹掉 —— 表现就是"跳到服务端要的位置又弹回旧位置，声音却在新位置"。
         *
         * 判据：视频已经落在【我们请求的那个 seek 目标】上（±2 秒），且 seek 后 30 秒内。
         * 这时该等的是音频，不是动视频。
         */
        if (this.lastRequestedSeekMs >= 0 && now - this.lastSeekAtMs < 30_000L
                && ((sign > 0 && minPts != Long.MAX_VALUE && Math.abs(minPts - this.lastRequestedSeekMs) < 2000L)
                    || (sign < 0 && presented != null && Math.abs(presented.ptsMs - this.lastRequestedSeekMs) < 2000L))) {
            this.desyncSince = now;                    // 不累计，等音频把时钟挪过去
            if (now - this.lastSettleLogAt > 1000L) {
                this.lastSettleLogAt = now;
                CineDuct.LOGGER.info("[CineDuct] seek 过渡期：视频已在目标 {}ms，等音频时钟跟上（当前时钟 {}ms）",
                        this.lastRequestedSeekMs, clockMs);
            }
            return;
        }
        if (now - this.desyncSince < 3000L) return;          // 还没持续够久
        if (now - this.lastSeekAtMs < 3000L) return;          // seek 刚发生，不同步是正常的
        if (now - this.lastDesyncSeekAt < 10000L) return;     // 限速

        this.lastDesyncSeekAt = now;
        this.desyncSign = 0;
        CineDuct.LOGGER.warn("[CineDuct] 视频与时钟持续脱节 {}ms 达 3 秒：按时钟 {}ms 重新对齐（自愈）",
                amount, clockMs);
        requestSeek(clockMs);
    }

    private volatile long lastDesyncSeekAt;
    /**
     * 最近一次【请求】的 seek 目标位置（-1 = 没有）。
     *
     * <p>注意与 {@link #seekTargetMs} 的区别：那个是"精确寻址"的临时目标，第一帧追上后就归 -1；
     * 这个是"我们要求跳到哪儿"的记录，要留一段时间 —— evalDesync 靠它区分
     * "视频已落到 seek 目标、只是音频时钟还没跟上"（过渡期，别动视频）和"真的脱节"。
     */
    private volatile long lastRequestedSeekMs = -1L;
    /** 上一条"seek 过渡期"诊断的时间（限流）。 */
    private volatile long lastSettleLogAt;
    /** 1 = 视频超前, -1 = 视频落后, 0 = 正常。 */
    private int desyncSign;
    private long desyncSince;
    /** 最近一次 seek（请求或执行）的时间，用于避免在 seek 期间误判脱节。 */
    /** 渲染线程调用 acquire 的累计次数：用来判断"是它不常叫我，还是我老是不给帧"。 */
    private volatile long acquireCalls;

    public long acquireCalls() {
        return this.acquireCalls;
    }

    private volatile long lastSeekAtMs;
    private volatile long lastAcqLogAt;
    private volatile long nullEmpty;
    private volatile long nullFuture;

    /** 上传完成后归还槽位。 */
    public void release(Frame frame) {
        if (frame == null) return;
        synchronized (this.lock) {
            frame.slot.state = ST_FREE;
            frame.slot.stateAtMs = System.currentTimeMillis();
            this.framesReleased++;
            this.lock.notifyAll();
        }
    }

    /**
     * 关闭。必须是线程安全的：渲染线程（播放器 release）与启动线程可能同时进来，
     * 无锁会造成双重释放，直接触发 JVM 原生崩溃。
     */
    @Override
    public synchronized void close() {
        if (this.closed) return;
        this.closed = true;
        if (this.packets != null) this.packets.abort();
        synchronized (this.lock) {
            this.lock.notifyAll();
        }
        Thread t = this.worker;
        Thread d = this.decodeThread;
        for (Thread thread : new Thread[]{t, d}) {
            if (thread != null && thread != Thread.currentThread()) {
                try {
                    thread.join(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        if ((t != null && t.isAlive()) || (d != null && d.isAlive())) {
            CineDuct.LOGGER.warn("[CineDuct] 视频线程未能在 3 秒内退出，跳过原生资源释放以避免崩溃");
            return;
        }
        if (t != null && t.isAlive() && t != Thread.currentThread()) {
            // 解码线程还活着：此时释放原生上下文就是 use-after-free。
            // 宁可泄漏这几 MB（进程退出时由系统回收），也不能崩游戏。
            CineDuct.LOGGER.warn("[CineDuct] 视频解码线程未能在 3 秒内退出，跳过原生资源释放以避免崩溃");
            return;
        }
        freeNative();
    }

    // =====================================================================
    // 解码线程
    // =====================================================================

    private void run() {
        try {
            if (!open()) {
                this.failed = true;
                CineDuct.LOGGER.error("[CineDuct] FFmpeg 视频源打开失败: {} ({})", this.lastError, this.url);
                return;
            }
            this.opened = true;
            AVStream vs0 = this.fmt.streams(this.vIdx);
            AVRational tb = vs0.time_base();
            AVRational fr = vs0.avg_frame_rate().den() == 0 ? vs0.r_frame_rate() : vs0.avg_frame_rate();
            CineDuct.LOGGER.info("[CineDuct] FFmpeg 已打开视频轨: {}x{} {}ms 时基={}/{} 帧率={}/{} ({}fps) {}",
                    this.width, this.height, this.durationMs,
                    tb.num(), tb.den(), fr.num(), fr.den(),
                    fr.den() == 0 ? "?" : String.format("%.3f", fr.num() / (double) fr.den()),
                    CineDuct.LOGGER.url(this.url));
            startDecodeThread();

            // 解复用线程：只负责从网络读取并把压缩包灌进预读队列（队列满即阻塞 = 预读深度上限）。
            // 解码器只由解码线程使用，formatContext 只由本线程使用，互不跨线程。
            while (!this.closed) {
                if (this.seekRequested) {
                    performSeek();
                    continue;
                }

                int r = av_read_frame(this.fmt, this.pkt);
                if (r < 0) {
                    this.packets.finish();          // 告诉解码线程：读完了，排空后可以收尾
                    synchronized (this.lock) {
                        this.lock.wait(50);
                    }
                    continue;
                }

                try {
                    if (this.pkt.stream_index() == this.vIdx) {
                        int size = this.pkt.size();
                        if (!this.packets.put(this.pkt)) break;
                        /*
                         * ABR 的带宽估计采样：只有"队列没被灌满"时读到的速度才代表网络真实能力。
                         * 队列满说明我们读得比下得慢（网络有余量），那种采样只会低估带宽，
                         * 会把码率无谓地压下去。
                         */
                        long buf = this.packets.bufferedBytes();
                        long cap = (long) dev.mediafix.config.FfmpegConfig.videoReadAheadMb * 1024L * 1024L;
                        if (size > 0) this.windowBytes += size;
                        this.windowSaturated |= buf > cap * 4 / 5;
                        long now = System.currentTimeMillis();
                        if (now - this.windowStart >= 1000L) {
                            this.readBps = this.windowSaturated ? 0L
                                    : this.windowBytes * 1000L / Math.max(1L, now - this.windowStart);
                            this.windowBytes = 0;
                            this.windowStart = now;
                            this.windowSaturated = false;
                        }
                    }
                } finally {
                    av_packet_unref(this.pkt);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            this.failed = true;
            this.lastError = String.valueOf(t);
            CineDuct.LOGGER.error("[CineDuct] FFmpeg 解码线程异常", t);
        } finally {
            // 原生资源统一由 close() 释放：那里的 join 保证本线程已退出
            synchronized (this.lock) {
                this.lock.notifyAll();
            }
        }
    }

    /** 解码线程：只碰解码器与帧缓冲；按队列代次（seek）自行重置解码器。 */
    private void decodeMain() {
        int[] serialOut = new int[1];
        int lastSerial = -1;
        boolean drained = false;
        try {
            while (!this.closed) {
                AVPacket packet = this.packets.poll(serialOut);
                boolean eof = packet == null;
                if (eof && !this.packets.endOfFile()) break;   // 被中止

                if (eof) {
                    // 读完了：只排空一次 —— 延迟解码器要送空包才会吐出重排序窗口里的帧
                    if (!drained) {
                        drained = true;
                        drainDecoder();
                        this.ended = true;
                    }
                    synchronized (this.lock) {
                        this.lock.wait(50);
                    }
                    continue;
                }

                try {
                    if (serialOut[0] != lastSerial) {
                        drained = false;
                        this.ended = false;   // seek 之后重新开始
                        if (lastSerial >= 0 && this.dec != null) avcodec_flush_buffers(this.dec);
                        lastSerial = serialOut[0];
                    }
                    // 兜底留一份输入包的时间戳：万一解码器输出帧上没带时间戳，还能退回用它
                    long pktPts = packet.pts();
                    if (pktPts == AV_NOPTS_VALUE) pktPts = packet.dts();
                    this.lastPacketPts = pktPts;
                    if (avcodec_send_packet(this.dec, packet) >= 0) {
                        while (true) {
                            long t0 = System.nanoTime();
                            if (avcodec_receive_frame(this.dec, this.frame) < 0) break;
                            this.tDecodeNs += System.nanoTime() - t0;
                            if (!publishFrame(serialOut[0])) break;
                        }
                    }
                } finally {
                    av_packet_free(packet);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            this.failed = true;
            this.lastError = String.valueOf(t);
            CineDuct.LOGGER.error("[CineDuct] FFmpeg 视频解码线程异常", t);
        }
    }

    /** EOF 时给解码器送一个空包，把重排序窗口里的帧吐出来（否则尾部丢帧）。 */
    private void drainDecoder() {
        try {
            int serial = this.packets == null ? -1 : this.packets.serial();
            avcodec_send_packet(this.dec, null);
            while (avcodec_receive_frame(this.dec, this.frame) >= 0) {
                if (!publishFrame(serial)) break;
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 把当前 frame 转成 RGBA 放进一个空闲槽。
     * @return false 表示已关闭或收到 seek，调用方应放弃剩余帧
     */
    private boolean publishFrame(int serial) {
        if (this.closed || this.seekRequested) return false;
        /*
         * 只发布"当前队列代次"的帧：seek 之后解码器里可能还残留着旧代次的帧，
         * 它们会被当成 seek 之后的落点（日志里"视频 seek 落点偏后 54042ms（目标 0ms）"就是这么来的），
         * 而且会把旧画面又显示出来。
         */
        if (serial != this.packets.serial()) return true;

        /*
         * 就地快进（见 requestSkipTo）：判定放在最前面 —— 用解码帧自己的 PTS，
         * 命中就直接丢，连 GPU→CPU 搬运和颜色转换都不做。
         * 位置很关键：一帧 4K 的完整流水线约 9ms，光解码约 2ms；
         * 快进 8 秒 = 192 帧，放前面是 0.4 秒，放后面要 1.7 秒（画面就白等这么久）。
         */
        long framePts = framePtsMs(this.frame);
        long skipTo = this.skipBeforeMs;
        if (skipTo > 0) {
            if (framePts >= 0 && framePts < skipTo) {
                this.framesSkipped++;
                return true;
            }
            this.skipBeforeMs = -1L;
            CineDuct.LOGGER.info("[CineDuct] 视频快进完成：丢弃 {} 帧，落到 {}ms（目标 {}ms）",
                    this.framesSkipped, framePts, skipTo);
        }

        /*
         * ★ 这里【刻意不做】"解码领先量限流"。
         *
         * 曾经在这里写过"这一帧离时钟还太远就先等时钟推进"，想用它治帧环整体跑到时钟前面
         * 导致的 3~4fps 饥饿。结果进了游戏的第一个坑：等待条件里用了会被渲染线程不断刷新的
         * 时间戳（lastAcquireAt），条件恒为真 → 解码线程永久卡死 → 画面定住、只有声音
         * （实测解出/已显示/帧队列 14 秒一动不动，取帧 162/秒全返回空）。
         *
         * 同一条饥饿问题改成【非阻塞】方式解决：acquire 里允许把"比时钟晚一点点"的帧
         * 提前放出来（EARLY_TOLERANCE_MS）。解码线程不碰任何同步等待，就不可能再卡死。
         */
        // 硬件解码出来的帧在显存里，先搬到 CPU 侧再转 RGBA
        AVFrame src = this.frame;
        if (this.hwDevice != null && this.frame.format() == this.hwPixFmt) {
            if (this.swFrame == null) {
                this.swFrame = av_frame_alloc();
                if (this.swFrame == null) return true;
            }
            av_frame_unref(this.swFrame);
            long tTr = System.nanoTime();
            int trRet = av_hwframe_transfer_data(this.swFrame, this.frame, 0);
            this.tTransferNs += System.nanoTime() - tTr;
            if (trRet < 0) {
                if (++this.hwTransferFails >= 30) {
                    CineDuct.LOGGER.error("[CineDuct] GPU 取帧连续失败 {} 次，回退软件解码", this.hwTransferFails);
                    mediafix$dropHw();
                }
                return true;
            }
            /*
             * ★ 关键：av_hwframe_transfer_data 只搬像素，【不搬属性】——
             *   搬完的 swFrame 上 best_effort_timestamp 和 pts 全是 AV_NOPTS_VALUE。
             *   以前这里没补属性，导致每一帧算出来的 PTS 都是 0：
             *   "按时间显示"彻底失效 → 画面按渲染帧率一路狂奔。
             *   实测 24fps 的片子被放成 ~44fps ≈ 1.8 倍速，这就是"二倍速"的真身。
             */
            av_frame_copy_props(this.swFrame, this.frame);
            this.hwTransferFails = 0;
            src = this.swFrame;
        }

        int fw = src.width();
        int fh = src.height();
        int fFmt = src.format();
        if (fw <= 0 || fh <= 0) return true;

        // 精确寻址：丢掉早于目标的帧（上限 900 帧 ≈ 30 秒，防止时间戳异常时死丢）
        long target = this.seekTargetMs;
        if (target >= 0) {
            long fpts = framePtsMs(src);
            if (fpts >= 0 && fpts + 40 < target && this.seekDropped < 900) {
                this.seekDropped++;
                return true;
            }
            // 落点偏后只记录、不重试（重试会形成"再调 performSeek → 计数器归零"的死循环）
            if (fpts > target + 1000L) {
                CineDuct.LOGGER.warn("[CineDuct] 视频 seek 落点偏后 {}ms（目标 {}ms），接受并交给引擎对齐",
                        fpts - target, target);
            }
            this.seekTargetMs = -1;
        }

        if (this.sws == null || this.swsW != fw || this.swsH != fh || this.swsFmt != fFmt) {
            recreateSws(fw, fh, fFmt);
            recreateBands(fw, fh, fFmt);
            if (this.sws == null) return true;
            this.width = fw;
            this.height = fh;
        }
        // 帧缓冲大小按内存预算来定（第一帧出来才知道分辨率）
        ensureRingSize(fw, fh);

        Slot slot;
        try {
            slot = takeFreeSlot();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (slot == null) return false;
        if (!slot.ensure(fw, fh)) {
            releaseSlot(slot);
            return true;
        }

        long tCv = System.nanoTime();
        int scaled = convertBands(src, slot, fh);
        this.tConvertNs += System.nanoTime() - tCv;
        this.tFrames++;
        if (scaled <= 0) {
            releaseSlot(slot);
            return true;
        }

        long ptsMs = framePtsMs(src);
        if (ptsMs < 0) ptsMs = 0;

        synchronized (this.lock) {
            slot.ptsMs = ptsMs;
            slot.state = ST_READY;
            slot.stateAtMs = System.currentTimeMillis();
            this.framesProduced++;
            this.lock.notifyAll();
        }
        return true;
    }

    /**
     * 把一帧从源格式转换成 RGBA。单线程时走整帧一次；多线程时按横向带并行，
     * 每条带用自己的上下文与指针偏移（离线验证过与整帧结果逐字节一致）。
     * @return 成功转换的行数（<=0 视为失败）
     */
    private int convertBands(AVFrame src, Slot slot, int fh) {
        if (this.bandSws == null || this.bandSws.length <= 1 || this.convertPool == null) {
            return sws_scale(this.sws, src.data(), src.linesize(), 0, fh, slot.dst, slot.dstLines);
        }
        int n = this.bandSws.length;
        java.util.concurrent.Future<?>[] tasks = new java.util.concurrent.Future<?>[n];
        IntPointer srcLines = src.linesize();
        for (int i = 0; i < n; i++) {
            int y0 = this.bandY0[i];
            int bh = this.bandBh[i];
            if (bh <= 0) continue;
            for (int pl = 0; pl < this.bandPlanes; pl++) {
                long off = (pl == 0)
                        ? (long) y0 * srcLines.get(pl)
                        : (long) (y0 >> this.bandChromaH) * srcLines.get(pl);
                this.bandSrc[i].put(pl, src.data(pl).position(off));
            }
            this.bandDst[i].put(0, slot.ptr.position((long) y0 * this.bandW * 4));
            final int k = i;
            tasks[i] = this.convertPool.submit(() -> sws_scale(this.bandSws[k], this.bandSrc[k],
                    srcLines, 0, this.bandBh[k], this.bandDst[k], this.bandDstLines));
        }
        int rows = 0;
        for (int i = 0; i < n; i++) {
            if (tasks[i] == null) continue;
            try {
                Object r = tasks[i].get();
                if (r instanceof Integer v && v > 0) rows += v;
            } catch (Throwable t) {
                CineDuct.LOGGER.warn("[CineDuct] 分带转换失败: {}", String.valueOf(t));
                return -1;
            }
        }
        return rows;
    }

    /**
     * 取帧时间戳。硬解时 src 是搬运后的 swFrame（属性要靠 av_frame_copy_props 才带得过来），
     * 所以优先问解码器直接输出的那一帧，它必定带时间戳。
     */
    private long framePtsMs(AVFrame src) {
        long pts = ptsOf(this.frame);
        if (pts < 0 && src != this.frame) pts = ptsOf(src);
        if (pts < 0 && this.lastPacketPts != AV_NOPTS_VALUE) {
            // 最后一层兜底：用输入包的时间戳（同一 time_base）。宁可粗一点，
            // 也绝不能让时间戳全变成 0 —— 那样"按时间显示"就失效，画面会一路狂奔。
            pts = (long) (this.lastPacketPts * this.timeBase * 1000.0);
        }
        return pts;
    }

    /** 解码器输出帧的时间戳（与包同单位）。三级退路，尽量避免拿不到。 */
    private long ptsOf(AVFrame f) {
        long pts = f.best_effort_timestamp();
        if (pts == AV_NOPTS_VALUE) pts = f.pts();
        if (pts == AV_NOPTS_VALUE) pts = f.pkt_dts();
        if (pts == AV_NOPTS_VALUE) return -1;
        return (long) (pts * this.timeBase * 1000.0);
    }

    /** 当前包的时间戳（原始单位），作为最后兜底。 */
    private volatile long lastPacketPts = AV_NOPTS_VALUE;

    // ---- 各阶段耗时（纳秒累计，引擎诊断每秒取一次并清零）----
    private long tDecodeNs;
    private long tTransferNs;
    private long tConvertNs;
    private long tFrames;

    /**
     * 取走并清零各阶段累计耗时，返回 [解码, 显存搬运, 色彩转换, 帧数]（单位毫秒，帧数为个数）。
     * 用来定位"到底哪一段吃掉了 41.7ms 的每帧预算"。
     */
    public long[] takeStageTimings() {
        long[] out = {this.tDecodeNs / 1000000L, this.tTransferNs / 1000000L,
                this.tConvertNs / 1000000L, this.tFrames};
        this.tDecodeNs = 0;
        this.tTransferNs = 0;
        this.tConvertNs = 0;
        this.tFrames = 0;
        return out;
    }

    /** 取一个空闲槽；没有就等（背压），期间响应 seek/close/暂停。 */
    private Slot takeFreeSlot() throws InterruptedException {
        synchronized (this.lock) {
            Slot free;
            // 背压条件 = 真的没有空槽了（而不是某个计数器到了上限）
            while (!this.closed && !this.seekRequested && (free = firstFreeLocked()) == null) {
                this.lock.wait(50);
            }
            if (this.closed || this.seekRequested) return null;
            free = firstFreeLocked();
            if (free != null) {
                free.state = ST_WRITING;   // 占位：写完前 acquire() 不会碰它
                free.stateAtMs = System.currentTimeMillis();
            }
            return free;
        }
    }

    private void releaseSlot(Slot slot) {
        synchronized (this.lock) {
            slot.state = ST_FREE;
            slot.stateAtMs = System.currentTimeMillis();
            this.lock.notifyAll();
        }
    }

    private void performSeek() {
        long ms;
        synchronized (this.lock) {
            ms = this.seekRequestMs;
            this.seekRequested = false;
            // 只释放"没在被显示"的槽：SHOWING 的那个可能正被渲染线程上传，动它会把纹理内容抹掉
            for (Slot s : this.ring) {
                if (s.state != ST_SHOWING) s.state = ST_FREE;
            }
            this.lock.notifyAll();
        }
        // 解码器归解码线程所有：这里只递增队列代次，解码线程见到新代次会自己 flush
        if (this.packets != null) this.packets.bumpSerial();
        // 与音频同理：用带窗口的 seek，max_ts = 目标 → 落点绝不可能在目标之后，
        // 于是"丢帧往前追"必然收敛，不需要任何重试。视频 GOP 密，窗口取 3 秒即可。
        long ts = ms * 1000L;
        long minTs = Math.max(0L, (ms - 3000L) * 1000L);
        int ok = avformat_seek_file(this.fmt, -1, minTs, ts, ts, AVSEEK_FLAG_BACKWARD);
        if (ok < 0) ok = av_seek_frame(this.fmt, -1, ts, AVSEEK_FLAG_BACKWARD);
        if (ok < 0) ok = av_seek_frame(this.fmt, -1, ts, 0);
        this.ended = false;
        // 精确寻址：av_seek_frame 只落到目标之前最近的 I 帧，GOP 长的流会差好几秒。
        // 记下目标，后续把早于目标的解码帧丢掉，直到真正追上目标为止。
        this.seekTargetMs = ms;
        this.seekDropped = 0;
        CineDuct.LOGGER.info("[CineDuct] 视频 seek -> {}ms (result={}, 丢弃前导帧上限900)", ms, ok);
    }

    // =====================================================================
    // 打开 / 释放
    // =====================================================================

    private boolean open() {
        AVDictionary opts = new AVDictionary();
        try {
            if (this.rawHeaders != null && !this.rawHeaders.isEmpty()) {
                av_dict_set(opts, "headers", this.rawHeaders, 0);
            }
            av_dict_set(opts, "user_agent", UA, 0);
            av_dict_set(opts, "reconnect", "1", 0);
            av_dict_set(opts, "reconnect_streamed", "1", 0);
            av_dict_set(opts, "reconnect_delay_max", "5", 0);
            av_dict_set(opts, "timeout", "10000000", 0);
            av_dict_set(opts, "buffer_size", "8388608", 0);
            av_dict_set(opts, "analyzeduration", "2000000", 0);
            av_dict_set(opts, "probesize", "4000000", 0);

            this.fmt = avformat_alloc_context();
            // 中断回调：close() 之后所有阻塞中的原生 I/O 立刻返回（否则关不掉卡在网络的流）
            this.interruptCallback = new AVIOInterruptCB.Callback_Pointer() {
                @Override
                public int call(org.bytedeco.javacpp.Pointer opaque) {
                    return closed ? 1 : 0;
                }
            };
            this.fmt.interrupt_callback().callback(this.interruptCallback);
            if (avformat_open_input(this.fmt, this.url, null, opts) < 0) {
                this.lastError = "无法打开输入";
                this.fmt = null;
                return false;
            }
            if (avformat_find_stream_info(this.fmt, (PointerPointer<?>) null) < 0) {
                this.lastError = "无法读取流信息";
                return false;
            }

            for (int i = 0; i < this.fmt.nb_streams(); i++) {
                if (this.fmt.streams(i).codecpar().codec_type() == AVMEDIA_TYPE_VIDEO) {
                    this.vIdx = i;
                    break;
                }
            }
            if (this.vIdx < 0) {
                this.lastError = "没有视频轨";
                return false;
            }

            AVStream stream = this.fmt.streams(this.vIdx);
            this.timeBase = av_q2d(stream.time_base());
            AVCodec codec = avcodec_find_decoder(stream.codecpar().codec_id());
            if (codec == null) {
                this.lastError = "找不到解码器 codecId=" + stream.codecpar().codec_id();
                return false;
            }

            this.dec = avcodec_alloc_context3(codec);
            if (this.dec == null) {
                this.lastError = "avcodec_alloc_context3 失败";
                return false;
            }
            if (avcodec_parameters_to_context(this.dec, stream.codecpar()) < 0) {
                this.lastError = "参数复制失败";
                return false;
            }
            int threads = FfmpegConfig.threads > 0
                    ? FfmpegConfig.threads
                    : Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
            this.dec.thread_count(threads);
            this.dec.thread_type(AVCodecContext.FF_THREAD_FRAME | AVCodecContext.FF_THREAD_SLICE);

            // 硬件解码：VLC 内置参数里有 --avcodec-hw=d3d11va,dxva2,any，我们也要跟上，
            // 否则 4K 只能软解。失败不致命——退回软解即可。
            if (FfmpegConfig.hwAccel) {
                mediafix$tryInitHw(codec);
            }
            if (this.hwDevice != null) {
                this.dec.hw_device_ctx(av_buffer_ref(this.hwDevice));
            }
            if (avcodec_open2(this.dec, codec, (PointerPointer<?>) null) < 0) {
                this.lastError = "avcodec_open2 失败";
                return false;
            }
            String codecName = codec.name() == null ? "?" : codec.name().getString();
            CineDuct.LOGGER.info("[CineDuct] 视频解码器: {} {}x{} 硬件解码={}",
                    codecName, this.dec.width(), this.dec.height(),
                    this.hwDevice != null ? "开" : "关(软解)");

            this.width = Math.max(1, this.dec.width());
            this.height = Math.max(1, this.dec.height());
            long dur = this.fmt.duration();
            this.durationMs = dur > 0 ? dur / 1000L : -1L;

            this.pkt = av_packet_alloc();
            this.frame = av_frame_alloc();
            return this.pkt != null && this.frame != null;
        } catch (Throwable t) {
            this.lastError = String.valueOf(t);
            CineDuct.LOGGER.error("[CineDuct] FFmpeg 打开失败", t);
            return false;
        } finally {
            av_dict_free(opts);
        }
    }

    /**
     * 尝试创建硬件解码设备并挑一个该解码器支持的硬件像素格式。
     * 顺序按 Windows 上的经验：D3D11VA 优先，其次 DXVA2。
     */
    private void mediafix$tryInitHw(AVCodec codec) {
        for (int type : new int[]{AV_HWDEVICE_TYPE_D3D11VA, AV_HWDEVICE_TYPE_DXVA2}) {
            AVBufferRef ref = new AVBufferRef();
            try {
                if (av_hwdevice_ctx_create(ref, type, (String) null, (AVDictionary) null, 0) < 0) {
                    continue;
                }
            } catch (Throwable t) {
                continue;
            }
            for (int i = 0; ; i++) {
                AVCodecHWConfig cfg;
                try {
                    cfg = avcodec_get_hw_config(codec, i);
                } catch (Throwable t) {
                    cfg = null;
                }
                if (cfg == null) break;
                if (cfg.device_type() == type
                        && (cfg.methods() & AV_CODEC_HW_CONFIG_METHOD_HW_DEVICE_CTX) != 0) {
                    this.hwDevice = ref;
                    this.hwPixFmt = cfg.pix_fmt();
                    CineDuct.LOGGER.info("[CineDuct] 硬件解码可用: type={} pixFmt={}",
                            type == AV_HWDEVICE_TYPE_D3D11VA ? "d3d11va" : "dxva2", this.hwPixFmt);
                    return;
                }
            }
            try {
                av_buffer_unref(ref);
            } catch (Throwable ignored) {
            }
        }
        CineDuct.LOGGER.info("[CineDuct] 未找到可用的硬件解码设备，使用软件解码");
    }

    /** 放弃硬件解码：释放设备、清掉绑定，后续帧走软解。 */
    private void mediafix$dropHw() {
        this.hwDevice = null;
        this.hwPixFmt = AV_PIX_FMT_NONE;
        this.hwTransferFails = 0;
        FfmpegConfig.hwAccel = false;   // 本次会话不再尝试
    }

    /**
     * 按内存预算决定保留几帧。
     * 参考流媒体播放器的缓冲水位线：缓冲应当按"能撑住多少秒"来配，而不是死写几帧。
     * 4K RGBA 一帧 33MB，256MB 预算 → 7 帧（约 0.3 秒）；1080p 同样预算 → 30 帧（约 1.2 秒）。
     * ringSize 配置若大于 4（旧默认值）则认为是用户显式指定，优先使用。
     */
    private void ensureRingSize(int w, int h) {
        long bytes = (long) w * h * 4;
        int wanted;
        if (FfmpegConfig.ringSize > 4) {
            wanted = FfmpegConfig.ringSize;
        } else {
            wanted = (int) ((long) FfmpegConfig.videoFrameBufferMb * 1024L * 1024L / Math.max(1L, bytes));
            wanted = Math.max(2, Math.min(64, wanted));
        }
        synchronized (this.lock) {
            if (this.ringBytes == bytes && this.ring.length == wanted) return;
            if (occupiedLocked() != 0) return;   // 有帧在飞就不动，下次再说
            this.ring = new Slot[wanted];
            for (int i = 0; i < wanted; i++) this.ring[i] = new Slot();
            this.ringBytes = bytes;
        }
        CineDuct.LOGGER.info("[CineDuct] 解码帧缓冲: {} 帧（{}MB 预算，每帧 {}MB ≈ {}ms 余量）",
                wanted, FfmpegConfig.videoFrameBufferMb, bytes / 1048576L, wanted * 1000L / 24);
    }

    /** 建立/更新并行分带转换所需的一切（分辨率或源格式变了才重建）。 */
    private void recreateBands(int w, int h, int srcFmt) {
        if (this.bandW == w && this.bandFullH == h && this.bandFmt == srcFmt) return;
        freeBands();
        int threads = FfmpegConfig.convertThreads > 0
                ? FfmpegConfig.convertThreads
                : Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 4));
        int band = ((h / threads) + 1) & ~1;      // 取偶数行，色度（4:2:0）才对得上
        if (band <= 0) band = h;
        int count = (h + band - 1) / band;
        this.bandPlanes = Math.max(1, av_pix_fmt_count_planes(srcFmt));
        this.bandChromaH = Math.max(0, av_pix_fmt_desc_get(srcFmt).log2_chroma_h());
        this.bandSws = new SwsContext[count];
        this.bandSrc = new PointerPointer[count];
        this.bandDst = new PointerPointer[count];
        this.bandY0 = new int[count];
        this.bandBh = new int[count];
        for (int i = 0; i < count; i++) {
            int y0 = i * band;
            int bh = Math.min(band, h - y0);
            this.bandY0[i] = y0;
            this.bandBh[i] = bh;
            this.bandSws[i] = sws_getContext(w, bh, srcFmt, w, bh, AV_PIX_FMT_RGBA,
                    SWS_BILINEAR, null, null, (double[]) null);
            this.bandSrc[i] = new PointerPointer<>(new BytePointer[this.bandPlanes]);
            this.bandDst[i] = new PointerPointer<>(new BytePointer[1]);
        }
        this.bandDstLines = new IntPointer(new int[]{w * 4});
        this.bandW = w;
        this.bandFullH = h;
        this.bandFmt = srcFmt;
        if (this.convertPool == null) {
            this.convertPool = java.util.concurrent.Executors.newFixedThreadPool(
                    Math.max(1, count), r -> {
                        Thread t = new Thread(r, "cineduct-ffmpeg-convert");
                        t.setDaemon(true);
                        return t;
                    });
        }
        CineDuct.LOGGER.info("[CineDuct] 分带转换: {} 条带 x {}行（源格式={} 平面={}）", count, band, srcFmt, this.bandPlanes);
    }

    private void freeBands() {
        if (this.bandSws != null) {
            for (SwsContext c : this.bandSws) if (c != null) sws_freeContext(c);
            this.bandSws = null;
        }
        this.bandW = -1;
    }

    private void recreateSws(int w, int h, int srcFmt) {
        if (this.sws != null) {
            sws_freeContext(this.sws);
            this.sws = null;
        }
        // 输出统一为 RGBA，配合 GL 侧 GL_RGBA + GL_UNSIGNED_BYTE，字节序自洽不依赖 VLC 的约定
        this.sws = sws_getContext(w, h, srcFmt, w, h, AV_PIX_FMT_RGBA,
                SWS_BILINEAR, null, null, (double[]) null);
        this.swsW = w;
        this.swsH = h;
        this.swsFmt = srcFmt;
        if (this.sws == null) {
            this.lastError = "sws_getContext 失败";
            CineDuct.LOGGER.error("[CineDuct] sws_getContext 失败: {}x{} fmt={}", w, h, srcFmt);
        }
    }

    private void freeNative() {
        if (!FfmpegConfig.freeNativeOnClose) {
            // 诊断开关：只泄漏不释放。用来判断崩溃是否来自原生释放路径。
            CineDuct.LOGGER.warn("[CineDuct] freeNativeOnClose=false：跳过视频原生资源释放（故意泄漏，供排查）");
            return;
        }
        if (this.swFrame != null) {
            av_frame_free(this.swFrame);
            this.swFrame = null;
        }
        if (this.hwDevice != null) {
            av_buffer_unref(this.hwDevice);
            this.hwDevice = null;
        }
        if (this.frame != null) {
            av_frame_free(this.frame);
            this.frame = null;
        }
        if (this.pkt != null) {
            av_packet_free(this.pkt);
            this.pkt = null;
        }
        if (this.convertPool != null) {
            this.convertPool.shutdownNow();
            this.convertPool = null;
        }
        freeBands();
        if (this.sws != null) {
            sws_freeContext(this.sws);
            this.sws = null;
        }
        if (this.dec != null) {
            avcodec_free_context(this.dec);
            this.dec = null;
        }
        if (this.fmt != null) {
            avformat_close_input(this.fmt);
            this.fmt = null;
        }
    }

    /** 环形缓冲的一个槽：一块 direct RGBA 缓冲 + 复用的 JavaCPP 指针包装。 */
    private static final class Slot {
        ByteBuffer buf;
        BytePointer ptr;
        PointerPointer<BytePointer> dst;
        IntPointer dstLines;
        long ptsMs;
        volatile int state = ST_FREE;
        /** 状态最近一次改变的时间（用来发现"槽位被占用后再也没变 READY"的泄漏）。 */
        volatile long stateAtMs = System.currentTimeMillis();
        int w;
        int h;

        boolean ensure(int width, int height) {
            int need = width * height * 4;
            if (need <= 0 || need > 64 * 1024 * 1024) return false;
            if (this.buf == null || this.buf.capacity() < need) {
                this.buf = ByteBuffer.allocateDirect(need);
                this.ptr = new BytePointer(this.buf);
                this.dst = new PointerPointer<>(new BytePointer[]{this.ptr});
                this.dstLines = new IntPointer(new int[]{width * 4});
            }
            this.w = width;
            this.h = height;
            return true;
        }
    }
}

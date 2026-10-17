package dev.mediafix.engine;

import dev.mediafix.CineDuct;
import dev.mediafix.config.FfmpegConfig;
import dev.mediafix.ffmpeg.FfmpegVideoSource;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVIOInterruptCB;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.avutil.AVChannelLayout;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVRational;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.swresample.SwrContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.PointerPointer;

import java.net.URI;
import java.nio.ByteBuffer;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avformat.*;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.swresample.*;

/**
 * 用 FFmpeg 解码一路音频轨，产出可直接写入 {@link AudioSink} 的 PCM 块（S16LE，交错）。
 *
 * <p>与视频侧不同，音频<b>不需要按时钟节流</b>：sink 自己的缓冲区就是队列，
 * 写满它会退回 0 字节（天然背压）。所以这里只做"解码 + 重采样 + 排队"，节奏交给引擎。
 *
 * <p>输出的声道数/采样率由引擎按"源格式 + 设备能力"决定并传进来；源参数中途变化
 * （chained OGG / Icecast 换采样率）会重建重采样器。
 */
public final class FfmpegAudioSource implements AutoCloseable {

    /** 每次输出的样本数（交错后 = samples × channels）。 */
    private static final int OUT_SAMPLES = 2048;
    /**
     * 解码后 PCM 的环形缓冲槽数。32 槽只有 1.4 秒，设备行缓冲又只有 ~0.8 秒，
     * 抗抖动余量太薄；192 槽约 8 秒，代价不到 2MB（整条音频码流本身才几 MB）。
     */
    private static final int RING = 192;

    /** 一块待写入 sink 的 PCM。 */
    public static final class Chunk {
        public final ByteBuffer buf;
        public final int length;
        public final long ptsMs;
        public final int slotIndex;
        private final Slot slot;

        Chunk(Slot slot, int length, long ptsMs, int slotIndex) {
            this.slot = slot;
            this.buf = slot.buf;
            this.length = length;
            this.ptsMs = ptsMs;
            this.slotIndex = slotIndex;
        }
    }

    private final String url;
    private final String rawHeaders;
    /** 输出格式由引擎在探测到源格式后协商决定（见 start(int,int)）。 */
    private int outChannels = 2;
    private int outRate = 48000;
    /** 源音频参数（prepare() 之后有效），供引擎与设备能力做协商。 */
    private volatile int srcChannels = 2;
    private volatile int srcSampleRate = 48000;
    /** 源编码名（ec-3 / aac / flac …），仅用于日志与状态展示。 */
    private volatile String codecName = "?";
    private volatile long srcDurationMs = -1;

    private AVFormatContext fmt;
    private AVCodecContext dec;
    private SwrContext swr;
    private AVPacket pkt;
    private AVFrame frame;
    private int aIdx = -1;
    private double timeBase = 1.0 / 1000.0;

    private volatile boolean opened;
    private volatile boolean failed;
    private volatile boolean ended;
    private volatile boolean closed;
    private volatile boolean paused;
    private volatile String lastError = "";
    /** 中断回加强引用，防止被 GC 后 native 侧拿到悬空函数指针。 */
    private AVIOInterruptCB.Callback_Pointer interruptCallback;
    private volatile long durationMs = -1;

    private volatile long seekRequestMs = -1L;
    private volatile boolean seekRequested;

    private final Object lock = new Object();
    private final Slot[] ring = new Slot[RING];
    private Thread worker;
    /** 压缩数据预读队列（demux → decode）。音频码率低，几 MB 就能缓好几分钟。 */
    private PacketBuffer packets;
    private Thread decodeThread;
    private double nextPtsSec = Double.NaN;
    /** 解码线程当前正在处理的队列代次（seek 时递增）。 */
    private volatile int currentSerial;
    /** 当前正在发布的数据块所属代次（用于过滤 seek 之前的旧块）。 */
    private volatile int publishSerial = -1;
    /** 最近一次真正执行 seek 的时间：用于抑制 seek 之后的误报。 */
    private volatile long lastSeekDoneAt;
    /** seek 目标（毫秒，-1 = 无进行中的精确寻址）。 */
    private volatile long seekTargetMs = -1L;
    private int seekDropped;
    private double ptsOffsetSec;
    /** 上一块音频的时间戳与结束点，用来检查"音块有没有接上"（接不上就是耳朵听到的接缝）。 */
    private long lastChunkPtsMs = -1L;
    private long lastChunkEndMs = -1L;
    private long lastJumpLogAt;
    private volatile long resampleMismatch;
    private long lastMismatchLogAt;

    public long resampleMismatch() {
        return this.resampleMismatch;
    }

    public FfmpegAudioSource(URI uri, String headers) {
        this.url = uri.toString();
        this.rawHeaders = headers;
    }

    /** 源声道数（prepare() 之后有效）。 */
    public int sourceChannels() {
        return this.srcChannels;
    }

    /** 源采样率（prepare() 之后有效）。 */
    public int sourceSampleRate() {
        return this.srcSampleRate;
    }

    /** 源编码名（如 ec-3 / aac / flac），prepare() 之后有效。 */
    public String sourceCodecName() {
        return this.codecName;
    }

    /**
     * 打开输入并读取音频参数（同步、会阻塞在网络上；由引擎的启动线程调用）。
     * 只做打开与参数读取，不启动解码线程——因为输出格式要等引擎看过设备能力才能定。
     */
    public boolean prepare() {
        if (!open()) {
            this.failed = true;
            return false;
        }
        this.srcDurationMs = this.durationMs;
        return true;
    }

    /** 用协商好的输出格式启动解码线程。 */
    public void start(int outChannels, int outRate) {
        this.outChannels = Math.max(1, outChannels);
        this.outRate = Math.max(8000, outRate);
        for (int i = 0; i < RING; i++) {
            this.ring[i] = new Slot(this.outChannels * OUT_SAMPLES * 2);
        }
        this.packets = new PacketBuffer((long) FfmpegConfig.audioReadAheadMb * 1024L * 1024L);
        Thread t = new Thread(this::run, "cineduct-ffmpeg-audiodemux");
        t.setDaemon(true);
        this.worker = t;
        t.start();
    }

    // ---------------- 对外 ----------------

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
     * 解复用是不是**已经读到流尾**（比 {@link #isEnded()} 早，见视频源里同名方法的说明）。
     * 上层用它把"流读完了"和"网络卡了"分开 —— 否则片尾会被判成"音频欠载（缓冲耗尽）"。
     */
    public boolean inputEnded() {
        return this.packets != null && this.packets.endOfFile();
    }

    public String lastError() {
        return this.lastError;
    }

    public long durationMs() {
        return this.durationMs;
    }

    public int channels() {
        return this.outChannels;
    }

    public int sampleRate() {
        return this.outRate;
    }

    /** 环形缓冲里已解码但还没交给设备的音频时长（毫秒）= 解码提前量。 */
    public long bufferedMs() {
        synchronized (this.lock) {
            if (this.outRate <= 0) return 0L;
            return (long) occupiedLocked() * OUT_SAMPLES * 1000L / this.outRate;
        }
    }

    /** 环形缓冲占用的字节数（内存足迹诊断）。 */
    public long ringBytesTotal() {
        synchronized (this.lock) {
            return (long) RING * this.outChannels * OUT_SAMPLES * 2;
        }
    }

    /** 环形缓冲的总容量（毫秒）。 */
    public long capacityMs() {
        synchronized (this.lock) {
            if (this.outRate <= 0) return 0L;
            return (long) RING * OUT_SAMPLES * 1000L / this.outRate;
        }
    }

    /** 非阻塞取一块 PCM；null 表示暂时没有。取到后必须 {@link #release(Chunk)}。 */
    public Chunk poll() {
        if (!isReady()) return null;
        synchronized (this.lock) {
            /*
             * ★ 必须取【时间戳最小】的那一块，而不是"数组顺序上第一个就绪的槽"。
             *
             * 曾经按数组顺序取，结果播放顺序会乱：日志里每分钟十几次
             * "音频出队顺序倒退：上一块 39744ms，本块 39722ms（差 -22ms）"，
             * 倒退量恰好是一个音块（21ms）的整数倍 —— 相邻几块被换了顺序。
             * 耳朵听到的就是块边界上的"咔"（采样级检测：边界跳变 8748，而块内最大只有 521）。
             *
             * 原因：槽位的填充顺序与数组顺序在 seek 之后、以及填充与消费交错时会错开。
             * 按时间戳取块可以让这件事在构造上不可能发生，代价只是每次扫 192 个槽。
             */
            Slot best = null;
            int bestIdx = -1;
            for (int i = 0; i < this.ring.length; i++) {
                Slot s = this.ring[i];
                if (s.state != ST_READY) continue;
                if (best == null || s.ptsMs < best.ptsMs) {
                    best = s;
                    bestIdx = i;
                }
            }
            if (best == null) return null;
            best.state = ST_SHOWING;
            return new Chunk(best, best.length, best.ptsMs, bestIdx);
        }
    }

    public void release(Chunk chunk) {
        if (chunk == null) return;
        synchronized (this.lock) {
            chunk.slot.state = ST_FREE;
            this.lock.notifyAll();
        }
    }

    public void requestSeek(long ms) {
        this.seekRequestMs = Math.max(0L, ms);
        this.seekRequested = true;
        /*
         * 请求发出的一瞬间就把队列里已有的数据全部作废。
         * 否则从"请求 seek"到"解复用线程真正执行 seek"之间有几十毫秒，
         * 解码线程会继续把【旧位置】的音频灌进环形缓冲并继续播放 —— 听感就是接缝。
         */
        synchronized (this.lock) {
            for (Slot s : this.ring) {
                if (s.state != ST_SHOWING) s.state = ST_FREE;
            }
            this.lock.notifyAll();
        }
    }

    public void setPaused(boolean paused) {
        if (this.paused == paused) return;
        this.paused = paused;
        synchronized (this.lock) {
            this.lock.notifyAll();
        }
    }

    /** 线程安全的关闭：启动线程与渲染线程可能同时进来，无锁会双重释放并崩 JVM。 */
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
        for (Thread th : new Thread[]{t, d}) {
            if (th != null && th != Thread.currentThread()) {
                try {
                    th.join(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        if ((t != null && t.isAlive() && t != Thread.currentThread())
                || (d != null && d.isAlive() && d != Thread.currentThread())) {
            CineDuct.LOGGER.warn("[CineDuct] 音频解码线程未能在 3 秒内退出，跳过原生资源释放以避免崩溃");
            return;
        }
        freeNative();
    }

    // ---------------- 解码线程 ----------------

    private static final int ST_FREE = 0;
    private static final int ST_WRITING = 1;
    private static final int ST_READY = 2;
    private static final int ST_SHOWING = 3;

    private void run() {
        try {
            if (this.fmt == null && !open()) {   // 引擎已 prepare 过则不重复打开
                this.failed = true;
                return;
            }
            this.opened = true;
            AVRational atb = this.fmt.streams(this.aIdx).time_base();
            CineDuct.LOGGER.info("[CineDuct] FFmpeg 音频轨时基={}/{}", atb.num(), atb.den());
            // 源格式与输出格式都要打：杜比全景声是 6ch 源 → 设备只有 2ch 时要下混，这里能一眼看出来
            CineDuct.LOGGER.info("[CineDuct] FFmpeg 音频轨已打开: {} {}ch {}Hz -> 输出 {}ch {}Hz dur={}ms {}",
                    this.codecName, this.srcChannels, this.srcSampleRate,
                    this.outChannels, this.outRate, this.durationMs, CineDuct.LOGGER.url(this.url));
            startDecodeThread();

            // 解复用：只读网络、灌预读队列（队列满即阻塞，这就是预读深度的上限）
            while (!this.closed) {
                if (this.seekRequested) {
                    performSeek();
                    continue;
                }
                int r = av_read_frame(this.fmt, this.pkt);
                if (r < 0) {
                    this.packets.finish();
                    synchronized (this.lock) {
                        this.lock.wait(50);
                    }
                    continue;
                }
                try {
                    if (this.pkt.stream_index() == this.aIdx) {
                        if (!this.packets.put(this.pkt)) break;
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
            CineDuct.LOGGER.error("[CineDuct] FFmpeg 音频解码线程异常", t);
        }
    }

    private void startDecodeThread() {
        Thread t = new Thread(this::decodeMain, "cineduct-ffmpeg-audiodec");
        t.setDaemon(true);
        this.decodeThread = t;
        t.start();
    }

    /** 解码线程：只碰解码器/重采样器；遇到新代次（seek）自行重置两者。 */
    private void decodeMain() {
        int[] serialOut = new int[1];
        int lastSerial = -1;
        boolean drained = false;
        try {
            while (!this.closed) {
                AVPacket packet = this.packets.poll(serialOut);
                boolean eof = packet == null;
                if (eof && !this.packets.endOfFile()) break;
                if (!eof) this.currentSerial = serialOut[0];
                this.publishSerial = serialOut[0];

                if (eof) {
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
                        this.ended = false;
                        if (lastSerial >= 0) {
                            if (this.dec != null) avcodec_flush_buffers(this.dec);
                            // 丢掉重采样器里属于旧位置的样本
                            if (this.swr != null) {
                                swr_close(this.swr);
                                swr_init(this.swr);
                            }
                            this.nextPtsSec = Double.NaN;
                        }
                        lastSerial = serialOut[0];
                    }
                    if (avcodec_send_packet(this.dec, packet) >= 0) {
                        while (avcodec_receive_frame(this.dec, this.frame) >= 0) {
                            if (!publish()) break;
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
            CineDuct.LOGGER.error("[CineDuct] FFmpeg 音频解码线程异常", t);
        }
    }

    private void drainDecoder() {
        try {
            avcodec_send_packet(this.dec, null);
            while (avcodec_receive_frame(this.dec, this.frame) >= 0) {
                if (!publish()) break;
            }
            flushResampler();
        } catch (Throwable ignored) {
        }
    }

    /** 把当前 frame 重采样成 S16 交错 PCM 放进一个空槽。 */
    private boolean publish() {
        if (this.closed || this.seekRequested) return false;
        // 只发布当前代次的数据块：seek 前解出的旧块会被重新打上时间戳、混进时间轴
        if (this.publishSerial != this.packets.serial()) return true;

        int inRate = this.frame.sample_rate();
        int inFmt = this.frame.format();
        int inCh = this.frame.ch_layout().nb_channels();
        if (inRate <= 0 || inCh <= 0 || inFmt == AV_SAMPLE_FMT_NONE) return true;

        if (this.swr == null || inRate != this.lastInRate || inFmt != this.lastInFmt || inCh != this.lastInCh) {
            rebuildResampler(inRate, inFmt, inCh);
            if (this.swr == null) return true;
        }

        long ptsMs = ptsOf(this.frame, inRate);
        if (ptsMs < 0) ptsMs = 0;

        /*
         * 精确寻址：丢掉早于目标的音频块（音频是主时钟，容差一个小块 + 上限保护）。
         *
         * 这里【刻意不做重试】。曾经加过"落点偏后则回退重 seek"，但重试本身又是通过
         * seekRequested 再调一次 performSeek，而 performSeek 会重置重试计数 ——
         * 结果就是无限重 seek：解码器被反复 flush、重采样器反复重建、整个环形缓冲反复清空，
         * 听感就是"拼接没接好"，位置还会被算飞到 0（= 从头开始）。
         * 落点偏后改由 performSeek 的"窗口"从 API 层面杜绝（max_ts = 目标）。
         */
        long target = this.seekTargetMs;
        if (target >= 0) {
            if (ptsMs + 200 < target && this.seekDropped < 3000) {
                this.seekDropped++;
                return true;
            }
            if (ptsMs > target + 1000L) {
                CineDuct.LOGGER.warn("[CineDuct] 音频 seek 落点偏后 {}ms（目标 {}ms），接受并让画面跟随",
                        ptsMs - target, target);
            }
            this.seekTargetMs = -1;
        }

        // 接缝检测：相邻音块必须首尾相接（差一个块长）。不接 = 耳朵听到的"拼接没接好"，必须留证。
        // seek 之后本来就会跳位置，那几秒内的"不连续"是正常的，别刷成告警。
        if (this.seekTargetMs < 0 && this.lastChunkEndMs >= 0
                && System.currentTimeMillis() - this.lastSeekDoneAt > 2000L) {
            long gap = ptsMs - this.lastChunkEndMs;
            if (Math.abs(gap) > 3L && System.currentTimeMillis() - this.lastJumpLogAt > 1000L) {
                this.lastJumpLogAt = System.currentTimeMillis();
                CineDuct.LOGGER.warn("[CineDuct] 音频块不连续：上一块结束于 {}ms，本块起于 {}ms（差 {}ms）",
                        this.lastChunkEndMs, ptsMs, gap);
            }
        }
        this.lastChunkPtsMs = ptsMs;
        this.lastChunkEndMs = ptsMs + (long) (this.frame.nb_samples() * 1000.0 / Math.max(1, inRate));

        Slot slot;
        try {
            slot = takeFreeSlot();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (slot == null) return false;

        int inSamples = this.frame.nb_samples();
        int converted = swr_convert(this.swr, slot.out, OUT_SAMPLES, this.frame.data(), inSamples);
        if (converted <= 0) {
            releaseSlot(slot);
            return true;
        }
        /*
         * 同采样率同声道布局时，swr 只做格式转换，理应 1:1 输出。
         * 如果输入输出帧数不一致，就意味着每块都在丢/重采样 —— 而时间戳是按输入帧数推进的，
         * 于是每块边界都会出现不连续（听感就是"咔"）。这里把它记下来。
         */
        if (converted != inSamples) {
            this.resampleMismatch++;
            if (System.currentTimeMillis() - this.lastMismatchLogAt > 1000L) {
                this.lastMismatchLogAt = System.currentTimeMillis();
                CineDuct.LOGGER.warn("[CineDuct] 重采样帧数不一致：输入 {} 帧 / 输出 {} 帧（累计 {} 次）",
                        inSamples, converted, this.resampleMismatch);
            }
        }

        int bytes = converted * this.outChannels * 2;
        synchronized (this.lock) {
            slot.length = bytes;
            slot.ptsMs = ptsMs;
            slot.state = ST_READY;
            this.lock.notifyAll();
        }
        return true;
    }

    /** 把重采样器里残留的样本吐出来（否则每次 seek/结束时尾部会有杂音或截断）。 */
    private void flushResampler() {
        if (this.swr == null) return;
        try {
            while (true) {
                Slot slot;
                try {
                    slot = takeFreeSlot();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (slot == null) return;
                int flushed = swr_convert(this.swr, slot.out, OUT_SAMPLES, (PointerPointer<?>) null, 0); // NOSONAR
                if (flushed <= 0) {
                    releaseSlot(slot);
                    return;
                }
                synchronized (this.lock) {
                    slot.length = flushed * this.outChannels * 2;
                    slot.ptsMs = (long) (Double.isNaN(this.nextPtsSec) ? 0 : this.nextPtsSec * 1000.0);
                    slot.state = ST_READY;
                    this.lock.notifyAll();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private int lastInRate = -1;
    private int lastInFmt = -1;
    private int lastInCh = -1;

    private void rebuildResampler(int inRate, int inFmt, int inCh) {
        if (this.swr != null) {
            swr_free(this.swr);
            this.swr = null;
        }
        SwrContext swr = swr_alloc();
        if (swr == null) return;

        AVChannelLayout inLayout = new AVChannelLayout();
        AVChannelLayout outLayout = new AVChannelLayout();
        try {
            av_channel_layout_copy(inLayout, this.frame.ch_layout());
            av_channel_layout_default(outLayout, this.outChannels);

            av_opt_set_chlayout(swr, "in_chlayout", inLayout, 0);
            av_opt_set_int(swr, "in_sample_rate", inRate, 0);
            av_opt_set_sample_fmt(swr, "in_sample_fmt", inFmt, 0);
            av_opt_set_chlayout(swr, "out_chlayout", outLayout, 0);
            av_opt_set_int(swr, "out_sample_rate", this.outRate, 0);
            av_opt_set_sample_fmt(swr, "out_sample_fmt", AV_SAMPLE_FMT_S16, 0);

            if (swr_init(swr) < 0) {
                swr_free(swr);
                return;
            }
        } finally {
            av_channel_layout_uninit(inLayout);
            av_channel_layout_uninit(outLayout);
        }

        this.swr = swr;
        this.lastInRate = inRate;
        this.lastInFmt = inFmt;
        this.lastInCh = inCh;
        CineDuct.LOGGER.info("[CineDuct] 音频重采样: {}ch {}Hz fmt={} -> {}ch {}Hz S16",
                inCh, inRate, inFmt, this.outChannels, this.outRate);
    }

    private long ptsOf(AVFrame f, int inRate) {
        long raw = f.best_effort_timestamp();
        if (raw == AV_NOPTS_VALUE) raw = f.pts();
        double ptsSec;
        if (raw == AV_NOPTS_VALUE) {
            ptsSec = Double.isNaN(this.nextPtsSec) ? 0.0 : this.nextPtsSec;
        } else {
            ptsSec = raw * this.timeBase;
        }
        // 跳变（广告拼接/seek 残留）拉平，保持时间轴连续
        if (!Double.isNaN(this.nextPtsSec)) {
            double jump = ptsSec + this.ptsOffsetSec - this.nextPtsSec;
            if (Math.abs(jump) > 5.0) {
                /*
                 * 每次修正都必须留痕：这个偏移会直接改掉音频时间戳，而音频是主时钟，
                 * 一次错误修正就能把整条时间轴拉走（实测把 92 秒的时钟拉到 19 秒，
                 * 视频就"永久定在最后一帧"了）。跳变太大（>20 秒）时宁可不修。
                 */
                if (Math.abs(jump) > 20.0) {
                    if (System.currentTimeMillis() - this.lastJumpLogAt > 1000L) {
                        this.lastJumpLogAt = System.currentTimeMillis();
                        CineDuct.LOGGER.warn("[CineDuct] 音频时间戳出现 {}ms 的巨大跳变：忽略该次修正（保持原始时间轴）",
                                (long) (jump * 1000));
                    }
                } else {
                    this.ptsOffsetSec -= jump;
                    if (System.currentTimeMillis() - this.lastJumpLogAt > 1000L) {
                        this.lastJumpLogAt = System.currentTimeMillis();
                        CineDuct.LOGGER.info("[CineDuct] 音频时间戳跳变 {}ms：已按连续时间轴拉平（累计偏移 {}ms）",
                                (long) (jump * 1000), (long) (this.ptsOffsetSec * 1000));
                    }
                }
            }
        }
        double absolute = ptsSec + this.ptsOffsetSec;
        this.nextPtsSec = absolute + (double) f.nb_samples() / Math.max(1, inRate);
        return (long) (absolute * 1000.0);
    }

    private Slot takeFreeSlot() throws InterruptedException {
        synchronized (this.lock) {
            Slot free;
            // 背压条件 = 真的没有空槽了。用计数器时会有一个死角：performSeek 把 count 归零，
            // 但被渲染/投喂线程拿着的 SHOWING 槽仍占着，于是槽用尽却"看起来还有空"，返回 null，
            // 调用方直接 break 掉解码循环 —— 音频就永久没了。
            while (!this.closed && !this.seekRequested && (free = firstFreeLocked()) == null) {
                this.lock.wait(50);
            }
            if (this.closed || this.seekRequested) return null;
            free = firstFreeLocked();
            if (free != null) free.state = ST_WRITING;
            return free;
        }
    }

    private Slot firstFreeLocked() {
        for (Slot s : this.ring) {
            if (s.state == ST_FREE) return s;
        }
        return null;
    }

    /** 已占用的槽数（数状态，不维护计数器）。 */
    private int occupiedLocked() {
        int n = 0;
        for (Slot s : this.ring) {
            if (s.state != ST_FREE) n++;
        }
        return n;
    }

    private void releaseSlot(Slot slot) {
        synchronized (this.lock) {
            slot.state = ST_FREE;
            this.lock.notifyAll();
        }
    }

    private void performSeek() {
        long ms;
        synchronized (this.lock) {
            ms = this.seekRequestMs;
            this.seekRequested = false;
            for (Slot s : this.ring) {
                if (s.state != ST_SHOWING) s.state = ST_FREE;
            }
            this.lock.notifyAll();
        }
        // 解码器与重采样器归解码线程所有：这里只递增队列代次
        if (this.packets != null) this.packets.bumpSerial();
        /*
         * 用"带窗口的精确 seek"而不是裸的 av_seek_frame。
         *
         * DASH 分片是 5 秒一个，sidx 索引下裸 seek 会落到目标【之后】整整一个分片
         * （实测 seek 47250ms 落到 ~52200ms）。音频是主时钟，落点偏后 = 整条时间轴超前 5 秒，
         * 于是 waterframes 每 2 秒自动 seek 一次去追，画面被反复向前拽（看着像二倍速），
         * 每次 seek 又 flush 掉音频缓冲（听着一直卡）。
         *
         * avformat_seek_file 的 max_ts 给了硬约束：落点绝不可能超过目标，
         * 于是"丢帧往前追"的逻辑（下面 publish 里那段）必然能收敛，不需要任何重试。
         */
        long ts = ms * 1000L;
        long minTs = Math.max(0L, (ms - 6000L) * 1000L);
        int ok = avformat_seek_file(this.fmt, -1, minTs, ts, ts, AVSEEK_FLAG_BACKWARD);
        if (ok < 0) ok = av_seek_frame(this.fmt, -1, ts, AVSEEK_FLAG_BACKWARD);
        if (ok < 0) ok = av_seek_frame(this.fmt, -1, ts, 0);
        CineDuct.LOGGER.info("[CineDuct] 音频 seek -> {}ms (result={})", ms, ok);
        this.ended = false;
        this.nextPtsSec = ms / 1000.0;
        this.ptsOffsetSec = 0;
        this.seekTargetMs = ms;
        this.seekDropped = 0;
        this.lastSeekDoneAt = System.currentTimeMillis();
    }

    // ---------------- 打开 / 释放 ----------------

    private boolean open() {
        AVDictionary opts = new AVDictionary();
        try {
            if (this.rawHeaders != null && !this.rawHeaders.isEmpty()) {
                av_dict_set(opts, "headers", this.rawHeaders, 0);
            }
            av_dict_set(opts, "user_agent", FfmpegVideoSource.UA, 0);
            av_dict_set(opts, "reconnect", "1", 0);
            av_dict_set(opts, "reconnect_streamed", "1", 0);
            av_dict_set(opts, "reconnect_delay_max", "5", 0);
            av_dict_set(opts, "timeout", "10000000", 0);
            av_dict_set(opts, "buffer_size", "4194304", 0);
            av_dict_set(opts, "probesize", "2000000", 0);

            this.fmt = avformat_alloc_context();
            this.interruptCallback = new AVIOInterruptCB.Callback_Pointer() {
                @Override
                public int call(org.bytedeco.javacpp.Pointer opaque) {
                    return closed ? 1 : 0;
                }
            };
            this.fmt.interrupt_callback().callback(this.interruptCallback);
            if (avformat_open_input(this.fmt, this.url, null, opts) < 0) {
                this.lastError = "无法打开音频输入";
                this.fmt = null;
                return false;
            }
            if (avformat_find_stream_info(this.fmt, (PointerPointer<?>) null) < 0) {
                this.lastError = "无法读取音频流信息";
                return false;
            }
            for (int i = 0; i < this.fmt.nb_streams(); i++) {
                if (this.fmt.streams(i).codecpar().codec_type() == AVMEDIA_TYPE_AUDIO) {
                    this.aIdx = i;
                    break;
                }
            }
            if (this.aIdx < 0) {
                this.lastError = "没有音频轨";
                return false;
            }

            AVStream stream = this.fmt.streams(this.aIdx);
            this.timeBase = av_q2d(stream.time_base());
            AVCodec codec = avcodec_find_decoder(stream.codecpar().codec_id());
            if (codec == null) {
                this.lastError = "找不到音频解码器 id=" + stream.codecpar().codec_id();
                return false;
            }
            this.dec = avcodec_alloc_context3(codec);
            if (this.dec == null || avcodec_parameters_to_context(this.dec, stream.codecpar()) < 0) {
                this.lastError = "音频解码器参数失败";
                return false;
            }
            if (avcodec_open2(this.dec, codec, (PointerPointer<?>) null) < 0) {
                this.lastError = "音频 avcodec_open2 失败";
                return false;
            }
            long dur = this.fmt.duration();
            this.durationMs = dur > 0 ? dur / 1000L : -1L;
            this.srcChannels = Math.max(1, stream.codecpar().ch_layout().nb_channels());
            this.srcSampleRate = Math.max(8000, stream.codecpar().sample_rate());
            try {
                this.codecName = org.bytedeco.ffmpeg.global.avcodec
                        .avcodec_get_name(stream.codecpar().codec_id()).getString();
            } catch (Throwable ignored) {
                this.codecName = "?";
            }
            this.pkt = av_packet_alloc();
            this.frame = av_frame_alloc();
            return this.pkt != null && this.frame != null;
        } catch (Throwable t) {
            this.lastError = String.valueOf(t);
            CineDuct.LOGGER.error("[CineDuct] 打开音频失败", t);
            return false;
        } finally {
            av_dict_free(opts);
        }
    }

    private void freeNative() {
        if (!FfmpegConfig.freeNativeOnClose) {
            CineDuct.LOGGER.warn("[CineDuct] freeNativeOnClose=false：跳过音频原生资源释放（故意泄漏，供排查）");
            return;
        }
        if (this.frame != null) {
            av_frame_free(this.frame);
            this.frame = null;
        }
        if (this.pkt != null) {
            av_packet_free(this.pkt);
            this.pkt = null;
        }
        if (this.swr != null) {
            swr_free(this.swr);
            this.swr = null;
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

    /** 一个 PCM 槽：固定大小的直接缓冲 + 复用的 JavaCPP 输出指针。 */
    private static final class Slot {
        final ByteBuffer buf;
        final PointerPointer<BytePointer> out;
        volatile int state = ST_FREE;
        int length;
        long ptsMs;

        Slot(int bytes) {
            this.buf = ByteBuffer.allocateDirect(bytes);
            this.out = new PointerPointer<>(new BytePointer[]{new BytePointer(this.buf)});
        }
    }
}

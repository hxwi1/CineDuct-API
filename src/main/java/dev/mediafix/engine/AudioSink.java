package dev.mediafix.engine;

import dev.mediafix.CineDuct;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * 音频输出：把 PCM(S16LE) 写到系统混音器，并提供"已播放到哪儿"。
 *
 * <p>用 Java Sound 而不是 OpenAL 的原因：本引擎要自己掌握时间轴，需要<b>可读的播放位置</b>
 * （{@link #playedMs()}）和独立的缓冲深度（{@link #pendingMs()}）；OpenAL 的时间查询依赖
 * AL_SOFT_source_latency 扩展，且要和 MC 自己的 SoundEngine 抢 source 管理。用 Java Sound
 * 走系统混音器与 MC 的输出天然共存，位置查询也稳定。
 *
 * <p>增益在写入时用软件乘法实现（不是设备的 MASTER_GAIN），这样：范围不受设备限制、
 * 音量变化立即生效、静音与音量互相独立——和 waterframes 的"距离衰减 + 主音量"模型对得上。
 */
public final class AudioSink implements AutoCloseable {

    private SourceDataLine line;
    private int channels;
    private int sampleRate;
    private volatile float gain = 1.0f;
    /** MC 主音量系数（由桥接层提供；waterframes 自己已乘过时为 1）。 */
    private volatile float masterGain = 1.0f;
    private volatile boolean muted;
    private volatile boolean paused;
    private long writtenFrames;
    private byte[] scratch = new byte[0];
    /** 设备缓冲写空（= 播出静音）的累计次数，用于判断"音频断续"是不是 CPU 饿着了。 */
    private volatile long underruns;
    /** 设备没有接受完整一块的累计次数（= 那一段样本被截断，听感就是接缝）。 */
    private volatile long shortWrites;
    private long lastShortLogAt;
    /** 块边界爆音的累计次数（采样级检测）。 */
    private volatile long seams;
    /** 实际施加过淡入去咔的次数。 */
    private volatile long declicks;
    /** 下一次块边界跳变是预期内的（就地快进/跳转），仍去咔但不计入爆音统计。 */
    private volatile boolean expectJump;

    /**
     * 下一块必须做淡入去咔（一次性）。
     *
     * <p>有三种"拼接"在采样连续性检查里是【看不出来】的，只能靠这个标志：
     * <ul>
     *   <li><b>设备缓冲写空（欠载）</b> —— 声卡已经播出去一段【我们数据里根本没有的静音】，
     *       新块从任意相位接回去就是"咔"的一下，而我们的数据首尾是连续的；</li>
     *   <li><b>seek / 就地快进</b> —— flush 之后新旧位置毫无关系；</li>
     *   <li><b>暂停后恢复</b> —— 线路 stop/start，接缝同理。</li>
     * </ul>
     * 实测：这三类接缝在日志里 爆音=0 去咔=0（检测器一次都没报），但耳朵听得很清楚 ——
     * 就是因为它们根本不在我们的采样数据里。
     */
    private volatile boolean forceDeclick;

    public long declicks() {
        return this.declicks;
    }
    private short lastSample;
    private boolean haveLastSample;
    private long lastSeamLogAt;
    private byte[] prevTail = new byte[0];
    private int prevTailLen;
    private long lastAccepted;

    public long seams() {
        return this.seams;
    }

    public long shortWrites() {
        return this.shortWrites;
    }

    public long underruns() {
        return this.underruns;
    }

    /** 设备实际播放到的帧数（{@link SourceDataLine#getLongFramePosition()}）。 */
    public long playedFrames() {
        SourceDataLine l = this.line;
        return l == null ? 0L : l.getLongFramePosition();
    }

    public boolean isOpen() {
        return this.line != null && this.line.isOpen();
    }

    public int channels() {
        return this.channels;
    }

    public int sampleRate() {
        return this.sampleRate;
    }

    /** 已写入但还没播出来的毫秒数（= 引擎的 A/V 提前量）。 */
    public long pendingMs() {
        if (this.line == null || this.sampleRate <= 0) return 0L;
        long pending = this.writtenFrames - this.playedFrames();
        if (pending <= 0) return 0L;
        return pending * 1000L / this.sampleRate;
    }

    /**
     * 设备行缓冲的真实容量（毫秒）。
     * 注意：{@code open(format, n)} 里的 n 只是"请求值"，系统混音器常常只给一个远小于它的缓冲
     * （实测约 800ms）。任何"缓冲够不够"的判断都必须以这个真实值为准，否则会永远等不到。
     */
    public long capacityMs() {
        SourceDataLine l = this.line;
        if (l == null || this.sampleRate <= 0 || this.channels <= 0) return 0L;
        return l.getBufferSize() / (2L * this.channels) * 1000L / this.sampleRate;
    }

    /** 当前可听位置（毫秒，从本 sink 打开算起）。 */
    public long playedMs() {
        if (this.sampleRate <= 0) return 0L;
        return this.playedFrames() * 1000L / this.sampleRate;
    }

    /**
     * 打开输出。声道数与采样率由调用方（引擎）按"源格式 + 设备能力"决定。
     * @return false 表示设备不支持该组合，调用方应降级后重试
     */
    public boolean open(int channels, int sampleRate) {
        close();
        try {
            AudioFormat format = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                    sampleRate, 16, channels, channels * 2, sampleRate, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            if (!AudioSystem.isLineSupported(info)) {
                CineDuct.LOGGER.warn("[CineDuct] 音频设备不支持 {}ch {}Hz S16", channels, sampleRate);
                return false;
            }
            SourceDataLine dl = (SourceDataLine) AudioSystem.getLine(info);
            // 缓冲深度按配置（默认 2.5 秒）：网络抖动全靠它吸收，太小就会断续
            int bufferBytes = Math.max(4096,
                    sampleRate * channels * 2 * Math.max(1, dev.mediafix.config.FfmpegConfig.audioBufferMs) / 1000);
            dl.open(format, bufferBytes);
            dl.start();
            this.line = dl;
            this.channels = channels;
            this.sampleRate = sampleRate;
            this.writtenFrames = 0;
            CineDuct.LOGGER.info("[CineDuct] 音频输出已打开: {}ch {}Hz S16 行缓冲={}ms (请求 {}ms)",
                    channels, sampleRate, capacityMs(),
                    dev.mediafix.config.FfmpegConfig.audioBufferMs);
            return true;
        } catch (Throwable t) {
            CineDuct.LOGGER.warn("[CineDuct] 音频输出打开失败 ({}ch {}Hz): {}", channels, sampleRate, String.valueOf(t));
            return false;
        }
    }

    /** 设备支持的最大物理声道数（用于把 5.1/7.1 降级到设备能播的档位）。 */
    public static int maxSupportedChannels(int sampleRate) {
        for (int ch : new int[]{8, 6, 2, 1}) {
            AudioFormat format = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                    sampleRate, 16, ch, ch * 2, sampleRate, false);
            if (AudioSystem.isLineSupported(new DataLine.Info(SourceDataLine.class, format))) {
                return ch;
            }
        }
        return 2;
    }

    /**
     * 写入一块 PCM（S16LE，声道数与本 sink 一致）。软件增益/静音在复制时应用。
     * @return 实际写入的字节数
     */
    public int write(ByteBuffer pcm, int length) {
        SourceDataLine l = this.line;
        if (l == null || !l.isOpen() || length <= 0) return 0;
        if (this.scratch.length < length) this.scratch = new byte[length];
        int free0 = l.available();
        pcm.clear();
        pcm.limit(length);
        pcm.get(this.scratch, 0, length);

        // 采样级接缝检测：块边界的跳变如果远大于块内最大跳变，那就是一次能听见的爆音
        checkSeam(length);

        float g = this.muted ? 0f : this.gain * this.masterGain;
        if (g != 1.0f) {
            applyGain(this.scratch, length, g);
        }
        /*
         * 真实的"声卡欠载"检测：写入前如果设备缓冲几乎是空的，说明上一段音频已经播完、
         * 设备只能播静音 —— 这就是耳朵听到的"断一下"。缓冲里明明还有几秒数据却仍然断，
         * 只可能是写声卡的线程被 CPU 饿着了（比如 4K 的色彩转换把核心占满）。
         */
        // 刚开声或刚 seek 完（缓冲必然为空）不算欠载，只统计"已经在正常播放中"的情况
        if (this.writtenFrames > this.sampleRate && free0 >= l.getBufferSize() - length - 2048) {
            this.underruns++;
            // 设备已经播出去一段静音：下一块的起跳沿必须削掉，否则就是"咔"的一下
            this.forceDeclick = true;
        }
        /*
         * ★ 必须写满整块。
         *
         * SourceDataLine.write 的契约允许"只写一部分"（设备被 flush、线路中断等），
         * 而以前我们只写一次就按【整块长度】记账并丢弃这一块 —— 没写进去的那部分样本
         * 就凭空消失了，耳朵听到的就是"拼接没接好"。这里循环写到写完为止。
         */
        int off = 0;
        while (off < length) {
            int n = l.write(this.scratch, off, length - off);
            if (n <= 0) break;
            off += n;
        }
        if (off < length && this.writtenFrames > this.sampleRate) {
            this.shortWrites++;
            if (System.currentTimeMillis() - this.lastShortLogAt > 1000L) {
                this.lastShortLogAt = System.currentTimeMillis();
                dev.mediafix.CineDuct.LOGGER.warn("[CineDuct] 声卡只接受了 {} / {} 字节（累计 {} 次）—— 这一段音频会被截断",
                        off, length, this.shortWrites);
            }
        }
        this.lastAccepted = off;
        return off;
    }

    /**
     * 边界爆音检测。
     *
     * <p>相邻两块 PCM 必须首尾相接；如果边界的采样跳变【远大于】块内最大跳变，
     * 说明这两块之间丢了/多了样本（或顺序错了），耳朵听到的就是"咔"一下 —— 也就是"拼接没接好"。
     * 用"与块内最大跳跃比较"而不是固定阈值，是为了不把鼓点这类正常的瞬态误判成爆音。
     */
    private void checkSeam(int length) {
        if (length < 8 || this.channels <= 0) return;
        short firstSample = (short) ((this.scratch[0] & 0xFF) | (this.scratch[1] << 8));
        // 块内（同一声道）相邻采样的最大跳变
        int maxInner = 0;
        int step = 2 * this.channels;
        for (int i = step; i + 1 < length; i += step) {
            short a = (short) ((this.scratch[i - step] & 0xFF) | (this.scratch[i - step + 1] << 8));
            short b = (short) ((this.scratch[i] & 0xFF) | (this.scratch[i + 1] << 8));
            int d = Math.abs(b - a);
            if (d > maxInner) maxInner = d;
        }
        if (this.forceDeclick) {
            /*
             * 设备刚欠载（播出去一段数据里根本没有的静音）、刚 seek 或刚恢复播放：
             * 这些接缝在我们的采样数据里是连续的，下面那套"边界跳变远大于块内跳变"永远发现不了，
             * 但耳朵照样听得见 —— 无条件削掉起跳沿。
             */
            this.forceDeclick = false;
            this.expectJump = false;
            declick(length);
        } else if (this.haveLastSample) {
            int jump = Math.abs(firstSample - this.lastSample);
            if (jump > 6000 && jump > maxInner * 3) {
                /*
                 * 无论是不是预期内的跳变（就地快进），都必须削掉起跳沿 ——
                 * 否则耳朵听到的就是"咔"一下。区别只在于要不要算进爆音统计：
                 * 快进造成的不连续是我们的主动行为，不该报警。
                 */
                declick(length);
                if (this.expectJump) {
                    this.expectJump = false;
                } else {
                this.seams++;
                if (System.currentTimeMillis() - this.lastSeamLogAt > 1000L) {
                    this.lastSeamLogAt = System.currentTimeMillis();
                    int fstep = 2 * Math.max(1, this.channels);
                    StringBuilder prev = new StringBuilder();
                    StringBuilder next = new StringBuilder();
                    for (int k = 3; k >= 0; k--) {
                        if (this.prevTailLen >= (k + 1) * fstep) {
                            int i = k * fstep;
                            prev.append(this.prevTail[i]).append(' ');
                        }
                    }
                    for (int k = 0; k < 4 && (k + 1) * fstep <= length; k++) {
                        next.append(this.scratch[k * fstep]).append(' ');
                    }
                    dev.mediafix.CineDuct.LOGGER.warn(
                            "[CineDuct] 块边界爆音：跳变={} 块内最大={} 累计={} | 上一块尾采样[{}] 本块首采样[{}] 增益={} 设备接收={}/{}",
                            jump, maxInner, this.seams, prev.toString().trim(), next.toString().trim(),
                            String.format("%.3f", this.gain * this.masterGain), this.lastAccepted, length);
                }
                }
            }
        }
        int stepBytes = 2 * Math.max(1, this.channels);
        int lastIdx = length - stepBytes;
        if (lastIdx >= 0) {
            this.lastSample = (short) ((this.scratch[lastIdx] & 0xFF) | (this.scratch[lastIdx + 1] << 8));
            this.haveLastSample = true;
        }
        // 留一份尾部样本，爆音时和本块头部一起打出来（只看第一个声道）
        int keep = Math.min(this.scratch.length, Math.max(stepBytes * 4, 8));
        if (this.prevTail.length != keep) this.prevTail = new byte[keep];
        int from = Math.max(0, length - stepBytes * 4);
        for (int i = 0; i < this.prevTail.length && from + i < length; i++) {
            this.prevTail[i] = this.scratch[from + i];
        }
        this.prevTailLen = Math.max(0, Math.min(this.prevTail.length, length - from));
        for (int i = this.prevTailLen; i < this.prevTail.length; i++) this.prevTail[i] = 0;
    }

    /**
     * 写入一整块已经拼好的 PCM（S16LE 交错）。
     * 攒批的意义见 MediaEngine.feedLoop：把 ~200ms 的连续音频一次交给设备，边界数量降到 1/9。
     */
    public int writeBatch(byte[] pcm, int length) {
        SourceDataLine l = this.line;
        if (l == null || !l.isOpen() || length <= 0) return 0;
        if (this.scratch.length < length) this.scratch = new byte[length];
        System.arraycopy(pcm, 0, this.scratch, 0, length);

        int free0 = l.available();
        checkSeam(length);

        float g = this.muted ? 0f : this.gain * this.masterGain;
        if (g != 1.0f) {
            applyGain(this.scratch, length, g);
        }
        if (this.writtenFrames > this.sampleRate && free0 >= l.getBufferSize() - length - 2048) {
            this.underruns++;
            // 设备已经播出去一段静音：下一块的起跳沿必须削掉，否则就是"咔"的一下
            this.forceDeclick = true;
        }
        int off = 0;
        while (off < length) {
            int n = l.write(this.scratch, off, length - off);
            if (n <= 0) break;
            off += n;
        }
        if (off < length && this.writtenFrames > this.sampleRate) {
            this.shortWrites++;
            if (System.currentTimeMillis() - this.lastShortLogAt > 1000L) {
                this.lastShortLogAt = System.currentTimeMillis();
                dev.mediafix.CineDuct.LOGGER.warn("[CineDuct] 声卡只接受了 {} / {} 字节（累计 {} 次）—— 这一段音频会被截断",
                        off, length, this.shortWrites);
            }
        }
        this.lastAccepted = off;
        return off;
    }

    /**
     * 兜底去咔：给这一块的开头施加约 1ms 的余弦淡入，把不连续处的起跳沿削掉。
     *
     * <p><b>只对检测到断点的块施加</b>。如果对每一块都做淡入淡出，21ms 一块就会变成
     * 47Hz 的颤音 —— 那是比爆音更糟的问题。资料里说的"2~5ms 淡入淡出"针对的是真正的
     * 切换点（换码率、换音轨、缓冲区重建），而不是连续播放的每一个块。
     */
    private void declick(int length) {
        int ch = Math.max(1, this.channels);
        // 只在真正的接缝上做，所以可以给足 3ms（1ms 对满幅跳变还是能听见"噗"）
        int frames = Math.min(length / (2 * ch), Math.max(1, this.sampleRate * 3 / 1000));
        if (frames <= 0) return;
        for (int i = 0; i < frames; i++) {
            double g = 0.5 - 0.5 * Math.cos(Math.PI * (i + 1) / (double) frames);
            for (int c = 0; c < ch; c++) {
                int idx = (i * ch + c) * 2;
                if (idx + 1 >= length) break;
                short v = (short) ((this.scratch[idx] & 0xFF) | (this.scratch[idx + 1] << 8));
                int out = (int) (v * g);
                this.scratch[idx] = (byte) (out & 0xFF);
                this.scratch[idx + 1] = (byte) ((out >> 8) & 0xFF);
            }
        }
        this.declicks++;
    }

    /** 对 S16LE 采样逐点乘增益（带钳位）。96000 采样/秒的量级，开销可忽略。 */
    private static void applyGain(byte[] data, int length, float gain) {
        for (int i = 0; i + 1 < length; i += 2) {
            int v = (short) ((data[i] & 0xFF) | (data[i + 1] << 8));
            int out = (int) (v * gain);
            if (out > 32767) out = 32767;
            else if (out < -32768) out = -32768;
            data[i] = (byte) (out & 0xFF);
            data[i + 1] = (byte) ((out >> 8) & 0xFF);
        }
    }

    /** 引擎时钟在写入后调用，用于统计"已写入"进度。 */
    public void noteWritten(int bytes) {
        if (this.channels <= 0) return;
        this.writtenFrames += bytes / (2L * this.channels);
    }

    public void setGain(float gain) {
        this.gain = Math.max(0f, gain);
    }

    public float gain() {
        return this.gain;
    }

    public void setMasterGain(float masterGain) {
        this.masterGain = Math.max(0f, Math.min(1f, masterGain));
    }

    public void setMuted(boolean muted) {
        this.muted = muted;
    }

    public boolean isMuted() {
        return this.muted;
    }

    public void setPaused(boolean paused) {
        SourceDataLine l = this.line;
        if (l == null || this.paused == paused) return;
        this.paused = paused;
        try {
            if (paused) {
                l.stop();
            } else {
                l.start();
                this.forceDeclick = true;   // 线路 stop/start 之后同样是一处接缝
            }
        } catch (Throwable ignored) {
        }
    }

    /** 丢弃尚未播放的缓冲（seek 时用，避免残留声音继续播放）。 */
    /**
     * 告诉 sink："下一块与上一块不连续是预期的"（快进/跳转造成），不要计入爆音统计。
     * 只是清掉上一块尾样本记录，不做任何缓冲操作。
     */
    public void expectDiscontinuity() {
        this.expectJump = true;
        this.forceDeclick = true;      // 快进后的第一块必须淡入：新旧位置毫无关系
    }

    public void flush() {
        SourceDataLine l = this.line;
        if (l == null) return;
        try {
            l.flush();
            this.writtenFrames = l.getLongFramePosition();
            // 丢弃缓冲 = 波形在中途被硬切，下一块是全新相位：必须淡入
            this.forceDeclick = true;
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void close() {
        SourceDataLine l = this.line;
        this.line = null;
        if (l != null) {
            try {
                l.stop();
                l.flush();
                l.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 列出可用混音器的声道能力（诊断用）。 */
    public static List<String> describeDevices() {
        List<String> out = new ArrayList<>();
        try {
            for (var info : AudioSystem.getMixerInfo()) {
                out.add(info.getName() + " / " + info.getDescription());
            }
        } catch (Throwable ignored) {
        }
        return out;
    }
}

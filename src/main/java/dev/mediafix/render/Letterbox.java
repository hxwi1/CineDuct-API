package dev.mediafix.render;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * 信箱化(黑边)帧上传：把视频帧居中拷入一块与**屏幕自身比例**等比的黑色画布，再上传 ——
 * 画面比例永远正确、黑边为真实黑色像素、零拉伸。
 *
 * <p>画布缓冲按尺寸缓存复用；黑边只在分配/视频分辨率变化时重涂一次，常态每帧仅一次行拷贝。
 * 目标比例由调用方显式传入（屏幕实体自己的宽高比）—— 以前是 waterframes 的 Display 每帧
 * 通过 Mixin 写进一个全局状态，前置 mod 去掉后改成参数传递，反而更清楚。
 */
public final class Letterbox {

    private static final int MAX_CANVAS = 4;
    private static final int MAX_SIDE = 8192;
    /**
     * 单块画布的最大像素数。4K(3840x2160) = 829 万，1:1 的屏幕格要 1475 万 ——
     * 取 1600 万，保证 4K 视频在非 16:9 的屏幕格上也能加黑边（原先是 838 万，4K 上会退化成拉伸）。
     */
    private static final long MAX_CANVAS_PIXELS = 16L * 1024 * 1024;

    private Letterbox() {
    }

    /**
     * 信箱化准备（唯一的帧处理入口）：
     * 把视频帧居中拷进一块"与屏幕比例等比"的黑色画布，返回画布；不需要处理时返回 null（原样上传）。
     *
     * @param targetAspect 屏幕实体自身的宽高比（宽/高）；&lt;=0.05 或非有限值表示不处理
     */
    public static Canvas prepare(ByteBuffer data, int w, int h, float targetAspect) {
        float aspect = targetAspect;
        if (aspect <= 0.05f || !Float.isFinite(aspect) || w <= 0 || h <= 0
                || w > MAX_SIDE || h > MAX_SIDE) {
            return null;
        }
        float videoAspect = (float) w / (float) h;
        if (Math.abs(videoAspect - aspect) <= aspect * 0.01f) {
            return null;   // 比例已经一致，不必加黑边
        }

        // 等比画布：包住视频且比例等于屏幕比例
        int cw, ch;
        if (videoAspect > aspect) {
            cw = w;
            ch = Math.max(h, Math.round(w / aspect));
        } else {
            ch = h;
            cw = Math.max(w, Math.round(h * aspect));
        }
        /*
         * 画布尺寸对齐到偶数。
         *
         * 为什么：视频解码器/子进程那边对偶数尺寸更友好（NV12 是 2x2 子采样，奇数尺寸要额外处理），
         * 而且**零拷贝那条路（子进程的 --fit-aspect）本来就对齐到偶数** —— 两边算出来的框尺寸不一致时，
         * 同一个显示面在两条传输方式下会差 1 像素（实测 708 vs 709）。这里对齐一下，两条路完全一致。
         */
        cw = Math.max(2, cw & ~1);
        ch = Math.max(2, ch & ~1);
        if (cw > MAX_SIDE || ch > MAX_SIDE || (long) cw * ch > MAX_CANVAS_PIXELS) {
            return null;
        }

        Canvas c = CANVASES.get(key(cw, ch));
        if (c == null) {
            if (CANVASES.size() >= MAX_CANVAS) {
                CANVASES.clear();
            }
            c = new Canvas(cw, ch);
            CANVASES.put(key(cw, ch), c);
        }
        c.paintedNow = false;
        if (c.lastVw != w || c.lastVh != h) {
            c.paintBlack(); // 视频尺寸变了，黑边里可能有旧画面残留
            c.lastVw = w;
            c.lastVh = h;
            c.paintedNow = true;
        }

        // 逐行居中拷贝（4字节/像素）
        int srcPos = data.position();
        int srcLimit = data.limit();
        int srcStride = w * 4;
        int dstStride = cw * 4;
        int barTop = (ch - h) / 2;
        int barLeft = (cw - w) / 2;
        int dstBase = (barTop * cw + barLeft) * 4;
        // 防御：源缓冲装不下整帧（重连/Display 重建瞬间出现截断缓冲）时原样透传，
        // 任何一帧异常都不许把上传路径抛异常卡死。
        long srcBytes = (long) srcLimit - srcPos;
        if (srcStride <= 0 || srcBytes < (long) h * srcStride
                || (long) dstBase + (long) (h - 1) * dstStride + (long) srcStride
                        > (long) c.buf.capacity()) {
            return null;
        }
        try {
            for (int row = 0; row < h; row++) {
                data.position(row * srcStride).limit(row * srcStride + srcStride);
                c.buf.position(dstBase + row * dstStride).limit(dstBase + row * dstStride + srcStride);
                c.buf.put(data);
            }
        } catch (RuntimeException ex) {
            data.position(srcPos).limit(srcLimit);
            return null;
        }
        data.position(srcPos).limit(srcLimit);
        c.buf.clear();
        return c;
    }

    /** 画布缓存：尺寸 -> 画布（渲染线程独占访问）。 */
    private static final Map<Long, Canvas> CANVASES = new HashMap<>();

    private static long key(int w, int h) {
        return ((long) w << 32) | (h & 0xFFFFFFFFL);
    }

    /** 画布：复用的直接缓冲 + 上次视频尺寸（变化时需重涂黑边并整幅上传）。 */
    public static final class Canvas {
        public final ByteBuffer buf;
        public final int w;
        public final int h;
        int lastVw = -1;
        int lastVh = -1;
        /** 本帧是否重新涂过黑边（需要整幅上传）。 */
        boolean paintedNow;

        Canvas(int w, int h) {
            this.w = w;
            this.h = h;
            this.buf = ByteBuffer.allocateDirect(w * h * 4);
            paintBlack();
        }

        /** 整幅涂不透明黑（RGB=0, A=255）。 */
        private void paintBlack() {
            byte[] black = {0, 0, 0, -1};
            byte[] row = new byte[w * 4];
            for (int x = 0; x < w; x++) {
                System.arraycopy(black, 0, row, x * 4, 4);
            }
            buf.clear();
            for (int y = 0; y < h; y++) {
                buf.put(row);
            }
            buf.clear();
        }
    }
}

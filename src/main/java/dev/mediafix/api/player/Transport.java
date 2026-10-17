package dev.mediafix.api.player;

/**
 * 帧传输方式偏好（{@link PlayerOptions.Builder#transport}）。
 *
 * <p>CineDuct 有两条出帧路径，默认自己挑（{@link #AUTO}）：
 * <ul>
 *   <li><b>零拷贝</b>：C++ 子进程解码 → D3D11 共享纹理 → GL 互操作。省一次 CPU 拷贝，
 *       音频走 WASAPI 直出（能直通杜比/DTS 给功放）。要求子进程和游戏在**同一块显卡**上。</li>
 *   <li><b>进程内</b>：Java 进程里的 FFmpeg 引擎，帧走 CPU → 上传 GL 纹理。兜底路径，
 *       没有显卡要求、也没有子进程，但多一次 CPU 往返。</li>
 * </ul>
 */
public enum Transport {

    /** 自动（默认）：能零拷贝就零拷贝，探不到 GL 适配器/配置关掉时用进程内引擎。 */
    AUTO,

    /**
     * 强制零拷贝（子进程）。
     *
     * <p>注意：零拷贝的前提是"子进程与游戏在同一块卡"，这个前提只能靠渲染线程探测得到。
     * 所以探不到时（或配置里关掉了 {@code gpuHostEnabled}）**仍然会退回进程内引擎**并在日志里说明 ——
     * 这不是"忽略你的要求"，而是那条路本来就跑不起来。
     */
    ZERO_COPY,

    /**
     * 强制进程内引擎（不起子进程）。
     *
     * <p>用途：排障（怀疑零拷贝在某驱动上出问题）、或者你就想让画面经过 Java 侧的
     * 信箱化/上传路径（例如某些环境里 GL 互操作不稳）。
     */
    IN_PROCESS;

    /** 是否要求"不要子进程"。 */
    public boolean forcesInProcess() {
        return this == IN_PROCESS;
    }

    /** 是否要求"尽量用子进程"。 */
    public boolean prefersZeroCopy() {
        return this == ZERO_COPY;
    }
}

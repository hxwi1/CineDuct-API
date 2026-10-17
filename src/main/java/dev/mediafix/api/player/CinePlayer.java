package dev.mediafix.api.player;

import dev.mediafix.api.PlaybackState;

/**
 * 一个 <b>独立播放器</b>：不挂在任何屏幕实体上的播放会话句柄。
 *
 * <p>屏幕实体（{@code cineduct:screen}）那套 API 解决的是"世界里有一块屏幕，服务端是权威"的场景；
 * 但有些附属 mod 自带渲染（例如把画面贴到自己的方块/实体/自绘平面上），它们要的只是
 * "给我一个正在解码的会话，我每帧来取一张能采样的纹理" —— 这个接口就是给那种需求用的。
 *
 * <h2>典型用法（渲染线程）</h2>
 * <pre>{@code
 * CinePlayer player = CinePlayerApi.get().open("https://www.bilibili.com/video/BV1xx411c7mD",
 *         PlayerOptions.builder().volume(80).loop(true).build());
 *
 * // 每帧：
 * int tex = player.acquireDrawTexture(myTextureId, false, player.drawAspect());
 * if (tex > 0) {
 *     // 画你的四边形（UV 0~1），纹理就是 tex
 * }
 * player.releaseDrawTexture();
 * }</pre>
 *
 * <h2>线程</h2>
 * <ul>
 *   <li>{@code open} / 状态读取 / 控制方法：<b>任意线程</b>可调，内部不阻塞；
 *       解析链接（可能发网络请求）由实现放在后台线程做。</li>
 *   <li>{@link #acquireDrawTexture} / {@link #releaseDrawTexture}：<b>必须在渲染线程</b>
 *       （GL 上下文当前）调用，且必须成对 —— 取到帧后画完立刻 release。
 *       零拷贝模式下那是"解锁互操作对象"，不解锁会影响下一帧。</li>
 * </ul>
 *
 * <h2>生命周期</h2>
 * <p>{@link #close()} 释放一次引用：同一链接 + 同一音频形态的多个播放器共用一条会话，
 * 只有最后一个 close 才会真正关掉解码与声卡。
 */
public interface CinePlayer extends AutoCloseable {

    // =====================================================================
    // 身份
    // =====================================================================

    /** 本播放器的 id（每次 {@code open} 唯一，与内部会话 id 不是一回事）。 */
    long id();

    /** 原始播放源链接（就是 open 时传进来的那个）。 */
    String sourceUri();

    /** 是否还活着（close 之后为 false）。 */
    boolean isAlive();

    // =====================================================================
    // 状态（渲染/主线程读，不阻塞、不抛异常）
    // =====================================================================

    /** 当前状态。解析/起播期间是 {@link PlaybackState#LOADING}。 */
    PlaybackState state();

    /** 是否已经有画面可以出（还没起播时 false，调用方该画"加载中"）。 */
    boolean isReady();

    /** 是否在解析 / 起播 / 缓冲（等价于"现在还没有连续画面"）。 */
    boolean isLoading();

    /** 是否卡在缓冲（音视频一起停住，不是暂停）。 */
    boolean isBuffering();

    /** 是否出错了（{@link #lastError()} 里有原因）。 */
    boolean isBroken();

    /** 是否直播（直播时 {@link #seek} 无效、{@link #durationMs()} 恒为 -1）。 */
    boolean isLive();

    /** 是否支持 seek（直播 / 时长未知时 false）。 */
    boolean isSeekable();

    /** 播放位置（毫秒）。直播时是"已经播了多久"。 */
    long positionMs();

    /** 时长（毫秒）；未知或直播时 -1。 */
    long durationMs();

    /** 视频宽（像素）；未知时 0。 */
    int videoWidth();

    /** 视频高（像素）；未知时 0。 */
    int videoHeight();

    /** 实际在用的帧传输方式：{@code dxgi-interop}（零拷贝子进程）/ {@code java}（进程内引擎）。 */
    String transport();

    /** 最近一次错误（正常时是空串）。 */
    String lastError();

    // =====================================================================
    // 出帧（渲染线程）
    // =====================================================================

    /**
     * 取"这一帧要画的 GL 纹理"。
     *
     * <p>两种传输方式在实现内部统一了：
     * <ul>
     *   <li>零拷贝：返回子进程共享纹理经 DXGI→WGL 导入的 <b>GL 纹理 id</b>（它不在 MC 的纹理管理器里）；
     *       这种情况下 {@code fallbackTextureId} 用不到，但调用方仍然需要自己备一张纹理给兜底路径用。</li>
     *   <li>进程内引擎：把引擎那一帧上传到 {@code fallbackTextureId} 再返回它。</li>
     * </ul>
     *
     * @param fallbackTextureId 进程内引擎上传的目标纹理（必须是一张真实可写的 GL 纹理 id）
     * @param forceAllocate     是否强制重新分配纹理存储（第一帧、或换源后为 true）
     * @param targetAspect      调用方希望的画面宽高比（{@code <= 0} = 不关心，
     *                          实现可以在进程内路径上按它做信箱化）
     * @return GL 纹理 id；{@code <= 0} 表示这一帧没有可画的东西
     */
    int acquireDrawTexture(int fallbackTextureId, boolean forceAllocate, float targetAspect);

    /** 画完归还（零拷贝模式下是解锁互操作对象）。必须在渲染线程调用，且与 acquire 成对。 */
    void releaseDrawTexture();

    /**
     * 最近一次 {@link #acquireDrawTexture} 返回的纹理<b>自身</b>代表的宽高比。
     *
     * <p>零拷贝模式下是视频自身的比例（调用方要按它加黑边）；
     * 进程内引擎模式下如果按 {@code targetAspect} 做了信箱化，这里就是目标的那个比例。
     * 未知时返回 0。
     */
    float drawAspect();

    // =====================================================================
    // 信箱化（"视频比例 ≠ 显示面比例"时的黑边）
    // =====================================================================

    /**
     * 显示面宽高比（宽/高）；{@code 0} = 不做信箱化。
     *
     * <p>由 {@link PlayerOptions.Builder#displayAspect(float)} 或 {@link #setDisplayAspect(float)} 设定。
     */
    float displayAspect();

    /**
     * 设置显示面宽高比（宽/高），例如你的显示面是 16:9 就传 {@code 16f/9f}。
     *
     * <p><b>开启后黑边由 CineDuct 烘焙进纹理</b>：画面等比放进"比例 = 本值"的输出框，
     * 框外是真黑边（不是几何留空）。于是：
     * <ul>
     *   <li>调用方把整张纹理铺满自己的显示面 —— **不会拉伸**，也不需要自己算黑边；</li>
     *   <li>{@link #drawAspect()} 会返回"这张纹理自己的比例"（= 本值），
     *       而不是视频原始比例（想看原始比例请自己从 {@link #videoWidth()}/{@link #videoHeight()} 算）。</li>
     * </ul>
     *
     * <p><b>生效时机</b>：进程内引擎下一帧生效；零拷贝路径的输出尺寸是开子进程时定的，
     * 所以会**自动重开一次子进程**（画面闪一下、位置自动续播）。传 {@code <= 0} 关掉信条化。
     *
     * <p>任意线程可调；同一比例重复设置是无操作。
     */
    void setDisplayAspect(float aspect);

    // =====================================================================
    // 控制（任意线程）
    // =====================================================================

    /** 暂停 / 继续。 */
    void pause(boolean paused);

    /** 期望的暂停状态（刚推过去还没确认的也算）。 */
    boolean isPaused();

    /** 跳转到指定位置（毫秒）；直播无效。 */
    void seek(long positionMs);

    /** 音量 0~100（这是引擎自己的音量，不含调用方那边的距离衰减/主音量）。 */
    void setVolumePercent(int percent);

    int volumePercent();

    void setMuted(boolean muted);

    boolean isMuted();

    /** 循环（点播有效）。 */
    void setRepeat(boolean repeat);

    boolean isRepeat();

    /**
     * 音量是否由接收端（功放）控制 —— IEC 61937 直通时为 true，这时音量/静音命令无效，
     * 调用方可以据此跳过每帧推音量。
     */
    boolean volumeLocked();

    // =====================================================================
    // 诊断 / 修复
    // =====================================================================

    /**
     * 本条播放的运行统计（出帧/丢帧/等锁/子进程时长/分辨率/错误…）。
     *
     * <p>任意线程可调，不阻塞、不抛异常。走进程内引擎时帧计数恒为 0（见 {@link PlayerStatistics}）。
     */
    PlayerStatistics statistics();

    /**
     * 重建这条播放：丢掉全部缓冲重新开始（零拷贝下就是换一个新子进程），**播放位置尽量保留**。
     *
     * <p>给"画面卡住 / 断了 / 状态可疑时按一下刷新"用，等价于屏幕那边的 {@code /cineduct refresh full}。
     * 与"关掉再 open 一个"的区别：
     * <ul>
     *   <li>保留会话身份（{@code Sessions} 里的 id），同源共享引用计数不受影响 ——
     *       所以**同源多块屏时刷新真的会生效**（关掉再开会被复用接回原会话，等于空转）；</li>
     *   <li>零拷贝的互操作纹理由渲染线程自动重导（不用调用方做任何事）。</li>
     * </ul>
     *
     * <p>任意线程可调；画面会闪一下（换进程/丢缓冲），随后从原位置续播。
     */
    void rebuild();

    // =====================================================================
    // 生命周期
    // =====================================================================

    /** 释放一次引用（见类注释）。重复调用无副作用。 */
    @Override
    void close();
}

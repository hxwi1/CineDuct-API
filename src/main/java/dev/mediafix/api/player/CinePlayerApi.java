package dev.mediafix.api.player;

/**
 * 独立播放器门面：给"自带渲染的附属 mod"用（不需要在世界里创建屏幕实体）。
 *
 * <pre>{@code
 * if (CinePlayerApi.available()) {
 *     CinePlayer player = CinePlayerApi.get().open(uri, PlayerOptions.defaults());
 *     // 每帧：player.acquireDrawTexture(...) → 画 → player.releaseDrawTexture()
 * }
 * }</pre>
 *
 * <p>兼容承诺与 {@code CineDuctApi} 一致：{@link #API_VERSION} 变更即不兼容。
 * 本接口只依赖 {@link CinePlayer} / {@link PlayerOptions}，三者一起构成"独立播放器"这一层 API。
 */
public interface CinePlayerApi {

    /** API 版本；不兼容改动时 +1。 */
    int API_VERSION = 1;

    /** 取门面；CineDuct 没加载（或不在客户端）时抛异常。 */
    static CinePlayerApi get() {
        CinePlayerApi api = Holder.INSTANCE;
        if (api == null) {
            throw new IllegalStateException("CineDuct 独立播放器 API 不可用（未加载，或运行在服务端）");
        }
        return api;
    }

    /** 是否可用（不想因缺少 CineDuct 而崩的附属 mod 可以先问这个）。 */
    static boolean available() {
        return Holder.INSTANCE != null;
    }

    /** 由 CineDuct 自己在客户端初始化时调用（附属 mod 不要调）。 */
    static void install(CinePlayerApi api) {
        Holder.INSTANCE = api;
    }

    // =====================================================================

    /**
     * 打开一个独立播放器。
     *
     * <p><b>非阻塞</b>：解析链接（可能要发网络请求，B 站直播还要换链）在后台线程进行，
     * 返回的句柄立刻可用，此时 {@link CinePlayer#state()} 一般是 {@code LOADING}。
     *
     * <p><b>复用</b>：同一个"解析结果 + 音频形态"的多个播放器共用一条会话（只解一份、
     * 只占一份音频设备），各自 close 一次，最后一个才真正关闭。
     *
     * @param sourceUri 原始播放源链接（B 站视频/番剧/直播/直链/本地文件 URI 都行）
     * @param options   参数包（音量/循环/暂停/起播位置/音频形态/清晰度上限），不能为 null
     * @return 播放器句柄；链接无法解析时不会抛异常，而是返回一个状态为 {@code ERROR} 的句柄
     *         （{@link CinePlayer#lastError()} 里有原因）
     */
    CinePlayer open(String sourceUri, PlayerOptions options);

    /** 同 {@link #open(String, PlayerOptions)}，全默认参数。 */
    default CinePlayer open(String sourceUri) {
        return open(sourceUri, PlayerOptions.defaults());
    }

    /** 当前打开的独立播放器数量（诊断用）。 */
    int openPlayerCount();

    /**
     * 当前所有还活着的独立播放器（诊断面板 / 批量管理用）。
     *
     * <p>返回的是**快照**：之后有 open/close 也不会影响这个列表本身。
     */
    java.util.List<CinePlayer> players();

    /** 按 id 取一个播放器；不存在（或已关闭）返回 empty。 */
    java.util.Optional<CinePlayer> player(long id);

    /**
     * 这个链接值不值得当视频处理（**只看链接本身，不发任何网络请求**）。
     *
     * <p>用途：附属 mod 在建屏/建会话**之前**就能判断该不该把某个链接交给 CineDuct ——
     * 例如"方块上贴 URL"的场景，链接可能是图片、网页或别的 mod 的资源，
     * 先问一句就不会为一张图片起一条解码会话。
     *
     * <p>判定顺序：
     * <ol>
     *   <li>解析成 URI（失败 = false）；</li>
     *   <li>问每个已注册的解析器 {@code canHandle(uri)}（内置 B 站解析器也在其中：它只看 host，不发请求）；</li>
     *   <li>都不是的话看链接本身像不像媒体：{@code http/https/file} 且扩展名是常见容器/流
     *       （mp4/mkv/webm/mov/flv/m3u8/ts/mp3/m4a/flac/wav/ogg…）。</li>
     * </ol>
     *
     * @return true = 大概率能播；false = 别当视频处理（也可能是"我不认识"，不保证一定播不了）
     */
    boolean canHandle(String sourceUri);

    /** 一句话诊断（传输方式、会话数、适配器），给日志/调试界面用。 */
    String diagnostics();

    /** 内部持有者。 */
    final class Holder {
        static volatile CinePlayerApi INSTANCE;

        private Holder() {
        }
    }
}

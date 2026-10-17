package dev.mediafix.source;

import dev.mediafix.CineDuct;
import dev.mediafix.api.MediaSourceResolver;
import dev.mediafix.api.ResolvedStream;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 解析器注册表：按顺序问每个解析器"你能处理吗"，第一个说能的就是它。
 *
 * <p>内置的 B 站解析器也走这里注册（见静态块），所以附属 mod 的解析器和它是完全平等的。
 * 注册是追加式的：想抢在 B 站解析器之前处理某个链接（比如自定义短链），
 * 用 {@link #registerFirst(MediaSourceResolver)}。
 *
 * <p>全部解析器都不认、或者都失败时，回退成
 * {@link ResolvedStream#direct(URI)}（把原链接当直链），保证玩家总能看到明确结果。
 */
public final class SourceResolvers {

    private static final List<MediaSourceResolver> RESOLVERS = new CopyOnWriteArrayList<>();

    static {
        register(new BiliSourceResolver());
    }

    private SourceResolvers() {
    }

    /** 追加一个解析器（排在最后）。 */
    public static void register(MediaSourceResolver resolver) {
        if (resolver != null) RESOLVERS.add(resolver);
    }

    /** 插到最前面（优先级最高，用于覆盖内置解析器）。 */
    public static void registerFirst(MediaSourceResolver resolver) {
        if (resolver != null) RESOLVERS.add(0, resolver);
    }

    public static List<MediaSourceResolver> all() {
        return List.copyOf(RESOLVERS);
    }

    /**
     * 解析一个链接。
     *
     * <p>可能发网络请求 —— 必须在后台线程调用。
     *
     * @return 永远非 null：失败时返回"把它当直链"的结果
     */
    public static ResolvedStream resolve(URI uri) {
        if (uri == null) return null;
        for (MediaSourceResolver r : RESOLVERS) {
            try {
                if (!r.canHandle(uri)) continue;
            } catch (Throwable t) {
                CineDuct.LOGGER.warn("[CineDuct] 解析器 {} 的 canHandle 抛异常，跳过", r.name(), t);
                continue;
            }
            try {
                ResolvedStream out = r.resolve(uri);
                if (out != null) {
                    CineDuct.LOGGER.info("[CineDuct] 由 {} 解析成功: video={} audio={} live={} quality={}",
                            r.name(), CineDuct.LOGGER.url(out.video()), CineDuct.LOGGER.url(out.audio()),
                            out.live(), out.qualityLabel());
                    return out;
                }
                CineDuct.LOGGER.info("[CineDuct] {} 处理 {} 未得到可用流", r.name(), CineDuct.LOGGER.url(uri));
            } catch (Throwable t) {
                CineDuct.LOGGER.warn("[CineDuct] {} 解析 {} 异常", r.name(), CineDuct.LOGGER.url(uri), t);
            }
        }
        // 谁都不认：当直链试一把（本地文件、其它站的直链都走这里）
        return ResolvedStream.direct(uri);
    }
}

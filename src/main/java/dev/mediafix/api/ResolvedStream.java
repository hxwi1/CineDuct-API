package dev.mediafix.api;

import java.net.URI;

/**
 * 一次「链接 → 可播放直链」的解析结果。
 *
 * <p>这是引擎与解析器之间唯一的交换格式，也是附属 mod 通过
 * {@link MediaSourceResolver} 参与解析时必须返回的东西。
 *
 * <p><b>两条链</b>：B 站这类站点的音视频是分开的 DASH 直链，所以 {@link #video()} 与
 * {@link #audio()} 各自独立；而直播（FLV）两者复用同一根时间轴，此时两条链**指向同一个 URI**
 * —— 引擎本来就允许两条链同源，音频源会自己去挑音轨。
 *
 * @param video        视频直链；纯音频时为 null
 * @param audio        音频直链；无独立音轨时为 null（引擎会退回"从视频链里挑音轨"）
 * @param live         是否直播（直播会忽略一切 seek、不参与 ABR、断流自动重连、用系统时间锚定时间轴）
 * @param title        展示名（可为 null）
 * @param qualityLabel 选中档位的可读名，如 {@code 1080P60}（可为 null）
 * @param durationMs   时长（毫秒）；未知为 -1（直播恒为 -1）
 */
public record ResolvedStream(URI video, URI audio, boolean live, String title, String qualityLabel,
                             long durationMs) {

    public ResolvedStream {
        if (video == null && audio == null) {
            throw new IllegalArgumentException("ResolvedStream 至少要有一条链");
        }
    }

    /** 点播：音视频两条独立链。 */
    public static ResolvedStream of(URI video, URI audio) {
        return new ResolvedStream(video, audio, false, null, null, -1L);
    }

    /** 直播：音视频同一条链。 */
    public static ResolvedStream live(URI url, String title) {
        return new ResolvedStream(url, url, true, title, null, -1L);
    }

    /** 普通直链（非 B 站、或解析失败时的直连尝试）：两条链都用同一个 URI。 */
    public static ResolvedStream direct(URI uri) {
        return new ResolvedStream(uri, uri, false, null, null, -1L);
    }

    /** 播放器最该打开的那条链（优先视频）。 */
    public URI mainUri() {
        return video != null ? video : audio;
    }

    public ResolvedStream withTitle(String newTitle) {
        return new ResolvedStream(video, audio, live, newTitle, qualityLabel, durationMs);
    }

    public ResolvedStream withQuality(String label) {
        return new ResolvedStream(video, audio, live, title, label, durationMs);
    }
}

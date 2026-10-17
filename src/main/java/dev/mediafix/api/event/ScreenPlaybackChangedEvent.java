package dev.mediafix.api.event;

import dev.mediafix.api.ScreenHandle;
import dev.mediafix.api.ScreenInfo;
import net.minecraft.server.level.ServerLevel;

/**
 * 屏幕的播放参数/状态被改动。
 *
 * <p>不管是走指令、走 {@link ScreenHandle}，还是别的 mod 直接改实体，都会发这个事件 ——
 * 因为事件是在实体 setter 里发的（服务端权威值真正变化时）。
 *
 * <p>注意这里反映的是**服务端权威值**；客户端实际播到哪、用的哪种传输方式，
 * 用 {@code dev.mediafix.api.client.CineDuctClientApi} 查。
 */
public final class ScreenPlaybackChangedEvent extends ScreenEvent {

    /** 改了什么。 */
    public enum Change {
        /** 暂停/继续。 */
        PAUSE,
        /** 跳转播放位置。 */
        SEEK,
        VOLUME,
        /** 音量距离衰减范围。 */
        RANGE,
        LOOP,
        MUTE,
        /** 屏幕尺寸。 */
        SIZE,
        /** 清晰度上限。 */
        QUALITY,
        /** 位置（中心点）。 */
        POSITION,
        /** 朝向（yaw/pitch）。 */
        ROTATION,
        /** 不透明度。 */
        ALPHA,
        /** 亮度倍率。 */
        BRIGHTNESS,
        /** 画面铺法（等比黑边 / 裁切铺满 / 拉伸）。 */
        FIT,
        /** 单面 / 双面可见。 */
        DOUBLE_SIDED,
        /** 每屏音频输出形态覆盖。 */
        AUDIO_MODE
    }

    private final Change change;
    private final String detail;

    public ScreenPlaybackChangedEvent(ServerLevel level, ScreenHandle screen, ScreenInfo info,
                                      Change change, String detail) {
        super(level, screen, info);
        this.change = change;
        this.detail = detail;
    }

    public Change change() {
        return this.change;
    }

    /** 人类可读的说明（写日志/提示用）。 */
    public String detail() {
        return this.detail;
    }
}

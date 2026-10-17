package dev.mediafix.player;

import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;

/**
 * Minecraft 主音量系数。
 *
 * <p>屏幕上的声音要跟着游戏的主音量条走，否则玩家把游戏静音了视频还在响。
 *
 * <p>（以前这里还读 waterframes 的 {@code DisplaysConfig.useMasterVolume()} 来判断是否需要
 * 二次乘算 —— 那是 waterframes 自己的配置项，前置 mod 去掉后整个判断都不需要了：
 * 距离衰减由我们自己算，主音量只乘这一次。）
 */
public final class MasterVolume {

    private MasterVolume() {
    }

    /** 主音量（0.0~1.0）；读不到时返回 1.0（不衰减）。 */
    public static float gain() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.options == null) return 1.0f;
            return mc.options.getSoundSourceVolume(SoundSource.MASTER);
        } catch (Throwable t) {
            return 1.0f;
        }
    }
}

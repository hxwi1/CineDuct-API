package dev.mediafix.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.mediafix.CineDuct;
import dev.mediafix.api.player.CinePlayer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 全局音量（**只影响本机玩家**）：所有播放器最终音量 = 屏幕音量 × 全局音量。
 *
 * <p>为什么需要它：服务端的每屏音量是"这块屏该多大声"（场景属性），而玩家自己可能正在听语音、
 * 或者在半夜——需要一个只属于自己、对**所有**屏一起生效的旋钮。它不经过任何网络，
 * 也不会回写到服务端：别人看不见你调了音量。
 *
 * <p>存 {@code <游戏目录>/cineduct-audio.json}，改完立刻生效并落盘。
 */
public final class GlobalVolume {

    /** 0~100；100 = 不衰减。 */
    private static volatile int percent = 100;

    private GlobalVolume() {
    }

    /** 读配置（启动时调一次）。坏文件不影响播放，退回 100%。 */
    public static void load() {
        final Path file = file();
        if (file == null || !Files.isRegularFile(file)) return;
        try {
            final Prefs prefs = new Gson().fromJson(Files.readString(file, StandardCharsets.UTF_8), Prefs.class);
            if (prefs != null) percent = clamp(prefs.volume);
        } catch (Exception e) {
            CineDuct.LOGGER.warn("[CineDuct] 读取 cineduct-audio.json 失败，全局音量按 100% 处理：{}", e.getMessage());
        }
    }

    public static int percent() {
        return percent;
    }

    /** 设置全局音量（0~100），返回生效值。 */
    public static int set(int value) {
        percent = clamp(value);
        save();
        return percent;
    }

    /**
     * 把全局音量叠加到某个播放器上。
     *
     * @param screenVolume 服务端给的屏幕音量；&lt;0 表示没指定
     */
    public static void apply(CinePlayer player, int screenVolume) {
        if (player == null) return;
        final int base = screenVolume < 0 ? 100 : screenVolume;
        player.setVolumePercent(clamp(Math.round(base * percent / 100.0f)));
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private static Path file() {
        try {
            final java.io.File dir = net.minecraft.client.Minecraft.getInstance().gameDirectory;
            return dir == null ? null : dir.toPath().resolve("cineduct-audio.json");
        } catch (Throwable t) {
            return null;   // 还没进游戏时拿不到目录，等下次写盘
        }
    }

    private static void save() {
        final Path file = file();
        if (file == null) return;
        try {
            Files.writeString(file, new GsonBuilder().setPrettyPrinting().create()
                    .toJson(new Prefs(percent)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            CineDuct.LOGGER.warn("[CineDuct] 保存全局音量失败：{}", e.getMessage());
        }
    }

    /** 落盘结构。 */
    private static final class Prefs {
        int volume = 100;

        Prefs(int volume) {
            this.volume = volume;
        }
    }
}

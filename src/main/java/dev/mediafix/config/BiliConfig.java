package dev.mediafix.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.mediafix.CineDuct;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * B 站相关配置（cineduct-bili.json）。
 * <p>cookie 留空时自动去读 {@code ~/.bilimedia/cookie.properties}（沿用原登录态）。
 */
public final class BiliConfig {

    /** 手填的登录 Cookie；留空则读 bilibili_media 的存储文件。 */
    public static String cookie = "";

    private static final Path CFG = FMLPaths.GAMEDIR.get().resolve("cineduct-bili.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private BiliConfig() {
    }

    public static void load() {
        try {
            if (Files.exists(CFG)) {
                Map<String, Object> map = GSON.fromJson(
                        new String(Files.readAllBytes(CFG), StandardCharsets.UTF_8),
                        new TypeToken<Map<String, Object>>() {}.getType());
                if (map != null && map.get("cookie") instanceof String s) cookie = s;
            }
        } catch (Exception e) {
            CineDuct.LOGGER.warn("[CineDuct] 读取 B 站配置失败", e);
        }
        save();
    }

    public static void save() {
        try {
            Map<String, Object> map = new HashMap<>();
            map.put("cookie", cookie);
            Files.write(CFG, GSON.toJson(map).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            CineDuct.LOGGER.warn("[CineDuct] 保存 B 站配置失败", e);
        }
    }
}

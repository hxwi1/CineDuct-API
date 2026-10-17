package dev.mediafix.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 日志配置：本模组的诊断日志默认写到游戏根目录的 {@code cineductlogs/}，
 * 避免高频诊断把 latest.log 刷得没法查别的问题。
 *
 * <p>事件与警告仍会同时写进 latest.log（{@link #mirrorToMain}），
 * 这样别人看主日志也知道本模组做了什么；需要细节时再翻独立日志。
 * 配置存于游戏目录 cineduct-log.json。
 */
public final class LogConfig {

    /** 日志子目录名（相对游戏根目录）。 */
    public static String dir = "cineductlogs";
    /** 保留最近多少个日志文件（每次启动一个文件）。 */
    public static int keepFiles = 10;
    /** 事件/警告是否同时写进 latest.log。 */
    public static boolean mirrorToMain = true;
    /** 高频诊断（引擎状态、每帧耗时、取帧、内存）是否写入独立日志。 */
    public static boolean diagEnabled = true;

    private static final Path CFG = FMLPaths.GAMEDIR.get().resolve("cineduct-log.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private LogConfig() {
    }

    public static void load() {
        try {
            if (!Files.exists(CFG)) {
                save();
                return;
            }
            String json = Files.readString(CFG, StandardCharsets.UTF_8);
            Map<String, Object> map = GSON.fromJson(json, new TypeToken<Map<String, Object>>() {
            }.getType());
            if (map == null) return;
            if (map.get("dir") instanceof String s && !s.isBlank()) dir = s;
            if (map.get("keepFiles") instanceof Number n) keepFiles = Math.max(1, n.intValue());
            if (map.get("mirrorToMain") instanceof Boolean b) mirrorToMain = b;
            if (map.get("diagEnabled") instanceof Boolean b) diagEnabled = b;
        } catch (Throwable ignored) {
            // 配置坏了也要能启动
        }
    }

    public static void save() {
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("dir", dir);
            map.put("keepFiles", keepFiles);
            map.put("mirrorToMain", mirrorToMain);
            map.put("diagEnabled", diagEnabled);
            Files.writeString(CFG, GSON.toJson(map), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
        }
    }
}

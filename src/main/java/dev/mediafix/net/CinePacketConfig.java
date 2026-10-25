package dev.mediafix.net;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.mediafix.CineDuct;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 服务端控制通道的配置（{@code cineduct-packet.json}，游戏目录根）。
 *
 * <p><b>默认全关</b>：一个客户端会连很多服务器，这条通道等于把"客户端播放"交给对方 ——
 * <p>★ 早先有 {@code enabled} 总开关和服务器白名单，**已取消**：模组默认对所有服务器生效。\n * 保留的是真正防滥用的三道：{@code token}、{@code disabledOps}、{@code allowedHosts} + 频率限制。
 * 语义与闸门顺序见 {@code docs/paper-control-payload.md} §5。
 */
public final class CinePacketConfig {

    /** 总开关。false 时所有包直接被拒（不解析、不记日志刷屏）。 */


    /**
     * 服务器白名单：留空 = 全拒。
     *
     * <p>匹配方式：拿客户端看到的服务器地址（{@code ip} 或 {@code host:port}）做**前缀/包含**匹配，
     * 所以写 {@code mc.example.com} 能匹配 {@code mc.example.com:25565}。
     * 单人游戏（集成服务器）的地址是 {@code singleplayer}。
     */


    /** 共享令牌：非空时报文必须带同样的 {@code token}。给"公开服但只有自家插件能控"用。 */
    public static String token = "";

    /** 禁用某些 op（例如租服场景不让服务端 {@code open}，只允许 pause/seek）。 */
    public static List<String> disabledOps = new ArrayList<>();

    /**
     * `open` 允许的 URL host（后缀匹配）。
     *
     * <p>防的是"恶意服让客户端去拉任意地址"。默认只放 B 站相关域名；
     * 用本地文件/自建源调试时把它临时改成空列表之外的值前请想清楚后果。
     */
    public static List<String> allowedHosts = new ArrayList<>(
            List.of("bilibili.com", "bilibili.tv", "b23.tv", "bilivideo.com", "hdslb.com"));

    /** 每个客户端每秒最多接受多少条报文（超了丢弃并回 {@code rate_limited}）。 */
    public static int maxPerSecond = 20;

    private static final Path CFG = FMLPaths.GAMEDIR.get().resolve("cineduct-packet.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private CinePacketConfig() {
    }

    public static void load() {
        try {
            if (Files.exists(CFG)) {
                final String json = Files.readString(CFG, StandardCharsets.UTF_8);
                final JsonElement root = GSON.fromJson(json, JsonElement.class);
                if (root != null && root.isJsonObject()) {
                    final JsonObject o = root.getAsJsonObject();
                    if (o.get("token") instanceof JsonElement e && e.isJsonPrimitive()) {
                        token = e.getAsString();
                    }
                    if (o.get("disabledOps") instanceof JsonArray a) {
                        disabledOps = strings(a);
                    }
                    if (o.get("allowedHosts") instanceof JsonArray a) {
                        allowedHosts = strings(a);
                    }
                    if (o.get("maxPerSecond") instanceof JsonElement e && e.isJsonPrimitive()) {
                        maxPerSecond = Math.max(1, Math.min(200, e.getAsInt()));
                    }
                }
            }
        } catch (Exception e) {
            CineDuct.LOGGER.warn("[CineDuct] 读取 cineduct-packet.json 失败（按默认值处理）", e);
        }
        save();
    }

    public static void save() {
        try {
            final JsonObject o = new JsonObject();
            o.addProperty("token", token);
            o.add("disabledOps", toArray(disabledOps));
            o.add("allowedHosts", toArray(allowedHosts));
            o.addProperty("maxPerSecond", maxPerSecond);
            Files.writeString(CFG, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Exception e) {
            CineDuct.LOGGER.warn("[CineDuct] 保存 cineduct-packet.json 失败", e);
        }
    }

    private static List<String> strings(JsonArray a) {
        final List<String> out = new ArrayList<>(a.size());
        for (JsonElement e : a) {
            if (e != null && e.isJsonPrimitive()) out.add(e.getAsString());
        }
        return out;
    }

    private static JsonArray toArray(List<String> values) {
        final JsonArray a = new JsonArray();
        for (String s : values) {
            if (s != null && !s.isBlank()) a.add(s);
        }
        return a;
    }
}

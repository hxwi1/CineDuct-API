package dev.mediafix.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 一条 {@code cineduct:control} 报文（协议版本 1，定义见 {@code docs/paper-control-payload.md}）。
 *
 * <p>解析是**严格**的：版本不认识、op 不认识、slot 不合法都直接抛 {@link Rejected}，
 * 由上层回一条错误（不猜、不"尽力而为"）—— 这条通道是外部输入，宁可拒绝。
 * 未知**字段**则一律忽略（向前兼容）。
 */
public final class CineControlPacket {

    /** 当前协议版本。 */
    public static final int PROTOCOL = 1;

    /** 认识的 op。{@code sync} 是对一批 slot 的 open/close 组合，不新增播放能力。 */
    public static final Set<String> OPS = Set.of(
            "open", "close", "pause", "seek", "volume", "mute", "repeat", "aspect", "rebuild", "sync", "list",
            // 虚拟屏：几何下发 / 进度锚点（见 docs/paper-control-payload.md §8）
            "place", "anchor");

    /** slot 名：1~32 个 {@code [A-Za-z0-9_.-]}。 */
    private static final Pattern SLOT = Pattern.compile("[A-Za-z0-9_.-]{1,32}");

    public final int v;
    public final String op;
    public final String slot;
    public final long seq;
    public final String token;

    // 各 op 的字段（不适用时为 null / -1）
    public final String url;
    public final Boolean paused;
    public final Boolean muted;
    public final Boolean repeat;
    public final Integer volume;
    public final Long positionMs;
    public final Float aspect;
    /** {@code anchor} 的服务端时刻（毫秒）：客户端只做参考，不拿它算延迟。 */
    public final Long serverTimeMs;
    /** {@code anchor} 是否直播（直播时客户端不做"按锚点外推"）。 */
    public final Boolean live;
    /** 原始报文（{@code place} 的四角几何直接从这里解析）。 */
    public final JsonObject raw;
    /** {@code sync} 携带的一组 slot 规格（每项等价于一条 open）。 */
    public final List<Entry> entries;

    private CineControlPacket(JsonObject o, String forcedOp, String forcedSlot) throws Rejected {
        this.v = intOr(o, "v", -1);
        if (this.v != PROTOCOL) {
            throw new Rejected(this.v, "unsupported v=" + this.v + "（本客户端只认 v=" + PROTOCOL + "）");
        }
        this.op = forcedOp != null ? forcedOp : strOr(o, "op", "");
        if (!OPS.contains(this.op)) {
            throw new Rejected(this.v, "unknown op=" + this.op);
        }
        final String rawSlot = forcedSlot != null ? forcedSlot : strOr(o, "slot", null);
        if (!"list".equals(this.op)) {
            if (rawSlot == null || !SLOT.matcher(rawSlot).matches()) {
                throw new Rejected(this.v, "bad slot（1~32 个 [A-Za-z0-9_.-]）");
            }
        }
        this.slot = rawSlot;
        this.seq = longOr(o, "seq", -1L);
        this.token = strOr(o, "token", "");
        this.url = strOr(o, "url", null);
        this.paused = boolOr(o, "paused");
        this.muted = boolOr(o, "muted");
        this.repeat = boolOr(o, "repeat");
        this.positionMs = longOrNull(o, "positionMs");
        this.volume = intOrNull(o, "volume") != null ? intOrNull(o, "volume")
                : intOrNull(o, "percent");
        this.aspect = floatOrNull(o, "aspect");
        this.entries = parseEntries(o);
        this.serverTimeMs = longOrNull(o, "serverTimeMs");
        this.live = boolOr(o, "live");
        this.raw = o;
    }

    /** {@code sync} 里的一项。 */
    public record Entry(String slot, JsonObject raw) {
    }

    /**
     * 解析一条报文。
     *
     * @throws Rejected 版本/op/slot 不合法（上层要回一条错误，并原样回显 {@code v}）
     */
    public static CineControlPacket parse(String json) throws Rejected {
        if (json == null || json.isBlank()) throw new Rejected(-1, "空报文");
        final JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (Exception e) {
            throw new Rejected(-1, "不是合法 JSON: " + e.getMessage());
        }
        if (root == null || !root.isJsonObject()) throw new Rejected(-1, "报文必须是 JSON 对象");
        return new CineControlPacket(root.getAsJsonObject(), null, null);
    }

    private List<Entry> parseEntries(JsonObject o) throws Rejected {
        if (!"sync".equals(this.op)) return List.of();
        final JsonElement e = o.get("slots");
        if (e == null || !e.isJsonArray()) throw new Rejected(this.v, "sync 需要 slots 数组");
        final JsonArray a = e.getAsJsonArray();
        final List<Entry> out = new ArrayList<>(a.size());
        for (JsonElement item : a) {
            if (item == null || !item.isJsonObject()) throw new Rejected(this.v, "slots 里必须是对象");
            final JsonObject spec = item.getAsJsonObject();
            final String name = strOr(spec, "slot", null);
            if (name == null || !SLOT.matcher(name).matches()) {
                throw new Rejected(this.v, "sync: bad slot");
            }
            out.add(new Entry(name, spec));
        }
        return out;
    }

    /** 把 {@code sync} 里的一项当成"open 这条 slot"重新解析（复用同一套字段与校验）。 */
    public CineControlPacket asOpen(Entry entry) throws Rejected {
        final JsonObject merged = entry.raw().deepCopy();
        merged.addProperty("v", PROTOCOL);
        merged.addProperty("op", "open");
        return new CineControlPacket(merged, "open", entry.slot());
    }

    // =====================================================================
    // 解析辅助（全部容忍缺省：缺省交给上层用"不改变现状"处理）
    // =====================================================================

    private static String strOr(JsonObject o, String key, String def) {
        final JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : def;
    }

    private static int intOr(JsonObject o, String key, int def) {
        final Integer v = intOrNull(o, key);
        return v == null ? def : v;
    }

    private static Integer intOrNull(JsonObject o, String key) {
        final JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) return null;
        try {
            return e.getAsInt();
        } catch (Exception ex) {
            return null;
        }
    }

    private static long longOr(JsonObject o, String key, long def) {
        final Long v = longOrNull(o, key);
        return v == null ? def : v;
    }

    private static Long longOrNull(JsonObject o, String key) {
        final JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) return null;
        try {
            return e.getAsLong();
        } catch (Exception ex) {
            return null;
        }
    }

    private static Float floatOrNull(JsonObject o, String key) {
        final JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) return null;
        try {
            return e.getAsFloat();
        } catch (Exception ex) {
            return null;
        }
    }

    private static Boolean boolOr(JsonObject o, String key) {
        final JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) return null;
        try {
            return e.getAsBoolean();
        } catch (Exception ex) {
            return null;
        }
    }

    /** 最小 JSON 字符串转义（只处理规范要求的几个字符，够拼上行报文用）。 */
    static String escapeJson(String text) {
        if (text == null) return "";
        final StringBuilder sb = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /** 报文被拒（版本/op/slot/JSON 不合法）。 */
    public static final class Rejected extends Exception {
        /** 报文自称的版本号（回显用；拿不到时为 -1）。 */
        public final int echoedVersion;

        public Rejected(int echoedVersion, String reason) {
            super(reason);
            this.echoedVersion = echoedVersion;
        }
    }
}

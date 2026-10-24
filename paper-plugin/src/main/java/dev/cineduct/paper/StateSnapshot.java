package dev.cineduct.paper;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端回执 {@code cineduct:state} 的解析结果（字段定义见
 * {@code docs/paper-control-payload.md} §6）。
 *
 * <p>为什么要在插件侧解析而不是把原始 JSON 丢进聊天框：服主真正想知道的是
 * "这条 slot 现在是什么状态、播到第几秒、有没有报错"，而不是一串花括号。
 *
 * <p>解析对**坏数据**必须宽容：回执来自客户端，版本可能对不上、可能半截 ——
 * 任何异常都退化成"没有可用信息"，绝不让它把指令线程炸掉。
 */
record StateSnapshot(int seq, long ack, List<Slot> slots, String err) {

    /** 一条 slot 的现场。 */
    record Slot(String slot, String state, long positionMs, long durationMs, boolean live,
                boolean buffering, String transport, int volume, boolean muted, String err) {

        /** 人类可读的一行："main  PLAYING  00:12/04:00  dxgi-interop  音量 80"。 */
        Component line() {
            final NamedTextColor color = switch (this.state) {
                case "PLAYING" -> NamedTextColor.GREEN;
                case "PAUSED", "LOADING" -> NamedTextColor.YELLOW;
                case "BUFFERING" -> NamedTextColor.GOLD;
                case "ENDED" -> NamedTextColor.GRAY;
                default -> NamedTextColor.RED;
            };
            Component line = Component.text(this.slot, NamedTextColor.AQUA)
                    .append(Component.text("  " + this.state, color))
                    .append(Component.text("  " + time(), NamedTextColor.GRAY))
                    .append(Component.text("  " + (this.transport == null || this.transport.isEmpty()
                            ? "?" : this.transport), NamedTextColor.DARK_GRAY))
                    .append(Component.text("  音量 " + this.volume + (this.muted ? "(静音)" : ""),
                            NamedTextColor.DARK_GRAY));
            if (this.err != null && !this.err.isEmpty()) {
                line = line.append(Component.text("  错误: " + this.err, NamedTextColor.RED));
            }
            return line;
        }

        private String time() {
            if (this.live) return "直播 " + mmss(this.positionMs);
            if (this.durationMs <= 0) return mmss(this.positionMs) + "/?";
            return mmss(this.positionMs) + "/" + mmss(this.durationMs);
        }

        private static String mmss(long ms) {
            if (ms < 0) return "?";
            final long total = ms / 1000;
            return String.format("%02d:%02d", total / 60, total % 60);
        }
    }

    static StateSnapshot empty() {
        return new StateSnapshot(-1, -1L, List.of(), "");
    }

    /** 解析；任何异常都返回 {@link #empty()}（调用方据此说"回执读不出来"）。 */
    static StateSnapshot parse(String json) {
        if (json == null || json.isBlank()) return empty();
        try {
            final JsonElement root = JsonParser.parseString(json);
            if (root == null || !root.isJsonObject()) return empty();
            final JsonObject o = root.getAsJsonObject();
            final int seq = intOf(o, "seq", -1);
            final long ack = longOf(o, "ack", -1L);
            final String err = strOf(o, "err", "");
            final List<Slot> slots = new ArrayList<>();
            if (o.get("slots") instanceof JsonArray array) {
                for (JsonElement item : array) {
                    if (item == null || !item.isJsonObject()) continue;
                    final JsonObject s = item.getAsJsonObject();
                    slots.add(new Slot(
                            strOf(s, "slot", "?"),
                            strOf(s, "state", "?"),
                            longOf(s, "positionMs", -1L),
                            longOf(s, "durationMs", -1L),
                            boolOf(s, "live", false),
                            boolOf(s, "buffering", false),
                            strOf(s, "transport", ""),
                            intOf(s, "volume", -1),
                            boolOf(s, "muted", false),
                            strOf(s, "err", "")));
                }
            }
            return new StateSnapshot(seq, ack, List.copyOf(slots), err);
        } catch (RuntimeException e) {
            return empty();
        }
    }

    // =====================================================================

    private static String strOf(JsonObject o, String key, String def) {
        final JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : def;
    }

    private static int intOf(JsonObject o, String key, int def) {
        final JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) return def;
        try {
            return e.getAsInt();
        } catch (RuntimeException ex) {
            return def;
        }
    }

    private static long longOf(JsonObject o, String key, long def) {
        final JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) return def;
        try {
            return e.getAsLong();
        } catch (RuntimeException ex) {
            return def;
        }
    }

    private static boolean boolOf(JsonObject o, String key, boolean def) {
        final JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) return def;
        try {
            return e.getAsBoolean();
        } catch (RuntimeException ex) {
            return def;
        }
    }
}

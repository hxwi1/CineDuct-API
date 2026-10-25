// SPDX-License-Identifier: MIT
// Copyright (c) 2026 hxwi1
package dev.cineduct.paper;

/**
 * 一条 {@code cineduct:control} 报文的构造器（协议 v1，见仓库根目录
 * {@code docs/paper-control-payload.md}）。
 *
 * <p>为什么自己拼 JSON 而不引 Gson：这是**整个插件唯一的序列化点**，字段是扁平的、
 * 类型就四种（字符串/数字/布尔/数组）。自己拼能少一个依赖，也就不存在"服务端没带 Gson"
 * 这种编译期/运行期不一致；代价是要把转义写对（见 {@link #escape(String)}）。
 */
public final class ControlMessage {

    /** 协议版本。客户端只认 1，别的整包拒绝。 */
    public static final int PROTOCOL = 1;

    private final StringBuilder body = new StringBuilder();
    private final String op;

    private ControlMessage(String op, String slot) {
        this.op = op;
        put("v", PROTOCOL);
        put("op", op);
        if (slot != null) put("slot", slot);
    }

    public static ControlMessage of(String op) {
        return new ControlMessage(op, null);
    }

    public static ControlMessage of(String op, String slot) {
        return new ControlMessage(op, slot);
    }

    /** 服务端自定义序号：客户端会把它原样回显在回执的 {@code ack} 里。 */
    public ControlMessage seq(long value) {
        put("seq", value);
        return this;
    }

    public ControlMessage token(String value) {
        if (value != null && !value.isBlank()) put("token", value);
        return this;
    }

    public ControlMessage url(String value) {
        put("url", value);
        return this;
    }

    public ControlMessage volume(int percent) {
        put("volume", Math.max(0, Math.min(100, percent)));
        return this;
    }

    public ControlMessage paused(boolean value) {
        put("paused", value);
        return this;
    }

    public ControlMessage muted(boolean value) {
        put("muted", value);
        return this;
    }

    public ControlMessage repeat(boolean value) {
        put("repeat", value);
        return this;
    }

    public ControlMessage positionMs(long value) {
        put("positionMs", Math.max(0L, value));
        return this;
    }

    public ControlMessage aspect(float value) {
        put("aspect", value);
        return this;
    }

    /** 维度 id（{@code place} 用），例如 {@code world} 或 {@code minecraft:overworld}。 */
    public ControlMessage world(String value) {
        put("world", value);
        return this;
    }

    public ControlMessage live(boolean value) {
        put("live", value);
        return this;
    }

    public ControlMessage serverTimeMs(long value) {
        put("serverTimeMs", value);
        return this;
    }

    /**
     * 塞一段**已经是 JSON** 的值（数组/对象）。
     *
     * <p>只给 {@code corners} 这种结构用：把它拆成 4 个字符串字段会让协议更难看。
     * 调用方负责保证 {@code rawJson} 是合法 JSON —— 这里不校验（内部只有一处调用）。
     */
    /** 写一个数字字段（值原样拼进 JSON，不经过字符串转义）。 */
    public ControlMessage rawInt(String key, int value) {
        put(key, value);
        return this;
    }

    public ControlMessage raw(String key, String rawJson) {
        if (this.body.length() > 0) this.body.append(',');
        this.body.append('"').append(key).append("\":").append(rawJson);
        return this;
    }

    public String op() {
        return this.op;
    }

    public String json() {
        return "{" + this.body + "}";
    }

    // =====================================================================

    private void put(String key, Object value) {
        if (this.body.length() > 0) this.body.append(',');
        this.body.append('"').append(key).append("\":");
        if (value instanceof Number || value instanceof Boolean) {
            this.body.append(value);
        } else {
            this.body.append('"').append(escape(String.valueOf(value))).append('"');
        }
    }

    /**
     * JSON 字符串转义。
     *
     * <p>只做规范要求的那几条就够了：{@code "}、{@code \}、控制字符。
     * 不转 {@code & ? = %} 这些（URL 里常见，但它们在 JSON 字符串里本来就不需要转义）。
     */
    static String escape(String s) {
        if (s == null) return "";
        final StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
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
}

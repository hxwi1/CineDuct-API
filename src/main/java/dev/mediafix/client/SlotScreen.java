package dev.mediafix.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 由服务端（Paper 插件）下发的**虚拟屏**几何（{@code place} op，见 {@code docs/paper-control-payload.md} §8）。
 *
 * <p>客户端**不建实体**：屏幕实体（{@code cineduct:screen}）是服务端权威数据，Paper 服没有本模组，
 * 造不出来。所以这里只是一组数字 —— 四个角点 + 显示参数，渲染器按它画一个四边形。
 *
 * <p>四个角点由插件算好、按"左上→右上→右下→左下"的环序发下来；客户端**不做几何推导**，
 * 这样斜放的屏、拼接屏以后都不用改协议。
 */
public record SlotScreen(String slot, ResourceKey<Level> world, Vec3[] corners,
                         float alpha, float brightness) {

    /** 单边合法长度（格）：太小的多半是选点失误，太大的会把渲染剔除算错。 */
    private static final double MIN_EDGE = 0.1;
    private static final double MAX_EDGE = 128.0;

    /** 解析 {@code place} 报文的几何部分；不合法抛 {@link IllegalArgumentException}（上层回错误）。 */
    public static SlotScreen parse(String slot, JsonObject o) {
        final String worldId = str(o, "world", "minecraft:overworld");
        final ResourceLocation rl = ResourceLocation.tryParse(worldId);
        if (rl == null) throw new IllegalArgumentException("world 不是合法维度 id: " + worldId);

        final JsonElement e = o.get("corners");
        if (e == null || !e.isJsonArray()) throw new IllegalArgumentException("place 需要 corners 数组");
        final JsonArray array = e.getAsJsonArray();
        if (array.size() != 4) throw new IllegalArgumentException("corners 必须是 4 个点（实际 " + array.size() + "）");

        final Vec3[] corners = new Vec3[4];
        for (int i = 0; i < 4; i++) {
            final JsonElement item = array.get(i);
            if (item == null || !item.isJsonArray()) throw new IllegalArgumentException("corners[" + i + "] 不是数组");
            final JsonArray xyz = item.getAsJsonArray();
            if (xyz.size() != 3) throw new IllegalArgumentException("corners[" + i + "] 需要 3 个坐标");
            corners[i] = new Vec3(xyz.get(0).getAsDouble(), xyz.get(1).getAsDouble(), xyz.get(2).getAsDouble());
            if (!isFinite(corners[i])) throw new IllegalArgumentException("corners[" + i + "] 含 NaN/Inf");
        }
        for (int i = 0; i < 4; i++) {
            final double edge = corners[i].distanceTo(corners[(i + 1) % 4]);
            if (edge < MIN_EDGE || edge > MAX_EDGE) {
                throw new IllegalArgumentException("屏幕边长非法: " + String.format(java.util.Locale.ROOT, "%.2f", edge));
            }
        }

        final float alpha = (float) clamp(num(o, "alpha", 1.0), 0.05, 1.0);
        final float brightness = (float) clamp(num(o, "brightness", 1.0), 0.05, 2.0);
        return new SlotScreen(slot, ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, rl),
                corners, alpha, brightness);
    }

    /** 平面中心（距离门控与剔除都用它）。 */
    public Vec3 center() {
        return this.corners[0].add(this.corners[1]).add(this.corners[2]).add(this.corners[3]).scale(0.25);
    }

    /** 屏幕"半径"：中心到最远角点，用于剔除的包围半径。 */
    public double radius() {
        final Vec3 c = center();
        double max = 0;
        for (Vec3 p : this.corners) max = Math.max(max, c.distanceTo(p));
        return max;
    }

    // =====================================================================

    private static boolean isFinite(Vec3 v) {
        return Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
    }

    private static double num(JsonObject o, String key, double def) {
        final JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) return def;
        try {
            return e.getAsDouble();
        } catch (RuntimeException ex) {
            return def;
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static String str(JsonObject o, String key, String def) {
        final JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : def;
    }
}

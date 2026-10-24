package dev.cineduct.paper;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;

import java.util.Locale;

/**
 * 把"两个对角点 + 玩家朝向"变成一个屏幕四边形的四个角。
 *
 * <p>规则（与 {@code docs/paper-control-payload.md} §8.1 一致）：
 * <ul>
 *   <li>屏幕**面朝玩家**：法线是水平的，指向创建时玩家所在的那一侧；</li>
 *   <li>**俯仰固定 90°**：屏幕竖直摆放（上方向恒为世界 +Y），不跟随抬头/低头；</li>
 *   <li>两个点击点投影到这个平面上，取投影后的包围盒作为宽高 —— 于是"隔空点两下"
 *       也能得到一块尺寸合理的屏，而不是要求玩家精确对齐坐标。</li>
 * </ul>
 *
 * <p>为什么让插件算、而不是把两个点丢给客户端：几何只算一次，所有客户端看到的完全一致；
 * 而且以后要加"斜放/拼接"只要改这里，协议还是四个角点。
 */
final class Geometry {

    /** 屏幕最大边长（格）：超过这个值多半是点错了，直接拒绝比画一块巨大的屏好。 */
    static final double MAX_EDGE = 64.0;
    /** 最小边长：太小说明两次点在同一格上。 */
    static final double MIN_EDGE = 0.25;

    private Geometry() {
    }

    /** 几何算不出来时抛 {@link IllegalArgumentException}（指令层转成红字提示）。 */
    static Location[] corners(Location a, Location b, double playerYawDegrees) {
        if (a == null || b == null) throw new IllegalArgumentException("两个对角点还没选全");
        final World world = a.getWorld();
        if (world == null || b.getWorld() == null || !world.equals(b.getWorld())) {
            throw new IllegalArgumentException("两个点必须在同一个维度里");
        }

        final Vector mid = a.toVector().add(b.toVector()).multiply(0.5);
        // 水平法线：从屏幕中心指向玩家。玩家在屏幕哪一侧，就朝哪一侧看。
        final double yawRad = Math.toRadians(playerYawDegrees);
        // MC 的 yaw：0 = +Z 方向看，90 = -X。取"玩家看向的方向"反过来就是指向玩家。
        final Vector look = new Vector(-Math.sin(yawRad), 0, Math.cos(yawRad)).normalize();
        final Vector normal = look.multiply(-1.0);
        if (normal.lengthSquared() < 1e-6) throw new IllegalArgumentException("朝向算不出来（玩家在屏幕正上方？）");

        // 右手系：右 = 上 × 法线，上 = 世界 +Y（俯仰固定 90°）
        final Vector up = new Vector(0, 1, 0);
        final Vector right = up.clone().crossProduct(normal).normalize();

        final Vector delta = b.toVector().subtract(a.toVector());
        final double width = Math.abs(delta.dot(right));
        final double height = Math.abs(delta.dot(up));
        if (width < MIN_EDGE || height < MIN_EDGE) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "选中的面积太小（%.2f × %.2f 格）：点两个**对角**，别点在同一个格子上", width, height));
        }
        if (width > MAX_EDGE || height > MAX_EDGE) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "屏幕太大（%.1f × %.1f 格，上限 %.0f）", width, height, MAX_EDGE));
        }

        final Vector halfRight = right.multiply(width / 2.0);
        final Vector halfUp = up.multiply(height / 2.0);
        // 环序：左上 → 右上 → 右下 → 左下（从屏幕正面看）
        return new Location[]{
                at(world, mid.clone().subtract(halfRight).add(halfUp)),
                at(world, mid.clone().add(halfRight).add(halfUp)),
                at(world, mid.clone().add(halfRight).subtract(halfUp)),
                at(world, mid.clone().subtract(halfRight).subtract(halfUp)),
        };
    }

    private static Location at(World world, Vector v) {
        return new Location(world, v.getX(), v.getY(), v.getZ());
    }

    /** 屏幕名字 → 合法 slot 名（客户端对 slot 有 `[A-Za-z0-9_.-]{1,32}` 的硬校验）。 */
    static boolean validName(String name) {
        return name != null && name.matches("[A-Za-z0-9_.-]{1,32}");
    }
}

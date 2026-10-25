// SPDX-License-Identifier: MIT
// Copyright (c) 2026 hxwi1
package dev.cineduct.paper;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.util.Vector;

import java.util.Locale;

/**
 * 把"两个对角点 + 点击到的方块面"变成一个屏幕四边形。
 *
 * <p>规则：
 * <ul>
 *   <li>屏幕所在的**平面**由点到的那一面决定（法线 = 方块面的朝向），不是玩家的朝向 ——
 *       这样你站在哪儿点都不影响结果，量出来的就是墙上那块矩形；</li>
 *   <li>屏幕**竖直摆放**（上方向恒为世界 +Y）；</li>
 *   <li>两个点在这个平面内取包围盒：宽 = 水平方向的差，高 = Y 方向的差。</li>
 * </ul>
 *
 * <p>★ 踩过的坑：早先用"玩家朝向"当法线，于是同一个墙面，你侧着站和正着站点出来的
 * 屏幕宽度不一样（投影被余弦压缩），玩家看到的就是"大小跟服务端说的不一样"。
 */
final class Geometry {

    /** 屏幕最大边长（格）。 */
    static final double MAX_EDGE = 64.0;
    /** 屏幕浮出墙面的距离（格）：够拉开深度差消除 z-fighting，又看不出来。 */
    static final double SURFACE_GAP = 0.05;

    /** 最小边长：太小说明两次点在同一格上。 */
    static final double MIN_EDGE = 0.25;

    private Geometry() {
    }

    /**
     * @param a    第一个点击点（方块坐标）
     * @param faceA 第一个点所在的方块面
     * @param b    第二个点击点
     * @param faceB 第二个点所在的方块面
     * @return 四角（环序：左上→右上→右下→左下，从屏幕正面看）
     * @throws IllegalArgumentException 两个点不构成合法矩形
     */
    static Location[] corners(Location a, BlockFace faceA, Location b, BlockFace faceB) {
        if (a == null || b == null) throw new IllegalArgumentException("两个对角点还没选全");
        final World world = a.getWorld();
        if (world == null || b.getWorld() == null || !world.equals(b.getWorld())) {
            throw new IllegalArgumentException("两个点必须在同一个维度里");
        }

        // 法线：优先用两次点击都落在的同一类面；都不一致就取第一个点的面。
        final BlockFace face = pickFace(faceA, faceB);
        final Vector normal = new Vector(face.getModX(), face.getModY(), face.getModZ());
        if (normal.lengthSquared() < 0.5) throw new IllegalArgumentException("拿不到方块面朝向，请再点一次方块侧面");

        // 屏幕必须竖直：法线不能朝上/朝下（点到地面或天花板时给个明确提示，而不是画一块躺着的屏）
        if (Math.abs(normal.getY()) > 0.5) {
            throw new IllegalArgumentException("请点在**竖直的墙面上**（点到地面/天花板没法立屏）");
        }

        /*
         * ★ 右方向 = normal × up，**不是** up × normal。
         *
         * <p>玩家是从方块外面点这面墙的，所以观察者在 +normal 一侧。
         * 站在那一侧看，他的"右手边"是 normal × up；用反了整块屏会**左右镜像**
         * （和屏幕实体那条路踩过的 `180 - yaw` 是同一类坑）。
         */
        final Vector up = new Vector(0, 1, 0);
        Vector right = up.clone().crossProduct(normal);
        if (right.lengthSquared() < 1e-6) throw new IllegalArgumentException("朝向算不出来");
        right.normalize();

        /*
         * 平面位置取两次点击在法线方向上的平均：点的是方块坐标（角点），
         * 如果两次点到了墙的两面（一前一后），取中间而不是随便挑一个。
         */
        /*
         * 点击给的是**方块坐标**（最小角），要换成"方块中心"再算，屏幕才正好压在这几格上。
         *
         * <p>两件事：
         *   * 水平/竖直方向：每个点 +0.5，于是"点第 10 格和第 12 格"的中心在 11.5，
         *     跨度 = 差 + 1 = 3 格，正好覆盖 10~13 这一段；
         *   * 法线方向：不能 +0.5（那样会把屏埋进墙里半格），而是取**点击到的那个面的外表面**
         *     —— 正向面（SOUTH/EAST/UP）在坐标 +1 处，负向面就在坐标处。
         */
        /*
         * 平面 = 方块**外表面**再往法线方向推 0.5 格。
         *
         * <p>为什么推出去：正好贴在墙面上会 z-fighting（闪烁、穿模）。推 0.1 格就够拉开深度差，
         * 看起来仍然贴着墙；0.5 太明显（像悬空）。正向面（SOUTH/EAST/UP）外表面在坐标 +1 处，
         * 负向面在坐标处。
         */
        final double faceSign = normal.getX() + normal.getY() + normal.getZ();   // ±1
        final double faceOffset = faceSign > 0 ? 1.0 : 0.0;
        final double planeCoord = (a.toVector().dot(normal) + b.toVector().dot(normal)) / 2.0
                + faceOffset + SURFACE_GAP;

        final double aRight = a.toVector().dot(right) + 0.5;
        final double bRight = b.toVector().dot(right) + 0.5;
        final double width = Math.abs(aRight - bRight) + 1.0;
        final double height = Math.abs(a.getY() - b.getY()) + 1.0;
        if (width < MIN_EDGE || height < MIN_EDGE) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "选中的面积太小（%.2f × %.2f 格）：点两个**对角**，别点在同一个格子上", width, height));
        }
        if (width > MAX_EDGE || height > MAX_EDGE) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "屏幕太大（%.1f × %.1f 格，上限 %.0f）", width, height, MAX_EDGE));
        }

        final double midRight = (aRight + bRight) / 2.0;
        final double midY = (a.getY() + b.getY()) / 2.0 + 0.5;
        final Vector center = right.clone().multiply(midRight).add(normal.clone().multiply(planeCoord))
                .add(new Vector(0, midY, 0));
        final Vector halfRight = right.clone().multiply(width / 2.0);
        final Vector halfUp = up.clone().multiply(height / 2.0);

        return new Location[]{
                at(world, center.clone().subtract(halfRight).add(halfUp)),
                at(world, center.clone().add(halfRight).add(halfUp)),
                at(world, center.clone().add(halfRight).subtract(halfUp)),
                at(world, center.clone().subtract(halfRight).subtract(halfUp)),
        };
    }

    /** 两次点击的面：如果同轴（比如都是墙的南北面）就用它们，否则用第一个。 */
    private static BlockFace pickFace(BlockFace a, BlockFace b) {
        if (a == null) return b == null ? BlockFace.NORTH : b;
        if (b == null) return a;
        // 同一根轴（法线方向一致）才采用第一个；不同轴说明点歪了，仍然用第一个并靠后续校验兜住
        final boolean sameAxis = (a.getModX() != 0) == (b.getModX() != 0)
                && (a.getModY() != 0) == (b.getModY() != 0)
                && (a.getModZ() != 0) == (b.getModZ() != 0);
        return sameAxis ? a : b;
    }

    private static Location at(World world, Vector v) {
        return new Location(world, v.getX(), v.getY(), v.getZ());
    }

    /** 屏幕名字 → 合法 slot 名（客户端对 slot 有 `[A-Za-z0-9_.-]{1,32}` 的硬校验）。 */
    static boolean validName(String name) {
        return name != null && name.matches("[A-Za-z0-9_.-]{1,32}");
    }
}

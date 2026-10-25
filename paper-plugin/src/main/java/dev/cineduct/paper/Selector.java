// SPDX-License-Identifier: MIT
// Copyright (c) 2026 hxwi1
package dev.cineduct.paper;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 选点会话：{@code /cineplay create} 之后，玩家**手持木锄右键**点两个对角。
 *
 * <p>流程（见 {@code docs/paper-control-payload.md} §8.1）：
 * <pre>
 *   /cineplay create            进入选区（可重复，后一次覆盖前一次）
 *   右键第 1 次                 记第一个角，开始撒粒子
 *   右键第 2 次                 记第二个角，画出预览矩形
 *   /cineplay confirm &lt;名字&gt;    确认并命名 → 算几何 → 下发 place
 *   /cineplay cancel            放弃
 * </pre>
 *
 * <p><b>粒子只给选区的人看、只在选区期间撒</b>：常驻描边要每 tick 给每个玩家发包，
 * 几十人同屏就是网络灾难；而"屏幕上没画面时看不见"用指令回执解决更省。
 */
final class Selector implements Listener {

    /** 选区超时（毫秒）：玩家点了一下然后跑去吃饭，别一直留着状态。 */
    private static final long TIMEOUT_MS = 5 * 60 * 1000L;
    /** 粒子刷新间隔（tick）。 */
    private static final int PARTICLE_PERIOD = 10;

    /** 一个玩家的选区。 */
    private static final class Session {
        Location pos1;
        Location pos2;
        org.bukkit.block.BlockFace face1;
        org.bukkit.block.BlockFace face2;
        long touchedAt;
    }

    private final CinePlayPlugin plugin;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    Selector(CinePlayPlugin plugin) {
        this.plugin = plugin;
        // 粒子由插件自己按玩家撒（量很小：两个点 + 四条边，每 0.5 秒一次）
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickParticles, 20L, PARTICLE_PERIOD);
    }

    // =====================================================================
    // 会话
    // =====================================================================

    void begin(Player player) {
        final Session session = new Session();
        session.touchedAt = System.currentTimeMillis();
        this.sessions.put(player.getUniqueId(), session);
        Messages.ok(player, "选区开始：手持木锄右键点**两个对角**（射线 64 格内的方块表面）");
        Messages.note(player, "选完用 /cineplay confirm <屏幕名字> 确认，或 /cineplay cancel 放弃");
    }

    void cancel(Player player) {
        if (this.sessions.remove(player.getUniqueId()) != null) {
            Messages.ok(player, "已放弃这次选区");
        } else {
            Messages.warn(player, "你当前没有在选区");
        }
    }

    /** 当前会话（没有则 null）。 */
    Session session(Player player) {
        final Session session = this.sessions.get(player.getUniqueId());
        if (session == null) return null;
        if (System.currentTimeMillis() - session.touchedAt > TIMEOUT_MS) {
            this.sessions.remove(player.getUniqueId());
            Messages.warn(player, "选区超时（5 分钟没操作），已取消");
            return null;
        }
        return session;
    }

    boolean hasBoth(Player player) {
        final Session session = session(player);
        return session != null && session.pos1 != null && session.pos2 != null;
    }

    Location[] points(Player player) {
        final Session session = session(player);
        return session == null ? null : new Location[]{session.pos1, session.pos2};
    }

    /** 两次点击落到的方块面（没有会话时为 null）。 */
    org.bukkit.block.BlockFace[] faces(Player player) {
        final Session session = session(player);
        return session == null ? new org.bukkit.block.BlockFace[]{null, null}
                : new org.bukkit.block.BlockFace[]{session.face1, session.face2};
    }

    void finish(Player player) {
        this.sessions.remove(player.getUniqueId());
    }

    // =====================================================================
    // 右键选点
    // =====================================================================

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;              // 只认主手，避免一次点击算两次
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getItem() == null || event.getItem().getType() != Material.WOODEN_HOE) return;

        final Player player = event.getPlayer();
        final Session session = this.sessions.get(player.getUniqueId());
        if (session == null) return;    // 没在选区：木锄当普通工具用，不拦

        event.setCancelled(true);       // 选区期间别真的去锄地
        session.touchedAt = System.currentTimeMillis();

        final Location hit = event.getClickedBlock() == null ? null : event.getClickedBlock().getLocation();
        if (hit == null) return;
        final org.bukkit.block.BlockFace face = event.getBlockFace();
        if (session.pos1 == null) {
            session.pos1 = hit;
            session.face1 = face;
            Messages.ok(player, "第一个角: " + fmt(hit));
        } else if (session.pos2 == null) {
            session.pos2 = hit;
            session.face2 = face;
            Messages.ok(player, "第二个角: " + fmt(hit));
            Messages.note(player, "用 /cineplay confirm <屏幕名字> 确认，或 /cineplay cancel 放弃");
        } else {
            // 已经两个点：再点一次就重来（换第一个角），避免玩家卡在错误状态
            session.pos1 = hit;
            session.face1 = face;
            session.pos2 = null;
            session.face2 = null;
            Messages.warn(player, "重新选点：第一个角已更新为 " + fmt(hit));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        this.sessions.remove(event.getPlayer().getUniqueId());
    }

    /**
     * 进服补投：玩家不在的时候建的屏，几何与锚点都要补给他。
     *
     * <p>不补的话，先建屏后进服的人永远收不到 {@code place} —— 表现为"别人看得见，他看不见"。
     */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        // 延后一 tick：刚进服时客户端还没把通道注册好，立刻发会被丢掉
        this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> {
            if (!player.isOnline()) return;
            for (String name : this.plugin.screens().names()) {
                final Screens.Screen screen = this.plugin.screens().get(name);
                if (screen != null) this.plugin.screens().sendTo(player, screen);
            }
        }, 40L);
    }

    // =====================================================================
    // 粒子
    // =====================================================================

    private void tickParticles() {
        if (this.sessions.isEmpty()) return;
        final long now = System.currentTimeMillis();
        this.sessions.entrySet().removeIf(e -> now - e.getValue().touchedAt > TIMEOUT_MS);

        for (Map.Entry<UUID, Session> entry : this.sessions.entrySet()) {
            final Player player = this.plugin.getServer().getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) continue;
            final Session session = entry.getValue();
            final World world = player.getWorld();
            if (session.pos1 != null && session.pos1.getWorld() != null
                    && session.pos1.getWorld().equals(world)) {
                marker(player, session.pos1);
            }
            if (session.pos2 != null && session.pos2.getWorld() != null
                    && session.pos2.getWorld().equals(world)) {
                marker(player, session.pos2);
            }
            if (session.pos1 != null && session.pos2 != null) {
                preview(player, session.pos1, session.pos2);
            }
        }
    }

    /** 一个角：一小团密集粒子，玩家一眼能找到点在哪个方块角。 */
    private void marker(Player player, Location l) {
        player.spawnParticle(Particle.END_ROD, l.clone().add(0.5, 0.5, 0.5), 6, 0.06, 0.06, 0.06, 0.0);
    }

    /**
     * 两角预览：画一条"矩形边"。
     *
     * <p>这里只画<b>立方体对角线</b>那一根 + 两端标记，不做完整矩形 —— 真正的矩形要等确认时
     * 按"面朝玩家、俯仰 90°"算出来，现在画的矩形大概率和最终结果不一样，反而误导。
     */
    private void preview(Player player, Location a, Location b) {
        if (!a.getWorld().equals(b.getWorld())) return;
        final Vector from = a.toVector().add(new Vector(0.5, 0.5, 0.5));
        final Vector to = b.toVector().add(new Vector(0.5, 0.5, 0.5));
        final Vector delta = to.clone().subtract(from);
        final double len = delta.length();
        if (len < 0.1) return;
        final int steps = (int) Math.min(64, Math.max(2, Math.round(len * 2.0)));
        for (int i = 0; i <= steps; i++) {
            final Vector p = from.clone().add(delta.clone().multiply((double) i / steps));
            // 用 Location 那个重载：World 版本没有 (x,y,z,count) 这对参数
            player.spawnParticle(Particle.END_ROD, p.getX(), p.getY(), p.getZ(), 1, 0, 0, 0, 0);
        }
    }

    private static String fmt(Location l) {
        return String.format(java.util.Locale.ROOT, "%d %d %d（%s）",
                l.getBlockX(), l.getBlockY(), l.getBlockZ(),
                l.getWorld() == null ? "?" : l.getWorld().getName());
    }
}

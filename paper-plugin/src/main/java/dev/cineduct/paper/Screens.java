// SPDX-License-Identifier: MIT
// Copyright (c) 2026 hxwi1

package dev.cineduct.paper;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 屏幕注册表：名字 → 几何 + 播放列表 + 进度。
 *
 * <p>这是"服务端只管状态"那部分的状态本体：**几何**（四角）、**放什么**（playlist + index）、
 * **放到哪**（进度锚点）。
 *
 * <p><b>进度是服务端自己算的</b>：{@code 锚点位置 + (现在 − 锚点时刻)}。客户端不回传任何数据
 * —— 它只是按锚点起播、按时钟自己放。这条设计的好处是没有"回报不可信"的问题，
 * 代价是服务端不知道客户端到底卡了没有，所以播放列表推进要靠每项的**时长**（{@link #durations}）。
 *
 * <p>持久化在 {@code plugins/CinePlay/screens.yml}：重启后屏幕、播放列表、进度都在。
 */
final class Screens {

    /** 一块屏。 */
    static final class Screen {
        final String name;
        final List<String> playlist = new ArrayList<>();
        /** 与 playlist 一一对应的时长（秒）；0 = 不知道，不自动推进。 */
        final List<Integer> durations = new ArrayList<>();
        int index;
        /** 四角（环序：左上→右上→右下→左下）；空 = 还没选点。 */
        List<Location> corners = List.of();

        /*
         * 选点原始数据（两个方块坐标 + 点击到的面）。
         *
         * 存它而不是只存算好的四角：几何规则（面法线、+1 格、浮出距离）以后还会调，
         * 只存四角的话每次改规则都要求玩家把屏幕删了重建 —— 上一次调 SURFACE_GAP
         * 就是"改了没反应"，实际是旧坐标还躺在存档里。
         */
        /* ---- 权限：谁能管理这块屏 ---- */

        /** 创建者（唯一能授权给别人的人，OP 除外）。 */
        java.util.UUID ownerId;
        String ownerName;
        /** 被逐项授权的人（可以管理这块屏，但不能再授权）。 */
        final java.util.Set<java.util.UUID> access = new java.util.LinkedHashSet<>();

        /** 信任者：能操控播放（加片/切条/音量/范围），但**不能删**。 */
        final java.util.Set<java.util.UUID> trusted = new java.util.LinkedHashSet<>();
        /** 是否对所有人开放管理。 */
        boolean publicAccess;

        /* ---- 每块屏自己的播放参数 ---- */

        /** 音量百分比（0~100）。 */
        int volumePercent = 100;
        /** 起播距离（格）。 */
        int enterRange = 48;
        /** 停止距离（格）。 */
        int exitRange = 64;

        Location origin1;
        Location origin2;
        String face1;
        String face2;

        /** 锚点：某个时刻应播到 positionMs。 */
        long positionMs;
        long anchorWallMs;
        boolean paused;
        boolean live;
        /** 队列已经播完并被清空（只用于显示，不参与播放逻辑）。 */
        boolean finished;

        Screen(String name) {
            this.name = name;
        }

        String currentUrl() {
            if (this.playlist.isEmpty()) return "";
            return this.playlist.get(Math.floorMod(this.index, this.playlist.size()));
        }

        /** 当前项的时长（秒）；0 = 未知。 */
        int currentDurationSec() {
            if (this.durations.isEmpty()) return 0;
            return this.durations.get(Math.floorMod(this.index, this.durations.size()));
        }

        String nextUrl() {
            if (this.playlist.isEmpty()) return "";
            this.index = Math.floorMod(this.index + 1, this.playlist.size());
            return currentUrl();
        }

        String prevUrl() {
            if (this.playlist.isEmpty()) return "";
            this.index = Math.floorMod(this.index - 1, this.playlist.size());
            return currentUrl();
        }

        /** 现在应该播到哪（服务端权威，不依赖任何客户端回报）。 */
        long currentPositionMs() {
            if (this.paused || this.live) return this.positionMs;
            return this.positionMs + Math.max(0L, System.currentTimeMillis() - this.anchorWallMs);
        }

        /** 把"当前算出来的位置"固化成一个新锚点（落盘/重载/切歌前都要先做一次）。 */
        void reanchor() {
            this.positionMs = currentPositionMs();
            this.anchorWallMs = System.currentTimeMillis();
        }
    }

    private final CinePlayPlugin plugin;
    private final Map<String, Screen> screens = new LinkedHashMap<>();
    /** 有未落盘的改动（进度随时间走，不能一变就写盘）。 */
    private boolean dirty;
    private long lastSaveAt;

    Screens(CinePlayPlugin plugin) {
        this.plugin = plugin;
    }

    // =====================================================================
    // 读写
    // =====================================================================

    synchronized Screen get(String name) {
        return name == null ? null : this.screens.get(name.toLowerCase(Locale.ROOT));
    }

    synchronized Screen getOrCreate(String name) {
        return this.screens.computeIfAbsent(name.toLowerCase(Locale.ROOT), Screen::new);
    }

    synchronized void put(Screen screen) {
        this.screens.put(screen.name.toLowerCase(Locale.ROOT), screen);
        save();
    }

    synchronized boolean remove(String name) {
        final boolean removed = this.screens.remove(name.toLowerCase(Locale.ROOT)) != null;
        if (removed) save();
        return removed;
    }

    synchronized List<String> names() {
        return new ArrayList<>(this.screens.keySet());
    }

    synchronized Map<String, Screen> all() {
        return new LinkedHashMap<>(this.screens);
    }

    /*
     * 三档权限（由小到大）：
     *   信任（trusted）  能操控播放：加片、切条、音量、范围。
     *   管理员（access） 在信任之上还能**删除**这块屏、给别人授权。
     *   主人（owner）    创建者，天然是管理员。
     *   public = "信任所有人"：所有玩家都拿到信任级，但没人因此获得删除权。
     *   OP 视同主人（排障用）。
     *
     * 为什么这么分：删屏不可撤销，把它和"能放片子"绑在一起迟早出事；
     * 而"公开"给的是**使用**权，不该顺带给销毁权。
     */

    /** 信任级及以上：能操控播放。 */
    static boolean canPlay(Screen screen, Player player) {
        if (player == null) return true;                       // 控制台
        if (player.isOp()) return true;
        if (screen == null) return false;
        if (screen.ownerId == null) return true;               // 没主人的老屏：不锁
        if (screen.ownerId.equals(player.getUniqueId())) return true;
        if (screen.access.contains(player.getUniqueId())) return true;
        return screen.publicAccess || screen.trusted.contains(player.getUniqueId());
    }

    /** 管理员级：能删屏、能授权。**不看 publicAccess**。 */
    static boolean canAdmin(Screen screen, Player player) {
        if (player == null) return true;
        if (player.isOp()) return true;
        if (screen == null) return false;
        if (screen.ownerId == null) return true;
        if (screen.ownerId.equals(player.getUniqueId())) return true;
        return screen.access.contains(player.getUniqueId());
    }

    /** 删屏 = 管理员级（保留旧名字给删除逻辑用）。 */
    static boolean canDelete(Screen screen, Player player) {
        return canAdmin(screen, player);
    }

    /** 兼容旧调用：能管这块屏 = 能操控播放。 */
    static boolean canManage(Screen screen, Player player) {
        return canPlay(screen, player);
    }

    /** 能用这块屏 = 信任级及以上。 */
    static boolean canUse(Screen screen, Player player) {
        return canPlay(screen, player);
    }

    /** 这个人对这块屏的身份（菜单/提示显示用）。 */
    static String roleOf(Screen screen, Player player) {
        if (player == null) return "控制台";
        if (player.isOp()) return "OP";
        if (screen == null) return "无";
        if (screen.ownerId != null && screen.ownerId.equals(player.getUniqueId())) return "主人";
        if (screen.access.contains(player.getUniqueId())) return "管理员";
        if (screen.trusted.contains(player.getUniqueId())) return "信任";
        if (screen.publicAccess) return "公开";
        return "无";
    }
    private static Location vec(ConfigurationSection s, String key, World world) {
        final List<Double> list = new ArrayList<>(3);
        for (Object o : s.getList(key, List.of())) {
            list.add(o instanceof Number n ? n.doubleValue() : 0.0);
        }
        if (world == null || list.size() != 3) return null;
        return new Location(world, list.get(0), list.get(1), list.get(2));
    }

    private File file() {
        return new File(this.plugin.getDataFolder(), "screens.yml");
    }

    synchronized void load() {
        this.screens.clear();
        final File f = file();
        if (!f.isFile()) return;
        final YamlConfiguration yaml = YamlConfiguration.loadConfiguration(f);
        final ConfigurationSection root = yaml.getConfigurationSection("screens");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            final ConfigurationSection s = root.getConfigurationSection(key);
            if (s == null) continue;
            final Screen screen = new Screen(key.toLowerCase(Locale.ROOT));
            screen.playlist.addAll(s.getStringList("playlist"));
            for (Object o : s.getList("durations", List.of())) {
                screen.durations.add(o instanceof Number n ? n.intValue() : 0);
            }
            screen.index = s.getInt("index", 0);
            screen.positionMs = s.getLong("positionMs", 0L);
            screen.paused = s.getBoolean("paused", false);
            screen.live = s.getBoolean("live", false);
            screen.finished = s.getBoolean("finished", false);
            final String owner = s.getString("ownerId");
            screen.ownerId = owner == null || owner.isBlank() ? null : java.util.UUID.fromString(owner);
            screen.ownerName = s.getString("ownerName");
            screen.publicAccess = s.getBoolean("publicAccess", false);
            for (String id : s.getStringList("trusted")) {
                try {
                    screen.trusted.add(java.util.UUID.fromString(id));
                } catch (IllegalArgumentException ignored) {
                    // 坏 UUID 就跳过，不因为一条脏数据丢掉整块屏
                }
            }
            for (String id : s.getStringList("access")) {
                try {
                    screen.access.add(java.util.UUID.fromString(id));
                } catch (IllegalArgumentException ignored) {
                    // 存档里写坏了就跳过，不因为一条坏 UUID 丢掉整块屏
                }
            }
            screen.volumePercent = Math.clamp(s.getInt("volumePercent", 100), 0, 100);
            screen.enterRange = Math.max(4, s.getInt("enterRange", 48));
            screen.exitRange = Math.max(screen.enterRange + 4, s.getInt("exitRange", 64));
            /*
             * 锚点时刻用"现在"：存档里存的是关服那一刻的位置，重启后应当从那里继续走，
             * 而不是把停机这段时间也算成"播过去了"。
             */
            screen.anchorWallMs = System.currentTimeMillis();
            final String world = s.getString("world", "");
            final World w = world.isEmpty() ? null : Bukkit.getWorld(world);
            final List<List<Double>> raw = new ArrayList<>();
            for (Object o : s.getList("corners", List.of())) {
                if (o instanceof List<?> list && list.size() == 3) {
                    final List<Double> xyz = new ArrayList<>(3);
                    for (Object n : list) xyz.add(n instanceof Number num ? num.doubleValue() : 0.0);
                    raw.add(xyz);
                }
            }
            if (w != null && raw.size() == 4) {
                final List<Location> corners = new ArrayList<>(4);
                for (List<Double> xyz : raw) corners.add(new Location(w, xyz.get(0), xyz.get(1), xyz.get(2)));
                screen.corners = List.copyOf(corners);
            }
            screen.origin1 = vec(s, "origin1", w);
            screen.origin2 = vec(s, "origin2", w);
            screen.face1 = s.getString("face1");
            screen.face2 = s.getString("face2");
            // 有原始选点就按**当前**几何规则重算四角（改了规则不用重建屏幕）
            if (screen.origin1 != null && screen.origin2 != null) {
                try {
                    screen.corners = List.of(Geometry.corners(screen.origin1,
                            org.bukkit.block.BlockFace.valueOf(screen.face1),
                            screen.origin2, org.bukkit.block.BlockFace.valueOf(screen.face2)));
                } catch (Exception e) {
                    this.plugin.getLogger().warning("重算屏幕 " + screen.name + " 的几何失败（沿用存档里的四角）: "
                            + e.getMessage());
                }
            }
            this.screens.put(screen.name, screen);
        }
        this.plugin.getLogger().info("已载入 " + this.screens.size() + " 块屏幕");
    }

    synchronized void save() {
        this.dirty = false;
        this.lastSaveAt = System.currentTimeMillis();
        final YamlConfiguration yaml = new YamlConfiguration();
        for (Screen screen : this.screens.values()) {
            final String path = "screens." + screen.name;
            yaml.set(path + ".playlist", screen.playlist);
            yaml.set(path + ".durations", screen.durations);
            yaml.set(path + ".index", screen.index);
            yaml.set(path + ".positionMs", screen.currentPositionMs());
            yaml.set(path + ".paused", screen.paused);
            yaml.set(path + ".live", screen.live);
            yaml.set(path + ".finished", screen.finished);
            yaml.set(path + ".ownerId", screen.ownerId == null ? null : screen.ownerId.toString());
            yaml.set(path + ".ownerName", screen.ownerName);
            yaml.set(path + ".publicAccess", screen.publicAccess);
            yaml.set(path + ".trusted", screen.trusted.stream().map(java.util.UUID::toString).toList());
            yaml.set(path + ".access", screen.access.stream().map(java.util.UUID::toString).toList());
            yaml.set(path + ".volumePercent", screen.volumePercent);
            yaml.set(path + ".enterRange", screen.enterRange);
            yaml.set(path + ".exitRange", screen.exitRange);
            if (screen.origin1 != null && screen.origin2 != null) {
                yaml.set(path + ".origin1", List.of(screen.origin1.getX(), screen.origin1.getY(), screen.origin1.getZ()));
                yaml.set(path + ".origin2", List.of(screen.origin2.getX(), screen.origin2.getY(), screen.origin2.getZ()));
                yaml.set(path + ".face1", screen.face1);
                yaml.set(path + ".face2", screen.face2);
            }
            if (!screen.corners.isEmpty()) {
                final Location first = screen.corners.getFirst();
                yaml.set(path + ".world", first.getWorld() == null ? "" : first.getWorld().getName());
                final List<List<Double>> corners = new ArrayList<>(4);
                for (Location c : screen.corners) corners.add(List.of(c.getX(), c.getY(), c.getZ()));
                yaml.set(path + ".corners", corners);
            }
        }
        try {
            if (!this.plugin.getDataFolder().isDirectory() && !this.plugin.getDataFolder().mkdirs()) {
                this.plugin.getLogger().warning("建不出插件数据目录，屏幕信息只在本次运行有效");
                return;
            }
            yaml.save(file());
        } catch (Exception e) {
            this.dirty = true;
            this.plugin.getLogger().warning("保存 screens.yml 失败: " + e.getMessage());
        }
    }

    /**
     * 写回某一项的时长（客户端起播时上报的实际时长）。
     *
     * <p>比插件自己查 API 更准：客户端知道自己实际放的是什么（清晰度、是否只有试看片段），
     * 所以**上报值覆盖查询值**。找不到对应项就忽略（可能是客户端自己开的播放，不属于这块屏）。
     */
    synchronized boolean setDurationByUrl(String screenName, String url, int seconds) {
        final Screen screen = get(screenName);
        if (screen == null || url == null || seconds <= 0) return false;
        final int index = screen.playlist.indexOf(url);
        if (index < 0) return false;
        while (screen.durations.size() <= index) screen.durations.add(0);
        if (screen.durations.get(index) == seconds) return false;
        screen.durations.set(index, seconds);
        save();
        return true;
    }

    /**
     * 写回某一项的时长（异步查询回来的结果）。
     *
     * @return false = 这一项已经不在了（列表被改过），忽略即可
     */
    synchronized boolean setDuration(String screenName, int index, int seconds) {
        final Screen screen = get(screenName);
        if (screen == null || index < 0 || index >= screen.playlist.size()) return false;
        while (screen.durations.size() <= index) screen.durations.add(0);
        screen.durations.set(index, Math.max(0, seconds));
        save();
        return true;
    }

    /**
     * 播放列表播完的收尾：清空队列 + 停播 + 标 finished。
     *
     * <p>必须和"定时推进"共用同一段逻辑：手动 {@code next} 在最后一项上按下去，
     * 之前走的是取模绕回 → 最后一条从头再放一遍，队列还留着，永远清不掉。
     */
    synchronized void finishAndClear(Screen screen, String why) {
        screen.playlist.clear();
        screen.durations.clear();
        screen.index = 0;
        screen.positionMs = 0L;
        screen.anchorWallMs = System.currentTimeMillis();
        screen.paused = false;
        screen.finished = true;
        save();
        this.plugin.broadcastClose(screen.name);
        this.plugin.getLogger().info("播放列表已清空：" + screen.name + " " + why);
    }

    /** 定时任务调（每 20 秒）：把"正在播"的屏固化成锚点并落盘。 */
    synchronized void autosave() {
        boolean any = false;
        final long now = System.currentTimeMillis();
        for (Screen screen : this.screens.values()) {
            if (screen.playlist.isEmpty() || screen.paused) continue;
            // 位置随时间走，落盘前先固化，否则存下来的还是上一次锚点
            if (now - screen.anchorWallMs >= 1000L) {
                screen.reanchor();
                any = true;
            }
        }
        if (any || this.dirty) save();
    }

    /**
     * 按"每项时长"推进播放列表（每秒钟由插件调一次）。
     *
     * <p>为什么必须靠时长：服务端不回读客户端状态，所以"这一条放完了没有"只能用
     * {@code 当前项时长} 与自己算的位置比。没填时长的项（0）不会自动推进 —— 宁可停住，
     * 也不要"看着像放完了"就跳。
     */
    synchronized void tickPlaylist() {
        for (Screen screen : this.screens.values()) {
            final int duration = screen.currentDurationSec();
            if (duration <= 0 || screen.playlist.size() < 2 || screen.paused) continue;
            if (screen.currentPositionMs() < duration * 1000L) continue;
            screen.reanchor();
            final boolean last = screen.index >= screen.playlist.size() - 1;
            if (last && !this.plugin.playlistLoop()) {
                /*
                 * 播完最后一项就**清空队列**，而不是原地索引归零继续"看起来在播"。
                 *
                 * <p>为什么必须清：留着状态的话，服务端永远显示"第 1 项 00:00"，
                 * 操作者无法分辨"客户端还在放"和"早就放完了、服务端自己在空转"。
                 * 清空之后 /cineplay playlist 是空的、status 显示播完，这就是明确的完成信号。
                 *
                 * <p>客户端那边不用发指令：播放器自己到 EOF 就停了。
                 */
                finishAndClear(screen, "全部播完（最后一项 " + duration + "s）→ 需要重播请重新 /cineplay add");
                continue;
            }
            screen.nextUrl();
            screen.positionMs = 0L;
            screen.anchorWallMs = System.currentTimeMillis();
            screen.finished = false;
            save();
            broadcastAnchor(screen);
            this.plugin.getLogger().info("播放列表推进 → " + screen.name
                    + " 第 " + (screen.index + 1) + " 项（上一项按时长 " + duration + "s 结束）");
        }
    }

    // =====================================================================
    // 下发
    // =====================================================================

    /** 把几何下发给所有在线玩家（一次就够：客户端会记住）。 */
    void broadcastPlace(Screen screen) {
        if (screen.corners.size() != 4) return;
        final String json = jsonPlace(screen, worldOf(screen), screen.corners);
        int sent = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (this.plugin.sendControlJson(p, json)) sent++;
        }
        this.plugin.getLogger().info("下发屏幕几何 " + screen.name + " → " + sent + " 个客户端");
    }

    /** 下发进度锚点（换源 / seek / 暂停 / 播放列表推进）。 */
    void broadcastAnchor(Screen screen) {
        final String url = screen.currentUrl();
        if (url.isEmpty() || screen.corners.isEmpty()) return;
        final String json = jsonAnchor(screen, url, screen.currentPositionMs(), screen.paused, screen.live);
        int sent = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (this.plugin.sendControlJson(p, json)) sent++;
        }
        this.plugin.getLogger().info("下发锚点 " + screen.name + " → " + safeUrl(url)
                + " @" + screen.currentPositionMs() + "ms → " + sent + " 个客户端");
    }

    /** 只发给一个人（玩家进服时补投：他不在的时候建的屏，几何与锚点都要补）。 */
    void sendTo(Player player, Screen screen) {
        if (screen.corners.size() == 4) {
            this.plugin.sendControlJson(player, jsonPlace(screen, worldOf(screen), screen.corners));
        }
        final String url = screen.currentUrl();
        if (!url.isEmpty()) {
            this.plugin.sendControlJson(player,
                    jsonAnchor(screen, url, screen.currentPositionMs(), screen.paused, screen.live));
        }
    }

    /**
     * 屏幕所在维度 → **客户端认的维度 id**。
     *
     * <p>★ 这里踩过一次：Bukkit 的 {@code World#getName()} 是**世界名**（存档目录名，通常就叫
     * {@code world}），而客户端比较的是**维度 id**（{@code minecraft:overworld}）。
     * 发世界名过去永远不会相等，客户端那句"不在这个维度就把播放关掉"于是每次都命中 ——
     * 表现就是"几何收到、画面永远不出来"。
     */
    private static String worldOf(Screen screen) {
        if (screen.corners.isEmpty()) return "minecraft:overworld";
        final Location first = screen.corners.getFirst();
        final World world = first.getWorld();
        if (world == null) return "minecraft:overworld";
        return switch (world.getEnvironment()) {
            case NETHER -> "minecraft:the_nether";
            case THE_END -> "minecraft:the_end";
            default -> "minecraft:overworld";
        };
    }

    // =====================================================================
    // JSON（自己拼，见 ControlMessage 的说明）
    // =====================================================================

    private static String jsonPlace(Screen screen, String world, List<Location> corners) {
        final ControlMessage m = ControlMessage.of("place", screen.name).world(world)
                .rawInt("enter", screen.enterRange)
                .rawInt("exit", screen.exitRange);
        final StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < corners.size(); i++) {
            final Location c = corners.get(i);
            if (i > 0) sb.append(',');
            sb.append('[').append(fmt(c.getX())).append(',').append(fmt(c.getY())).append(',')
                    .append(fmt(c.getZ())).append(']');
        }
        sb.append(']');
        m.raw("corners", sb.toString());
        return m.json();
    }

    private static String jsonAnchor(Screen screen, String url, long positionMs, boolean paused, boolean live) {
        final String slot = screen.name;
        return ControlMessage.of("anchor", slot)
                .url(url)
                .positionMs(positionMs)
                .paused(paused)
                .live(live)
                .rawInt("volume", screen.volumePercent)
                .serverTimeMs(System.currentTimeMillis())
                .json();
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    /** 日志里不打印查询串（可能带凭证）。 */
    static String safeUrl(String url) {
        if (url == null) return "null";
        final int q = url.indexOf('?');
        final String head = q < 0 ? url : url.substring(0, q);
        return head.length() > 80 ? head.substring(0, 80) + "…" : head;
    }
}

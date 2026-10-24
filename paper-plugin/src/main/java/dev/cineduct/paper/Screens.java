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
 * **放到哪**（锚点）。客户端只负责画和按距离开关播放。
 *
 * <p>持久化在插件数据目录的 {@code screens.yml}：服务器重启后屏幕还在，播放列表也在。
 */
final class Screens {

    /** 一块屏。 */
    static final class Screen {
        final String name;
        final List<String> playlist = new ArrayList<>();
        int index;
        /** 四角（环序：左上→右上→右下→左下）；空 = 还没选点。 */
        List<Location> corners = List.of();
        /** 锚点（相对当前条目）。 */
        long positionMs;
        boolean paused;
        /** 最近一次推进播放列表的时刻（防回执重复触发）。 */
        long lastAdvanceAt;

        Screen(String name) {
            this.name = name;
        }

        String currentUrl() {
            if (this.playlist.isEmpty()) return "";
            final int i = Math.floorMod(this.index, this.playlist.size());
            return this.playlist.get(i);
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
    }

    private final CinePlayPlugin plugin;
    private final Map<String, Screen> screens = new LinkedHashMap<>();
    /** 有未落盘的改动（进度每 tick 都在变，不能一变就写盘）。 */
    private boolean dirty;
    /** 上一次落盘的时刻（诊断用）。 */
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
            screen.index = s.getInt("index", 0);
            screen.positionMs = s.getLong("positionMs", 0L);
            screen.paused = s.getBoolean("paused", false);
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
            yaml.set(path + ".index", screen.index);
            yaml.set(path + ".positionMs", screen.positionMs);
            yaml.set(path + ".paused", screen.paused);
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
            this.dirty = true;      // 写失败就留着脏标记，下一轮 autosave 再试
            this.plugin.getLogger().warning("保存 screens.yml 失败: " + e.getMessage());
        }
    }

    // ---- 进度持久化 ----

    /**
     * 把客户端报上来的进度写进内存里的屏幕状态（**不立刻落盘**）。
     *
     * <p>为什么要这条：播放进度是服务端唯一"自己不会知道"的状态 —— 它只知道给了哪个 url、
     * 从哪一刻起播。不回写的话，重启之后锚点永远是上一次手动设置的值（通常是 0），
     * 表现为"重启后从头开始放"。
     *
     * <p>只在变化超过 1 秒或暂停状态翻了才记，避免每 2 秒的心跳都改状态。
     */
    synchronized void noteProgress(String slot, long positionMs, boolean paused) {
        final Screen screen = get(slot);
        if (screen == null || screen.playlist.isEmpty()) return;
        final boolean pauseChanged = screen.paused != paused;
        final boolean moved = Math.abs(positionMs - screen.positionMs) >= 1000L;
        if (!pauseChanged && !moved) return;
        screen.positionMs = Math.max(0L, positionMs);
        screen.paused = paused;
        this.dirty = true;
    }

    /** 定时任务调：有脏数据才写盘（默认每 20 秒一次，见插件 onEnable）。 */
    synchronized void autosave() {
        if (this.dirty) save();
    }

    /** 诊断：最近一次落盘多久之前。 */
    synchronized long sinceLastSaveMs() {
        return this.lastSaveAt <= 0 ? -1L : System.currentTimeMillis() - this.lastSaveAt;
    }

    // =====================================================================
    // 下发
    // =====================================================================

    /** 把几何下发给所有在线玩家（一次就够：客户端会记住）。 */
    void broadcastPlace(Screen screen) {
        if (screen.corners.size() != 4) return;
        final String json = jsonPlace(screen.name, worldOf(screen), screen.corners);
        int sent = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (this.plugin.sendControlJson(p, json)) sent++;
        }
        this.plugin.getLogger().info("下发屏幕几何 " + screen.name + " → " + sent + " 个客户端");
    }

    /** 只发给一个人（玩家进服时补投：他不在的时候建的屏，几何与锚点都要补）。 */
    void sendTo(Player player, Screen screen) {
        if (screen.corners.size() == 4) {
            this.plugin.sendControlJson(player, jsonPlace(screen.name, worldOf(screen), screen.corners));
        }
        final String url = screen.currentUrl();
        if (!url.isEmpty()) {
            this.plugin.sendControlJson(player,
                    jsonAnchor(screen.name, url, screen.positionMs, screen.paused, false));
        }
    }

    private static String worldOf(Screen screen) {
        if (screen.corners.isEmpty()) return "minecraft:overworld";
        final Location first = screen.corners.getFirst();
        return first.getWorld() == null ? "minecraft:overworld" : first.getWorld().getName();
    }

    /** 下发进度锚点（换源 / seek / 暂停 / 播放列表推进）。 */
    void broadcastAnchor(Screen screen) {
        final String url = screen.currentUrl();
        if (url.isEmpty() || screen.corners.isEmpty()) return;
        final String json = jsonAnchor(screen.name, url, screen.positionMs, screen.paused, false);
        int sent = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (this.plugin.sendControlJson(p, json)) sent++;
        }
        this.plugin.getLogger().info("下发锚点 " + screen.name + " → " + CineGateStub.safeUrl(url)
                + " @" + screen.positionMs + "ms → " + sent + " 个客户端");
    }

    /** 客户端回执 ENDED 时推进播放列表（同一条目 3 秒内只推进一次）。 */
    synchronized void noteEnded(String slot) {
        final Screen screen = get(slot);
        if (screen == null || screen.playlist.size() < 2) return;
        final long now = System.currentTimeMillis();
        if (now - screen.lastAdvanceAt < 3000L) return;
        screen.lastAdvanceAt = now;
        screen.positionMs = 0L;
        screen.paused = false;
        screen.nextUrl();
        save();
        broadcastAnchor(screen);
        this.plugin.getLogger().info("播放列表推进 → " + screen.name + " 第 " + (screen.index + 1) + " 项");
    }

    // =====================================================================
    // JSON（自己拼，见 ControlMessage 的说明）
    // =====================================================================

    private static String jsonPlace(String slot, String world, List<Location> corners) {
        final ControlMessage m = ControlMessage.of("place", slot).world(world);
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

    private static String jsonAnchor(String slot, String url, long positionMs, boolean paused, boolean live) {
        return ControlMessage.of("anchor", slot)
                .url(url)
                .positionMs(positionMs)
                .paused(paused)
                .live(live)
                .serverTimeMs(System.currentTimeMillis())
                .json();
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    /** 只为了日志好看：URL 太长/带凭证时截断。 */
    private static final class CineGateStub {
        static String safeUrl(String url) {
            if (url == null) return "null";
            final int q = url.indexOf('?');
            final String head = q < 0 ? url : url.substring(0, q);
            return head.length() > 80 ? head.substring(0, 80) + "…" : head;
        }
    }
}

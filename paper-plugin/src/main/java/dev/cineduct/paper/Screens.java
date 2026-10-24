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

        /** 锚点：某个时刻应播到 positionMs。 */
        long positionMs;
        long anchorWallMs;
        boolean paused;
        boolean live;

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
            screen.nextUrl();
            screen.positionMs = 0L;
            screen.anchorWallMs = System.currentTimeMillis();
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
        final String json = jsonPlace(screen.name, worldOf(screen), screen.corners);
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
        final String json = jsonAnchor(screen.name, url, screen.currentPositionMs(),
                screen.paused, screen.live);
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
            this.plugin.sendControlJson(player, jsonPlace(screen.name, worldOf(screen), screen.corners));
        }
        final String url = screen.currentUrl();
        if (!url.isEmpty()) {
            this.plugin.sendControlJson(player,
                    jsonAnchor(screen.name, url, screen.currentPositionMs(), screen.paused, screen.live));
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

    /** 日志里不打印查询串（可能带凭证）。 */
    static String safeUrl(String url) {
        if (url == null) return "null";
        final int q = url.indexOf('?');
        final String head = q < 0 ? url : url.substring(0, q);
        return head.length() > 80 ? head.substring(0, 80) + "…" : head;
    }
}

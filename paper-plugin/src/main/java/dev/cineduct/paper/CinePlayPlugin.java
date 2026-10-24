package dev.cineduct.paper;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CinePlay：用服务端指令控制**装了 CineDuct 模组**的客户端播放。
 *
 * <p>职责很薄：拼 JSON → 走 Bukkit 插件消息广播 → 自己维护进度。所有播放语义都在客户端
 * （模组的 {@code dev.mediafix.net}），协议见 {@code docs/paper-control-payload.md}。
 *
 * <p><b>单向通道</b>：服务端只广播，客户端**不回传任何数据**。播放进度由服务端用
 * "锚点 + 流逝时间"自己算（{@link Screens.Screen#currentPositionMs()}），于是不存在
 * "客户端回报不可信/被伪造"这类问题；代价是自动推进要靠每项的时长（{@code durations}）。
 *
 * <p><b>不需要模组的 jar</b>：编译期只依赖 Paper API。
 */
public final class CinePlayPlugin extends JavaPlugin {

    /** 下行：服务端 → 客户端。 */
    public static final String CHANNEL_CONTROL = "cineduct:control";

    /** 单包上限：Bukkit 自定义通道约 32 KB；客户端那边也按 30 KB 校验。 */
    private static final int MAX_BYTES = 30 * 1024;

    /** 每个玩家"当前操作的 slot/屏幕"（`/cineplay slot` 改它）。 */
    private final Map<UUID, String> slots = new ConcurrentHashMap<>();
    /** 服务端序号（客户端原样回显在日志里，便于对账）。 */
    private final AtomicLong seq = new AtomicLong();
    /** `play` 的冷却，防止手滑连点把客户端刷成反复重开。 */
    private final Map<UUID, Long> lastPlayAt = new ConcurrentHashMap<>();

    private CinePlayCommands commands;
    private DurationLookup durations;
    private Screens screens;
    private Selector selector;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL_CONTROL);

        this.durations = new DurationLookup(this);
        this.screens = new Screens(this);
        this.screens.load();
        this.selector = new Selector(this);
        getServer().getPluginManager().registerEvents(this.selector, this);

        this.commands = new CinePlayCommands(this);
        // Paper 插件的官方注册方式：/reload 之后会自动重新注册，不用手工处理
        getLifecycleManager().registerEventHandler(
                io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents.COMMANDS,
                event -> this.commands.register(event.registrar()));

        /* 每秒：按"每项时长"推进播放列表（服务端自己算位置，不依赖客户端回报）。 */
        getServer().getScheduler().runTaskTimer(this, () -> this.screens.tickPlaylist(), 20L, 20L);
        /* 每 20 秒：把正在播的屏固化成新锚点并落盘（进度随时间走，不能一变就写盘）。 */
        getServer().getScheduler().runTaskTimer(this, () -> this.screens.autosave(), 400L, 400L);

        getLogger().info("CinePlay 已启用：下行 " + CHANNEL_CONTROL
                + "（单向；屏幕数据存 plugins/CinePlay/screens.yml）");
    }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, CHANNEL_CONTROL);
        if (this.screens != null) this.screens.save();
        this.slots.clear();
        this.lastPlayAt.clear();
    }

    // =====================================================================
    // 发送
    // =====================================================================

    /** 给一个玩家发一条控制报文。 */
    boolean sendControl(Player player, ControlMessage message) {
        return message != null && sendControlJson(player, message.json());
    }

    /** 发送一段已经拼好的 JSON（几何/锚点字段多且结构固定，由 {@link Screens} 拼）。 */
    boolean sendControlJson(Player player, String json) {
        if (player == null || json == null) return false;
        final byte[] data = json.getBytes(StandardCharsets.UTF_8);
        if (data.length > MAX_BYTES) {
            getLogger().warning("报文过大（" + data.length + " 字节），已丢弃");
            return false;
        }
        /*
         * 默认不检查客户端有没有通告通道：NeoForge 客户端是否通过 Bukkit 的 register 机制
         * 把通道报上来，各版本行为不一致；拿它当硬门禁会出现"客户端装了也发不出去"。
         * 发给没人监听的通道对原版客户端无副作用。
         */
        if (isRequireChannel() && !player.getListeningPluginChannels().contains(CHANNEL_CONTROL)) return false;
        player.sendPluginMessage(this, CHANNEL_CONTROL, data);
        return true;
    }

    /** 让所有客户端把某个 slot 关掉（删屏幕时用）。 */
    void broadcastClose(String slot) {
        final String json = ControlMessage.of("close", slot).seq(nextSeq()).token(token()).json();
        for (Player p : getServer().getOnlinePlayers()) sendControlJson(p, json);
    }

    long nextSeq() {
        return this.seq.incrementAndGet();
    }

    // =====================================================================
    // 配置
    // =====================================================================

    String token() {
        return getConfig().getString("token", "");
    }

    String defaultSlot() {
        final String s = getConfig().getString("defaultSlot", "main");
        return s == null || s.isBlank() ? "main" : s;
    }

    boolean isRequireChannel() {
        return getConfig().getBoolean("requireChannel", false);
    }

    long playCooldownMs() {
        return Math.max(0L, getConfig().getLong("playCooldownMs", 1500L));
    }

    /** 某个玩家"当前操作的 slot"（`/cineplay slot` 改过就是那个，否则用配置默认）。 */
    String slotOf(Player player) {
        if (player == null) return defaultSlot();
        final String s = this.slots.get(player.getUniqueId());
        return s == null || s.isBlank() ? defaultSlot() : s;
    }

    void setSlot(Player player, String slot) {
        if (player == null) return;
        this.slots.put(player.getUniqueId(), slot);
    }

    boolean playCoolingDown(Player player) {
        final long cooldown = playCooldownMs();
        if (cooldown <= 0 || player == null) return false;
        final long now = System.currentTimeMillis();
        final Long last = this.lastPlayAt.get(player.getUniqueId());
        if (last != null && now - last < cooldown) return true;
        this.lastPlayAt.put(player.getUniqueId(), now);
        return false;
    }

    void reloadAll() {
        reloadConfig();
        this.commands.reload();
    }

    // =====================================================================
    // 配件
    // =====================================================================

    DurationLookup durations() {
        return this.durations;
    }

    Screens screens() {
        return this.screens;
    }

    Selector selector() {
        return this.selector;
    }

    /** 一句话诊断：这个玩家能不能收到控制。 */
    String channelHint(Player player) {
        if (player == null) return "玩家不在线";
        final boolean listening = player.getListeningPluginChannels().contains(CHANNEL_CONTROL);
        if (listening) return "客户端已通告 " + CHANNEL_CONTROL;
        return isRequireChannel()
                ? "客户端没有通告 " + CHANNEL_CONTROL + "（requireChannel=true，因此不会发送）"
                : "客户端没有通告 " + CHANNEL_CONTROL + "（仍会发送；没装模组就没人处理）";
    }
}

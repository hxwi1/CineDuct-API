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
 * <p>职责刻意很薄：拼 JSON → 走 Bukkit 插件消息发出去 → 把客户端回执存成
 * {@link StateSnapshot}。所有播放语义都在客户端（模组的 {@code dev.mediafix.net}），
 * 协议见 {@code docs/paper-control-payload.md}。
 *
 * <p><b>不需要模组的 jar</b>：两条通道都是普通插件消息，编译期只依赖 Paper API。
 *
 * <p>指令注册走 `LifecycleEvents.COMMANDS`（Paper 插件的官方做法，
 * 见 docs.papermc.io/paper/dev/command-api），因此 {@code paper-plugin.yml} 里**没有**
 * {@code commands:} 段。
 */
public final class CinePlayPlugin extends JavaPlugin {

    /** 下行：服务端 → 客户端。 */
    public static final String CHANNEL_CONTROL = "cineduct:control";
    /** 上行：客户端 → 服务端（状态跳变 + 每 2 秒心跳）。 */
    public static final String CHANNEL_STATE = "cineduct:state";

    /** 单包上限：Bukkit 自定义通道约 32 KB；客户端那边也按 30 KB 校验。 */
    private static final int MAX_BYTES = 30 * 1024;

    /** 每个玩家最近一条回执（解析后）。 */
    private final Map<UUID, StateSnapshot> states = new ConcurrentHashMap<>();
    private final Map<UUID, Long> stateAt = new ConcurrentHashMap<>();
    /** 每个玩家"当前操作的 slot"（`/cineplay slot` 改它）。 */
    private final Map<UUID, String> slots = new ConcurrentHashMap<>();
    /** 服务端序号：客户端回执里的 ack 就是它。 */
    private final AtomicLong seq = new AtomicLong();
    /** `play` 的冷却，防止手滑连点把客户端刷成反复重开。 */
    private final Map<UUID, Long> lastPlayAt = new ConcurrentHashMap<>();

    private CinePlayCommands commands;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL_CONTROL);
        getServer().getMessenger().registerIncomingPluginChannel(this, CHANNEL_STATE, this::onStateMessage);

        this.commands = new CinePlayCommands(this);
        // Paper 插件的官方注册方式：/reload 之后会自动重新注册，不用手工处理
        getLifecycleManager().registerEventHandler(
                io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents.COMMANDS,
                event -> this.commands.register(event.registrar()));

        getLogger().info("CinePlay 已启用：下行 " + CHANNEL_CONTROL + "，上行 " + CHANNEL_STATE
                + "（客户端需要在 cineduct-packet.json 里打开闸门）");
    }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, CHANNEL_CONTROL);
        getServer().getMessenger().unregisterIncomingPluginChannel(this, CHANNEL_STATE);
        this.states.clear();
        this.stateAt.clear();
        this.slots.clear();
        this.lastPlayAt.clear();
    }

    // =====================================================================
    // 发送
    // =====================================================================

    /**
     * 给一个玩家发一条控制报文。
     *
     * @return true = 已交给网络层（不代表客户端接受；接受与否看回执）
     */
    boolean sendControl(Player player, ControlMessage message) {
        if (player == null || message == null) return false;
        final byte[] data = message.json().getBytes(StandardCharsets.UTF_8);
        if (data.length > MAX_BYTES) {
            getLogger().warning("报文过大（" + data.length + " 字节），已丢弃：" + message.op());
            return false;
        }
        /*
         * 是否要求客户端先通告监听这个通道。
         *
         * 默认 false（见 config.yml）：NeoForge 客户端会不会通过 Bukkit 的 register 机制
         * 把通道报上来，各版本行为不一致；拿它当硬门禁会出现"客户端明明装了却发不出去"。
         * 发给没人监听的通道对原版客户端无副作用（服务端只会在日志里看到一条 ignored）。
         */
        if (isRequireChannel() && !player.getListeningPluginChannels().contains(CHANNEL_CONTROL)) {
            return false;
        }
        player.sendPluginMessage(this, CHANNEL_CONTROL, data);
        return true;
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

    boolean logState() {
        return getConfig().getBoolean("logState", true);
    }

    /** `play` 的最小间隔（毫秒）；0 = 不限制。 */
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

    /** 重载配置（`/cineplay reload`）。 */
    void reloadAll() {
        reloadConfig();
        this.commands.reload();
    }

    // =====================================================================
    // 上行回执
    // =====================================================================

    private void onStateMessage(String channel, Player player, byte[] data) {
        if (player == null || data == null || !CHANNEL_STATE.equals(channel)) return;
        final StateSnapshot snapshot = StateSnapshot.parse(new String(data, StandardCharsets.UTF_8));
        this.states.put(player.getUniqueId(), snapshot);
        this.stateAt.put(player.getUniqueId(), System.currentTimeMillis());
        if (logState()) {
            final StringBuilder sb = new StringBuilder("[state] ").append(player.getName());
            if (snapshot.err() != null && !snapshot.err().isEmpty()) sb.append("  err=").append(snapshot.err());
            for (StateSnapshot.Slot s : snapshot.slots()) {
                sb.append("  ").append(s.slot()).append('=').append(s.state())
                        .append('@').append(s.positionMs()).append("ms");
            }
            getLogger().info(sb.toString());
        }
    }

    StateSnapshot stateOf(Player player) {
        return player == null ? null : this.states.get(player.getUniqueId());
    }

    long stateAgeMs(Player player) {
        final Long at = player == null ? null : this.stateAt.get(player.getUniqueId());
        return at == null ? -1L : System.currentTimeMillis() - at;
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

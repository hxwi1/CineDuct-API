package dev.cineduct.paper;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CinePlay：用服务端指令控制**装了 CineDuct 模组**的客户端播放。
 *
 * <p>实现刻意做得很薄 —— 它只负责三件事：拼 JSON、走 Bukkit 插件消息发出去、把客户端回执存下来。
 * 播放语义全在客户端（模组的 {@code dev.mediafix.net} 那套），协议见
 * {@code docs/paper-control-payload.md}。
 *
 * <p><b>不需要模组的 jar</b>：两条通道都是普通插件消息，编译期只依赖 Paper API。
 */
public final class CinePlayPlugin extends JavaPlugin {

    /** 下行：服务端 → 客户端。 */
    public static final String CHANNEL_CONTROL = "cineduct:control";
    /** 上行：客户端 → 服务端（客户端每 2 秒心跳一次，状态跳变时也会发）。 */
    public static final String CHANNEL_STATE = "cineduct:state";

    /** 单包上限（Bukkit 自定义通道约 32 KB，客户端那边也按 30 KB 校验）。 */
    private static final int MAX_BYTES = 30 * 1024;

    /** 每个玩家最近一条回执（原样 JSON，`/cineplay status` 用）。 */
    private final Map<UUID, String> lastState = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastStateAt = new ConcurrentHashMap<>();
    /** 服务端序号：客户端回执里的 ack 就是它。 */
    private final AtomicLong seq = new AtomicLong();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL_CONTROL);
        getServer().getMessenger().registerIncomingPluginChannel(this, CHANNEL_STATE, this::onStateMessage);

        final CinePlayCommand executor = new CinePlayCommand(this);
        final var command = getCommand("cineplay");
        if (command == null) {
            getLogger().severe("plugin.yml 里没有 cineplay 指令，插件未正确加载");
            return;
        }
        command.setExecutor(executor);
        command.setTabCompleter(executor);

        getLogger().info("CinePlay 已启用：下行 " + CHANNEL_CONTROL + "，上行 " + CHANNEL_STATE
                + "（客户端闸门需要在其 cineduct-packet.json 里打开）");
    }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, CHANNEL_CONTROL);
        getServer().getMessenger().unregisterIncomingPluginChannel(this, CHANNEL_STATE);
        this.lastState.clear();
        this.lastStateAt.clear();
    }

    // =====================================================================
    // 发送
    // =====================================================================

    /**
     * 给一个玩家发一条控制报文。
     *
     * @return true = 已发出（不代表客户端接受了；接受与否看回执）
     */
    public boolean sendControl(Player player, ControlMessage message) {
        if (player == null || message == null) return false;
        final byte[] data = message.json().getBytes(StandardCharsets.UTF_8);
        if (data.length > MAX_BYTES) {
            getLogger().warning("报文过大（" + data.length + " 字节），已丢弃：" + message.op());
            return false;
        }
        /*
         * 是否要求客户端先通告监听这个通道。
         *
         * 默认 false（见 config.yml 的 requireChannel）：NeoForge 客户端会不会通过 Bukkit 的
         * register 机制把通道报上来，各版本行为不一致；拿它当硬门禁会出现"客户端明明装了却发不出去"。
         * 发给没人监听的通道对原版客户端无副作用。
         */
        if (isRequireChannel() && !player.getListeningPluginChannels().contains(CHANNEL_CONTROL)) {
            return false;
        }
        player.sendPluginMessage(this, CHANNEL_CONTROL, data);
        return true;
    }

    /** 广播给所有在线玩家，返回成功发出的数量。 */
    public int broadcastControl(ControlMessage message) {
        int sent = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (sendControl(p, message)) sent++;
        }
        return sent;
    }

    /** 下一次发送用的序号（单调递增）。 */
    public long nextSeq() {
        return this.seq.incrementAndGet();
    }

    public String token() {
        return getConfig().getString("token", "");
    }

    public String defaultSlot() {
        final String s = getConfig().getString("defaultSlot", "main");
        return s == null || s.isBlank() ? "main" : s;
    }

    public boolean isRequireChannel() {
        return getConfig().getBoolean("requireChannel", false);
    }

    // =====================================================================
    // 上行回执
    // =====================================================================

    private void onStateMessage(String channel, Player player, byte[] data) {
        if (player == null || data == null) return;
        final String json = new String(data, StandardCharsets.UTF_8);
        this.lastState.put(player.getUniqueId(), json);
        this.lastStateAt.put(player.getUniqueId(), System.currentTimeMillis());
        if (getConfig().getBoolean("logState", true)) {
            getLogger().info("[state] " + player.getName() + " → " + truncate(json));
        }
    }

    /** 某个玩家最近一条回执（没有则 null）。 */
    public String lastState(Player player) {
        return player == null ? null : this.lastState.get(player.getUniqueId());
    }

    public long lastStateAgeMs(Player player) {
        final Long at = player == null ? null : this.lastStateAt.get(player.getUniqueId());
        return at == null ? -1L : System.currentTimeMillis() - at;
    }

    /** 一句话诊断：这个玩家能不能收控制。 */
    public String channelHint(Player player) {
        if (player == null) return "玩家不在线";
        final boolean listening = player.getListeningPluginChannels().contains(CHANNEL_CONTROL);
        if (listening) return "客户端已通告 " + CHANNEL_CONTROL;
        return isRequireChannel()
                ? "客户端没有通告 " + CHANNEL_CONTROL + "（requireChannel=true，不会发送）"
                : "客户端没有通告 " + CHANNEL_CONTROL + "（仍会发送；没装模组就没人处理）";
    }

    private static String truncate(String s) {
        return s.length() <= 300 ? s : s.substring(0, 300) + "…";
    }

    /** 供指令层拼人类可读的地址（回执里没有名字，只用于日志）。 */
    static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}

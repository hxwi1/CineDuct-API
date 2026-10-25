// SPDX-License-Identifier: MIT
// Copyright (c) 2026 hxwi1
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
    /** 上行：客户端起播时上报一次实际时长（唯一的上行数据）。 */
    public static final String CHANNEL_STATE = "cineduct:state";

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
    private PasteWizard pasteWizard;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL_CONTROL);
        getServer().getMessenger().registerIncomingPluginChannel(this, CHANNEL_STATE, this::onDurationReport);

        this.durations = new DurationLookup(this);
        this.screens = new Screens(this);
        this.screens.load();
        this.selector = new Selector(this);
        getServer().getPluginManager().registerEvents(this.selector, this);
        this.pasteWizard = new PasteWizard(this);
        getServer().getPluginManager().registerEvents(this.pasteWizard, this);

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
        getServer().getMessenger().unregisterIncomingPluginChannel(this, CHANNEL_STATE);
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

    /**
     * 上行：客户端起播时报告一次实际时长（{@code {"op":"duration","slot":…,"url":…,"durationMs":…}}）。
     *
     * <p>这是**唯一**的上行数据，也是自动推进的依据：时长只有真放起来的客户端才知道，
     * 而且它天然带着"非大会员只能试看 6 分钟"这类截断 —— 插件查 API 只能拿到总时长，算不对。
     */
    private void onDurationReport(String channel, Player player, byte[] data) {
        if (player == null || data == null || !CHANNEL_STATE.equals(channel)) return;
        try {
            final com.google.gson.JsonElement root = com.google.gson.JsonParser.parseString(
                    new String(data, StandardCharsets.UTF_8));
            if (root == null || !root.isJsonObject()) return;
            final com.google.gson.JsonObject o = root.getAsJsonObject();
            if (!o.has("op") || !"duration".equals(o.get("op").getAsString())) return;
            final String slot = o.has("slot") ? o.get("slot").getAsString() : "";
            final String url = o.has("url") ? o.get("url").getAsString() : "";
            final long durationMs = o.has("durationMs") ? o.get("durationMs").getAsLong() : 0L;
            final boolean live = o.has("live") && o.get("live").getAsBoolean();
            if (live || durationMs <= 0) {
                getLogger().info(player.getName() + " 上报 " + slot + " 是直播/时长未知 → 不参与自动推进");
                return;
            }
            final int seconds = (int) Math.min(Integer.MAX_VALUE, durationMs / 1000L);
            if (this.screens.setDurationByUrl(slot, url, seconds)) {
                getLogger().info(player.getName() + " 上报时长：" + slot + " 「" + Screens.safeUrl(url)
                        + "」= " + seconds + "s（自动推进按这个走）");
            }
        } catch (Throwable t) {
            getLogger().warning("解析时长上报失败: " + t.getMessage());
        }
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

    /**
     * 需要大会员的付费内容，非大会员能试看的秒数（B 站通常 6 分钟 = 360）。
     *
     * <p>为什么要有这个数：服务端**无从知道**每个客户端是不是大会员（各人账号的事），
     * 而试看到的画面会在试看结束时停住。默认按试看时长截断，避免"画面停了服务端还在等"。
     * 设成 0 = 不截断（一律按总时长，适合全服都是大会员的情况）。
     */
    int premiumPreviewSeconds() {
        return Math.max(0, getConfig().getInt("premiumPreviewSeconds", 360));
    }

    /** 菜单里"附近"的范围（格）：超出这个距离的屏只在拥有者视角下显示。 */
    int menuRange() {
        return Math.max(8, getConfig().getInt("menuRange", 64));
    }

    /** 播完最后一项后是否回到开头继续循环（默认 false：清空队列，让"播完"这件事看得见）。 */
    boolean playlistLoop() {
        return getConfig().getBoolean("playlistLoop", false);
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

    PasteWizard pasteWizard() {
        return this.pasteWizard;
    }

    /** 聊天栏里填的内容走这里（由 PasteWizard 在主线程回调）。 */
    void applyChatInput(Player player, String screen, int kindOrdinal, String text) {
        this.commands.applyChatInput(player, screen, PasteWizard.Kind.values()[kindOrdinal], text);
    }

    /** 菜单里点 [加片] 后，玩家粘贴的那条链接走这里（由 PasteWizard 在主线程回调）。 */
    void addFromChat(Player player, String screen, String url) {
        this.commands.addUrlFromChat(player, screen, url);
    }

    /** 粘贴向导的有效期（秒）。 */
    int pasteTimeoutSeconds() {
        return Math.max(10, getConfig().getInt("pasteTimeoutSeconds", 60));
    }

    CinePlayCommands commands() {
        return this.commands;
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

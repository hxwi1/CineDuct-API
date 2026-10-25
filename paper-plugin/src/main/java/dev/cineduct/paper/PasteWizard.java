// SPDX-License-Identifier: MIT
// Copyright (c) 2026 hxwi1
package dev.cineduct.paper;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * "聊天栏输入向导"：点菜单里的按钮，然后**把参数直接发到聊天栏**，不用敲指令。
 *
 * <p>不只是加片：音量、范围、授权都能这么填。菜单里凡是"还需要补一个参数"的按钮，
 * 点完之后就进入这里，下一条消息被当作那个参数。
 *
 * <p>三条约束（都是"别碍事"）：
 * <ul>
 *   <li><b>有时限</b>：默认 60 秒（{@code pasteTimeoutSeconds}），过期自动失效；</li>
 *   <li><b>能取消</b>：输入 {@code cancel} / {@code 取消} 立刻退出；</li>
 *   <li><b>不吞正常聊天</b>：内容解析不出来就把状态清掉、**这条消息照常发出**
 *       （宁可让玩家重按一次按钮，也不要把他打的字吃掉）。</li>
 * </ul>
 *
 * <p>用 {@link AsyncChatEvent}（Paper 的现代聊天事件）：异步线程里只做解析判断，
 * 真正改服务端状态的部分通过 {@code runTask} 回主线程。
 */
final class PasteWizard implements Listener {

    /** 这次要玩家在聊天栏里填什么。 */
    enum Kind {
        /** 播放源链接。 */
        URL,
        /** 音量 0~100。 */
        VOLUME,
        /** 起播距离（格）。 */
        RANGE,
        /** 管理员（能删/能授权）。 */
        ADMIN,
        /** 信任者（能操控播放，不能删）。 */
        TRUST
    }

    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBER = Pattern.compile("\\d{1,4}");

    private record Entry(String screen, Kind kind, long expiresAt) {
    }

    private final CinePlayPlugin plugin;
    private final Map<UUID, Entry> pending = new ConcurrentHashMap<>();

    PasteWizard(CinePlayPlugin plugin) {
        this.plugin = plugin;
    }

    /** 给玩家挂上"下一条消息当作某个参数"的状态。 */
    void arm(Player player, String screen, Kind kind) {
        final long ttl = Math.max(10, this.plugin.pasteTimeoutSeconds()) * 1000L;
        this.pending.put(player.getUniqueId(), new Entry(screen, kind, System.currentTimeMillis() + ttl));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        final Player player = event.getPlayer();
        final Entry entry = this.pending.get(player.getUniqueId());
        if (entry == null) return;

        if (System.currentTimeMillis() > entry.expiresAt()) {
            this.pending.remove(player.getUniqueId());   // 过期：静默清掉，别打扰聊天
            return;
        }

        final String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        if (text.equalsIgnoreCase("cancel") || text.equals("取消")) {
            event.setCancelled(true);
            this.pending.remove(player.getUniqueId());
            this.plugin.getServer().getScheduler().runTask(this.plugin,
                    () -> Messages.note(player, "已取消这次输入"));
            return;
        }

        // 先判断内容对不对；不对就清状态并放行（不吃玩家打的字）
        final String problem = validate(entry.kind(), text);
        if (problem != null) {
            this.pending.remove(player.getUniqueId());
            this.plugin.getServer().getScheduler().runTask(this.plugin,
                    () -> Messages.warn(player, problem + "（已退出输入模式，这条消息照常发出）"));
            return;
        }

        event.setCancelled(true);
        this.pending.remove(player.getUniqueId());
        final Kind kind = entry.kind();
        final String screen = entry.screen();
        this.plugin.getServer().getScheduler().runTask(this.plugin,
                () -> this.plugin.applyChatInput(player, screen, kind.ordinal(), text));
    }

    /** @return null = 内容可用；否则是给玩家看的原因。 */
    private static String validate(Kind kind, String text) {
        return switch (kind) {
            case URL -> URL_PATTERN.matcher(text).find() ? null : "没识别到链接";
            case VOLUME -> NUMBER.matcher(text).matches() && Integer.parseInt(text) <= 100
                    ? null : "音量要填 0~100 的数字";
            case RANGE -> NUMBER.matcher(text).matches() ? null : "范围要填格数（数字）";
            case ADMIN, TRUST -> text.matches("[A-Za-z0-9_]{3,16}") ? null : "请填玩家名（3~16 位英文/数字/下划线）";
        };
    }

    /** 玩家退服时清掉状态，免得 UUID 复用时误触发。 */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        this.pending.remove(event.getPlayer().getUniqueId());
    }
}

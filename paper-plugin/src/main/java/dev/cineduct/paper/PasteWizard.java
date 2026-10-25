package dev.cineduct.paper;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * "粘贴一次链接"向导：点菜单里的 {@code [加片]} → 下一条聊天内容被当作播放源，不用敲指令。
 *
 * <p>为什么这么做：`/cineplay add main <链接>` 要玩家手打指令再粘链接，实际用起来很别扭
 * （粘贴到指令里容易多带空格/被自动补全打断）。让玩家"点一下按钮、粘一次链接、回车"最接近本能。
 *
 * <p>三条约束（都是"别碍事"）：
 * <ul>
 *   <li><b>有时限</b>：默认 60 秒，过期自动失效，不会一直吞聊天；</li>
 *   <li><b>能取消</b>：输入 {@code cancel} / {@code 取消} 立刻退出；</li>
 *   <li><b>不吞正常聊天</b>：只有像链接的内容才会被拦截；不像链接就把状态清掉、让这条消息
 *       照常发出去（宁可让玩家重按一次按钮，也不要把他打的字吃掉）。</li>
 * </ul>
 *
 * <p>用的是 {@link AsyncChatEvent}（Paper 的现代聊天事件），所以**异步线程**里只有判断，
 * 真正改服务端状态的部分通过 {@code runTask} 回到主线程。
 */
final class PasteWizard implements Listener {

    /** 只看"像不像链接"，不做严格 URL 校验（BV 短链、直播链接都要放过）。 */
    private static final Pattern URL = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);

    private record Entry(String screen, long expiresAt) {
    }

    private final CinePlayPlugin plugin;
    private final Map<UUID, Entry> pending = new ConcurrentHashMap<>();

    PasteWizard(CinePlayPlugin plugin) {
        this.plugin = plugin;
    }

    /** 给玩家挂上"下一条消息当作链接"的状态。 */
    void arm(Player player, String screen) {
        final long ttl = Math.max(10, this.plugin.pasteTimeoutSeconds()) * 1000L;
        this.pending.put(player.getUniqueId(), new Entry(screen, System.currentTimeMillis() + ttl));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        final Player player = event.getPlayer();
        final Entry entry = this.pending.get(player.getUniqueId());
        if (entry == null) return;

        // 过期：静默清掉，别打扰聊天
        if (System.currentTimeMillis() > entry.expiresAt()) {
            this.pending.remove(player.getUniqueId());
            return;
        }

        final String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        if (text.equalsIgnoreCase("cancel") || text.equals("取消")) {
            event.setCancelled(true);
            this.pending.remove(player.getUniqueId());
            this.plugin.getServer().getScheduler().runTask(this.plugin,
                    () -> Messages.note(player, "已取消这次粘贴"));
            return;
        }

        if (!URL.matcher(text).find()) {
            /*
             * 不像链接：清掉状态，**不拦截**，让这条消息正常发出去。
             * 宁可让玩家重按一次 [加片]，也不要吃他打的字。
             */
            this.pending.remove(player.getUniqueId());
            this.plugin.getServer().getScheduler().runTask(this.plugin,
                    () -> Messages.warn(player, "没识别到链接，已退出粘贴模式（这条消息照常发出）"));
            return;
        }

        event.setCancelled(true);
        this.pending.remove(player.getUniqueId());
        final String url = text;
        final String screen = entry.screen();
        this.plugin.getServer().getScheduler().runTask(this.plugin,
                () -> this.plugin.addFromChat(player, screen, url));
    }

    /** 玩家退服时清掉状态，免得 UUID 复用时误触发。 */
    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        this.pending.remove(event.getPlayer().getUniqueId());
    }
}

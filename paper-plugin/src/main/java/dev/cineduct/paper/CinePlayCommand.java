package dev.cineduct.paper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /cineplay} 的全部子指令。
 *
 * <p>设计取向：**默认只控制自己**（{@code play} / {@code pause} …），想让所有人一起看用
 * {@code broadcast}。这样"管理员手滑广播一条 open"不会变成全服意外开播。
 *
 * <p>所有子指令都不阻塞：发出去就返回，结果看 {@code /cineplay status} 或服务器日志里的回执。
 */
public final class CinePlayCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of(
            "play", "broadcast", "pause", "resume", "stop", "seek", "volume", "aspect",
            "rebuild", "list", "status", "help");

    private final CinePlayPlugin plugin;

    public CinePlayCommand(CinePlayPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String[] args) {
        if (args.length == 0) {
            help(sender, label);
            return true;
        }
        final String sub = args[0].toLowerCase(Locale.ROOT);
        try {
            switch (sub) {
                case "help" -> help(sender, label);
                case "play" -> play(sender, label, args, false);
                case "broadcast" -> play(sender, label, args, true);
                case "pause" -> simple(sender, args, "pause", m -> m.paused(true));
                case "resume" -> simple(sender, args, "pause", m -> m.paused(false));
                case "stop" -> simple(sender, args, "close", m -> m);
                case "rebuild" -> simple(sender, args, "rebuild", m -> m);
                case "list" -> simple(sender, args, "list", m -> m);
                case "seek" -> seek(sender, label, args);
                case "volume" -> volume(sender, label, args);
                case "aspect" -> aspect(sender, label, args);
                case "status" -> status(sender, args);
                default -> {
                    error(sender, "未知子指令: " + sub + "（用 /" + label + " help）");
                }
            }
        } catch (NumberFormatException e) {
            error(sender, "数字格式不对: " + e.getMessage());
        }
        return true;
    }

    // =====================================================================
    // 子指令
    // =====================================================================

    private void play(CommandSender sender, String label, String[] args, boolean everyone) {
        if (args.length < 2) {
            error(sender, "用法: /" + label + (everyone ? " broadcast" : " play") + " <链接> [slot]");
            return;
        }
        final String url = args[1];
        final String slot = args.length >= 3 ? args[2] : this.plugin.defaultSlot();
        final ControlMessage msg = ControlMessage.of("open", slot)
                .seq(this.plugin.nextSeq())
                .token(this.plugin.token())
                .url(url);

        if (everyone) {
            final int n = this.plugin.broadcastControl(msg);
            info(sender, "已广播 open 给 " + n + " 位玩家（slot=" + slot + "）");
            return;
        }
        final Player target = self(sender);
        if (target == null) return;
        final boolean ok = this.plugin.sendControl(target, msg);
        if (ok) {
            info(sender, "已发送 open → slot=" + slot);
            detail(sender, this.plugin.channelHint(target));
        } else {
            error(sender, "发送失败（" + this.plugin.channelHint(target) + "）");
        }
    }

    /** pause/resume/stop/rebuild/list 这一类"不需要参数、只作用于自己"的子指令。 */
    private void simple(CommandSender sender, String[] args, String op,
                        java.util.function.UnaryOperator<ControlMessage> decorate) {
        final Player target = self(sender);
        if (target == null) return;
        final String slot = args.length >= 2 ? args[1] : this.plugin.defaultSlot();
        final ControlMessage msg = decorate.apply(ControlMessage.of(op, slot)
                .seq(this.plugin.nextSeq())
                .token(this.plugin.token()));
        if (this.plugin.sendControl(target, msg)) {
            info(sender, "已发送 " + op + "（slot=" + slot + "）");
        } else {
            error(sender, "发送失败（" + this.plugin.channelHint(target) + "）");
        }
    }

    private void seek(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            error(sender, "用法: /" + label + " seek <秒> [slot]");
            return;
        }
        final long ms = Math.round(Double.parseDouble(args[1]) * 1000.0);
        final Player target = self(sender);
        if (target == null) return;
        final String slot = args.length >= 3 ? args[2] : this.plugin.defaultSlot();
        final ControlMessage msg = ControlMessage.of("seek", slot)
                .seq(this.plugin.nextSeq())
                .token(this.plugin.token())
                .positionMs(ms);
        if (this.plugin.sendControl(target, msg)) {
            info(sender, "已跳转到 " + (ms / 1000.0) + "s（slot=" + slot + "）");
        } else {
            error(sender, "发送失败（" + this.plugin.channelHint(target) + "）");
        }
    }

    private void volume(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            error(sender, "用法: /" + label + " volume <0-100> [slot]");
            return;
        }
        final int percent = Integer.parseInt(args[1]);
        final Player target = self(sender);
        if (target == null) return;
        final String slot = args.length >= 3 ? args[2] : this.plugin.defaultSlot();
        final ControlMessage msg = ControlMessage.of("volume", slot)
                .seq(this.plugin.nextSeq())
                .token(this.plugin.token())
                .volume(percent);
        if (this.plugin.sendControl(target, msg)) {
            info(sender, "音量 → " + percent + "%（slot=" + slot + "）");
        } else {
            error(sender, "发送失败（" + this.plugin.channelHint(target) + "）");
        }
    }

    private void aspect(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            error(sender, "用法: /" + label + " aspect <宽高比> [slot]   （16:9 传 1.7778；0 = 关闭）");
            return;
        }
        final float aspect = Float.parseFloat(args[1]);
        final Player target = self(sender);
        if (target == null) return;
        final String slot = args.length >= 3 ? args[2] : this.plugin.defaultSlot();
        final ControlMessage msg = ControlMessage.of("aspect", slot)
                .seq(this.plugin.nextSeq())
                .token(this.plugin.token())
                .aspect(aspect);
        if (this.plugin.sendControl(target, msg)) {
            info(sender, "显示面比例 → " + aspect + "（slot=" + slot + "）");
        } else {
            error(sender, "发送失败（" + this.plugin.channelHint(target) + "）");
        }
    }

    private void status(CommandSender sender, String[] args) {
        final Player target;
        if (args.length >= 2) {
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                error(sender, "找不到在线玩家: " + args[1]);
                return;
            }
        } else {
            target = self(sender);
            if (target == null) return;
        }
        info(sender, "玩家 " + target.getName() + "：" + this.plugin.channelHint(target));
        final String state = this.plugin.lastState(target);
        if (state == null) {
            detail(sender, "还没有收到回执（客户端闸门没开、或没装模组、或还没播过）");
            return;
        }
        final long age = this.plugin.lastStateAgeMs(target);
        detail(sender, "最近回执 " + (age < 0 ? "?" : (age / 1000) + "s 前") + "：" + state);
    }

    private void help(CommandSender sender, String label) {
        info(sender, "CinePlay —— 控制装了 CineDuct 模组的客户端播放");
        detail(sender, "/" + label + " play <链接> [slot]       在自己的客户端开播");
        detail(sender, "/" + label + " broadcast <链接> [slot]   给所有在线玩家开播");
        detail(sender, "/" + label + " pause|resume|stop [slot]  暂停 / 继续 / 关闭");
        detail(sender, "/" + label + " seek <秒> [slot]          跳转（直播无效）");
        detail(sender, "/" + label + " volume <0-100> [slot]     音量");
        detail(sender, "/" + label + " aspect <比例> [slot]      显示面比例（0 = 关闭信箱化）");
        detail(sender, "/" + label + " rebuild|list [slot]       重建播放 / 请求回执");
        detail(sender, "/" + label + " status [玩家]             通道与最近回执");
        detail(sender, "客户端需要在 cineduct-packet.json 里把 enabled 打开、并把你服务器的地址加进 servers");
    }

    // =====================================================================
    // 工具
    // =====================================================================

    /** 取执行者玩家；控制台执行时返回 null（并提示用 status <玩家>）。 */
    private Player self(CommandSender sender) {
        if (sender instanceof Player player) return player;
        error(sender, "这条子指令只能由玩家执行（控制台可用 /cineplay status <玩家> 或 broadcast）");
        return null;
    }

    private static void info(CommandSender to, String text) {
        to.sendMessage(Component.text(text).color(NamedTextColor.AQUA));
    }

    private static void detail(CommandSender to, String text) {
        to.sendMessage(Component.text("  " + text).color(NamedTextColor.GRAY));
    }

    private static void error(CommandSender to, String text) {
        to.sendMessage(Component.text(text).color(NamedTextColor.RED));
    }

    // =====================================================================
    // 补全
    // =====================================================================

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, String[] args) {
        final List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : SUBS) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
            }
            return out;
        }
        if (args.length == 2) {
            final String sub = args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "pause", "resume", "stop", "rebuild", "list" -> out.add(this.plugin.defaultSlot());
                case "status" -> {
                    for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
                }
                case "seek" -> out.add("60");
                case "volume" -> out.add("80");
                case "aspect" -> out.add("1.7778");
                default -> {
                }
            }
            return out;
        }
        if (args.length == 3 && List.of("seek", "volume", "aspect", "play", "broadcast")
                .contains(args[0].toLowerCase(Locale.ROOT))) {
            out.add(this.plugin.defaultSlot());
        }
        return out;
    }
}

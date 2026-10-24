package dev.cineduct.paper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.CommandSender;

/**
 * 统一的文案与配色。
 *
 * <p>集中在一处的好处：改文案不用翻指令树；也不会出现"同一个错误三种说法"。
 * 这里刻意不做 i18n 资源包 —— 这个插件的用户就是服主自己，多一层 yml 只会让改字更慢。
 */
final class Messages {

    static final String PREFIX = "[CinePlay] ";

    private Messages() {
    }

    static void ok(CommandSender to, String text) {
        to.sendMessage(Component.text(PREFIX, NamedTextColor.AQUA)
                .append(Component.text(text, NamedTextColor.WHITE)));
    }

    static void note(CommandSender to, String text) {
        to.sendMessage(Component.text("  " + text, NamedTextColor.GRAY));
    }

    static void warn(CommandSender to, String text) {
        to.sendMessage(Component.text(PREFIX, NamedTextColor.GOLD)
                .append(Component.text(text, NamedTextColor.YELLOW)));
    }

    static void error(CommandSender to, String text) {
        to.sendMessage(Component.text(PREFIX, NamedTextColor.RED)
                .append(Component.text(text, NamedTextColor.RED)));
    }

    /** 指令帮助里的一行：命令用高亮，说明用灰色。 */
    static void usage(CommandSender to, String command, String description) {
        to.sendMessage(Component.text("  " + command, NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text("  " + description, NamedTextColor.GRAY)));
    }

    static Component colored(String text, NamedTextColor color) {
        return Component.text(text, color);
    }
}

// SPDX-License-Identifier: MIT
// Copyright (c) 2026 hxwi1
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

    /**
     * 可点击的一行：点一下**直接执行**命令，悬停显示提示。
     *
     * <p>Minecraft Java 版的聊天栏支持 clickEvent / hoverEvent（Adventure 的
     * {@code ClickEvent.runCommand} / {@code HoverEvent.showText}）。用它把"下一步该敲什么"
     * 变成可以点的链接，省得玩家对着帮助手打命令还打错。
     *
     * <p>命令值要带前导斜杠（原版约定），客户端会自己剥掉再发。
     */
    static void action(CommandSender to, String text, String command, String hover) {
        to.sendMessage(Component.text("  ").append(actionComponent(text, command, hover)));
    }

    /** 同上，但返回 Component —— 用来把多个按钮拼在**同一行**。 */
    static Component actionComponent(String text, String command, String hover) {
        return Component.text(text, NamedTextColor.AQUA)
                .decoration(TextDecoration.UNDERLINED, true)
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand(command))
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                        Component.text(hover, NamedTextColor.GRAY)));
    }

    /** 可点击的一行：点一下把命令**填进输入框**（不直接执行，适合还需要改参数的）。 */
    static void suggest(CommandSender to, String text, String command, String hover) {
        to.sendMessage(Component.text("  ").append(suggestComponent(text, command, hover)));
    }

    /** 同上，但返回 Component —— 用来把多个按钮拼在**同一行**。 */
    static Component suggestComponent(String text, String command, String hover) {
        return Component.text(text, NamedTextColor.AQUA)
                .decoration(TextDecoration.UNDERLINED, true)
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.suggestCommand(command))
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                        Component.text(hover, NamedTextColor.GRAY)));
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

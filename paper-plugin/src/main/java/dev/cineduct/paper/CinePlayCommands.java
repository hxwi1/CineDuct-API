package dev.cineduct.paper;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * {@code /cineplay} 指令树（Brigadier）。
 *
 * <p>形状刻意做成两套一样的操作：
 * <pre>
 *   /cineplay &lt;op&gt; ...                 → 作用于自己
 *   /cineplay to &lt;目标&gt; &lt;op&gt; ...       → 作用于选中的人（@a、玩家名、选择器）
 * </pre>
 * 这样"只控制自己"和"广播给一片人"用同一套语义，不用额外记一个 {@code broadcast} 子指令；
 * 目标用原版选择器（{@code @a} / {@code @a[distance=..32]} / 玩家名），比自己解析名字更省事也更强。
 *
 * <p>为什么 {@code url} 用 {@code greedyString}：B 站直链带 {@code ?a=1&b=2}，
 * {@code string()} 不接受未加引号的分隔符。代价是**它必须吃到最后** ——
 * 所以 slot 不做成参数，而是用 {@code /cineplay slot <名字>} 记住"我现在操作哪个槽位"。
 */
final class CinePlayCommands {

    /** 没装模组 / 没开闸门时最常看到的提示。 */
    private static final SimpleCommandExceptionType NEED_PLAYER =
            new SimpleCommandExceptionType(() -> "这条指令只能由玩家执行（控制台请用 /cineplay to <目标> ...）");

    private static final List<String> NO_ARG_OPS = List.of("pause", "resume", "stop", "rebuild", "list", "sync");

    private final CinePlayPlugin plugin;
    /** 帮助文本缓存（`/reload` 之后重建）。 */
    private List<Component> helpCache = List.of();

    CinePlayCommands(CinePlayPlugin plugin) {
        this.plugin = plugin;
        rebuildHelp();
    }

    void reload() {
        rebuildHelp();
    }

    // =====================================================================
    // 指令树
    // =====================================================================

    void register(Commands registrar) {
        final LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("cineplay")
                .requires(src -> src.getSender().hasPermission("cineplay.use"));

        // ---- 作用于自己 ----
        for (ArgumentBuilder<CommandSourceStack, ?> node : opNodes(false)) {
            root.then(node);
        }

        // ---- 作用于选中的一批人（原版选择器）----
        final RequiredArgumentBuilder<CommandSourceStack, PlayerSelectorArgumentResolver> targetsNode =
                Commands.argument("targets", ArgumentTypes.players());
        for (ArgumentBuilder<CommandSourceStack, ?> node : opNodes(true)) {
            targetsNode.then(node);
        }
        root.then(Commands.literal("to")
                .requires(src -> src.getSender().hasPermission("cineplay.others"))
                .then(targetsNode));

        root.then(Commands.literal("slot")
                .executes(ctx -> showSlot(ctx))
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> setSlot(ctx, StringArgumentType.getString(ctx, "name")))));

        root.then(Commands.literal("status")
                .executes(ctx -> status(ctx, null))
                .then(Commands.argument("target", ArgumentTypes.player())
                        .requires(src -> src.getSender().hasPermission("cineplay.others"))
                        .executes(ctx -> status(ctx, one(ctx)))));

        root.then(Commands.literal("reload")
                .requires(src -> src.getSender().hasPermission("cineplay.admin"))
                .executes(ctx -> {
                    this.plugin.reloadAll();
                    Messages.ok(ctx.getSource().getSender(), "配置已重载");
                    return 1;
                }));

        root.then(Commands.literal("help").executes(ctx -> help(ctx)));

        registrar.register(root.build(), "控制装了 CineDuct 模组的客户端播放", List.of("cp"));
    }

    /**
     * 生成"操作"这一组节点（**每次新建**：Brigadier 的节点只能有一个父，复用会串味）。
     *
     * @param others true = 目标来自 {@code to <targets>}，false = 目标就是执行者自己
     */
    private List<ArgumentBuilder<CommandSourceStack, ?>> opNodes(boolean others) {
        final List<ArgumentBuilder<CommandSourceStack, ?>> nodes = new ArrayList<>();

        nodes.add(Commands.literal("play")
                .then(Commands.argument("url", StringArgumentType.greedyString())
                        .executes(ctx -> play(ctx, StringArgumentType.getString(ctx, "url"), others))));

        for (String op : NO_ARG_OPS) {
            final UnaryOperator<ControlMessage> decorate = switch (op) {
                case "pause" -> m -> m.paused(true);
                case "resume" -> m -> m.paused(false);
                default -> m -> m;
            };
            final String wireOp = switch (op) {
                case "resume" -> "pause";
                case "stop" -> "close";
                default -> op;
            };
            nodes.add(Commands.literal(op).executes(ctx -> simple(ctx, wireOp, decorate, others)));
        }

        nodes.add(Commands.literal("seek")
                .then(Commands.argument("seconds", DoubleArgumentType.doubleArg(0.0))
                        .executes(ctx -> {
                            final long ms = Math.round(DoubleArgumentType.getDouble(ctx, "seconds") * 1000.0);
                            return simple(ctx, "seek", m -> m.positionMs(ms), others);
                        })));

        nodes.add(Commands.literal("volume")
                .then(Commands.argument("percent", IntegerArgumentType.integer(0, 100))
                        .executes(ctx -> simple(ctx, "volume",
                                m -> m.volume(IntegerArgumentType.getInteger(ctx, "percent")), others))));

        nodes.add(Commands.literal("aspect")
                .then(Commands.argument("ratio", FloatArgumentType.floatArg(0.0f, 32.0f))
                        .executes(ctx -> simple(ctx, "aspect",
                                m -> m.aspect(FloatArgumentType.getFloat(ctx, "ratio")), others))));

        return nodes;
    }

    // =====================================================================
    // 执行
    // =====================================================================

    private int play(CommandContext<CommandSourceStack> ctx, String url, boolean others)
            throws CommandSyntaxException {
        final CommandSender sender = ctx.getSource().getSender();
        final List<Player> targets = targets(ctx, others);
        // 一条指令一个序号：同一次下发里的所有目标共用它，回执才能对上
        final long seq = this.plugin.nextSeq();

        int sent = 0;
        int cooling = 0;
        for (Player target : targets) {
            if (this.plugin.playCoolingDown(target)) {
                cooling++;
                continue;
            }
            final ControlMessage m = ControlMessage.of("open", this.plugin.slotOf(target))
                    .seq(seq)
                    .token(this.plugin.token())
                    .url(url);
            if (this.plugin.sendControl(target, m)) {
                sent++;
                if (others) Messages.note(sender, "→ " + target.getName() + "（slot=" + this.plugin.slotOf(target) + "）");
            }
        }
        report(sender, "open", sent, cooling);
        return sent;
    }

    private int simple(CommandContext<CommandSourceStack> ctx, String wireOp,
                       UnaryOperator<ControlMessage> decorate, boolean others) throws CommandSyntaxException {
        final CommandSender sender = ctx.getSource().getSender();
        final List<Player> targets = targets(ctx, others);
        int sent = 0;
        for (Player target : targets) {
            final ControlMessage m = decorate.apply(
                    ControlMessage.of(wireOp, this.plugin.slotOf(target))
                            .seq(this.plugin.nextSeq())
                            .token(this.plugin.token()));
            if (this.plugin.sendControl(target, m)) {
                sent++;
                if (others) Messages.note(sender, "→ " + target.getName() + "（" + wireOp + "）");
            }
        }
        report(sender, wireOp, sent, 0);
        return sent;
    }

    /** 统一的"发出去几条"回执；0 条时给出最可能的原因。 */
    private void report(CommandSender sender, String op, int sent, int cooling) {
        if (sent > 0) {
            Messages.ok(sender, "已发送 " + op + " → " + sent + " 个客户端"
                    + (cooling > 0 ? "（" + cooling + " 个在冷却中已跳过）" : ""));
            return;
        }
        if (cooling > 0) {
            Messages.warn(sender, "全部目标都在 play 冷却中（" + this.plugin.playCooldownMs() + "ms）");
            return;
        }
        Messages.error(sender, "没有发出去：客户端可能没装模组，或 cineduct-packet.json 里 requireChannel=true 而通道没通告");
        Messages.note(sender, "用 /cineplay status 看具体原因");
    }

    // =====================================================================
    // slot / status / help
    // =====================================================================

    private int showSlot(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        final Player player = self(ctx);
        Messages.ok(ctx.getSource().getSender(), "当前操作槽位: " + this.plugin.slotOf(player));
        Messages.note(ctx.getSource().getSender(), "用 /cineplay slot <名字> 切换（客户端按名字区分多条播放）");
        return 1;
    }

    private int setSlot(CommandContext<CommandSourceStack> ctx, String name) throws CommandSyntaxException {
        final Player player = self(ctx);
        this.plugin.setSlot(player, name);
        Messages.ok(ctx.getSource().getSender(), "当前操作槽位已切换为 " + name);
        return 1;
    }

    private int status(CommandContext<CommandSourceStack> ctx, Player explicit)
            throws CommandSyntaxException {
        final CommandSender sender = ctx.getSource().getSender();
        final Player target = explicit != null ? explicit : self(ctx);

        Messages.ok(sender, target.getName() + "  " + this.plugin.channelHint(target));
        final StateSnapshot snapshot = this.plugin.stateOf(target);
        final long age = this.plugin.stateAgeMs(target);
        if (snapshot == null || snapshot.slots().isEmpty() && (snapshot.err() == null || snapshot.err().isEmpty())) {
            Messages.warn(sender, age < 0
                    ? "还没收到过回执（闸门没开 / 没装模组 / 还没播放过）"
                    : "最近一条回执是 " + (age / 1000) + " 秒前，里面没有 slot");
            Messages.note(sender, "客户端侧检查: /cineduct packet status");
            return 0;
        }
        if (snapshot.err() != null && !snapshot.err().isEmpty()) {
            Messages.warn(sender, "最近一次错误: " + snapshot.err());
        }
        for (StateSnapshot.Slot slot : snapshot.slots()) {
            sender.sendMessage(Component.text("  ").append(slot.line()));
        }
        Messages.note(sender, "回执 " + (age / 1000) + " 秒前  ack=" + snapshot.ack() + "  seq=" + snapshot.seq());
        return 1;
    }

    private int help(CommandContext<CommandSourceStack> ctx) {
        final CommandSender sender = ctx.getSource().getSender();
        for (Component line : this.helpCache) sender.sendMessage(line);
        return 1;
    }

    private void rebuildHelp() {
        final List<Component> lines = new ArrayList<>();
        lines.add(Component.text("[CinePlay] ", NamedTextColor.AQUA)
                .append(Component.text("控制装了 CineDuct 模组的客户端播放", NamedTextColor.WHITE)));
        lines.add(Component.text("  /cineplay play <链接>", NamedTextColor.AQUA)
                .append(Component.text("            在自己客户端开播", NamedTextColor.GRAY)));
        lines.add(Component.text("  /cineplay pause|resume|stop", NamedTextColor.AQUA)
                .append(Component.text("    暂停 / 继续 / 关闭", NamedTextColor.GRAY)));
        lines.add(Component.text("  /cineplay seek <秒>", NamedTextColor.AQUA)
                .append(Component.text("            跳转（直播无效）", NamedTextColor.GRAY)));
        lines.add(Component.text("  /cineplay volume <0-100>", NamedTextColor.AQUA)
                .append(Component.text("      音量", NamedTextColor.GRAY)));
        lines.add(Component.text("  /cineplay aspect <比例>", NamedTextColor.AQUA)
                .append(Component.text("      显示面比例（1.7778 = 16:9；0 = 关闭）", NamedTextColor.GRAY)));
        lines.add(Component.text("  /cineplay rebuild|list|sync", NamedTextColor.AQUA)
                .append(Component.text("    重建 / 请求回执 / 对齐", NamedTextColor.GRAY)));
        lines.add(Component.text("  /cineplay to <目标> <子指令>", NamedTextColor.AQUA)
                .append(Component.text(" 对别人执行（@a、玩家名、选择器都行）", NamedTextColor.GRAY)));
        lines.add(Component.text("  /cineplay slot [名字]", NamedTextColor.AQUA)
                .append(Component.text("      查看/切换当前操作的槽位", NamedTextColor.GRAY)));
        lines.add(Component.text("  /cineplay status [玩家]", NamedTextColor.AQUA)
                .append(Component.text("    通道状态与客户端回执", NamedTextColor.GRAY)));
        lines.add(Component.text("  /cineplay reload", NamedTextColor.AQUA)
                .append(Component.text("              重载 config.yml", NamedTextColor.GRAY)));
        lines.add(Component.text("  客户端要先把 cineduct-packet.json 的 enabled 打开、并把你服务器地址放进 servers",
                NamedTextColor.DARK_GRAY));
        this.helpCache = List.copyOf(lines);
    }

    // =====================================================================
    // 工具
    // =====================================================================

    /** 解析目标：自己 / `to <目标>` 选出来的一批人。 */
    private List<Player> targets(CommandContext<CommandSourceStack> ctx, boolean others)
            throws CommandSyntaxException {
        if (!others) return List.of(self(ctx));
        final PlayerSelectorArgumentResolver resolver =
                ctx.getArgument("targets", PlayerSelectorArgumentResolver.class);
        final List<Player> resolved = resolver.resolve(ctx.getSource());
        if (resolved.isEmpty()) throw NEED_PLAYER.create();
        return resolved;
    }

    private static Player self(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        if (ctx.getSource().getSender() instanceof Player player) return player;
        throw NEED_PLAYER.create();
    }

    private static Player one(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        final PlayerSelectorArgumentResolver resolver =
                ctx.getArgument("target", PlayerSelectorArgumentResolver.class);
        final List<Player> resolved = resolver.resolve(ctx.getSource());
        if (resolved.isEmpty()) throw NEED_PLAYER.create();
        return resolved.getFirst();
    }
}

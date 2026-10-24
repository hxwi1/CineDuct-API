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
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

        // ---- 建屏：create → 木锄点两角 → confirm <名字> / cancel ----
        root.then(Commands.literal("create")
                .requires(src -> src.getSender().hasPermission("cineplay.admin"))
                .executes(ctx -> {
                    final Player player = self(ctx);
                    this.plugin.selector().begin(player);
                    return 1;
                }));

        root.then(Commands.literal("cancel")
                .executes(ctx -> {
                    this.plugin.selector().cancel(self(ctx));
                    return 1;
                }));

        root.then(Commands.literal("confirm")
                .requires(src -> src.getSender().hasPermission("cineplay.admin"))
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> confirm(ctx, StringArgumentType.getString(ctx, "name")))));

        // ---- 播放列表（每条都能显式指定屏幕；不指定就用"当前屏幕"）----
        root.then(Commands.literal("add")
                .requires(src -> src.getSender().hasPermission("cineplay.admin"))
                /*
                 * 顺序要紧：先 word 再 greedy。
                 *
                 * 屏幕名是 word（Brigadier 的 word 不含 `:` `/`），而 URL 一定含 `://`，
                 * 所以 `add https://…` 只可能命中 greedy 那个；`add main https://…` 命中 word。
                 * 反过来注册就会把 `main https://…` 整串当 URL 吃掉。
                 */
                .then(Commands.argument("screen", StringArgumentType.word())
                        .then(Commands.argument("url", StringArgumentType.greedyString())
                                .executes(ctx -> add(ctx, StringArgumentType.getString(ctx, "screen"),
                                        StringArgumentType.getString(ctx, "url"), 0))))
                .then(Commands.argument("url", StringArgumentType.greedyString())
                        .executes(ctx -> add(ctx, targetScreenName(ctx.getSource()),
                                StringArgumentType.getString(ctx, "url"), 0))));

        root.then(Commands.literal("playlist")
                .executes(ctx -> playlist(ctx, targetScreenName(ctx.getSource())))
                .then(Commands.argument("screen", StringArgumentType.word())
                        .executes(ctx -> playlist(ctx, StringArgumentType.getString(ctx, "screen")))));

        root.then(Commands.literal("next")
                .executes(ctx -> step(ctx, targetScreenName(ctx.getSource()), true))
                .then(Commands.argument("screen", StringArgumentType.word())
                        .executes(ctx -> step(ctx, StringArgumentType.getString(ctx, "screen"), true))));

        root.then(Commands.literal("prev")
                .executes(ctx -> step(ctx, targetScreenName(ctx.getSource()), false))
                .then(Commands.argument("screen", StringArgumentType.word())
                        .executes(ctx -> step(ctx, StringArgumentType.getString(ctx, "screen"), false))));

        root.then(Commands.literal("screens").executes(ctx -> listScreens(ctx)));

        root.then(Commands.literal("remove")
                .requires(src -> src.getSender().hasPermission("cineplay.admin"))
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> remove(ctx, StringArgumentType.getString(ctx, "name")))));

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
    // 建屏 / 播放列表
    // =====================================================================

    /** {@code /cineplay confirm <名字>}：确认选区 → 算几何 → 下发。 */
    private int confirm(CommandContext<CommandSourceStack> ctx, String name) throws CommandSyntaxException {
        final Player player = self(ctx);
        final CommandSender sender = ctx.getSource().getSender();

        if (!Geometry.validName(name)) {
            Messages.error(sender, "屏幕名字只能用 字母/数字/下划线/点/横线，1~32 个字符");
            return 0;
        }
        if (!this.plugin.selector().hasBoth(player)) {
            Messages.error(sender, "还没选好两个角：手持木锄右键点两个对角（/cineplay create 重新开始）");
            return 0;
        }
        if (this.plugin.screens().get(name) != null) {
            Messages.error(sender, "已经有叫 " + name + " 的屏幕了；换个名字，或先 /cineplay remove " + name);
            return 0;
        }

        final Location[] points = this.plugin.selector().points(player);
        final Location[] corners;
        try {
            corners = Geometry.corners(points[0], points[1], player.getLocation().getYaw());
        } catch (IllegalArgumentException e) {
            Messages.error(sender, e.getMessage());
            return 0;
        }

        final Screens.Screen screen = this.plugin.screens().getOrCreate(name);
        screen.corners = List.of(corners[0], corners[1], corners[2], corners[3]);
        this.plugin.screens().put(screen);
        this.plugin.selector().finish(player);

        this.plugin.setSlot(player, name);   // 之后不带屏幕名的子指令默认就作用在它上面
        Messages.ok(sender, "屏幕 " + name + " 已创建：" + describe(corners));
        Messages.note(sender, "接下来用 /cineplay add " + name + " <链接> 往播放列表里加片子");
        this.plugin.screens().broadcastPlace(screen);
        return 1;
    }

    /** {@code /cineplay add [屏幕名] <url>}：追加到某块屏幕的播放列表（空列表则立刻开始播）。 */
    private int add(CommandContext<CommandSourceStack> ctx, String name, String url, int durationSec)
            throws CommandSyntaxException {
        final CommandSender sender = ctx.getSource().getSender();
        final Screens.Screen screen = this.plugin.screens().get(name);
        if (screen == null) {
            Messages.error(sender, "找不到屏幕 " + name + "（/cineplay screens 看现有屏幕）");
            return 0;
        }
        final boolean wasEmpty = screen.playlist.isEmpty();
        screen.playlist.add(url);
        screen.durations.add(Math.max(0, durationSec));
        if (wasEmpty) {
            screen.index = 0;
            screen.positionMs = 0L;
        }
        final int index = screen.playlist.size() - 1;
        this.plugin.screens().put(screen);
        Messages.ok(sender, "已加入屏幕 " + screen.name + " 的播放列表（第 " + (index + 1) + " 项）：" + shorten(url));
        if (wasEmpty) {
            this.plugin.screens().broadcastAnchor(screen);
            Messages.note(sender, "开始播放第 1 项");
        }
        /*
         * 时长：没手填就去 B 站查（公开接口，不需要登录）。
         * 单向下行意味着"这条放完了"只能靠时长判断，所以这一步是自动推进的前提。
         */
        if (screen.durations.size() > index ? screen.durations.get(index) <= 0 : true) {
            Messages.note(sender, "正在查询时长…（查到才会自动推进下一条）");
            this.plugin.durations().fetch(url, (u, info) -> {
                int seconds = info.seconds();
                /*
                 * 需要大会员的内容，非大会员账号只能**试看**（B 站通常 6 分钟）。
                 * 服务端没法知道每个客户端是不是大会员（那是各人账号的事），所以默认按试看时长截断 ——
                 * 宁可早一点切下一条，也不要"画面早就停了、服务端还以为在播"。
                 * 大会员观众想按完整时长走，就用 /cineplay add <屏幕> <链接> <秒> 手填。
                 */
                final int preview = this.plugin.premiumPreviewSeconds();
                if (info.premium() && preview > 0 && seconds > preview) {
                    this.plugin.getLogger().warning("需要大会员的付费内容：" + Screens.safeUrl(u)
                            + " 总时长 " + seconds + "s，非大会员只能试看 → 按 " + preview + "s 处理"
                            + "（大会员观众请手填完整秒数覆盖）");
                    seconds = preview;
                }
                if (!this.plugin.screens().setDuration(screen.name, index, seconds)) return;
                this.plugin.getLogger().info("时长已就绪：" + Screens.safeUrl(u) + " = " + seconds + "s（"
                        + screen.name + " 第 " + (index + 1) + " 项"
                        + (info.premium() ? "，付费内容" : "") + "）");
            });
        }
        return 1;
    }

    private int playlist(CommandContext<CommandSourceStack> ctx, String name) {
        final CommandSender sender = ctx.getSource().getSender();
        final Screens.Screen screen = this.plugin.screens().get(name);
        if (screen == null) {
            Messages.error(sender, "还没有这块屏幕");
            return 0;
        }
        Messages.ok(sender, "屏幕 " + screen.name + " 的播放列表（当前第 " + (screen.index + 1) + " 项）");
        for (int i = 0; i < screen.playlist.size(); i++) {
            final String mark = i == Math.floorMod(screen.index, Math.max(1, screen.playlist.size())) ? " ▶ " : "   ";
            Messages.note(sender, mark + (i + 1) + ". " + shorten(screen.playlist.get(i)));
        }
        if (screen.playlist.isEmpty()) Messages.note(sender, "（空）用 /cineplay add <链接> 添加");
        return 1;
    }

    /** {@code /cineplay next} / {@code prev}：手动切一条并重新下发锚点。 */
    private int step(CommandContext<CommandSourceStack> ctx, String name, boolean forward) throws CommandSyntaxException {
        final CommandSender sender = ctx.getSource().getSender();
        final Screens.Screen screen = this.plugin.screens().get(name);
        if (screen == null || screen.playlist.isEmpty()) {
            Messages.error(sender, "这块屏幕还没有播放列表");
            return 0;
        }
        final String url = forward ? screen.nextUrl() : screen.prevUrl();
        screen.positionMs = 0L;
        this.plugin.screens().put(screen);
        this.plugin.screens().broadcastAnchor(screen);
        Messages.ok(sender, "切到第 " + (screen.index + 1) + " 项：" + shorten(url));
        return 1;
    }

    private int listScreens(CommandContext<CommandSourceStack> ctx) {
        final CommandSender sender = ctx.getSource().getSender();
        final List<String> names = this.plugin.screens().names();
        if (names.isEmpty()) {
            Messages.warn(sender, "还没有任何屏幕（/cineplay create 开始建一块）");
            return 0;
        }
        Messages.ok(sender, "共 " + names.size() + " 块屏幕");
        for (String name : names) {
            final Screens.Screen s = this.plugin.screens().get(name);
            Messages.note(sender, name + "：播放列表 " + s.playlist.size() + " 项"
                    + (s.corners.isEmpty() ? "（几何未设置）" : "，第 " + (s.index + 1) + " 项"));
        }
        return 1;
    }

    private int remove(CommandContext<CommandSourceStack> ctx, String name) throws CommandSyntaxException {
        final CommandSender sender = ctx.getSource().getSender();
        final Screens.Screen screen = this.plugin.screens().get(name);
        if (screen == null) {
            Messages.error(sender, "没有叫 " + name + " 的屏幕");
            return 0;
        }
        this.plugin.screens().remove(name);
        // 让客户端把这块屏关掉（不发几何它就画不出来了，但播放器要显式关）
        this.plugin.broadcastClose(name);
        Messages.ok(sender, "已删除屏幕 " + name);
        return 1;
    }

    /** 当前操作的屏幕名：`/cineplay slot` 设过的那个，否则配置默认。 */
    private String targetScreenName(CommandSourceStack source) {
        if (source.getSender() instanceof Player player) return this.plugin.slotOf(player);
        return this.plugin.defaultSlot();
    }

    private static String describe(Location[] corners) {
        final double w = corners[0].distance(corners[1]);
        final double h = corners[1].distance(corners[2]);
        return String.format(java.util.Locale.ROOT, "%.2f × %.2f 格", w, h);
    }

    private static String shorten(String url) {
        if (url == null) return "";
        return url.length() <= 72 ? url : url.substring(0, 72) + "…";
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

    /**
     * {@code /cineplay status [玩家]}：通道状态 + **服务端自己算的**进度。
     *
     * <p>客户端不回传数据，所以这里显示的是"服务端认为应该播到哪"，而不是"客户端实际播到哪"。
     * 两者不一致（客户端卡了/关了）只能从客户端侧看（{@code /cineduct packet status}）。
     */
    private int status(CommandContext<CommandSourceStack> ctx, Player explicit)
            throws CommandSyntaxException {
        final CommandSender sender = ctx.getSource().getSender();
        final Player target = explicit != null ? explicit : self(ctx);

        Messages.ok(sender, target.getName() + "  " + this.plugin.channelHint(target));
        final Map<String, Screens.Screen> all = this.plugin.screens().all();
        if (all.isEmpty()) {
            Messages.warn(sender, "还没有任何屏幕（/cineplay create 开始建一块）");
            return 0;
        }
        for (Screens.Screen screen : all.values()) {
            final String url = screen.currentUrl();
            Messages.note(sender, screen.name
                    + "：第 " + (screen.playlist.size() == 0 ? 0 : screen.index + 1) + "/"
                    + screen.playlist.size() + " 项"
                    + (screen.playlist.isEmpty() ? "（空）" : "  " + mmss(screen.currentPositionMs())
                            + (screen.currentDurationSec() > 0
                                    ? "/" + mmss(screen.currentDurationSec() * 1000L) : "/?"))
                    + (screen.paused ? "  暂停" : "")
                    + (url.isEmpty() ? "" : "  " + Screens.safeUrl(url)));
        }
        return 1;
    }

    private static String mmss(long ms) {
        if (ms < 0) return "?";
        final long total = ms / 1000;
        return String.format(java.util.Locale.ROOT, "%02d:%02d", total / 60, total % 60);
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

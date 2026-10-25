// SPDX-License-Identifier: MIT
// Copyright (c) 2026 hxwi1
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
import org.bukkit.Bukkit;
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
            new SimpleCommandExceptionType(() -> "这条指令只能由玩家执行");

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

        /*
         * 这里**没有** `to <目标>` 之类的"只给某个客户端放视频"的指令：
         * 这个插件是服务端建虚拟屏，屏幕本身属于世界（谁走近谁看见），
         * 不存在"指定某个人播放"这种东西 —— 那样会变成两套并存的模型，反而没人搞得清。
         */

        root.then(Commands.literal("slot")
                .executes(ctx -> showSlot(ctx))
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> setSlot(ctx, StringArgumentType.getString(ctx, "name")))));

        root.then(Commands.literal("status").executes(ctx -> status(ctx, null)));

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
                        .suggests(this::suggestScreens)
                        .executes(ctx -> playlist(ctx, StringArgumentType.getString(ctx, "screen")))));

        root.then(Commands.literal("next")
                .executes(ctx -> step(ctx, targetScreenName(ctx.getSource()), true))
                .then(Commands.argument("screen", StringArgumentType.word())
                        .executes(ctx -> step(ctx, StringArgumentType.getString(ctx, "screen"), true))));

        root.then(Commands.literal("prev")
                .executes(ctx -> step(ctx, targetScreenName(ctx.getSource()), false))
                .then(Commands.argument("screen", StringArgumentType.word())
                        .executes(ctx -> step(ctx, StringArgumentType.getString(ctx, "screen"), false))));

        // ---- 权限与每屏参数 ----
        root.then(Commands.literal("access")
                .then(Commands.argument("screen", StringArgumentType.word())
                        .suggests(this::suggestScreens)
                        .then(Commands.literal("public")
                                .then(Commands.argument("value", com.mojang.brigadier.arguments.BoolArgumentType.bool())
                                        .executes(ctx -> accessPublic(ctx,
                                                StringArgumentType.getString(ctx, "screen"),
                                                com.mojang.brigadier.arguments.BoolArgumentType.getBool(ctx, "value")))))
                        .then(Commands.literal("add")
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .executes(ctx -> accessChange(ctx,
                                                StringArgumentType.getString(ctx, "screen"),
                                                StringArgumentType.getString(ctx, "player"), true, true))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .executes(ctx -> accessChange(ctx,
                                                StringArgumentType.getString(ctx, "screen"),
                                                StringArgumentType.getString(ctx, "player"), false, true))))
                        .then(Commands.literal("list").executes(ctx -> accessList(ctx,
                                StringArgumentType.getString(ctx, "screen"))))));

        root.then(Commands.literal("trust")
                .then(Commands.argument("screen", StringArgumentType.word())
                        .suggests(this::suggestScreens)
                        .then(Commands.literal("add")
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .executes(ctx -> accessChange(ctx,
                                                StringArgumentType.getString(ctx, "screen"),
                                                StringArgumentType.getString(ctx, "player"), true, false))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .executes(ctx -> accessChange(ctx,
                                                StringArgumentType.getString(ctx, "screen"),
                                                StringArgumentType.getString(ctx, "player"), false, false))))
                        .then(Commands.literal("list").executes(ctx -> accessList(ctx,
                                StringArgumentType.getString(ctx, "screen"))))));

        root.then(Commands.literal("volume")
                .then(Commands.argument("screen", StringArgumentType.word())
                        .suggests(this::suggestScreens)
                        .then(Commands.argument("percent", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 100))
                                .executes(ctx -> setVolume(ctx,
                                        StringArgumentType.getString(ctx, "screen"),
                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "percent"))))));

        root.then(Commands.literal("range")
                .then(Commands.argument("screen", StringArgumentType.word())
                        .suggests(this::suggestScreens)
                        .then(Commands.argument("enter", com.mojang.brigadier.arguments.IntegerArgumentType.integer(4, 512))
                                .executes(ctx -> setRange(ctx, StringArgumentType.getString(ctx, "screen"),
                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "enter"), -1))
                                .then(Commands.argument("exit", com.mojang.brigadier.arguments.IntegerArgumentType.integer(8, 512))
                                        .executes(ctx -> setRange(ctx, StringArgumentType.getString(ctx, "screen"),
                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "enter"),
                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "exit")))))));
        root.then(Commands.literal("screens").executes(ctx -> listScreens(ctx)));

        root.then(Commands.literal("remove")
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(this::suggestScreens)
                        .executes(ctx -> remove(ctx, StringArgumentType.getString(ctx, "name"), false))
                        .then(Commands.literal("confirm")
                                .executes(ctx -> remove(ctx, StringArgumentType.getString(ctx, "name"), true)))));

        root.then(Commands.literal("ask")
                .then(Commands.argument("screen", StringArgumentType.word())
                        .suggests(this::suggestScreens)
                        .then(Commands.argument("what", StringArgumentType.word())
                                .suggests((ctx, b) -> {
                                    for (String k : new String[]{"url", "volume", "range", "admin", "trust"}) b.suggest(k);
                                    return b.buildFuture();
                                })
                                .executes(ctx -> ask(ctx, StringArgumentType.getString(ctx, "screen"),
                                        StringArgumentType.getString(ctx, "what"))))));

        root.then(Commands.literal("paste")
                .then(Commands.argument("screen", StringArgumentType.word())
                        .suggests(this::suggestScreens)
                        .executes(ctx -> paste(ctx, StringArgumentType.getString(ctx, "screen")))));

        root.then(Commands.literal("menu")
                .executes(ctx -> menu(ctx))
                .then(Commands.argument("screen", StringArgumentType.word())
                        .suggests(this::suggestScreens)
                        .executes(ctx -> screenMenu(ctx, StringArgumentType.getString(ctx, "screen")))));
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
        final org.bukkit.block.BlockFace[] faces = this.plugin.selector().faces(player);
        try {
            corners = Geometry.corners(points[0], faces[0], points[1], faces[1]);
        } catch (IllegalArgumentException e) {
            Messages.error(sender, e.getMessage());
            return 0;
        }

        final Screens.Screen screen = this.plugin.screens().getOrCreate(name);
        screen.corners = List.of(corners[0], corners[1], corners[2], corners[3]);
        // 原始选点也存下来：以后调几何规则（法线、+1 格、浮出距离）不需要玩家重建屏幕
        screen.origin1 = points[0];
        screen.origin2 = points[1];
        screen.face1 = faces[0] == null ? "NORTH" : faces[0].name();
        screen.face2 = faces[1] == null ? "NORTH" : faces[1].name();
        this.plugin.screens().put(screen);
        this.plugin.selector().finish(player);

        if (screen.ownerId == null) {        // 第一任主人：谁先建归谁，之后只有本人/OP 能改
            screen.ownerId = player.getUniqueId();
            screen.ownerName = player.getName();
        }
        this.plugin.setSlot(player, name);   // 之后不带屏幕名的子指令默认就作用在它上面
        Messages.ok(sender, "屏幕 " + name + " 已创建：" + describe(corners));
        Messages.suggest(sender, "/cineplay add " + name + " <链接>",
                "/cineplay add " + name + " ", "点击填入指令，粘上链接就能播");
        this.plugin.screens().broadcastPlace(screen);
        return 1;
    }

    /** {@code /cineplay add [屏幕名] <url>}：追加到某块屏幕的播放列表（空列表则立刻开始播）。 */
    /** {@code /cineplay paste <屏幕>}：进入"下一条消息当作链接"的粘贴模式（菜单 [加片] 调的就是它）。 */
    private int paste(CommandContext<CommandSourceStack> ctx, String name) throws CommandSyntaxException {
        final Player player = self(ctx);
        final Screens.Screen screen = this.plugin.screens().get(name);
        if (screen == null) { Messages.error(player, "找不到屏幕 " + name); return 0; }
        if (!ensureManage(player, screen)) return 0;
        this.plugin.pasteWizard().arm(player, name, PasteWizard.Kind.URL);
        Messages.ok(player, "粘贴模式：把链接直接发到聊天栏就行（" + this.plugin.pasteTimeoutSeconds()
                + " 秒内有效，输入 cancel 取消）");
        Messages.note(player, "这条消息不会发出去，会被当作 屏幕 " + name + " 的播放源");
        return 1;
    }

    /** {@code /cineplay ask <屏幕> <url|volume|range|access>}：进入"下一条消息当作参数"模式。 */
    private int ask(CommandContext<CommandSourceStack> ctx, String name, String what) throws CommandSyntaxException {
        final Player player = self(ctx);
        final Screens.Screen screen = this.plugin.screens().get(name);
        if (screen == null) { Messages.error(player, "找不到屏幕 " + name); return 0; }
        final PasteWizard.Kind kind = switch (what.toLowerCase(java.util.Locale.ROOT)) {
            case "url" -> PasteWizard.Kind.URL;
            case "volume" -> PasteWizard.Kind.VOLUME;
            case "range" -> PasteWizard.Kind.RANGE;
            case "admin" -> PasteWizard.Kind.ADMIN;
            case "trust" -> PasteWizard.Kind.TRUST;
            default -> null;
        };
        if (kind == null) { Messages.error(player, "只支持 url / volume / range / access"); return 0; }
        if (!ensureManage(player, screen)) return 0;
        if (kind == PasteWizard.Kind.URL) {   // 加片走 paste（文案不同，逻辑一样）
            return paste(ctx, name);
        }
        this.plugin.pasteWizard().arm(player, name, kind);
        Messages.ok(player, switch (kind) {
            case VOLUME -> "请在聊天栏输入音量（0~100）";
            case RANGE -> "请在聊天栏输入起播距离（格数，如 32）";
            case ADMIN -> "请在聊天栏输入要设为**管理员**的玩家名";
            case TRUST -> "请在聊天栏输入要设为**信任者**的玩家名";
            default -> "请在聊天栏输入内容";
        } + "（" + this.plugin.pasteTimeoutSeconds() + " 秒内有效，输入 cancel 取消）");
        return 1;
    }

    /** 聊天栏填的内容落地（PasteWizard 解析校验后回调）。 */
    void applyChatInput(Player player, String screenName, PasteWizard.Kind kind, String text) {
        final Screens.Screen screen = this.plugin.screens().get(screenName);
        if (screen == null) { Messages.error(player, "屏幕已经不在了：" + screenName); return; }
        switch (kind) {
            case URL -> addUrlFromChat(player, screenName, text);
            case VOLUME -> {
                screen.volumePercent = Math.clamp(Integer.parseInt(text), 0, 100);
                this.plugin.screens().put(screen);
                this.plugin.screens().broadcastAnchor(screen);
                Messages.ok(player, "屏幕 " + screenName + " 音量 → " + screen.volumePercent + "%");
            }
            case RANGE -> {
                final int enter = Integer.parseInt(text);
                screen.enterRange = enter;
                screen.exitRange = Math.max(enter + 16, (int) Math.round(enter * 1.33));
                this.plugin.screens().put(screen);
                this.plugin.screens().broadcastPlace(screen);
                Messages.ok(player, "屏幕 " + screenName + " 可视范围：" + screen.enterRange + " 格内起播 / "
                        + screen.exitRange + " 格外停止");
            }
            case ADMIN, TRUST -> {
                final Player target = Bukkit.getPlayerExact(text);
                if (target == null) { Messages.error(player, "找不到在线玩家 " + text); return; }
                final boolean admin = kind == PasteWizard.Kind.ADMIN;
                (admin ? screen.access : screen.trusted).add(target.getUniqueId());
                this.plugin.screens().put(screen);
                Messages.ok(player, admin
                        ? "已把 " + target.getName() + " 设为 " + screenName + " 的管理员（能删、能授权）"
                        : "已把 " + target.getName() + " 设为 " + screenName + " 的信任者（能操控播放，不能删）");
            }
        }
    }

    /** 聊天里粘贴的链接（已由 PasteWizard 校验过像链接）。 */
    void addUrlFromChat(Player player, String screenName, String url) {
        final int result = this.addUrl(player, screenName, url, 0);
        if (result > 0) Messages.note(player, "（下一条消息回到普通聊天）");
    }

    /** {@code /cineplay add ...}：转给 {@link #addUrl}，指令与粘贴向导共用同一段逻辑。 */
    private int add(CommandContext<CommandSourceStack> ctx, String name, String url, int durationSec)
            throws CommandSyntaxException {
        return addUrl(ctx.getSource().getSender(), name, url, durationSec);
    }

    /** 加一条的真实逻辑（权限校验也在这里，两条入口都走）。 */
    int addUrl(CommandSender sender, String name, String url, int durationSec) {
        final Screens.Screen screen = this.plugin.screens().get(name);
        if (screen == null) {
            Messages.error(sender, "找不到屏幕 " + name + "（/cineplay screens 看现有屏幕）");
            return 0;
        }
        if (!ensureManage(sender, screen)) return 0;
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
        if (screen.playlist.isEmpty()) {
            Messages.note(sender, "（空）加一条试试：");
            Messages.suggest(sender, "/cineplay add " + screen.name + " <链接>",
                    "/cineplay add " + screen.name + " ", "点击填入指令，然后粘链接");
        }
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
        /*
         * 最后一项上按"下一条"：应该**清空队列**（和自然播完一致），而不是取模绕回第 1 项。
         * 绕回的行为让人误以为"它又从头放了一遍"，而且队列永远清不掉。
         */
        if (forward && screen.index >= screen.playlist.size() - 1 && !this.plugin.playlistLoop()) {
            this.plugin.screens().finishAndClear(screen, "手动点下一条（已在最后一项）");
            Messages.ok(sender, "已经是最后一项，播放列表已清空（需要重播请重新 /cineplay add）");
            return 1;
        }
        final String url = forward ? screen.nextUrl() : screen.prevUrl();
        screen.positionMs = 0L;
        this.plugin.screens().put(screen);
        this.plugin.screens().broadcastAnchor(screen);
        Messages.ok(sender, "切到第 " + (screen.index + 1) + " 项：" + shorten(url));
        return 1;
    }

    /** 操控播放的权限校验（信任级及以上）。 */
    private boolean ensureManage(CommandSender sender, Screens.Screen screen) {
        final Player player = sender instanceof Player p ? p : null;
        if (Screens.canPlay(screen, player)) return true;
        Messages.error(sender, "这块屏不归你管（主人 " + (screen.ownerName == null ? "未知" : screen.ownerName)
                + "）。需要主人给信任或管理员权限");
        return false;
    }

    /** 管理员级校验（删屏、授权用）：信任级不够。 */
    private boolean ensureAdmin(CommandSender sender, Screens.Screen screen) {
        final Player player = sender instanceof Player p ? p : null;
        if (Screens.canAdmin(screen, player)) return true;
        Messages.error(sender, "只有这块屏的主人 / 管理员（或 OP）能做这件事");
        return false;
    }

    private int accessPublic(CommandContext<CommandSourceStack> ctx, String name, boolean value)
            throws CommandSyntaxException {
        final CommandSender sender = ctx.getSource().getSender();
        final Screens.Screen screen = this.plugin.screens().get(name);
        if (screen == null) { Messages.error(sender, "找不到屏幕 " + name); return 0; }
        if (!ensureAdmin(sender, screen)) return 0;
        screen.publicAccess = value;
        this.plugin.screens().put(screen);
        Messages.ok(sender, value ? "屏幕 " + name + " 已对所有人开放管理" : "屏幕 " + name + " 只允许主人与授权者管理");
        return 1;
    }

    /** {@code /cineplay access|trust <屏幕> add|remove <玩家>}：管理员（能删） / 信任（能操控）。 */
    private int accessChange(CommandContext<CommandSourceStack> ctx, String name, String targetName,
                             boolean add, boolean admin) throws CommandSyntaxException {
        final CommandSender sender = ctx.getSource().getSender();
        final Screens.Screen screen = this.plugin.screens().get(name);
        if (screen == null) { Messages.error(sender, "找不到屏幕 " + name); return 0; }
        if (!ensureAdmin(sender, screen)) return 0;   // 只有管理员能给别人授权
        final Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            Messages.error(sender, "找不到在线玩家 " + targetName + "（授权对象需要在线一次）");
            return 0;
        }
        final java.util.Set<java.util.UUID> set = admin ? screen.access : screen.trusted;
        if (add) {
            set.add(target.getUniqueId());
            Messages.ok(sender, admin
                    ? "已把 " + target.getName() + " 设为屏幕 " + name + " 的**管理员**（能删、能授权）"
                    : "已把 " + target.getName() + " 设为屏幕 " + name + " 的**信任者**（能操控播放，不能删）");
        } else {
            set.remove(target.getUniqueId());
            Messages.ok(sender, (admin ? "已撤销 " + target.getName() + " 的管理员权限"
                    : "已撤销 " + target.getName() + " 的信任权限"));
        }
        this.plugin.screens().put(screen);
        return 1;
    }

    private int accessList(CommandContext<CommandSourceStack> ctx, String name) {
        final CommandSender sender = ctx.getSource().getSender();
        final Screens.Screen screen = this.plugin.screens().get(name);
        if (screen == null) { Messages.error(sender, "找不到屏幕 " + name); return 0; }
        Messages.ok(sender, "屏幕 " + name + "  主人 " + (screen.ownerName == null ? "（无）" : screen.ownerName)
                + (screen.publicAccess ? "  【信任所有人】" : ""));
        Messages.note(sender, "管理员（能删/能授权）：" + roster(screen.access));
        Messages.note(sender, "信任者（能操控播放）：" + roster(screen.trusted));
        Messages.note(sender, "改：/cineplay access " + name + " add <玩家>  → 管理员"
                + "  ／  /cineplay trust " + name + " add <玩家>  → 信任");
        return 1;
    }

    /** UUID 名单 → 逗号分隔的名字（离线也尽量给名字）。 */
    private static String roster(java.util.Set<java.util.UUID> ids) {
        if (ids.isEmpty()) return "无";
        final StringBuilder sb = new StringBuilder();
        for (java.util.UUID id : ids) {
            if (sb.length() > 0) sb.append(", ");
            final String n = Bukkit.getOfflinePlayer(id).getName();
            sb.append(n == null ? id.toString().substring(0, 8) : n);
        }
        return sb.toString();
    }
    private int setVolume(CommandContext<CommandSourceStack> ctx, String name, int percent)
            throws CommandSyntaxException {
        final CommandSender sender = ctx.getSource().getSender();
        final Screens.Screen screen = this.plugin.screens().get(name);
        if (screen == null) { Messages.error(sender, "找不到屏幕 " + name); return 0; }
        if (!ensureManage(sender, screen)) return 0;
        screen.volumePercent = Math.clamp(percent, 0, 100);
        this.plugin.screens().put(screen);
        this.plugin.screens().broadcastAnchor(screen);        // 立刻生效（锚点里带 volume）
        Messages.ok(sender, "屏幕 " + name + " 音量 → " + screen.volumePercent + "%");
        return 1;
    }

    private int setRange(CommandContext<CommandSourceStack> ctx, String name, int enter, int exit)
            throws CommandSyntaxException {
        final CommandSender sender = ctx.getSource().getSender();
        final Screens.Screen screen = this.plugin.screens().get(name);
        if (screen == null) { Messages.error(sender, "找不到屏幕 " + name); return 0; }
        if (!ensureManage(sender, screen)) return 0;
        screen.enterRange = enter;
        screen.exitRange = exit > 0 ? Math.max(enter + 2, exit) : Math.max(enter + 16, (int) Math.round(enter * 1.33));
        this.plugin.screens().put(screen);
        this.plugin.screens().broadcastPlace(screen);         // 范围在 place 里，重发几何
        Messages.ok(sender, "屏幕 " + name + " 可视范围：" + screen.enterRange + " 格内起播 / "
                + screen.exitRange + " 格外停止");
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
            Messages.action(sender, name, "/cineplay playlist " + name, "点击查看这块屏的播放列表");
            Messages.note(sender, "  播放列表 " + s.playlist.size() + " 项"
                    + (s.corners.isEmpty() ? "（几何未设置）" : "，第 " + (s.index + 1) + " 项")
                    + (s.ownerName == null ? "" : "，主人 " + s.ownerName));
        }
        return 1;
    }

    /**
     * {@code /cineplay remove <屏幕> [confirm]}：删除屏幕（**两段确认**）。
     *
     * <p>第一次只提示并给一个 [确认删除] 按钮（15 秒内有效）；带 {@code confirm} 才真删。
     * 破坏性操作不该一个误点就生效。
     *
     * <p>权限：主人 / 被单独授权的人 / OP。**公开（publicAccess）不算**——
     * 公开是"大家都能放片子"，不是"大家都能拆"。
     */
    private int remove(CommandContext<CommandSourceStack> ctx, String name, boolean confirmed)
            throws CommandSyntaxException {
        final CommandSender sender = ctx.getSource().getSender();
        final Player player = sender instanceof Player p ? p : null;
        final Screens.Screen screen = this.plugin.screens().get(name);
        if (screen == null) {
            Messages.error(sender, "没有叫 " + name + " 的屏幕");
            return 0;
        }
        if (!Screens.canDelete(screen, player)) {
            Messages.error(sender, "这块屏是公开的，只有主人或被单独授权的人才能删除"
                    + (screen.ownerName == null ? "" : "（主人 " + screen.ownerName + "）"));
            return 0;
        }
        if (!confirmed) {
            Messages.warn(sender, "要删除屏幕 " + name + "？这会同时清掉它的播放列表和几何，无法撤销。");
            Messages.ok(sender, "15 秒内点下面的按钮确认：");
            sender.sendMessage(Component.text("  ")
                    .append(Messages.actionComponent("[ 确认删除 " + name + " ]",
                            "/cineplay remove " + name + " confirm", "点了就真删")));
            return 1;
        }
        this.plugin.screens().remove(name);
        this.plugin.broadcastClose(name);
        Messages.ok(sender, "屏幕 " + name + " 已删除");
        return 1;
    }
    /** 当前操作的屏幕名：`/cineplay slot` 设过的那个，否则配置默认。 */
    private String targetScreenName(CommandSourceStack source) {
        if (source.getSender() instanceof Player player) return this.plugin.slotOf(player);
        return this.plugin.defaultSlot();
    }

    /** 屏幕名补全（`remove` / `playlist` / `next` / `prev` 共用）。 */
    private java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestScreens(
            CommandContext<CommandSourceStack> ctx,
            com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        for (String n : this.plugin.screens().names()) builder.suggest(n);
        return builder.buildFuture();
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
                    + (screen.finished ? "  播完（队列已清空）" : "")
                    + (screen.paused ? "  暂停" : "")
                    + (url.isEmpty() ? "" : "  " + Screens.safeUrl(url)));
        }
        return 1;
    }

    private static final String PFX = "[CinePlay] ";
    private static final Component SP = Component.text(" ");

    private static String mmss(long ms) {
        if (ms < 0) return "?";
        final long total = ms / 1000;
        return String.format(java.util.Locale.ROOT, "%02d:%02d", total / 60, total % 60);
    }

    /** 装饰线：一行等号（顶部/底部）。 */
    private static Component rule() {
        return Component.text("==================== ", NamedTextColor.DARK_AQUA)
                .append(Component.text("CinePlay", NamedTextColor.AQUA))
                .append(Component.text(" ====================", NamedTextColor.DARK_AQUA));
    }

    /**
     * {@code /cineplay menu}：一级菜单 —— 只列「我的屏 + 附近有权限的屏」，每块屏一个 [管理]。
     *
     * <p>二级（{@code /cineplay menu <屏幕>}）才是对着这块屏能做的一排操作。
     * 分两级的理由：一级是"选哪块屏"，二级是"对这块屏做什么"，混在一起一行放七八个按钮，
     * 聊天栏里既难读也容易点错。
     */
    private int menu(CommandContext<CommandSourceStack> ctx) {
        final CommandSender sender = ctx.getSource().getSender();
        final List<String> names = this.plugin.screens().names();
        final Player self = sender instanceof Player p ? p : null;
        final Location eye = self == null ? null : self.getLocation();
        final int range = this.plugin.menuRange();

        sender.sendMessage(rule());
        if (names.isEmpty()) {
            Messages.ok(sender, "还没有任何屏幕");
            sender.sendMessage(Component.text("  ")
                    .append(Messages.actionComponent("[+ 新建屏幕]", "/cineplay create", "木锄点两个对角开始建屏")));
            sender.sendMessage(rule());
            return 1;
        }

        int hidden = 0;
        for (String name : names) {
            final Screens.Screen s = this.plugin.screens().get(name);
            final boolean mine = self != null && s.ownerId != null && s.ownerId.equals(self.getUniqueId());
            final boolean nearby = eye != null && !s.corners.isEmpty() && eye.getWorld() != null
                    && eye.getWorld().equals(s.corners.getFirst().getWorld())
                    && eye.distance(s.corners.getFirst()) <= range;
            if (!mine && !(nearby && Screens.canUse(s, self))) {
                hidden++;
                continue;
            }
            final String state = s.finished ? "播完" : (s.playlist.isEmpty() ? "空"
                    : (s.paused ? "暂停" : "播放中"));
            final String pos = s.playlist.isEmpty() ? ""
                    : " " + mmss(s.currentPositionMs()) + (s.currentDurationSec() > 0
                            ? "/" + mmss(s.currentDurationSec() * 1000L) : "");
            final String tag = mine ? "" : (nearby ? "附近 " : "");
            sender.sendMessage(Component.text(" " + tag, NamedTextColor.DARK_GRAY)
                    .append(Component.text(name, NamedTextColor.WHITE))
                    .append(Component.text("  [" + state + pos + "]", NamedTextColor.GRAY))
                    .append(Component.text(s.playlist.isEmpty() ? "  —" : "  " + s.playlist.size() + " 项",
                            NamedTextColor.DARK_GRAY))
                    .append(SP)
                    .append(Messages.actionComponent("[管理]", "/cineplay menu " + name, "打开这块屏的操作菜单")));
        }
        if (hidden > 0) {
            Messages.note(sender, "另有 " + hidden + " 块屏（" + range + " 格外，或没对你开放）已隐藏");
        }
        sender.sendMessage(Component.text("  ")
                .append(Messages.actionComponent("[+ 新建屏幕]", "/cineplay create", "木锄点两个对角"))
                .append(SP)
                .append(Messages.actionComponent("[刷新]", "/cineplay menu", "重新加载"))
                .append(SP)
                .append(Messages.actionComponent("[帮助]", "/cineplay help", "所有指令")));
        sender.sendMessage(rule());
        return 1;
    }

    /** {@code /cineplay menu <屏幕>}：二级菜单 —— 对着某块屏能做的事。 */
    private int screenMenu(CommandContext<CommandSourceStack> ctx, String name) {
        final CommandSender sender = ctx.getSource().getSender();
        final Player self = sender instanceof Player p ? p : null;
        final Screens.Screen s = this.plugin.screens().get(name);
        sender.sendMessage(rule());
        if (s == null) {
            Messages.error(sender, "找不到屏幕 " + name);
            sender.sendMessage(rule());
            return 0;
        }
        if (!Screens.canUse(s, self)) {
            Messages.error(sender, "这块屏没有对你开放（找主人 " + (s.ownerName == null ? "?" : s.ownerName) + " 授权）");
            sender.sendMessage(rule());
            return 0;
        }
        final String state = s.finished ? "播完" : (s.playlist.isEmpty() ? "空" : (s.paused ? "暂停" : "播放中"));
        final String role = Screens.roleOf(s, self);
        sender.sendMessage(Component.text(" " + s.name, NamedTextColor.WHITE)
                .append(Component.text("  [" + state + "]", NamedTextColor.GRAY))
                .append(Component.text("  主人 " + (s.ownerName == null ? "无" : s.ownerName)
                        + (s.publicAccess ? "  【信任所有人】" : "") + "  你的身份：" + role, NamedTextColor.DARK_GRAY)));
        sender.sendMessage(Component.text("  音量 " + s.volumePercent + "%  范围 "
                + s.enterRange + "/" + s.exitRange + " 格  第 "
                + (s.playlist.isEmpty() ? 0 : s.index + 1) + "/" + s.playlist.size() + " 项",
                NamedTextColor.DARK_GRAY));

        sender.sendMessage(Component.text("  ")
                .append(Messages.actionComponent("[播放列表]", "/cineplay playlist " + name, "看这一屏的列表"))
                .append(SP)
                .append(Messages.actionComponent("[下一条]", "/cineplay next " + name, "切到下一项"))
                .append(SP)
                .append(Messages.actionComponent("[加片]", "/cineplay paste " + name, "点一下，然后在聊天栏粘链接")));
        sender.sendMessage(Component.text("  ")
                .append(Messages.actionComponent("[音量]", "/cineplay ask " + name + " volume", "点一下，然后在聊天栏输入 0~100"))
                .append(SP)
                .append(Messages.actionComponent("[范围]", "/cineplay ask " + name + " range", "点一下，然后在聊天栏输入格数"))
                .append(SP)
                .append(Messages.actionComponent("[管理员]", "/cineplay ask " + name + " admin", "给管理员（能删/能授权）"))
                .append(SP)
                .append(Messages.actionComponent("[信任]", "/cineplay ask " + name + " trust", "给信任（能操控播放，不能删）"))
                .append(SP)
                .append(Messages.actionComponent("[删除]", "/cineplay remove " + name, "先弹确认，不会一次点掉")));
        sender.sendMessage(Component.text("  ")
                .append(Messages.actionComponent("[◀ 返回]", "/cineplay menu", "回到屏幕列表")));
        sender.sendMessage(rule());
        return 1;
    }
    private int help(CommandContext<CommandSourceStack> ctx) {
        final CommandSender sender = ctx.getSource().getSender();
        for (Component line : this.helpCache) sender.sendMessage(line);
        Messages.ok(sender, "点下面的链接就能执行/填入：");
        Messages.action(sender, "/cineplay screens", "/cineplay screens", "列出所有屏幕（点名字看播放列表）");
        Messages.action(sender, "/cineplay create", "/cineplay create", "开始选区建一块新屏");
        Messages.suggest(sender, "/cineplay add <屏幕> <链接>", "/cineplay add ", "点一下填进输入框，再粘链接");
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
        lines.add(Component.text("  /cineplay create", NamedTextColor.AQUA)
                .append(Component.text("              开始选区（木锄右键点两个对角）", NamedTextColor.GRAY)));
        lines.add(Component.text("  /cineplay confirm <名字>", NamedTextColor.AQUA)
                .append(Component.text("   确认并命名这块屏", NamedTextColor.GRAY)));
        lines.add(Component.text("  /cineplay cancel", NamedTextColor.AQUA)
                .append(Component.text("              放弃这次选区", NamedTextColor.GRAY)));
        lines.add(Component.text("  /cineplay add [屏幕] <链接> [秒]", NamedTextColor.AQUA)
                .append(Component.text(" 加入播放列表（秒数可省，插件会自己查）", NamedTextColor.GRAY)));
        lines.add(Component.text("  /cineplay playlist|next|prev [屏幕]", NamedTextColor.AQUA)
                .append(Component.text(" 查看 / 切上一条 / 切下一条", NamedTextColor.GRAY)));
        lines.add(Component.text("  /cineplay screens", NamedTextColor.AQUA)
                .append(Component.text("             列出所有屏幕与进度", NamedTextColor.GRAY)));
        lines.add(Component.text("  /cineplay remove <名字>", NamedTextColor.AQUA)
                .append(Component.text("     删除这块屏（并让客户端停掉播放）", NamedTextColor.GRAY)));        lines.add(Component.text("  /cineplay to <目标> <子指令>", NamedTextColor.AQUA)
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

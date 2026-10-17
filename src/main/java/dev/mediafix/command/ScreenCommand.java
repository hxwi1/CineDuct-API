package dev.mediafix.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import dev.mediafix.CineDuctRegistry;
import dev.mediafix.screen.ScreenEntity;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * 屏幕指令 —— CineDuct 唯一的操控入口（没有 GUI、没有物品）。
 *
 * <p><b>为什么注册在服务端</b>：屏幕状态是"服务端权威"的（谁在放、放到哪一刻、暂停没有都在实体里），
 * 客户端命令改不了服务端实体。所以这棵树走 {@code RegisterCommandsEvent}，
 * 单人存档（集成服务器）与多人服务器行为一致；改动类操作需要 OP 权限 2 级。
 *
 * <p>链接直接用 {@code string()} 参数：不带空格时无需引号，
 * 例如 {@code /cineduct screen create https://www.bilibili.com/video/BV1xx411c7mD}。
 */
public final class ScreenCommand {

    private static final SimpleCommandExceptionType ERR_PERMISSION =
            new SimpleCommandExceptionType(Component.literal("需要 OP 权限（等级 2）"));
    private static final SimpleCommandExceptionType ERR_NOT_SCREEN =
            new SimpleCommandExceptionType(Component.literal("目标不是 CineDuct 屏幕"));
    private static final SimpleCommandExceptionType ERR_NO_TARGET =
            new SimpleCommandExceptionType(Component.literal("范围内没有 CineDuct 屏幕"));

    private ScreenCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("cineduct")
                .then(Commands.literal("screen")
                        // ---- 建屏幕 ----
                        // url 用 greedyString：Brigadier 的 string() 未加引号分支只接受
                        // [0-9A-Za-z_.+-]，冒号和斜杠都会被判为"多余数据"，玩家就没法直接粘链接了。
                        // 代价是它必须吃掉整行，所以尺寸改用单独的 size 子指令。
                        .then(Commands.literal("create")
                                .requires(src -> src.hasPermission(2))
                                .then(Commands.argument("url", StringArgumentType.greedyString())
                                        .executes(ctx -> create(ctx,
                                                StringArgumentType.getString(ctx, "url")))))
                        // ---- 列出 ----
                        .then(Commands.literal("list")
                                .executes(ScreenCommand::list))
                        // ---- 删除 ----
                        .then(Commands.literal("remove")
                                .requires(src -> src.hasPermission(2))
                                .then(Commands.argument("screen", EntityArgument.entities())
                                        .executes(ScreenCommand::remove)))
                        // ---- 查看 ----
                        .then(Commands.literal("info")
                                .then(Commands.argument("screen", EntityArgument.entities())
                                        .executes(ScreenCommand::info)))
                        // ---- 换源 ----
                        .then(Commands.literal("source")
                                .requires(src -> src.hasPermission(2))
                                .then(Commands.argument("screen", EntityArgument.entities())
                                        .then(Commands.argument("url", StringArgumentType.greedyString())
                                                .executes(ctx -> forEach(ctx, e -> {
                                                    e.setSourceUri(StringArgumentType.getString(ctx, "url"));
                                                    e.setPaused(false);
                                                }, "已换源，从头开始播放")))))
                        // ---- 播放控制 ----
                        .then(Commands.literal("play")
                                .requires(src -> src.hasPermission(2))
                                .then(Commands.argument("screen", EntityArgument.entities())
                                        .executes(ctx -> forEach(ctx, e -> e.setPaused(false), "已继续播放"))))
                        .then(Commands.literal("pause")
                                .requires(src -> src.hasPermission(2))
                                .then(Commands.argument("screen", EntityArgument.entities())
                                        .executes(ctx -> forEach(ctx, e -> e.setPaused(true), "已暂停"))))
                        .then(Commands.literal("stop")
                                .requires(src -> src.hasPermission(2))
                                .then(Commands.argument("screen", EntityArgument.entities())
                                        .executes(ctx -> forEach(ctx, e -> {
                                            e.setPaused(true);
                                            e.seekTo(0L);
                                        }, "已停止并回到开头"))))
                        .then(Commands.literal("seek")
                                .requires(src -> src.hasPermission(2))
                                .then(Commands.argument("screen", EntityArgument.entities())
                                        .then(Commands.argument("seconds", FloatArgumentType.floatArg(0.0f))
                                                .executes(ctx -> forEach(ctx, e -> e.seekTo(
                                                                (long) (FloatArgumentType.getFloat(ctx, "seconds") * 1000.0f)),
                                                        "已跳转")))))
                        // ---- 音量 / 距离衰减 ----
                        .then(Commands.literal("volume")
                                .requires(src -> src.hasPermission(2))
                                .then(Commands.argument("screen", EntityArgument.entities())
                                        .then(Commands.argument("percent",
                                                        com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 100))
                                                .executes(ctx -> forEach(ctx, e -> e.setVolume(
                                                                com.mojang.brigadier.arguments.IntegerArgumentType
                                                                        .getInteger(ctx, "percent")),
                                                        "音量已设置")))))
                        .then(Commands.literal("range")
                                .requires(src -> src.hasPermission(2))
                                .then(Commands.argument("screen", EntityArgument.entities())
                                        .then(Commands.argument("min", FloatArgumentType.floatArg(0.0f))
                                                .then(Commands.argument("max", FloatArgumentType.floatArg(0.0f))
                                                        .executes(ctx -> forEach(ctx, e -> e.setRange(
                                                                        FloatArgumentType.getFloat(ctx, "min"),
                                                                        FloatArgumentType.getFloat(ctx, "max")),
                                                                "音量距离范围已设置"))))))
                        // ---- 循环 ----
                        .then(Commands.literal("loop")
                                .requires(src -> src.hasPermission(2))
                                .then(Commands.argument("screen", EntityArgument.entities())
                                        .then(Commands.literal("on").executes(ctx -> forEach(ctx, e -> e.setLoop(true), "循环已开")))
                                        .then(Commands.literal("off").executes(ctx -> forEach(ctx, e -> e.setLoop(false), "循环已关")))))
                        // ---- 尺寸 ----
                        .then(Commands.literal("size")
                                .requires(src -> src.hasPermission(2))
                                .then(Commands.argument("screen", EntityArgument.entities())
                                        .then(Commands.argument("width", FloatArgumentType.floatArg(0.25f, 64.0f))
                                                .then(Commands.argument("height", FloatArgumentType.floatArg(0.25f, 64.0f))
                                                        .executes(ctx -> forEach(ctx, e -> e.setScreenSize(
                                                                        FloatArgumentType.getFloat(ctx, "width"),
                                                                        FloatArgumentType.getFloat(ctx, "height")),
                                                                "尺寸已设置"))))))
                        // ---- 清晰度上限 ----
                        .then(Commands.literal("quality")
                                .requires(src -> src.hasPermission(2))
                                .then(Commands.argument("screen", EntityArgument.entities())
                                        .then(Commands.literal("auto").executes(ctx -> forEach(ctx, e -> e.setQualityCap(-1), "清晰度：自动（按网速自适应）")))
                                        .then(Commands.literal("max").executes(ctx -> forEach(ctx, e -> e.setQualityCap(0), "清晰度：账号内最高")))
                                        .then(Commands.literal("1080p").executes(ctx -> forEach(ctx, e -> e.setQualityCap(80), "清晰度：1080P")))
                                        .then(Commands.literal("1080p60").executes(ctx -> forEach(ctx, e -> e.setQualityCap(116), "清晰度：1080P60")))
                                        .then(Commands.literal("4k").executes(ctx -> forEach(ctx, e -> e.setQualityCap(120), "清晰度：4K")))
                                        .then(Commands.literal("8k").executes(ctx -> forEach(ctx, e -> e.setQualityCap(127), "清晰度：8K（需要大会员 + 显卡 AV1 硬解）")))))
                        // ---- 朝向：yaw [pitch] ----
                        .then(Commands.literal("rotate")
                                .requires(src -> src.hasPermission(2))
                                .then(Commands.argument("screen", EntityArgument.entities())
                                        .then(Commands.argument("yaw", FloatArgumentType.floatArg(-360.0f, 360.0f))
                                                .executes(ctx -> forEach(ctx, e -> e.setRotation(
                                                                FloatArgumentType.getFloat(ctx, "yaw"), 0.0f),
                                                        "朝向已设置"))
                                                .then(Commands.argument("pitch", FloatArgumentType.floatArg(-90.0f, 90.0f))
                                                        .executes(ctx -> forEach(ctx, e -> e.setRotation(
                                                                        FloatArgumentType.getFloat(ctx, "yaw"),
                                                                        FloatArgumentType.getFloat(ctx, "pitch")),
                                                                "朝向已设置"))))))
                        // ---- 相对位移 ----
                        .then(Commands.literal("move")
                                .requires(src -> src.hasPermission(2))
                                .then(Commands.argument("screen", EntityArgument.entities())
                                        .then(Commands.argument("dx", FloatArgumentType.floatArg(-64.0f, 64.0f))
                                                .then(Commands.argument("dy", FloatArgumentType.floatArg(-64.0f, 64.0f))
                                                        .then(Commands.argument("dz", FloatArgumentType.floatArg(-64.0f, 64.0f))
                                                                .executes(ctx -> forEach(ctx, e -> e.translate(
                                                                                FloatArgumentType.getFloat(ctx, "dx"),
                                                                                FloatArgumentType.getFloat(ctx, "dy"),
                                                                                FloatArgumentType.getFloat(ctx, "dz")),
                                                                        "位置已移动")))))))
                        // ---- 不透明度 / 亮度 ----
                        .then(Commands.literal("alpha")
                                .requires(src -> src.hasPermission(2))
                                .then(Commands.argument("screen", EntityArgument.entities())
                                        .then(Commands.argument("value", FloatArgumentType.floatArg(0.05f, 1.0f))
                                                .executes(ctx -> forEach(ctx, e -> e.setAlpha(
                                                                FloatArgumentType.getFloat(ctx, "value")),
                                                        "不透明度已设置")))))
                        .then(Commands.literal("brightness")
                                .requires(src -> src.hasPermission(2))
                                .then(Commands.argument("screen", EntityArgument.entities())
                                        .then(Commands.argument("value", FloatArgumentType.floatArg(0.05f, 1.0f))
                                                .executes(ctx -> forEach(ctx, e -> e.setBrightness(
                                                                FloatArgumentType.getFloat(ctx, "value")),
                                                        "亮度已设置")))))
                        // ---- 画面铺法 ----
                        .then(Commands.literal("fit")
                                .requires(src -> src.hasPermission(2))
                                .then(Commands.argument("screen", EntityArgument.entities())
                                        .then(Commands.literal("letterbox").executes(ctx -> forEach(ctx, e -> e.setFit(dev.mediafix.api.ScreenFit.LETTERBOX), "铺法：等比加黑边")))
                                        .then(Commands.literal("fill").executes(ctx -> forEach(ctx, e -> e.setFit(dev.mediafix.api.ScreenFit.FILL), "铺法：裁切铺满")))
                                        .then(Commands.literal("stretch").executes(ctx -> forEach(ctx, e -> e.setFit(dev.mediafix.api.ScreenFit.STRETCH), "铺法：拉伸铺满"))))
                        // ---- 单面 / 双面 ----
                        .then(Commands.literal("mirror")
                                .requires(src -> src.hasPermission(2))
                                .then(Commands.argument("screen", EntityArgument.entities())
                                        .then(Commands.literal("on").executes(ctx -> forEach(ctx, e -> e.setDoubleSided(true), "双面可见（背面镜像）")))
                                        .then(Commands.literal("off").executes(ctx -> forEach(ctx, e -> e.setDoubleSided(false), "单面可见（只看正面）")))))
                        // ---- 音频输出形态 ----
                        .then(Commands.literal("audio")
                                .requires(src -> src.hasPermission(2))
                                .then(Commands.argument("screen", EntityArgument.entities())
                                        .then(Commands.literal("default").executes(ctx -> forEach(ctx, e -> e.setAudioModeOverride(""), "音频输出：跟随全局配置")))
                                        .then(Commands.literal("auto").executes(ctx -> forEach(ctx, e -> e.setAudioModeOverride("auto"), "音频输出：自动（能直通就直通）")))
                                        .then(Commands.literal("shared").executes(ctx -> forEach(ctx, e -> e.setAudioModeOverride("shared"), "音频输出：共享模式")))
                                        .then(Commands.literal("exclusive").executes(ctx -> forEach(ctx, e -> e.setAudioModeOverride("exclusive"), "音频输出：独占 PCM")))
                                        .then(Commands.literal("passthrough").executes(ctx -> forEach(ctx, e -> e.setAudioModeOverride("passthrough"), "音频输出：IEC61937 直通"))))))));
    }

    // =====================================================================

    private static int create(CommandContext<CommandSourceStack> ctx, String url)
            throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();

        Vec3 base = src.getPosition();
        float yaw = 0.0f;
        float pitch = 0.0f;
        Vec3 offset = Vec3.ZERO;
        if (src.getEntity() != null) {
            Entity owner = src.getEntity();
            yaw = owner.getYRot();
            pitch = owner.getXRot();
            offset = owner.getLookAngle().scale(2.0);
        }
        Vec3 pos = new Vec3(base.x + offset.x, base.y + offset.y + 1.0, base.z + offset.z);

        // 走开放 API：与附属 mod 用的是同一条路径（同一套事件与校验）
        dev.mediafix.api.ScreenHandle handle = dev.mediafix.api.CineDuctApi.get()
                .createScreen(level, pos, yaw, pitch, 2.0f, 1.125f, url);

        final float createdYaw = yaw;
        src.sendSuccess(() -> Component.literal(String.format(
                "[CineDuct] 已创建屏幕 %s：默认尺寸 2.00×1.13 朝向 %.0f°（可用 screen size 调整），源 %s",
                handle.id(), createdYaw, url)), true);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        List<ScreenEntity> screens = allScreens(src.getLevel());
        if (screens.isEmpty()) {
            src.sendSuccess(() -> Component.literal("[CineDuct] 当前维度没有屏幕"), false);
            return 0;
        }
        src.sendSuccess(() -> Component.literal("[CineDuct] 共 " + screens.size() + " 块屏幕："), false);
        for (ScreenEntity e : screens) {
            src.sendSuccess(() -> Component.literal(String.format(
                    "  %s  %.2f×%.2f  %s  %s  %s",
                    e.getUUID().toString().substring(0, 8),
                    e.screenWidth(), e.screenHeight(),
                    e.isPaused() ? "已暂停" : "播放中",
                    e.projectedPositionMs() + "ms",
                    e.sourceUri().isEmpty() ? "（无源）" : e.sourceUri())), false);
        }
        return screens.size();
    }

    private static int remove(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        List<ScreenEntity> screens = targets(ctx);
        int n = screens.size();
        for (ScreenEntity e : screens) {
            e.discard();
        }
        ctx.getSource().sendSuccess(() -> Component.literal("[CineDuct] 已删除 " + n + " 块屏幕"), true);
        return n;
    }

    private static int info(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        for (ScreenEntity e : targets(ctx)) {
            src.sendSuccess(() -> Component.literal(String.format(
                    "[CineDuct] 屏幕 %s%n  源: %s%n  尺寸: %.2f×%.2f（比例 %.3f）  铺法: %s%n"
                            + "  位置: %.2f, %.2f, %.2f  朝向: yaw %.1f° pitch %.1f°%n"
                            + "  状态: %s  锚点: %dms（此刻应为 %dms）%n"
                            + "  音量: %d%%  距离范围: %.1f~%.1f  循环: %s  静音: %s  清晰度上限: %s%n"
                            + "  不透明度: %.2f  亮度: %.2f×  可见面: %s  音频输出: %s",
                    e.getUUID(), e.sourceUri().isEmpty() ? "（空）" : e.sourceUri(),
                    e.screenWidth(), e.screenHeight(), e.aspect(), e.fit(),
                    e.getX(), e.getY(), e.getZ(), e.getYRot(), e.getXRot(),
                    e.isPaused() ? "已暂停" : "播放中",
                    e.positionMsAtAnchor(), e.projectedPositionMs(),
                    e.volume(), e.rangeMin(), e.rangeMax(), e.isLoop() ? "开" : "关",
                    e.isMuted() ? "是" : "否",
                    e.qualityCap() < 0 ? "自动" : String.valueOf(e.qualityCap()),
                    e.alpha(), e.brightness(),
                    e.isDoubleSided() ? "双面" : "单面",
                    e.audioModeOverride().isEmpty() ? "跟随全局配置" : e.audioModeOverride())), false);
        }
        return 1;
    }

    /** 对选中屏幕逐个执行一个动作。 */
    private static int forEach(CommandContext<CommandSourceStack> ctx, ScreenAction action, String message)
            throws CommandSyntaxException {
        List<ScreenEntity> screens = targets(ctx);
        for (ScreenEntity e : screens) {
            action.apply(e);
        }
        String text = "[CineDuct] " + message + "（" + screens.size() + " 块屏幕）";
        ctx.getSource().sendSuccess(() -> Component.literal(text), true);
        return screens.size();
    }

    private interface ScreenAction {
        void apply(ScreenEntity screen);
    }

    private static List<ScreenEntity> targets(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        List<ScreenEntity> out = new ArrayList<>();
        for (Entity e : EntityArgument.getEntities(ctx, "screen")) {
            if (e instanceof ScreenEntity screen) {
                out.add(screen);
            } else {
                throw ERR_NOT_SCREEN.create();
            }
        }
        if (out.isEmpty()) throw ERR_NO_TARGET.create();
        return out;
    }

    private static List<ScreenEntity> allScreens(ServerLevel level) {
        List<ScreenEntity> out = new ArrayList<>();
        for (Entity e : level.getAllEntities()) {
            if (e instanceof ScreenEntity screen) out.add(screen);
        }
        return out;
    }
}

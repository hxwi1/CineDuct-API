package dev.mediafix.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.mediafix.CineDuct;
import dev.mediafix.config.StreamConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Map;
/**
 * 指令：/cineduct-stream —— 流式播放管理（只做边下边播，不落盘）。
 * <ul>
 *   <li>/cineduct-stream [on|off] —— 开关 DASH 高清缓存播放；</li>
 *   <li>/cineduct-stream quality [档位] —— 查看/设置清晰度上限
 *       (360p/480p/720p/720p60/1080p/1080p60/4k/8k/auto)；</li>
 *   <li>/cineduct-stream cache —— 查看缓存占用；</li>
 * </ul>
 */
public final class StreamCommand {

    private StreamCommand() {
    }

    public static void register(RegisterClientCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> cmd = Commands.literal("cineduct-stream")
                .executes(ctx -> status(ctx.getSource()))
                .then(Commands.literal("quality")
                        .executes(ctx -> showQuality(ctx.getSource()))
                        .then(Commands.literal("360p").executes(ctx -> setQuality(ctx.getSource(), 16)))
                        .then(Commands.literal("480p").executes(ctx -> setQuality(ctx.getSource(), 32)))
                        .then(Commands.literal("720p").executes(ctx -> setQuality(ctx.getSource(), 64)))
                        .then(Commands.literal("720p60").executes(ctx -> setQuality(ctx.getSource(), 74)))
                        .then(Commands.literal("1080p").executes(ctx -> setQuality(ctx.getSource(), 80)))
                        .then(Commands.literal("1080p60").executes(ctx -> setQuality(ctx.getSource(), 116)))
                        .then(Commands.literal("4k").executes(ctx -> setQuality(ctx.getSource(), 120)))
                .then(Commands.literal("8k").executes(ctx -> setQuality(ctx.getSource(), 127)))
                        .then(Commands.literal("auto").executes(ctx -> setQuality(ctx.getSource(), -1)))
                        .then(Commands.literal("max").executes(ctx -> setQuality(ctx.getSource(), 0))))
                .then(Commands.literal("streaming")
                        .executes(ctx -> status(ctx.getSource()))
                        .then(Commands.literal("on").executes(ctx -> setStreaming(ctx.getSource(), true)))
                        .then(Commands.literal("off").executes(ctx -> setStreaming(ctx.getSource(), false)))
                        .then(Commands.argument("offsetMs", com.mojang.brigadier.arguments.IntegerArgumentType.integer(-5000, 5000))
                                .executes(ctx -> setOffset(ctx.getSource(),
                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "offsetMs")))));
        event.getDispatcher().register(cmd);
    }

    private static int status(CommandSourceStack source) {
        String q = qualityName(StreamConfig.maxQn);
        if (StreamConfig.autoQuality()) {
            dev.mediafix.abr.AbrLadder.Candidate cur = dev.mediafix.abr.AbrLadder.current();
            long est = dev.mediafix.abr.AbrController.estimateBps();
            q = q + (cur == null ? "（等待解析）"
                    : "（当前 " + cur.label() + " " + (cur.bandwidthBps() / 1000) + "kbps"
                      + (est > 0 ? "，实测带宽 " + (est / 1000) + "kbps" : "，暂无测速") + "）");
        }
        final String quality = q;
        source.sendSuccess(() -> Component.literal(
                "[CineDuct] 流式播放: " + (StreamConfig.dashStreaming ? "开" : "关")
                        + " | 清晰度: " + quality
                        + " | HDR: 不拉（硬编码，自动落到最高 SDR 档）"
                        + (StreamConfig.streamOffsetMs != 0 ? " (偏移 " + StreamConfig.streamOffsetMs + "ms)" : "")
                        + " | /cineduct-stream quality|streaming 调节"), false);
        return 1;
    }

    /** /cineduct-stream streaming —— 开关 DASH 流式直连（不下载）。 */
    private static int setStreaming(CommandSourceStack source, boolean value) {
        StreamConfig.dashStreaming = value;
        StreamConfig.save();
        boolean usable = value && dev.mediafix.ffmpeg.FfmpegRuntime.available();
        source.sendSuccess(() -> Component.literal(
                "[CineDuct] DASH 流式直连: " + (value ? "开" : "关")
                        + (value && !usable
                        ? " （注意：FFmpeg 原生库不可用，当前会交回前置模组）"
                        : " （对之后打开的视频生效）")), false);
        return 1;
    }

    /** /cineduct-stream streaming <毫秒> —— 音画偏移微调：正数=画面提前。 */
    private static int setOffset(CommandSourceStack source, int offsetMs) {
        StreamConfig.streamOffsetMs = offsetMs;
        StreamConfig.save();
        source.sendSuccess(() -> Component.literal(
                "[CineDuct] 流式音画偏移: " + offsetMs + "ms (" + (offsetMs == 0 ? "不调整"
                        : offsetMs > 0 ? "画面提前" : "画面滞后") + ")"), false);
        return 1;
    }

    private static int showQuality(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(
                "[CineDuct] 当前清晰度: " + qualityName(StreamConfig.maxQn)
                        + "，可选: auto(自动) 360p 480p 720p 720p60 1080p 1080p60 4k 8k max"), false);
        return 1;
    }

    private static int setQuality(CommandSourceStack source, int qn) {
        StreamConfig.maxQn = qn;
        StreamConfig.save();
        source.sendSuccess(() -> Component.literal(
                "[CineDuct] 清晰度上限已设为 " + qualityName(qn)
                        + "（下一个打开的链接生效）"), false);
        return 1;
    }

    private static String qualityName(int qn) {
        return switch (qn) {
            case -1 -> "自动(按网速自适应)";
            case 0 -> "最高(不限)";
            case 16 -> "360P";
            case 32 -> "480P";
            case 64 -> "720P";
            case 74 -> "720P60";
            case 80 -> "1080P";
            case 116 -> "1080P60";
            case 120 -> "4K";
            case 127 -> "8K";
            default -> "qn" + qn;
        };
    }
}
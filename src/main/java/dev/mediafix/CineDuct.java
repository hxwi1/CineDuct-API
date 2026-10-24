package dev.mediafix;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.mediafix.command.StreamCommand;
import dev.mediafix.config.CookieHarden;
import dev.mediafix.config.BiliConfig;
import dev.mediafix.config.FfmpegConfig;
import dev.mediafix.ffmpeg.FfmpegRuntime;
import dev.mediafix.config.StreamConfig;
import dev.mediafix.player.Sessions;
import dev.mediafix.source.SourceResolvers;
import net.minecraft.commands.CommandSourceStack;

import java.net.URI;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * CineDuct —— **独立**的视频模组（不依赖任何前置 mod），**客户端与服务端都安装**。
 *
 * <p>职责划分很干净：
 * <ul>
 *   <li><b>服务端</b>：屏幕实体（{@code cineduct:screen}）与它的权威状态（放什么、从哪一刻开始、
 *       暂停/音量/距离范围），以及 {@code /cineduct screen} 指令与 {@code dev.mediafix.api} 门面。
 *       服务端不碰解码，也不懂 B 站协议。</li>
 *   <li><b>客户端</b>：解析直链、播放、渲染到屏幕实体上、按服务端锚点对齐进度。
 *       播放链路有两条，按可用性自动选择：
 *       <ol>
 *         <li><b>零拷贝</b>：画面与声音由独立 C++ 子进程产出，经 DXGI 共享纹理 +
 *             {@code WGL_NV_DX_interop2} 进到游戏的 OpenGL 纹理（`/cineduct subprocess on` 开启，见 native/README.md）；</li>
 *         <li><b>兜底（默认）</b>：进程内自研 FFmpeg 引擎（{@code dev.mediafix.engine}）。
 *             子进程没开、不可用、驱动不支持互操作时走这条，保证任何机器上都有画面。</li>
 *       </ol>
 *       两条路径共用 {@code dev.mediafix.player.MediaSession}，上层屏幕/指令/API 不感知差别。</li>
 * </ul>
 *
 * <p>解析侧可扩展：{@code dev.mediafix.source.SourceResolvers} 里注册的解析器按顺序尝试，
 * 内置 B 站解析器（wbi 签名 / 番剧 ep / 直播间）也走这条注册，附属 mod 与它完全平等。
 */
@Mod(CineDuct.MODID)
public final class CineDuct {
    public static final String MODID = "cineduct";
    /**
     * 本模组日志门面。
     * {@code info/warn/error} 与方法名和 SLF4J 一致（现有调用无需改动），
     * 但要区分"高频诊断"和"事件"时用 {@code LOGGER.diag(...)} —— 它只写 cineductlogs，不污染 latest.log。
     */
    public static final dev.mediafix.log.CineDuctLog LOGGER = new dev.mediafix.log.CineDuctLog();

    public CineDuct(IEventBus modEventBus, net.neoforged.fml.ModContainer modContainer) {
        // 注册表（屏幕实体）挂 MOD 事件总线
        CineDuctRegistry.ENTITY_TYPES.register(modEventBus);
        // 开放 API 门面：附属 mod 用 CineDuctApi.get() 拿到它
        dev.mediafix.api.CineDuctApi.install(new dev.mediafix.screen.ScreenApi());
        IEventBus buses = NeoForge.EVENT_BUS;
        // 屏幕指令注册在**服务端**分发器上：屏幕状态是服务端权威的（单人存档 = 集成服务器）
        buses.addListener(CineDuct::registerServerCommands);
        if (!FMLEnvironment.dist.isClient()) {
            // 服务端没有 CineDuctLog 那套文件日志（它是客户端初始化时开的），这里直接用 SLF4J
            org.slf4j.LoggerFactory.getLogger("cineduct").info(
                    "[CineDuct] 服务端已加载：屏幕实体已注册，/cineduct screen 可用；"
                            + "解析与播放都在客户端进行，双方都需安装本模组");
        }
        if (FMLEnvironment.dist.isClient()) {
            // 先开日志，后面所有诊断才有地方落
            dev.mediafix.config.LogConfig.load();
            dev.mediafix.log.CineDuctLog.init(
                    net.neoforged.fml.loading.FMLPaths.GAMEDIR.get(),
                    dev.mediafix.config.LogConfig.dir,
                    dev.mediafix.config.LogConfig.keepFiles,
                    dev.mediafix.config.LogConfig.mirrorToMain);
            dev.mediafix.log.CineDuctLog.setDiagEnabled(dev.mediafix.config.LogConfig.diagEnabled);
            StreamConfig.load();
            FfmpegConfig.load();
            BiliConfig.load();
            // 服务端控制通道（Paper 插件 → 客户端）：默认全关，见 docs/paper-control-payload.md
            dev.mediafix.net.CinePacketConfig.load();
            // 账号权限（清晰度/音轨上限）：先按缓存走，后台查一次 nav；登录/退出时会重新查。
            // 设置界面重建时同步读缓存，所以这里预热一次能避免"刚进游戏那一页上限偏低"。
            dev.mediafix.bili.BiliEntitlements.refreshAsync();
            // 客户端 API 门面：附属 mod 用 CineDuctClientApi.get() 读实际播放状态
            dev.mediafix.api.client.CineDuctClientApi.install(new dev.mediafix.client.ClientApiImpl());
            // 独立播放器 API：给"自带渲染、不需要屏幕实体"的附属 mod 用（通用，与屏幕那套互不影响）
            dev.mediafix.api.player.CinePlayerApi.install(new dev.mediafix.player.CinePlayerApiImpl());
            buses.addListener(CineDuct::registerClientCommands);
            buses.addListener(CineDuct::registerStreamCommands);
            buses.addListener(CineDuct::onServerStopped);
            buses.addListener(CineDuct::onClientTick);
            buses.addListener(CineDuct::onPlayerLoggedIn);
            // 退服/换服：清掉所有由服务端（Paper 插件）创建的播放 —— 不能"已经退服了还在放"
            buses.addListener(CineDuct::onClientLoggingOut);
            StringBuilder resolverNames = new StringBuilder();
            for (dev.mediafix.api.MediaSourceResolver r : SourceResolvers.all()) {
                if (resolverNames.length() > 0) resolverNames.append(", ");
                resolverNames.append(r.name());
            }
            LOGGER.info("[CineDuct] 已加载（独立模组，不依赖任何前置 mod）");
            LOGGER.info("[CineDuct] 源解析器: {}", resolverNames);
            if (dev.mediafix.config.FfmpegConfig.avOffsetMs != 0) {
                final int ms = dev.mediafix.config.FfmpegConfig.avOffsetMs;
                LOGGER.info("[CineDuct] 音画偏移: {} {}ms（补 HDMI/功放那段物理延迟；/cineduct av <ms> 现场调）",
                        ms < 0 ? "画面延后" : "画面提前", Math.abs(ms));
            }            String ver = version();
            LOGGER.info("[CineDuct] 本模组日志: {}", dev.mediafix.log.CineDuctLog.sessionFile());
            LOGGER.info("[CineDuct] CineDuct API {} 启动", ver);

            // 启动即打印状态，方便从日志判断"到底有没有启用"
            LOGGER.info("[CineDuct] 状态: 自研引擎=常开 DASH流式直连={} 清晰度={} 硬件解码={}",
                    StreamConfig.dashStreaming ? "开" : "关",
                    qualityText(),
                    FfmpegConfig.hwAccel ? "开" : "关");

            // 引擎常开：原生库可能要解压几十 MB，放后台线程，不阻塞游戏启动
            {
                Thread boot = new Thread(() -> {
                    boolean ok = FfmpegRuntime.ensure();
                    LOGGER.info("[CineDuct] FFmpeg 视频解码: {} ({})", ok ? "可用" : "不可用", FfmpegRuntime.status());
                }, "cineduct-ffmpeg-bootstrap");
                boot.setDaemon(true);
                boot.start();
            }
        }
    }

    /** 注册客户端指令：/cineduct status|streams|refresh|audio|login|help 等。 */
    private static void registerClientCommands(RegisterClientCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> cmd = net.minecraft.commands.Commands.literal("cineduct")
                .then(net.minecraft.commands.Commands.literal("login")
                        .executes(ctx -> startLogin(ctx.getSource()))
                        .then(net.minecraft.commands.Commands.literal("status")
                                .executes(ctx -> loginStatus(ctx.getSource())))
                        .then(net.minecraft.commands.Commands.literal("logout")
                                .executes(ctx -> logout(ctx.getSource()))))
                .then(net.minecraft.commands.Commands.literal("audio")
                        .executes(ctx -> showAudio(ctx.getSource()))
                        .then(net.minecraft.commands.Commands.literal("dolby")
                                .executes(ctx -> setAudioPref(ctx.getSource(), "dolby")))
                        .then(net.minecraft.commands.Commands.literal("hires")
                                .executes(ctx -> setAudioPref(ctx.getSource(), "hires")))
                        .then(net.minecraft.commands.Commands.literal("best")
                                .executes(ctx -> setAudioPref(ctx.getSource(), "best")))
                        .then(net.minecraft.commands.Commands.literal("mode")
                                .then(net.minecraft.commands.Commands.argument("mode",
                                                com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .suggests((ctx, builder) -> {
                                            builder.suggest("auto");
                                            builder.suggest("passthrough");
                                            builder.suggest("exclusive");
                                            builder.suggest("shared");
                                            return builder.buildFuture();
                                        })
                                        .executes(ctx -> setAudioMode(ctx.getSource(),
                                                com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "mode")))))
                        .then(net.minecraft.commands.Commands.literal("device")
                                .then(net.minecraft.commands.Commands.argument("selector",
                                                com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                        .executes(ctx -> setAudioDevice(ctx.getSource(),
                                                com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "selector"))))))
                .then(net.minecraft.commands.Commands.literal("streams")
                        .executes(ctx -> showStreams(ctx.getSource())))
                .then(net.minecraft.commands.Commands.literal("refresh")
                        .executes(ctx -> refreshPlayback(ctx.getSource(), false))
                        .then(net.minecraft.commands.Commands.literal("full")
                                .executes(ctx -> refreshPlayback(ctx.getSource(), true)))
                        .then(net.minecraft.commands.Commands.literal("fallback")
                                .executes(ctx -> fallbackToJava(ctx.getSource()))))
                .then(net.minecraft.commands.Commands.literal("subprocess")
                        .executes(ctx -> showTransportStatus(ctx.getSource()))
                        .then(net.minecraft.commands.Commands.literal("on")
                                .executes(ctx -> setSubprocessTransport(ctx.getSource(), true)))
                        .then(net.minecraft.commands.Commands.literal("off")
                                .executes(ctx -> setSubprocessTransport(ctx.getSource(), false)))
                        .then(net.minecraft.commands.Commands.literal("status")
                                .executes(ctx -> showTransportStatus(ctx.getSource()))))
                .then(net.minecraft.commands.Commands.literal("fallback")
                        .executes(ctx -> fallbackToJavaAll(ctx.getSource(), true))
                        .then(net.minecraft.commands.Commands.literal("on")
                                .executes(ctx -> fallbackToJavaAll(ctx.getSource(), true)))
                        .then(net.minecraft.commands.Commands.literal("off")
                                .executes(ctx -> fallbackToJavaAll(ctx.getSource(), false)))
                        .then(net.minecraft.commands.Commands.literal("status")
                                .executes(ctx -> showTransportStatus(ctx.getSource()))))
                .then(net.minecraft.commands.Commands.literal("status")
                        .executes(ctx -> showStatus(ctx.getSource())))
                /*
                 * 服务端控制通道（Paper 插件 → 客户端）的调试入口。
                 *
                 * <p>没有它，验收这条通道就必须先起一个 Paper 服务器；有了它，
                 * 同一条"解析 → 分派 → 回执"路径可以在游戏里直接喂报文。
                 */
                .then(net.minecraft.commands.Commands.literal("packet")
                        .executes(ctx -> showPacketStatus(ctx.getSource()))
                        .then(net.minecraft.commands.Commands.literal("status")
                                .executes(ctx -> showPacketStatus(ctx.getSource())))
                        .then(net.minecraft.commands.Commands.literal("closeall")
                                .executes(ctx -> packetCloseAll(ctx.getSource())))
                        .then(net.minecraft.commands.Commands.literal("test")
                                .then(net.minecraft.commands.Commands.argument("json",
                                                com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                        .executes(ctx -> packetTest(ctx.getSource(),
                                                com.mojang.brigadier.arguments.StringArgumentType
                                                        .getString(ctx, "json"))))))
                .then(net.minecraft.commands.Commands.literal("player")
                        .executes(ctx -> showPlayers(ctx.getSource()))
                        .then(net.minecraft.commands.Commands.literal("list")
                                .executes(ctx -> showPlayers(ctx.getSource())))
                        .then(net.minecraft.commands.Commands.literal("open")
                                .then(net.minecraft.commands.Commands.argument("url",
                                                com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                        .executes(ctx -> openPlayer(ctx.getSource(),
                                                com.mojang.brigadier.arguments.StringArgumentType
                                                        .getString(ctx, "url")))))
                        .then(net.minecraft.commands.Commands.literal("info")
                                .then(net.minecraft.commands.Commands.argument("id",
                                                com.mojang.brigadier.arguments.LongArgumentType.longArg(1L))
                                        .executes(ctx -> showPlayer(ctx.getSource(),
                                                com.mojang.brigadier.arguments.LongArgumentType
                                                        .getLong(ctx, "id")))))
                        .then(net.minecraft.commands.Commands.literal("close")
                                .then(net.minecraft.commands.Commands.argument("id",
                                                com.mojang.brigadier.arguments.LongArgumentType.longArg(1L))
                                        .executes(ctx -> closePlayer(ctx.getSource(),
                                                com.mojang.brigadier.arguments.LongArgumentType
                                                        .getLong(ctx, "id")))))
                        .then(net.minecraft.commands.Commands.literal("closeall")
                                .executes(ctx -> closeAllPlayers(ctx.getSource())))
                        /*
                         * 播放控制（2026-10-18 补）。
                         *
                         * <p>为什么必须有：以前 `player` 只有 list/open/info/close/rebuild ——
                         * 用附属 mod（waterframes 显示器）看片时**没有任何办法暂停/跳转**，
                         * 而 `CinePlayer` 接口本来就有 pause/seek/volume。补上之后，
                         * 不经过附属 mod 也能直接控制播放器，方便判断"指令有没有到 C++"。
                         */
                        .then(net.minecraft.commands.Commands.literal("pause")
                                .then(net.minecraft.commands.Commands.argument("id",
                                                com.mojang.brigadier.arguments.LongArgumentType.longArg(1L))
                                        .executes(ctx -> playerPause(ctx.getSource(),
                                                com.mojang.brigadier.arguments.LongArgumentType.getLong(ctx, "id"),
                                                true))))
                        .then(net.minecraft.commands.Commands.literal("play")
                                .then(net.minecraft.commands.Commands.argument("id",
                                                com.mojang.brigadier.arguments.LongArgumentType.longArg(1L))
                                        .executes(ctx -> playerPause(ctx.getSource(),
                                                com.mojang.brigadier.arguments.LongArgumentType.getLong(ctx, "id"),
                                                false))))
                        .then(net.minecraft.commands.Commands.literal("seek")
                                .then(net.minecraft.commands.Commands.argument("id",
                                                com.mojang.brigadier.arguments.LongArgumentType.longArg(1L))
                                        .then(net.minecraft.commands.Commands.argument("seconds",
                                                        com.mojang.brigadier.arguments.FloatArgumentType
                                                                .floatArg(0.0f))
                                                .executes(ctx -> playerSeek(ctx.getSource(),
                                                        com.mojang.brigadier.arguments.LongArgumentType
                                                                .getLong(ctx, "id"),
                                                        com.mojang.brigadier.arguments.FloatArgumentType
                                                                .getFloat(ctx, "seconds"))))))
                        .then(net.minecraft.commands.Commands.literal("volume")
                                .then(net.minecraft.commands.Commands.argument("id",
                                                com.mojang.brigadier.arguments.LongArgumentType.longArg(1L))
                                        .then(net.minecraft.commands.Commands.argument("percent",
                                                        com.mojang.brigadier.arguments.IntegerArgumentType
                                                                .integer(0, 100))
                                                .executes(ctx -> playerVolume(ctx.getSource(),
                                                        com.mojang.brigadier.arguments.LongArgumentType
                                                                .getLong(ctx, "id"),
                                                        com.mojang.brigadier.arguments.IntegerArgumentType
                                                                .getInteger(ctx, "percent"))))))
                        .then(net.minecraft.commands.Commands.literal("rebuild")
                                .then(net.minecraft.commands.Commands.argument("id",
                                                com.mojang.brigadier.arguments.LongArgumentType.longArg(1L))
                                        .executes(ctx -> rebuildPlayer(ctx.getSource(),
                                                com.mojang.brigadier.arguments.LongArgumentType.getLong(ctx, "id")))))
                        .then(net.minecraft.commands.Commands.literal("stats")
                                .then(net.minecraft.commands.Commands.argument("id",
                                                com.mojang.brigadier.arguments.LongArgumentType.longArg(1L))
                                        .executes(ctx -> showPlayerStats(ctx.getSource(),
                                                com.mojang.brigadier.arguments.LongArgumentType.getLong(ctx, "id")))))
                        .then(net.minecraft.commands.Commands.literal("aspect")
                                .then(net.minecraft.commands.Commands.argument("id",
                                                com.mojang.brigadier.arguments.LongArgumentType.longArg(1L))
                                        .then(net.minecraft.commands.Commands.argument("aspect",
                                                        com.mojang.brigadier.arguments.FloatArgumentType.floatArg(0.0f, 16.0f))
                                                .executes(ctx -> setPlayerAspect(ctx.getSource(),
                                                        com.mojang.brigadier.arguments.LongArgumentType.getLong(ctx, "id"),
                                                        com.mojang.brigadier.arguments.FloatArgumentType.getFloat(ctx, "aspect")))))))
                .then(net.minecraft.commands.Commands.literal("api")
                        .executes(ctx -> showApi(ctx.getSource())))
                /*
                 * /cineduct host ... —— 子进程实例管理（见 docs/host-uuid.md §6）。
                 *
                 * 寻址支持：完整 UUID / 前 8 位短 UUID（session 或 instance 都认）/ #会话id。
                 * 以前只能看 Sessions.last() 一条，多屏时无法定点管理。
                 */
                .then(net.minecraft.commands.Commands.literal("host")
                        .executes(ctx -> showHostList(ctx.getSource()))
                        .then(net.minecraft.commands.Commands.literal("list")
                                .executes(ctx -> showHostList(ctx.getSource())))
                        .then(net.minecraft.commands.Commands.literal("info")
                                .then(net.minecraft.commands.Commands.argument("target",
                                                com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .executes(ctx -> showHostInfo(ctx.getSource(),
                                                com.mojang.brigadier.arguments.StringArgumentType
                                                        .getString(ctx, "target")))))
                        .then(net.minecraft.commands.Commands.literal("kill")
                                .then(net.minecraft.commands.Commands.argument("target",
                                                com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .executes(ctx -> killHost(ctx.getSource(),
                                                com.mojang.brigadier.arguments.StringArgumentType
                                                        .getString(ctx, "target")))))
                        .then(net.minecraft.commands.Commands.literal("restart")
                                .then(net.minecraft.commands.Commands.argument("target",
                                                com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .executes(ctx -> restartHost(ctx.getSource(),
                                                com.mojang.brigadier.arguments.StringArgumentType
                                                        .getString(ctx, "target"))))))
                .then(net.minecraft.commands.Commands.literal("av")
                        .executes(ctx -> showAvOffset(ctx.getSource()))
                        .then(net.minecraft.commands.Commands.argument("ms",
                                        com.mojang.brigadier.arguments.IntegerArgumentType.integer(-5000, 5000))
                                .executes(ctx -> setAvOffset(ctx.getSource(),
                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "ms")))))
                .then(net.minecraft.commands.Commands.literal("notice")
                        .executes(ctx -> showNotice(ctx.getSource()))
                        .then(net.minecraft.commands.Commands.literal("on")
                                .executes(ctx -> setNotice(ctx.getSource(), true)))
                        .then(net.minecraft.commands.Commands.literal("off")
                                .executes(ctx -> setNotice(ctx.getSource(), false)))
                        .then(net.minecraft.commands.Commands.literal("reset")
                                .executes(ctx -> resetNotice(ctx.getSource()))))
                .then(net.minecraft.commands.Commands.literal("help")
                        .executes(ctx -> showHelp(ctx.getSource())));

        event.getDispatcher().register(cmd);
    }

    /** 独立指令：/cineduct-stream [on|off] —— 切流式播放（与登录逻辑分离）。 */
    private static void registerStreamCommands(RegisterClientCommandsEvent event) {
        StreamCommand.register(event);
    }

    /** 屏幕指令（服务端）：/cineduct screen create|list|remove|info|source|play|pause|seek|volume|range|loop|size|quality。 */
    private static void registerServerCommands(net.neoforged.neoforge.event.RegisterCommandsEvent event) {
        dev.mediafix.command.ScreenCommand.register(event.getDispatcher());
    }
    /** 离开世界/服务端停止时主动收掉播放会话，别把原生资源留到 JVM 关闭阶段。 */
    private static void onServerStopped(ServerStoppedEvent event) {
        try {
            Sessions.closeAll();
        } catch (Throwable t) {
            LOGGER.warn("[CineDuct] 退出世界时关闭会话失败", t);
        }
    }

    /**
     * 离开世界时主动收掉播放引擎。
     *
     * <p>实测 {@code ServerStoppedEvent} 在"退出世界"时并不一定会走到（崩溃时 5 条引擎线程
     * 全都还活着，说明压根没关过）。引擎线程和 FFmpeg 原生上下文如果一直留到 JVM 关闭阶段，
     * MC 那边正在拆 GL/JIT/类加载器，我们这边还在跑原生解码，出问题的概率就高了。
     * 这里用"上一 tick 在世界里、这一 tick 不在"来判断，保证在游戏还活着的时候收摊。
     */
    private static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        boolean inWorld = net.minecraft.client.Minecraft.getInstance() != null
                && net.minecraft.client.Minecraft.getInstance().level != null;
        if (levelWasLoaded && !inWorld) {
            try {
                Sessions.closeAll();
            } catch (Throwable t) {
                LOGGER.warn("[CineDuct] 离开世界时关闭会话失败", t);
            }
        }
        levelWasLoaded = inWorld;
        // 屏幕实体卸载/删除后，客户端侧状态（会话 + GL 纹理）要跟着收
        try {
            dev.mediafix.client.ScreenClientState.prune();
            // 互操作导入的 GL 资源只能在渲染线程释放（本回调就是渲染线程）
            Sessions.drainGlRelease();
            // 子进程崩了/卡死了就自动重启（判定很便宜，重启在后台线程）
            Sessions.tickHealth();
            // 服务端控制通道的状态回执（心跳 / 状态跳变；没启用时是一次空判断）
            // 虚拟屏的距离门控：进范围开播、出范围释放（没有虚拟屏时是一次空判断）
            dev.mediafix.client.SlotPlayback.tick();
        } catch (Throwable t) {
            LOGGER.warn("[CineDuct] 清理屏幕客户端状态失败", t);
        }
    }

    private static boolean levelWasLoaded;

    /**
     * 退服 / 换服：把服务端控制通道开出来的播放全部关掉。
     *
     * <p>为什么必须有：{@code CinePlayer} 的生命周期本来由调用方管，而这里的调用方是
     * "已经离开的那个服务器" —— 不断开就会留下"退服了还在放"的会话（还占着声卡）。
     */
    private static void onClientLoggingOut(
            net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        try {
            dev.mediafix.net.CineControlHandler.closeAll("退服/换服");
            dev.mediafix.client.SlotPlayback.closeAll("退服/换服");
        } catch (Throwable t) {
            LOGGER.warn("[CineDuct] 退服清理服务端播放失败", t);
        }
    }

    /** 进世界后：收紧 Cookie 文件权限，并一次性提示登录态仅限本账号、勿外传。 */
    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        CookieHarden.harden();
        if (CookieHarden.hasCookie() && CookieHarden.shouldNotice()) {
            Player player = event.getEntity();
            if (player != null) {
                player.displayClientMessage(Component.literal(
                        "[CineDuct] 检测到B站登录态(Cookie=账号凭证)。已给密钥文件限权，仅当前系统用户可读，请勿泄露给他人。"),
                        false);
            }
        }
    }

    /**
     * /cineduct login —— B 站扫码登录。
     *
     * <p>把二维码内容 URL 交给玩家：聊天栏给出链接并自动用系统浏览器打开（浏览器若已登录 B 站，
     * 点一下"确认"就等同扫码）；也可以用手机扫这个链接对应的二维码。随后后台轮询直到确认。
     */
    private static int startLogin(CommandSourceStack source) {
        sendSuccess(source, "[CineDuct] 正在请求登录二维码…");
        Thread t = new Thread(() -> {
            String url = dev.mediafix.bili.BiliLogin.start(msg -> notify(source, msg));
            if (url == null) return;
            // 二维码内容用手机 B 站 App 扫；这里画成 PNG 并交给系统看图器打开（聊天栏字体不等宽，
            // 用字符拼二维码会变形，扫不出来）
            java.nio.file.Path png = dev.mediafix.bili.BiliLogin.renderQrPng(url);
            if (png != null) {
                notify(source, "请用手机 B 站 App 扫描刚弹出的二维码图片（文件: " + png + "）");
                try {
                    net.minecraft.Util.getPlatform().openFile(png.toFile());
                } catch (Throwable e) {
                    notify(source, "（自动打开图片失败，请手动打开上面的文件）");
                }
            } else {
                notify(source, "二维码图片生成失败，请手动在手机浏览器打开下面的链接：");
                notify(source, url);
            }
        }, "cineduct-login-request");
        t.setDaemon(true);
        t.start();
        return 1;
    }

    /** 异步回报：命令回执必须回到主线程。 */
    private static void notify(CommandSourceStack source, String message) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc != null) {
            mc.execute(() -> source.sendSuccess(() -> Component.literal("[CineDuct] " + message), false));
        }
    }

    /** /cineduct login status —— 查登录态（昵称/UID/大会员）。 */
    private static int loginStatus(CommandSourceStack source) {
        sendSuccess(source, "[CineDuct] 正在查询登录态…");
        Thread t = new Thread(() -> {
            String s = dev.mediafix.bili.BiliLogin.status();
            notify(source, s == null ? "未登录（/cineduct login 扫码登录）" : "已登录: " + s);
        }, "cineduct-login-status");
        t.setDaemon(true);
        t.start();
        return 1;
    }

    /** /cineduct login logout —— 清除登录态。 */
    private static int logout(CommandSourceStack source) {
        dev.mediafix.bili.BiliLogin.logout();
        dev.mediafix.bili.BiliLogin.cancel();
        sendSuccess(source, "[CineDuct] 已清除登录态（重启后不再带 Cookie 请求）");
        return 1;
    }

    /**
     * /cineduct refresh [full] —— 手动刷新当前视频。
     *
     * <p>普通模式：重新解析直链 + 无缝换链（视频与音频两条链一起换，保持播放位置、声音不断）。
     * 这条路径与"直链到期自动续期"完全相同，只是由玩家主动触发，
     * 用来救画面卡住/黑屏/直链失效这类只能靠换链恢复的情况。
     *
     * <p>full 模式：整个引擎重建（丢掉全部缓冲重新打开），换链都救不回来时的最后手段。
     *
     * <p>重新解析要发网络请求，所以放在后台线程；结果回到主线程提示。
     */
    /**
     * `/cineduct subprocess on|off` —— **永久**开关：用不用 C++ 子进程播放。
     *
     * <p>与 {@code /cineduct fallback} 的分工：
     * <ul>
     *   <li>{@code subprocess on|off}：写进 {@code cineduct-ffmpeg.json}，**永久生效**，重启也还在；</li>
     *   <li>{@code fallback}：只在**本局游戏**里临时把播放压到进程内引擎（内存开关，重启即失效）。</li>
     * </ul>
     *
     * <p>默认是**关**：默认播放方式就是进程内 Java 引擎（见 {@code FfmpegConfig.gpuHostEnabled} 的说明）。
     * 开关只影响**之后新建**的会话 —— 已经跑着的会话不会自己换路（换路要重开会话，
     * 子进程↔进程内没有"原地切换"的路径），所以回执里会让人重开那块屏。
     */
    private static int setSubprocessTransport(CommandSourceStack source, boolean on) {
        dev.mediafix.config.FfmpegConfig.gpuHostEnabled = on;
        dev.mediafix.config.FfmpegConfig.save();
        if (on && dev.mediafix.player.Sessions.forceInProcess()) {
            // 本局那个临时开关如果还开着，"打开子进程"根本不会生效 —— 顺手清掉，别让人以为命令坏了
            dev.mediafix.player.Sessions.setForceInProcess(false);
            sendSuccess(source, "[CineDuct] （顺带关掉了本局临时回退开关，否则子进程不会生效）");
        }
        if (on) {
            sendSuccess(source, "[CineDuct] 已启用 C++ 子进程播放（DXGI 零拷贝 + WASAPI），并写进 "
                    + "cineduct-ffmpeg.json —— **永久生效**。");
            sendSuccess(source, "[CineDuct]   台机器上跑不起来时会自动降级回进程内引擎（日志会写明原因）；"
                    + "当前正在播的会话不会自己换路，重开那块屏/播放器即可。");
        } else {
            sendSuccess(source, "[CineDuct] 已改为只用进程内 Java 引擎（默认），并写进 cineduct-ffmpeg.json "
                    + "—— **永久生效**。当前正在播的会话不受影响。");
        }
        return 1;
    }

    /**
     * `/cineduct fallback` —— **手动把播放退回 Java 进程内引擎**（`/cineduct fallback off` 取消）。
     *
     * <p>与 {@code /cineduct refresh fallback} 的区别：那个是"回退最近一条会话"的诊断入口；
     * 这个是**这一局的播放方式开关** —— 把当前所有会话立刻切过去，并让本局之后新建的会话
     * 也走进程内引擎（否则"回退完再开一块屏"又会起子进程，开关等于没用）。
     *
     * <p>★ **只在内存里，重启游戏即失效**：不写任何配置文件。想**永久**改默认（用子进程还是用引擎）
     * 请用 {@code /cineduct subprocess on|off}（那个会写 cineduct-ffmpeg.json）。
     *
     * <p>切过去要收掉子进程 + 重开两条链（网络素材 1~2 秒），所以放后台线程做，
     * 结果回到主线程提示（和 fallbackToJava 一个套路）。
     */
    private static int fallbackToJavaAll(CommandSourceStack source, boolean on) {
        dev.mediafix.player.Sessions.setForceInProcess(on);
        if (!on) {
            sendSuccess(source, "[CineDuct] 已取消手动回退开关：之后新建的会话恢复自动选择。"
                    + "已经在 Java 引擎上跑的会话不会自己切回子进程 —— 想试零拷贝请重开那个屏幕。");
            return 1;
        }
        final int sessions = dev.mediafix.player.Sessions.count();
        sendSuccess(source, "[CineDuct] 已切到进程内引擎（Java 播放）" + (sessions > 0
                ? "，正在收掉 " + sessions + " 条会话的子进程…（位置/暂停/音量会搬过去，画面应停在原处继续）"
                : "（当前没有会话；本局之后开的屏幕都会用 Java 引擎）"));
        Thread t = new Thread(() -> {
            int n;
            try {
                n = dev.mediafix.player.Sessions.fallbackAllNow();
            } catch (Throwable ex) {
                CineDuct.LOGGER.warn("[CineDuct] 手动回退（全部）异常", ex);
                n = 0;
            }
            final int done = n;
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            mc.execute(() -> source.sendSuccess(() -> Component.literal(done > 0
                    ? "[CineDuct] 已回退 " + done + " 条会话到进程内引擎。检查位置/暂停/声音是否都对；"
                      + "本局内不会再用子进程，重启游戏自动恢复（也可 /cineduct fallback off 只取消开关）。"
                    : "[CineDuct] 没有会话需要回退（可能本来就跑在 Java 引擎上，或已经没有会话）。"), false));
        }, "cineduct-fallback-all");
        t.setDaemon(true);
        t.start();
        return 1;
    }

    /** `/cineduct fallback status` 与 `/cineduct subprocess status` 共用：播放方式现状。 */
    private static int showTransportStatus(CommandSourceStack source) {
        final boolean cfg = dev.mediafix.config.FfmpegConfig.gpuHostEnabled;
        final boolean temp = dev.mediafix.player.Sessions.forceInProcess();
        sendSuccess(source, "[CineDuct] 子进程播放（零拷贝 + WASAPI）: " + (cfg
                ? "开（cineduct-ffmpeg.json，永久）"
                : "关（默认）—— 关闭时一律走进程内 Java 引擎"));
        sendSuccess(source, "[CineDuct] 本局临时回退（/cineduct fallback）: " + (temp
                ? "开 → 本局新建的会话也一律走 Java 引擎（重启即失效）"
                : "关"));
        sendSuccess(source, "[CineDuct] 生效结果: " + ((temp || !cfg)
                ? "进程内 Java 引擎"
                : "子进程（起不来/装不上会自动降级回 Java 引擎）"));
        java.util.List<dev.mediafix.player.MediaSession> all = dev.mediafix.player.Sessions.all();
        if (all.isEmpty()) {
            sendSuccess(source, "[CineDuct]   当前没有播放会话");
            return 1;
        }
        for (dev.mediafix.player.MediaSession s : all) {
            sendSuccess(source, String.format("[CineDuct]   会话 #%d  传输=%s  位置=%dms  暂停=%s",
                    s.id(), s.transportId(), s.timeMs(), s.isPauseWanted() ? "是" : "否"));
        }
        return 1;
    }

    /**
     * `/cineduct refresh fallback` —— **手动触发子进程→进程内引擎的回退**（诊断/自测用）。
     *
     * <p>为什么需要一个人工入口：回退这条路只在"子进程真的坏了"时才走（崩溃/卡死/互操作建不起来），
     * 平时永远看不见；而它恰恰是最需要在需要时可靠的一条路 —— 位置有没有接住、暂停/音量有没有丢、
     * 会不会两条链同时出声。手动跑一次就能当场验证整套交接（和"想验证重启就直接 taskkill"同理）。
     *
     * <p>放后台线程：要收掉子进程（强杀 + 等它退出）并打开两条新链，不能在主线程做。
     */
    private static int fallbackToJava(CommandSourceStack source) {
        dev.mediafix.player.MediaSession e = dev.mediafix.player.Sessions.last();
        if (e == null || e.isClosed()) {
            sendSuccess(source, "[CineDuct] 当前没有正在播放的屏幕（回退只对正在播的屏幕起作用）");
            return 0;
        }
        sendSuccess(source, "[CineDuct] 正在放弃子进程并切到进程内引擎…"
                + "（看日志里的「回退前记下位置 / 从这里续播」两行；画面应停在原位置继续）");
        Thread t = new Thread(() -> {
            boolean ok;
            try {
                ok = dev.mediafix.player.Sessions.fallbackLast();
            } catch (Throwable ex) {
                CineDuct.LOGGER.warn("[CineDuct] 手动回退异常", ex);
                ok = false;
            }
            final boolean okFinal = ok;
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            mc.execute(() -> source.sendSuccess(() -> Component.literal(okFinal
                    ? "[CineDuct] 已切到进程内引擎（Java 播放）。若位置/暂停/声音都对，说明回退是可靠的；"
                      + "想回子进程模式请重开这个屏幕"
                    : "[CineDuct] 回退失败：见模组日志（可能本来就在 Java 模式，或会话已关闭）"), false));
        }, "cineduct-manual-fallback");
        t.setDaemon(true);
        t.start();
        return 1;
    }

    private static int refreshPlayback(CommandSourceStack source, boolean full) {
        dev.mediafix.player.MediaSession e = dev.mediafix.player.Sessions.last();
        if (e == null || e.isClosed()) {
            sendSuccess(source, "[CineDuct] 当前没有正在播放的屏幕（刷新只对正在播的屏幕起作用）");
            return 0;
        }
        if (full) {
            sendSuccess(source, "[CineDuct] 强制重建引擎中…（会重新打开两条链，位置尽量保留）");
        } else {
            sendSuccess(source, "[CineDuct] 正在重新解析直链并无缝换链…");
        }
        Thread t = new Thread(() -> {
            boolean ok;
            String detail;
            if (full) {
                ok = dev.mediafix.player.Sessions.rebuildLast();
                detail = ok ? "引擎已重建" : "重建失败：找不到对应的会话";
            } else {
                ok = e.refresh();
                detail = ok ? "已换上新鲜直链（无缝，位置不变）" : "重新解析失败：检查登录态或稍后重试";
            }
            final boolean okFinal = ok;
            final String msg = detail;
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            mc.execute(() -> source.sendSuccess(
                    () -> Component.literal("[CineDuct] 手动刷新" + (okFinal ? "完成" : "失败") + "：" + msg), false));
        }, "cineduct-manual-refresh");
        t.setDaemon(true);
        t.start();
        return 1;
    }

    /**
     * /cineduct audio —— 音频链现状：选中的音轨、源格式与输出格式、设备能吃的最大声道数。
     *
     * <p>杜比全景声是 6ch E-AC-3；如果这里显示"输出 2ch"，说明系统里那块输出设备只暴露了立体声，
     * 我们按标准矩阵下混（不是丢声道）。想让环绕出声，得先让系统把设备配成 5.1/7.1。
     */
    private static int showAudio(CommandSourceStack source) {
        sendSuccess(source, "[CineDuct] 音频偏好: " + StreamConfig.audioPreference
                + "（dolby=杜比全景声优先 / hires=Hi-Res无损优先 / best=只取普通最高码率）");
        sendSuccess(source, "[CineDuct] 本次解析选中: " + dev.mediafix.proxy.DashResolver.lastAudioSummary());
        dev.mediafix.player.MediaSession e = dev.mediafix.player.Sessions.last();
        sendSuccess(source, "[CineDuct] 会话音频链: " + (e == null ? "尚未启动" : e.audioSummary()));
        sendSuccess(source, "[CineDuct] 输出形态: " + FfmpegConfig.audioMode
                + "（当前会话: " + (e == null ? "-" : e.audioModeName()) + "）"
                + (e != null && e.volumeLocked() ? " —— 直通模式，音量由接收端（功放）控制" : ""));
        if (e != null && e.hostSession() != null && e.hostSession().isReady()) {
            dev.mediafix.host.HostSession h = e.hostSession();
            sendSuccess(source, "[CineDuct] 输出设备: " + h.audioModeName() + " / "
                    + h.audioContentRate() + "Hz 内容 → " + h.audioSampleRate() + "Hz 链路, "
                    + h.audioChannels() + "ch @" + h.audioDeviceName());
            /*
             * 空间音效（杜比全景声）状态 —— 程序化读出来的确定答案，不用靠耳朵判断。
             * 来源：WinRT SpatialAudioDeviceConfiguration（用户选择 / 系统当前在跑）。
             */
            sendSuccess(source, "[CineDuct] 空间音效(Atmos): " + switch (h.spatialAudioState()) {
                case 1 -> "★ 已启用 —— " + h.spatialAudioFormat();
                case 0 -> "未启用（该端点支持空间音效，但当前没选任何格式）";
                case 3 -> "该端点不支持 Windows 空间音效";
                default -> "查不到（系统未上报）";
            });
        }
        sendSuccess(source, "[CineDuct] 端点选择: "
                + (FfmpegConfig.audioDevice.isBlank() ? "系统默认" : FfmpegConfig.audioDevice));
        sendSuccess(source, "[CineDuct] 切换形态: /cineduct audio mode auto|passthrough|exclusive|shared"
                + "；切换端点: /cineduct audio device <名字或@编号>（会重建当前会话）");
        int max = dev.mediafix.engine.AudioSink.maxSupportedChannels(48000);
        sendSuccess(source, "[CineDuct] 本机输出能力: 最多 " + max + " 声道 @48kHz"
                + (max <= 2 ? "（Java Sound 只看到立体声设备；杜比会被下混成立体声）"
                            : "（可输出多声道，杜比按设备能力直出 5.1/7.1）"));
        for (String d : dev.mediafix.engine.AudioSink.describeDevices()) {
            if (d.contains("Direct Audio Device")) {
                sendSuccess(source, "   " + d);
            }
        }
        sendSuccess(source, "[CineDuct] 切换: /cineduct audio dolby|hires|best（下次播放生效）");
        return 1;
    }

    private static int setAudioPref(CommandSourceStack source, String pref) {
        StreamConfig.audioPreference = pref;
        StreamConfig.save();
        sendSuccess(source, "[CineDuct] 音频偏好已设为 " + pref + "（下次播放生效）");
        return 1;
    }

    /**
     * /cineduct audio mode &lt;auto|passthrough|exclusive|shared&gt; —— 切换音频输出形态。
     *
     * <p>形态在**开声卡那一刻**定死（格式、独占与否都改了），所以改完必须重建会话。
     * 重建会丢掉解码缓冲，画面会瞬间重来 —— 这是预期行为，不是 bug。
     *
     * <ul>
     *   <li>{@code passthrough}：压缩码流直通（IEC 61937），由功放硬件解码出全景声。
     *       需要端点支持独占模式；音轨必须是 AC-3 / E-AC-3 / TrueHD / DTS。**音量改不了**。</li>
     *   <li>{@code exclusive}：独占 PCM（位完美，绕过系统混音器），音量仍可调，但没有全景声。</li>
     *   <li>{@code shared}：共享模式，最兼容（经过系统混音器，可能被重采样）。</li>
     *   <li>{@code auto}：能直通就直通，否则独占，再否则共享。</li>
     * </ul>
     */
    private static int setAudioMode(CommandSourceStack source, String mode) {
        final String normalized = mode.toLowerCase(java.util.Locale.ROOT);
        if (!normalized.equals("auto") && !normalized.equals("passthrough")
                && !normalized.equals("exclusive") && !normalized.equals("shared")) {
            source.sendFailure(Component.literal("[CineDuct] 未知形态: " + mode
                    + "（可用 auto/passthrough/exclusive/shared）"));
            return 0;
        }
        FfmpegConfig.audioMode = normalized;
        FfmpegConfig.save();
        int rebuilt = 0;
        for (dev.mediafix.player.MediaSession s : dev.mediafix.player.Sessions.all()) {
            if (s.rebuild()) rebuilt++;
        }
        sendSuccess(source, "[CineDuct] 音频输出形态已设为 " + normalized + "，重建了 " + rebuilt
                + " 条会话（直通需要音轨本身是 Dolby/DTS 码流，且端点支持独占）");
        return 1;
    }

    /** /cineduct audio device &lt;选择串&gt; —— 换输出端点（空串 = 系统默认）。 */
    private static int setAudioDevice(CommandSourceStack source, String selector) {
        FfmpegConfig.audioDevice = "default".equalsIgnoreCase(selector) ? "" : selector;
        FfmpegConfig.save();
        int rebuilt = 0;
        for (dev.mediafix.player.MediaSession s : dev.mediafix.player.Sessions.all()) {
            if (s.rebuild()) rebuilt++;
        }
        sendSuccess(source, "[CineDuct] 输出端点已设为 "
                + (FfmpegConfig.audioDevice.isBlank() ? "系统默认" : FfmpegConfig.audioDevice)
                + "，重建了 " + rebuilt + " 条会话");
        return 1;
    }

    /**
     * /cineduct streams —— 列出当前视频实际可选的每一条流（清晰度/编码/真实分辨率/码率）。
     * 用来分辨"真 4K"与"标称 4K 实为 1080p"，以及看清 B 站到底给了多大的码率。
     */
    private static int showStreams(CommandSourceStack source) {
        java.util.List<dev.mediafix.abr.AbrLadder.Candidate> all = dev.mediafix.abr.AbrLadder.all();
        if (all.isEmpty()) {
            sendSuccess(source, "[CineDuct] 还没有解析记录：先播放一个 B 站视频，再执行本指令");
            return 1;
        }
        int cur = dev.mediafix.abr.AbrLadder.currentIndex();
        sendSuccess(source, "[CineDuct] 本视频可选流（共 " + all.size() + " 条，" + all.get(0).resolution() + " ~ "
                + all.get(all.size() - 1).resolution() + "）：");
        for (int i = 0; i < all.size(); i++) {
            dev.mediafix.abr.AbrLadder.Candidate c = all.get(i);
            sendSuccess(source, String.format("  %s %-14s %-11s %6dkbps  qn=%d",
                    i == cur ? "▶" : " ", c.label(), c.resolution(), c.bandwidthBps() / 1000, c.qn()));
        }
        sendSuccess(source, "[CineDuct] 自动模式会随网速在这些流之间切换（音频始终最高音质，不参与）");
        return 1;
    }

    // =====================================================================
    // /cineduct host ... —— 子进程实例管理（见 docs/host-uuid.md §6）
    // =====================================================================

    /** `/cineduct host [list]`：列出所有子进程实例。 */
    private static int showHostList(CommandSourceStack source) {
        final java.util.List<dev.mediafix.player.Sessions.HostView> views =
                dev.mediafix.player.Sessions.hostViews();
        if (views.isEmpty()) {
            sendSuccess(source, "[CineDuct] 当前没有子进程（会话数 "
                    + dev.mediafix.player.Sessions.count() + "，可能都在进程内引擎上）");
            return 0;
        }
        sendSuccess(source, "[CineDuct] 子进程 " + views.size()
                + " 个（寻址用 #会话id 或 UUID 前 8 位）：");
        for (dev.mediafix.player.Sessions.HostView v : views) {
            sendSuccess(source, String.format(
                    "[CineDuct]   #%d  %s  %s  pid=%d  %s  发布=%d 丢弃=%d  %s",
                    v.sessionId(), v.instanceShort().isEmpty() ? "--------" : v.instanceShort(),
                    v.alive() ? "存活" : "已退出", v.pid(), v.state(),
                    v.published(), v.dropped(), v.transport()));
        }
        return views.size();
    }

    /** `/cineduct host info <寻址>`：单个子进程实例的详情。 */
    private static int showHostInfo(CommandSourceStack source, String target) {
        final dev.mediafix.player.MediaSession s = dev.mediafix.player.Sessions.resolveHostTarget(target);
        if (s == null) {
            sendSuccess(source, "[CineDuct] 找不到子进程：" + target + "（用 /cineduct host list 看可寻址的短 UUID）");
            return 0;
        }
        final dev.mediafix.host.HostSession h = s.hostSession();
        sendSuccess(source, "[CineDuct] 会话 #" + s.id() + "  子进程 pid=" + h.pid()
                + "（" + (h.alive() ? "存活" : "已退出") + "）  形态=" + h.state()
                + "  传输=" + s.transport());
        sendSuccess(source, "[CineDuct]   sessionUuid=" + s.sessionUuid());
        sendSuccess(source, "[CineDuct]   instanceUuid=" + h.instanceUuid()
                + (h.sessionUuidFromRing().isEmpty() ? "（状态环里没有身份：子进程是旧版）" : ""));
        sendSuccess(source, "[CineDuct]   解码路径=" + h.decodeModeName()
                + "  缓冲位=" + (h.buffering() ? "1(暂无新帧)" : "0")
                + "  发布=" + h.publishedFrames() + " 帧  丢弃=" + h.droppedFrames()
                + "  缓冲=" + h.bufferSummary());
        sendSuccess(source, "[CineDuct]   音频: 水位 " + h.audioQueuedMs() + "ms  欠载 "
                + h.audioUnderruns() + " 次"
                + (h.audioStalled() ? "  ⚠ 时钟已改走挂钟" : "")
                + (h.audioFailed() ? "  ✘ 音频链已失败" : ""));
        sendSuccess(source, "[CineDuct]   音频链: 读失败 " + h.audioReadErrors() + " 次  重连 "
                + h.audioReconnects() + " 次  自动修复 " + h.audioRepairs() + " 次"
                + (s.audioStarvedForMs() > 0 ? "  ⚠ 目前仍饿着 " + s.audioStarvedForMs() + "ms" : ""));
        return 1;
    }

    /**
     * `/cineduct host kill <寻址>`：放弃这个子进程，**立刻**回退到进程内引擎（位置/音量交接过去）。
     *
     * <p>为什么不是"只杀不管"：杀了之后这条会话既没有子进程也没有引擎，屏幕会永远停在
     * "加载中" —— 那不是"杀掉一个进程"该有的结果。回退是把播放接过去，屏幕不黑。
     */
    private static int killHost(CommandSourceStack source, String target) {
        final dev.mediafix.player.MediaSession s = dev.mediafix.player.Sessions.resolveHostTarget(target);
        if (s == null) {
            sendSuccess(source, "[CineDuct] 找不到子进程：" + target);
            return 0;
        }
        final String shortId = s.hostInstanceUuid().replace("-", "");
        final boolean ok = s.fallbackToEngineNow("/cineduct host kill " + target);
        sendSuccess(source, ok
                ? "[CineDuct] 已放弃子进程 " + (shortId.length() >= 8 ? shortId.substring(0, 8) : shortId)
                        + "（会话 #" + s.id() + "），已回退到进程内引擎并从原位置继续"
                : "[CineDuct] 回退失败（会话 #" + s.id() + "），见日志");
        return ok ? 1 : 0;
    }

    /** `/cineduct host restart <寻址>`：换一个新子进程（位置保留，相当于屏幕那边的 refresh full）。 */
    private static int restartHost(CommandSourceStack source, String target) {
        final dev.mediafix.player.MediaSession s = dev.mediafix.player.Sessions.resolveHostTarget(target);
        if (s == null) {
            sendSuccess(source, "[CineDuct] 找不到子进程：" + target);
            return 0;
        }
        final boolean ok = s.rebuild();
        sendSuccess(source, ok
                ? "[CineDuct] 会话 #" + s.id() + " 的子进程已重建（位置尽量保留，画面会闪一下）"
                : "[CineDuct] 重建失败（会话 #" + s.id() + "），见日志");
        return ok ? 1 : 0;
    }

    /**
     * `/cineduct packet` —— 服务端控制通道现状：闸门判定链 + 当前由服务端创建的播放。
     *
     * <p>排查"为什么纸片服发过来的控制没反应"时先看这一行：多数情况是 `enabled=false`
     * 或者服务器地址不在白名单里（默认白名单为空 = 全拒）。
     */
    private static int showPacketStatus(CommandSourceStack source) {
        sendSuccess(source, "[CineDuct] 服务端控制通道: " + dev.mediafix.net.CineGate.summary());
        sendSuccess(source, "[CineDuct]   服务端 slot=" + dev.mediafix.net.CineControlHandler.slotCount()
                + "  最近 ack=" + dev.mediafix.net.CineControlHandler.lastAckSeq()
                + "  配置: <游戏目录>/cineduct-packet.json");
        for (dev.mediafix.net.CineControlHandler.Snapshot s : dev.mediafix.net.CineControlHandler.snapshot()) {
            sendSuccess(source, "[CineDuct]   slot=" + s.slot() + " " + s.state()
                    + "  位置=" + s.positionMs() + "ms  传输=" + s.transport()
                    + (s.err() == null || s.err().isEmpty() ? "" : "  错误=" + s.err()));
        }
        sendSuccess(source, "[CineDuct]   本地调试: /cineduct packet test <json>（跳过闸门，仅验收用）");
        return 1;
    }

    /** `/cineduct packet closeall` —— 手动清掉所有服务端创建的播放。 */
    private static int packetCloseAll(CommandSourceStack source) {
        dev.mediafix.net.CineControlHandler.closeAll("玩家手动清理");
        sendSuccess(source, "[CineDuct] 已关闭全部由服务端创建的播放");
        return 1;
    }

    /** `/cineduct packet test <json>` —— 本地喂一条报文（跳过闸门，走同一条处理路径）。 */
    private static int packetTest(CommandSourceStack source, String json) {
        dev.mediafix.net.CineControlHandler.handleLocal(json);
        sendSuccess(source, "[CineDuct] 已把报文喂给处理器（**跳过闸门**，只用于本地验收）");
        return 1;
    }

    /** /cineduct status —— 只读状态：引擎常开，这里只报告原生库与开关情况。 */    private static int showStatus(CommandSourceStack source) {        sendSuccess(source, "[CineDuct] 自研引擎: 常开（不可关闭 —— 关掉就没法播 B 站内容）"
                + " | 原生库: " + FfmpegRuntime.status());
        // 原生库不可用时顺手重试一次：临时性失败（DLL 被杀软短暂锁定、解压被中断）不用重启游戏
        if (!FfmpegRuntime.available()) {
            sendSuccess(source, "[CineDuct] 原生库当前不可用，正在重新尝试加载…");
            Thread t = new Thread(() -> {
                boolean ok = FfmpegRuntime.retry();
                notify(source, ok ? "原生库重新加载成功: " + FfmpegRuntime.status()
                        : "原生库仍不可用: " + FfmpegRuntime.status());
            }, "cineduct-ffmpeg-retry");
            t.setDaemon(true);
            t.start();
        }
        sendSuccess(source, "[CineDuct] DASH 流式直连: " + (StreamConfig.dashStreaming ? "开" : "关")
                + " | 清晰度: " + qualityText()
                + " | 音画偏移: " + FfmpegConfig.avOffsetMs + "ms（改 cineduct-ffmpeg.json 生效）");
        dev.mediafix.player.MediaSession e = dev.mediafix.player.Sessions.last();
        sendSuccess(source, e == null
                ? "[CineDuct] 本局还没播放过"
                : "[CineDuct] 本次播放: 状态=" + e.state() + " 位置=" + e.timeMs() + "ms"
                        + " | seek 执行 " + e.seekCount() + " 次，忽略原地 seek " + e.ignoredSeekCount()
                        + " 次（每次忽略都省下一次重开连接+清空音频缓冲）"
                        + (e.recoverCount() > 0
                                ? " | 子进程自动重启 " + e.recoverCount() + " 次（崩溃/卡死自愈）" : "")
                        + (e.audioDowngraded()
                                ? " | 音频已降级为共享模式（该端点独占会让音频引擎崩）" : ""));
        if (e != null && e.isLive()) {
            long latency = e.liveLatencyMs();
            sendSuccess(source, "[CineDuct] 直播: 音视频同一条流（原画）"
                    + " | 实时延迟 " + (latency < 0 ? "未锚定" : latency + "ms")
                    + " | 时间轴 " + e.liveElapsedMs() + "ms（开播以来）"
                    + " | seek/ABR 已停用，断流自动重连");
        }
        sendSuccess(source, "[CineDuct] 会话数: " + dev.mediafix.player.Sessions.count()
                + "（同一条源的多块屏幕共用一个会话）");
        if (e != null && e.hostSession() != null) {
            dev.mediafix.host.HostSession h = e.hostSession();
            sendSuccess(source, "[CineDuct] C++ 子进程: pid=" + h.pid()
                    + " 存活=" + (h.alive() ? "是" : "否") + " 形态=" + h.state()
                    + " 已发布=" + h.publishedFrames() + " 帧 丢弃=" + h.droppedFrames()
                    + (h.endedByStatus() ? "（已到片尾，停在最后一帧待命）" : ""));
            // v4 诊断：解码路径 / 缓冲 / 迟到丢帧 / 定位自愈 —— 全是从状态环零 IPC 读出来的
            //
            // 「缓冲位」= 宿主**此刻**的原始值（0/1，等时钟那一两帧也会翻，所以会抖）；
            // 它对外的播放状态是 400ms 迟滞之后的那个「状态=」（见 MediaSession.hostState）。
            // 两者摆在一起，才能分清"只是抖了一下"和"真的缓冲了"。
            sendSuccess(source, "[CineDuct]   解码路径=" + h.decodeModeName()
                    + "  缓冲位=" + (h.buffering() ? "1(暂无新帧)" : "0")
                    + "  迟到丢帧=" + h.droppedLateFrames()
                    + "  定位自愈=" + h.seekHealCount() + " 次"
                    + "  音画自愈=" + h.clockHealCount() + " 次");
            /*
             * v11：缓冲水位与"等缓存"。
             *
             * 这一行回答的是"卡的时候，到底还有多少存货、我们有没有主动停下来等"：
             *   * 水位 = 网络侧还没解码的秒数 + 已解码没呈现的秒数（目标默认 4 秒）；
             *   * 等缓存中 = 子进程已冻住时钟与声卡（位置不会往前跑）；
             *   * 消费者停 = 游戏进程多久没来取帧（这才是"客户端卡顿"的客观读数）。
             */
            sendSuccess(source, "[CineDuct]   缓冲: " + h.bufferSummary()
                    + (h.gateActive() ? "  ⏸ 等缓存中" : "")
                    + (h.gateCount() > 0
                            ? "  累计等缓存 " + h.gateCount() + " 次 / " + h.gateTotalMs() + "ms"
                            : ""));
            // v8：音频健康 —— 子进程模式下音频整条链都在子进程里，这是唯一能看到它的地方
            sendSuccess(source, "[CineDuct]   音频: 水位 " + h.audioQueuedMs() + "ms"
                    + "  欠载 " + h.audioUnderruns() + " 次"
                    + (h.audioStalled() ? "  ⚠ 时钟已改走挂钟（音频停滞，声音可能已中断）" : "")
                    + (h.audioFailed() ? "  ✘ 音频链已失败（这一路没有声音，画面照走）" : "")
                    + "  抖动拒绝 " + h.audioJitterRejects() + " 次");
            /*
             * v10：音频链本身还活着吗 —— 这是 2026-10-17 事故后加的一行。
             * 那次音频链把读超时当片尾、静默停摆 26 秒，而旧字段只有"欠载"（结果是没数据），
             * 看日志根本分不清"声卡没东西播"还是"链路已经死了"。
             */
            sendSuccess(source, "[CineDuct]   音频链: 读失败 " + h.audioReadErrors() + " 次"
                    + "  重连 " + h.audioReconnects() + " 次"
                    + "  自动修复 " + h.audioRepairs() + " 次"
                    + (h.audioReadErrors() > 0 || h.audioRepairs() > 0
                            ? "  ⚠ 说明音频链路断过（网络抖动或服务端断流）" : ""));
            // v5/v6：音画偏移要看**内容**，不是"画面 vs 音频时钟"（后者恒等于 0，永远看不出错位）
            sendSuccess(source, "[CineDuct]   音画偏移: 画面 " + h.videoPtsMs() + "ms / 音频时钟 "
                    + h.mediaPositionMs() + "ms；★ 真正能听出来的偏移在子进程日志（cineductlogs 里"
                    + " host 的『AV明细』与『重锚』行）—— 子进程默认会自己纠正，纠正次数见上面的「音画自愈」");
            sendSuccess(source, "[CineDuct] 自愈: 子进程崩溃/卡死会自动重启并从原位置续播"
                    + "（60 秒内最多 3 次，超了回退到进程内引擎）。想验证就直接杀它："
                    + "taskkill /f /pid " + h.pid() + " —— 观察日志与状态里的「重启」计数");
        }
        return 1;
    }

    /**
     * /cineduct player —— 独立播放器（{@code dev.mediafix.api.player}）的现场验证入口。
     *
     * <p>为什么要做成指令：独立播放器**没有屏幕实体**，附属 mod 用它时画面在人家自己的渲染里，
     * 出问题时没办法"看游戏里的屏"来定位。这里给一条不依赖任何附属 mod 的通道：
     * 开一个播放器 → 看状态/传输方式/分辨率/位置 → 关掉，功能是否真的通一眼就能看出来
     * （声音会真的响，解码与声卡都走同一条链路）。
     */
    private static int openPlayer(CommandSourceStack source, String url) {
        if (!dev.mediafix.api.player.CinePlayerApi.available()) {
            notify(source, "[CineDuct] 独立播放器 API 不可用（只在客户端加载）");
            return 0;
        }
        if (url == null || url.isBlank()) {
            notify(source, "[CineDuct] 用法: /cineduct player open <链接>");
            return 0;
        }
        try {
            dev.mediafix.api.player.CinePlayer player = dev.mediafix.api.player.CinePlayerApi.get()
                    .open(url.trim(), dev.mediafix.api.player.PlayerOptions.defaults());
            sendSuccess(source, "[CineDuct] 已打开独立播放器 #" + player.id()
                    + "（解析在后台线程，状态先看 LOADING）");
            sendSuccess(source, "[CineDuct] 详情: /cineduct player info " + player.id()
                    + "    关闭: /cineduct player close " + player.id());
            return 1;
        } catch (Throwable t) {
            notify(source, "[CineDuct] 打开独立播放器失败: " + t);
            return 0;
        }
    }

    private static int showPlayers(CommandSourceStack source) {
        if (!dev.mediafix.api.player.CinePlayerApi.available()) {
            notify(source, "[CineDuct] 独立播放器 API 不可用（只在客户端加载）");
            return 0;
        }
        final dev.mediafix.api.player.CinePlayerApi api = dev.mediafix.api.player.CinePlayerApi.get();
        java.util.List<dev.mediafix.api.player.CinePlayer> list = api.players();
        sendSuccess(source, "[CineDuct] 独立播放器 " + list.size() + " 个（会话总数 "
                + dev.mediafix.player.Sessions.count() + "）");
        if (list.isEmpty()) {
            sendSuccess(source, "[CineDuct] 用法: /cineduct player open <链接>");
            return 0;
        }
        for (dev.mediafix.api.player.CinePlayer p : list) {
            sendSuccess(source, "[CineDuct]   #" + p.id() + "  " + playerLine(p));
        }
        return list.size();
    }

    private static int showPlayer(CommandSourceStack source, long id) {
        if (!dev.mediafix.api.player.CinePlayerApi.available()) {
            notify(source, "[CineDuct] 独立播放器 API 不可用（只在客户端加载）");
            return 0;
        }
        java.util.Optional<dev.mediafix.api.player.CinePlayer> found =
                dev.mediafix.api.player.CinePlayerApi.get().player(id);
        if (found.isEmpty()) {
            notify(source, "[CineDuct] 没有 #" + id + " 这个独立播放器");
            return 0;
        }
        dev.mediafix.api.player.CinePlayer p = found.get();
        sendSuccess(source, "[CineDuct] 独立播放器 #" + p.id() + "  源: " + p.sourceUri());
        sendSuccess(source, "[CineDuct]   " + playerLine(p));
        sendSuccess(source, String.format(
                "[CineDuct]   分辨率 %dx%d  比例 %.3f  音量 %d%%  静音 %s  循环 %s  暂停 %s  音量锁定 %s",
                p.videoWidth(), p.videoHeight(), p.drawAspect(), p.volumePercent(),
                p.isMuted() ? "是" : "否", p.isRepeat() ? "开" : "关", p.isPaused() ? "是" : "否",
                p.volumeLocked() ? "是（直通，由功放控制）" : "否"));
        sendSuccess(source, String.format("[CineDuct]   显示面比例 %s   纹理比例 %.4f",
                p.displayAspect() > 0.01f
                        ? String.format("%.4f（黑边已烘焙进纹理）", p.displayAspect())
                        : "关（纹理 = 视频原始尺寸）",
                p.drawAspect()));
        if (p.isBroken()) notify(source, "[CineDuct]   错误: " + p.lastError());
        return 1;
    }

    /** 一行摘要（列表与详情共用）。 */
    private static String playerLine(dev.mediafix.api.player.CinePlayer p) {
        return String.format("%s%s  位置 %dms  时长 %s  传输 %s%s",
                p.state(),
                p.isLive() ? "（直播）" : "",
                p.positionMs(),
                p.durationMs() < 0 ? "未知" : (p.durationMs() + "ms"),
                p.transport(),
                p.lastError().isEmpty() ? "" : ("  错误=" + p.lastError()));
    }

    private static int closePlayer(CommandSourceStack source, long id) {
        if (!dev.mediafix.api.player.CinePlayerApi.available()) {
            notify(source, "[CineDuct] 独立播放器 API 不可用（只在客户端加载）");
            return 0;
        }
        java.util.Optional<dev.mediafix.api.player.CinePlayer> found =
                dev.mediafix.api.player.CinePlayerApi.get().player(id);
        if (found.isEmpty()) {
            notify(source, "[CineDuct] 没有 #" + id + " 这个独立播放器");
            return 0;
        }
        found.get().close();
        sendSuccess(source, "[CineDuct] 已关闭独立播放器 #" + id + "（共用同一会话的其它使用者不受影响）");
        return 1;
    }

    /**
     * `/cineduct player pause|play <id>`：暂停/继续一个独立播放器。
     *
     * <p>回执里会写**子进程当前状态**——这正是排查"指令有没有到 C++"的关键：
     * `子进程=已连 pid=…` 说明管道通了，值已下发；`子进程=无` 说明这个播放器的会话
     * 还在进程内引擎上（或者子进程还没起来），此时暂停走的是另一条路。
     */
    private static int playerPause(CommandSourceStack source, long id, boolean paused) {
        final dev.mediafix.api.player.CinePlayer player = findPlayer(source, id);
        if (player == null) return 0;
        player.pause(paused);
        final dev.mediafix.player.MediaSession session = dev.mediafix.player.Sessions.of(id);
        final dev.mediafix.host.HostSession host = session == null ? null : session.hostSession();
        sendSuccess(source, "[CineDuct] 播放器 #" + id + " → " + (paused ? "暂停" : "继续")
                + "（传输=" + player.transport()
                + "，子进程=" + (host == null ? "无" : (host.alive() ? "已连 pid=" + host.pid() : "已退出"))
                + "）");
        CineDuct.LOGGER.info("[CineDuct] 播放器 #{} 设置为 paused={}（{}）", id, paused,
                host == null ? "无子进程" : "子进程 pid=" + host.pid());
        return 1;
    }

    /** `/cineduct player seek <id> <秒>`：跳到指定位置。 */
    private static int playerSeek(CommandSourceStack source, long id, float seconds) {
        final dev.mediafix.api.player.CinePlayer player = findPlayer(source, id);
        if (player == null) return 0;
        final long targetMs = Math.max(0L, (long) (seconds * 1000.0f));
        player.seek(targetMs);
        sendSuccess(source, "[CineDuct] 播放器 #" + id + " → 跳转到 " + targetMs + "ms（时长 "
                + player.durationMs() + "ms，传输=" + player.transport() + "）");
        return 1;
    }

    /** `/cineduct player volume <id> <0~100>`：设音量（子进程模式下是给声卡下发增益）。 */
    private static int playerVolume(CommandSourceStack source, long id, int percent) {
        final dev.mediafix.api.player.CinePlayer player = findPlayer(source, id);
        if (player == null) return 0;
        player.setVolumePercent(percent);
        sendSuccess(source, "[CineDuct] 播放器 #" + id + " → 音量 " + percent + "%"
                + (player.volumePercent() >= 0 ? "（当前 " + player.volumePercent() + "%）" : ""));
        return 1;
    }

    /** 按 id 找独立播放器；找不到时给一句人话。 */
    private static dev.mediafix.api.player.CinePlayer findPlayer(CommandSourceStack source, long id) {
        if (!dev.mediafix.api.player.CinePlayerApi.available()) {
            notify(source, "[CineDuct] 独立播放器 API 不可用（只在客户端加载）");
            return null;
        }
        final java.util.Optional<dev.mediafix.api.player.CinePlayer> found =
                dev.mediafix.api.player.CinePlayerApi.get().player(id);
        if (found.isEmpty()) {
            notify(source, "[CineDuct] 没有 #" + id + " 这个独立播放器（/cineduct player list 看现有 id）");
            return null;
        }
        return found.get();
    }

    private static int closeAllPlayers(CommandSourceStack source) {        if (!dev.mediafix.api.player.CinePlayerApi.available()) {
            notify(source, "[CineDuct] 独立播放器 API 不可用（只在客户端加载）");
            return 0;
        }
        final java.util.List<dev.mediafix.api.player.CinePlayer> list =
                dev.mediafix.api.player.CinePlayerApi.get().players();
        for (dev.mediafix.api.player.CinePlayer p : list) p.close();
        sendSuccess(source, "[CineDuct] 已关闭全部独立播放器（" + list.size() + " 个）");
        return list.size();
    }

    /**
     * /cineduct player rebuild &lt;id&gt; —— 走正规重建路径（等价于屏幕那边的 /cineduct refresh full）。
     *
     * <p>和"关掉再开一个"的区别：保留会话身份、同源引用计数不受影响，
     * 所以同源多屏时**只有这条真的会换新子进程**（关掉再开会被会话复用接回去，等于空转）。
     */
    private static int rebuildPlayer(CommandSourceStack source, long id) {
        if (!dev.mediafix.api.player.CinePlayerApi.available()) {
            notify(source, "[CineDuct] 独立播放器 API 不可用（只在客户端加载）");
            return 0;
        }
        java.util.Optional<dev.mediafix.api.player.CinePlayer> found =
                dev.mediafix.api.player.CinePlayerApi.get().player(id);
        if (found.isEmpty()) {
            notify(source, "[CineDuct] 没有 #" + id + " 这个独立播放器");
            return 0;
        }
        found.get().rebuild();
        sendSuccess(source, "[CineDuct] 独立播放器 #" + id
                + " 已请求重建（丢缓冲重开，位置尽量保留；零拷贝会换一个新子进程）");
        return 1;
    }

    /** /cineduct player stats &lt;id&gt; —— 出帧/丢帧/等锁/子进程时长等现场数据。 */
    private static int showPlayerStats(CommandSourceStack source, long id) {
        if (!dev.mediafix.api.player.CinePlayerApi.available()) {
            notify(source, "[CineDuct] 独立播放器 API 不可用（只在客户端加载）");
            return 0;
        }
        java.util.Optional<dev.mediafix.api.player.CinePlayer> found =
                dev.mediafix.api.player.CinePlayerApi.get().player(id);
        if (found.isEmpty()) {
            notify(source, "[CineDuct] 没有 #" + id + " 这个独立播放器");
            return 0;
        }
        dev.mediafix.api.player.PlayerStatistics st = found.get().statistics();
        sendSuccess(source, String.format("[CineDuct] 独立播放器 #%d 统计（传输 %s%s）",
                id, st.transport(), st.zeroCopy() ? "／零拷贝" : ""));
        sendSuccess(source, String.format("[CineDuct]   已发布 %d 帧  丢弃 %d  等锁 last=%dus max=%dus  槽位 %d",
                st.publishedFrames(), st.droppedFrames(), st.lastAcquireUs(), st.maxAcquireUs(),
                st.slots()));
        sendSuccess(source, String.format("[CineDuct]   子进程 pid=%d 存活 %dms  重启次数 %d",
                st.hostPid(), st.hostUptimeMs(), st.restarts()));
        sendSuccess(source, String.format("[CineDuct]   分辨率 %dx%d  位置 %dms  时长 %s  直播 %s  延迟 %sms",
                st.videoWidth(), st.videoHeight(), st.positionMs(),
                st.durationMs() < 0 ? "未知" : (st.durationMs() + "ms"),
                st.live() ? "是" : "否", st.liveLatencyMs() < 0 ? "未知" : String.valueOf(st.liveLatencyMs())));
        sendSuccess(source, String.format("[CineDuct]   音频 %s  音量锁定 %s  错误 %s",
                st.audioMode().isEmpty() ? "?" : st.audioMode(),
                st.volumeLocked() ? "是（直通）" : "否",
                st.error().isEmpty() ? "无" : st.error()));
        return 1;
    }

    /**
     * /cineduct player aspect &lt;id&gt; &lt;比例&gt; —— 设置"显示面比例"，开启后黑边会烘焙进纹理。
     *
     * <p>比例 = 你的显示面宽/高（16:9 传 1.7778）。传 0 = 关掉信箱化。
     * 用来在游戏里直接验证"视频不会被拉伸"，不需要写附属 mod。
     */
    private static int setPlayerAspect(CommandSourceStack source, long id, float aspect) {
        if (!dev.mediafix.api.player.CinePlayerApi.available()) {
            notify(source, "[CineDuct] 独立播放器 API 不可用（只在客户端加载）");
            return 0;
        }
        java.util.Optional<dev.mediafix.api.player.CinePlayer> found =
                dev.mediafix.api.player.CinePlayerApi.get().player(id);
        if (found.isEmpty()) {
            notify(source, "[CineDuct] 没有 #" + id + " 这个独立播放器");
            return 0;
        }
        dev.mediafix.api.player.CinePlayer p = found.get();
        try {
            p.setDisplayAspect(aspect);
        } catch (Throwable t) {
            notify(source, "[CineDuct] 设置失败: " + t);
            return 0;
        }
        if (aspect <= 0.01f) {
            sendSuccess(source, "[CineDuct] 独立播放器 #" + id + " 已关闭信箱化（纹理回到视频原始尺寸）");
        } else {
            sendSuccess(source, String.format(
                    "[CineDuct] 独立播放器 #%d 显示面比例 = %.4f：黑边已烘焙进纹理，"
                            + "整张纹理铺满显示面即可（不会拉伸）；零拷贝路径会重开一次子进程以换输出尺寸",
                    id, aspect));
        }
        return 1;
    }

    /**
     * /cineduct av [毫秒] —— 音画偏移现场调（<b>正 = 画面提前，负 = 画面延后</b>）。
     *
     * <p>为什么需要它：画面是在"音频设备把这一段播出去"的时刻放出去的，而声音从设备缓冲到<b>耳朵</b>
     * 还要过 HDMI → 功放/回音壁的处理（几十到两三百毫秒），所以看起来<b>画面跑在声音前面</b>。
     * 这是物理延迟（不是逻辑错误），谁也测不准，只能一边看一边调 —— 所以做成运行期可调，
     * 改完立刻生效（零拷贝走控制通道动词，进程内引擎下一帧就读到新值），不用重启游戏/子进程。
     */
    private static int showAvOffset(CommandSourceStack source) {
        int ms = dev.mediafix.config.FfmpegConfig.avOffsetMs;
        sendSuccess(source, "[CineDuct] 音画偏移 = " + ms + "ms（"
                + (ms > 0 ? "画面提前" : ms < 0 ? "画面延后" : "关闭") + "）");
        sendSuccess(source, "[CineDuct] 画面比声音快就输负数（例：/cineduct av -120），慢慢逼近到对上为止；0 = 关闭");
        return ms;
    }

    private static int setAvOffset(CommandSourceStack source, int ms) {
        dev.mediafix.config.FfmpegConfig.avOffsetMs = ms;
        int applied = 0;
        for (dev.mediafix.player.MediaSession s : dev.mediafix.player.Sessions.all()) {
            try {
                s.setAvOffsetMs(ms);
                applied++;
            } catch (Throwable t) {
                LOGGER.warn("[CineDuct] 会话 #{} 设置音画偏移失败", s.id(), t);
            }
        }
        try {
            dev.mediafix.config.FfmpegConfig.save();
        } catch (Throwable t) {
            LOGGER.warn("[CineDuct] 音画偏移写回配置失败（本次仍已生效）", t);
        }
        sendSuccess(source, String.format(
                "[CineDuct] 音画偏移 = %dms（%s），已应用到 %d 条会话并写入配置",
                ms, ms > 0 ? "画面提前" : ms < 0 ? "画面延后" : "关闭", applied));
        return 1;
    }

    /**
     * /cineduct notice [on|off|reset] —— 玩家提示（聊天栏 [CineDuct] 消息）的开关与现场验证。
     *
     * <p>和 API（{@code CineDuctClientApi.setNoticesEnabled}）走的是同一个开关：
     * {@code off} 是**粘滞**的（本局内别人再 {@code on} 也不会打开），{@code reset} 是排障用的清粘滞位。
     */
    private static int showNotice(CommandSourceStack source) {
        boolean on = dev.mediafix.ui.Notice.enabled();
        sendSuccess(source, "[CineDuct] 玩家提示现在" + (on ? "开着" : "已被关闭（粘滞）")
                + "｜on = 打开 / off = 关闭（粘滞）/ reset = 清掉粘滞位恢复");
        return on ? 1 : 0;
    }

    private static int setNotice(CommandSourceStack source, boolean want) {
        boolean actual = dev.mediafix.ui.Notice.setEnabled(want);
        if (want && !actual) {
            sendSuccess(source, "[CineDuct] 打开提示的请求被忽略：已有调用方关闭过（要恢复用 /cineduct notice reset）");
            return 0;
        }
        sendSuccess(source, "[CineDuct] 玩家提示已" + (actual ? "打开" : "关闭")
                + (want ? "" : "（本局游戏内粘滞：其它 mod 再打开也不会生效）"));
        return 1;
    }

    private static int resetNotice(CommandSourceStack source) {
        dev.mediafix.ui.Notice.reset();
        sendSuccess(source, "[CineDuct] 玩家提示已恢复，粘滞位已清除（这条消息之后照常提示）");
        return 1;
    }

    /**
     * /cineduct api —— 给附属 mod 作者看的现状：API 版本、解析器清单、传输方式可用性、会话一览。
     */
    private static int showApi(CommandSourceStack source) {
        sendSuccess(source, "[CineDuct] API 版本: server=" + dev.mediafix.api.CineDuctApi.API_VERSION
                + " client=" + dev.mediafix.api.client.CineDuctClientApi.API_VERSION
                + " player=" + dev.mediafix.api.player.CinePlayerApi.API_VERSION
                + "（dev.mediafix.api 是唯一兼容承诺）");
        sendSuccess(source, "[CineDuct] 玩家提示: " + (dev.mediafix.ui.Notice.enabled() ? "开" : "关（粘滞，/cineduct notice reset 恢复）"));
        sendSuccess(source, "[CineDuct] 源解析器: " + String.join(", ",
                dev.mediafix.api.CineDuctApi.get().resolverNames()));
        if (dev.mediafix.api.player.CinePlayerApi.available()) {
            sendSuccess(source, "[CineDuct] 独立播放器: "
                    + dev.mediafix.api.player.CinePlayerApi.get().diagnostics());
        }
        if (dev.mediafix.api.client.CineDuctClientApi.available()) {
            sendSuccess(source, "[CineDuct] 客户端传输: "
                    + dev.mediafix.api.client.CineDuctClientApi.get().transportDiagnostics());
            for (java.util.UUID id : dev.mediafix.api.client.CineDuctClientApi.get().activeScreens()) {
                dev.mediafix.api.client.CineDuctClientApi.get().status(id).ifPresent(st ->
                        sendSuccess(source, String.format(
                                "  屏幕 %s  %s  %dms  transport=%s  发布=%d 丢弃=%d（取帧等锁 %dus）",
                                id.toString().substring(0, 8), st.state(), st.positionMs(),
                                st.transport(), st.publishedFrames(), st.droppedFrames(),
                                st.lastAcquireUs())));
            }
        }
        sendSuccess(source, "[CineDuct] 服务端屏幕数: "
                + dev.mediafix.api.CineDuctApi.get().screens(source.getLevel()).size());
        sendSuccess(source, "[CineDuct] 用法见 docs/API.md");
        return 1;
    }

    /** /cineduct help —— 显示所有可用子指令帮助。 */    private static int showHelp(CommandSourceStack source) {
        sendSuccess(source, "[CineDuct] CineDuct " + version() + " —— 自研 FFmpeg DASH 流式引擎");
        sendSuccess(source, "");
        sendSuccess(source, "【播放】");
        sendSuccess(source, "  /cineduct refresh         - 手动刷新当前视频（重新解析直链 + 无缝换链，位置不变）");
        sendSuccess(source, "      ↳ 画面卡住/黑屏/直链失效时用，声音不断；与自动续期是同一条路径");
        sendSuccess(source, "  /cineduct refresh full    - 强制重建引擎（丢掉全部缓冲重开，换链救不回来时用）");
        sendSuccess(source, "  /cineduct subprocess on|off - 用不用 C++ 子进程播放（零拷贝 + WASAPI）；");
        sendSuccess(source, "      ↳ 默认 **off**：默认就走进程内 Java 引擎。这里改的是配置，**永久生效**");
        sendSuccess(source, "  /cineduct subprocess status - 播放方式现状（永久开关 / 本局临时回退 / 每条会话）");
        sendSuccess(source, "  /cineduct fallback        - 只在**本局**把播放压到 Java 进程内引擎（内存开关，重启即失效）");
        sendSuccess(source, "  /cineduct fallback off    - 取消上面这个临时开关（已在 Java 引擎上的会话要重开屏幕才回子进程）");
        sendSuccess(source, "");
        sendSuccess(source, "【清晰度】");
        sendSuccess(source, "  /cineduct streams         - 列出本视频实际可选的每条流（编码/真实分辨率/码率，辨别真假 4K）");
        sendSuccess(source, "  /cineduct-stream quality <档位> - 锁定清晰度：auto(默认，按网速自适应) / 360p~4k / 8k / max");
        sendSuccess(source, "  /cineduct-stream streaming on|off      - 开关 DASH 流式直连（关掉则回退前置模组原生播放）");
        sendSuccess(source, "  /cineduct-stream streaming <毫秒偏移>   - 音画偏移微调（正=画面提前）");
        sendSuccess(source, "");
        sendSuccess(source, "【音频】");
        sendSuccess(source, "  /cineduct audio           - 音频链现状：选中音轨、源/输出格式、设备最大声道数");
        sendSuccess(source, "  /cineduct audio dolby|hires|best - 音频偏好：杜比全景声优先 / Hi-Res 无损优先 / 只取普通最高码率");
        sendSuccess(source, "      ↳ 杜比是 6ch E-AC-3；设备只有 2ch 时按标准下混矩阵折成立体声（不是丢声道）");
        sendSuccess(source, "");
        sendSuccess(source, "【登录】");
        sendSuccess(source, "  /cineduct login           - B 站扫码登录（4K/杜比/高清直链必需）");
        sendSuccess(source, "  /cineduct login status    - 查看登录态");
        sendSuccess(source, "  /cineduct login logout    - 清除登录态");
        sendSuccess(source, "");
        sendSuccess(source, "【独立播放器（给自带渲染的附属 mod 用，不需要屏幕实体）】");
        sendSuccess(source, "  /cineduct player open <链接> - 开一个独立播放器（解码+声卡照常走，画面交给调用方自己画）");
        sendSuccess(source, "  /cineduct player list        - 列出所有独立播放器（状态/传输方式/位置/分辨率）");
        sendSuccess(source, "  /cineduct player info <id>   - 单个详情；/cineduct player close <id> 关一个，closeall 全关");
        sendSuccess(source, "  /cineduct player aspect <id> <比例> - 显示面比例（16:9 传 1.7778）：黑边烘焙进纹理、不拉伸；0 = 关");
        sendSuccess(source, "  /cineduct player rebuild <id> - 重建这条播放（丢缓冲重开、位置续播；等价于 /cineduct refresh full）");
        sendSuccess(source, "  /cineduct player stats <id>   - 出帧/丢帧/等锁/子进程存活等现场统计");
        sendSuccess(source, "      ↳ 功能自检用：声音会真的响，能听到/看到状态就说明这条链路是通的");
        sendSuccess(source, "");
        sendSuccess(source, "【诊断】");
        sendSuccess(source, "  /cineduct av              - 音画偏移（正=画面提前/负=画面延后）：画面比声音快就给负值，边看边调；会写入配置");
        sendSuccess(source, "  /cineduct notice          - 玩家提示开关（off 粘滞；reset 清粘滞位）；附属 mod 用 CineDuctClientApi.setNoticesEnabled 调");
        sendSuccess(source, "  /cineduct status          - 引擎/原生库/流式开关 + 本次播放的 seek 统计");
        sendSuccess(source, "  /cineduct-stream          - 流式播放参数现状（清晰度/偏移/开关）");
        sendSuccess(source, "");
        sendSuccess(source, "  /cineduct help            - 显示本帮助");
        sendSuccess(source, "");
        sendSuccess(source, "配置文件都在游戏目录：cineduct-stream.json / cineduct-ffmpeg.json");
        return 1;
    }

    /** 模组版本号（取自 mods.toml / neoforge.mods.toml）。 */
    private static String version() {
        try {
            return net.neoforged.fml.ModList.get().getModContainerById(MODID)
                    .map(c -> String.valueOf(c.getModInfo().getVersion())).orElse("?");
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 给指令执行者发送一条成功消息。 */
    private static void sendSuccess(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal(message), false);
    }

    /**
     * 清晰度的人话描述。
     *
     * <p>{@code maxQn < 0} 是**自动**（ABR 按实测网速自适应选档），别再显示成 "qn-1" 了 ——
     * 用户看到负数只会以为是坏了。默认配置就是自动。
     */
    private static String qualityText() {
        if (StreamConfig.autoQuality()) return "自动（ABR 按网速自适应）";
        return switch (StreamConfig.maxQn) {
            case 0 -> "不限（账号内最高）";
            case 16 -> "360P";
            case 32 -> "480P";
            case 64 -> "720P";
            case 74 -> "720P60";
            case 80 -> "1080P";
            case 116 -> "1080P60";
            case 120 -> "4K";
            default -> "qn" + StreamConfig.maxQn;
        };
    }

}
package dev.mediafix.config.sodium;

import dev.mediafix.CineDuct;
import dev.mediafix.bili.BiliEntitlements;
import dev.mediafix.bili.BiliLoginScreen;
import dev.mediafix.config.FfmpegConfig;
import dev.mediafix.config.StreamConfig;
import dev.mediafix.player.MediaSession;
import dev.mediafix.player.Sessions;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPointForge;
import net.caffeinemc.mods.sodium.api.config.ConfigState;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.structure.BooleanOptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.EnumOptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ModOptionsBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionGroupBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionPageBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;

import java.util.EnumSet;
import java.util.Set;

/**
 * 把本模组「玩家真正会去调」的那部分设置挂进 Sodium 的视频设置界面。
 *
 * <h2>怎么被加载</h2>
 * Sodium 0.8.13 在 NeoForge 上会扫描带 {@link ConfigEntryPointForge} 注解的类
 * （{@code neoforge/.../config/ConfigLoaderForge.java:48-68}），按注解里写的 modid 归到本模组名下；
 * 之后在 {@code Minecraft.buildInitialScreens} 末尾跑一次 late 注册，把页面构建进全局 Config。
 * 所以：<b>只有 {@link #registerConfigLate} 里的选项会出现在界面里</b>，early 阶段留空。
 *
 * <p>这个类**只在 Sodium 主动加载时才被实例化**（{@code Class.forName}），因此 Sodium 缺席时
 * 不会触发 {@code NoClassDefFoundError} —— 本模组对 Sodium 依旧是"可选"的。
 *
 * <h2>这里的都是"玩家设置"，不是调试开关</h2>
 * 只放会影响观看效果的项：清晰度、播放链路（零拷贝子进程 / 进程内引擎的硬解）、AV1 取舍、
 * 音轨偏好、音画同步，以及账号登录入口。
 * 日志目录/诊断开关/线程数/环形缓冲/原生库路径/内部兜底开关这些东西**故意不进来** ——
 * 它们要么是排障用的，要么改错了会让播放异常，属于命令行与配置文件的领域。
 *
 * <h2>值从哪来、存到哪</h2>
 * Sodium 的 API 只管展示，不碰读写（{@code USAGE.md:11}）。所以每个选项的 binding 直接读/写
 * {@link StreamConfig}、{@link FfmpegConfig} 的静态字段，落盘由 {@link #STORAGE} 统一做：
 * 点「应用」时 Sodium 先把所有选项写进 binding，再回调一次 storage handler 把文件刷一次，
 * 不会出现"改一项写一次盘"。
 *
 * <p><b>改完生效时机</b>：绝大多数值是在"打开下一个视频"时被读取的（清晰度/音轨/解码/AV1），
 * 所以界面里也这么写；只有音画偏移是运行期即时可调的，用 apply hook 推给正在播的会话。
 */
@ConfigEntryPointForge(CineDuct.MODID)
public final class CineDuctSodiumConfig implements ConfigEntryPoint {

    /** 本模组的命名空间：所有选项 ID、贴图 ID 都用它，避免和其它 mod 撞车。 */
    private static final String NS = CineDuct.MODID;

    /** 主题色：影厅蓝。要够亮够艳（Sodium 会把过暗/发灰的主题色拉回可用范围）。 */
    private static final int THEME_BASE_RGB = 0x5B9CFF;

    /**
     * 落盘回调：一次「应用」里所有选项都写进 binding 之后由 Sodium 调一次
     * （{@code Config.java:345-350}）。两份配置各写一次文件，顺序无所谓。
     */
    private static final StorageEventHandler STORAGE = () -> {
        StreamConfig.save();
        FfmpegConfig.save();
    };

    /** 必须是无参可构造（Sodium 用 {@code getDeclaredConstructor()} 反射创建，见 ConfigManager:57-59）。 */
    public CineDuctSodiumConfig() {
    }

    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        ModOptionsBuilder mod = builder.registerOwnModOptions()
                .setColorTheme(builder.createColorTheme().setBaseThemeRGB(THEME_BASE_RGB))
                .setIcon(ResourceLocation.fromNamespaceAndPath(NS, "textures/gui/config_icon.png"));

        mod.addPage(this.buildPlaybackPage(builder));
        // 「账号登录」做成 external page：点左侧列表直接打开我们自己的 Screen。
        // 登录要画二维码、要显示账号昵称，这些在 Sodium 的控件体系里做不出来，
        // 而 API 官方给出的出口就是 external page（USAGE.md:220）。
        mod.addPage(builder.createExternalPage()
                .setName(text("cineduct.sodium.page.account", "账号登录"))
                .setScreenConsumer(current -> Minecraft.getInstance().setScreen(new BiliLoginScreen(current))));
    }

    // ------------------------------------------------------------------
    // 页面
    // ------------------------------------------------------------------

    private OptionPageBuilder buildPlaybackPage(ConfigBuilder builder) {
        OptionPageBuilder page = builder.createOptionPage()
                .setName(text("cineduct.sodium.page.playback", "播放"));

        page.addOptionGroup(this.buildQualityGroup(builder));
        page.addOptionGroup(this.buildAudioGroup(builder));
        page.addOptionGroup(this.buildSyncGroup(builder));

        return page;
    }

    private OptionGroupBuilder buildQualityGroup(ConfigBuilder builder) {
        OptionGroupBuilder group = builder.createOptionGroup()
                .setName(text("cineduct.sodium.group.quality", "画质与解码"));

        group.addOption(this.buildQualityOption(builder));
        // 直播清晰度紧跟在点播清晰度下面（两套档位是不同的体系，放一起便于对照）
        group.addOption(this.buildLiveQualityOption(builder));

        /*
         * 零拷贝子进程（gpuHostEnabled）。
         *
         * ★ 别把它和"硬件解码"混为一谈：两条链路**都**是 d3d11va 硬解
         *   （进程内：FfmpegVideoSource 走 AV_HWDEVICE_TYPE_D3D11VA / DXVA2；
         *    子进程：cineduct-host.exe 里 d3d11va 解码后经 DXGI 共享纹理出帧）。
         *   真正的差别是"画面要不要搬回内存"——零拷贝这条在子进程里把帧直接绑成 GL 纹理。
         *   所以文案要按"播放链路"讲，不能按"开不开硬解"讲。
         */
        BooleanOptionBuilder zeroCopy = builder.createBooleanOption(id("zero_copy_subprocess"))
                .setName(text("cineduct.sodium.zero_copy.name", "零拷贝子进程"))
                .setTooltip(text("cineduct.sodium.zero_copy.tooltip",
                        "交给独立的 C++ 子进程播放：帧硬解后经 DXGI 共享纹理零拷贝绑成 GL 纹理，不做 CPU 回读；"
                                + "音频走 WASAPI 直出。与「进程内硬解」不冲突 —— 那条同样硬解，只是要搬帧。"
                                + "不可用时自动回退，不会没画面。实验性，默认关闭；对之后打开的视频生效。"))
                .setStorageHandler(STORAGE)
                .setDefaultValue(false)
                .setBinding(v -> FfmpegConfig.gpuHostEnabled = v, () -> FfmpegConfig.gpuHostEnabled)
                .setImpact(OptionImpact.VARIES);
        group.addOption(zeroCopy);

        /*
         * 进程内引擎的硬解开关（hwAccelPreferred）。
         *
         * 只管"回退链路"：零拷贝子进程在子进程里自己硬解，不受这里影响。
         * 落盘只写偏好，运行时的自动降级（FfmpegVideoSource 把 hwAccel 置 false）不回写。
         */
        BooleanOptionBuilder hwDecode = builder.createBooleanOption(id("hardware_decoding"))
                .setName(text("cineduct.sodium.hw_decode.name", "进程内硬解"))
                .setTooltip(text("cineduct.sodium.hw_decode.tooltip",
                        "进程内 Java 引擎是否用 d3d11va / dxva2 解码（默认开）。关掉后这条链路退回软解，"
                                + "4K 基本跑不动，只适合排查驱动花屏；零拷贝子进程不受影响 —— 它在子进程里自己硬解。"
                                + "对之后打开的视频生效。"))
                .setStorageHandler(STORAGE)
                .setDefaultValue(true)
                .setBinding(
                        // 同时写偏好和"本次会话"的运行时开关，这样改完立刻生效；落盘只存偏好，
                        // 避免运行时的自动降级被误当成玩家的选择（见 FfmpegConfig.hwAccelPreferred）
                        v -> {
                            FfmpegConfig.hwAccelPreferred = v;
                            FfmpegConfig.hwAccel = v;
                        },
                        () -> FfmpegConfig.hwAccelPreferred)
                .setImpact(OptionImpact.MEDIUM);
        group.addOption(hwDecode);

        BooleanOptionBuilder avoidAv1 = builder.createBooleanOption(id("avoid_av1"))
                .setName(text("cineduct.sodium.avoid_av1.name", "避开 AV1 编码"))
                .setTooltip(text("cineduct.sodium.avoid_av1.tooltip",
                        "AV1 压缩率最高，但大多数显卡没有 AV1 硬解，只能软解（4K AV1 会掉到个位数帧率）。"
                                + "开启后优先选 H.264 / HEVC。注意：8K 基本只有 AV1，开启后可能拿不到 8K。"
                                + "对之后打开的视频生效。"))
                .setStorageHandler(STORAGE)
                .setDefaultValue(true)
                .setBinding(v -> StreamConfig.avoidAv1 = v, () -> StreamConfig.avoidAv1)
                .setImpact(OptionImpact.LOW);
        group.addOption(avoidAv1);

        return group;
    }

    private OptionGroupBuilder buildAudioGroup(ConfigBuilder builder) {
        OptionGroupBuilder group = builder.createOptionGroup()
                .setName(text("cineduct.sodium.group.audio", "音轨"));

        EnumOptionBuilder<AudioTrack> audio = builder.createEnumOption(id("audio_track"), AudioTrack.class)
                .setName(text("cineduct.sodium.audio_track.name", "音轨偏好"))
                .setTooltip(value -> text("cineduct.sodium.audio_track.tooltip." + value.key, value.tooltip))
                .setElementNameProvider(value -> text("cineduct.sodium.audio_track." + value.key, value.label))
                // 杜比全景声 / Hi-Res 无损只有大会员能拿：非大会员的允许集合里只留普通音轨
                // （UPDATE_ON_REBUILD：每次重建视频设置界面都按当前账号重算）
                .setAllowedValuesProvider(state -> allowedAudioTracks(), ConfigState.UPDATE_ON_REBUILD)
                // 默认值必须落在允许集合里，否则 EnumOption 会"回落到一个并不允许的默认值"
                .setDefaultProvider(state -> defaultAudioTrack(), ConfigState.UPDATE_ON_REBUILD)
                .setStorageHandler(STORAGE)
                .setBinding(
                        v -> StreamConfig.audioPreference = v.key,
                        () -> AudioTrack.fromKey(StreamConfig.audioPreference))
                .setImpact(OptionImpact.LOW);
        group.addOption(audio);

        return group;
    }

    private OptionGroupBuilder buildSyncGroup(ConfigBuilder builder) {
        OptionGroupBuilder group = builder.createOptionGroup()
                .setName(text("cineduct.sodium.group.sync", "同步"));

        group.addOption(builder.createIntegerOption(id("av_offset"))
                .setName(text("cineduct.sodium.av_offset.name", "音画同步偏移"))
                .setTooltip(text("cineduct.sodium.av_offset.tooltip",
                        "声音要经过 HDMI → 功放/回音壁才到耳朵，画面看起来会跑在前面。"
                                + "画面比声音快就调正值，慢就调负值，看到对上为止。"
                                + "拖动后点「应用」立刻对正在播放的视频生效，不用重启。"))
                .setStorageHandler(STORAGE)
                .setRange(-5000, 5000, 10)
                .setDefaultValue(0)
                .setValueFormatter(CineDuctSodiumConfig::formatMilliseconds)
                .setBinding(v -> FfmpegConfig.avOffsetMs = v, () -> FfmpegConfig.avOffsetMs)
                .setApplyHook(state -> pushAvOffsetToLiveSessions())
                .setImpact(OptionImpact.LOW));

        /*
         * 与服务端校验的最大延迟（秒）→ StreamConfig.catchUpMaxMs。
         *
         * 服务端按 tick 进度纠偏；目标比当前超前不超过这个值时，引擎在已预读的码流里"就地快进"
         * （不重开连接、不清空缓冲），超过才做真 seek（见 MediaEngine 的 seek 分支）。
         * 引擎里保留了 1 秒下限（Math.max(1000, catchUpMaxMs)），所以 0 等于"尽量走真 seek"。
         */
        group.addOption(builder.createIntegerOption(id("max_sync_delay"))
                .setName(text("cineduct.sodium.max_delay.name", "与服务端校验的最大延迟"))
                .setTooltip(seconds -> text("cineduct.sodium.max_delay.tooltip",
                        "服务端每次同步进度时，如果目标位置比你当前超前不超过这个秒数，就在已预读的码流里"
                                + "直接快进（不重开连接、不清空缓冲）；超过这个值才做真正的 seek。"
                                + "调大能减少卡顿，调小更严格地跟服务端。0 时仍保留 1 秒下限。"))
                .setStorageHandler(STORAGE)
                .setRange(0, 30, 1)
                .setDefaultValue(8)
                .setValueFormatter(seconds -> text("cineduct.sodium.max_delay.value", "%s 秒", seconds))
                .setBinding(
                        v -> StreamConfig.catchUpMaxMs = v * 1000,
                        () -> Math.round(StreamConfig.catchUpMaxMs / 1000.0f))
                .setImpact(OptionImpact.LOW));

        return group;
    }

    // ------------------------------------------------------------------
    // 各选项
    // ------------------------------------------------------------------

    private EnumOptionBuilder<Quality> buildQualityOption(ConfigBuilder builder) {
        return builder.createEnumOption(id("quality"), Quality.class)
                .setName(text("cineduct.sodium.quality.name", "清晰度"))
                .setTooltip(value -> text("cineduct.sodium.quality.tooltip." + value.key, value.tooltip))
                .setElementNameProvider(value -> text("cineduct.sodium.quality." + value.key, value.label))
                // 按账号权限收窄可选档位：未登录最高 720P、普通用户 1080P、大会员全开。
                // UPDATE_ON_REBUILD：VideoSettingsScreen 每次 init 都会 invalidate 这类动态值，
                // 所以玩家登录/退出后重开本页，档位列表就会跟着变（配置结构本身不用重建）。
                .setAllowedValuesProvider(state -> allowedQualities(), ConfigState.UPDATE_ON_REBUILD)
                .setStorageHandler(STORAGE)
                .setDefaultValue(Quality.AUTO)
                .setBinding(
                        v -> StreamConfig.maxQn = v.qn,
                        () -> Quality.fromQn(StreamConfig.maxQn))
                .setImpact(OptionImpact.VARIES);
    }

    /**
     * 直播清晰度（B 站直播 qn）。
     *
     * <p>放在点播「清晰度」的下面一项，但两者是独立配置：点播有 ABR 与账号权限收窄，
     * 直播是固定档位（切档必须断流重连），实际可用档位由主播推流决定。
     */
    private EnumOptionBuilder<LiveQuality> buildLiveQualityOption(ConfigBuilder builder) {
        return builder.createEnumOption(id("live_quality"), LiveQuality.class)
                .setName(text("cineduct.sodium.live_quality.name", "直播清晰度"))
                .setTooltip(value -> text("cineduct.sodium.live_quality.tooltip." + value.key, value.tooltip))
                .setElementNameProvider(value -> text("cineduct.sodium.live_quality." + value.key, value.label))
                .setStorageHandler(STORAGE)
                .setDefaultValue(LiveQuality.ORIGIN)
                .setBinding(
                        v -> StreamConfig.liveQn = v.qn,
                        () -> LiveQuality.fromQn(StreamConfig.liveQn))
                .setImpact(OptionImpact.LOW);
    }

    // ------------------------------------------------------------------
    // 账号权限 → 可选项
    // ------------------------------------------------------------------

    /**
     * 当前账号能选的清晰度。
     *
     * <p>{@link Quality#AUTO} 永远可选（自动档本身不越权：ABR 阶梯在解析时会按权限裁剪）；
     * {@link Quality#UNLIMITED}（不限）只给大会员 —— 对普通用户它名不副实，容易让人以为能拿到 4K。
     */
    private static Set<Quality> allowedQualities() {
        BiliEntitlements.Tier tier = BiliEntitlements.current();
        int cap = BiliEntitlements.maxQnCap(tier);
        Set<Quality> allowed = EnumSet.noneOf(Quality.class);
        for (Quality quality : Quality.values()) {
            boolean selectable = switch (quality) {
                case AUTO -> true;
                case UNLIMITED -> tier == BiliEntitlements.Tier.VIP;
                default -> quality.qn <= cap;
            };
            if (selectable) {
                allowed.add(quality);
            }
        }
        return allowed;
    }

    /** 当前账号能选的音轨偏好：杜比 / Hi-Res 只有大会员能拿。 */
    private static Set<AudioTrack> allowedAudioTracks() {
        return BiliEntitlements.current() == BiliEntitlements.Tier.VIP
                ? EnumSet.allOf(AudioTrack.class)
                : EnumSet.of(AudioTrack.BEST);
    }

    /**
     * 音轨的默认值。
     *
     * <p>不能固定写 DOLBY：非大会员时它不在允许集合里，{@code EnumOption} 会反复回落到一个
     * "默认但不可选"的值。所以默认值也跟着权限走。
     */
    private static AudioTrack defaultAudioTrack() {
        return BiliEntitlements.current() == BiliEntitlements.Tier.VIP
                ? AudioTrack.DOLBY
                : AudioTrack.BEST;
    }

    // ------------------------------------------------------------------
    // 值 <-> 配置文件
    // ------------------------------------------------------------------

    /**
     * 音画偏移的显示值：正数画面提前、负数画面延后。
     *
     * <p>连单位一起走语言键，别把「ms」硬编码 —— 其它语言的写法可能不同。
     */
    private static Component formatMilliseconds(int ms) {
        return text("cineduct.sodium.av_offset.value", "%s ms", (ms > 0 ? "+" : "") + ms);
    }

    /**
     * 音画偏移是唯一"改完立刻生效"的项：正在播的会话要逐条推一遍
     * （等价于 {@code /cineduct av <ms>} 做的事）。
     *
     * <p>apply hook 在渲染线程上跑，抛异常会把「应用」整个打断，所以这里兜住所有 Throwable。
     */
    private static void pushAvOffsetToLiveSessions() {
        try {
            for (MediaSession session : Sessions.all()) {
                session.setAvOffsetMs(FfmpegConfig.avOffsetMs);
            }
        } catch (Throwable t) {
            CineDuct.LOGGER.warn("[CineDuct] 把音画偏移推给正在播放的会话失败（配置已保存）", t);
        }
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(NS, path);
    }

    /**
     * 可翻译文本 + 中文兜底。
     *
     * <p>用 {@code translatableWithFallback} 而不是 {@code translatable}：本模组没有随包 lang 文件，
     * 直接 translatable 会在界面上显示成 {@code cineduct.sodium.quality.name} 这种原始键；
     * 而带着兜底文案时，资源包/语言文件里一旦出现同名键就会自动换成译文。
     */
    private static MutableComponent text(String key, String fallback) {
        return Component.translatableWithFallback(key, fallback);
    }

    /** 带格式参数的版本（fallback 里的 {@code %s} 会被 args 填上）。 */
    private static MutableComponent text(String key, String fallback, Object... args) {
        return Component.translatableWithFallback(key, fallback, args);
    }

    // ==================================================================
    // 枚举：B 站清晰度（点播 / 直播）与音轨偏好
    // ==================================================================

    /**
     * B 站直播清晰度档位（qn）。
     *
     * <p>与点播的 {@link Quality} 是两套体系：直播只有这几档固定值，切档必须断流重连，
     * 而且实际能出哪几档取决于主播推流（选到不支持的档位时 B 站会自动回落到可用档位）。
     *
     * <p>枚举常量<b>按从高到低声明</b>：默认档是「原画」，玩家点一下自然是往下降一档；
     * 从最低档再点一下会回到原画（Sodium 的轮换控件按 enum 顺序循环）。
     */
    public enum LiveQuality {
        ORIGIN(10000, "origin", "原画",
                "主播推流的最高画质，也是 B 站直播的默认档。"),
        BLURAY(400, "bluray", "蓝光",
                "1080P 高码率；只有提供了这档转码的直播间才可选，否则自动回落。"),
        ULTRA(250, "ultra", "超清",
                "约 720P，带宽占用明显更低。"),
        HIGH(150, "high", "高清",
                "约 480P，网络较差时用。"),
        SMOOTH(80, "smooth", "流畅",
                "最低档，最省带宽。");

        /** B 站直播接口里的清晰度编号。 */
        final int qn;
        /** 翻译键后缀。 */
        final String key;
        /** 中文显示名（语言文件缺失时的兜底）。 */
        final String label;
        /** 中文说明（语言文件缺失时的兜底）。 */
        final String tooltip;

        LiveQuality(int qn, String key, String label, String tooltip) {
            this.qn = qn;
            this.key = key;
            this.label = label;
            this.tooltip = tooltip;
        }

        /** 配置里的 qn 还原成档位；认不出来的值回落到原画。 */
        static LiveQuality fromQn(int qn) {
            for (LiveQuality quality : values()) {
                if (quality.qn == qn) {
                    return quality;
                }
            }
            return ORIGIN;
        }
    }

    /**
     * B 站清晰度档位。
     *
     * <p>枚举常量<b>按从低到高声明</b>：Sodium 的轮换控件就是按 enum 声明顺序循环的，
     * 所以左键点一下 = 升一档，Shift + 左键 = 降一档，手感与它自己的选项一致。
     *
     * <p>{@code qn} 是 B 站接口里的清晰度编号：{@code -1} = 自动（ABR），{@code 0} = 不限（账号内最高）。
     */
    public enum Quality {
        AUTO(-1, "auto", "自动",
                "按实测网速在清晰度阶梯里自动升降档：网络好就往上走，网络吃紧就先降档保流畅。推荐。"),
        P360(16, "360p", "360P",
                "标清。最省带宽、最省解码性能，网络很差时用。"),
        P480(32, "480p", "480P",
                "清晰度略高于 360P，仍然是低码率。"),
        P720(64, "720p", "720P",
                "高清。多数视频的常用档，画质与带宽比较均衡。"),
        P720_60(74, "720p60", "720P60",
                "720P + 60 帧：动作、游戏类画面更顺，码率比 720P 高一截。"),
        P1080(80, "1080p", "1080P",
                "全高清。需要登录 B 站账号；大会员才能拿到高码率版本。"),
        P1080_60(116, "1080p60", "1080P60",
                "全高清 + 60 帧，通常需要大会员。对解码性能有一定要求。"),
        P4K(120, "4k", "4K",
                "超清（2160P）。需要大会员，解码压力大，建议保持「进程内硬解」开启。"),
        P8K(127, "8k", "8K",
                "8K。B 站基本只提供 AV1 编码，绝大多数显卡只能软解，通常会非常卡。"),
        UNLIMITED(0, "unlimited", "不限",
                "不设上限：直接用账号权限里能拿到的最高清晰度。大会员 + 高配机器可以选这个。");

        /** B 站接口里的清晰度编号。 */
        final int qn;
        /** 翻译键后缀，同时也是配置文件里存的值（音轨偏好用它，清晰度存 qn）。 */
        final String key;
        /** 中文显示名（语言文件缺失时的兜底）。 */
        final String label;
        /** 中文说明（语言文件缺失时的兜底）。 */
        final String tooltip;

        Quality(int qn, String key, String label, String tooltip) {
            this.qn = qn;
            this.key = key;
            this.label = label;
            this.tooltip = tooltip;
        }

        /**
         * 配置里的 qn 还原成档位。
         *
         * <p>认不出来的编号（手改过配置文件、或以后 B 站加了新档位）回落到自动档，
         * 交给 ABR 处理 —— 总比把 0 当成 360P 播要好。
         */
        static Quality fromQn(int qn) {
            for (Quality quality : values()) {
                if (quality.qn == qn) {
                    return quality;
                }
            }
            return AUTO;
        }
    }

    /**
     * 音轨偏好，对应 {@code cineduct-stream.json} 里的 {@code audioPreference}。
     *
     * <p>杜比全景声 / Hi-Res 无损都要大会员，而且只有部分视频提供；拿不到时会自动回退，
     * 不会没声音（见 {@code DashResolver} 的选轨逻辑）。
     */
    public enum AudioTrack {
        DOLBY("dolby", "杜比全景声",
                "优先杜比全景声（E-AC-3），其次 Hi-Res 无损，最后普通音轨里码率最高的一条。"
                        + "需要大会员与视频本身提供，否则自动回退。"),
        HIRES("hires", "Hi-Res 无损",
                "优先 Hi-Res 无损（FLAC），其次杜比，最后普通音轨。需要大会员。"),
        BEST("best", "普通音轨",
                "只在普通 AAC 音轨里挑码率最高的一条，兼容性最好，不需要会员。");

        /** 配置文件里存的值。 */
        final String key;
        final String label;
        final String tooltip;

        AudioTrack(String key, String label, String tooltip) {
            this.key = key;
            this.label = label;
            this.tooltip = tooltip;
        }

        /** 认不出来的值回落到默认的杜比优先，不让脏配置把声音弄没。 */
        static AudioTrack fromKey(String key) {
            if (key != null) {
                String normalized = key.trim().toLowerCase(java.util.Locale.ROOT);
                for (AudioTrack track : values()) {
                    if (track.key.equals(normalized)) {
                        return track;
                    }
                }
            }
            return DOLBY;
        }
    }
}

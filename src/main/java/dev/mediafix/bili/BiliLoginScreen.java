package dev.mediafix.bili;

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.qrcode.encoder.ByteMatrix;
import com.google.zxing.qrcode.encoder.Encoder;
import dev.mediafix.CineDuct;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * B 站账号登录界面。从 Sodium 视频设置左侧的「账号登录」入口打开
 * （入口注册见 {@code dev.mediafix.config.sodium.CineDuctSodiumConfig}）。
 *
 * <h2>为什么要自己画二维码</h2>
 * 之前的登录入口只有 {@code /cineduct login}：它把二维码画成 PNG 再交给系统看图器打开，
 * 玩家得在游戏和看图器之间来回切。这里直接把 ZXing 编出来的点阵铺在界面上 ——
 * 手机对着屏幕就能扫，扫完不用动，轮询结果会自己刷到屏幕上。
 *
 * <p>二维码用"1 个模块 = 整数个像素"来画（{@code scale = 可用边长 / 模块数}），不缩放、不插值，
 * 保证扫描器能认出黑白格子；外面留一圈白边（静区），这是二维码规范要求的。
 *
 * <h2>线程</h2>
 * 取二维码、轮询扫码结果、查询登录态都是网络操作，一律在后台线程做；
 * 任何回写界面状态都经 {@link #post(Runnable)} 回到渲染线程。
 * 关闭界面时调 {@link BiliLogin#cancel()} 停掉轮询，避免关掉之后后台还在空转。
 */
public final class BiliLoginScreen extends Screen {

    /** 按钮高度（对齐原版）。 */
    private static final int BUTTON_HEIGHT = 20;
    /** 按钮之间、按钮与面板边缘的间距。 */
    private static final int GAP = 6;
    /** 面板内边距。 */
    private static final int PADDING = 10;
    /** 按钮数量：扫码登录 / 刷新状态 / 退出登录 / 完成。 */
    private static final int BUTTON_COUNT = 4;

    /** 打开本界面的上一级界面（Sodium 的视频设置），关闭时回到它。 */
    private final Screen parent;

    /** 登录态那一行：已登录 / 未登录 / 查询中。 */
    private Component accountLine = text("cineduct.login.querying", "正在查询登录状态…");
    /** 进度那一行（二维码提示、已扫码待确认、登录成功、失败原因…）。 */
    private Component infoLine = Component.empty();

    /** 二维码点阵：{@code qr[y][x]} 为 true 表示该模块是黑色。null = 还没取到。 */
    private boolean[][] qr;
    /** 正在等二维码 / 正在等扫码确认。 */
    private boolean busy;
    /** 界面是否已经关闭（关闭之后所有异步回调都丢弃，不再碰控件）。 */
    private boolean closed;
    /** 登录态是否已经查过一次（窗口改变大小会重跑 init，不要每次都发网络请求）。 */
    private boolean accountQueried;

    private Button loginButton;

    // 布局（init 里算好，render 直接用）
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int buttonTop;
    private int qrLeft;
    private int qrTop;
    private int qrSize;

    public BiliLoginScreen(Screen parent) {
        super(text("cineduct.login.title", "B 站账号登录"));
        this.parent = parent;
    }

    // ------------------------------------------------------------------
    // 布局与控件
    // ------------------------------------------------------------------

    @Override
    protected void init() {
        this.layout();

        int columns = this.columnCount();
        int width = this.buttonWidth();
        int blockWidth = width * columns + GAP * (columns - 1);
        int left = this.panelX + (this.panelW - blockWidth) / 2;

        Button login = this.addRenderableWidget(Button.builder(
                        text("cineduct.login.button.qr", "扫码登录"), button -> this.beginLogin())
                .bounds(0, 0, width, BUTTON_HEIGHT).build());
        Button refresh = this.addRenderableWidget(Button.builder(
                        text("cineduct.login.button.refresh", "刷新状态"), button -> this.refreshAccount())
                .bounds(0, 0, width, BUTTON_HEIGHT).build());
        Button logout = this.addRenderableWidget(Button.builder(
                        text("cineduct.login.button.logout", "退出登录"), button -> this.logout())
                .bounds(0, 0, width, BUTTON_HEIGHT).build());
        Button done = this.addRenderableWidget(Button.builder(
                        text("cineduct.login.button.done", "完成"), button -> this.onClose())
                .bounds(0, 0, width, BUTTON_HEIGHT).build());

        // 4 列 = 一行四个；2 列 = 两行（只有超窄窗口才会走这条）
        Button[] buttons = {login, refresh, logout, done};
        for (int i = 0; i < buttons.length; i++) {
            int column = i % columns;
            int row = i / columns;
            buttons[i].setX(left + column * (width + GAP));
            buttons[i].setY(this.buttonTop + row * (BUTTON_HEIGHT + GAP));
        }

        this.loginButton = login;
        this.syncButtonState();

        if (!this.accountQueried) {
            this.accountQueried = true;
            this.refreshAccount();
        }
    }

    /** 按钮列数：常规窗口一行排四个，窄窗口两列两行。 */
    private int columnCount() {
        return this.panelW >= 300 ? 4 : 2;
    }

    private int buttonWidth() {
        int columns = this.columnCount();
        return Math.max(50, (this.panelW - PADDING * 2 - GAP * (columns - 1)) / columns);
    }

    private void layout() {
        this.panelW = Math.min(this.width - 16, 440);
        this.panelH = Math.min(this.height - 16, 300);
        this.panelX = (this.width - this.panelW) / 2;
        this.panelY = (this.height - this.panelH) / 2;

        int rows = (BUTTON_COUNT + this.columnCount() - 1) / this.columnCount();
        int blockHeight = rows * BUTTON_HEIGHT + (rows - 1) * GAP;
        this.buttonTop = this.panelY + this.panelH - PADDING - blockHeight;

        // 标题 / 登录态 / 进度三行文字之后才是二维码区域
        int qrAreaTop = this.panelY + PADDING + 38;
        int qrAreaBottom = this.buttonTop - 8;
        int available = Math.max(0, qrAreaBottom - qrAreaTop);
        this.qrSize = Math.max(0, Math.min(available, this.panelW - PADDING * 2));
        this.qrLeft = this.panelX + (this.panelW - this.qrSize) / 2;
        this.qrTop = qrAreaTop + (available - this.qrSize) / 2;
    }

    private void syncButtonState() {
        if (this.loginButton != null) {
            this.loginButton.active = !this.busy;
        }
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    /**
     * 面板、文字、二维码都画在<b>背景</b>这一层。
     *
     * <p>★ 为什么不写在 {@code render()} 里：{@link Screen#render} 的字节码第一句就是
     * {@code renderBackground(...)}，然后才遍历按钮。如果 {@code render()} 里再手动调一次
     * {@code renderBackground()}，第二次会把<b>已经画好的面板和二维码一起当成背景做模糊</b>
     * （1.21 的模糊是整帧后处理），再盖一层菜单背景纹理 —— 表现就是二维码被糊掉、扫不出来。
     *
     * <p>所以这里只覆盖 {@code renderBackground()}：先让原版画它的背景，紧接着画我们的东西；
     * 基类的 {@code render()} 会在之后遍历按钮，天然形成「背景 → 面板/二维码 → 按钮」三层。
     */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);

        // 面板：先描一圈浅边，再铺深色底（不透明度高一点，避免透出后面的模糊背景影响扫码）
        graphics.fill(this.panelX - 1, this.panelY - 1, this.panelX + this.panelW + 1,
                this.panelY + this.panelH + 1, 0xFF3C3C3C);
        graphics.fill(this.panelX, this.panelY, this.panelX + this.panelW,
                this.panelY + this.panelH, 0xFF101010);

        int centerX = this.width / 2;
        int titleY = this.panelY + PADDING;
        graphics.drawCenteredString(this.font, this.title, centerX, titleY, 0xFFFFFF);
        graphics.drawCenteredString(this.font, this.accountLine, centerX, titleY + 14, 0xA8A8A8);
        graphics.drawCenteredString(this.font, this.infoText(), centerX, titleY + 26, 0xD8C080);

        this.renderQr(graphics);
    }

    /** 二维码区域：有二维码就画点阵，没有就给个占位提示。 */
    private void renderQr(GuiGraphics graphics) {
        if (this.qrSize <= 0) {
            return;
        }

        boolean[][] matrix = this.qr;
        if (matrix == null) {
            graphics.fill(this.qrLeft, this.qrTop, this.qrLeft + this.qrSize, this.qrTop + this.qrSize,
                    0x30FFFFFF);
            graphics.drawCenteredString(this.font,
                    this.busy ? text("cineduct.login.waiting", "正在获取二维码…")
                            : text("cineduct.login.no_qr", "点「扫码登录」获取二维码"),
                    this.width / 2, this.qrTop + this.qrSize / 2 - 4, 0x9A9A9A);
            return;
        }

        // 规范要求四周留 4 个模块的静区，所以按"模块数 + 8"来算每模块像素数，
        // 保证「点阵 + 静区」整体放得进 qrSize，不会顶到面板文字上。
        int modules = matrix.length;
        int scale = Math.max(1, this.qrSize / (modules + 8));
        int drawn = modules * scale;
        int quiet = scale * 4;
        int originX = this.qrLeft + (this.qrSize - drawn) / 2;
        int originY = this.qrTop + (this.qrSize - drawn) / 2;

        graphics.fill(originX - quiet, originY - quiet, originX + drawn + quiet, originY + drawn + quiet,
                0xFFFFFFFF);
        for (int y = 0; y < modules; y++) {
            boolean[] row = matrix[y];
            for (int x = 0; x < modules; x++) {
                if (!row[x]) {
                    continue;
                }
                int px = originX + x * scale;
                int py = originY + y * scale;
                graphics.fill(px, py, px + scale, py + scale, 0xFF000000);
            }
        }
    }

    // ------------------------------------------------------------------
    // 动作
    // ------------------------------------------------------------------

    /** 取二维码并开始等扫码确认。网络请求在后台线程。 */
    private void beginLogin() {
        if (this.busy) {
            return;
        }
        this.busy = true;
        this.qr = null;
        this.infoLine = text("cineduct.login.requesting", "正在获取二维码…");
        this.syncButtonState();

        Thread worker = new Thread(() -> {
            String url = BiliLogin.start(
                    // 第一个参数是给聊天栏指令看的中文进度句，界面不显示它（否则英文界面会蹦中文）；
                    // 界面按阶段自己取语言文件里的文案，见 phaseText
                    null,
                    // 阶段回调：已扫码待确认 / 登录成功 / 过期 / 超时 / 失败
                    phase -> this.post(() -> this.infoLine = phaseText(phase)),
                    // 一次扫码会话结束（成功、过期、超时、取消）：清掉二维码、恢复按钮、重查登录态
                    () -> this.post(() -> {
                        this.qr = null;
                        this.busy = false;
                        this.syncButtonState();
                        this.refreshAccount();
                    }));

            if (url == null) {
                this.post(() -> {
                    this.busy = false;
                    this.syncButtonState();
                    this.infoLine = text("cineduct.login.qr_failed", "获取二维码失败，请稍后重试");
                });
                return;
            }

            boolean[][] matrix = encodeQr(url);
            if (matrix == null) {
                // 兜底：画不出二维码就交给系统浏览器（浏览器里已登录 B 站时，点一下"确认"等同扫码）
                try {
                    Util.getPlatform().openUri(url);
                } catch (Throwable t) {
                    CineDuct.LOGGER.warn("[CineDuct] 打开登录链接失败", t);
                }
            }
            this.post(() -> {
                this.qr = matrix;
                this.infoLine = matrix != null
                        ? text("cineduct.login.scan_hint", "用手机 B 站 App 扫描二维码")
                        : text("cineduct.login.browser_hint", "二维码生成失败，已在浏览器打开登录链接");
            });
        }, "cineduct-login-request");
        worker.setDaemon(true);
        worker.start();
    }

    /** 查一次登录态（昵称 / uid / 大会员），网络请求在后台线程。 */
    private void refreshAccount() {
        this.accountLine = text("cineduct.login.querying", "正在查询登录状态…");
        Thread worker = new Thread(() -> {
            BiliLogin.Account account = BiliLogin.account();
            boolean hasCookie = BiliAuth.hasCookie();
            this.post(() -> {
                if (account != null) {
                    // 昵称/uid 是数据；"已登录"和会员标记走语言文件，英文界面不会蹦中文
                    MutableComponent line = text("cineduct.login.logged_in", "已登录：")
                            .append(Component.literal(account.name() + " (uid " + account.uid() + ")"));
                    if (account.premium()) {
                        line.append(text("cineduct.login.member.vip", " · 大会员")
                                .withStyle(ChatFormatting.GOLD));
                    }
                    this.accountLine = line;
                } else if (hasCookie) {
                    this.accountLine = text("cineduct.login.stale_cookie",
                            "登录态已失效或查询失败（可重新扫码）");
                } else {
                    this.accountLine = text("cineduct.login.not_logged_in", "未登录");
                }
            });
        }, "cineduct-login-status");
        worker.setDaemon(true);
        worker.start();
    }

    /** 退出登录：清本地凭证 + 停轮询 + 状态回到"未登录"。 */
    private void logout() {
        BiliLogin.logout();
        BiliLogin.cancel();
        this.qr = null;
        this.busy = false;
        this.syncButtonState();
        this.accountLine = text("cineduct.login.not_logged_in", "未登录");
        this.infoLine = text("cineduct.login.logged_out", "已退出登录");
    }

    @Override
    public void onClose() {
        this.closed = true;
        // 关界面就停掉扫码轮询：不然关掉之后后台还在为空会话轮询三分钟
        BiliLogin.cancel();
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }

    // ------------------------------------------------------------------
    // 线程 / 二维码
    // ------------------------------------------------------------------

    /** 把界面更新排回渲染线程；界面已关闭就丢弃。 */
    private void post(Runnable action) {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return;
        }
        client.execute(() -> {
            if (!this.closed) {
                action.run();
            }
        });
    }

    /** 提示行：有进度就显示进度，没有就显示扫码说明。 */
    private Component infoText() {
        return this.infoLine.getString().isEmpty()
                ? text("cineduct.login.idle_hint", "扫码后请在手机上确认，登录态会自动保存")
                : this.infoLine;
    }

    /**
     * 扫码阶段的提示文案。
     *
     * <p>走语言文件（{@code assets/cineduct/lang/}）而不是直接显示 {@code BiliLogin} 的中文进度句，
     * 英文环境才不会突然蹦出中文。
     */
    private static Component phaseText(BiliLogin.Phase phase) {
        return switch (phase) {
            case SCANNED -> text("cineduct.login.phase.scanned", "已扫码，请在手机上确认");
            case SUCCESS -> text("cineduct.login.phase.success", "登录成功");
            case EXPIRED -> text("cineduct.login.phase.expired", "二维码已过期，请重新获取");
            case TIMEOUT -> text("cineduct.login.phase.timeout", "等待扫码超时，请重新获取");
            case FAILED -> text("cineduct.login.phase.failed", "登录失败，请稍后重试");
        };
    }

    /**
     * 把二维码内容编码成点阵（1 个模块 = 1 个格子）。
     *
     * <p>用 ZXing 的 {@code Encoder} 而不是 {@code QRCodeWriter}：后者要指定输出像素尺寸，
     * 内部会做整数倍缩放并留静区，取回来还得反推模块大小；直接拿编码矩阵最省事，
     * 画的时候按窗口大小自己算每格几个像素。
     *
     * @return {@code grid[y][x]}，true = 黑；失败返回 null
     */
    private static boolean[][] encodeQr(String content) {
        try {
            ByteMatrix matrix = Encoder.encode(content, ErrorCorrectionLevel.M).getMatrix();
            int width = matrix.getWidth();
            int height = matrix.getHeight();
            boolean[][] grid = new boolean[height][width];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    grid[y][x] = matrix.get(x, y) == 1;
                }
            }
            return grid;
        } catch (Throwable t) {
            CineDuct.LOGGER.warn("[CineDuct] 生成登录二维码失败", t);
            return null;
        }
    }

    /** 可翻译文本 + 中文兜底（本模组不随包 lang 文件，见 CineDuctSodiumConfig 的说明）。 */
    private static MutableComponent text(String key, String fallback) {
        return Component.translatableWithFallback(key, fallback);
    }
}

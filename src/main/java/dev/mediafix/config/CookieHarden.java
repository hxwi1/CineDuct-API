package dev.mediafix.config;

import dev.mediafix.CineDuct;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 登录凭证（Cookie）安全加固。bilibili_media 把整号登录态（含 SESSDATA + bili_jct，
 * 几乎等价于账号本身）明文写在 cookie.properties，默认对系统所有用户可读，是本模组
 * 最主要的被盗号风险点。这里在 Windows 下用 icacls 把该文件 ACL 收紧为"仅当前用户可读"，
 * 并在首次进世界时给出一行提示，提醒勿外传。
 *
 * <p>仅做限权与提示，不触碰 Cookie 内容、不重新实现登录（登录仍走 bilibili_media
 * 自带的 {@code /bilimedia login} 扫码）。
 */
public final class CookieHarden {

    /** 与 BiliCookieStore 相同的两个候选存储路径。 */
    private static final Path HOME_COOKIE = Path.of(System.getProperty("user.home"), ".bilimedia", "cookie.properties");
    private static final Path GAME_COOKIE = Path.of(getGameDir(), ".bilimedia", "cookie.properties");

    private static boolean noticeSent = false;
    private static boolean hardened = false;

    private CookieHarden() {
    }

    private static String getGameDir() {
        return net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().toString();
    }

    /** 对存在的 cookie 文件执行一次 ACL 收紧（幂等）。Windows 专用。 */
    public static void harden() {
        if (hardened) {
            return;
        }
        if (!isWindows()) {
            hardened = true;
            return;
        }
        List<Path> paths = new ArrayList<>();
        if (Files.exists(HOME_COOKIE)) {
            paths.add(HOME_COOKIE);
        }
        if (Files.exists(GAME_COOKIE)) {
            paths.add(GAME_COOKIE);
        }
        for (Path p : paths) {
            try {
                List<String> cmd = new ArrayList<>();
                cmd.add("icacls");
                cmd.add(p.toString());
                cmd.add("/inheritance:r");
                cmd.add("/grant:r");
                cmd.add(System.getProperty("user.name", "") + ":(F)");
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                int exit = proc.waitFor();
                if (exit == 0) {
                    CineDuct.LOGGER.info("[CineDuct] 已收紧 Cookie 文件权限: {}", p);
                } else {
                    CineDuct.LOGGER.warn("[CineDuct] icacls 限权失败(exit={}): {}", exit, p);
                }
            } catch (Exception e) {
                CineDuct.LOGGER.warn("[CineDuct] 执行 icacls 限权异常: {}", p, e);
            }
        }
        hardened = true;
    }

    /**
     * Cookie 是否存在（用于决定是否提示）。
     */
    public static boolean hasCookie() {
        return (Files.exists(HOME_COOKIE) && cookieHasContent(HOME_COOKIE))
                || (Files.exists(GAME_COOKIE) && cookieHasContent(GAME_COOKIE));
    }

    private static boolean cookieHasContent(Path p) {
        try {
            return Files.size(p) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** 首次进入世界时提示一次。 */
    public static boolean shouldNotice() {
        if (noticeSent) {
            return false;
        }
        noticeSent = true;
        return true;
    }

    private static boolean isWindows() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win");
    }
}
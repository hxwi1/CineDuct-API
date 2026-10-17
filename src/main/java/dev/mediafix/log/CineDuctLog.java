package dev.mediafix.log;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 本模组自己的日志。
 *
 * <p><b>为什么要单独一份</b>：引擎的两秒一条状态、每帧分带计时、取帧诊断这些高频信息
 * 会把 {@code latest.log} 刷得没法看，排查别的问题时全是干扰。
 * 所以分两个频道：
 * <ul>
 *   <li>{@link #diag} —— 高频诊断，<b>只写本模组的日志文件</b>；</li>
 *   <li>{@link #info}/{@link #warn}/{@link #error} —— 事件与问题，<b>两边都写</b>，
 *       这样别人看 latest.log 也知道我们做了什么，需要细节时再翻独立日志。</li>
 * </ul>
 *
 * <p>文件放在游戏根目录的 {@code cineductlogs/}，每次启动一个文件（按时间命名，方便对着报错时间找），
 * 并自动清理旧的，避免无限堆积。
 */
public final class CineDuctLog {

    private static final Logger MAIN = LoggerFactory.getLogger("cineduct");

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private static final Object LOCK = new Object();
    private static BufferedWriter writer;
    private static Path sessionFile;
    private static boolean mirrorToMain = true;
    private static boolean enabled = true;

    public CineDuctLog() {
    }

    /**
     * 打开本次会话的日志文件。
     *
     * @param gameDir   游戏根目录（配置/日志所在目录）
     * @param dirName   子目录名，默认 cineductlogs
     * @param keepFiles 保留最近多少个日志文件（多余的删掉）
     * @param mirror    事件/警告是否同时写进 latest.log
     */
    public static void init(Path gameDir, String dirName, int keepFiles, boolean mirror) {
        mirrorToMain = mirror;
        enabled = true;
        synchronized (LOCK) {
            try {
                Path dir = gameDir.resolve(dirName == null || dirName.isBlank() ? "cineductlogs" : dirName);
                Files.createDirectories(dir);
                sessionFile = dir.resolve("cineduct-" + FILE_TS.format(LocalDateTime.now()) + ".log");
                writer = Files.newBufferedWriter(sessionFile, StandardCharsets.UTF_8);
                cleanup(dir, Math.max(1, keepFiles));
                raw("===== mediafix 会话开始 " + LocalDateTime.now() + " =====");
            } catch (Throwable t) {
                // 日志系统本身绝不能影响游戏：出问题就退回只写主日志
                enabled = false;
                MAIN.warn("[CineDuct] 独立日志初始化失败，改为只写主日志: {}", String.valueOf(t));
            }
        }
    }

    /** 本次会话的日志文件路径（没开就是 null）。 */
    public static Path sessionFile() {
        return sessionFile;
    }

    private static boolean diagEnabled = true;

    /** 高频诊断开关（配置项 diagEnabled）。 */
    public static void setDiagEnabled(boolean enabled) {
        diagEnabled = enabled;
    }

    /** 高频诊断：只进本模组日志文件，不污染 latest.log。 */
    public static void diag(String fmt, Object... args) {
        if (!diagEnabled) return;
        write("DIAG", fmt, args, false);
    }

    /** 细节日志：只进本模组日志文件（对应原来 SLF4J 的 debug，主日志通常也不显示）。 */
    public static void debug(String fmt, Object... args) {
        write("DEBUG", fmt, args, false);
    }

    public static void info(String fmt, Object... args) {
        write("INFO", fmt, args, true);
    }

    public static void warn(String fmt, Object... args) {
        write("WARN", fmt, args, true);
    }

    public static void error(String fmt, Object... args) {
        write("ERROR", fmt, args, true);
    }

    /**
     * 把长 URL 截短再打日志。
     * B 站直链的查询串有几百个字符（还带签名 token），原样打进日志既淹没信息也等于把凭证抄了一份。
     */
    public static String url(Object uri) {
        if (uri == null) return "null";
        String s = String.valueOf(uri);
        int q = s.indexOf('?');
        if (q < 0) return s.length() > 96 ? s.substring(0, 96) + "…" : s;
        String head = s.substring(0, q);
        String query = s.substring(q + 1);
        String tail = query.length() > 24 ? query.substring(0, 24) + "…" : query;
        return head + "?" + tail + "(共" + s.length() + "字符)";
    }

    private static void write(String level, String fmt, Object[] args, boolean toMain) {
        String msg = format(fmt, args);
        if (enabled) {
            synchronized (LOCK) {
                raw("[" + level + "] " + msg);
            }
        }
        if (toMain && mirrorToMain) {
            switch (level) {
                case "WARN" -> MAIN.warn(msg);
                case "ERROR" -> MAIN.error(msg);
                default -> MAIN.info(msg);
            }
        } else if (toMain && !mirrorToMain && ("WARN".equals(level) || "ERROR".equals(level))) {
            // 即使关掉镜像，问题也要留在主日志里
            if ("WARN".equals(level)) MAIN.warn(msg); else MAIN.error(msg);
        }
    }

    /** SLF4J 风格的 {} 占位符；末尾若是异常对象则打印堆栈。 */
    private static String format(String fmt, Object[] args) {
        if (args == null || args.length == 0) return fmt;
        Throwable thrown = null;
        int n = args.length;
        if (args[n - 1] instanceof Throwable t && fmt.contains("{}")) {
            // 最后一个参数是异常：占位符不够时当作堆栈处理
            int placeholders = countPlaceholders(fmt);
            if (placeholders < n) {
                thrown = t;
                n--;
            }
        }
        StringBuilder sb = new StringBuilder();
        int argIdx = 0;
        for (int i = 0; i < fmt.length(); i++) {
            char c = fmt.charAt(i);
            if (c == '{' && i + 1 < fmt.length() && fmt.charAt(i + 1) == '}' && argIdx < n) {
                sb.append(args[argIdx] == null ? "null" : String.valueOf(args[argIdx]));
                argIdx++;
                i++;
            } else {
                sb.append(c);
            }
        }
        if (thrown != null) {
            sb.append(" | ").append(thrown);
        }
        return sb.toString();
    }

    private static int countPlaceholders(String fmt) {
        int c = 0;
        for (int i = 0; i + 1 < fmt.length(); i++) {
            if (fmt.charAt(i) == '{' && fmt.charAt(i + 1) == '}') {
                c++;
                i++;
            }
        }
        return c;
    }

    private static void raw(String line) {
        if (writer == null) return;
        try {
            writer.write(TS.format(LocalTime.now()));
            writer.write(' ');
            writer.write(line);
            writer.newLine();
            writer.flush();
        } catch (IOException ignored) {
        }
    }

    /** 只保留最近 keepFiles 个日志文件。 */
    private static void cleanup(Path dir, int keepFiles) {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "cineduct-*.log")) {
            List<Path> files = new ArrayList<>();
            for (Path p : ds) files.add(p);
            if (files.size() <= keepFiles) return;
            files.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());
            for (int i = keepFiles; i < files.size(); i++) {
                try {
                    Files.deleteIfExists(files.get(i));
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public static void close() {
        synchronized (LOCK) {
            try {
                if (writer != null) {
                    raw("===== mediafix 会话结束 =====");
                    writer.close();
                }
            } catch (Throwable ignored) {
            }
            writer = null;
        }
    }
}

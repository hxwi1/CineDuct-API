package dev.mediafix.ffmpeg;

import dev.mediafix.CineDuct;
import dev.mediafix.config.FfmpegConfig;
import net.neoforged.fml.loading.FMLPaths;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * FFmpeg 原生库（avcodec/avformat/avutil/swscale/…）的定位与装载。
 *
 * <p>为什么要有这个类：JavaCPP 的 FFmpeg 绑定只带 Java 侧，真正的 DLL 体积很大
 * （Windows x64 约 60MB+），而 JavaCPP 只能从**目录**加载。所以流程是：
 * 找到原生库压缩包 → 解压到游戏目录 → 设 {@code org.bytedeco.javacpp.platform.preloadpath}
 * → 再让 JavaCPP 加载。
 *
 * <p><b>压缩包现在直接打在本 mod 的 jar 里</b>（{@value #BUNDLED_PATH}），
 * 所以分发只需要一个 jar，首次启动自动解压到 {@code <gamedir>/cineduct-ffmpeg/v2-bundled-…/}。
 *
 * <p>查找顺序：
 * <ol>
 *   <li>配置里显式指定的 nativesPath（文件或目录）—— 用于换成别的 FFmpeg 版本/平台；</li>
 *   <li><b>本 jar 内置的压缩包</b>（默认，无需任何额外文件）；</li>
 *   <li>{@code <gamedir>/config/cineduct/natives/} 与 {@code <gamedir>/mods/} 下的
 *       *.zip / *.jar（旧的分发方式，仍然兼容）；</li>
 *   <li>直接走 classpath（若使用者自行把原生库放进了某个 jar）。</li>
 * </ol>
 */
public final class FfmpegRuntime {

    /** 打进本 mod jar 的原生库压缩包路径（由 build.gradle 的 jar 任务放入）。 */
    private static final String BUNDLED_PATH = "cineduct-natives/ffmpeg-natives.zip";

    private static final String PRELOAD_PROP = "org.bytedeco.javacpp.platform.preloadpath";
    private static final String PATHS_FIRST_PROP = "org.bytedeco.javacpp.pathsFirst";

    private static volatile boolean attempted;
    private static volatile boolean available;
    private static volatile String status = "未初始化";

    private FfmpegRuntime() {
    }

    /** 是否可用（已成功装载原生库并可调用 FFmpeg）。 */
    public static boolean available() {
        return available;
    }

    public static String status() {
        return status;
    }

    /** 原生库解压目录（C++ 子进程要用**同一批 DLL**，保证两条路径的 FFmpeg 构建一致）。 */
    public static java.nio.file.Path nativesDir() {
        return extractedDir;
    }

    private static volatile java.nio.file.Path extractedDir;

    /**
     * 确保 FFmpeg 可用。可重复调用；只在第一次真正做事。
     * @return true 表示后续可以安全调用 org.bytedeco.ffmpeg.* 的函数
     */
    public static synchronized boolean ensure() {
        if (attempted) return available;
        attempted = true;

        // 1) 显式指定 > 2) 本 jar 内置 > 3) 外部目录里的包
        Path explicit = explicitArchive();
        boolean useBundled = explicit == null && bundledResourceExists();
        if (explicit == null && !useBundled && locateArchive() == null) {
            // 都没有：可能是原生库已随某个 jar 放在 classpath 上
            if (probe()) {
                available = true;
                status = "已从 classpath 加载";
                return true;
            }
            status = "未找到 FFmpeg 原生库（本 mod 自带，若缺失请重新下载完整 jar）";
            CineDuct.LOGGER.warn("[CineDuct] {}", status);
            return false;
        }

        Path archive = useBundled ? null : (explicit != null ? explicit : locateArchive());
        String srcName = useBundled ? "jar 内置原生库" : archive.getFileName().toString();
        try {
            String tag = useBundled ? bundledTag() : archive.getFileName().toString().replaceAll("[^A-Za-z0-9._-]", "_");
            Path dir = FMLPaths.GAMEDIR.get().resolve("cineduct-ffmpeg").resolve("v2-" + tag);
            extractedDir = dir;   // 子进程直接加载这里的 DLL（同一个 FFmpeg 构建）
            Path marker = dir.resolve(".unpacked");
            if (!Files.exists(marker)) {
                if (useBundled) {
                    CineDuct.LOGGER.info("[CineDuct] 首次启动：正在解压内置的 FFmpeg 原生库（约 30MB，之后启动会跳过）");
                    try (InputStream in = FfmpegRuntime.class.getClassLoader().getResourceAsStream(BUNDLED_PATH)) {
                        if (in == null) throw new IllegalStateException("jar 内缺少 " + BUNDLED_PATH);
                        extractStream(in, dir);
                    }
                } else {
                    extract(archive, dir);
                }
                Files.write(marker, srcName.getBytes());
            }
            StringBuilder preload = new StringBuilder(dir.toAbsolutePath().toString());
            // 把子目录里也含 dll 的目录一并加进去：不同来源的原生包布局并不统一
            try (var walk = Files.walk(dir, 3)) {
                walk.filter(Files::isDirectory).forEach(d -> {
                    try (var list = Files.list(d)) {
                        if (list.anyMatch(p -> p.getFileName().toString().toLowerCase().endsWith(".dll"))) {
                            String s = d.toAbsolutePath().toString();
                            if (!preload.toString().contains(s)) preload.append(java.io.File.pathSeparator).append(s);
                        }
                    } catch (Exception ignored) {
                    }
                });
            } catch (Exception ignored) {
            }
            System.setProperty(PRELOAD_PROP, preload.toString());
            System.setProperty(PATHS_FIRST_PROP, "true");
            CineDuct.LOGGER.info("[CineDuct] FFmpeg 原生库目录: {}", dir);
        } catch (Throwable t) {
            status = "解压原生库失败: " + t;
            CineDuct.LOGGER.error("[CineDuct] {}", status, t);
            return false;
        }

        if (probe()) {
            available = true;
            status = "已从 " + srcName + " 解压加载";
            CineDuct.LOGGER.info("[CineDuct] FFmpeg 可用: {}", status);
            return true;
        }
        // ★ 一定要把真实原因写进 status：曾经这里只写"版本/架构不匹配？"，
        // 而真正的原因是开发环境运行期 classpath 里没有 org.bytedeco.ffmpeg.*（NoClassDefFoundError），
        // 害得排查方向完全跑偏。
        status = "原生库已解压但加载失败: " + lastProbeError;
        CineDuct.LOGGER.error("[CineDuct] {}", status);
        return false;
    }

    /** 最近一次探测失败的原因（完整异常）。 */
    private static volatile String lastProbeError = "未探测";

    /**
     * 清掉"已经试过"的缓存后重新加载。
     *
     * <p>用途：临时性失败（DLL 被杀软短暂锁定、解压途中被中断）不必重启游戏 ——
     * {@code /cineduct status} 在原生库不可用时会自动调一次。
     */
    public static synchronized boolean retry() {
        attempted = false;
        available = false;
        status = "未初始化";
        CineDuct.LOGGER.info("[CineDuct] 重新尝试加载 FFmpeg 原生库…");
        return ensure();
    }

    /** 触发一次真正的 FFmpeg 调用，验证 JNI + 原生库齐全。 */
    private static boolean probe() {
        try {
            int v = org.bytedeco.ffmpeg.global.avutil.avutil_version();
            lastProbeError = "无";
            if (FfmpegConfig.verbose) {
                CineDuct.LOGGER.info("[CineDuct] FFmpeg avutil 版本号: {} ({}), avcodec: {}",
                        v, org.bytedeco.ffmpeg.global.avutil.av_version_info(),
                        org.bytedeco.ffmpeg.global.avcodec.avcodec_version());
            }
            return v != 0;
        } catch (Throwable t) {
            // 无论 verbose 与否都要留下原因（并且带上堆栈），否则只能靠猜
            lastProbeError = String.valueOf(t);
            CineDuct.LOGGER.error("[CineDuct] FFmpeg 探测失败: {}", lastProbeError, t);
            return false;
        }
    }

    /** 配置里显式指定的原生库包/目录（最高优先级，用于换 FFmpeg 版本或平台）。 */
    private static Path explicitArchive() {
        if (FfmpegConfig.nativesPath.isBlank()) return null;
        try {
            Path p = Path.of(FfmpegConfig.nativesPath);
            if (Files.isRegularFile(p)) return p;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean bundledResourceExists() {
        try {
            return FfmpegRuntime.class.getClassLoader().getResource(BUNDLED_PATH) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 内置包的版本签名：用 jar 条目的字节数 + CRC 拼成目录名。
     * 这样换了新 jar（原生库有变动）就会解压到新目录，不会复用旧文件。
     */
    private static String bundledTag() {
        // 打成 jar 运行时：用 jar 条目的字节数 + CRC
        try {
            var src = FfmpegRuntime.class.getProtectionDomain().getCodeSource();
            if (src != null) {
                Path p = Path.of(src.getLocation().toURI());
                if (Files.isRegularFile(p)) {
                    try (java.util.jar.JarFile jf = new java.util.jar.JarFile(p.toFile())) {
                        var e = jf.getEntry(BUNDLED_PATH);
                        if (e != null) {
                            return "bundled-" + e.getSize() + "-" + Long.toHexString(e.getCrc());
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        // 开发环境（IDEA runClient）：资源目录里是个普通文件，用大小 + 修改时间
        try {
            var res = FfmpegRuntime.class.getClassLoader().getResource(BUNDLED_PATH);
            if (res != null && "file".equals(res.getProtocol())) {
                Path p = Path.of(res.toURI());
                if (Files.isRegularFile(p)) {
                    return "bundled-dev-" + Files.size(p) + "-" + Long.toHexString(Files.getLastModifiedTime(p).toMillis());
                }
            }
        } catch (Throwable ignored) {
        }
        return "bundled";
    }

    /** 从流里解压（jar 内资源无法用 ZipFile 直接打开，只能用流式 ZipInputStream）。 */
    private static void extractStream(InputStream raw, Path dir) throws Exception {
        Files.createDirectories(dir);
        int n = 0;
        try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(raw)) {
            java.util.zip.ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                Path out = dir.resolve(flatten(e.getName())).normalize();
                if (!out.startsWith(dir)) continue;      // 防 zip slip
                Files.createDirectories(out.getParent());
                Files.copy(zip, out, StandardCopyOption.REPLACE_EXISTING);
                n++;
            }
        }
        CineDuct.LOGGER.info("[CineDuct] 解压 FFmpeg 原生库 {} 个文件 -> {}", n, dir);
    }

    private static Path locateArchive() {
        List<Path> dirs = new ArrayList<>();
        try {
            dirs.add(FMLPaths.GAMEDIR.get().resolve("config").resolve("cineduct").resolve("natives"));
            dirs.add(FMLPaths.GAMEDIR.get().resolve("mods"));
            dirs.add(FMLPaths.GAMEDIR.get().resolve("cineduct-ffmpeg"));
        } catch (Throwable ignored) {
        }

        if (!FfmpegConfig.nativesPath.isBlank()) {
            // 目录形式的覆盖（文件形式由 explicitArchive 处理）
            try {
                Path p = Path.of(FfmpegConfig.nativesPath);
                if (Files.isDirectory(p)) dirs.add(0, p);
            } catch (Throwable ignored) {
            }
        }

        // 只有当前平台的原生库才有意义
        String osTag = osTag();
        for (Path dir : dirs) {
            if (!Files.isDirectory(dir)) continue;
            try (var stream = Files.list(dir)) {
                Path best = stream
                        .filter(Files::isRegularFile)
                        .filter(p -> {
                            String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                            if (!(n.endsWith(".zip") || n.endsWith(".jar"))) return false;
                            return n.contains("ffmpeg-natives") || n.contains("ffmpeg_natives") || n.contains("cineduct-ffmpeg");
                        })
                        .filter(p -> {
                            String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                            return n.contains(osTag) || osTag.isEmpty();
                        })
                        .findFirst().orElse(null);
                if (best != null) return best;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static String osTag() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String plat = os.contains("win") ? "windows" : os.contains("mac") ? "macosx" : "linux";
        String a = (arch.contains("aarch64") || arch.contains("arm")) ? "arm64" : "x86_64";
        return plat + "-" + a;
    }

    /**
     * 把 JavaCPP 原生包的路径拍平：{@code org/bytedeco/<lib>/<platform>/xxx.dll} → {@code xxx.dll}。
     * JavaCPP 在 platform.preloadpath 指定的目录里是按文件名直接查找的，保留前缀会找不到。
     */
    private static String flatten(String name) {
        // org/bytedeco/<lib>/<platform>/<file...> -> <file...>
        // 注意：要剥掉 org/bytedeco/<lib>/<platform> 共 4 段，DLL 才会落在解压目录根部；
        // 少剥一层会让 DLL 留在 windows-x86_64/ 子目录里，而 JavaCPP 是按文件名在
        // preloadpath 根目录直接查找的 —— 这曾经导致"解压成功但加载失败"。
        String[] parts = name.split("/");
        if (parts.length >= 5 && "org".equals(parts[0]) && "bytedeco".equals(parts[1])) {
            StringBuilder sb = new StringBuilder();
            for (int i = 4; i < parts.length; i++) {
                if (sb.length() > 0) sb.append('/');
                sb.append(parts[i]);
            }
            return sb.toString();
        }
        return name;
    }

    /** 把压缩包里的文件按相对路径解压到目标目录（跳过目录项）。 */
    private static void extract(Path archive, Path dir) throws Exception {
        Files.createDirectories(dir);
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            int n = 0;
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e.isDirectory()) continue;
                Path out = dir.resolve(flatten(e.getName())).normalize();
                if (!out.startsWith(dir)) continue; // 防 zip slip
                Files.createDirectories(out.getParent());
                try (InputStream in = zip.getInputStream(e)) {
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }
                n++;
            }
            CineDuct.LOGGER.info("[CineDuct] 解压 FFmpeg 原生库 {} 个文件 -> {}", n, dir);
        }
    }
}

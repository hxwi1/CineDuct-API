package dev.mediafix;

import net.neoforged.fml.loading.FMLPaths;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 把 jar 里打包的原生文件释放到游戏目录，供运行时加载。
 *
 * <p>现有两份：
 * <ul>
 *   <li>{@code cineduct-glshim.dll} —— 游戏进程内的 D3D11 垫片（建 D3D11 设备、导入共享纹理、
 *       连控制管道）。LWJGL 不绑定 D3D11，所以必须有这一层；</li>
 *   <li>{@code cineduct-host.exe} —— C++ 子进程（d3d11va 硬解 + VideoProcessor 转 BGRA +
 *       WASAPI 音频）。缺失时自动降级到进程内 Java 引擎。</li>
 * </ul>
 * 释放策略：**按内容**判断是否需要重写（大小 + CRC32），避免每次启动都写盘。
 * 这也顺手解决了"玩家手动放文件版本不对"的经典问题。
 *
 * <p><b>为什么不能只比大小</b>：C++ 侧经常是"改几行、产物大小一模一样"（实测 rebuild 后
 * exe 与上一版同为 163328 字节）。只比大小就会**悄悄用旧 exe 跑**，症状是"改了没生效"，
 * 而这种问题排查起来极费时间 —— 代价不过是启动时算一次 CRC32，非常划算。
 */
public final class NativeFiles {

    private static final String RESOURCE_DIR = "cineduct-native";
    private static final String[] FILES = {"cineduct-glshim.dll", "cineduct-host.exe"};

    private static volatile Path dir;
    private static volatile boolean attempted;

    private NativeFiles() {
    }

    /** 释放目录（&lt;游戏目录&gt;/cineduct-native/）；失败返回 null。 */
    public static synchronized Path ensure() {
        if (attempted) return dir;
        attempted = true;
        try {
            final Path target = FMLPaths.GAMEDIR.get().resolve("cineduct-native");
            Files.createDirectories(target);
            for (String name : FILES) {
                release(target, name);
            }
            dir = target;
            return dir;
        } catch (Throwable t) {
            CineDuct.LOGGER.warn("[CineDuct] 释放原生文件失败（相关功能会自动降级）", t);
            return null;
        }
    }

    private static void release(Path target, String name) throws Exception {
        final byte[] data;
        try (InputStream in = NativeFiles.class.getClassLoader()
                .getResourceAsStream(RESOURCE_DIR + "/" + name)) {
            if (in == null) return;   // jar 里没有（例如未构建原生部分）
            data = in.readAllBytes();
        }
        final Path file = target.resolve(name);
        if (Files.isRegularFile(file) && Files.size(file) == data.length
                && crc32(Files.readAllBytes(file)) == crc32(data)) {
            return;   // 内容完全一致：没必要重写（文件可能正被游戏占用）
        }
        Files.write(file, data);
        CineDuct.LOGGER.info("[CineDuct] 已释放原生文件 {} ({} 字节)", file, data.length);
    }

    private static long crc32(byte[] data) {
        final java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(data);
        return crc.getValue();
    }

    /** 某个原生文件的路径；不存在返回 null。 */
    public static Path file(String name) {
        final Path base = ensure();
        if (base == null) return null;
        final Path file = base.resolve(name);
        return Files.isRegularFile(file) ? file : null;
    }
}

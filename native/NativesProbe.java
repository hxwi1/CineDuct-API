import org.bytedeco.javacpp.Loader;
import org.bytedeco.ffmpeg.global.avutil;

/** 复现 FfmpegRuntime 的加载过程，把真实异常打出来。 */
public class NativesProbe {
    public static void main(String[] args) {
        String dir = args.length > 0 ? args[0] : "F:\\mos\\video\\run\\mediafix-ffmpeg\\v2-bundled";
        System.setProperty("org.bytedeco.javacpp.platform.preloadpath", dir);
        System.setProperty("org.bytedeco.javacpp.pathsFirst", "true");
        System.out.println("preloadpath = " + System.getProperty("org.bytedeco.javacpp.platform.preloadpath"));
        try {
            Class<?> c = Class.forName("org.bytedeco.ffmpeg.global.avutil");
            System.out.println("找到 avutil 类: " + c.getName());
            String v = avutil.av_version_info().getString();
            System.out.println("✔ av_version_info = " + v);
        } catch (Throwable t) {
            System.out.println("✘ 加载失败: " + t);
            Throwable cause = t.getCause();
            while (cause != null) {
                System.out.println("   caused by: " + cause);
                cause = cause.getCause();
            }
            t.printStackTrace(System.out);
        }
    }
}

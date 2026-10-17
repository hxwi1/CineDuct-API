package dev.mediafix.gpu;

import dev.mediafix.CineDuct;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.WGLCapabilities;
import org.lwjgl.opengl.WGLNVDXInterop;

/**
 * 当前 GL 上下文所在适配器的探测结果（整局缓存）。
 *
 * <p><b>为什么需要它</b>：D3D11 共享纹理只在**同一块显卡**上成立，
 * 而子进程必须知道该在哪块卡上建 D3D11 设备。显卡名字靠不住 ——
 * 本机就有 3 个同名 NVIDIA LUID，还有 MuMu/GameViewer 的虚拟显示器适配器。
 * 唯一可靠的判据是：逐个适配器建设备试 {@code wglDXOpenDeviceNV}，
 * 成功说明"这块卡就是 GL 上下文所在的那块"（驱动说了算）。
 *
 * <p><b>线程</b>：必须在渲染线程首探（需要 GL 上下文当前）；结果缓存后其它线程可读。
 * 从别的线程误调**不会抛异常**（{@link #probe()} 自己吞掉"没有当前上下文"的那种失败），
 * 只是这次探不到 —— 调用方据此退回进程内引擎，渲染线程之后还会再探一次。
 */
public final class GpuContext {

    private static volatile boolean probed;
    private static volatile long adapterLuid;
    private static volatile String adapterName = "";
    private static volatile String reason = "未探测";
    /** "从没有 GL 上下文的线程误调"只提示一次，免得后台线程反复调时刷屏。 */
    private static volatile boolean warnedNoContext;

    private GpuContext() {
    }

    /** GL 上下文所在适配器的 LUID；0 = 不可用（此时应回退到进程内引擎）。 */
    public static long adapterLuid() {
        if (!this0()) return 0L;
        return adapterLuid;
    }

    public static String adapterName() {
        if (!this0()) return "";
        return adapterName;
    }

    /** 探测失败原因（诊断用）。 */
    public static String reason() {
        return reason;
    }

    public static boolean available() {
        return this0() && adapterLuid != 0L;
    }

    private static boolean this0() {
        if (probed) return true;
        return probe();
    }

    /** 首探（渲染线程）。 */
    public static synchronized boolean probe() {
        if (probed) return adapterLuid != 0L;
        probed = true;
        adapterLuid = 0L;

        if (!NativeD3D.available()) {
            reason = "垫片 DLL 未加载（" + NativeD3D.loadError() + "）";
            CineDuct.LOGGER.info("[CineDuct] 零拷贝不可用：{}", reason);
            return false;
        }
        // 探测要调 GL 函数，必须有当前上下文 —— 也就是只能在渲染线程做。
        // 从别的线程误调时给个明确理由，而不是让驱动崩掉。
        //
        // ★ 这里必须 try/catch，不能判 null：**LWJGL 不会返回 null，而是抛 IllegalStateException**
        // （"No GLCapabilities instance set for the current thread…"）。以前判 null 的写法等于
        // 这条兜底从来没生效过 —— 后台线程误调会把异常抛穿到调用方，而调用方（比如独立播放器的
        // 解析线程）本来只想要一个"探不到"的结论，然后**退回进程内引擎**（见 reason/adapterLuid 的约定）。
        try {
            GL.getCapabilities();
        } catch (IllegalStateException e) {
            probed = false;   // 让渲染线程之后能再探一次（这次失败只是"线程不对"，不是"这台机器不行"）
            reason = "没有当前 GL 上下文（适配器探测必须在渲染线程做）";
            if (!warnedNoContext) {
                warnedNoContext = true;
                CineDuct.LOGGER.warn("[CineDuct] {} —— 本次调用退回进程内引擎；渲染线程会再探一次", reason);
            }
            return false;
        }

        final WGLCapabilities wgl;
        try {
            WGLCapabilities current = GL.getCapabilitiesWGL();
            if (current == null || current.wglDXOpenDeviceNV == 0L) {
                GL.createCapabilitiesWGL();
                current = GL.getCapabilitiesWGL();
            }
            wgl = current;
        } catch (IllegalStateException e) {
            // 与上面同理：WGL 能力查询同样需要当前上下文
            probed = false;
            reason = "WGL 能力查询失败（" + e.getClass().getSimpleName() + "：不在渲染线程？）";
            if (!warnedNoContext) {
                warnedNoContext = true;
                CineDuct.LOGGER.warn("[CineDuct] {} —— 本次调用退回进程内引擎", reason);
            }
            return false;
        }
        if (wgl == null || wgl.wglDXOpenDeviceNV == 0L) {
            reason = "WGL_NV_DX_interop 函数指针未装载（需要 NVIDIA 驱动）";
            CineDuct.LOGGER.info("[CineDuct] 零拷贝不可用：{}", reason);
            return false;
        }

        try {
            for (NativeD3D.Adapter adapter : NativeD3D.enumerateAdapters()) {
                if (adapter.isSoftwareOrRemote()) continue;
                final long device = NativeD3D.createDevice(adapter.luid(), NativeD3D.FLAG_BGRA_SUPPORT);
                if (device == 0L) continue;
                final long interop = WGLNVDXInterop.wglDXOpenDeviceNV(device);
                if (interop != 0L) {
                    // 只租不占：探到就立刻还掉，真正用的时候 GlInterop 会再建一次
                    WGLNVDXInterop.wglDXCloseDeviceNV(interop);
                    NativeD3D.release(device);
                    adapterLuid = adapter.luid();
                    adapterName = adapter.description();
                    reason = "OK";
                    CineDuct.LOGGER.info("[CineDuct] 零拷贝可用：GL 适配器 = {} luid={}",
                            adapterName, adapter.luidText());
                    return true;
                }
                NativeD3D.release(device);
            }
        } catch (IllegalStateException | NullPointerException e) {
            // 探测中途上下文失效（切窗口/换卡/驱动掉了）：同样只当成"这次探不到"
            probed = false;
            reason = "适配器探测中断（" + e.getClass().getSimpleName() + "）";
            CineDuct.LOGGER.warn("[CineDuct] {} —— 退回进程内引擎", reason);
            return false;
        }
        reason = "所有适配器都注册不上（D3D11 与 GL 必须在同一块卡上）";
        CineDuct.LOGGER.info("[CineDuct] 零拷贝不可用：{}", reason);
        return false;
    }
}

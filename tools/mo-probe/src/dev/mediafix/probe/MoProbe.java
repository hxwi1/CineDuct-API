package dev.mediafix.probe;

import dev.mediafix.gpu.NativeD3D;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.EXTMemoryObject;
import org.lwjgl.opengl.EXTMemoryObjectWin32;
import org.lwjgl.opengl.EXTSemaphore;
import org.lwjgl.opengl.EXTSemaphoreWin32;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.WGLCapabilities;
import org.lwjgl.opengl.WGLNVDXInterop;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR;
import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR;
import static org.lwjgl.glfw.GLFW.GLFW_FALSE;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_CORE_PROFILE;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_PROFILE;
import static org.lwjgl.glfw.GLFW.GLFW_RESIZABLE;
import static org.lwjgl.glfw.GLFW.GLFW_TRUE;
import static org.lwjgl.glfw.GLFW.GLFW_VISIBLE;
import static org.lwjgl.glfw.GLFW.glfwCreateWindow;
import static org.lwjgl.glfw.GLFW.glfwDestroyWindow;
import static org.lwjgl.glfw.GLFW.glfwInit;
import static org.lwjgl.glfw.GLFW.glfwMakeContextCurrent;
import static org.lwjgl.glfw.GLFW.glfwPollEvents;
import static org.lwjgl.glfw.GLFW.glfwSwapInterval;
import static org.lwjgl.glfw.GLFW.glfwTerminate;
import static org.lwjgl.glfw.GLFW.glfwWindowHint;

/**
 * <b>NT 共享句柄 → OpenGL 内存对象</b> 的能力探测（自包含，不需要第二个进程）。
 *
 * <p>回答两个决定性的问题，而且**不需要 AMD 机器上的游戏、不需要子进程**：
 * <ol>
 *   <li>这块卡的 GL 驱动能不能把一个 <b>D3D11 的 NT 共享句柄</b>
 *       （{@code D3D11_RESOURCE_MISC_SHARED_NTHANDLE} + {@code IDXGIResource1::CreateSharedHandle}）
 *       通过 {@code glImportMemoryWin32HandleEXT} 直接别成 GL 纹理？</li>
 *   <li>能不能用 {@code GL_EXT_semaphore_win32} 把一个共享的 D3D11 fence 导入成 GL 信号量，
 *       从而做 GPU 间（而不是 CPU 间）的等待？</li>
 * </ol>
 *
 * <p>为什么值得单独做这个程序：{@code WGL_NV_DX_interop2} 是 NVIDIA 血统的扩展，
 * 非 NVIDIA 上要么拿不到函数指针、要么注册不上；而 EXT_memory_object_win32 是
 * <b>Khronos 的跨厂商扩展</b>（AMD Adrenalin 18.8.1 起在 GL 扩展表里就有
 * {@code GL_EXT_memory_object} / {@code GL_EXT_memory_object_win32} /
 * {@code GL_EXT_semaphore} / {@code GL_EXT_semaphore_win32}）。
 * 于是"这条路的可用性"完全可以由一次不依赖游戏的探测给出结论。
 *
 * <p><b>关于 HandleType 的名字</b>：EXT_memory_object_win32 里并没有
 * {@code GL_HANDLE_TYPE_OPAQUE_WIN32_NT_EXT} 这个枚举。与 D3D11 纹理对应的是：
 * <pre>
 *   GL_HANDLE_TYPE_D3D11_IMAGE_EXT     = 0x958B  ← NT 句柄（CreateSharedHandle）用这个
 *   GL_HANDLE_TYPE_D3D11_IMAGE_KMT_EXT = 0x958C  ← 老的 GetSharedHandle（KMT）用这个
 *   GL_HANDLE_TYPE_OPAQUE_WIN32_EXT    = 0x9587  ← 给 Vulkan 导出的 NT 句柄用的
 * </pre>
 * 本程序把这三个都试一遍并分别报告，免得"参数写错"被误判成"驱动不支持"。
 *
 * <p>用法：
 * <pre>
 *   java -Djava.library.path=native\build\bin -cp &lt;classes;lwjgl...&gt; dev.mediafix.probe.MoProbe
 *   .\native\probe-interop.ps1            # 已经把这些拼好了
 * </pre>
 */
public final class MoProbe {

    private static final int WIDTH = 256;
    private static final int HEIGHT = 256;
    private static final int FBO_SIZE = 64;

    /** 与生产者约定一致的色标：B 恒定 0xA5，R/G 由生产者写。 */
    private static final int STAMP_BLUE = 0xA5;
    private static final int STAMP_R = 0x5A;
    private static final int STAMP_G = 0x33;

    // ---- GL 枚举（LWJGL 的 EXTMemoryObject/EXTSemaphore 里已经有一批，这里只补缺的） ----
    private static final int GL_TEXTURE_SWIZZLE_RGBA = 0x8E46;
    private static final int GL_RGBA8 = 0x8058;
    private static final int GL_FRAMEBUFFER = 0x8D40;
    private static final int GL_COLOR_ATTACHMENT0 = 0x8CE0;
    private static final int GL_FRAMEBUFFER_COMPLETE = 0x8CD5;

    private static final List<String> report = new ArrayList<>();
    private static int failures;

    public static void main(String[] args) throws Exception {
        boolean hidden = true;   // 默认不弹窗（探测不需要人看，也就不抢焦点）
        for (String arg : args) {
            if ("--visible".equals(arg)) hidden = false;
            else if ("--hidden".equals(arg)) hidden = true;
        }

        System.out.println("=== CineDuct 互操作探测：NT 共享句柄 → OpenGL 内存对象 ===");
        System.out.println();

        if (!NativeD3D.available()) {
            System.out.println("[致命] 垫片 DLL（cineduct-glshim.dll）没加载上：" + NativeD3D.loadError());
            System.out.println("      用 -D" + NativeD3D.PROPERTY_PATH + "=<绝对路径> 或 -Djava.library.path=<bin 目录>");
            System.exit(2);
        }

        if (!glfwInit()) {
            System.out.println("[致命] glfwInit 失败");
            System.exit(2);
        }
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_VISIBLE, hidden ? GLFW_FALSE : GLFW_TRUE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE);
        final long window = glfwCreateWindow(320, 240, "CineDuct interop probe", 0L, 0L);
        if (window == 0L) {
            System.out.println("[致命] glfwCreateWindow 失败（没有可用的 GL 上下文）");
            glfwTerminate();
            System.exit(2);
        }
        glfwMakeContextCurrent(window);
        glfwSwapInterval(0);
        GL.createCapabilities();

        try {
            dumpGlInfo();
            final long luid = resolveGlAdapterLuid();
            final long device = createDeviceOn(luid);
            if (device == 0L) {
                report.add("D3D11 设备: ✘ 建不起来（" + NativeD3D.lastError() + "）");
                failures++;
            } else {
                testMemoryObjectImport(device, luid);
                testSharedFenceSemaphore(device);
                NativeD3D.release(device);
            }
        } finally {
            glfwDestroyWindow(window);
            glfwTerminate();
        }

        printReport();
        System.exit(failures == 0 ? 0 : 1);
    }

    // -----------------------------------------------------------------------
    // 1) GL 侧能力清单
    // -----------------------------------------------------------------------

    private static void dumpGlInfo() {
        System.out.println("GL_VENDOR   : " + GL11.glGetString(GL11.GL_VENDOR));
        System.out.println("GL_RENDERER : " + GL11.glGetString(GL11.GL_RENDERER));
        System.out.println("GL_VERSION  : " + GL11.glGetString(GL11.GL_VERSION));
        System.out.println();

        // 用 glGetStringi 自己枚举一遍扩展（不依赖 LWJGL 的 caps 字段有没有绑这个扩展），
        // 顺便把整份清单打出来 —— 报告要发回来给别人看，原始清单比"我记得有/没有"可靠。
        final int count = GL11.glGetInteger(GL30.GL_NUM_EXTENSIONS);
        final List<String> exts = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            final String e = GL30.glGetStringi(GL11.GL_EXTENSIONS, i);
            if (e != null) exts.add(e);
        }
        System.out.println("--- GL 扩展清单（" + exts.size() + " 条） ---");
        for (String e : exts) {
            System.out.println("    " + e);
        }
        System.out.println();

        System.out.println("--- 本探测关心的那几个 ---");
        probeLine(exts, "GL_EXT_memory_object");
        probeLine(exts, "GL_EXT_memory_object_win32");
        probeLine(exts, "GL_EXT_semaphore");
        probeLine(exts, "GL_EXT_semaphore_win32");
        probeLine(exts, "GL_EXT_external_objects");
        probeLine(exts, "GL_EXT_external_objects_win32");
        probeLine(exts, "GL_ARB_texture_storage");
        probeLine(exts, "GL_ARB_direct_state_access");
        System.out.println();

        System.out.println("--- WGL 扩展 / NV_DX_interop（对照，AMD 上这条老路就是问题所在） ---");
        final String wglExt = wglExtensionString();
        if (wglExt == null) {
            System.out.println("    （拿不到 WGL 扩展字符串）");
        } else {
            System.out.println("    WGL_EXT_memory_object_win32 = " + wglExt.contains("WGL_EXT_memory_object_win32"));
            System.out.println("    WGL_EXT_memory_object       = " + wglExt.contains("WGL_EXT_memory_object "));
            System.out.println("    WGL_NV_DX_interop           = " + wglExt.contains("WGL_NV_DX_interop "));
            System.out.println("    WGL_NV_DX_interop2          = " + wglExt.contains("WGL_NV_DX_interop2"));
            System.out.println("    原始 WGL 扩展串: " + wglExt);
        }
        WGLCapabilities wgl = null;
        try {
            wgl = GL.getCapabilitiesWGL();
            if (wgl == null || wgl.wglDXOpenDeviceNV == 0L) {
                GL.createCapabilitiesWGL();
                wgl = GL.getCapabilitiesWGL();
            }
        } catch (Throwable t) {
            System.out.println("    WGL 能力查询异常: " + t);
        }
        System.out.println("    WGL_NV_DX_interop      = " + (wgl != null && wgl.WGL_NV_DX_interop));
        System.out.println("    WGL_NV_DX_interop2     = " + (wgl != null && wgl.WGL_NV_DX_interop2));
        System.out.println("    wglDXOpenDeviceNV 指针 = "
                + (wgl != null && wgl.wglDXOpenDeviceNV != 0L ? "已装载" : "缺失"));
        System.out.println();
    }

    /** 取 WGL 扩展字符串（失败返回 null）。 */
    private static String wglExtensionString() {
        try {
            final long dc = org.lwjgl.opengl.WGL.wglGetCurrentDC();
            if (dc == 0L) return null;
            return org.lwjgl.opengl.WGLARBExtensionsString.wglGetExtensionsStringARB(dc);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void probeLine(List<String> exts, String name) {
        System.out.printf("    %-32s %s%n", name, exts.contains(name) ? "✔" : "✘");
    }

    // -----------------------------------------------------------------------
    // 2) 找出 GL 上下文所在适配器的 LUID
    //
    // NV_DX_interop 那边只能"逐个适配器试 wglDXOpenDeviceNV"来猜；EXT_memory_object 提供了
    // 一个**正规**答案：GL_DEVICE_LUID_EXT 直接给出 GL 设备的 LUID，拿去和 DXGI 适配器比就行。
    // -----------------------------------------------------------------------

    private static long resolveGlAdapterLuid() {
        System.out.println("--- GL 上下文所在适配器 ---");
        final var caps = GL.getCapabilities();
        long luid = 0L;
        if (caps.GL_EXT_memory_object) {
            try {
                final ByteBuffer buf = BufferUtils.createByteBuffer(8);
                // 注意：GL_DEVICE_LUID_EXT 定义在 EXTMemoryObjectWin32 上，不在 EXTMemoryObject 上
                EXTMemoryObject.glGetUnsignedBytevEXT(EXTMemoryObjectWin32.GL_DEVICE_LUID_EXT, buf);
                // LUID 是 LARGE_INTEGER：前 4 字节 LowPart、后 4 字节 HighPart（小端）
                final long low = Integer.toUnsignedLong(buf.getInt(0));
                final long high = Integer.toUnsignedLong(buf.getInt(4));
                luid = (high << 32) | low;
                System.out.printf("    GL_DEVICE_LUID_EXT = %08x:%08x（正规来源，优先用它）%n",
                        high, low);
            } catch (Throwable t) {
                System.out.println("    GL_DEVICE_LUID_EXT 读取失败: " + t);
            }
        } else {
            System.out.println("    GL_EXT_memory_object 不在 → 拿不到 GL_DEVICE_LUID_EXT");
        }
        System.out.println();
        return luid;
    }

    /**
     * 在指定 LUID 上建 D3D11 设备；LUID = 0 表示"默认硬件适配器"。
     *
     * <p>没拿到 LUID 时的兜底：逐个适配器建设备，取第一个**能用内存对象导入成功**的
     * ——在 {@link #testMemoryObjectImport} 里会真正验证；这里先取第一个非软件适配器。
     */
    private static long createDeviceOn(long luid) {
        if (luid != 0L) {
            final long dev = NativeD3D.createDevice(luid, NativeD3D.FLAG_BGRA_SUPPORT);
            System.out.println("--- D3D11 设备 ---");
            System.out.printf("    在 LUID %08x:%08x 上建设备: %s%n", (int) (luid >>> 32), (int) luid,
                    dev != 0L ? "✔" : "✘ " + NativeD3D.lastError());
            System.out.println();
            return dev;
        }
        System.out.println("--- D3D11 设备 ---");
        for (NativeD3D.Adapter a : NativeD3D.enumerateAdapters()) {
            System.out.println("    适配器: " + a);
        }
        for (NativeD3D.Adapter a : NativeD3D.enumerateAdapters()) {
            if (a.isSoftwareOrRemote()) continue;
            final long dev = NativeD3D.createDevice(a.luid(), NativeD3D.FLAG_BGRA_SUPPORT);
            if (dev != 0L) {
                System.out.println("    没拿到 GL LUID，先取第一个非软件适配器: " + a.luidText()
                        + "（下面的导入实验才是真正的判据）");
                System.out.println();
                return dev;
            }
        }
        System.out.println();
        return 0L;
    }

    // -----------------------------------------------------------------------
    // 3) 核心实验：D3D11 NT 共享句柄 → GL 纹理（内存对象）
    // -----------------------------------------------------------------------

    private static void testMemoryObjectImport(long device, long glLuid) {
        System.out.println("--- 实验 A：D3D11 NT 共享句柄 → GL 内存对象 ---");
        final var caps = GL.getCapabilities();
        if (!caps.GL_EXT_memory_object || !caps.GL_EXT_memory_object_win32) {
            report.add("内存对象导入: ✘ 驱动没有 GL_EXT_memory_object_win32"
                    + "（memory_object=" + caps.GL_EXT_memory_object
                    + " win32=" + caps.GL_EXT_memory_object_win32 + "）→ 这条路这台机器上不成立");
            failures++;
            System.out.println();
            return;
        }

        // ---- 夹具自检：先用一张**普通** GL 纹理走同一条采样路径 ----
        // 没有这一步就无法区分"我的采样夹具画错了"和"共享显存读不到"——第一次跑就吃过这个亏：
        // 导入明明成功，回读全是 0，其实分不清是哪一边的问题。
        System.out.println("    [夹具自检] " + controlSamplingSelfTest());

        // ---- 共享纹理 + 共享 fence ----
        final long[] made = NativeD3D.createSharedTexture(device, WIDTH, HEIGHT, null);
        if (made == null) {
            report.add("建共享纹理: ✘ " + NativeD3D.lastError());
            failures++;
            System.out.println();
            return;
        }
        final long dxTexture = made[0];
        final long ntHandle = made[1];
        System.out.printf("    共享纹理: %dx%d BGRA8 NTHANDLE|KEYEDMUTEX，NT 句柄 = 0x%x%n",
                WIDTH, HEIGHT, ntHandle);

        final long[] fencePair = NativeD3D.createSharedFence(device, 0L, null);
        if (fencePair == null) {
            report.add("建共享 fence: ✘ " + NativeD3D.lastError() + "（没法做 GPU 间同步）");
            failures++;
            NativeD3D.release(dxTexture);
            System.out.println();
            return;
        }
        final long fencePtr = fencePair[0];
        final long fenceHandle = fencePair[1];
        System.out.printf("    共享 fence: 句柄 = 0x%x（GL_EXT_semaphore_win32 的载体）%n", fenceHandle);

        // ---- 写方的完整发布协议（与生产完全一致） ----
        //   ① 拿到键控互斥（创建者本来就以 key 0 持有，这里显式 Acquire 只是为了姿势一致）
        //   ② 写内容
        //   ③ GPU 上 Signal(fence, 1)：告诉读方"写到哪了"
        //   ④ ReleaseSync(1)：把资源交棒出去，读方才可能看见
        final int acq = NativeD3D.acquireSync(dxTexture, NativeD3D.KEY_WRITER, 100);
        System.out.println("    写方 AcquireSync(0) = " + acq
                + (acq == 0 ? "（拿到）" : acq == 1 ? "（超时）" : "（出错: " + NativeD3D.lastError() + "）"));
        if (!NativeD3D.fillTexture(device, dxTexture, STAMP_R, STAMP_G)) {
            report.add("填充共享纹理: ✘ " + NativeD3D.lastError());
            failures++;
            NativeD3D.release(dxTexture);
            System.out.println();
            return;
        }
        final boolean signalled = NativeD3D.fenceSignal(device, fencePtr, 1L);
        System.out.println("    写方 Signal(fence, 1) = " + signalled
                + (signalled ? "" : "（" + NativeD3D.lastError() + "）"));
        NativeD3D.releaseSync(dxTexture, NativeD3D.KEY_READER);   // 交棒给读方（key 1）

        // ---- handleType × size 矩阵：把"枚举写错"和"驱动不支持"分开 ----
        final int[] handleTypes = {
                EXTMemoryObjectWin32.GL_HANDLE_TYPE_D3D11_IMAGE_EXT,
                EXTMemoryObjectWin32.GL_HANDLE_TYPE_D3D11_IMAGE_KMT_EXT,
                EXTMemoryObjectWin32.GL_HANDLE_TYPE_OPAQUE_WIN32_EXT,
        };
        final String[] handleTypeNames = {
                "GL_HANDLE_TYPE_D3D11_IMAGE_EXT(0x958B, NT 句柄)",
                "GL_HANDLE_TYPE_D3D11_IMAGE_KMT_EXT(0x958C, 老 KMT 句柄)",
                "GL_HANDLE_TYPE_OPAQUE_WIN32_EXT(0x9587, Vulkan 风格)",
        };
        // size 参数对 D3D11 句柄有歧义（有的驱动要 0、有的要字节数），都试一遍。
        final long[] sizes = {0L, (long) WIDTH * HEIGHT * 4, (long) WIDTH * HEIGHT * 4 + 65536};
        final String[] sizeNames = {"size=0", "size=w*h*4", "size=w*h*4+64K"};

        boolean anySuccess = false;
        int winType = 0;
        long winSize = 0;
        for (int h = 0; h < handleTypes.length && !anySuccess; h++) {
            for (int s = 0; s < sizes.length; s++) {
                final String outcome = tryImport(ntHandle, handleTypes[h], sizes[s]);
                final boolean ok = outcome == null;
                System.out.printf("    导入 %-46s %-18s %s%n", handleTypeNames[h], sizeNames[s],
                        ok ? "✔ 成功" : "✘ " + outcome);
                if (ok) {
                    anySuccess = true;
                    winType = handleTypes[h];
                    winSize = sizes[s];
                    break;   // 这个 handleType 已经成功，不用再试别的 size
                }
            }
        }

        // ---- 采样校验：GL 先等 fence=1（GPU 侧等，不占 CPU），读完再 signal(fence, 2) ----
        if (anySuccess) {
            final String verify = verifySampling(ntHandle, winType, winSize, fencePtr, fenceHandle);
            System.out.println("    采样校验: " + verify);
            if (!verify.startsWith("✔")) failures++;
            report.add("内存对象导入: ✔ 成功（" + (verify.startsWith("✔") ? "内容与通道都对" : "但内容校验有问题") + "）");
        } else {
            failures++;
            report.add("内存对象导入: ✘ 三个 HandleType × 三种 size 全部失败 —— 这台机器的 GL 驱动"
                    + "不接受 D3D11 共享句柄");
        }
        NativeD3D.release(fencePtr);
        NativeD3D.release(dxTexture);
        System.out.println();
    }

    /** @return null = 成功；否则返回失败原因文本。 */
    private static String tryImport(long ntHandle, int handleType, long size) {
        final int mem = EXTMemoryObject.glCreateMemoryObjectsEXT();
        if (mem == 0) return "glCreateMemoryObjectsEXT 返回 0";
        drainGlError();
        EXTMemoryObjectWin32.glImportMemoryWin32HandleEXT(mem, size, handleType, ntHandle);
        int err = GL11.glGetError();
        if (err != 0) {
            EXTMemoryObject.glDeleteMemoryObjectsEXT(mem);
            return String.format("glImportMemoryWin32HandleEXT → %s", glErrorName(err));
        }
        // 用内存对象给一张 GL 纹理分配存储（这一步失败通常说明"格式/尺寸对不上"）
        final int tex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        EXTMemoryObject.glTexStorageMem2DEXT(GL11.GL_TEXTURE_2D, 1, GL_RGBA8, WIDTH, HEIGHT, mem, 0);
        err = GL11.glGetError();
        if (err != 0) {
            GL11.glDeleteTextures(tex);
            EXTMemoryObject.glDeleteMemoryObjectsEXT(mem);
            return String.format("glTexStorageMem2DEXT(GL_RGBA8) → %s", glErrorName(err));
        }
        GL11.glDeleteTextures(tex);
        EXTMemoryObject.glDeleteMemoryObjectsEXT(mem);
        return null;
    }

    /**
     * GL 错误码翻译。
     *
     * <p>★ 实测（NVIDIA 616.92）：给 {@code glImportMemoryWin32HandleEXT} 传一个**不支持的
     * handleType** 时报的是 <b>{@code GL_OUT_OF_MEMORY}</b>（0x505），既不是 INVALID_ENUM 也不是
     * INVALID_VALUE。所以看到 0x505 不要以为是显存不够 —— 大多数情况是"这个 handleType 不支持"。
     */
    private static String glErrorName(int err) {
        return switch (err) {
            case 0x500 -> "GL_INVALID_ENUM(0x500)";
            case 0x501 -> "GL_INVALID_VALUE(0x501)";
            case 0x502 -> "GL_INVALID_OPERATION(0x502)";
            case 0x505 -> "GL_OUT_OF_MEMORY(0x505) ← 驱动不支持该 handleType 时就是这个";
            default -> String.format("0x%X", err);
        };
    }

    /**
     * 夹具自检：拿一张**普通** GL 纹理（内容由 CPU 上传）走同一条"画进 FBO → glReadPixels"的路径。
     *
     * <p>为什么必须有：导入成功但回读全 0 时，原因可能是"共享显存没读到"，也可能是
     * "我的采样夹具本身画错了"。先证明夹具能读到已知的 CPU 数据，后面的 0 才有意义。
     */
    private static String controlSamplingSelfTest() {
        drainGlError();
        final int tex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        final ByteBuffer src = BufferUtils.createByteBuffer(WIDTH * HEIGHT * 4);
        for (int i = 0; i < WIDTH * HEIGHT; i++) {
            src.put((byte) STAMP_R).put((byte) STAMP_G).put((byte) STAMP_BLUE).put((byte) 0xFF);
        }
        src.flip();
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL_RGBA8, WIDTH, HEIGHT, 0, GL11.GL_RGBA,
                GL11.GL_UNSIGNED_BYTE, src);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        final int[] px = sampleAt(tex, false, WIDTH / 2, HEIGHT / 2);
        GL11.glDeleteTextures(tex);
        final boolean ok = px[0] == STAMP_R && px[1] == STAMP_G && px[2] == STAMP_BLUE;
        return String.format("%s 普通纹理回读到 R=%02X G=%02X B=%02X（期望 %02X/%02X/%02X）",
                ok ? "✔" : "✘", px[0], px[1], px[2], STAMP_R, STAMP_G, STAMP_BLUE);
    }

    /**
     * 把导入的纹理画进一张 FBO 再回读，验证内容。
     *
     * <p>同步走的正是生产要用的那条链：
     * <pre>
     *   写方 D3D:  写内容 → Signal(fence, 1) → ReleaseSync(key=读方)
     *   读方 GL :  glSemaphoreParameterui64(GL_D3D12_FENCE_VALUE_EXT, 1)
     *              → glWaitSemaphoreEXT(把纹理列为 barrier)   ← GPU 侧等，不占 CPU
     *              → 采样 → glSignalSemaphoreEXT(告诉写方"我读完了")
     * </pre>
     *
     * <p>编解码约定：生产者把 B 固定成 0xA5、R=STAMP_R、G=STAMP_G；左上/左下还各有一块
     * 8×8 的 G 角标（0x11 / 0x22）用来判定上下方向。回读时同时报告"加不加 swizzle"的结果，
     * 这样通道顺序是**测出来的**，不是猜的。
     */
    private static String verifySampling(long ntHandle, int handleType, long size, long fencePtr,
                                         long fenceHandle) {
        drainGlError();
        final int mem = EXTMemoryObject.glCreateMemoryObjectsEXT();
        EXTMemoryObjectWin32.glImportMemoryWin32HandleEXT(mem, size, handleType, ntHandle);
        int err = GL11.glGetError();
        if (err != 0) return String.format("✘ 重新导入失败 %s", glErrorName(err));

        final int tex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        EXTMemoryObject.glTexStorageMem2DEXT(GL11.GL_TEXTURE_2D, 1, GL_RGBA8, WIDTH, HEIGHT, mem, 0);
        err = GL11.glGetError();
        if (err != 0) return String.format("✘ 分配存储失败 %s", glErrorName(err));
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        // BGRA 显存 → GL_RGBA8 采样：把 R/B 对调，数据就"严格对应"上了。
        // ★ GL_TEXTURE_SWIZZLE_RGBA 只能配 glTexParameteriv 用（一次给 4 个值），
        //   拿它当 glTexParameteri 的 pname 是无效枚举 —— 这里踩过一次。
        GL11.glTexParameteriv(GL11.GL_TEXTURE_2D, GL_TEXTURE_SWIZZLE_RGBA,
                swizzle(GL11.GL_BLUE, GL11.GL_GREEN, GL11.GL_RED, GL11.GL_ALPHA));

        // 导入 fence 成 GL 信号量，并让 GL 在采样前等 fence=1
        final int sem = EXTSemaphore.glGenSemaphoresEXT();
        EXTSemaphoreWin32.glImportSemaphoreWin32HandleEXT(
                sem, EXTSemaphoreWin32.GL_HANDLE_TYPE_D3D12_FENCE_EXT, fenceHandle);
        err = GL11.glGetError();
        if (err != 0) return String.format("✘ 导入 fence 信号量失败 %s", glErrorName(err));
        EXTSemaphore.glSemaphoreParameterui64EXT(sem, EXTSemaphoreWin32.GL_D3D12_FENCE_VALUE_EXT, 1L);
        final int[] noBuffers = {};
        final int[] texBarrier = {tex};
        final int[] readLayout = {EXTSemaphore.GL_LAYOUT_SHADER_READ_ONLY_EXT};
        EXTSemaphore.glWaitSemaphoreEXT(sem, noBuffers, texBarrier, readLayout);
        err = GL11.glGetError();
        if (err != 0) return String.format("✘ glWaitSemaphoreEXT 失败 %s", glErrorName(err));

        final int[] rawNoSwizzle = sampleOnce(tex, false);
        final int[] rawSwizzled = sampleOnce(tex, true);
        // 角标写在 D3D 内存的第 0..7 行（= D3D 眼里的**图像顶部**）与最后 8 行（底部）。
        // 分别按 GL 的 v≈0 / v≈1 采一次，就能知道"GL 的 v=0 对着哪一头"，进而判断上屏要不要翻。
        final int[] atGlV0 = sampleAt(tex, true, 4, 4);
        final int[] atGlV1 = sampleAt(tex, true, 4, HEIGHT - 4);

        // 读完 → 告诉写方"这个槽位我释放了"（fence 推进到 2）
        EXTSemaphore.glSemaphoreParameterui64EXT(sem, EXTSemaphoreWin32.GL_D3D12_FENCE_VALUE_EXT, 2L);
        EXTSemaphore.glSignalSemaphoreEXT(sem, noBuffers, texBarrier,
                new int[]{EXTSemaphore.GL_LAYOUT_SHADER_READ_ONLY_EXT});
        GL11.glFinish();
        final int backRc = NativeD3D.fenceWaitCpu(fencePtr, 2L, 2000);

        GL11.glDeleteTextures(tex);
        EXTMemoryObject.glDeleteMemoryObjectsEXT(mem);
        EXTSemaphore.glDeleteSemaphoresEXT(sem);

        final boolean correct = rawSwizzled[0] == STAMP_R && rawSwizzled[1] == STAMP_G
                && rawSwizzled[2] == STAMP_BLUE;
        // D3D 的图像"顶部" = 内存第 0 行；GL 把 v=0 当**底部**。
        //   GL 的 v≈0 读到顶部角标(0x11) ⇒ 上屏时图像会上下颠倒，渲染要用 vUv.y = 1-y
        final String orientation = atGlV0[1] == 0x11
                ? "GL 的 v=0 对着 D3D 图像顶部 ⇒ 上屏要翻转（vUv.y = 1-y），与 NV_DX_interop 那条路一致"
                : atGlV0[1] == 0x22
                  ? "GL 的 v=0 对着 D3D 图像底部 ⇒ 不需要翻转"
                  : "角标都读不到（v0=G" + atGlV0[1] + " v1=G" + atGlV1[1] + "）";
        return String.format("%s 加 swizzle(R←B) 读到 R=%02X G=%02X B=%02X（期望 %02X/%02X/%02X）；"
                        + "不加 swizzle 读到 R=%02X G=%02X B=%02X（这就是 BGRA 直读的样子）；"
                        + "方向: %s；回程 fence: %s",
                correct ? "✔" : "✘",
                rawSwizzled[0], rawSwizzled[1], rawSwizzled[2], STAMP_R, STAMP_G, STAMP_BLUE,
                rawNoSwizzle[0], rawNoSwizzle[1], rawNoSwizzle[2], orientation,
                backRc == 0 ? "✔ 写方能收到（fence=" + NativeD3D.fenceCompleted(fencePtr) + "）"
                            : "✘ rc=" + backRc);
    }

    private static int[] swizzle(int r, int g, int b, int a) {
        return new int[]{r, g, b, a};
    }

    private static int[] sampleOnce(int texture, boolean withSwizzle) {
        return sampleAt(texture, withSwizzle, WIDTH / 2, HEIGHT / 2);
    }

    /** 把纹理画进 FBO_SIZE 的 FBO 并回读一个像素。 */
    private static int[] sampleAt(int texture, boolean withSwizzle, int srcX, int srcY) {
        final int program = buildProgram();
        final int vao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(vao);
        final int fbo = GL30.glGenFramebuffers();
        final int color = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, color);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL_RGBA8, FBO_SIZE, FBO_SIZE, 0, GL11.GL_RGBA,
                GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL30.glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        GL30.glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, color, 0);
        if (GL30.glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            return new int[]{-1, -1, -1, -1};
        }
        GL11.glViewport(0, 0, FBO_SIZE, FBO_SIZE);
        // 先用洋红清屏：这样"回读到 0"和"根本没画"能分开（0 与洋红是两种不同的失败）
        GL11.glClearColor(1.0f, 0.0f, 1.0f, 1.0f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        GL20.glUseProgram(program);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL20.glUniform1i(GL20.glGetUniformLocation(program, "uTex"), 0);
        // 采样点：把源像素 (srcX, srcY) 映射到 FBO 的 (FBO_SIZE/2, FBO_SIZE/2)
        GL20.glUniform2f(GL20.glGetUniformLocation(program, "uUv"),
                (srcX + 0.5f) / WIDTH, (srcY + 0.5f) / HEIGHT);
        // swizzle 是**纹理对象状态**，而且会残留：所以每次采样都显式设一遍，
        // 而不是"设一次 + 另一处还原" —— 后者会被调用顺序坑（第一次跑就是这么读到两组一样的结果）。
        GL11.glTexParameteriv(GL11.GL_TEXTURE_2D, GL_TEXTURE_SWIZZLE_RGBA,
                withSwizzle ? swizzle(GL11.GL_BLUE, GL11.GL_GREEN, GL11.GL_RED, GL11.GL_ALPHA)
                            : swizzle(GL11.GL_RED, GL11.GL_GREEN, GL11.GL_BLUE, GL11.GL_ALPHA));
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        GL11.glFinish();
        // ★ 必须用 ByteBuffer 按**字节**回读：GL_UNSIGNED_BYTE 写的是 4 个字节，
        //   用 IntBuffer 读会把 4 个通道当成一个小端 int（实测踩过）。
        final ByteBuffer px = BufferUtils.createByteBuffer(4);
        GL11.glReadPixels(FBO_SIZE / 2, FBO_SIZE / 2, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, px);
        final int[] out = {px.get(0) & 0xFF, px.get(1) & 0xFF, px.get(2) & 0xFF, px.get(3) & 0xFF};

        GL30.glBindFramebuffer(GL_FRAMEBUFFER, 0);
        GL30.glDeleteFramebuffers(fbo);
        GL11.glDeleteTextures(color);
        GL20.glDeleteProgram(program);
        GL30.glDeleteVertexArrays(vao);
        return out;
    }

    private static int buildProgram() {
        final String vs = """
                #version 330 core
                out vec2 vUv;
                void main() {
                    // 用一个覆盖全屏的大三角形，不需要 VBO
                    vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                    vUv = p;
                    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
                }
                """;
        final String fs = """
                #version 330 core
                in vec2 vUv;
                out vec4 fragColor;
                uniform sampler2D uTex;
                uniform vec2 uUv;
                void main() {
                    fragColor = texture(uTex, uUv);
                }
                """;
        final int v = compile(GL20.GL_VERTEX_SHADER, vs);
        final int f = compile(GL20.GL_FRAGMENT_SHADER, fs);
        final int p = GL20.glCreateProgram();
        GL20.glAttachShader(p, v);
        GL20.glAttachShader(p, f);
        GL20.glLinkProgram(p);
        if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            throw new IllegalStateException("链接失败: " + GL20.glGetProgramInfoLog(p));
        }
        GL20.glDeleteShader(v);
        GL20.glDeleteShader(f);
        return p;
    }

    private static int compile(int type, String src) {
        final int s = GL20.glCreateShader(type);
        GL20.glShaderSource(s, src);
        GL20.glCompileShader(s);
        if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            throw new IllegalStateException("编译失败: " + GL20.glGetShaderInfoLog(s));
        }
        return s;
    }

    // -----------------------------------------------------------------------
    // 4) 实验 B：共享 D3D11 fence → GL_EXT_semaphore_win32
    // -----------------------------------------------------------------------

    private static void testSharedFenceSemaphore(long device) {
        System.out.println("--- 实验 B：共享 D3D11 fence → GL 信号量（GPU 间同步） ---");
        final var caps = GL.getCapabilities();
        if (!caps.GL_EXT_semaphore || !caps.GL_EXT_semaphore_win32) {
            report.add("fence 信号量: ✘ 驱动没有 GL_EXT_semaphore_win32"
                    + "（semaphore=" + caps.GL_EXT_semaphore
                    + " win32=" + caps.GL_EXT_semaphore_win32 + "）");
            failures++;
            System.out.println();
            return;
        }
        final long[] fence = NativeD3D.createSharedFence(device, 0L, null);
        if (fence == null) {
            report.add("fence 信号量: ✘ 建共享 fence 失败（" + NativeD3D.lastError() + "）");
            failures++;
            System.out.println();
            return;
        }
        final long fencePtr = fence[0];
        final long fenceHandle = fence[1];
        System.out.printf("    共享 fence: 指针=0x%x 句柄=0x%x%n", fencePtr, fenceHandle);

        // D3D 侧先推进到 7，再让 GL 侧等 7
        final boolean signalled = NativeD3D.fenceSignal(device, fencePtr, 7L);
        System.out.println("    D3D Signal(7) = " + signalled
                + (signalled ? "" : "（" + NativeD3D.lastError() + "）"));

        drainGlError();
        final int sem = EXTSemaphore.glGenSemaphoresEXT();
        EXTSemaphoreWin32.glImportSemaphoreWin32HandleEXT(
                sem, EXTSemaphoreWin32.GL_HANDLE_TYPE_D3D12_FENCE_EXT, fenceHandle);
        int err = GL11.glGetError();
        if (err != 0) {
            report.add(String.format("fence 信号量: ✘ glImportSemaphoreWin32HandleEXT → 0x%X", err));
            failures++;
            System.out.println("    导入: ✘ 0x" + Integer.toHexString(err));
            System.out.println();
            return;
        }
        System.out.println("    导入: ✔ glImportSemaphoreWin32HandleEXT(GL_HANDLE_TYPE_D3D12_FENCE_EXT)");

        EXTSemaphore.glSemaphoreParameterui64EXT(sem, EXTSemaphoreWin32.GL_D3D12_FENCE_VALUE_EXT, 7L);
        final IntBuffer emptyB = BufferUtils.createIntBuffer(0);
        final IntBuffer emptyT = BufferUtils.createIntBuffer(0);
        final IntBuffer emptyL = BufferUtils.createIntBuffer(0);
        EXTSemaphore.glWaitSemaphoreEXT(sem, emptyB, emptyT, emptyL);
        err = GL11.glGetError();
        System.out.println("    GPU 侧 glWaitSemaphoreEXT(7): " + (err == 0 ? "✔ 已排入命令流" : "✘ 0x" + Integer.toHexString(err)));

        // 反过来：GL 侧 signal 11，D3D 侧在 CPU 上等到 11（证明双向都通）
        EXTSemaphore.glSemaphoreParameterui64EXT(sem, EXTSemaphoreWin32.GL_D3D12_FENCE_VALUE_EXT, 11L);
        EXTSemaphore.glSignalSemaphoreEXT(sem, emptyB, emptyT, emptyL);
        GL11.glFinish();
        final int waitRc = NativeD3D.fenceWaitCpu(fencePtr, 11L, 2000);
        System.out.println("    GL signal(11) → D3D 侧等到 11: "
                + (waitRc == 0 ? "✔（fence 已完成到 " + NativeD3D.fenceCompleted(fencePtr) + "）"
                               : "✘ rc=" + waitRc + " 当前完成值=" + NativeD3D.fenceCompleted(fencePtr)));
        final boolean ok = err == 0 && waitRc == 0;
        if (!ok) failures++;
        report.add("fence 信号量: " + (ok ? "✔ 双向都通" : "✘ 单向或双向不通"));
        NativeD3D.release(fencePtr);
        System.out.println();
    }

    // -----------------------------------------------------------------------

    private static void drainGlError() {
        // GL 的错误是"累积的"，一次只报一个 —— 每次实验前清空，才能把错误归到具体调用上
        for (int i = 0; i < 8; i++) {
            if (GL11.glGetError() == 0) break;
        }
    }

    private static void printReport() {
        System.out.println("=== 结论 ===");
        for (String s : report) {
            System.out.println("  " + s);
        }
        System.out.println();
        System.out.println(failures == 0
                ? "总体: ✔ 这台机器上「NT 共享句柄 → GL 内存对象」这条路成立"
                : "总体: ✘ 有 " + failures + " 项不成立（把上面整段发回来即可）");
        System.out.println();
        System.out.println("（提示：这条结论只与显卡驱动有关，与 Minecraft / 本 mod 无关；"
                + "把整段输出发回来就能判断要不要切到这条路线。）");
    }

    private MoProbe() {
    }
}

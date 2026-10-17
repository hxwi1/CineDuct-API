package dev.mediafix.gpu;

import java.util.ArrayList;
import java.util.List;

/**
 * 游戏进程内的 D3D11 原生垫片绑定（{@code cineduct-glshim.dll}）。
 *
 * <p><b>为什么必须有这一层</b>：零拷贝路线是「D3D11 共享纹理 → {@code wglDXRegisterObjectNV}
 * → GL 纹理」，而 LWJGL 只绑定 OpenGL/WGL，不绑定 D3D11。要拿到
 * {@code ID3D11Device*}（{@code wglDXOpenDeviceNV} 的入参）并在本进程打开共享纹理，
 * 只能在游戏进程里调 D3D11 —— 就是这个小垫片干的事（约 200 行 C）。
 *
 * <p>所有返回 {@code long} 的方法返回的都是原生的 COM 指针（当不透明句柄用），
 * 用完必须 {@link #release(long)}。本类只做绑定，不做生命周期管理。
 *
 * <p>{@code FLAG_*} 的值必须与 {@code native/glshim/include/cineduct_glshim.h} 一致。
 */
public final class NativeD3D {

    /** NV_DX_interop 使用 BGRA8 纹理的前提。 */
    public static final int FLAG_BGRA_SUPPORT = 0x1;
    /** d3d11va 硬解的前提（子进程用；游戏进程不需要）。 */
    public static final int FLAG_VIDEO_SUPPORT = 0x2;
    /** D3D11 调试层。 */
    public static final int FLAG_DEBUG = 0x4;
    /** {@code ID3D10Multithread::SetMultithreadProtected}。 */
    public static final int FLAG_MULTITHREAD = 0x8;

    /** 一个 DXGI 适配器。 */
    public record Adapter(long luid, String description, long dedicatedVideoMemoryMb, int flags) {

        /** 形如 {@code 00000001:0a2b3c4d}，与 {@code /cineduct gpu} 的输出一致。 */
        public String luidText() {
            return String.format("%08x:%08x", (int) (luid >>> 32), (int) luid);
        }

        /** 软件/远程适配器（虚拟显示器、模拟器）不能用来做互操作。 */
        public boolean isSoftwareOrRemote() {
            return (flags & 0x2) != 0 || (flags & 0x4) != 0;   // DXGI_ADAPTER_FLAG_SOFTWARE / REMOTE
        }

        @Override
        public String toString() {
            return description + " luid=" + luidText() + " vram=" + dedicatedVideoMemoryMb + "MB"
                    + (isSoftwareOrRemote() ? " (软件/远程)" : "");
        }
    }

    /**
     * 垫片加载失败的现场（null = 已就绪）。
     *
     * <p>JVM 对 native 方法**不会**自动去找 {@code cineduct-glshim.dll} —— 它按类名找
     * {@code NativeD3D.dll}。所以这里必须显式加载：生产环境由 mod 把 DLL 解压到临时目录后
     * 用绝对路径 {@code System.load}（见系统属性 {@code cineduct.glshim}），
     * 开发/打样阶段则交给 {@code java.library.path}。
     */
    private static final Throwable LOAD_ERROR;

    /** 显式指定垫片 DLL 绝对路径的系统属性；不设则按库名在 java.library.path 里找。 */
    public static final String PROPERTY_PATH = "cineduct.glshim";

    static {
        Throwable err = null;
        try {
            String explicit = System.getProperty(PROPERTY_PATH);
            if (explicit != null && !explicit.isBlank()) {
                System.load(explicit);
            } else {
                // 优先用 jar 里释放出来的那份（版本一定和 mod 配套）
                java.nio.file.Path released = releasedShimPath();
                if (released != null) {
                    System.load(released.toAbsolutePath().toString());
                } else {
                    System.loadLibrary("cineduct-glshim");
                }
            }
        } catch (Throwable t) {
            err = t;
        }
        LOAD_ERROR = err;
    }

    /**
     * 取 jar 释放出来的垫片路径 —— 走**反射**调用 {@code dev.mediafix.NativeFiles}。
     *
     * <p>为什么不直接调用：这个类还要能在**脱离 NeoForge** 的场合单独编译
     * （P1/P2 的原生打样工具 {@code native/build.ps1} 会把本文件拎出来单独 javac，
     * 而 {@code NativeFiles} 依赖 FML 的 {@code FMLPaths}）。反射在这里是刻意的解耦：
     * 游戏里该类一定存在，打样工具里取不到就回退到 {@code java.library.path}。
     */
    private static java.nio.file.Path releasedShimPath() {
        try {
            final Class<?> cls = Class.forName("dev.mediafix.NativeFiles");
            final Object path = cls.getMethod("file", String.class).invoke(null, "cineduct-glshim.dll");
            return path instanceof java.nio.file.Path p ? p : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 是否已经能加载垫片 DLL。false 时不要调用下面任何 native 方法。 */
    public static boolean available() {
        return LOAD_ERROR == null;
    }

    /** 垫片加载失败的原因（可用时返回 null）。 */
    public static Throwable loadError() {
        return LOAD_ERROR;
    }

    /**
     * 创建 D3D11 设备。
     *
     * @param adapterLuid 适配器 LUID（{@link Adapter#luid()}）；0 = 默认硬件适配器
     * @param flags       {@code FLAG_*} 的按位或
     * @return {@code ID3D11Device*}；失败返回 0，原因见 {@link #lastError()}
     */
    public static native long createDevice(long adapterLuid, int flags);

    /** 释放一个由本类返回的原生对象（COM {@code Release()}）。 */
    public static native void release(long object);

    /** 设备所在适配器的 LUID（打包成 64 位）；失败返回 0。 */
    public static native long adapterLuidOf(long device);

    /** 按名打开共享纹理（打样用；生产走 NT 句柄 + {@code OpenSharedResource1}）。 */
    public static native long openSharedTextureByName(long device, String name);

    /**
     * 按 NT 句柄打开共享纹理（**生产路径**）。
     *
     * <p>子进程用 {@code IDXGIResource1::CreateSharedHandle} 建 NT 句柄，再 {@code DuplicateHandle}
     * 投递到游戏进程；本方法在游戏进程里用句柄值打开。比按名路径好的地方：不留全局命名对象、
     * 不需要额外的命名约定，也就没有被本机其它进程顶替的风险。
     *
     * @param handle 已经在本进程有效的 NT 句柄值
     */
    public static native long openSharedTextureByHandle(long device, long handle);

    // ---- 纹理标志（D3D11 原值，做互操作标志矩阵实验用） ----
    //
    // ⚠️ 2026-10 修正：这三个值以前写错了（SHARED 写成 0x1、KEYEDMUTEX 写成 0x2）。
    // d3d11.h 里的真值是下面这三个；打样时的标志矩阵实验传的是**裸数字**，
    // 所以那批结论（0x802 = SHARED|NTHANDLE 注册不上）本身没错，错的只是这里的名字。
    /** {@code D3D11_RESOURCE_MISC_SHARED}。 */
    public static final int MISC_SHARED = 0x2;
    /** {@code D3D11_RESOURCE_MISC_SHARED_KEYEDMUTEX}。 */
    public static final int MISC_SHARED_KEYEDMUTEX = 0x100;
    /** {@code D3D11_RESOURCE_MISC_SHARED_NTHANDLE}（跨进程共享的唯一选择）。 */
    public static final int MISC_SHARED_NTHANDLE = 0x800;
    /** 跨进程共享纹理实际用的组合：{@code NTHANDLE | KEYEDMUTEX} = 0x900。 */
    public static final int MISC_SHARED_NT_KM = MISC_SHARED_NTHANDLE | MISC_SHARED_KEYEDMUTEX;
    public static final int BIND_RENDER_TARGET = 0x20;
    public static final int BIND_SHADER_RESOURCE = 0x8;

    /**
     * 在**本进程**直接建一张纹理（诊断/自检用）。
     *
     * <p>用途：把「我们的互操作调用姿势不对」和「跨进程共享纹理不被支持」这两种失败区分开。
     * 同进程自建纹理能注册成功、跨进程的不行，问题就一定在共享路径上。
     */
    public static native long createTexture(long device, int width, int height,
                                           int miscFlags, int bindFlags);

    /**
     * 把共享纹理的内容在 GPU 上拷进本地纹理（内部会做键控互斥的 Acquire/Release），供 GL 采样。
     *
     * <p><b>为什么必须绕这一下</b>：实测 `NV_DX_interop` **拒绝注册带
     * {@link #MISC_SHARED_NTHANDLE} 的纹理**（misc=0x0/0x1/0x2/0x3 都能注册，0x802 一律返回 0），
     * 而跨进程共享又只能靠 NTHANDLE。所以让共享纹理只负责"跨进程搬运"，游戏进程再
     * GPU→GPU 拷进一张本地纹理（misc=0）交给 GL —— 显存内拷贝几乎免费，
     * 跨 API 的可见性由随后的 {@code wglDXLockObjectsNV} 保证。
     *
     * @return 0 = 拷贝成功；1 = 等锁超时（没有新帧，不算错误）；负数 = 出错，见 {@link #lastError()}
     */
    public static native int copyFromShared(long device, long dstTexture, long srcTexture,
                                            int acquireKey, int releaseKey, int timeoutMs);

    /**
     * 键控互斥的两把钥匙：**必须用两个不同的 key 乒乓交棒**。
     *
     * <p>{@code IDXGIKeyedMutex::AcquireSync} 的语义是"<b>同 key 直接成功</b>"（同 key 是递归的），
     * 所以两侧都用同一个 key 等于完全没有互斥 —— 实测踩过这个坑。正确用法是：
     * 子进程（写方）{@code Acquire(0)} → 写 → {@code Release(1)}；
     * 游戏进程（读方）{@code Acquire(1)} → 拷 → {@code Release(0)}。
     */
    public static final int KEY_WRITER = 0;
    public static final int KEY_READER = 1;

    /** 纹理描述 {@code [width, height, dxgiFormat, bindFlags]}；失败返回 null。 */
    public static native int[] textureDesc(long texture);

    // -----------------------------------------------------------------------
    // NT 共享句柄路线（D3D11 → GL 内存对象）
    //
    // 与上面 NV_DX_interop 那套并列：**不依赖任何厂商特有的 WGL 互操作扩展**。
    // 生产者拿 NT 句柄 → DuplicateHandle 投递 → 消费者 glImportMemoryWin32HandleEXT
    // （handleType = GL_HANDLE_TYPE_D3D11_IMAGE_EXT = 0x958B）直接把显存别成 GL 纹理。
    //
    // 注意：并不存在 GL_HANDLE_TYPE_OPAQUE_WIN32_NT_EXT 这个枚举；EXT_memory_object_win32 里
    // 与 D3D11 纹理对应的值是 D3D11_IMAGE_EXT（NT 句柄）与 D3D11_IMAGE_KMT_EXT（老的 KMT 句柄）。
    // -----------------------------------------------------------------------

    /**
     * 在本进程建一张**可跨进程共享**的 2D 纹理（BGRA8 + NTHANDLE + KEYEDMUTEX）。
     *
     * @param name 命名共享对象名；{@code null} = 无名 NT 句柄（只能靠 {@code DuplicateHandle} 投递）
     * @return {@code [ID3D11Texture2D*, NT 句柄值]}；失败返回 null，原因见 {@link #lastError()}
     */
    public static native long[] createSharedTexture(long device, int width, int height, String name);

    /** 用纯色图案填充纹理（自检用，不需要着色器）；成功返回 true。 */
    public static native boolean fillTexture(long device, long texture, int stampR, int stampG);

    /** 键控互斥加锁：0 = 拿到；1 = 超时（当"没有新帧"处理）；负数 = 出错。 */
    public static native int acquireSync(long texture, int key, int timeoutMs);

    /** 键控互斥解锁。 */
    public static native int releaseSync(long texture, int key);

    /**
     * 建一个可共享的 D3D11 fence（{@code D3D11_FENCE_FLAG_SHARED}）——
     * GL_EXT_semaphore_win32 那条 GPU 间同步链的 D3D 侧载体。
     *
     * @return {@code [ID3D11Fence*, 共享句柄值]}；失败返回 null（老系统没有 ID3D11Device5 时会失败）
     */
    public static native long[] createSharedFence(long device, long initialValue, String name);

    /** 按共享句柄打开 fence（消费方向）。 */
    public static native long openSharedFence(long device, long handle);

    /** GPU 侧 Signal（不占 CPU）。 */
    public static native boolean fenceSignal(long device, long fence, long value);

    /** GPU 侧 Wait：**排队等待，不占 CPU**。 */
    public static native boolean fenceWait(long device, long fence, long value);

    /** 已经完成到的 fence 值（不动 GPU）。 */
    public static native long fenceCompleted(long fence);

    /**
     * CPU 侧有超时地等某个 fence 值：0 = 已到；1 = 超时；负数 = 出错。
     *
     * <p>用途是**避免发出永远不会被满足的 GPU Wait**：对端进程中途死掉时，
     * 直接排一个 {@code fenceWait} 会把 GPU 队列永久挂住（比撕裂严重得多）。
     * 先用这个方法确认值真的会到，再排 GPU Wait。
     */
    public static native int fenceWaitCpu(long fence, long value, int timeoutMs);

    /**
     * 关掉一个裸内核句柄（比如生产者 {@code DuplicateHandle} 直投进来的 NT 句柄）。
     *
     * <p><b>别提前关</b>：GL 侧是在 {@code glImportMemoryWin32HandleEXT} /
     * {@code glTexStorageMem2DEXT} 时才真正建立引用的，保险做法是"整段会话用完再关"。
     */
    public static native int closeRawHandle(long handle);

    /**
     * 打开子进程创建的命名共享内存段，包成直接 {@code ByteBuffer}（零拷贝、零系统调用）。
     *
     * <p>状态环（播放位置 / 缓冲 / 丢帧 / 槽位就绪标志）走这条路：每渲染帧只读一块内存，
     * 不需要任何 IPC 往返。返回的缓冲区默认大端序，读多字节字段前记得
     * {@code order(ByteOrder.LITTLE_ENDIAN)}。
     *
     * @param name  段名（子进程 {@code CreateFileMapping} 用的名字）
     * @param bytes 映射字节数
     * @return 直接缓冲区；失败返回 null，原因见 {@link #lastError()}
     */
    public static native java.nio.ByteBuffer openSharedSection(String name, int bytes);

    /** 关闭 {@link #openSharedSection} 返回的映射。 */
    public static native void closeSharedSection(java.nio.ByteBuffer buffer);

    // ---- 控制通道（命名管道）----
    // Java 没有原生命名管道 API，所以这一层也放在垫片里（见 native/glshim）。

    /** 连接子进程的控制管道；连不上（超时）返回 0。 */
    public static native long pipeConnect(String name, int timeoutMs);

    /** 发一条命令并取回执文本；失败返回 null。 */
    public static native String pipeRequest(long pipe, String payload);

    public static native void pipeClose(long pipe);

    // ---- Job Object（保证子进程不变成孤儿）----

    /** 建一个 KILL_ON_JOB_CLOSE 的 Job（最后一个句柄关闭时连带杀掉里面的进程）。 */
    public static native long jobCreate();

    /** 把指定 pid 的进程放进 Job。 */
    public static native boolean jobAssign(long job, int pid);

    public static native void jobClose(long job);

    /** 最近一次失败原因（英文 + HRESULT）。 */
    public static native String lastError();

    /** 适配器列表（原始字符串形式，见 {@link #enumerateAdapters()}）。 */
    private static native String[] adapters();

    /**
     * 枚举 DXGI 适配器。
     *
     * <p>一台机器上常有多个适配器（本机就有 NVIDIA 独显 + MuMu/GameViewer 虚拟显示器适配器）。
     * 互操作要求 D3D11 设备与 GL 上下文在**同一个**适配器上，所以这一步是必须的：
     * 要么按描述匹配 GL 的 {@code GL_RENDERER}，要么像 {@code GpuInterop} 那样逐个试
     * {@code wglDXOpenDeviceNV} 直到成功（驱动说了算，最可靠）。
     */
    public static List<Adapter> enumerateAdapters() {
        List<Adapter> out = new ArrayList<>();
        String[] raw = adapters();
        if (raw == null) return out;
        for (String line : raw) {
            // 格式: "hi:lo|描述|显存MB|flags"
            String[] parts = line.split("\\|", -1);
            if (parts.length < 4) continue;
            try {
                String[] luid = parts[0].split(":");
                long high = Long.parseUnsignedLong(luid[0], 16);
                long low = Long.parseUnsignedLong(luid[1], 16);
                out.add(new Adapter((high << 32) | low, parts[1],
                        Long.parseLong(parts[2]), Integer.parseInt(parts[3])));
            } catch (Throwable ignored) {
                // 单行解析失败不影响其它适配器
            }
        }
        return out;
    }

    private NativeD3D() {
    }
}

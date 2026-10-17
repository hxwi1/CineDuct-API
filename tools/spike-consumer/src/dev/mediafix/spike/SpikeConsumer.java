package dev.mediafix.spike;

import dev.mediafix.gpu.NativeD3D;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.WGLCapabilities;
import org.lwjgl.opengl.WGLNVDXInterop;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR;
import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_CORE_PROFILE;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_PROFILE;
import static org.lwjgl.glfw.GLFW.GLFW_RESIZABLE;
import static org.lwjgl.glfw.GLFW.GLFW_VISIBLE;
import static org.lwjgl.glfw.GLFW.glfwCreateWindow;
import static org.lwjgl.glfw.GLFW.glfwDestroyWindow;
import static org.lwjgl.glfw.GLFW.glfwGetFramebufferSize;
import static org.lwjgl.glfw.GLFW.glfwInit;
import static org.lwjgl.glfw.GLFW.glfwMakeContextCurrent;
import static org.lwjgl.glfw.GLFW.glfwPollEvents;
import static org.lwjgl.glfw.GLFW.glfwSetWindowShouldClose;
import static org.lwjgl.glfw.GLFW.glfwSwapBuffers;
import static org.lwjgl.glfw.GLFW.glfwSwapInterval;
import static org.lwjgl.glfw.GLFW.glfwTerminate;
import static org.lwjgl.glfw.GLFW.glfwWindowHint;
import static org.lwjgl.glfw.GLFW.glfwWindowShouldClose;

/**
 * P1 打样的「消费者」：在**游戏进程的角色**（OpenGL 侧）验证
 * 「D3D11 共享纹理能不能被 GL 直接采到」。
 *
 * <p>它做的事就是将来 mod 里 {@code DxgiInteropTransport} 要做的事：
 * <ol>
 *   <li>建 GL 上下文，读 WGL 能力（{@code WGL_NV_DX_interop2}）；</li>
 *   <li><b>自动探测 GL 上下文所在适配器</b>：逐个适配器建设备并试
 *       {@code wglDXOpenDeviceNV}，成功的就是它（驱动说了算，比猜描述字符串可靠）；</li>
 *   <li>{@code OpenSharedResourceByName} 打开子进程的共享纹理 →
 *       {@code wglDXRegisterObjectNV} 注册成 GL 纹理（**不分配**存储）；</li>
 *   <li>每帧：只对状态为 READY 的槽位 {@code wglDXLockObjectsNV} → 采样 → unlock
 *       → 回写 SHOWN（计划文档 3.3 的非阻塞同步做法）；</li>
 *   <li><b>自动校验</b>：把视频纹理渲进一张 128×128 的 FBO，{@code glReadPixels} 回读
 *       角落像素，解出生产者写进去的帧号，与状态块里的帧号比对 —— 这样不需要人眼看
 *       就能判定「帧在更新 + 通道没错位 + 内容不是垃圾」。</li>
 * </ol>
 *
 * <p>用法：见 native/run-spike.ps1，或
 * {@code java -cp ... dev.mediafix.spike.SpikeConsumer --seconds 20}
 */
public final class SpikeConsumer {

    // ---- 与 native/spike/shared/cineduct_spike_status.h 的偏移保持一致 ----
    private static final int OFF_MAGIC = 0;
    private static final int OFF_VERSION = 4;
    private static final int OFF_WIDTH = 8;
    private static final int OFF_HEIGHT = 12;
    private static final int OFF_SLOTS = 16;
    private static final int OFF_FRAMES_PUBLISHED = 24;
    private static final int OFF_FRAMES_DROPPED = 32;
    private static final int OFF_PRODUCER_FPS_X100 = 40;
    private static final int OFF_LAST_ACQUIRE_US = 44;
    private static final int OFF_MAX_ACQUIRE_US = 48;
    private static final int OFF_ADAPTER_HIGH = 52;
    private static final int OFF_ADAPTER_LOW = 56;
    private static final int OFF_PRODUCER_PID = 60;
    private static final int OFF_SLOTS0 = 64;
    private static final int SLOT_STRIDE = 16;
    private static final int MAPPING_BYTES = 4096;

    private static final int MAGIC = 0x4D465850;      // "MFXP"
    private static final int SLOT_EMPTY = 0;
    private static final int SLOT_WRITING = 1;
    private static final int SLOT_READY = 2;
    private static final int SLOT_SHOWN = 3;

    /** 生产者写进 B 通道的固定值：通道一错位，这里就对不上。 */
    private static final int STAMP_BLUE = 0xA5;

    private static final int FBO_SIZE = 128;

    // ---- 状态块的只读（+ 槽位 SHOWN 回写）视图 ----
    private static final class StatusView {
        private final ByteBuffer b;

        StatusView(ByteBuffer raw) {
            this.b = raw.order(ByteOrder.LITTLE_ENDIAN);
        }

        int magic() { return b.getInt(OFF_MAGIC); }
        int version() { return b.getInt(OFF_VERSION); }
        int width() { return b.getInt(OFF_WIDTH); }
        int height() { return b.getInt(OFF_HEIGHT); }
        int slots() { return b.getInt(OFF_SLOTS); }
        long framesPublished() { return b.getLong(OFF_FRAMES_PUBLISHED); }
        long framesDropped() { return b.getLong(OFF_FRAMES_DROPPED); }
        int producerFpsX100() { return b.getInt(OFF_PRODUCER_FPS_X100); }
        int lastAcquireUs() { return b.getInt(OFF_LAST_ACQUIRE_US); }
        int maxAcquireUs() { return b.getInt(OFF_MAX_ACQUIRE_US); }
        int adapterHigh() { return b.getInt(OFF_ADAPTER_HIGH); }
        int adapterLow() { return b.getInt(OFF_ADAPTER_LOW); }
        int producerPid() { return b.getInt(OFF_PRODUCER_PID); }
        int slotState(int i) { return b.getInt(OFF_SLOTS0 + i * SLOT_STRIDE); }
        int slotGen(int i) { return b.getInt(OFF_SLOTS0 + i * SLOT_STRIDE + 4); }
        void setSlotState(int i, int v) { b.putInt(OFF_SLOTS0 + i * SLOT_STRIDE, v); }
    }

    private static String glVersionString() {
        String s = GL11.glGetString(GL11.GL_VERSION);
        return s == null ? "?" : s;
    }

    private static String glRendererString() {
        String s = GL11.glGetString(GL11.GL_RENDERER);
        return s == null ? "?" : s;
    }

    // ------------------------------------------------------------------
    // GL 小工具
    // ------------------------------------------------------------------

    private static int compileProgram() {
        String vs = """
                #version 330 core
                out vec2 vUv;
                void main() {
                    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                    // D3D 纹理第 0 行在内存顶部，GL 把它当 v=0（底部）——
                    // 所以互操作导入的画面天生是上下翻转的，采样时翻回来。
                    vUv = vec2(p.x, 1.0 - p.y);
                    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
                }
                """;
        String fs = """
                #version 330 core
                in vec2 vUv;
                out vec4 fragColor;
                uniform sampler2D uTex;
                void main() {
                    fragColor = vec4(texture(uTex, vUv).rgb, 1.0);
                }
                """;
        int v = compile(GL20.GL_VERTEX_SHADER, vs);
        int f = compile(GL20.GL_FRAGMENT_SHADER, fs);
        int p = GL20.glCreateProgram();
        GL20.glAttachShader(p, v);
        GL20.glAttachShader(p, f);
        GL20.glLinkProgram(p);
        if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            throw new IllegalStateException("着色器链接失败: " + GL20.glGetProgramInfoLog(p));
        }
        GL20.glDeleteShader(v);
        GL20.glDeleteShader(f);
        return p;
    }

    private static int compile(int type, String src) {
        int s = GL20.glCreateShader(type);
        GL20.glShaderSource(s, src);
        GL20.glCompileShader(s);
        if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            throw new IllegalStateException("着色器编译失败: " + GL20.glGetShaderInfoLog(s));
        }
        return s;
    }

    private static String luidString(long luid) {
        return String.format("%08x:%08x", (int) (luid >>> 32), (int) luid);
    }

    private static String luidString(int high, int low) {
        return String.format("%08x:%08x", high, low);
    }

    // ------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        int seconds = 20;
        boolean hidden = false;
        boolean vsync = false;
        boolean selftest = false;
        boolean verify = true;   // 真实视频没有 P1 的"帧号编码"，用 --no-verify 关掉色标校验
        String texName = "cineduct_spike_tex";
        String statusName = "cineduct_spike_status";
        long waitProducerMs = 8000;
        long selftestMisc = 0L;
        int access = WGLNVDXInterop.WGL_ACCESS_READ_ONLY_NV;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--seconds" -> seconds = Integer.parseInt(args[++i]);
                case "--hidden" -> hidden = true;
                case "--vsync" -> vsync = true;
                case "--tex" -> texName = args[++i];
                case "--status" -> statusName = args[++i];
                case "--wait-ms" -> waitProducerMs = Long.parseLong(args[++i]);
                case "--selftest" -> selftest = true;
                case "--no-verify" -> verify = false;
                case "--misc" -> selftestMisc = Long.decode(args[++i]);
                case "--access" -> access = switch (args[++i].toLowerCase()) {
                    case "rw", "write" -> WGLNVDXInterop.WGL_ACCESS_READ_WRITE_NV;
                    case "discard" -> WGLNVDXInterop.WGL_ACCESS_WRITE_DISCARD_NV;
                    default -> WGLNVDXInterop.WGL_ACCESS_READ_ONLY_NV;
                };
                default -> System.out.println("[consumer] 忽略未知参数: " + args[i]);
            }
        }

        System.out.println("=== CineDuct P1 打样：跨进程共享显存 → OpenGL 零拷贝 ===");

        if (!NativeD3D.available()) {
            fail("垫片 DLL 没加载上（java.library.path 里要有 cineduct-glshim.dll，"
                    + "或用 -D" + NativeD3D.PROPERTY_PATH + "=<绝对路径> 指定）");
            return;
        }

        // ---------- 1) GL 上下文 ----------
        if (!glfwInit()) {
            fail("glfwInit 失败");
            return;
        }
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_VISIBLE, hidden ? GLFW.GLFW_FALSE : GLFW.GLFW_TRUE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW.GLFW_FALSE);
        long window = glfwCreateWindow(1280, 720, "CineDuct P1 spike (consumer)", 0L, 0L);
        if (window == 0L) {
            fail("glfwCreateWindow 失败");
            glfwTerminate();
            return;
        }
        glfwMakeContextCurrent(window);
        glfwSwapInterval(vsync ? 1 : 0);
        GL.createCapabilities();
        System.out.println("[consumer] GL: " + glVersionString() + " | renderer=" + glRendererString());

        WGLCapabilities wgl = GL.getCapabilitiesWGL();
        if (wgl == null || wgl.wglDXOpenDeviceNV == 0L) {
            System.out.println("[consumer] WGL 能力未就绪，尝试 GL.createCapabilitiesWGL()");
            GL.createCapabilitiesWGL();
            wgl = GL.getCapabilitiesWGL();
        }
        boolean wglOk = wgl != null && wgl.wglDXOpenDeviceNV != 0L;
        System.out.println("[consumer] WGL_NV_DX_interop=" + (wgl != null && wgl.WGL_NV_DX_interop)
                + " WGL_NV_DX_interop2=" + (wgl != null && wgl.WGL_NV_DX_interop2)
                + " 函数指针=" + (wglOk ? "已装载" : "缺失"));
        if (!wglOk) {
            fail("WGL_NV_DX_interop 函数指针不可用 —— 这条路在驱动层面就不成立");
            glfwTerminate();
            return;
        }

        // ---------- 2) 探测 GL 上下文所在适配器 ----------
        // 互操作硬性要求 D3D11 设备与 GL 上下文在同一个适配器上。与其猜显卡名字，
        // 不如逐个建设备试 wglDXOpenDeviceNV：返回非 0 的就是 GL 所在的那块卡。
        List<NativeD3D.Adapter> adapters = NativeD3D.enumerateAdapters();
        System.out.println("[consumer] DXGI 适配器 " + adapters.size() + " 个:");
        for (NativeD3D.Adapter a : adapters) {
            System.out.println("    " + a);
        }

        long d3dDevice = 0L;
        long interopDevice = 0L;
        NativeD3D.Adapter chosen = null;
        for (NativeD3D.Adapter a : adapters) {
            if (a.isSoftwareOrRemote()) {
                System.out.println("[consumer] 跳过软件/远程适配器: " + a.description());
                continue;
            }
            long dev = NativeD3D.createDevice(a.luid(), NativeD3D.FLAG_BGRA_SUPPORT);
            if (dev == 0L) {
                System.out.println("[consumer] " + a.luidText() + " 建设备失败: " + NativeD3D.lastError());
                continue;
            }
            long ix = WGLNVDXInterop.wglDXOpenDeviceNV(dev);
            if (ix != 0L) {
                d3dDevice = dev;
                interopDevice = ix;
                chosen = a;
                break;
            }
            System.out.println("[consumer] " + a.luidText() + " 不是 GL 上下文所在适配器（wglDXOpenDeviceNV=0）");
            NativeD3D.release(dev);
        }
        if (interopDevice == 0L) {
            fail("所有适配器都注册不上 —— 无法建立 D3D11 ↔ OpenGL 互操作");
            glfwTerminate();
            return;
        }
        System.out.println("[consumer] ✔ 互操作设备已建立: 适配器=" + chosen.description()
                + " luid=" + chosen.luidText() + "（LUID 与下面的生产者必须一致）");

        // 自检：在本进程自建纹理走一遍注册。用于区分「我们的调用姿势不对」与「跨进程共享受限」。
        if (selftest) {
            int rc = runSelfTest(d3dDevice, interopDevice, selftestMisc, access);
            WGLNVDXInterop.wglDXCloseDeviceNV(interopDevice);
            NativeD3D.release(d3dDevice);
            glfwDestroyWindow(window);
            glfwTerminate();
            System.exit(rc);
        }

        // ---------- 3) 等生产者，打开共享内存状态块 ----------
        StatusView status = null;
        long deadline = System.currentTimeMillis() + waitProducerMs;
        while (System.currentTimeMillis() < deadline) {
            ByteBuffer raw = NativeD3D.openSharedSection(statusName, MAPPING_BYTES);
            if (raw != null) {
                StatusView sv = new StatusView(raw);
                if (sv.magic() == MAGIC) {
                    status = sv;
                    break;
                }
                NativeD3D.closeSharedSection(raw);
            }
            Thread.sleep(100);
        }
        if (status == null) {
            fail("等不到生产者（状态块 " + statusName + " 没出现或 magic 不匹配）");
            glfwTerminate();
            return;
        }
        long producerLuid = ((long) status.adapterHigh() << 32) | (status.adapterLow() & 0xFFFFFFFFL);
        int width = status.width();
        int height = status.height();
        int slots = status.slots();
        System.out.printf("[consumer] 生产者: pid=%d %dx%d slots=%d luid=%s fps=%d.%02d%n",
                status.producerPid(), width, height, slots, luidString(status.adapterHigh(), status.adapterLow()),
                status.producerFpsX100() / 100, status.producerFpsX100() % 100);
        boolean sameAdapter = producerLuid == chosen.luid();
        System.out.println("[consumer] 适配器一致性: " + (sameAdapter ? "✔ 同一个 LUID"
                : "�’ 不一致（生产者=" + luidString(status.adapterHigh(), status.adapterLow())
                  + " 消费者=" + chosen.luidText() + "）"));

        // ---------- 4) 打开共享纹理 + 建本地纹理，并把【本地】纹理注册给 GL ----------
        //
        // ★ 这里是被实测逼出来的关键设计：NV_DX_interop 拒绝注册带
        //   D3D11_RESOURCE_MISC_SHARED_NTHANDLE 的纹理（misc=0x802 → wglDXRegisterObjectNV 返回 0；
        //   0x0/0x1/0x2/0x3 都能注册），而跨进程共享又只能靠 NTHANDLE。
        //   所以分工是：共享纹理只管"跨进程搬运"，游戏进程逐帧 GPU→GPU 拷进一张本地纹理（misc=0），
        //   GL 采样的是那张本地纹理。显存内拷贝代价极低，跨 API 可见性由 wglDXLockObjectsNV 保证。
        long[] glTextures = new long[slots];
        long[] interopObjects = new long[slots];
        long[] sharedTextures = new long[slots];
        long[] localTextures = new long[slots];
        final int localBind = NativeD3D.BIND_RENDER_TARGET | NativeD3D.BIND_SHADER_RESOURCE;
        for (int i = 0; i < slots; i++) {
            String name = texName + "_" + i;
            long dxTex = NativeD3D.openSharedTextureByName(d3dDevice, name);
            if (dxTex == 0L) {
                fail("打开共享纹理 " + name + " 失败: " + NativeD3D.lastError());
                glfwTerminate();
                return;
            }
            int[] desc = NativeD3D.textureDesc(dxTex);
            long localTex = NativeD3D.createTexture(d3dDevice, width, height, 0, localBind);
            if (localTex == 0L) {
                fail("建本地纹理失败: " + NativeD3D.lastError());
                glfwTerminate();
                return;
            }
            // GL 纹理**不能自己分配存储**（别调 glTexImage2D），存储由注册的 D3D 纹理提供
            int glTex = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, glTex);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            long obj = WGLNVDXInterop.wglDXRegisterObjectNV(interopDevice, localTex, glTex,
                    GL11.GL_TEXTURE_2D, access);
            if (obj == 0L) {
                System.out.printf("[consumer] glGetError=0x%x access=0x%x%n",
                        GL11.glGetError(), access);
                fail("wglDXRegisterObjectNV 失败（本地纹理槽位 " + i + "）");
                glfwTerminate();
                return;
            }
            sharedTextures[i] = dxTex;
            localTextures[i] = localTex;
            glTextures[i] = glTex;
            interopObjects[i] = obj;
            System.out.printf("[consumer] ✔ 槽位 %d: 共享纹理 %s (%dx%d) → 本地纹理 → GL 纹理 tex=%d%n",
                    i, name, desc != null ? desc[0] : -1, desc != null ? desc[1] : -1, glTex);
        }

        // ---------- 5) 渲染资源 ----------
        int program = compileProgram();
        int uTex = GL20.glGetUniformLocation(program, "uTex");
        int vao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(vao);

        int fboTex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, fboTex);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, FBO_SIZE, FBO_SIZE, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        int fbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, fboTex, 0);
        if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
            fail("FBO 不完整（回读校验没法做）");
            glfwTerminate();
            return;
        }
        ByteBuffer pixel = MemoryUtil.memAlloc(4);
        // 渲染循环里复用数组、不逐帧分配（好习惯；注意"卡顿"的真正元凶实测是锁协议，
        // 不是这里的分配 —— 见 native/README.md 的键控互斥结论）
        int[] fbW = new int[1];
        int[] fbH = new int[1];

        PointerBuffer one = MemoryUtil.memAllocPointer(1);

        // ---------- 6) 主循环 ----------
        long startNanos = System.nanoTime();
        long endNanos = startNanos + seconds * 1_000_000_000L;

        long framesConsumed = 0;
        long framesRendered = 0;
        long framesNoNew = 0;
        long lockFailures = 0;
        long copyErrors = 0;
        long channelBad = 0;
        long staleBad = 0;
        long lockWaitSumNs = 0;
        long lockWaitMaxNs = 0;
        long lastObservedGen = -1;
        long maxObservedGen = -1;
        long lastGenAdvanceAt = startNanos;
        long longestFreezeMs = 0;
        List<Integer> genTransitions = new ArrayList<>();
        int lastSlot = -1;
        long loops = 0;

        while (System.nanoTime() < endNanos && !glfwWindowShouldClose(window)) {
            loops++;
            glfwPollEvents();

            // 只挑 READY 且帧号最新的槽位：这样加锁基本不会等（计划文档 3.3）
            int bestSlot = -1;
            int bestGen = -1;
            for (int i = 0; i < slots; i++) {
                if (status.slotState(i) == SLOT_READY) {
                    int g = status.slotGen(i);
                    if (g > bestGen) {
                        bestGen = g;
                        bestSlot = i;
                    }
                }
            }

            // 有新帧就先 GPU→GPU 拷进本地纹理（内部自带键控互斥，与生产者写帧互斥）。
            // 拷完立刻把共享槽位标成 SHOWN：我们对它的使用到此结束（后面采样的是本地私有纹理）。
            if (bestSlot >= 0) {
                int copyResult = NativeD3D.copyFromShared(d3dDevice, localTextures[bestSlot],
                        sharedTextures[bestSlot], NativeD3D.KEY_READER, NativeD3D.KEY_WRITER, 4);
                if (copyResult == 0) {
                    status.setSlotState(bestSlot, SLOT_SHOWN);
                    lastSlot = bestSlot;
                    framesConsumed++;
                } else {
                    framesNoNew++;
                    if (copyResult < 0) {
                        copyErrors++;
                        if (copyErrors <= 3) {
                            System.out.println("[consumer] ✘ 共享纹理拷贝失败: " + NativeD3D.lastError());
                        }
                    }
                }
            } else {
                framesNoNew++;
            }

            /*
             * ★ 采样互操作纹理**必须**在加锁状态下进行。
             *
             * 实测：不加锁直接画，读到的是垃圾（回读像素 B 通道 100% 不对，共 18060 次）。
             * 所以每一轮渲染都要 wglDXLockObjectsNV → 画 → wglDXUnlockObjectsNV；
             * 没有新帧时复用上一张**本地**纹理是安全的（那是我们自己的私有纹理，
             * 只有我们自己在拷贝时才会变），但锁还是得加。
             */
            if (lastSlot >= 0) {
                int slot = lastSlot;
                int glTex = (int) glTextures[slot];
                one.put(0, interopObjects[slot]);
                long t0 = System.nanoTime();
                boolean locked = WGLNVDXInterop.wglDXLockObjectsNV(interopDevice, one);
                long waited = System.nanoTime() - t0;
                lockWaitSumNs += waited;
                if (waited > lockWaitMaxNs) lockWaitMaxNs = waited;
                if (!locked) {
                    lockFailures++;
                    continue;
                }
                framesRendered++;

                // (a) 画到窗口：肉眼看有没有卡顿/撕裂
                glfwGetFramebufferSize(window, fbW, fbH);
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
                GL11.glViewport(0, 0, fbW[0], fbH[0]);
                GL11.glClearColor(0.05f, 0.05f, 0.08f, 1.0f);
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
                GL20.glUseProgram(program);
                GL13State.bind(glTex, uTex);
                GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
                glfwSwapBuffers(window);

                // (b) 画进 128x128 的 FBO 并回读角落像素 → 自动校验
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
                GL11.glViewport(0, 0, FBO_SIZE, FBO_SIZE);
                GL20.glUseProgram(program);
                GL13State.bind(glTex, uTex);
                GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
                pixel.clear();
                GL11.glReadPixels(6, 6, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel);
                int r = pixel.get(0) & 0xFF;
                int g = pixel.get(1) & 0xFF;
                int b = pixel.get(2) & 0xFF;

                long observed = (r | (g << 8)) & 0xFFFFL;
                if (verify && Math.abs(b - STAMP_BLUE) > 1) {
                    channelBad++;
                    if (channelBad <= 3) {
                        System.out.printf("[consumer] �’ 通道异常: 读到 RGB=(%d,%d,%d)，B 应为 %d%n",
                                r, g, b, STAMP_BLUE);
                    }
                }
                long producerGen = newestProducerGen(status, slots);
                if (verify && observed > producerGen) {
                    staleBad++;
                    if (staleBad <= 3) {
                        System.out.printf("[consumer] �’ 帧号超前: 读到 %d > 生产者 %d%n", observed, producerGen);
                    }
                }
                if (observed != lastObservedGen) {
                    if (lastObservedGen >= 0 && observed < lastObservedGen) {
                        // 槽位轮转导致回退是正常的（不同槽的帧号可能略有前后）
                    }
                    genTransitions.add((int) observed);
                    lastObservedGen = observed;
                    long now = System.nanoTime();
                    if (observed > maxObservedGen) maxObservedGen = observed;
                    lastGenAdvanceAt = now;
                } else {
                    longestFreezeMs = Math.max(longestFreezeMs,
                            (System.nanoTime() - lastGenAdvanceAt) / 1_000_000L);
                }

                WGLNVDXInterop.wglDXUnlockObjectsNV(interopDevice, one);
            }

            if (loops % 120 == 0) {
                System.out.printf("[consumer] 拷贝=%d 渲染=%d 无新帧=%d 生产者已发=%d 丢=%d 生产者fps=%.2f "
                                + "回读帧号=%d 锁等待avg=%.2fms max=%.2fms%n",
                        framesConsumed, framesRendered, framesNoNew, status.framesPublished(),
                        status.framesDropped(), status.producerFpsX100() / 100.0, maxObservedGen,
                        framesRendered == 0 ? 0.0 : lockWaitSumNs / 1e6 / framesRendered,
                        lockWaitMaxNs / 1e6);
            }
        }

        // ---------- 7) 结论 ----------
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        long producerGen = newestProducerGen(status, slots);
        System.out.println();
        System.out.println("=== 打样结果 ===");
        System.out.printf("  时长            : %.1f s%n", elapsedMs / 1000.0);
        System.out.printf("  GL 渲染器        : %s%n", glRendererString());
        System.out.printf("  互操作适配器     : %s luid=%s%n", chosen.description(), chosen.luidText());
        System.out.printf("  生产者适配器     : luid=%s%s%n", luidString(status.adapterHigh(), status.adapterLow()),
                sameAdapter ? "（一致）" : "（不一致！）");
        System.out.printf("  注册槽位         : %d 张 %dx%d 共享纹理%n", slots, width, height);
        System.out.printf("  拷贝帧数         : %d（渲染 %d 帧，无新帧轮次 %d）%n",
                framesConsumed, framesRendered, framesNoNew);
        System.out.printf("  生产者          : 发布 %d，丢 %d，fps=%.2f%n", status.framesPublished(),
                status.framesDropped(), status.producerFpsX100() / 100.0);
        System.out.printf("  帧号变化次数     : %d（最新回读=%d，生产者最新=%d）%n",
                genTransitions.size(), maxObservedGen, producerGen);
        System.out.printf("  加锁等待         : avg=%.3f ms, max=%.3f ms, 失败=%d%n",
                framesRendered == 0 ? 0.0 : lockWaitSumNs / 1e6 / framesRendered,
                lockWaitMaxNs / 1e6, lockFailures);
        System.out.printf("  通道校验         : B 通道异常 %d 次%n", channelBad);
        System.out.printf("  帧号超前异常     : %d 次%n", staleBad);
        System.out.printf("  共享→本地拷贝失败: %d 次%n", copyErrors);
        System.out.printf("  最长无新帧       : %d ms%n", longestFreezeMs);

        boolean pass = framesConsumed >= 30
                && framesRendered >= 30
                && genTransitions.size() >= 10
                && (!verify || (channelBad == 0 && staleBad == 0))
                && copyErrors == 0
                && lockFailures == 0
                && sameAdapter;
        System.out.println(pass
                ? "  结论            : ✔ PASS —— D3D11 共享纹理可以被 OpenGL 直接采样（零拷贝成立）"
                : "  结论            : �’ FAIL —— 见上面的异常计数");
        System.out.println("=== 打样结束 ===");

        // ---------- 8) 清理 ----------
        for (int i = 0; i < slots; i++) {
            if (interopObjects[i] != 0L) WGLNVDXInterop.wglDXUnregisterObjectNV(interopDevice, interopObjects[i]);
        }
        WGLNVDXInterop.wglDXCloseDeviceNV(interopDevice);
        NativeD3D.release(d3dDevice);
        MemoryUtil.memFree(one);
        MemoryUtil.memFree(pixel);
        glfwDestroyWindow(window);
        glfwTerminate();
        System.exit(pass ? 0 : 1);
    }

    /**
     * 同进程自检：在本进程建一张 D3D11 纹理并注册成 GL 纹理。
     *
     * <p>这能把两类失败分开：
     * <ul>
     *   <li>自检也失败 → 我们的调用姿势/环境不对（纹理标志、访问权限、驱动限制）；</li>
     *   <li>自检成功但跨进程失败 → 问题在"共享"这一步（NTHANDLE / 键控互斥 / 跨进程注册）。</li>
     * </ul>
     */
    private static int runSelfTest(long d3dDevice, long interopDevice, long misc, int access) {
        final int bind = NativeD3D.BIND_RENDER_TARGET | NativeD3D.BIND_SHADER_RESOURCE;
        System.out.printf("[selftest] 同进程自建 256x256 BGRA8: misc=0x%x bind=0x%x access=0x%x%n",
                misc, bind, access);
        long dxTex = NativeD3D.createTexture(d3dDevice, 256, 256, (int) misc, bind);
        if (dxTex == 0L) {
            System.out.println("[selftest] CreateTexture2D 失败: " + NativeD3D.lastError());
            return 1;
        }
        int glTex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, glTex);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        while (GL11.glGetError() != GL11.GL_NO_ERROR) {
            // 清掉历史 GL 错误，保证下面读到的是本次调用的
        }
        long obj = WGLNVDXInterop.wglDXRegisterObjectNV(interopDevice, dxTex, glTex,
                GL11.GL_TEXTURE_2D, access);
        int glErr = GL11.glGetError();
        System.out.printf("[selftest] wglDXRegisterObjectNV -> %s (glGetError=0x%x)%n",
                obj != 0L ? "✔ OK" : "✘ 返回 0", glErr);
        if (obj == 0L) {
            NativeD3D.release(dxTex);
            return 1;
        }
        PointerBuffer one = MemoryUtil.memAllocPointer(1);
        one.put(0, obj);
        boolean locked = WGLNVDXInterop.wglDXLockObjectsNV(interopDevice, one);
        System.out.println("[selftest] wglDXLockObjectsNV -> " + (locked ? "✔ OK" : "✘ false"));
        if (locked) WGLNVDXInterop.wglDXUnlockObjectsNV(interopDevice, one);
        WGLNVDXInterop.wglDXUnregisterObjectNV(interopDevice, obj);
        GL11.glDeleteTextures(glTex);
        NativeD3D.release(dxTex);
        MemoryUtil.memFree(one);
        boolean ok = locked;
        System.out.println("[selftest] 结论: " + (ok
                ? "同进程互操作可用 → 失败只可能出在跨进程共享这一环"
                : "同进程都注册不上 → 环境/驱动层面问题"));
        return ok ? 0 : 1;
    }

    /** 生产者最新发布的帧号（取所有槽位的最大值）。 */
    private static long newestProducerGen(StatusView status, int slots) {
        long best = -1;
        for (int i = 0; i < slots; i++) {
            long g = status.slotGen(i);
            if (g > best) best = g;
        }
        return best;
    }

    private static void fail(String message) {
        System.out.println("[consumer] ✘ " + message);
        System.out.println("[consumer] 垫片最后错误: " + safeLastError());
        System.exit(2);
    }

    private static String safeLastError() {
        try {
            if (!NativeD3D.available()) {
                return "垫片未加载: " + NativeD3D.loadError();
            }
            return String.valueOf(NativeD3D.lastError());
        } catch (Throwable t) {
            return "(取不到: " + t + ")";
        }
    }

    /** 采样相关的 GL 状态包装（避免把 GL13 静态导入弄得到处都是）。 */
    private static final class GL13State {
        static void bind(int texture, int uniformLocation) {
            org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL20.glUniform1i(uniformLocation, 0);
        }
    }

    private SpikeConsumer() {
    }
}

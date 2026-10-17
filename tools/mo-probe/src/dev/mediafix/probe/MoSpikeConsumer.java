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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

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
 * <b>跨进程</b>打样消费者：走「NT 共享句柄 → GL 内存对象」，**完全不用 WGL_NV_DX_interop**。
 *
 * <p>与 {@code SpikeConsumer}（NV_DX_interop 路线）并列，用来做 A/B 对照。两者的生产者是同一个
 * （{@code cineduct-spike-host.exe --mo}）。
 *
 * <p>数据流（★ 标注的是与老路线不同的地方）：
 * <pre>
 * 生产者（子进程 / D3D11）                          消费者（游戏进程 / OpenGL）
 *  N 张共享纹理：BGRA8 NTHANDLE|KEYEDMUTEX          ① 写自己的 pid + 请求句柄直投
 *  渲染动画 → CopyResource 进共享纹理              ② 等 dupReady 后拿到**本进程有效**的句柄值
 *  ★ Signal(共享 fence, 2k+1)                       ★ glImportMemoryWin32HandleEXT(D3D11_IMAGE_EXT)
 *  写槽位状态 = READY（附上 2k+1）                      + glTexStorageMem2DEXT → 直接别名那块显存
 *                                                   ★ glImportSemaphoreWin32HandleEXT(D3D12_FENCE_EXT)
 *                                                   ★ glWaitSemaphoreEXT(2k+1) → 采样
 *  ★ 复用槽位前只做有界的 GetCompletedValue 探测      ★ glSignalSemaphoreEXT(2k+2) → 标 SHOWN
 * </pre>
 *
 * <p>为什么这条路线更可能在 AMD 上成立：整条链上**没有任何厂商特有的 WGL 互操作扩展**
 * （不需要 {@code wglDXOpenDeviceNV} / {@code wglDXRegisterObjectNV} / {@code wglDXLockObjectsNV}），
 * 只有 Khronos 的 {@code GL_EXT_memory_object_win32} + {@code GL_EXT_semaphore_win32}，
 * 而这两个扩展 AMD 从 Adrenalin 18.8.1 起就在 GL 扩展表里公开支持。
 *
 * <p>用法（一般由 {@code native\run-mo-spike.ps1} 拉起）：
 * <pre>
 *   java ... dev.mediafix.probe.MoSpikeConsumer --seconds 20 [--status cineduct_spike_status]
 * </pre>
 */
public final class MoSpikeConsumer {

    // ---- 与 cineduct_spike_status.h 对齐的偏移（v7） ----
    private static final int OFF_MAGIC = 0;
    private static final int OFF_VERSION = 4;
    private static final int OFF_WIDTH = 8;
    private static final int OFF_HEIGHT = 12;
    private static final int OFF_SLOTS = 16;
    private static final int OFF_FRAMES_PUBLISHED = 24;
    private static final int OFF_PRODUCER_FPS_X100 = 40;
    private static final int OFF_ADAPTER_HIGH = 52;
    private static final int OFF_ADAPTER_LOW = 56;
    private static final int OFF_PRODUCER_PID = 60;
    private static final int OFF_SLOTS0 = 64;
    private static final int SLOT_STRIDE = 16;
    private static final int OFF_CONSUMER_PID = 408;
    private static final int OFF_CONSUMER_WANT_DUP = 412;
    private static final int OFF_DUP_READY = 416;
    private static final int OFF_MO_SLOTS_READY = 420;
    private static final int OFF_FENCE_HANDLE_DUP = 424;
    private static final int OFF_TEX_HANDLE_DUP = 432;
    private static final int OFF_SLOT_FENCE_VALUE = 496;
    private static final int OFF_SLOT_CONSUMER_DONE = 560;
    private static final int MAPPING_BYTES = 4096;

    private static final int MAGIC = 0x4D465850;      // "MFXP"
    private static final int SLOT_EMPTY = 0;
    private static final int SLOT_WRITING = 1;
    private static final int SLOT_READY = 2;
    private static final int SLOT_SHOWN = 3;

    private static final int STAMP_BLUE = 0xA5;
    private static final int FBO_SIZE = 64;

    private static final int GL_TEXTURE_SWIZZLE_RGBA = 0x8E46;
    private static final int GL_RGBA8 = 0x8058;
    private static final int GL_FRAMEBUFFER = 0x8D40;
    private static final int GL_COLOR_ATTACHMENT0 = 0x8CE0;
    private static final int GL_FRAMEBUFFER_COMPLETE = 0x8CD5;

    private static ByteBuffer status;
    private static int slotCount;

    public static void main(String[] args) throws Exception {
        int seconds = 20;
        boolean hidden = true;
        boolean vsync = false;
        boolean noFence = false;     // --no-fence：完全跳过信号量等待（诊断"是不是 fence 语义卡住了"）
        boolean trace = false;       // --trace：前几帧逐步打印 + 每步 glFinish
        String statusName = "cineduct_spike_status";
        long waitProducerMs = 8000;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--seconds" -> seconds = Integer.parseInt(args[++i]);
                case "--status" -> statusName = args[++i];
                case "--wait-ms" -> waitProducerMs = Long.parseLong(args[++i]);
                case "--visible" -> hidden = false;
                case "--hidden" -> hidden = true;
                case "--vsync" -> vsync = true;
                case "--no-fence" -> noFence = true;
                case "--trace" -> trace = true;
                default -> System.out.println("[mo] 忽略未知参数: " + args[i]);
            }
        }

        System.out.println("=== CineDuct 跨进程打样：NT 共享句柄 → OpenGL 内存对象（不走 NV_DX_interop）===");

        if (!NativeD3D.available()) {
            fail("垫片 DLL 没加载上（" + NativeD3D.loadError() + "）");
            System.exit(2);
        }
        if (!glfwInit()) {
            fail("glfwInit 失败");
            System.exit(2);
        }
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_VISIBLE, hidden ? GLFW_FALSE : GLFW_TRUE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE);
        final long window = glfwCreateWindow(1280, 720, "CineDuct MO spike (consumer)", 0L, 0L);
        if (window == 0L) {
            fail("glfwCreateWindow 失败");
            glfwTerminate();
            System.exit(2);
        }
        glfwMakeContextCurrent(window);
        glfwSwapInterval(vsync ? 1 : 0);
        GL.createCapabilities();
        System.out.println("[mo] GL: " + GL11.glGetString(GL11.GL_VERSION)
                + " | renderer=" + GL11.glGetString(GL11.GL_RENDERER));

        final var caps = GL.getCapabilities();
        if (!caps.GL_EXT_memory_object || !caps.GL_EXT_memory_object_win32) {
            fail("驱动没有 GL_EXT_memory_object_win32 —— 这条路在本机不成立"
                    + "（memory_object=" + caps.GL_EXT_memory_object
                    + " win32=" + caps.GL_EXT_memory_object_win32 + "）");
            glfwTerminate();
            System.exit(3);
        }
        if (!caps.GL_EXT_semaphore || !caps.GL_EXT_semaphore_win32) {
            fail("驱动没有 GL_EXT_semaphore_win32 —— 做不了 GPU 间同步（会撕裂）");
            glfwTerminate();
            System.exit(3);
        }

        int exit = 1;
        try {
            exit = run(window, seconds, statusName, waitProducerMs, noFence, trace);
        } finally {
            glfwDestroyWindow(window);
            glfwTerminate();
        }
        System.exit(exit);
    }

    private static int run(long window, int seconds, String statusName, long waitProducerMs,
                           boolean noFence, boolean trace) throws Exception {
        // ---------- 1) 等生产者，打开共享状态块 ----------
        ByteBuffer raw = null;
        final long deadline = System.currentTimeMillis() + waitProducerMs;
        while (System.currentTimeMillis() < deadline) {
            raw = NativeD3D.openSharedSection(statusName, MAPPING_BYTES);
            if (raw != null) {
                raw.order(ByteOrder.LITTLE_ENDIAN);
                if (raw.getInt(OFF_MAGIC) == MAGIC) break;
                NativeD3D.closeSharedSection(raw);
                raw = null;
            }
            Thread.sleep(100);
        }
        if (raw == null) {
            fail("等不到生产者（状态块 " + statusName + " 没出现或 magic 不匹配）");
            return 2;
        }
        status = raw;
        slotCount = status.getInt(OFF_SLOTS);
        final int width = status.getInt(OFF_WIDTH);
        final int height = status.getInt(OFF_HEIGHT);
        final long producerLuid = ((long) status.getInt(OFF_ADAPTER_HIGH) << 32)
                | (status.getInt(OFF_ADAPTER_LOW) & 0xFFFFFFFFL);
        System.out.printf("[mo] 生产者: pid=%d %dx%d slots=%d luid=%08x:%08x version=%d%n",
                status.getInt(OFF_PRODUCER_PID), width, height, slotCount,
                status.getInt(OFF_ADAPTER_HIGH), status.getInt(OFF_ADAPTER_LOW),
                status.getInt(OFF_VERSION));

        // ---------- 2) GL 上下文所在适配器（用 GL_DEVICE_LUID_EXT，正规来源） ----------
        final ByteBuffer luidBuf = BufferUtils.createByteBuffer(8);
        EXTMemoryObject.glGetUnsignedBytevEXT(EXTMemoryObjectWin32.GL_DEVICE_LUID_EXT, luidBuf);
        final long glLuid = (Integer.toUnsignedLong(luidBuf.getInt(4)) << 32)
                | Integer.toUnsignedLong(luidBuf.getInt(0));
        System.out.printf("[mo] GL 适配器 LUID = %08x:%08x；生产者 LUID = %08x:%08x → %s%n",
                (int) (glLuid >>> 32), (int) glLuid,
                status.getInt(OFF_ADAPTER_HIGH), status.getInt(OFF_ADAPTER_LOW),
                glLuid == producerLuid ? "✔ 同一块卡" : "⚠ 不一致（跨适配器共享不可能成立）");
        if (glLuid != producerLuid) {
            fail("生产者与 GL 不在同一适配器上 —— 让它按 --adapter-luid "
                    + String.format("%08x:%08x", (int) (glLuid >>> 32), (int) glLuid) + " 重跑");
            NativeD3D.closeSharedSection(status);
            return 3;
        }

        // ---------- 3) 请求 NT 句柄直投（DuplicateHandle 到本进程） ----------
        final long myPid = ProcessHandle.current().pid();
        System.out.printf("[mo] 报到: pid=%d，请求把 NT 句柄复制进本进程...%n", myPid);
        status.putInt(OFF_CONSUMER_PID, (int) myPid);
        // wantDup 最后写：生产者看到它就说明 pid 已经到位
        status.putInt(OFF_CONSUMER_WANT_DUP, 1);

        final long dupDeadline = System.currentTimeMillis() + 5000;
        while (status.getInt(OFF_DUP_READY) == 0) {
            if (System.currentTimeMillis() > dupDeadline) {
                fail("等不到句柄直投（dupReady）—— 生产者没跑 --mo，或者 OpenProcess(PROCESS_DUP_HANDLE) 失败");
                NativeD3D.closeSharedSection(status);
                return 3;
            }
            Thread.sleep(20);
        }
        final long fenceHandle = status.getLong(OFF_FENCE_HANDLE_DUP);
        final long[] texHandles = new long[slotCount];
        for (int i = 0; i < slotCount; i++) {
            texHandles[i] = status.getLong(OFF_TEX_HANDLE_DUP + i * 8);
        }
        System.out.printf("[mo] ✔ 句柄已直投: fence=0x%x，纹理=%s%n", fenceHandle,
                describe(texHandles));

        // ---------- 4) 每槽建 GL 纹理（别名共享显存）+ GL 信号量（别名共享 fence） ----------
        final int[] textures = new int[slotCount];
        final int[] semaphores = new int[slotCount];
        final int[] memoryObjects = new int[slotCount];
        for (int i = 0; i < slotCount; i++) {
            memoryObjects[i] = EXTMemoryObject.glCreateMemoryObjectsEXT();
            EXTMemoryObjectWin32.glImportMemoryWin32HandleEXT(memoryObjects[i], 0,
                    EXTMemoryObjectWin32.GL_HANDLE_TYPE_D3D11_IMAGE_EXT, texHandles[i]);
            int err = GL11.glGetError();
            if (err != 0) {
                fail(String.format("槽 %d glImportMemoryWin32HandleEXT 失败 0x%X", i, err));
                return 3;
            }
            textures[i] = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textures[i]);
            EXTMemoryObject.glTexStorageMem2DEXT(GL11.GL_TEXTURE_2D, 1, GL_RGBA8, width, height,
                    memoryObjects[i], 0);
            err = GL11.glGetError();
            if (err != 0) {
                fail(String.format("槽 %d glTexStorageMem2DEXT 失败 0x%X", i, err));
                return 3;
            }
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            // ★ 格式严格对应：D3D 侧是 DXGI_FORMAT_B8G8R8A8_UNORM（内存字节序 B,G,R,A），
            //   GL 侧用 GL_RGBA8 承载同一块显存，所以采样时把 R/B 对调才对得上。
            GL11.glTexParameteriv(GL11.GL_TEXTURE_2D, GL_TEXTURE_SWIZZLE_RGBA,
                    new int[]{GL11.GL_BLUE, GL11.GL_GREEN, GL11.GL_RED, GL11.GL_ALPHA});

            semaphores[i] = EXTSemaphore.glGenSemaphoresEXT();
            EXTSemaphoreWin32.glImportSemaphoreWin32HandleEXT(semaphores[i],
                    EXTSemaphoreWin32.GL_HANDLE_TYPE_D3D12_FENCE_EXT, fenceHandle);
            err = GL11.glGetError();
            if (err != 0) {
                fail(String.format("槽 %d glImportSemaphoreWin32HandleEXT 失败 0x%X", i, err));
                return 3;
            }
        }
        status.putInt(OFF_MO_SLOTS_READY, slotCount);
        System.out.printf("[mo] ✔ %d 个槽已就绪：GL 纹理直接别名共享显存（本进程不需要任何 D3D11 设备）%n",
                slotCount);

        // ---------- 5) 渲染 + 校验循环 ----------
        final int program = buildProgram();
        final int vao = GL30.glGenVertexArrays();
        // ★ core profile 下没有绑定 VAO 时 glDrawArrays 是无效操作（只报 GL_INVALID_OPERATION，
        //   画面一个像素都不写）—— 上次调试就是把这一句漏了，于是"回读全 0"被误读成
        //   "共享显存读不到"，白查了一轮。夹具自检就是为了让这种错误立刻现形。
        GL30.glBindVertexArray(vao);
        final int fbo = GL30.glGenFramebuffers();
        final int colorTex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, colorTex);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL_RGBA8, FBO_SIZE, FBO_SIZE, 0, GL11.GL_RGBA,
                GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL30.glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        GL30.glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D,
                colorTex, 0);
        if (GL30.glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            fail("FBO 不完整");
            return 3;
        }
        GL11.glViewport(0, 0, FBO_SIZE, FBO_SIZE);

        // ---- 夹具自检：用一张普通（CPU 上传）纹理走同一条"画进 FBO → 回读"的路 ----
        // 必须先证明这条路能读出已知数据；否则"回读到 0"到底是共享显存没读到，
        // 还是我自己画错了，就永远分不清（上一次调试正是靠这一步定性的）。
        {
            final int ctrl = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, ctrl);
            final ByteBuffer src = BufferUtils.createByteBuffer(64 * 64 * 4);
            for (int i = 0; i < 64 * 64; i++) {
                src.put((byte) 0x5A).put((byte) 0x33).put((byte) 0xA5).put((byte) 0xFF);
            }
            src.flip();
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL_RGBA8, 64, 64, 0, GL11.GL_RGBA,
                    GL11.GL_UNSIGNED_BYTE, src);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL20.glUseProgram(program);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, ctrl);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "uTex"), 0);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            final ByteBuffer cpx = BufferUtils.createByteBuffer(4);
            GL11.glReadPixels(6, 6, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, cpx);
            System.out.printf("[mo] [夹具自检] 普通纹理回读 = %02X%02X%02X（期望 5A33A5）→ %s%n",
                    cpx.get(0) & 0xFF, cpx.get(1) & 0xFF, cpx.get(2) & 0xFF,
                    (cpx.get(0) & 0xFF) == 0x5A ? "✔" : "✘ 夹具本身有问题");
            GL11.glDeleteTextures(ctrl);
        }

        final long[] consumedUpTo = new long[slotCount];   // 每槽已消费到的 fence 值
        final int[] noBuffers = {};
        final int[] noLayouts = {};
        long consumed = 0;
        long verifyFailures = 0;
        long distinctStamps = 0;
        int lastStamp = -1;
        long emptyPolls = 0;
        final ByteBuffer pixel = BufferUtils.createByteBuffer(4);
        final long started = System.currentTimeMillis();
        final long until = started + seconds * 1000L;

        while (System.currentTimeMillis() < until) {
            glfwPollEvents();
            boolean didWork = false;

            for (int i = 0; i < slotCount; i++) {
                final int state = status.getInt(OFF_SLOTS0 + i * SLOT_STRIDE);
                if (state != SLOT_READY) continue;
                final long fv = status.getLong(OFF_SLOT_FENCE_VALUE + i * 8);
                if (fv == 0 || fv <= consumedUpTo[i]) continue;

                final int stampR = status.getInt(OFF_SLOTS0 + i * SLOT_STRIDE + 8);
                final int stampG = status.getInt(OFF_SLOTS0 + i * SLOT_STRIDE + 12);

                // 等生产者把这一帧写完（GPU 侧等，不占 CPU），并把纹理列成 barrier
                if (!noFence) {
                    if (trace && consumed < 3) say("  设信号量值 fv=" + fv);
                    EXTSemaphore.glSemaphoreParameterui64EXT(semaphores[i],
                            EXTSemaphoreWin32.GL_D3D12_FENCE_VALUE_EXT, fv);
                    if (trace && consumed < 3) say("  glWaitSemaphoreEXT...");
                    EXTSemaphore.glWaitSemaphoreEXT(semaphores[i], noBuffers, new int[]{textures[i]},
                            new int[]{EXTSemaphore.GL_LAYOUT_SHADER_READ_ONLY_EXT});
                    if (trace && consumed < 3) {
                        say("  glWait 已排入；glFinish 等它真的过...");
                        GL11.glFinish();
                        say("  ✔ 过了");
                    }
                }

                // 采样 + 回读校验（左上角区域是纯色标：R=帧号低 8 位、G=高 8 位、B=0xA5）
                GL20.glUseProgram(program);
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, textures[i]);
                GL20.glUniform1i(GL20.glGetUniformLocation(program, "uTex"), 0);
                if (trace && consumed < 3) say("  画 + 回读...");
                GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
                GL11.glReadPixels(6, 6, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel);
                if (trace && consumed < 3) say("  ✔ 回读完成");
                final int r = pixel.get(0) & 0xFF;
                final int g = pixel.get(1) & 0xFF;
                final int b = pixel.get(2) & 0xFF;

                // 前 3 帧把整张 FBO 回读下来，打一张 8×8 的降采样网格 ——
                // 用来判断"整张都是 0"还是"只有某些区域不对"，以及 GPU 内容与 CPU 角标
                // （--cpu-pattern，写在右下角）到底哪一个可见。
                if (trace && consumed < 3) {
                    dumpGrid(i, fv, r, g, b, stampR, stampG);
                }

                // 读完 → 把回程推上 fence（生产者复用这个槽之前会等它）
                final long doneValue = fv + 1;
                if (!noFence) {
                    EXTSemaphore.glSemaphoreParameterui64EXT(semaphores[i],
                            EXTSemaphoreWin32.GL_D3D12_FENCE_VALUE_EXT, doneValue);
                    EXTSemaphore.glSignalSemaphoreEXT(semaphores[i], noBuffers,
                            new int[]{textures[i]},
                            new int[]{EXTSemaphore.GL_LAYOUT_SHADER_READ_ONLY_EXT});
                }
                // 先提交指令流，再让生产者看见"这个槽读完了"
                GL11.glFlush();
                consumedUpTo[i] = doneValue;
                status.putLong(OFF_SLOT_CONSUMER_DONE + i * 8, doneValue);
                status.putInt(OFF_SLOTS0 + i * SLOT_STRIDE, SLOT_SHOWN);

                final int decoded = (r & 0xFF) | ((g & 0xFF) << 8);
                final int expected = (stampR & 0xFF) | ((stampG & 0xFF) << 8);
                if (b != STAMP_BLUE) {
                    if (verifyFailures < 5) {
                        System.out.printf("[mo] ✘ 槽 %d B 通道异常: 读到 %02X，期望 %02X（通道错位？）%n",
                                i, b, STAMP_BLUE);
                    }
                    verifyFailures++;
                } else if (decoded != expected) {
                    if (verifyFailures < 5) {
                        System.out.printf("[mo] ✘ 槽 %d 帧号不符: 读到 R=%02X G=%02X（=帧 %d），"
                                + "槽位状态说 R=%02X G=%02X（=帧 %d）%n", i, r, g, decoded,
                                stampR, stampG, expected);
                    }
                    verifyFailures++;
                }
                if (decoded != lastStamp) {
                    distinctStamps++;
                    lastStamp = decoded;
                }
                consumed++;
                didWork = true;
            }

            if (!didWork) {
                emptyPolls++;
                Thread.sleep(1);
            }
        }

        final long elapsedMs = System.currentTimeMillis() - started;
        System.out.println();
        System.out.println("--- 结果 ---");
        System.out.printf("    消费帧数 = %d（%.1f fps），不同帧号 = %d，校验失败 = %d，空转次数 = %d%n",
                consumed, consumed * 1000.0 / Math.max(1, elapsedMs), distinctStamps, verifyFailures,
                emptyPolls);
        System.out.printf("    生产者发布 = %d，fps = %.1f%n",
                status.getLong(OFF_FRAMES_PUBLISHED), status.getInt(OFF_PRODUCER_FPS_X100) / 100.0);

        // ---------- 6) 清理 ----------
        GL30.glDeleteFramebuffers(fbo);
        GL11.glDeleteTextures(colorTex);
        GL20.glDeleteProgram(program);
        GL30.glDeleteVertexArrays(vao);
        for (int i = 0; i < slotCount; i++) {
            GL11.glDeleteTextures(textures[i]);
            EXTMemoryObject.glDeleteMemoryObjectsEXT(memoryObjects[i]);
            EXTSemaphore.glDeleteSemaphoresEXT(semaphores[i]);
            // 句柄现在（且只有现在）才能关：GL 侧的引用是在 glTexStorageMem2DEXT 时建立的
            NativeD3D.closeRawHandle(texHandles[i]);
        }
        NativeD3D.closeRawHandle(fenceHandle);
        NativeD3D.closeSharedSection(status);
        status = null;

        final boolean pass = consumed >= 30 && verifyFailures == 0 && distinctStamps >= 10;
        System.out.println();
        System.out.println(pass
                ? "结论: ✔ PASS —— 跨进程「NT 共享句柄 → GL 内存对象」成立，且内容逐帧正确"
                : "结论: ✘ FAIL —— 把上面整段日志发回来");
        return pass ? 0 : 1;
    }

    /**
     * 诊断用：把 64×64 的 FBO 整张回读，打成 8×8 的降采样网格（每格取中心像素的 RGB）。
     *
     * <p>⚠️ 这里**不能**用 {@code glGetTexImage} 直接读别名纹理本体的内容：
     * 实测（NVIDIA 616.92）在"内存对象别名的 D3D11 纹理"上调用它会**直接把驱动/进程挂住**
     * （不是报错，是卡死）。要看内容就走"画进 FBO 再 glReadPixels"这条路。
     */
    private static void dumpGrid(int slot, long fv, int r, int g, int b, int stampR, int stampG) {
        final ByteBuffer all = BufferUtils.createByteBuffer(FBO_SIZE * FBO_SIZE * 4);
        GL11.glReadPixels(0, 0, FBO_SIZE, FBO_SIZE, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, all);
        System.out.printf("[mo] 诊断 槽%d fv=%d 槽位色标 R=%02X G=%02X；FBO(6,6)=%02X%02X%02X%n",
                slot, fv, stampR, stampG, r, g, b);
        final int step = FBO_SIZE / 8;
        for (int gy = 7; gy >= 0; gy--) {
            final StringBuilder row = new StringBuilder("        ");
            for (int gx = 0; gx < 8; gx++) {
                final int x = gx * step + step / 2;
                final int y = gy * step + step / 2;
                final int off = (y * FBO_SIZE + x) * 4;
                row.append(String.format("%02X%02X%02X ", all.get(off) & 0xFF, all.get(off + 1) & 0xFF,
                        all.get(off + 2) & 0xFF));
            }
            System.out.println(row);
        }
    }

    private static String describe(long[] handles) {        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < handles.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(String.format("0x%x", handles[i]));
        }
        return sb.toString();
    }

    /**
     * 把源纹理画进那张 64×64 的 FBO（一次画满，之后 glReadPixels 取左上角像素）。
     *
     * <p>★ 与 NV_DX_interop 那条路一样要上下翻转：D3D 的第 0 行在内存顶部，而 GL 把 v=0 当底部
     * （这条结论在自检工具 {@code MoProbe} 里是**测出来**的，不是假设）。
     */
    private static int buildProgram() {
        final String vs = """
                #version 330 core
                out vec2 vUv;
                void main() {
                    vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                    vUv = vec2(p.x, 1.0 - p.y);      // D3D 内存顺序 → GL 采样顺序
                    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
                }
                """;
        final String fs = """
                #version 330 core
                in vec2 vUv;
                out vec4 fragColor;
                uniform sampler2D uTex;
                void main() { fragColor = texture(uTex, vUv); }
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

    private static void fail(String message) {
        System.out.println("[mo] 失败: " + message);
    }

    /** 诊断用：立刻刷出来的单行输出（重定向到文件时也能看到"卡在哪一步"）。 */
    private static void say(String message) {
        System.out.println("[mo]" + message);
        System.out.flush();
    }

    private MoSpikeConsumer() {
    }
}

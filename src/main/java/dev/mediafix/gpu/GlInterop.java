package dev.mediafix.gpu;

import dev.mediafix.CineDuct;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.WGLCapabilities;
import org.lwjgl.opengl.WGLNVDXInterop;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 游戏进程侧的「D3D11 共享纹理 → OpenGL 纹理」互操作。
 *
 * <p><b>这里做的事就是 P1 打样验证过的那条链路</b>（结论见 native/README.md）：
 * <ol>
 *   <li>用 {@link NativeD3D}（游戏进程内的 D3D11 垫片）建一个 D3D11 设备，**必须与 GL 上下文同适配器** ——
 *       适配器不是猜的：逐个 DXGI 适配器建设备试 {@code wglDXOpenDeviceNV}，成功的就是它；</li>
 *   <li>子进程的共享纹理按名打开（NTHANDLE + 键控互斥）；</li>
 *   <li>★ 共享纹理**不能**直接注册给 GL —— 实测 {@code wglDXRegisterObjectNV} 拒绝带
 *       {@code SHARED_NTHANDLE} 的纹理。所以每槽再建一张本地纹理（misc=0）注册给 GL，
 *       每帧在 GPU 上把共享纹理 {@code CopyResource} 进本地纹理（显存内拷贝，几乎免费）；</li>
 *   <li>采样本地纹理时必须在 {@code wglDXLockObjectsNV} 保护下（不加锁读到的是垃圾，实测过）。</li>
 * </ol>
 *
 * <p><b>线程</b>：所有方法都必须在**渲染线程**（GL 上下文当前的那个线程）调用。
 */
public final class GlInterop implements AutoCloseable {

    /** 一个槽位：子进程的共享纹理 + 本进程的本地纹理 + 注册出来的 GL 纹理。 */
    public static final class Slot {
        private long sharedTexture;
        private long localTexture;
        private int glTexture;
        private long interopObject;
        private final PointerBuffer lockBuf;

        private Slot(PointerBuffer lockBuf) {
            this.lockBuf = lockBuf;
        }

        public int glTexture() {
            return this.glTexture;
        }

        public boolean valid() {
            return this.interopObject != 0L && this.glTexture > 0;
        }
    }

    private final long d3dDevice;
    private final long interopDevice;
    private final long adapterLuid;
    private final String adapterName;
    private final List<Slot> slots = new ArrayList<>();
    private final int width;
    private final int height;

    private GlInterop(long d3dDevice, long interopDevice, long adapterLuid, String adapterName,
                      int width, int height) {
        this.d3dDevice = d3dDevice;
        this.interopDevice = interopDevice;
        this.adapterLuid = adapterLuid;
        this.adapterName = adapterName;
        this.width = width;
        this.height = height;
    }

    public long adapterLuid() {
        return this.adapterLuid;
    }

    public String adapterName() {
        return this.adapterName;
    }

    public void addSlot(boolean unused) {
        this.slots.add(new Slot(MemoryUtil.memAllocPointer(1)));
    }

    public int slotCount() {
        return this.slots.size();
    }

    public Slot slot(int index) {
        return index >= 0 && index < this.slots.size() ? this.slots.get(index) : null;
    }

    /**
     * 探测并建立互操作设备。
     *
     * <p>返回 null 表示这条路在这台机器/驱动上不可用（非 NVIDIA、驱动不支持、垫片缺失…）——
     * 调用方应当**自动降级**到进程内 Java 引擎，而不是报错。
     */
    public static GlInterop probe(int width, int height) {
        final long luid = GpuContext.adapterLuid();
        if (luid == 0L) {
            CineDuct.LOGGER.warn("[CineDuct] 互操作不可用：{}", GpuContext.reason());
            return null;
        }
        final long device = NativeD3D.createDevice(luid, NativeD3D.FLAG_BGRA_SUPPORT);
        if (device == 0L) {
            CineDuct.LOGGER.warn("[CineDuct] 互操作不可用：在 LUID {} 上建设备失败（{}）",
                    luid, NativeD3D.lastError());
            return null;
        }
        final long interop = WGLNVDXInterop.wglDXOpenDeviceNV(device);
        if (interop == 0L) {
            NativeD3D.release(device);
            CineDuct.LOGGER.warn("[CineDuct] 互操作不可用：wglDXOpenDeviceNV 失败");
            return null;
        }
        CineDuct.LOGGER.info("[CineDuct] 互操作设备已建立: {} luid={}",
                GpuContext.adapterName(), String.format(java.util.Locale.ROOT, "%08x:%08x",
                        (int) (luid >>> 32), (int) luid));
        return new GlInterop(device, interop, luid, GpuContext.adapterName(), width, height);
    }

    /** 按名打开一块共享纹理，建本地纹理并注册成 GL 纹理。 */
    public boolean importSlot(int index, String sharedName) {
        while (this.slots.size() <= index) {
            this.slots.add(new Slot(MemoryUtil.memAllocPointer(1)));
        }
        Slot slot = this.slots.get(index);
        if (slot.valid()) return true;

        final long sharedTex = NativeD3D.openSharedTextureByName(this.d3dDevice, sharedName);
        if (sharedTex == 0L) {
            CineDuct.LOGGER.warn("[CineDuct] 打开共享纹理 {} 失败: {}", sharedName, NativeD3D.lastError());
            return false;
        }
        final long localTex = NativeD3D.createTexture(this.d3dDevice, this.width, this.height, 0,
                NativeD3D.BIND_RENDER_TARGET | NativeD3D.BIND_SHADER_RESOURCE);
        if (localTex == 0L) {
            NativeD3D.release(sharedTex);
            CineDuct.LOGGER.warn("[CineDuct] 建本地纹理失败: {}", NativeD3D.lastError());
            return false;
        }

        final int glTex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, glTex);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        final long object = WGLNVDXInterop.wglDXRegisterObjectNV(this.interopDevice, localTex, glTex,
                GL11.GL_TEXTURE_2D, WGLNVDXInterop.WGL_ACCESS_READ_ONLY_NV);
        if (object == 0L) {
            GL11.glDeleteTextures(glTex);
            NativeD3D.release(localTex);
            NativeD3D.release(sharedTex);
            CineDuct.LOGGER.warn("[CineDuct] wglDXRegisterObjectNV 失败（槽位 {}）", index);
            return false;
        }
        slot.sharedTexture = sharedTex;
        slot.localTexture = localTex;
        slot.glTexture = glTex;
        slot.interopObject = object;
        CineDuct.LOGGER.info("[CineDuct] 槽位 {} 已导入: 共享纹理 {} → GL 纹理 {}", index, sharedName, glTex);
        return true;
    }

    /**
     * 把共享纹理的内容拷进本地纹理（GPU 内拷贝，内部按"写方 0 / 读方 1"的乒乓做键控互斥）。`n     *`n     * <p>超时给 8ms：写方一次 CopyResource 偶尔会到几十毫秒（4K/8K + 信箱化那两下），`n     * 正常竞争时等一下就能拿到；真拿不到就当"本帧没有新帧"，调用方继续用上一帧，不撕帧。
     *
     * @return true = 拷到了新帧；false = 暂时没有新帧（调用方可继续用上一帧）
     */
    public boolean copyInto(Slot slot) {
        if (slot == null || !slot.valid()) return false;
        final int result = NativeD3D.copyFromShared(this.d3dDevice, slot.localTexture,
                slot.sharedTexture, NativeD3D.KEY_READER, NativeD3D.KEY_WRITER, 4);
        if (result < 0) {
            CineDuct.LOGGER.warn("[CineDuct] 共享纹理拷贝失败: {}", NativeD3D.lastError());
            return false;
        }
        return result == 0;
    }

    /** 采样前加锁（不加锁读到的是垃圾）。 */
    public boolean lock(Slot slot) {
        if (slot == null || !slot.valid()) return false;
        slot.lockBuf.put(0, slot.interopObject);
        return WGLNVDXInterop.wglDXLockObjectsNV(this.interopDevice, slot.lockBuf);
    }

    /** 采样后解锁。 */
    public void unlock(Slot slot) {
        if (slot == null || !slot.valid()) return;
        slot.lockBuf.put(0, slot.interopObject);
        WGLNVDXInterop.wglDXUnlockObjectsNV(this.interopDevice, slot.lockBuf);
    }

    @Override
    public void close() {
        for (Slot slot : this.slots) {
            if (slot.interopObject != 0L) {
                WGLNVDXInterop.wglDXUnregisterObjectNV(this.interopDevice, slot.interopObject);
                slot.interopObject = 0L;
            }
            if (slot.glTexture > 0) {
                GL11.glDeleteTextures(slot.glTexture);
                slot.glTexture = 0;
            }
            if (slot.localTexture != 0L) {
                NativeD3D.release(slot.localTexture);
                slot.localTexture = 0L;
            }
            if (slot.sharedTexture != 0L) {
                NativeD3D.release(slot.sharedTexture);
                slot.sharedTexture = 0L;
            }
            MemoryUtil.memFree(slot.lockBuf);
        }
        this.slots.clear();
        if (this.interopDevice != 0L) {
            WGLNVDXInterop.wglDXCloseDeviceNV(this.interopDevice);
        }
        if (this.d3dDevice != 0L) {
            NativeD3D.release(this.d3dDevice);
        }
    }
}

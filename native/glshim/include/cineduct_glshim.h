// ============================================================================
// cineduct-glshim —— 游戏进程内的极小 D3D11 垫片
//
// 为什么需要它：LWJGL 只绑定 OpenGL/WGL，而 WGL_NV_DX_interop2 的输入是
// **一个 ID3D11Device 指针**，共享纹理也要在本进程里 OpenSharedResource 才能注册。
// 这两件事必须发生在游戏进程内，所以只能自己开一层原生接口。
//
// 这里刻意只暴露"最少够用"的 C 接口，JNI 只是薄薄一层包装：
//   * 建 D3D11 设备（可指定适配器 LUID）
//   * 枚举适配器（判断哪个才是 GL 上下文所在的那块卡）
//   * 按名打开共享纹理（打样用；生产走 NT 句柄 + OpenSharedResource1）
//   * 取设备所在适配器 LUID / 纹理描述（诊断用）
//
// 接口用 C 声明，将来若改用别的 FFI（或从 C++ 侧直接调）都不用改。
// ============================================================================
#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// flags 位（Java 侧 NativeD3D.FLAG_* 必须与之一致）
#define MF_GPU_FLAG_BGRA_SUPPORT  0x1u   // NV_DX_interop 用 BGRA8 纹理的前提
#define MF_GPU_FLAG_VIDEO_SUPPORT 0x2u   // d3d11va 硬解的前提（子进程用；游戏进程用不到）
#define MF_GPU_FLAG_DEBUG         0x4u   // D3D11 调试层
#define MF_GPU_FLAG_MULTITHREAD   0x8u   // ID3D10Multithread::SetMultithreadProtected

// 建 D3D11 设备。adapter_luid = 0 表示默认硬件适配器。
// 成功返回 ID3D11Device*（当不透明指针用），失败返回 NULL，原因见 mf_gpu_last_error()。
__declspec(dllexport) void* mf_gpu_create_device(uint64_t adapter_luid, uint32_t flags);

// 释放一个由本 DLL 返回的 COM 对象（Release）。
__declspec(dllexport) void mf_gpu_release(void* obj);

// 设备所在适配器的 LUID（打包成 64 位：high<<32 | low）；失败返回 0。
__declspec(dllexport) uint64_t mf_gpu_adapter_luid(void* device);

// 枚举适配器，写入 UTF-8 文本，每行 "hi:lo|描述|显存MB|flags"。
// 返回写入的字节数（不含结尾 0）；buf 太小则返回需要的字节数（负值表示出错）。
__declspec(dllexport) int mf_gpu_adapters(char* buf, int buf_bytes);

// 按名打开共享纹理，返回 ID3D11Texture2D*（当不透明指针用）；失败返回 NULL。
// 打样用；名字是全局命名对象，生产更推荐下面的句柄路径。
__declspec(dllexport) void* mf_gpu_open_shared_texture_by_name(void* device, const wchar_t* name);

// 按 NT 句柄打开共享纹理（生产路径）：子进程 CreateSharedHandle + DuplicateHandle 投递过来。
__declspec(dllexport) void* mf_gpu_open_shared_texture_by_handle(void* device, uint64_t handle);

// 在本进程直接建一张纹理（诊断/自检用：用来区分"我们调用姿势不对"和"跨进程受限"）。
// misc_flags / bind_flags 用 D3D11 的原值，方便做标志矩阵实验。
__declspec(dllexport) void* mf_gpu_create_texture(void* device, uint32_t width, uint32_t height,
                                                  uint32_t misc_flags, uint32_t bind_flags);

// 把共享纹理的内容在 GPU 上拷到本地纹理（键控互斥保护下），供 GL 采样。
//
// 为什么必须绕这一下：NV_DX_interop **拒绝注册带 D3D11_RESOURCE_MISC_SHARED_NTHANDLE
// 的纹理**（实测：misc=0x0/0x1/0x2/0x3 都能注册，0x802 一律返回 0），而跨进程共享又只能靠
// NTHANDLE。于是改成：共享纹理只负责跨进程搬运，游戏进程再 GPU→GPU 拷进一张本地纹理，
// 由 GL 采样那张本地纹理。显存内拷贝代价极低，且跨 API 同步由 wglDXLockObjectsNV 保证。
//
// ★ 键控互斥必须用【两个不同的 key】乒乓交棒：写方 Acquire(0) → Release(1)，
//   读方 Acquire(1) → Release(0)。两侧用同一个 key 时 AcquireSync 会**同 key 直接成功**，
//   等于完全没有互斥（实测踩过）。
//
// 返回 0 = 拷贝成功；1 = 等锁超时（没有新帧，不算错误）；<0 = 出错，详情见 mf_gpu_last_error()。
__declspec(dllexport) int mf_gpu_copy_from_shared(void* device, void* dst, void* src,
                                                  uint32_t acquire_key, uint32_t release_key,
                                                  uint32_t timeout_ms);

// 取纹理描述，写 4 个 uint32：width, height, dxgiFormat, bindFlags。成功返回 1。
__declspec(dllexport) int mf_gpu_texture_desc(void* texture, uint32_t* out4);

// ---------------------------------------------------------------------------
// NT 共享句柄路线（D3D11 → OpenGL 内存对象）所需的原语
//
// 与上面的 NV_DX_interop 路线并列的一套，**不依赖任何厂商扩展函数指针**：
//   生产者：mf_gpu_create_shared_texture() 拿 NT 句柄 → DuplicateHandle 投递
//   消费者：glImportMemoryWin32HandleEXT(..., GL_HANDLE_TYPE_D3D11_IMAGE_EXT, handle)
//           + glTextureStorageMem2DEXT() 直接把这句柄背后的显存别成 GL 纹理
//
// 注意（2026-10 核实）：并不存在 GL_HANDLE_TYPE_OPAQUE_WIN32_NT_EXT 这个枚举。
// EXT_memory_object_win32 里与 D3D11 纹理对应的两个值是：
//   GL_HANDLE_TYPE_D3D11_IMAGE_EXT     = 0x958B  ← **NT 句柄**用这个
//   GL_HANDLE_TYPE_D3D11_IMAGE_KMT_EXT = 0x958C  ← 老的 GetSharedHandle（KMT）用这个
// （GL_HANDLE_TYPE_OPAQUE_WIN32_EXT = 0x9587 是给 Vulkan 导出的 NT 句柄用的。）
// ---------------------------------------------------------------------------

// 建一张**可跨进程共享**的 2D 纹理：B8G8R8A8_UNORM + NTHANDLE + KEYEDMUTEX。
// name 可为 nullptr（无名 NT 句柄，只能靠 DuplicateHandle 投递）；非空则同时是命名对象。
// 成功返回 ID3D11Texture2D*，NT 句柄写入 *out_handle（调用方负责 CloseHandle）。
__declspec(dllexport) void* mf_gpu_create_shared_texture(void* device, uint32_t width, uint32_t height,
                                                         const wchar_t* name, uint64_t* out_handle);

// 用纯色图案填充纹理（自检用：不需要着色器，也就不会把"采样错了"和"画错了"混在一起）。
// 写入的字节序是 **BGRA**（与 DXGI_FORMAT_B8G8R8A8_UNORM 一致）：
//   每像素 = B0xA5, G=stamp_g, R=stamp_r, A=0xFF
// 于是 GL 侧用 GL_RGBA8 读到的 (R,G,B) 恰好是 (0xA5, stamp_g, stamp_r) ——
// 这正好把"格式对应关系"变成可断言的数字，而不是靠人眼看颜色。成功返回 1。
__declspec(dllexport) int mf_gpu_fill_texture(void* device, void* texture, uint32_t stamp_r,
                                              uint32_t stamp_g);

// 键控互斥（跨进程/跨 API 的顺序原语）。不需要拷贝时直接用它，不要为了拿锁去 CopyResource。
// 返回 0 = 拿到；1 = 超时（WAIT_TIMEOUT，调用方当"没有新帧"处理）；<0 = 出错。
__declspec(dllexport) int mf_gpu_acquire_sync(void* texture, uint32_t key, uint32_t timeout_ms);
__declspec(dllexport) int mf_gpu_release_sync(void* texture, uint32_t key);

// 建一个**可共享的 D3D11 fence**（D3D11_FENCE_FLAG_SHARED，需要 ID3D11Device5 / Win10+）。
// 这是 GL_EXT_semaphore_win32 那条 GPU 间同步链的 D3D 侧载体：
// 它的共享句柄就是 GL 侧用 GL_HANDLE_TYPE_D3D12_FENCE_EXT 导入的那个对象。
// 成功返回 ID3D11Fence*，共享句柄写入 *out_handle。
__declspec(dllexport) void* mf_gpu_create_shared_fence(void* device, uint64_t initial_value,
                                                       const wchar_t* name, uint64_t* out_handle);

// 按共享句柄打开 fence（消费方向）。
__declspec(dllexport) void* mf_gpu_open_shared_fence(void* device, uint64_t handle);

// GPU 侧 Signal / Wait（ID3D11DeviceContext4）。**Wait 是 GPU 排队等待，不占 CPU**。
// 返回 0 成功，<0 失败（老系统没有 ID3D11DeviceContext4 时就是这里失败）。
__declspec(dllexport) int mf_gpu_fence_signal(void* device, void* fence, uint64_t value);
__declspec(dllexport) int mf_gpu_fence_wait(void* device, void* fence, uint64_t value);
// 已经完成到的 fence 值（不动 GPU）。
__declspec(dllexport) uint64_t mf_gpu_fence_completed(void* fence);
// CPU 侧有超时地等某个值 —— 用来**避免**发出"永远不会被满足的 GPU Wait"（对端崩了会挂死 GPU 队列）。
// 返回 0 = 已到；1 = 超时；<0 = 出错。
__declspec(dllexport) int mf_gpu_fence_wait_cpu(void* fence, uint64_t value, uint32_t timeout_ms);

// 打开一个已存在的命名共享内存段（由子进程 CreateFileMapping 创建）并映射。
// 返回映射地址（Java 侧包成直接 ByteBuffer），失败返回 NULL。
// 这是状态环（位置/统计/槽位标志）的载体：零系统调用、零 IPC 就能读到最新状态。
__declspec(dllexport) void* mf_gpu_open_shared_section(const wchar_t* name, uint32_t bytes);
__declspec(dllexport) void mf_gpu_close_shared_section(void* mapped);

// ---- 控制通道（命名管道的客户端；Java 没有原生管道 API）----
// 连不上返回 NULL（子进程还没把管道建起来时用 timeout_ms 轮询等待）。
__declspec(dllexport) void* mf_gpu_pipe_connect(const wchar_t* name, uint32_t timeout_ms);
// 发一条命令并收一条回执，回执文本写入 out（UTF-8），返回写入长度；<0 出错。
__declspec(dllexport) int mf_gpu_pipe_request(void* pipe, const char* payload, char* out,
                                             int out_bytes);
__declspec(dllexport) void mf_gpu_pipe_close(void* pipe);

// ---- Job Object（第二道保险）----
// 子进程卡死不响应时，"父进程看护"那种优雅退出是没用的；Job Object 的
// KILL_ON_JOB_CLOSE 能保证：只要游戏进程一结束（哪怕被强杀），句柄关闭即连带杀掉子进程。
// 返回 job 句柄（失败 NULL）；assign 把子进程放进去；close 释放句柄（会让句柄为最后一个的子进程被杀）。
__declspec(dllexport) void* mf_gpu_job_create(void);
__declspec(dllexport) int mf_gpu_job_assign(void* job, uint32_t pid);
__declspec(dllexport) void mf_gpu_job_close(void* job);

// 最近一次失败原因（线程局部，UTF-8）。
__declspec(dllexport) const char* mf_gpu_last_error(void);

// 关掉一个裸内核句柄（例如生产者 DuplicateHandle 直投进来的 NT 句柄）。
// GL 侧导入之后**不能**提前关：驱动是在 glTexStorageMem2DEXT/glImport* 时才真正建立引用，
// 保险做法是"用完整段会话再关"。返回 0 成功。
__declspec(dllexport) int mf_gpu_close_handle(uint64_t handle);

#ifdef __cplusplus
}
#endif

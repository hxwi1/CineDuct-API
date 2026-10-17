> **性质说明（CineDuct 仓库内版本）**：本文件是**设计索引**，只记录 OBS Studio 中零拷贝相关实现的
> 位置（文件 + 行号），**不包含任何 OBS 代码**。它用来表明 CineDuct 的零拷贝方案参考了哪些公开实现；
> CineDuct 的对应实现为独立编写，未复制 OBS 源码，说明见 [`THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md)。
>
> 行号基于作者当时的本地 obs-studio 检出，未记录具体 commit，上游变动后可能不再对应。
# OBS 显存零拷贝（GPU zero-copy）代码索引

> 说明：本文只引用文件路径与行号，不复制代码。行号基于当前 `F:\mos\obs-studio-master` 检出。
> 术语约定：
> - **真·零拷贝 / VRAM 共享**：同一块显存被两个设备/进程/API 直接引用，帧数据不做逐像素搬运（Windows 共享句柄 KMT/NT、NT 句柄、macOS IOSurface、Linux DMA-BUF）。
> - **近似零拷贝 / GPU→GPU 拷贝**：数据始终留在显存，但用 `CopyResource` / `CmdCopyImage` / `glBlitFramebuffer` 做一次 GPU 内部搬运（不下 CPU、不走系统内存）。

---

## 0. 总览：三类零拷贝路径

| 类别 | 机制 | 主要出现位置 |
| --- | --- | --- |
| API/进程间显存共享 | D3D11 共享句柄 + KeyedMutex（KMT）、NT 句柄、IOSurface、DMA-BUF | `libobs/graphics`、`libobs-d3d11`、`libobs-opengl`、`libobs-metal` |
| 游戏/窗口捕获零拷贝 | 注入 hook → 共享纹理 → OBS 打开共享句柄 | `plugins/win-capture/graphics-hook/*`、`plugins/win-capture/game-capture.c` |
| GPU 编码零拷贝 | NV12/P010 共享纹理 → 编码器 `OpenSharedResource` / DMA-BUF / CUDA-GL | `libobs/obs-video*.c`、`plugins/obs-nvenc`、`plugins/obs-ffmpeg`、`plugins/obs-qsv11` |

---

## 1. 图形抽象层（libobs/graphics）：零拷贝的统一入口

- 共享标志位定义：`libobs/graphics/graphics.h:462-470`（`GS_SHARED_TEX`、`GS_SHARED_KM_TEX`）
- Windows 零拷贝 API 声明：`libobs/graphics/graphics.h:861-882`
  - `gs_texture_open_shared` / `gs_texture_open_nt_shared` / `gs_texture_get_shared_handle`
  - `gs_texture_wrap_obj`（包裹已有 `ID3D11Texture2D`）
  - `gs_texture_acquire_sync` / `gs_texture_release_sync`（KeyedMutex 同步原语）
- Apple IOSurface API 声明：`libobs/graphics/graphics.h:819-826`（`gs_texture_create_from_iosurface`、`gs_texture_rebind_iosurface`、`gs_texture_open_shared`、`gs_shared_texture_available`）
- Linux DMA-BUF / 同步对象声明：`libobs/graphics/graphics.h:890-919`
  - `gs_texture_create_from_dmabuf`、`gs_texture_create_from_pixmap`
  - `gs_query_dmabuf_capabilities` / `gs_query_dmabuf_modifiers_for_format`
  - `gs_sync_create*` / `gs_sync_export_syncobj_timeline_point` / `gs_sync_signal_syncobj_timeline_point`
- 设备导出函数表（后端实现这些函数指针）：`libobs/graphics/graphics-internal.h:223-253`
- 动态导入（哪些后端提供哪些零拷贝能力）：`libobs/graphics/graphics-imports.c:201-236`
  - `device_texture_create_nv12`、`device_shared_texture_available`、`device_texture_create_from_iosurface`、`gs_texture_rebind_iosurface`、`device_texture_open_nt_shared`、`device_texture_get_shared_handle`
- 对外函数实现（转发到设备导出）：
  - `gs_texture_create_from_dmabuf`：`libobs/graphics/graphics.c:1417-1425`
  - `gs_texture_create_from_pixmap`：`libobs/graphics/graphics.c:1443-1450`
  - `gs_query_dmabuf_capabilities`：`libobs/graphics/graphics.c:1427-1433`
  - `gs_texture_create_from_iosurface`：`libobs/graphics/graphics.c:3000-3009`
  - `gs_texture_rebind_iosurface`：`libobs/graphics/graphics.c:3012-3021`
  - `gs_shared_texture_available`：`libobs/graphics/graphics.c:3024-3029`（Apple 分支）、`3053-3058`（Windows 分支）
  - `gs_texture_open_shared`：`libobs/graphics/graphics.c:3032`（Apple）、`3186`（Windows）
  - `gs_texture_open_nt_shared`：`libobs/graphics/graphics.c:3197`
  - `gs_texture_get_shared_handle`：`libobs/graphics/graphics.c:3208`
  - `gs_texture_wrap_obj`：`libobs/graphics/graphics.c:3219`
  - `gs_texture_acquire_sync` / `gs_texture_release_sync`：`libobs/graphics/graphics.c:3230`、`3241`
- NV12 / P010 打包（编码用共享纹理的创建入口）：`libobs/graphics/graphics.h:789`、`libobs/graphics/graphics.c:2911-2930`
- 反面参照（会走 CPU 的路径，非零拷贝）：`gs_stage_texture` `libobs/graphics/graphics.c:1902`、`gs_texture_map` `libobs/graphics/graphics.c:2460`、`gs_texture_get_obj` `libobs/graphics/graphics.c:2493`

---

## 2. Windows / D3D11

### 2.1 共享 2D 纹理的创建、句柄与 KeyedMutex

- 普通共享（`GS_SHARED_TEX`）/ 关键互斥（`GS_SHARED_KM_TEX`）标志写入 `D3D11_TEXTURE2D_DESC.MiscFlags`：`libobs-d3d11/d3d11-texture2d.cpp:128-132`
- 创建后取共享句柄 `GetSharedHandle`，并对 KM 纹理立即 `AcquireSync(0, INFINITE)`：`libobs-d3d11/d3d11-texture2d.cpp:151-178`
- 着色器资源视图（零拷贝渲染/采样所必需）：`libobs-d3d11/d3d11-texture2d.cpp:181-212`
- 打开已有共享纹理（KMT 句柄与 NT 句柄两条路）：`libobs-d3d11/d3d11-texture2d.cpp:323-358`
  - NT 句柄：`OpenSharedResource1`（需 `ID3D11Device1`）：`d3d11-texture2d.cpp:329-332`
  - KMT 句柄：`OpenSharedResource`：`d3d11-texture2d.cpp:333-336`
- 包裹外部 `ID3D11Texture2D`：`libobs-d3d11/d3d11-texture2d.cpp:360-381`
- NV12 双平面共享纹理（UV 是 Y 的视图，因此只共享一次）：`libobs-d3d11/d3d11-subsystem.cpp:3293-3326`

### 2.2 设备导出实现

- `device_texture_open_shared`：`libobs-d3d11/d3d11-subsystem.cpp:3186-3199`
- `device_texture_open_nt_shared`：`libobs-d3d11/d3d11-subsystem.cpp:3201-3214`
- `device_texture_get_shared_handle`：`libobs-d3d11/d3d11-subsystem.cpp:3216-3224`
- `device_texture_wrap_obj`：`libobs-d3d11/d3d11-subsystem.cpp:3226-3239`
- `device_texture_acquire_sync`（`IDXGIKeyedMutex::AcquireSync`，`WAIT_TIMEOUT` → `ETIMEDOUT`）：`libobs-d3d11/d3d11-subsystem.cpp:3241-3266`
- `device_texture_release_sync`：`libobs-d3d11/d3d11-subsystem.cpp:3268-3291`
- `device_texture_create_nv12` / `device_texture_create_p010`：`libobs-d3d11/d3d11-subsystem.cpp:3293`、`3328`
- 能力查询：`device_shared_texture_available`（恒为 true）`libobs-d3d11/d3d11-subsystem.cpp:3088-3091`
- 3D 纹理的同类逻辑：`libobs-d3d11/d3d11-texture3d.cpp:112-115`、`142-147`、`214`

### 2.3 设备丢失（device loss）后的共享纹理重建

- 共享纹理重建：重新 `OpenSharedResource` 打开原 `sharedHandle`，失败才回退为私有纹理：`libobs-d3d11/d3d11-rebuild.cpp:69-122`（回退函数 `RebuildSharedTextureFallback`，`55-67`）
- 配对 Y/UV 平面重建（保持共享句柄与 KM 同步）：`libobs-d3d11/d3d11-rebuild.cpp:124-163`、`165-...`

### 2.4 DXGI 桌面复制（Desktop Duplication）

- 捕获帧并转为 OBS 纹理：`libobs-d3d11/d3d11-duplicator.cpp:256-299`
- **注意**：这里 `copy_texture` 使用 `CopyResource`，属于 GPU→GPU 拷贝而非句柄共享：`libobs-d3d11/d3d11-duplicator.cpp:234-254`（`CopyResource` 在 `252`）

### 2.5 Windows Graphics Capture（WGC，窗口/显示器捕获）

- 从 WinRT 帧拿到 `ID3D11Texture2D`：`libobs-winrt/winrt-capture.cpp:30-37`、`128-139`
- 客户端区域裁剪拷贝 `CopySubresourceRegion`：`libobs-winrt/winrt-capture.cpp:168-170`
- 整帧拷贝 `CopyResource`，代码注释明确写了“if they gave an SRV, we could avoid this copy”，即当前是 GPU 内拷贝而非零拷贝：`libobs-winrt/winrt-capture.cpp:171-175`
- 统一 `IDirect3DDxgiInterfaceAccess` 获取接口：`libobs-winrt/winrt-capture.cpp:31-37`

---

## 3. Vulkan（捕获侧）

Vulkan 在 OBS 中不是渲染后端（无 `libobs-vulkan`），零拷贝相关代码集中在 `win-capture` 的 Vulkan 注入层，功能是：**让 Vulkan 应用与 D3D11 通过 `VK_KHR_external_memory_win32` 共享同一块显存**。

### 3.1 层（layer）注入与能力协商

- 层清单（`VK_LAYER_OBS_HOOK`，`DISABLE_VULKAN_OBS_CAPTURE` 开关）：`plugins/win-capture/graphics-hook/obs-vulkan64.json:1-17`（32 位版本 `obs-vulkan32.json`）
- 实例创建时校验扩展（`EnumerateDeviceExtensionProperties` 等）：`plugins/win-capture/graphics-hook/vulkan-capture.c:1228-1332`
- 设备创建时要求 `VK_KHR_external_memory_win32` 扩展：`vulkan-capture.c:1512`、`1515-1547`
- 能力探测 `vk_shared_tex_supported`（导出 `VK_EXTERNAL_MEMORY_HANDLE_TYPE_D3D11_TEXTURE_KMT_BIT`、要求 `IMPORTABLE`）：`vulkan-capture.c:1344-1377`，调用点 `1552-1555`
- 函数表（`AllocateMemory` / `BindImageMemory2` / `CmdCopyImage` / `WaitForFences` 等）：`plugins/win-capture/graphics-hook/vulkan-capture.h:16-52`

### 3.2 共享纹理的建立（核心零拷贝点）

- 创建 D3D11 侧共享纹理并取共享句柄（`D3D11_RESOURCE_MISC_SHARED` + `GetSharedHandle`）：`vulkan-capture.c:637-687`（描述符 `654-665`，取句柄 `672-684`）
- Vulkan 侧把同一句柄导入为 `VkImage`：
  - `VkExternalMemoryImageCreateInfo.handleTypes = ..._D3D11_TEXTURE_KMT_BIT`：`vulkan-capture.c:697-700`
  - `VkImageCreateInfo`（`TRANSFER_DST` + `SAMPLED`）：`702-724`
  - 内存需求（`DEDICATED_ONLY` 时走 `GetImageMemoryRequirements2`）：`736-757`
  - `VkImportMemoryWin32HandleInfoKHR` + `VkMemoryAllocateInfo`（优先 `DEVICE_LOCAL`，失败再退普通内存）：`770-830`
  - `VkMemoryDedicatedAllocateInfo` 与 `BindImageMemory` / `BindImageMemory2`：`782-852`
- 总入口与共享内存捕获的限制说明：`vulkan-capture.c:856-884`（`force_shmem` 时提示"shared memory capture currently unsupported"）

### 3.3 每帧搬运与同步（近似零拷贝：GPU 内拷贝）

- 帧资源（命令池/命令缓冲/栅栏）按交换链图像数创建与销毁：`vulkan-capture.c:886-957`
- `vk_shtex_capture` 全流程：`vulkan-capture.c:959-1145`
  - 首次布局转换 `UNDEFINED → GENERAL`：`1013-1034`
  - 交换链图像 `PRESENT_SRC_KHR → TRANSFER_SRC_OPTIMAL`：`1039-1052`
  - 导出图像 `GENERAL → TRANSFER_DST_OPTIMAL`，含 `VK_QUEUE_FAMILY_EXTERNAL` 队列族交接：`1057-1073`
  - `CmdCopyImage` 拷贝到共享图像：`1078-1097`
  - 还原布局并把所有权交回外部：`1105-1119`
  - `QueueSubmit` + 栅栏：`1125-1144`
- 帧驱动与初始化/释放状态机：`vulkan-capture.c:1152-1198`、`1200-1220`

> 结论：Vulkan 路径的"零拷贝"体现在**显存对象层面的共享**（Vulkan 与 D3D11 共用同一块显存，避免 CPU 回读）；单帧仍有一次 GPU 内 `CmdCopyImage`，属于"GPU→GPU 近似零拷贝"，与 D3D11 桌面复制的 `CopyResource` 同级。

---

## 4. 跨平台其他方面

### 4.1 捕获侧：Windows OpenGL 应用（WGL_NV_DX_interop）

- WGL-NV 函数加载与可用性判定：`plugins/win-capture/graphics-hook/gl-capture.c:141-177`
- 创建 D3D11 共享纹理并取共享句柄：`gl-capture.c:334-371`（`D3D11_RESOURCE_MISC_SHARED` 在 `347`，`GetSharedHandle` 在 `362`）
- 把 D3D11 纹理注册为 GL 纹理（`wglDXOpenDeviceNV` / `wglDXRegisterObjectNV` + `WGL_ACCESS_WRITE_DISCARD_NV`）：`gl-capture.c:373-394`
- 初始化组合与 `capture_init_shtex` 上报句柄：`gl-capture.c:402-426`
- 回退路径（共享内存 PBO，逐帧 `glReadPixels` 式拷贝，非零拷贝）：`gl-capture.c:546-594`（`gl_copy_backbuffer`）、`604-680`（shmem 版本使用 `glMapBuffer`/`shmem_copy_data`）

### 4.2 捕获侧：接收端（OBS 打开 hook 提供的共享纹理）

- 共享纹理模式：`gs_texture_open_shared(gc->shtex_data->tex_handle)` 直接渲染：`plugins/win-capture/game-capture.c:1597-1645`（打开在 `1606`）
- 共享内存模式（对照组，CPU 拷贝）：`game-capture.c:1560-1595`
- 能力探测（不支持共享纹理时降级）：`game-capture.c:804`
- 其他 hook 也走同一套"D3D11 共享纹理 + 句柄上报"：
  - D3D11 应用：`plugins/win-capture/graphics-hook/d3d11-capture.cpp:98`
  - D3D12 应用（经 D3D11 共享纹理中转）：`plugins/win-capture/graphics-hook/d3d12-capture.cpp:91`
  - D3D9 应用（经 D3D11 共享纹理中转）：`plugins/win-capture/graphics-hook/d3d9-capture.cpp:188`
  - DXGI 抓帧：`plugins/win-capture/graphics-hook/dxgi-capture.cpp`

### 4.3 接收侧：macOS（IOSurface）

- OpenGL（`CGLTexImageIOSurface2D`，纹理直接指向 surface 内存）：
  - `libobs-opengl/gl-cocoa.m:466-544`（`IOSurfaceGetPixelFormat` 在 `471`，`CGLTexImageIOSurface2D` 在 `530`）
  - 重新绑定新 surface（复用纹理对象，不重建）：`gl-cocoa.m:572-615`
  - 由 mach port 反查 IOSurface：`gl-cocoa.m:561-562`
  - 能力查询：`gl-cocoa.m:567`
- Metal（`MTLTexture` 绑定 IOSurface）：
  - `libobs-metal/MetalTexture.swift:52-70`（`bindSurface`）
  - `libobs-metal/MetalTexture.swift:100-115`（从 IOSurface 构造）、`162-175`（`rebind`）
  - 导出：`libobs-metal/metal-texture2d.swift:463-475`（`device_shared_texture_available`）、`476-487`（`device_texture_create_from_iosurface`）、`489-508`（`gs_texture_rebind_iosurface`）、`515-527`（mach port → `IOSurfaceLookupFromMachPort`）
- 捕获源使用（屏幕捕获/窗口捕获/AVCapture/Syphon）：
  - 显示器捕获：`plugins/mac-capture/mac-display-capture.m:154-168`、`382-384`
  - ScreenCaptureKit：`plugins/mac-capture/mac-sck-video-capture.m:333-347`、`mac-sck-common.m:260-313`
  - AVCapture：`plugins/mac-avcapture/plugin-main.m:158-183`、`OBSAVCapture.m:1272-1304`
  - Syphon：`plugins/mac-syphon/syphon.m:136-162`
- 输出侧（虚拟摄像头，Mach port 传递 IOSurface，避免整帧拷贝）：`plugins/mac-virtualcam/src/obs-plugin/OBSDALMachServer.mm:117-156`（`IOSurfaceCreateMachPort` 在 `141`）、`plugins/mac-virtualcam/src/dal-plugin/OBSDALMachClient.mm:106-140`

### 4.4 接收侧：Linux（DMA-BUF + 显式同步）

- EGL 导入 DMA-BUF：`libobs-opengl/gl-egl-common.c:90-175`（平面 fd/offset/pitch/modifier 属性，`eglCreateImage(EGL_LINUX_DMA_BUF_EXT)` 在 `174`）
- 把 `EGLImage` 绑成 GL 纹理（真正的零拷贝绑定）：`gl-egl-common.c:177-200`（`glEGLImageTargetTexture2DOES` 在 `191`）
- 对外封装：`gl-egl-common.c:243-260`
- 能力/修饰符查询：`gl-egl-common.c:295-410`
- 窗口系统分派（X11 EGL / Wayland EGL）：
  - `libobs-opengl/gl-x11-egl.c:535-546`（`device_texture_create_from_dmabuf`）、`548-557`（pixmap）、`559-573`（能力）、`581-632`（同步对象），函数表 `649-659`
  - `libobs-opengl/gl-wayland-egl.c:344-353`、`355-369`、`371-...`、`399-...`，函数表 `459-466`
  - 统一转调层：`libobs-opengl/gl-nix.c:146-190`
- PipeWire 采集（DMA-BUF 零拷贝 + DRM syncobj 显式同步）：
  - 缓冲区与同步对象结构：`plugins/linux-pipewire/pipewire.c:143-147`
  - 导入纹理：`pipewire.c:820`
  - 采集 fd 与时间点：`pipewire.c:796-802`
  - 渲染前等待 acquire 时间点：`pipewire.c:1334-1339`
  - 渲染后导出 release 时间点：`pipewire.c:1386-1392`
  - 协商显式同步参数：`pipewire.c:1020-1028`
  - 信号 release 时间点：`pipewire.c:781-790`
- 对照（非零拷贝）：XComposite 走 GLX pixmap：`plugins/linux-capture/xcomposite-input.c:392`

---

## 5. 编码侧：GPU 编码零拷贝（跨平台）

### 5.1 公共管线（libobs）

- 可共享编码纹理的创建（NV12/P010 + `GS_SHARED_KM_TEX`）：`libobs/obs.c:178-188`
- 初始化编码纹理池（每帧一个带句柄的 `obs_tex_frame`）：`libobs/obs-video-gpu-encode.c:231-275`（创建 `242-248`，取句柄 `253-259`）
- 出帧时加锁 / 解锁并生成句柄：
  - `gs_texture_acquire_sync`（回收纹理时重新加锁）：`libobs/obs-video.c:461-501`（在 `466`）
  - 纹理交换避免拷贝（双缓冲轮转而不 `gs_copy_texture`）：`libobs/obs-video.c:471-493`
  - 取共享句柄 + `gs_texture_release_sync(++lock_key)` 交给编码器：`libobs/obs-video.c:498-501`
- 编码线程把句柄 + `lock_key` 交给编码器（`encode_texture2`）：`libobs/obs-video-gpu-encode.c:144-168`（`tex.handle`/`tex.tex[]` 在 `156-163`），key 轮转在 `190-194`
- 编码器输入结构（Windows 才有 `handle`）：`libobs/obs-encoder.h:180-186`；接口 `encode_texture2`：`obs-encoder.h:346`

### 5.2 各后端

- **NVENC / D3D11**：按句柄缓存并 `OpenSharedResource`，`QueryInterface(IDXGIKeyedMutex)`：`plugins/obs-nvenc/nvenc-d3d11.c:173-207`；每帧 `AcquireSync → CopyResource → ReleaseSync`：`nvenc-d3d11.c:210-263`（`243-247`）
- **NVENC / OpenGL（CUDA-GL 互操作，句柄机制之外的零拷贝）**：`plugins/obs-nvenc/nvenc-opengl.c:93-144`（取 `GLuint` 在 `118-119`，`copy_tex_data` 注册 CUDA 资源在 `121`）
- **AMF（obs-ffmpeg）**：句柄缓存结构 `plugins/obs-ffmpeg/texture-amf.cpp:53-56`；`get_tex_from_handle`（`OpenSharedResource` + `IDXGIKeyedMutex`）：`426-458`；每帧 `AcquireSync`/`ReleaseSync`：`823-855`（`852`、`855`）
- **QSV / D3D11**：`plugins/obs-qsv11/common_directx11.cpp:434-459`（`OpenSharedResource` 在 `438`，`AcquireSync`/`ReleaseSync` 在 `452`/`459`）；调用点 `plugins/obs-qsv11/obs-qsv11.c:1240-1250`
- **QSV / Linux（DMA-BUF 导入）**：`plugins/obs-qsv11/common_utils_linux.cpp:140-145`
- **VAAPI / Linux（VA surface 导出为 DRM PRIME fd 再导入纹理）**：`plugins/obs-ffmpeg/obs-ffmpeg-vaapi.c:423-458`（`vaExportSurfaceHandle` 在 `429`，`gs_texture_create_from_dmabuf` 在 `444`，关闭 fd 在 `457-458`）；纹理编码入口 `obs-ffmpeg-vaapi.c:762`

---

## 6. 对照表：哪些地方不是零拷贝（便于定位优化点）

| 位置 | 行为 |
| --- | --- |
| `libobs-winrt/winrt-capture.cpp:171-175` | WGC 整帧 `CopyResource`（注释自承可用 SRV 省掉） |
| `libobs-winrt/winrt-capture.cpp:168-170` | 客户端区域 `CopySubresourceRegion` |
| `libobs-d3d11/d3d11-duplicator.cpp:234-254` | DXGI 桌面复制 `CopyResource` 到私有纹理 |
| `plugins/win-capture/graphics-hook/vulkan-capture.c:1078-1097` | Vulkan `CmdCopyImage` 到共享图像 |
| `plugins/win-capture/graphics-hook/gl-capture.c:546-594` | GL 共享内存回退路径（blit + PBO 拷贝） |
| `plugins/win-capture/game-capture.c:1560-1595` | 共享内存捕获（CPU 拷贝） |
| `libobs-opengl/gl-windows.c:602-605` | Windows 上 OpenGL 后端不具备共享纹理能力 |
| `plugins/linux-capture/xcomposite-input.c:392` | GLX pixmap 绑定（非 DMA-BUF 路径） |

---

## 7. 一句话总结

OBS 的显存零拷贝由三层构成：
1. **libobs/graphics 抽象层**统一暴露"共享句柄 / IOSurface / DMA-BUF + 同步"三套原语（`graphics.h:819-919`）；
2. **捕获层**通过注入 hook（D3D11/D3D12/D3D9/GL/Vulkan）把游戏画面写到共享纹理并把句柄交给 OBS，Vulkan 走 `VK_KHR_external_memory_win32` 直接导入 D3D11 共享句柄，Linux 走 DMA-BUF + syncobj，macOS 走 IOSurface；
3. **编码层**用带 KeyedMutex 的 NV12/P010 共享纹理把帧交给 NVENC/AMF/QSV/VAAPI，Windows 靠共享句柄 + `AcquireSync/ReleaseSync`，Linux 靠 DMA-BUF，NVENC+OpenGL 靠 CUDA-GL 互操作。

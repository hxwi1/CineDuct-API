# NT 共享句柄 → OpenGL 内存对象：分析、打样与实测

> 本文回答一个问题：**能不能不用 `WGL_NV_DX_interop`，让游戏的 OpenGL 直接采到 C++ 子进程那块显存**
> —— 目的是给 AMD 显卡提供一条不依赖 NVIDIA 血统扩展的路。
>
> 结论：**能，而且在 NVIDIA 上已实测跑通**（跨进程 899 帧逐帧校验 0 失败）。
> 复现命令见 §6；本文提到的所有数字都来自本机 RTX 5060 Ti / 驱动 616.92 / Windows 11。
> **AMD 侧尚未实测** —— §6.1 那个探测程序就是为此准备的，一次跑完给结论。

---

## 1. 结论速览

| 问题 | 结论 |
| --- | --- |
| 现在这条路（D3D11 共享纹理 → `wglDXRegisterObjectNV`）为什么在 AMD 上不好使 | 它整条建立在 **NVIDIA 血统的 `WGL_NV_DX_interop2`** 上：函数指针装载、注册、加锁三个环节全是 NV 扩展。`src/main/java/dev/mediafix/gpu/GpuContext.java:106-110` 的探测直接以"`wglDXOpenDeviceNV` 指针能不能装载"为判据 —— **非 NVIDIA 在第一步就被拒了** |
| 有没有跨厂商的替代 | 有：**`GL_EXT_memory_object_win32` + `GL_EXT_semaphore_win32`**（Khronos 扩展，AMD 从 Adrenalin 18.8.1 起就公开支持；见 §3） |
| 这条路的核心动作 | 生产者 `IDXGIResource1::CreateSharedHandle` 拿 **NT 句柄** → `DuplicateHandle` 投进游戏进程 → GL 侧 `glImportMemoryWin32HandleEXT(...)` + `glTexStorageMem2DEXT(...)` **把同一块显存别成 GL 纹理**（不再有每帧 `CopyResource`，也不再需要 D3D11 互操作设备） |
| GPU 间同步 | **共享 D3D11 fence**（`D3D11_FENCE_FLAG_SHARED`）→ GL 侧按 `GL_HANDLE_TYPE_D3D12_FENCE_EXT` 导入成信号量 → `glWaitSemaphoreEXT`/`glSignalSemaphoreEXT`（GPU 排队等，不占 CPU） |
| 实测 | ✔ 跨进程 1280×720@60 跑 899 帧、逐帧像素校验 **0 失败**、生产者侧槽位等待超时 **0**；GL 进程**完全不需要 D3D11 设备** |

---

## 2. 这个项目现在的纹理共享（逐环节 + 代码位置）

链路（老路，`dxgi-interop`）：

```
cineduct-host.exe（子进程）                        Minecraft 进程（Java + OpenGL）
 shared_tex.cpp:50-92                               GlInterop.probe()          GpuContext.java:98-141
   建 N 张 B8G8R8A8_UNORM 纹理：                     ├ 逐个适配器试 wglDXOpenDeviceNV  ← 只认 NVIDIA
   NTHANDLE | KEYEDMUTEX                             └ 拿到"GL 上下文所在适配器"的 LUID
   CreateSharedHandle(名字)  ← 命名 NT 句柄          GlInterop.importSlot()     GlInterop.java:123-164
 shared_tex.cpp:120-190                              ├ OpenSharedResourceByName(名字)
   挑槽位（EMPTY/SHOWN）→ 零超时试锁 →               ├ 建一张**本地**纹理（misc=0）
   CopyResource → ReleaseSync(1) →                   ├ CopyResource(本地, 共享)   ← 每帧一次显存内拷贝
   状态写 READY                                      └ wglDXRegisterObjectNV(本地纹理)  ← NV_DX_interop
                                                     每帧：wglDXLockObjectsNV → 采样 → Unlock
```

三个关键事实（都是实测逼出来的，不是猜的）：

1. **跨进程只能靠 `SHARED_NTHANDLE`**；`CreateSharedHandle` 之后句柄必须一直持有，提前关掉对端就 `E_INVALIDARG`。
2. **`wglDXRegisterObjectNV` 拒绝带 `SHARED_NTHANDLE` 的纹理**（`misc=0x802` 恒返回 0），
   所以才要"共享纹理只做搬运 + 拷进本地纹理再注册"这一下。
3. `GpuContext.probe()` 把 `wglDXOpenDeviceNV` 指针当成"这条路成不成立"的唯一判据 ——
   于是**在 AMD 上永远不会进入这条路**，直接降级到共享内存 / Java 引擎。

一句话：老路的每一环都能在纸上跑，但**第一环就只要 NVIDIA**。

---

## 3. OBS 是怎么做的（学到什么）

看了 `F:\mos\obs-studio-master` 的对应实现，有三条很有价值的事实：

### 3.1 OBS **从不创建** NT 句柄

`libobs-d3d11/d3d11-texture2d.cpp:85-99` 只有 `IDXGIResource::GetSharedHandle`（**老的 KMT 句柄**）；
整个 OBS 源码里 `CreateSharedHandle` **一次都没出现**。

`GS_SHARED_TEX`（`MISC_SHARED`）/ `GS_SHARED_KM_TEX`（`MISC_SHARED_KEYEDMUTEX`）都还是老一套
（`d3d11-texture2d.cpp:128-132`）。它自己的共享纹理全走 KMT。

### 3.2 OBS 只在**消费别人的** NT 句柄时用到 NT 路径

`gs_texture_open_nt_shared`（`libobs/graphics/graphics.c:3197`）→ `device_texture_open_nt_shared`
（`libobs-d3d11/d3d11-subsystem.cpp:3201`）→ `OpenSharedResource1`（`d3d11-texture2d.cpp:329-332`）。
也就是说：**"NT 句柄"在 OBS 里是接收侧的能力**（打开游戏 hook / WGC 给出的句柄），
不是它自己发布帧的手段。

### 3.3 ★ 在 Windows 上 OBS 的 OpenGL 后端**直接放弃**共享纹理

`libobs-opengl/gl-windows.c:602-605`：

```c
EXPORT bool device_shared_texture_available(void) { return false; }
```

而 `docs/gpu-zero-copy-notes.md` §6 也把这条列为"不是零拷贝"的对照项。
OBS 在 Windows 上唯一真正打通 D3D11↔GL 的地方是 **hook 侧**的
`plugins/win-capture/graphics-hook/gl-capture.c:334-394`：它建的是
**`D3D11_RESOURCE_MISC_SHARED`（KMT！）** 的纹理 + `GetSharedHandle`，
再用 `wglDXRegisterObjectNV` 注册 —— 注意它**刻意避开 NTHANDLE**，
因为这正是 NV_DX_interop 的限制。

**学到的结论**：OBS 在这个问题上**没有我们可以照抄的答案**。
它把"D3D11→GL"这件事只做在 NVIDIA 的 hook 里，Windows 上的 GL 后端干脆声明不支持。
所以要给 AMD 找出路，只能自己走 EXT_memory_object 这条 Khronos 路线。

---

## 4. 三处需要纠正的既有说法

### 4.1 "AMD 的 OpenGL 驱动不支持 D3D 互操作" —— 不准确

`native/README.md:55-56` 是这么写的。但 AMD **Adrenalin 18.8.1** 的 GL-Z 实测扩展清单里
**同时**有：

```
WGL_NV_DX_interop
WGL_NV_DX_interop2
GL_EXT_memory_object
GL_EXT_memory_object_win32
GL_EXT_semaphore
GL_EXT_semaphore_win32
```

见 [Geeks3D: AMD Adrenalin 18.8.1 新增 OpenGL/Vulkan 扩展](https://geeks3d.com/20180807/amd-adrenalin-18-8-1-graphics-drivers-released-new-opengl-and-vulkan-extensions-added/)。
所以准确的说法是：**AMD 公开声称支持，实现质量/边界条件另说** ——
而"实现不理想"恰恰是我们这次要绕开它的理由，不是"没有这个扩展"。

### 4.2 `GL_HANDLE_TYPE_OPAQUE_WIN32_NT_EXT` 这个枚举**不存在**

`EXT_memory_object_win32` 里与 D3D11 纹理对应的值是：

| 枚举 | 值 | 用在哪 |
| --- | --- | --- |
| `GL_HANDLE_TYPE_D3D11_IMAGE_EXT` | `0x958B` | **NT 句柄**（`CreateSharedHandle`）← 我们要用的 |
| `GL_HANDLE_TYPE_D3D11_IMAGE_KMT_EXT` | `0x958C` | 老的 `GetSharedHandle`（KMT） |
| `GL_HANDLE_TYPE_OPAQUE_WIN32_EXT` | `0x9587` | Vulkan `VK_KHR_external_memory_win32` 导出的 NT 句柄 |
| `GL_HANDLE_TYPE_D3D12_FENCE_EXT` | `0x9594` | **信号量**侧：导入共享 fence |

本机实测（NVIDIA 616.92）：只有 `0x958B` 能接受我们的 D3D11 NT 句柄；
`0x958C` 与 `0x9587` 都返回 **`GL_OUT_OF_MEMORY (0x505)`**。
★ 注意这个错误码很反直觉：**驱动不支持某个 handleType 时报的是"显存不足"**，
别据此以为显存不够（`tools/mo-probe/.../MoProbe.java` 里专门有一张错误码翻译表）。

### 4.3 `WGL_EXT_memory_object_win32` **不是**必需的

本机 WGL 扩展串里**没有** `WGL_EXT_memory_object` / `WGL_EXT_memory_object_win32`
（只有 `GL_` 那两个），但功能完全正常。所以探测时**不能**拿 WGL 串当判据。

### 4.4 另外两条实测出来的坑

- **`size` 必须传 0**（对 D3D11 image 句柄）。传 `w*h*4` 会 `GL_OUT_OF_MEMORY`。
- **不能对"别名了 D3D11 显存的 GL 纹理"调用 `glGetTexImage`** —— 实测**直接把进程/驱动挂死**
  （不是报错，是卡住）。要看内容只能"画进 FBO 再 `glReadPixels`"。

---

## 5. 新路线的设计（打样已实现的那一套）

### 5.1 句柄投递（用户要求的 `DuplicateHandle`）

```
生产者（子进程）                                     消费者（游戏进程 / GL）
 ① 建纹理：BGRA8 + NTHANDLE|KEYEDMUTEX
    CreateSharedHandle(无名) → NT 句柄（一直持有）
 ② 建共享 fence：ID3D11Device5::CreateFence(SHARED)
    ID3D11Fence::CreateSharedHandle(...)

 ③ 等消费者报到（共享状态块里写了自己的 pid）─┐
                                             │  ④ OpenProcess(PROCESS_DUP_HANDLE, pid)
                                             │     DuplicateHandle(每个纹理句柄 + fence 句柄)
                                             └─→ 把"消费者进程里的句柄值"写回状态块 → dupReady=1
                                                                          │
                                                     ⑤ 拿句柄值直接 glImport*（不需要 OpenProcess 权限之外的东西）
```

- 打样阶段把"报到 / 句柄值"放在**既有的共享状态块**里（`cineduct_spike_status.h` v7 追加字段），
  因为打样还没有控制管道；生产环境直接走管道（Java 侧本来就知道子进程 pid，少一次握手）。
- 句柄在消费者进程里是**独立有效**的，生产者关掉自己那份也不影响。

### 5.2 GPU 间同步（用户要求的 `GL_EXT_semaphore_win32`）

一条共享 fence 上跑单调时间线，两侧各推一个值：

```
生产者：写内容 → Signal(fence, 2k+1) → 槽位标 READY 并带上 2k+1
消费者：glSemaphoreParameterui64(GL_D3D12_FENCE_VALUE_EXT, 2k+1)
        → glWaitSemaphoreEXT(把该纹理列成 barrier)   ← GPU 侧等
        → 采样
        → glSignalSemaphoreEXT(2k+2) → 槽位标 SHOWN 并写回 2k+2
生产者：复用这个槽之前，只做**有界**探测（GetCompletedValue + 最多 2ms）
```

★ 生产者**绝不排一个"对端可能已经死了、永远不会满足"的 GPU Wait**：
`ID3D11DeviceContext4::Wait` 一旦排进队列，值不来 GPU 队列就永久挂住，
比"偶尔撕裂"严重得多（这是本项目反复踩过的同一类坑：**发帧进度不能依赖读方**）。
所以策略是：**能确认就等，确认不了也照发**，并把超时次数记进诊断字段
（实测 12 秒 718 帧：**超时 0 次**）。

### 5.3 格式严格对应

| 侧 | 格式 | 说明 |
| --- | --- | --- |
| D3D11 | `DXGI_FORMAT_B8G8R8A8_UNORM` | 视频处理器（`ID3D11VideoProcessor`）的原生输出 |
| GL | `GL_RGBA8`（`glTexStorageMem2DEXT` 的 internalFormat） | 承载同一块显存；字节序是 B,G,R,A |
| 采样 | `GL_TEXTURE_SWIZZLE_RGBA = (B,G,R,A)` | 把 R/B 对调，于是 GL 的 R 就是 D3D 的 R |

同一块显存被 GL 当 RGBA8 读时，通道顺序天然是错位的（R↔B），
所以"严格对应"要靠这个 swizzle 补上 —— 这是**实测出来**的（`MoProbe` 会同时打印加/不加 swizzle 的读数）。

另外：**上下翻转与老路一样需要**。实测 `GL 的 v=0 对着 D3D 图像顶部`，
所以渲染时 `vUv.y = 1 - y`。

---

## 6. 怎么跑（NVIDIA 上已验证；AMD 上请照这个跑）

### 6.1 第一步：能力探测（一次跑完给结论，不需要游戏、不需要第二个进程）

```powershell
.\native\probe-interop.ps1
```

它会打印：GL/WGL 扩展全清单、`GL_DEVICE_LUID_EXT`、适配器匹配、
**三个 handleType × 三种 size 的实际导入结果**、以及"导入后采到的像素是不是对"。
把这个输出整段贴回来就能判断要不要切路线。

### 6.2 第二步：跨进程零拷贝打样

```powershell
.\native\run-mo-spike.ps1 -Seconds 20          # 内存对象路线（新）
.\native\run-spike.ps1   -Seconds 20          # NV_DX_interop 路线（老，对照）
```

`run-mo-spike.ps1` 的 PASS 判据：消费 ≥30 帧、**校验失败 0**、不同帧号 ≥10。

---

## 7. 本机实测结果（RTX 5060 Ti / 616.92 / Win11）

### 7.1 能力探测（`probe-interop.ps1`）

| 项 | 结果 |
| --- | --- |
| `GL_EXT_memory_object` / `_win32` / `GL_EXT_semaphore` / `_win32` | ✔ 全在（另有 `GL_EXT_win32_keyed_mutex`、`GL_NVX_progress_fence`） |
| `WGL_EXT_memory_object_win32` | ✘ 不在（**但功能正常** → 探测不能拿它当判据） |
| `GL_DEVICE_LUID_EXT` | `00000000:0000b9b9` —— 与 DXGI 适配器一致，**拿 LUID 选卡比"逐个试 `wglDXOpenDeviceNV`"正规得多** |
| `glImportMemoryWin32HandleEXT(0x958B, size=0)` | ✔ 成功 |
| `0x958C` / `0x9587` | ✘ `GL_OUT_OF_MEMORY(0x505)` |
| 采样校验 | ✔ 加 swizzle 读到 `R=5A G=33 B=A5`（与写入完全一致）；不加 swizzle 读到 `R=A5 G=33 B=5A` |
| 方向 | GL 的 `v=0` 对着 D3D 图像顶部 ⇒ 上屏要翻转 |
| 共享 D3D11 fence → GL 信号量 | ✔ 导入成功；D3D `Signal(7)` → GL 等到 7；GL signal 11 → D3D 侧 `GetCompletedValue()==11`（**双向都通**） |

### 7.2 跨进程 A/B（1280×720 / 3 槽 / 60fps / 15 秒，同一台机器、同一个生产者 exe）

| | 老路 `NV_DX_interop` | 新路 `NT 句柄 + 内存对象` |
| --- | --- | --- |
| GL 侧消费帧数 | 888（另有 13304 次"渲染无新帧"轮次） | **899**（59.9 fps，空转 1942） |
| 每帧 GPU 内拷贝 | **有**（`CopyResource` 共享→本地，888 次） | **没有**（GL 直接采样生产者的纹理） |
| 跨 API 锁 | `wglDXLockObjectsNV` 每帧 avg 0.051ms / max 1.36ms | 无（改用 fence，写完/读完各推一个值） |
| 帧号校验 | 888 次变化、超前异常 0 | **899 帧逐帧校验失败 0** |
| 生产者侧同步等待超时 | —（键控互斥零超时试锁，丢帧 0） | **0**（有界探测，从不排死等） |
| GL 进程需要 D3D11 设备吗 | **需要**（还要建 D3D11 设备 + 逐个适配器试 `wglDXOpenDeviceNV`） | **不需要**（只要一个句柄值 + GL 扩展） |
| 依赖的厂商扩展 | `WGL_NV_DX_interop2`（NVIDIA 血统） | `GL_EXT_memory_object_win32` + `GL_EXT_semaphore_win32`（Khronos） |

### 7.3 调试过程中踩到的坑（都已写进代码注释）

1. **core profile 下没绑 VAO ⇒ `glDrawArrays` 静默什么都不画**（只报 `GL_INVALID_OPERATION`）。
   第一次跑"回读全是 0"，差点被误判成"共享显存读不到" ——
   现在两个打样程序里都留了**夹具自检**（先拿一张普通纹理走同一条采样路，证明夹具本身没问题）。
2. **`glGetTexImage` 读别名纹理 → 挂死**（见 §4.4）。
3. `GL_TEXTURE_SWIZZLE_RGBA` **只能**配 `glTexParameteriv`（一次给 4 个分量），
   当 `glTexParameteri` 的 pname 用是无效枚举。
4. swizzle 是**纹理对象状态**，会残留；每次采样都要显式设一遍，不能"设一次、别处还原"。
5. `glReadPixels(..., GL_UNSIGNED_BYTE, IntBuffer)` 会把 4 个通道当成一个小端 int
   —— 必须用 `ByteBuffer` 按字节读。
6. 改 `native/*.ps1` 后**必须保住 UTF-8 BOM**：PowerShell 5.1 会把无 BOM 的脚本按 GB2312 读坏
   （本机真的踩了一次，整个 `build.ps1` 解析失败）。

---

## 8. 接入生产的建议（还没做，下一步）

改动面很小，而且**可以和老路并存**（探测不到就退回老路，老路不需要动）：

| 文件 | 改动 |
| --- | --- |
| `native/host/src/shared_tex.cpp` | 加一个共享 fence（`D3D11_FENCE_FLAG_SHARED`）+ 每帧 `Signal`；`DuplicateHandle` 服务（生产环境 pid 由 Java 传、句柄值走控制管道回执，不必放状态块） |
| `native/glshim/src/cineduct_glshim.cpp` | 已经加好了：`mf_gpu_create_shared_texture` / `mf_gpu_fill_texture` / `mf_gpu_acquire_sync` / `mf_gpu_release_sync` / `mf_gpu_create_shared_fence` / `mf_gpu_open_shared_fence` / `mf_gpu_fence_signal` / `mf_gpu_fence_wait` / `mf_gpu_fence_completed` / `mf_gpu_fence_wait_cpu` / `mf_gpu_close_handle` |
| `src/main/java/dev/mediafix/gpu/NativeD3D.java` | 对应绑定已加好（另外修掉了 `MISC_SHARED`/`KEYEDMUTEX` 两个写错的常量：真值是 `0x2`/`0x100`，不是 `0x1`/`0x2`） |
| **新** `src/main/java/dev/mediafix/gpu/GlMemoryObjectInterop.java` | 从 `tools/mo-probe/.../MoSpikeConsumer.java` 抽出来：`probe()`（判 `GL_EXT_memory_object_win32` + `GL_EXT_semaphore_win32`，用 `GL_DEVICE_LUID_EXT` 选卡）→ `importSlot(handle, w, h)` → `beginSample(slot)` / `endSample(slot)` |
| `src/main/java/dev/mediafix/gpu/GpuContext.java` | 探测顺序改成：① 内存对象路线（跨厂商）② `NV_DX_interop`（老路）③ 降级；理由文案同步改 |
| `src/main/java/dev/mediafix/gpu/GlInterop.java` | 保持不动，作为 NVIDIA 上的老路兜底；`FrameTransport` 抽象（`docs/PLAN-standalone-gpu.md` §3.5）正好能挂两个实现 |
| `native/README.md` | 改掉 §55-56 那句"AMD 不支持 D3D 互操作"，指向本文 |

风险与注意：

- **AMD 侧是否真的支持 `GL_HANDLE_TYPE_D3D11_IMAGE_EXT` 尚未实测** ——
  这正是要请在 AMD 机器上跑 §6.1 的原因。若 AMD 只支持 D3D12 句柄而不支持 D3D11 image 句柄，
  备选是把生产者改成"D3D11 解码 → 拷进一张 D3D12 共享纹理"（成本高，且仍然要一张中转纹理）。
- `GL_EXT_semaphore_win32` 的 fence 导入需要 `ID3D11Device5`（Win10 1703+）与较新驱动；
  老系统上要能退回"键控互斥 + 只做 CPU 边界"的降级档。
- 生产者侧那个 `signalFence` 的**单调值空间**要跟 `loop`/`seek` 无关地一直递增
  （打样里的 `fenceSeq += 2` 就是这个意思）；不要按槽位各自计数。
- 多屏共享同一会话时，槽位是被多个消费者读的：`slotConsumerDone` 要取"所有消费者的最小值"
  再决定能不能复用（打样只有单消费者，生产要考虑这点）。

---

## 9. 复现用料（本次新增/改动的文件）

```
native/glshim/include/cineduct_glshim.h          + NT 句柄/fence/键控互斥原语声明
native/glshim/src/cineduct_glshim.cpp            + 实现与 JNI 包装
src/main/java/dev/mediafix/gpu/NativeD3D.java    + 绑定；修正 MISC_* 常量
native/spike/shared/cineduct_spike_status.h      v7：句柄直投 + 每槽 fence 值（只追加，旧偏移没动）
native/spike/host/main.cpp                       + --mo（共享 fence + DuplicateHandle 服务）/ --cpu-pattern
tools/mo-probe/src/dev/mediafix/probe/MoProbe.java         新增：自包含能力探测（同进程）
tools/mo-probe/src/dev/mediafix/probe/MoSpikeConsumer.java 新增：跨进程内存对象消费者
native/probe-interop.ps1                        新增：一键探测
native/run-mo-spike.ps1                         新增：一键跨进程打样
native/build.ps1                                把两个新 Java 文件加进编译列表
```

`--cpu-pattern` 是诊断开关：它用 CPU（`UpdateSubresource`）往纹理右下角戳一个 8×8 角标，
用来区分"CPU 写进去的也看不见"和"只有 GPU 渲染的内容看不见"。
（注意 8×8 角标在 64×64 的降采样网格里小于一个像素，正常看不出，别拿它当校验）

# CineDuct 独立化 + C++ 子进程共享显存 —— 实施计划

> 状态：**计划已定稿，待开工**
> 本文是跨轮次的实施基准。改动方向与本文冲突时，先改本文再改代码。

---

## 0. 已确认的决策

| # | 决策项 | 结论 | 影响 |
|---|---|---|---|
| 1 | 显示形态 | **只做屏幕**；**实体渲染**（不是方块）；**纯指令操控**（无 GUI、无物品、无交互界面）；**开放 API** 给附属 mod | P0 砍掉方块/BE/GUI/遥控器一整块；新增「稳定 API 面」与「实体 + 渲染器 + 服务端权威状态」 |
| 2 | 音频归属 | **搬进 C++ 子进程，用 WASAPI** | 音画时钟同进程，A/V 同步最稳；支持多声道/独占，高于现在 Java Sound 的立体声天花板 |
| 3 | 降级策略 | **保留 Java 侧进程内 FFmpeg 引擎作兜底** | 双引擎共存：零拷贝优先 → 共享内存回传 → Java 进程内引擎；玩家任何情况下都有画面 |
| 4 | 多人同步 | **要服务端权威进度 + 客户端对齐** | 现存 `SeekGuard`/`TimePacket`/`DisplayTileTimeSync` 一整套补丁全部删除（它们只是给水帧的 bug 打的补丁） |
| 5 | 平台 | **先只做 Windows**；要**考虑多显卡**；后续计划 Linux，架构必须留口 | 帧传输层做成可替换的平台实现（Windows: DXGI + WGL_NV_DX_interop2；Linux 预留: Vulkan + DMA-BUF/EGL） |

### 已实测的环境事实（决定方案可行性）

| 项 | 实测值 | 意义 |
|---|---|---|
| GPU / 驱动 | RTX 5060 Ti（Blackwell），驱动 616.64 | `WGL_NV_DX_interop2` 可用 → 零拷贝路线成立；**Blackwell 有 AV1 硬解**，`avoidAv1` 默认值应改 |
| CPU | i5-13400F（**无核显**） | 只可能渲染在 NVIDIA 上，适配器歧义小 |
| 其它适配器 | MuMu / GameViewer 虚拟显示器适配器存在 | 适配器枚举必须**按 LUID 精确匹配 GL 上下文所在适配器**，不能取"第一个" |
| LWJGL | MC 1.21.1 = lwjgl-opengl **3.3.3**，含 `org.lwjgl.opengl.WGLNVDXInterop` 全部 8 个函数 | GL 侧不需要自己写 WGL 绑定 |
| 工具链 | VS 2026 Build Tools (v18)：MSVC x64 + 自带 CMake + Ninja；`cmake` 不在 PATH | Gradle 里用 vswhere 定位，或固定用 VS 自带 CMake |

---

## 1. 目标与非目标

**目标**
- 彻底摆脱 waterframes / watermedia / bilibili_media 的编译期与运行期依赖（Mixin 全部删除）。
- 画面/声音由独立 C++ 子进程产出，**经共享显存零拷贝进入游戏的 OpenGL 纹理**。
- 屏幕是一等公民的**实体**，一切操作走指令；同时暴露**稳定的 Java API**，让附属 mod 能创建/控制/扩展屏幕与解析器。
- 多人场景下进度由服务端权威决定。

**非目标（当前阶段）**
- 不做方块形态、不做 GUI 配置界面、不做遥控器物品、不做播放列表（都可由附属 mod 通过 API 实现）。
- 不做 Linux 实现（但架构必须能挂上去）。
- 不做视频下载/落盘（合规红线，延续现状）。

---

## 2. 架构总览

```
┌─ Minecraft 进程（Java / OpenGL）────────────────────────────────────┐
│  api/            稳定公开面：CineDuctApi / ScreenHandle / Resolver SPI │
│  entity/         屏幕实体 + 渲染器 + 服务端权威状态 + NBT 持久化        │
│  command/        /cineduct screen ...（全部操控入口）                  │
│  bili/           链接识别 / wbi 解析 / 扫码登录 / 候选流 / ABR 档位     │
│  host/           MediaHostClient：子进程生命周期 + IPC + 状态镜像       │
│  gpu/            GpuInterop(JNI shim) + FrameTransport 平台实现        │
│  engine/         【兜底】现有进程内 FFmpeg 引擎（保留）                 │
└──────────────┬────────────────────────────────┬────────────────────┘
               │ 控制管道（可靠、带 ACK）         │ 共享内存状态环（位置/统计/槽位就绪）
               │ 共享纹理 NT 句柄（子进程投递）    │
┌──────────────┴────────────────────────────────┴────────────────────┐
│ cineduct-host.exe（C++20，单进程多会话）                              │
│  会话 = 1 条媒体：                                                    │
│    FFmpeg(d3d11va 硬解) → D3D11 VideoProcessor(NV12/P010→BGRA8+缩放)  │
│      → 3~4 张 DXGI 共享纹理轮转（NT handle + keyed mutex）            │
│    + WASAPI 音频输出 + 自有时钟（音频可听位置为主时钟）                 │
│  会话内自带：状态机 / 看门狗 / ABR / 直链续期 / 直播断流重连           │
│  同一 URL 多块屏幕 = 1 个会话 + N 个消费者（显存与解码不翻倍）          │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 3. 帧传输层（核心风险所在）

### 3.1 零拷贝路径（Windows，主路径）

> ⚠️ **本节已按 P1 打样实测修正**（原假设被推翻，见下面的「P1 实测结论」）。

机制：

1. 子进程创建 N 张 `ID3D11Texture2D` 作为**搬运槽**，flags = `D3D11_RESOURCE_MISC_SHARED_NTHANDLE | D3D11_RESOURCE_MISC_SHARED_KEYEDMUTEX`，格式 **BGRA8**（HDR 时用 `R10G10B10A2_UNORM`，或子进程 tonemap），`mipLevels=1`、`sampleCount=1`，bind = `RENDER_TARGET | SHADER_RESOURCE`。
2. 子进程 `IDXGIResource1::CreateSharedHandle` 拿到 NT 句柄（**句柄必须一直持有**，关掉名字就失效），再用 `DuplicateHandle` 把它**投递进游戏进程**（子进程启动时就知道游戏 pid，用 `OpenProcess(PROCESS_DUP_HANDLE)`），然后把"游戏进程侧句柄值"经 IPC 告知 Java。打样阶段暂时用**命名句柄** + `OpenSharedResourceByName` 简化验证。
3. 游戏进程：`D3D11CreateDevice`（同 LUID 适配器，带 `BGRA_SUPPORT`）→ `OpenSharedResource1(句柄)` / `OpenSharedResourceByName` → 得到本进程的共享纹理。
   → 设备创建与共享资源打开需要 **JNI shim DLL**（`cineduct-glshim`），因为 LWJGL 根本不绑定 D3D11。
4. **游戏进程再建一张本地纹理**（`MiscFlags=0`），逐帧在键控互斥保护下 `CopyResource(本地, 共享)`；GL 用 `wglDXRegisterObjectNV` 注册的是**这张本地纹理**。
   → **这是被实测逼出来的**：`wglDXRegisterObjectNV` 拒绝 NTHANDLE 纹理，而跨进程共享又只能靠 NTHANDLE。显存内一次 `CopyResource` 在 4K60 下只有 ~2GB/s，对 400+GB/s 的显存带宽是噪声（实测 4K60 零失败）。
5. 每帧 GL 侧：`wglDXLockObjectsNV` → 采样/绘制 → `wglDXUnlockObjectsNV`。**锁是采样的必要条件**（实测不加锁直接画会读到垃圾），跨 API 的可见性也由它保证。不做任何 CPU 拷贝。

**为什么必须 BGRA8**：`NV12`/`P010` 不能被 NV_DX_interop 导入，GL 也没有对应的单张纹理格式。所以子进程要在 GPU 上完成 YUV→RGB（`ID3D11VideoProcessor` 最省事，且顺带做缩放）。

#### P1 实测结论（RTX 5060 Ti / 驱动 616.64 / Windows 11）

| 结论 | 证据 |
|---|---|
| `WGL_NV_DX_interop` 与 `_interop2` 均可用，函数指针可装载 | `GL.getCapabilitiesWGL().WGL_NV_DX_interop2 == true`，`wglDXOpenDeviceNV` 返回非 0 |
| **适配器不该靠名字猜**：逐个适配器建设备试 `wglDXOpenDeviceNV`，成功者即 GL 上下文所在适配器 | 本机 4 个适配器（3 个同名 NVIDIA LUID + 1 个软件）中被正确选中 `0000c52a` |
| 跨进程共享只能靠 NTHANDLE；且 `CreateSharedHandle` 后**不能立刻 CloseHandle** | 提前关句柄 → 对端 `OpenSharedResourceByName` 返回 `E_INVALIDARG (0x80070057)` |
| ★ **`wglDXRegisterObjectNV` 拒绝 NTHANDLE 纹理** | 标志矩阵：`misc=0x0` ✔、`0x1`(SHARED) ✔、`0x2`(KEYEDMUTEX) ✔、`0x3` ✔、**`0x802`(NTHANDLE\|KEYEDMUTEX) ✘**；`access` 用 ro/rw 都一样 ✘ |
| 因此帧的落地方式是「共享纹理跨进程搬运 + 游戏进程 GPU→GPU 拷本地纹理」 | 4K60 跑 45s：拷贝 2681 帧，拷贝失败 0，锁失败 0 |
| **GL 采样互操作纹理必须处在 `wglDXLockObjectsNV` 保护下** | 不加锁直接画：回读像素 B 通道 100% 异常（18060/18060）；加锁后 0 异常 |
| 同步协议（槽位 4 态 + 两侧 key=0 键控互斥）足够，且不阻塞 | 生产者 60fps 发布 0 丢弃；加锁等待 avg 0.051ms / max 13.9ms |
| D3D→GL 画面**天生上下翻转**（D3D 第 0 行在内存顶部，GL 当 v=0） | 采样时 `vUv.y = 1 - y` 修正 |
| 端到端正确性可以全自动校验，不需要人眼 | 生产者把帧号编码进 R/G、B 固定 0xA5；消费者用 128×128 FBO 回读角落像素解码比对 |

复现方式：`native\build.ps1` 然后 `native\run-spike.ps1 -Seconds 30`（详见 `native/README.md`）。


### 3.2 共享纹理尺寸 ≠ 解码分辨率（省钱关键）

4K 解码 + VideoProcessor 缩放到 1080p/720p 写进共享纹理：槽位 33MB → 8MB，缩放由 GPU 免费完成。屏幕实体在世界里占的像素通常远小于 4K，所以**按显示需要定共享纹理尺寸**，多屏显存因此可控。

### 3.3 非阻塞同步（规避 `wglDXLockObjectsNV` 无超时）

`wglDXLockObjectsNV` 没有 timeout 参数，锁会阻塞渲染线程。做法：

- 每槽 4 态：`EMPTY(0) → WRITING(1) → READY(2) → SHOWN(3)`，状态写在共享内存里（生产者写 0/1/2，消费者写 3）。
- **槽位状态机是唯一的正确性保证**（不是键控互斥，见下）：
  - 写方只在 `EMPTY`/`SHOWN` 槽上写，读方只碰 `READY` 槽，读方**拷完之后**才标 `SHOWN`
  - ⇒ 两者不可能同时接触同一个槽（写方看到 SHOWN 时，读方的拷贝必然已结束）
- 子进程的 D3D 侧：**零超时试锁**（两把钥匙各试一次，都失败就丢掉这一帧），绝不为了等锁而阻塞生产。
- 游戏进程的 D3D 侧：同样零超时试锁；失败就复用上一张本地纹理。
- GL 侧渲染每个采样帧都要 `wglDXLockObjectsNV`（实测必需）；拿不到新帧就复用上一张**本地**纹理，但锁照加。
- 实测：加锁等待 avg 0.05ms、max 3.5ms、丢帧 0、失败 0。

**★ 键控互斥不能当跨进程互斥用**（P1 实测，两条坑都踩过）：

| 方案 | 结果 |
|---|---|
| 两侧同 key（都用 0） | `AcquireSync` **同 key 直接成功**（递归语义）⇒ 完全没有互斥 |
| 严格乒乓：写 `Acquire(0)/Release(1)`、读 `Acquire(1)/Release(0)` | 标签式互斥：任一方缺席或慢一拍，接力棒**永久断掉** —— 实测生产者发布 4 帧后彻底卡死，丢 2644 帧 |
| ✅ 零超时试锁（两把钥匙各试一次）+ 状态机兜底 | 生产者加锁延迟 0.218ms、丢帧 0；「最长无新帧」从 183ms 降到 33ms |

结论：**键控互斥退化为"GPU 顺序提示 + 尽力而为"，正确性由状态机承担**。另外 `WAIT_ABANDONED` 必须当"已获得"处理（对端持锁崩溃时锁归我们），否则存活方会永远等下去。

### 3.4 兜底路径（共享内存 + `glTexSubImage2D`）

子进程把 BGRA（或 NV12 + 着色器转换）写进命名共享内存段，Java 侧 `glTexSubImage2D` 上传。代价是每帧一次内存拷贝（1080p60 ≈ 0.5GB/s，可行；4K60 ≈ 2GB/s，偏重，可用 PBO/分块缓解）。保留崩溃隔离与解码下沉的全部好处，且不需要 shim DLL 和厂商扩展。

### 3.5 平台抽象（为 Linux 预留）

```java
public interface FrameTransport {          // 由能力探测选择实现
    Capabilities probe();
    FrameSlot acquire(long generationHint); // 只返回"可安全采样"的槽
    void release(FrameSlot slot);
    void close();
}
```
- Windows：`DxgiInteropTransport`（本节 3.1）
- Linux（未来）：`VulkanDmaBufTransport`（子进程 Vulkan/VAAPI 解码 → DMA-BUF → `EGL_EXT_image_dma_buf_import` + `GL_EXT_EGL_image_storage` → GL 纹理）
- 决策一律走 `probe()`，**不可用时自动降级**，上层渲染代码对所有实现一视同仁。

### 3.6 多显卡

- **由游戏进程决定适配器**：枚举 DXGI 适配器（跳过 `SOFTWARE`/`REMOTE`），逐个建设备试 `wglDXOpenDeviceNV`，**返回非 0 的那个就是 GL 上下文所在适配器** —— 这比拿显卡名字匹配 `GL_RENDERER` 可靠（本机就有 3 个同名 NVIDIA LUID，名字完全无法区分）。
- 启动子进程时把该 LUID 传进去（`--adapter-luid <high>:<low>`），**子进程必须在该适配器上建 D3D11 设备**——否则 `wglDXOpenDeviceNV` 直接失败（跨适配器不可能互通）。
- `probe()` 失败或 LUID 不匹配 → 立刻降级到共享内存路径，并在日志与 `/cineduct gpu` 里说明原因。

---

## 4. 子进程设计

### 4.1 进程与会话
- 一个游戏实例**一个** `cineduct-host.exe`，内部多会话（线程：每会话视频解码 1 + 音频 1 + 呈现 1；共享 1 个 D3D11 device，每会话自己的 immediate context）。
- 会话按 `(url, 输出尺寸, 音轨)` 归一化：**同 URL 的 N 块屏幕共用一个会话**，只增加消费者计数。
- 由 Java 用 `ProcessBuilder` 启动，`CREATE_NO_WINDOW`，并用 **Job Object（KILL_ON_JOB_CLOSE）** 保证游戏退出时子进程必死。

### 4.2 控制协议（命名管道，可靠 + ACK）
- 管道名 `\\.\pipe\cineduct-<随机token>`，token 由 Java 生成并经命令行/env 传入（防其它本地进程顶替）。
- 帧格式：`u32 length | u16 type | u32 reqId | payload`（控制面低频，payload 用 JSON；高频数据一律不经过管道）。
- 命令集：`hello`（版本握手）`open` `close` `play` `pause` `seek` `setVolume` `setOutput`（尺寸/格式/槽数）`swapSource`（直链续期/直播重连由 Java 下发新链）`setQuality`（ABR 档位/上限）`stats` `shutdown`。
- 事件（子进程→Java，同一管道）：`state` `error` `ended` `resolutionChanged` `liveReconnect` `sessionClosed` `bye`。

### 4.3 共享内存状态环
- 段名 `cineduct-<token>`：头部（magic / 协议版本 / 会话表）+ 每会话状态块（positionMs、durationMs、state、bufferedBytes、droppedFrames、clockDriftUs、liveLatencyMs、解码/呈现计数）+ 每槽状态块（state、generation、ptsMs）。
- Java 每渲染帧只读这个段（无系统调用、无 IPC），**不产生每帧 IPC 流量**。

### 4.4 崩溃与恢复（已实现）

- 子进程异常退出 → Java 侧检测 → **自动重启并从原位置续播**；连续失败 N 次 → 停止重启，
  降级到 Java 进程内引擎，并在日志/指令回执里说明原因。
- 实现要点（`MediaSession.checkHostHealth/scheduleRecover/doRecover`）：
  - **检测**：客户端 tick 每帧检查（很便宜：几个 atomic + 一次 `isAlive`）。判据三种
    ① 状态环拿到之前进程就死了（`State.FAILED`）；② 进程已退出（`!alive()`）；
    ③ **卡死**：不暂停、未到片尾，却连续 8 秒没有新帧（发布计数不动）。
  - **重启在后台线程做**：关掉一个僵死进程最坏要等几秒（`process.waitFor` 超时再强杀），
    绝不能堵在渲染线程上。渲染线程只负责在下一帧释放/重建互操作对象（`needsGlReset`）。
  - **续播**：重启前记下当前媒体位置，新进程就绪后补推 位置/音量/暂停/循环 四样状态
    （`pushStateToHost`）。为什么必须补推：`setVolumePercent/setMuted` 做了去重，
    但那只对同一个进程有意义 —— 新进程手里是默认值，不补推会出现"重启后音量突然变满"。
  - **限流**：60 秒窗口内最多重启 3 次，超了落回进程内引擎并**不再回头**（避免崩溃-重启抽搐）；
    重启后连续健康 60 秒则把计数清零。
  - **片尾不能算崩溃**：子进程到片尾后**不再退出**（保持最后一帧待命，见状态环 `ended` 字段），
    否则客户端会把"放完"当成"崩溃"而无限重播。测试形态（`--seconds`/无共享纹理）保持"放完就退"。
  - **顺手补齐的两件事**：① 循环播放打通到子进程（`loop 1/0` 可在运行期改，回卷时把媒体时钟
    重新锚到 0 —— 不重锚会让第二遍以解码速度狂发，实测 460fps）；② 片尾后 seek 会先把音频链
    重新拉起（音频链在片尾就退出了，不拉起则时钟永久冻结，画面只能靠 3 秒兜底慢慢挪）。
  - **验证脚本**：`native/eof-test.ps1`（片尾存活 / ended 上报 / 片尾后 seek / 循环回卷与节奏）。
    游戏内验证：`/cineduct status` 会打印子进程 pid 与重启计数，直接
    `taskkill /f /pid <pid>` 即可观察自动恢复。

---

## 5. Mod 侧设计

### 5.1 屏幕实体与服务端权威状态
- `ScreenEntity`：位置/朝向/尺寸（宽高，支持非 16:9）、音量与距离衰减范围（min/max distance）、可见性。
- 权威播放状态存服务端实体 NBT：`{ source, positionMsAtAnchor, anchorGameTime, paused, loop, volume, rangeMin, rangeMax, qualityCap }`。
- 客户端目标位置 = `paused ? positionMsAtAnchor : positionMsAtAnchor + (serverGameTime - anchorGameTime) * 50`。
- 客户端实体在渲染帧拉取对应 `FrameTransport` 的 GL 纹理绘制四边形（保留现有信箱化能力：非 16:9 屏幕居中不拉伸）。
- 音频由子进程输出，服务端/客户端只下发增益与距离衰减结果（乘算后发送，避免每 tick 重算）。

**进度模型（2026-09 改成"真实时间"，之前是游戏刻）**：

权威位置以前按**游戏刻**推进（`base + (gameTime - anchorTick) * 50`）。问题很致命：**媒体时间是真实时间，
而游戏刻随 TPS 走** —— 服务端 TPS 一低（例如 10 TPS），权威位置就以半速前进，本地播放却是实时的，
于是客户端每隔一会儿发现"我跑太快了"，就 seek 回去，看起来就是**画面和声音一直被往回拉**。

现在：

1. 锚点只记"播到了哪一毫秒 + 一个**代次**"；推进速率用**本端挂钟**（`anchorWallClockMs`，
   服务端在写锚点时打时间戳，客户端在 `onSyncedDataUpdated` 收到锚点时打时间戳）→ 与 TPS 无关；
2. 客户端**不再每秒比对一次进度**，只在**锚点代次变化时**对齐一次（seek / 暂停继续 / 换源 / 循环回卷）；
   另留一张"偏差 > 5 秒才纠一次"的安全网，正常播放永远不会碰到；
3. 暂停语义：实体暂停（指令/API）**以及单人游戏按 ESC**（`hasSingleplayerServer` + `PauseScreen`）
   都会把本地播放一起暂停 —— 以前客户端根本没调用过 `session.pause(...)`，实体的 paused
   只影响权威位置的计算，于是"暂停之后画面声音照播、进度还在一直校准"。多人服开菜单不算暂停。
4. 子进程侧：暂停会让声卡停止排空、音频时钟自然不动 —— 这**不是**"音频链死了"，
   不能触发"音频时钟停滞 → 永久改挂钟节奏"，否则暂停后画面还会继续播。

### 5.2 指令（唯一操控入口）
```
/cineduct screen create <url> [size <w> <h>] [at <pos>] [facing <yaw>]
/cineduct screen list
/cineduct screen remove <id|selector>
/cineduct screen info <id>                    # 状态/统计/延迟/码率
/cineduct screen source <id> <url>            # 换片（同会话复用或新建）
/cineduct screen play|pause|stop <id>
/cineduct screen seek <id> <时间>
/cineduct screen volume <id> <0..100> | range <min> <max>
/cineduct screen loop <id> on|off
/cineduct screen quality <id> auto|<档位>
/cineduct screen refresh <id> [full]          # 换链 / 重建会话
/cineduct gpu                                 # 能力探测结果 / 适配器 LUID / 传输层选择
/cineduct host status|restart                 # 子进程状态与手动重启
```
权限：服务端 op 2 级以上（含创建/删除）；只读类指令放开。

### 5.3 开放 API（供附属 mod）
稳定的只有 `dev.mediafix.api` 包；`dev.mediafix.*` 内部包不承诺兼容。

```java
public interface CineDuctApi {
    int API_VERSION = 1;
    ScreenHandle createScreen(ServerLevel level, Vec3 pos, float yaw, ScreenSize size);
    Optional<ScreenHandle> screen(UUID id);
    List<ScreenHandle> screens(Level level);

    /** SPI：附属 mod 可注册自己的站点解析器（B 站解析器自身也走这条注册）。 */
    void addResolver(MediaSourceResolver resolver);
}

public interface ScreenHandle {
    UUID id();
    void source(String uri); void play(); void pause(); void stop(); void seek(long ms);
    void volume(int percent); void range(float min, float max); void loop(boolean on);
    void size(float w, float h); void remove();
    Status status();
}

public record Status(long positionMs, long durationMs, PlaybackState state, Stats stats) {}
public record Stats(long bufferedBytes, long droppedFrames, long clockDriftUs,
                    long liveLatencyMs, String transport /* dxgi-interop | shm | java */) {}
```

- 事件（NeoForge 事件总线，服务端侧）：`ScreenCreatedEvent` / `ScreenRemovedEvent` / `ScreenPlaybackChangedEvent` / `ScreenSourceChangedEvent`。
- `MediaSourceResolver` SPI：`boolean canHandle(URI)` + `ResolvedStream resolve(URI)`（视频链/音频链/是否直播/时长/候选项），使附属 mod 能在不解包内部类的前提下扩展源。
- 兜底引擎与子进程共用同一套 `Status`/`Stats` 语义，**API 不暴露"当前用哪条引擎"为必需分支**（但 `transport` 字段可查）。

### 5.4 保留的 Java 兜底引擎
- 现有 `engine/`（MediaEngine / AudioSink / MediaClock / PacketBuffer）+ `ffmpeg/`（JavaCPP）整体保留，作为第三条路径。
- 选择顺序：`dxgi-interop` → `shm` → `java`，由 `/cineduct gpu` 展示，可由配置强制。

---

## 6. 仓库结构改造

```
src/main/java/dev/cineduct/api/          ← 稳定公开 API（唯一兼容承诺）
src/main/java/dev/cineduct/
    entity/  command/  host/  gpu/  bili/  engine/  ffmpeg/  render/  config/  log/
native/cineduct-host/                    ← C++20 + CMake（子进程）
native/cineduct-glshim/                  ← JNI shim（D3D11 设备/共享资源，游戏进程内）
docs/PLAN-standalone-gpu.md              ← 本文
```

- 删除：`src/main/java/dev/cineduct/mixin/**`、`bridge/TimeSyncAware`（水帧专用）、`config/SeekGuardConfig`、`config/CommentHide`、`ui/`（水帧提示）、`bilibili-media-stub/`（不再需要占位）。
- Gradle：新增 `nativeHost` / `nativeShim` 任务（vswhere 定位 VS → CMake + Ninja），产物放进 `cineduct-native/` 资源目录 → `jar` 自动带上。
- 首启解压到 `<gamedir>/cineduct/bin/<版本>/`，做**协议版本握手**，不匹配则拒绝启动子进程并给人话提示。
- CI：`.github/workflows/build.yml` 需改到 **windows runner**（native 交叉编译不划算）。

---

## 7. 阶段计划与验收

| 阶段 | 内容 | 验收标准 |
|---|---|---|
| **P0 独立化** | 删 Mixin、删前置依赖；屏幕实体 + 渲染器（暂用 Java 引擎出帧）+ 指令 + API 骨架 + 服务端权威进度 | 无任何前置 mod：贴链接能播、能暂停/seek/音量/多人进度一致；附属 mod 能通过 API 创建并控制屏幕 |
| **P1 互操作打样**（最高风险，先打样） | 独立小程序：最小 `host.exe` 创建共享纹理 + 最小 LWJGL/GL 程序导入并画动态彩条；`/cineduct gpu` 能力探测 | 跨进程彩条连续跑 30 分钟无卡顿无崩；测出 lock/unlock 与端到端帧延迟；**失败即切 3.4 方案，后续计划不变** |
| **P2 子进程媒体核心** | D3D11 设备 / d3d11va 硬解 / VideoProcessor 转 BGRA+缩放 / 多槽共享纹理 / WASAPI / 自有时钟 / seek / ABR / 续期 / 直播重连（移植现有逻辑） | 命令行独立跑 4K 不丢帧；音画偏差 <40ms；seek 落点误差 <50ms；CPU 占用显著低于现有 Java 路径 |
| **P3 IPC + 控制器** | 控制管道 + 共享内存状态环 + `MediaHostClient` + `GpuInterop(shim)` + 崩溃重启恢复 | 游戏内零拷贝播放；杀子进程后自动恢复且游戏不崩；`stats` 与子进程一致 |
| **P4 渲染与观感** | 实体渲染接入导入纹理（信箱化/缩放/朝向/亮度/全屏投影）；音量与距离衰减下沉；主音量跟随 | 多人同屏音画同步；不污染 MC 的 GL 纹理状态 |
| **P5 体验补齐** | HDR/10bit 路径、AV1 优先（Blackwell 有硬解）、多屏拼接辅助、附属 mod 示例、`/cineduct gpu` 诊断完善 | 4K HDR 可播；API 有可运行的示例附属 mod |
| **P6 打包发布** | Gradle↔CMake 一体构建、native 进 jar、版本握手、CI 改 Windows、LGPL 合规材料 | 干净实例只放一个 jar 就能跑；非 NVIDIA 机器自动降级不崩 |

---

## 8. 风险表

| 风险 | 等级 | 应对 |
|---|---|---|
| `WGL_NV_DX_interop2` 在本机不可用/性能不达标 | ~~高~~ → **已排除** | P1 打样已证明可用（4K60 零失败）；非 NVIDIA（AMD 无 GL↔D3D 互操作）仍需降级 |
| keyed mutex 竞态/死锁/卡渲染线程 | 中 | 3.3 的"只锁 READY 槽" + 多槽轮转 + 锁内只做 `CopyResource` |
| 游戏进程需要 D3D11（LWJGL 不管） | 中 | 200 行 JNI shim；句柄用 `DuplicateHandle` 从子进程投递，Java 侧不碰权限 |
| 多显卡/虚拟适配器选错导致互操作失败 | 中 | 游戏进程决定 LUID 并传给子进程；探测失败即降级 |
| 显存预算（多屏 4K） | 中 | 输出尺寸与解码尺寸解耦 + 同 URL 扇出 + 每会话上限可配 |
| FFmpeg 体积（~60MB DLL）与 LGPL 合规 | 中 | 只开解码相关、动态链接、随包附许可与源码获取说明，**绝不静态链 GPL** |
| 未签名 exe 被杀软/Defender 拦 | 低-中 | 发布说明 + 白名单指引；后续考虑代码签名 |
| 双引擎维护成本 | 中 | 兜底引擎冻结功能，只修崩溃；接口统一到 `Status`/`Stats` |
| 实体渲染的表现力（亮度/投影/遮挡排序） | 低-中 | 先做基础四边形 + 信箱化，观感项放 P4/P5 |
| 附属 mod 生态的 API 稳定性 | 中 | API 包单独隔离 + `API_VERSION` + 事件化，内部包不作承诺 |

---

## 9. 合规与许可

- 延续现状：**只做流式直连，不下载、不落盘、不提供离线保存**。
- 自有实现，不复制 waterframes/watermedia 的任何代码与资源（其模型/贴图/许可均不得沿用）。
- 打包的 FFmpeg 采用 **LGPL 共享库构建**（动态链接 + 许可证文本 + 源码获取方式）；不使用 GPL-only 组件。

---

## 10. 待定项（开工后按需确认）

1. 屏幕实体的**尺寸语义**：世界单位（米/格）还是像素？是否需要"按像素密度自动选共享纹理尺寸"。
2. 同 URL 扇出时，**不同屏幕不同尺寸**如何共用纹理（取最大尺寸，各屏自行缩放？还是分档）。
3. 附属 mod 的分发形式：只发布完整 jar 作 `compileOnly`，还是额外产出 `cineduct-api` 薄 jar。
4. HDR 的具体策略（子进程 tonemap 到 SDR 8bit，还是共享 10bit 纹理 + 游戏内着色器）。
5. 是否需要在**无子进程**（如非 Windows）时把兜底引擎提升为一等路径（关系到 Linux 阶段的默认行为）。

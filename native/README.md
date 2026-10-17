# native/ —— MediaFix 原生部分

这里放**C++ 子进程**、游戏进程内的 JNI 垫片，以及 P1 打样工程。

```
native/
  CMakeLists.txt              顶层（cineduct-glshim + 打样生产者 + cineduct-host）
  build.ps1                   一键构建（vswhere 找 VS → vcvars → CMake/Ninja → 再编 Java 侧）
  eof-test.ps1                端到端回归：片尾存活 / 片尾后 seek / 回卷 / 暂停冻结 / 暂停中 seek
  pipeline-test.ps1           流水线回归：出帧节奏 / seek 落点 / seek 风暴 / 状态块契约 / quit 收尾
  seek-heal-test.ps1          定位自愈回归：非零 PTS 起播 + 坏定位不变成假片尾
  hdr-test.ps1                HDR 回归：HDR10 不发灰（驱动色调映射 + 关掉的对照组）
  run-spike.ps1               跑 P1 打样（生产者 + 消费者）
  probe-interop.ps1           互操作能力探测：这台机器吃不吃 D3D11 的 NT 共享句柄（不需要游戏）
  run-mo-spike.ps1            跨进程打样：NT 共享句柄 → GL 内存对象（不走 NV_DX_interop）
  live-smoke.ps1              直播直链自检（静音；UA 对照实验）
  glshim/                     游戏进程内的极小 D3D11 垫片（JNI，生产要用）
    include/cineduct_glshim.h 纯 C 接口
    src/cineduct_glshim.cpp   C 实现 + JNI 薄包装
  spike/
    shared/cineduct_spike_status.h  生产者↔消费者的共享内存状态块布局（跨语言契约）
    host/main.cpp                  打样生产者：D3D11 共享纹理 + 键控互斥 + 着色器动画
  host/src/                   媒体子进程（cineduct-host.exe）
    main.cpp                命令行解析 / FFmpeg 加载 / 崩溃转储 / 自检形态（--list-* / --audio-only / --spdif-dump）
    video_pipeline.*         ★ 播放主体：demux / 解码 / 呈现三线程 + seek 三阶段握手 + 状态块
    packet_queue.*           有界包队列（demux → 解码；可被打断 + 世代标记）
    frame_queue.*            有界帧队列（解码 → 呈现；容量 3，满了等而不是丢）
    media_clock.*            主时钟（音频优先 + 挂钟回退 + 停滞检测/恢复）
    video_decoder.*          解码器封装（硬解优先、软解回退、正确的 EAGAIN 处理、flush）
    convert.*                BGRA 输出纹理 + 硬件路径（ID3D11VideoProcessor）+ 信箱化几何
    sw_scale.*               软件路径（swscale → BGRA，含黑边）
    audio_setup.*            音频输出形态规划（共享 / 独占 PCM / IEC 61937 直通）
    audio_sink.*             WASAPI 输出（共享/独占/直通三形态 + 可听位置时钟）
    audio_source.*           音频解码链 → 声卡（自带线程、异步 seek）
    audio_spdif.*            杜比/DTS 码流直通（IEC 61937 封装）
    audio_device.*           端点枚举与空间音效查询
    shared_tex.*             共享纹理发布（槽位状态机 + 键控互斥）
    ipc.*                    控制通道（命名管道，文本命令 + 回执）
    ffmpeg_dyn.*             FFmpeg 动态加载（LoadLibrary + 符号表，含可选 swscale）
    gpu.*                    D3D11 设备（按适配器 LUID 建，与游戏进程同一块卡）
  build/                      构建产物（bin/ 里是 exe+dll，java-classes/ 是消费者）

tools/spike-consumer/         打样消费者（模拟"游戏进程 / OpenGL 侧"，NV_DX_interop 路线）
tools/mo-probe/               内存对象路线的两个工具：MoProbe（自包含能力探测）、MoSpikeConsumer（跨进程消费者）
src/main/java/dev/mediafix/gpu/NativeD3D.java   垫片的 Java 绑定（生产位置）
```

## 构建与运行

```powershell
.\native\build.ps1                 # 需要 VS（带 VC 工具集）；cmake/ninja 用 VS 自带的
.\native\verify-deploy.ps1 -All    # ★ 收尾必跑：实例 mods 里的 jar 是不是最新构建
.\native\eof-test.ps1              # 端到端回归（静音）
.\native\pipeline-test.ps1         # 流水线回归（静音）
.\native\run-spike.ps1 -Seconds 30 # P1 打样；加 -Visible 可以肉眼看
.\native\run-spike.ps1 -ListAdapters
```

**部署核对的规矩（每次改完代码收尾都做）**：`.gradlew build` → 复制 jar 到实例 `mods\` →
跑 `.\native\verify-deploy.ps1 -All`。它按 **SHA256** 比对三件事，任意一条不满足就退出码 1：

1. `build\libs` 里最新的 `cineduct-*.jar`（按修改时间取最新，排除 sources/javadoc）；
2. 实例 `mods` 里那份是不是**同一份**（不看文件名看哈希），以及有没有多余的 cineduct jar
   （同 modid 两个 jar 会让游戏直接拒绝启动）；
3. jar 内嵌的 `cineduct-host.exe` / `cineduct-glshim.dll` 与 `native\build\bin` 里的**当前**产物一致
   —— "最新构建"必须是"最新原生件 + 最新 class"，只对一半会出现"改了没生效"。

默认核对 `1.21.1-NeoForge_21.1.251`；`-All` 会把 `Potato World 26.9.17` 一起核对。

系统要求：Windows + NVIDIA（`WGL_NV_DX_interop2`）或支持该扩展的 Intel 驱动。
AMD 的 OpenGL 驱动不支持 D3D 互操作 —— 那条路走不通，需要降级方案（见 `docs/PLAN-standalone-gpu.md` §3.4）。

> ⚠️ **2026-10 更正**：上面这句"AMD 不支持 D3D 互操作"**不准确**。AMD 从 Adrenalin 18.8.1 起
> 在 GL 扩展表里**同时**公开了 `WGL_NV_DX_interop` / `_interop2` **和**
> `GL_EXT_memory_object(_win32)` / `GL_EXT_semaphore(_win32)`。真正的问题是
> **NV_DX_interop 这条路整条建立在 NVIDIA 血统的扩展上**（函数指针装载 → 注册 → 加锁），
> 实现质量在非 NVIDIA 上不可靠。
>
> 因此新增了一条**不依赖 NV_DX_interop** 的路线：**NT 共享句柄 → GL 内存对象**
> （`DuplicateHandle` 投递句柄 + `glImportMemoryWin32HandleEXT(D3D11_IMAGE_EXT)` +
> `GL_EXT_semaphore_win32` 做 GPU 间同步）。分析、协议、实测数字与 AMD 复测步骤见
> **`docs/NT-handle-interop.md`**。
>
> ```powershell
> .\native\probe-interop.ps1        # 一次跑完给结论：这台机器的驱动吃不吃 NT 句柄（不需要游戏）
> .\native\run-mo-spike.ps1         # 跨进程零拷贝打样（内存对象路线）
> .\native\run-spike.ps1            # 老路对照（NV_DX_interop）
> ```

已验证环境：Windows 11 / RTX 5060 Ti（Blackwell）/ 驱动 616.64 / VS 2026 Build Tools / JDK 21。

> 两个环境坑（脚本里已经处理）：
> 1. 本机 PowerShell 是 5.1，**无 BOM 的 UTF-8 脚本会被按 GB2312 读坏** —— 改这些 .ps1 后要保证开头有 BOM。
> 2. MSVC 中文消息里 `/showIncludes` 的前缀是「注意: 包含文件:」，ninja 只认英文前缀，会把每个头文件都打一行并失去增量依赖跟踪 —— 构建脚本里设了 `VSLANG=1033`。

## 子进程调试模式（cmd 窗口 + 自动落盘）

排查"子进程到底怎么了"时用它。它把子进程的**全部**输出（包括 FFmpeg 自己走 stderr 的
`av_log` 警告）同时送到三个地方：**自己的日志文件**、**一个 cmd 窗口**、以及
**Java 那侧照旧的 `[host#N]` 诊断**（调试模式不会让 Java 日志里反而看不到子进程）。

入口：`native/host/src/host_log.{h,cpp}`。

### 怎么开（三种，任选其一）

| 方式 | 用法 | 备注 |
| --- | --- | --- |
| 标记（推荐） | 在**游戏目录**里建 `cineduct-host-debug.flag` —— **空文件或同名文件夹都认** | 游戏里最方便；**文件夹的意思是"日志就放这里面"**，删掉即关 |
| 环境变量 | `CINEDUCT_HOST_DEBUG=1` | 从终端起游戏 / 跑测试脚本时用 |
| 命令行 | `--console`，另有 `--log-dir <目录>` / `--log-file <文件>` / `--log-keep N` | 手动跑 exe 时用（Java 侧也会自动传） |

> 这个开关**由 Java 侧判定**（只有它确定知道游戏目录）：`HostSession` 检查游戏目录里的标记，
> 命中就把 `--console --log-dir <绝对路径>` 传给子进程。子进程自己也有一套同名查找（exe 目录
> 往上两级 + 工作目录）当兜底 —— 因为"启动器给的工作目录"实测**既不是版本目录也不是 .minecraft 根**。

`--console` 的行为：**先 `FreeConsole()` 把继承来的控制台摘掉**，再 `AllocConsole()` 弹一个
**属于自己的**窗口（见下面「窗口的三条硬规矩」）。用 `--no-console` / `--no-log-file` 可强关。

启动横幅第 4 行直接告诉你窗口状态，**一眼可验**：

```
窗口  : 已就绪（日志实时投影到 cmd 窗口；焦点已还给游戏）
窗口  : 未启用（只落盘 + 回写 Java 管道）
```

### 日志落在哪

```
标记是文件夹 → 就写进那个文件夹里：  <游戏目录>/cineduct-host-debug.flag/cineduct-host-<时间>-p<pid>.log
标记是文件   → 写到它旁边：          <游戏目录>/cineductlogs/host/cineduct-host-<时间>-p<pid>.log
```

建文件夹的用法最直观（"我建了个文件夹，日志当然放里面"），所以优先按它办；两种都留了退路，
首选目录不可写时自动退到另一个。

- 每次会话一份（开几块屏幕就是几个子进程、几份日志），默认保留最近 10 份（`--log-keep` 可改）——
  轮转只删同目录下的 `cineduct-host-*.log`，不会碰你放在同一个文件夹里的别的东西；
- **写完就落盘**：stdout/stderr 本来就是无缓冲，所以崩在 `audiokse.dll` 那种地方也保得住最后一行；
- 头部有命令行，但**做过打码**：`--url` 只留查询串前 24 字符、`--headers` 整段隐去
  （里面有 `Cookie: SESSDATA=…`）—— 日志是要发给别人看的，不能把凭证抄进去；
- 「窗口关过一次」的标记 `console-closed-p<游戏pid>.marker` 也放在同一个文件夹里，
  所以**把这个文件夹整个删掉 = 全部复位**。

### 三个已知取舍

1. 窗口/管道里的内容比文件晚 **约 60ms**（文件写完就落盘，是权威来源；投影由独立线程做）；
2. 投影写管道**绝不会阻塞媒体线程**：`printf` 只写本地文件，Java 那侧就算卡住，
   最多是窗口慢一拍，画面和声音不受影响；
3. 不开调试模式时这几行代码**一个都不走** —— 不建目录、不写文件、不碰控制台，
   输出路径与以前逐字一致（`pipeline-test.ps1` / `eof-test.ps1` 都验证过）。
   关着的时候会多打两行诊断（exe 目录 / 工作目录 / 标记文件候选是否存在），
   这是专门给"flag 放了却没反应"这类问题留的线索。

### 窗口的三条硬规矩（都踩过坑，别改）

1. **只用自己的窗口**：`--console` 时先 `FreeConsole()` 把**继承来的**那个控制台摘掉，
   再 `AllocConsole()` 建自己的。实测坑（2026-10-06，用户当场发现）：游戏从终端起的时候，
   子进程会继承那个终端的控制台，于是调试日志被打进了**别人的窗口** ——
   而那个窗口一关，附带在里面的一切（连 agent 的宿主进程）都会被一起带走。
2. **弹窗后把焦点还给游戏**：`AllocConsole` 会抢焦点，单人游戏一丢焦点就弹暂停菜单，
   mod 随即把播放暂停 → 音频时钟冻结 → 一帧不发 → 看起来就是"视频放不出来了"。
   所以建完窗口立刻用 `--parent-pid` 找到游戏主窗口 `SetForegroundWindow`，并以
   `SW_SHOWNOACTIVATE` 显示自己的窗口。
3. **关过就不再弹**：窗口被关时（`CTRL_CLOSE_EVENT`）写一个
   `console-closed-p<游戏pid>.marker`，同一个游戏进程里后续重建的子进程看到它就不再弹 ——
   否则就是"关一次弹一次、永远关不掉"。想让它再弹：删掉那个 marker（或重启游戏）。

> 因此：**别点着那个窗口读日志**。你一点它，MC 就丢焦点、就暂停（MC 自己的行为，
> 与这个功能无关）。看内容请打开文件：`<游戏目录>/cineductlogs/host/cineduct-host-*.log`。

## cineduct-host 的流水线（v2，2026-09-28 重写）

老版本是一条"什么都干"的主循环（1700 行）：读包 → 送解码器 → 收帧 → 转换 → 发布 → 等时钟，
而且"等音频时钟"是在这条循环里用 2ms 轮询实现的。结果是：网络抖一下画面就停摆、
seek 只能排队、落后了没有丢帧策略。现在按成熟播放器的分层来（对照 CicadaPlayer 的实现）：

```
[demux 线程]  av_read_frame ──► 包队列（60 包 / 8MB，满了就睡）
     ▲                              │  所有等待都能被 seek / 停止打断
     │ interrupt_callback           ▼
[解码线程]  avcodec_send/receive ──► 帧队列（3 帧，满了等而不是丢）
     ▲                              │
     │                              ▼
[呈现线程 = main 线程]  主时钟判定（等 / 丢）──► 转换 ──► 共享纹理发布 ──► 状态块
```

**seek = 三阶段握手**（每段由各自的线程执行，靠一个递增的世代号对齐）：

| 阶段 | 线程 | 做什么 |
| --- | --- | --- |
| 1 | demux | `av_seek_frame`（带 BACKWARD）+ 清包队列 |
| 2 | 解码 | `avcodec_flush_buffers` + 清片尾标记 |
| 3 | 呈现 | 作废时钟、清声卡缓冲、请求音频定位、置前导帧丢弃阈值 |

跨世代的包/帧一律在出队时丢掉（`generation` 标记），所以"seek 之后先吐出来的旧包"
不可能被解成画面。定位点之前的前导帧也在这里丢掉。

**出帧判定**（数值取向对照 CicadaPlayer 的 `RenderVideo`）：早于主时钟 10ms 以内就等
（每次最多等 20ms，累计等满 2 秒强行出帧，绝不饿死画面）；晚于 100ms **且队列里还有更新的帧**
才丢这一帧（队列空着不丢 —— 宁晚不空）。

**时钟**：有音频就以"声卡已播出的采样数"为主时钟（`playedFrames()`，已扣掉 flush 丢弃的那段），
音频链路死了或停滞超过 3 秒就退回挂钟，**并且配一条恢复路径**（回来了就重新对表，
画面领先 >300ms 就把音频 seek 上去追）。纯音频以外的细节见 `media_clock.h` 顶部的三条教训。

### 与 Java 侧的契约（**一个字都不能改**）

- 命令行参数（`HostSession` 传的那些）语义不变；
- 控制通道的动词与回执格式不变：`hello / status / pause / seek / volume / loop / av-offset / quit`；
- 共享状态块 `spike/shared/cineduct_spike_status.h`：**原有字段的偏移一个都没动**。
  v4 只在**末尾追加**了 4 个诊断字段（`decodeMode@372` / `buffering@376` / `seekHealCount@380` /
  `droppedLate@384`，`kVersion` → 4）。追加是安全的：老 jar 读老偏移依然正确 ——
  **以后加字段也只许追加，不许插入或改序**；
- 行为约定不变：片尾不退出进程而是"保持最后一帧待命"、`loop 1` 回卷重播、
  暂停冻结画面与位置、暂停中 seek 立刻回执、音频坏了不牵连画面、直播 PTS 原点归一。

v4 那 4 个字段让上层零 IPC 就能看到"现在跑的是硬解还是软解 / 有没有在缓冲 / 丢了多少帧 /
定位自愈过几次"——这三件事以前只能翻子进程日志（Java 侧读法见 `HostSession` 的
`decodeModeId()` 等，日志提示见 `MediaSession.reportHostDiagnostics()`）。

### 已知取舍

- **HDR（HDR10/HLG）**：默认把真实色彩空间（`G2084`/`GHLG` + BT.2020）声明给 D3D11 VideoProcessor，
  由**驱动**做 HDR→SDR 色调映射（输出是 BGRA8，最终被 Minecraft 当 LDR 贴图画）。
  不声明的话驱动按 BT.709 SDR 处理 PQ 数据 → **高光被压死、画面发灰**（本机实测最亮从 255 掉到 131）。
  另有两个诊断开关：`--hdr-tonemap cpu`（CPU 自己算，曲线可控但每帧多一次回拷）、
  `--hdr-tonemap off`（复现发灰，供 `hdr-test.ps1` 当对照组）。
- **阻塞读的中断**：seek 靠 `AVFormatContext::interrupt_callback` 打断正在阻塞的网络读；
  执行定位时（`SeekState::demuxSeeking`）不打断自己，避免"seek 风暴"把在做的定位打散。
- **软解回退**：d3d11va 打不开（或运行期连续报错 ≥5 帧）就换软解重开（回到最近关键帧重解），
  软解帧经 swscale → BGRA → `UpdateSubresource` 进同一张输出纹理。
  设备没有可用 `ID3D11VideoProcessor` 时，硬解帧会回拷 CPU 再走这条路 —— 画面照出，只是不再零拷贝。
  这需要 FFmpeg 包里有 `swscale-9.dll`（可选加载：缺它只失去软解兜底，硬解不受影响）。
- **音频 seek 的收尾**：链路的定位是异步的（只有它自己的线程能碰 FFmpeg 上下文），
  发完请求要等它真的落地再清一次声卡缓冲（否则会先响几十毫秒旧声音）。
- **音画偏移默认会自愈，而且不动声卡**：可听内容位置 = 音频链"最新写出样本的内容时间"
  − `queuedDurationMs()`（环形 + 设备待播）。偏移（画面 − 可听内容）连续 4 次 ≥300ms 且稳定、
  离最近一次音频不连续（seek / 清缓冲 / 暂停恢复）≥4 秒时，把**时钟**重锚到可听内容位置
  （`MediaClock::anchor`）—— 声卡一个字节都不动，画面自己追上去（落后就等、领先就丢帧）。
  老的 `--av-correct`（把音频 seek 到画面，会有跳音）仍然保留，走那条路时以它为准。
  判据/实测见 `docs/DEV-WORKFLOW.md` §8.5.1。
- **暂停/恢复的记账**：`playedFrames` 必须单调、且**不许凭空前跳**。`Start()` 之后立刻读
  `GetCurrentPadding()` 在某些驱动上会返回 0，于是"已播帧"凭空多出一个设备缓冲（≤200ms）
  → 时钟前跳、画面丢帧追上、而那段没播的音频恢复后照旧播出来 = **声音永久落后**。
  所以恢复时固定用暂停前的读数，`Start()` 后的读数只当诊断打印（`恢复：… 跳变 Nms`）。


## P1 打样要回答的问题

「**另一个进程用 D3D11 写出来的画面，能不能被 Minecraft 进程的 OpenGL 直接采样到**」——
这是整个「C++ 子进程 + 共享显存」方案唯一的高风险假设。打样刻意不含 FFmpeg / B站逻辑，
只产出**可自动校验**的动画帧：每帧把帧号编码进 R/G 通道、B 通道固定 `0xA5`，
消费者把纹理画进 128×128 的 FBO、`glReadPixels` 回读角落像素解码比对 —— 于是
「帧在更新 / 通道没错位 / 内容不是垃圾」全部不需要人眼判断。

数据流（**已实测跑通**）：

```
mediafix-spike-host.exe（模拟子进程）                SpikeConsumer（模拟游戏进程 / GL 侧）
 D3D11 设备（指定适配器 LUID）                        GLFW + GL 3.3 上下文
 着色器渲染动画 → 中间纹理                            wglDXOpenDeviceNV（逐适配器探测）
 键控互斥 AcquireSync(0,4ms)                          OpenSharedResourceByName（共享纹理）
 CopyResource → 共享纹理(NTHANDLE|KEYEDMUTEX)   ──►   CopyResource → 本地纹理(misc=0)
 ReleaseSync → 槽位标 READY                          wglDXRegisterObjectNV(本地纹理)
 状态写进命名共享内存段                          ◄──  wglDXLockObjectsNV → 采样 → Unlock
                                                     回写槽位 SHOWN
```

## 实测结论（重要，已同步进计划文档 §3.1）

| 结论 | 证据 |
|---|---|
| NVIDIA 驱动 616.64 上 `WGL_NV_DX_interop` / `_interop2` **可用** | 函数指针装载成功，`wglDXOpenDeviceNV` 返回非 0 |
| **D3D11 设备必须与 GL 上下文同适配器**；用"逐个适配器试 `wglDXOpenDeviceNV`"来判定最可靠 | 本机 4 个适配器（3 个同名 NVIDIA LUID + 1 个软件适配器）中被正确选中 `0000c52a` |
| 跨进程共享**只能**靠 `SHARED_NTHANDLE`；`CreateSharedHandle` 之后**必须保持句柄存活** | 提前 `CloseHandle` → 对端 `OpenSharedResourceByName` 得到 `E_INVALIDARG (0x80070057)` |
| ★ **`wglDXRegisterObjectNV` 拒绝 NTHANDLE 纹理** | 标志矩阵实测：`0x0` ✔ / `0x1`(SHARED) ✔ / `0x2`(KEYEDMUTEX) ✔ / `0x3` ✔ / **`0x802`(NTHANDLE+KEYEDMUTEX) ✘**（`access` 用只读/读写都一样 ✘） |
| 于是帧的落地方式改为：**共享纹理只做跨进程搬运，游戏进程再 GPU→GPU 拷进本地纹理**给 GL 采样 | 4K60 跑 45s：拷贝 2681 帧、拷贝失败 0、锁失败 0 |
| **GL 采样互操作纹理必须在 `wglDXLockObjectsNV` 保护下** | 不加锁直接画：回读像素 B 通道 100% 异常（18060/18060）；加锁后 0 异常 |
| 槽位 4 态 + 两侧 key=0 键控互斥的同步协议够用且不阻塞 | 生产者 60fps 发布 0 丢弃；加锁等待 avg 0.051ms / max 13.9ms |
| D3D→GL 画面**天生上下翻转** | GL 采样时 `vUv.y = 1 - y` |
| `WAIT_ABANDONED` 必须当"已获得"处理 | 否则对端崩溃后存活方会永远等锁 |
| ★ **键控互斥不能当作跨进程互斥用** | ① 两侧同 key ⇒ `AcquireSync` **同 key 直接成功**（递归语义），等于没互斥；② 严格乒乓（写 `Acquire(0)/Release(1)`、读 `Acquire(1)/Release(0)`）⇒ 任一方缺席/慢一拍接力棒永久断掉，**实测生产者发布 4 帧后彻底卡死、丢 2644 帧** |
| 正确做法：**零超时试锁**（两把钥匙各试一次，都失败就丢掉这一帧）+ **槽位状态机才是正确性保证** | 写方只碰 `EMPTY`/`SHOWN` 槽、读方只碰 `READY` 槽、读方拷完才标 `SHOWN` ⇒ 两者不可能同时接触同一个槽。改完生产者加锁延迟 0.218ms、丢帧 0 |
| 锁协议会直接变成画面顿挫 | 超时回退版「最长无新帧 183ms」，严格乒乓版直接卡死；零超时试锁版降到 **33ms**。同一份代码只改锁协议，别无差别 |
| 1080p60 / 4K60 长跑均稳定 | 4K60 跑 45s：拷贝 2681 帧零异常；1080p60 跑 120s：拷贝 7118 帧、渲染 128820 帧、丢帧 0、锁失败 0、通道异常 0 |

## 与生产（P3）的差异

打样为了少写管道，做了两处简化，**生产要换掉**：

1. **句柄投递**：打样用命名共享句柄（`CreateSharedHandle` 带名字 + 对端 `OpenSharedResourceByName`）。
   生产改为无名 NT 句柄 + 子进程 `OpenProcess(PROCESS_DUP_HANDLE)` + `DuplicateHandle` 投递进游戏进程，
   游戏进程用 `NativeD3D.openSharedTextureByHandle` 打开（垫片里已经实现好，打样没走）。
2. **握手**：打样用共享内存状态块的 `magic`/`version` 当就绪信号。生产走命名管道（带 ACK）+ 协议版本握手。

另外 P2 起，生产者里的「着色器画动画」会换成
`FFmpeg(d3d11va 硬解) → ID3D11VideoProcessor(NV12/P010→BGRA8 + 缩放) → 共享纹理`。

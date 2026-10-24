# CineDuct API

**语言 / Language：** [中文](#中文) · [English](#english)

> **状态**：`1025-build-alpha` —— Alpha 阶段，功能可用，细节仍在打磨。
> 版本号规则：`<MMdd>-build-<通道>`（构建日期在前，如 `1025-build-alpha` = 10 月 25 日构建的 alpha 包）。
> **仓库 / 源码**：<https://github.com/hxwi1/CineDuct-API>（旧仓库已归档，问题反馈请走 [Issues](https://github.com/hxwi1/CineDuct-API/issues)）

---

## 中文

### 这是什么

一个**独立的** Minecraft 视频模组：世界里放一块屏幕、贴个链接就能播；同时把播放能力开放给其它模组。

- **不依赖任何前置模组** —— 直链解析、解码、音频输出、时钟、渲染全部自研。
- **只做流式直连** —— B 站音视频是两条 DASH 直链，边下边播，**不下载、不落盘、无缓存文件**。
- **客户端必装、服务端可选** —— 画面与声音都在客户端；服务端装了才多出「屏幕实体 + `/cineduct screen`」这套权威状态。

---

### 运行要求

#### 必需

| 项目 | 要求 | 说明 |
| --- | --- | --- |
| Minecraft | **1.21.1** | 仅此版本 |
| NeoForge | **21.1.231 或更高** | 实测 21.1.251 / 21.1.264 |
| Java | **21** | 启动器通常已带，别手动换成 17 |
| 操作系统 | **Windows 10 / 11 x64** | 见下方「平台说明」 |
| 显卡 | 支持 **D3D11** 即可（硬解 D3D11VA 为可选加速） | 核显也能放，软解兜底 |
| 内存 | 游戏本体之外再留 **≥1 GB** | 缓冲与解码帧都在这部分 |
| 硬盘 | ~70 MB | jar 本体 + 首次启动解压出的 FFmpeg 原生库 |

> **平台说明（重要）**：随 jar 打包的 FFmpeg 原生库**只有 `windows-x86_64`** 一份。
> 所以两条播放链路在 Linux / macOS 上都开箱不可用 —— 这不是"只有零拷贝挑平台"，**进程内引擎同样挑**。
> 那两套系统要跑，得自备对应平台的 FFmpeg 原生库，把路径填进 `cineduct-ffmpeg.json` 的 `nativesPath`；
> C++ 子进程（DXGI/WASAPI）则**只有 Windows** 有。

#### 两条播放链路（决定画质上限与占用的部分）

模组会在本地自动二选一，也可以在设置里强制。它们的硬件要求不同：

| | **进程内引擎**（默认） | **C++ 子进程零拷贝** |
| --- | --- | --- |
| 开启方式 | 默认即用 | `/cineduct subprocess on`（永久）或 Sodium 设置页勾选 |
| 画面路径 | 解码 → CPU/GPU 转换 → 上传成 GL 纹理 | 解码留在显存 → 跨进程共享纹理 → GL 直接采样（不经过 CPU） |
| 音频 | Java 侧自研声卡 | WASAPI（子进程内） |
| 额外要求 | 无（任意 D3D11 显卡） | GPU + 驱动支持 **D3D11 ↔ OpenGL 互操作** |
| 适合 | 任何机器、直播、首次安装 | 想要最低 CPU 占用 / 最高画质余量 |
| 失败时 | — | 自动降级回进程内引擎，**始终有画面** |

驱动兼容性现状：**NVIDIA 最稳**（主打路径）；**Intel** 可用；**AMD** 的互操作实现有质量差异，
本仓库只在 NVIDIA 上做过长跑实测 —— AMD 上若画面异常，`/cineduct subprocess off` 用回进程内引擎即可。

#### 可选组件

| 组件 | 作用 | 不装会怎样 |
| --- | --- | --- |
| [Sodium](https://github.com/CaffeineMC/sodium) **0.8.13+** | 游戏内出现「视频设置 → CineDuct API」设置页（含扫码登录） | 一切功能照常，只是没有这个设置页，得用指令和 json 配 |
| [Iris](https://github.com/IrisShaders/Iris) | 光影下屏幕正确参与渲染 | 屏幕可能不出现在光影里 |
| B 站账号（`/cineduct login` 扫码） | 解锁 1080P60 / 4K / 杜比全景声 / Hi-Res | 未登录最高 **720P**、仅普通 AAC 音轨 |

---

### 安装

1. 把 `cineduct-<版本>.jar` 放进 `mods/`（客户端必装；服务端可选，装了才支持屏幕实体）。
2. 启动游戏。首次启动会把 jar 内的 FFmpeg 原生库解压到 `<游戏目录>/cineduct-ffmpeg/`，**不需要另外装 FFmpeg**。
3. 想要设置页就再装 Sodium 0.8.13+；想要最高画质就 `/cineduct login` 扫码登录。

---

### 快速开始

```text
/cineduct login                              # 可选：扫码登录，解锁高清/杜比
/cineduct screen create https://www.bilibili.com/video/BV...   # 在自己面前建一块屏幕（需要 OP 等级 2）
/cineduct screen info @e[type=cineduct:screen,limit=1]         # 看这块屏的状态与进度
```

> `/cineduct screen create` 需要**权限等级 2**（单人游戏默认就是）。屏幕尺寸是**服务端权威数据**，
> 只能用 `/cineduct screen size` 改，或由服务端代码通过 API 指定 —— 普通玩家改不了，这是有意为之。

---

### 指令

#### 屏幕（`/cineduct screen ...`）

| 指令 | 作用 |
| --- | --- |
| `create <链接>` | 在玩家面前建一块屏并开播（OP 2） |
| `list` / `info <屏幕>` / `remove <屏幕>` | 列表 / 详情 / 删除（删除需 OP 2） |
| `source <屏幕> <链接>` | 换源，进度重置（OP 2） |
| `play` / `pause` / `stop` / `seek <屏幕> <秒>` | 播放控制 |
| `volume <屏幕> <0-100>` / `range <屏幕> <近> <远>` | 音量与距离衰减范围 |
| `loop on\|off` | 循环播放 |
| `size <屏幕> <宽> <高>` | 屏幕尺寸（OP 2；单位是方块，0.25–64） |
| `quality auto\|max\|1080p\|1080p60\|4k\|8k` | 该屏的清晰度上限 |
| `rotate` / `move` / `alpha` / `brightness` | 朝向 / 位移 / 透明度 / 亮度 |
| `fit letterbox\|fill\|stretch` / `mirror on\|off` | 画面铺法 / 是否双面可见 |
| `audio default\|auto\|shared\|exclusive\|passthrough` | 该屏的音频输出形态 |

#### 播放与画质

| 指令 | 作用 |
| --- | --- |
| `/cineduct subprocess on\|off` / `status` | 用不用 C++ 子进程（**写入配置，永久生效**）/ 查看现状 |
| `/cineduct fallback [off]` | 只在**本局**压回进程内引擎（重启失效） |
| `/cineduct refresh [full]` | 重新解析直链并无缝换链；`full` 丢掉缓冲重建引擎 |
| `/cineduct-stream quality auto\|360p…4k\|8k\|max` | 全局清晰度上限 |
| `/cineduct-stream streaming on\|off` | 开关 DASH 流式直连 |
| `/cineduct streams` | 列出该视频每条可选流（编码 / 真实分辨率 / 码率） |

#### 音频与账号

| 指令 | 作用 |
| --- | --- |
| `/cineduct audio [dolby\|hires\|best]` | 音轨偏好：杜比全景声 / Hi-Res 无损 / 只取最高码率 |
| `/cineduct av [毫秒]` | 音画偏移（正 = 画面提前），写入配置 |
| `/cineduct login` / `status` / `logout` | B 站扫码登录 / 查登录态 / 退出 |

#### 独立播放器（给自带渲染的附属 mod）

| 指令 | 作用 |
| --- | --- |
| `/cineduct player open <链接>` | 开一条播放，画面交给你自己画 |
| `/cineduct player list` / `info <id>` / `stats <id>` | 列表 / 详情 / 现场统计 |
| `/cineduct player aspect <id> <比例>` | 显示面比例（16:9 = 1.7778）；0 = 关闭 |
| `/cineduct player rebuild <id>` / `close <id>` / `closeall` | 重建（续播）/ 关一条 / 全关 |

#### 诊断

| 指令 | 作用 |
| --- | --- |
| `/cineduct status` | 引擎、原生库、缓冲水位、解码路径、丢帧、自愈次数 |
| `/cineduct packet status` | 服务端控制通道现状（闸门 / 白名单 / 当前由服务端创建的播放） |
| `/cineduct packet test <json>` | 本地喂一条控制报文（跳过闸门，验收用） |
| `/cineduct help` | 完整帮助（上面这些都在里面） |

---

### 配置文件

全部生成在**游戏目录根**（不是 `config/`），改完**重启游戏**生效。

#### `cineduct-ffmpeg.json`

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `gpuHostEnabled` | `false` | 是否用 C++ 子进程（零拷贝 + WASAPI）。`/cineduct subprocess on` 就是改它 |
| `hwAccel` | `true` | 进程内引擎的硬解（D3D11VA）；驱动不支持时自动降为软解 |
| `bufferTargetMs` | `4000` | **子进程提前缓存几秒**（包队列 PTS 跨度上限）。网络抖动大就调大 |
| `prerollMs` | `1500` | 子进程起播预缓冲：攒够再开播，避免开头卡 |
| `stallGateMs` | `1500` | 游戏卡顿时，子进程冻住时钟等缓存的门限；`0` = 关这条判据 |
| `bufferGate` | `true` | 缓冲闸门总开关（关掉 = 位置永远按主时钟走，卡了就丢帧追） |
| `prebufferMs` | `1500` | 进程内引擎的起播预缓冲（音频提前量） |
| `audioBufferMs` | `2500` | 声卡行缓冲时长 |
| `videoReadAheadMb` | `48` | 压缩流预读预算（进程内引擎） |
| `audioReadAheadMb` | `8` | 音频流预读预算 |
| `videoFrameBufferMb` | `256` | 解码帧内存预算（4K 一帧约 33 MB） |
| `hostSlots` | `3` | 子进程共享纹理槽数（越多抗抖动越强，显存也越多） |
| `hostPath` | `""` | 子进程 exe 路径；空 = 自动在游戏目录下找 |
| `audioMode` | `shared` | 音频输出形态：`shared` / `exclusive` / `passthrough` / `auto` |
| `audioDevice` | `""` | 指定输出端点（空 = 系统默认） |
| `nativesPath` | `""` | FFmpeg 原生库 zip 或目录；换版本 / 非 Windows 平台时用 |
| `threads` | `0` | 解码线程数，0 = 自动（核数一半） |
| `convertThreads` | `0` | NV12→RGBA 转换线程，0 = 自动 |
| `ringSize` | `4` | 环形帧槽数量（能容忍的抖动深度） |
| `avOffsetMs` | `0` | 音画偏移（正 = 画面提前）；也可用 `/cineduct av` |
| `verbose` | `false` | 把 FFmpeg 的详细日志写进游戏日志 |
| `freeNativeOnClose` | `true` | 关闭播放时释放原生上下文（排查崩溃时才设为 false） |

#### `cineduct-stream.json`

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `maxQn` | `-1` | 清晰度上限；`-1` = 自动（按网速）；0 = 账号内最高；80/116/120/127 = 1080P/1080P60/4K/8K |
| `dashStreaming` | `true` | 流式直连（关掉就没有本模组的播放能力了） |
| `highres` | `true` | 是否接受高码率（4K/HDR）直链 |
| `avoidAv1` | `true` | 避开 AV1（多数显卡无 AV1 硬解，软解只有个位数帧率） |
| `audioPreference` | `dolby` | 音轨偏好：`dolby` / `hires` / `best` |
| `streamOffsetMs` | `0` | 流式模式的音画偏移 |
| `catchUpMaxMs` | `8000` | "就地快进"的最大跨度（在已预读码流里丢数据，不重开连接） |
| `noopSeekToleranceMs` | `1500` | 原地 seek 容差（差这么点就当作没动） |

#### 其它

| 文件 | 内容 |
| --- | --- |
| `cineduct-bili.json` | `cookie`：登录凭证（可手填覆盖扫码结果） |
| `cineduct-log.json` | `dir`（默认 `cineductlogs`）/ `keepFiles`（10）/ `mirrorToMain`（true）/ `diagEnabled`（true） |
| `cineduct-packet.json` | **服务端（Paper 插件）控制客户端播放**的闸门：`enabled`（默认 **false**）、`servers`（白名单，空 = 全拒）、`token`、`disabledOps`、`allowedHosts`、`maxPerSecond`（20）。协议见 [docs/paper-control-payload.md](docs/paper-control-payload.md) |

---

### 日志与排查

| 位置 | 内容 |
| --- | --- |
| `<游戏目录>/cineductlogs/cineduct-<时间>.log` | 完整诊断日志，默认保留最近 10 份 |
| `<游戏目录>/logs/latest.log` | 事件与警告（`mirrorToMain`） |
| `<游戏目录>/cineductlogs/host/` | **子进程**日志，默认不产生（见下） |

**要看 C++ 子进程在干什么**（零拷贝那条链路默认不弹窗、不落盘），任选一种打开：

| 方式 | 做法 |
| --- | --- |
| 标记（推荐） | 游戏目录放一个空文件 `cineduct-host-debug.flag`；建成**同名文件夹**也行，那样日志就写进这个文件夹 |
| 启动参数 | JVM 参数加 `-Dcineduct.hostDebug=true` |

打开后 `latest.log` 会多一行 `[CineDuct] 子进程调试模式: 开（…）`，据此确认生效。删掉标记即关闭。

**卡顿时先看这三处**：

1. `/cineduct status` → 「缓冲: 水位=…/…ms」：水位远低于目标 = 网络真的跟不上；`⏸ 等缓存中` = 子进程正停着等。
2. 日志搜 `进入等缓存` / `等缓存结束`：有这对 = 卡顿被兜住了；只有 `PLAYING -> BUFFERING` 而没有 `等缓存结束` = 是水位在兜。
3. 日志搜 `[host#` 前缀：子进程侧的原始输出（没开调试模式时只在 Java 日志里转账）。

---

### 已知限制

- **只支持 Windows x64**（随包 FFmpeg 原生库只有这一份；其它平台需自备原生库，见「平台说明」）。
- **只支持 B 站**（视频 / 直播）；自定义源需要写 `MediaSourceResolver`（见 `docs/API.md`）。
- 4K / 1080P60 / 杜比全景声 / Hi-Res 需要 B 站**大会员**，权限不足会自动降级。
- 杜比全景声只解码到 **5.1 底混**（没有开源 Atmos 对象渲染器）；实际声道数以系统设备配置为准。
- 8K 基本只有 AV1 编码，开启「避开 AV1」时可能拿不到 8K。
- 零拷贝链路依赖 GPU/驱动互操作，不满足时自动回退进程内引擎。
- 不做弹幕、不做下载/离线缓存（合规要求，见下）。

---

### 合规与隐私

本模组**只做流式直连播放**：不下载视频、不留缓存文件、不提供离线保存能力。登录凭证只存在本机并收紧文件权限；
不做弹幕，不上传任何播放数据。

---

### 许可

以 **GPL-3.0-or-later** 发布，全文见 [LICENSE](LICENSE)。

**API / Mixin 例外**：依据 GPLv3 第 7 节的附加许可，通过本模组**公开 API**、**扩展点**，或以 **Mixin / 字节码方式**
修改或调用本模组功能的**独立项目**，不属于本模组的衍生作品，可选择任意许可（含闭源）。修改本模组自身、
或把本模组源码大量复制进其它项目，仍须遵守 GPL。细则见 [LICENSE-EXCEPTION.md](LICENSE-EXCEPTION.md)。

**第三方组件**：CicadaPlayer（MIT，`native/host` 播放器内核的参考实现）、OBS Studio（GPL-2.0，零拷贝**设计**参考，
实现为独立代码）、FFmpeg（LGPL-3.0-or-later，随 jar 分发）、JavaCPP（Apache-2.0）、ZXing（Apache-2.0）。
许可文本与源码获取方式见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)（jar 内 `META-INF/` 也有一份）。

---

### 构建（开发者）

```bash
gradlew build            # 产物在 build/libs/
```

C++ 子进程（`native/host`、`native/glshim`）需要 MSVC + CMake：`native/build.ps1` 编出
`cineduct-host.exe` / `cineduct-glshim.dll`，构建时自动打进 jar。`deploy.ps1` 可以先构建再复制进客户端实例
（只动 `cineduct-` 开头的文件）。部署后建议跑 `native/verify-deploy.ps1` 核对 jar 与内嵌原生件的哈希。

### 文档

- [docs/API.md](docs/API.md) —— 附属模组开发接口（唯一兼容承诺：`dev.mediafix.api`）
- [docs/DEV-WORKFLOW.md](docs/DEV-WORKFLOW.md) —— 开发流程与子进程流水线
- [docs/PLAN-standalone-gpu.md](docs/PLAN-standalone-gpu.md) —— 独立 GPU 播放架构
- [docs/PLAN-preload-buffer.md](docs/PLAN-preload-buffer.md) —— 缓冲水位 / 起播预缓冲 / 卡顿闸门
- [docs/AUDIO-passthrough.md](docs/AUDIO-passthrough.md) —— 音频直通
- [docs/NT-handle-interop.md](docs/NT-handle-interop.md) —— 非 NVIDIA 路线的 NT 共享句柄互操作
- [docs/gpu-zero-copy-notes.md](docs/gpu-zero-copy-notes.md) —— 显存零拷贝设计索引
- [docs/host-uuid.md](docs/host-uuid.md) —— 子进程身份契约（两层 UUID）
- [docs/paper-control-payload.md](docs/paper-control-payload.md) —— 服务端（Paper 插件）控制客户端播放的网络包契约
- [CHANGELOG.md](CHANGELOG.md) —— 变更记录

### 鸣谢

- **WaterMedia**（及 WaterFrames）—— 本模组早期在它之上起步，播放链路、音频输出、帧上传的最初实践来自那段时期。
- **阿里巴巴 / CicadaPlayer** —— `native/host` 的播放器内核（三线程流水线、两级队列、主时钟、硬解重试、出帧判定）
  参考了它的实现思路（MIT，Copyright (c) 2019-present Alibaba Inc.）。
- **OBS Studio** —— 显存零拷贝方案的参考（DXGI 共享纹理、跨 API 互操作与同步），实现独立编写。
- **FFmpeg 及其贡献者** —— 所有解封装与解码都靠它。

以及所有提交过 issue、帮忙测过机器、在群里指出过问题的玩家。

---

## English

### What it is

A **standalone** Minecraft video mod: place a screen in the world, paste a link, and it plays — while exposing the
playback engine to other mods.

- **No dependencies**: URL resolution, decoding, audio output, clocking and rendering are all built in.
- **Streaming only**: Bilibili audio/video are two DASH streams played as they download — **nothing is saved to disk**.
- **Client required, server optional**: picture and sound live on the client; the server only adds the authoritative
  screen entity and `/cineduct screen`.

---

### Requirements

#### Required

| Item | Requirement | Notes |
| --- | --- | --- |
| Minecraft | **1.21.1** | only this version |
| NeoForge | **21.1.231+** | tested on 21.1.251 / 21.1.264 |
| Java | **21** | |
| OS | **Windows 10 / 11 x64** | see "Platform" below |
| GPU | anything with **D3D11** (D3D11VA hardware decoding is optional) | integrated GPUs work via software fallback |
| RAM | **≥1 GB free** beyond the game itself | buffers and decoded frames live there |
| Disk | ~70 MB | jar + FFmpeg natives extracted on first launch |

> **Platform (important)**: the bundled FFmpeg natives are **`windows-x86_64` only**, so *both* pipelines are
> unavailable out of the box on Linux/macOS — this is not limited to the zero-copy path. To run there you must supply
> your platform's FFmpeg natives and point `nativesPath` at them; the C++ subprocess (DXGI/WASAPI) is **Windows-only**
> regardless.

#### The two playback pipelines

| | **In-process engine** (default) | **C++ subprocess (zero-copy)** |
| --- | --- | --- |
| Enabled by | default | `/cineduct subprocess on` (persisted) or the Sodium settings page |
| Video path | decode → convert → upload as a GL texture | decode stays in VRAM → shared texture → sampled by GL (no CPU copy) |
| Audio | built-in Java sink | WASAPI inside the subprocess |
| Extra requirement | none (any D3D11 GPU) | GPU + driver support for **D3D11 ↔ OpenGL interop** |
| Best for | any machine, live streams, first install | lowest CPU usage, headroom for high bitrates |
| On failure | — | automatic fallback to the in-process engine |

Driver status: **NVIDIA is the primary, best-tested path**; **Intel** works; **AMD** interop quality varies and has
not been long-run tested here — if the picture misbehaves, `/cineduct subprocess off`.

#### Optional

| Component | Purpose | Without it |
| --- | --- | --- |
| [Sodium](https://github.com/CaffeineMC/sodium) **0.8.13+** | adds the *Video Settings → CineDuct API* page (incl. QR sign-in) | everything works, configured via commands / json |
| [Iris](https://github.com/IrisShaders/Iris) | screens render correctly under shaders | screens may be missing with shaders |
| Bilibili account (`/cineduct login`) | unlocks 1080p60 / 4K / Dolby Atmos / Hi-Res | capped at **720p**, standard AAC audio |

---

### Install

1. Drop `cineduct-<version>.jar` into `mods/` (client required; server optional).
2. Launch. On first run the bundled FFmpeg natives are extracted to `<game dir>/cineduct-ffmpeg/` — no separate
   FFmpeg install needed.
3. Optionally install Sodium 0.8.13+ for the settings page, and run `/cineduct login` for high quality.

---

### Quick start

```text
/cineduct login                                                # optional: QR sign-in for HD/Dolby
/cineduct screen create https://www.bilibili.com/video/BV...   # spawn a screen in front of you (permission level 2)
/cineduct screen info @e[type=cineduct:screen,limit=1]         # inspect status and position
```

> Screen size is **server-authoritative**: only `/cineduct screen size` or the server-side API can change it.

---

### Commands

| Command | Purpose |
| --- | --- |
| `/cineduct screen ...` | create / list / info / remove / source / play / pause / stop / seek / volume / range / loop / size / quality / rotate / move / alpha / brightness / fit / mirror / audio |
| `/cineduct subprocess on\|off` / `status` | enable the C++ subprocess (persisted) / inspect current mode |
| `/cineduct fallback [off]` | force the in-process engine for this session only |
| `/cineduct refresh [full]` | re-resolve and hot-swap the stream; `full` rebuilds the engine |
| `/cineduct-stream quality auto\|360p…4k\|8k\|max` | global quality cap |
| `/cineduct audio [dolby\|hires\|best]` | preferred audio track |
| `/cineduct av [ms]` | A/V offset (positive = picture ahead), persisted |
| `/cineduct login` / `status` / `logout` | Bilibili QR sign-in / status / sign-out |
| `/cineduct player open\|list\|info\|stats\|aspect\|rebuild\|close` | standalone player API from commands |
| `/cineduct streams` | list every available stream (codec / real resolution / bitrate) |
| `/cineduct status` | engine, natives, buffer level, decode path, drops |
| `/cineduct help` | full help |

---

### Configuration

All files are created in the **game directory root** (not `config/`) and need a restart.

**`cineduct-ffmpeg.json`**

| Key | Default | Meaning |
| --- | --- | --- |
| `gpuHostEnabled` | `false` | use the C++ subprocess (zero-copy + WASAPI) |
| `hwAccel` | `true` | hardware decoding for the in-process engine |
| `bufferTargetMs` | `4000` | **seconds of read-ahead** in the subprocess (PTS span cap) |
| `prerollMs` | `1500` | startup preroll in the subprocess |
| `stallGateMs` | `1500` | freeze the clock when the game stalls for this long (`0` disables) |
| `bufferGate` | `true` | master switch for the buffer gate |
| `prebufferMs` | `1500` | startup preroll for the in-process engine |
| `audioBufferMs` | `2500` | audio device line buffer |
| `videoReadAheadMb` / `audioReadAheadMb` | `48` / `8` | compressed-stream read-ahead budgets |
| `videoFrameBufferMb` | `256` | decoded-frame memory budget (a 4K frame is ~33 MB) |
| `hostSlots` | `3` | shared texture slots |
| `hostPath` | `""` | subprocess exe path (empty = auto) |
| `audioMode` | `shared` | `shared` / `exclusive` / `passthrough` / `auto` |
| `audioDevice` | `""` | output endpoint (empty = system default) |
| `nativesPath` | `""` | FFmpeg natives zip or directory (other versions / platforms) |
| `threads` / `convertThreads` | `0` | decode / conversion threads (0 = auto) |
| `ringSize` | `4` | ring frame slots |
| `avOffsetMs` | `0` | A/V offset (positive = picture ahead) |
| `verbose` | `false` | write verbose FFmpeg logs |
| `freeNativeOnClose` | `true` | release native contexts on close |

**`cineduct-stream.json`**: `maxQn` (`-1` auto), `dashStreaming` (`true`), `highres` (`true`), `avoidAv1` (`true`),
`audioPreference` (`dolby`), `streamOffsetMs` (`0`), `catchUpMaxMs` (`8000`), `noopSeekToleranceMs` (`1500`).

**Others**: `cineduct-bili.json` (`cookie`), `cineduct-log.json` (`dir`, `keepFiles`, `mirrorToMain`, `diagEnabled`),
`cineduct-packet.json` (server-side playback control gate — `enabled` defaults to **false**; see
[docs/paper-control-payload.md](docs/paper-control-payload.md)).

---

### Logs and troubleshooting

| Location | Content |
| --- | --- |
| `<game dir>/cineductlogs/cineduct-<time>.log` | full diagnostic log (last 10 kept) |
| `<game dir>/logs/latest.log` | events and warnings |
| `<game dir>/cineductlogs/host/` | subprocess log — off by default, see below |

Enable subprocess logging with an empty `cineduct-host-debug.flag` in the **game directory** (a directory with that
name also works — logs then go inside it) or `-Dcineduct.hostDebug=true`. `latest.log` will confirm with
`[CineDuct] 子进程调试模式: 开（…）`.

**When playback stutters**, check: 1) `/cineduct status` → `缓冲: 水位=…/…ms` (level far below target = the network is
the bottleneck; `⏸ 等缓存中` = the subprocess is deliberately waiting); 2) log lines `进入等缓存` / `等缓存结束`;
3) `[host]` lines for the raw subprocess output.

---

### Known limitations

- **Windows x64 only** with the bundled natives; other platforms need their own FFmpeg natives.
- **Bilibili only** (video and live); custom sources require a `MediaSourceResolver` (see `docs/API.md`).
- 4K / 1080p60 / Dolby Atmos / Hi-Res require premium; the mod degrades automatically.
- Dolby Atmos decodes to the **5.1 bed** only (no open-source Atmos object renderer).
- 8K is mostly AV1-only, so "Avoid AV1" may make 8K unavailable.
- No danmaku, no download/offline cache (compliance).

---

### License

**GPL-3.0-or-later**, see [LICENSE](LICENSE). Under the GPLv3 section 7 additional permission, independent projects that
use the **public API**, extension points, or **mixin/bytecode integration** are not derivative works and may use any
license; modifying this mod itself still requires GPL compliance. Details in [LICENSE-EXCEPTION.md](LICENSE-EXCEPTION.md).

**Third-party**: CicadaPlayer (MIT, reference for the player core), OBS Studio (GPL-2.0, design reference for zero-copy;
implementation is independent), FFmpeg (LGPL-3.0-or-later, bundled), JavaCPP (Apache-2.0), ZXing (Apache-2.0). See
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

### Building

`gradlew build` → `build/libs/`. The C++ subprocess needs MSVC + CMake (`native/build.ps1`); the binaries are packed
into the jar automatically. `deploy.ps1` builds and copies into an instance; `native/verify-deploy.ps1` verifies hashes.

### Documentation

[docs/API.md](docs/API.md) · [docs/DEV-WORKFLOW.md](docs/DEV-WORKFLOW.md) · [docs/PLAN-standalone-gpu.md](docs/PLAN-standalone-gpu.md)
· [docs/PLAN-preload-buffer.md](docs/PLAN-preload-buffer.md) · [docs/AUDIO-passthrough.md](docs/AUDIO-passthrough.md)
· [docs/NT-handle-interop.md](docs/NT-handle-interop.md) · [docs/gpu-zero-copy-notes.md](docs/gpu-zero-copy-notes.md)
· [docs/host-uuid.md](docs/host-uuid.md) · [docs/paper-control-payload.md](docs/paper-control-payload.md)
· [CHANGELOG.md](CHANGELOG.md)

### Acknowledgements

**WaterMedia / WaterFrames** — the project started on top of it. **Alibaba / CicadaPlayer** (MIT) — the player core in
`native/host` follows its approach. **OBS Studio** — reference for the zero-copy design (independent implementation).
**FFmpeg and its contributors** — every demux and decode goes through it. And everyone who tested or reported issues.

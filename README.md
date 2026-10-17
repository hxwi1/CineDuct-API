# CineDuct API

**语言 / Language：** [中文](#中文) · [English](#english)

> **状态**：`3.3.0-alpha-1017` —— Alpha 阶段，功能已可用，细节仍在打磨。
> **仓库 / 源码**：<https://github.com/hxwi1/CineDuct-API>（旧仓库已归档废弃，问题反馈与源码索取请走新仓库的 [Issues](https://github.com/hxwi1/CineDuct-API/issues)）

---

## 中文

### 这是什么

**CineDuct API** 是一个独立的 Minecraft 视频模组：在游戏里放一块屏幕、贴个链接就能播，
同时把播放能力开放给其它模组。

- **不依赖任何前置模组**：解析、解码、音频输出、渲染、时钟全部自研。
- **只做流式直连**：B 站视频/音频是两条 DASH 直链，边下边播，**不下载、不落盘、不留缓存文件**。
- **客户端必装、服务端可选**：画面与声音都在客户端；服务端装了才多出「屏幕实体 + `/cineduct screen`」这套权威状态。

### 核心特性

**播放**

- 自研流式引擎：FFmpeg 解码（D3D11VA / DXVA2 硬解）+ Java 侧自研声卡 + 自研时钟/缓冲。
- **两条播放链路**：默认**进程内引擎**（不挑显卡驱动）；可开 **C++ 子进程零拷贝**（DXGI 共享纹理 + `WGL_NV_DX_interop2` 直接绑成 GL 纹理、WASAPI 直出），任一步不可用会自动降级回进程内，始终有画面。
- 清晰度：`auto`（按实测网速 ABR 升降档）/ 360P / 480P / 720P / 720P60 / 1080P / 1080P60 / 4K / 8K / 不限。
- 默认**避开 AV1**（多数显卡无 AV1 硬解，4K AV1 软解只有个位数帧率）。
- 无缝换链：直链约 2 小时过期，到期前自动重新解析并热切换，声音不断、位置不变。
- 就地快进：前进时在已预读码流里丢数据，不重开连接、不清空缓冲。
- 信箱化：非 16:9 的屏幕按比例居中，不拉伸。

**音频**

- 音轨偏好：杜比全景声（E-AC-3）→ Hi-Res 无损（FLAC）→ 普通音轨最高码率，缺档自动回退。
- 多声道：设备支持多少声道就输出多少；5.1 源遇 2 声道设备按标准矩阵下混。
- 音画同步：以音频可听位置为主时钟，画面跟随；支持运行期偏移微调。

**直播**

- 直接贴 `live.bilibili.com/<房间号>`；固定原画（qn=10000）。
- 用系统时间锚定时间轴，可给出实时延迟；断流自动重连、重新开播自动接上。

**账号与权限**

- 设置界面里**扫码登录**（二维码直接画在界面上），也支持 `/cineduct login`。
- 清晰度与音轨按账号权限收窄：**未登录最高 720P、普通用户 1080P、大会员全开**；界面收窄 + 解析层夹紧两道，越权档位即便写进配置也不会真去请求。

**设置界面（Sodium 集成）**

- 装了 [Sodium](https://github.com/CaffeineMC/sodium)（0.8.13+，需其公开配置 API）后，「视频设置」里会出现 **CineDuct API** 页：
  清晰度、零拷贝子进程、进程内硬解、避开 AV1、音轨偏好、音画同步，以及**账号登录**入口。
- 中英双语；所有选项都走 Sodium 的配置 API 注册（**不注入、不 Mixin**）。

**屏幕与对外 API**

- 屏幕实体 `mediafix:screen` + `/cineduct screen` 指令；多人服里所有人看到同一块屏、进度一致。
- 附属模组两种接法：**用屏幕实体**（`CineDuctApi` + `ScreenHandle`）或**只借播放会话自己画**（`CinePlayerApi` + `CinePlayer`）。
- 自定义源解析器：`MediaSourceResolver` 注册后与内置 B 站解析器完全平等。
- **屏幕尺寸没法用指令随意控制**：尺寸是服务端权威数据，`/cineduct screen size` 需要 **OP 等级 2**；普通玩家
  （没有 OP）用指令改不了尺寸，也不能在 `create` 里顺带带尺寸（链接是 `greedyString`，会吃掉整行）。
  要由服务端代码控制尺寸，请用 API：创建时 `ScreenOptions.Builder.size(w, h)`，运行中 `ScreenHandle.size(w, h)`。

### 系统要求

| 项目 | 值 |
| --- | --- |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.231+ |
| Java | 21 |
| 操作系统 | Windows（零拷贝链路依赖 `WGL_NV_DX_interop2` + D3D11；进程内引擎无此要求） |

### 安装

1. 把 `cineduct-<version>.jar` 放进 `mods/`（客户端必装；服务端可选）。
2. 首次启动会把 jar 内的 FFmpeg 原生库解压到 `<游戏目录>/cineduct-ffmpeg/`，**无需单独安装 FFmpeg**。
3. 想要「视频设置」里的设置页，再装 Sodium 0.8.13+。

### 常用指令

| 指令 | 作用 |
| --- | --- |
| `/cineduct screen ...` | 创建 / 操作屏幕实体（详见 `/cineduct help`） |
| `/cineduct player open <链接>` | 开一个独立播放器（画面交给调用方自己画） |
| `/cineduct login` | B 站扫码登录（高清 / 杜比 / 4K 需要）；`status` / `logout` |
| `/cineduct refresh [full]` | 手动重新解析 + 无缝换链；`full` 重建引擎 |
| `/cineduct subprocess on\|off` | 是否用 C++ 子进程播放（零拷贝 + WASAPI，默认 off，永久生效） |
| `/cineduct fallback [off]` | 只在**本局**压回进程内引擎（内存开关，重启失效） |
| `/cineduct-stream quality <档位>` | 锁定清晰度：`auto` / `360p`~`8k` / `max` |
| `/cineduct audio [dolby\|hires\|best]` | 查看 / 切换音轨偏好 |
| `/cineduct av <毫秒>` | 音画偏移（正 = 画面提前），写入配置 |
| `/cineduct streams` | 列出该视频每条可选流（编码 / 真实分辨率 / 码率） |
| `/cineduct status` | 引擎、原生库、流式开关、本次播放统计 |
| `/cineduct help` | 完整指令列表 |

### 配置文件（生成于游戏目录根）

| 文件 | 说明 |
| --- | --- |
| `cineduct-stream.json` | 清晰度、音频偏好、流式开关、音画偏移 |
| `cineduct-ffmpeg.json` | 原生库路径、硬解、缓冲、预读、日志详略、`gpuHostEnabled`（子进程，默认 false） |
| `cineduct-bili.json` | 登录 Cookie（可手填覆盖扫码结果） |
| `cineduct-log.json` | 日志级别 |

### 日志

| 位置 | 内容 |
| --- | --- |
| `<游戏目录>/cineductlogs/cineduct-<时间>.log` | 完整诊断日志（保留最近若干份） |
| `<游戏目录>/logs/latest.log` | 关键 INFO / WARN |

### 排查：C++ 子进程日志

子进程（零拷贝 + WASAPI 那条链路）默认**不弹窗、不落盘**。要查它，任选一种方式打开调试：

| 方式 | 做法 |
| --- | --- |
| 标记文件（推荐） | 在**游戏目录**放一个空文件 `cineduct-host-debug.flag`。把它建成**同名文件夹**也行——那种情况下日志直接写进这个文件夹 |
| JVM 参数 | 启动参数加 `-Dcineduct.hostDebug=true` |

打开后：

- 子进程会弹出**控制台窗口**（能看到实时输出）；
- 日志写入 `<游戏目录>/cineductlogs/host/cineduct-host-<时间>-p<pid>.log`（标记是文件夹时写进该文件夹）；
- `latest.log` 里会多一行 `[CineDuct] 子进程调试模式: 开（弹窗 + 日志落到 …）`，可据此确认生效。

关掉：删掉标记文件（或去掉 JVM 参数）即可。

### 构建

```bash
gradlew build            # 产物在 build/libs/
```

C++ 子进程（`native/host`、`native/glshim`）需要 MSVC + CMake，`native/build.ps1` 会编出
`cineduct-host.exe` / `cineduct-glshim.dll`；构建时会自动把它们打进 jar。
仓库自带的 `deploy.ps1` 可以先构建再复制进客户端实例（只动 `cineduct-` 开头的文件）。

### 已知限制

- 4K / 1080P60 / 杜比全景声 / Hi-Res 无损需要 B 站**大会员**；权限不足会自动降级。
- 杜比全景声**只到 5.1 底混**（没有开源 Atmos 对象层渲染器）；输出为几声道取决于系统把设备配置成几声道。
- AV1 默认避开：无 AV1 硬解的显卡上 4K AV1 软解跑不动。
- 8K 基本只有 AV1 编码；开启「避开 AV1」时可能拿不到 8K。
- 零拷贝链路需要 GPU/驱动支持互操作，不满足时自动回退进程内引擎。

### 合规与隐私

本模组**只做流式直连播放**：不下载视频、不留缓存、不提供离线保存能力。登录凭证只存在本机，并收紧文件权限；
不做弹幕、不上传任何播放数据。

### 许可

以 **GPL-3.0-or-later** 发布，全文见 [LICENSE](LICENSE)。

**API / Mixin 例外**：依据 GPLv3 第 7 节的附加许可，通过本模组**公开 API**、**扩展点**，或通过 **Mixin / 字节码方式**
修改或调用本模组功能的**独立项目**，不属于本模组的衍生作品，可选择任意许可（含闭源）。修改本模组自身、
或把本模组源码大量复制进其它项目，仍须遵守 GPL。细则见 [LICENSE-EXCEPTION.md](LICENSE-EXCEPTION.md)。

**第三方组件**：CicadaPlayer（MIT，`native/host` 播放器内核的参考实现）、OBS Studio（GPL-2.0，零拷贝**设计**参考，
实现为独立代码）、FFmpeg（LGPL-3.0-or-later，随 jar 分发）、JavaCPP（Apache-2.0）、ZXing（Apache-2.0）。
许可文本与源码获取方式见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)（jar 内 `META-INF/` 也有一份）。

### 文档

- [docs/API.md](docs/API.md) —— 附属模组开发接口（唯一兼容承诺：`dev.mediafix.api`）
- [docs/DEV-WORKFLOW.md](docs/DEV-WORKFLOW.md) —— 开发流程与子进程流水线
- [docs/PLAN-standalone-gpu.md](docs/PLAN-standalone-gpu.md) —— 独立 GPU 播放架构
- [docs/AUDIO-passthrough.md](docs/AUDIO-passthrough.md) —— 音频直通
- [docs/gpu-zero-copy-notes.md](docs/gpu-zero-copy-notes.md) —— 显存零拷贝设计索引
- [CHANGELOG.md](CHANGELOG.md) —— 变更记录

### 鸣谢

这个模组能跑起来，离不开下面这些开源项目和它们背后的人：

- **WaterMedia**（及其配套的 WaterFrames）—— 本模组早期就是在它之上起步的：播放链路、音频输出、帧上传这些最早的一批实践都来自那段时间。现在的独立引擎已经不再依赖它，但很多设计取舍仍然受益于那段经验。
- **阿里巴巴（Alibaba）/ CicadaPlayer** —— `native/host` 的播放器内核（三线程流水线、两级队列、主时钟、硬解失败重试、出帧判定）参考了 CicadaPlayer 的实现思路（MIT，Copyright (c) 2019-present Alibaba Inc.）。
- **OBS Studio** —— 显存零拷贝方案的参考：DXGI 共享纹理、跨 API 互操作与同步的做法。对应实现是独立编写的，但设计上受它启发。
- **FFmpeg 及其贡献者** —— 所有解封装与解码都靠它；没有 FFmpeg 就没有这个模组。

以及所有提交过 issue、帮忙测过机器、在群里指出过问题的玩家。

---

## English

### What it is

**CineDuct API** is a standalone Minecraft video mod : place a screen in the world,
paste a link, and it plays — while exposing the playback engine to other mods.

- **No dependencies**: resolution, decoding, audio output, rendering and clocking are all built in.
- **Streaming only**: Bilibili video/audio are two DASH streams, played as they download — **nothing is saved to disk or cached**.
- **Client required, server optional**: picture and sound live on the client; the server only adds the authoritative
  screen entity and `/cineduct screen`.

### Features

**Playback**

- In-house streaming engine: FFmpeg decoding (D3D11VA / DXVA2), custom Java audio sink, custom clock and buffering.
- **Two pipelines**: in-process engine by default (works on any GPU/driver), or an optional **C++ subprocess zero-copy**
  path (DXGI shared texture → `WGL_NV_DX_interop2` → GL texture, WASAPI audio). Any failure falls back automatically,
  so you always get a picture.
- Quality: `auto` (ABR from measured bandwidth) / 360p / 480p / 720p / 720p60 / 1080p / 1080p60 / 4K / 8K / unlimited.
- **AV1 avoided by default** (most GPUs lack AV1 hardware decoding; 4K AV1 in software drops to single-digit FPS).
- Seamless URL renewal: direct links expire in ~2 hours; the mod re-resolves and hot-swaps without audio glitches or position loss.
- In-place fast-forward: drops already-buffered packets instead of reopening the connection.
- Letterboxing: non-16:9 screens keep their aspect ratio.

**Audio**

- Track preference: Dolby Atmos (E-AC-3) → Hi-Res lossless (FLAC) → highest-bitrate standard track, with automatic fallback.
- Multichannel: outputs as many channels as the device supports; 5.1 sources are downmixed to stereo when needed.
- A/V sync: audio playback position is the master clock, with a runtime offset for receiver/soundbar latency.

**Live streams**

- Paste `live.bilibili.com/<room>`; fixed original quality (qn=10000).
- Timeline anchored to wall-clock time, so the mod can report real latency; reconnects automatically on CDN drops.

**Account and entitlements**

- **QR sign-in inside the settings screen** (the code is drawn in-game), or `/cineduct login`.
- Quality and audio tracks are capped by account tier: **720p when signed out, 1080p for a standard account, everything for Premium**.
  Both the UI and the resolver enforce it, so over-tier values cannot be requested even if written into the config.

**Settings UI (Sodium integration)**

- With [Sodium](https://github.com/CaffeineMC/sodium) 0.8.13+ (public config API), a **CineDuct API** page appears in
  Video Settings: quality, zero-copy subprocess, in-process hardware decode, avoid AV1, preferred audio track,
  A/V sync offset, and the account sign-in entry.
- Bilingual (Chinese / English); registered purely through Sodium's public API — **no injection, no mixins**.

**Screens and API**

- Screen entity `mediafix:screen` plus `/cineduct screen`; every client in a server sees the same screen and position.
- Two integration styles for addons: **use the screen entity** (`CineDuctApi` + `ScreenHandle`) or **borrow only the
  playback session and render it yourself** (`CinePlayerApi` + `CinePlayer`).
- Custom source resolvers: register a `MediaSourceResolver` and it is treated exactly like the built-in Bilibili resolver.
- **Screen size cannot be freely controlled from commands**: size is server-authoritative, and `/cineduct screen size`
  requires **permission level 2** (OP). Players without OP cannot resize screens from commands, and `create` cannot take
  a size either (the URL is a `greedyString` that consumes the whole line). To control size from server code use the API:
  `ScreenOptions.Builder.size(w, h)` at creation, or `ScreenHandle.size(w, h)` at runtime.

### Requirements

| Item | Value |
| --- | --- |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.231+ |
| Java | 21 |
| OS | Windows (the zero-copy path needs `WGL_NV_DX_interop2` + D3D11; the in-process engine does not) |

### Install

1. Drop `cineduct-<version>.jar` into `mods/` (client required, server optional).
2. On first launch the bundled FFmpeg natives are extracted to `<game dir>/cineduct-ffmpeg/` — no separate FFmpeg install needed.
3. For the in-game settings page, install Sodium 0.8.13+ as well.

### Commands

| Command | Purpose |
| --- | --- |
| `/cineduct screen ...` | Create / control screen entities (see `/cineduct help`) |
| `/cineduct player open <url>` | Open a standalone player (caller renders the frames) |
| `/cineduct login` | QR sign-in for Bilibili (needed for HD / Dolby / 4K); `status` / `logout` |
| `/cineduct refresh [full]` | Re-resolve and hot-swap the stream; `full` rebuilds the engine |
| `/cineduct subprocess on\|off` | Enable/disable the C++ subprocess (zero-copy + WASAPI, default off, persisted) |
| `/cineduct fallback [off]` | Force the in-process engine for this session only (memory switch) |
| `/cineduct-stream quality <tier>` | Lock quality: `auto` / `360p`–`8k` / `max` |
| `/cineduct audio [dolby\|hires\|best]` | Show / switch the preferred audio track |
| `/cineduct av <ms>` | A/V offset (positive = picture ahead), persisted |
| `/cineduct streams` | List every available stream (codec / real resolution / bitrate) |
| `/cineduct status` | Engine, natives, streaming switch, playback stats |
| `/cineduct help` | Full command list |

### Config files (created in the game directory)

| File | Contents |
| --- | --- |
| `cineduct-stream.json` | Quality, audio preference, streaming switch, A/V offset |
| `cineduct-ffmpeg.json` | Natives path, hardware decoding, buffers, read-ahead, log verbosity, `gpuHostEnabled` (subprocess, default false) |
| `cineduct-bili.json` | Login cookie (can be filled manually) |
| `cineduct-log.json` | Log level |

### Logs

| Location | Contents |
| --- | --- |
| `<game dir>/cineductlogs/cineduct-<time>.log` | Full diagnostic log (recent files kept) |
| `<game dir>/logs/latest.log` | Key INFO / WARN lines |

### Debugging: C++ subprocess logs

The C++ subprocess (the zero-copy + WASAPI path) shows no window and writes no log by default. Enable debugging with either:

| Method | How |
| --- | --- |
| Flag file (recommended) | Put an empty `cineduct-host-debug.flag` in the **game directory**. A directory with the same name also works — logs then go into that directory |
| JVM argument | Add `-Dcineduct.hostDebug=true` to the launch arguments |

Once enabled:

- the subprocess opens a **console window** with live output;
- logs are written to `<game dir>/cineductlogs/host/cineduct-host-<time>-p<pid>.log` (or into the flag directory);
- `latest.log` gains `[CineDuct] 子进程调试模式: 开（…）`, which confirms it took effect.

Disable it by deleting the flag file (or removing the JVM argument).

### Building

```bash
gradlew build            # output in build/libs/
```

The C++ subprocess (`native/host`, `native/glshim`) needs MSVC + CMake; `native/build.ps1` produces
`cineduct-host.exe` / `cineduct-glshim.dll`, which are packed into the jar at build time.
`deploy.ps1` builds and copies the jar into a client instance (it only touches `cineduct-*` files).

### Known limitations

- 4K / 1080p60 / Dolby Atmos / Hi-Res lossless require a Bilibili **Premium** account; the mod degrades automatically otherwise.
- Dolby Atmos is decoded **only as the 5.1 bed** (no open-source Atmos object renderer); actual channel count follows the
  OS device configuration.
- AV1 is avoided by default: 4K AV1 in software is not watchable.
- 8K is mostly AV1-only, so "Avoid AV1" may make 8K unavailable.
- The zero-copy path needs GPU/driver support for the interop; otherwise the in-process engine takes over.

### Compliance and privacy

This mod only streams: it does not download videos, keep caches, or offer offline saving. Credentials stay on the
local machine with hardened file permissions. No danmaku, no telemetry.

### License

Released under **GPL-3.0-or-later**; see [LICENSE](LICENSE).

**API / Mixin exception**: under the additional permission of GPLv3 section 7, independent projects that use this mod's
**public API**, its **extension points**, or **mixin/bytecode integration** to modify or call its features are not
derivative works of this mod and may use any license (including proprietary). Modifying this mod itself, or copying
substantial parts of its source into another project, still requires GPL compliance. Details in
[LICENSE-EXCEPTION.md](LICENSE-EXCEPTION.md).

**Third-party components**: CicadaPlayer (MIT; reference for the player core in `native/host`), OBS Studio (GPL-2.0;
**design reference** for the zero-copy approach — implementation is independent), FFmpeg (LGPL-3.0-or-later, bundled),
JavaCPP (Apache-2.0), ZXing (Apache-2.0). License texts and source-availability notes are in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) (also packaged under `META-INF/` in the jar).

### Documentation

- [docs/API.md](docs/API.md) — addon development API (the only compatibility promise is `dev.mediafix.api`)
- [docs/DEV-WORKFLOW.md](docs/DEV-WORKFLOW.md) — development workflow and the subprocess pipeline
- [docs/PLAN-standalone-gpu.md](docs/PLAN-standalone-gpu.md) — standalone GPU playback architecture
- [docs/AUDIO-passthrough.md](docs/AUDIO-passthrough.md) — audio passthrough
- [docs/gpu-zero-copy-notes.md](docs/gpu-zero-copy-notes.md) — zero-copy design index
- [CHANGELOG.md](CHANGELOG.md) — changelog

### Acknowledgements

None of this would run without the open-source projects below and the people behind them:

- **WaterMedia** (and WaterFrames) — the project started on top of it; the earliest playback, audio output and
  frame-upload work came from that period. The engine no longer depends on it, but many design decisions still
  benefit from that experience.
- **Alibaba / CicadaPlayer** — the player core in `native/host` (three-thread pipeline, tiered queues, master clock,
  hardware-decode retry, frame pacing) follows CicadaPlayer's approach (MIT, Copyright (c) 2019-present Alibaba Inc.).
- **OBS Studio** — reference for the zero-copy design: DXGI shared textures, cross-API interop and synchronization.
  The implementation is independent, but the design was inspired by it.
- **FFmpeg and its contributors** — every demux and decode goes through it; without FFmpeg this mod would not exist.

And everyone who filed issues, tested on their machines, or pointed out problems along the way.

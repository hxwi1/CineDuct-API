# 第三方组件与许可声明（Third-Party Notices）

本项目在实现播放器内核时**参考并精简**了 CicadaPlayer 的实现。按 MIT 许可的要求，
其版权声明与许可文本必须随本仓库保留，因此单独写在本文件里。

本文件**不改变**本项目的整体许可：本项目整体仍以 `GPL-3.0-or-later` 发布
（见 [`LICENSE`](LICENSE) 与 [`LICENSE-EXCEPTION.md`](LICENSE-EXCEPTION.md)）。
第三方部分的代码/思路仍按其各自的原始许可提供。

---

## 1. CicadaPlayer

| 项 | 内容 |
|---|---|
| 名称 | CicadaPlayer（多平台播放器内核，AliPlayer 的播放器核心） |
| 上游 | <https://github.com/alibaba/CicadaPlayer> |
| 许可 | **MIT** |
| 版权 | Copyright (c) 2019-present Alibaba Inc. |

### 1.1 本项目参考 / 精简的范围

以下文件在实现思路上参考了 CicadaPlayer，并按其分层做了**精简重写**（不是逐字复制，
但保留本声明）：`native/host/` 下的

- `main.cpp`、`video_pipeline.{h,cpp}` —— demux / 解码 / 呈现三线程 + 有界队列 + 主时钟的分层结构；
  出帧判定（对照其 `RenderVideo` 的数值取向）；seek 回退到最近关键帧、seek 允许时间窗口的做法。
- `media_clock.h` —— 音频在播时以音频为主时钟、音频不在时以视频为基准的时钟模型
  （对应其 `mMasterClock = 首帧 pts + 漂移修正 + 设备已播位置`）。
- `packet_queue.h` —— 两层队列与 60 包上限（≈2 秒）的阻塞读模型。
- `frame_queue.h` —— 帧队列深度与"宁晚不空"的丢弃策略。
- `video_decoder.{h,cpp}` —— 硬解失败 → 软解重试一次；连续解码错误阈值。
- 文档 `docs/DEV-WORKFLOW.md`、`native/README.md` 中标出了与 CicadaPlayer 的对照点。

### 1.2 许可文本（原文）

完整原文另存于 [`licenses/CicadaPlayer-LICENSE.txt`](licenses/CicadaPlayer-LICENSE.txt)，内容如下：

```text
MIT LICENSE

Copyright (c) 2019-present Alibaba Inc.

Permission is hereby granted, free of charge, to any person obtaining
a copy of this software and associated documentation files (the
"Software"), to deal in the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish,
distribute, sublicense, and/or sell copies of the Software, and to
permit persons to whom the Software is furnished to do so, subject to
the following conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
```

### 1.3 与 GPL-3.0-or-later 的关系

- MIT 与 GPLv3 **兼容**：MIT 代码可以并入采用 GPL 的项目，前提是保留其版权声明与许可文本
  （本文件与 `licenses/CicadaPlayer-LICENSE.txt` 即为满足该条件）。
- 上述参考部分仍以 **MIT** 提供；本项目对自身代码适用的 GPL-3.0-or-later（以及
  `LICENSE-EXCEPTION.md` 里的 API/Mixin 附加许可）**不改变**这些第三方部分的原始许可。
- 再分发本项目（含二进制）时，请一并保留本文件与许可原文。

---

## 2. OBS Studio（仅设计参考，非衍生代码）

| 项 | 内容 |
|---|---|
| 名称 | OBS Studio |
| 上游 | <https://github.com/obsproject/obs-studio> |
| 许可 | 仓库根 `COPYING` 为 **GPL-2.0**；`libobs*` 多数文件带 "either version 2 ... or any later version" 头，但 `plugins/win-capture/graphics-hook/*` **无许可头**，保守按 **GPL-2.0-only** 理解 |
| 与本项目的关系 | **仅设计参考**：零拷贝相关实现为独立编写，**未复制其源码** |

### 2.1 说明

本项目的显存零拷贝方案（D3D11 共享纹理 → NT/KMT 句柄传递 → GL 侧 `wglDX*NV` 互操作 → 槽位状态同步）
在**设计层面**参考了 OBS Studio 的公开实现思路。设计索引（只记录文件与行号、**不含代码**）见
[`docs/gpu-zero-copy-notes.md`](docs/gpu-zero-copy-notes.md)。

对应实现位于 `native/host/src/shared_tex.cpp`、`native/host/src/gpu.cpp`、
`native/glshim/src/cineduct_glshim.cpp`，均为独立编写：自有 `cineduct::host` 命名空间、自有 IPC 与
槽位状态协议，未使用 OBS 的专有标识符（`capture_init_shtex`、`using_shtex`、`shtex_data`、
`gs_texture_*`、`GS_SHARED_*` 等）与注释。

因为**没有复制 OBS 的代码表达**，这些部分不构成 OBS 的衍生作品，**不适用其 GPL 条款**；
本项目整体仍以 `GPL-3.0-or-later` 发布。

### 2.2 贡献者注意（重要）

- `plugins/win-capture/graphics-hook/*`（`d3d11-capture.cpp`、`dxgi-capture.cpp`、`gl-capture.c`、
  `graphics-hook.c`、`vulkan-capture.c` 等）**没有 "or later" 许可头**，只能按 **GPL-2.0-only** 理解。
  GPLv2-only **与 GPLv3 不兼容**，请**不要**把这些文件或其中成段的代码直接引入本项目。
- 如果确实需要引入 OBS 源码，请先单独说明，并按以下方式之一处理：整段重写为独立实现、
  隔离成独立可执行文件/仓库并按 GPL-2.0 分发、或调整本项目的整体许可版本。
- `plugins/win-capture/graphics-hook/gl-capture.c` 还引用了 Microsoft Detours（MIT 许可），
  引入时须同时保留 Detours 的版权与许可声明。

---

## 3. 随 jar 分发的第三方库（FFmpeg / JavaCPP / ZXing）

jar 里打包了下列库的二进制或绑定代码。注意：shade 规则是 `exclude 'META-INF/**'`，
所以这些库自带的许可文件**没有**随 jar 一起打包 —— 许可文本改由 `licenses/` 保存，
本文件负责写明来源与义务。

### 3.1 FFmpeg（LGPL-3.0-or-later）

| 项 | 内容 |
|---|---|
| 组件 | FFmpeg 动态库（Windows x86_64）+ JavaCPP 生成的 Java 绑定 |
| 版本 | FFmpeg **8.0.1**，经 `org.bytedeco:ffmpeg:8.0.1-1.5.13`（classifier `windows-x86_64`） |
| 许可 | **LGPL-3.0-or-later** |
| 许可文本 | [`licenses/FFmpeg-LICENSE.md`](licenses/FFmpeg-LICENSE.md)、[`licenses/FFmpeg-COPYING.LGPLv3.txt`](licenses/FFmpeg-COPYING.LGPLv3.txt) |

**为什么是 LGPLv3**：FFmpeg 上游默认是 LGPL-2.1-or-later；bytedeco 的默认构建带
`--enable-version3`（把 (L)GPL 升到第 3 版），而 `--enable-gpl` **只在 `*gpl` 变体里启用**
（见 `bytedeco/javacpp-presets@1.5.13` 的 `ffmpeg/cppbuild.sh` 里 `if [[ "$EXTENSION" == *gpl ]]`
分支）。本项目用的是普通坐标，未启用 GPL 变体，因此不含 x264/x265 等 GPL 组件。

**对应源码（corresponding source）的获取方式**：本项目**不把 FFmpeg 源码放进仓库**
（它是上游项目，体积大，且应随其自身的构建体系发布）。按 LGPL 的要求，二进制的对应源码
通过以下方式提供：

1. 上游源码：<https://ffmpeg.org/releases/ffmpeg-8.0.1.tar.xz>
   （或 <https://github.com/FFmpeg/FFmpeg/tree/n8.0.1>）；
2. 精确构建配置：<https://github.com/bytedeco/javacpp-presets/tree/1.5.13/ffmpeg>
   的 `cppbuild.sh`（默认、非 `-gpl` 配置）；
3. 若上述地址失效，或你需要我们这边留档的源码包/补丁，可通过新仓库的
   [Issues](https://github.com/hxwi1/CineDuct-API/issues) 索取，
   我们会提供与上述版本一致的源码包与构建脚本。

**可替换性（LGPL 的要求）**：本项目以**动态库**方式加载 FFmpeg，并且支持替换——把自定义的
`*ffmpeg-natives*.zip` 放到 `config/cineduct/natives/` 或 `mods/`，或用配置项 `nativesPath`
指向自定义目录，即可用自己编译/修改的 FFmpeg 替换随包版本（见 `FfmpegRuntime`）。
因此你可以替换、重新链接并重新分发这个库。

### 3.2 JavaCPP（Apache-2.0 或 GPL-2.0-or-later + Classpath 例外）

| 项 | 内容 |
|---|---|
| 组件 | JavaCPP 运行时 + 由它生成的 Java 绑定代码 |
| 版本 | `org.bytedeco:javacpp:1.5.13` |
| 许可 | 双许可：**Apache-2.0** 或 **GPL-2.0-or-later with Classpath exception**（本项目按 Apache-2.0 使用） |
| 许可文本 | [`licenses/JavaCPP-LICENSE.txt`](licenses/JavaCPP-LICENSE.txt)（含两种许可原文） |
| 源码 | <https://github.com/bytedeco/javacpp/tree/1.5.13> |

### 3.3 ZXing core（Apache-2.0）

| 项 | 内容 |
|---|---|
| 组件 | ZXing core（生成登录二维码） |
| 版本 | `com.google.zxing:core:3.5.3` |
| 许可 | **Apache-2.0** |
| 许可文本 | [`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt) |
| 源码 | <https://github.com/zxing/zxing/tree/zxing-3.5.3> |

---

*本文件不构成法律意见。*

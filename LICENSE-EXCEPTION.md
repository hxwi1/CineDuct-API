# CineDuct License Exception — API / Extension / Mixin

Copyright (C) 2026 hxwi1 (the "copyright holders")

This document states (1) the license under which CineDuct is distributed, and
(2) an **additional permission** granted under section 7 of the GNU General
Public License version 3, so that other projects which merely *interact with*
CineDuct are not required to be licensed under the GPL.

The authoritative text of this document is the English text below. The Chinese
summary at the end is provided for convenience only.

---

## 1. License of CineDuct

CineDuct is free software: you can redistribute it and/or modify it under the
terms of the **GNU General Public License as published by the Free Software
Foundation, either version 3 of the License, or (at your option) any later
version** (SPDX identifier: `GPL-3.0-or-later`).

The full license text is provided in the file [`LICENSE`](LICENSE).

CineDuct is distributed in the hope that it will be useful, but **WITHOUT ANY
WARRANTY**; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
A PARTICULAR PURPOSE. See the GNU General Public License for more details.

---

## 2. Additional permission under GNU GPL version 3, section 7

### 2.1 Grant

As an **additional permission** under section 7 of the GNU GPL version 3, the
copyright holders grant you permission to link, load, combine, and distribute
CineDuct (or a modified version of it, to the extent this permission applies)
with any **Independent Work** — a separate work that interacts with CineDuct
only through one or more of the following interfaces, and that does not
incorporate CineDuct's source code, assets, or other copyrightable expression:

**(a) Public API.** Packages, classes, methods, fields, events, registries and
service interfaces documented or intended as public API, including without
limitation `dev.mediafix.api.*` and its subpackages (for example
`CineDuctApi`, `CineDuctClientApi`, `CinePlayerApi`, `MediaSourceResolver`,
`ScreenApi`, `ScreenOptions`, `PlayerOptions`, and the `dev.mediafix.api.event`
types).

**(b) Extension points.** Any documented mechanism by which a separate work
registers, resolves, renders, or otherwise hooks into CineDuct's behaviour
without modifying CineDuct itself, including media source resolvers, player
implementations, screen renderers and audio/video output integrations.

**(c) Bytecode-level integration.** Runtime integration such as Mixin
transformers, access transformers, or other bytecode patching that changes how
CineDuct behaves at load time, **provided** that the Independent Work does not
copy, redistribute verbatim, or relicense CineDuct's source code or assets.

### 2.2 Effect of the grant

You may distribute CineDuct together with an Independent Work, and you may
distribute the resulting combined work under **terms of your choice**. The
copyright holders will **not** assert that an Independent Work is a derivative
work of CineDuct merely because it uses the interfaces listed in §2.1, and you
are **not required** to license an Independent Work under the GPL, to disclose
its source code, or to distribute it under a GPL-compatible license.

In other words: using CineDuct's API, its extension points, or Mixin-based
integration does **not** by itself make your project subject to the GPL. Your
project may be released under any license you choose, including a proprietary
license.

### 2.3 What this permission does **not** cover

This additional permission does **not** apply to, and does **not** relax the
GPL for:

1. **Modifications of CineDuct itself** — changes to its own source code,
   assets, data files, or bundled binaries. Such modifications, and any
   distribution of them, remain subject to `GPL-3.0-or-later`.
2. **Copying or adapting substantial portions of CineDuct's source code or
   assets** into another work, including "vendoring", code extraction, or
   relicensing of CineDuct code under a different license.
3. **Redistribution of CineDuct itself** (modified or unmodified) under terms
   other than `GPL-3.0-or-later`.
4. **Bundling CineDuct as a library** in a way that is not a mere runtime or
   jar-in-jar dependency governed by §2.1, for example statically copying its
   classes into your own artifact.

### 2.4 Removal of this permission

As provided by section 7 of the GNU GPL version 3, a downstream distributor of
CineDuct **may remove this additional permission** from a copy they
redistribute. If the permission is removed from the copy you received, then the
plain `GPL-3.0-or-later` terms apply to that copy again, without the exception
described here.

### 2.5 Unmodified binary redistribution (source-offer exemption)

As an **additional permission** under section 7 of the GNU GPL version 3, the
copyright holders grant you permission to **convey verbatim, unmodified copies
of a CineDuct binary** (for example a released `.jar`, or a `.jar` you
redownloaded from an official or third-party mirror) **without** satisfying the
"Corresponding Source" obligations of GPLv3 section 6, provided that:

1. the binary is conveyed **unmodified** — no patched classes, no re-signed
   package with different contents, no stripped notices, no added or removed
   files inside the archive; and
2. this license notice, [`LICENSE`](LICENSE), [`LICENSE-EXCEPTION.md`](LICENSE-EXCEPTION.md)
   and [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) are kept available next
   to the binary (a link to the upstream repository is sufficient), and
3. you do not claim authorship of CineDuct or imply endorsement.

This exemption covers **only unmodified binaries**. It does **not** cover a
binary you built from modified sources, nor a bundle that contains CineDuct
alongside other code in a way that changes what is being conveyed — those remain
subject to the ordinary GPLv3 terms (source must be offered). Re-signing,
repackaging (other than lossless re-archive), or modifying any file inside the
archive counts as a **modification**, not as verbatim conveyance.

Nothing in this section changes the rights a recipient already has under the
GPL itself; it only adds an option for distributors of unmodified binaries.
### 2.6 No trademark or endorsement grant

This document grants no rights to the "CineDuct" name or logo beyond those
needed for factual, descriptive references. It does not imply that the
copyright holders endorse or support any Independent Work.

---

## 3. Third-party components

Parts of the native player core were modelled on **CicadaPlayer** (MIT,
Copyright (c) 2019-present Alibaba Inc.) and remain under their original MIT
terms. Their notices and license text are kept in
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) and
[`licenses/CicadaPlayer-LICENSE.txt`](licenses/CicadaPlayer-LICENSE.txt).
The additional permission in this document does not modify those licenses.

---

## 4. 中文摘要（仅供参考，以上英文为准）

- 本项目以 **GPL-3.0-or-later**（GNU 通用公共许可证第 3 版或更高版本）发布，全文见
  [`LICENSE`](LICENSE)。
- 依据 GPLv3 **第 7 节的附加许可**，以下情形的**独立项目不会被传染 GPL**，可以自由选择许可（包括闭源）：
  1. 通过本模组**公开 API**（`dev.mediafix.api.*`、事件、注册表、服务接口）调用或接入；
  2. 通过本模组的**扩展点**（媒体源解析器、播放器、屏幕渲染、音视频输出等）接入；
  3. 通过 **Mixin / 访问转换器 / 其它字节码方式**在运行期修改本模组行为，但**不复制**本模组源码。
- 不受该例外保护、仍须遵守 GPL 的情形：
  1. **修改本模组自身**的源码/资源并分发；
  2. 把本模组**源码大量复制**进你自己的项目；
  3. 用非 GPL 条款**再分发本模组本体**；
  4. 把本模组的类**静态拷进**你自己的产物（而非运行期依赖）。
- **未经修改的二进制产物可以豁免源码义务**：逐字节原样转发本项目的 `jar`（例如官方发布包或从镜像
  重新下载的同一个包）时，**不需要**同时提供对应源码（GPLv3 第 6 节的 Corresponding Source 义务），
  但必须保留 [`LICENSE`](LICENSE)、[`LICENSE-EXCEPTION.md`](LICENSE-EXCEPTION.md)、
  [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)（放个上游仓库链接也算），并且不得声称自己是作者。
  **重新签名、改包内容、加/删包内文件都算“修改”**，不再享受该豁免，回到正常 GPL 条款。
- 按 GPLv3 第 7 节，下游再分发者**可以移除**这项附加许可；移除后该副本恢复为不含例外的 GPL 条款。
- 本文档**不构成法律意见**。如用于商业分发，请自行咨询律师。

---

*SPDX-License-Identifier: GPL-3.0-or-later WITH CineDuct-API-Exception*

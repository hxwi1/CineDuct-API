CinePlay (this `paper-plugin/` directory)
Copyright (c) 2026 hxwi1

本目录（Paper 服务端插件 CinePlay）以 **MIT** 许可发布，全文见同目录 `LICENSE`。

它与仓库其余部分（CineDuct 模组与原生播放核心，GPL-3.0-or-later + 附加许可，
见根目录 `LICENSE` 与 `LICENSE-EXCEPTION.md`）是**互相独立的两个程序**：

- 插件**不包含**模组的任何代码，也不引用模组的任何类；
- 插件编译期只依赖 `io.papermc.paper:paper-api`（`compileOnly`，不打包、不分发）；
- 运行时两者只通过 **Minecraft 插件消息通道**（`cineduct:control` / `cineduct:state`，
  规定了 JSON 格式）通信 —— 属于进程间数据交换，不是代码派生。

因此把本目录单独按 MIT 分发（包括发布到 Modrinth / Hangar / SpigotMC）不违反根目录的
GPL-3.0-or-later。反向则**不允许**：不得把模组（GPL）的代码复制进本目录，
否则本目录也无法继续按 MIT 分发。

线上协议（与模组之间的契约）见 [`../docs/plugin-protocol.md`](../docs/plugin-protocol.md)。

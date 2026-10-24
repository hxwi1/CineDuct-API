# 服务端（Paper 插件）控制客户端播放：自定义网络包契约

> 状态：**P0（客户端协议层）与 P1（Paper 参考插件）已实现**；P2 显示目标与 P3 同步播放未做，见 §9。
> 目标场景：**服务端是 Paper**，用插件直接控制装了本模组的客户端播放。

---

## 1. 结论与一条硬约束

通道名：**`cineduct:control`**（下行，服务端 → 客户端），载荷是 **UTF-8 JSON 文本**。
另留一个可选上行通道 **`cineduct:state`**（客户端 → 服务端），供插件做面板/日志。

**硬约束：Paper 上不存在本模组的服务端部分。**
本模组的结构是"客户端必须、服务端可选"，而服务端那半是 **NeoForge** 的（屏幕实体 `cineduct:screen`、服务端权威进度、`/cineduct screen ...` 指令都在那一侧）。Paper 不加载 NeoForge mod，所以在 Paper 服务器上：

- 没有屏幕实体 → 客户端世界里**不会**有由服务端创建的屏幕；
- 没有服务端指令 → `/cineduct screen create ...` 那条路走不通；
- 唯一可被服务端控制的播放对象是 **客户端本地播放器**（`dev.mediafix.api.player.CinePlayer`），见 `src/main/java/dev/mediafix/api/player/CinePlayer.java:38`。

因此本通道的能力上限是"**让客户端本地开一条播放、并控制它**"，画面画在哪里是 §8 的未定项。

---

## 2. 通道与注册

| 通道 | 方向 | 用途 |
| --- | --- | --- |
| `cineduct:control` | 服务端 → 客户端 | 控制指令（下面所有 op） |
| `cineduct:state` | 客户端 → 服务端 | 状态回执（可选实现；插件不订阅也照常工作） |

客户端侧注册入口：`RegisterPayloadHandlersEvent` + `PayloadRegistrar`（NeoForge 21.1），
实现在 `src/main/java/dev/mediafix/net/CineDuctNetwork.java`。

要点：
- 载荷体就是"整段 JSON 的 UTF-8 字节"，不需要二进制结构对齐（这也是选 JSON 的原因：Paper 侧用 Gson 拼字符串即可，不需要共享结构体定义）。
- **通道必须先由客户端注册并通告**，否则 Paper 发的包会被丢弃。插件侧发之前用 `player.getListeningPluginChannels()` 判断有没有 `cineduct:control`：没有 = 模组没装或版本低于本特性，直接跳过（不要报错刷屏）。

### ★ 两个必须照做的实现细节（P0 实测代码里就是这么写的）

1. **注册时必须是 `optional()`**。NeoForge 客户端连到非 NeoForge 服务器（Paper / 原版）时，
   会拿"服务端没有任何通道"去协商客户端注册表；只要有一个**非 optional** 的通道，
   客户端会**直接断开连接**（`NetworkRegistry.initializeOtherConnection`）。
2. **编解码必须是裸字节**（读到底、写到底，不带长度前缀）。Paper 的 `sendPluginMessage` 发的是原样 UTF-8，
   用 `ByteBufCodecs.STRING_UTF8` 会写出 VarInt 长度前缀，对面就解不出来。

顺带一个结论，省得以后有人去找 Mixin：NeoForge 只为**已注册**的 payload id 装解码器；
未注册的 id 在客户端会变成 `DiscardedPayload` 被原版忽略（**不会踢人**），
所以服务端不需要装本模组，插件发的东西也能正常工作。

---

## 3. 报文格式

```json
{ "v": 1, "op": "open", "slot": "main", "url": "https://www.bilibili.com/video/BV1xx411c7mD",
  "volume": 80, "loop": true, "muted": false, "positionMs": 0, "aspect": 1.7778 }
```

| 字段 | 类型 | 必需 | 说明 |
| --- | --- | --- | --- |
| `v` | int | 是 | 协议版本；当前 **1**。客户端收到不认识的版本要**整包忽略并回一条错误**（回显 `v`），不猜 |
| `op` | string | 是 | 操作名，见 §4 |
| `slot` | string | 视 op | 服务端自定义的槽位名（1~32 字符，`[A-Za-z0-9_.-]`）。**服务端决定槽位，客户端维护 slot → 播放器映射**，这样插件不需要知道客户端的内部 id |
| 其它 | — | 视 op | 见 §4 |

通用规则：
- **未知 op / 未知字段**：忽略未知字段（向前兼容）；未知 op 回错误，不崩。
- **幂等**：同一 `(slot, op)` 重复下发必须等价（重连后插件可以无脑重放）。
- 单条报文建议 **< 8KB**；本协议只传控制指令与 URL，不放媒体数据。

---

## 4. 操作表

每个 op 都对应 `CinePlayer` 上**已存在**的方法，不新增客户端内部能力。

| op | 额外字段 | 语义 | 对应实现 |
| --- | --- | --- | --- |
| `place` | `world`、`corners[4]`、`alpha`、`brightness` | **下发虚拟屏几何**：四个角点（世界坐标，按 左上→右上→右下→左下 的环序）。客户端不建实体，只按这四个点画一个四边形；同一 slot 重复下发＝就地改几何 | `dev.mediafix.client.SlotScreen` |
| `anchor` | `url`、`positionMs`、`serverTimeMs`、`paused`、`live` | **进度权威**：客户端用 `positionMs + (now − serverTimeMs)` 算出"现在该播到哪"，进范围内起播时按它对齐 | `dev.mediafix.client.SlotPlayback` |
| `open` | `url`（必需）、`volume`、`loop`、`muted`、`positionMs`、`aspect` | 在该 slot 上开一条本地播放；slot 已存在则先 `close` 再开（等价"换源"） | `CinePlayerApi.open`（`src/main/java/dev/mediafix/api/player/CinePlayerApi.java:56`）+ `PlayerOptions` |
| `close` | — | 关闭该 slot；不存在则无操作 | `CinePlayer.close`（`CinePlayer.java:224`） |
| `pause` | `paused`（bool） | 暂停/继续 | `CinePlayer.pause`（`CinePlayer.java:164`） |
| `seek` | `positionMs` | 跳转（直播忽略） | `CinePlayer.seek`（`CinePlayer.java:170`） |
| `volume` | `percent`（0~100） | 设音量 | `CinePlayer.setVolumePercent`（`CinePlayer.java:173`） |
| `mute` | `muted`（bool） | 静音开关 | `CinePlayer.setMuted`（`CinePlayer.java:177`） |
| `repeat` | `repeat`（bool） | 循环 | `CinePlayer.setRepeat`（`CinePlayer.java:182`） |
| `aspect` | `aspect`（float，`<=0` 关） | 显示面比例（信箱化由模组烘焙进纹理） | `CinePlayer.setDisplayAspect`（`CinePlayer.java:157`） |
| `rebuild` | — | 重建该 slot 的播放（卡住/断流时用） | `CinePlayer.rebuild`（`CinePlayer.java:216`） |
| `sync` | `slots[]` | **幂等对齐**：把客户端状态收敛到给定 slot 集合（多余的服务端 slot 关闭），适合玩家重连/插件重载后一次下发 | 上述方法的组合 |
| `list` | — | 请求回执（走 `cineduct:state`）；无状态副作用 | 读 `CinePlayer` 状态方法 |

---

## 5. 安全闸门（必须实现，默认全关）

一个客户端会连很多服务器；这个通道等于把"播放控制"交给了服务器。默认策略：

| 闸门 | 默认 | 配置键（`cineduct-packet.json`） |
| --- | --- | --- |
| 启用开关 | **关** | `enabled` |
| 服务器白名单 | 空（= 全拒） | `servers`（地址包含匹配，所以写 `mc.example.com` 能匹配带端口的地址；单人游戏地址是 `singleplayer`） |
| 共享令牌 | 空 | `token`（报文带同样的 `token` 才接受） |
| 可禁用 op | 空 | `disabledOps`（例如 `["open"]`，租服只给控制权不给开播权） |
| 域名白名单 | `bilibili.com` / `bilibili.tv` / `b23.tv` / `bilivideo.com` / `hdslb.com` | `allowedHosts`（后缀匹配） |
| 频率上限 | 20 条/秒/客户端 | `maxPerSecond` |
| 生命周期 | 退服/换服即清 | 代码侧行为，无配置 |

闸门判定顺序：`enabled` → 地址白名单 → 令牌 → op 白名单（可配置禁用 `open`）→ URL 白名单 → 频率。

---

## 6. 上行回执（可选）

`cineduct:state`，同样是 UTF-8 JSON，客户端在以下时机发一条：`open` 成功后、状态发生跳变（如 `LOADING→PLAYING`）、错误、`list` 请求、以及每 **2 秒**的心跳（仅当有 slot 活着）。

```json
{ "v": 1, "seq": 12, "ack": 7, "slots": [
  { "slot": "main", "state": "PLAYING", "positionMs": 12345, "durationMs": 240000,
    "live": false, "buffering": false, "transport": "dxgi-interop",
    "volume": 80, "muted": false, "err": "" } ],
  "err": "" }
```

- `ack` = 最近一条成功处理的 `cineduct:control` 的序号（服务端若在报文里带 `seq`，客户端原样回显；不带则 `ack` 为 -1）。
- `state` 取值集合同 `PlaybackState`（`src/main/java/dev/mediafix/api/PlaybackState.java:5`）。
- 插件侧要读这个通道，需要用 Bukkit 的 `Messenger` 注册 `cineduct:state` 的入站监听；不注册也不影响下行。

---

## 7. 生命周期（谁负责收拾）

| 事件 | 客户端动作 |
| --- | --- |
| 收到 `open`（slot 已存在） | 先关旧播放器再开新的（顺序：close → open，避免两条链同时出声） |
| `sync` 里没有的 server slot | 关闭 |
| **断开与服务器的连接 / 换服 / 退出世界** | **关闭所有由服务端创建的 slot**（不能出现"已经退服了还在放"） |
| 模组更新/重载 | 同上，全清 |

"由服务端创建"= 收到过 `cineduct:control` 开出来的 slot；客户端自己（玩家/附属 mod）开的播放器**不受影响**。

---

## 8. 画面谁来画：**客户端虚拟屏（已定）**

`CinePlayer` 只负责"给出一张能采样的纹理"，必须有人每帧来画。Paper 服务端没有渲染能力，
也**造不出屏幕实体**（`cineduct:screen` 是服务端权威数据，Paper 不加载本模组）。所以：

- **服务端管状态**：这块屏在哪（几何）、放什么（播放列表）、放到哪（进度锚点）；
- **客户端管渲染与资源**：按几何画四边形，进出距离自己开播/释放。

几何由插件用**两个对角点**确定（见 §8.1）；`place` 下发四个角点，客户端不做几何推导，只按环序画。
这样以后要斜放的屏、拼接屏都不用改协议。

### 距离门控（客户端实现）

| 条件 | 动作 |
| --- | --- |
| 与屏幕中心 ≤ `enterDistance`（默认 48 格） | 没有 slot 就按 `anchor` 起播；有就继续 |
| ≥ `exitDistance`（默认 64 格） | `close` 并释放纹理/解码器/声卡 |
| 有几何但没收到过 `anchor` | 发一条 `query`（§6 上行）向服务端要 |

**为什么进出距离不同**：每次起播都要重新解析直链（B 站要发请求）。边界上晃来晃去如果用同一个阈值，
会变成"走走停停反复重开"，比不播更糟。

## 8.1 选点交互（插件侧，`/cineplay create`）

1. `/cineplay create [名字]` → 该玩家进入选区模式（默认名字 `main`）；
2. **手持木锄右键**两次，记两个对角点（射线取方块表面，最多 64 格）；
3. 第二次点击后插件算几何并下发 `place`：屏幕**面朝玩家**（只取水平 yaw）、**俯仰固定 90°**
   （竖直摆放，不跟随抬头低头），两个角点投影到这个平面上取包围盒作为宽高；
4. 选区期间**只给该玩家**撒粒子描边（服务端 `world_particles`，一次几百个点、几秒）。

> 粒子只用于**选区预览**，不做常驻描边 —— 常驻描边要每 tick 给每个玩家发包，几十人同屏就是网络灾难；
> 而"空屏看不见"这个问题用 `/cineplay status` 与选点回执解决更省。

## 8.2 播放列表（插件侧）

每块屏维护 `playlist: [url...]` 与 `index`：客户端回执 `state=ENDED` → `index++`（到末尾循环）→
下发新的 `anchor`；`/cineplay add <url>` 追加、`/cineplay playlist` 查看、`/cineplay next|prev` 手动切。
**没人靠近时不推进** —— 这是影院，散场就该停着。
---

## 9. 实施计划

分四期，前两期就能"用指令控制客户端播放"，第三期才谈"看见画面"。

### P0 客户端协议层（本模组内，Java）—— ✅ 已实现

新增包 `dev/mediafix/net/`，不碰现有播放代码（只调 `dev.mediafix.api.player` 那层公开 API）：

| 文件 | 职责 |
| --- | --- |
| `CinePacketConfig` | `cineduct-packet.json` 读写（键见 §5） |
| `CineGate` | 闸门判定链 + 1 秒滑窗限速；`summary()` 给状态面板 |
| `CineControlPacket` | 报文解析/校验（`v`/`op`/`slot` 严格；未知字段忽略） |
| `CineControlHandler` | op 分派 → `CinePlayer`；`slot → CinePlayer` 映射；退服清理；`snapshot()` 给回执 |
| `CineStateUplink` | `cineduct:state`：open 成功、状态跳变、错误、2 秒心跳 |
| `CineDuctNetwork` | payload 定义 + `optional()` 注册 + 裸字节编解码 |

调试入口（**不需要 Paper 服务器就能验收**）：

| 指令 | 作用 |
| --- | --- |
| `/cineduct packet` / `packet status` | 闸门现状（启用/白名单/令牌/域名/限速）+ 当前 slot 列表 |
| `/cineduct packet test <json>` | 把一条报文喂给同一个处理器，**跳过闸门**（只做 op 验收） |
| `/cineduct packet closeall` | 手动清掉所有服务端创建的播放 |

生命周期：客户端监听 `ClientPlayerNetworkEvent.LoggingOut` → 关闭**全部服务端 slot**（§7）。
玩家自己（指令/附属 mod）开的播放器不受影响。

### P1 Paper 参考插件（新目录 `paper-plugin/`）—— ✅ 已实现

Java 21 + `paper-api:1.21.1-R0.1-SNAPSHOT`，独立 gradle 项目（`.\gradlew.bat -p paper-plugin build`），
产物 `cineplay-<版本>.jar`，**不进本模组产物**。用法见 `paper-plugin/README.md`。

按 [Paper 官方文档](https://docs.papermc.io/paper/dev/getting-started/paper-plugins) 的现代写法：

| 内容 | 说明 |
| --- | --- |
| 插件描述 | `paper-plugin.yml`（不是 `plugin.yml`）；指令**不**在里面声明 |
| 指令注册 | `getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, …)` + Brigadier 指令树，`/reload` 后自动重注册 |
| 指令形状 | `/cineplay <op> …` 作用于自己；`/cineplay to <目标> <op> …` 作用于原版选择器选中的一批人（`@a`、玩家名、`@a[distance=..32]`） |
| 参数类型 | 用 Brigadier 强类型（`DoubleArgumentType` / `IntegerArgumentType` / `FloatArgumentType`）；`url` 用 `greedyString`（B 站直链带 `?&=`），因此它在最后、槽位改用 `/cineplay slot <名字>` 记住 |
| 回执展示 | 插件侧解析 `cineduct:state`（Gson，由 Paper 提供），`/cineplay status` 显示"slot 状态 进度/时长 传输 音量 错误" |
| 配置 | `config.yml`：`token`、`defaultSlot`、`logState`、`playCooldownMs`、`requireChannel`；`/cineplay reload` 热重载 |
| 权限 | `cineplay.use` / `cineplay.others` / `cineplay.admin`（默认 OP） |

`requireChannel` 默认 **false** 是刻意留的：NeoForge 客户端是否通过 Bukkit 的 register 机制通告通道，
各版本行为不一致，拿它当硬门禁会出现"客户端明明装了却发不出去"；发给没人监听的通道对原版客户端无副作用。

验收：本地 Paper 1.21.1 起服 → 装插件 → 客户端装模组 → 指令控制；**没装模组的玩家收到指令不报错**，
`status` 里明确显示"该玩家客户端没有 cineduct:control 通道"。

### P2 显示目标（决定"能不能看见画面"，§8 的 B 方案）

`CinePlayer` 只给纹理，没人画就是一帧都看不见 —— Paper 侧没有渲染能力，所以画面必须在客户端出：

- 新增客户端渲染目标：右下角 PiP / 全屏层二选一，`slot` 里编码位置（如 `hud:pip:br`、`hud:full`）；
- 画法复用 `ScreenStageRenderer` 的四边形 + 信箱化逻辑，纹理来自 `CinePlayer.acquireDrawTexture`；
- 生命周期跟随 slot：`close` 后不再画。

验收：`/cineplay play` 后画面出现在指定位置；`stop` 后消失；`aspect` 改比例下一帧生效。

### P3 同步播放（可选，影院效果）

多人服"所有人同一进度"：`open`/`sync` 里带 `positionMs` + 服务端时刻，客户端按锚点起播
（思路同 `ScreenEntity.projectedPositionMs`：只在锚点变化时对齐，不每帧纠偏）。
不在 P0/P1 范围，想要再加。

### 工作量与顺序

| 期 | 内容 | 估算 | 风险 |
| --- | --- | --- | --- |
| P0 | 协议层 + 闸门 + 调试指令 | 1~1.5 天 | 低（全在客户端，可本地验收） |
| P1 | Paper 插件 | 0.5~1 天 | 低（要起一个本地 Paper 服才能端到端测） |
| P2 | 内置显示目标 | 1~2 天 | 中（GL 绘制 + 纹理生命周期，容易漏 release） |
| P3 | 同步播放 | 0.5~1 天 | 中（时钟语义，容易做成"每帧纠偏"） |

### 已知风险

- **通道必须由客户端先注册**：插件发之前必须查 `getListeningPluginChannels()`，否则包被静默丢弃。
- **单包上限**：Bukkit 自定义通道单包约 32 KB；本协议只传指令与 URL，正常远低于此 —— 但仍要在插件侧截断超长 URL 并报错。
- **安全默认全关**：`enabled=false` 时**任何包都不接受**。这条不能为了"方便测试"改默认值。
- **不要伪造播放器身份**：报文里没有、也不该有子进程 pid / UUID（§10）。


---

## 10. 与子进程 UUID 的关系

两者正交，但有一处交汇：`cineduct:state` 里可以带上 `transport`（`dxgi-interop` / `java`），而排查"这条播放到底跑在哪个子进程上"要靠 `docs/host-uuid.md` 的身份体系。**不要在报文里暴露子进程 pid 或 UUID** —— 那是客户端内部实现，服务端不需要知道，也不该被允许指定。

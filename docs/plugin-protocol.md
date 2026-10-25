# CineDuct 插件协议（线上格式）

> 插件已独立成仓（`video-paper-plugin`，MIT）。本文件与那边的 `PROTOCOL.md` 是同一份内容；
> 改动协议请**两边一起改**，否则会漂。

服务端插件（Paper，`paper-plugin/`）与客户端模组（`dev.mediafix.net`）之间**唯一**的通信格式。
这份文档只讲**线上字节长什么样**；服务端怎么算进度、权限怎么判，见 `docs/paper-control-payload.md`。

- 版本：`v = 1`（`CineControlPacket.PROTOCOL`）
- 载荷：**UTF-8 JSON 文本**，直接塞进插件消息，**不加长度前缀**
- 上限：单包 30 KB（Bukkit 自定义通道约 32 KB，留余量；两端都校验）
- 不在本协议里的东西：音频数据、画面数据、文件。视频由客户端自己拉流，插件只下发"放什么、放哪儿、从哪儿开始"

## 通道

| 方向 | 通道名 | 用途 |
| --- | --- | --- |
| 服务端 → 客户端 | `cineduct:control` | 下发几何、进度锚点、关闭 |
| 客户端 → 服务端 | `cineduct:state` | **只有一条**：起播时上报一次实际时长 |

客户端侧两条通道都必须注册成 `optional()`：NeoForge 连到非 NeoForge 服务端时会用"对方没有任何通道"
协商注册表，只要有一个非 optional 通道就会直接断开连接（踩过）。

编解码必须是裸字节（`StreamCodec.of` 手写 `writeBytes` / `readBytes`）。用
`ByteBufCodecs.STRING_UTF8` 会写出 VarInt 长度前缀，Paper 侧解不出来。

## 公共字段

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `v` | int | 协议版本。客户端只接受 `1`，其它值整包丢弃 |
| `op` | string | 操作名，见下表 |
| `slot` | string | 屏幕名（`[A-Za-z0-9_.-]{1,32}`）。客户端用它做主键 |
| `seq` | long | 服务端自增序号，仅用于日志对账，客户端不回显 |
| `token` | string | 可选共享密钥；服务端与客户端配置一致才放行 |
| `serverTimeMs` | long | 服务端当前时间（`anchor` 用来把"服务端时刻"换算成本机时刻） |

## 下行 op

### `place` —— 屏幕几何

```json
{"v":1,"op":"place","slot":"main","world":"minecraft:overworld",
 "corners":[[12.0,72.0,-54.0],[16.0,72.0,-54.0],[16.0,68.0,-54.0],[12.0,68.0,-54.0]],
 "enter":48,"exit":64}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `world` | string | **维度 id**（`minecraft:overworld` / `the_nether` / `the_end`），不是 Bukkit 世界名 |
| `corners` | double[4][3] | 四角**世界坐标**，环序：左上 → 右上 → 右下 → 左下（从屏幕正面看） |
| `enter` | int | 起播距离（格）。0 或缺省 = 客户端默认 48 |
| `exit` | int | 停止距离（格）。必须 ≥ `enter`，0 或缺省 = 客户端默认 64 |

客户端校验：四角必须是 4 个点、相邻点距离 0.1~128 格；不通过整包丢弃并打日志。
`world` 与当前维度不一致时**几何保留但不播放**（换维度回来会自己恢复）。

### `anchor` —— 播放进度权威

```json
{"v":1,"op":"anchor","slot":"main","url":"https://www.bilibili.com/video/BV1xx411c7mD",
 "positionMs":12000,"paused":false,"live":false,"volume":80,"serverTimeMs":1761384000000}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `url` | string | 播放源。空字符串 = 无内容（客户端停播） |
| `positionMs` | long | **下发时刻**应播到的位置；客户端此后按本机时钟自行推进 |
| `paused` | bool | 暂停状态 |
| `live` | bool | 直播标记。客户端不做 seek、服务端也不按时长推进 |
| `volume` | int? | 这块屏的音量 0~100。缺省 = 不覆盖客户端已有音量 |

客户端最终音量 = `volume` × 本机**全局音量**（`/cineduct gvolume`，完全本地，不上报）。

### `close` —— 关闭这块屏

```json
{"v":1,"op":"close","slot":"main"}
```

客户端收到后：停播并**清掉几何**（屏幕上立刻消失）。删屏、队列播完都发这条。

## 上行 op

### `duration` —— 起播时长上报（每个 `(slot, url)` 只发一次）

```json
{"v":1,"op":"duration","slot":"main","url":"https://...","durationMs":213000,"live":false}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `durationMs` | long | 客户端**实际可播**的时长。`live=true` 时可为 0 |
| `live` | bool | 直播（服务端不会按时长自动推进） |

为什么需要它：自动推进播放列表必须知道"这条放完了没有"，而这个数只有真放起来的客户端知道
——它天然带着"非大会员只能试看 6 分钟"这类截断，服务端查 API 查不出来。
服务端按 `(slot, url)` 对回播放列表项，**上报值覆盖**插件自己查到的值。

这条是**唯一**的上行数据，不做心跳、不报位置、不报卡顿。

## 客户端闸门

客户端**默认对所有服务器生效**（不需要开关、也没有服务器白名单）。剩下的三道防线在
`<游戏目录>/cineduct-packet.json`（可选文件，不存在就用默认值）：

```json
{
  "token": "",
  "disabledOps": [],
  "allowedHosts": ["bilibili.com", "b23.tv", "hdslb.com"],
  "maxPerSecond": 20
}
```

| 字段 | 说明 |
| --- | --- |
| `token` | 与插件 `config.yml` 的 `token` 一致才放行；两边都空 = 不校验 |
| `disabledOps` | 禁用的 op 名，例如 `["seek"]` |
| `allowedHosts` | 只允许播放这些域名的 URL（防"服务器让我客户端去拉内网地址"） |
| `maxPerSecond` | 每秒最多接受多少条报文，超了丢弃并记日志 |

闸门不通过只打日志、不改任何状态。`/cineduct packet status` 会打印判定链。

## 不在协议里的（有意为之）

- **服务端算的进度**：服务端用"锚点 + 流逝时间"自己算，不读客户端状态
- **权限模型**：主人 / 管理员 / 信任 / 公开，纯服务端概念，客户端一概不知
- **播放列表**：服务端存、服务端切；客户端只收到"当前这条"的 `anchor`

## 变更规则

- 只允许**加字段**（客户端对未知字段宽容），不允许改已有字段语义
- 破坏性改动必须抬 `v`
- 新 op 先加进客户端的 `OPS` 白名单，否则整包被拒

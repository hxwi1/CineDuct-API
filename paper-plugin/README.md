# CinePlay —— Paper 侧参考插件

用服务器指令控制**装了 CineDuct 模组**的客户端播放。与模组完全解耦：
只依赖 Paper API + Bukkit 插件消息，**不需要模组的 jar**。

```
服务端 /cineplay play <链接>
     └─ 插件消息 cineduct:control（JSON）
          └─ 客户端模组：闸门 → 分派 → CinePlayer 播放
               └─ 插件消息 cineduct:state（JSON 回执，可选）
```

协议与所有字段定义在仓库根目录 [`docs/paper-control-payload.md`](../docs/paper-control-payload.md)。

## 构建

```powershell
.\gradlew.bat -p paper-plugin build      # 产物 paper-plugin/build/libs/cineplay-<版本>.jar
```

需要 JDK 21（编译期只依赖 `io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT`）。

## 安装

1. 把 jar 放进服务器的 `plugins/`。
2. **客户端**（装了 CineDuct 模组的玩家）要在 `<游戏目录>/cineduct-packet.json` 里打开闸门：

```json
{
  "enabled": true,
  "servers": ["你的服务器地址"],
  "token": "",
  "disabledOps": [],
  "allowedHosts": ["bilibili.com", "bilibili.tv", "b23.tv", "bilivideo.com", "hdslb.com"],
  "maxPerSecond": 20
}
```

- `servers` 是**包含匹配**，所以写 `mc.example.com` 能匹配 `mc.example.com:25565`；局域网测试就写你
  多人游戏列表里填的那个地址（`localhost:25565` 之类）。**留空 = 全拒**。
- `token` 与插件 `config.yml` 里的 `token` 要么都空、要么一致。
- 默认是**关**的：这条通道等于把播放控制权交给服务器，默认不接任何包。

3. 游戏里确认通道状态：`/cineduct packet status`。

## 指令

| 指令 | 作用 |
| --- | --- |
| `/cineplay play <链接> [slot]` | 在自己的客户端开播 |
| `/cineplay broadcast <链接> [slot]` | 给所有在线玩家开播 |
| `/cineplay pause` / `resume` / `stop` | 暂停 / 继续 / 关闭 |
| `/cineplay seek <秒>` | 跳转（直播无效） |
| `/cineplay volume <0-100>` | 音量 |
| `/cineplay aspect <比例>` | 显示面比例（16:9 = 1.7778；0 = 关闭信箱化） |
| `/cineplay rebuild` / `list` | 重建播放 / 请求回执 |
| `/cineplay status [玩家]` | 通道状态与最近一条回执 |

不带 `[slot]` 时用 `config.yml` 的 `defaultSlot`（默认 `main`）。权限 `cineplay.admin`（默认 OP）。

## 注意

- **P2 还没做**：客户端能收指令、能出声、能报位置，但这个插件本身**不提供画面** ——
  画面得等模组内置显示目标（`docs/paper-control-payload.md` §8 的 B 方案）。现在验收看
  `/cineduct packet status` 与 `/cineplay status`。
- `requireChannel` 默认 `false`：NeoForge 客户端是否通过 Bukkit 的 register 机制通告通道，
  各版本行为不一致，拿它当硬门禁会出现"客户端装了也发不出去"。发给没人监听的通道对原版客户端无副作用。
- 插件侧不做限流（客户端有 20 条/秒的闸门），但**不要在循环里调指令**。

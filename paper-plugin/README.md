# CinePlay —— Paper 侧参考插件

用服务器指令控制**装了 CineDuct 模组**的客户端播放。与模组完全解耦：
只依赖 Paper API + Bukkit 插件消息，**不需要模组的 jar**。

```
服务端 /cineplay play <链接>
     └─ 插件消息 cineduct:control（JSON）
          └─ 客户端模组：闸门 → 分派 → CinePlayer 播放
               └─ 插件消息 cineduct:state（JSON 回执）
                    └─ /cineplay status 显示成一行行状态
```

协议与所有字段定义在仓库根目录 [`docs/paper-control-payload.md`](../docs/paper-control-payload.md)。

## 构建

```powershell
.\gradlew.bat -p paper-plugin build      # 产物 paper-plugin/build/libs/cineplay-<版本>.jar
```

需要 JDK 21。编译期只依赖 `io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT`。

## 部署

- **服务端**：`F:\msl\MSL\Server5\plugins\`（本机这套 Paper 1.21.1 测试服，`paper-1.21.1.jar`）。
  换代时先删旧的 `cineplay-*.jar` —— 同一个插件留两份会被 Paper 拒绝加载。
- **客户端**：`F:\potato world\.minecraft\versions\1.21.1-NeoForge_21.1.264\mods\`
  （jar 本身）＋ 该实例根目录的 `cineduct-packet.json`（闸门，本地测试见下）。

## 安装

1. jar 放进服务器的 `plugins/`，重启。
2. **客户端**（装了 CineDuct 的玩家）要在 `<游戏目录>/cineduct-packet.json` 里打开闸门：

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

- `servers` 是**包含匹配**：写 `mc.example.com` 能匹配 `mc.example.com:25565`；局域网测试写你在
  多人游戏列表里填的那个地址（`localhost:25565` 之类）。**留空 = 全拒**（默认就是空）。
- `token` 与插件 `config.yml` 的 `token` 要么都空、要么一致。
- 默认是**关**的：这条通道等于把播放控制权交给服务器。

3. 游戏里核对通道：`/cineduct packet status`。

## 建屏流程

```
/cineplay create                 进入选区（5 分钟没操作自动取消）
手持木锄右键两次                  两个对角（第三次点击 = 重选第一个角）
/cineplay confirm <屏幕名字>      确认并命名（名字只能用 字母/数字/下划线/点/横线，1~32 字符）
/cineplay add <链接>             往播放列表加片子；列表原本为空就立刻开播
```

几何规则：屏幕**面朝玩家**（只取水平朝向）、**竖直摆放**（俯仰固定 90°），两个点击点投影到
该平面后取包围盒作为宽高（0.25~64 格）。选区期间只有**你自己**看得到粒子（两角标记 + 对角虚线）。

## 指令

两套形状：**不带 `to` 作用于自己**，**带 `to <目标>` 作用于别人**（目标用原版选择器）。

| 指令 | 作用 |
| --- | --- |
| `/cineplay play <链接>` | 在自己客户端开播 |
| `/cineplay to @a play <链接>` | 给所有在线玩家开播（`@a[distance=..32]`、玩家名都行） |
| `/cineplay pause` / `resume` / `stop` | 暂停 / 继续 / 关闭 |
| `/cineplay seek <秒>` | 跳转（直播无效） |
| `/cineplay volume <0-100>` | 音量 |
| `/cineplay aspect <比例>` | 显示面比例（16:9 = 1.7778；0 = 关闭信箱化） |
| `/cineplay rebuild` / `list` / `sync` | 重建播放 / 请求回执 / 对齐 |
| `/cineplay slot [名字]` | 查看 / 切换当前操作的槽位（客户端按名字区分多条播放） |
| `/cineplay status [玩家]` | 通道状态 + 客户端回执（状态/进度/音量/错误） |
| `/cineplay reload` | 重载 `config.yml` |

`URL` 用 `greedyString`（B 站直链带 `?a=1&b=2`，普通 string 参数吃不下），所以它**必须在最后** ——
槽位因此不做成参数，而是用 `/cineplay slot <名字>` 记住。

权限：`cineplay.use`（自己）、`cineplay.others`（`to` 与 `status <玩家>`）、`cineplay.admin`（`reload`），默认都是 OP。

## 注意

- **P2 还没做**：客户端能收指令、能出声、能报位置，但这个插件**不提供画面** ——
  画面要等模组内置显示目标（`docs/paper-control-payload.md` §8 的 B 方案）。
  现在验收看 `/cineplay status`（应有 slot 在 `PLAYING`、位置在走）与听声音。
- `requireChannel` 默认 `false`：见 `config.yml` 里的说明。
- 插件侧不做限流（客户端有 20 条/秒的闸门），但别在循环里调指令；`play` 自带 1.5 秒冷却。

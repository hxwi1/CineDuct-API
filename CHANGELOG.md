# MediaFix / CineDuct API 更新日志

> 3.0.0 起显示名改为 **CineDuct API**（modid `cineduct`、指令 `/cineduct`；Java 包名仍是 `dev.mediafix`），并从"前置 mod 附属"改为**独立模组**：不再需要 waterframes / watermedia / bilibili_media，屏幕是实体、指令驱动、带开放 API，播放走自研 FFmpeg 引擎 + 可选的 C++ 子进程零拷贝。

> 版本号规则：`<MMdd>-build-<通道>`，例如 `1025-build-alpha` = 10 月 25 日构建的 alpha 包。
> 更早的条目沿用的是当时的产出版本名，保留原样。

---

## CineDuct API 1025-build-alpha · 子进程缓冲 + Sodium 设置 + 账号权限 + 中英双语 (2026-10-25)

> 模组设置搬进了 Sodium 的「视频设置」界面，并按 B 站账号权限收窄可选档位；
> 同时把子进程的缓冲改成按秒计（起播预缓冲 + 卡顿闸门）。

- **Sodium 集成**：新增「播放 / 账号」页（清晰度、直播清晰度、零拷贝子进程、进程内硬解、避开 AV1、音轨偏好、音画同步、与服务端校验的最大延迟），
  只用 Sodium 0.8.13 的公开配置 API，不注入、不 Mixin；没装 Sodium 时功能完全不受影响。
- **账号权限限制**：未登录最高 720P、普通用户 1080P、大会员全开；音轨同理（杜比 / Hi-Res 仅大会员）。
  界面收窄（`allowedValues` + `UPDATE_ON_REBUILD`）与解析层夹紧两道，越权值不会真的去请求。
- **界面内扫码登录**：二维码直接画在设置界面里，可刷新状态 / 退出登录；登录态变化后权限缓存立即失效重查。
- **中英双语**：设置页与登录界面全部走语言文件（65 个键），`/cineduct login` 的聊天栏提示仍为中文。
- **许可变更**：项目改为 GPL-3.0-or-later，附 GPLv3 第 7 节的 API/Mixin 附加许可（独立项目调用 API / 注入不传染 GPL）；
  第三方声明（CicadaPlayer / OBS / FFmpeg / JavaCPP / ZXing）与许可全文随 jar 分发（`META-INF/`）。
- 修复：`hwAccel` 拆成「玩家偏好」与「本局运行时状态」，避免硬解自动降级被当成玩家选择写进配置。
- 文档：README 重写为中英双语；`deploy.ps1` 修正为按 `cineduct-*.jar` 部署（原先还在找 `mediafix-*`，必然失败）。
- **缓冲按秒计**：子进程包队列改用「首尾 PTS 跨度」限深（`bufferTargetMs`，默认 4000ms），
  网络侧稳定领先播放位置若干秒，不再只靠条数/字节判断。
- **起播预缓冲**：第一帧解出来后先攒够水位再开播、再放声卡（`prerollMs`，默认 1500ms）。
- **卡顿闸门**：供给见底、或游戏进程连续 1.5 秒没来取帧时，子进程把时钟与声卡一同冻住等缓存，
  恢复后从同一位置继续（`stallGateMs`，默认 1500ms；`bufferGate` 可一键关掉做对照）。
  闸门期间 `MediaSession` 与 `ScreenClientState` 会相应让路（不判卡死、不做进度纠偏）。
- **可观测**：状态块 v11 追加 9 个字段（水位 / 目标水位 / 闸门 / 消费者停顿 / 累计冻住时长），
  `/cineduct status` 新增「缓冲: 水位=…/…ms」一行，进出闸门各打一条日志。
- 缓冲部分的参数与取舍见 `docs/PLAN-preload-buffer.md`。

---

## CineDuct API 3.1.0-Beta · 默认改用进程内引擎 + 一批实测修出来的问题 (2026-10-06)

> 这一版把**默认播放方式反过来**了：默认走进程内 Java 引擎，C++ 子进程改成显式开启。
> 顺带修掉三处实测暴露的问题（直播音画偏移爆表、片尾被判成卡顿、循环回卷后时钟卡在旧位置），
> 并给子进程加了调试模式（cmd 窗口 + 自动落盘）。

### 🔀 默认播放方式：改为进程内 Java 引擎

- `cineduct-ffmpeg.json` 的 `gpuHostEnabled` 默认值 **`true` → `false`**；
- 子进程（DXGI 零拷贝 + WASAPI）改成显式开启：**`/cineduct subprocess on`**（写进配置，**永久生效**），
  `off` 关回去、`status` 看现状；开着时任何一步不可用仍会自动降级回进程内引擎；
- 新增 **`/cineduct fallback [on|off]`**：只在**本局游戏**里把播放压到进程内引擎（纯内存，重启即失效）；
- 为什么反过来：进程内引擎不挑显卡驱动（零拷贝要求 NVIDIA 的 `NV_DX_interop`），
  而且直播那套"系统时间锚定 + 实时延迟"只在它这条路上有。

### 🐛 直播：音画偏移恒为 -12925215ms，时钟被反复重锚

- 成因：新的"时间轴原点归零"（给直播/非零起点流用的）只应用在**画面**上，音频链上报的内容位置
  仍是**容器原始 PTS**（实测 3.6 小时 = 12925382ms，而画面 167ms）—— 两条时间轴不同源相减，
  偏移爆表，时钟被一次次重锚到 12.9M ms（位置显示成几小时、每 9 秒一条"已自动纠正"告警）；
- 修法：比较之前把音频内容也减掉同一个原点（点播原点为 0，行为完全不变）。
  实测 `bbb-livepts.ts`（开场 PTS=39 分钟）：可听 vs 画面全程差 **5~30ms**。

### 🐛 片尾被判成"卡顿"（缓冲耗尽 → BUFFERING）

- 成因：片尾预读见底时，引擎分不清"**流读完了**"和"**网络卡了**" —— 只看两个源的 `isEnded()`，
  而它要等解码线程把重排序窗口排空才置位，片尾那一刻永远还没真（原来靠一条 8 秒兜底盖着）；
- 修法：两个源新增 `inputEnded()`（**解复用一到流尾就真**），引擎新增 `tailOfStream()`：
  片尾不再判"视频缓冲见底/音频欠载"，`checkEnded()` 也不用再等满 8 秒。直播不受影响。

### 🐛 循环/回卷后时钟卡在旧位置（"疯狂校准"）

- 成因：`seek` 之后引擎手里那块音频（属于 seek 之前的位置，例如 59.4s）照写不误 ——
  音频源作废环形缓冲时**刻意保留 SHOWING 那一槽**（那块已经交给引擎了）—— 写完 `update()`
  就把时钟锚回 59.4s，而画面已经跳到 0：`视频与时钟差=-57996ms`，画面里每一帧都成了过期帧被丢光
  → 帧环空 → 判成饥饿 → 反复校准（seek 0 → 2002 → …）；
- 修法：真 seek 分支里**交还并丢掉**手里那块（不归还会把音频槽位漏光）、加 seek 代次
  （在途的那块也作废）、目标之前的旧块一律不写声卡。

### 🛠 子进程调试模式（cmd 窗口 + 自动落盘）

- 游戏目录放一个 `cineduct-host-debug.flag`（**空文件或同名文件夹都认**；是文件夹的话就把日志写进那个文件夹）
  即可开启；调试日志同时进**它自己的 cmd 窗口**、日志文件、以及 Java 侧的 `[host#N]` 诊断；
- 三条硬规矩（都踩过坑）：**只用自己的窗口**（先 `FreeConsole()` 再 `AllocConsole()`，绝不往继承来的
  终端窗口里写 —— 那个窗口一关会把挂在同一控制台下的东西一起带走）；**弹窗后把焦点还给游戏**
  （单人游戏一丢焦点就弹暂停菜单，进而把播放一起暂停）；**关过就不再弹**（写
  `console-closed-p<pid>.marker`，免得"关一次弹一次"）；
- 关着的时候也会打两行诊断（exe 目录 / 工作目录 / 标记文件候选是否存在），专门给"flag 放了却没反应"留线索。

### 📦 版本

- `mod_version` → **3.1.0-Beta**，产物 `cineduct-3.1.0-Beta.jar`。

---

## CineDuct API 3.0.0-hotfix · 修"两条音频一起播放"（**上一版自己引入的回归**） (2026-09-27)

> 反馈："现在是两条音频一起播放，一条是配合视频的，另一条不受我控制，但是音频内容都是一个视频里的。"

### 🐛 成因：HOST 模式掉进了 Java 兜底
上一版把子进程改成"推迟启动"，于是出现了一个**新的时间窗**：`mode` 已经是 HOST，但 `host` 还没建好。
`acquireDrawTexture()` 的 HOST 分支旧写法是 `if (mode == HOST && host != null) {…}`，**然后落到下面的
Java 兜底**——而那句兜底会 `ensureEngine()`，于是**真的把进程内引擎建了起来**（它自己开一块声卡）：

```
会话 #1 已创建: transport=(子进程启动中)
会话 #1 进程内引擎已启动        ← 0.3 秒后：第二条链
子进程已启动: pid=50272         ← 0.6 秒后：第一条链
```

两条链同时出声；音量/暂停指令只发给子进程，所以另一条"不受控制"。**跟时钟、跟槽位都无关。**

### 🔧 修复（三条不变式）
1. `acquireDrawTexture`：`mode == HOST` 时**绝不落到 Java 兜底**（没子进程就返回 -1 等它起来）；
2. `ensureEngine()`：**只允许 `mode == JAVA`** 时建（降级走 `failHost()`，它会先把 mode 切成 JAVA）；
3. `spawnHostNow()`：起子进程前先关掉这条会话上已存在的引擎（防御历史状态）。
4. 顺带把"推迟到第二帧启动"改回"**第一次取帧就启动**" —— 实测等待没有收益
   （附属模组是等**会话绑定**之后才推比例的，晚好几秒），却把上面那个危险窗口撑大了。

### ✅ 同一份日志还证实了上一版的正事：帧序不再回退
`09:18` 那次会话（跑的是带 `dropStaleSlots` + 单调性守卫的构建）：
`帧号回退` **0 次**、`保持上一帧` **0 次**（对照：修之前 25 秒 200 次）→ 画面往回跳的问题确实解决了。

---

## CineDuct API 3.0.0-hotfix · 修"画面会闪/帧顺序不对"（**根因：消费者往回画旧帧**） (2026-09-27)

> 反馈："视频的帧播放顺序不对，会闪一点。" → 反复确认："我之前没有，是你改完才有的"、"更严重了"。
> 中间按"槽位撕裂"和"子进程重开"各改过一轮，**都不是根因**（撕裂那版还把生产者冻死了、已回退）。

### ✅ 根因（由**新增的探针日志**抓到的硬证据，不是推测）

在 `acquireDrawTexture` 里加了一行只记日志的"帧号回退"探针后，现场日志（`logs/latest.log`）给出：

```
[CineDuct] 会话 #1 帧号回退 #1：槽 2 拿到帧号 9，上次已是 10
[CineDuct] 会话 #1 帧号回退 #100：槽 0 拿到帧号 756，上次已是 757
[CineDuct] 会话 #1 帧号回退 #200：槽 0 拿到帧号 1389，上次已是 1390
```

**25 秒 200 次** —— 每画 6 帧就有 1 帧是**比上一帧更旧的内容**。机制是"槽位残留"：

```
槽 1 = READY(帧 10)   槽 2 = READY(帧 9)      ← 两次发布都还没被取走
消费者挑最高帧号 → 画槽 1（帧 10）✓，并把槽 1 标成 SHOWN
生产者还没发下一帧；
消费者下一帧再挑 → 槽 1 不算了，只剩槽 2 = READY(帧 9) → **画了帧 9（往回跳）**
```

客户端帧率 > 视频帧率（59.94fps）时每帧都可能撞上，玩家看到的就是"闪一下旧内容"。
**这跟时钟、跟子进程重开都无关**：时钟那次会话里 `音频时钟停滞/挂钟节奏/已恢复` 一条都没有。

### 🔧 修复
1. **画完最新一帧就作废旧槽位**（`HostSession.dropStaleSlots(gen)`）：把帧号**不大于**刚画出去那一帧的
   READY 槽位全部标成"已取用"。它们已经被时间甩掉，留着只会制造"往回画"的机会。
2. **单调性守卫**（`MediaSession.acquireDrawTexture`）：万一还是只挑到更旧的帧（竞态），
   就**保持上一帧**（上一槽位的本地纹理是快照，生产者只写共享纹理、不会动它），
   绝不往回画。计数打到日志（`保持上一帧第 N 次`，正常情况下应该恒为 0）。
3. 验证方法：重启后看 `logs/latest.log` —— **`帧号回退` 应该归零**（探针保留，一旦再出现就是真乱序）。

### 🔧 顺带修掉的"开屏重开子进程"
| 日志指纹 | 触发者 | 代价 |
| --- | --- | --- |
| `会话 #N 显示面比例改为 1.7727 → 重开子进程以换输出尺寸` | 附属模组在**显示面第一次渲染**时才算比例（compat `DisplayRendererMixin` 的 vertex 重定向），比同一次渲染里的"取帧"更晚 | 每开一块屏白起一次子进程，首帧多等 ~2.5 秒（画面此时还没画出来，所以是"慢"而不是"闪"） |
| `独立播放器 #N 已重建会话 #N` | 附属模组（waterframes **刷新按钮**/`reload_all`） | 黑 ~1.2 秒 + 按 `resumeSeekMs` 续播 —— 这是**按了刷新**才有的，不是 bug |

- **子进程推迟到"第二帧"才启动**（`MediaSession.hostSpawnPending`）：第一帧取帧时比例还没到
  （它在同一次渲染的"画四边形"那一步才被推过来），第二帧就一定能拿到 → **一次都不用重开**。
  兜底自带 `cineduct-host-defer-N` 守护线程（**不能指望 tick**：`Sessions.tickHealth()`
  实测只在"退出世界那一 tick"被调用）。
- **比例阈值 0.005 → 1%**：附属模组每帧都算一次比例，16:9 的 1.7778 与脸面的 1.7727 只差 0.0051，
  卡在旧阈值上就会来回重开。
- **附属模组提前推比例**（`AspectRoute`：确定的点播页不再等"会话绑定"）：直播仍走几何、不动。

### ⚠️ 没做的事（刻意的）
时钟/节奏/槽位互斥（`shared_tex.cpp` 的 Acquire/Release 协议）**一行都没动** ——
这次的证据指向"消费者挑了更旧的槽位"，改生产者的互斥只会重演上一版把生产者冻死的教训。

---

## ~~CineDuct API 3.0.0-hotfix · 修"画面会闪/帧顺序不对"（槽位同步）~~ **【已全部回退，反面教材】** (2026-09-27)

> 反馈："视频的帧播放顺序不对，会闪一点。"（信箱化开启、4K/8K 输出之后更明显）
>
> **结局**：按这一版改完，用户报"改完更严重"，实测**生产者被冻死**（`发布` 帧数不再增长、画面卡住）。
> 已逐行回退（`shared_tex.cpp` 的盲选兜底、glshim 的"试另一把钥匙"、`GlInterop` 超时 8→4ms）。
> 教训写在 `docs/DEV-WORKFLOW.md` §6.6：**没有 `帧号回退` 探针的证据，不许再改槽位/同步代码。**

### 🐛 根因：写方与读方会同时碰同一张槽位纹理
共享纹理这条路上有两个口子，叠在一起就会撕裂（看起来就是"帧不对/闪一下"）：

1. **写方盲选槽位**：没有空闲槽位时 `nextSlot++ % N` 挑一个 —— 可能正是消费者**正在拷**的 READY 槽位；
2. **两边都"抢对方的钥匙"**：写方拿不到自己的 key 就去 `AcquireSync(读方的 key, 0)`，读方同理。
   而 D3D 键控互斥在同 key 上存在"直接成功"的行为（本仓 P1 打样就记过这条），
   于是两边同时以为独占 → 读方读到"半张新 + 半张旧"的帧。

之前看不出问题是因为每帧只有一次 `CopyResource`；信箱化之后每帧多了
`ClearRenderTargetView` + `VideoProcessorBlt`（4K/8K 全屏），写窗口从几百微秒变成毫秒级，
撞车概率随之飙升 —— 于是"新 bug"浮现（其实是老口子被放大）。

### 🔧 修复（两条铁律）
- **写方只写 EMPTY/SHOWN，没有空闲槽位就丢帧**（绝不覆盖 READY）
- **写方只 Acquire 0 / Release 1；读方只 Acquire 1 / Release 0；谁都不许抢对方的钥匙**
  （拿不到就丢帧/跳过本帧，继续显示上一帧）
- 读方超时从 4ms 提到 8ms（写方一次拷贝偶尔几十毫秒，等一下比跳帧好）
- 配套：**健康检查先看"最近有没有人来取帧"**（`lastDrawRequestAt`，2 秒窗口）——
  没人取帧时写方本来就会停下，这是正常的，不能再判成"子进程卡死"并反复重启

### 🧪 验证
不给消费者跑子进程（`--output-textures` 但没人取帧）：
```
M2 共享纹理: 发布 3 帧，丢弃 145
```
即"发布 = 槽位数 之后转为丢帧"✓ 不再覆盖 READY 槽位。游戏里用 `/cineduct player stats <id>`
看 `丢弃` 计数可以现场确认（屏幕可见时应该接近 0）。

---

## CineDuct API 3.0.0-hotfix · 音画偏移（画面领先的物理延迟补偿） (2026-09-27)

> 反馈："还是有延迟，画面跑在音频前面。"

### 结论：这不是时钟坏了，是**物理延迟**
画面是在"音频设备把这一段播出去"的时刻放出去的（子进程的节奏以 `playedFrames()` 为基准），
也就是说**在媒体时间轴上 A 与 V 是对齐的**；但声音从设备缓冲到**耳朵**还要过
HDMI → 回音壁/功放的处理（几十到两三百毫秒），于是看起来画面领先。这一段谁也测不准（驱动不报），
只能给一个偏移量，一边看一边调。

顺带查了设备自报的延迟：`设备报的流延迟: 0ms（引擎周期 10.0ms）` —— 驱动基本不报，
我们自己的设备缓冲（200ms）也不构成偏移（因为它已经从 `playedFrames()` 里扣掉了）。
所以剩下的量就是 HDMI/回音壁那一段。

### 🔧 新增：音画偏移，运行期可调
- 指令 `/cineduct av <毫秒>`（**正 = 画面提前，负 = 画面延后**），`/cineduct av` 看当前值
  - 你的情况（画面快）输负数，例如 `/cineduct av -120`，慢慢逼近到对上为止；`0` = 关闭
  - 改完**立刻生效**：零拷贝走控制通道新动词 `av-offset`（子进程按新偏移出帧，画面最多晚一帧），
    进程内引擎下一帧读到新值；同时写入配置，下次启动继续用
- 子进程新增 `--av-offset-ms N`（启动参数，取自 `FfmpegConfig.avOffsetMs`），启动时会打印一行
- 诊断：音频输出打开时打印 `设备报的流延迟: Nms（引擎周期 Nms；不含 HDMI/功放那段）`

### 关于"用系统时间差做时钟"
系统时钟**解决不了这个固定偏移**（它只能替代"漂移"这一类问题，而音频设备时钟在半小时内漂不到毫秒级），
反而会让画面脱离声音自己跑。真正需要挂钟的两种情况我们本来就有：
音频时钟停住时切挂钟（画面不停）、恢复时重新对表并把音频追回来。

---

## CineDuct API 3.0.0-hotfix · 改为「客户端必须、服务端可选」 (2026-09-27)

- `mods.toml` 增加 **`displayTest="IGNORE_ALL_VERSION"`**。
  默认的 `MATCH_VERSION` 要求"联机两边都装且版本一致"，于是**装了本模组的客户端进不去没装它的服务器**
  （服务端反过来也一样）。改成不校验之后：客户端装上就能玩；服务端装了才多出
  「屏幕实体 + `/cineduct screen` 指令」这套服务端权威状态，不装也不影响进服与播放。
- 描述文本同步写清安装约定；`README.md` 的说明一节也改了。
- 结构上本来就是"客户端必备"：解析、解码、播放、音频输出、渲染全在客户端；
  独立播放器 API（`dev.mediafix.api.player`）更是纯客户端，不需要服务端配合。
  另外本模组**没有任何自定义网络包**（屏幕状态走实体的同步数据），所以进"没装本模组的服务器"不存在兼容问题。

---

## CineDuct API 3.0.0-hotfix · 音画不同步（时钟）修复 (2026-09-27)

> 症状：放着放着画面和声音错开，一次之后再也不回来。日志里的指纹是：
> `seek -> 2ms（音频=ok）` 之后**再也没有** `音频已定位到 2ms`，3 秒后
> `音频时钟停滞超过 3 秒，已永久改为挂钟节奏`。

### 🐛 根因：音频解码线程在片尾直接退出，之后所有 seek 都没人执行
`AudioSource::decodeLoop()` 读不到包（= 片尾）时排空解码器就 `return` 把线程收摊了，
而 `AudioSource::seek()` **只是往 `requestSeekMs` 里登记请求**、等解码线程下一轮去执行 ——
线程没了，请求就永久没人执行：音频一个字节都不再产出，音频时钟自然停住；
3 秒后被判成"音频链已死"→ **画面永久改挂钟节奏**，声音还停在片尾那个位置 → 音画错开且不再恢复。

片尾是常态（放完、跳到结尾、循环前一刻都会到），所以这条路上任何一次 seek 都会踩中。

### 🔧 三处修复
1. **片尾不再退线程**：排空解码器后留在原地等命令（10ms 轮询 seek/quit），收到 seek 就回到循环开头执行
   —— `音频已定位到 …` 恢复打印，时钟继续走
2. **声卡 flush 的计数修正**：`flush()` 里 `Stop()` 会丢掉设备缓冲里没播的那段，而 `written` 是累计写入、
   不会减 —— 等于把被丢弃的帧算成"已经播过了"，音频时钟凭空前跳（最多一个设备缓冲），
   按时钟对齐的画面也跟着提前。现在记账到 `discarded`，`playedFrames()` = 写入 − 丢弃 − 未播
3. **音频时钟恢复后重新对表**（不再"一次卡顿 = 永久错位"）：停滞判定之后再观察，若时钟重新在走就恢复
   "以音频为准"；且若画面已跑到音频前面 >300ms，就**请求音频定位到画面位置**（非阻塞），两边重新对齐

### 🧪 验证
`native/eof-test.ps1` 全绿，其中关键两条：
- 「片尾后子进程仍存活 / 已上报 ended=1 / **seek 后已退出片尾待命**」—— 旧代码这条路上音频线程已死，
  现在 seek 后 `mediaClock` 与声卡时钟都在推进
- 「暂停/恢复/暂停中 seek」照旧全过（没有引入死锁）

---

## CineDuct API 3.0.0-hotfix · 可关闭的玩家提示 (2026-09-27)

- 新增 `CineDuctClientApi.setNoticesEnabled(boolean)` / `noticesEnabled()`：附属 mod 可以关掉
  聊天栏那些 `[CineDuct]` 提示（解析结果、"回退原生播放"、"屏幕播放失败…"）
- **"关"是粘滞的**：提示是全客户端共享的一条通道，一块屏可能同时被好几个 mod 驱动 ——
  只要有任何一个调用方希望它安静，就听那个"关"，本局游戏内后续 `true` 请求都被忽略
  （日志里说明一次）。`setNoticesEnabled(true)` 返回**实际状态**，被拒绝时返回 false
- 恢复通道：`/cineduct notice on|off|reset`（`reset` 清粘滞位，给排障用）；`/cineduct notice`
  与 `/cineduct api` 都能看到当前状态
- 关掉提示不影响功能与日志：命令反馈与 `cineductlogs/` 照常

---

## CineDuct API 3.0.0-hotfix · 新增 8K 清晰度档 (2026-09-27)

- 清晰度档位新增 **8K（qn=127）**：`/cineduct screen quality <屏> 8k`、`/cineduct-stream quality 8k`、
  API 侧 `qualityCap(127)`；`/cineduct screen info`、`/cineduct-stream quality` 的档位名也会显示 8K
- 解析链路本来就有 8K：`fnval=4048`（含 8K 位 1024 与 AV1 位 2048）、`describeQn(127)="8K"`、
  `maxQn=0`（不限）时的上限就是 127 —— 这次缺的只是**选项入口**
- **8K 的代价写进了日志与文档**：B 站 8K 基本只有 **AV1** 一种编码，而 `avoidAv1` 默认开着；
  但挑选规则是"清晰度优先"，所以显式选 8K 时那条 AV1 流仍会被选中（这是使用者的明确选择，
  不该被静默降级），同时打一条 warn 说明"没有 AV1 硬解就会退回软解、8K 软解跑不动"。
  同一清晰度内若同时有 HEVC/AV1，仍按编码优先级取 HEVC

---

## CineDuct API 3.0.0-hotfix · 独立播放器补齐四个 API (2026-09-27)

### 🔌 新增（`CinePlayerApi.API_VERSION` 仍是 1：纯新增，不破坏既有调用）
- **`CinePlayer.rebuild()`** —— 丢缓冲重开（零拷贝换新子进程）、位置尽量保留，走的是
  `MediaSession.rebuild()` 这条正规路径（屏幕那边 `/cineduct refresh full` 用的同一条）。
  与"close 掉再 open"的关键差别：**保留会话身份、不影响同源引用计数**，
  所以同源多屏时它真的会换新子进程，而 close+open 会被会话复用接回去、等于空转
- **`CinePlayer.statistics()`** + `PlayerStatistics` 记录：出帧/丢帧/等锁（last/max）/槽位数/
  子进程 pid 与存活时长/重启次数/分辨率/直播延迟/音频形态/错误。
  帧计数与进程信息来自子进程状态环，进程内引擎路径恒为 0（用 `zeroCopy()` 判断）
- **`CinePlayerApi.canHandle(String)`** —— **只看链接、不发任何网络请求**的判定：
  先问已注册解析器的 `canHandle(uri)`（内置 B 站解析器只看 host），再退回扩展名启发式。
  用途：附属 mod 在建屏/开会话之前就能判断"这个 URL 值不值得当视频处理"
- **`PlayerOptions.transport(Transport)`** + `Transport` 枚举（`AUTO` / `ZERO_COPY` / `IN_PROCESS`）：
  排障时强制走进程内引擎，或反过来要求零拷贝。传输偏好**参与会话复用判定**
  （一块屏要进程内、另一块要零拷贝时共用一条会话必然违背其中一个）

### 🧪 现场自检
```
/cineduct player rebuild <id>   # 规范化重建
/cineduct player stats <id>     # 出帧/丢帧/等锁/子进程存活/重启次数
```
`/cineduct help` 与文档（`docs/API.md` 15.8–15.10）已同步。

---

## CineDuct API 3.0.0-hotfix · 信箱化（真黑边） (2026-09-27)

> 目标：视频比例 ≠ 显示面比例时**不能让视频拉伸**，而且黑边要**真黑边**（烘焙进纹理，
> 而不是把四边形在几何上缩一圈、露出世界）。

### 🔌 新增 API
- `PlayerOptions.Builder.displayAspect(float)`：显示面宽高比（宽/高），`>0` 开启信箱化、`0` 关闭
- `CinePlayer.setDisplayAspect(float)` / `displayAspect()`：运行期改/读（同一比例重复设置无操作）
- 语义：开启后 `drawAspect()` 返回**这张纹理自己的比例**（= 显示面比例），调用方把整张纹理
  铺满自己的显示面即可，不需要也不应该再加一层边
- 指令：`/cineduct player aspect <id> <比例>`（`/cineduct player info` 里也会显示比例）

### 🔧 实现（两条链路都做，行为一致）
- **零拷贝（子进程）**：新增 `--fit-aspect`。子进程按这个比例算输出框（长边不变、短边补黑、偶数对齐、
  4K 封顶），用 `VideoProcessorSetStreamSourceRect`（裁掉 16 对齐的填充行）+ `VideoProcessorSetStreamDestRect`
  （画面等比落进框内）+ **每帧清黑**（`ClearRenderTargetView`，Blt 只写 dest rect，框外必须自己清）
- **进程内引擎**：上传前按同一比例做信箱化（沿用原有 `Letterbox`），比例从会话取，调用方不必每帧传
- 改比例：进程内下一帧生效；零拷贝输出尺寸是开进程时定的 → 自动重建一次子进程（位置续播）

### 🧪 验证（像素级，不靠眼看）
- 新增子进程诊断 `--dump-frame <file.ppm>`：把发布出去的首帧落成 PPM（零依赖格式）
- 实测：21:9 素材（1260×540）放进 16:9 显示面 → 输出 1260×708、**上下各 84px 纯黑**、
  内容区 1260×540 比例 2.3333（与源视频偏差 **0.000%**，即完全没拉伸），画面正中非黑
- 复现：`native/build/bin/cineduct-host.exe --url <视频> --fit-aspect 1.7777778 --dump-frame out.ppm`

---

## CineDuct API 3.0.0-hotfix · 通用独立播放器 API (2026-09-27)

> 新增 `dev.mediafix.api.player`：**不带屏幕实体**的播放会话。给"自带渲染"的附属 mod 用 ——
> 它只要"一条正在解码的会话 + 每帧取一张能采样的 GL 纹理"，进度/画面/生命周期自己管。

### 🔌 新增 API（`CinePlayerApi.API_VERSION = 1`）
- `CinePlayerApi`：`open(uri, options)`（**非阻塞**，解析在后台线程）/ `open(uri)` / `players()` /
  `player(id)` / `openPlayerCount()` / `diagnostics()`
- `PlayerOptions`（builder）：`audioMode` / `volume` / `muted` / `loop` / `paused` / `startAt` / `qualityCap`
- `CinePlayer`（句柄）：
  - 状态：`state / isReady / isLoading / isBuffering / isBroken / isLive / isSeekable /
    positionMs / durationMs / videoWidth / videoHeight / transport / lastError`
  - 出帧（渲染线程）：`acquireDrawTexture / releaseDrawTexture / drawAspect`
  - 控制（任意线程）：`pause / seek / setVolumePercent / setMuted / setRepeat / volumeLocked`
  - 生命周期：`close()`（引用计数，最后一个才真关）

### 🔧 实现要点（复用现成链路，没有第二套机制）
- 解析 → `SourceResolvers`；开会话 → `Sessions.open(stream, audioMode)`；取帧 → `MediaSession`
  —— 所以**子进程崩溃自愈、直链续期、会话复用、音频形态**这些行为与屏幕那套完全一致，
  同一个源的两块屏 + 一个独立播放器会共用一条会话
- **非阻塞 open**：句柄立刻返回，解析失败不抛异常，而是 `state()==ERROR` + `lastError()`
- **攒控制意图**：会话还没建好时推过来的 `pause/volume/muted/repeat/seek` 会先攒着、建好即补推
  （否则"设了静音，起播第一秒还是有声"）
- 所有状态读取**不阻塞、不抛异常**，没有会话时给安全默认值

### 🧪 现场自检（不依赖任何附属 mod）
```
/cineduct player open <链接>   # 真的解码 + 真的出声（走进程内引擎/子进程同一条链路）
/cineduct player list|info <id>|close <id>|closeall
```
`/cineduct api` 里也一并显示独立播放器 API 版本与诊断。

### 🐛 修复（同一天补的两处）
- **`GpuContext.probe()` 的兜底是死代码**：原来写的是 `if (GL.getCapabilities() == null)`，
  但 **LWJGL 在没有当前上下文时是抛 `IllegalStateException`，不会返回 null**
  （已实测确认：`No GLCapabilities instance set for the current thread…`）——
  于是"从后台线程误调"的路径是**抛异常**而不是作者注释里写的"退回进程内引擎"。
  现在整段探测都包了 try/catch，失败只记 `reason` 并返回 false，且"线程不对"这种失败**不永久缓存**
  （`probed` 复位，渲染线程之后还能再探一次），提示只打一次不刷屏
- **`Sessions.open()` 现在把"有没有探到 GL 适配器"一起纳入判定**：探不到就直接走进程内引擎，
  而不是带着 `luid=0` 去起子进程（那会在默认适配器上起来、之后互操作注册不上，白折腾一轮再回退）
- **独立播放器 `open()` 里补了渲染线程预探测**（写法同 `ScreenClientState.update()`）：
  原来那次探测放在后台解析线程里、还带 `RenderSystem.isOnRenderThread()` 条件 ——
  等于**永远探不到**，第一个用这套 API 的附属 mod 必然踩坑（会话按 `luid=0` 建）。
  现在探测在 `open()` 本体、不判线程：调用方在渲染线程触发就正常探到；在后台线程触发也只是探不到而退化，
  不会抛异常。任何用这套 API 的模组都不必再自己记得"先探一次"

---

## CineDuct API 3.0.0-hotfix · 开放 API 补全 (2026-09-27)

> 附属 mod 现在能调整屏幕的**全部数值**，不再需要绕过 API 直接改实体。
> 完整文档见 [`docs/API.md`](docs/API.md)（中文）。

### 🔌 新增 API（`API_VERSION` 1 → 2）
- **变换**：`setPosition(Vec3)`、`translate(dx,dy,dz)`、`rotation(yaw,pitch)`、`lookAt(Vec3)`（让画面正对某个世界坐标点）、`size(w,h)`
- **画面**：`alpha`（不透明度 0.05~1，半透明/淡出）、`brightness`（亮度倍率 0.1~4，自发光）、`fit(ScreenFit)`（等比黑边 / 裁切铺满 / 拉伸）、`doubleSided`（单面只留"画面正确的那一面"）
- **声音**：`audioMode("")`——**每块屏幕**单独的音频形态覆盖（`auto`/`shared`/`exclusive`/`passthrough`），影院屏可以强制直通、旁边的背景屏照样共享；形态不同的屏不再复用同一条会话
- **建屏即配好**：新增 `ScreenOptions`（builder 全参数）+ `createScreen(level, options)`；`ScreenInfo` 增补中心点/朝向/alpha/亮度/铺法/单双面/音频形态
- **事件**：`ScreenPlaybackChangedEvent.Change` 增补 `POSITION`/`ROTATION`/`ALPHA`/`BRIGHTNESS`/`FIT`/`DOUBLE_SIDED`/`AUDIO_MODE`
- **客户端 API**：`ClientScreenStatus` 增补实际分辨率、直播延迟、空间音效状态、音频端点名；新增 `shaderDiagnostics()` / `shadersInUse()`
- **指令**：新增 `rotate` / `move` / `alpha` / `brightness` / `fit` / `mirror` / `audio`，`info` 输出补全所有数值

### 🎬 光影（Iris）
- **屏幕改为走 `RenderType` 提交**（`entitySolid` / `entityTranslucentCull` + `MultiBufferSource`）：
  光影加载器是按 RenderType 识别绘制的，自己拿 GL 直绘会绕开这层识别（包的程序接不上 → 屏幕发黑或画不出来）。
  画面纹理由 `TextureManager` 里的"id 转发壳"`ScreenVideoTexture` 提供 —— GL 纹理仍归会话所有，
  子进程导入的互操作纹理不用拷进 MC 纹理，传输方式切换对渲染侧透明
- **自发光坐实**：顶点写满亮光照贴图（`LightTexture.FULL_BRIGHT`）+ 面法线 + `NO_OVERLAY`，
  光影包里那些 `color *= lightmap` 的程序不会再把屏幕算成"零光照"而发黑
- 两面分开画（正面法线 +Z、背面 -Z，背面镜像），不再依赖 `disableCull` —— 因为 RenderType 会剔除背面
- 装了 Iris 时**阴影贴图 pass 里不再画屏幕**（否则一块发光的屏会投出画面形状的阴影）——
  `IrisCompat` 纯反射，不想耦合可随时删
- 亮度语义收紧为 **0.05~1（只能调暗）**：走 RenderType 后顶点色是 8 位归一化值，
  上限就是 1.0；以前直绘用 float 乘子能过曝，但那条路光影认不出来
- **仍不做**：不写 gbuffer、不参与光照/反射/光追（自发光已经够用）。
  做法参考同类模组 WaterFrames（**只复刻思路，不抄代码**），说明写在 `docs/API.md` 第 13 节

---

## CineDuct API 3.0.0-hotfix · 直播修复 (2026-09-26)

> 现场表现：放**直播**时屏幕一闪一闪、聊天框每 5 秒一条"屏幕播放失败：正在解析…"，
> 日志里子进程"启动失败 → 自动重启 → 再失败 → 回退进程内引擎"。全部定位到三个独立缺陷：

### 🐛 修复
- **子进程拉不了直播：403 Forbidden** —— 子进程里 FFmpeg 的默认 UA 是 `Lavf/8.0.1`，B 站**直播** CDN（`live-bvc`）直接回 403；点播 CDN 不挑 UA，所以这个坑只在直播上暴露。同一条直链 + 同一份 Referer/Cookie，进程内引擎（一直带 Chrome UA）能打开、子进程必挂。现在 `HostSession` 传 `--user-agent`（与 `FfmpegVideoSource.UA` 同源），子进程也内置同一个默认值；顺带补齐 `reconnect / reconnect_streamed / reconnect_delay_max` 与进程内引擎一致
- **直播有画面没声音** —— 同一个 UA 坑的第二处：音频是**另开一路输入**去拉同一条流的（探测链 `probeAudio`、解码链 `AudioSource::open`、直通链 `AudioBitstream::openInput`），这三处以前只带 headers、不带 UA → 直播上 403 → `音频不可用，改为纯视频: 打不开音频链`，于是画面正常、一点声音都没有。现在 UA 一路传到底（`AudioChain::open` 增加 `userAgent` 参数），所有 `avformat_open_input` 调用点都带上它
- **子进程放直播永远不出画面** —— 直播的 PTS 不从 0 起（实测首帧 `2550283ms` ≈ 42 分钟），而音频时钟在首帧发布时锚定成 0，拿原始 PTS 去比 → 子进程要等 42 分钟才允许放第一帧。现在统一用"时间轴原点"归一（`mediaPtsMs = pts − 首帧 PTS`），点播原点为 0、行为不变
- **屏幕闪烁** —— Java 传输下拿不到新帧时 `acquireDrawTexture` 返回 -1，渲染器那一帧**什么都不画**（直播缓冲薄、反复进出 BUFFERING 时最明显）。现在**保持上一帧**，只有"一帧都还没上传过"才留空
- **聊天框刷"正在解析…"** —— 起播那几秒本来就没帧，却每 5 秒喊一次，看起来像"模组在反复重新拉流"。现在真错误立刻报，只是没画面则先给 15 秒宽限、最多提醒两次

### 🧪 新增自检
- `native/live-smoke.ps1`：解出当前直播直链，用**两个 UA 做对照实验**跑子进程（Lavf UA 应 403、Chrome UA 应打开），再验共享纹理发布速率。全程 `--volume 0` 静音
- 实测（原画 1280x720 直播）：子进程发布 **603 帧 / 20 秒 = 30.1 fps**、丢弃 0，音频时钟与挂钟一致

---

## v3.0.0-hotfix (2026-09-24) —— 播放稳定性修补

> 3.0.0 上线当天实测暴露的几类问题，全部集中在**状态机与同步**上（不动解码 / 时钟 / 帧管线）。

### 🐛 修复
- **画面全黑：引擎被误关** —— waterframes 换播放器时会 `release()` 掉旧对象，5 秒后旧对象被 GC，它当初留在回收表里的弱引用**又给引擎减了一次计数**，把"已经转交给新播放器、正在出画面"的引擎判成孤儿，20 秒后被回收线程关掉。引擎一关 `video/audio` 源置空 → `isBroken()=true` → `readyForDisplay()=false` → waterframes 的 `canRender()` 恒 false → 画面再也不画（日志里 `canRender=false … 引擎状态=PLAYING 引擎就绪可显示=false` 一直刷）。现在：只有**没有任何活着的播放器持有**时才挂孤儿；到点回收前再核一遍，仍被持有就顺延；显式回收过的播放器不再重复减计数；`of()` 不再把已关闭的引擎当活引擎回答状态
- **片尾死锁：播完一直显示"正在加载"** —— `checkEnded()` 原本只在音频投喂循环"取不到下一块"时调用，而视频预读见底时的 `rebufferHold` 会让整个循环跳过；视频播到片尾预读必然见底且再也回填不上（流已读完），于是片尾判定一次都跑不到：引擎既进不了 ENDED 也不停，被 4 秒兜底在 BUFFERING ⇄ PLAYING 之间来回拽（实测 11:18:59~11:19:12 十几秒，取帧计数一动不动）。现在兜底线程也做片尾判定；并补一条保守判据：**位置已抵住片尾 + 连续 8 秒出不了帧 + 压缩预读为 0** 也算播完（视频源在片尾卡"等网络"时 `ended` 永远为 false）。日志新增 `播放结束（时长 …ms）`
- **播完后重新请求视频要手动刷新** —— 引擎停在片尾时，waterframes 新建播放器=重新播放，而转交过去的引擎还站在片尾（帧环空、出不了帧）。现在转交时若引擎已 ENDED 就自动回起点
- **在服务器播放时进度对不上** —— 两处：① 进度守卫把"服务端下发的往回跳"也当成陈旧同步拦掉（实测连续拦下 `目标 2059150 / 823650 / 0ms，当前 2562500ms`）；② 更下游一层：seek 之后声卡的"已播出位置"要等缓冲回填并真正播出去才跳（实测 6 秒），而视频 1 秒内就落到新位置，脱节自愈于是"按时钟纠正视频"，把刚对齐好的位置又拽回旧位置（`11:06:36 自愈 → 视频 seek 回 66303ms`）。现在：新增 `TimePacket` 打点识别"服务端明确下发的位置"并放行；守卫只拦"没有下发、又跳回 ≈0"的那种；seek 过渡期内不做脱节自愈，改为等音频时钟跟上
- **暂停状态下换源 = 全黑** —— waterframes 的 `switchVideoMode()` 会把 `data.paused` 直接套给新播放器，于是新引擎一出生就是暂停；我们以前连解码一起停掉，一帧都没有 → 黑屏（看起来像"直播坏了"，实测整场 20 秒一直是 PAUSED）。现在暂停时仍会把"当前这一帧"解出来并显示（VLC 的行为），补完立刻冻回去；渲染侧只在"这个播放器还没上传过任何一帧"时才要这一帧，不会每帧重复上传 4K 图
- **拼接处的音频爆音** —— 采样级爆音检测只看我们自己的数据块边界，而三类接缝在数据里是**连续的**、检测器根本看不见：设备缓冲写空（欠载）后声卡已经播出一段数据里没有的静音、`flush()` 把波形中途硬切（seek / 就地快进）、暂停恢复时的线路 stop/start。实测 3.5 小时会话里 `爆音=0 去咔=0`，但耳朵能听见。现在这三类接缝一律强制做 3ms 余弦淡入去咔（原来只在检测到跳变时做，且只有 1ms）

### 📋 新增诊断
- `waterframes 要求暂停/播放：方块数据 paused=… active=… tick=…`（谁让我们暂停的一目了然）
- `跟随服务端进度（…）：目标 …ms，当前 …ms` / `seek 过渡期：视频已在目标 …ms，等音频时钟跟上`
- `播放结束（时长 …ms）`；`无人接管，关闭孤儿引擎` 之前会先核对是否真的无人接管

---

## v3.0.0 (2026-09-24) —— 自研 FFmpeg DASH 流式引擎

> 这一版把播放链路整个换掉：**VLC 只做外壳，解码 / 时钟 / 音频输出 / 换链全部自研**；同时把"先下载缓存再播放"整条移除，只保留合规的流式直连。

### 🎬 引擎（新增 `dev.mediafix.engine` / `dev.mediafix.ffmpeg`）
- `MediaEngine`：状态机（LOADING / PLAYING / PAUSED / BUFFERING / ENDED / ERROR）、自研时钟、看门狗、水位线、ABR 调度、直链续期、无缝换源
- `FfmpegVideoSource`：FFmpeg 解 DASH 视频，D3D11VA 硬解；GPU→CPU 搬运后多线程**分带 sws** 转 RGBA；帧环背压；就地快进
- `FfmpegAudioSource` + `AudioSink`：自研音频链与声卡输出（Java Sound），多声道协商、软件增益、欠载 / 截断 / 爆音计数
- `MediaClock`：以**音频可听位置**为主时钟（死区 40ms + 残差 EMA + 速率微调 ≤3%/s + 单次离群剔除 / 连续离群硬对齐）
- `PacketBuffer`：压缩流预读队列（视频 48MB / 音频 8MB），解复用与解码分离、互不阻塞
- `MediaEngines`：引擎注册表（WeakHashMap + ReferenceQueue 回收）；播放器对象被重建时**引擎转交**、孤儿宽限

### 🔊 音频
- 杜比全景声（E-AC-3 6ch）/ Hi-Res 无损（FLAC）/ 普通 AAC 按偏好自动选择，缺失自动回退，不会没声音
- 多声道按设备能力输出；源 5.1 遇 2ch 设备时按**标准下混矩阵**折立体声（不是丢声道）
- 新增 `/mediafix audio [dolby|hires|best]`

### 📶 清晰度
- ABR 自适应（默认 `auto`）：按实测下载吞吐在可用档位间升降（保守取 min(快/慢) 估计、下档 0.90 / 上档 0.70、带驻留时间约束）
- 档位去重 + 编码偏好（H.264 > HEVC > AV1），默认**避开 AV1**（无硬解时软解 4K 只有个位数帧率）
- 新增 `/mediafix streams`：列出每条流的编码 / 真实分辨率 / 码率，可辨别"标称 4K 实为 1080p"

### 📺 B 站直播（新增）
- **链接识别**：`live.bilibili.com/<房间号>`（兼容 `/blanc/`、`/h5/`、`?room_id=`）
- **新增 `bili/BiliLive`**：调 `xlive/web-room/v2/index/getRoomPlayInfo`（`protocol=0&format=0&codec=0&qn=10000`）拼出 FLV 直链；未开播 / 付费加密 / 无可用组合一律给提示并回退前置模组
- **音视频同一条流**：直播 FLV 里 aac 与 h264 复用同一根时间轴，所以 video = audio = 同一个直链；固定原画，直播不参与 ABR
- **系统时间锚定时间轴**：直播时间戳是服务器侧固定偏移，实测与系统时间 1:1（8 秒窗口漂移约 6ppm，换算即本地时间）。用它把原始 PTS 折算成"开播以来"的位置并算出**实时延迟**；断流重连沿用同一锚点
- **seek 全部忽略**（含进度同步与暂停同步，留一条日志）、**换源不做定位**（直播打开即在边缘）
- **断流自动重连**：CDN 掐流 / 主播重推 / URL 到期（直播直链带 `expires=`）自动重新取链并热切换；下播每 5 秒重试，重开自动接上
- `DashHandoff` 的直播标记同时进线程内交接棒与按主 URI 的全局表（解析在 ImageFetch 线程、引擎在播放器线程创建，只存线程内会丢）
- **顺带修掉两个直播专属 bug**：① `readyForDisplay()` 的"等时长"约束把直播（duration = −1）永远判为未就绪 → waterframes 的 `canRender()` 恒 false → 画面永远不渲染（表现为"只有声音没画面"）；② 直播标记没跨线程 → `seek` 守卫失效，直播被 `seekTo(0)` 打断（落点偏后十几秒）

### 🔁 稳定性（本版集中修掉的几类问题）
- **时钟不再被永久冻住**：修复"seek / 视频饥饿 pause 时钟后，状态被翻成 PLAYING 却没人 start 时钟"。该状态下 `update()` 只把冻结时间设成音频可听位置，时钟退化成**每批音频跳一次（~200ms）**，视频帧成批过期 —— 表现为起播后长时间只有 3~4fps、每秒丢 21 帧，且只有人工暂停/恢复才能救回
- **直链续期**：到期前自动重新解析并热切换（声音不断、位置不变）；新增 `/mediafix refresh [full]`
- **就地快进**：进度同步要求小幅前跳时在已预读数据里丢弃，不重开连接、不清空缓冲（消除"卡一下 → 倍速追赶 → 再卡"）
- **帧归还与数据源解耦**：帧自带产出它的那条源，换链 / 关闭后归还不再落空（此前实测取走 550 帧只归还 90 次）
- **纹理强制重分配**：播放器实例或纹理 id 变化时重建纹理存储，修掉"多次换视频 / 刷新后只有声音没画面"
- **停止 / 暂停优先**：看门狗兜底不再覆盖用户的暂停意图（此前按停止后 4 秒会被拽回播放）
- **原地 seek 过滤 + 陈旧同步拦截**：避免无谓的"重开连接 + 清空音频缓冲"
- **同源判定按流路径**：直链签名每次解析都变，也能认出"同一个视频"，播放器重建时不再重新缓冲

### 🗑️ 移除
- 整条"先下载到本地缓存再播放"路径（`MediaStreamProxy`、`MediaCache`、`SimpleFileServerMixin`、下载进度 UI）—— 下载到本地属于灰色地带，现在只做流式直连
- `/mediafix ffmpeg on|off|offset`（引擎常开）、`/mediafix-stream cache` 相关指令

### ⚙️ 配置与工具
- 新增 `mediafix-ffmpeg.json`（原生库路径 / 硬解 / 预缓冲 / 缓冲区）、`mediafix-bili.json`、`mediafix-log.json`
- `mediafix-stream.json` 增加 `audioPreference`、`avoidAv1`、`catchUpMaxMs`
- 新增 `deploy.ps1`：一键构建 + 部署到客户端实例（**只动 `mediafix-*` 文件**）

### ✅ 已离线验证
- 打包的 FFmpeg 原生库可加载（`av_version_info = 8.0.1`）
- 扫码登录全链路（生成二维码 → PNG → 解码比对 → 轮询 `qrcode_key`）
- 杜比音轨可解（`eac3 6ch 48000Hz`，80/80 帧解码成功）
- 音频 seek 落点误差 −22 ~ 0 ms；视频 seek 落点与首帧时间戳符合预期（第 0 帧 = 0.0ms）
- **直播接口与流**：可用组合、JSON 层级逐级核对（`playurl_info.playurl.stream[].format[].codec[].url_info[]`）；打包的 FFmpeg 直接打开直播 FLV（590ms，流内 `aac 2ch 48k` + `h264 1080p60`，连读 200 包正常；HLS 打开要 4.3s，故固定用 FLV）
- **直播时间轴**：`PTS − 系统时间` 为恒定值（8 秒窗口 ±50ms ≈ 6ppm），换算结果与本地时间吻合；断流重新取链后两段流该偏移只差 0.45s → 复用同一锚点即可无缝续播

---

## v2.0.0 (2026-09-21)

### 🎉 重大功能

**DASH 高清缓存播放**（`DashResolver.java`）
- 通过 wbi 签名 + `fnval=4048` 请求 B 站 DASH playurl，绕过前置 durl 链路的 `platform=html5` 限制
- 支持 4K / 1080p60 / 720p 等全档位，最高码率自动挑选
- 杜比全景声优先选择（dash.dolby 块），下载失败自动降级普通音轨
- 视频与音频分轨下载（`.part` 临时文件 → 原子改名，缓存命中直接复用）
- 下载进度实时显示在 actionbar，完成/失败走聊天栏提示

**番剧 / 大会员内容支持**
- 新增 `BilibiliBangumiPatchMixin`：拦截 bilibili_media 的番剧独立解析器（`BilibiliBangumiPatch`），ep 链接转发给 DashResolver
- 走 `pgc/player/web/playurl` 接口（无需 wbi 签名），带 Cookie 换大会员清晰度
- 自动处理试看（`is_preview=1`）→ 回退原生，DRM（`is_drm=1`）→ 跳过
- 缓存文件命名：`mediafix_ep<epId>_v.mp4` / `_a.m4a`
- 番剧 DASH 音轨字段兼容（`baseUrl/base_url/backupUrl/backup_url` 四种键）

**input-slave 音频桥**（`BasePlayerDiagMixin.java`）
- watermedia 2.1.36 的 `BasePlayer` 完全忽略 `Result.audioUrl`，DASH 独立音轨无法自动挂载
- 在 `play()` 调用处，从视频缓存 URL 直接推导出音频缓存 URL，注入 VLC `:input-slave` 选项实现分轨挂载

### 🛠️ 画面修复

**GL 纹理污染修复**（`VideoPlayerMixin.java`）
- watermedia 的 `VideoPlayer.lambda$display$0()` 每帧在"当前活动纹理单元"上传视频帧后，不恢复 `GL_ACTIVE_TEXTURE` 和 `GL_TEXTURE_BINDING_2D`
- 修复：在 HEAD/TAIL 保存并恢复 GL 状态，避免后续渲染误采样到上一个输入的玩家皮肤贴图 → 画面串成玩家皮肤

**信箱化自适应（不拉伸、允许黑边）**
- `DisplayAspectMixin`：每帧捕获屏幕格宽高比 → `LetterboxState`
- `VideoPlayerMixin`：用 `@Redirect` 重定向 `RenderAPI.uploadBuffer` 调用到 `Letterbox.upload`
- `Letterbox.java`：按屏幕比例居中拷贝视频帧到黑色画布后上传，视频不拉伸变形
- 修复：首次实现误把 Canvas 内部类写在 mixin 包 → `IllegalClassLoadError`；现已搬到普通类
- 修复：`uploadBuffer` 实际调用点在 `lambda$display$0` 而非 `preRender`，注入点写错导致 `Scanned 0 target(s)` → 类加载硬崩
- 修复：逐行拷贝时帧缓冲实际大小不足（`newPosition > limit`）→ semaphore 永远不 release → 1 秒超时 → 播放器挂死黑屏；现已加越界防御，异常时整幅透传

### 🔧 多人同步修复

**进度纠偏阈值**（`WaterframesSeekGuardMixin.java`）
- waterframes `Display.tick()` 在多人同步时双闸条件 `|本地时间-远端时间|>TH && |本地时间-上次时间|>TH` 触发 `seekTo(0)`
- 硬编码阈值 2 秒太敏感 → 偶尔把已播了一段的视频拉回开头
- **v2.0.0 改动**：阈值默认改为 **5000ms（5 秒）**，通过 `SeekGuardConfig` 可配置
- 新增 `/mediafix seekguard [秒]` 指令（1~60 秒范围），**实时生效无需重进游戏**
- 仅拦截"目标为 0 且已播超阈值且不在片尾"的陈旧同步，正常循环/重播（片尾触发）和玩家手动拖动全部放行

### 🔊 音频

**全景声 WASAPI 强制输出**（`PanoramicAudioMixin.java`）
- VLC 默认直通系统音频；部分环境下被 Windows 音频栈强制下混立体声
- 强制 VLC 使用 Windows WASAPI（`--aout mmdevice`），5.1/7.1 多声道视频按原生声道数直出
- 通过 Mixin 注入 `require=1` 确保前置 BasePlayer 的选项配置点存在

**视频音量跟随 Minecraft 主音量**（`VideoPlayerVolumeMixin.java`）
- watermedia 的 `setVolume` 定义在父类 `BasePlayer`，Mixin 必须瞄准 `org.watermedia.api.player.videolan.BasePlayer` 而非 `VideoPlayer`

### 👁️ WorldComment 评论隐藏

- `CommentHideMixin` + `CommentHide` 配置类
- `/mediafix hide [on|off]` —— 切换全隐藏，配置存 `mediafix-comment.json`
- 可选依赖 WorldComment，缺失时静默跳过

### 📋 指令一览（v2.0.0 新增 help）

| 指令 | 说明 |
|------|------|
| `/mediafix help` | 显示本帮助（**v2.0.0 新增**） |
| `/mediafix hide` | 切换 WorldComment 评论隐藏（无参 = 切换） |
| `/mediafix hide on/off` | 开启/关闭 |
| `/mediafix seekguard` | 查看当前进度纠偏阈值（默认 5 秒，**v2.0.0 改动**） |
| `/mediafix seekguard <秒>` | 设置阈值（1~60），实时生效并写盘 |
| `/mediafix-stream` | 无参查看当前配置 |
| `/mediafix-stream on/off` | 开启/关闭 DASH 高清缓存播放 |
| `/mediafix-stream quality <模式>` | 设置清晰度（360p / 480p / 720p / 720p60 / 1080p / 1080p60 / 4k / auto） |
| `/mediafix-stream cache` | 查看缓存占用统计 |
| `/mediafix-stream cache clear` | 清空所有视频缓存文件 |

### ⚙️ 配置文件（游戏目录）

| 文件 | 键 | 默认值 | 说明 |
|------|---|--------|------|
| `mediafix-stream.json` | `stream` / `maxQn` / `highres` | true / 116 / true | DASH 缓存开关、清晰度上限（qn）、高分辨率开关 |
| `mediafix-seekguard.json` | `seekGuardMs` | 5000 | 进度纠偏阈值（毫秒） |
| `mediafix-comment.json` | `enabled` | false | WorldComment 评论隐藏开关 |

### ⚠️ 已知限制

- **纯客户端**：本 mod 仅客户端侧生效，服务端加载时静默跳过
- **依赖前置 mod**：watermedia 2.1.36 + waterframes 2.1.23 + bilibili_media 2.3；缺少任一 DASH 链路自动回退原生
- **DRM / 试看番剧**：pgc 接口返回 `is_drm=1` 或 `is_preview=1` 时自动回退前置原生播放
- **缓存管理**：DASH 缓存文件存 `BiliBiliMediaFiles` 目录，需手动清理或用 `/mediafix-stream cache clear`
- **VLC 日志**：`VlcLogMixin` 需 waterframes 的 VLC 日志配置点存在（静态字段非 private），否则注入静默失败

### 📦 前置依赖

```
bilibili_media  → 2.3       (compileOnly, 可选依赖)
watermedia      → 2.1.36    (compileOnly, 可选依赖)
waterframes     → 2.1.23    (compileOnly, 可选依赖)
WorldComment    → 任意      (compileOnly, 可选依赖)
```

---

## 历史版本（摘要）

### v1.1.x ~ v1.2.x（迭代期，功能逐步稳定）
- 先下载后播策略定型（放弃流式代理，解决"连接拒绝"问题）
- VLC 媒体探针线程曾导致 JNA "Invalid memory access" 硬崩 → 已删除
- waterframes 缓存 FORGOTTEN 重建 Display 时新旧 VideoPlayer 短暂并存 → GL_INVALID_OPERATION 刷屏（暂未证实可致崩）

### v1.0.x（初始版本）
- 修复多人播放黑屏（扩大 HTTP 线程池 + 随机端口 + Range 分片）
- 修复画面串成玩家皮肤贴图（GL 状态保存/恢复）
- 修复进度自动跳回开头（seek 纠偏逻辑）
- 全景声 WASAPI 强制输出 + 音量跟随主音量

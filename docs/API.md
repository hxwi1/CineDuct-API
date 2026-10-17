# CineDuct API 中文文档（附属 mod 开发接口）

> 面向写附属 mod 的人。**唯一兼容承诺是 `dev.mediafix.api` 这个包**
> （含 `.api.client`、`.api.event`、`.api.player`）；
> `dev.mediafix.player` / `.screen` / `.client` / `.engine` / `.host` / `.gpu` / `.proxy` 都是内部实现，
> 随时可能改，**不要依赖**。
>
> 当前版本：`CineDuctApi.API_VERSION = 2`、`CineDuctClientApi.API_VERSION = 2`、
> `CinePlayerApi.API_VERSION = 1`。
> 不兼容改动时 `API_VERSION` +1（`CineDuctApi` / `CineDuctClientApi` 上一版都是 1：
> `ScreenInfo` / `ClientScreenStatus` 有字段增补，新增 `ScreenOptions`、`ScreenFit`，
> `ScreenHandle` 多了位置/角度/画面参数等方法）。
>
> 附属 mod 有**两种接法**：把画面交给 CineDuct 的**屏幕实体**（第 3–12 节），
> 或者只借它的**播放会话**、画面自己画（**独立播放器 API**，第 15 节）。

---

## 目录

1. [心智模型](#1-心智模型)
2. [快速上手](#2-快速上手)
3. [创建屏幕](#3-创建屏幕)
4. [屏幕变换（位置 / 角度 / 大小 / 铺法）](#4-屏幕变换位置--角度--大小--铺法)
5. [播放控制](#5-播放控制)
6. [声音](#6-声音)
7. [画面参数](#7-画面参数)
8. [读回状态](#8-读回状态)
9. [客户端 API](#9-客户端-api)
10. [事件](#10-事件)
11. [自定义源解析器（支持别的站点）](#11-自定义源解析器支持别的站点)
12. [指令对照表](#12-指令对照表)
13. [光影（Iris）现状](#13-光影iris现状)
14. [全局配置（不属于每屏数值）](#14-全局配置不属于每屏数值)
15. [独立播放器 API（不带屏幕实体）](#15-独立播放器-api不带屏幕实体)
16. [线程模型与常见坑](#16-线程模型与常见坑)
17. [内部实现（别依赖）](#17-内部实现别依赖)

---

## 1. 心智模型

**接法一：用 CineDuct 的屏幕实体**（第 3–12 节）

```
服务端：屏幕实体（cineduct:screen）持有【权威状态】
        源 / 锚点（播到哪一刻）/ 暂停 / 循环 / 音量 / 距离衰减 / 清晰度上限
        尺寸 / 位置 / 角度 / 不透明度 / 亮度 / 铺法 / 单双面 / 每屏音频形态
   ↑ 你在这里**写**：CineDuctApi + ScreenHandle（自动调度到服务端线程）
   ↓ 客户端：各自解析直链、各自播放、各自渲染
        画面传输两种，自动选择：dxgi-interop（C++ 子进程零拷贝）/ java（进程内引擎兜底）
   ↑ 你在这里**读**：CineDuctClientApi（实际传输方式、位置、分辨率、丢帧、音频形态、错误）
```

**接法二：只借播放会话，画面自己画**（第 15 节，`dev.mediafix.api.player`）

```
客户端：CinePlayerApi.open(链接)  →  CinePlayer 句柄
   ↑ 你：每帧 acquireDrawTexture(...) 取一张能采样的 GL 纹理 → 画在你自己的渲染里 → release
   ↑ 你：进度/位置/音量/循环/暂停自己管（没有服务端权威那一套，也不创建实体）
   两种接法**共用同一套会话层**：同一个源只解一份、只占一份声卡，
   子进程崩溃自愈、直链续期、音频形态等行为完全一致
```

**进度为什么不会跑偏**（接法一）：服务端只存锚点 `{positionMsAtAnchor, 锚点代次, paused}`，
每个客户端各自按**真实时间**算出"此刻该播到哪"，只在锚点变化时对齐一次。
你无法（也不需要）从服务端"推进度" —— 要跳转就 `seek(ms)`，它会钉一个新锚点，所有客户端跟着对齐。

**一块屏 ≠ 一条会话**：同一个源的 N 块屏共享一条会话（只解一份、只占一份音频设备）。
所以"每屏音频形态"也会参与复用判定：形态不同就不复用。
独立播放器（接法二）走的是同一张会话表，所以它和屏幕也能共用一条会话。

---

## 2. 快速上手

### 2.1 声明依赖

```toml
# META-INF/neoforge.mods.toml
[[dependencies.你的modid]]
    modId="cineduct"
    type="required"          # 只在编译期用 API、运行期可选的话可以写 optional + CineDuctApi.available()
    versionRange="[3.0.0,)"
    ordering="AFTER"
    side="BOTH"
```

`build.gradle` 里把 CineDuct 的 jar 放进 `compileOnly`（或 `modCompileOnly`）即可，
运行时由玩家自己装 —— API 类只在 `dev.mediafix.api` 里。

> 屏幕实体那套需要**服务端**（多人服上装在服务器 + 客户端）；
> 独立播放器 API（第 15 节）是**纯客户端**的，只在客户端用的话 `side="CLIENT"` 也行。

### 2.2 最小例子

```java
import dev.mediafix.api.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

public void spawnCinema(ServerLevel level, Vec3 pos) {
    if (!CineDuctApi.available()) return;                 // 没装就安静退出
    CineDuctApi api = CineDuctApi.get();

    ScreenHandle screen = api.createScreen(
            level, pos, 180.0f /* yaw */, 0.0f /* pitch */,
            8.0f /* 宽 */, 4.5f /* 高 */,
            "https://www.bilibili.com/video/BV1xx411c7mD");

    screen.volume(80);
    screen.range(2f, 40f);        // 2 格内满音量，40 格外静音
    screen.loop(true);
    screen.brightness(0.9f);
}
```

---

## 3. 创建屏幕

### 3.1 简版

```java
ScreenHandle createScreen(ServerLevel level, Vec3 pos, float yaw, float pitch,
                          float width, float height, String source);
```

| 参数 | 含义 |
| --- | --- |
| `level` | 服务端世界（`ServerLevel`） |
| `pos` | 屏幕**中心点**的世界坐标 |
| `yaw` / `pitch` | 朝向（见 [第 4 节](#4-屏幕变换位置--角度--大小--铺法)） |
| `width` / `height` | 宽×高，世界单位，各 0.25~64（1 格 = 1.0） |
| `source` | 播放源原始链接；空串 = 先建一块空屏，之后再 `source(...)` |

### 3.2 全参数版 `ScreenOptions`

```java
ScreenOptions opts = ScreenOptions.builder()
        .position(new Vec3(100, 65, 200))
        .rotation(180f, 0f)               // 朝北
        .size(8f, 4.5f)
        .source("https://live.bilibili.com/1817651835")
        .volume(85).range(2f, 40f).muted(false)
        .loop(false).paused(false)
        .qualityCap(-1)                   // 自动
        .alpha(1.0f).brightness(0.95f)
        .fit(ScreenFit.LETTERBOX)
        .doubleSided(true)
        .audioMode("shared")              // 空串 = 跟随全局配置
        .startAt(0L)                      // 建好就从 0ms 播
        .build();

ScreenHandle screen = CineDuctApi.get().createScreen(serverLevel, opts);
```

| Builder 项 | 默认值 | 范围 | 说明 |
| --- | --- | --- | --- |
| `position(Vec3 / x,y,z)` | `(0,0,0)` | — | 屏幕中心点 |
| `rotation(yaw, pitch)` | `0, 0` | yaw ±360、pitch ±90 | 朝向 |
| `size(w, h)` | `2.0 × 1.125` | 0.25~64 | 世界单位 |
| `source(uri)` | `""` | — | 原始链接（支持 B 站视频/直播/直链/BV 号） |
| `volume(percent)` | `100` | 0~100 | 还要再乘距离衰减与 MC 主音量 |
| `range(min, max)` | `1.0 / 24.0` | ≥0 | 距离衰减：`min` 内满音量 → `max` 处静音 |
| `muted(bool)` | `false` | — | 静音 |
| `loop(bool)` | `false` | — | 循环（点播有效；直播循环无意义） |
| `paused(bool)` | `false` | — | 建好就暂停 |
| `qualityCap(qn)` | `-1` | -1/0/其他 qn | 清晰度上限，见 [第 5 节](#5-播放控制) |
| `alpha(f)` | `1.0` | 0.05~1 | 不透明度 |
| `brightness(f)` | `1.0` | 0.05~1 | 亮度倍率（只能调暗；屏幕已是满亮自发光） |
| `fit(ScreenFit)` | `LETTERBOX` | — | 画面铺法 |
| `doubleSided(bool)` | `true` | — | 双面可见（背面镜像） |
| `audioMode(s)` | `""` | `auto/shared/exclusive/passthrough/空` | 每屏音频形态 |
| `startAt(ms)` | `0` | ≥0 | 起始播放位置 |

### 3.3 其它查询

```java
Optional<ScreenHandle> byId  = api.screen(uuid);        // 按 UUID 找（已卸载 = empty）
List<ScreenHandle> inLevel   = api.screens(level);      // 该维度所有已加载屏幕
Optional<ScreenHandle> byEnt = api.handleOf(entity);    // 用实体换句柄
```

> 从**任意线程**调用 `createScreen` 都安全：非服务端线程会被自动排到服务端线程执行，
> 返回的句柄用预分配的 UUID，创建完成后立刻可用。

---

## 4. 屏幕变换（位置 / 角度 / 大小 / 铺法）

### 4.1 角度约定（重要）

- `yaw` 用 MC 玩家视角习惯：**0 = 朝南（+Z）**、`90 = 朝西（-X）`、`180 = 朝北`、`-90 = 朝东`。
- `yaw` 描述的是"**放置屏幕时你看向的方向**"：**画面正确（不镜像）的那一面朝你**。
  也就是说 `yaw` 方向是屏幕背面所朝的方向，你站在放置时的位置看到的是正常画面，绕到背后看是镜像。
- `pitch` 正数 = 朝下（与玩家视角一致），会被夹在 ±90。
- `mirror off`（单面模式）保留的是**画面正确的那一面**（朝放置者那面），背面直接不画。

### 4.2 方法

```java
screen.size(8f, 4.5f);                    // 宽×高（世界单位）
screen.setPosition(new Vec3(x, y, z));    // 直接放到某处（中心点）
screen.translate(0, 2, 0);                // 相对位移
screen.rotation(180f, 0f);                // 设朝向
screen.lookAt(player.position());          // 让画面正对某个世界坐标点
```

`lookAt(target)` 等价于"玩家在 target 处放一块屏幕"的朝向：`lookAt(玩家眼睛位置)`
得到的 yaw 与玩家自己用指令放置完全一致。

**尺寸与视频比例**：屏幕的物理比例（`width/height`）和视频比例通常不同，
多出来的部分怎么处理由 [`fit`](#7-画面参数) 决定。

---

## 5. 播放控制

```java
screen.source("https://www.bilibili.com/video/BV1xx411c7mD");  // 换源（重置进度）
screen.play();  screen.pause();  screen.stop();                 // 停止 = 暂停 + 回到 0
screen.seek(90_000L);                                           // 跳到 90 秒
screen.loop(true);
screen.quality(-1);                                             // 清晰度上限
```

**清晰度上限 `qualityCap`**（B 站 qn）：

| 值 | 含义 |
| --- | --- |
| `-1` | 自动（按网速自适应，默认） |
| `0` | 该账号能拿到的最高清晰度 |
| `80` | 1080P |
| `116` | 1080P60 |
| `120` | 4K |
| `127` | 8K（需要大会员 + 显卡 AV1 硬解：8K 基本只有 AV1 一种编码） |
| `10000` | 直播原画 |

> 上限只用于**解析时挑选**：拿不到就自动降级到次高档，不会因此没画面。
> 直播的清晰度由房间本身决定（可选档位见解析日志）。

---

## 6. 声音

```java
screen.volume(80);          // 0~100
screen.range(2f, 40f);      // 距离衰减：2 格内满音量 → 40 格外静音
screen.muted(true);
screen.audioMode("passthrough");   // 每屏音频形态；空串 = 跟随全局配置
```

### 最终音量怎么算

```
实际音量 = volume × 距离衰减系数 × 实体静音(0/1) × Minecraft 主音量(MASTER)
```

### 每屏音频形态 `audioMode`

| 值 | 含义 | 什么时候用 |
| --- | --- | --- |
| `""`（空） | 跟随全局配置（`cineduct-ffmpeg.json` 的 `audioMode`，默认 `shared`） | 一般都用这个 |
| `auto` | 能直通就直通，否则共享 | 想要杜比/DTS 硬件解码时 |
| `shared` | 系统混音器（不独占声卡） | 最稳，默认 |
| `exclusive` | 独占 PCM（位完美，但**会独占声卡**，游戏自己的声音会听不见） | 追求音质 |
| `passthrough` | 强制 IEC 61937 压缩码流直通（功放解码，全景声走这条） | 有支持 Dolby 的功放 |

**生效时机**：音频形态是**打开声卡那一刻**定下来的，所以对**已经在播**的会话不会立刻生效 ——
改完换一次源（或重开会话）才生效。同一块屏改这个值也会参与会话复用判定（形态不同不复用）。

**空间音效（Atmos）**：端点是否真的在跑空间音效由 Windows 回答（WinRT
`SpatialAudioDeviceConfiguration`），客户端可以在
[`ClientScreenStatus.spatialAudioState`](#9-客户端-api) 里读到；
游戏里的查法是 `/cineduct-stream audio-info`（见 [第 12 节](#12-指令对照表)）。

---

## 7. 画面参数

```java
screen.alpha(0.6f);          // 不透明度 0.05~1（半透明幕布、淡出）
screen.brightness(0.6f);     // 亮度倍率 0.05~1（只能调暗：夜里不想太刺眼）
screen.fit(ScreenFit.FILL);  // 画面铺法
screen.doubleSided(false);   // 单面（只看正面，背面不画）
```

### `ScreenFit` 三档

| 值 | 效果 | 适合 |
| --- | --- | --- |
| `LETTERBOX`（默认） | 等比缩放 + 黑边，整幅画面都在 | 影院幕布（21:9 电影放 16:9 屏） |
| `FILL` | 等比放大铺满 + **裁掉**溢出部分（没有黑边） | 想让屏幕被画面填满 |
| `STRETCH` | 直接拉伸铺满，**会变形** | 屏幕比例与视频比例本来就接近时 |

### 屏幕是自发光的

屏幕的顶点格式里没有光照贴图，所以它在夜里也是亮的（不受方块光照影响），
`brightness` 是乘在顶点色上的调暗倍率。这也是它在光影下的行为基础，见 [第 13 节](#13-光影iris现状)。

---

## 8. 读回状态

```java
ScreenInfo info = screen.info();      // 实体已不存在时返回 null
```

`ScreenInfo` 是**不可变快照**（服务端权威值）：

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `id` | `UUID` | 屏幕 UUID |
| `source` | `String` | 播放源原始链接（空串 = 无源） |
| `centerX/Y/Z` | `double` | 中心点坐标（也可用 `center()` 取 `Vec3`） |
| `yaw` / `pitch` | `float` | 朝向 |
| `width` / `height` | `float` | 宽×高（世界单位） |
| `aspect` | `float` | 宽高比 |
| `paused` | `boolean` | 是否暂停 |
| `loop` | `boolean` | 是否循环 |
| `muted` | `boolean` | 是否静音 |
| `volume` | `int` | 音量 0~100（未计距离衰减与主音量） |
| `rangeMin` / `rangeMax` | `float` | 距离衰减范围 |
| `positionMs` | `long` | **此刻**应播到的位置（毫秒，服务端算出来的） |
| `qualityCap` | `int` | 清晰度上限（-1 自动 / 0 最高 / 否则 qn） |
| `alpha` | `float` | 不透明度 |
| `brightness` | `float` | 亮度倍率（0.05~1） |
| `fit` | `ScreenFit` | 画面铺法 |
| `doubleSided` | `boolean` | 是否双面可见 |
| `audioMode` | `String` | 每屏音频形态覆盖（空串 = 跟随配置；另有 `hasAudioModeOverride()`） |

> `info()` 是"快速读取"，别在渲染线程每帧调（每次调用都会在世界里线性找实体）。

---

## 9. 客户端 API

```java
if (CineDuctClientApi.available()) {
    CineDuctClientApi api = CineDuctClientApi.get();
    api.status(screenId).ifPresent(st -> {
        System.out.println(st.state() + " " + st.positionMs() + "ms via " + st.transport()
                + "  " + st.resolutionText());
    });
}
```

| 方法 | 说明 |
| --- | --- |
| `status(UUID)` | 某块屏的**实际**播放状态；不存在/还没起播 = empty |
| `activeScreens()` | 当前客户端已建立会话的屏幕 UUID |
| `zeroCopyAvailable()` | 这次游戏会话里零拷贝（D3D11 → GL）是否可用 |
| `transportDiagnostics()` | 传输诊断（不可用原因、GL 适配器、会话数） |
| `shaderDiagnostics()` | 光影诊断（是否装 Iris、是否启用光影） |
| `shadersInUse()` | 光影包是否正在使用 |
| `noticesEnabled()` | 现在是否在发聊天栏提示 |
| `setNoticesEnabled(boolean)` | 开关聊天栏提示（**关是粘滞的**，见 9.1） |

### 9.1 关掉聊天栏提示

CineDuct 会往聊天栏发提示（`DASH 流式直连: 1080P60`、`回退原生播放`、`屏幕播放失败…`）。
附属 mod 自己接管了 UI、或者不想被刷屏时，调一次就行：

```java
CineDuctClientApi.get().setNoticesEnabled(false);   // 立即生效，并且本局游戏内粘滞
boolean on = CineDuctClientApi.get().setNoticesEnabled(true);   // 只在没人关过时才真的打开
```

**为什么"关"是粘滞的**：提示是全客户端共享的一条通道，一块屏可能同时被好几个附属 mod 驱动。
只要有**任何一个**调用方希望它安静（自己画了 UI、或在做无头测试），就该听那个"关" ——
否则后调用的 mod 一开，前面那位又被刷屏。所以一旦关闭，后续 `true` 都会被忽略（日志里说明一次）；
`setNoticesEnabled(true)` 的返回值是**实际状态**（被拒绝时返回 `false`）。

要恢复只有一条路（排障用，正式调用方别调）：

```
/cineduct notice          # 看当前状态
/cineduct notice off      # 关闭（粘滞）
/cineduct notice on       # 请求打开（若已被关过会被忽略）
/cineduct notice reset    # 清掉粘滞位、恢复提示
```

> 关掉提示不影响功能与日志：命令反馈（`/cineduct ...` 的回显）与 `cineductlogs/` 都照常。

`ClientScreenStatus` 字段：

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `screenId` | `UUID` | 屏幕 |
| `state` | `PlaybackState` | 实际播放状态 |
| `positionMs` / `durationMs` | `long` | 实际位置 / 时长（直播 `-1`） |
| `transport` | `String` | `dxgi-interop`（子进程零拷贝）/ `java`（进程内兜底） |
| `live` | `boolean` | 是否直播 |
| `publishedFrames` / `droppedFrames` | `long` | 子进程已发布 / 丢弃帧数 |
| `lastAcquireUs` | `int` | 最近一次等槽位锁耗时（微秒） |
| `error` | `String` | 最近一次错误（正常为空串） |
| `audioMode` | `String` | `IEC61937直通` / `独占PCM` / `共享模式` |
| `volumeLocked` | `boolean` | 音量是否由接收端控制（直通时命令无效） |
| `videoWidth` / `videoHeight` | `int` | 实际视频分辨率（未知 0；另有 `resolutionText()`） |
| `liveLatencyMs` | `long` | 直播实时延迟（毫秒）；未知 -1 |
| `spatialAudioState` | `String` | 空间音效状态人话（含格式名） |
| `audioDeviceName` | `String` | 实际在用的音频端点名 |

**线程**：这些方法读的是客户端状态，请在客户端线程（主线程/渲染线程）调用；
从别的线程读不会崩，但可能读到上一帧的值。

---

## 10. 事件

都发在 **NeoForge 游戏事件总线**（`NeoForge.EVENT_BUS`）的**服务端**侧：

| 事件 | 时机 | 主要信息 |
| --- | --- | --- |
| `ScreenCreatedEvent` | 屏幕被创建（含指令/API 两条路径） | `screen()`、`info()` |
| `ScreenRemovedEvent` | 屏幕被删除 | `screen()`、`info()`（删除前快照） |
| `ScreenSourceChangedEvent` | 播放源变了 | `oldSource()`、`newSource()` |
| `ScreenPlaybackChangedEvent` | 任一播放/画面参数变了 | `change()`（枚举）、`detail()`（人话） |

`ScreenPlaybackChangedEvent.Change` 全部取值：
`PAUSE`、`SEEK`、`VOLUME`、`RANGE`、`LOOP`、`MUTE`、`SIZE`、`QUALITY`、
`POSITION`、`ROTATION`、`ALPHA`、`BRIGHTNESS`、`FIT`、`DOUBLE_SIDED`、`AUDIO_MODE`。

```java
@EventBusSubscriber(modid = "你的modid")
public final class MyListeners {
    @SubscribeEvent
    public static void onSource(ScreenSourceChangedEvent e) {
        // e.level() / e.screen() / e.info() / e.oldSource() / e.newSource()
    }

    @SubscribeEvent
    public static void onPlayback(ScreenPlaybackChangedEvent e) {
        if (e.change() == ScreenPlaybackChangedEvent.Change.SEEK) {
            // 播放位置被跳了
        }
    }
}
```

> 事件都发在**服务端**（多人服 = 服务器、单人 = 集成服务器）。
> 关心"本机实际播成什么样"请用 `CineDuctClientApi`。
> 读存档期间不会发事件（避免读一次档刷一屏）。

---

## 11. 自定义源解析器（支持别的站点）

```java
public final class MySiteResolver implements MediaSourceResolver {
    @Override public String name() { return "mysite"; }

    /** 只看链接本身，不要发网络请求。 */
    @Override public boolean canHandle(URI uri) {
        return uri.getHost() != null && uri.getHost().endsWith("mysite.com");
    }

    /** 可能发网络请求 —— 绝不能在渲染/主线程调用（上层在后台线程调它）。 */
    @Override public ResolvedStream resolve(URI uri) throws Exception {
        // 返回 null = 我处理不了/失败，上层会继续试下一个解析器，最后回退成"把原链接当直链"
        return new ResolvedStream(videoUri, audioUri, false /* live */, title, qualityLabel);
    }
}
```

注册：

```java
CineDuctApi.get().addResolver(new MySiteResolver());   // 排在最后（内置 bilibili 之后）
CineDuctApi.get().resolverNames();                     // 看当前顺序
```

- 想抢在内置解析器之前，用内部类 `dev.mediafix.source.SourceResolvers.registerFirst(...)`
  （**不在兼容承诺里**，能用但换版本可能改）。
- 失败约定统一：`null` 或抛异常 = 让位给下一个；全部失败则把原链接当直链播
  （玩家看到明确报错，不是黑屏转圈）。
- `ResolvedStream` 是一个 record：
  `(URI video, URI audio, boolean live, String title, String qualityLabel, long durationMs)`。
  `video`/`audio` 是**直链**（DASH 时两条；直播时两条指向同一个 FLV）；
  便利构造：`ResolvedStream.of(video, audio)`（点播）、`ResolvedStream.live(url, title)`、
  `ResolvedStream.direct(uri)`（非 B 站直链 / 解析失败的兜底）。
  `live=true` 会让引擎按直播处理（忽略 seek、不做 ABR、断流重连、时长恒为 -1）。

---

## 12. 指令对照表

所有指令都是 `/cineduct screen ...`（服务端指令，`create/remove/...` 改动类需要 OP 2 级）。

| 指令 | 等价 API |
| --- | --- |
| `/cineduct screen create <url>` | `createScreen(...)`（尺寸 2×1.125，朝玩家看的方向） |
| `/cineduct screen list` | `api.screens(level)` |
| `/cineduct screen info <屏>` | `handle.info()` |
| `/cineduct screen source <屏> <url>` | `source(uri)` |
| `/cineduct screen play\|pause\|stop <屏>` | `play()` / `pause()` / `stop()` |
| `/cineduct screen seek <屏> <秒>` | `seek(ms)` |
| `/cineduct screen volume <屏> <0~100>` | `volume(percent)` |
| `/cineduct screen range <屏> <min> <max>` | `range(min, max)` |
| `/cineduct screen loop <屏> on\|off` | `loop(bool)` |
| `/cineduct screen size <屏> <宽> <高>` | `size(w, h)` |
| `/cineduct screen rotate <屏> <yaw> [pitch]` | `rotation(yaw, pitch)` |
| `/cineduct screen move <屏> <dx> <dy> <dz>` | `translate(dx, dy, dz)` |
| `/cineduct screen alpha <屏> <0.05~1>` | `alpha(f)` |
| `/cineduct screen brightness <屏> <0.05~1>` | `brightness(f)` |
| `/cineduct screen fit <屏> letterbox\|fill\|stretch` | `fit(ScreenFit)` |
| `/cineduct screen mirror <屏> on\|off` | `doubleSided(bool)` |
| `/cineduct screen audio <屏> default\|auto\|shared\|exclusive\|passthrough` | `audioMode(s)` |
| `/cineduct screen quality <屏> auto\|max\|1080p\|1080p60\|4k\|8k` | `quality(qn)` |
| `/cineduct screen remove <屏>` | `remove()` |

另有诊断/全局设置用的 `/cineduct-stream ...`（`quality <档位>` 调全局清晰度、
`streaming on|off` 开关流式直连），面向玩家排障与全局配置，不属于 API。

---

## 13. 光影（Iris）现状

**结论：能放，屏幕是自发光（不受方块光照/阴影影响），走的是光影加载器能识别的绘制路径。**

### 屏幕是怎么画的（不需要 Iris 的任何 API）

一块屏幕的四边形**通过 `RenderType` + `MultiBufferSource` 提交**，而不是自己 `setShader` 立刻直绘：

- 光影加载器是**按 `RenderType` 识别绘制**的 —— 它把这个 RenderType 映射到光影包自己的程序
  （`gbuffers_entities` 那一档），采样器 0 也按 RenderType 的纹理状态绑定（就是我们的画面）。
  自己拿 GL 直绘会绕开这层识别，包的程序接不上，屏幕就可能被算成全黑甚至画不出来。
- `alpha = 1` 用 `RenderType.entitySolid`（不透明、写深度，屏幕该挡住后面的东西）；
  `alpha < 1` 用 `RenderType.entityTranslucentCull`（半透明混合）。
- 顶点里写死**满亮**：`setLight(LightTexture.FULL_BRIGHT)`。光影包的程序普遍 `color *= lightmap`，
  顶点里没有光照贴图属性时那份数据是 0 → 屏幕会被算成"零光照"而发黑；写满亮之后任何包拿到的都是"最亮"，
  于是**画面恒为自发光**。
- 顶点还带上**面法线**（`setNormal`）与 `NO_OVERLAY`：包会用面法线做方向性着色，
  缺了它有的包会算出乱七八糟的结果。
- 画面纹理在 `TextureManager` 里登记（`RenderType` 要 `ResourceLocation`），但 GL 纹理**仍归会话所有**：
  登记的只是一个"id 转发壳"`ScreenVideoTexture`（子进程导入的互操作纹理 / 进程内引擎上传的目标纹理），
  谁也不多占一张纹理，传输方式切换对渲染侧透明。

### 已知边界

- **阴影贴图 pass 会跳过屏幕**：装了 Iris 时用 `IrisApi.isRenderingShadowPass()` 判断
  （`dev.mediafix.client.IrisCompat`，纯反射软依赖）。不这么做，一块发光的屏会在每个光影包里
  投出"画面形状的阴影"。不想要这个行为、想完全零 Iris 耦合的话，删掉那几行即可。
- **屏幕不写 gbuffer**：不参与延迟渲染的光照/反射/水体折射，也不进光追；
  阴影、曝光、tonemapping 由光影包施加在它的最终颜色上。
- 客户端可以读到现状：`CineDuctClientApi.shaderDiagnostics()` / `shadersInUse()`。

### 还没验证的部分（开发环境限制）

`run/mods` 里没有 Iris、`run/shaderpacks` 里没有光影包，所以"开着光影跑一遍"**还没实测**。
实现参考了同类模组（WaterFrames）在光影下可用的做法：RenderType 提交 + 满亮光照贴图 + 面法线 +
两面分开画 + "纹理登记但 GL id 归播放器所有"。**参考的是做法，不是代码**（按作者要求只复刻思路）。
装光影后请重点看：画面是否发黑（光照贴图问题）、是否有屏幕形状但内容纯色（采样器绑定问题）。
另外**亮度只能调暗**（顶点色是 8 位归一化值，上限 1.0）：以前的直绘路径靠 float 乘子能过曝，
但那条路光影认不出来 —— 现在以"光影能正确绘制"为先。

---

## 14. 全局配置（不属于每屏数值）

上面 API 管的都是**每块屏幕**的值。剩下的是全局的，写在 dev/正式游戏目录的
游戏目录**根下**的 `cineduct-*.json`（就是 `<游戏目录>/cineduct-ffmpeg.json` 这些，**不在 config/ 里**；改完重启生效）：

| 配置项 | 文件 | 默认 | 说明 |
| --- | --- | --- | --- |
| `audioMode` | `cineduct-ffmpeg.json` | `shared` | 全局音频形态（每屏可用 `audioMode()` 覆盖） |
| `audioDevice` | `cineduct-ffmpeg.json` | 空 | 指定音频端点（编号/名字子串；空 = 系统默认） |
| `gpuHostEnabled` | `cineduct-ffmpeg.json` | `true` | 是否优先用 C++ 子进程（零拷贝） |
| `hostSlots` | `cineduct-ffmpeg.json` | `3` | 子进程共享纹理槽位数 |
| `prebufferMs` | `cineduct-ffmpeg.json` | `1500` | 起播预缓冲门限（音频提前量） |
| `audioBufferMs` | `cineduct-ffmpeg.json` | `2500` | 请求的声卡行缓冲 |
| `hwAccel` | `cineduct-ffmpeg.json` | `true` | 硬件解码（D3D11VA） |
| `videoReadAheadMb` / `audioReadAheadMb` | `cineduct-ffmpeg.json` | `48` / `8` | 压缩流预读队列 |
| `videoFrameBufferMb` | `cineduct-ffmpeg.json` | `256` | 解码帧缓冲预算 |
| `threads` / `convertThreads` | `cineduct-ffmpeg.json` | `0`（自动） | 解码 / 格式转换线程数 |
| `maxQn` | `cineduct-stream.json` | `-1` | 全局清晰度上限（每屏可用 `quality()` 覆盖） |
| `dashStreaming` | `cineduct-stream.json` | `true` | 流式直连（不整份下载） |
| `audioPreference` | `cineduct-stream.json` | `dolby` | 音轨偏好：`dolby` / `highres` / `normal` |
| `highres` | `cineduct-stream.json` | `true` | 是否接受 Hi-Res 无损音轨 |
| `noopSeekToleranceMs` | `cineduct-stream.json` | `1500` | 引擎"原地 seek"容差 |
| `catchUpMaxMs` | `cineduct-stream.json` | `8000` | 追赶上限（超过就硬对齐） |

> 直播直链是**有时效**的（B 站约 1 小时），CineDuct 会在到期前 5 分钟自动换链（无缝，不中断播放）。

---

## 15. 独立播放器 API（不带屏幕实体）

屏幕那套 API 解决的是"世界里有一块屏，服务端权威、客户端各自播放"。
**但如果你的附属 mod 自带渲染**（把画面贴到自己的方块/实体/自绘平面/UI 上），
你需要的东西只有一句：

> 给我一条正在解码的播放会话，我每帧来取一张能采样的 GL 纹理；进度怎么对齐、画在哪、谁在播，我自己管。

这就是 `dev.mediafix.api.player`（三个 API 类型 + 一个内部实现）：

```java
import dev.mediafix.api.player.CinePlayer;
import dev.mediafix.api.player.CinePlayerApi;
import dev.mediafix.api.player.PlayerOptions;

if (!CinePlayerApi.available()) return;              // 没装 CineDuct 就安静退出

CinePlayer player = CinePlayerApi.get().open(
        "https://www.bilibili.com/video/BV1xx411c7mD",
        PlayerOptions.builder().volume(80).loop(true).build());

// 每帧（渲染线程）：
int tex = player.acquireDrawTexture(myGlTextureId, /*forceAllocate=*/ false, /*targetAspect=*/ 0f);
if (tex > 0) {
    // 用 tex 画你自己的几何（UV 0~1）；黑边按 player.drawAspect() 自己算
}
player.releaseDrawTexture();                          // 必须成对

// 不用了：
player.close();
```

### 15.1 `CinePlayerApi`（门面）

| 方法 | 说明 |
| --- | --- |
| `available()` / `get()` | 有没有 / 取门面（未加载抛异常） |
| `open(uri, options)` | 打开一个独立播放器；**非阻塞**，返回的句柄立刻可用 |
| `open(uri)` | 同上，全默认参数 |
| `players()` | 当前所有还活着的播放器（诊断/批量管理） |
| `player(id)` | 按 id 取；不存在返回 empty |
| `openPlayerCount()` | 数量 |
| `diagnostics()` | 一句话诊断（会话数、零拷贝是否可用、适配器） |
| `API_VERSION` | 版本号，不兼容改动 +1 |

### 15.2 `PlayerOptions`（参数包）

| Builder 项 | 默认 | 说明 |
| --- | --- | --- |
| `audioMode(String)` | `""` | `""` 跟随全局配置 / `auto` / `shared` / `exclusive` / `passthrough` |
| `volume(int)` | `100` | 0~100（引擎自己的音量，不含你那边的距离衰减/主音量） |
| `muted(boolean)` | `false` | 静音 |
| `loop(boolean)` | `false` | 循环（点播有效） |
| `paused(boolean)` | `false` | 建好就暂停 |
| `startAt(long)` | `0` | 起播位置（毫秒） |
| `qualityCap(int)` | `-1` | 清晰度上限：-1 自动 / 0 最高 / 否则 B 站 qn |
| `displayAspect(float)` | `0` | **显示面宽高比**（宽/高）；`>0` = 开启信箱化（黑边烘焙进纹理），`0` = 关 |
| `transport(Transport)` | `AUTO` | 帧传输方式偏好：`AUTO` / `ZERO_COPY`（尽量子进程）/ `IN_PROCESS`（不起子进程） |

### 15.3 `CinePlayer`（句柄）

| 分组 | 方法 |
| --- | --- |
| 身份 | `id()`、`sourceUri()`、`isAlive()` |
| 状态 | `state()`、`isReady()`、`isLoading()`、`isBuffering()`、`isBroken()`、`isLive()`、`isSeekable()`、`positionMs()`、`durationMs()`、`videoWidth()`、`videoHeight()`、`transport()`、`lastError()` |
| 出帧（渲染线程） | `acquireDrawTexture(fallbackTextureId, forceAllocate, targetAspect)`、`releaseDrawTexture()`、`drawAspect()` |
| 信箱化 | `displayAspect()`、`setDisplayAspect(float)`（见 15.5） |
| 控制（任意线程） | `pause(boolean)`、`isPaused()`、`seek(ms)`、`setVolumePercent(int)`、`volumePercent()`、`setMuted(boolean)`、`isMuted()`、`setRepeat(boolean)`、`isRepeat()`、`volumeLocked()` |
| 诊断 / 修复 | `statistics()`（见 15.9）、`rebuild()`（见 15.10） |
| 生命周期 | `close()`（释放一次引用；重复调用无副作用） |

`CinePlayerApi` 还有一个**不发网络请求**的判定：`canHandle(String uri)`（见 15.8）。

### 15.4 语义（写调用方代码时按这几条来）

| # | 约定 |
| --- | --- |
| 1 | `open` **不阻塞**：解析直链在后台线程做，返回的句柄立刻可用，此时 `state()` 一般是 `LOADING`。所以**不要在 open 之后同步等**，每帧读状态即可 |
| 2 | `acquireDrawTexture` / `releaseDrawTexture` **只在渲染线程**调，且必须成对（零拷贝模式下那是解锁互操作对象） |
| 3 | 同一个"解析结果 + 音频形态"的多个 `open` **共用一条会话**：只解一份、只占一份声卡；各自 `close()` 一次，最后一个才真关 |
| 4 | `open` 之后立刻推的 `pause/volume/muted/repeat/seek` 不会丢：会话还没建好时实现会先把意图攒着，建好即补推 |
| 5 | 状态读取**不抛异常、不阻塞**：没有会话时给安全默认值（`positionMs()=0`、`durationMs()=-1`、`videoWidth()=0`、`transport()="(解析中)"`） |
| 6 | 链接解析失败**不抛异常**：返回一个 `state()==ERROR` 的句柄，原因在 `lastError()` |
| 7 | `isLoading()` 只表示"还没起播"；播放中偶尔的缓冲是 `isBuffering()`（别用它去盖"加载中"动画，否则播到一半会被加载图盖住） |
| 8 | 直播：`seek` 无效、`durationMs()==-1`、`isSeekable()==false` |
| 9 | `volumeLocked()==true` 表示音频走 IEC 61937 直通（音量由功放控制），这时音量/静音命令无效，调用方可以跳过每帧推音量 |
| 10 | `drawAspect()` 返回**那张纹理自己**的宽高比（零拷贝 = 视频比例）；`targetAspect` 传 `<=0` 表示"不关心"，进程内路径会按它做信箱化 |

### 15.5 信箱化：把黑边烘焙进纹理（不拉伸）

**要解决的问题**：视频比例和你的显示面比例不一样时（21:9 电影放进 16:9 的电视、4:3 老片放进宽屏），
要么拉伸变形，要么在几何上缩一圈四边形（那样屏幕外露出的是世界，不是黑边）。

**做法**：把"显示面比例"告诉 CineDuct，它**在出帧时就把黑边烘进纹理** ——
画面等比放进"比例 = 你给的比例"的框里，框外是真黑边。于是你只要把整张纹理铺满显示面就行：

```java
CinePlayer player = CinePlayerApi.get().open(uri,
        PlayerOptions.builder()
                .displayAspect(16f / 9f)   // ← 你的显示面比例
                .build());

// 每帧：整张纹理直接铺满你的面（UV 0~1），不用自己算黑边
int tex = player.acquireDrawTexture(fallbackTextureId, false, 0f);
// ... 画你的几何 ...
player.releaseDrawTexture();
```

语义要点：

| 项 | 说明 |
| --- | --- |
| `displayAspect > 0` | 纹理里**已经含黑边**；`drawAspect()` 返回的是这张纹理自己的比例（= 你给的值）。**不要再自己加一层边**，否则会叠出两层 |
| `displayAspect = 0`（默认） | 不做信箱化：纹理就是视频原始尺寸，比例由你自己处理（用 `drawAspect()` 或按 `videoWidth()/videoHeight()`） |
| 输出尺寸 | 长边不变、短边补黑（不会放大变糊），对齐到 2 的倍数，上限 4K |
| 两条链路都支持 | 进程内引擎在 CPU 侧做（`Letterbox`）；零拷贝由子进程的 D3D11 VideoProcessor 做（source/dest rect + 每帧清黑），**纹理本身就是带黑边的一整幅画面** |
| 生效时机 | **推荐在 `open()` 时就用 `PlayerOptions.Builder.displayAspect(...)` 带上**（或建会话后、第一帧画出来之前调一次）：CineDuct 的子进程是**推迟启动**的（等"比例已知／首次取帧／1.5 秒兜底"），所以比例赶得上启动，**一次子进程都不用重开**。运行期才改比例：进程内引擎下一帧生效；零拷贝必须重开一次子进程（画面黑一下、位置续播），因为输出尺寸是开进程时定死的。同一比例（差 <1%）重复设置是无操作 |
| 会话级 | 输出框属于**会话**：同源 + 同音频形态的多个播放器共享一条会话，所以也共享这个比例 |
| 想看视频原始比例 | `videoWidth()` / `videoHeight()`（两者都未知时为 0） |

**不写代码也能试**（游戏里）：

```
/cineduct player open <链接>
/cineduct player aspect <id> 1.7778    # 16:9 显示面；黑边会烘焙进纹理
/cineduct player info <id>             # 会显示"显示面比例 / 纹理比例"
/cineduct player aspect <id> 0         # 关掉信箱化
```

### 15.6 关系与差异

- **共用同一套会话层**：独立播放器和屏幕实体用的是同一个会话注册表，所以子进程崩溃自愈、
  直链续期、音频形态、会话复用这些行为**两边完全一致**（`/cineduct api` 里能看到总会话数）。
- **不创建实体、不联网同步**：独立播放器是纯客户端的东西，不会出现在世界里，也没有 UUID/锚点那套。
- **进度归你管**：`positionMs()` 只是"现在播到哪"，没有权威锚点，你想怎么对齐就怎么对齐。

### 15.7 不写代码也能自检

```
/cineduct player open <链接>    # 开一个独立播放器（声音会真的响，解码+声卡走同一条链路）
/cineduct player list           # 列出全部：状态 / 直播 / 位置 / 时长 / 传输方式 / 错误
/cineduct player info <id>      # 详情：分辨率、比例、音量、静音、循环、暂停、音量是否被锁定
/cineduct player aspect <id> <比例>   # 显示面比例（黑边烘焙进纹理）；0 = 关
/cineduct player stats <id>     # 统计：出帧/丢帧/等锁/子进程 pid 与存活时长/重启次数
/cineduct player rebuild <id>   # 丢缓冲重开（等价于屏幕那边的 /cineduct refresh full）
/cineduct player close <id>     # 关一个（共用会话的其它使用者不受影响）
/cineduct player closeall       # 全关
```

### 15.8 `canHandle()`：建会话之前先问一句

```java
String uri = "https://example.com/photo.png";
if (CinePlayerApi.get().canHandle(uri)) {
    // 才去 open：不认识的链接（图片/网页/别的 mod 的资源）就别为它起一条解码会话
}
```

**只看链接、不发任何网络请求**，所以可以在"决定要不要建屏"的当场调用。判定顺序：
① 解析成 URI（失败 = false）→ ② 问每个已注册的解析器 `canHandle(uri)`
（内置 B 站解析器只看 host）→ ③ 都不是就看扩展名像不像媒体
（`mp4/mkv/webm/flv/m3u8/ts/mp3/flac/wav…`）。

返回 `false` 的意思是"**别当视频处理**"，不代表绝对播不了（也可能是"我不认识这个站"）。

### 15.9 `statistics()`：这条路跑得怎么样

```java
PlayerStatistics st = player.statistics();
if (st.zeroCopy()) {
    LOGGER.info("出帧 {} 丢帧 {} 等锁 {}us/{}us 子进程 pid={} 存活 {}ms 重启 {} 次",
            st.publishedFrames(), st.droppedFrames(), st.lastAcquireUs(), st.maxAcquireUs(),
            st.hostPid(), st.hostUptimeMs(), st.restarts());
}
```

字段：`transport / zeroCopy / publishedFrames / droppedFrames / lastAcquireUs / maxAcquireUs /
slots / hostPid / hostUptimeMs / videoWidth / videoHeight / positionMs / durationMs / live /
liveLatencyMs / audioMode / volumeLocked / restarts / error`。

> 帧计数、等锁耗时、pid/存活时长都来自**子进程**的状态环：走进程内引擎时它们恒为 0
> （那条路没有"发布/丢弃"这个概念），用 `zeroCopy()` 判断。`maxAcquireUs` 明显偏大说明渲染线程
> 被别的活堵住了（会影响所有屏幕的取帧）。

### 15.10 `rebuild()`：刷新走正规路径

```java
player.rebuild();   // 丢缓冲重开（零拷贝 = 换一个新子进程），位置尽量保留
```

和"`close()` 掉再 `open()` 一个"的区别：

| | `rebuild()` | close + open |
| --- | --- | --- |
| 会话身份 | 保留（`Sessions` 里的 id 不变） | 换新会话 |
| 同源多屏时 | **真的会换新子进程** | 会被会话复用接回原会话 —— **等于空转** |
| 零拷贝纹理 | 渲染线程自动重导，调用方什么都不用做 | 全部槽位作废一遍 |

场景：画面卡住 / 断流 / 状态可疑时"按一下刷新"，等价于屏幕那边的 `/cineduct refresh full`。
任意线程可调；**代价是画面会黑一下（换新子进程约 1 秒）然后从原位置续播** —— 这是重建的固有成本，
`/cineduct av` 那种"改数值"不要走这条路。会话还没建好（`LOADING`）时调用是空操作（日志里会说明）。
如果你在日志里看到 `独立播放器 #N 已重建会话 #N` 而玩家没按刷新，那就是水帧的刷新按钮/`reload_all` 触发的。

---

## 16. 线程模型与常见坑
| 事项 | 结论 |
| --- | --- |
| 调用 `ScreenHandle` / `CineDuctApi` 的线程 | 任意线程都安全（内部自动排队到服务端线程） |
| `info()` 的频率 | 便宜但**不是免费**（线性找实体）：别每帧调 |
| 句柄有效期 | 实体被删/区块卸载后 `isAlive()=false`，后续调用静默无效（日志里会有一条 warn） |
| `quality` / `audioMode` 生效时机 | 都是"下次开会话/解析时"生效；改完对已播会话不追溯（换源或重开） |
| 直播 `seek` | 无意义（引擎忽略）；直播也没有 `durationMs`（恒为 -1） |
| 直播 `loop` | 无意义（直播没有片尾） |
| 服务端 TPS 低 | **不影响**进度：锚点按真实时间推进，客户端只在锚点变化时对齐 |
| 一块屏一个实体 | 屏幕是实体（`cineduct:screen`），会被区块加载/卸载带走；卸载后会话由客户端回收 |
| 音量的距离衰减 | 由**每个客户端**按自己玩家的距离算，所以多人各自听到的音量可能不同（符合直觉） |

---

## 17. 内部实现（别依赖）

| 包 | 作用 |
| --- | --- |
| `dev.mediafix.api` | **稳定**：门面、句柄、快照、事件、解析器 SPI |
| `dev.mediafix.screen` | 屏幕实体与服务端实现 |
| `dev.mediafix.client` | 客户端渲染、会话绑定、Iris 兼容层 |
| `dev.mediafix.player` | 会话/引擎门面（两种传输方式统一在这里） |
| `dev.mediafix.engine` / `.ffmpeg` | 进程内 FFmpeg 引擎（兜底路径） |
| `dev.mediafix.host` | C++ 子进程会话（零拷贝 + WASAPI） |
| `dev.mediafix.gpu` | GL/D3D 互操作探测 |
| `dev.mediafix.proxy` | 直链解析、本地代理 |
| `dev.mediafix.source` | 解析器注册表（`SourceResolvers`） |

其它文档：[`DEV-WORKFLOW.md`](DEV-WORKFLOW.md)（开发/测试约定与踩坑）、
[`AUDIO-passthrough.md`](AUDIO-passthrough.md)（音频直通/全景声）、
[`PLAN-standalone-gpu.md`](PLAN-standalone-gpu.md)（总体计划）。

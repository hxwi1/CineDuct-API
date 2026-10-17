# 子进程身份契约（两层 UUID）

> 状态：**接口已定稿，待实现**。本文是阶段 A（C++）与阶段 B（Java）的共同基准，两边都必须照此实现。
> 改动方向与本文冲突时，先改本文再改代码。

---

## 1. 结论

给每个子进程装 **两层 UUID**，并把身份写进命令行、状态环、控制回执、日志与文件名：

| 概念 | 谁生成 | 生命周期 | 用途 |
| --- | --- | --- | --- |
| `sessionUuid` | Java（`Sessions.open` 时） | 与逻辑会话同生共死，**子进程重启不变** | 注册表主键、命令寻址、跨重建追溯 |
| `instanceUuid` | Java（每次 spawn） | **每个进程一个**，重启即换 | 进程实例身份、对象命名、状态环校验、dump/日志文件名 |

现状（改造前）的身份是四个互不相关的东西：自增 `sessionId`（`src/main/java/dev/mediafix/player/Sessions.java:24`）、只用于命名内核对象的随机 `token`（`src/main/java/dev/mediafix/host/HostSession.java:178-182`）、状态环里的 `producerPid`（offset 60）、以及 OS 的 pid。它们共同的问题是：**pid 会复用、sessionId 跨重启重来、token 不回传给子进程**，因此"这条日志/这个 dump 属于哪次进程"事后无法确定。

---

## 2. 命令行契约（新增两个可选参数）

| 参数 | 格式 | 缺省行为 |
| --- | --- | --- |
| `--session-uuid` | 38 字符以内、`8-4-4-4-12` 小写带连字符 | 缺省时子进程自己生成一个（**独立可跑**，不依赖 Java） |
| `--instance-uuid` | 同上 | 缺省时子进程自己生成一个 |

约束：
- 解析失败**不致命**：打一条 warning，然后按"缺省自己生成"处理（不能因为一个新参数就让进程起不来）。
- 子进程必须把两个值都当作**不可变身份**：横幅打印一次、状态环写一次、`hello` 回执每次回显同一值。
- 对象命名沿用 `instanceUuid` 的前 12 个十六进制字符（去掉连字符），替换现有的随机 `token`。于是共享纹理名、状态段名、管道名、日志名、dump 名全部可从同一个 UUID 反推。

命名现状（改造对象，Java 侧）：`HostSession.java:178-182`，管道名格式见 `native/host/src/ipc.h:29`。

---

## 3. 状态环 v9（只追加）

在 `native/spike/shared/cineduct_spike_status.h` 末尾追加两个定长 UUID 字段，**既有偏移一个都不动**，`kVersion` 8 → 9：

| 字段 | 偏移 | 类型 | 内容 |
| --- | --- | --- | --- |
| `sessionUuid` | 648 | `char[37]` | NUL 结尾小写带连字符；前 36 字节有效，第 37 字节恒 0 |
| `instanceUuid` | 685 | `char[37]` | 同上 |
| `reserved3` | 722 | `uint8_t[6]` | 对齐占位，恒 0 |

- 需要 `static_assert` 锁住三个偏移，并保留现有的 `sizeof(Status) <= kMappingBytes` 断言（当前用到 644，加完仍远小于 4096）。
- 写入时机：`initSharedStatus` 之后、`magic` 置位**之前**（与现有"magic 最后写"的约定一致，见 `native/spike/shared/cineduct_spike_status.h` 里 magic 的注释）。
- 读取方用途（Java，`awaitRing`）：拿到映射后校验 `instanceUuid` 必须等于自己 spawn 时传入的那个；不等则视为失败（防止 pid 复用/旧段残留导致"映射到了别人的环"）。

---

## 4. `hello` 回执（末尾追加，向前兼容）

现状：`native/host/src/video_pipeline.cpp:722-726` 回 `ok 1 protocol=1 ffmpeg=<ver> transport=dxgi-interop audio=<mode> volume_locked=<0|1>`。

追加在**末尾**（老解析方只看前缀 `ok` 即可）：

```
... volume_locked=0 instance=<instanceUuid> session=<sessionUuid>
```

- `protocol` 保持 1；身份字段是**可选附加**，不构成协议版本变更。
- Java 侧 `connectPipe`（`src/main/java/dev/mediafix/host/HostSession.java:455-458`）目前只把它写进日志，因此追加零风险；实现阶段顺手把回显值与本地值比对一次，不一致就 warn。

---

## 5. 日志与文件名

| 位置 | 现状 | 改为 |
| --- | --- | --- |
| 子进程横幅 | 无身份 | 增加一行：`身份: session=<uuid> instance=<uuid> pid=<pid>` |
| Java 日志前缀 | `[host#<sessionId>]`（`src/main/java/dev/mediafix/host/HostSession.java:428`） | `[host#<sessionId>/<instanceUuid 前 8>]` |
| 子进程日志文件名 | `cineduct-host-<时间>-p<pid>.log`（`native/host/src/host_log.cpp:528`） | `cineduct-host-<时间>-<instanceUuid 前 8>-p<pid>.log` |
| minidump 名 | `cineduct-host-crash-<pid>.dmp`（`native/host/src/main.cpp:213`） | `cineduct-host-crash-<instanceUuid 前 8>-<pid>.dmp` |

短 UUID 统一取 **8** 位（够区分、不占宽度）。命名长度检查：日志文件名的轮转只认 `cineduct-host-*.log`（`native/host/src/host_log.cpp:244`），加一段前缀不影响轮转。

**边界**：传进来的 UUID 格式非法时**不加标签**（文件名退回 `...-p<pid>.log`）—— 实测第一版会把非法参数里的合法字符拼成 `-xxx-`，已修，见 `shortUuidTag()` 的注释。手动跑 exe、不传身份参数时同理。

---

## 6. 命令面（Java）

新增 `/cineduct host ...`：

| 命令 | 作用 |
| --- | --- |
| `/cineduct host list` | 列出**所有**子进程实例：`#sessionId  短UUID  存活/已退出  pid  形态  发布帧  丢弃  传输方式` |
| `/cineduct host info <寻址>` | 单条详情：两个 UUID、pid、形态、解码路径、缓冲位、音频水位/欠载 |
| `/cineduct host kill <寻址>` | **放弃该子进程并回退到进程内引擎**（位置/音量交接过去，屏幕不会黑）。注意：不是"只杀不管"—— 杀了之后这条会话既没子进程也没引擎，屏幕会永远停在"加载中" |
| `/cineduct host restart <寻址>` | 换一个新子进程（等价 `rebuild()`：位置尽量保留，画面闪一下） |

`<寻址>` 三种写法都接受：完整 UUID（session 或 instance）、UUID 前 8 位（可带不带连字符）、`#<sessionId>`。

现状缺口：命令面只有全局开关（`src/main/java/dev/mediafix/CineDuct.java:168-175`），看进程只看 `Sessions.last()` 一条（同文件 `716-744`），多屏时无法定点管理 —— 这是本次要补的。

注册表改造（**与最初写法有一处调整**）：`Map<Long, MediaSession> BY_ID`（`src/main/java/dev/mediafix/player/Sessions.java:23`）保持不动，**不新增** `Map<UUID, HostInstance>` 索引，而是提供 `hostViews()` 现算视图。
理由：索引会有"会话已关但索引忘了删"的过期问题；这个视图只给诊断/管理命令用（BY_ID 通常 1~4 条），现算成本可忽略，却能保证**永不陈旧**。目标不变（能枚举、能定点）。

---

## 7. 分阶段实施

| 阶段 | 语言 | 内容 | 验收 |
| --- | --- | --- | --- |
| **A** | 仅 C++ | 参数解析 + 缺省自生成、横幅、状态环 v9 写入、`hello` 回显、日志与 dump 名带短 UUID | `cineduct-host.exe --url <本地 mp4> --seconds 5`：横幅有身份行、`hello` 回显一致（配合现有自检形态 `native/host/src/main.cpp:518-524`），并且**不传新参数时行为与现在完全一致** |
| **B** | 仅 Java | 生成两层 UUID、传参、`awaitRing` 校验、日志前缀、注册表、四条命令 | 游戏内：多屏时 `host list` 全部列出；`kill`/`restart` 能定点命中；日志前缀带短 UUID；旧子进程（无身份字段）仍能起（校验按"空 = 跳过"处理） |

**兼容底线**：阶段 A 单独上线必须对老 Java 无事（只多两个可选参数、只追加字段）；阶段 B 必须容忍"状态环里 UUID 为空"的老 exe（读不到就跳过校验，不要判失败）。

---

## 8. 风险与前提

- `char[37]` 只追加，`#pragma pack(8)` 下不改变既有字段对齐（644 已按 8 字节对齐，648/685 之后到 722 由 `reserved3` 补齐到 728）。
- 短 UUID 冲突概率可忽略（8 位十六进制 = 4×10⁹），但 `host list` 里仍显示 `#sessionId` 兜底。
- 不改 `protocol` 版本号；`hello` 回执新增字段属"向后兼容的附加信息"。
- 未做：把 UUID→pid 落盘（外部看门狗用）。按 D2 决策**暂不落盘**；将来要外部枚举，再加 `cineduct-hosts/` 目录并把 `instanceUuid` 写进文件名，`sessionUuid` 写进内容。

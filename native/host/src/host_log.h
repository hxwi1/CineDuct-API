// ============================================================================
// 子进程调试日志（cmd 弹窗 + 落盘 + 回写 Java 管道）
//
// 为什么需要这一层：排查"子进程到底怎么了"时，现有的三条路都不够用 ——
//   ① 它的 stdout 被 Java 的 drainOutput 收进 cineductlogs 的 diag 里（行首带 [host#N]），
//      但要跟别的高频诊断混在一起看；
//   ② 手动在 cmd 里跑 exe 才有窗口，游戏里跑起来是黑盒；
//   ③ 它崩在系统音频模块里（`audiokse.dll` 那种）时，管道里那点内容救不了场 ——
//      要的是"崩溃前最后几十行"。
//
// 所以这里做三件事，**只在调试模式生效，正常播放一行代码都不走**：
//   1. **落盘**：`<游戏目录>/cineductlogs/host/cineduct-host-<时间>-<pid>.log`。
//      stdout/stderr 都是无缓冲的（见 main.cpp 的 setvbuf），所以是**写完就落盘**，
//      崩溃也能保住最后一行；
//   2. **弹窗**：`AllocConsole()` 开一个 cmd 窗口，实时把同样的内容写进去。
//      用 `WriteConsoleW` 而不是 `WriteFile`：中文与代码页无关，不会乱码；
//   3. **镜像**：把同样的内容再写回 Java 那条 stdout 管道 —— 调试模式**不改变**
//      Java 侧的 `[host#N]` 诊断（否则一开调试反而在 Java 日志里看不到子进程了）。
//
// ★ 实现手法（为什么不改那 138 处 printf）：
//   把 fd 1/2 用 `_dup2` 指到日志文件，于是**所有**既有输出自动落盘 ——
//   包括视频流水线那 1800 行里的、包括将来新加的、也包括 FFmpeg 自己经 stderr 出来的
//   `av_log` 警告。再由一个后台线程把文件里新增的内容读出来，镜像到控制台与管道。
//   好处是**零调用点改动**（流水线一个字都不用动，也不会漏），代价是控制台/管道
//   比文件晚约 60ms —— 文件永远是最新的，它才是排查时的权威来源。
//
// ⚠️ 两个必须守住的坑（都实测踩过，注释留在实现里）：
//   * `GetStdHandle` 拿到的句柄**不能直接存下来当镜像目标**：`_dup2` 在替换目标描述符时
//     会把目标原有的 OS 句柄关掉，存下来的值立刻变野值，还会被"日志文件自己的句柄"重新用掉
//     —— 症状是日志以每轮一整份的速度无限增长、进程永远退不出去。必须 DuplicateHandle 一份。
//   * 镜像写管道**绝不能阻塞媒体线程**：所以 printf 只写本地文件，投影交给独立线程；
//     Java 那侧就算卡住，最坏也只是窗口慢一点，画面与声音不受影响。
//
// 为什么不让后台线程直接"接管 printf"（比如把 fd 1 接到管道、线程再写文件）：
//   那样一旦 Java 那侧的读取线程卡住，管道缓冲区一满，**媒体线程的 printf 就会阻塞**，
//   卡的是画面与声音。现在的写法里 printf 只写本地文件，永远不阻塞，
//   最坏情况只是控制台镜像慢一点（见 emit() 里对 ERROR_BROKEN_PIPE 的处理）。
// ============================================================================
#pragma once

#include <string>

namespace cineduct::host {

/**
 * 预扫描命令行，决定要不要开调试日志。
 *
 * <p>必须在 {@code parseArgs} **之前**调用：参数写错了也要能落盘（不然最需要日志的时候没有）。
 * 本函数只读不写，没有任何副作用。
 *
 * <p>开启方式（优先级从高到低）：
 * <ol>
 *   <li>命令行：{@code --console} / {@code --log-dir <目录>} / {@code --log-file <文件>}
 *       / {@code --log-keep <个数>} / {@code --no-console} / {@code --no-log-file}
 *       （Java 侧将来想接，就在 HostSession 的 cmd 里加一个 {@code --console} 即可）；</li>
 *   <li>环境变量：{@code CINEDUCT_HOST_DEBUG=1}；</li>
 *   <li>标记文件：当前目录或 exe 同目录下的 {@code cineduct-host-debug.flag}
 *       —— **这条是为了不改 Java 代码就能开**（游戏里没法方便地设环境变量）。</li>
 * </ol>
 */
void hostLogConfigure(int argc, char** argv);

/**
 * 按 {@link hostLogConfigure} 的结论把日志支起来。
 *
 * @return 是否真的在写文件；false = 调试模式没开，一切照旧（连控制台都不碰）
 */
bool hostLogStart();

/**
 * 设置日志文件名里的"进程实例标签"（8 位以内的短 UUID）。
 *
 * <p>为什么要在 {@link hostLogStart} **之前**单独设一次：文件名里的时间 + pid 不足以区分
 * "同一秒内重建的两次子进程实例"（pid 会复用），而身份是 main() 预扫描 `--instance-uuid`
 * 之后才有的，parseArgs 又晚于 hostLogStart —— 所以由调用方在两者之间设进来。
 * 空串 = 文件名保持旧格式（手动跑 exe、不传身份参数时就是这样）。
 */
void hostLogSetInstanceTag(const std::string& shortTag);

/** 收尾：冲干净 → 补一轮镜像 → 停线程 → 关句柄。可重复调用。 */
void hostLogStop();

/** 把文件里新增的内容镜像到控制台与管道（崩溃处理里调用；只试锁，不死等）。 */
void hostLogDrain();

/** 是否已经支起来了（在写文件）。 */
bool hostLogEnabled();

/** 是否有可写的控制台窗口（弹出来了，或者原本就有一个）。 */
bool hostLogConsoleActive();

/** 本次调试日志的文件路径（没开就是空串）。 */
const std::wstring& hostLogFilePath();

} // namespace cineduct::host

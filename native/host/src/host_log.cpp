// ============================================================================
// 子进程调试日志的实现。设计意图与"为什么这么写"见 host_log.h 顶部。
//
// 一句话概括数据流：
//
//     printf / fprintf(stderr)  ──► fd 1/2（被 _dup2 指到日志文件）──► 日志文件
//                                                                       │
//                                            镜像线程每 60ms 读新增内容 ─┤
//                                                                       ├──► cmd 窗口（WriteConsoleW）
//                                                                       └──► Java 那条 stdout 管道（WriteFile）
//
// 文件是权威来源：它写完就落盘（stdout/stderr 都是无缓冲），崩溃也保得住最后一行；
// 控制台与管道是它的"投影"。
// ============================================================================
#include "host_log.h"

#include <windows.h>

#include <fcntl.h>
#include <io.h>
#include <sys/stat.h>

#include <algorithm>
#include <atomic>
#include <cctype>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <functional>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace cineduct::host {
namespace {

struct Options {
    bool enabled = false;      // 整条链路的开关（写文件）
    bool console = false;      // 允许弹/用 cmd 窗口
    int keep = 10;             // 保留最近几份（含本次）
    std::wstring dir;          // 首选目录（空 = 用兜底）
    std::wstring fallbackDir;  // 次选目录（跳过 exe 目录找标记时用）
    std::wstring filePath;     // 空 = 目录 + 自动文件名
};

Options g_opt;

/**
 * 日志文件名里的"进程实例标签"（短 UUID，见 hostLogSetInstanceTag）。
 *
 * <p>放在匿名 namespace 里 + 提供 setter：它必须在 hostLogStart 之前被设进来，
 * 而那时 parseArgs 还没跑（身份来自 main() 的预扫描）。
 */
std::string g_instanceTag;

std::mutex g_mutex;
std::atomic<bool> g_running{false};
std::thread g_worker;

std::wstring g_path;                          // 本次日志文件
std::string g_cmdline;                        // 打码之后的命令行（进日志头）
std::wstring g_flagPath;                      // 命中的标记文件路径（诊断用）
DWORD g_parentPid = 0;                        // 游戏进程 pid（弹窗后要把焦点还给它）
bool g_focusReturned = false;                 // 是否成功把焦点还给了游戏（进日志头）
HANDLE g_read = INVALID_HANDLE_VALUE;         // 读回用手柄（与写出共享读写）
long long g_offset = 0;                       // 已经镜像到文件的哪个字节
std::vector<HANDLE> g_sinks;                  // 要镜像回去的管道（Java 的 stdout / stderr）
bool g_pipeAlive = false;
std::string g_pending;                        // 还没凑齐一行的尾巴
bool g_started = false;
std::wstring g_closeMark;                     // "窗口被关过一次"的标记文件（见 consoleCloseHandler）
std::atomic<HANDLE> g_console{nullptr};       // 只指向**我们自己建的**控制台，绝不指向继承来的那个

/**
 * 调试窗口被关掉时走这里。
 *
 * <p>两件事：① 立刻停止往窗口投影（文件照旧写，播放不受影响）；
 * ② 留一个标记文件，让**同一个游戏进程**里后续重建的子进程**别再弹窗** ——
 * 不做这件事的后果是"关不掉的窗口"：窗口一关 → 子进程被系统结束 → Java 判定异常并重建 →
 * 又弹一个 → 永远关不掉。
 *
 * <p>Windows 的规矩是：CTRL_CLOSE_EVENT 处理完（或超时 5 秒）之后进程仍会被结束，
 * 返回 TRUE 也拦不住 —— 所以这里只做"清理 + 不再弹"，不去假装能活下来。
 */
BOOL WINAPI consoleCloseHandler(DWORD type) {
    if (type != CTRL_CLOSE_EVENT) return FALSE;
    g_console.store(nullptr, std::memory_order_relaxed);
    if (!g_closeMark.empty()) {
        HANDLE h = CreateFileW(g_closeMark.c_str(), GENERIC_WRITE, 0, nullptr,
                               CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
        if (h != INVALID_HANDLE_VALUE) {
            const std::string note = "窗口被关过一次（pid " + std::to_string(GetCurrentProcessId()) + "）";
            DWORD written = 0;
            WriteFile(h, note.data(), static_cast<DWORD>(note.size()), &written, nullptr);
            CloseHandle(h);
        }
    }
    return FALSE;
}


// ===========================================================================
// 小工具
// ===========================================================================

std::wstring utf8ToWide(const char* s) {
    if (s == nullptr || *s == '\0') return {};
    const int n = MultiByteToWideChar(CP_UTF8, 0, s, -1, nullptr, 0);
    if (n <= 1) return {};
    std::wstring w(static_cast<size_t>(n), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, s, -1, w.data(), n);
    w.pop_back();
    return w;
}

/** 反过来：宽字符 → UTF-8（写日志用；路径可能带中文，不能直接 %ls 打）。 */
std::string wideToUtf8(const std::wstring& w) {
    if (w.empty()) return {};
    const int n = WideCharToMultiByte(CP_UTF8, 0, w.c_str(), static_cast<int>(w.size()),
                                     nullptr, 0, nullptr, nullptr);
    if (n <= 0) return {};
    std::string s(static_cast<size_t>(n), '\0');
    WideCharToMultiByte(CP_UTF8, 0, w.c_str(), static_cast<int>(w.size()), s.data(), n,
                        nullptr, nullptr);
    return s;
}

bool envTruthy(const char* name) {
    char buf[32] = {0};
    const DWORD n = GetEnvironmentVariableA(name, buf, sizeof(buf));
    if (n == 0 || n >= sizeof(buf)) return false;
    std::string v(buf, n);
    for (char& c : v) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    return v == "1" || v == "true" || v == "yes" || v == "on";
}

/**
 * 这个路径在不在 —— **文件和目录都算**。
 *
 * <p>★ 教训（2026-10-06，用户现场）：标记文件原先要求"必须是文件"（显式排除目录），
 * 结果玩家在资源管理器里顺手建成了**文件夹** —— 子进程判"不存在"、Java 侧
 * `Files.isRegularFile` 也判"不存在"，于是调试模式静默失效；
 * 而 `Test-Path` / `Get-Item` 对目录照样返回成功，把我自己核对时也一起骗过去了。
 * 这种开关类标记"建错了类型"没有任何意义，一律按"存在即生效"处理。
 */
bool fileExists(const std::wstring& p) {
    if (p.empty()) return false;
    return GetFileAttributesW(p.c_str()) != INVALID_FILE_ATTRIBUTES;
}

std::wstring exeDir() {
    wchar_t buf[MAX_PATH] = {0};
    const DWORD n = GetModuleFileNameW(nullptr, buf, MAX_PATH);
    if (n == 0) return {};
    std::wstring s(buf, n);
    const size_t p = s.find_last_of(L"\\/");
    return p == std::wstring::npos ? std::wstring() : s.substr(0, p);
}

/**
 * 进程现在有没有"已经挂着"的控制台。
 *
 * <p>★ 不能用 {@code GetConsoleWindow()} 来判断：**伪控制台（ConPTY）下它返回 NULL**，
 * 于是我们会去调 {@code AllocConsole} —— 可那时进程其实已经有控制台了，返回
 * {@code ERROR_ACCESS_DENIED(5)}，窗口根本建不出来（实测：Java 起的子进程正是这种情形，
 * 日志里只有一句"建控制台窗口失败（错误 5）"）。
 * {@code GetConsoleOutputCP()} 才是可靠判据：**没有控制台时它返回 0**。
 */
bool hasConsole() {
    return GetConsoleOutputCP() != 0 || GetConsoleWindow() != nullptr;
}

/**
 * 标记文件可能在哪。
 *
 * <p>★ 实测踩过的坑（2026-10-06）：只查"工作目录"是不够的 —— 子进程的工作目录是
 * **游戏进程继承下来的**，而启动器把它设成哪儿完全看启动器（实测不是版本目录），
 * 于是玩家按说明把 `cineduct-host-debug.flag` 放进版本目录，子进程却看不见：
 * 没有窗口、没有日志文件，整条调试链路静默失效。
 * 现在连 **exe 目录往上两级**一起找：jar 释放出来的 exe 在
 * {@code <实例>\cineduct-native\}，所以"<实例>\"这一级正好被覆盖。
 */
std::vector<std::wstring> flagCandidates() {
    std::vector<std::wstring> out;
    constexpr const wchar_t* name = L"cineduct-host-debug.flag";
    out.emplace_back(name);                       // ① 工作目录
    std::wstring dir = exeDir();
    for (int i = 0; i < 3 && !dir.empty(); ++i) { // ② exe 目录 + 往上两级
        out.push_back(dir + L"\\" + name);
        const size_t p = dir.find_last_of(L"\\/");
        if (p == std::wstring::npos || p == 0) break;
        dir = dir.substr(0, p);
    }
    return out;
}

/**
 * 找出某个进程的顶层主窗口（用来把焦点还给它）。
 *
 * <p>为什么必须还：{@code AllocConsole()} 会**抢走前台焦点**，而单人游戏下
 * Minecraft 一丢焦点就自动弹出暂停菜单 —— 而暂停菜单会让 mod 把播放一起暂停
 * （见 {@code ScreenClientState.isGamePaused()}），最终表现就是**视频黑屏不动**。
 * 实测证据：2026-10-06 那次"改完放不出来"的会话里，子进程一接上控制通道，
 * Java 就把 paused=true 推了过来，之后一帧都没发。
 */
BOOL CALLBACK findMainWindowProc(HWND hwnd, LPARAM param) {
    struct Ctx {
        DWORD pid;
        HWND found;
    };
    auto* ctx = reinterpret_cast<Ctx*>(param);
    DWORD pid = 0;
    GetWindowThreadProcessId(hwnd, &pid);
    if (pid != ctx->pid) return TRUE;
    if (!IsWindowVisible(hwnd)) return TRUE;
    if (GetWindow(hwnd, GW_OWNER) != nullptr) return TRUE;   // 只要顶层窗口，不要对话框/工具窗
    if (GetWindowTextLengthW(hwnd) == 0) return TRUE;
    ctx->found = hwnd;
    return FALSE;                                            // 找到第一个就停
}

HWND mainWindowOf(DWORD pid) {
    if (pid == 0) return nullptr;
    struct Ctx {
        DWORD pid;
        HWND found;
    } ctx{pid, nullptr};
    EnumWindows(findMainWindowProc, reinterpret_cast<LPARAM>(&ctx));
    return ctx.found;
}

/** 逐级建目录（"cineductlogs\host" 这种两级路径要能一次建出来）。 */
bool ensureDir(const std::wstring& path) {
    if (path.empty()) return false;
    const DWORD attrs = GetFileAttributesW(path.c_str());
    if (attrs != INVALID_FILE_ATTRIBUTES) return (attrs & FILE_ATTRIBUTE_DIRECTORY) != 0;
    for (size_t i = 1; i <= path.size(); ++i) {
        if (i != path.size() && path[i] != L'\\' && path[i] != L'/') continue;
        const std::wstring prefix = path.substr(0, i);
        // 失败也无所谓（"C:" 这种会 ACCESS_DENIED、已存在的会 ALREADY_EXISTS）
        CreateDirectoryW(prefix.c_str(), nullptr);
    }
    return (GetFileAttributesW(path.c_str()) & FILE_ATTRIBUTE_DIRECTORY) != 0;
}

/** 只保留最近 keep 份 `cineduct-host-*.log`（文件名带时间，字典序倒排就是新到旧）。 */
void rotateOldLogs(const std::wstring& dir, int keep) {
    if (keep <= 0 || dir.empty()) return;
    WIN32_FIND_DATAW fd{};
    const std::wstring pattern = dir + L"\\cineduct-host-*.log";
    HANDLE h = FindFirstFileW(pattern.c_str(), &fd);
    if (h == INVALID_HANDLE_VALUE) return;
    std::vector<std::wstring> names;
    do {
        if ((fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) == 0) names.emplace_back(fd.cFileName);
    } while (FindNextFileW(h, &fd));
    FindClose(h);
    if (static_cast<int>(names.size()) <= keep) return;
    std::sort(names.begin(), names.end(), std::greater<std::wstring>());
    for (size_t i = static_cast<size_t>(keep); i < names.size(); ++i) {
        DeleteFileW((dir + L"\\" + names[i]).c_str());
    }
}

/**
 * 能不能往这个句柄上镜像。
 *
 * <p>不是管道也要接（有人可能把 stdout 重定向到文件），**但控制台句柄必须排除** ——
 * 往控制台句柄上 WriteFile 原始 UTF-8 会乱码，而且那条路已经由 {@code WriteConsoleW} 负责了，
 * 两边都写还会重复一遍。
 */
bool mirrorable(HANDLE h) {
    if (h == nullptr || h == INVALID_HANDLE_VALUE) return false;
    DWORD mode = 0;
    if (GetConsoleMode(h, &mode)) return false;      // 控制台：走 g_console 那条
    const DWORD type = GetFileType(h);
    return type == FILE_TYPE_PIPE || type == FILE_TYPE_DISK;
}

/**
 * 命令行文本（进日志头）。
 *
 * <p>★ 必须打码：`--url` 是带签名的直链，`--headers` 里**含 `Cookie: SESSDATA=…`**。
 * 日志是要发给别人看的，原样抄一遍就等于把凭证抄进了日志里 —— Java 侧
 * `CineDuctLog.url()` 也是这个处理（截掉查询串）。
 */
std::string commandLineText(int argc, char** argv) {
    std::string out;
    for (int i = 1; i < argc; ++i) {
        std::string a = argv[i] == nullptr ? "" : argv[i];
        if (a == "--headers" && i + 1 < argc) {
            const size_t len = std::strlen(argv[i + 1]);
            out += " --headers <已隐去，含 Cookie，共 " + std::to_string(len) + " 字符>";
            ++i;
            continue;
        }
        // 通用兜底：任何看起来像凭证的参数都不原样落盘
        std::string low = a;
        for (char& c : low) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
        if (low.find("cookie") != std::string::npos || low.find("sessdata") != std::string::npos) {
            out += " <已隐去（疑似凭证）>";
            continue;
        }
        const size_t q = a.find('?');
        if (q != std::string::npos && a.size() > 96) {
            const std::string head = a.substr(0, q);
            const std::string query = a.substr(q + 1);
            out += " " + head + "?" + (query.size() > 24 ? query.substr(0, 24) + "…" : query)
                    + "(共 " + std::to_string(a.size()) + " 字符)";
            continue;
        }
        out += " " + a;
    }
    return out;
}

// ===========================================================================
// 投影：把一行发给 cmd 窗口与 Java 管道
// ===========================================================================

void emitLine(const std::string& raw) {
    std::string body = raw;
    while (!body.empty() && (body.back() == '\r' || body.back() == '\n')) body.pop_back();
    if (body.empty()) return;

    const HANDLE con = g_console.load(std::memory_order_relaxed);
    if (con != nullptr) {
        const int need = MultiByteToWideChar(CP_UTF8, 0, body.data(), static_cast<int>(body.size()),
                                             nullptr, 0);
        if (need > 0) {
            std::wstring w(static_cast<size_t>(need), L'\0');
            MultiByteToWideChar(CP_UTF8, 0, body.data(), static_cast<int>(body.size()), w.data(), need);
            w += L"\r\n";
            DWORD written = 0;
            if (!WriteConsoleW(con, w.data(), static_cast<DWORD>(w.size()), &written, nullptr)) {
                g_console.store(nullptr, std::memory_order_relaxed);   // 窗口没了：以后不再写（日志照旧落盘）
            }
        }
    }

    if (!g_pipeAlive) return;
    std::string out = body;
    out += "\r\n";
    for (HANDLE h : g_sinks) {
        DWORD written = 0;
        if (!WriteFile(h, out.data(), static_cast<DWORD>(out.size()), &written, nullptr)) {
            const DWORD e = GetLastError();
            // Java 那侧收了/进程要退了：别再试。注意这里**绝不能**回头影响文件那条路。
            if (e == ERROR_BROKEN_PIPE || e == ERROR_NO_DATA || e == ERROR_INVALID_HANDLE) {
                g_pipeAlive = false;
                return;
            }
        }
    }
}

/** 把文件里从 g_offset 起的新内容读出来，按行投影出去。 */
void pumpOnce() {
    if (g_read == INVALID_HANDLE_VALUE) return;
    LARGE_INTEGER off{};
    off.QuadPart = g_offset;
    if (!SetFilePointerEx(g_read, off, nullptr, FILE_BEGIN)) return;
    char buf[8192];
    DWORD got = 0;
    while (ReadFile(g_read, buf, sizeof(buf), &got, nullptr) && got > 0) {
        g_pending.append(buf, got);
        g_offset += got;
        size_t nl = 0;
        while ((nl = g_pending.find('\n')) != std::string::npos) {
            emitLine(g_pending.substr(0, nl));
            g_pending.erase(0, nl + 1);
        }
        // 保护：有人打了一行几百 MB 的东西时别把内存吃光
        if (g_pending.size() > (4u << 20)) {
            emitLine(g_pending);
            g_pending.clear();
        }
    }
}

void workerLoop() {
    while (g_running.load(std::memory_order_relaxed)) {
        {
            std::lock_guard<std::mutex> lk(g_mutex);
            pumpOnce();
        }
        // 60ms 一轮：控制台看起来是"实时"的，CPU 开销可以忽略（一次 open/read 小文件）
        std::this_thread::sleep_for(std::chrono::milliseconds(60));
    }
}

void printBanner() {
    SYSTEMTIME st{};
    GetLocalTime(&st);
    std::printf("===== cineduct-host 调试会话 =====\n");
    std::printf("时间  : %04u-%02u-%02u %02u:%02u:%02u.%03u\n", st.wYear, st.wMonth, st.wDay,
                st.wHour, st.wMinute, st.wSecond, st.wMilliseconds);
    std::printf("进程  : pid=%lu\n", static_cast<unsigned long>(GetCurrentProcessId()));
    std::printf("窗口  : %s\n", g_console.load() != nullptr
            ? (g_focusReturned ? "已就绪（日志实时投影到 cmd 窗口；焦点已还给游戏）"
                               : "已就绪（日志实时投影到 cmd 窗口）")
            : "未启用（只落盘 + 回写 Java 管道）");
    std::printf("日志  : %ls\n", g_path.c_str());
}

} // namespace

// ===========================================================================
// 对外接口
// ===========================================================================

void hostLogSetInstanceTag(const std::string& shortTag) {
    /*
     * 只收 8 位以内的 [0-9a-z]：这个名字要进**文件名**，
     * 而文件名会出现在玩家要发给别人的路径里 —— 不能带空格、斜杠、冒号之类。
     * 其余字符一律丢弃，超长截断。
     */
    std::string clean;
    for (char c : shortTag) {
        if (c >= 'A' && c <= 'Z') c = static_cast<char>(c - 'A' + 'a');
        const bool ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z');
        if (!ok) continue;
        clean.push_back(c);
        if (clean.size() == 8) break;
    }
    std::lock_guard<std::mutex> lock(g_mutex);
    g_instanceTag = clean;
}

void hostLogConfigure(int argc, char** argv) {
    Options opt;

    // ---- ① 环境变量 / 标记文件：只能"打开"，要关掉得用命令行 ----
    if (envTruthy("CINEDUCT_HOST_DEBUG")) {
        opt.enabled = true;
        opt.console = true;
    }
    // 标记文件是为了**不改 Java 代码就能开**：游戏里设环境变量很麻烦，
    // 而在游戏目录里放一个空文件（或干脆建个文件夹，都认）谁都会。
    for (const std::wstring& candidate : flagCandidates()) {
        if (!fileExists(candidate)) continue;
        opt.enabled = true;
        opt.console = true;
        g_flagPath = candidate;
        const size_t slash = candidate.find_last_of(L"\\/");
        const std::wstring parent =
                slash == std::wstring::npos ? std::wstring() : candidate.substr(0, slash);
        /*
         * ★ 日志放哪（按玩家的直觉来）：
         *   * 标记是**文件夹** → 日志就写进那个文件夹里（"我建了个文件夹，日志当然放里面"）；
         *   * 标记是**文件**   → 写进它旁边的 cineductlogs\host（文件旁边没法装东西）；
         *   * 两种都留一条退路到 cineductlogs\host，免得首选目录不可写时整条链路静默失效。
         * 命令行上的 --log-dir 优先级更高（它在下面的循环里按顺序覆盖）。
         */
        const DWORD attrs = GetFileAttributesW(candidate.c_str());
        if (attrs != INVALID_FILE_ATTRIBUTES && (attrs & FILE_ATTRIBUTE_DIRECTORY) != 0) {
            opt.dir = candidate;
        }
        if (!parent.empty()) opt.fallbackDir = parent + L"\\cineductlogs\\host";
        break;
    }

    // ---- ② 命令行：优先级最高，且按出现顺序（后面的覆盖前面的） ----
    for (int i = 1; i < argc; ++i) {
        const std::string k = argv[i] == nullptr ? "" : argv[i];
        if (k == "--console" || k == "--debug-console" || k == "--debug") {
            opt.enabled = true;
            opt.console = true;
        } else if (k == "--no-console") {
            opt.console = false;
        } else if (k == "--no-log-file") {
            opt.enabled = false;
            opt.console = false;
        } else if (k == "--log-dir" && i + 1 < argc) {
            opt.dir = utf8ToWide(argv[++i]);
            opt.enabled = true;
        } else if (k == "--log-file" && i + 1 < argc) {
            opt.filePath = utf8ToWide(argv[++i]);
            opt.enabled = true;
        } else if (k == "--log-keep" && i + 1 < argc) {
            const int n = std::atoi(argv[++i]);
            opt.keep = n > 0 ? n : 10;
        } else if (k == "--parent-pid" && i + 1 < argc) {
            // 弹窗后要把焦点还给游戏窗口，所以这里顺手记下来（Args 里也有一份，各用各的）
            g_parentPid = static_cast<DWORD>(std::strtoul(argv[++i], nullptr, 10));
        }
    }
    // 要窗口就必须落盘：窗口显示的内容就是从文件镜像过去的，没有文件就没有窗口内容
    if (opt.console) opt.enabled = true;

    g_opt = opt;
    g_cmdline = commandLineText(argc, argv);
}

bool hostLogStart() {
    if (g_started) return false;
    if (!g_opt.enabled) {
        /*
         * ★ 没开也留一行线索（2026-10-06 的教训）。
         *
         * 那次玩家按说明把 cineduct-host-debug.flag 放进了游戏目录，结果"没窗口、没日志"，
         * 而两边都拿不到任何证据 —— 因为关闭状态这里一个字都不打，只能靠猜。
         * 现在把三样东西打出来：exe 目录、进程工作目录、每个候选路径存在与否。
         * 两行而已，走的是原本的 stdout 管道，Java 侧照旧收得到，正常播放不受影响。
         */
        std::printf("[host] 调试日志: 关（没有 --console/--log-dir，也没找到标记文件）\n");
        std::printf("[host]   exe 目录=%s\n", wideToUtf8(exeDir()).c_str());
        wchar_t cwd[MAX_PATH] = {0};
        if (GetCurrentDirectoryW(MAX_PATH, cwd) != 0) {
            std::printf("[host]   工作目录=%s\n", wideToUtf8(cwd).c_str());
        }
        for (const std::wstring& candidate : flagCandidates()) {
            std::printf("[host]   候选 %s %s\n", wideToUtf8(candidate).c_str(),
                        fileExists(candidate) ? "存在" : "不存在");
        }
        return false;
    }

    // ---- 1) 定路径：按优先级逐个试，第一个能开的就用它 ----
    //
    // 顺序（与 Java 侧传下来的参数一致）：
    //   ① --log-file 指定的整条路径；
    //   ② --log-dir 指定的目录；
    //   ③ **标记文件本身是文件夹** → 就写进那个文件夹（玩家建个文件夹当开关，
    //      直觉就是"日志放这里面"）；标记是文件的话退到它旁边的 cineductlogs\host；
    //   ④ 兜底：工作目录下的 cineductlogs\host。
    std::wstring path = g_opt.filePath;
    int fd = -1;
    const int kOpenFlags = _O_WRONLY | _O_CREAT | _O_APPEND | _O_TEXT;
    if (!path.empty()) {
        const size_t slash = path.find_last_of(L"\\/");
        if (slash != std::wstring::npos) ensureDir(path.substr(0, slash));
        fd = _wopen(path.c_str(), kOpenFlags, _S_IREAD | _S_IWRITE);
        if (fd < 0) {
            std::fprintf(stderr, "[host] 打不开调试日志（%ls），调试模式关闭\n", path.c_str());
            return false;
        }
    } else {
        std::vector<std::wstring> dirs;
        if (!g_opt.dir.empty()) dirs.push_back(g_opt.dir);
        if (!g_opt.fallbackDir.empty() && g_opt.fallbackDir != g_opt.dir) {
            dirs.push_back(g_opt.fallbackDir);
        }
        dirs.emplace_back(L"cineductlogs\\host");
        for (const std::wstring& dir : dirs) {
            if (!ensureDir(dir)) continue;
            // 先删旧的：keep 是"含本次"的总数，给马上要建的这份留一个名额
            rotateOldLogs(dir, g_opt.keep > 1 ? g_opt.keep - 1 : 1);
            SYSTEMTIME st{};
            GetLocalTime(&st);
            wchar_t name[160];
            /*
             * 文件名：cineduct-host-<时间>[-<实例短UUID>]-p<pid>.log
             *
             * 中间那段只有拿到身份时才加（见 hostLogSetInstanceTag 的说明）；
             * 轮转只认 `cineduct-host-*.log`（见 rotateOldLogs），所以加一段不影响轮转。
             */
            std::wstring tagW;
            if (!g_instanceTag.empty()) {
                tagW.assign(g_instanceTag.begin(), g_instanceTag.end());
                tagW = L"-" + tagW;
            }
            _snwprintf_s(name, _TRUNCATE, L"cineduct-host-%04u-%02u-%02u_%02u-%02u-%02u%s-p%lu.log",
                         st.wYear, st.wMonth, st.wDay, st.wHour, st.wMinute, st.wSecond,
                         tagW.c_str(),
                         static_cast<unsigned long>(GetCurrentProcessId()));
            const std::wstring candidate = dir + L"\\" + name;
            fd = _wopen(candidate.c_str(), kOpenFlags, _S_IREAD | _S_IWRITE);
            if (fd >= 0) {
                path = candidate;
                break;
            }
        }
        if (fd < 0) {
            std::fprintf(stderr, "[host] 调试日志一个候选目录都建不出来，调试模式关闭\n");
            return false;
        }
    }

    // ---- 2) 先抓住要镜像回去的句柄（Java 用 redirectErrorStream 合并时两者本就同一个） ----
    //
    // ★★ 必须 **DuplicateHandle 一份自己的**，不能直接用 GetStdHandle 拿到的值。
    //
    // 血的教训（2026-10-06，实测踩到）：UCRT 的 `_dup2` 在替换目标描述符之前会**把目标原有的
    // OS 句柄关掉**（"target descriptor is closed first"）。于是下面第 3 步一旦执行，
    // 我们刚存进 g_sinks 的那个句柄值就**变成了野值**，而它的值会被紧接着的
    // `_wopen`（日志文件）重新用掉 —— 结果镜像线程"把日志文件的内容写回日志文件"：
    // 文件以每轮一整份的速度无限增长（实测跑到 127MB 仍在涨），进程也永远退不出去。
    // 拿一份自己的副本就与 `_dup2` 关谁无关了。
    g_sinks.clear();
    const HANDLE out = GetStdHandle(STD_OUTPUT_HANDLE);
    const HANDLE err = GetStdHandle(STD_ERROR_HANDLE);
    auto addSink = [](HANDLE h) {
        if (!mirrorable(h)) return;
        HANDLE dup = nullptr;
        if (DuplicateHandle(GetCurrentProcess(), h, GetCurrentProcess(), &dup, 0, FALSE,
                            DUPLICATE_SAME_ACCESS)) {
            g_sinks.push_back(dup);
        }
    };
    addSink(out);
    if (err != out) addSink(err);
    g_pipeAlive = !g_sinks.empty();

    // ---- 3) 把 fd 1/2 指到日志文件：此后**所有**既有 printf 自动落盘 ----
    std::fflush(nullptr);   // 重定向前先把可能缓冲的东西吐到原目的地
    // _dup2 出来的两个 fd 与源 fd **共享同一个文件对象（含文件偏移）**，
    // 所以 stdout 与 stderr 各写各的也不会互相覆盖 —— 这正是这里要的效果。
    if (_dup2(fd, 1) != 0 || _dup2(fd, 2) != 0) {
        _close(fd);
        std::fprintf(stderr, "[host] 重定向 stdout/stderr 失败，调试模式关闭\n");
        return false;
    }
    _close(fd);   // fd 1/2 已经指向它，源句柄可以放掉了
    g_path = path;
    /*
     * "窗口被关过一次"的标记：按**游戏进程 pid** 分文件，所以换一次游戏就是干净的一份
     * （上一次游戏里关过窗口，不影响这一次照样弹）。
     */
    {
        const size_t slash = path.find_last_of(L"\\/");
        const std::wstring logDir = slash == std::wstring::npos ? std::wstring() : path.substr(0, slash);
        g_closeMark = logDir + L"\\console-closed-p" + std::to_wstring(g_parentPid) + L".marker";
    }

    /*
     * ★ 不变量：镜像目标的句柄值绝不能等于日志文件自己的句柄。
     * 等于它就是"把日志写回日志"，会无限增长（成因见上面 DuplicateHandle 那段注释）。
     * 正常不可能发生（DuplicateHandle 给的是新句柄值），但这行是**立刻能发现**的护栏 ——
     * 当初要是写了它，那个 127MB 的坑一跑就暴露了。
     */
    const intptr_t logHandle = _get_osfhandle(1);
    if (logHandle >= 0) {
        const size_t before = g_sinks.size();
        g_sinks.erase(std::remove_if(g_sinks.begin(), g_sinks.end(),
                                     [logHandle](HANDLE h) {
                                         return reinterpret_cast<intptr_t>(h) == logHandle;
                                     }),
                      g_sinks.end());
        if (g_sinks.size() != before) {
            std::fprintf(stderr, "[host] 镜像句柄与日志文件句柄撞了（已丢弃，不影响落盘）\n");
        }
    }

    // ---- 4) 读回用手柄（与写句柄共享读写） ----
    g_read = CreateFileW(path.c_str(), GENERIC_READ,
                         FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                         nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (g_read == INVALID_HANDLE_VALUE) {
        // 不致命：日志照样落盘，只是没有窗口/管道投影
        std::fprintf(stderr, "[host] 日志文件打不开读句柄（错误 %lu），只有落盘没有窗口\n",
                     static_cast<unsigned long>(GetLastError()));
    }

    // ---- 5) 控制台窗口 ----
    /*
     * ★★ 铁律：**只用自己的窗口，绝不往继承来的控制台里写一个字**。
     *
     * 现场（2026-10-06，用户当场指出）：游戏是**从一个终端窗口起**的，于是子进程继承了那个
     * 终端的控制台 —— 我原来的写法"已经有控制台就直接用它"把调试日志打进了**别人的窗口**
     * （那个窗口里还跑着 agent 的宿主进程），而那个窗口一关，附带在里面的一切全被带走。
     * 现在：已经挂着控制台就先 FreeConsole（只把我们自己摘出来，不影响别的进程），
     * 再 AllocConsole 建一个**属于自己**的窗口；建不出来就只落盘，绝不碰任何已有窗口。
     */
    bool consoleOwned = false;
    if (g_opt.console) {
        if (!g_closeMark.empty() && fileExists(g_closeMark)) {
            // 上一次窗口被玩家关过：同一个游戏进程里就别再弹了（否则关一次弹一次，关不掉）
            std::fprintf(stderr, "[host] 调试窗口这次不弹（上一次被关过；删掉 %ls 可恢复）\n",
                         g_closeMark.c_str());
        } else {
            if (hasConsole()) FreeConsole();   // 先把继承来的那个摘掉（它可能是启动器/终端/agent 的）
            if (AllocConsole()) {
                consoleOwned = true;
                SetConsoleCtrlHandler(consoleCloseHandler, TRUE);   // 窗口被关时能留标记、且立刻停写
            } else {
                std::fprintf(stderr, "[host] 建控制台窗口失败（错误 %lu）；日志只落盘，不碰任何已有窗口\n",
                             static_cast<unsigned long>(GetLastError()));
            }
        }
    }
    if (consoleOwned) {
        /*
         * ★★ 建完自己的窗口后**必须把焦点还给游戏**。
         *
         * 现场（2026-10-06）：单人游戏里 AllocConsole 一弹窗就抢走焦点，Minecraft
         * 一丢焦点立刻弹出暂停菜单 —— mod 的 isGamePaused() 随即把播放暂停，
         * 子进程的音频时钟冻住 → 一帧都不发 → 玩家看到的就是"视频放不出来了"。
         * 顺序是"先把前台交给游戏，再让我们的窗口以不激活的方式显示"：
         * 此刻我们就是前台进程，SetForegroundWindow 才被允许。
         */
        const HWND game = mainWindowOf(g_parentPid);
        const HWND con = GetConsoleWindow();
        if (game != nullptr && SetForegroundWindow(game)) {
            g_focusReturned = true;
        }
        if (con != nullptr) ShowWindow(con, SW_SHOWNOACTIVATE);
        SetConsoleOutputCP(CP_UTF8);
        SetConsoleCP(CP_UTF8);
        // 中文走 WriteConsoleW，所以就算代码页是 GBK 也不会乱码
        HANDLE opened = CreateFileW(L"CONOUT$", GENERIC_WRITE | GENERIC_READ,
                                    FILE_SHARE_WRITE | FILE_SHARE_READ, nullptr,
                                    OPEN_EXISTING, 0, nullptr);
        g_console.store(opened == INVALID_HANDLE_VALUE ? nullptr : opened);
        if (g_console.load() != nullptr) {
            // 标题带上 pid：同时开几块屏幕就是几个子进程，任务栏里要分得清
            wchar_t title[700];
            _snwprintf_s(title, _TRUNCATE, L"CineDuct host pid=%lu（调试日志）",
                         static_cast<unsigned long>(GetCurrentProcessId()));
            SetConsoleTitleW(title);
        }
    }

    // ---- 6) 起镜像线程，再打日志头（日志头也会被投影到窗口与 Java 日志里） ----
    g_started = true;
    g_running.store(true);
    g_worker = std::thread(workerLoop);

    printBanner();
    std::printf("命令行:%s\n", g_cmdline.c_str());
    std::printf("==================================\n");
    std::fflush(nullptr);
    return true;
}

void hostLogDrain() {
    if (!g_started) return;
    std::unique_lock<std::mutex> lk(g_mutex, std::try_to_lock);
    if (!lk.owns_lock()) return;   // 崩溃现场：镜像线程正拿着锁就算了，绝不在这里死等
    pumpOnce();
}

void hostLogStop() {
    if (!g_started) return;
    g_started = false;
    std::fflush(nullptr);
    std::printf("===== cineduct-host 调试会话结束（pid=%lu）=====\n",
                static_cast<unsigned long>(GetCurrentProcessId()));
    std::fflush(nullptr);

    g_running.store(false);
    if (g_worker.joinable()) g_worker.join();
    {
        std::lock_guard<std::mutex> lk(g_mutex);
        pumpOnce();   // 收尾横幅补一轮投影（不然窗口里看不到最后一行）
    }
    if (g_read != INVALID_HANDLE_VALUE) {
        CloseHandle(g_read);
        g_read = INVALID_HANDLE_VALUE;
    }
    HANDLE con = g_console.exchange(nullptr);
    if (con != nullptr) CloseHandle(con);
    // 镜像用的句柄是 DuplicateHandle 出来的自己那份，要自己关（不关的话 Java 那侧读不到 EOF）
    for (HANDLE h : g_sinks) CloseHandle(h);
    g_sinks.clear();
    g_pipeAlive = false;
}

bool hostLogEnabled() {
    return g_started;
}

bool hostLogConsoleActive() {
    return g_started && g_console.load() != nullptr;
}

const std::wstring& hostLogFilePath() {
    return g_path;
}

} // namespace cineduct::host

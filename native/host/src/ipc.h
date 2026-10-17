// ============================================================================
// 控制通道（P2 · M4）
//
// 命名管道上的极简协议：Java 侧发命令，子进程回执。高频数据（帧、时钟、统计）**不走这里**，
// 它们走共享内存状态环（见 shared_tex）—— 管道只承担低频控制，简单可靠优先。
//
// 帧格式（小端）：
//   u32 length   —— 其后字节数（= 2 + 4 + payload 长度）
//   u16 type     —— 1 = 请求，2 = 回执
//   u32 requestId
//   payload      —— UTF-8 文本，形如 "hello 1" / "pause 1" / "seek 12000" / "volume 80"
//
// 为什么用文本而不是 JSON：这一步没有第三方库，文本够用且抓包可读。
// 协议版本随 hello 交换，双方不一致时子进程在回执里说明，上层可据此拒绝使用。
// ============================================================================
#pragma once

#include <atomic>
#include <functional>
#include <string>
#include <thread>

namespace cineduct::host {

/** 子进程要响应的一条命令。返回值是回执文本（空 = 无回执）。 */
using CommandHandler = std::function<std::string(const std::string& command)>;

struct ControlPipe {
    /** 管道名形如 cineduct-host-<token>（token 由 Java 生成，防别的本地进程顶替）。 */
    bool start(const std::string& pipeName, CommandHandler handler, std::string& error);
    void stop();

    bool running() const { return this->active.load(); }
    const std::string& name() const { return this->pipeName; }

private:
    void serve();

    std::string pipeName;
    CommandHandler handler;
    std::atomic<bool> quit{false};
    std::atomic<bool> active{false};
    std::thread worker;
    void* stopEvent = nullptr;   // 用于打断 ConnectNamedPipe（void* 避免头文件拉 Windows）
};

} // namespace cineduct::host

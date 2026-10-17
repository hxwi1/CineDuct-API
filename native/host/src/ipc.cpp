#include "ipc.h"

#include <windows.h>

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <vector>

namespace cineduct::host {

namespace {

constexpr uint16_t kTypeRequest = 1;
constexpr uint16_t kTypeReply = 2;

bool readExact(HANDLE pipe, void* dst, DWORD bytes) {
    auto* p = static_cast<uint8_t*>(dst);
    DWORD done = 0;
    while (done < bytes) {
        DWORD got = 0;
        if (!ReadFile(pipe, p + done, bytes - done, &got, nullptr) || got == 0) return false;
        done += got;
    }
    return true;
}

bool writeExact(HANDLE pipe, const void* src, DWORD bytes) {
    auto* p = static_cast<const uint8_t*>(src);
    DWORD done = 0;
    while (done < bytes) {
        DWORD wrote = 0;
        if (!WriteFile(pipe, p + done, bytes - done, &wrote, nullptr) || wrote == 0) return false;
        done += wrote;
    }
    return true;
}

} // namespace

bool ControlPipe::start(const std::string& name, CommandHandler cmdHandler, std::string& error) {
    this->pipeName = name;
    this->handler = std::move(cmdHandler);
    this->quit.store(false);

    // 用于在 stop() 时打断 ConnectNamedPipe 的阻塞等待
    this->stopEvent = CreateEventW(nullptr, TRUE, FALSE, nullptr);
    if (this->stopEvent == nullptr) {
        error = "CreateEvent 失败";
        return false;
    }
    this->active.store(true);
    this->worker = std::thread([this] { this->serve(); });
    return true;
}

void ControlPipe::serve() {
    const std::wstring full = L"\\\\.\\pipe\\" + std::wstring(this->pipeName.begin(), this->pipeName.end());

    while (!this->quit.load()) {
        HANDLE pipe = CreateNamedPipeW(
                full.c_str(),
                PIPE_ACCESS_DUPLEX,
                PIPE_TYPE_BYTE | PIPE_READMODE_BYTE | PIPE_WAIT,
                1,              // 单实例：只有一个游戏进程会连
                64 * 1024,
                64 * 1024,
                0,
                nullptr);
        if (pipe == INVALID_HANDLE_VALUE) {
            std::fprintf(stderr, "[host] 创建控制管道失败 (err=%lu)\n", GetLastError());
            this->active.store(false);
            return;
        }

        // 等客户端连接，同时监听停止事件
        OVERLAPPED ov{};
        ov.hEvent = CreateEventW(nullptr, TRUE, FALSE, nullptr);
        const BOOL connected = ConnectNamedPipe(pipe, &ov);
        const DWORD connErr = GetLastError();
        if (!connected && connErr == ERROR_IO_PENDING) {
            HANDLE waits[2] = {ov.hEvent, static_cast<HANDLE>(this->stopEvent)};
            const DWORD w = WaitForMultipleObjects(2, waits, FALSE, INFINITE);
            if (w == WAIT_OBJECT_0 + 1) {   // 要求停止
                CancelIo(pipe);
                CloseHandle(ov.hEvent);
                DisconnectNamedPipe(pipe);
                CloseHandle(pipe);
                break;
            }
            DWORD transferred = 0;
            if (!GetOverlappedResult(pipe, &ov, &transferred, FALSE)) {
                CloseHandle(ov.hEvent);
                DisconnectNamedPipe(pipe);
                CloseHandle(pipe);
                continue;
            }
        }
        CloseHandle(ov.hEvent);

        std::printf("[host] 控制通道已连接（游戏进程接入了）\n");

        // 一条连接上循环处理请求，直到客户端断开
        for (;;) {
            uint32_t length = 0;
            if (!readExact(pipe, &length, sizeof(length))) break;
            if (length < 6 || length > 65536) break;

            std::vector<uint8_t> buf(length);
            if (!readExact(pipe, buf.data(), length)) break;

            const uint16_t type = *reinterpret_cast<const uint16_t*>(buf.data());
            const uint32_t requestId = *reinterpret_cast<const uint32_t*>(buf.data() + 2);
            const std::string payload(reinterpret_cast<const char*>(buf.data() + 6), length - 6);

            if (type != kTypeRequest) continue;

            std::string reply;
            if (this->handler != nullptr) {
                reply = this->handler(payload);
            }

            const uint32_t replyLen = 6 + static_cast<uint32_t>(reply.size());
            std::vector<uint8_t> out(4 + replyLen);
            std::memcpy(out.data(), &replyLen, 4);
            const uint16_t replyType = kTypeReply;
            std::memcpy(out.data() + 4, &replyType, 2);
            std::memcpy(out.data() + 6, &requestId, 4);
            std::memcpy(out.data() + 10, reply.data(), reply.size());
            if (!writeExact(pipe, out.data(), static_cast<DWORD>(out.size()))) break;

            // quit 请求：回执发完就整体退出
            if (payload == "quit") {
                this->quit.store(true);
                break;
            }
        }

        FlushFileBuffers(pipe);
        DisconnectNamedPipe(pipe);
        CloseHandle(pipe);
        if (this->quit.load()) break;
        std::printf("[host] 控制通道断开，等待重新接入…\n");
    }
    this->active.store(false);
}

void ControlPipe::stop() {
    this->quit.store(true);
    if (this->stopEvent != nullptr) {
        SetEvent(static_cast<HANDLE>(this->stopEvent));
    }
    if (this->worker.joinable()) this->worker.join();
    if (this->stopEvent != nullptr) {
        CloseHandle(static_cast<HANDLE>(this->stopEvent));
        this->stopEvent = nullptr;
    }
}

} // namespace cineduct::host

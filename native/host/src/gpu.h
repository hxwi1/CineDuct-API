// ============================================================================
// 子进程用的 D3D11 设备（P2）
//
// 必须和游戏进程用**同一个适配器**：共享纹理是 DXGI 共享资源，
// 跨适配器根本不成立（详见 docs/PLAN-standalone-gpu.md §3.1/§3.6）。
// 因此适配器 LUID 由游戏进程（Java 侧）决定后经命令行传进来。
//
// 设备标志也有讲究：
//   BGRA_SUPPORT —— 共享纹理是 B8G8R8A8_UNORM，且 NV_DX_interop 要求；
//   VIDEO_SUPPORT —— d3d11va 硬解的前提；
//   ID3D10Multithread::SetMultithreadProtected —— 我们会有解码/音频/呈现多条线程。
// ============================================================================
#pragma once

#include <cstdint>
#include <string>

struct ID3D11Device;
struct ID3D11DeviceContext;

namespace cineduct::host {

struct GpuDevice {
    ID3D11Device* device = nullptr;
    ID3D11DeviceContext* context = nullptr;
    uint64_t luid = 0;
    std::string description;

    /** @param adapterLuid 0 = 默认硬件适配器（会挑显存最大的非软件适配器） */
    bool create(uint64_t adapterLuid, std::string& error);

    void destroy();
};

/** 枚举 DXGI 适配器，返回 "hi:lo|描述|显存MB|flags" 每行一条 UTF-8 文本。 */
std::string listAdapters();

} // namespace cineduct::host

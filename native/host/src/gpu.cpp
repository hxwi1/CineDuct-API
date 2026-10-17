#include "gpu.h"

#include <windows.h>
#include <d3d11.h>
#include <d3d10.h>
#include <dxgi1_2.h>
#include <wrl/client.h>

#include <cstdio>
#include <vector>

using Microsoft::WRL::ComPtr;

namespace cineduct::host {

namespace {

std::string narrow(const wchar_t* w) {
    if (w == nullptr) return {};
    const int n = WideCharToMultiByte(CP_UTF8, 0, w, -1, nullptr, 0, nullptr, nullptr);
    if (n <= 1) return {};
    std::string s(static_cast<size_t>(n), '\0');
    WideCharToMultiByte(CP_UTF8, 0, w, -1, s.data(), n, nullptr, nullptr);
    s.pop_back();
    return s;
}

uint64_t luidOf(const LUID& l) {
    return (static_cast<uint64_t>(static_cast<uint32_t>(l.HighPart)) << 32) | l.LowPart;
}

struct AdapterEntry {
    ComPtr<IDXGIAdapter1> adapter;
    DXGI_ADAPTER_DESC1 desc;
};

std::vector<AdapterEntry> enumAdapters() {
    std::vector<AdapterEntry> out;
    ComPtr<IDXGIFactory1> factory;
    if (FAILED(CreateDXGIFactory1(IID_PPV_ARGS(&factory)))) return out;
    for (UINT i = 0;; ++i) {
        ComPtr<IDXGIAdapter1> a;
        if (factory->EnumAdapters1(i, &a) == DXGI_ERROR_NOT_FOUND) break;
        DXGI_ADAPTER_DESC1 d{};
        a->GetDesc1(&d);
        out.push_back({a, d});
    }
    return out;
}

} // namespace

bool GpuDevice::create(uint64_t adapterLuid, std::string& error) {
    const auto adapters = enumAdapters();
    if (adapters.empty()) {
        error = "没有找到任何 DXGI 适配器";
        return false;
    }

    ComPtr<IDXGIAdapter1> adapter;
    if (adapterLuid != 0) {
        for (const auto& a : adapters) {
            if (luidOf(a.desc.AdapterLuid) == adapterLuid) {
                adapter = a.adapter;
                break;
            }
        }
        if (!adapter) {
            error = "指定的适配器 LUID 不存在（游戏进程和子进程必须在同一块卡上）";
            return false;
        }
    } else {
        uint64_t bestMemory = 0;
        for (const auto& a : adapters) {
            if (a.desc.Flags & (DXGI_ADAPTER_FLAG_SOFTWARE | DXGI_ADAPTER_FLAG_REMOTE)) continue;
            if (a.adapter == nullptr) continue;
            if (adapter == nullptr || a.desc.DedicatedVideoMemory > bestMemory) {
                adapter = a.adapter;
                bestMemory = a.desc.DedicatedVideoMemory;
            }
        }
        if (!adapter) adapter = adapters.front().adapter;
    }

    DXGI_ADAPTER_DESC1 desc{};
    adapter->GetDesc1(&desc);
    this->luid = luidOf(desc.AdapterLuid);
    this->description = narrow(desc.Description);

    const D3D_FEATURE_LEVEL levels[] = {D3D_FEATURE_LEVEL_11_1, D3D_FEATURE_LEVEL_11_0,
                                        D3D_FEATURE_LEVEL_10_1, D3D_FEATURE_LEVEL_10_0};
    const UINT flags = D3D11_CREATE_DEVICE_BGRA_SUPPORT | D3D11_CREATE_DEVICE_VIDEO_SUPPORT;

    ComPtr<ID3D11Device> dev;
    ComPtr<ID3D11DeviceContext> ctx;
    D3D_FEATURE_LEVEL got{};
    HRESULT hr = D3D11CreateDevice(adapter.Get(), D3D_DRIVER_TYPE_UNKNOWN, nullptr, flags, levels,
                                   _countof(levels), D3D11_SDK_VERSION, dev.GetAddressOf(), &got,
                                   ctx.GetAddressOf());
    if (FAILED(hr)) {
        char buf[256];
        std::snprintf(buf, sizeof(buf), "D3D11CreateDevice 失败 hr=0x%08lx",
                      static_cast<unsigned long>(hr));
        error = buf;
        return false;
    }

    // d3d11va 官方文档要求：用了 VIDEO_SUPPORT 就必须开多线程保护
    ComPtr<ID3D10Multithread> multithread;
    if (SUCCEEDED(ctx.As(&multithread))) {
        multithread->SetMultithreadProtected(TRUE);
    }

    this->device = dev.Detach();
    this->context = ctx.Detach();
    return true;
}

void GpuDevice::destroy() {
    if (this->context != nullptr) {
        this->context->Release();
        this->context = nullptr;
    }
    if (this->device != nullptr) {
        this->device->Release();
        this->device = nullptr;
    }
}

std::string listAdapters() {
    std::string out;
    for (const auto& a : enumAdapters()) {
        char line[512];
        std::snprintf(line, sizeof(line), "%08x:%08x|%s|%llu|%u\n",
                      static_cast<uint32_t>(a.desc.AdapterLuid.HighPart), a.desc.AdapterLuid.LowPart,
                      narrow(a.desc.Description).c_str(),
                      static_cast<unsigned long long>(a.desc.DedicatedVideoMemory / (1024ull * 1024ull)),
                      static_cast<unsigned>(a.desc.Flags));
        out += line;
    }
    return out;
}

} // namespace cineduct::host

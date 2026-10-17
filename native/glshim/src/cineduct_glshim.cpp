// ============================================================================
// cineduct-glshim 实现：C 接口 + JNI 薄包装
// ============================================================================

#include "cineduct_glshim.h"

#include <windows.h>
#include <d3d11.h>
#include <d3d11_2.h>        // ID3D11Device1::OpenSharedResource1 / ID3D11Device2::OpenSharedResourceByName
#include <d3d11_4.h>        // ID3D11Device5 / ID3D11Fence / ID3D11DeviceContext4（共享 fence 那条路）
#include <d3d10.h>          // ID3D10Multithread
#include <dxgi1_2.h>        // IDXGIResource1::CreateSharedHandle
#include <wrl/client.h>
#include <jni.h>

#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

using Microsoft::WRL::ComPtr;

namespace {

thread_local std::string g_lastError;

void setError(const char* what, HRESULT hr) {
    char buf[512];
    snprintf(buf, sizeof(buf), "%s (hr=0x%08lx)", what, static_cast<unsigned long>(hr));
    g_lastError = buf;
}

void setErrorText(const char* text) {
    g_lastError = text;
}

std::string narrow(const wchar_t* w) {
    if (w == nullptr) return {};
    int n = WideCharToMultiByte(CP_UTF8, 0, w, -1, nullptr, 0, nullptr, nullptr);
    if (n <= 1) return {};
    std::string s(static_cast<size_t>(n), '\0');
    WideCharToMultiByte(CP_UTF8, 0, w, -1, s.data(), n, nullptr, nullptr);
    s.pop_back();
    return s;
}

// 找到 LUID 对应的适配器；adapter_luid == 0 时返回 nullptr（表示"默认适配器"）。
HRESULT findAdapter(uint64_t adapter_luid, ComPtr<IDXGIAdapter1>& out, bool& isDefault) {
    isDefault = (adapter_luid == 0);
    if (isDefault) return S_OK;

    ComPtr<IDXGIFactory1> factory;
    HRESULT hr = CreateDXGIFactory1(IID_PPV_ARGS(&factory));
    if (FAILED(hr)) return hr;
    for (UINT i = 0;; ++i) {
        ComPtr<IDXGIAdapter1> a;
        if (factory->EnumAdapters1(i, &a) == DXGI_ERROR_NOT_FOUND) break;
        DXGI_ADAPTER_DESC1 d{};
        a->GetDesc1(&d);
        const uint64_t luid =
                (static_cast<uint64_t>(static_cast<uint32_t>(d.AdapterLuid.HighPart)) << 32) |
                d.AdapterLuid.LowPart;
        if (luid == adapter_luid) {
            out = a;
            return S_OK;
        }
    }
    setErrorText("指定的适配器 LUID 不存在");
    return DXGI_ERROR_NOT_FOUND;
}

uint64_t luidOfDevice(ID3D11Device* device) {
    if (device == nullptr) return 0;
    ComPtr<IDXGIDevice> dxgiDevice;
    if (FAILED(device->QueryInterface(IID_PPV_ARGS(&dxgiDevice)))) return 0;
    ComPtr<IDXGIAdapter> adapter;
    if (FAILED(dxgiDevice->GetAdapter(&adapter))) return 0;
    DXGI_ADAPTER_DESC d{};
    if (FAILED(adapter->GetDesc(&d))) return 0;
    return (static_cast<uint64_t>(static_cast<uint32_t>(d.AdapterLuid.HighPart)) << 32) |
           d.AdapterLuid.LowPart;
}

} // namespace

// ---------------------------------------------------------------------------
// C 接口
// ---------------------------------------------------------------------------

extern "C" void* mf_gpu_create_device(uint64_t adapter_luid, uint32_t flags) {
    g_lastError.clear();

    ComPtr<IDXGIAdapter1> adapter;
    bool isDefault = false;
    HRESULT hr = findAdapter(adapter_luid, adapter, isDefault);
    if (FAILED(hr)) return nullptr;

    UINT createFlags = 0;
    if (flags & MF_GPU_FLAG_BGRA_SUPPORT) createFlags |= D3D11_CREATE_DEVICE_BGRA_SUPPORT;
    if (flags & MF_GPU_FLAG_VIDEO_SUPPORT) createFlags |= D3D11_CREATE_DEVICE_VIDEO_SUPPORT;
    if (flags & MF_GPU_FLAG_DEBUG) createFlags |= D3D11_CREATE_DEVICE_DEBUG;

    D3D_FEATURE_LEVEL levels[] = {D3D_FEATURE_LEVEL_11_1, D3D_FEATURE_LEVEL_11_0,
                                  D3D_FEATURE_LEVEL_10_1, D3D_FEATURE_LEVEL_10_0};
    const D3D_DRIVER_TYPE driver = isDefault ? D3D_DRIVER_TYPE_HARDWARE : D3D_DRIVER_TYPE_UNKNOWN;

    ComPtr<ID3D11Device> device;
    ComPtr<ID3D11DeviceContext> context;
    D3D_FEATURE_LEVEL got{};
    hr = D3D11CreateDevice(adapter.Get(), driver, nullptr, createFlags, levels, _countof(levels),
                           D3D11_SDK_VERSION, device.GetAddressOf(), &got, context.GetAddressOf());
    if (FAILED(hr)) {
        // 有些环境不认 11_1（或 VIDEO_SUPPORT）：退到 11_0 再来一次
        D3D_FEATURE_LEVEL levels2[] = {D3D_FEATURE_LEVEL_11_0, D3D_FEATURE_LEVEL_10_1,
                                       D3D_FEATURE_LEVEL_10_0};
        hr = D3D11CreateDevice(adapter.Get(), driver, nullptr, createFlags, levels2,
                               _countof(levels2), D3D11_SDK_VERSION, device.GetAddressOf(), &got,
                               context.GetAddressOf());
    }
    if (FAILED(hr)) {
        setError("D3D11CreateDevice 失败", hr);
        return nullptr;
    }

    if (flags & MF_GPU_FLAG_MULTITHREAD) {
        ComPtr<ID3D10Multithread> mt;
        if (SUCCEEDED(context.As(&mt))) mt->SetMultithreadProtected(TRUE);
    }

    return device.Detach();   // 调用方负责 mf_gpu_release
}

extern "C" void mf_gpu_release(void* obj) {
    if (obj != nullptr) static_cast<IUnknown*>(obj)->Release();
}

extern "C" uint64_t mf_gpu_adapter_luid(void* device) {
    return luidOfDevice(static_cast<ID3D11Device*>(device));
}

extern "C" int mf_gpu_adapters(char* buf, int buf_bytes) {
    g_lastError.clear();
    ComPtr<IDXGIFactory1> factory;
    HRESULT hr = CreateDXGIFactory1(IID_PPV_ARGS(&factory));
    if (FAILED(hr)) {
        setError("CreateDXGIFactory1 失败", hr);
        return -1;
    }
    std::string text;
    for (UINT i = 0;; ++i) {
        ComPtr<IDXGIAdapter1> a;
        if (factory->EnumAdapters1(i, &a) == DXGI_ERROR_NOT_FOUND) break;
        DXGI_ADAPTER_DESC1 d{};
        a->GetDesc1(&d);
        char line[512];
        snprintf(line, sizeof(line), "%08x:%08x|%s|%llu|%u\n",
                 static_cast<uint32_t>(d.AdapterLuid.HighPart), d.AdapterLuid.LowPart,
                 narrow(d.Description).c_str(),
                 static_cast<unsigned long long>(d.DedicatedVideoMemory / (1024ull * 1024ull)),
                 static_cast<unsigned>(d.Flags));
        text += line;
    }
    if (buf == nullptr || buf_bytes < static_cast<int>(text.size()) + 1) {
        return static_cast<int>(text.size()) + 1;
    }
    memcpy(buf, text.data(), text.size());
    buf[text.size()] = '\0';
    return static_cast<int>(text.size());
}

extern "C" void* mf_gpu_open_shared_texture_by_name(void* device, const wchar_t* name) {
    g_lastError.clear();
    if (device == nullptr || name == nullptr) {
        setErrorText("参数为空");
        return nullptr;
    }
    // OpenSharedResourceByName 在 ID3D11Device2 上（不是 IDXGIResource1，那个只有 CreateSharedHandle）
    ComPtr<ID3D11Device2> dev2;
    HRESULT hr = static_cast<ID3D11Device*>(device)->QueryInterface(IID_PPV_ARGS(&dev2));
    if (FAILED(hr)) {
        setError("设备不支持 ID3D11Device2（需要 Win8+ 与较新驱动）", hr);
        return nullptr;
    }
    ComPtr<ID3D11Texture2D> tex;
    // 请求读写权限：虽然 GL 侧只读采样，但键控互斥的 AcquireSync 需要写权限，
    // 只申请 READ 会导致驱动在加锁时返回 E_ACCESSDENIED（NVIDIA 实测）。
    hr = dev2->OpenSharedResourceByName(name, DXGI_SHARED_RESOURCE_READ | DXGI_SHARED_RESOURCE_WRITE,
                                        IID_PPV_ARGS(&tex));
    if (FAILED(hr)) {
        setError("OpenSharedResourceByName 失败（共享纹理不存在？适配器不同？）", hr);
        return nullptr;
    }
    return tex.Detach();
}

// 生产路径：子进程 CreateSharedHandle 拿到 NT 句柄、DuplicateHandle 投递到本进程，
// 这里用句柄值直接打开（不需要名字，也不留全局命名对象）。
extern "C" void* mf_gpu_open_shared_texture_by_handle(void* device, uint64_t handle) {
    g_lastError.clear();
    if (device == nullptr || handle == 0) {
        setErrorText("参数为空");
        return nullptr;
    }
    ComPtr<ID3D11Device1> dev1;
    HRESULT hr = static_cast<ID3D11Device*>(device)->QueryInterface(IID_PPV_ARGS(&dev1));
    if (FAILED(hr)) {
        setError("设备不支持 ID3D11Device1", hr);
        return nullptr;
    }
    ComPtr<ID3D11Texture2D> tex;
    hr = dev1->OpenSharedResource1(reinterpret_cast<HANDLE>(static_cast<uintptr_t>(handle)),
                                  IID_PPV_ARGS(&tex));
    if (FAILED(hr)) {
        setError("OpenSharedResource1 失败（句柄无效？适配器不同？）", hr);
        return nullptr;
    }
    return tex.Detach();
}

extern "C" void* mf_gpu_create_texture(void* device, uint32_t width, uint32_t height,
                                       uint32_t misc_flags, uint32_t bind_flags) {
    g_lastError.clear();
    if (device == nullptr || width == 0 || height == 0) {
        setErrorText("参数为空");
        return nullptr;
    }
    D3D11_TEXTURE2D_DESC d{};
    d.Width = width;
    d.Height = height;
    d.MipLevels = 1;
    d.ArraySize = 1;
    d.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
    d.SampleDesc.Count = 1;
    d.Usage = D3D11_USAGE_DEFAULT;
    d.BindFlags = bind_flags;
    d.MiscFlags = misc_flags;
    ComPtr<ID3D11Texture2D> tex;
    HRESULT hr = static_cast<ID3D11Device*>(device)->CreateTexture2D(&d, nullptr, tex.GetAddressOf());
    if (FAILED(hr)) {
        setError("CreateTexture2D 失败", hr);
        return nullptr;
    }
    return tex.Detach();
}

extern "C" int mf_gpu_copy_from_shared(void* device, void* dst, void* src, uint32_t acquire_key,
                                       uint32_t release_key, uint32_t timeout_ms) {
    g_lastError.clear();
    if (device == nullptr || dst == nullptr || src == nullptr) {
        setErrorText("参数为空");
        return -1;
    }
    auto* dev = static_cast<ID3D11Device*>(device);
    ComPtr<ID3D11DeviceContext> ctx;
    dev->GetImmediateContext(&ctx);
    if (!ctx) {
        setErrorText("取不到 immediate context");
        return -1;
    }
    ComPtr<IDXGIKeyedMutex> km;
    const bool haveMutex =
            SUCCEEDED(static_cast<ID3D11Texture2D*>(src)->QueryInterface(IID_PPV_ARGS(&km)));
    if (haveMutex) {
        /*
         * 读方拿钥匙：先拿自己的（1），拿不到再试写方那把（0）。
         *
         * ★ 这个"试另一把钥匙"的兜底**不能删**：写方的发帧与时钟推进绑在一起，
         * 一旦它拿不到钥匙就会整个停下来（发布帧数、位置都不走）。
         * 删掉那次换来的是"画面直接冻住"，比撕裂严重得多，已回退。
         */
        HRESULT hr = km->AcquireSync(acquire_key, timeout_ms);
        if (hr == static_cast<HRESULT>(WAIT_TIMEOUT)) {
            hr = km->AcquireSync(release_key, timeout_ms);
            if (hr == static_cast<HRESULT>(WAIT_TIMEOUT)) {
                setErrorText("等待共享纹理超时（生产者还没写新帧）");
                return 1;
            }
        }
        if (FAILED(hr) && hr != static_cast<HRESULT>(WAIT_ABANDONED)) {
            setError("AcquireSync 失败", hr);
            return -1;
        }
    }
    ctx->CopyResource(static_cast<ID3D11Resource*>(dst), static_cast<ID3D11Resource*>(src));
    // 交棒给写方：释放到"写方要 Acquire 的那个 key"
    if (haveMutex) km->ReleaseSync(release_key);
    // 尽早提交：跨 API 的可见性由随后的 wglDXLockObjectsNV 负责
    ctx->Flush();
    return 0;
}

extern "C" int mf_gpu_texture_desc(void* texture, uint32_t* out4) {    if (texture == nullptr || out4 == nullptr) return 0;
    D3D11_TEXTURE2D_DESC d{};
    static_cast<ID3D11Texture2D*>(texture)->GetDesc(&d);
    out4[0] = d.Width;
    out4[1] = d.Height;
    out4[2] = static_cast<uint32_t>(d.Format);
    out4[3] = static_cast<uint32_t>(d.BindFlags);
    return 1;
}

// ---------------------------------------------------------------------------
// NT 共享句柄路线（D3D11 → GL 内存对象）的原语
// ---------------------------------------------------------------------------

extern "C" void* mf_gpu_create_shared_texture(void* device, uint32_t width, uint32_t height,
                                              const wchar_t* name, uint64_t* out_handle) {
    g_lastError.clear();
    if (device == nullptr || width == 0 || height == 0 || out_handle == nullptr) {
        setErrorText("参数为空");
        return nullptr;
    }
    *out_handle = 0;

    D3D11_TEXTURE2D_DESC d{};
    d.Width = width;
    d.Height = height;
    d.MipLevels = 1;
    d.ArraySize = 1;
    // ★ 格式必须是 BGRA8：视频处理器（ID3D11VideoProcessor）的原生输出，也是
    //   NV_DX_interop 与 GL_RGBA8 都能对上的那个（对应关系见 mf_gpu_fill_texture 的说明）。
    d.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
    d.SampleDesc.Count = 1;
    d.Usage = D3D11_USAGE_DEFAULT;
    d.BindFlags = D3D11_BIND_RENDER_TARGET | D3D11_BIND_SHADER_RESOURCE;
    // 跨进程共享只能用 NTHANDLE；KEYEDMUTEX 提供跨设备/跨 API 的顺序原语。
    d.MiscFlags = D3D11_RESOURCE_MISC_SHARED_NTHANDLE | D3D11_RESOURCE_MISC_SHARED_KEYEDMUTEX;

    auto* dev = static_cast<ID3D11Device*>(device);
    ComPtr<ID3D11Texture2D> tex;
    HRESULT hr = dev->CreateTexture2D(&d, nullptr, tex.GetAddressOf());
    if (FAILED(hr)) {
        setError("CreateTexture2D(共享) 失败", hr);
        return nullptr;
    }
    ComPtr<IDXGIResource1> res;
    hr = tex.As(&res);
    if (FAILED(hr)) {
        setError("取 IDXGIResource1 失败", hr);
        return nullptr;
    }
    HANDLE h = nullptr;
    // name == nullptr → 无名 NT 句柄（生产用法，只能靠 DuplicateHandle 投递）
    hr = res->CreateSharedHandle(nullptr, DXGI_SHARED_RESOURCE_READ | DXGI_SHARED_RESOURCE_WRITE,
                                 name, &h);
    if (FAILED(hr)) {
        setError("CreateSharedHandle 失败", hr);
        return nullptr;
    }
    *out_handle = static_cast<uint64_t>(reinterpret_cast<uintptr_t>(h));
    return tex.Detach();
}

extern "C" int mf_gpu_fill_texture(void* device, void* texture, uint32_t stamp_r, uint32_t stamp_g) {
    g_lastError.clear();
    if (device == nullptr || texture == nullptr) {
        setErrorText("参数为空");
        return 0;
    }
    auto* dev = static_cast<ID3D11Device*>(device);
    ComPtr<ID3D11DeviceContext> ctx;
    dev->GetImmediateContext(&ctx);
    if (!ctx) {
        setErrorText("取不到 immediate context");
        return 0;
    }
    D3D11_TEXTURE2D_DESC d{};
    static_cast<ID3D11Texture2D*>(texture)->GetDesc(&d);
    const size_t rowBytes = static_cast<size_t>(d.Width) * 4;
    std::vector<uint8_t> buf(rowBytes * d.Height);
    // 字节序 BGRA：B=0xA5 恒定（通道错位立刻能看出来）、G=stamp_g、R=stamp_r、A=0xFF
    for (uint32_t y = 0; y < d.Height; ++y) {
        for (uint32_t x = 0; x < d.Width; ++x) {
            uint8_t* p = buf.data() + static_cast<size_t>(y) * rowBytes + static_cast<size_t>(x) * 4;
            p[0] = 0xA5;
            p[1] = static_cast<uint8_t>(stamp_g);
            p[2] = static_cast<uint8_t>(stamp_r);
            p[3] = 0xFF;
        }
    }
    // 两个 8×8 角标：左上一块 G=0x11、左下一块 G=0x22 —— 用来判定"到底哪条边在上面"
    // （D3D 第 0 行在内存顶部，GL 把 v=0 当底部，天生会上下翻；这是测量，不是失败）。
    const uint32_t mark = 8;
    for (uint32_t y = 0; y < d.Height && y < mark; ++y) {
        for (uint32_t x = 0; x < d.Width && x < mark; ++x) {
            buf[static_cast<size_t>(y) * rowBytes + static_cast<size_t>(x) * 4 + 1] = 0x11;
        }
    }
    for (uint32_t y = (d.Height > mark ? d.Height - mark : 0); y < d.Height; ++y) {
        for (uint32_t x = 0; x < d.Width && x < mark; ++x) {
            buf[static_cast<size_t>(y) * rowBytes + static_cast<size_t>(x) * 4 + 1] = 0x22;
        }
    }
    ctx->UpdateSubresource(static_cast<ID3D11Resource*>(texture), 0, nullptr, buf.data(),
                           static_cast<UINT>(rowBytes), static_cast<UINT>(buf.size()));
    ctx->Flush();
    return 1;
}

extern "C" int mf_gpu_acquire_sync(void* texture, uint32_t key, uint32_t timeout_ms) {
    g_lastError.clear();
    if (texture == nullptr) {
        setErrorText("参数为空");
        return -1;
    }
    ComPtr<IDXGIKeyedMutex> km;
    HRESULT hr = static_cast<ID3D11Texture2D*>(texture)->QueryInterface(IID_PPV_ARGS(&km));
    if (FAILED(hr)) {
        setError("纹理没有 IDXGIKeyedMutex（创建时少了 MISC_SHARED_KEYEDMUTEX？）", hr);
        return -1;
    }
    hr = km->AcquireSync(key, timeout_ms);
    if (hr == static_cast<HRESULT>(WAIT_TIMEOUT)) return 1;
    // WAIT_ABANDONED = 上一个持有者崩了，锁归我们 —— 必须当"已获得"
    if (FAILED(hr) && hr != static_cast<HRESULT>(WAIT_ABANDONED)) {
        setError("AcquireSync 失败", hr);
        return -1;
    }
    return 0;
}

extern "C" int mf_gpu_release_sync(void* texture, uint32_t key) {
    g_lastError.clear();
    if (texture == nullptr) {
        setErrorText("参数为空");
        return -1;
    }
    ComPtr<IDXGIKeyedMutex> km;
    HRESULT hr = static_cast<ID3D11Texture2D*>(texture)->QueryInterface(IID_PPV_ARGS(&km));
    if (FAILED(hr)) {
        setError("纹理没有 IDXGIKeyedMutex", hr);
        return -1;
    }
    hr = km->ReleaseSync(key);
    if (FAILED(hr)) {
        setError("ReleaseSync 失败", hr);
        return -1;
    }
    return 0;
}

// ---- 共享 fence（GL_EXT_semaphore_win32 的 D3D 侧载体） ----

namespace {

/** 超时后仍挂着 SetEventOnCompletion 的事件：不能立刻 Close（还有挂起引用），
 *  下次调用时顺手回收已经信号化的那些。 */
std::mutex g_pendingMutex;
std::vector<HANDLE> g_pendingEvents;

void reapPendingEvents() {
    std::lock_guard<std::mutex> lock(g_pendingMutex);
    for (auto it = g_pendingEvents.begin(); it != g_pendingEvents.end();) {
        if (WaitForSingleObject(*it, 0) == WAIT_OBJECT_0) {
            CloseHandle(*it);
            it = g_pendingEvents.erase(it);
        } else {
            ++it;
        }
    }
}

bool context4Of(void* device, ComPtr<ID3D11DeviceContext4>& out) {
    auto* dev = static_cast<ID3D11Device*>(device);
    ComPtr<ID3D11DeviceContext> ctx;
    dev->GetImmediateContext(&ctx);
    if (!ctx) {
        setErrorText("取不到 immediate context");
        return false;
    }
    HRESULT hr = ctx.As(&out);
    if (FAILED(hr)) {
        setError("immediate context 不支持 ID3D11DeviceContext4（需要 Win10 1703+ 与较新驱动）", hr);
        return false;
    }
    return true;
}

} // namespace

extern "C" void* mf_gpu_create_shared_fence(void* device, uint64_t initial_value,
                                            const wchar_t* name, uint64_t* out_handle) {
    g_lastError.clear();
    if (device == nullptr || out_handle == nullptr) {
        setErrorText("参数为空");
        return nullptr;
    }
    *out_handle = 0;
    ComPtr<ID3D11Device5> dev5;
    HRESULT hr = static_cast<ID3D11Device*>(device)->QueryInterface(IID_PPV_ARGS(&dev5));
    if (FAILED(hr)) {
        setError("设备不支持 ID3D11Device5（共享 fence 需要 Win10 1703+ 与较新驱动）", hr);
        return nullptr;
    }
    ComPtr<ID3D11Fence> fence;
    // SHARED：句柄可以被别的进程/别的 API（含 GL 的 D3D12 fence 导入）打开
    hr = dev5->CreateFence(initial_value, D3D11_FENCE_FLAG_SHARED, IID_PPV_ARGS(&fence));
    if (FAILED(hr)) {
        setError("CreateFence(D3D11_FENCE_FLAG_SHARED) 失败", hr);
        return nullptr;
    }
    HANDLE h = nullptr;
    hr = fence->CreateSharedHandle(nullptr, GENERIC_ALL, name, &h);
    if (FAILED(hr)) {
        setError("ID3D11Fence::CreateSharedHandle 失败", hr);
        return nullptr;
    }
    *out_handle = static_cast<uint64_t>(reinterpret_cast<uintptr_t>(h));
    return fence.Detach();
}

extern "C" void* mf_gpu_open_shared_fence(void* device, uint64_t handle) {
    g_lastError.clear();
    if (device == nullptr || handle == 0) {
        setErrorText("参数为空");
        return nullptr;
    }
    ComPtr<ID3D11Device5> dev5;
    HRESULT hr = static_cast<ID3D11Device*>(device)->QueryInterface(IID_PPV_ARGS(&dev5));
    if (FAILED(hr)) {
        setError("设备不支持 ID3D11Device5", hr);
        return nullptr;
    }
    ComPtr<ID3D11Fence> fence;
    hr = dev5->OpenSharedFence(reinterpret_cast<HANDLE>(static_cast<uintptr_t>(handle)),
                               IID_PPV_ARGS(&fence));
    if (FAILED(hr)) {
        setError("OpenSharedFence 失败（句柄无效？适配器不同？）", hr);
        return nullptr;
    }
    return fence.Detach();
}

extern "C" int mf_gpu_fence_signal(void* device, void* fence, uint64_t value) {
    g_lastError.clear();
    if (device == nullptr || fence == nullptr) {
        setErrorText("参数为空");
        return -1;
    }
    ComPtr<ID3D11DeviceContext4> ctx4;
    if (!context4Of(device, ctx4)) return -1;
    HRESULT hr = ctx4->Signal(static_cast<ID3D11Fence*>(fence), value);
    if (FAILED(hr)) {
        setError("ID3D11DeviceContext4::Signal 失败", hr);
        return -1;
    }
    return 0;
}

extern "C" int mf_gpu_fence_wait(void* device, void* fence, uint64_t value) {
    g_lastError.clear();
    if (device == nullptr || fence == nullptr) {
        setErrorText("参数为空");
        return -1;
    }
    ComPtr<ID3D11DeviceContext4> ctx4;
    if (!context4Of(device, ctx4)) return -1;
    HRESULT hr = ctx4->Wait(static_cast<ID3D11Fence*>(fence), value);
    if (FAILED(hr)) {
        setError("ID3D11DeviceContext4::Wait 失败", hr);
        return -1;
    }
    return 0;
}

extern "C" uint64_t mf_gpu_fence_completed(void* fence) {
    if (fence == nullptr) return 0;
    return static_cast<ID3D11Fence*>(fence)->GetCompletedValue();
}

extern "C" int mf_gpu_fence_wait_cpu(void* fence, uint64_t value, uint32_t timeout_ms) {
    g_lastError.clear();
    if (fence == nullptr) {
        setErrorText("参数为空");
        return -1;
    }
    reapPendingEvents();
    auto* f = static_cast<ID3D11Fence*>(fence);
    if (f->GetCompletedValue() >= value) return 0;
    HANDLE ev = CreateEventW(nullptr, TRUE, FALSE, nullptr);
    if (ev == nullptr) {
        setError("CreateEvent 失败", HRESULT_FROM_WIN32(GetLastError()));
        return -1;
    }
    HRESULT hr = f->SetEventOnCompletion(value, ev);
    if (FAILED(hr)) {
        CloseHandle(ev);
        setError("SetEventOnCompletion 失败", hr);
        return -1;
    }
    const DWORD rc = WaitForSingleObject(ev, timeout_ms);
    if (rc == WAIT_OBJECT_0) {
        CloseHandle(ev);
        return 0;
    }
    if (rc == WAIT_TIMEOUT) {
        // 挂起的事件回调还在引用它 —— 交给回收列表，不要在这里 CloseHandle
        std::lock_guard<std::mutex> lock(g_pendingMutex);
        g_pendingEvents.push_back(ev);
        return 1;
    }
    CloseHandle(ev);
    setErrorText("WaitForSingleObject 失败");
    return -1;
}

extern "C" const char* mf_gpu_last_error(void) {
    return g_lastError.c_str();
}

extern "C" int mf_gpu_close_handle(uint64_t handle) {
    if (handle == 0) return 0;
    return CloseHandle(reinterpret_cast<HANDLE>(static_cast<uintptr_t>(handle))) ? 0 : -1;
}

extern "C" void* mf_gpu_open_shared_section(const wchar_t* name, uint32_t bytes) {
    g_lastError.clear();
    if (name == nullptr) {
        setErrorText("共享内存段名为空");
        return nullptr;
    }
    HANDLE mapping = OpenFileMappingW(FILE_MAP_ALL_ACCESS, FALSE, name);
    if (mapping == nullptr) {
        setError("OpenFileMapping 失败（段还没被创建？）", HRESULT_FROM_WIN32(GetLastError()));
        return nullptr;
    }
    void* view = MapViewOfFile(mapping, FILE_MAP_ALL_ACCESS, 0, 0, bytes);
    CloseHandle(mapping);
    if (view == nullptr) {
        setError("MapViewOfFile 失败", HRESULT_FROM_WIN32(GetLastError()));
        return nullptr;
    }
    return view;
}

extern "C" void mf_gpu_close_shared_section(void* mapped) {
    if (mapped != nullptr) UnmapViewOfFile(mapped);
}

// ---------------------------------------------------------------------------
// 控制通道客户端（与子进程 ipc.cpp 同一套帧格式）
// ---------------------------------------------------------------------------

namespace {

bool pipeReadExact(HANDLE pipe, void* dst, DWORD bytes) {
    auto* p = static_cast<uint8_t*>(dst);
    DWORD done = 0;
    while (done < bytes) {
        DWORD got = 0;
        if (!ReadFile(pipe, p + done, bytes - done, &got, nullptr) || got == 0) return false;
        done += got;
    }
    return true;
}

bool pipeWriteExact(HANDLE pipe, const void* src, DWORD bytes) {
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

extern "C" void* mf_gpu_pipe_connect(const wchar_t* name, uint32_t timeout_ms) {
    g_lastError.clear();
    if (name == nullptr) {
        setErrorText("管道名为空");
        return nullptr;
    }
    const std::wstring full = std::wstring(L"\\\\.\\pipe\\") + name;
    const ULONGLONG deadline = GetTickCount64() + timeout_ms;
    for (;;) {
        // 子进程启动后才会 CreateNamedPipe，所以这里要轮询重试
        HANDLE h = CreateFileW(full.c_str(), GENERIC_READ | GENERIC_WRITE, 0, nullptr,
                               OPEN_EXISTING, 0, nullptr);
        if (h != INVALID_HANDLE_VALUE) return h;
        if (GetTickCount64() >= deadline) {
            setError("连接控制管道超时（子进程还没起来？）", HRESULT_FROM_WIN32(GetLastError()));
            return nullptr;
        }
        Sleep(100);
    }
}

extern "C" int mf_gpu_pipe_request(void* pipe, const char* payload, char* out, int out_bytes) {
    if (pipe == nullptr || payload == nullptr || out == nullptr || out_bytes <= 0) return -1;
    HANDLE h = static_cast<HANDLE>(pipe);
    const char* p = payload;
    const uint32_t payloadLen = static_cast<uint32_t>(strlen(payload));
    const uint32_t len = 6 + payloadLen;
    std::vector<uint8_t> frame(4 + len);
    memcpy(frame.data(), &len, 4);
    const uint16_t type = 1;   // 请求
    memcpy(frame.data() + 4, &type, 2);
    const uint32_t reqId = 0;
    memcpy(frame.data() + 6, &reqId, 4);
    memcpy(frame.data() + 10, p, payloadLen);
    if (!pipeWriteExact(h, frame.data(), static_cast<DWORD>(frame.size()))) {
        setErrorText("写管道失败");
        return -1;
    }

    uint32_t replyLen = 0;
    if (!pipeReadExact(h, &replyLen, 4) || replyLen < 6 || replyLen > 65536) {
        setErrorText("读管道失败（子进程退出了？）");
        return -1;
    }
    std::vector<uint8_t> reply(replyLen);
    if (!pipeReadExact(h, reply.data(), replyLen)) {
        setErrorText("读管道回执失败");
        return -1;
    }
    const int textLen = static_cast<int>(replyLen) - 6;
    const int copy = textLen < out_bytes - 1 ? textLen : out_bytes - 1;
    memcpy(out, reply.data() + 6, copy);
    out[copy] = '\0';
    return copy;
}

extern "C" void mf_gpu_pipe_close(void* pipe) {
    if (pipe != nullptr) CloseHandle(static_cast<HANDLE>(pipe));
}

extern "C" void* mf_gpu_job_create(void) {
    g_lastError.clear();
    HANDLE job = CreateJobObjectW(nullptr, nullptr);
    if (job == nullptr) {
        setError("CreateJobObject 失败", HRESULT_FROM_WIN32(GetLastError()));
        return nullptr;
    }
    JOBOBJECT_EXTENDED_LIMIT_INFORMATION info{};
    info.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
    if (!SetInformationJobObject(job, JobObjectExtendedLimitInformation, &info, sizeof(info))) {
        setError("SetInformationJobObject 失败", HRESULT_FROM_WIN32(GetLastError()));
        CloseHandle(job);
        return nullptr;
    }
    return job;
}

extern "C" int mf_gpu_job_assign(void* job, uint32_t pid) {
    g_lastError.clear();
    if (job == nullptr || pid == 0) {
        setErrorText("Job 或 pid 为空");
        return 0;
    }
    HANDLE process = OpenProcess(PROCESS_SET_QUOTA | PROCESS_TERMINATE, FALSE, pid);
    if (process == nullptr) {
        setError("OpenProcess 子进程失败", HRESULT_FROM_WIN32(GetLastError()));
        return 0;
    }
    const BOOL ok = AssignProcessToJobObject(static_cast<HANDLE>(job), process);
    CloseHandle(process);
    if (!ok) {
        // 游戏自己被某个带 UI 限制的 Job 包着时可能失败 —— 不是致命的：
        // 子进程侧的"父进程看护"仍然会兜住绝大多数情况
        setError("AssignProcessToJobObject 失败（游戏进程可能已在受限 Job 里）",
                 HRESULT_FROM_WIN32(GetLastError()));
        return 0;
    }
    return 1;
}

extern "C" void mf_gpu_job_close(void* job) {
    if (job != nullptr) CloseHandle(static_cast<HANDLE>(job));
}

// ---------------------------------------------------------------------------
// JNI 包装：dev.mediafix.gpu.NativeD3D
// ---------------------------------------------------------------------------

namespace {

std::string jstringToUtf8(JNIEnv* env, jstring s) {
    if (s == nullptr) return {};
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string out = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(s, chars);
    return out;
}

std::wstring utf8ToWide(const std::string& s) {
    if (s.empty()) return {};
    int n = MultiByteToWideChar(CP_UTF8, 0, s.c_str(), -1, nullptr, 0);
    if (n <= 1) return {};
    std::wstring w(static_cast<size_t>(n), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, s.c_str(), -1, w.data(), n);
    w.pop_back();
    return w;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_dev_mediafix_gpu_NativeD3D_createDevice(JNIEnv*, jclass, jlong adapterLuid, jint flags) {
    return reinterpret_cast<jlong>(
            mf_gpu_create_device(static_cast<uint64_t>(adapterLuid), static_cast<uint32_t>(flags)));
}

extern "C" JNIEXPORT void JNICALL
Java_dev_mediafix_gpu_NativeD3D_release(JNIEnv*, jclass, jlong object) {
    mf_gpu_release(reinterpret_cast<void*>(object));
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_mediafix_gpu_NativeD3D_adapterLuidOf(JNIEnv*, jclass, jlong device) {
    return static_cast<jlong>(mf_gpu_adapter_luid(reinterpret_cast<void*>(device)));
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_dev_mediafix_gpu_NativeD3D_adapters(JNIEnv* env, jclass) {
    char buf[8192];
    const int n = mf_gpu_adapters(buf, sizeof(buf));
    if (n < 0) return nullptr;

    // 按行切分
    std::string text(buf, static_cast<size_t>(n));
    std::vector<std::string> lines;
    size_t start = 0;
    while (start < text.size()) {
        const size_t nl = text.find('\n', start);
        if (nl == std::string::npos) break;
        lines.push_back(text.substr(start, nl - start));
        start = nl + 1;
    }
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(lines.size()), stringClass, nullptr);
    if (arr == nullptr) return nullptr;
    for (size_t i = 0; i < lines.size(); ++i) {
        jstring s = env->NewStringUTF(lines[i].c_str());
        env->SetObjectArrayElement(arr, static_cast<jsize>(i), s);
        env->DeleteLocalRef(s);
    }
    return arr;
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_mediafix_gpu_NativeD3D_openSharedTextureByName(JNIEnv* env, jclass, jlong device,
                                                        jstring name) {
    const std::string utf8 = jstringToUtf8(env, name);
    const std::wstring wide = utf8ToWide(utf8);
    return reinterpret_cast<jlong>(
            mf_gpu_open_shared_texture_by_name(reinterpret_cast<void*>(device), wide.c_str()));
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_mediafix_gpu_NativeD3D_openSharedTextureByHandle(JNIEnv*, jclass, jlong device,
                                                          jlong handle) {
    return reinterpret_cast<jlong>(mf_gpu_open_shared_texture_by_handle(
            reinterpret_cast<void*>(device), static_cast<uint64_t>(handle)));
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_mediafix_gpu_NativeD3D_createTexture(JNIEnv*, jclass, jlong device, jint width, jint height,
                                              jint miscFlags, jint bindFlags) {
    return reinterpret_cast<jlong>(mf_gpu_create_texture(
            reinterpret_cast<void*>(device), static_cast<uint32_t>(width),
            static_cast<uint32_t>(height), static_cast<uint32_t>(miscFlags),
            static_cast<uint32_t>(bindFlags)));
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_mediafix_gpu_NativeD3D_copyFromShared(JNIEnv*, jclass, jlong device, jlong dst, jlong src,
                                               jint acquireKey, jint releaseKey, jint timeoutMs) {
    return static_cast<jint>(mf_gpu_copy_from_shared(
            reinterpret_cast<void*>(device), reinterpret_cast<void*>(dst),
            reinterpret_cast<void*>(src), static_cast<uint32_t>(acquireKey),
            static_cast<uint32_t>(releaseKey), static_cast<uint32_t>(timeoutMs)));
}

extern "C" JNIEXPORT jintArray JNICALL
Java_dev_mediafix_gpu_NativeD3D_textureDesc(JNIEnv* env, jclass, jlong texture) {    uint32_t out[4] = {0, 0, 0, 0};
    if (!mf_gpu_texture_desc(reinterpret_cast<void*>(texture), out)) return nullptr;
    jintArray arr = env->NewIntArray(4);
    if (arr == nullptr) return nullptr;
    jint vals[4] = {static_cast<jint>(out[0]), static_cast<jint>(out[1]), static_cast<jint>(out[2]),
                    static_cast<jint>(out[3])};
    env->SetIntArrayRegion(arr, 0, 4, vals);
    return arr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_mediafix_gpu_NativeD3D_lastError(JNIEnv* env, jclass) {
    return env->NewStringUTF(mf_gpu_last_error());
}

// ---- NT 共享句柄路线（返回 {指针, 句柄} 两个 long，省掉 out 参数） ----

extern "C" JNIEXPORT jlongArray JNICALL
Java_dev_mediafix_gpu_NativeD3D_createSharedTexture(JNIEnv* env, jclass, jlong device, jint width,
                                                    jint height, jstring name) {
    const std::string utf8 = jstringToUtf8(env, name);
    const std::wstring wide = utf8ToWide(utf8);
    uint64_t handle = 0;
    void* tex = mf_gpu_create_shared_texture(reinterpret_cast<void*>(device),
                                             static_cast<uint32_t>(width),
                                             static_cast<uint32_t>(height),
                                             utf8.empty() ? nullptr : wide.c_str(), &handle);
    if (tex == nullptr) return nullptr;
    jlongArray arr = env->NewLongArray(2);
    if (arr == nullptr) {
        mf_gpu_release(tex);
        return nullptr;
    }
    const jlong vals[2] = {reinterpret_cast<jlong>(tex), static_cast<jlong>(handle)};
    env->SetLongArrayRegion(arr, 0, 2, vals);
    return arr;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_mediafix_gpu_NativeD3D_fillTexture(JNIEnv*, jclass, jlong device, jlong texture, jint stampR,
                                            jint stampG) {
    return mf_gpu_fill_texture(reinterpret_cast<void*>(device), reinterpret_cast<void*>(texture),
                               static_cast<uint32_t>(stampR), static_cast<uint32_t>(stampG))
                   ? JNI_TRUE
                   : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_mediafix_gpu_NativeD3D_acquireSync(JNIEnv*, jclass, jlong texture, jint key,
                                            jint timeoutMs) {
    return static_cast<jint>(mf_gpu_acquire_sync(reinterpret_cast<void*>(texture),
                                                 static_cast<uint32_t>(key),
                                                 static_cast<uint32_t>(timeoutMs)));
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_mediafix_gpu_NativeD3D_releaseSync(JNIEnv*, jclass, jlong texture, jint key) {
    return static_cast<jint>(mf_gpu_release_sync(reinterpret_cast<void*>(texture),
                                                 static_cast<uint32_t>(key)));
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_dev_mediafix_gpu_NativeD3D_createSharedFence(JNIEnv* env, jclass, jlong device,
                                                  jlong initialValue, jstring name) {
    const std::string utf8 = jstringToUtf8(env, name);
    const std::wstring wide = utf8ToWide(utf8);
    uint64_t handle = 0;
    void* fence = mf_gpu_create_shared_fence(reinterpret_cast<void*>(device),
                                             static_cast<uint64_t>(initialValue),
                                             utf8.empty() ? nullptr : wide.c_str(), &handle);
    if (fence == nullptr) return nullptr;
    jlongArray arr = env->NewLongArray(2);
    if (arr == nullptr) {
        mf_gpu_release(fence);
        return nullptr;
    }
    const jlong vals[2] = {reinterpret_cast<jlong>(fence), static_cast<jlong>(handle)};
    env->SetLongArrayRegion(arr, 0, 2, vals);
    return arr;
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_mediafix_gpu_NativeD3D_openSharedFence(JNIEnv*, jclass, jlong device, jlong handle) {
    return reinterpret_cast<jlong>(mf_gpu_open_shared_fence(
            reinterpret_cast<void*>(device), static_cast<uint64_t>(handle)));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_mediafix_gpu_NativeD3D_fenceSignal(JNIEnv*, jclass, jlong device, jlong fence,
                                            jlong value) {
    return mf_gpu_fence_signal(reinterpret_cast<void*>(device), reinterpret_cast<void*>(fence),
                               static_cast<uint64_t>(value)) == 0
                   ? JNI_TRUE
                   : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_mediafix_gpu_NativeD3D_fenceWait(JNIEnv*, jclass, jlong device, jlong fence, jlong value) {
    return mf_gpu_fence_wait(reinterpret_cast<void*>(device), reinterpret_cast<void*>(fence),
                             static_cast<uint64_t>(value)) == 0
                   ? JNI_TRUE
                   : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_mediafix_gpu_NativeD3D_fenceCompleted(JNIEnv*, jclass, jlong fence) {
    return static_cast<jlong>(mf_gpu_fence_completed(reinterpret_cast<void*>(fence)));
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_mediafix_gpu_NativeD3D_fenceWaitCpu(JNIEnv*, jclass, jlong fence, jlong value,
                                             jint timeoutMs) {
    return static_cast<jint>(mf_gpu_fence_wait_cpu(reinterpret_cast<void*>(fence),
                                                   static_cast<uint64_t>(value),
                                                   static_cast<uint32_t>(timeoutMs)));
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_mediafix_gpu_NativeD3D_closeRawHandle(JNIEnv*, jclass, jlong handle) {
    return static_cast<jint>(mf_gpu_close_handle(static_cast<uint64_t>(handle)));
}

extern "C" JNIEXPORT jobject JNICALL
Java_dev_mediafix_gpu_NativeD3D_openSharedSection(JNIEnv* env, jclass, jstring name, jint bytes) {
    const std::string utf8 = jstringToUtf8(env, name);
    const std::wstring wide = utf8ToWide(utf8);
    void* view = mf_gpu_open_shared_section(wide.c_str(), static_cast<uint32_t>(bytes));
    if (view == nullptr) return nullptr;
    // 直接包成 ByteBuffer：Java 侧读状态不需要任何拷贝或系统调用
    return env->NewDirectByteBuffer(view, bytes);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_mediafix_gpu_NativeD3D_closeSharedSection(JNIEnv* env, jclass, jobject buffer) {
    if (buffer == nullptr) return;
    void* view = env->GetDirectBufferAddress(buffer);
    if (view != nullptr) mf_gpu_close_shared_section(view);
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_mediafix_gpu_NativeD3D_pipeConnect(JNIEnv* env, jclass, jstring name, jint timeoutMs) {
    const std::string utf8 = jstringToUtf8(env, name);
    const std::wstring wide = utf8ToWide(utf8);
    return reinterpret_cast<jlong>(
            mf_gpu_pipe_connect(wide.c_str(), static_cast<uint32_t>(timeoutMs)));
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_mediafix_gpu_NativeD3D_pipeRequest(JNIEnv* env, jclass, jlong pipe, jstring payload) {
    const std::string utf8 = jstringToUtf8(env, payload);
    char out[4096];
    const int n = mf_gpu_pipe_request(reinterpret_cast<void*>(pipe), utf8.c_str(), out, sizeof(out));
    if (n < 0) return nullptr;
    return env->NewStringUTF(out);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_mediafix_gpu_NativeD3D_pipeClose(JNIEnv*, jclass, jlong pipe) {
    mf_gpu_pipe_close(reinterpret_cast<void*>(pipe));
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_mediafix_gpu_NativeD3D_jobCreate(JNIEnv*, jclass) {
    return reinterpret_cast<jlong>(mf_gpu_job_create());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_mediafix_gpu_NativeD3D_jobAssign(JNIEnv*, jclass, jlong job, jint pid) {
    return mf_gpu_job_assign(reinterpret_cast<void*>(job), static_cast<uint32_t>(pid)) ? JNI_TRUE
                                                                                       : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_mediafix_gpu_NativeD3D_jobClose(JNIEnv*, jclass, jlong job) {
    mf_gpu_job_close(reinterpret_cast<void*>(job));
}

// ============================================================================
// cineduct-spike-host —— P1 打样的「生产者」
//
// 它模拟将来真正的子进程（cineduct-host.exe）在帧传输这一层做的事：
//   1. 在指定适配器上建 D3D11 设备（与游戏进程 GL 上下文同适配器）
//   2. 建 N 张 DXGI 共享纹理：B8G8R8A8_UNORM + SHARED_NTHANDLE + SHARED_KEYEDMUTEX
//      —— 这就是「共享显存」的载体：同一块显存被两个进程映射
//   3. 每帧把动画画面渲染到中间纹理，再在键控互斥保护下 CopyResource 进共享纹理
//      （计划文档 3.3：锁内只做一次拷贝，把加锁时间压到最短）
//   4. 把帧号/丢帧/加锁耗时/适配器 LUID 写进共享内存状态块
//
// 打样要回答的问题只有一个：**游戏进程（OpenGL）能不能零拷贝采到这块显存**。
// 因此这里刻意不含任何 FFmpeg / B 站逻辑，只产出「可被验证的动画帧」：
// 每帧把帧号编码进 R/G 通道、B 通道固定为 0xA5，消费者回读像素即可自动判定
// 「帧在更新 + 通道没错位 + 内容不是垃圾」。
//
// 用法：cineduct-spike-host.exe [--width 1280] [--height 720] [--slots 3] [--fps 60]
//        [--duration 60] [--name cineduct_spike_tex] [--status cineduct_spike_status]
//        [--adapter-luid HI:LO] [--list]
// ============================================================================

#include <windows.h>
#include <d3d11.h>
#include <d3d11_4.h>        // ID3D11Device5 / ID3D11Fence / ID3D11DeviceContext4（共享 fence 那条路）
#include <dxgi1_2.h>
#include <d3dcompiler.h>
#include <wrl/client.h>

#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <thread>
#include <vector>

#include "cineduct_spike_status.h"

using Microsoft::WRL::ComPtr;
using namespace cineduct::spike;

namespace {

constexpr uint32_t kAcquireTimeoutMs = 4;

std::atomic<bool> g_stop{false};

BOOL WINAPI consoleHandler(DWORD type) {
    if (type == CTRL_C_EVENT || type == CTRL_CLOSE_EVENT || type == CTRL_BREAK_EVENT) {
        g_stop.store(true);
        return TRUE;
    }
    return FALSE;
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

uint64_t luidOf(const LUID& l) {
    return (static_cast<uint64_t>(static_cast<uint32_t>(l.HighPart)) << 32) | l.LowPart;
}

struct Args {
    uint32_t width = 1280;
    uint32_t height = 720;
    uint32_t slots = 3;
    uint32_t fps = 60;
    uint32_t durationSec = 0;   // 0 = 一直跑到 Ctrl+C
    bool listAdapters = false;
    bool moMode = false;        // --mo：额外建共享 fence，并接受消费者的 NT 句柄直投请求
    bool cpuPattern = false;    // --cpu-pattern：每帧再用 CPU（UpdateSubresource）戳一个角标
    uint64_t adapterLuid = 0;   // 0 = 自动挑
    std::wstring texName = L"cineduct_spike_tex";
    std::wstring statusName = L"cineduct_spike_status";
};

bool parseArgs(int argc, char** argv, Args& a) {
    for (int i = 1; i < argc; ++i) {
        std::string k = argv[i];
        auto next = [&](std::string& out) {
            if (i + 1 < argc) { out = argv[++i]; return true; }
            return false;
        };
        if (k == "--list") { a.listAdapters = true; continue; }
        if (k == "--mo") { a.moMode = true; continue; }
        if (k == "--cpu-pattern") { a.cpuPattern = true; continue; }
        std::string v;
        if (k == "--width") { if (!next(v)) return false; a.width = static_cast<uint32_t>(strtoul(v.c_str(), nullptr, 10)); }
        else if (k == "--height") { if (!next(v)) return false; a.height = static_cast<uint32_t>(strtoul(v.c_str(), nullptr, 10)); }
        else if (k == "--slots") { if (!next(v)) return false; a.slots = static_cast<uint32_t>(strtoul(v.c_str(), nullptr, 10)); }
        else if (k == "--fps") { if (!next(v)) return false; a.fps = static_cast<uint32_t>(strtoul(v.c_str(), nullptr, 10)); }
        else if (k == "--duration") { if (!next(v)) return false; a.durationSec = static_cast<uint32_t>(strtoul(v.c_str(), nullptr, 10)); }
        else if (k == "--name") { if (!next(v)) return false; a.texName.assign(v.begin(), v.end()); }
        else if (k == "--status") { if (!next(v)) return false; a.statusName.assign(v.begin(), v.end()); }
        else if (k == "--adapter-luid") {
            if (!next(v)) return false;
            uint32_t hi = 0, lo = 0;
            if (sscanf_s(v.c_str(), "%x:%x", &hi, &lo) != 2) return false;
            a.adapterLuid = (static_cast<uint64_t>(hi) << 32) | lo;
        } else {
            fprintf(stderr, "[host] 未知参数: %s\n", k.c_str());
            return false;
        }
    }
    return a.width > 0 && a.height > 0 && a.slots >= 1 && a.slots <= kMaxSlots && a.fps > 0;
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

void printAdapters(const std::vector<AdapterEntry>& list) {
    printf("[host] 适配器列表:\n");
    for (size_t i = 0; i < list.size(); ++i) {
        const auto& d = list[i].desc;
        printf("  [%zu] %s | luid=%08x:%08x | vram=%lluMB | flags=%s%s%s\n",
               i, narrow(d.Description).c_str(),
               static_cast<uint32_t>(d.AdapterLuid.HighPart), d.AdapterLuid.LowPart,
               static_cast<unsigned long long>(d.DedicatedVideoMemory / (1024ull * 1024ull)),
               (d.Flags & DXGI_ADAPTER_FLAG_SOFTWARE) ? "SOFTWARE " : "",
               (d.Flags & DXGI_ADAPTER_FLAG_REMOTE) ? "REMOTE " : "",
               (d.Flags == 0) ? "-" : "");
    }
}

// P1 打样用 B8G8R8A8 + 键控互斥；下面这段 HLSL 只是"会动的画面"，
// 真正解码后这里会换成 VideoProcessorBlt 的输出。
const char* kShaderSource = R"HLSL(
cbuffer Params : register(b0) {
    float4 stamp;    // 本帧色标（帧号编码），四角区域恒为此色
    float4 timing;   // x = 秒
};

struct VSOut {
    float4 pos : SV_Position;
    float2 uv  : TEXCOORD0;
};

VSOut VSMain(uint vid : SV_VertexID) {
    VSOut o;
    float2 p = float2((vid << 1) & 2, vid & 2);      // (0,0) (2,0) (0,2)
    o.uv = p;
    o.pos = float4(p * float2(2.0, -2.0) + float2(-1.0, 1.0), 0.0, 1.0);
    return o;
}

float4 PSMain(VSOut i) : SV_Target {
    float3 c = stamp.rgb;
    // 中间区域画一个来回移动的竖条 + 两条绿边，用来肉眼判断卡顿/撕裂；
    // 四个角永远保持纯 stamp 色，消费者就在角上回读像素做自动校验。
    if (i.uv.x > 0.15 && i.uv.x < 0.85 && i.uv.y > 0.30 && i.uv.y < 0.70) {
        float bar = frac(timing.x * 0.35);
        if (abs(i.uv.x - lerp(0.20, 0.80, bar)) < 0.03) c = float3(1.0, 1.0, 1.0);
        else if (i.uv.y < 0.33 || i.uv.y > 0.67) c = float3(0.0, 1.0, 0.0);
    }
    return float4(c, 1.0);
}
)HLSL";

bool compileStage(const char* entry, const char* target, ComPtr<ID3DBlob>& out) {
    ComPtr<ID3DBlob> err;
    HRESULT hr = D3DCompile(kShaderSource, strlen(kShaderSource), "spike.hlsl", nullptr, nullptr,
                            entry, target, D3DCOMPILE_OPTIMIZATION_LEVEL3, 0,
                            out.GetAddressOf(), err.GetAddressOf());
    if (FAILED(hr)) {
        fprintf(stderr, "[host] 着色器 %s 编译失败 hr=0x%08lx %s\n", entry,
                static_cast<unsigned long>(hr),
                err ? static_cast<const char*>(err->GetBufferPointer()) : "");
        return false;
    }
    return true;
}

struct Params {
    float stamp[4];
    float timing[4];
};

} // namespace

int main(int argc, char** argv) {
    SetConsoleOutputCP(CP_UTF8);
    setvbuf(stdout, nullptr, _IONBF, 0);
    SetConsoleCtrlHandler(consoleHandler, TRUE);

    Args args;
    if (!parseArgs(argc, argv, args)) {
        fprintf(stderr, "[host] 参数错误\n");
        return 2;
    }

    auto adapters = enumAdapters();
    if (args.listAdapters) {
        printAdapters(adapters);
        return 0;
    }
    if (adapters.empty()) {
        fprintf(stderr, "[host] 没有找到任何 DXGI 适配器\n");
        return 3;
    }

    // ---- 选适配器：命令行指定 > 显存最大的硬件适配器 ----
    AdapterEntry* chosen = nullptr;
    if (args.adapterLuid != 0) {
        for (auto& a : adapters) {
            if (luidOf(a.desc.AdapterLuid) == args.adapterLuid) { chosen = &a; break; }
        }
        if (chosen == nullptr) {
            fprintf(stderr, "[host] 指定的适配器 LUID 不存在\n");
            return 3;
        }
    } else {
        for (auto& a : adapters) {
            if (a.desc.Flags & (DXGI_ADAPTER_FLAG_SOFTWARE | DXGI_ADAPTER_FLAG_REMOTE)) continue;
            if (chosen == nullptr || a.desc.DedicatedVideoMemory > chosen->desc.DedicatedVideoMemory) chosen = &a;
        }
        if (chosen == nullptr) chosen = &adapters.front();
    }
    printf("[host] 适配器: %s | luid=%08x:%08x | vram=%lluMB\n",
           narrow(chosen->desc.Description).c_str(),
           static_cast<uint32_t>(chosen->desc.AdapterLuid.HighPart), chosen->desc.AdapterLuid.LowPart,
           static_cast<unsigned long long>(chosen->desc.DedicatedVideoMemory / (1024ull * 1024ull)));

    // ---- D3D11 设备（VIDEO_SUPPORT 是将来 d3d11va 硬解的前提） ----
    D3D_FEATURE_LEVEL levels[] = {D3D_FEATURE_LEVEL_11_1, D3D_FEATURE_LEVEL_11_0,
                                  D3D_FEATURE_LEVEL_10_1, D3D_FEATURE_LEVEL_10_0};
    ComPtr<ID3D11Device> device;
    ComPtr<ID3D11DeviceContext> context;
    D3D_FEATURE_LEVEL featureLevel{};
    UINT flags = D3D11_CREATE_DEVICE_BGRA_SUPPORT | D3D11_CREATE_DEVICE_VIDEO_SUPPORT;
    HRESULT hr = D3D11CreateDevice(chosen->adapter.Get(), D3D_DRIVER_TYPE_UNKNOWN, nullptr, flags,
                                   levels, _countof(levels), D3D11_SDK_VERSION,
                                   device.GetAddressOf(), &featureLevel, context.GetAddressOf());
    if (FAILED(hr)) {
        printf("[host] VIDEO_SUPPORT 设备创建失败 hr=0x%08lx，退回仅 BGRA_SUPPORT\n", static_cast<unsigned long>(hr));
        hr = D3D11CreateDevice(chosen->adapter.Get(), D3D_DRIVER_TYPE_UNKNOWN, nullptr,
                               D3D11_CREATE_DEVICE_BGRA_SUPPORT, levels, _countof(levels),
                               D3D11_SDK_VERSION, device.GetAddressOf(), &featureLevel,
                               context.GetAddressOf());
    }
    if (FAILED(hr)) {
        fprintf(stderr, "[host] D3D11CreateDevice 失败 hr=0x%08lx\n", static_cast<unsigned long>(hr));
        return 4;
    }
    printf("[host] D3D11 设备就绪 featureLevel=0x%04x\n", static_cast<unsigned>(featureLevel));

    // ---- 共享纹理（N 个槽位） ----
    D3D11_TEXTURE2D_DESC td{};
    td.Width = args.width;
    td.Height = args.height;
    td.MipLevels = 1;
    td.ArraySize = 1;
    td.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
    td.SampleDesc.Count = 1;
    td.Usage = D3D11_USAGE_DEFAULT;
    td.BindFlags = D3D11_BIND_RENDER_TARGET | D3D11_BIND_SHADER_RESOURCE;
    td.MiscFlags = D3D11_RESOURCE_MISC_SHARED_NTHANDLE | D3D11_RESOURCE_MISC_SHARED_KEYEDMUTEX;

    std::vector<ComPtr<ID3D11Texture2D>> shared(args.slots);
    std::vector<ComPtr<IDXGIKeyedMutex>> mutexes(args.slots);
    std::vector<HANDLE> sharedHandles;   // 必须一直持有：句柄一关，命名共享对象就没了
    sharedHandles.reserve(args.slots);
    for (uint32_t i = 0; i < args.slots; ++i) {
        hr = device->CreateTexture2D(&td, nullptr, shared[i].GetAddressOf());
        if (FAILED(hr)) {
            fprintf(stderr, "[host] 共享纹理创建失败 hr=0x%08lx\n", static_cast<unsigned long>(hr));
            return 5;
        }
        ComPtr<IDXGIResource1> res;
        hr = shared[i].As(&res);
        if (FAILED(hr)) { fprintf(stderr, "[host] 取 IDXGIResource1 失败\n"); return 5; }
        std::wstring name = args.texName + L"_" + std::to_wstring(i);
        // 打样用【命名句柄】：消费者 OpenSharedResourceByName 即可拿到，不需要
        // DuplicateHandle 那一套。生产环境（P3）会改用无名 NT 句柄 + DuplicateHandle 投递进
        // 游戏进程（见 native/README.md）。
        //
        // ★ 句柄必须一直持有到进程结束：命名共享对象靠这个句柄活着，提前 CloseHandle
        //   会让名字立刻失效，消费者 OpenSharedResourceByName 就只能拿到 E_INVALIDARG
        //   （实测就是踩了这个：hr=0x80070057）。
        HANDLE h = nullptr;
        hr = res->CreateSharedHandle(nullptr, DXGI_SHARED_RESOURCE_READ | DXGI_SHARED_RESOURCE_WRITE,
                                     name.c_str(), &h);
        if (FAILED(hr)) {
            fprintf(stderr, "[host] CreateSharedHandle(%ls) 失败 hr=0x%08lx\n", name.c_str(), static_cast<unsigned long>(hr));
            return 5;
        }
        sharedHandles.push_back(h);
        hr = shared[i].As(&mutexes[i]);
        if (FAILED(hr)) { fprintf(stderr, "[host] 取 IDXGIKeyedMutex 失败\n"); return 5; }
        printf("[host] shared texture: %s (%ux%u B8G8R8A8_UNORM, NTHANDLE|KEYEDMUTEX, handle=0x%p)\n",
               narrow(name.c_str()).c_str(), args.width, args.height, h);
    }

    // ---- 共享 fence（只在 --mo 时需要）：GL_EXT_semaphore_win32 的 D3D 侧载体 ----
    //
    // 为什么共享 fence 而不是继续用键控互斥做同步：键控互斥的 Acquire 是**阻塞**的
    // （且没有"面向 GL"的接口），而 fence 让两侧都能"GPU 排队等待 + 事后查询"：
    //   写方：CopyResource → Signal(值) ；复用槽位前只做**有界**的完成度探测
    //   读方：glWaitSemaphoreEXT(值) → 采样 → glSignalSemaphoreEXT(回程值)
    // 于是谁都不用为了等对方而停手（这正是 P1 用血换来的结论：写方的发帧绝不能依赖读方）。
    ComPtr<ID3D11Fence> fence;
    ComPtr<ID3D11DeviceContext4> context4;
    HANDLE fenceHandle = nullptr;
    if (args.moMode) {
        ComPtr<ID3D11Device5> device5;
        hr = device.As(&device5);
        if (FAILED(hr)) {
            fprintf(stderr, "[host] --mo 需要 ID3D11Device5（Win10 1703+）hr=0x%08lx\n",
                    static_cast<unsigned long>(hr));
            return 5;
        }
        hr = device5->CreateFence(0, D3D11_FENCE_FLAG_SHARED, IID_PPV_ARGS(&fence));
        if (FAILED(hr)) {
            fprintf(stderr, "[host] CreateFence(SHARED) 失败 hr=0x%08lx\n", static_cast<unsigned long>(hr));
            return 5;
        }
        hr = context.As(&context4);
        if (FAILED(hr)) {
            fprintf(stderr, "[host] immediate context 不支持 ID3D11DeviceContext4\n");
            return 5;
        }
        // 无名共享句柄也可以带名字：GL 侧走"句柄直投"，D3D 侧仍可按名打开，两条路都留着
        hr = fence->CreateSharedHandle(nullptr, GENERIC_ALL, L"cineduct_spike_fence", &fenceHandle);
        if (FAILED(hr)) {
            fprintf(stderr, "[host] fence CreateSharedHandle 失败 hr=0x%08lx\n", static_cast<unsigned long>(hr));
            return 5;
        }
        printf("[host] shared fence: handle=0x%p（--mo：等消费者请求后 DuplicateHandle 直投）\n",
               fenceHandle);
    }

    // ---- 中间纹理：画面先渲染到这里，再在锁内拷进共享纹理 ----
    ComPtr<ID3D11Texture2D> intermediate;    D3D11_TEXTURE2D_DESC id = td;
    id.MiscFlags = 0;
    id.BindFlags = D3D11_BIND_RENDER_TARGET;
    hr = device->CreateTexture2D(&id, nullptr, intermediate.GetAddressOf());
    if (FAILED(hr)) { fprintf(stderr, "[host] 中间纹理创建失败\n"); return 5; }
    ComPtr<ID3D11RenderTargetView> rtv;
    hr = device->CreateRenderTargetView(intermediate.Get(), nullptr, rtv.GetAddressOf());
    if (FAILED(hr)) { fprintf(stderr, "[host] RTV 创建失败\n"); return 5; }

    // ---- 着色器 / 常量缓冲 ----
    ComPtr<ID3DBlob> vsBlob, psBlob;
    if (!compileStage("VSMain", "vs_5_0", vsBlob)) return 6;
    if (!compileStage("PSMain", "ps_5_0", psBlob)) return 6;
    ComPtr<ID3D11VertexShader> vs;
    ComPtr<ID3D11PixelShader> ps;
    if (FAILED(device->CreateVertexShader(vsBlob->GetBufferPointer(), vsBlob->GetBufferSize(), nullptr, vs.GetAddressOf()))) return 6;
    if (FAILED(device->CreatePixelShader(psBlob->GetBufferPointer(), psBlob->GetBufferSize(), nullptr, ps.GetAddressOf()))) return 6;
    D3D11_BUFFER_DESC bd{};
    bd.ByteWidth = sizeof(Params);
    bd.Usage = D3D11_USAGE_DYNAMIC;
    bd.BindFlags = D3D11_BIND_CONSTANT_BUFFER;
    bd.CPUAccessFlags = D3D11_CPU_ACCESS_WRITE;
    ComPtr<ID3D11Buffer> cb;
    if (FAILED(device->CreateBuffer(&bd, nullptr, cb.GetAddressOf()))) return 6;

    context->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
    context->VSSetShader(vs.Get(), nullptr, 0);
    context->PSSetShader(ps.Get(), nullptr, 0);
    ID3D11Buffer* cbs[] = {cb.Get()};
    context->VSSetConstantBuffers(0, 1, cbs);
    context->PSSetConstantBuffers(0, 1, cbs);

    // ---- 共享内存状态块 ----
    HANDLE mapping = CreateFileMappingW(INVALID_HANDLE_VALUE, nullptr, PAGE_READWRITE, 0,
                                        kMappingBytes, args.statusName.c_str());
    if (mapping == nullptr) {
        fprintf(stderr, "[host] CreateFileMapping 失败 err=%lu\n", GetLastError());
        return 7;
    }
    auto* status = static_cast<Status*>(MapViewOfFile(mapping, FILE_MAP_ALL_ACCESS, 0, 0, kMappingBytes));
    if (status == nullptr) { fprintf(stderr, "[host] MapViewOfFile 失败\n"); return 7; }
    memset(status, 0, kMappingBytes);
    status->version = kVersion;
    status->width = args.width;
    status->height = args.height;
    status->slots = args.slots;
    status->adapterLuidHigh = static_cast<uint32_t>(chosen->desc.AdapterLuid.HighPart);
    status->adapterLuidLow = chosen->desc.AdapterLuid.LowPart;
    status->producerPid = GetCurrentProcessId();
    std::atomic_thread_fence(std::memory_order_seq_cst);
    status->magic = kMagic;   // magic 最后写：消费者据此判定"块已初始化"
    printf("[host] status mapping: %s\n", narrow(args.statusName.c_str()).c_str());
    printf("[host] READY\n");

    auto slotState = [&](uint32_t i) -> uint32_t {
        return reinterpret_cast<std::atomic<uint32_t>*>(&status->slot[i].state)->load(std::memory_order_acquire);
    };
    auto setSlotState = [&](uint32_t i, uint32_t v) {
        reinterpret_cast<std::atomic<uint32_t>*>(&status->slot[i].state)->store(v, std::memory_order_release);
    };

    /*
     * ---- NT 句柄直投服务（--mo）----
     *
     * GL 侧的 glImportMemoryWin32HandleEXT 要的是**消费者进程内有效的内核句柄值**；
     * "按名打开"那一手只有 D3D 侧有（OpenSharedResourceByName），GL 没有。
     * 所以生产者必须 OpenProcess(PROCESS_DUP_HANDLE) + DuplicateHandle 把每个槽的纹理句柄
     * 和那个共享 fence 句柄复制进消费者进程，再把"消费者进程里的句柄值"写回状态块。
     *
     * 句柄在消费者进程里是**独立有效**的：复制之后即使生产者关掉自己那份也不影响对方。
     */
    auto dupHandlesToConsumer = [&](uint32_t consumerPid) -> bool {
        HANDLE proc = OpenProcess(PROCESS_DUP_HANDLE, FALSE, consumerPid);
        if (proc == nullptr) {
            fprintf(stderr, "[host] OpenProcess(PROCESS_DUP_HANDLE, pid=%u) 失败 err=%lu\n",
                    consumerPid, GetLastError());
            return false;
        }
        bool ok = true;
        for (uint32_t i = 0; i < args.slots && ok; ++i) {
            HANDLE dup = nullptr;
            if (!DuplicateHandle(GetCurrentProcess(), sharedHandles[i], proc, &dup, 0, FALSE,
                                 DUPLICATE_SAME_ACCESS)) {
                fprintf(stderr, "[host] DuplicateHandle(tex %u) 失败 err=%lu\n", i, GetLastError());
                ok = false;
                break;
            }
            status->texHandleDup[i] = static_cast<uint64_t>(reinterpret_cast<uintptr_t>(dup));
        }
        if (ok && fenceHandle != nullptr) {
            HANDLE dup = nullptr;
            if (!DuplicateHandle(GetCurrentProcess(), fenceHandle, proc, &dup, 0, FALSE,
                                 DUPLICATE_SAME_ACCESS)) {
                fprintf(stderr, "[host] DuplicateHandle(fence) 失败 err=%lu\n", GetLastError());
                ok = false;
            } else {
                status->fenceHandleDup = static_cast<uint64_t>(reinterpret_cast<uintptr_t>(dup));
            }
        }
        CloseHandle(proc);
        if (!ok) return false;
        std::atomic_thread_fence(std::memory_order_seq_cst);
        status->dupReady = 1;   // 最后写：消费者看到它就说明句柄值都可用了
        printf("[host] 已把 %u 个纹理句柄 + fence 句柄直投进消费者 pid=%u\n", args.slots, consumerPid);
        for (uint32_t i = 0; i < args.slots; ++i) {
            printf("[host]   槽 %u: 消费者侧句柄 = 0x%llx\n", i,
                   static_cast<unsigned long long>(status->texHandleDup[i]));
        }
        printf("[host]   fence: 消费者侧句柄 = 0x%llx\n",
               static_cast<unsigned long long>(status->fenceHandleDup));
        return true;
    };

    /*
     * 有界地等一个 fence 值（**绝不排"永远不会满足"的 GPU Wait**）。
     *
     * 为什么不用 ID3D11DeviceContext4::Wait 直接等：
     *   ① 消费者的 GL 侧如果中途死了，那个值就永远不会到 —— GPU 队列会被永久挂住，
     *      比"偶尔撕裂"严重得多（这是本项目反复踩过的同一类坑）；
     *   ② 写方的发帧与时钟推进绑在一起，绝不能被同步原语停住。
     * 所以只做"最多等几毫秒"的探测：等到了就继续（此时该槽的内容对端确实读完了），
     * 没等到也继续（宁可这一帧撕裂，也不能停住生产），并把次数记进诊断字段。
     */
    auto waitFenceBounded = [&](uint64_t value, uint32_t timeoutMs, HANDLE ev) -> bool {
        if (fence == nullptr || value == 0) return true;
        if (fence->GetCompletedValue() >= value) return true;
        HRESULT r = fence->SetEventOnCompletion(value, ev);
        if (FAILED(r)) return false;
        if (WaitForSingleObject(ev, timeoutMs) == WAIT_OBJECT_0) return true;
        return false;
    };
    HANDLE fenceEvent = CreateEventW(nullptr, TRUE, FALSE, nullptr);
    uint64_t fenceSeq = 0;        // 生产者每发布一帧 +2（奇数值是"写好了"，偶数留给读方回程）
    uint64_t dupRejectCount = 0;
    uint64_t fenceWaitTimeout = 0;
    bool dupRequested = false;

    // ---- 主循环 ----
    using clock = std::chrono::steady_clock;
    const auto frameDuration = std::chrono::microseconds(1'000'000 / args.fps);
    const auto started = clock::now();
    auto next = started;
    uint64_t gen = 0;
    uint64_t published = 0, dropped = 0, abandoned = 0;
    uint64_t acquireSumUs = 0, acquireMaxUs = 0;
    uint32_t rr = 0;
    uint64_t fpsWindowFrames = 0;
    auto fpsWindowStart = started;
    auto lastStatPrint = started;

    while (!g_stop.load()) {
        ++gen;
        const uint32_t stampR = static_cast<uint32_t>(gen & 0xFF);
        const uint32_t stampG = static_cast<uint32_t>((gen >> 8) & 0xFF);

        // ---- --mo：消费者报到之后把 NT 句柄直投过去（只做一次） ----
        if (args.moMode && !dupRequested) {
            const uint32_t wantDup =
                    reinterpret_cast<std::atomic<uint32_t>*>(&status->consumerWantDup)
                            ->load(std::memory_order_acquire);
            const uint32_t consumerPid =
                    reinterpret_cast<std::atomic<uint32_t>*>(&status->consumerPid)
                            ->load(std::memory_order_acquire);
            if (wantDup != 0 && consumerPid != 0) {
                if (!dupHandlesToConsumer(consumerPid)) ++dupRejectCount;
                dupRequested = true;   // 失败也不重试：本轮打样直接报错，别每帧刷日志
            }
        }

        Params p{};
        p.stamp[0] = stampR / 255.0f;
        p.stamp[1] = stampG / 255.0f;
        p.stamp[2] = kStampBlue;
        p.stamp[3] = 1.0f;
        p.timing[0] = std::chrono::duration<float>(clock::now() - started).count();

        D3D11_MAPPED_SUBRESOURCE mapped{};
        if (SUCCEEDED(context->Map(cb.Get(), 0, D3D11_MAP_WRITE_DISCARD, 0, &mapped))) {
            memcpy(mapped.pData, &p, sizeof(p));
            context->Unmap(cb.Get(), 0);
        }

        const float clear[4] = {0.0f, 0.0f, 0.0f, 1.0f};
        context->OMSetRenderTargets(1, rtv.GetAddressOf(), nullptr);
        context->ClearRenderTargetView(rtv.Get(), clear);
        D3D11_VIEWPORT vp{0.0f, 0.0f, static_cast<float>(args.width), static_cast<float>(args.height), 0.0f, 1.0f};
        context->RSSetViewports(1, &vp);
        context->Draw(3, 0);

        // 挑槽位：优先"消费者已经用过的"（EMPTY / SHOWN），避免覆盖还没被采样的帧
        uint32_t pick = UINT32_MAX;
        for (uint32_t i = 0; i < args.slots; ++i) {
            const uint32_t st = slotState(i);
            if (st == kSlotEmpty || st == kSlotShown) { pick = i; break; }
        }
        if (pick == UINT32_MAX) pick = (rr++) % args.slots;   // 全都在等消费者

        setSlotState(pick, kSlotWriting);

        /*
         * --mo：复用这个槽之前，先**有界地**确认消费者已经读完它（fence 上那笔回程）。
         *
         * 注意这里等的是"消费者读完上一帧"这件事，而不是"消费者取走这一帧"：
         * 后者会让生产者的发帧依赖消费者是否及时取帧，那正是本项目禁止的依赖。
         */
        if (args.moMode && fence != nullptr) {
            const uint64_t need =
                    *reinterpret_cast<volatile uint64_t*>(&status->slotConsumerDone[pick]);
            if (need != 0 && !waitFenceBounded(need, 2, fenceEvent)) {
                ++fenceWaitTimeout;   // 没等到也照发：宁可撕裂，绝不停产
            }
        }

        const auto t0 = clock::now();
        /*
         * 键控互斥在这里是【尽力而为】，不是正确性保证。
         *
         * 实测教训（两条都踩过）：
         *  ① 两侧都用同一个 key ⇒ AcquireSync **同 key 直接成功**（递归语义），等于完全没有互斥；
         *  ② 严格乒乓（写方 Acquire(0)/Release(1)、读方 Acquire(1)/Release(0)）⇒ 任一方缺席或慢一拍，
         *     接力棒就永久断掉：实测生产者发布 4 帧后彻底卡死（丢 2644 帧）。
         * 所以策略是：**零超时试锁**，两把钥匙各试一次，都拿不到就丢掉这一帧
         * （16ms 后还有下一帧，绝不为了等锁而阻塞生产）。
         *
         * 真正的互斥由**槽位状态机**保证：写方只碰 EMPTY/SHOWN 槽，读方只碰 READY 槽，
         * 读方在拷完之后才标 SHOWN —— 于是两者不可能同时接触同一个槽。
         */
        HRESULT aq = mutexes[pick]->AcquireSync(0, 0);
        if (aq == static_cast<HRESULT>(WAIT_TIMEOUT)) {
            aq = mutexes[pick]->AcquireSync(1, 0);
        }
        const auto waitedUs = static_cast<uint64_t>(
                std::chrono::duration_cast<std::chrono::microseconds>(clock::now() - t0).count());
        acquireSumUs += waitedUs;
        if (waitedUs > acquireMaxUs) acquireMaxUs = waitedUs;
        status->lastAcquireUs = static_cast<uint32_t>(waitedUs);
        status->maxAcquireUs = static_cast<uint32_t>(acquireMaxUs);

        if (aq == WAIT_ABANDONED) {
            // 上一次持有者（可能是消费者进程）崩了：句柄归我们，继续用
            ++abandoned;
        } else if (aq == WAIT_TIMEOUT || FAILED(aq)) {
            ++dropped;
            status->framesDropped = dropped;
            setSlotState(pick, kSlotShown);
            continue;
        }

        context->CopyResource(shared[pick].Get(), intermediate.Get());
        mutexes[pick]->ReleaseSync(1);   // 交棒给读方（它在等 key 1）

        /*
         * --cpu-pattern：再用 CPU 往右下角戳一个 8×8 的角标（UpdateSubresource）。
         *
         * 这是**诊断专用**的：它把两类失败分开 ——
         *   ① "消费者在 GL 里根本看不到生产者的显存"（连 CPU 写进去的都读不到）
         *   ② "GPU 渲染/拷贝出来的内容对 GL 不可见"（CPU 角标能看到，画面却是 0）
         * 角标内容：B=0xFF, G=(帧号低 8 位), R=0xEE, A=0xFF。
         */
        if (args.cpuPattern) {
            uint8_t mark[8 * 8 * 4];
            for (size_t px = 0; px < sizeof(mark); px += 4) {
                mark[px + 0] = 0xFF;
                mark[px + 1] = static_cast<uint8_t>(gen & 0xFF);
                mark[px + 2] = 0xEE;
                mark[px + 3] = 0xFF;
            }
            D3D11_BOX box{};
            box.left = args.width - 8;
            box.top = args.height - 8;
            box.front = 0;
            box.right = args.width;
            box.bottom = args.height;
            box.back = 1;
            context->UpdateSubresource(shared[pick].Get(), 0, &box, mark, 8 * 4, 0);
        }

        /*
         * --mo：把这一帧"写好"这件事推到共享 fence 上（奇数值），消费者等这个值再采样。
         *
         * Signal 是**排进命令流**的（GPU 执行到那里才推进 fence），所以它天然排在
         * CopyResource 之后 —— 不需要任何 CPU 同步就能保证"读方看到值 ⇒ 内容已写完"。
         */
        if (args.moMode && context4 != nullptr) {
            fenceSeq += 2;                                   // 1,3,5,...
            if (SUCCEEDED(context4->Signal(fence.Get(), fenceSeq))) {
                *reinterpret_cast<volatile uint64_t*>(&status->slotFenceValue[pick]) = fenceSeq;
            }
        }

        // 先写内容再写 state（release），消费者读 state==READY（acquire）后必能看到
        status->slot[pick].generation = static_cast<uint32_t>(gen);
        status->slot[pick].stampR = stampR;
        status->slot[pick].stampG = stampG;
        setSlotState(pick, kSlotReady);

        ++published;
        ++fpsWindowFrames;
        status->framesPublished = published;

        const auto now = clock::now();
        if (now - fpsWindowStart >= std::chrono::seconds(1)) {
            const double secs = std::chrono::duration<double>(now - fpsWindowStart).count();
            status->producerFpsX100 = static_cast<uint32_t>(fpsWindowFrames / secs * 100.0);
            fpsWindowFrames = 0;
            fpsWindowStart = now;
        }
        if (now - lastStatPrint >= std::chrono::seconds(2)) {
            lastStatPrint = now;
            if (args.moMode) {
                printf("[host] fps=%.1f published=%llu dropped=%llu abandoned=%llu "
                       "acquireUs(avg=%llu max=%llu) fenceSeq=%llu 槽位等待超时=%llu\n",
                       status->producerFpsX100 / 100.0,
                       static_cast<unsigned long long>(published),
                       static_cast<unsigned long long>(dropped),
                       static_cast<unsigned long long>(abandoned),
                       static_cast<unsigned long long>(published ? acquireSumUs / published : 0),
                       static_cast<unsigned long long>(acquireMaxUs),
                       static_cast<unsigned long long>(fenceSeq),
                       static_cast<unsigned long long>(fenceWaitTimeout));
            } else {
                printf("[host] fps=%.1f published=%llu dropped=%llu abandoned=%llu acquireUs(avg=%llu max=%llu)\n",
                       status->producerFpsX100 / 100.0,
                       static_cast<unsigned long long>(published),
                       static_cast<unsigned long long>(dropped),
                       static_cast<unsigned long long>(abandoned),
                       static_cast<unsigned long long>(published ? acquireSumUs / published : 0),
                       static_cast<unsigned long long>(acquireMaxUs));
            }
        }

        if (args.durationSec > 0 && now - started >= std::chrono::seconds(args.durationSec)) break;

        next += frameDuration;
        const auto after = clock::now();
        if (next > after) std::this_thread::sleep_until(next);
        else next = after;   // 落后了就别累积补偿，避免"倍速追赶"
    }

    // 收尾：把槽位都标回 EMPTY，并把 magic 清掉，消费者据此知道生产者已退出
    for (uint32_t i = 0; i < args.slots; ++i) setSlotState(i, kSlotEmpty);
    std::atomic_thread_fence(std::memory_order_seq_cst);
    status->magic = 0;

    printf("[host] 汇总: published=%llu dropped=%llu abandoned=%llu acquireUs(avg=%llu max=%llu)\n",
           static_cast<unsigned long long>(published),
           static_cast<unsigned long long>(dropped),
           static_cast<unsigned long long>(abandoned),
           static_cast<unsigned long long>(published ? acquireSumUs / published : 0),
           static_cast<unsigned long long>(acquireMaxUs));
    if (args.moMode) {
        printf("[host] --mo 汇总: 句柄直投=%s 槽位等待超时=%llu fenceSeq=%llu\n",
               dupRejectCount == 0 && dupRequested ? "成功" : "未完成或失败",
               static_cast<unsigned long long>(fenceWaitTimeout),
               static_cast<unsigned long long>(fenceSeq));
    }
    printf("[host] done\n");

    if (fenceEvent != nullptr) CloseHandle(fenceEvent);
    if (fenceHandle != nullptr) CloseHandle(fenceHandle);
    UnmapViewOfFile(status);
    CloseHandle(mapping);
    return published > 0 ? 0 : 1;
}

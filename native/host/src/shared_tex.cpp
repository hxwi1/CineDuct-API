#include "shared_tex.h"

#include <dxgi1_2.h>
#include <wrl/client.h>

#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstring>

using Microsoft::WRL::ComPtr;
using cineduct::spike::Status;
using cineduct::spike::kMagic;
using cineduct::spike::kMappingBytes;
using cineduct::spike::kMaxSlots;
using cineduct::spike::kSlotEmpty;
using cineduct::spike::kSlotReady;
using cineduct::spike::kSlotShown;
using cineduct::spike::kSlotWriting;
using cineduct::spike::kVersion;

namespace cineduct::host {

namespace {

std::string hrText(const char* what, HRESULT hr) {
    char buf[256];
    std::snprintf(buf, sizeof(buf), "%s (hr=0x%08lx)", what, static_cast<unsigned long>(hr));
    return buf;
}

std::atomic<uint32_t>* statePtr(Status* status, uint32_t slot) {
    return reinterpret_cast<std::atomic<uint32_t>*>(&status->slot[slot].state);
}

/**
 * 把身份文本写进定长字段：最多 {@code bytes - 1} 个字符 + NUL。
 *
 * <p>不做格式校验（那是上层的事）：这里只保证**不越界**、且空串写出来也是 NUL 结尾的空串。
 */
void copyIdentity(char* dst, size_t bytes, const std::string& value) {
    if (dst == nullptr || bytes == 0) return;
    const size_t n = value.size() < bytes - 1 ? value.size() : bytes - 1;
    if (n > 0) std::memcpy(dst, value.data(), n);
    dst[n] = '\0';
}

} // namespace

bool SharedTextureSet::create(ID3D11Device* dev, uint32_t w, uint32_t h, uint32_t slots,
                              uint64_t adapterLuid, const std::wstring& baseName,
                              const std::wstring& statusName, const std::string& sessionUuid,
                              const std::string& instanceUuid, std::string& error) {
    if (dev == nullptr || w == 0 || h == 0 || slots == 0 || slots > kMaxSlots) {
        error = "共享纹理参数非法";
        return false;
    }
    this->device = dev;
    this->width = w;
    this->height = h;
    this->slotCount = slots;

    D3D11_TEXTURE2D_DESC desc{};
    desc.Width = w;
    desc.Height = h;
    desc.MipLevels = 1;
    desc.ArraySize = 1;
    desc.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
    desc.SampleDesc.Count = 1;
    desc.Usage = D3D11_USAGE_DEFAULT;
    desc.BindFlags = D3D11_BIND_RENDER_TARGET | D3D11_BIND_SHADER_RESOURCE;
    desc.MiscFlags = D3D11_RESOURCE_MISC_SHARED_NTHANDLE | D3D11_RESOURCE_MISC_SHARED_KEYEDMUTEX;

    for (uint32_t i = 0; i < slots; ++i) {
        ComPtr<ID3D11Texture2D> tex;
        HRESULT hr = dev->CreateTexture2D(&desc, nullptr, tex.GetAddressOf());
        if (FAILED(hr)) {
            error = hrText("CreateTexture2D 失败", hr);
            return false;
        }
        ComPtr<IDXGIResource1> res;
        hr = tex.As(&res);
        if (FAILED(hr)) {
            error = hrText("取 IDXGIResource1 失败", hr);
            return false;
        }
        std::wstring name = baseName + L"_" + std::to_wstring(i);
        HANDLE handle = nullptr;
        hr = res->CreateSharedHandle(nullptr, DXGI_SHARED_RESOURCE_READ | DXGI_SHARED_RESOURCE_WRITE,
                                     name.c_str(), &handle);
        if (FAILED(hr)) {
            error = hrText("CreateSharedHandle 失败", hr);
            return false;
        }
        ComPtr<IDXGIKeyedMutex> mutex;
        hr = tex.As(&mutex);
        if (FAILED(hr)) {
            error = hrText("取 IDXGIKeyedMutex 失败", hr);
            return false;
        }
        this->textures.push_back(tex.Detach());
        this->mutexes.push_back(mutex.Detach());
        this->handles.push_back(handle);
        this->names.push_back(name);
    }

    this->mapping = CreateFileMappingW(INVALID_HANDLE_VALUE, nullptr, PAGE_READWRITE, 0,
                                       kMappingBytes, statusName.c_str());
    if (this->mapping == nullptr) {
        error = "CreateFileMapping 失败";
        return false;
    }
    this->status = static_cast<Status*>(
            MapViewOfFile(this->mapping, FILE_MAP_ALL_ACCESS, 0, 0, kMappingBytes));
    if (this->status == nullptr) {
        error = "MapViewOfFile 失败";
        return false;
    }
    std::memset(this->status, 0, kMappingBytes);
    this->status->version = kVersion;
    this->status->width = w;
    this->status->height = h;
    this->status->slots = slots;
    this->status->producerPid = GetCurrentProcessId();
    /*
     * v9：子进程身份（见 docs/host-uuid.md）。
     *
     * 必须在 magic 之前写好 —— 与 producerPid/LUID 同一约定：读者看到 magic 时，
     * 这整块内容已经是可用的。截断拷贝：最多写 36 个字符 + NUL，超长直接截断（不会越界）。
     */
    copyIdentity(this->status->sessionUuid, sizeof(this->status->sessionUuid), sessionUuid);
    copyIdentity(this->status->instanceUuid, sizeof(this->status->instanceUuid), instanceUuid);
    // 填适配器 LUID：消费者据此核对"是否与游戏进程的 GL 上下文同一块卡"（不一致就没法互操作）
    this->status->adapterLuidHigh = static_cast<uint32_t>(adapterLuid >> 32);
    this->status->adapterLuidLow = static_cast<uint32_t>(adapterLuid & 0xFFFFFFFFull);
    std::atomic_thread_fence(std::memory_order_seq_cst);
    this->status->magic = kMagic;   // magic 最后写：消费者据此判定"块已初始化"
    return true;
}

bool SharedTextureSet::publish(ID3D11Texture2D* source, std::string& error) {
    if (this->status == nullptr || source == nullptr) {
        error = "共享纹理未初始化";
        return false;
    }

    /*
    /*
     * 挑槽位：优先消费者已经用过的（EMPTY/SHOWN），避免覆盖还没被采样的帧。
     *
     * ★ 保留"都没有空闲槽位时轮转挑一个"的兜底。
     * 去掉它试过一次，换来的是**更严重**的故障：消费者慢半拍或没取帧时写方再也拿不到槽位，
     * 连发帧带**时钟推进**一起停住（发布帧数与位置都不动了）→ 上层看到画面冻住。
     * 结论：发帧进度**绝不能**依赖消费者是否及时取帧。撕裂要另想办法（见下面 mutex 的说明），
     * 不能靠"写方停手"。
     */
    uint32_t pick = UINT32_MAX;
    for (uint32_t i = 0; i < this->slotCount; ++i) {
        const uint32_t st = statePtr(this->status, i)->load(std::memory_order_acquire);
        if (st == kSlotEmpty || st == kSlotShown) {
            pick = i;
            break;
        }
    }
    if (pick == UINT32_MAX) pick = this->nextSlot++ % this->slotCount;

    statePtr(this->status, pick)->store(kSlotWriting, std::memory_order_release);

    /*
     * 键控互斥：零超时试锁，两把钥匙各试一次。
     *
     * （P1 打样实测：同 key 会"直接成功"等于没锁；严格乒乓又会在对端缺席时死锁；
     *   所以互斥交给槽位状态机，锁只做 GPU 顺序提示，绝不阻塞生产。
     *   —— 这条兜底被我拿掉过一次，代价是"消费者一慢写方就彻底停"，已回退。）
     */
    const auto t0 = std::chrono::steady_clock::now();
    HRESULT hr = this->mutexes[pick]->AcquireSync(kWriterKey, 0);
    if (hr == static_cast<HRESULT>(WAIT_TIMEOUT)) {
        hr = this->mutexes[pick]->AcquireSync(kReaderKey, 0);
    }
    const auto waitedUs = static_cast<uint32_t>(
            std::chrono::duration_cast<std::chrono::microseconds>(
                    std::chrono::steady_clock::now() - t0).count());
    this->status->lastAcquireUs = waitedUs;
    if (waitedUs > this->status->maxAcquireUs) this->status->maxAcquireUs = waitedUs;

    if (hr == static_cast<HRESULT>(WAIT_TIMEOUT) || (FAILED(hr) && hr != static_cast<HRESULT>(WAIT_ABANDONED))) {
        this->dropped++;
        this->status->framesDropped = this->dropped;
        statePtr(this->status, pick)->store(kSlotShown, std::memory_order_release);
        error = "拿不到槽位锁（消费者占用中），本帧丢弃";
        return false;
    }
    ComPtr<ID3D11DeviceContext> ctx;
    this->device->GetImmediateContext(&ctx);
    // 锁内只做一次显存内拷贝（几百微秒），把占锁时间压到最短
    ctx->CopyResource(this->textures[pick], source);
    this->mutexes[pick]->ReleaseSync(kReaderKey);

    // 先写内容再写 state（release）：消费者读到 READY（acquire）后必然能看到
    this->status->slot[pick].generation = static_cast<uint32_t>(this->published);
    this->status->slot[pick].stampR = 0;
    this->status->slot[pick].stampG = 0;
    statePtr(this->status, pick)->store(kSlotReady, std::memory_order_release);

    this->published++;
    this->status->framesPublished = this->published;
    this->lastSlot = pick;
    this->hasLast = true;
    return true;
}

void SharedTextureSet::reportThroughput(uint32_t fpsX100, uint32_t lastAcquireUs,
                                        uint32_t maxAcquireUs) {
    if (this->status == nullptr) return;
    this->status->producerFpsX100 = fpsX100;
    this->status->lastAcquireUs = lastAcquireUs;
    this->status->maxAcquireUs = maxAcquireUs;
}

void SharedTextureSet::destroy() {
    if (this->status != nullptr) {
        for (uint32_t i = 0; i < this->slotCount; ++i) {
            statePtr(this->status, i)->store(kSlotEmpty, std::memory_order_release);
        }
        std::atomic_thread_fence(std::memory_order_seq_cst);
        this->status->magic = 0;   // 告诉消费者"生产者已退出"
        UnmapViewOfFile(this->status);
        this->status = nullptr;
    }
    if (this->mapping != nullptr) {
        CloseHandle(this->mapping);
        this->mapping = nullptr;
    }
    for (IDXGIKeyedMutex* m : this->mutexes) {
        if (m != nullptr) m->Release();
    }
    for (ID3D11Texture2D* t : this->textures) {
        if (t != nullptr) t->Release();
    }
    for (HANDLE h : this->handles) {
        if (h != nullptr) CloseHandle(h);   // 进程退出前可以关了
    }
    this->mutexes.clear();
    this->textures.clear();
    this->handles.clear();
}

} // namespace cineduct::host

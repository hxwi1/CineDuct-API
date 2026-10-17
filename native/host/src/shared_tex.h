// ============================================================================
// 共享纹理输出（P2 · M2）
//
// 把解码出来的帧发布到一组 DXGI 共享纹理上，游戏进程据此零拷贝取帧。
//
// 这里刻意**复用 P1 打样验证过的机制与状态布局**（native/spike/shared/cineduct_spike_status.h）：
//   * 纹理：B8G8R8A8_UNORM + SHARED_NTHANDLE + SHARED_KEYEDMUTEX
//   * 同步：键控互斥两把钥匙「零超时试锁」，**正确性由槽位状态机承担**
//     （写方只碰 EMPTY/SHOWN，读方只碰 READY，读方拷完才标 SHOWN）
//   * 状态：命名共享内存段，消费者每帧只读内存，零 IPC 往返
//
// 为什么发布的是"转好的 BGRA"而不是 NV12：GL 侧无法采样 NV12，
// 而 NV_DX_interop 只接受 BGRA8 这类格式（详见 docs/PLAN-standalone-gpu.md §3.1）。
// ============================================================================
#pragma once

#include <d3d11.h>

#include <cstdint>
#include <string>
#include <vector>

#include "cineduct_spike_status.h"

namespace cineduct::host {

/**
 * 键控互斥的两把钥匙（跨进程乒乓）。
 *
 * 约定：写方 Acquire 0 / Release 1，读方 Acquire 1 / Release 0；
 * 两边都保留"超时后试另一把钥匙"的兜底（对端缺席时不能死锁）。
 *
 * ⚠️ 关于这对兜底的教训（2026-09-27，已回退过一版）：**不能把它们删掉**。
 * 写方的发帧与**时钟推进**绑在一起，一旦它拿不到钥匙就整个停下来（发布帧数、位置都不走），
 * 上层看到的是画面直接冻住 —— 比"偶尔撕裂"严重得多。
 * 想治撕裂得在别处下手（比如让读方拷完再交还槽位、或者给读方的拷贝加完成等待），
 * 而不是让写方停手。
 *
 * Java 侧同名常量：dev.mediafix.gpu.NativeD3D.KEY_WRITER = 0 / KEY_READER = 1。
 */
inline constexpr uint32_t kWriterKey = 0;
inline constexpr uint32_t kReaderKey = 1;
struct SharedTextureSet {
    /** 共享纹理槽位（每槽一张，轮转使用）。 */
    std::vector<ID3D11Texture2D*> textures;
    std::vector<IDXGIKeyedMutex*> mutexes;
    std::vector<HANDLE> handles;          // 必须持有：句柄一关，命名共享对象就没了
    std::vector<std::wstring> names;

    ID3D11Device* device = nullptr;
    uint32_t width = 0;
    uint32_t height = 0;
    uint32_t slotCount = 0;

    HANDLE mapping = nullptr;
    cineduct::spike::Status* status = nullptr;

    uint64_t published = 0;
    uint64_t dropped = 0;
    uint32_t nextSlot = 0;
    uint32_t lastSlot = 0;
    bool hasLast = false;

    /**
     * 建 N 张共享纹理 + 状态段。
     *
     * @param baseName     纹理名基（实际为 name_0 … name_N-1）
     * @param statusName   状态段名
     * @param sessionUuid  逻辑会话级 UUID 文本（可为空；写进状态环 v9，见 docs/host-uuid.md）
     * @param instanceUuid 进程实例级 UUID 文本（可为空；写进状态环 v9）
     */
    bool create(ID3D11Device* dev, uint32_t w, uint32_t h, uint32_t slots, uint64_t adapterLuid,
                const std::wstring& baseName, const std::wstring& statusName,
                const std::string& sessionUuid, const std::string& instanceUuid,
                std::string& error);

    /**
     * 发布一帧。调用方保证 source 是 BGRA8、尺寸与 create 时一致。
     *
     * @return true = 已发布；false = 本次丢帧（拿不到锁或无空槽），原因见 error
     */
    bool publish(ID3D11Texture2D* source, std::string& error);

    /** 累加诊断（由调用方每帧喂进来）。 */
    void reportThroughput(uint32_t fpsX100, uint32_t lastAcquireUs, uint32_t maxAcquireUs);

    void destroy();
};

} // namespace cineduct::host

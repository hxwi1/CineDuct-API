#include "ffmpeg_dyn.h"

#include <windows.h>

#include <cstdio>

namespace cineduct::host {

std::string win32Error(const char* what) {
    const DWORD code = GetLastError();
    char buf[512];
    std::snprintf(buf, sizeof(buf), "%s (Win32 error %lu)", what, static_cast<unsigned long>(code));
    return buf;
}

namespace {

// 按依赖顺序加载：avutil 是底座，其余都依赖它
const char* const kModules[] = {
        "avutil-60.dll",
        "swresample-6.dll",
        "avcodec-62.dll",
        "avformat-62.dll",
};

/**
 * swscale 单独一份"可选"清单：没有它只失去软解回退，硬解必须照常可用。
 *
 * <p>单独列出来还有个原因：不同 FFmpeg 包的 DLL 命名会随大版本变（swscale-9.dll），
 * 把它塞进必需清单会让"换一套 FFmpeg 包"直接变成整个子进程起不来。
 */
const char* const kOptionalModules[] = {
        "swscale-9.dll",
};

std::wstring widen(const char* s) {
    if (s == nullptr) return {};
    const int n = MultiByteToWideChar(CP_UTF8, 0, s, -1, nullptr, 0);
    if (n <= 1) return {};
    std::wstring w(static_cast<size_t>(n), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, s, -1, w.data(), n);
    w.pop_back();
    return w;
}

} // namespace

bool FfmpegApi::load(const std::wstring& dir, std::string& error) {
    const std::wstring prefix = dir.empty() ? L"" : (dir + L"\\");

    // 先把目录加进 DLL 搜索路径：FFmpeg 的 DLL 之间互相依赖，靠绝对路径加载
    // 也仍会因为"依赖项找不到"失败，必须让加载器能在这个目录里找到同伴。
    SetDllDirectoryW(dir.c_str());

    for (const char* module : kModules) {
        const std::wstring full = prefix + widen(module);
        HMODULE handle = LoadLibraryW(full.c_str());
        if (handle == nullptr) {
            error = win32Error((std::string("加载 ") + module + " 失败").c_str());
            return false;
        }
        this->modules.push_back(reinterpret_cast<void*>(handle));
        this->names.emplace_back(module);
    }
    // 可选模块：加载失败只记一行，不进 modules（符号解析自然拿不到 → 能力标记为不可用）
    for (const char* module : kOptionalModules) {
        const std::wstring full = prefix + widen(module);
        HMODULE handle = LoadLibraryW(full.c_str());
        if (handle == nullptr) {
            std::printf("[host] 可选模块 %s 未加载（软解回退不可用，硬解不受影响）\n", module);
            continue;
        }
        this->modules.push_back(reinterpret_cast<void*>(handle));
        this->names.emplace_back(module);
    }

    // 逐个解析符号；缺一个就报名字，便于判断 DLL 版本是否匹配。
    // 注意：只列**真正导出**的函数 —— 头文件里 static inline 的（av_q2d 之类）取不到地址。
    int missing = 0;
    std::string missingNames;
#define MEDIAFIX_RESOLVE(name)                                                        \
    for (void* m : this->modules) {                                                   \
        this->name = reinterpret_cast<decltype(this->name)>(                          \
                GetProcAddress(reinterpret_cast<HMODULE>(m), #name));                 \
        if (this->name != nullptr) break;                                             \
    }                                                                                 \
    if (this->name == nullptr) {                                                      \
        missing++;                                                                    \
        missingNames += std::string(missingNames.empty() ? "" : ", ") + #name;        \
    }
    MEDIAFIX_FFMPEG_FUNCS(MEDIAFIX_RESOLVE)
#undef MEDIAFIX_RESOLVE

    if (missing > 0) {
        char buf[512];
        std::snprintf(buf, sizeof(buf), "有 %d 个 FFmpeg 符号没找到: %s（DLL 版本不匹配？）",
                      missing, missingNames.c_str());
        error = buf;
        return false;
    }

    // 可选符号：缺了不算错（见 ffmpeg_dyn.h 的 MEDIAFIX_FFMPEG_OPTIONAL_FUNCS）
    std::string optionalMissing;
#define MEDIAFIX_RESOLVE_OPTIONAL(name)                                               \
    for (void* m : this->modules) {                                                   \
        this->name = reinterpret_cast<decltype(this->name)>(                          \
                GetProcAddress(reinterpret_cast<HMODULE>(m), #name));                 \
        if (this->name != nullptr) break;                                             \
    }                                                                                 \
    if (this->name == nullptr) {                                                      \
        optionalMissing += std::string(optionalMissing.empty() ? "" : ", ") + #name;  \
    }
    MEDIAFIX_FFMPEG_OPTIONAL_FUNCS(MEDIAFIX_RESOLVE_OPTIONAL)
#undef MEDIAFIX_RESOLVE_OPTIONAL
    if (!optionalMissing.empty()) {
        std::printf("[host] 可选 FFmpeg 符号缺失（软解回退不可用）: %s\n", optionalMissing.c_str());
    }
    return true;
}

} // namespace cineduct::host

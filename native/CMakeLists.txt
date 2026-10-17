# ============================================================================
# MediaFix 原生工程
#
# 目前包含两块：
#   cineduct-glshim      —— 游戏进程内的 JNI 垫片（D3D11 设备创建 / 适配器枚举 /
#                           按名打开共享纹理）。LWJGL 不绑定 D3D11，所以这层必须有。
#   cineduct-spike-host  —— P1 打样用生产者：创建 DXGI 共享纹理并持续写入动画画面，
#                           模拟将来真正子进程（FFmpeg 解码 → 共享纹理）的角色。
#
# 将来 P2 会加 cineduct-host（真正的媒体子进程，链接 FFmpeg）。
# 只支持 Windows（DXGI / D3D11）。
# ============================================================================
cmake_minimum_required(VERSION 3.20)

# ---------------------------------------------------------------------------
# ★ 必须在 project() 之前设置：ninja 靠这个前缀从 cl 的 /showIncludes 输出里提取头文件依赖。
# MSVC 的中文消息前缀是「注意: 包含文件:」，与 ninja 默认的英文前缀不符 ——
# 后果是**依赖跟踪整体失效**：改了头文件不会重编对应 .cpp，于是出现
# "改动没生效"，甚至两个编译单元看到的类/结构体布局不一致 → 函数指针表错位 → 调野指针崩溃。
# （cineduct-host 的头几次调试就踩了它，症状是"打印出 0 个 DLL 却又能调 FFmpeg"。）
# ---------------------------------------------------------------------------
# 依赖跟踪（血的教训，别删这段注释）：
#
# MSVC 的 /showIncludes 输出前缀跟随**编译器消息语言**：中文版 VS 输出
# 「注意: 包含文件:」，而 ninja 只认英文的「Note: including file:」。
# 两者不匹配时 ninja 无法建立头文件依赖 —— 后果不是"编译慢一点"，而是
# **改了头文件不重编**：症状是"改动没生效"，更糟的是两个编译单元看到的结构体布局不一致
# → 函数指针表错位 → 调野指针崩溃（cineduct-host 的头几次调试就是这么栽的）。
#
# 修法在 native/build.ps1：`set VSLANG=1033` 必须放在 `call vcvars64.bat` **之后**
# （中文版 vcvars 会把 VSLANG 设成 2052，盖掉之前的设置）。
# 所以这里**不要**再去覆盖 CMAKE_CL_SHOWINCLUDES_PREFIX —— 设成中文前缀反而会把
# 正常的英文输出打回原形。
project(cineduct-native LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

if(NOT WIN32)
    message(FATAL_ERROR "MediaFix 原生部分目前只支持 Windows（DXGI/D3D11）")
endif()

# 可执行文件与 DLL 都放同一个目录：Java 侧 -Djava.library.path 指它即可
set(CMAKE_RUNTIME_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/bin)
set(CMAKE_LIBRARY_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/bin)
set(CMAKE_ARCHIVE_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/lib)

if(MSVC)
    # /utf-8 必须开：源码里有中文注释，否则 MSVC 按本地代码页读会报 C2001
    add_compile_options(/utf-8 /W4 /permissive- /EHsc /MP)
    add_compile_definitions(_CRT_SECURE_NO_WARNINGS NOMINMAX WIN32_LEAN_AND_MEAN UNICODE _UNICODE)
endif()

# ---------------------------------------------------------------------------
# 游戏进程内的 JNI 垫片
# ---------------------------------------------------------------------------
find_package(JNI REQUIRED)

add_library(cineduct-glshim SHARED
        glshim/src/cineduct_glshim.cpp)
target_include_directories(cineduct-glshim PRIVATE
        glshim/include
        spike/shared
        ${JNI_INCLUDE_DIRS})
target_link_libraries(cineduct-glshim PRIVATE d3d11 dxgi)
set_target_properties(cineduct-glshim PROPERTIES OUTPUT_NAME "cineduct-glshim")

# ---------------------------------------------------------------------------
# P1 打样生产者
# ---------------------------------------------------------------------------
add_executable(cineduct-spike-host
        spike/host/main.cpp)
set_target_properties(cineduct-spike-host PROPERTIES OUTPUT_NAME "cineduct-spike-host")
target_include_directories(cineduct-spike-host PRIVATE spike/shared)
target_link_libraries(cineduct-spike-host PRIVATE d3d11 dxgi d3dcompiler)

# ---------------------------------------------------------------------------
# P2 媒体子进程
#
# FFmpeg 走**动态加载**（LoadLibrary + GetProcAddress），所以这里只链接系统库，
# 不需要 FFmpeg 的导入库 —— 头文件由 native/fetch-ffmpeg.ps1 取到 third_party/ffmpeg/include。
# 运行时 DLL 直接用 mod 里那套 javacpp FFmpeg natives（同一个构建，行为一致）。
# ---------------------------------------------------------------------------
set(CINEDUCT_FFMPEG_INCLUDE ${CMAKE_CURRENT_SOURCE_DIR}/third_party/ffmpeg/include)
if(NOT EXISTS ${CINEDUCT_FFMPEG_INCLUDE}/libavcodec/avcodec.h)
    message(WARNING "缺少 FFmpeg 头文件，先跑 native/fetch-ffmpeg.ps1（cineduct-host 会被跳过）")
else()
    add_executable(cineduct-host
            host/src/main.cpp
            host/src/host_log.cpp
            host/src/ffmpeg_dyn.cpp
            host/src/gpu.cpp
            host/src/convert.cpp
            host/src/video_color.cpp
            host/src/sw_scale.cpp
            host/src/shared_tex.cpp
            host/src/audio_device.cpp
            host/src/audio_setup.cpp
            host/src/audio_sink.cpp
            host/src/audio_source.cpp
            host/src/audio_spdif.cpp
            host/src/media_clock.cpp
            host/src/packet_queue.cpp
            host/src/frame_queue.cpp
            host/src/video_decoder.cpp
            host/src/video_pipeline.cpp
            host/src/ipc.cpp)
    set_target_properties(cineduct-host PROPERTIES OUTPUT_NAME "cineduct-host")
    target_include_directories(cineduct-host PRIVATE
            ${CINEDUCT_FFMPEG_INCLUDE}
            host/src
            spike/shared)   # 复用 P1 打样验证过的状态块布局
    target_link_libraries(cineduct-host PRIVATE d3d11 dxgi)
    # FFmpeg 的部分工具函数依赖 ws2_32（网络）与 secur32
    # dbghelp：崩溃时写 minidump（见 main.cpp 的 crashHandler）
    # runtimeobject：WinRT 激活（读"当前激活的空间音效格式"，见 audio_device.cpp）
    target_link_libraries(cineduct-host PRIVATE ws2_32 secur32 bcrypt dbghelp runtimeobject)
endif()

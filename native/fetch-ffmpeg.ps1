# ============================================================================
# 获取 P2（C++ 子进程）需要的 FFmpeg 开发文件
#
# 需要什么、为什么：
#   * DLL：**已经有了** —— mod 里打包的那套 javacpp FFmpeg natives（8.0.1），
#     FfmpegRuntime 会解压到 <gamedir>/mediafix-ffmpeg/v2-bundled。子进程直接加载同一批 DLL，
#     于是 Java 兜底路径与 C++ 快路径用的**是同一个 FFmpeg 构建**，行为一致、不会出现
#     "同一个视频两条路径表现不一样"。
#   * 头文件：javacpp 的 platform 包里只有 DLL，没有 C 头，所以要从匹配版本的
#     FFmpeg 开发包里取。这里取 BtbN 的 lgpl-shared 构建（含 include/），
#     版本与运行时 DLL 对齐（8.0）。
#   * 导入库（.lib/.dll.a）：**刻意不用**。MinGW 产出的 .dll.a 给 MSVC 链接并不可靠，
#     而我们只需要 20 多个函数 —— 全部走 LoadLibrary + GetProcAddress 动态解析，
#     链接期零依赖，顺带还能在缺某个函数时报出明确的缺失名字。
#
# 用法：
#   .\native\fetch-ffmpeg.ps1                 # 下载并解压头文件到 native/third_party/ffmpeg
#   .\native\fetch-ffmpeg.ps1 -Force          # 重新下载
#   .\native\fetch-ffmpeg.ps1 -CopyDlls       # 顺便把 DLL 也复制过来（独立跑子进程时用）
# ============================================================================
param(
    [switch]$Force,
    [switch]$CopyDlls,
    # 与运行时 DLL 对齐：javacpp 打包的是 FFmpeg 8.0.1（libavcodec 62 / libavutil 60），
    # BtbN 只提供 n8.1 的构建 —— 8.0 与 8.1 同属 8.x 大版本，公开头文件的结构布局一致，
    # n8.1 的头文件对 8.0 的 DLL 是 ABI 兼容的（脚本末尾会核对主版本号与 DLL 名）。
    [string]$Url = 'https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-n8.1-latest-win64-lgpl-shared-8.1.zip'
)

$ErrorActionPreference = 'Stop'
$nativeDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$third = Join-Path $nativeDir 'third_party'
$dest = Join-Path $third 'ffmpeg'
$include = Join-Path $dest 'include'
$zipPath = Join-Path $third 'ffmpeg-dev.zip'

Write-Host '=== 获取 FFmpeg 开发文件（P2 子进程用）===' -ForegroundColor Cyan

if ((Test-Path (Join-Path $include 'libavcodec/avcodec.h')) -and -not $Force) {
    Write-Host "[跳过] 头文件已存在: $include"
} else {
    New-Item -ItemType Directory -Force -Path $third | Out-Null
    Write-Host "[下载] $Url"
    $ProgressPreference = 'SilentlyContinue'
    Invoke-WebRequest -Uri $Url -OutFile $zipPath -UseBasicParsing
    Write-Host ("[下载完成] {0:N1} MB" -f ((Get-Item $zipPath).Length / 1MB))

    $tmp = Join-Path $third '_unzip'
    if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
    Expand-Archive -Path $zipPath -DestinationPath $tmp -Force

    # 包内是 ffmpeg-n8.0-latest-win64-lgpl-shared-8.0/ 这样的顶层目录
    $root = Get-ChildItem $tmp -Directory | Select-Object -First 1
    if (-not $root) { throw '压缩包里没有找到顶层目录' }

    New-Item -ItemType Directory -Force -Path $dest | Out-Null
    Copy-Item (Join-Path $root.FullName 'include') $include -Recurse -Force
    if (Test-Path (Join-Path $root.FullName 'LICENSE.txt')) {
        Copy-Item (Join-Path $root.FullName 'LICENSE.txt') (Join-Path $dest 'LICENSE.txt') -Force
    }
    if ($CopyDlls -and (Test-Path (Join-Path $root.FullName 'bin'))) {
        Copy-Item (Join-Path $root.FullName 'bin') (Join-Path $dest 'bin') -Recurse -Force
        Write-Host '[复制] DLL 已一并放到 native/third_party/ffmpeg/bin（独立跑子进程用）'
    }
    Remove-Item $tmp -Recurse -Force
    Remove-Item $zipPath -Force
    Write-Host "[完成] 头文件 -> $include"
}

# 报告关键头文件与版本宏，确认可用
$need = @('libavcodec/avcodec.h', 'libavformat/avformat.h', 'libavutil/avutil.h',
          'libavutil/hwcontext_d3d11va.h', 'libswresample/swresample.h')
Write-Host ''
Write-Host '关键头文件:'
foreach ($h in $need) {
    $p = Join-Path $include $h
    $ok = Test-Path $p
    Write-Host ("  {0} {1}" -f ($(if ($ok) { '[OK]' } else { '[缺]' })), $h)
}

$verHeader = Join-Path $include 'libavutil/version.h'
if (Test-Path $verHeader) {
    $major = (Get-Content $verHeader | Select-String -Pattern '#define LIBAVUTIL_VERSION_MAJOR\s+(\d+)').Matches.Groups[1].Value
    Write-Host "  libavutil 主版本: $major（javacpp 那套 DLL 是 FFmpeg 8.0.1，应一致）"
}

# 与运行时 DLL 名核对：头文件主版本必须与 DLL 文件名后缀一致，
# 否则结构布局可能不同 —— 那种错配在运行期是"静默读到垃圾"甚至崩溃，必须提前拦住。
Write-Host ''
$dllDirs = @('F:\mos\video\run\cineduct-ffmpeg\v2-bundled')
$dllDirs += (Get-ChildItem 'F:\potato world\.minecraft\versions' -Directory -ErrorAction SilentlyContinue |
        ForEach-Object { Join-Path $_.FullName 'mediafix-ffmpeg\v2-bundled' })
foreach ($d in $dllDirs) {
    if (Test-Path $d) {
        $dlls = Get-ChildItem $d -Filter 'avcodec-*.dll' -ErrorAction SilentlyContinue
        if ($dlls) {
            Write-Host "运行时 DLL 版本（$d）: $($dlls[0].Name) / $(Get-ChildItem $d -Filter 'avutil-*.dll' | Select-Object -First 1 | ForEach-Object { $_.Name })"
            break
        }
    }
}
Write-Host '提示：若 DLL 名里的主版本与头文件主版本不一致，请用 -Url 换成对应版本的开发包。'

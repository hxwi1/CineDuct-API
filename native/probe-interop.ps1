# ============================================================================
# 互操作能力探测：NT 共享句柄 → OpenGL 内存对象
#
# 这个脚本回答一个与游戏无关的问题：**这台机器的 GL 驱动能不能吃 D3D11 的 NT 共享句柄**。
# 它不需要 Minecraft、不需要子进程、不需要第二个进程，一次跑完给出结论。
#
# 用法：
#   .\native\probe-interop.ps1              # 隐藏窗口跑，把整段输出贴回来即可
#   .\native\probe-interop.ps1 -Visible     # 弹个小窗口（肉眼看它有没有画出来）
#   .\native\probe-interop.ps1 -NoBuild     # 跳过构建（已经编好了）
#
# 为什么要单独做这个：WGL_NV_DX_interop2 是 NVIDIA 血统的扩展，AMD 上这条老路不可靠；
# 而 GL_EXT_memory_object_win32 / GL_EXT_semaphore_win32 是 Khronos 的跨厂商扩展。
# 结论只跟显卡驱动有关，所以可以让任何一台机器跑完把输出发回来。
# ============================================================================
param(
    [switch]$Visible,
    [switch]$NoBuild
)

$ErrorActionPreference = 'Stop'
$nativeDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo = Split-Path -Parent $nativeDir
$buildDir = Join-Path $nativeDir 'build'
$bin = Join-Path $buildDir 'bin'
$classes = Join-Path $buildDir 'java-classes'
$cpFile = Join-Path $buildDir 'lwjgl-cp.txt'

if (-not $NoBuild) {
    Write-Host '=== 先构建原生 + Java 侧 ===' -ForegroundColor Cyan
    & (Join-Path $nativeDir 'build.ps1') | Out-Host
}

if (-not (Test-Path -LiteralPath (Join-Path $bin 'cineduct-glshim.dll'))) {
    throw "缺少 cineduct-glshim.dll（先跑 .\native\build.ps1）"
}
if (-not (Test-Path -LiteralPath $classes)) { throw "Java 侧没编译（先跑 .\native\build.ps1）" }

$lwjglCp = (Get-Content -LiteralPath $cpFile -Raw).Trim()
$fullCp = "$classes;$lwjglCp"

$javaArgs = @(
    "-Djava.library.path=$bin",
    '-Dstdout.encoding=UTF-8',
    '-Dstderr.encoding=UTF-8',
    '-cp', $fullCp,
    'dev.mediafix.probe.MoProbe'
)
if ($Visible) { $javaArgs += '--visible' } else { $javaArgs += '--hidden' }

Write-Host ''
Write-Host '=== 探测开始 ===' -ForegroundColor Cyan
& java @javaArgs
$rc = $LASTEXITCODE
Write-Host ''
if ($rc -eq 0) {
    Write-Host '探测结论: ✔ 这条路线在你这台机器上成立' -ForegroundColor Green
} else {
    Write-Host "探测结论: ✘ 退出码 $rc —— 把上面整段输出发回来" -ForegroundColor Yellow
}
exit $rc

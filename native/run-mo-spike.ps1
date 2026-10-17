# ============================================================================
# 跨进程打样：NT 共享句柄 → OpenGL 内存对象（不走 WGL_NV_DX_interop）
#
# 与 run-spike.ps1（NV_DX_interop 路线）并列，做 A/B 对照；生产者是同一个 exe，只是多给 --mo：
#   * 生产者额外建一个**共享 D3D11 fence**，每帧 Signal(2k+1)
#   * 消费者报到（写自己的 pid）后，生产者用 DuplicateHandle 把每槽的纹理 NT 句柄与 fence 句柄
#     复制进消费者进程，再把"消费者侧句柄值"写回共享状态块
#   * 消费者 glImportMemoryWin32HandleEXT(D3D11_IMAGE_EXT) 直接别名那块显存，
#     glImportSemaphoreWin32HandleEXT(D3D12_FENCE_EXT) 做 GPU 间同步
#
# 用法：
#   .\native\run-mo-spike.ps1                 # 默认 20 秒
#   .\native\run-mo-spike.ps1 -Seconds 60 -Visible
#   .\native\run-mo-spike.ps1 -Slots 4 -Fps 120
#   .\native\run-mo-spike.ps1 -NoBuild
# ============================================================================
param(
    [int]$Seconds = 20,
    [int]$Width = 1280,
    [int]$Height = 720,
    [int]$Slots = 3,
    [int]$Fps = 60,
    [switch]$Visible,
    [switch]$Vsync,
    [switch]$NoBuild,
    [int]$WaitMs = 8000
)

$ErrorActionPreference = 'Stop'
$nativeDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$buildDir = Join-Path $nativeDir 'build'
$bin = Join-Path $buildDir 'bin'
$hostExe = Join-Path $bin 'cineduct-spike-host.exe'
$classes = Join-Path $buildDir 'java-classes'
$cpFile = Join-Path $buildDir 'lwjgl-cp.txt'

if (-not $NoBuild) { & (Join-Path $nativeDir 'build.ps1') | Out-Host }

if (-not (Test-Path -LiteralPath $hostExe)) { throw "先构建：.\native\build.ps1" }
if (-not (Test-Path -LiteralPath $classes)) { throw "Java 侧没编译：.\native\build.ps1" }

$lwjglCp = (Get-Content -LiteralPath $cpFile -Raw).Trim()
$fullCp = "$classes;$lwjglCp"

$hostLog = Join-Path $buildDir 'host-mo.log'
if (Test-Path -LiteralPath $hostLog) { Remove-Item -LiteralPath $hostLog -Force }

Write-Host '=== MO 打样：NT 共享句柄 → OpenGL 内存对象 ===' -ForegroundColor Cyan
Write-Host "[1/3] 启动生产者（--mo）: $Width x $Height, $Slots 槽位, $Fps fps" -ForegroundColor Cyan

$hostArgs = @('--mo', '--width', $Width, '--height', $Height, '--slots', $Slots, '--fps', $Fps)
if ($Seconds -gt 0) { $hostArgs += @('--duration', ($Seconds + 10)) }

$hostProc = Start-Process -FilePath $hostExe -ArgumentList $hostArgs `
    -RedirectStandardOutput $hostLog -NoNewWindow -PassThru

$consumerExit = 1
try {
    $deadline = (Get-Date).AddSeconds(10)
    $ready = $false
    while ((Get-Date) -lt $deadline) {
        if (Test-Path -LiteralPath $hostLog) {
            $text = Get-Content -LiteralPath $hostLog -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
            if ($text -and $text -match 'READY') { $ready = $true; break }
        }
        if ($hostProc.HasExited) { throw "生产者提前退出（见 $hostLog）" }
        Start-Sleep -Milliseconds 100
    }
    if (-not $ready) { throw "等不到生产者 READY（见 $hostLog）" }

    Get-Content -LiteralPath $hostLog -Encoding UTF8 | ForEach-Object { Write-Host "  | $_" }

    Write-Host '[2/3] 启动消费者（OpenGL 侧，内存对象路线）...' -ForegroundColor Cyan
    $javaArgs = @(
        "-Djava.library.path=$bin",
        '-Dstdout.encoding=UTF-8',
        '-Dstderr.encoding=UTF-8',
        '-cp', $fullCp,
        'dev.mediafix.probe.MoSpikeConsumer',
        '--seconds', $Seconds,
        '--wait-ms', $WaitMs
    )
    if ($Visible) { $javaArgs += '--visible' } else { $javaArgs += '--hidden' }
    if ($Vsync) { $javaArgs += '--vsync' }

    & java @javaArgs
    $consumerExit = $LASTEXITCODE

    Write-Host ''
    Write-Host '[3/3] 生产者日志（末尾 30 行）：' -ForegroundColor Cyan
    Get-Content -LiteralPath $hostLog -Encoding UTF8 | Select-Object -Last 30 |
        ForEach-Object { Write-Host "  | $_" }

    Write-Host ''
    if ($consumerExit -eq 0) {
        Write-Host '结论: ✔ PASS —— 「NT 共享句柄 → GL 内存对象」这条跨进程路线成立' -ForegroundColor Green
    } else {
        Write-Host "结论: ✘ FAIL (消费者退出码 $consumerExit) —— 把上面的日志发我" -ForegroundColor Red
    }
} finally {
    if ($hostProc -and -not $hostProc.HasExited) {
        Stop-Process -Id $hostProc.Id -Force -ErrorAction SilentlyContinue
    }
}

exit $consumerExit

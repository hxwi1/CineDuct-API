# ============================================================================
# 运行 P1 打样：生产者（模拟子进程）+ 消费者（模拟游戏进程 / OpenGL 侧）
#
# 用法：
#   .\native\run-spike.ps1                        # 默认跑 20 秒
#   .\native\run-spike.ps1 -Seconds 60 -Visible   # 跑 60 秒并弹出窗口肉眼看
#   .\native\run-spike.ps1 -Slots 4 -Fps 120      # 改槽位数 / 生产者帧率
#   .\native\run-spike.ps1 -ListAdapters          # 只看适配器，不跑打样
# ============================================================================
param(
    [int]$Seconds = 20,
    [int]$Width = 1280,
    [int]$Height = 720,
    [int]$Slots = 3,
    [int]$Fps = 60,
    [switch]$Visible,
    [switch]$Vsync,
    [switch]$ListAdapters,
    [int]$WaitMs = 8000
)

$ErrorActionPreference = 'Stop'
$nativeDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$buildDir = Join-Path $nativeDir 'build'
$bin = Join-Path $buildDir 'bin'
$hostExe = Join-Path $bin 'cineduct-spike-host.exe'
$classes = Join-Path $buildDir 'java-classes'
$cpFile = Join-Path $buildDir 'lwjgl-cp.txt'

if (-not (Test-Path -LiteralPath $hostExe)) { throw "先构建：.\native\build.ps1" }

if ($ListAdapters) {
    & $hostExe --list
    exit $LASTEXITCODE
}

if (-not (Test-Path -LiteralPath $classes)) { throw "Java 消费者没编译：.\native\build.ps1" }
$lwjglCp = Get-Content -LiteralPath $cpFile -Raw
$fullCp = "$classes;$($lwjglCp.Trim())"

$hostLog = Join-Path $buildDir 'host.log'
if (Test-Path -LiteralPath $hostLog) { Remove-Item -LiteralPath $hostLog -Force }

Write-Host '=== P1 打样：跨进程共享显存 → OpenGL 零拷贝 ===' -ForegroundColor Cyan
Write-Host "[1/3] 启动生产者: $Width x $Height, $Slots 槽位, $Fps fps" -ForegroundColor Cyan

$hostArgs = @(
    '--width', $Width, '--height', $Height, '--slots', $Slots, '--fps', $Fps
)
if ($Seconds -gt 0) { $hostArgs += @('--duration', ($Seconds + 10)) }

$hostProc = Start-Process -FilePath $hostExe -ArgumentList $hostArgs `
    -RedirectStandardOutput $hostLog -NoNewWindow -PassThru

try {
    # 等生产者把共享纹理和状态块建好（日志里出现 READY）
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

    Write-Host '[2/3] 启动消费者（OpenGL 侧）...' -ForegroundColor Cyan
    $javaArgs = @(
        "-Djava.library.path=$bin",
        '-Dstdout.encoding=UTF-8',   # 否则 Java 按 GBK 输出，中文日志会乱码
        '-Dstderr.encoding=UTF-8',
        '-cp', $fullCp,
        'dev.mediafix.spike.SpikeConsumer',
        '--seconds', $Seconds,
        '--wait-ms', $WaitMs
    )
    if (-not $Visible) { $javaArgs += '--hidden' }
    if ($Vsync) { $javaArgs += '--vsync' }

    & java @javaArgs
    $consumerExit = $LASTEXITCODE

    Write-Host ''
    Write-Host '[3/3] 生产者日志：' -ForegroundColor Cyan
    Get-Content -LiteralPath $hostLog -Encoding UTF8 | Select-Object -Last 25 |
        ForEach-Object { Write-Host "  | $_" }

    Write-Host ''
    if ($consumerExit -eq 0) {
        Write-Host '结论: ✔ PASS —— 零拷贝路线在你这台机器上成立' -ForegroundColor Green
    } else {
        Write-Host "结论: ✘ FAIL (消费者退出码 $consumerExit) —— 把上面的日志发我" -ForegroundColor Red
    }
} finally {
    if ($hostProc -and -not $hostProc.HasExited) {
        Stop-Process -Id $hostProc.Id -Force -ErrorAction SilentlyContinue
    }
}

exit $consumerExit

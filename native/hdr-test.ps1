# ============================================================================
# HDR 回归测试：HDR10 内容不许再"发灰"
#
# 用的素材 native/testdata/bbb-hdr10.mp4 是**真 HDR10**（bt2020nc/bt2020/smpte2084、
# 10bit、PQ），内容与 bbb.mp4 完全一样，所以判据很清楚：
#
#   * 发灰的特征是**高光被压死** —— 修之前实测最亮只有 131（而 SDR 原片是 253）；
#   * 修好之后驱动会做 HDR→SDR 色调映射，动态范围回到 200 以上。
#
# 断言不依赖任何外部工具：靠子进程自己打印的"首帧亮度统计"（--dump-frame 附带）。
#
# 用法： .\native\hdr-test.ps1
# ============================================================================
param(
    [string]$Exe = 'F:\mos\video\native\build\bin\cineduct-host.exe',
    [string]$FfmpegDir = 'F:\mos\video\run\cineduct-ffmpeg\v2-bundled',
    [string]$HdrUrl = 'F:\mos\video\native\testdata\bbb-hdr10.mp4',
    [string]$SdrUrl = 'F:\mos\video\native\testdata\bbb.mp4'
)

$ErrorActionPreference = 'Stop'
$script:failures = 0
function Report([bool]$ok, [string]$text) {
    if ($ok) { Write-Output "[ok] $text" } else { Write-Output "[FAIL] $text"; $script:failures++ }
}

function Run-Host([string]$url, [string]$mode, [string]$tag) {
    $log = Join-Path $env:TEMP "cineduct-hdr-$tag.log"
    $err = Join-Path $env:TEMP "cineduct-hdr-$tag.err"
    $dump = Join-Path $env:TEMP "cineduct-hdr-$tag.ppm"
    $hostArgs = @('--ffmpeg-dir', $FfmpegDir, '--url', $url,
                  '--output-textures', "hdrtest_$tag", '--status', "hdrtest_$tag",
                  '--no-audio', '--seconds', '2', '--hdr-tonemap', $mode,
                  '--dump-frame', $dump)
    # 必须 -Wait：Start-Process 拿到的进程对象只有在 -Wait 之后才有 ExitCode
    #（不带 -Wait 时 ExitCode 取到的是空值 —— 这个脚本第一版就踩了）
    $proc = Start-Process -FilePath $Exe -ArgumentList $hostArgs -RedirectStandardOutput $log `
        -RedirectStandardError $err -NoNewWindow -Wait -PassThru
    $code = $proc.ExitCode
    $text = ''
    foreach ($f in @($log, $err)) { if (Test-Path $f) { $text += (Get-Content -LiteralPath $f -Encoding UTF8 | Out-String) } }
    $maxLuma = -1
    if ($text -match '最亮=(\d+)') { $maxLuma = [int]$Matches[1] }
    $avgLuma = -1.0
    if ($text -match '平均=([\d.]+)') { $avgLuma = [double]$Matches[1] }
    return [pscustomobject]@{
        text     = $text
        exitCode = $code
        maxLuma  = $maxLuma
        avgLuma  = $avgLuma
    }
}

Write-Output "[test] HDR10 素材 = $HdrUrl"

# ---------------------------------------------------------------------------
# 1) 驱动色调映射（默认路径）：必须认出 HDR，并且动态范围要回来
# ---------------------------------------------------------------------------
$driver = Run-Host $HdrUrl 'driver' 'driver'
Write-Output ("     driver: 平均={0} 最亮={1}" -f $driver.avgLuma, $driver.maxLuma)
Report ($driver.text -match '检测到 HDR 内容') "认出 HDR 内容（日志: 检测到 HDR 内容）"
Report ($driver.text -match 'HDR 输入已声明给驱动') "把 HDR 色彩空间声明给了驱动（G2084/P2020 → G22/P709）"
Report ($driver.maxLuma -ge 200) "色调映射后动态范围正常（最亮 $($driver.maxLuma) ≥ 200）"

# ---------------------------------------------------------------------------
# 2) 诊断对照：关掉色调映射必须复现"发灰"（否则说明这条测试根本没在测东西）
# ---------------------------------------------------------------------------
$off = Run-Host $HdrUrl 'off' 'off'
Write-Output ("     off   : 平均={0} 最亮={1}" -f $off.avgLuma, $off.maxLuma)
Report ($off.maxLuma -le 170) "关掉色调映射确实复现发灰（最亮 $($off.maxLuma) ≤ 170）"
Report (($driver.maxLuma - $off.maxLuma) -ge 50) "两者差异显著（$($off.maxLuma) → $($driver.maxLuma)）"

# ---------------------------------------------------------------------------
# 3) CPU 色调映射（备选路径）：不许崩，而且确实走了 CPU 那条路
# ---------------------------------------------------------------------------
$cpu = Run-Host $HdrUrl 'cpu' 'cpu'
Write-Output ("     cpu   : 平均={0} 最亮={1} 退出码={2}" -f $cpu.avgLuma, $cpu.maxLuma, $cpu.exitCode)
Report ($cpu.exitCode -eq 0) "CPU 色调映射模式没有崩溃（退出码 $($cpu.exitCode)）"
Report ($cpu.text -match 'CPU 色调映射') "确实启用了 CPU 色调映射"

# ---------------------------------------------------------------------------
# 4) SDR 素材不许被这套改动影响（走同一条 VideoProcessor，色彩空间应是 BT.709）
# ---------------------------------------------------------------------------
$sdr = Run-Host $SdrUrl 'driver' 'sdr'
Write-Output ("     SDR   : 平均={0} 最亮={1} 退出码={2}" -f $sdr.avgLuma, $sdr.maxLuma, $sdr.exitCode)
Report ($sdr.exitCode -eq 0) "SDR 素材照常播放（退出码 $($sdr.exitCode)）"
Report ($sdr.text -notmatch '检测到 HDR 内容') "SDR 素材不会被误判成 HDR"
Report ($sdr.maxLuma -ge 200) "SDR 画面动态范围正常（最亮 $($sdr.maxLuma)）"

Write-Output ''
if ($script:failures -eq 0) { Write-Output 'RESULT: PASS'; exit 0 }
Write-Output "RESULT: FAIL ($($script:failures) 项)"
exit 1

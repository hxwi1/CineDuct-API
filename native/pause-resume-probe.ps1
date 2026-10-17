#requires -Version 5.1
<#
    暂停/恢复音画偏移现场探针（内容级）

    目的：验证用户报的「暂停之后起播时音画不同步」是否还存在。
    关键点：**不能用「画面 vs 音频时钟」判断**——画面就是被音频时钟调速的，
    所以那个差值恒等于 0，永远看不出内容错位。
    这里读 host 的 AV明细 行：可听内容 = 写出内容 − 排队（环形+设备待播），
    再和画面 PTS 相减，才是真正能听出来的偏移。

    用法：
      powershell -NoProfile -ExecutionPolicy Bypass -File .\native\pause-resume-probe.ps1 `
        -FfmpegDir 'F:\mos\video\run\cineduct-ffmpeg\v2-bundled' [-PauseMs 5000]
#>
param(
    [string]$Exe = 'F:\mos\video\native\build\bin\cineduct-host.exe',
    [string]$FfmpegDir = 'F:\mos\video\run\cineduct-ffmpeg\v2-bundled',
    [string]$Url = 'F:\mos\video\native\testdata\bbb.mp4',
    [string]$PipeName = 'cineduct-pauseprobe',
    [string]$Status = 'cineduct_pauseprobe_status',
    [string]$Log = "$env:TEMP\cineduct-pauseprobe.log",
    [int]$PauseMs = 5000,
    [int]$WarmupMs = 3000,
    [double]$ThresholdMs = 120
)
$ErrorActionPreference = 'Stop'

$hostArgs = @('--ffmpeg-dir', $FfmpegDir, '--url', $Url, '--output-textures', 'cineduct_pauseprobe_tex',
              '--status', $Status, '--volume', '0', '--pipe', $PipeName, '--av-debug')
$proc = Start-Process -FilePath $Exe -ArgumentList $hostArgs -RedirectStandardOutput $Log `
    -RedirectStandardError "$Log.err" -NoNewWindow -PassThru

$pipe = New-Object System.IO.Pipes.NamedPipeClientStream('.', $PipeName, [System.IO.Pipes.PipeDirection]::InOut)
$pipe.Connect(8000)
$script:id = 1
function Send([string]$payload) {
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($payload)
    $ms = New-Object System.IO.MemoryStream
    $bw = New-Object System.IO.BinaryWriter($ms)
    $bw.Write([uint32](2 + 4 + $bytes.Length)); $bw.Write([uint16]1); $bw.Write([uint32]($script:id++)); $bw.Write($bytes); $bw.Flush()
    $out = $ms.ToArray(); $pipe.Write($out, 0, $out.Length); $pipe.Flush()
    $hdr = New-Object byte[] 4; $read = 0
    while ($read -lt 4) { $n = $pipe.Read($hdr, $read, 4 - $read); if ($n -le 0) { throw 'pipe closed' }; $read += $n }
    $len = [System.BitConverter]::ToUInt32($hdr, 0); $body = New-Object byte[] $len; $read = 0
    while ($read -lt $len) { $n = $pipe.Read($body, $read, $len - $read); if ($n -le 0) { throw 'pipe closed' }; $read += $n }
    return [System.Text.Encoding]::UTF8.GetString($body, 6, $len - 6)
}

$script:mmf = $null; $script:acc = $null
function Open-Status() {
    if ($script:acc -ne $null) { return }
    try {
        $script:mmf = [System.IO.MemoryMappedFiles.MemoryMappedFile]::OpenExisting($Status)
        $script:acc = $script:mmf.CreateViewAccessor()
    } catch { }
}
function VideoPts() {
    Open-Status
    if ($script:acc -eq $null) { return -1 }
    return [int64]$script:acc.ReadUInt64(392)
}

# 从日志里取最近一条 AV明细 行 -> 内容级偏移
function ReadDetail() {
    if (-not (Test-Path $Log)) { return $null }
    $lines = @(Get-Content $Log -Encoding UTF8 | Select-String -Pattern 'AV明细' | Select-Object -Last 1)
    if ($lines.Count -eq 0) { return $null }
    $line = $lines[0].Line
    $content = $null; $video = $null; $queued = $null
    if ($line -match '可听=(-?\d+)') { $content = [double]$Matches[1] }
    if ($line -match '画面=(-?\d+)') { $video = [double]$Matches[1] }
    if ($line -match '排队=(\d+)') { $queued = [double]$Matches[1] }
    if ($content -eq $null -or $video -eq $null) { return $null }
    return [pscustomobject]@{ content = $content; video = $video; queued = $queued; offset = $video - $content; raw = $line.Trim() }
}

$script:records = @()
function Sample([string]$phase, [string]$tag) {
    $s = Send 'status'
    $pos = -1; if ($s -match 'pos=(-?\d+)ms') { $pos = [int]$Matches[1] }
    $sink = -1; if ($s -match 'sinkClock=(-?\d+)ms') { $sink = [int]$Matches[1] }
    $v = VideoPts
    $d = ReadDetail
    $content = -1; $off = [double]::NaN
    if ($d -ne $null) { $content = $d.content; $off = $d.offset }
    Write-Host ("  [{0,-10}] {1,-18} 画面={2,6}ms 可听={3,8}ms 内容偏移={4,7}ms 时钟={5,6}ms 声卡={6,6}ms" -f `
        $phase, $tag, $v, $content, [math]::Round($off), $pos, $sink)
    $script:records += [pscustomobject]@{ phase = $phase; tag = $tag; video = $v; content = $content; offset = $off }
}

try {
    Start-Sleep -Milliseconds 1500
    Write-Host '--- A 正常播放（基线） ---'
    for ($i = 1; $i -le 6; $i++) { Start-Sleep -Milliseconds 500; Sample '播放' "t=$([math]::Round($i*0.5,1))s" }

    Write-Host '--- B 暂停 ---'
    $null = Send 'pause 1'
    for ($i = 1; $i -le 3; $i++) { Start-Sleep -Milliseconds 700; Sample '暂停' "p=$([math]::Round($i*0.7,1))s" }
    Start-Sleep -Milliseconds $PauseMs
    Sample '暂停' "等 ${PauseMs}ms 后"

    Write-Host '--- C 恢复 ---'
    $null = Send 'pause 0'
    for ($i = 1; $i -le 10; $i++) { Start-Sleep -Milliseconds 500; Sample '恢复' "r=$([math]::Round($i*0.5,1))s" }
}
finally {
    try { $null = Send 'quit' } catch { }
    try { $pipe.Close() } catch { }
    [void]$proc.WaitForExit(5000)
    if (-not $proc.HasExited) { $proc.Kill() }
}

$before = $script:records | Where-Object { $_.phase -eq '播放' -and -not [double]::IsNaN($_.offset) }
$after = $script:records | Where-Object { $_.phase -eq '恢复' -and -not [double]::IsNaN($_.offset) } | Select-Object -Last 6
Write-Host ''
if ($before.Count -eq 0 -or $after.Count -eq 0) {
    Write-Host '[BUG] 没拿到内容级偏移样本（AV明细 行缺失？）'
    exit 1
}
$bAvg = ($before | Measure-Object -Property offset -Average).Average
$aAvg = ($after | Measure-Object -Property offset -Average).Average
$aMax = ($after | ForEach-Object { [math]::Abs($_.offset) } | Measure-Object -Maximum).Maximum
Write-Host ("暂停前内容偏移平均 {0:N0}ms -> 恢复后平均 {1:N0}ms（变化 {2:N0}ms，恢复后最大 |偏移| {3:N0}ms）" -f `
    $bAvg, $aAvg, ($aAvg - $bAvg), $aMax)
$ok = ([math]::Abs($aAvg - $bAvg) -le $ThresholdMs) -and ($aMax -le ($ThresholdMs * 2))
Write-Host ("[{0}] 恢复后音画没有错开（阈值 {1}ms）" -f $(if ($ok) { 'ok' } else { 'BUG' }), $ThresholdMs)

Write-Host ''
Write-Host '--- host 日志：暂停/恢复记账、时钟迁移、偏移纠正 ---'
foreach ($f in @($Log, "$Log.err")) {
    if (Test-Path $f) {
        Get-Content $f -Encoding UTF8 | Select-String -Pattern '暂停：|恢复：|时钟拒绝|停滞|音画偏移|纠正' |
            ForEach-Object { '  ' + $_.Line }
    }
}
if (-not $ok) { exit 1 }
exit 0

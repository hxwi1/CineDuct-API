#requires -Version 5.1
<#
    音画偏移「默认纠正」测试（时钟重锚，不动声卡）

    覆盖两件事：
      A 误报检查：正常播放 8 秒 —— 不该出现任何"重锚"日志，|可听−画面| 一直在小范围内；
      B 真错位能自愈：故意把音频拖后 700ms（--debug-audio-lag，不需要 --av-correct），
        期望日志出现「已把**时钟**重锚到音频内容」，之后 |可听−画面| 回到小范围，
        并且**不能**出现 --av-correct 那条「已把音频对齐到」（默认路线不动声卡）。

    判据用的是 host 的 AV明细 行（可听=… 画面=…）—— 那是"耳朵位置 vs 眼睛位置"的真实差值，
    不是"画面 vs 时钟"（后者恒等于 0，永远看不出错位，见 video_pipeline.cpp 的 ★ 说明）。
#>
param(
    [string]$Exe = 'F:\mos\video\native\build\bin\cineduct-host.exe',
    [string]$FfmpegDir = 'F:\mos\video\run\cineduct-ffmpeg\v2-bundled',
    [string]$Url = 'F:\mos\video\native\testdata\bbb.mp4',
    [int]$LagMs = 700,
    [int]$BaselineSeconds = 8,
    [int]$HealSeconds = 14,
    [double]$GoodMs = 150,
    [string]$Log = "$env:TEMP\cineduct-avheal.log"
)
$ErrorActionPreference = 'Stop'
$script:fail = 0

function Start-Host([string[]]$extra, [string]$pipeName, [string]$statusName, [string]$logPath, [string]$texName) {
    $hostArgs = @('--ffmpeg-dir', $FfmpegDir, '--url', $Url, '--output-textures', $texName,
                  '--status', $statusName, '--volume', '0', '--pipe', $pipeName, '--av-debug') + $extra
    $proc = Start-Process -FilePath $Exe -ArgumentList $hostArgs -RedirectStandardOutput $logPath `
        -RedirectStandardError "$logPath.err" -NoNewWindow -PassThru
    $pipe = New-Object System.IO.Pipes.NamedPipeClientStream('.', $pipeName, [System.IO.Pipes.PipeDirection]::InOut)
    $pipe.Connect(8000)
    return [pscustomobject]@{ proc = $proc; pipe = $pipe; id = 1; mmf = $null; acc = $null; status = $statusName }
}

function Send-Pipe($h, [string]$payload) {
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($payload)
    $ms = New-Object System.IO.MemoryStream
    $bw = New-Object System.IO.BinaryWriter($ms)
    $bw.Write([uint32](2 + 4 + $bytes.Length)); $bw.Write([uint16]1); $bw.Write([uint32]($h.id++)); $bw.Write($bytes); $bw.Flush()
    $out = $ms.ToArray(); $h.pipe.Write($out, 0, $out.Length); $h.pipe.Flush()
    $hdr = New-Object byte[] 4; $read = 0
    while ($read -lt 4) { $n = $h.pipe.Read($hdr, $read, 4 - $read); if ($n -le 0) { throw 'pipe closed' }; $read += $n }
    $len = [System.BitConverter]::ToUInt32($hdr, 0); $body = New-Object byte[] $len; $read = 0
    while ($read -lt $len) { $n = $h.pipe.Read($body, $read, $len - $read); if ($n -le 0) { throw 'pipe closed' }; $read += $n }
    return [System.Text.Encoding]::UTF8.GetString($body, 6, $len - 6)
}

function Stop-Host($h) {
    try { $null = Send-Pipe $h 'quit' } catch { }
    try { $h.pipe.Close() } catch { }
    [void]$h.proc.WaitForExit(5000)
    if (-not $h.proc.HasExited) { $h.proc.Kill() }
}

# 从日志里精确定位「重锚」之前/之后的明细行，算出真实偏移
function Analyze-Log([string]$logPath, [int]$fromLine) {
    # 注意：注入诊断行是走 stderr 的（⚠ 警告都走 stderr），两份都要看
    $all = @(Get-Content -LiteralPath $logPath -Encoding UTF8)
    if (Test-Path "$logPath.err") { $all += @(Get-Content -LiteralPath "$logPath.err" -Encoding UTF8) }
    $lines = @($all | Select-Object -Skip $fromLine)
    $healed = $false; $healCount = 0; $healOffset = 0; $audioSeekCorrect = $false
    $before = @(); $after = @()
    foreach ($l in $lines) {
        if ($l -match '已把\*\*时钟\*\*重锚到音频内容') {
            $healed = $true; $healCount++
            if ($l -match '音画偏移 ([+-][\d]+)ms') { $healOffset = [math]::Abs([int]$Matches[1]) }
            continue
        }
        if ($l -match '已把音频对齐到') { $audioSeekCorrect = $true }
        if ($l -match '可听=(-?\d+) 画面=(-?\d+)') {
            $skew = [double]$Matches[2] - [double]$Matches[1]
            if ($healed) { $after += $skew } else { $before += $skew }
        }
    }
    return [pscustomobject]@{
        healed = $healed; healCount = $healCount; healOffset = $healOffset
        audioSeekCorrect = $audioSeekCorrect; before = $before; after = $after
        injected = (($all -join "`n") -match '已把音频故意拖后')
    }
}

function MaxAbs($arr) {
    if ($arr.Count -eq 0) { return -1 }
    return ($arr | ForEach-Object { [math]::Abs($_) } | Measure-Object -Maximum).Maximum
}

# ---------------------------------------------------------------- A 误报检查
Write-Host "=== A 正常播放 ${BaselineSeconds}s：不该触发任何重锚 ==="
$logA = "$env:TEMP\cineduct-avheal-a.log"
$h = Start-Host @() 'cineduct-avhealA' 'cineduct_avhealA_status' $logA 'cineduct_avhealA_tex'
Start-Sleep -Seconds $BaselineSeconds
Stop-Host $h
$a = Analyze-Log $logA 0
$aMax = MaxAbs $a.before
$aCount = $a.before.Count
Write-Host ("  明细样本 {0} 条，最大 |可听−画面| = {1:N0}ms，重锚次数 = {2}" -f $aCount, $aMax, $a.healCount)
Write-Host ("[{0}] 正常播放不误报（{1} 次重锚，要求 0 次）" -f $(if ($a.healCount -eq 0) { 'ok' } else { 'FAIL' }), $a.healCount)
if ($a.healCount -ne 0) { $script:fail++ }
$okA = ($aMax -ge 0 -and $aMax -le $GoodMs)
Write-Host ("[{0}] 正常播放偏移本来就小（{1:N0}ms ≤ {2:N0}ms）" -f $(if ($okA) { 'ok' } else { 'FAIL' }), $aMax, $GoodMs)
if (-not $okA) { $script:fail++ }

# ---------------------------------------------------------------- B 真错位自愈
Write-Host ''
Write-Host "=== B 故意把音频拖后 ${LagMs}ms：默认路线（无 --av-correct）应把时钟重锚回来 ==="
$logB = "$env:TEMP\cineduct-avheal-b.log"
$h = Start-Host @('--debug-audio-lag', "$LagMs") 'cineduct-avhealB' 'cineduct_avhealB_status' $logB 'cineduct_avhealB_tex'
$baseline = 0
for ($i = 0; $i -lt ($HealSeconds * 2); $i++) {
    Start-Sleep -Milliseconds 500
    if ($i -eq 3) { $baseline = @(Get-Content -LiteralPath $logB -Encoding UTF8).Count }
    if ($i % 4 -eq 0) {
        $s = Send-Pipe $h 'status'
        $pos = -1; if ($s -match 'pos=(-?\d+)ms') { $pos = [int]$Matches[1] }
        Write-Host ("  t={0,4:N1}s 位置={1,6}ms" -f ($i * 0.5), $pos)
    }
}
Stop-Host $h
$b = Analyze-Log $logB $baseline
$bBefore = MaxAbs $b.before
$bAfter = MaxAbs $b.after
Write-Host ("  注入={0}；重锚 {1} 次（触发时偏移 {2:N0}ms）；重锚前最大偏移 {3:N0}ms → 重锚后最大 {4:N0}ms（样本 {5} 条）" -f `
    $b.injected, $b.healCount, $b.healOffset, $bBefore, $bAfter, $b.after.Count)
Write-Host ("[{0}] 诊断注入生效" -f $(if ($b.injected) { 'ok' } else { 'FAIL' }))
if (-not $b.injected) { $script:fail++ }
Write-Host ("[{0}] 真错位被发现并重锚（日志: 已把**时钟**重锚到音频内容）" -f $(if ($b.healed) { 'ok' } else { 'FAIL' }))
if (-not $b.healed) { $script:fail++ }
$okHeal = ($b.healed -and $b.healOffset -ge 400)
Write-Host ("[{0}] 触发重锚时偏移确实很大（{1:N0}ms ≥ 400ms）" -f $(if ($okHeal) { 'ok' } else { 'FAIL' }), $b.healOffset)
if (-not $okHeal) { $script:fail++ }
$okAfter = ($b.after.Count -ge 2 -and $bAfter -le $GoodMs)
Write-Host ("[{0}] 重锚之后偏移回到小范围（{1:N0}ms ≤ {2:N0}ms，样本 {3} 条）" -f $(if ($okAfter) { 'ok' } else { 'FAIL' }), $bAfter, $GoodMs, $b.after.Count)
if (-not $okAfter) { $script:fail++ }
Write-Host ("[{0}] 默认路线**没有**去 seek 声卡（不动音频内容）" -f $(if (-not $b.audioSeekCorrect) { 'ok' } else { 'FAIL' }))
if ($b.audioSeekCorrect) { $script:fail++ }

Write-Host ''
Write-Host '--- 相关日志（B 段） ---'
Get-Content -LiteralPath $logB -Encoding UTF8 | Select-String -Pattern '故意拖后|重锚|已把音频对齐|音画偏移' | ForEach-Object { '  ' + $_.Line }

Write-Host ''
if ($script:fail -eq 0) { Write-Host 'RESULT: PASS'; exit 0 }
Write-Host "RESULT: FAIL（$script:fail 项）"
exit 1

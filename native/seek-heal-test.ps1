# ============================================================================
# 定位自愈测试（"跳转之后变成假片尾"这一类问题的回归）
#
# 用的素材是 native/testdata/bbb-livepts.ts：它同时覆盖两件难事 ——
#   1. **PTS 不从 0 起**（start = 2345 秒，模拟直播流）：时间轴原点必须归一，
#      否则子进程会等 39 分钟才允许放第一帧；
#   2. **定位根本读不下去**：连 ffmpeg 自己的 `-ss 4` 都解不出帧
#      （解复用器的读指针被扔到文件末尾）。老代码遇到这种定位会把 EOF 当片尾上报，
#      用户看到的就是"点了跳转 → 播放结束"。
#
# 这一版要求：定位坏了要**自愈**（换单点定位 → 退回 2 秒 → 回起点），
# 而不是直接报片尾；位置必须继续推进。
#
# 用法： .\native\seek-heal-test.ps1
# ============================================================================
param(
    [string]$Exe = 'F:\mos\video\native\build\bin\cineduct-host.exe',
    [string]$FfmpegDir = 'F:\mos\video\run\cineduct-ffmpeg\v2-bundled',
    [string]$Url = 'F:\mos\video\native\testdata\bbb-livepts.ts',
    [string]$PipeName = 'cineduct-host-healtest',
    [string]$Log = 'F:\mos\video\native\build\seek-heal-test.log'
)

$ErrorActionPreference = 'Stop'
$script:failures = 0
function Report([bool]$ok, [string]$text) {
    if ($ok) { Write-Output "[ok] $text" } else { Write-Output "[FAIL] $text"; $script:failures++ }
}

$hostArgs = @(
    '--ffmpeg-dir', $FfmpegDir,
    '--url', $Url,
    '--output-textures', 'healtest_tex',
    '--status', 'healtest_status',
    '--volume', '0',
    '--pipe', $PipeName
)
$proc = Start-Process -FilePath $Exe -ArgumentList $hostArgs -RedirectStandardOutput $Log `
    -RedirectStandardError "$Log.err" -NoNewWindow -PassThru
Write-Output "[test] host pid=$($proc.Id)（素材：PTS 从 2345 秒起 + 定位读不下去的 TS）"

function Connect-Pipe([int]$TimeoutMs) {
    $pipe = New-Object System.IO.Pipes.NamedPipeClientStream('.', $PipeName, [System.IO.Pipes.PipeDirection]::InOut)
    $pipe.Connect($TimeoutMs)
    return $pipe
}
function Send-Command($pipe, [int]$reqId, [string]$payload) {
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($payload)
    $ms = New-Object System.IO.MemoryStream
    $bw = New-Object System.IO.BinaryWriter($ms)
    $bw.Write([uint32](2 + 4 + $bytes.Length)); $bw.Write([uint16]1); $bw.Write([uint32]$reqId); $bw.Write($bytes); $bw.Flush()
    $out = $ms.ToArray(); $pipe.Write($out, 0, $out.Length); $pipe.Flush()
    $hdr = New-Object byte[] 4; $read = 0
    while ($read -lt 4) { $n = $pipe.Read($hdr, $read, 4 - $read); if ($n -le 0) { throw 'pipe closed' }; $read += $n }
    $bodyLen = [System.BitConverter]::ToUInt32($hdr, 0); $body = New-Object byte[] $bodyLen; $read = 0
    while ($read -lt $bodyLen) { $n = $pipe.Read($body, $read, $bodyLen - $read); if ($n -le 0) { throw 'pipe closed' }; $read += $n }
    return [System.Text.Encoding]::UTF8.GetString($body, 6, $bodyLen - 6)
}
function Field([string]$status, [string]$name) {
    if ($status -match "$name=(-?\d+)") { return [int]$Matches[1] }
    return -999999
}

# ---------------------------------------------------------------------------
# 1) 非零 PTS 原点的流也要能正常起播（不是"握手 ok 但一直不出画面"）
# ---------------------------------------------------------------------------
Start-Sleep -Seconds 4
$pipe = Connect-Pipe 5000
$s1 = Send-Command $pipe 1 'status'
Write-Output "     $s1"
$pub1 = Field $s1 'published'
Report ($pub1 -ge 60) "非零 PTS 原点起播正常（4 秒发布 $pub1 帧）"

# ---------------------------------------------------------------------------
# 2) 定位到"读不下去"的位置：必须自愈，不能变成假片尾
# ---------------------------------------------------------------------------
$null = Send-Command $pipe 2 'seek 4000'
Start-Sleep -Seconds 2
$s2 = Send-Command $pipe 3 'status'
Write-Output "     $s2"
$ended2 = Field $s2 'ended'
$pub2 = Field $s2 'published'
Report ($ended2 -eq 0) "定位失败没有变成假片尾（ended=$ended2）"
Report ($pub2 -gt $pub1) "自愈之后画面继续出（发布 $pub1 -> $pub2 帧）"

Start-Sleep -Seconds 2
$s3 = Send-Command $pipe 4 'status'
Write-Output "     $s3"
$pub3 = Field $s3 'published'
$pos3 = Field $s3 'pos'
Report ($pub3 -gt $pub2 + 10) "位置持续推进（发布 $pub2 -> $pub3 帧，位置 ${pos3}ms）"

# ---------------------------------------------------------------------------
# 3) 日志里必须留下"自愈发生过"的证据（否则下次出问题无从下手）
# ---------------------------------------------------------------------------
$healed = @()
foreach ($file in @($Log, "$Log.err")) {
    if (Test-Path -LiteralPath $file) {
        $healed += Get-Content -LiteralPath $file -Encoding UTF8 | Select-String -Pattern '⚠'
    }
}
Report ($healed.Count -ge 1) "日志里有定位自愈的警告行（$($healed.Count) 条）"
$healed | Select-Object -First 3 | ForEach-Object { "     " + $_.Line }

$null = Send-Command $pipe 9 'quit'
$pipe.Close()
$exited = $proc.WaitForExit(5000)
if (-not $exited) { $proc.Kill() }
Report $exited "quit 后进程在 5 秒内退出"

Write-Output ''
if ($script:failures -eq 0) { Write-Output 'RESULT: PASS'; exit 0 }
Write-Output "RESULT: FAIL ($($script:failures) 项)"
exit 1

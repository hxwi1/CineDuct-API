# ============================================================================
# EOF / 自愈行为测试（ASCII only：Windows PowerShell 5.1 读无 BOM 的 UTF-8 会乱码）
#
# 验证三件事（客户端"崩溃自动重启"依赖它们）：
#   1. 播放到片尾后**子进程不退出**（否则客户端会把"放完"当成"崩溃"而无限重播）；
#   2. 片尾状态上报 ended=1，且最后一帧被反复发布（屏幕不会变黑）；
#   3. 片尾待命时收到 seek 会回到解码循环继续播；loop 1 则回卷重播。
#
# 用法： .\native\eof-test.ps1
# ============================================================================
param(
    [string]$Exe = 'F:\mos\video\native\build\bin\cineduct-host.exe',
    [string]$FfmpegDir = 'F:\mos\video\run\cineduct-ffmpeg\v2-bundled',
    [string]$Url = 'F:\mos\video\native\testdata\bbb.mp4',
    [string]$PipeName = 'cineduct-host-eoftest',
    [string]$Log = 'F:\mos\video\native\build\eof-test.log'
)

$ErrorActionPreference = 'Stop'

$hostArgs = @(
    '--ffmpeg-dir', $FfmpegDir,
    '--url', $Url,
    '--output-textures', 'eoftest_tex',
    '--status', 'eoftest_status',
    '--slots', '3',
    '--volume', '0',
    '--pipe', $PipeName
)
$proc = Start-Process -FilePath $Exe -ArgumentList $hostArgs -RedirectStandardOutput $Log `
    -RedirectStandardError "$Log.err" -NoNewWindow -PassThru
Write-Output "[test] host pid=$($proc.Id)，等待播放到片尾（素材约 10 秒）..."

function Connect-Pipe([int]$TimeoutMs) {
    $pipe = New-Object System.IO.Pipes.NamedPipeClientStream('.', $PipeName, [System.IO.Pipes.PipeDirection]::InOut)
    $pipe.Connect($TimeoutMs)
    return $pipe
}

function Send-Command($pipe, [int]$reqId, [string]$payload) {
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($payload)
    $len = 2 + 4 + $bytes.Length
    $ms = New-Object System.IO.MemoryStream
    $bw = New-Object System.IO.BinaryWriter($ms)
    $bw.Write([uint32]$len)
    $bw.Write([uint16]1)
    $bw.Write([uint32]$reqId)
    $bw.Write($bytes)
    $bw.Flush()
    $out = $ms.ToArray()
    $pipe.Write($out, 0, $out.Length)
    $pipe.Flush()

    $hdr = New-Object byte[] 4
    $read = 0
    while ($read -lt 4) { $n = $pipe.Read($hdr, $read, 4 - $read); if ($n -le 0) { throw 'pipe closed' }; $read += $n }
    $bodyLen = [System.BitConverter]::ToUInt32($hdr, 0)
    $body = New-Object byte[] $bodyLen
    $read = 0
    while ($read -lt $bodyLen) { $n = $pipe.Read($body, $read, $bodyLen - $read); if ($n -le 0) { throw 'pipe closed' }; $read += $n }
    return [System.Text.Encoding]::UTF8.GetString($body, 6, $bodyLen - 6)
}

$pipe = Connect-Pipe 5000

# ---------------------------------------------------------------------------
# 等片尾：改成**轮询**，不再用固定 13 秒。
#
# 固定等待本来就是碰运气：10 秒素材 + 子进程启动（加载 FFmpeg / 打开输入 / 首帧解码）
# 通常只要 2~3 秒，但机器忙、上游 CDN 慢时会拖到 4 秒以上 —— 那时 13 秒只够播到 9.5 秒，
# 断言就会假失败（本次重构就撞上了）。轮询直到 ended=1（最多 40 秒）测的是同一件事，
# 但对启动耗时不再敏感。
# ---------------------------------------------------------------------------
$deadline = (Get-Date).AddSeconds(40)
$status1 = ''
while ((Get-Date) -lt $deadline) {
    $status1 = Send-Command $pipe 1 'status'
    if ($status1 -match 'ended=1') { break }
    Start-Sleep -Milliseconds 500
}

if ($proc.HasExited) {
    Write-Output "[FAIL] 片尾后子进程已退出（exit=$($proc.ExitCode)）—— 客户端会把它当成崩溃"
} else {
    Write-Output "[ok] 片尾后子进程仍存活"
}

Write-Output "     $status1"
if ($status1 -match 'ended=1') { Write-Output '[ok] 已上报 ended=1' } else { Write-Output '[FAIL] 没有上报 ended=1' }

# 片尾待命 -> seek：应当回到解码循环继续播
$null = Send-Command $pipe 2 'seek 2000'
Start-Sleep -Seconds 3
$status2 = Send-Command $pipe 3 'status'
Write-Output "     $status2"
if ($status2 -match 'ended=0') { Write-Output '[ok] seek 后已退出片尾待命' } else { Write-Output '[FAIL] seek 后仍停在片尾' }

# 打开循环：再 seek 回 0，应当能一路播到自然回卷
$null = Send-Command $pipe 4 'loop 1'
$null = Send-Command $pipe 5 'seek 0'
Start-Sleep -Seconds 13
$status3 = Send-Command $pipe 6 'status'
Write-Output "     $status3"
if ($status3 -match 'ended=0' -and $status3 -match 'pos=(\d+)') {
    Write-Output "[ok] loop 打开后回卷重播（pos=$($Matches[1])ms 且未停在片尾）"
} else {
    Write-Output '[FAIL] loop 打开后没有回卷'
}

# ---------------------------------------------------------------------------
# 暂停 + seek：这是"死锁"的经典触发组合。
# 暂停后声卡不再排空，音频线程会永久卡在背压里；如果 seek 还让视频线程去等它，
# 整个子进程就死锁（没声音 + 画面停在 1~2 帧，游戏却完全正常）。
# 正确行为：管道立刻回执；恢复播放后画面继续推进。
# ---------------------------------------------------------------------------
$null = Send-Command $pipe 7 'pause 1'
Start-Sleep -Milliseconds 400
# 暂停期间画面/时钟必须**真的停住**：以前这里只影响服务端权威位置，本地照样播，
# 于是进度校准每秒把画面拽回去 = "暂停之后画面和声音一直在原地重播、进度一直在校准"。
$p1 = Send-Command $pipe 20 'status'
Start-Sleep -Seconds 3
$p2 = Send-Command $pipe 21 'status'
Write-Output "     $p1"
Write-Output "     $p2"
$pubA = 0; $pubB = 0; $posA = 0; $posB = 0
if ($p1 -match 'published=(\d+)') { $pubA = [int]$Matches[1] }
if ($p2 -match 'published=(\d+)') { $pubB = [int]$Matches[1] }
if ($p1 -match 'pos=(-?\d+)ms') { $posA = [int]$Matches[1] }
if ($p2 -match 'pos=(-?\d+)ms') { $posB = [int]$Matches[1] }
if ($pubB -eq $pubA -and $posB -eq $posA) {
    Write-Output "[ok] 暂停后画面与时钟都真的停住（发布 $pubA -> $pubB 帧，位置 $posA -> $posB ms）"
} else {
    Write-Output "[FAIL] 暂停后仍在推进（发布 $pubA -> $pubB 帧，位置 $posA -> $posB ms）"
}

$seekReply = Send-Command $pipe 8 'seek 5000'
Write-Output "     暂停中 seek 的回执: $seekReply"
Start-Sleep -Seconds 2
$pausedStatus = Send-Command $pipe 9 'status'
Write-Output "     $pausedStatus"
$pausedPublished = 0
if ($pausedStatus -match 'published=(\d+)') { $pausedPublished = [int]$Matches[1] }
if ($seekReply -match '^ok') {
    Write-Output '[ok] 暂停中 seek 立刻回执（控制通道没有被堵死）'
} else {
    Write-Output '[FAIL] 暂停中 seek 没有正常回执'
}

$null = Send-Command $pipe 10 'pause 0'
Start-Sleep -Seconds 4
$resumedStatus = Send-Command $pipe 11 'status'
Write-Output "     $resumedStatus"
$resumedPublished = 0
if ($resumedStatus -match 'published=(\d+)') { $resumedPublished = [int]$Matches[1] }
if ($resumedPublished -gt $pausedPublished + 10) {
    Write-Output "[ok] 恢复后画面继续推进（$pausedPublished -> $resumedPublished 帧）—— 没有死锁"
} else {
    Write-Output "[FAIL] 恢复后画面没有推进（$pausedPublished -> $resumedPublished 帧）—— 疑似死锁"
}

$null = Send-Command $pipe 12 'quit'
$pipe.Close()
Start-Sleep -Seconds 2
Write-Output "[ok] 退出后进程状态: HasExited=$($proc.HasExited)"
Get-Content -LiteralPath $Log | Select-String -Pattern '播放结束|回卷重播|seek ->' | ForEach-Object { "     log: $($_.Line)" }

# ============================================================================
# 流水线行为测试（v2：demux / 解码 / 呈现三线程 + seek 三阶段握手）
#
# 与 eof-test.ps1 互补：那个测的是"片尾 / 回卷 / 暂停冻结"这些**老约定**，
# 这个测的是拆线程之后新增/易坏的几件事：
#   1. 出帧节奏真的是按主时钟走的（不是"能多快解多快"）
#   2. seek 落点准（定位到目标附近，不是回到 0 或停住）
#   3. **seek 风暴**：连点 12 次不同目标，最后必须停在第 12 个目标上且继续播
#      （三阶段握手 + 世代号就是为这个场景设计的：队列要清、解码器要 flush、时钟要重锚）
#   4. 收尾：quit 之后进程必须能自己退出（线程/队列没有死等）
#
# 用法： .\native\pipeline-test.ps1
# ============================================================================
param(
    [string]$Exe = 'F:\mos\video\native\build\bin\cineduct-host.exe',
    [string]$FfmpegDir = 'F:\mos\video\run\cineduct-ffmpeg\v2-bundled',
    [string]$Url = 'F:\mos\video\native\testdata\bbb.mp4',
    [string]$PipeName = 'cineduct-host-pipetest',
    [string]$Log = 'F:\mos\video\native\build\pipeline-test.log'
)

$ErrorActionPreference = 'Stop'
$script:failures = 0

function Report([bool]$ok, [string]$text) {
    if ($ok) {
        Write-Output "[ok] $text"
    } else {
        Write-Output "[FAIL] $text"
        $script:failures++
    }
}

$hostArgs = @(
    '--ffmpeg-dir', $FfmpegDir,
    '--url', $Url,
    '--output-textures', 'pipetest_tex',
    '--status', 'pipetest_status',
    '--slots', '3',
    '--volume', '0',
    '--pipe', $PipeName
)
$proc = Start-Process -FilePath $Exe -ArgumentList $hostArgs -RedirectStandardOutput $Log `
    -RedirectStandardError "$Log.err" -NoNewWindow -PassThru
Write-Output "[test] host pid=$($proc.Id)（素材 10 秒 25fps）"

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

function Field([string]$status, [string]$name) {
    if ($status -match "$name=(-?\d+)") { return [int]$Matches[1] }
    return -999999
}

# ---------------------------------------------------------------------------
# 1) 出帧节奏：25fps 的素材，跑 4 秒应该出 ~100 帧（而不是 0 帧，也不是几百帧）
# ---------------------------------------------------------------------------
Start-Sleep -Seconds 4
$pipe = Connect-Pipe 5000
$s1 = Send-Command $pipe 1 'status'
Write-Output "     $s1"
$published = Field $s1 'published'
Report ($published -ge 70 -and $published -le 140) "出帧节奏正常（4 秒发布 $published 帧，期望 70~140）"

# ---------------------------------------------------------------------------
# 2) seek 落点：跳到 3000ms，1.5 秒之后位置应该在 4500ms 附近（±500）
# ---------------------------------------------------------------------------
$null = Send-Command $pipe 2 'seek 3000'
Start-Sleep -Milliseconds 1500
$s2 = Send-Command $pipe 3 'status'
Write-Output "     $s2"
$pos2 = Field $s2 'pos'
Report ($pos2 -ge 3800 -and $pos2 -le 5200) "seek 落点正确（目标 3000ms，1.5 秒后 pos=${pos2}ms，期望 3800~5200）"

# ---------------------------------------------------------------------------
# 3) seek 风暴：连续 12 次不同目标（间隔 60ms，比一次定位还快）
#    最后必须停在最后一个目标（6000ms）上并继续播
# ---------------------------------------------------------------------------
for ($i = 1; $i -le 12; $i++) {
    $null = Send-Command $pipe (10 + $i) ("seek " + ($i * 500))
    Start-Sleep -Milliseconds 60
}
Start-Sleep -Seconds 2
$s3 = Send-Command $pipe 30 'status'
Write-Output "     $s3"
$pos3 = Field $s3 'pos'
$pub3 = Field $s3 'published'
Report ($pos3 -ge 7000 -and $pos3 -le 9200) "seek 风暴后停在最后目标（目标 6000ms，2 秒后 pos=${pos3}ms，期望 7000~9200）"

$s4 = Send-Command $pipe 31 'status'
Start-Sleep -Seconds 1
$s5 = Send-Command $pipe 32 'status'
$pub5 = Field $s5 'published'
Report ($pub5 -gt $pub3 + 10) "风暴之后仍在正常出帧（$pub3 -> $pub5）"

# ---------------------------------------------------------------------------
# 5) 状态块跨语言契约：Java 侧按**固定偏移**读，所以这里直接把共享内存映射过来核对一遍。
#    最容易悄悄错的就是这一处（C++ 改了结构体、Java 没跟着改 → 字段读到垃圾或恒 0）。
# ---------------------------------------------------------------------------
try {
    $mmf = [System.IO.MemoryMappedFiles.MemoryMappedFile]::OpenExisting('pipetest_status')
    $acc = $mmf.CreateViewAccessor()
    $magic = $acc.ReadUInt32(0)
    $version = $acc.ReadUInt32(4)
    $decodeMode = $acc.ReadUInt32(372)
    $buffering = $acc.ReadUInt32(376)
    $seekHeal = $acc.ReadUInt32(380)
    $droppedLate = $acc.ReadUInt64(384)
    $videoPts = $acc.ReadUInt64(392)
    $clockHeal = $acc.ReadUInt32(400)
    $acc.Dispose(); $mmf.Dispose()
    Report ($magic -eq 0x4D465850) "状态块 magic 正确（0x$('{0:X8}' -f $magic)）"
    Report ($version -eq 6) "状态块版本为 6（v4/v5/v6 都是只在末尾追加，老偏移不变）"
    Report ($decodeMode -ge 1 -and $decodeMode -le 3) "decodeMode 取值合法（$decodeMode）"
    Report ($videoPts -gt 0) "videoPtsMs 可读（$videoPts ms —— 音画偏移就靠它算）"
    Report ($clockHeal -eq 0) "clockHealCount 可读且正常播放为 0（$clockHeal —— 音画偏移自愈次数）"    Write-Output "     buffering=$buffering seekHeal=$seekHeal clockHeal=$clockHeal droppedLate=$droppedLate"
} catch {
    Report $false "状态块契约核对失败: $_"
}

# ---------------------------------------------------------------------------
# 6) 收尾：quit 之后进程必须自己退出（线程与队列都不能死等）
# ---------------------------------------------------------------------------
$null = Send-Command $pipe 40 'quit'
$pipe.Close()
$exited = $proc.WaitForExit(5000)
if (-not $exited) { $proc.Kill(); }
Report $exited "quit 后进程在 5 秒内退出（否则说明有线程死等）"

Write-Output ''
if ($script:failures -eq 0) {
    Write-Output 'RESULT: PASS'
    exit 0
} else {
    Write-Output "RESULT: FAIL ($($script:failures) 项)"
    exit 1
}

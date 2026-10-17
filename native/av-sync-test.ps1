param(
    [string]$Exe = 'F:\mos\video\native\build\bin\cineduct-host.exe',
    [string]$FfmpegDir = 'F:\mos\video\run\cineduct-ffmpeg\v2-bundled',
    [string]$Url = 'F:\mos\video\native\testdata\bbb.mp4',
    [int]$LagMs = 700,
    [string]$PipeName = 'cineduct-avsync',
    [string]$Status = 'cineduct_avsync_status',
    [string]$Log = "$env:TEMP\cineduct-avsync.log"
)
$ErrorActionPreference = 'Stop'
$hostArgs = @('--ffmpeg-dir', $FfmpegDir, '--url', $Url, '--output-textures', 'cineduct_avsync_tex',
              '--status', $Status, '--volume', '0', '--pipe', $PipeName,
              '--debug-audio-lag', "$LagMs", '--av-correct')
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
$script:acc = $null
function Samples() {
    if ($script:acc -eq $null) {
        $mmf = [System.IO.MemoryMappedFiles.MemoryMappedFile]::OpenExisting($Status)
        $script:acc = $mmf.CreateViewAccessor()
    }
    return $script:acc
}
Write-Host "[test] 起播后故意把音频拖后 $LagMs ms，看纠正通道是否把它拉回来"
$offsets = @()
for ($i = 0; $i -lt 26; $i++) {
    Start-Sleep -Milliseconds 400
    $s = Send 'status'
    $pos = -1; if ($s -match 'pos=(-?\d+)ms') { $pos = [int]$Matches[1] }
    $acc = Samples
    $v = [int64]$acc.ReadUInt64(392)
    $off = $v - $pos
    $offsets += [pscustomobject]@{ t = ($i * 0.4); pos = $pos; video = $v; off = $off }
    if ($i % 3 -eq 0) { Write-Host ("  t={0,4:N1}s 画面={1,6}ms 位置={2,6}ms 偏移={3,6}ms" -f ($i * 0.4), $v, $pos, $off) }
}
$null = Send 'quit'
$pipe.Close()
[void]$proc.WaitForExit(4000)

$text = ''
foreach ($f in @($Log, "$Log.err")) { if (Test-Path $f) { $text += (Get-Content -LiteralPath $f -Encoding UTF8 | Out-String) } }
$injected = $text -match '已把音频故意拖后'
$corrected = $text -match '已把音频对齐到'
$aligned = $text -match '首帧对表：音频链已重头开始'
# 从"AV明细"里取 可听 / 画面 两组数字：起播是否对齐的关键证据
$detail = [regex]::Matches($text, '可听=(-?[\d]+) 画面=(-?[\d]+)')
$firstSkew = -1
if ($detail.Count -gt 0) {
    $firstSkew = [math]::Abs([double]$detail[0].Groups[1].Value - [double]$detail[0].Groups[2].Value)
}
$maxOffset = 0
foreach ($m in [regex]::Matches($text, '音画偏移 ([+-][\d]+)ms')) {
    $v = [math]::Abs([int]$m.Groups[1].Value)
    if ($v -gt $maxOffset) { $maxOffset = $v }
}
Write-Host ''
Write-Host ("起播首次偏移（可听内容 vs 画面）：{0:N0}ms；运行期最大偏移 {1:N0}ms" -f $firstSkew, $maxOffset)
Write-Host ("[{0}] 首帧对表走的是**重启链路**（确定性对齐）" -f $(if ($aligned) { 'ok' } else { 'FAIL' }))
Write-Host ("[{0}] 起播就对齐（{1:N0}ms ≤ 150ms）" -f $(if ($firstSkew -ge 0 -and $firstSkew -le 150) { 'ok' } else { 'FAIL' }), $firstSkew)
Write-Host ("[{0}] 诊断注入生效（日志: 已把音频故意拖后）" -f $(if ($injected) { 'ok' } else { 'FAIL' }))
Write-Host ("[{0}] 偏移超过阈值被发现（最大 {1:N0}ms > 400ms）" -f $(if ($maxOffset -gt 400) { 'ok' } else { 'FAIL' }), $maxOffset)
Write-Host ("[{0}] 纠正通道生效（日志: 已把音频对齐到）" -f $(if ($corrected) { 'ok' } else { 'FAIL' }))
Write-Host ''
Write-Host '--- 相关日志 ---'
Get-Content -LiteralPath $Log -Encoding UTF8 | Select-String -Pattern '故意拖后|音画偏移|已把音频对齐|音频已定位' | ForEach-Object { '  ' + $_.Line }
Get-Content -LiteralPath "$Log.err" -Encoding UTF8 -ErrorAction SilentlyContinue | Select-String -Pattern '故意拖后|音画偏移|已把音频对齐' | ForEach-Object { '  ' + $_.Line }

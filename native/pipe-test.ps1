# 控制通道测试：直接用 .NET 命名管道按协议发命令，验证子进程的回执。
# 用法：.\native\pipe-test.ps1 -PipeName cineduct-host-test
param(
    [string]$PipeName = 'cineduct-host-test',
    [string[]]$Commands = @('hello 1', 'status', 'pause 1', 'volume 40', 'status', 'pause 0', 'quit')
)

$ErrorActionPreference = 'Stop'
$pipe = New-Object System.IO.Pipes.NamedPipeClientStream('.', $PipeName, [System.IO.Pipes.PipeDirection]::InOut)
$pipe.Connect(5000)
Write-Output "[pipe] 已连接 $PipeName"

function Send-Command([int]$reqId, [string]$payload) {
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($payload)
    $len = 2 + 4 + $bytes.Length
    $ms = New-Object System.IO.MemoryStream
    $bw = New-Object System.IO.BinaryWriter($ms)
    $bw.Write([uint32]$len)
    $bw.Write([uint16]1)          # 1 = 请求
    $bw.Write([uint32]$reqId)
    $bw.Write($bytes)
    $bw.Flush()
    $out = $ms.ToArray()
    $script:pipe.Write($out, 0, $out.Length)
    $script:pipe.Flush()

    $hdr = New-Object byte[] 4
    $read = 0
    while ($read -lt 4) { $n = $script:pipe.Read($hdr, $read, 4 - $read); if ($n -le 0) { throw '管道已关闭' }; $read += $n }
    $bodyLen = [System.BitConverter]::ToUInt32($hdr, 0)
    $body = New-Object byte[] $bodyLen
    $read = 0
    while ($read -lt $bodyLen) { $n = $script:pipe.Read($body, $read, $bodyLen - $read); if ($n -le 0) { throw '管道已关闭' }; $read += $n }

    $type = [System.BitConverter]::ToUInt16($body, 0)
    $id = [System.BitConverter]::ToUInt32($body, 2)
    $text = [System.Text.Encoding]::UTF8.GetString($body, 6, $bodyLen - 6)
    Write-Output ("  >>> {0}" -f $payload)
    Write-Output ("  <<< [type={0} id={1}] {2}" -f $type, $id, $text)
}

$i = 1
foreach ($c in $Commands) {
    Send-Command $i $c
    $i++
    if ($c -eq 'quit') { break }
}
$pipe.Close()

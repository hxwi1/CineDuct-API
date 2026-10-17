# RCON 客户端：用来在无窗口的前提下把 /mediafix screen 指令真正跑一遍
param(
    [string]$Password = 'mediafixtest',
    [string[]]$Commands
)

$ErrorActionPreference = 'Stop'
$client = New-Object System.Net.Sockets.TcpClient('127.0.0.1', 25575)
$stream = $client.GetStream()

function Write-Packet([int]$id, [int]$type, [string]$body) {
    $bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($body)
    $len = 4 + 4 + $bodyBytes.Length + 2
    $ms = New-Object System.IO.MemoryStream
    $bw = New-Object System.IO.BinaryWriter($ms)
    $bw.Write([int]$len)
    $bw.Write([int]$id)
    $bw.Write([int]$type)
    $bw.Write($bodyBytes)
    $bw.Write([byte]0)
    $bw.Write([byte]0)
    $bw.Flush()
    $bytes = $ms.ToArray()
    $script:stream.Write($bytes, 0, $bytes.Length)
    $script:stream.Flush()
}

function Read-Exact([int]$count) {
    $buf = New-Object byte[] $count
    $read = 0
    while ($read -lt $count) {
        $n = $script:stream.Read($buf, $read, $count - $read)
        if ($n -le 0) { throw 'RCON 连接关闭' }
        $read += $n
    }
    return $buf
}

function Read-Packet {
    $lenBytes = Read-Exact 4
    $len = [System.BitConverter]::ToInt32($lenBytes, 0)
    $rest = Read-Exact $len
    $id = [System.BitConverter]::ToInt32($rest, 0)
    $type = [System.BitConverter]::ToInt32($rest, 4)
    $body = [System.Text.Encoding]::UTF8.GetString($rest, 8, $len - 10)
    return [pscustomobject]@{ Id = $id; Type = $type; Body = $body }
}

# 1) 认证
Write-Packet 1 3 $Password
$auth = Read-Packet
if ($auth.Id -eq -1) { throw "RCON 认证失败" }
Write-Output "[rcon] 认证成功"

# 2) 逐条执行
$i = 2
foreach ($cmd in $Commands) {
    Write-Packet $i 2 $cmd
    $resp = Read-Packet
    Write-Output ">>> $cmd"
    foreach ($line in ($resp.Body -split "`n")) {
        if ($line.Trim().Length -gt 0) { Write-Output "    $line" }
    }
    $i++
}

$client.Close()

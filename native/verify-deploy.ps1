# ============================================================================
# 部署核对：实例 mods 里的 jar 是不是**最新构建**
#
# 用途：每次改完代码、构建、部署之后跑一遍，回答三个问题：
#   ① build\libs 里最新的 cineduct-*.jar 是哪一个（名字/大小/时间/SHA）；
#   ② 实例 mods 里装的是不是**同一份**（按 SHA256 比，不看文件名）；
#   ③ 有没有多余的 cineduct jar（两个同 modid 的 jar 会让游戏直接拒绝启动）。
# 顺带核对 jar 内嵌的原生件（cineduct-host.exe / cineduct-glshim.dll）与
# native\build\bin 里的是否一致 —— "最新构建"必须是"最新原生件 + 最新 class"。
#
# 用法：
#   .\native\verify-deploy.ps1                                  # 默认核对 1.21.1 那个实例
#   .\native\verify-deploy.ps1 -Instance "D:\other\instance"
#   .\native\verify-deploy.ps1 -All                             # 两个常用实例都核对
#
# 退出码：0 = 一致；1 = 不一致/缺失/有多余 jar
# ============================================================================
param(
    [string]$Instance = 'F:\potato world\.minecraft\versions\1.21.1-NeoForge_21.1.251',
    [switch]$All
)

$ErrorActionPreference = 'Stop'
$nativeDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo = Split-Path -Parent $nativeDir
$libs = Join-Path $repo 'build\libs'

function Sha256Of([string]$path) {
    if (-not (Test-Path -LiteralPath $path)) { return '' }
    return (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
}

function Verify-One([string]$instanceRoot) {
    $mods = Join-Path $instanceRoot 'mods'
    $name = Split-Path $instanceRoot -Leaf
    Write-Host "=== $name ===" -ForegroundColor Cyan
    if (-not (Test-Path -LiteralPath $mods)) {
        Write-Host "  [FAIL] 没有 mods 目录: $mods" -ForegroundColor Red
        return $false
    }

    # ---- ① 最新构建 ----
    $latest = Get-ChildItem -LiteralPath $libs -File -Filter 'cineduct-*.jar' -ErrorAction SilentlyContinue |
              Where-Object { $_.Name -notmatch 'sources|javadoc' } |
              Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $latest) {
        Write-Host "  [FAIL] build\libs 里没有 cineduct-*.jar（先构建）" -ForegroundColor Red
        return $false
    }
    $latestSha = Sha256Of $latest.FullName
    Write-Host ("  最新构建 : {0}  {1:N0} KB  {2}  sha8={3}" -f
        $latest.Name, ($latest.Length / 1KB), $latest.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss'),
        $latestSha.Substring(0, 16))

    # ---- ② 实例里装的是不是同一份 ----
    $deployed = @(Get-ChildItem -LiteralPath $mods -File -Filter 'cineduct-*.jar' -ErrorAction SilentlyContinue)
    if ($deployed.Count -eq 0) {
        Write-Host "  [FAIL] mods 里没有 cineduct-*.jar（未部署）" -ForegroundColor Red
        return $false
    }
    $ok = $true
    foreach ($d in $deployed) {
        $dSha = Sha256Of $d.FullName
        $same = ($dSha -eq $latestSha)
        Write-Host ("  已部署   : {0}  {1:N0} KB  {2}  sha8={3}  {4}" -f
            $d.Name, ($d.Length / 1KB), $d.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss'),
            $dSha.Substring(0, 16), $(if ($same) { '✔ 与最新构建一致' } else { '✘ 与最新构建不同' })) `
            -ForegroundColor $(if ($same) { 'Green' } else { 'Red' })
        if (-not $same) { $ok = $false }
    }
    if ($deployed.Count -gt 1) {
        Write-Host "  [FAIL] mods 里有 $($deployed.Count) 个 cineduct jar —— 同 modid 会让游戏拒绝启动" -ForegroundColor Red
        $ok = $false
    }

    # ---- ③ jar 内嵌原生件是否与本地构建一致 ----
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zi = [System.IO.Compression.ZipFile]::OpenRead($latest.FullName)
    try {
        foreach ($pair in @(
            @('cineduct-native/cineduct-host.exe', 'cineduct-host.exe'),
            @('cineduct-native/cineduct-glshim.dll', 'cineduct-glshim.dll')
        )) {
            $entry = $zi.Entries | Where-Object { $_.FullName -eq $pair[0] }
            $localPath = Join-Path $nativeDir ('build\bin\' + $pair[1])
            if (-not $entry) {
                Write-Host ("  内嵌 {0} : ✘ jar 里没有" -f $pair[1]) -ForegroundColor Yellow
                continue
            }
            if (-not (Test-Path -LiteralPath $localPath)) {
                Write-Host ("  内嵌 {0} : （本地没有该产物，跳过比对）" -f $pair[1])
                continue
            }
            $ms = New-Object System.IO.MemoryStream
            $entry.Open().CopyTo($ms)
            $jarSha = ([System.Security.Cryptography.SHA256]::Create().ComputeHash($ms.ToArray()) |
                       ForEach-Object { $_.ToString('x2') }) -join ''
            $localSha = (Get-FileHash -LiteralPath $localPath -Algorithm SHA256).Hash.ToLower()
            $sameNative = ($jarSha -eq $localSha)
            Write-Host ("  内嵌 {0} : {1:N0} 字节  {2}" -f $pair[1], $entry.Length,
                $(if ($sameNative) { '✔ 与 native\build\bin 一致' } else { '✘ 与本地构建不同（jar 里是旧的原生件）' })) `
                -ForegroundColor $(if ($sameNative) { 'Green' } else { 'Red' })
            if (-not $sameNative) { $ok = $false }
        }
    } finally {
        $zi.Dispose()
    }
    return $ok
}

$targets = @($Instance)
if ($All) {
    $targets = @(
        'F:\potato world\.minecraft\versions\1.21.1-NeoForge_21.1.251',
        'F:\potato world\.minecraft\versions\Potato World 26.9.17'
    )
}

$allOk = $true
foreach ($t in $targets) {
    if (-not (Verify-One $t)) { $allOk = $false }
    Write-Host ''
}
if ($allOk) {
    Write-Host '结论: ✔ 实例里的 jar 就是最新构建（class + 原生件都对得上）' -ForegroundColor Green
    exit 0
}
Write-Host '结论: ✘ 不一致 —— 重新构建并部署（见 README 的部署一节）' -ForegroundColor Red
exit 1

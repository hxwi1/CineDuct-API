# ============================================================================
# 构建 MediaFix 原生部分（P1 打样）
#
#   cineduct-glshim.dll     游戏进程内的 D3D11 垫片
#   cineduct-spike-host.exe P1 打样的生产者（共享纹理 + 动画帧）
#   以及把 Java 侧的打样消费者编译到 build/java-classes
#
# 工具链：VS 2026 Build Tools（自带 CMake + Ninja）。cmake 不在 PATH 也没关系，
# 脚本会用 vswhere 找到 VS 自带的那个。
#
# 用法：
#   .\native\build.ps1
#   .\native\build.ps1 -Clean          # 清掉 build 目录重建
#   .\native\build.ps1 -NoJava         # 只编原生
# ============================================================================
param(
    [switch]$Clean,
    [switch]$Incremental,
    [switch]$NoJava,
    [string]$Config = 'Release'
)

$ErrorActionPreference = 'Stop'
$nativeDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo = Split-Path -Parent $nativeDir
$buildDir = Join-Path $nativeDir 'build'

Write-Host '=== MediaFix 原生构建 ===' -ForegroundColor Cyan

# ---- 1) 找 VS（带 VC 工具集） ----
$vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer\vswhere.exe'
if (-not (Test-Path -LiteralPath $vswhere)) { throw "找不到 vswhere: $vswhere" }
$vsPath = & $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if (-not $vsPath) { throw '找不到带 VC 工具集的 Visual Studio' }
$vcvars = Join-Path $vsPath 'VC\Auxiliary\Build\vcvars64.bat'
if (-not (Test-Path -LiteralPath $vcvars)) { throw "找不到 vcvars64.bat: $vcvars" }
Write-Host "[1/4] VS: $vsPath"

# ---- 2) 找 cmake / ninja（优先 VS 自带的） ----
$cmake = Join-Path $vsPath 'Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe'
$ninja = Join-Path $vsPath 'Common7\IDE\CommonExtensions\Microsoft\CMake\Ninja\ninja.exe'
if (-not (Test-Path -LiteralPath $cmake)) {
    $cmake = (Get-Command cmake -ErrorAction SilentlyContinue).Source
}
if (-not (Test-Path -LiteralPath $ninja)) {
    $ninja = (Get-Command ninja -ErrorAction SilentlyContinue).Source
}
if (-not $cmake) { throw '找不到 cmake（可用 VS 安装器补 CMake 组件）' }
if (-not $ninja) { throw '找不到 ninja（可用 VS 安装器补 CMake 组件）' }
Write-Host "[2/4] cmake: $cmake"
Write-Host "      ninja: $ninja"

# ---- 3) JAVA_HOME（find_package(JNI) 需要） ----
$javac = (Get-Command javac -ErrorAction SilentlyContinue).Source
if (-not $javac -and $env:JAVA_HOME) { $javac = Join-Path $env:JAVA_HOME 'bin\javac.exe' }
if (-not $javac) { throw '找不到 javac：请把 JDK 加到 PATH 或设置 JAVA_HOME' }
$env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $javac)
Write-Host "[3/4] JAVA_HOME: $($env:JAVA_HOME)"

# 让 MSVC 说英文（VSLANG 真正生效的位置在下面 _build.bat 里、vcvars 之后；
# 中文版 vcvars64.bat 会把 VSLANG 设回 2052，在这里设没用）。
$env:VSLANG = '1033'

# ★ 默认**全量重建**（-Incremental 可关掉，但要清楚风险）。
#
# 原因：CMake 4.3 给 Ninja 生成的是 `CXX_COMPILER__*_unscanned_*` 规则，规则里
# **没有 deps = msvc 那一行** —— `ninja -t deps` 显示 `#deps 0`，头文件依赖根本没被记录。
# 后果是改了 .h 之后对应 .cpp 不会重编：轻则"改动没生效"，重则两个编译单元看到的结构体
# 布局不一致 → 函数指针表错位 → 调野指针崩溃（本工程早期调试就是这么栽的，排查代价极高）。
# 这十几条 C++ 文件全量重编只要几十秒，用时间换确定性很值。
if (-not $Incremental -and (Test-Path -LiteralPath $buildDir)) {
    Remove-Item -LiteralPath $buildDir -Recurse -Force
    Write-Host '      已清理 build 目录（默认全量重建；-Incremental 可保留）'
}
if ($Incremental) {
    Write-Host '      [警告] -Incremental：本工程头文件依赖跟踪不可靠，改了 .h 请改用全量重建' -ForegroundColor Yellow
}
New-Item -ItemType Directory -Force -Path $buildDir | Out-Null

# ---- 4) 在 vcvars 环境里跑 cmake（Ninja 需要 cl.exe 在 PATH 上） ----
#
# ★ VSLANG 必须在 vcvars 之后设：中文版 VS 的 vcvars64.bat 会把 VSLANG 设成 2052，
# 于是 cl.exe 的诊断与 /showIncludes 输出都是中文 —— 而 ninja 只认英文前缀，
# 依赖跟踪会整体失效（改了头文件不重编 → 甚至两份代码看到的结构体布局不一致 → 调野指针崩溃）。
$batPath = Join-Path $buildDir '_build.bat'
$bat = @"
@echo off
call "$vcvars" >nul || exit /b 1
set VSLANG=1033
"$cmake" -S "$nativeDir" -B "$buildDir" -G Ninja -DCMAKE_BUILD_TYPE=$Config -DCMAKE_MAKE_PROGRAM="$ninja" || exit /b 1
"$cmake" --build "$buildDir" || exit /b 1
"@
Set-Content -LiteralPath $batPath -Value $bat -Encoding ASCII
Write-Host '[4/4] 编译原生目标...'
& cmd /c "`"$batPath`""
if ($LASTEXITCODE -ne 0) { throw "原生构建失败 (exit $LASTEXITCODE)" }

$bin = Join-Path $buildDir 'bin'
Get-ChildItem -LiteralPath $bin | ForEach-Object {
    Write-Host ("      -> {0}  ({1:N0} KB)" -f $_.Name, ($_.Length / 1KB))
}

# ---- 5) 编译 Java 侧打样消费者 ----
if (-not $NoJava) {
    $lwjglRoot = Join-Path $env:USERPROFILE '.gradle\caches\minecraft\libraries\org\lwjgl'
    $ver = '3.3.3'
    $jars = @(
        "$lwjglRoot\lwjgl\$ver\lwjgl-$ver.jar",
        "$lwjglRoot\lwjgl\$ver\lwjgl-$ver-natives-windows.jar",
        "$lwjglRoot\lwjgl-glfw\$ver\lwjgl-glfw-$ver.jar",
        "$lwjglRoot\lwjgl-glfw\$ver\lwjgl-glfw-$ver-natives-windows.jar",
        "$lwjglRoot\lwjgl-opengl\$ver\lwjgl-opengl-$ver.jar",
        "$lwjglRoot\lwjgl-opengl\$ver\lwjgl-opengl-$ver-natives-windows.jar"
    )
    foreach ($j in $jars) {
        if (-not (Test-Path -LiteralPath $j)) {
            throw "缺少 LWJGL jar: $j`n（先跑一次 gradlew build 让 Gradle 把 MC 的依赖下下来）"
        }
    }
    $cp = $jars -join ';'
    Set-Content -LiteralPath (Join-Path $buildDir 'lwjgl-cp.txt') -Value $cp -Encoding ASCII

    $classes = Join-Path $buildDir 'java-classes'
    New-Item -ItemType Directory -Force -Path $classes | Out-Null
    $sources = @(
        (Join-Path $repo 'src\main\java\dev\mediafix\gpu\NativeD3D.java'),
        (Join-Path $repo 'tools\spike-consumer\src\dev\mediafix\spike\SpikeConsumer.java'),
        (Join-Path $repo 'tools\mo-probe\src\dev\mediafix\probe\MoProbe.java'),
        (Join-Path $repo 'tools\mo-probe\src\dev\mediafix\probe\MoSpikeConsumer.java')
    )
    Write-Host '[5/5] 编译 Java 侧消费者...'
    & $javac -encoding UTF-8 -d $classes -cp $cp $sources
    if ($LASTEXITCODE -ne 0) { throw "javac 失败 (exit $LASTEXITCODE)" }
    Write-Host "      已编译到 $classes"
}

Write-Host ''
Write-Host '=== 构建完成 ===' -ForegroundColor Green
Write-Host '运行打样:  .\native\run-spike.ps1 -Seconds 20' -ForegroundColor Green

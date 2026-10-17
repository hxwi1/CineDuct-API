# 孤儿测试：本脚本启动子进程后立刻退出，模拟"游戏崩溃/被强杀"——
# 之后检查 cineduct-host 是否还活着（活着 = 孤儿进程，会一直解码 + 占着声卡）。
param(
    [string]$Exe = 'F:\mos\video\native\build\bin\cineduct-host.exe',
    [string]$FfmpegDir = 'F:\mos\video\run\cineduct-ffmpeg\v2-bundled',
    [string]$Url = 'F:\mos\video\native\testdata\bbb.mp4'
)

Start-Process -FilePath $Exe -ArgumentList @(
    '--ffmpeg-dir', $FfmpegDir,
    '--url', $Url,
    '--output-textures', 'orphan_tex',
    '--status', 'orphan_status',
    '--slots', '3',
    '--seconds', '120'
) -RedirectStandardOutput 'F:\mos\video\native\build\orphan.log' -NoNewWindow | Out-Null

Start-Sleep -Milliseconds 800
Write-Output "[launcher] 已启动子进程，本进程即将退出（模拟父进程消失）"

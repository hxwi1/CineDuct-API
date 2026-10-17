# Orphan test launcher: starts the host with --parent-pid = THIS process's pid,
# then exits after a few seconds. Used to verify the child notices its parent is gone.
# ASCII-only on purpose (Windows PowerShell 5.1 misreads UTF-8 without BOM).
param(
    [string]$Exe = 'F:\mos\video\native\build\bin\cineduct-host.exe',
    [string]$FfmpegDir = 'F:\mos\video\run\cineduct-ffmpeg\v2-bundled',
    [string]$Url = 'F:\mos\video\native\testdata\bbb.mp4',
    [int]$LifeSeconds = 5
)

Start-Process -FilePath $Exe -ArgumentList @(
    '--ffmpeg-dir', $FfmpegDir,
    '--url', $Url,
    '--output-textures', 'orphan2_tex',
    '--status', 'orphan2_status',
    '--slots', '3',
    '--seconds', '60',
    '--parent-pid', $PID
) -RedirectStandardOutput 'F:\mos\video\native\build\orphan2.log' -NoNewWindow | Out-Null

Write-Output "[launcher] started host with --parent-pid $PID; this launcher exits in $LifeSeconds s"
Start-Sleep -Seconds $LifeSeconds

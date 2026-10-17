<#
  Live-stream smoke test for cineduct-host.exe.

  Why this exists: on 2026-09-26 the child process got "HTTP error 403 Forbidden" on a Bilibili
  LIVE url while the in-process engine opened the very same url fine. The difference was the
  User-Agent (child defaulted to FFmpeg's "Lavf/...", live CDN rejects it). This script proves
  the discriminator by running BOTH user agents against the same fresh live url.

  Usage:
    .\native\live-smoke.ps1                 # room 1817651835
    .\native\live-smoke.ps1 -RoomId 12345
    .\native\live-smoke.ps1 -Url <flv url>  # skip the api call

  Silent: the child is started with --volume 0 and no --output-textures, so nothing is audible
  and no shared texture is created.
#>
[CmdletBinding()]
param(
    [string]$RoomId = '1817651835',
    [string]$Url,
    [int]$Seconds = 6,
    [string]$FfmpegDir = "$PSScriptRoot\..\run\cineduct-ffmpeg\v2-bundled"
)

$ErrorActionPreference = 'Stop'
$exe = Join-Path $PSScriptRoot 'build\bin\cineduct-host.exe'
if (!(Test-Path $exe)) { throw "host exe not found: $exe (run native\build.ps1 first)" }
if (!(Test-Path $FfmpegDir)) { throw "ffmpeg dir not found: $FfmpegDir" }

$chromeUa = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'

if (-not $Url) {
    $cookieFile = Join-Path $env:USERPROFILE '.bilimedia\cookie.properties'
    $cookie = ''
    if (Test-Path $cookieFile) {
        foreach ($line in (Get-Content $cookieFile)) {
            if ($line -match '^\s*BILIBILI_COOKIE\s*=\s*(.+)$') { $cookie = $matches[1].Trim() }
        }
    }
    $api = "https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo?room_id=$RoomId" +
           "&protocol=0,1&format=0,1,2&codec=0,1&qn=10000&platform=web&ptype=8"
    $headers = @{ 'Referer' = 'https://live.bilibili.com/'; 'User-Agent' = $chromeUa }
    if ($cookie) { $headers['Cookie'] = $cookie }
    Write-Host "[smoke] resolving live url for room $RoomId ..."
    $resp = Invoke-RestMethod -Uri $api -Headers $headers -TimeoutSec 20
    if ($resp.code -ne 0) { throw "api error: code=$($resp.code) message=$($resp.message)" }
    $play = $resp.data.playurl_info.playurl
    if (-not $play) { throw "room is offline (no playurl_info)" }
    $stream = $play.stream[0]
    $http = $stream.format[0].codec[0]
    $qi = $stream.format[0].codec[0].url_info[0]
    $Url = $qi.host + $http.base_url + $qi.extra
    Write-Host "[smoke] url resolved: $($Url.Substring(0, [Math]::Min(90, $Url.Length)))... ($($Url.Length) chars)"
}

# CRLF inside one argv element, exactly like the java side builds it.
$headerValue = "Referer: https://www.bilibili.com/`r`n" +
               "Cookie: " + (Get-Content (Join-Path $env:USERPROFILE '.bilimedia\cookie.properties') |
                   Where-Object { $_ -match '^\s*BILIBILI_COOKIE\s*=' } |
                   ForEach-Object { ($_ -split '=', 2)[1].Trim() }) + "`r`n"

# NOTE: keep this file pure ASCII. Windows PowerShell 5.1 reads a BOM-less .ps1 as ANSI, so
# non-ASCII text here would corrupt the parser (documented trap in docs/DEV-WORKFLOW.md).

function Run-Host([string]$label, [string]$ua) {
    Write-Host ""
    Write-Host "===== $label (user-agent = $ua) ====="
    $global:LASTEXITCODE = 0
    # the host writes its failures to stderr; PowerShell would turn that into a terminating
    # NativeCommandError while $ErrorActionPreference is Stop, so relax it for the call.
    $saved = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $out = & $exe --ffmpeg-dir $FfmpegDir --url $Url --headers $headerValue `
        --user-agent $ua --audio-mode shared --volume 0 --seconds $Seconds 2>&1
    $ErrorActionPreference = $saved
    $text = ($out | Out-String)
    $opened = ($text -match 'fps')            # "视频流 #0: h264 1920x1080 60.000fps ..."
    $denied = ($text -match '403')
    Write-Host ("  opened input : {0}" -f $opened)
    Write-Host ("  http 403     : {0}" -f $denied)
    Write-Host ("  exit code    : {0}" -f $LASTEXITCODE)
    $out | Select-Object -Last 6 | ForEach-Object { Write-Host ("  | " + $_) }
    return $opened
}

$control = Run-Host 'CONTROL (ffmpeg default UA, expected to FAIL on live)' 'Lavf/8.0.1'
$fixed = Run-Host 'FIXED (chrome UA, must open)' $chromeUa

# Step 3: the real production shape -- publish into shared textures (nobody consumes them here,
# so the host drops frames once the 3 slots are full; what matters is that it DOES publish live
# frames and never blocks waiting for a media clock that starts at 39 minutes).
Write-Host ""
Write-Host "===== FIXED + shared textures (publish check) ====="
$statusName = 'cineduct_smoke_status'
$texName = 'cineduct_smoke_tex'
$saved = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$out3 = & $exe --ffmpeg-dir $FfmpegDir --url $Url --headers $headerValue `
    --user-agent $chromeUa --audio-mode shared --volume 0 --seconds $Seconds `
    --output-textures $texName --status $statusName --slots 3 2>&1
$ErrorActionPreference = $saved
$text3 = ($out3 | Out-String)
$ringOk = ($text3 -match [regex]::Escape($statusName))
$published = ($text3 -match 'max=')
Write-Host ("  shared ring  : {0}" -f $ringOk)
Write-Host ("  published    : {0}" -f $published)
$out3 | Select-String -Pattern 'max=|1280x720|1920x1080|M2 ' | Select-Object -Last 8 |
    ForEach-Object { Write-Host ("  | " + $_.Line.Trim()) }
if (-not $published) {
    Write-Host "  (no publish line found -- dumping tail)"
    $out3 | Select-Object -Last 10 | ForEach-Object { Write-Host ("  | " + $_) }
}

Write-Host ""
if ((-not $control) -and $fixed) {
    Write-Host "RESULT: PASS - the user-agent is the discriminator (Lavf UA denied, Chrome UA opens)."
    exit 0
} elseif ($fixed) {
    Write-Host "RESULT: INCONCLUSIVE - the fix opens the stream, but the control also opened"
    Write-Host "        (CDN may not check the UA for this stream/room right now)."
    exit 0
} else {
    Write-Host "RESULT: FAIL - the chrome UA still cannot open the live url (look above)."
    exit 1
}

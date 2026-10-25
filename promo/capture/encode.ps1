param(
  [string]$Frames = "out/frames",
  [int]$Fps = 30,
  [string]$Out = "out/sample-1080p30.mp4",
  [string]$Audio = "",
  [switch]$Preview
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$ffmpeg = "F:\mos\video\run\ffmpeg-cli\ffmpeg-n8.1-latest-win64-lgpl-shared-8.1\bin\ffmpeg.exe"
if (-not (Test-Path $ffmpeg)) { throw "ffmpeg not found: $ffmpeg" }

$framesDir = Resolve-Path (Join-Path $root $Frames)
$outFile = Join-Path $root $Out

function Test-Encoder([string]$name) {
  $null = & $ffmpeg -hide_banner -loglevel error -f lavfi -i "nullsrc=s=320x240:d=0.1" -c:v $name -f null - 2>&1
  return $LASTEXITCODE -eq 0
}

$encoders = @(
  @{ Name = "h264_nvenc"; Args = @("-preset", "p5", "-rc", "vbr", "-cq", "19", "-b:v", "12M", "-maxrate", "16M", "-bufsize", "24M") },
  @{ Name = "h264_mf";   Args = @("-b:v", "12M") },
  @{ Name = "libopenh264"; Args = @("-b:v", "12M") }
)

$chosen = $null
foreach ($e in $encoders) {
  if (Test-Encoder $e.Name) { $chosen = $e; break }
}
if (-not $chosen) { throw "no usable h264 encoder" }
Write-Host "encoder: $($chosen.Name)"

$args = @(
  "-hide_banner", "-y",
  "-framerate", "$Fps",
  "-i", (Join-Path $framesDir "f_%05d.png")
)

if ($Audio -ne "") { $args += @("-i", (Join-Path $root $Audio)) }

if ($Preview) {
  $args += @("-vf", "scale=1280:720:flags=lanczos")
}

$args += @("-c:v", $chosen.Name) + $chosen.Args + @(
  "-pix_fmt", "yuv420p",
  "-profile:v", "high",
  "-r", "$Fps",
  "-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709",
  "-movflags", "+faststart"
)

if ($Audio -ne "") {
  $args += @("-c:a", "aac", "-b:a", "320k", "-ar", "48000", "-shortest")
}

$args += $outFile

& $ffmpeg @args
if ($LASTEXITCODE -ne 0) { throw "ffmpeg failed: $LASTEXITCODE" }

$bytes = (Get-Item $outFile).Length
Write-Host ("done: {0} ({1:N1} MB)" -f $outFile, ($bytes / 1MB))

exit 0

# Exports the in-repo API doc (docs/API.md) as a standalone, self-contained file.
#
# Why: docs/API.md links to source files and to the other docs in the repo, which is fine inside
# the project but useless when the file is handed to someone else. This script strips those
# relative links and adds a short "standalone edition" note, writing the result next to the repo
# root so it can be copied/sent anywhere.
#
# Re-run it after any change to docs/API.md:
#     .\tools\export-api-doc.ps1
#
# NOTE: keep this file pure ASCII. Windows PowerShell 5.1 reads a BOM-less .ps1 as ANSI, so
# non-ASCII text here would corrupt the parser (see docs/DEV-WORKFLOW.md).
[CmdletBinding()]
param(
    [string]$Source = (Join-Path $PSScriptRoot '..\docs\API.md'),
    [string]$Target = (Join-Path $PSScriptRoot '..\CineDuct-API-中文文档.md')
)

$ErrorActionPreference = 'Stop'
if (!(Test-Path $Source)) { throw "source doc not found: $Source" }

$text = Get-Content $Source -Raw -Encoding UTF8

# Drop relative links but keep their visible text.
$text = $text -replace '\[`([^`]+)`\]\(\.\./src/[^)]*\)', '`$1`'
$text = $text -replace '\[`([^`]+)`\]\((DEV-WORKFLOW|AUDIO-passthrough|PLAN-standalone-gpu)\.md\)', '`$1`'
$text = $text -replace '\[([^\]]+)\]\((src|\.\.)/[^)]*\)', '$1'
$text = $text -replace '\[([^\]]+)\]\(#([^)]*)\)', '$1'

$note = @'
# CineDuct API 中文文档（附属 mod 开发接口）

> **独立文件版**：自包含，可直接发给别人；不引用仓库里的其它文件。
> 适用模组版本：**CineDuct API 3.0.0-hotfix**（modid `cineduct`；Java 包名 `dev.mediafix`）。
> 仓库内版本是 `docs/API.md`（额外带指向源码的链接）。
'@

$text = $text -replace '^# CineDuct API 中文文档（附属 mod 开发接口）', $note

# UTF-8 without BOM is fine here: the file is Markdown, not a script.
[System.IO.File]::WriteAllText($Target, $text, (New-Object System.Text.UTF8Encoding($false)))

$lines = (Get-Content $Target).Count
Write-Host ("exported: {0}  ({1:N1} KB, {2} lines)" -f $Target, ((Get-Item $Target).Length / 1KB), $lines)

$left = Select-String -Path $Target -Pattern '\]\((?!http)[^)]*\.(java|md)\)' -Encoding UTF8
if ($left) {
    Write-Warning "relative links still present:"
    $left | ForEach-Object { Write-Warning ("  {0}: {1}" -f $_.LineNumber, $_.Line.Trim()) }
    exit 1
}
Write-Host "checked: no relative links left (self-contained)"

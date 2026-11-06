# Installs the obfaryn CLI from a GitHub Release zip (Windows).
#
# Usage:
#   iwr https://raw.githubusercontent.com/peco2282/Obfaryn/main/scripts/install.ps1 -UseBasicParsing | iex
#
# Env vars:
#   OBFARYN_VERSION   Version to install (default: latest release)
#   OBFARYN_HOME      Install directory (default: %LOCALAPPDATA%\obfaryn)
$ErrorActionPreference = "Stop"

$Repo = "peco2282/Obfaryn"
$InstallDir = if ($env:OBFARYN_HOME) { $env:OBFARYN_HOME } else { "$env:LOCALAPPDATA\obfaryn" }

if ($env:OBFARYN_VERSION) {
    $Version = $env:OBFARYN_VERSION
} else {
    Write-Host "Resolving latest release..."
    $Latest = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repo/releases/latest"
    $Version = $Latest.tag_name -replace '^v', ''
}

$Archive = "obfaryn-$Version.zip"
$Url = "https://github.com/$Repo/releases/download/v$Version/$Archive"
$TempZip = Join-Path $env:TEMP $Archive
$TempExtract = Join-Path $env:TEMP "obfaryn-extract-$Version"

Write-Host "Downloading obfaryn $Version..."
Invoke-WebRequest -Uri $Url -OutFile $TempZip

if (Test-Path $TempExtract) { Remove-Item $TempExtract -Recurse -Force }
Expand-Archive -Path $TempZip -DestinationPath $TempExtract
Remove-Item $TempZip

if (Test-Path $InstallDir) { Remove-Item $InstallDir -Recurse -Force }
$ExtractedRoot = Get-ChildItem -Path $TempExtract -Directory | Select-Object -First 1
Move-Item -Path $ExtractedRoot.FullName -Destination $InstallDir
Remove-Item $TempExtract -Recurse -Force

$BinDir = Join-Path $InstallDir "bin"
$UserPath = [Environment]::GetEnvironmentVariable("Path", "User")
if ($UserPath -notlike "*$BinDir*") {
    [Environment]::SetEnvironmentVariable("Path", "$UserPath;$BinDir", "User")
    Write-Host "Added $BinDir to your user PATH. Restart your terminal for it to take effect."
}

Write-Host "Installed obfaryn $Version to $InstallDir"
Write-Host "Run: obfaryn --help"
Write-Host "No PowerShell tab-completion is generated yet (picocli only emits bash/zsh scripts); see README's 'Shell completion' section."

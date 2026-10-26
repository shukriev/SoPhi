# Installs the latest released sophi CLI on Windows, no checkout needed:
#   irm https://raw.githubusercontent.com/shukriev/SoPhi/main/scripts/get-sophi.ps1 | iex
# Pin a version with $env:SOPHI_VERSION = 'cli-v0.9.0'. Re-run to upgrade.
$ErrorActionPreference = 'Stop'

$repo = 'shukriev/SoPhi'
$installDir = Join-Path $env:LOCALAPPDATA 'sophi'

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    throw "sophi needs Java 21+, and no 'java' is on your PATH. Install one (e.g. 'winget install EclipseAdoptium.Temurin.21.JRE') and re-run."
}
$versionLine = (& cmd /c 'java -version 2>&1' | Select-Object -First 1)
if ($versionLine -match '"(\d+)') {
    if ([int]$Matches[1] -lt 21) { throw "sophi needs Java 21+, found Java $($Matches[1])." }
}

$tag = $env:SOPHI_VERSION
if (-not $tag) {
    # Not /releases/latest: voice-tools releases live in the same repo.
    $releases = Invoke-RestMethod "https://api.github.com/repos/$repo/releases?per_page=100"
    $tag = ($releases | Where-Object { $_.tag_name -like 'cli-v*' } | Select-Object -First 1).tag_name
}
if (-not $tag) { throw "No cli-v* release found in $repo." }

Write-Host "Downloading sophi $tag..."
New-Item -ItemType Directory -Force -Path $installDir | Out-Null
$base = "https://github.com/$repo/releases/download/$tag"
$part = Join-Path $installDir 'sophi.jar.part'
Invoke-WebRequest "$base/sophi.jar" -OutFile $part -UseBasicParsing
Invoke-WebRequest "$base/sophi.jar.sha256" -OutFile "$part.sha256" -UseBasicParsing
$expected = (Get-Content "$part.sha256" -Raw).Trim().Split(' ')[0]
Remove-Item "$part.sha256"
$actual = (Get-FileHash $part -Algorithm SHA256).Hash
if ($expected -ne $actual) {
    Remove-Item $part
    throw "Checksum mismatch for sophi.jar (expected $expected, got $actual)."
}
Move-Item $part (Join-Path $installDir 'sophi.jar') -Force

Set-Content -Path (Join-Path $installDir 'sophi.cmd') -Encoding ASCII -Value "@java --enable-native-access=ALL-UNNAMED -jar `"%~dp0sophi.jar`" %*"

$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
if (-not (($userPath -split ';') -contains $installDir)) {
    [Environment]::SetEnvironmentVariable('Path', "$userPath;$installDir", 'User')
    Write-Host "Added $installDir to your user PATH. Open a new terminal to use 'sophi'."
}
Write-Host "Installed sophi $tag -> $installDir\sophi.cmd"

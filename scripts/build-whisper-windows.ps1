# Builds whisper-cli.exe or whisper-stream.exe on a Windows runner and packages it as .tar.gz.
#   build-whisper-windows.ps1 -Target cli|stream [-Arch x64] [-WhisperRef b4938]
#   build-whisper-windows.ps1 -Target cli|stream -Verify    # check the packaged artifact
# Static MSVC runtime (/MT) and no OpenMP, so the user needs no VC++ redistributable. whisper-stream
# additionally ships SDL2.dll beside the exe. CI calls this from voice-tools-release.yml.
param(
    [Parameter(Mandatory)][ValidateSet('cli', 'stream')][string]$Target,
    [string]$Arch = 'x64',
    [string]$WhisperRef = 'b4938',
    [switch]$Verify
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true

$Sdl2Version = '2.30.9'
# SHA-256 of SDL2-devel-2.30.9-VC.zip (hashed from the official release asset).
$Sdl2Sha256 = '8c91d91e5bcb997d062ec2b553c53832ebf95654d4aa35e8c02a954d4ce752ae'

$Root = Join-Path (Get-Location) '.whisper-windows-build'
$Dist = Join-Path $Root "dist-$Target"
$Exe = if ($Target -eq 'cli') { 'whisper-cli.exe' } else { 'whisper-stream.exe' }
$Archive = Join-Path $Root "whisper-$Target-windows-$Arch.tar.gz"

if ($Verify) {
    $tmp = Join-Path $Root "verify-$Target"
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
    New-Item -ItemType Directory $tmp | Out-Null
    tar xzf $Archive -C $tmp
    if (-not (Test-Path (Join-Path $tmp $Exe))) { throw "$Exe missing from archive" }
    if ($Target -eq 'stream' -and -not (Test-Path (Join-Path $tmp 'SDL2.dll'))) { throw 'SDL2.dll missing from archive' }

    $vs = & "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe" -latest -property installationPath
    $dumpbin = Get-ChildItem (Join-Path $vs 'VC\Tools\MSVC') -Recurse -Filter dumpbin.exe |
        Where-Object { $_.FullName -match 'Hostx64\\x64' } | Select-Object -First 1
    if (-not $dumpbin) { throw 'dumpbin.exe not found' }
    $out = & $dumpbin.FullName /dependents (Join-Path $tmp $Exe)
    $out | Write-Host
    $deps = $out | ForEach-Object { $_.Trim() } | Where-Object { $_ -match '\.dll$' }
    $allowed = '^(SDL2|KERNEL32|USER32|ADVAPI32|SHELL32|ole32|WINMM|api-ms-win-[\w-]+)\.dll$'
    foreach ($d in $deps) {
        if ($d -match 'VCRUNTIME|MSVCP|VCOMP|^ucrtbase') { throw "forbidden runtime dependency: $d" }
        if ($d -notmatch $allowed) { throw "unexpected dependency: $d" }
        if ($d -match '^SDL2' -and $Target -eq 'cli') { throw 'whisper-cli must not depend on SDL2' }
    }
    if ($Target -eq 'cli') {
        & (Join-Path $tmp $Exe) --help | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "$Exe --help exited $LASTEXITCODE" }
    }
    Write-Host "OK: $Exe"
    exit 0
}

Remove-Item -Recurse -Force $Root -ErrorAction SilentlyContinue
New-Item -ItemType Directory $Root, $Dist | Out-Null
$src = Join-Path $Root 'whisper.cpp'
git clone --depth 1 --branch $WhisperRef https://github.com/ggml-org/whisper.cpp $src

$cmakeArgs = @('-B', 'build', '-A', $Arch, '-DBUILD_SHARED_LIBS=OFF', '-DGGML_NATIVE=OFF', '-DGGML_OPENMP=OFF',
    # whisper.cpp's cmake_minimum_required is old, so without CMP0091 the runtime variable is ignored.
    '-DCMAKE_POLICY_DEFAULT_CMP0091=NEW', '-DCMAKE_MSVC_RUNTIME_LIBRARY=MultiThreaded')

if ($Target -eq 'stream') {
    $zip = Join-Path $Root "SDL2-devel-$Sdl2Version-VC.zip"
    Invoke-WebRequest "https://github.com/libsdl-org/SDL/releases/download/release-$Sdl2Version/SDL2-devel-$Sdl2Version-VC.zip" -OutFile $zip
    $got = (Get-FileHash $zip -Algorithm SHA256).Hash
    if ($got -ne $Sdl2Sha256) { throw "SDL2 zip SHA-256 mismatch: got $got, expected $Sdl2Sha256" }
    Expand-Archive $zip -DestinationPath $Root
    $sdl = Join-Path $Root "SDL2-$Sdl2Version"
    $cmakeArgs += @('-DWHISPER_SDL2=ON', "-DSDL2_DIR=$($sdl -replace '\\','/')/cmake")
}

Push-Location $src
try {
    cmake @cmakeArgs
    cmake --build build --config Release --target "whisper-$Target"
} finally { Pop-Location }

Copy-Item (Join-Path $src "build\bin\Release\$Exe") $Dist
if ($Target -eq 'stream') { Copy-Item (Join-Path $sdl "lib\$Arch\SDL2.dll") $Dist }
tar czf $Archive -C $Dist (Get-ChildItem $Dist).Name
Write-Host "Packaged $Archive"

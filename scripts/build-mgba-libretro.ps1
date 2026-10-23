#Requires -Version 5.1
<#
.SYNOPSIS
    Build the mGBA Libretro core for Android with the NDK.

.DESCRIPTION
    Builds mgba_libretro (emitting mgba_libretro.so) from the pinned
    mgba-source/mgba(mGBA-Libretro) tree, copies it into
    app/src/main/jniLibs/<abi>/libmgba_libretro.so, and verifies
    architecture, dynamic dependencies and exported retro_* symbols.

.PARAMETER Abi
    Android ABI to build. P0 only supports arm64-v8a.

.PARAMETER BuildType
    CMake build type. Defaults to Release.

.PARAMETER AndroidSdk
    Android SDK path. Read from local.properties when omitted.

.PARAMETER NdkVersion
    Installed NDK version. Defaults to 27.0.12077973.

.PARAMETER CmakeVersion
    CMake bundled with the SDK. Defaults to 3.22.1.

.PARAMETER Clean
    Delete the build directory before configuring.

.PARAMETER SkipCopy
    Do not copy the .so into app/src/main/jniLibs.

.PARAMETER SkipVerify
    Skip the readelf/nm verification step.
#>
[CmdletBinding()]
param(
    [ValidateSet("arm64-v8a")]
    [string]$Abi = "arm64-v8a",

    [ValidateSet("Release", "Debug", "RelWithDebInfo")]
    [string]$BuildType = "Release",

    [string]$AndroidSdk = "",
    [string]$NdkVersion = "27.0.12077973",
    [string]$CmakeVersion = "3.22.1",

    [switch]$Clean,
    [switch]$SkipCopy,
    [switch]$SkipVerify
)

$ErrorActionPreference = "Stop"

function Invoke-Native {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$Description
    )

    Write-Host ">> $Description" -ForegroundColor Cyan
    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Description failed with exit code $LASTEXITCODE"
    }
}

function Resolve-AndroidSdk {
    param([string]$ExplicitPath, [string]$RepoRoot)

    if ($ExplicitPath) {
        return (Resolve-Path -LiteralPath $ExplicitPath).Path
    }

    $localProperties = Join-Path $RepoRoot "local.properties"
    if (Test-Path -LiteralPath $localProperties) {
        $line = Get-Content -LiteralPath $localProperties |
            Where-Object { $_ -match '^\s*sdk\.dir\s*=' } |
            Select-Object -First 1
        if ($line) {
            $value = ($line -replace '^\s*sdk\.dir\s*=\s*', '').Trim()
            # local.properties escapes ':' and '\'
            $value = $value -replace '\\(.)', '$1'
            return (Resolve-Path -LiteralPath $value).Path
        }
    }

    throw "Android SDK not found. Pass -AndroidSdk or set sdk.dir in local.properties."
}

$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $RepoRoot
$SourceDir = Join-Path $RepoRoot "mgba-source\mgba(mGBA-Libretro)"
$BuildDir = Join-Path $RepoRoot "build\native\mgba-libretro\$Abi-$($BuildType.ToLowerInvariant())"
$JniLibsDir = Join-Path $RepoRoot "app\src\main\jniLibs\$Abi"
$ExpectedTarget = "mgba_libretro"
# The upstream target sets PREFIX "" so the build emits mgba_libretro.so
# (the canonical RetroArch core filename).
$BuiltSoName = "mgba_libretro.so"
# Android packaging/System.loadLibrary expects a lib*.so name.
$PackagedSoName = "libmgba_libretro.so"

if (-not (Test-Path -LiteralPath $SourceDir)) {
    throw "mGBA source not found: $SourceDir"
}

$AndroidSdk = Resolve-AndroidSdk -ExplicitPath $AndroidSdk -RepoRoot $RepoRoot
$NdkRoot = Join-Path $AndroidSdk "ndk\$NdkVersion"
$ToolchainFile = Join-Path $NdkRoot "build\cmake\android.toolchain.cmake"
$CmakeBin = Join-Path $AndroidSdk "cmake\$CmakeVersion\bin"
$Cmake = Join-Path $CmakeBin "cmake.exe"
$Ninja = Join-Path $CmakeBin "ninja.exe"
$LlvmBin = Join-Path $NdkRoot "toolchains\llvm\prebuilt\windows-x86_64\bin"
$ReadElf = Join-Path $LlvmBin "llvm-readelf.exe"
$Nm = Join-Path $LlvmBin "llvm-nm.exe"

foreach ($required in @($ToolchainFile, $Cmake, $Ninja, $ReadElf, $Nm)) {
    if (-not (Test-Path -LiteralPath $required)) {
        throw "Required tool not found: $required"
    }
}

# CMake needs ninja on PATH when using the Ninja generator.
$env:PATH = "$CmakeBin;$env:PATH"

if ($Clean -and (Test-Path -LiteralPath $BuildDir)) {
    Write-Host ">> Removing build directory: $BuildDir" -ForegroundColor Yellow
    Remove-Item -LiteralPath $BuildDir -Recurse -Force
}

New-Item -ItemType Directory -Force -Path $BuildDir | Out-Null

$configureArgs = @(
    "-S", $SourceDir,
    "-B", $BuildDir,
    "-G", "Ninja",
    "-DCMAKE_MAKE_PROGRAM=$Ninja",
    "-DCMAKE_TOOLCHAIN_FILE=$ToolchainFile",
    "-DANDROID_ABI=$Abi",
    "-DANDROID_PLATFORM=android-30",
    "-DCMAKE_BUILD_TYPE=$BuildType",
    "-DCMAKE_EXPORT_COMPILE_COMMANDS=ON",
    # Android 15+ devices may use 16 KiB pages; keep LOAD segments compatible.
    "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384",
    "-DBUILD_LIBRETRO=ON",
    "-DSKIP_LIBRARY=ON",
    # The libretro fork does not compile with M_CORE_GB=OFF: libretro.c
    # references GBModel/GB_MODEL_* outside any M_CORE_GB guard.
    "-DM_CORE_GB=ON",
    "-DENABLE_DEBUGGERS=OFF",
    "-DENABLE_GDB_STUB=OFF",
    "-DENABLE_SCRIPTING=OFF",
    "-DBUILD_QT=OFF",
    "-DBUILD_SDL=OFF",
    "-DBUILD_GL=OFF",
    "-DBUILD_GLES2=OFF",
    "-DBUILD_GLES3=OFF",
    "-DBUILD_TEST=OFF",
    "-DBUILD_SUITE=OFF",
    "-DDISABLE_DEPS=ON",
    "-DUSE_FFMPEG=OFF",
    "-DUSE_ZLIB=OFF",
    "-DUSE_MINIZIP=OFF",
    "-DUSE_PNG=OFF",
    "-DUSE_LIBZIP=OFF",
    "-DUSE_SQLITE3=OFF",
    "-DUSE_ELF=OFF",
    "-DUSE_LUA=OFF",
    "-DUSE_JSON_C=OFF",
    "-DUSE_FREETYPE=OFF",
    "-DUSE_LZMA=OFF",
    "-DUSE_DISCORD_RPC=OFF"
)

Invoke-Native -FilePath $Cmake -Arguments @("--version") -Description "cmake --version"
Invoke-Native -FilePath $Cmake -Arguments $configureArgs -Description "Configure mGBA Libretro ($Abi, $BuildType)"
Invoke-Native -FilePath $Cmake -Arguments @("--build", $BuildDir, "--target", $ExpectedTarget, "--parallel") -Description "Build $ExpectedTarget"

$SoPath = Join-Path $BuildDir $BuiltSoName
if (-not (Test-Path -LiteralPath $SoPath)) {
    $found = Get-ChildItem -LiteralPath $BuildDir -Recurse -Filter $BuiltSoName |
        Select-Object -First 1
    if (-not $found) {
        throw "Build finished but $BuiltSoName was not found under $BuildDir"
    }
    $SoPath = $found.FullName
}

Write-Host ">> Built: $SoPath" -ForegroundColor Green

if (-not $SkipVerify) {
    Write-Host ">> Verifying architecture" -ForegroundColor Cyan
    $header = & $ReadElf -h $SoPath
    if ($LASTEXITCODE -ne 0) {
        throw "llvm-readelf -h failed"
    }
    $header | Write-Host
    if (-not ($header -match 'AArch64')) {
        throw "Unexpected architecture: the .so is not AArch64"
    }

    Write-Host ">> Dynamic dependencies" -ForegroundColor Cyan
    $dynamic = & $ReadElf -d $SoPath
    if ($LASTEXITCODE -ne 0) {
        throw "llvm-readelf -d failed"
    }
    $dynamic | Write-Host
    $needed = @($dynamic | Where-Object { $_ -match '\(NEEDED\)' })
    Write-Host "NEEDED entries: $($needed.Count)" -ForegroundColor Green

    Write-Host ">> Verifying 16 KiB LOAD alignment" -ForegroundColor Cyan
    $programHeaders = & $ReadElf -l $SoPath
    if ($LASTEXITCODE -ne 0) {
        throw "llvm-readelf -l failed"
    }
    $loadLines = @($programHeaders | Where-Object { $_ -match '^\s*LOAD\s' })
    if ($loadLines.Count -eq 0) {
        throw "No LOAD segments found in $SoPath"
    }
    foreach ($line in $loadLines) {
        if ($line -notmatch '0x4000') {
            throw "LOAD segment is not 16 KiB aligned: $line"
        }
    }
    Write-Host "All $($loadLines.Count) LOAD segments are 16 KiB aligned." -ForegroundColor Green

    Write-Host ">> Exported retro_* symbols" -ForegroundColor Cyan
    $symbols = & $Nm -D --defined-only $SoPath
    if ($LASTEXITCODE -ne 0) {
        throw "llvm-nm failed"
    }
    $requiredSymbols = @(
        "retro_api_version",
        "retro_set_environment",
        "retro_set_video_refresh",
        "retro_set_audio_sample",
        "retro_set_audio_sample_batch",
        "retro_set_input_poll",
        "retro_set_input_state",
        "retro_init",
        "retro_deinit",
        "retro_get_system_info",
        "retro_get_system_av_info",
        "retro_load_game",
        "retro_unload_game",
        "retro_run",
        "retro_reset",
        "retro_serialize_size",
        "retro_serialize",
        "retro_unserialize",
        "retro_get_memory_data",
        "retro_get_memory_size"
    )
    $missing = @()
    foreach ($symbol in $requiredSymbols) {
        if (-not ($symbols -match "\b$symbol\b")) {
            $missing += $symbol
        }
    }
    if ($missing.Count -gt 0) {
        throw "Missing exported symbols: $($missing -join ', ')"
    }
    Write-Host "All $($requiredSymbols.Count) required symbols exported." -ForegroundColor Green
}

if (-not $SkipCopy) {
    New-Item -ItemType Directory -Force -Path $JniLibsDir | Out-Null
    $destination = Join-Path $JniLibsDir $PackagedSoName
    Copy-Item -LiteralPath $SoPath -Destination $destination -Force
    Write-Host ">> Copied to: $destination" -ForegroundColor Green
}

$commit = "(unknown)"
try {
    $commit = (& git -C $SourceDir rev-parse HEAD 2>$null)
    if ($LASTEXITCODE -ne 0) {
        $commit = "(unknown)"
    }
} catch {
    $commit = "(unknown)"
}

$hash = (Get-FileHash -LiteralPath $SoPath -Algorithm SHA256).Hash
$buildInfo = @(
    "source=$SourceDir",
    "commit=$commit",
    "abi=$Abi",
    "android_platform=android-30",
    "build_type=$BuildType",
    "ndk=$NdkVersion",
    "cmake=$CmakeVersion",
    "artifact=$SoPath",
    "packaged_artifact=$(Join-Path $JniLibsDir $PackagedSoName)",
    "sha256=$hash",
    "built_at=$((Get-Date).ToString('o'))",
    "configure_args=$($configureArgs -join ' ')"
) -join [Environment]::NewLine
$buildInfoPath = Join-Path $BuildDir "build-info.txt"
Set-Content -LiteralPath $buildInfoPath -Value $buildInfo -Encoding UTF8
Write-Host ">> Build info: $buildInfoPath" -ForegroundColor Green

Write-Host "Build succeeded." -ForegroundColor Green

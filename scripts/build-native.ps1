#Requires -Version 5.1
<#
.SYNOPSIS
    Build the mGBA Libretro core and the JNI frontend for Android.

.DESCRIPTION
    Calls build-mgba-libretro.ps1 to produce libmgba_libretro.so from the
    pinned source tree, then builds native/ (the JNI bridge) against the same
    NDK and copies both libraries into app/src/main/jniLibs/<abi>/.

.PARAMETER Abi
    Android ABI to build. P0 only supports arm64-v8a.

.PARAMETER BuildType
    Release or Debug. Defaults to Release.

.PARAMETER AndroidSdk
    Android SDK path. Read from local.properties when omitted.

.PARAMETER NdkVersion
    Installed NDK version. Defaults to 27.0.12077973.

.PARAMETER CmakeVersion
    CMake bundled with the SDK. Defaults to 3.22.1.

.PARAMETER Clean
    Rebuild both the core and the frontend from scratch.

.PARAMETER SkipCore
    Assume libmgba_libretro.so is already built and only build the frontend.
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
    [switch]$SkipCore
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
            $value = $value -replace '\\(.)', '$1'
            return (Resolve-Path -LiteralPath $value).Path
        }
    }

    throw "Android SDK not found. Pass -AndroidSdk or set sdk.dir in local.properties."
}

$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $RepoRoot
$NativeDir = Join-Path $RepoRoot "native"
$SourceDir = Join-Path $RepoRoot "mgba-source\mgba(mGBA-Libretro)"
$FrontendBuildDir = Join-Path $RepoRoot "build\native\frontend\$Abi-$($BuildType.ToLowerInvariant())"
$JniLibsDir = Join-Path $RepoRoot "app\src\main\jniLibs\$Abi"

if (-not (Test-Path -LiteralPath $NativeDir)) {
    throw "native/ directory not found: $NativeDir"
}

if (-not $SkipCore) {
    $coreScript = Join-Path $PSScriptRoot "build-mgba-libretro.ps1"
    & $coreScript -Abi $Abi -BuildType $BuildType -AndroidSdk $AndroidSdk `
        -NdkVersion $NdkVersion -CmakeVersion $CmakeVersion -Clean:$Clean
    if ($LASTEXITCODE -ne 0) {
        throw "Core build failed with exit code $LASTEXITCODE"
    }
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

$env:PATH = "$CmakeBin;$env:PATH"

if ($Clean -and (Test-Path -LiteralPath $FrontendBuildDir)) {
    Write-Host ">> Removing build directory: $FrontendBuildDir" -ForegroundColor Yellow
    Remove-Item -LiteralPath $FrontendBuildDir -Recurse -Force
}

New-Item -ItemType Directory -Force -Path $FrontendBuildDir | Out-Null

$configureArgs = @(
    "-S", $NativeDir,
    "-B", $FrontendBuildDir,
    "-G", "Ninja",
    "-DCMAKE_MAKE_PROGRAM=$Ninja",
    "-DCMAKE_TOOLCHAIN_FILE=$ToolchainFile",
    "-DANDROID_ABI=$Abi",
    "-DANDROID_PLATFORM=android-30",
    "-DCMAKE_BUILD_TYPE=$BuildType",
    "-DBUILD_TESTING=OFF",
    "-DMGBA_SOURCE_DIR=$SourceDir"
)

Invoke-Native -FilePath $Cmake -Arguments $configureArgs -Description "Configure JNI frontend ($Abi, $BuildType)"
Invoke-Native -FilePath $Cmake -Arguments @("--build", $FrontendBuildDir, "--target", "mgba_frontend", "--parallel") -Description "Build mgba_frontend"

$FrontendSo = Join-Path $FrontendBuildDir "libmgba_frontend.so"
if (-not (Test-Path -LiteralPath $FrontendSo)) {
    throw "Build finished but libmgba_frontend.so was not found under $FrontendBuildDir"
}

New-Item -ItemType Directory -Force -Path $JniLibsDir | Out-Null
Copy-Item -LiteralPath $FrontendSo -Destination (Join-Path $JniLibsDir "libmgba_frontend.so") -Force
Write-Host ">> Copied frontend to: $(Join-Path $JniLibsDir 'libmgba_frontend.so')" -ForegroundColor Green

$coreSo = Join-Path $JniLibsDir "libmgba_libretro.so"
if (-not (Test-Path -LiteralPath $coreSo)) {
    throw "Core library not found in jniLibs: $coreSo (run without -SkipCore)"
}

Write-Host ">> Verifying frontend architecture and symbols" -ForegroundColor Cyan
$header = & $ReadElf -h $FrontendSo
if ($LASTEXITCODE -ne 0 -or -not ($header -match 'AArch64')) {
    throw "libmgba_frontend.so is not AArch64"
}
$symbols = & $Nm -D --defined-only $FrontendSo
if ($LASTEXITCODE -ne 0) {
    throw "llvm-nm failed on libmgba_frontend.so"
}
$requiredSymbols = @(
    "nativeCreate", "nativeDestroy", "nativeInitialize", "nativeLoadGame",
    "nativeRunOnce", "nativeUnloadGame", "nativeShutdown", "nativeLastError",
    "nativeCoreName", "nativeCoreVersion", "nativeState", "nativeFrameCount",
    "nativeAudioFrameCount", "nativePixelFormat",
    "nativeAcquireFrame", "nativeReleaseFrame", "nativeFramesDropped",
    "nativeReadAudio", "nativeAudioQueued", "nativeAudioUnderruns",
    "nativeAudioOverflows", "nativeResetAudioStats", "nativeGetAvInfo",
    "nativeInputPress", "nativeInputRelease", "nativeInputReleaseSource",
    "nativeInputReset",
    "nativeCheatReset", "nativeCheatSet",
    "nativeSramSize", "nativeSaveSram", "nativeLoadSram",
    "nativeRtcSize", "nativeSaveRtc", "nativeLoadRtc",
    "nativeSerializeSize", "nativeSerialize", "nativeUnserialize",
    "nativeOptionCount", "nativeOptionKey", "nativeOptionValues",
    "nativeOptionCurrent", "nativeSetOption"
)
$missing = @()
foreach ($symbol in $requiredSymbols) {
    $jniName = "Java_com_mgba_bingbaihanji_emulator_NativeEmulator_$symbol"
    if (-not ($symbols -match "\b$jniName\b")) {
        $missing += $jniName
    }
}
if ($missing.Count -gt 0) {
    throw "Missing JNI symbols: $($missing -join ', ')"
}
Write-Host "All $($requiredSymbols.Count) JNI symbols exported." -ForegroundColor Green

$dynamic = & $ReadElf -d $FrontendSo
$needed = @($dynamic | Where-Object { $_ -match '\(NEEDED\)' })
Write-Host "Frontend NEEDED entries: $($needed.Count)" -ForegroundColor Green

Write-Host ">> Verifying 16 KiB LOAD alignment" -ForegroundColor Cyan
foreach ($binary in @($FrontendSo, $coreSo)) {
    $programHeaders = & $ReadElf -l $binary
    if ($LASTEXITCODE -ne 0) {
        throw "llvm-readelf -l failed for $binary"
    }
    $loadLines = @($programHeaders | Where-Object { $_ -match '^\s*LOAD\s' })
    if ($loadLines.Count -eq 0) {
        throw "No LOAD segments found in $binary"
    }
    foreach ($line in $loadLines) {
        if ($line -notmatch '0x4000') {
            throw "LOAD segment is not 16 KiB aligned in ${binary}: $line"
        }
    }
    Write-Host "  $(Split-Path -Leaf $binary): $($loadLines.Count) LOAD segments aligned." -ForegroundColor Green
}

$frontendHash = (Get-FileHash -LiteralPath $FrontendSo -Algorithm SHA256).Hash
$coreHash = (Get-FileHash -LiteralPath $coreSo -Algorithm SHA256).Hash
$commit = "(unknown)"
try {
    $commit = (& git -C $SourceDir rev-parse HEAD 2>$null)
    if ($LASTEXITCODE -ne 0) {
        $commit = "(unknown)"
    }
} catch {
    $commit = "(unknown)"
}

$buildInfo = @(
    "core_source=$SourceDir",
    "core_commit=$commit",
    "abi=$Abi",
    "android_platform=android-30",
    "build_type=$BuildType",
    "ndk=$NdkVersion",
    "cmake=$CmakeVersion",
    "frontend_artifact=$FrontendSo",
    "frontend_sha256=$frontendHash",
    "core_artifact=$coreSo",
    "core_sha256=$coreHash",
    "built_at=$((Get-Date).ToString('o'))",
    "configure_args=$($configureArgs -join ' ')"
) -join [Environment]::NewLine
Set-Content -LiteralPath (Join-Path $FrontendBuildDir "build-info.txt") `
    -Value $buildInfo -Encoding UTF8

Write-Host "Artifacts:" -ForegroundColor Green
Get-ChildItem -LiteralPath $JniLibsDir -Filter "*.so" |
    ForEach-Object {
        $hash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
        Write-Host ("  {0}  {1,10:N0} bytes  sha256={2}" -f $_.Name, $_.Length, $hash)
    }
Write-Host "Build info: $(Join-Path $FrontendBuildDir 'build-info.txt')" -ForegroundColor Green
Write-Host "Native build succeeded." -ForegroundColor Green

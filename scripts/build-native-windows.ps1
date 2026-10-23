#Requires -Version 5.1
<#
.SYNOPSIS
    Build the mGBA native layer as Windows x64 DLLs.

.DESCRIPTION
    Produces two artifacts that make up the desktop runtime:

      mgba_libretro.dll  - the mGBA Libretro core (unless -SkipCore)
      mgba_frontend.dll  - the JNI bridge used by NativeEmulator.kt

    Both are built with MinGW-w64 GCC and copied to
    build/native/windows-x64/dist/.

    The frontend loads the core at runtime through LoadLibraryA(), so the two
    DLLs may live in different directories; pass the core path to
    nativeInitialize() from Kotlin.

.PARAMETER BuildType
    Release, Debug or RelWithDebInfo. Defaults to Release.

.PARAMETER MingwRoot
    Directory containing gcc.exe, g++.exe and mingw32-make.exe.
    Auto-detected from -MingwRoot, $env:MINGW_HOME, D:\develop\gcc\mingw64,
    or the first gcc.exe on PATH.

.PARAMETER JavaHome
    JDK home containing include\jni.h and include\win32\jni_md.h.
    Defaults to $env:JAVA_HOME.

.PARAMETER Cmake
    cmake.exe path. Defaults to the first cmake.exe on PATH.

.PARAMETER Jobs
    Parallel compile jobs. Defaults to 2 because the mGBA core is
    memory-hungry. Use 1 if the compiler reports out-of-memory.

.PARAMETER EnableLto
    Enable link-time optimization. Off by default to reduce peak memory.

.PARAMETER SkipCore
    Only build mgba_frontend.dll (the core DLL must already exist).

.PARAMETER SkipVerify
    Skip PE architecture and export-table checks.

.PARAMETER Clean
    Delete the Windows build directories before configuring.

.EXAMPLE
    .\scripts\build-native-windows.ps1

.EXAMPLE
    .\scripts\build-native-windows.ps1 -SkipCore -Jobs 1
#>
[CmdletBinding()]
param(
    [ValidateSet("Release", "Debug", "RelWithDebInfo")]
    [string]$BuildType = "Release",

    [string]$MingwRoot = "",
    [string]$JavaHome = "",
    [string]$Cmake = "",

    [ValidateRange(0, 64)]
    [int]$Jobs = 2,

    [switch]$EnableLto,
    [switch]$SkipCore,
    [switch]$SkipVerify,
    [switch]$Clean
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

function Resolve-MingwRoot {
    param([string]$Explicit)

    $roots = New-Object System.Collections.Generic.List[string]
    if ($Explicit) { $roots.Add($Explicit) }
    if ($env:MINGW_HOME) { $roots.Add($env:MINGW_HOME) }
    # Common local layout: <root>\bin\gcc.exe.
    $roots.Add("D:\develop\gcc\mingw64")

    $gccOnPath = Get-Command gcc.exe -ErrorAction SilentlyContinue
    if ($gccOnPath) { $roots.Add((Split-Path -Parent $gccOnPath.Source)) }

    $checked = New-Object System.Collections.Generic.List[string]
    $rejected = New-Object System.Collections.Generic.List[string]

    foreach ($root in $roots) {
        if (-not $root) { continue }
        $binCandidates = @($root, (Join-Path $root "bin"))
        foreach ($candidate in $binCandidates) {
            if ($checked.Contains($candidate)) { continue }
            $checked.Add($candidate)

            $gcc = Join-Path $candidate "gcc.exe"
            $gxx = Join-Path $candidate "g++.exe"
            $make = Join-Path $candidate "mingw32-make.exe"
            $objdump = Join-Path $candidate "objdump.exe"
            if (-not (Test-Path -LiteralPath $gcc) -or
                -not (Test-Path -LiteralPath $gxx) -or
                -not (Test-Path -LiteralPath $make) -or
                -not (Test-Path -LiteralPath $objdump)) {
                continue
            }

            $target = (& $gcc -dumpmachine 2>$null | Out-String).Trim()
            if ($target -match "mingw32") {
                return (Resolve-Path -LiteralPath $candidate).Path
            }
            # Cygwin/MSYS GCC produce non-native DLLs; keep looking.
            $rejected.Add("$candidate ($target)")
        }
    }

    $detail = if ($rejected.Count -gt 0) {
        " Rejected: $($rejected -join '; ')."
    } else {
        ""
    }
    throw "MinGW-w64 not found. Pass -MingwRoot pointing at a directory " +
          "containing gcc.exe, g++.exe, mingw32-make.exe and objdump.exe.$detail"
}

function Resolve-JavaHome {
    param([string]$Explicit)

    $candidates = New-Object System.Collections.Generic.List[string]
    if ($Explicit) { $candidates.Add($Explicit) }
    if ($env:JAVA_HOME) { $candidates.Add($env:JAVA_HOME) }

    foreach ($candidate in $candidates) {
        if (-not $candidate) { continue }
        $jni = Join-Path $candidate "include\jni.h"
        $jniMd = Join-Path $candidate "include\win32\jni_md.h"
        if ((Test-Path -LiteralPath $jni) -and (Test-Path -LiteralPath $jniMd)) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }

    throw "JAVA_HOME not found or does not contain include\jni.h and " +
          "include\win32\jni_md.h. Pass -JavaHome."
}

$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $RepoRoot

$NativeDir = Join-Path $RepoRoot "native"
$SourceDir = Join-Path $RepoRoot "mgba-source\mgba(mGBA-Libretro)"
$BuildRoot = Join-Path $RepoRoot "build\native\windows-x64"
$CoreBuildDir = Join-Path $BuildRoot "mgba-libretro"
$FrontendBuildDir = Join-Path $BuildRoot "frontend"
$DistDir = Join-Path $BuildRoot "dist"

if (-not (Test-Path -LiteralPath $NativeDir)) {
    throw "native/ directory not found: $NativeDir"
}

$MingwRoot = Resolve-MingwRoot -Explicit $MingwRoot
$Gcc = Join-Path $MingwRoot "gcc.exe"
$Gxx = Join-Path $MingwRoot "g++.exe"
$Make = Join-Path $MingwRoot "mingw32-make.exe"
$Objdump = Join-Path $MingwRoot "objdump.exe"
$JavaHome = Resolve-JavaHome -Explicit $JavaHome

if (-not $Cmake) {
    $cmakeCommand = Get-Command cmake.exe -ErrorAction SilentlyContinue
    if (-not $cmakeCommand) {
        throw "cmake.exe not found on PATH. Pass -Cmake with an absolute path."
    }
    $Cmake = $cmakeCommand.Source
}

foreach ($required in @($Gcc, $Gxx, $Make, $Objdump, $Cmake)) {
    if (-not (Test-Path -LiteralPath $required)) {
        throw "Required tool not found: $required"
    }
}

# CMake invokes windres/ar from the same directory; keep MinGW first on PATH.
$env:PATH = "$MingwRoot;$env:PATH"

Write-Host ">> Toolchain" -ForegroundColor Cyan
Write-Host "   MinGW : $MingwRoot"
Write-Host "   Java  : $JavaHome"
Write-Host "   CMake : $Cmake"
Write-Host "   Jobs  : $Jobs"

if ($Clean) {
    foreach ($dir in @($CoreBuildDir, $FrontendBuildDir)) {
        if (Test-Path -LiteralPath $dir) {
            Write-Host ">> Removing build directory: $dir" -ForegroundColor Yellow
            Remove-Item -LiteralPath $dir -Recurse -Force
        }
    }
}

New-Item -ItemType Directory -Force -Path $CoreBuildDir | Out-Null
New-Item -ItemType Directory -Force -Path $FrontendBuildDir | Out-Null

# ---------------------------------------------------------------------------
# 1. mGBA Libretro core (optional)
# ---------------------------------------------------------------------------

$CoreDll = $null

if (-not $SkipCore) {
    if (-not (Test-Path -LiteralPath $SourceDir)) {
        throw "mGBA source not found: $SourceDir"
    }

    $ltoValue = if ($EnableLto) { "ON" } else { "OFF" }
    if ($EnableLto) {
        Write-Host ">> LTO enabled: this needs substantially more memory." -ForegroundColor Yellow
    }

    $coreConfigureArgs = @(
        "-S", $SourceDir,
        "-B", $CoreBuildDir,
        "-G", "MinGW Makefiles",
        "-DCMAKE_C_COMPILER=$Gcc",
        "-DCMAKE_CXX_COMPILER=$Gxx",
        "-DCMAKE_MAKE_PROGRAM=$Make",
        "-DCMAKE_BUILD_TYPE=$BuildType",
        "-DCMAKE_EXPORT_COMPILE_COMMANDS=ON",
        "-DCMAKE_SHARED_LINKER_FLAGS=-static-libgcc",
        "-DBUILD_LTO=$ltoValue",
        "-DBUILD_LIBRETRO=ON",
        "-DSKIP_LIBRARY=ON",
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

    Invoke-Native -FilePath $Cmake -Arguments $coreConfigureArgs `
        -Description "Configure mGBA Libretro core (Windows x64, $BuildType)"

    $coreBuildArgs = @("--build", $CoreBuildDir, "--target", "mgba_libretro")
    if ($Jobs -gt 1) {
        $coreBuildArgs += @("--parallel", "$Jobs")
    }

    try {
        Invoke-Native -FilePath $Cmake -Arguments $coreBuildArgs `
            -Description "Build mgba_libretro.dll"
    } catch {
        throw "Core build failed. If the compiler reported out-of-memory, " +
              "rerun with -Jobs 1. Original error: $($_.Exception.Message)"
    }

    $foundCore = Get-ChildItem -LiteralPath $CoreBuildDir -Recurse -Filter "mgba_libretro.dll" |
        Select-Object -First 1
    if (-not $foundCore) {
        throw "Build finished but mgba_libretro.dll was not found under $CoreBuildDir"
    }
    $CoreDll = $foundCore.FullName
    Write-Host ">> Core: $CoreDll" -ForegroundColor Green
} else {
    Write-Host ">> Skipping core build (-SkipCore)." -ForegroundColor Yellow
}

# ---------------------------------------------------------------------------
# 2. JNI frontend
# ---------------------------------------------------------------------------

$frontendConfigureArgs = @(
    "-S", $NativeDir,
    "-B", $FrontendBuildDir,
    "-G", "MinGW Makefiles",
    "-DCMAKE_C_COMPILER=$Gcc",
    "-DCMAKE_MAKE_PROGRAM=$Make",
    "-DCMAKE_BUILD_TYPE=$BuildType",
    "-DJAVA_HOME=$JavaHome",
    "-DBUILD_TESTING=OFF",
    "-DMGBA_SOURCE_DIR=$SourceDir"
)

Invoke-Native -FilePath $Cmake -Arguments $frontendConfigureArgs `
    -Description "Configure JNI frontend (Windows x64, $BuildType)"

$frontendBuildArgs = @("--build", $FrontendBuildDir, "--target", "mgba_frontend")
if ($Jobs -gt 1) {
    $frontendBuildArgs += @("--parallel", "$Jobs")
}
Invoke-Native -FilePath $Cmake -Arguments $frontendBuildArgs `
    -Description "Build mgba_frontend.dll"

$FrontendDll = Join-Path $FrontendBuildDir "mgba_frontend.dll"
if (-not (Test-Path -LiteralPath $FrontendDll)) {
    $foundFrontend = Get-ChildItem -LiteralPath $FrontendBuildDir -Recurse -Filter "mgba_frontend.dll" |
        Select-Object -First 1
    if (-not $foundFrontend) {
        throw "Build finished but mgba_frontend.dll was not found under $FrontendBuildDir"
    }
    $FrontendDll = $foundFrontend.FullName
}
Write-Host ">> Frontend: $FrontendDll" -ForegroundColor Green

# ---------------------------------------------------------------------------
# 3. Copy artifacts
# ---------------------------------------------------------------------------

New-Item -ItemType Directory -Force -Path $DistDir | Out-Null
$distFrontend = Join-Path $DistDir "mgba_frontend.dll"
Copy-Item -LiteralPath $FrontendDll -Destination $distFrontend -Force

$distCore = $null
if ($CoreDll) {
    $distCore = Join-Path $DistDir "mgba_libretro.dll"
    Copy-Item -LiteralPath $CoreDll -Destination $distCore -Force
}

Write-Host ">> Artifacts copied to: $DistDir" -ForegroundColor Green

# ---------------------------------------------------------------------------
# 4. Verify PE architecture, exports and imports
# ---------------------------------------------------------------------------

if (-not $SkipVerify) {
    $requiredJniSymbols = @(
        "nativeCreate", "nativeDestroy", "nativeInitialize", "nativeLoadGame",
        "nativeRunOnce", "nativeUnloadGame", "nativeShutdown", "nativeLastError",
        "nativeCoreName", "nativeCoreVersion", "nativeState", "nativeFrameCount",
        "nativeAudioFrameCount", "nativePixelFormat",
        "nativeAcquireFrame", "nativeReleaseFrame", "nativeFramesDropped",
        "nativeReadAudio", "nativeAudioQueued", "nativeAudioUnderruns",
        "nativeAudioOverflows", "nativeResetAudioStats", "nativeGetAvInfo",
        "nativeInputPress", "nativeInputRelease", "nativeInputReleaseSource",
        "nativeInputReset",
        "nativeSramSize", "nativeSaveSram", "nativeLoadSram",
        "nativeRtcSize", "nativeSaveRtc", "nativeLoadRtc",
        "nativeSerializeSize", "nativeSerialize", "nativeUnserialize",
        "nativeOptionCount", "nativeOptionKey", "nativeOptionValues",
        "nativeOptionCurrent", "nativeSetOption"
    )

    $requiredRetroSymbols = @(
        "retro_api_version", "retro_set_environment", "retro_set_video_refresh",
        "retro_set_audio_sample", "retro_set_audio_sample_batch",
        "retro_set_input_poll", "retro_set_input_state", "retro_init",
        "retro_deinit", "retro_get_system_info", "retro_get_system_av_info",
        "retro_load_game", "retro_unload_game", "retro_run", "retro_reset",
        "retro_serialize_size", "retro_serialize", "retro_unserialize",
        "retro_get_memory_data", "retro_get_memory_size"
    )

    $binaries = @($distFrontend)
    if ($distCore) { $binaries += $distCore }

    foreach ($binary in $binaries) {
        $name = Split-Path -Leaf $binary
        Write-Host ">> Verifying $name" -ForegroundColor Cyan

        $fileHeader = & $Objdump -f $binary
        if ($LASTEXITCODE -ne 0 -or -not ($fileHeader -match "pei-x86-64")) {
            throw "$name is not a PE x86-64 DLL"
        }

        $dump = & $Objdump -p $binary
        if ($LASTEXITCODE -ne 0) {
            throw "objdump -p failed for $name"
        }
        $dumpText = $dump -join "`n"

        if ($binary -eq $distFrontend) {
            $missing = @()
            foreach ($symbol in $requiredJniSymbols) {
                $jniName = "Java_com_mgba_bingbaihanji_emulator_NativeEmulator_$symbol"
                if ($dumpText -notmatch [regex]::Escape($jniName)) {
                    $missing += $jniName
                }
            }
            if ($missing.Count -gt 0) {
                throw "Missing JNI exports in $name`: $($missing -join ', ')"
            }
            Write-Host "   $($requiredJniSymbols.Count) JNI exports present." -ForegroundColor Green
        }

        if ($distCore -and $binary -eq $distCore) {
            $missing = @()
            foreach ($symbol in $requiredRetroSymbols) {
                if ($dumpText -notmatch ("\b" + [regex]::Escape($symbol) + "\b")) {
                    $missing += $symbol
                }
            }
            if ($missing.Count -gt 0) {
                throw "Missing Libretro exports in $name`: $($missing -join ', ')"
            }
            Write-Host "   $($requiredRetroSymbols.Count) retro_* exports present." -ForegroundColor Green
        }

        $imports = $dump | Select-String -Pattern "DLL Name:" |
            ForEach-Object { $_.Line.Trim() } | Sort-Object -Unique
        if ($imports) {
            Write-Host "   Imports:" -ForegroundColor DarkGray
            foreach ($import in $imports) {
                Write-Host "     $import"
            }
        }
    }
}

# ---------------------------------------------------------------------------
# 5. Build metadata
# ---------------------------------------------------------------------------

$gccVersion = (& $Gcc --version | Select-Object -First 1).Trim()
$cmakeVersion = (& $Cmake --version | Select-Object -First 1).Trim()

$infoLines = @(
    "build_type=$BuildType",
    "mingw_root=$MingwRoot",
    "gcc=$gccVersion",
    "cmake=$cmakeVersion",
    "java_home=$JavaHome",
    "jobs=$Jobs",
    "lto=$([bool]$EnableLto)",
    "frontend_artifact=$distFrontend",
    "frontend_sha256=$((Get-FileHash -LiteralPath $distFrontend -Algorithm SHA256).Hash)"
)
if ($distCore) {
    $infoLines += "core_artifact=$distCore"
    $infoLines += "core_sha256=$((Get-FileHash -LiteralPath $distCore -Algorithm SHA256).Hash)"
}
$infoLines += "built_at=$((Get-Date).ToString('o'))"

$infoPath = Join-Path $DistDir "build-info.txt"
Set-Content -LiteralPath $infoPath -Value ($infoLines -join [Environment]::NewLine) -Encoding UTF8

Write-Host ""
Write-Host "Windows native build succeeded." -ForegroundColor Green
Write-Host "  Output: $DistDir"
Write-Host "  Load in a desktop JVM with:"
Write-Host "    -Djava.library.path=$DistDir"
Write-Host '    System.loadLibrary("mgba_frontend")'
if ($distCore) {
    Write-Host "  Pass this core path to nativeInitialize():"
    Write-Host "    $distCore"
}
Write-Host "  Build info: $infoPath"

#Requires -Version 5.1
<#
.SYNOPSIS
    Run the host-side native unit tests for the session wrapper and loader.

.DESCRIPTION
    Builds native/ with the host compiler and runs test_session and
    test_core_loader through CTest. The default compiler is Cygwin GCC,
    which provides dlopen() for the dynamic-loader tests.

.PARAMETER BuildDir
    Host test build directory. Defaults to build/native/host-tests.

.PARAMETER CC
    Host C compiler. Defaults to D:/cygwin64/bin/gcc.exe.

.PARAMETER Clean
    Remove the host build directory before configuring.
#>
[CmdletBinding()]
param(
    [string]$BuildDir = "",
    [string]$CC = "D:/cygwin64/bin/gcc.exe",
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

$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $RepoRoot
$NativeDir = Join-Path $RepoRoot "native"
if (-not $BuildDir) {
    $BuildDir = Join-Path $RepoRoot "build\native\host-tests"
}

if (-not (Test-Path -LiteralPath $CC)) {
    throw "Host C compiler not found: $CC"
}
$compilerBin = Split-Path -Parent $CC
$env:PATH = "$compilerBin;$env:PATH"

$cmake = (Get-Command cmake -ErrorAction Stop).Source
$make = Join-Path $compilerBin "make.exe"
if (-not (Test-Path -LiteralPath $make)) {
    throw "make was not found next to the host compiler: $make"
}

if ($Clean -and (Test-Path -LiteralPath $BuildDir)) {
    Write-Host ">> Removing build directory: $BuildDir" -ForegroundColor Yellow
    Remove-Item -LiteralPath $BuildDir -Recurse -Force
}

Invoke-Native -FilePath $cmake -Arguments @(
    "-S", $NativeDir,
    "-B", $BuildDir,
    "-G", "Unix Makefiles",
    "-DCMAKE_C_COMPILER=$CC",
    "-DCMAKE_BUILD_TYPE=Debug",
    "-DBUILD_TESTING=ON"
) -Description "Configure host native tests"

Invoke-Native -FilePath $cmake -Arguments @("--build", $BuildDir, "--parallel") -Description "Build host native tests"
Invoke-Native -FilePath (Get-Command ctest -ErrorAction Stop).Source -Arguments @(
    "--test-dir", $BuildDir,
    "--output-on-failure"
) -Description "Run host native tests"

Write-Host "Host native tests succeeded." -ForegroundColor Green

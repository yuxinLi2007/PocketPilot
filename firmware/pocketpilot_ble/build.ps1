<#
  PocketPilot BLE - build script
  ------------------------------------------------------------------
  The ESP32 toolchain cannot handle non-ASCII paths (this project lives
  under D:\个人项目\...). This script mirrors the sources to an ASCII-only
  path, builds there, verifies the outputs, and copies the binaries plus
  a build record back into .\artifacts.

  Usage (PowerShell, from anywhere):
    .\build.ps1                 # incremental build
    .\build.ps1 -Clean          # wipe build dir and rebuild from scratch
    .\build.ps1 -Reconfigure    # re-run CMake (after sdkconfig changes)
    .\build.ps1 -FreshSdkconfig # drop mirrored sdkconfig first so sdkconfig.defaults wins again
    .\build.ps1 -IdfPath 'C:\Espressif\frameworks\esp-idf-v5.4.3'

  Exit code 0 only when the app binary, bootloader, partition table,
  ELF and map file all exist.
#>
[CmdletBinding()]
param(
    [string]$IdfPath   = 'C:\Espressif\frameworks\esp-idf-v5.4.3',
    [string]$MirrorDir = 'D:\pocketpilot_ble_build',
    [switch]$Clean,
    [switch]$Reconfigure,
    [switch]$FreshSdkconfig)

$ErrorActionPreference = 'Stop'

$src       = $PSScriptRoot
$artifacts = Join-Path $src 'artifacts'
$logDir    = Join-Path $artifacts 'logs'
New-Item -ItemType Directory -Force -Path $artifacts, $logDir | Out-Null

# ---- 1. ESP-IDF environment -------------------------------------------------
$exportPs1 = Join-Path $IdfPath 'export.ps1'
if (-not (Test-Path -LiteralPath $exportPs1)) {
    throw "ESP-IDF export script not found: $exportPs1 (override with -IdfPath)"
}
$env:PYTHONUTF8 = '1'
$env:PYTHONIOENCODING = 'utf-8'
. $exportPs1 | Out-Null
if (-not $env:IDF_PATH) { throw 'ESP-IDF environment did not initialise (IDF_PATH is empty).' }

# ---- 2. Mirror sources to an ASCII-only path --------------------------------
New-Item -ItemType Directory -Force -Path $MirrorDir | Out-Null
& robocopy $src $MirrorDir /E /NFL /NDL /NJH /NJS /XD build artifacts .git logs /XF '*.log' | Out-Null
$roboRc = $LASTEXITCODE
$global:LASTEXITCODE = 0
if ($roboRc -ge 8) { throw "robocopy failed with code $roboRc" }

# sdkconfig.defaults only applies when sdkconfig is (re)created. When a defaults
# change must take effect (partition table, PSRAM, ESP-SR models), drop the
# mirrored copy instead of hand-editing a generated file.
if ($FreshSdkconfig) {
    $freshPath = Join-Path $MirrorDir 'sdkconfig'
    if (Test-Path -LiteralPath $freshPath) {
        $mirrorRoot = (Resolve-Path -LiteralPath $MirrorDir).Path
        $resolved   = (Resolve-Path -LiteralPath $freshPath).Path
        if (-not $resolved.StartsWith($mirrorRoot, 'OrdinalIgnoreCase')) {
            throw "Refusing to delete outside the mirror directory: $resolved"
        }
        Remove-Item -LiteralPath $resolved -Force
        Write-Host '[build] removed mirrored sdkconfig (-FreshSdkconfig)'
    }
}

if ($Clean) {
    $buildDir = Join-Path $MirrorDir 'build'
    if (Test-Path -LiteralPath $buildDir) {
        $mirrorRoot = (Resolve-Path -LiteralPath $MirrorDir).Path
        $resolved   = (Resolve-Path -LiteralPath $buildDir).Path
        if (-not $resolved.StartsWith($mirrorRoot, 'OrdinalIgnoreCase')) {
            throw "Refusing to delete outside the mirror directory: $resolved"
        }
        Remove-Item -LiteralPath $resolved -Recurse -Force
        Write-Host '[build] cleaned previous build output'
    }
}

# ---- 3. Build ---------------------------------------------------------------
$buildLog = Join-Path $logDir 'build.log'
$rc = 1
Push-Location -LiteralPath $MirrorDir
try {
    # Without an sdkconfig, ESP-IDF defaults to esp32. This project is
    # esp32s3-only, so select the target before the first build or whenever
    # the mirrored sdkconfig points at a different chip.
    $target = 'esp32s3'
    $sdkconfigPath = Join-Path $MirrorDir 'sdkconfig'
    $targetMatches = $false
    if (Test-Path -LiteralPath $sdkconfigPath) {
        $targetLine = Select-String -LiteralPath $sdkconfigPath -Pattern '^CONFIG_IDF_TARGET="([^"]+)"' | Select-Object -First 1
        if ($targetLine -and $targetLine.Matches[0].Groups[1].Value -eq $target) {
            $targetMatches = $true
        }
    }
    if (-not $targetMatches) {
        Write-Host "[build] selecting target $target"
        & idf.py set-target $target 2>&1 | Tee-Object -FilePath (Join-Path $logDir 'set-target.log')
        if ($LASTEXITCODE -ne 0) { throw "set-target failed with exit code $LASTEXITCODE" }
    }
    if ($Reconfigure) {
        Write-Host '[build] idf.py reconfigure'
        & idf.py reconfigure 2>&1 | Tee-Object -FilePath (Join-Path $logDir 'reconfigure.log')
        if ($LASTEXITCODE -ne 0) { throw "reconfigure failed with exit code $LASTEXITCODE" }
    }
    Write-Host "[build] idf.py build   (mirror: $MirrorDir)"
    & idf.py build 2>&1 | Tee-Object -FilePath $buildLog
    $rc = $LASTEXITCODE
}
finally {
    Pop-Location
}
if ($rc -ne 0) { throw "Build failed with exit code $rc. See $buildLog" }

# ---- 4. Verify outputs ------------------------------------------------------
$need = [ordered]@{
    'pocketpilot_ble.bin'  = Join-Path $MirrorDir 'build\pocketpilot_ble.bin'
    'pocketpilot_ble.elf'  = Join-Path $MirrorDir 'build\pocketpilot_ble.elf'
    'pocketpilot_ble.map'  = Join-Path $MirrorDir 'build\pocketpilot_ble.map'
    'bootloader.bin'      = Join-Path $MirrorDir 'build\bootloader\bootloader.bin'
    'partition-table.bin' = Join-Path $MirrorDir 'build\partition_table\partition-table.bin'
    'srmodels.bin'        = Join-Path $MirrorDir 'build\srmodels\srmodels.bin'
    'flasher_args.json'   = Join-Path $MirrorDir 'build\flasher_args.json'
    'flash_args'          = Join-Path $MirrorDir 'build\flash_args'
    'sdkconfig'           = Join-Path $MirrorDir 'sdkconfig'
}
$missing = @()
foreach ($k in $need.Keys) {
    if (-not (Test-Path -LiteralPath $need[$k])) { $missing += $k }
}
if ($missing.Count -gt 0) {
    throw "Build returned success but these outputs are missing: $($missing -join ', ')"
}
foreach ($k in $need.Keys) {
    Copy-Item -LiteralPath $need[$k] -Destination (Join-Path $artifacts $k) -Force
}

$app = Get-Item -LiteralPath (Join-Path $artifacts 'pocketpilot_ble.bin')
$sha = (Get-FileHash -LiteralPath $app.FullName -Algorithm SHA256).Hash
$idfVersion = (& idf.py --version 2>&1 | Select-Object -Last 1)
$info = @(
    'PocketPilot BLE - build record'
    "built_at_utc     = $((Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ'))"
    "host             = $env:COMPUTERNAME"
    "esp_idf_path     = $env:IDF_PATH"
    "esp_idf_version  = $idfVersion"
    "source_dir       = $src"
    "mirror_dir       = $MirrorDir"
    "app_bin          = pocketpilot_ble.bin"
    "app_bin_bytes    = $($app.Length)"
    "app_bin_sha256   = $sha"
    "flash_size       = 8MB"
    "partition_table  = custom partitions.csv (factory 3MB + model 4MB)"
) -join "`r`n"
Set-Content -LiteralPath (Join-Path $artifacts 'BUILD-INFO.txt') -Value $info -Encoding UTF8

Write-Host ''
Write-Host '[build] OK' -ForegroundColor Green
Write-Host "  app     : $($app.FullName)  ($([math]::Round($app.Length / 1KB, 1)) KB)"
Write-Host "  sha256  : $sha"
Write-Host "  log     : $buildLog"

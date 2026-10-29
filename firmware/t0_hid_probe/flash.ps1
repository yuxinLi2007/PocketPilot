<#
  PocketPilot T0 - flash / monitor script
  ------------------------------------------------------------------
  Flashes the already-built firmware and (optionally) opens the serial
  monitor while recording everything to .\artifacts\logs\serial_*.log
  so the raw evidence for the T0 test sheet is kept.

  Usage:
    .\flash.ps1                       # auto-detect port, flash only
    .\flash.ps1 -Port COM5 -Monitor   # flash, then monitor + record log
    .\flash.ps1 -MonitorOnly -Monitor # monitor only (no re-flash)
    .\flash.ps1 -Erase -Monitor       # erase flash first (clears BLE bonds)

  Exit the monitor with Ctrl + ]  (that is the ESP-IDF default).
#>
[CmdletBinding()]
param(
    [string]$Port,
    [int]$Baud = 460800,
    [string]$IdfPath   = 'C:\Espressif\frameworks\esp-idf-v5.4.3',
    [string]$MirrorDir = 'D:\pocketpilot_t0_build',
    [switch]$Monitor,
    [switch]$MonitorOnly,
    [switch]$Erase
)

$ErrorActionPreference = 'Stop'

$src       = $PSScriptRoot
$artifacts = Join-Path $src 'artifacts'
$logDir    = Join-Path $artifacts 'logs'
New-Item -ItemType Directory -Force -Path $artifacts, $logDir | Out-Null

$exportPs1 = Join-Path $IdfPath 'export.ps1'
if (-not (Test-Path -LiteralPath $exportPs1)) { throw "ESP-IDF export script not found: $exportPs1" }
$env:PYTHONUTF8 = '1'
$env:PYTHONIOENCODING = 'utf-8'
. $exportPs1 | Out-Null

# ---- 1. Make sure there is something to flash -------------------------------
if (-not $MonitorOnly) {
    $appBin = Join-Path $MirrorDir 'build\esp_hid_device.bin'
    if (-not (Test-Path -LiteralPath $appBin)) {
        Write-Host '[flash] no build output found, running build.ps1 first ...'
        & (Join-Path $src 'build.ps1') -IdfPath $IdfPath -MirrorDir $MirrorDir
        if ($LASTEXITCODE -ne 0) { throw 'Build failed, cannot flash.' }
    }
}

# ---- 2. Resolve serial port -------------------------------------------------
function Find-EspPort {
    $ports = Get-CimInstance Win32_PnPEntity -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '\(COM\d+\)' } |
        ForEach-Object {
            [pscustomobject]@{
                Port = ([regex]::Match($_.Name, 'COM\d+')).Value
                Name = $_.Name
            }
        }
    if (-not $ports) { return $null }
    $preferred = $ports | Where-Object { $_.Name -match 'CP210|CH34|Silicon Labs|USB-SERIAL|Espressif|USB JTAG' }
    if ($preferred) { return @($preferred)[0].Port }
    if (@($ports).Count -eq 1) { return @($ports)[0].Port }
    Write-Host '[flash] several serial ports found; pass -Port explicitly:' -ForegroundColor Yellow
    $ports | ForEach-Object { Write-Host ("   {0}  {1}" -f $_.Port, $_.Name) }
    return $null
}

if ($MonitorOnly) { $Monitor = $true }
if (-not $Port) { $Port = Find-EspPort }
if (-not $Port) {
    throw 'No ESP32 serial port found. Plug the board into the PC with a data-capable USB cable, then run .\flash.ps1 -Port COMx (see Device Manager > Ports).'
}
Write-Host "[flash] using port $Port"

# ---- 3. Flash ---------------------------------------------------------------
Push-Location -LiteralPath $MirrorDir
try {
    if ($Erase) {
        Write-Host '[flash] erasing flash (this also removes BLE bonds) ...'
        & idf.py -p $Port erase-flash
        if ($LASTEXITCODE -ne 0) { throw 'erase-flash failed' }
    }
    if (-not $MonitorOnly) {
        Write-Host '[flash] writing firmware ...'
        & idf.py -p $Port -b $Baud flash
        if ($LASTEXITCODE -ne 0) { throw 'flash failed' }
        Write-Host '[flash] OK' -ForegroundColor Green
    }

    if ($Monitor) {
        $stamp   = Get-Date -Format 'yyyyMMdd_HHmmss'
        $logFile = Join-Path $logDir "serial_$stamp.log"
        $header  = @(
            "# PocketPilot T0 serial log"
            "# started_utc = $((Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ'))"
            "# port        = $Port"
            "# baud        = 115200"
            "# Exit with Ctrl+]"
        ) -join "`r`n"
        Write-Host "[monitor] recording to $logFile" -ForegroundColor Cyan
        Write-Host '[monitor] commands: a=vol+ z=vol- p=play/pause n=next b=prev m=mute ?=help  (Ctrl+] to quit)'
        # Write UTF-8 ourselves: Windows PowerShell 5.1's Tee-Object writes
        # UTF-16 by default, which the Python log converter cannot read.
        # The GDB warning on USB-Serial/JTAG is also treated as a native error
        # by PowerShell 7 unless native stderr handling is relaxed.
        $utf8 = New-Object System.Text.UTF8Encoding($false)
        $writer = New-Object System.IO.StreamWriter($logFile, $false, $utf8)
        $writer.AutoFlush = $true
        $oldEap = $ErrorActionPreference
        $oldNativeEap = $null
        if (Get-Variable -Name PSNativeCommandUseErrorActionPreference -ErrorAction SilentlyContinue) {
            $oldNativeEap = $PSNativeCommandUseErrorActionPreference
            $PSNativeCommandUseErrorActionPreference = $false
        }
        $ErrorActionPreference = 'Continue'
        try {
            $writer.Write($header)
            $writer.WriteLine()
            & idf.py -p $Port monitor -b 115200 2>&1 | ForEach-Object {
                $text = [string]$_
                $text = [regex]::Replace($text, '\x1B\[[0-?]*[ -/]*[@-~]', '')
                Write-Host $text
                $writer.WriteLine($text)
            }
        }
        finally {
            $writer.Dispose()
            $ErrorActionPreference = $oldEap
            if ($null -ne $oldNativeEap) {
                $PSNativeCommandUseErrorActionPreference = $oldNativeEap
            }
        }
    }
}
finally {
    Pop-Location
}

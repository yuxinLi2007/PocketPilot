<#
  Host test for the ESP32 receive frame assembler.
  Compiles main/pp_rx_assembler.c for the PC and feeds it the same fragmented
  byte streams produced by Android GattWriteChunker.

  Usage:
    .\run-host-tests.ps1
    .\run-host-tests.ps1 -ZigPath C:\path\to\zig.exe
#>
[CmdletBinding()]
param(
    [string]$ZigPath = ''
)

$ErrorActionPreference = 'Stop'

function Resolve-Zig {
    param([string]$Explicit)
    if ($Explicit) {
        if (-not (Test-Path -LiteralPath $Explicit)) { throw "zig not found: $Explicit" }
        return (Resolve-Path -LiteralPath $Explicit).Path
    }
    $cmd = Get-Command zig -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }

    $pythonCandidates = @(
        'C:\Espressif\python_env\idf5.4_py3.11_env\Scripts\python.exe',
        'python'
    )
    foreach ($python in $pythonCandidates) {
        try {
            $path = & $python -c "import ziglang, os; print(os.path.join(os.path.dirname(ziglang.__file__), 'zig.exe'))" 2>$null
            if ($LASTEXITCODE -eq 0 -and $path -and (Test-Path -LiteralPath $path)) {
                return (Resolve-Path -LiteralPath $path).Path
            }
        } catch {
        }
    }
    throw 'No C compiler found. Install ziglang (python -m pip install ziglang) or pass -ZigPath.'
}

$zig = Resolve-Zig -Explicit $ZigPath
$project = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$mainDir = Join-Path $project 'main'
$logDir = Join-Path $project 'artifacts\logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

$tempRoot = Join-Path $env:TEMP 'pocketpilot-rx-host-test'
$resolvedTemp = [System.IO.Path]::GetFullPath($tempRoot)
$resolvedBase = [System.IO.Path]::GetFullPath($env:TEMP)
if (-not $resolvedTemp.StartsWith($resolvedBase, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to use temp path outside TEMP: $resolvedTemp"
}
New-Item -ItemType Directory -Force -Path $tempRoot | Out-Null

Copy-Item -LiteralPath (Join-Path $mainDir 'pp_rx_assembler.c') -Destination $tempRoot -Force
Copy-Item -LiteralPath (Join-Path $mainDir 'pp_rx_assembler.h') -Destination $tempRoot -Force
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'test_pp_rx_assembler.c') -Destination $tempRoot -Force

$exe = Join-Path $tempRoot 'test_pp_rx_assembler.exe'
& $zig cc -std=c11 -Wall -Wextra -Werror -I $tempRoot `
    (Join-Path $tempRoot 'test_pp_rx_assembler.c') `
    (Join-Path $tempRoot 'pp_rx_assembler.c') `
    -o $exe
if ($LASTEXITCODE -ne 0) { throw "Host test compilation failed (zig cc exit $LASTEXITCODE)." }

$output = & $exe 2>&1
$testExit = $LASTEXITCODE
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$logPath = Join-Path $logDir "rx-assembler-host-$stamp.log"
$header = @(
    "PocketPilot ESP32 RX assembler host test",
    "time=$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')",
    "compiler=$zig",
    "source=$mainDir\pp_rx_assembler.c",
    "exit=$testExit"
)
$header + $output | Set-Content -LiteralPath $logPath -Encoding utf8
$output | ForEach-Object { Write-Host $_ }
Write-Host "log=$logPath"
exit $testExit

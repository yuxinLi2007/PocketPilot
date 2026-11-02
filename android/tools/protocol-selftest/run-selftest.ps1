# PocketPilot 协议自测：零依赖，只需 JDK 17+。
# 用法：powershell -ExecutionPolicy Bypass -File android\tools\protocol-selftest\run-selftest.ps1
$ErrorActionPreference = 'Stop'
$OutputEncoding = [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)

$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$out = Join-Path $root 'build\protocol-selftest'

$jdk = $env:JAVA_HOME
if (-not $jdk -or -not (Test-Path (Join-Path $jdk 'bin\javac.exe'))) {
    $jdk = 'D:\Android\jdk17'
}
$javac = Join-Path $jdk 'bin\javac.exe'
$java = Join-Path $jdk 'bin\java.exe'
if (-not (Test-Path $javac)) {
    $javac = 'javac'
    $java = 'java'
}

$core = Join-Path $root 'app\src\main\java\com\pocketpilot\app\core'
$sources = @(
    'CommandCodec.java', 'CommandType.java', 'CommandValidator.java',
    'CommandResult.java', 'ExecutionStatus.java', 'PocketCommand.java',
    'StatusFrameEncoder.java', 'GattWriteChunker.java',
    'TaskTextProtocol.java', 'TaskMessageGuard.java', 
    'PocketProtocol.java'
) | ForEach-Object { Join-Path $core $_ }

New-Item -ItemType Directory -Force -Path $out | Out-Null
& $javac -encoding UTF-8 -d $out @sources (Join-Path $PSScriptRoot 'ProtocolSelfTest.java')
if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }

& $java '-Dfile.encoding=UTF-8' -cp $out com.pocketpilot.app.protocoltest.ProtocolSelfTest
exit $LASTEXITCODE
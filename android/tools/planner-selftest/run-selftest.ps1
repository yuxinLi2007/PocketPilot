# PocketPilot 规划层自测：零依赖，只需 JDK 17+。
# 用法：powershell -ExecutionPolicy Bypass -File android\tools\planner-selftest\run-selftest.ps1
#
# 只编译 core 的纯 Java 部分 + plan 包，不引入 Android 依赖，因此没有模拟器也能快速回归。
# 通过仅代表“规划与校验逻辑正确”，不代表真实手机通知、真实云端模型或整机链路已验证。
$ErrorActionPreference = 'Stop'
$OutputEncoding = [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)

$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$out = Join-Path $root 'build\planner-selftest'

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

$base = Join-Path $root 'app\src\main\java\com\pocketpilot\app'
$core = Join-Path $base 'core'
$plan = Join-Path $base 'plan'
foreach ($dir in @($core, $plan)) {
    if (-not (Test-Path $dir)) { throw "missing source directory: $dir" }
}

# plan 包只依赖 core 的命令模型与校验器。core 里的 TargetConfig 引用了 Android API，
# 纯 JDK 环境编译不过，所以这里用显式列表而不是通配 core\*.java。
$coreFiles = @(
    'CommandType.java', 'CommandValidator.java', 'CommandResult.java',
    'ExecutionStatus.java', 'PocketCommand.java', 'TaskTextProtocol.java'
)
$sources = @($coreFiles | ForEach-Object { Join-Path $core $_ })
foreach ($f in $sources) {
    if (-not (Test-Path $f)) { throw "missing core source: $f" }
}
$sources += Get-ChildItem -Path $plan -Filter '*.java' | ForEach-Object { $_.FullName }
$sources += (Join-Path $PSScriptRoot 'PlannerSelfTest.java')

if (Test-Path $out) { Remove-Item -Recurse -Force -Path $out }
New-Item -ItemType Directory -Force -Path $out | Out-Null

Write-Host "compiling $($sources.Count) source files with $javac"
& $javac -encoding UTF-8 -d $out @sources
if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }

& $java '-Dfile.encoding=UTF-8' -cp $out com.pocketpilot.app.plannertest.PlannerSelfTest
exit $LASTEXITCODE

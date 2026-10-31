<#
  PocketPilot Android 模拟器回归脚本
  ---------------------------------------------------------------
  在 Android 模拟器上重跑“模拟 BLE 帧 -> 校验 -> 手机动作 -> 状态反馈”的
  关键用例，并把每个用例的 PASS / FAIL / SKIP 与原始证据打印出来。

  前提：
    * 模拟器已启动（默认 emulator-5554），系统 API 34
    * debug APK 已构建：android\app\build\outputs\apk\debug\app-debug.apk
    * 已安装 Mock 替代目标：android\mocktarget

  用法：
    .\run-emulator-regression.ps1
    .\run-emulator-regression.ps1 -Serial emulator-5554 -SkipInstall
    .\run-emulator-regression.ps1 -SkipGatt          # 跳过需要点 UI 的 GATT 用例

  说明：
    * 注入入口是 debug APK 内的 DebugInjectReceiver，属于“模拟输入”，不是真实 BLE。
    * 必须用 -p 指定包名：targetSdk 34 上隐式广播不会投递到 manifest receiver。
    * 帧里有 "|"，必须用单引号包住再交给设备端 shell，否则会被当成管道截断。
    * 脚本不清理应用数据；只在需要时临时开关无障碍服务，并在结束时恢复。
  * 第 7 节的多步骤任务同样走 DEBUG_TASK 广播注入，地址来源标记为 MOCK_FAKE_NOTIFICATION。
#>
[CmdletBinding()]
param(
    [string]$Serial   = 'emulator-5554',
    [string]$Apk      = '',
    [string]$Package  = 'com.pocketpilot.app',
    [string]$MockPkg  = 'com.pocketpilot.mocktarget',
    [string]$OutFile  = '',
    [switch]$SkipInstall,
    [switch]$SkipGatt
)

$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch { }

# $PSScriptRoot 在 `powershell -File` 调用下可能为空，这里做兜底解析，
# 保证 README 里的复现命令与直接运行脚本得到相同的默认路径。
$scriptDir = $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($scriptDir)) {
    $scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
}
if ([string]::IsNullOrWhiteSpace($scriptDir)) { throw 'cannot resolve script directory' }
if ([string]::IsNullOrWhiteSpace($Apk)) {
    $Apk = Join-Path $scriptDir '..\..\app\build\outputs\apk\debug\app-debug.apk'
}
if ([string]::IsNullOrWhiteSpace($OutFile)) {
    $OutFile = Join-Path $scriptDir '..\..\docs\emulator-regression-latest.txt'
}

$adb = 'D:\Android\Sdk\platform-tools\adb.exe'
if (-not (Test-Path -LiteralPath $adb)) { throw "adb not found: $adb" }
$frameMax      = 512
$mapAddress    = '北京市海淀区中关村大街1号'
$service       = "$Package/.service.PocketAccessibilityService"
$mapsComponent = 'com.google.android.apps.maps/com.google.android.maps.MapsActivity'
$mockMain      = "$MockPkg/.MainActivity"

# 断言用的精确片段：避免 "CONFIRMED" 命中 "EXECUTED_UNCONFIRMED" 这类子串
$S_NOT_INSTALLED  = '"status":"NOT_INSTALLED"'
$S_UNCONFIRMED    = '"status":"EXECUTED_UNCONFIRMED"'
$S_PERMISSION     = '"status":"PERMISSION_REQUIRED"'
$S_CONFIRMED      = '"status":"CONFIRMED"'

$script:lines = New-Object System.Collections.Generic.List[string]
function Log([string]$text, [string]$color = 'Gray') {
    Write-Host $text -ForegroundColor $color
    $script:lines.Add($text)
}

function Adb([string[]]$AdbArgs) {
    # PowerShell 5.1 在 ErrorActionPreference=Stop 下会把原生命令的 stderr 当成
    # NativeCommandError 抛出（例如 adb 的 "Activity not started" 提示），
    # 这里局部降级为 Continue，只把 stderr 当作普通输出返回。
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & $adb -s $Serial @AdbArgs 2>&1
    } finally {
        $ErrorActionPreference = $prevEap
    }
    return ($out -join "`n")
}

function Results {
    # 每条状态都会写入 files/results.jsonl；run-as 要求 debuggable 包（debug APK）
    return (Adb @('shell', 'run-as', $Package, 'cat', 'files/results.jsonl'))
}

function ResultCount { return ((Results) -split "`n").Count }

function Wait-ForResults {
    param([int]$From, [string]$Needle, [int]$TimeoutSec = 12)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        $all = (Results) -split "`n"
        if ($all.Count -gt $From) {
            $new = $all[$From..($all.Count - 1)] | Where-Object { $_ -match [regex]::Escape($Needle) }
            if ($new) { return ,@($new) }
        }
        Start-Sleep -Milliseconds 400
    }
    return ,@()
}

function Broadcast([string[]]$Extra) {
    # -p 包名 = 显式投递，绕过 targetSdk 34 的隐式广播限制
    $a = @('shell', 'am', 'broadcast', '-p', $Package)
    $a += $Extra
    return (Adb $a)
}

function Show-Pass($label, $needle, $record) {
    Log ("PASS  {0}  -> {1}" -f $label, $needle) 'Green'
    Log ("      evidence: {0}" -f ([string]$record).Trim()) 'DarkGray'
}

function Show-Fail($label, $needle) {
    $tail = (((Results) -split "`n" | Select-Object -Last 2) -join "`n") -replace "`n", ' | '
    Log ("FAIL  {0}  -> expected {1}" -f $label, $needle) 'Red'
    Log ("      last results: {0}" -f $tail) 'DarkGray'
}

function Inject-Raw([string]$frame, [string]$label, [string]$expect) {
    $before = ResultCount
    # 单引号防止设备端 shell 把 "|" 解释成管道
    Broadcast @('-a', "$Package.DEBUG_INJECT", '--es', 'raw', "'$frame'") | Out-Null
    $hit = Wait-ForResults -From $before -Needle $expect
    if ($hit.Count -gt 0) { Show-Pass $label $expect $hit[0]; return $true }
    Show-Fail $label $expect
    return $false
}

function Inject-Command {
    param(
        [string]$command,
        [string]$argument = $null,
        [int]$count = 1,
        [string]$label,
        [string]$expect
    )
    $before = ResultCount
    $a = @('-a', "$Package.DEBUG_INJECT", '--es', 'command', $command)
    if (-not [string]::IsNullOrEmpty($argument)) { $a += @('--es', 'argument', "'$argument'") }
    if ($count -gt 1) { $a += @('--ei', 'count', "$count") }
    Broadcast $a | Out-Null
    $hit = Wait-ForResults -From $before -Needle $expect
    if ($hit.Count -gt 0) { Show-Pass $label $expect $hit[0]; return $true }
    Show-Fail $label $expect
    return $false
}

# 不确定预期状态时使用：广播并返回新的 EXECUTED 记录
function Inject-Capture {
    param([string]$command, [string]$argument = $null, [int]$TimeoutSec = 15)
    $before = ResultCount
    $a = @('-a', "$Package.DEBUG_INJECT", '--es', 'command', $command)
    if (-not [string]::IsNullOrEmpty($argument)) { $a += @('--es', 'argument', "'$argument'") }
    Broadcast $a | Out-Null
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        $all = (Results) -split "`n"
        if ($all.Count -gt $before) {
            $new = $all[$before..($all.Count - 1)] | Where-Object { $_ -match '"event":"EXECUTED"|"event":"VALIDATION_REJECTED"' }
            if ($new) { return [string]($new | Select-Object -Last 1) }
        }
        Start-Sleep -Milliseconds 300
    }
    return ''
}

function New-RecordsSince {
    param([int]$From)
    $all = (Results) -split [char]10
    if ($all.Count -le $From) { return @() }
    return @($all[$From..($all.Count - 1)] | Where-Object { $_.Trim().Length -gt 0 })
}

# 注入一次多步骤任务并返回新产生的 TASK_* 记录（超时返回空串）。
# 这里的语句是 debug 广播注入的模拟输入：既不是真实语音识别结果，也不是真实 BLE 帧。
function Inject-TaskCapture {
    param(
        [string]$Utterance,
        [string]$Address = $null,
        [switch]$ClearAddress,
        [switch]$Confirm,
        [int]$TimeoutSec = 25
    )
    $before = ResultCount
    $a = @('-a', "$Package.DEBUG_TASK", '--es', 'utterance', "'$Utterance'")
    if ($ClearAddress) { $a += @('--ez', 'clear_address', 'true') }
    elseif (-not [string]::IsNullOrEmpty($Address)) { $a += @('--es', 'address', "'$Address'") }
    if ($Confirm) { $a += @('--ez', 'confirm', 'true') }
    Broadcast $a | Out-Null
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        $new = @(New-RecordsSince -From $before | Where-Object { $_ -match '"event":"TASK_' })
        if ($new.Count -gt 0) { return [string]($new | Select-Object -Last 1) }
        Start-Sleep -Milliseconds 400
    }
    return ''
}

function Assert-Task {
    param([string]$Label, [string]$Record, [string[]]$Needles)
    if ([string]::IsNullOrEmpty($Record)) {
        Log ("FAIL  {0}  -> 超时未产生 TASK_ 记录" -f $Label) 'Red'
        $tail = ((New-RecordsSince -From 0 | Select-Object -Last 2) -join ' | ')
        Log ("      last results: {0}" -f $tail) 'DarkGray'
        return $false
    }
    $missing = @($Needles | Where-Object { -not $Record.Contains($_) })
    if ($missing.Count -gt 0) {
        Log ("FAIL  {0}  -> 记录中缺少 {1}" -f $Label, ($missing -join ' , ')) 'Red'
        Log ("      record: {0}" -f $Record.Trim()) 'DarkGray'
        return $false
    }
    Show-Pass $Label ($Needles -join ' + ') $Record
    return $true
}

function Inject-TaskTextCapture {
    param(
        [string]$Text,
        [string]$MessageId,
        [int]$Count = 1,
        [string]$Address = $null,
        [switch]$ClearAddress,
        [string[]]$Needles = @(),
        [int]$TimeoutSec = 25
    )
    $before = ResultCount
    $a = @('-a', "$Package.DEBUG_INJECT", '--es', 'task_text', "'$Text'",
           '--es', 'message_id', $MessageId)
    if ($ClearAddress) { $a += @('--ez', 'clear_address', 'true') }
    elseif (-not [string]::IsNullOrEmpty($Address)) { $a += @('--es', 'address', "'$Address'") }
    if ($Count -gt 1) { $a += @('--ei', 'count', "$Count") }
    Broadcast $a | Out-Null
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        $new = @(New-RecordsSince -From $before)
        if ($new.Count -gt 0) {
            $joined = $new -join "`n"
            $missing = @($Needles | Where-Object { -not $joined.Contains($_) })
            if ($missing.Count -eq 0) { return $new }
        }
        Start-Sleep -Milliseconds 400
    }
    return @(New-RecordsSince -From $before)
}

function Assert-TaskTextRecords {
    param([string]$Label, [string[]]$Records, [string[]]$Needles)
    if ($Records.Count -eq 0) {
        Log ("FAIL  {0}  -> 超时未产生 COMMAND_TASK_TEXT 记录" -f $Label) 'Red'
        $tail = ((New-RecordsSince -From 0 | Select-Object -Last 2) -join ' | ')
        Log ("      last results: {0}" -f $tail) 'DarkGray'
        return $false
    }
    $joined = $Records -join "`n"
    $missing = @($Needles | Where-Object { -not $joined.Contains($_) })
    if ($missing.Count -gt 0) {
        Log ("FAIL  {0}  -> 记录中缺少 {1}" -f $Label, ($missing -join ' , ')) 'Red'
        Log ("      records: {0}" -f ($Records -join ' | ')) 'DarkGray'
        return $false
    }
    Show-Pass $Label ($Needles -join ' + ') ($Records -join ' | ')
    return $true
}

function Count-Text {
    param([string]$Haystack, [string]$Needle)
    if ([string]::IsNullOrEmpty($Haystack) -or [string]::IsNullOrEmpty($Needle)) { return 0 }
    return [regex]::Matches($Haystack, [regex]::Escape($Needle)).Count
}

function Assert-TextCount {
    param([string]$Label, [string]$Haystack, [string]$Needle, [int]$Expected)
    $actual = Count-Text $Haystack $Needle
    if ($actual -eq $Expected) {
        Log ("PASS  {0}  -> count({1}) = {2}" -f $Label, $Needle, $Expected) 'Green'
        return $true
    }
    Log ("FAIL  {0}  -> count({1}) = {2}, expected {3}" -f $Label, $Needle, $actual, $Expected) 'Red'
    return $false
}
function TopActivity {
    $dump = Adb @('shell', 'dumpsys', 'activity', 'activities')
    $line = [string](@($dump -split "`n" | Where-Object { $_ -match 'mResumedActivity|topResumedActivity' })[0])
    if (-not $line) { return '<unknown>' }
    return ($line -replace '.*\su0\s+', '').Trim()
}

function Wait-TopActivity {
    param([string]$Pattern, [int]$TimeoutSec = 12)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        $top = TopActivity
        if ($top -match $Pattern) { return $top }
        Start-Sleep -Milliseconds 500
    }
    return (TopActivity)
}

function UiXml {
    for ($i = 0; $i -lt 3; $i++) {
        $dump = Adb @('shell', 'uiautomator', 'dump', '/sdcard/pp-ui.xml')
        if ($dump -notmatch 'ERROR|Exception') {
            $xml = (Adb @('shell', 'cat', '/sdcard/pp-ui.xml'))
            if ($xml) { return ($xml -join "`n") }
        }
        Start-Sleep -Seconds 1
    }
    return ''
}

function ScrollToBottom([int]$times = 4) {
    for ($i = 0; $i -lt $times; $i++) {
        Adb @('shell', 'input', 'swipe', '540', '1900', '540', '500', '180') | Out-Null
        Start-Sleep -Milliseconds 350
    }
}

function ScrollToTop([int]$times = 4) {
    for ($i = 0; $i -lt $times; $i++) {
        Adb @('shell', 'input', 'swipe', '540', '500', '540', '1900', '180') | Out-Null
        Start-Sleep -Milliseconds 350
    }
}

function Get-NodeBounds([string]$xml, [string]$text) {
    foreach ($seg in ($xml -split '<node')) {
        if ($seg.Contains($text)) {
            $m = [regex]::Match($seg, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
            if ($m.Success) { return $m }
        }
    }
    return $null
}

function Tap-Text([string]$text) {
    $m = Get-NodeBounds (UiXml) $text
    if (-not $m) { return $false }
    $x = [int](([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2)
    $y = [int](([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2)
    Adb @('shell', 'input', 'tap', "$x", "$y") | Out-Null
    return $true
}

function AccessibilityBound {
    $dump = Adb @('shell', 'dumpsys', 'accessibility')
    return ($dump -match 'Bound services:\{[^}]*PocketPilot')
}

function AccessibilitySettingOn {
    # 应用内 PocketAccessibilityService.isEnabled() 也会读这个设置
    $v = (Adb @('shell', 'settings', 'get', 'secure', 'enabled_accessibility_services')).Trim()
    return ($v -match [regex]::Escape($service))
}

function Set-Accessibility {
    param([bool]$On, [int]$TimeoutSec = 20)
    if ($On) {
        Adb @('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', $service) | Out-Null
        Adb @('shell', 'settings', 'put', 'secure', 'accessibility_enabled', '1') | Out-Null
    } else {
        # 用 delete 而不是写空串：空串参数经 PowerShell 传给 adb 时可能被丢弃
        Adb @('shell', 'settings', 'delete', 'secure', 'enabled_accessibility_services') | Out-Null
        Adb @('shell', 'settings', 'put', 'secure', 'accessibility_enabled', '0') | Out-Null
    }
    # 系统解绑/绑定是异步的：设置值和 dumpsys 绑定状态都到位才算切换完成
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        if (((AccessibilitySettingOn) -eq $On) -and ((AccessibilityBound) -eq $On)) {
            Start-Sleep -Seconds 3
            return $true
        }
        Start-Sleep -Milliseconds 500
    }
    Log ("WARN  无障碍切换失败: setting={0} bound={1} target={2}" -f (AccessibilitySettingOn), (AccessibilityBound), $On) 'Yellow'
    return $false
}

function Get-Extra([string]$record, [string]$name) {
    $m = [regex]::Match($record, '"?before"?=([^;"]*?) -> (?:after=)?([^;"]+)')
    if ($name -eq 'status') {
        $s = [regex]::Match($record, '"status":"([A-Z_]+)"')
        if ($s.Success) { return $s.Groups[1].Value }
        return ''
    }
    if ($m.Success) {
        if ($name -eq 'before') { return $m.Groups[1].Value.Trim() }
        if ($name -eq 'after') { return $m.Groups[2].Value.Trim() }
    }
    return ''
}

# ------------------------------------------------------------------ 0. 准备
Log '=== PocketPilot 模拟器回归 ===' 'Cyan'
Log ("time   : {0}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))
Log ("serial : {0}" -f $Serial)

$devices = (& $adb devices) -join "`n"
if ($devices -notmatch [regex]::Escape($Serial) + '\s+device') {
    throw "Emulator '$Serial' not available. Start it first (emulator -avd PocketPilot_API34)."
}
Log ("device : Android {0} (API {1})" -f (Adb @('shell', 'getprop', 'ro.build.version.release')).Trim(), (Adb @('shell', 'getprop', 'ro.build.version.sdk')).Trim())

if (-not $SkipInstall) {
    if (-not (Test-Path -LiteralPath $Apk)) { throw "APK not found: $Apk (run gradlew assembleDebug)" }
    $install = Adb @('install', '-r', $Apk)
    if ($install -notmatch 'Success') { throw "APK install failed: $install" }
    Log ("install: OK  ({0})" -f $Apk)
}

# 目标配置回到“真实包名 + 系统地图选择”，避免把 Mock 结果当成真实应用
Broadcast @('-a', "$Package.DEBUG_CONFIG", '--es', 'wechat_package', 'com.tencent.mm', '--es', 'map_package', "''") | Out-Null
Start-Sleep -Milliseconds 500
Log 'targets : wechat=com.tencent.mm  map=<system resolver>'

$wechatInstalled = ((Adb @('shell', 'pm', 'path', 'com.tencent.mm')) -match 'package:')
$mapsInstalled   = ((Adb @('shell', 'pm', 'path', 'com.google.android.apps.maps')) -match 'package:')
$mockInstalled   = ((Adb @('shell', 'pm', 'path', $MockPkg)) -match 'package:')
Log ("results : {0} existing records" -f (ResultCount))
Log ''

$pass = 0; $fail = 0; $skip = 0
$originalA11y = AccessibilityBound

# ------------------------------------------------------- 1. 微信未安装
Log '--- 1. 命令校验与目标可用性 ---' 'Cyan'
Adb @('shell', 'am', 'start', '-n', "$Package/.ui.MainActivity") | Out-Null
Start-Sleep -Seconds 2
if (-not $wechatInstalled) {
    if (Inject-Command -command 'COMMAND_OPEN_WECHAT' -label 'COMMAND_OPEN_WECHAT (未安装)' -expect $S_NOT_INSTALLED) { $pass++ } else { $fail++ }
} else { Log 'SKIP  COMMAND_OPEN_WECHAT (未安装) — 模拟器已安装微信'; $skip++ }

# 1b. 地图目标指向不存在的包 -> 必须 NOT_INSTALLED，不能伪报成功
Broadcast @('-a', "$Package.DEBUG_CONFIG", '--es', 'map_package', 'com.pocketpilot.notexist') | Out-Null
Start-Sleep -Milliseconds 600
if (Inject-Command -command 'COMMAND_OPEN_MAP' -argument $mapAddress -label 'COMMAND_OPEN_MAP (目标包不存在)' -expect $S_NOT_INSTALLED) { $pass++ } else { $fail++ }
# 立刻恢复系统地图解析器，避免影响后续用例
Broadcast @('-a', "$Package.DEBUG_CONFIG", '--es', 'map_package', "''") | Out-Null
Start-Sleep -Milliseconds 600
# ------------------------------------------------------- 2. 地图（后台拉起）
Log '--- 2. 地图执行与状态 ---' 'Cyan'
Adb @('shell', 'input', 'keyevent', 'KEYCODE_HOME') | Out-Null
Start-Sleep -Milliseconds 800
$bgBefore = TopActivity
if (Inject-Command -command 'COMMAND_OPEN_MAP' -argument $mapAddress -label 'COMMAND_OPEN_MAP (系统解析器)' -expect $S_UNCONFIRMED) { $pass++ } else { $fail++ }
$bgAfter = Wait-TopActivity -Pattern 'maps|Maps|Resolver|resolver'
if ($bgAfter -match 'maps|Maps|Resolver|resolver') {
    Log ("PASS  前台由 {0} 变为 {1}（后台拉起）" -f $bgBefore, $bgAfter) 'Green'
    $pass++
} else {
    Log ("FAIL  地图未被拉起，前台仍为 {0}" -f $bgAfter) 'Red'
    $fail++
}

# ------------------------------------------------------- 3. 无障碍返回
Log '--- 3. 无障碍返回 ---' 'Cyan'

# 3a. 关闭无障碍 -> 明确拒绝
if (-not (Set-Accessibility -On $false)) {
    Log 'FAIL  无法关闭无障碍服务，跳过返回权限用例' 'Red'; $fail++
} elseif (Inject-Command -command 'COMMAND_BACK' -label 'COMMAND_BACK (无障碍关闭)' -expect $S_PERMISSION) { $pass++ } else { $fail++ }

# 3b. 恢复无障碍 -> Mock 第二页 -> 返回上一页
if (-not (Set-Accessibility -On $true)) {
    Log 'FAIL  无法启用无障碍服务，跳过返回执行用例' 'Red'; $fail++
} elseif (-not $mockInstalled) {
    Log 'SKIP  未安装 Mock 替代目标，无法验证“返回上一页”' 'Yellow'; $skip++
} else {
    Adb @('shell', 'am', 'start', '-n', $mockMain) | Out-Null
    Start-Sleep -Seconds 2
    if (-not (Tap-Text '进入第二页')) {
        Log 'FAIL  未能在 Mock 目标上进入第二页' 'Red'; $fail++
    } else {
        $detail = Wait-TopActivity -Pattern 'DetailActivity'
        if ($detail -notmatch 'DetailActivity') {
            Log ("FAIL  Mock 未进入第二页，前台 = {0}" -f $detail) 'Red'; $fail++
        } else {
            Log ("INFO  返回前前台 = {0}" -f $detail)
            if (Inject-Command -command 'COMMAND_BACK' -label 'COMMAND_BACK (无障碍开启，Mock 第二页)' -expect $S_CONFIRMED) { $pass++ } else { $fail++ }
            $afterBack = Wait-TopActivity -Pattern [regex]::Escape($MockPkg) -TimeoutSec 8
            if ($afterBack -match [regex]::Escape($MockPkg)) {
                Log ("PASS  返回后前台 = {0}（之前 {1}）" -f $afterBack, $detail) 'Green'
                $pass++
            } else {
                Log ("FAIL  返回后前台 = {0}（之前 {1}）" -f $afterBack, $detail) 'Red'
                $fail++
            }
        }
    }
}

# 3c. 真实 Google Maps 上的“不误报”不变式：窗口没变就不能报 CONFIRMED
if ($mapsInstalled) {
    Adb @('shell', 'am', 'start', '-n', "$Package/.ui.MainActivity") | Out-Null
    Start-Sleep -Milliseconds 800
    Adb @('shell', 'am', 'start', '-n', $mapsComponent) | Out-Null
    $mapsTop = Wait-TopActivity -Pattern 'Maps'
    $beforeRec = TopActivity
    $rec = Inject-Capture -command 'COMMAND_BACK'
    $status = Get-Extra $rec 'status'
    $before = Get-Extra $rec 'before'
    $after  = Get-Extra $rec 'after'
    if (-not $status) {
        Log 'FAIL  Maps 返回用例未产生执行记录' 'Red'; $fail++
    } elseif (($status -eq 'CONFIRMED') -and ($before -ne $after)) {
        Log ("PASS  Maps 返回：窗口变化 {0} -> {1}，状态 CONFIRMED" -f $before, $after) 'Green'
        $pass++
    } elseif (($status -eq 'EXECUTED_UNCONFIRMED') -and ($before -eq $after)) {
        Log ("PASS  不误报：窗口仍为 {0}，状态 EXECUTED_UNCONFIRMED（Maps 根页面返回会退出应用）" -f $before) 'Green'
        $pass++
    } else {
        Log ("FAIL  状态与窗口变化不一致：status={0} before={1} after={2}" -f $status, $before, $after) 'Red'
        $fail++
    }
} else {
    Log 'SKIP  模拟器未安装 Google Maps' 'Yellow'; $skip++
}

# ------------------------------------------------------- 4. 协议与参数边界
Log '--- 4. 协议与参数边界 ---' 'Cyan'
$cases = @(
    @{ raw = 'garbage';                               expect = 'frame_rejected=MALFORMED_FRAME';     label = '畸形帧 garbage' },
    @{ raw = ('PP2|COMMAND_OPEN_MAP|' + $mapAddress); expect = 'frame_rejected=UNSUPPORTED_VERSION'; label = '协议版本 PP2' },
    @{ raw = 'PP1|COMMAND_NOT_EXIST|';                expect = 'frame_rejected=UNKNOWN_COMMAND';     label = '未知命令 COMMAND_NOT_EXIST' },
    @{ raw = ('PP1|' + ('A' * ($frameMax + 1)));       expect = 'frame_rejected=FRAME_TOO_LONG';      label = "超长帧 $($frameMax + 1) 字节" }
)
foreach ($c in $cases) {
    if (Inject-Raw -frame $c.raw -label $c.label -expect $c.expect) { $pass++ } else { $fail++ }
}
if (Inject-Command -command 'COMMAND_OPEN_MAP' -label 'COMMAND_OPEN_MAP (缺地址)' -expect 'reason=MISSING_ARGUMENT') { $pass++ } else { $fail++ }
if (Inject-Command -command 'COMMAND_OPEN_MAP' -argument ('X' * 121) -label 'COMMAND_OPEN_MAP (地址 121 字符)' -expect 'reason=ARGUMENT_TOO_LONG') { $pass++ } else { $fail++ }

# ------------------------------------------------------- 5. 重复命令
Log '--- 5. 重复命令抑制 ---' 'Cyan'
$before = ResultCount
Broadcast @('-a', "$Package.DEBUG_INJECT", '--es', 'command', 'COMMAND_OPEN_WECHAT', '--ei', 'count', '2') | Out-Null
$dup = Wait-ForResults -From $before -Needle 'reason=DUPLICATE_SUPPRESSED'
if ($dup.Count -gt 0) {
    Show-Pass '连发两帧相同命令 -> 第二帧被抑制' 'reason=DUPLICATE_SUPPRESSED' $dup[0]
    $pass++
} else {
    Log 'FAIL  未观察到 DUPLICATE_SUPPRESSED' 'Red'; $fail++
}

# ------------------------------------------------------- 6. 真实 GATT 通道可切换
Log '--- 6. 真实 GATT 通道切换（模拟器无 BLE 外设，预期超时） ---' 'Cyan'
if ($SkipGatt) {
    Log 'SKIP  GATT 切换（-SkipGatt）'; $skip++
} else {
    Adb @('shell', 'am', 'start', '-n', "$Package/.ui.MainActivity") | Out-Null
    Start-Sleep -Seconds 2
    ScrollToBottom 4
    $xml = UiXml
    if (-not $xml.Contains('GATT')) {
        Log 'FAIL  UI 中未找到 GATT 卡片（uiautomator dump 失败？）' 'Red'; $fail++
    } elseif ($xml.Contains('切换到真实 GATT')) {
        if (Tap-Text '切换到真实 GATT') {
            Log 'INFO  已点击“切换到真实 GATT”，等待扫描结果 ...'
            $found = $false
            for ($i = 0; $i -lt 20; $i++) {
                Start-Sleep -Seconds 1
                if ((UiXml) -match '未发现 PocketPilot GATT 设备|真实 GATT 初始化失败') { $found = $true; break }
            }
            if ($found) {
                Log 'PASS  真实 GATT 通道可启动扫描，并在无设备时明确失败（未崩溃）' 'Green'; $pass++
            } else {
                Log 'FAIL  未见明确的 GATT 失败/超时提示' 'Red'; $fail++
            }
            ScrollToBottom 4
            if ((UiXml).Contains('切回模拟 BLE 通道')) {
                if (Tap-Text '切回模拟 BLE 通道') {
                    Start-Sleep -Seconds 2
                    ScrollToTop 4
                    if ((UiXml) -match '模拟 BLE · 非真实蓝牙') {
                        Log 'PASS  可切回模拟通道并恢复可用' 'Green'; $pass++
                    } else { Log 'FAIL  切回模拟通道后界面未恢复模拟标识' 'Red'; $fail++ }
                } else { Log 'FAIL  未能点击“切回模拟 BLE 通道”' 'Red'; $fail++ }
            } else { Log 'FAIL  未找到切回模拟通道的按钮' 'Red'; $fail++ }
        } else { Log 'FAIL  未能点击 GATT 切换按钮' 'Red'; $fail++ }
    } else {
        Log 'SKIP  当前处于真实 GATT 通道，跳过切换用例'; $skip++
    }
}

# ------------------------------------------------------- 7. AI 多步骤任务
Log '--- 7. AI 多步骤任务（规则规划器 + MOCK 地址上下文） ---' 'Cyan'
Log 'INFO  本节全部输入都是 debug 广播注入的模拟语句；地址来源恒为 MOCK_FAKE_NOTIFICATION。'
Log 'INFO  这不是真实语音识别、真实 BLE 帧，也不是真实手机通知。'

# 7a. 上下文里没有地址 -> BLOCKED，且一步都不许执行（绝不编造默认地址）
$before7a = ResultCount
$rec7a = Inject-TaskCapture -Utterance '把刚收到的地址打开地图' -ClearAddress
if (Assert-Task -Label '7a 任务“地址打开地图”（上下文无地址）' -Record $rec7a -Needles @(
        '"event":"TASK_BLOCKED"', '"status":"BLOCKED"',
        '"reasonCode":"NO_ADDRESS_IN_CONTEXT"', '"steps":[]')) { $pass++ } else { $fail++ }
$leak7a = @(New-RecordsSince -From $before7a | Where-Object { $_ -match '"command":"COMMAND_OPEN_MAP"' })
if ($leak7a.Count -eq 0) {
    Log 'PASS  7a 未编造地址：期间没有产生任何 COMMAND_OPEN_MAP 记录' 'Green'; $pass++
} else {
    Log ('FAIL  7a 无地址任务却产生了地图命令: ' + [string]$leak7a[0].Trim()) 'Red'; $fail++
}

# 7b. MOCK 地址 + Mock 替代目标 -> 已执行并确认（结论必须带 Mock 标记）
if (-not $mockInstalled) {
    Log 'SKIP  7b 未安装 Mock 替代目标，无法验证“已确认”路径' 'Yellow'; $skip++
} else {
    Broadcast @('-a', "$Package.DEBUG_CONFIG", '--es', 'map_package', $MockPkg) | Out-Null
    Start-Sleep -Milliseconds 600
    $rec7b = Inject-TaskCapture -Utterance '把刚收到的地址打开地图' -Address $mapAddress
    if (Assert-Task -Label '7b 任务“地址打开地图”（MOCK 地址 + Mock 替代目标）' -Record $rec7b -Needles @(
            '"event":"TASK_EXECUTED"', '"status":"COMPLETED_CONFIRMED"',
            '"addressSource":"MOCK_FAKE_NOTIFICATION"', '"plannerId":"RULE_V1"',
            ('"argument":"' + $mapAddress + '"'))) { $pass++ } else { $fail++ }
    $top7b = Wait-TopActivity -Pattern ([regex]::Escape($MockPkg)) -TimeoutSec 8
    if ($top7b -match [regex]::Escape($MockPkg)) {
        Log ("PASS  7b 前台确实到达 Mock 替代目标：{0}（Mock，不是真实地图）" -f $top7b) 'Green'; $pass++
    } else {
        Log ("FAIL  7b 前台未到达 Mock 替代目标：{0}" -f $top7b) 'Red'; $fail++
    }
}

# 7c. 同一句话，地图目标改回系统解析器 -> 只能“已执行·未确认”（诚实降级）
Broadcast @('-a', "$Package.DEBUG_CONFIG", '--es', 'map_package', "''") | Out-Null
Start-Sleep -Milliseconds 600
Adb @('shell', 'input', 'keyevent', 'KEYCODE_HOME') | Out-Null
Start-Sleep -Milliseconds 800
$rec7c = Inject-TaskCapture -Utterance '把刚收到的地址打开地图' -Address $mapAddress
if (Assert-Task -Label '7c 任务“地址打开地图”（系统地图解析器）' -Record $rec7c -Needles @(
        '"event":"TASK_EXECUTED"', '"status":"COMPLETED_UNCONFIRMED"',
        '"addressSource":"MOCK_FAKE_NOTIFICATION"')) { $pass++ } else { $fail++ }
$top7c = Wait-TopActivity -Pattern 'maps|Maps|Resolver|resolver' -TimeoutSec 10
if ($top7c -match 'maps|Maps|Resolver|resolver') {
    Log ("PASS  7c 前台变为 {0}（地图已被拉起，但搜索结果未经本应用确认）" -f $top7c) 'Green'; $pass++
} else {
    Log ("FAIL  7c 地图未被拉起，前台仍为 {0}" -f $top7c) 'Red'; $fail++
}

# 7d. 动作不在白名单 -> 明确拒绝，不产出计划、不执行任何动作
$before7d = ResultCount
$rec7d = Inject-TaskCapture -Utterance '给张三发消息说我到了'
if (Assert-Task -Label '7d 任务“发消息”（动作不在白名单）' -Record $rec7d -Needles @(
        '"event":"TASK_BLOCKED"', '"reasonCode":"UNSUPPORTED_ACTION"', '"steps":[]')) { $pass++ } else { $fail++ }
$leak7d = @(New-RecordsSince -From $before7d | Where-Object { $_ -match '"event":"EXECUTED"' })
if ($leak7d.Count -eq 0) {
    Log 'PASS  7d 被拒任务没有产生任何执行记录' 'Green'; $pass++
} else {
    Log ('FAIL  7d 被拒任务却执行了动作: ' + [string]$leak7d[0].Trim()) 'Red'; $fail++
}

# 7e. 没有规则能覆盖 -> 如实报“做不到”，不假装调用云端模型
$rec7e = Inject-TaskCapture -Utterance '帮我订一张明天去上海的机票'
if (Assert-Task -Label '7e 任务“订机票”（无规则匹配）' -Record $rec7e -Needles @(
        '"event":"TASK_BLOCKED"', '"reasonCode":"NO_RULE_MATCHED"',
        '"plannerId":"RULE_V1"', '"steps":[]')) { $pass++ } else { $fail++ }

# ------------------------------------------------------- 7A. 自然语言任务文本协议
Log '--- 7A. COMMAND_TASK_TEXT 协议（debug 模拟帧，不是真实 BLE） ---' 'Cyan'
Log 'INFO  本节通过 DebugInjectReceiver 注入真实格式的 TASK_TEXT 帧；输入仍是模拟数据。'
$stamp = Get-Date -Format 'HHmmssfff'

# 7A-a. 正常中文任务：真实任务帧格式 -> 现有任务入口 -> RULE_V1 -> Mock 地图执行
Broadcast @('-a', "$Package.DEBUG_CONFIG", '--es', 'map_package', $MockPkg) | Out-Null
Start-Sleep -Milliseconds 600
$cnId = "emu-cn-$stamp"
$cnRecords = @(Inject-TaskTextCapture -Text '把刚收到的地址打开地图' -MessageId $cnId `
    -Address $mapAddress -Needles @('"event":"TASK_TEXT_RECEIVED"', '"event":"TASK_TEXT_COMPLETED"',
        '"event":"TASK_EXECUTED"', '"plannerId":"RULE_V1"', '"addressSource":"MOCK_FAKE_NOTIFICATION"') -TimeoutSec 30)
if (Assert-TaskTextRecords -Label '7A-a 中文任务文本进入现有任务入口并执行' -Records $cnRecords -Needles @(
        '"command":"COMMAND_TASK_TEXT"', ('message_id=' + $cnId),
        '"event":"TASK_TEXT_RECEIVED"', '"event":"TASK_TEXT_COMPLETED"',
        '"event":"TASK_EXECUTED"', '"plannerId":"RULE_V1"',
        '"addressSource":"MOCK_FAKE_NOTIFICATION"', ('"argument":"' + $mapAddress + '"'))) { $pass++ } else { $fail++ }
$cnJoined = $cnRecords -join "`n"
if (Assert-TextCount -Label '7A-a 只调度一次任务' -Haystack $cnJoined -Needle '"event":"TASK_TEXT_RECEIVED"' -Expected 1) { $pass++ } else { $fail++ }
if (Assert-TextCount -Label '7A-a 只产生一条任务完成/映射记录' -Haystack $cnJoined -Needle '"event":"TASK_TEXT_COMPLETED"' -Expected 1) { $pass++ } else { $fail++ }
if (Assert-TextCount -Label '7A-a 只执行一次任务' -Haystack $cnJoined -Needle '"event":"TASK_EXECUTED"' -Expected 1) { $pass++ } else { $fail++ }
$top7a = Wait-TopActivity -Pattern ([regex]::Escape($MockPkg)) -TimeoutSec 8
if ($top7a -match [regex]::Escape($MockPkg)) {
    Log ("PASS  7A-a 前台到达 Mock 替代目标：{0}（Mock，不是真实地图）" -f $top7a) 'Green'; $pass++
} else {
    Log ("FAIL  7A-a 前台未到达 Mock 替代目标：{0}" -f $top7a) 'Red'; $fail++
}

# 7A-b. 正常英文文本：协议层接收成功，但规则规划器不猜测英文任务
$enId = "emu-en-$stamp"
$enRecords = @(Inject-TaskTextCapture -Text 'open map please' -MessageId $enId `
    -Needles @('"event":"TASK_TEXT_RECEIVED"', '"event":"TASK_BLOCKED"') -TimeoutSec 30)
$enJoined = $enRecords -join "`n"
if (Assert-TaskTextRecords -Label '7A-b 英文任务文本被协议接收并如实拒绝' -Records $enRecords -Needles @(
        '"command":"COMMAND_TASK_TEXT"', ('message_id=' + $enId),
        '"event":"TASK_TEXT_RECEIVED"', '"event":"TASK_BLOCKED"',
        '"reasonCode":"NO_RULE_MATCHED"', '"plannerId":"RULE_V1"')) { $pass++ } else { $fail++ }
if (Assert-TextCount -Label '7A-b 英文无规则任务没有执行动作' -Haystack $enJoined -Needle '"event":"EXECUTED"' -Expected 0) { $pass++ } else { $fail++ }

# 7A-c. 同一 message_id 重复两次：只调度一次，第二次按 DUPLICATE_MESSAGE_ID 抑制
$dupId = "emu-dup-$stamp"
$dupRecords = @(Inject-TaskTextCapture -Text '给张三发消息说我到了' -MessageId $dupId -Count 2 `
    -Needles @('"event":"TASK_BLOCKED"', '"event":"DUPLICATE_MESSAGE_ID"') -TimeoutSec 30)
$dupJoined = $dupRecords -join "`n"
if (Assert-TaskTextRecords -Label '7A-c 重复 message_id 被抑制且不支持动作仍被拒绝' -Records $dupRecords -Needles @(
        '"command":"COMMAND_TASK_TEXT"', ('message_id=' + $dupId),
        '"event":"TASK_TEXT_RECEIVED"', '"event":"TASK_BLOCKED"',
        '"reasonCode":"UNSUPPORTED_ACTION"', '"event":"DUPLICATE_MESSAGE_ID"')) { $pass++ } else { $fail++ }
if (Assert-TextCount -Label '7A-c 重复包只调度一次' -Haystack $dupJoined -Needle '"event":"TASK_TEXT_RECEIVED"' -Expected 1) { $pass++ } else { $fail++ }
if (Assert-TextCount -Label '7A-c 重复包只记录一次 DUPLICATE_MESSAGE_ID' -Haystack $dupJoined -Needle '"event":"DUPLICATE_MESSAGE_ID"' -Expected 1) { $pass++ } else { $fail++ }
if (Assert-TextCount -Label '7A-c 被拒绝任务没有执行动作' -Haystack $dupJoined -Needle '"event":"EXECUTED"' -Expected 0) { $pass++ } else { $fail++ }

# 7A-d. 畸形任务帧：空文本、超长声明、非法长度、长度不符、截断、非法 ID、错误版本
$taskCases = @(
    @{ raw = "PP1|COMMAND_TASK_TEXT|emu-empty-$stamp|0|";                  expect = 'frame_rejected=EMPTY_TASK_TEXT'; label = '7A-d 空任务文本' },
    @{ raw = "PP1|COMMAND_TASK_TEXT|emu-long-$stamp|385|abc";            expect = 'frame_rejected=TEXT_TOO_LONG';   label = '7A-d 超长声明 385 字节' },
    @{ raw = "PP1|COMMAND_TASK_TEXT|emu-badlen-$stamp|abc|打开地图";       expect = 'frame_rejected=INVALID_LENGTH';  label = '7A-d 非法长度' },
    @{ raw = "PP1|COMMAND_TASK_TEXT|emu-mismatch-$stamp|2|打开地图";       expect = 'frame_rejected=LENGTH_MISMATCH'; label = '7A-d 声明长度不符' },
    @{ raw = "PP1|COMMAND_TASK_TEXT|emu-trunc-$stamp|12";                expect = 'frame_rejected=MALFORMED_FRAME'; label = '7A-d 截断字段' },
    @{ raw = "PP1|COMMAND_TASK_TEXT|bad id $stamp|4|test";               expect = 'frame_rejected=INVALID_MESSAGE_ID'; label = '7A-d 非法 message_id' },
    @{ raw = "PP2|COMMAND_TASK_TEXT|emu-ver-$stamp|4|test";              expect = 'frame_rejected=UNSUPPORTED_VERSION'; label = '7A-d 错误协议版本' }
)
foreach ($tc in $taskCases) {
    if (Inject-Raw -frame $tc.raw -label $tc.label -expect $tc.expect) { $pass++ } else { $fail++ }
}

# 恢复地图目标，避免影响后续任务测试
Broadcast @('-a', "$Package.DEBUG_CONFIG", '--es', 'map_package', "''") | Out-Null
Start-Sleep -Milliseconds 600
# 7f. 无障碍关闭 -> 权限拦截，一步都不执行
if (-not (Set-Accessibility -On $false)) {
    Log 'FAIL  7f 无法关闭无障碍服务' 'Red'; $fail++
} else {
    $rec7f = Inject-TaskCapture -Utterance '返回上一页'
    if (Assert-Task -Label '7f 任务“返回上一页”（无障碍关闭）' -Record $rec7f -Needles @(
            '"event":"TASK_BLOCKED"', '"status":"BLOCKED"',
            '"reasonCode":"PERMISSION_REQUIRED"', '"steps":[]')) { $pass++ } else { $fail++ }
}

# 7g. 无障碍开启 + Mock 第二页 -> 任务真的把页面退回去
if (-not (Set-Accessibility -On $true)) {
    Log 'FAIL  7g 无法启用无障碍服务' 'Red'; $fail++
} elseif (-not $mockInstalled) {
    Log 'SKIP  7g 未安装 Mock 替代目标，无法验证任务级返回' 'Yellow'; $skip++
} else {
    Adb @('shell', 'am', 'start', '-n', $mockMain) | Out-Null
    Start-Sleep -Seconds 2
    if (-not (Tap-Text '进入第二页')) {
        Log 'FAIL  7g 未能在 Mock 目标上进入第二页' 'Red'; $fail++
    } else {
        $detail7g = Wait-TopActivity -Pattern 'DetailActivity'
        if ($detail7g -notmatch 'DetailActivity') {
            Log ("FAIL  7g Mock 未进入第二页，前台 = {0}" -f $detail7g) 'Red'; $fail++
        } else {
            $rec7g = Inject-TaskCapture -Utterance '帮我返回上一页'
            if (Assert-Task -Label '7g 任务“返回上一页”（无障碍开启，Mock 第二页）' -Record $rec7g -Needles @(
                    '"event":"TASK_EXECUTED"', '"status":"COMPLETED_CONFIRMED"',
                    '"type":"COMMAND_BACK"')) { $pass++ } else { $fail++ }
            $after7g = Wait-TopActivity -Pattern [regex]::Escape($MockPkg) -TimeoutSec 8
            if (($after7g -match [regex]::Escape($MockPkg)) -and ($after7g -notmatch 'DetailActivity')) {
                Log ("PASS  7g 返回后前台 = {0}（之前 {1}）" -f $after7g, $detail7g) 'Green'; $pass++
            } else {
                Log ("FAIL  7g 返回后前台 = {0}（之前 {1}）" -f $after7g, $detail7g) 'Red'; $fail++
            }
        }
    }
}

# 7h. 缺少 utterance -> 调试入口直接拒绝，不产生任务记录
Adb @('logcat', '-c') | Out-Null
$before7h = ResultCount
Broadcast @('-a', "$Package.DEBUG_TASK", '--ez', 'confirm', 'true') | Out-Null
Start-Sleep -Seconds 3
$new7h = @(New-RecordsSince -From $before7h | Where-Object { $_ -match '"event":"TASK_' })
if ($new7h.Count -eq 0) {
    Log 'PASS  7h 缺少 utterance 的任务被拒绝，未产生 TASK_ 记录' 'Green'
    $log7h = @((Adb @('logcat', '-d', '-s', 'PocketPilot/DebugInject')) -split [char]10 | Where-Object { $_ -match 'SIMULATED task rejected' })
    if ($log7h.Count -gt 0) { Log ("      evidence: {0}" -f ([string]$log7h[0]).Trim()) 'DarkGray' }
    $pass++
} else {
    Log ('FAIL  7h 缺少 utterance 仍产生任务记录: ' + [string]$new7h[0].Trim()) 'Red'; $fail++
}

# 7i. 敏感动作确认门：当前白名单（OPEN_MAP / OPEN_WECHAT / BACK）里没有敏感动作，
#     模拟器上无法构造真实的敏感任务；该逻辑由 tools/planner-selftest 的纯 Java 自测覆盖。
Log 'SKIP  7i 敏感动作确认门（白名单暂无敏感动作，已由 planner-selftest 覆盖）' 'Yellow'; $skip++

# 本节还原：地图目标回到系统解析器，清掉注入的 MOCK 地址上下文
Broadcast @('-a', "$Package.DEBUG_CONFIG", '--es', 'map_package', "''") | Out-Null
$null = Inject-TaskCapture -Utterance '清理上下文' -ClearAddress -TimeoutSec 15
Log 'INFO  已清理注入的 MOCK 地址（清理动作本身会留下一条 NO_RULE_MATCHED 记录）'
Adb @('shell', 'am', 'start', '-n', "$Package/.ui.MainActivity") | Out-Null
Start-Sleep -Seconds 1

# ------------------------------------------------------- 8. 还原环境
if ((AccessibilityBound) -ne $originalA11y) {
    [void](Set-Accessibility -On $originalA11y)
    Log 'restore: 无障碍服务已恢复为脚本运行前的状态'
}
Adb @('shell', 'am', 'start', '-n', "$Package/.ui.MainActivity") | Out-Null

Log ''
Log ("=== 结果：PASS {0}  FAIL {1}  SKIP {2} ===" -f $pass, $fail, $skip) $(if ($fail -eq 0) { 'Green' } else { 'Red' })
Log '说明：本脚本的所有输入都是 debug 注入的模拟帧，不代表真实 BLE、实体手机或真实微信/地图验收。'

$dir = Split-Path -Parent $OutFile
if ($dir -and -not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
Set-Content -LiteralPath $OutFile -Value ($script:lines -join "`r`n") -Encoding UTF8
Write-Host ("log     : {0}" -f $OutFile) -ForegroundColor Cyan

if ($fail -gt 0) { exit 1 }
# PocketPilot Android — 开发与复现指南

PocketPilot 的 Android 配套应用：接收 ESP32-S3-EYE 通过 BLE 发来的命令，校验权限与目标应用，执行允许的手机动作，并回传执行状态。

产品介绍见仓库根目录 [README](../README.md)；模拟器验收证据见 [docs/模拟器验收记录.md](docs/模拟器验收记录.md)；真实 BLE 进度见 [docs/真实BLE验证记录.md](docs/真实BLE验证记录.md)；多步骤任务（阶段 6）见 [docs/AI任务规划-阶段6.md](docs/AI任务规划-阶段6.md)。

## 目录结构

| 路径 | 职责 |
|---|---|
| `app/` | 主应用（`com.pocketpilot.app`） |
| `app/.../transport/` | `BleTransport` 接口、`FakeBleTransport`（模拟）、`GattBleTransport`（真实 GATT） |
| `app/.../core/` | 协议编解码、命令校验、协议常量、状态帧编码 |
| `app/.../runtime/` | 命令管线：解码 → 校验 → 执行 → 记录 → 反馈 |
| `app/.../exec/` | 微信、地图、返回三类动作执行器 |
| `app/.../plan/` | 多步骤任务规划层（纯 Java）：规则规划器、动作白名单、计划校验、任务结果 |
| `app/.../data/` | 执行证据落盘（`results.jsonl`）与地址上下文来源（当前为测试注入） |
| `app/.../service/` | 无障碍服务（返回动作与前台窗口观察） |
| `mocktarget/` | 明确标注的 Mock 目标应用，仅用于模拟器替代微信/地图 |
| `tools/protocol-selftest/` | 协议纯逻辑自测 |
| `tools/planner-selftest/` | 规划层纯逻辑自测（不需要设备） |
| `tools/emulator-test/` | 模拟器端到端回归脚本 |

## 环境

| 项目 | 版本 |
|---|---|
| JDK | 17（本机 `D:\Android\jdk17`） |
| Android SDK | platform 34、build-tools 34 |
| Gradle | 使用仓库内 `gradlew`，无需单独安装 |
| 模拟器 | `PocketPilot_API34`（Android 14 / API 34，`google_apis;x86_64`） |

`local.properties` 记录本机 SDK 路径，已被 `.gitignore` 忽略，不要提交。

## 构建与安装

```powershell
$env:JAVA_HOME='D:\Android\jdk17'
$env:ANDROID_HOME='D:\Android\Sdk'
$env:ANDROID_SDK_ROOT='D:\Android\Sdk'

cd 'D:\个人项目\android'
.\gradlew.bat assembleDebug --console=plain
```

产物：

```text
app\build\outputs\apk\debug\app-debug.apk
mocktarget\build\outputs\apk\debug\mocktarget-debug.apk
```

## 启动模拟器并运行

```powershell
Start-Process -FilePath 'D:\Android\Sdk\emulator\emulator.exe' `
  -ArgumentList '-avd','PocketPilot_API34','-no-snapshot-load'

$adb = 'D:\Android\Sdk\platform-tools\adb.exe'
& $adb wait-for-device
& $adb install -r 'app\build\outputs\apk\debug\app-debug.apk'
& $adb install -r 'mocktarget\build\outputs\apk\debug\mocktarget-debug.apk'
& $adb shell am start -n com.pocketpilot.app/.ui.MainActivity
```

## 测试

```powershell
# 协议纯逻辑自测
cd 'D:\个人项目\android'
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\protocol-selftest\run-selftest.ps1

# 规划层纯逻辑自测（多步骤任务）
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\planner-selftest\run-selftest.ps1

# 模拟器端到端回归（需要 emulator-5554 在线）
cd 'D:\个人项目\android\tools\emulator-test'
powershell -NoProfile -ExecutionPolicy Bypass -File .\run-emulator-regression.ps1
```

回归脚本会覆盖：微信未安装、地图拉起、无障碍关闭/开启、返回确认、协议与参数边界、重复命令抑制、真实 GATT 通道切换（无外设时预期超时），以及第 7 节的多步骤任务（无地址不得编造、Mock 目标可确认、系统解析器只能“已执行·未确认”、白名单外动作被拒、无规则匹配如实报告、权限拦截、任务级返回、缺 `utterance` 被拒）。

当前结果：模拟器回归 **PASS 30 / FAIL 0 / SKIP 1**，协议自测 **PASS 29 / FAIL 0**，规划层自测 **PASS 96 / FAIL 0**。

## 只存在于 debug APK 的测试入口

- `com.pocketpilot.app.DEBUG_INJECT` 广播：注入命令（`--es command` / `--es argument` / `--es raw`）。
- `com.pocketpilot.app.DEBUG_CONFIG` 广播：临时切换微信/地图目标包名。
- `com.pocketpilot.app.DEBUG_TASK` 广播：注入一次多步骤任务（`--es utterance` 必填，`--es address` / `--ez clear_address` / `--ez confirm`）。注入的语句不是真实语音识别结果，地址来源恒标记为 `MOCK_FAKE_NOTIFICATION`。

注入广播必须带 `-p com.pocketpilot.app`（Android 14 后台广播限制）。详细命令见验收记录第 5 节。

## 真实 BLE

- 设备广播名 `PocketPilot`，Service `6f706f63-6b65-742d-7069-6c6f74000001`。
- 设备 → 手机：`...0002` notify；手机 → 设备：`...0003` write。
- 命令帧 `PP1|<COMMAND>|<ARGUMENT>\n`，状态帧 `PP1|RESULT|<command_id>|<STATUS>|<detail>\n`。
- 主界面可切换“模拟通道 / 真实 GATT”；真实通道在无设备时会明确超时并允许切回。
- 连上后意外掉线会自动重连（最多 3 次，间隔 1.5 s）；首次扫描失败不做无限重试。

## 重要边界

- 模拟器里的所有命令都来自 `FakeBleTransport` 或 `adb` 注入，**不是**真实蓝牙。
- Mock 目标收到命令不等于微信/地图被打开；地图状态为 `EXECUTED_UNCONFIRMED` 才符合事实。
- 设备侧真实 BLE 已在实体 ESP32-S3-EYE 上跑通（电脑端完成扫描、订阅、三条命令通知、状态回写、断连重连），但**实体安卓手机 ↔ 开发板的端到端仍未验证**，详见 `docs/真实BLE验证记录.md`。
- 多步骤任务当前只由本地规则规划器（`RULE_V1`）驱动：云端规划未接入（`CloudPlannerStub` 恒返回 `UNAVAILABLE`），真实通知监听未实现，协议里也还没有承载自由语句的命令类型。详见 `docs/AI任务规划-阶段6.md` 第 7 节。
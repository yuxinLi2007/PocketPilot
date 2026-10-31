# PocketPilot 阶段 6：受约束的 AI 多步骤任务

- **记录时间**：2026-10-30
- **验证环境**：`PocketPilot_API34` / `emulator-5554`（Android 14, API 34），debug APK `com.pocketpilot.app`
- **范围**：Android App 内的任务规划层（`plan` 包）+ 运行时胶水层（`TaskController` / `CommandPipeline.runTaskStep`）
- **结论**：**规则规划器驱动的多步骤任务链路已在模拟器中跑通并留下可复核证据；真实通知监听、真实云端规划、实体手机端到端仍未验证。**

> 本阶段的“用户语句”是 `adb` 广播注入的文本，不是真实语音识别结果；
> 地址上下文来自 `FakeNotificationSource`，落盘恒标记 `MOCK_FAKE_NOTIFICATION`。
> 因此任何结论都不得升级为“手机真的收到了一条通知”或“语音已经能驱动任务”。

---

## 1. 设计边界

三条硬规则，写进代码而不是只写进文档：

1. **规划器只产出结构化计划**（`TaskPlan` = 有限的 `TaskStep` 列表），不产出代码、不产出任意坐标点击。
2. **计划必须过 `PlanValidator`**：动作白名单（`OPEN_MAP` / `OPEN_WECHAT` / `BACK`）、步数上限 `MAX_STEPS = 4`，并复用命令层 `CommandValidator`（参数长度、无障碍权限）。校验层不可被规划器绕过。
3. **任务步骤与 BLE 命令走同一条执行路径**（`CommandPipeline.processCommand`）。任务不获得任何特权：同样校验、同样落盘、同样受去重与权限约束。

### 状态语义（不允许把“发出命令”当成“任务完成”）

| `TaskResult.Status` | 含义 | `results.jsonl` 事件名 |
| --- | --- | --- |
| `COMPLETED_CONFIRMED` | 每一步都拿到可核对的执行证据 | `TASK_EXECUTED` |
| `COMPLETED_UNCONFIRMED` | 每一步都执行了，但至少一步只有“已执行·未确认” | `TASK_EXECUTED` |
| `BLOCKED` | 一步都没执行（缺数据 / 缺权限 / 缺确认 / 规划器做不到 / 校验拒绝） | `TASK_BLOCKED` |
| `FAILED` | 执行过，但有步骤失败 | `TASK_FAILED` |

`TaskRunner` 的另外两条行为：任一步未成功立即停止，后续步骤记为 `SKIPPED`；只有 `CONFIRMED` 与 `EXECUTED_UNCONFIRMED` 算成功。

---

## 2. 实现清单

### 规划层（纯 Java，不依赖 Android，可脱离设备自测）

路径前缀：`android/app/src/main/java/com/pocketpilot/app/`

| 文件 | 职责 |
| --- | --- |
| `plan/ContextFacts.java` | 规划器可见的上下文事实（地址、地址来源、无障碍是否开启） |
| `plan/TaskStep.java` / `plan/TaskPlan.java` | 单步动作与计划容器 |
| `plan/PlanOutcome.java` / `plan/PlanResult.java` | READY / NEEDS_CONFIRMATION / UNAVAILABLE / REJECTED 四态 |
| `plan/Planner.java` | 规划器接口（可替换为云端实现） |
| `plan/ActionPolicy.java` | 动作白名单与敏感动作集合 |
| `plan/PlanValidator.java` | 计划校验层，复用命令层校验规则 |
| `plan/RulePlanner.java` | 当前唯一真正产出计划的规划器（`RULE_V1`） |
| `plan/CloudPlannerStub.java` | 云端占位实现：恒返回 `UNAVAILABLE`，绝不编造计划 |
| `plan/TaskResult.java` | 任务级结果与单行 JSON 序列化（含 `addressSource`、每步 `argument`） |
| `plan/TaskRunner.java` | 规划 → 校验 → 逐步执行 → 聚合 |

### Android 胶水层

| 文件 | 职责 |
| --- | --- |
| `data/AddressContextProvider.java` | 地址上下文接口；权限状态由调用方传入，避免同一标志有两个来源 |
| `data/FakeNotificationSource.java` | 测试注入地址，来源恒为 `MOCK_FAKE_NOTIFICATION`；真实 `NotificationListenerService` **尚未实现** |
| `runtime/TaskController.java` | 组装点：上下文 → 规划 → 校验 → 执行 → 落盘 → UI 反馈；`run()` 禁止主线程调用，`submit()` 走内部单线程池 |
| `runtime/CommandPipeline.java` | 新增 `runTaskStep()`（15 s 超时转 `FAILED`，不抛异常）、`publishNotice()`、`awaitMockAck()`（最多等 2 s Mock 回执，等不到就如实报未确认） |
| `data/ResultsStore.java` | 新增 `appendRaw()`，任务级 JSON 与命令级记录写进同一个 `results.jsonl` |
| `PocketPilotApp.java` | 持有 `taskController()` / `addressSource()`；地址上下文跨传输切换保留 |
| `debug/DebugInjectReceiver.java` | 新增 `com.pocketpilot.app.DEBUG_TASK`（`utterance` 必填，`address` / `clear_address` / `confirm`），日志一律 `SIMULATED` 前缀 |
| `app/src/debug/AndroidManifest.xml` | 仅 debug 构建注册上述 receiver |

---

## 3. 当前能理解的语句（`RulePlanner`，`plannerId = RULE_V1`）

| 语句特征 | 产出计划 | 说明 |
| --- | --- | --- |
| 含“地址” + (“地图” / “导航” / “位置”) | `OPEN_MAP(上下文地址)` | 上下文没有地址 → `BLOCKED / NO_ADDRESS_IN_CONTEXT`，**不猜测、不使用默认地址** |
| 含“发送” / “发消息” | 无 | `BLOCKED / UNSUPPORTED_ACTION`（不在白名单） |
| 含“返回” / “后退” / “上一页” | `BACK` | 无障碍未开启 → `BLOCKED / PERMISSION_REQUIRED` |
| 含“打开” + “微信” | `OPEN_WECHAT` | 未安装 → `NOT_INSTALLED`，由执行层如实报告 |
| 含“打开” + (“地图” / “导航”) | `OPEN_MAP(上下文地址)` | 同样需要地址 |
| 其它 | 无 | `BLOCKED / NO_RULE_MATCHED`，detail 明确“未调用云端模型” |

匹配前会做 `normalize()`：用 `Character.getType()` 去掉空白与中英文标点，不改写用户原话（原话仍写进 `TaskPlan` 与 `results.jsonl`）。

---

## 4. 测试结果

### 4.1 规划层纯 Java 自测

```powershell
cd 'D:\个人项目\android\tools\planner-selftest'
powershell -ExecutionPolicy Bypass -File .\run-selftest.ps1
```

**结果：PASS 96 / FAIL 0**（`PLANNER_SELFTEST_OK`）。覆盖白名单拒绝、步数上限、缺确认拦截、执行失败中断与 `SKIPPED`、执行器抛异常 / 返回 null、JSON 单行转义、`MOCK_` 地址来源标记等。

### 4.2 模拟器端到端（回归脚本第 7 节）

```powershell
cd 'D:\个人项目\android\tools\emulator-test'
.\run-emulator-regression.ps1
```

**结果：PASS 30 / FAIL 0 / SKIP 1**（其中第 7 节任务用例 13 PASS + 1 SKIP）。

| 用例 | 输入（模拟） | 期望 | 结果 | 关键证据 |
| --- | --- | --- | --- | --- |
| 7a | 清空地址 + “把刚收到的地址打开地图” | `TASK_BLOCKED` / `NO_ADDRESS_IN_CONTEXT` / `"steps":[]` | **PASS** | 期间没有任何 `COMMAND_OPEN_MAP` 记录（未编造地址） |
| 7b | MOCK 地址 + 地图目标指向 Mock 替代目标 | `TASK_EXECUTED` / `COMPLETED_CONFIRMED` | **PASS** | `"addressSource":"MOCK_FAKE_NOTIFICATION"`；步骤 `argument` = 注入地址；前台确实到达 `com.pocketpilot.mocktarget/.MainActivity` |
| 7c | 同一句话，地图目标改回系统解析器 | `TASK_EXECUTED` / `COMPLETED_UNCONFIRMED` | **PASS** | 前台变为 `android/com.android.internal.app.ResolverActivity`；诚实降级为“已执行·未确认” |
| 7d | “给张三发消息说我到了” | `TASK_BLOCKED` / `UNSUPPORTED_ACTION` | **PASS** | 无任何 `"event":"EXECUTED"` 记录 |
| 7e | “帮我订一张明天去上海的机票” | `TASK_BLOCKED` / `NO_RULE_MATCHED` | **PASS** | detail = “没有匹配的规则；未调用云端模型” |
| 7f | 关闭无障碍 + “返回上一页” | `TASK_BLOCKED` / `PERMISSION_REQUIRED` / `"steps":[]` | **PASS** | 一步都未执行 |
| 7g | 开启无障碍 + Mock 第二页 + “帮我返回上一页” | `TASK_EXECUTED` / `COMPLETED_CONFIRMED` | **PASS** | `before=…DetailActivity -> after=…MainActivity`，前台确实退回 |
| 7h | 缺少 `utterance` extra | 调试入口拒绝，不产生任务记录 | **PASS** | logcat：`SIMULATED task rejected: missing or blank 'utterance' extra` |
| 7i | 敏感动作确认门 | — | **SKIP** | 当前白名单没有敏感动作，模拟器无法构造真实敏感任务；该逻辑由 4.1 的纯 Java 自测覆盖 |

### 4.3 既有回归未受影响

第 1–6 节仍为 **17 PASS / 0 FAIL**（命令校验与目标可用性、地图后台拉起、无障碍返回、协议与参数边界、重复命令抑制、真实 GATT 通道可切换）。协议自测 `tools/protocol-selftest` 仍为 **PASS 29 / FAIL 0**。

---

## 5. 证据

| 证据 | 位置 |
| --- | --- |
| 完整回归控制台记录（含每条 JSON） | `android/docs/emulator-regression-latest.txt` |
| 界面显示“任务未执行（NO_ADDRESS_IN_CONTEXT）…” | `android/docs/ui-task-blocked.png` / `.xml` |
| 界面显示“任务已完成并确认：全部 1 步已执行｜地址来自测试注入，非真实通知” | `android/docs/ui-task-confirmed.png` / `.xml` |
| 设备内任务级 JSONL | `adb -s emulator-5554 shell run-as com.pocketpilot.app cat files/results.jsonl` |

两条原始记录（直接从设备取出，未改写）：

```json
{"event":"TASK_BLOCKED","taskId":"","planId":"","utterance":"帮我订一张明天去上海的机票","plannerId":"RULE_V1","addressSource":"MOCK_FAKE_NOTIFICATION","status":"BLOCKED","reasonCode":"NO_RULE_MATCHED","detail":"没有匹配的规则；未调用云端模型","elapsedMillis":1,"steps":[]}
{"event":"TASK_EXECUTED","taskId":"TASK-cea6656c","planId":"TASK-cea6656c","utterance":"帮我返回上一页","plannerId":"RULE_V1","addressSource":"MOCK_FAKE_NOTIFICATION","status":"COMPLETED_CONFIRMED","reasonCode":"","detail":"全部 1 步已执行","elapsedMillis":517,"steps":[{"index":1,"type":"COMMAND_BACK","argument":"","status":"CONFIRMED","detail":"返回已生效，前台窗口发生变化","evidence":"before=com.pocketpilot.mocktarget/com.pocketpilot.mocktarget.DetailActivity -> after=com.pocketpilot.mocktarget/com.pocketpilot.mocktarget.MainActivity"}]}
```

---

## 6. 复现步骤

```powershell
# 1) 构建 debug APK
cd 'D:\个人项目\android'
$env:JAVA_HOME = 'D:\Android\jdk17'
.\gradlew.bat assembleDebug

# 2) 启动模拟器（若未运行）
& 'D:\Android\Sdk\emulator\emulator.exe' -avd PocketPilot_API34

# 3) 规划层纯 Java 自测
cd 'D:\个人项目\android\tools\planner-selftest'
powershell -ExecutionPolicy Bypass -File .\run-selftest.ps1

# 4) 模拟器端到端回归（自动安装 APK，跑全部 8 节）
cd 'D:\个人项目\android\tools\emulator-test'
.\run-emulator-regression.ps1

# 5) 手工注入一次任务（模拟输入，非真实语音 / 非真实 BLE）
$adb = 'D:\Android\Sdk\platform-tools\adb.exe'
& $adb -s emulator-5554 shell am broadcast -p com.pocketpilot.app -a com.pocketpilot.app.DEBUG_TASK --es utterance "'把刚收到的地址打开地图'" --es address "'北京市海淀区中关村大街1号'"
& $adb -s emulator-5554 shell run-as com.pocketpilot.app cat files/results.jsonl
```

Mock 替代目标（`android/mocktarget`，包名 `com.pocketpilot.mocktarget`）用于验证“已确认”路径；它不是微信，也不是真实地图。

---

## 7. 尚未验证（不得提前宣称）

| 项目 | 状态 | 原因 |
| --- | --- | --- |
| 真实通知监听（`NotificationListenerService`） | **未实现 / 未验证** | 当前只有 `FakeNotificationSource` 的 adb 注入；`REAL_NOTIFICATION_LISTENER` 这条来源还没有代码 |
| 真实语音驱动任务 | **未验证** | 阶段 5 的板上识别只覆盖 3 条固定命令词，尚未把自由语句送进规划器 |
| ESP32 → 任务语句的协议扩展 | **未实现** | 现有协议只有 `COMMAND_OPEN_WECHAT/OPEN_MAP/BACK` 三种帧，没有承载自由文本的命令类型 |
| 云端 / LLM 规划 | **未接入** | 没有可用 API 配置；`CloudPlannerStub` 恒返回 `UNAVAILABLE` |
| 真实微信、真实地图搜索结果确认 | **未验证** | 模拟器未安装微信；第三方地图最多只能“已执行·未确认” |
| 实体安卓手机端到端 | **未验证** | 当前没有实体安卓手机 |
| 真实 BLE 与手机联调 | **未验证** | 已通过的是电脑端 GATT 契约（见 `android/docs/真实BLE验证记录.md`），不是手机 |
| 敏感动作确认门（真机） | **仅自测覆盖** | 白名单里当前没有敏感动作 |
| 识别率 / 响应时间基线 | **未建立** | 样本量不足；ESP-SR 的 `score` 量纲未确定，**禁止当置信度引用** |

---

## 8. 下一步（按依赖顺序）

1. **协议扩展**：新增承载自由文本的命令类型（例如 `COMMAND_TASK_TEXT`），同步改 ESP32 侧帧构造、`CommandCodec`、`CommandValidator` 与两套自测。
2. **真实 `NotificationListenerService`**：需要用户在实体手机上手动授权，来源标记改为 `REAL_NOTIFICATION_LISTENER`。
3. **云端规划接入**：仅在拿到可用 API 配置后启用，且模型输出仍必须过 `PlanValidator`。
4. **实体安卓手机 + ESP32-S3-EYE 同场联调**：只有这一步完成，才能把“语音 → 任务 → 手机动作 → 状态反馈”整链标记为已验证。

# PocketPilot 阶段 7A：BLE 任务文本协议

- **记录时间**：2026-10-30
- **验证环境**：Windows + `PocketPilot_API34` / `emulator-5554`（Android 14, API 34）；ESP-IDF v5.4.3；无实体 Android 手机
- **范围**：协议层（`core/CommandCodec`、`core/TaskTextProtocol`、`core/TaskMessageGuard`）、Android GATT 接收重组（`transport/GattBleTransport`）、ESP32 发送分片（`firmware/pocketpilot_ble`）、以及相应测试
- **结论**：`COMMAND_TASK_TEXT` 帧格式已在两端实现并通过纯 Java 自测与模拟器回归；**真实 BLE、实体手机、真实语音识别仍未验证**。

> 本阶段所有“任务文本”输入均为 `adb` 调试广播注入的模拟帧，或纯 Java 单元自测构造的字节数组。
> 它们不是真实 BLE notify，不是真实语音识别结果。

---

## 1. 协议帧实际格式

沿用原 GATT 服务与特征，UUID 未改动：

| 方向 | 特征 | 属性 |
| --- | --- | --- |
| 设备 → 手机 | `6f706f63-6b65-742d-7069-6c6f74000002` | notify |
| 手机 → 设备 | `6f706f63-6b65-742d-7069-6c6f74000003` | write |

编码：**UTF-8 文本 + `\n` 结尾**，整帧（含换行）最多 **512 字节**，即换行前最多 **511 字节**。

### 1.1 旧固定命令（语义完全不变）

```text
PP1|COMMAND_OPEN_WECHAT\n
PP1|COMMAND_OPEN_MAP|<地址>\n
PP1|COMMAND_BACK\n
```

### 1.2 新增任务文本命令

```text
PP1|COMMAND_TASK_TEXT|<message_id>|<utf8_byte_length>|<text>\n
```

字段说明：

| 字段 | 约束 |
| --- | --- |
| 协议版本 | 固定 `PP1`；其它值一律 `UNSUPPORTED_VERSION` |
| 命令类型 | 固定 `COMMAND_TASK_TEXT` |
| `message_id` | 1–64 个 ASCII 字符，仅允许 `A-Z a-z 0-9 . _ : -`；用于结果关联与重复抑制 |
| `utf8_byte_length` | 十进制非负整数，`text` 的 UTF-8 字节数；1–384 |
| `text` | 1–384 个 UTF-8 字节；禁止空、纯空白、含 `\r` 或 `\n`；**位于最后一个字段，可以含 `|`** |

示例：

```text
PP1|COMMAND_TASK_TEXT|TASK-0001a2b3-0007|33|把刚收到的地址打开地图\n
```

### 1.3 为什么文本字段放最后

旧帧 `COMMAND_OPEN_MAP` 的地址本身就可能包含 `|`。把 `text` 放在末位、其余字段定长可校验，可以在不引入转义层的前提下保持旧命令语义完全不变。

---

## 2. 文本长度上限与依据

| 常量 | 值 | 位置 | 依据 |
| --- | --- | --- | --- |
| `MAX_TEXT_BYTES` | 384 | `TaskTextProtocol` / 固件 `MAX_TASK_TEXT_BYTES` | 中文 UTF-8 每字 3 字节，384 ≈ 128 个汉字；足够表达一句话任务 |
| `MAX_MESSAGE_ID_CHARS` | 64 | `TaskTextProtocol` / 固件 `MAX_MESSAGE_ID_CHARS` | 设备端生成 ID 形如 `TASK-<ms>-<seq>`，远小于 64；留足余量 |
| `MAX_FRAME_BYTES` | 512 | `CommandCodec` / 固件 `MAX_FRAME_BYTES` | 阶段 7 之前已存在的帧预算，本阶段沿用不扩大 |
| `MAX_PAYLOAD_BYTES` | 511 | `CommandCodec` | 512 减去 1 个换行 |

最坏情况核算：`PP1|` (4) + `COMMAND_TASK_TEXT` (17) + `|` (1) + 64 字符 ID + `|` (1) + 3 位长度 + `|` (1) + 384 字节中文 + `\n` (1) = **476 字节 < 512**，因此 384 与 64 两个上限可以同时取到而不溢出帧预算。

发送端（`CommandCodec.encodeTaskText` / 固件 `pp_send_task_text`）与接收端（`CommandCodec.decode`）使用**同一组常量**校验：长度按 UTF-8 字节而非 Java/C 字符数计算，否则中文会被低估。

---

## 3. BLE 分包与重组的实际实现

### 3.1 设备 → 手机（ESP32 发送）

`firmware/pocketpilot_ble/main/pocketpilot_ble_main.c`：

- 本地 MTU 目标 `TARGET_MTU = 185`（`esp_ble_gatt_set_local_mtu`），未协商成功时回落 `DEFAULT_MTU = 23`。
- 分片大小 `pp_chunk_size() = s_mtu - 3`（ATT 头 3 字节）；MTU 23 时每片 20 字节。
- `pp_send_frame()` 按片调用 `esp_ble_gatts_send_indicate(...)`，每片后 `vTaskDelay(15ms)`，等待拥塞解除，最多重试 5 次。
- **发送互斥**：`s_tx_lock`（FreeRTOS mutex）保证同一时刻只有一个生产者使用通知通道，避免两个发送方的分片交错。
- 发送途中若断连或取消订阅，`PP_SEND_ABORTED` 并中断，不会假装发送成功。
- 长度上限在构造帧之前就校验，超限直接 `pp_reject`，不进入发送流程。

### 3.2 手机 → 设备（Android 回传状态）

`GattBleTransport.send()` 把整帧入队（上限 16 帧，满则丢最旧并报错），逐帧 `WRITE_TYPE_DEFAULT` 写入。

> **已知限制（未验证）**：Android → 设备方向目前**不做分片**，一条状态帧必须能在单个 BLE 写入内完成。长 `RESULT` 帧（> MTU-3）在真实链路上是否会被截断或写入失败，尚未在实体手机上验证。这是本阶段明确保留的缺口，不是已通过项。

### 3.3 手机端帧重组

`CommandCodec.FrameAssembler`（每个 GATT 连接各自实例化）：

- 逐字节累积，遇到 `\n` 切出一帧；无 `\n` 的数据继续累积。
- 累积超过 511 字节仍无换行 → 丢弃半帧并抛 `FRAME_TOO_LONG`（`reset()`），不会静默溢出。
- 半帧超过 **2 秒**（`FRAME_TIMEOUT_MS = 2000`）没有新数据 → 丢弃旧半帧；**本次新到的分片仍按新帧继续处理**，通过 `consumeTimeoutDropped()` 上报一次性 `FRAME_TIMEOUT` 事件，通道不停止。
- `GattBleTransport` 用 `Handler` 定时器（`FRAME_TIMEOUT_MS + 100ms`，必要时 250ms 复查）驱动看门狗，并在有半帧时才挂定时器。
- 重组完成后做**严格 UTF-8 解码**（`CodingErrorAction.REPORT`），非法字节序列 → `INVALID_UTF8`，不会被替换成 `` 后继续执行。

### 3.4 乱序

协议**没有分片序号字段**。因此：

- 分片在正常链路上按序到达时可以完整重组（已测）。
- **任意乱序不做恢复**，也不会被误认为合法任务：乱序字节重组出的文本在 `decode` 阶段被拒绝（已测，`out-of-order bytes rejected`）。

这是设计上的明确选择，不是遗漏。若将来需要抗乱序，必须升级协议版本并加分片序号，不能在本阶段悄悄改变 `PP1` 语义。

---

## 4. 重复消息抑制

两套机制并存，互不替代：

| 机制 | 适用 | 行为 |
| --- | --- | --- |
| `CommandValidator` 300 ms 同类型去重 | 三种固定命令 | 保持原有行为不变 |
| `TaskMessageGuard` | `COMMAND_TASK_TEXT` | 按 `message_id` 去重，TTL 60 秒，容量 64，LRU 淘汰 |

为什么固定命令的去重规则不能用于任务文本：两条**不同**任务可能在 300 ms 内先后到达（例如用户连续说了两句话），按类型去重会误杀第二条。因此任务文本必须按协议消息 ID 去重。

重复包的处理是：记一条 `DUPLICATE_MESSAGE_ID`，**不再进入规划器、不再执行任何动作**，并保留首次到达的时间戳（重复包不会无限延长去重窗口）。

---

## 5. 安全边界：任务文本不能直接执行

`COMMAND_TASK_TEXT` 的路径是：

```text
BLE 帧 → CommandCodec.decode → CommandPipeline.handleTaskText
      → TaskMessageGuard 去重 → TaskDispatcher（PocketPilotApp 接到 TaskController）
      → RulePlanner 生成 TaskPlan → PlanValidator（动作白名单 + 步数上限 + CommandValidator）
      → TaskRunner → 原有 CommandPipeline.processCommand 执行
```

关键约束（在代码里，不只在文档里）：

1. `TASK_TEXT` **不在执行器表**（`ExecutorRegistry`）中，无法被当作可执行命令直接派发。`CommandCodec.encodeText()` 收到 `TASK_TEXT` 直接抛 `IllegalArgumentException`。
2. `TaskDispatcher` 未装配时，文本一律 `TASK_DISPATCHER_UNAVAILABLE` 失败，**不会降级为直接执行**。
3. 规划器返回 `BLOCKED`（`UNSUPPORTED_ACTION` / `NO_RULE_MATCHED` / `NO_ADDRESS_IN_CONTEXT` / `PERMISSION_REQUIRED`）时，任务保持拒绝态，零动作执行。
4. 任务状态映射保留四态语义：`COMPLETED_CONFIRMED` / `COMPLETED_UNCONFIRMED` / `BLOCKED` / `FAILED`，不把 `BLOCKED` 伪装成 `FAILED`，也不把“帧已接收”当成“动作已执行”。

---

## 6. 测试结果（本轮实际执行）

### 6.1 纯 Java 协议自测

```text
PASS 76  FAIL 0
PROTOCOL_SELFTEST_OK
```

### 6.2 纯 Java 规划层自测

```text
PASS 96  FAIL 0
PLANNER_SELFTEST_OK
```

### 6.3 Android 构建

```text
BUILD SUCCESSFUL in 1s
67 actionable tasks: 67 up-to-date
```

### 6.4 模拟器回归（`emulator-5554`, Android 14 / API 34）

```text
=== 结果：PASS 48  FAIL 0  SKIP 1 ===
```

日志：`android/docs/emulator-regression-latest.txt`

> 回归期间发现过一次时序 flake：7g「无障碍返回」用例曾在 500 ms 确认窗口内读到 `EXECUTED_UNCONFIRMED`，而随后断言时前台已经切换。第三次完整回归复跑通过，未修改任何断言。

### 6.5 ESP32 固件构建

```text
pocketpilot_ble.bin binary size 0x170190 bytes. Smallest app partition is 0x3000000 bytes.
0x18fe70 bytes (52%) free.
[build] OK
```

产物：`firmware/pocketpilot_ble/artifacts/pocketpilot_ble.bin`（1472.4 KB，sha256 `BB579D5C…5CE7`）

> 构建成功 ≠ BLE 联调通过。固件尚未烧录到开发板做真实链路验证。

---

## 7. 逐项测试矩阵

### 7.1 需求要求的覆盖项

| # | 要求 | 结果 | 证据 |
| --- | --- | --- | --- |
| 1 | 原有三种固定命令兼容 | **PASS** | `fixedCommandCompatibilityTests`（精确字节比对）+ 模拟器「连发两帧相同命令 → 第二帧被抑制」`DUPLICATE_SUPPRESSED` |
| 2 | 正常英文文本 | **PASS** | 模拟器 7A-b：`message_id=emu-en-…`，`TASK_TEXT_RECEIVED` → 规划器 `NO_RULE_MATCHED`，`count("EXECUTED")=0` |
| 3 | 正常中文文本「把刚收到的地址打开地图」 | **PASS** | 模拟器 7A-a：`TASK_TEXT_RECEIVED` + `TASK_TEXT_COMPLETED` + `TASK_EXECUTED`，`plannerId=RULE_V1`，`addressSource=MOCK_FAKE_NOTIFICATION`，前台到达 Mock 目标 |
| 4 | 空文本 | **PASS** | 模拟器 7A-d `frame_rejected=EMPTY_TASK_TEXT`；自测 `TASK_TEXT blank rejected` |
| 5 | 超长文本 | **PASS** | 模拟器 7A-d 声明 385 → `TEXT_TOO_LONG`；自测 `TASK_TEXT 385 bytes rejected` |
| 6 | 非法长度 / 截断数据 | **PASS** | `INVALID_LENGTH`（非数字、负数）、`LENGTH_MISMATCH`（声明 2 实际 12）、`MALFORMED_FRAME`（字段截断）、`assembler rejects overflow`、`513 字节帧拒绝` |
| 7 | 分包 / 乱序 / 重复包 / 超时 | **PASS**（乱序为“拒绝”而非“恢复”） | `assembler reassembles TASK_TEXT split across MTU and mid-CJK`、`out-of-order bytes rejected`、`assembler emits duplicate complete frames for guard-level dedup`、`assembler timeout drops stale half frame and accepts next frame`、`expireIfStale clears half frame and receiver recovers` |
| 8 | 重复命令 ID | **PASS** | 模拟器 7A-c：`TASK_TEXT_RECEIVED`=1、`DUPLICATE_MESSAGE_ID`=1、`EXECUTED`=0；自测 `message guard suppresses duplicate within TTL and accepts after expiry` |
| 9 | 非法协议版本 | **PASS** | 模拟器「协议版本 PP2」与 7A-d 均 `UNSUPPORTED_VERSION` |
| 10 | 未授权 / 不支持的任务仍被拒绝 | **PASS** | 7A-c 同帧内含不支持动作仍 `UNSUPPORTED_ACTION`；7d「发消息」`UNSUPPORTED_ACTION`；7e「订机票」`NO_RULE_MATCHED`；7a 无地址 `NO_ADDRESS_IN_CONTEXT`；7f 无障碍关闭 `PERMISSION_REQUIRED` |

### 7.2 额外覆盖

| 项 | 结果 | 证据 |
| --- | --- | --- |
| 非法 UTF-8 字节 | **PASS** | `illegal UTF-8 bytes` → `INVALID_UTF8` |
| 非法 message_id（非法字符 / 超长 / 空） | **PASS** | `INVALID_MESSAGE_ID` |
| 文本内含 `\|` | **PASS** | 末位字段设计，`decodeOk` 用例 |
| 中文在多字节字符中间被切断 | **PASS** | 逐字节喂入 + 按 MTU 边界切片两种方式 |
| 511 字节负载 + 换行 = 512 | **PASS** | 边界接受 |
| 512 字节负载 + 换行 = 513 | **PASS** | 边界拒绝 `FRAME_TOO_LONG` |
| 状态帧转义与 512 字节截断（CJK/emoji 不切半字符） | **PASS** | `status frame …` 4 项 |
| 缺少 utterance 的任务被拒绝 | **PASS** | 模拟器 7h，未产生任何 `TASK_` 记录 |
| 敏感动作确认门 | **SKIP** | 白名单当前无敏感动作，由 `planner-selftest` 覆盖 |

### 7.3 旧测试是否全部保持通过

是。协议自测由阶段 6 的 29 项扩展到 76 项，原有 29 项全部保留且全部 PASS；规划层 96 项全部 PASS；模拟器回归由上轮的 30 PASS / 1 SKIP 扩展到 48 PASS / 1 SKIP，原有用例无删除、无断言放宽。

---

## 8. 尚未验证的真实硬件行为

以下全部为**未验证**，不得作为已通过项对外表述：

1. 实体 Android 手机上的任意功能。
2. Android 真实 GATT 与 ESP32 的整链路联调。
3. 真实 BLE notify / write / MTU 协商（185 是否被接受、回落 23 时的表现）。
4. 真实断连、重连、传输中半帧与 2 秒超时的实际表现。
5. Android → ESP32 长 `RESULT` 状态帧的真实写入（当前不分片，见 3.2）。
6. 分片乱序的任意恢复（当前设计不支持）。
7. 任意中文语音识别：本轮文本来自调试注入 / 自测构造，不是语音。
8. `NotificationListenerService`（未实现）。
9. 云端模型（`CloudPlannerStub` 仍返回 `UNAVAILABLE`）。
10. 真实微信、真实地图应用的启动与搜索结果确认（模拟器用 Mock 目标替代）。
11. 固件构建成功不等于烧录成功，也不等于 BLE 联调通过。

---

## 9. 修改的文件清单

Android：

```text
android/app/src/main/java/com/pocketpilot/app/core/CommandCodec.java          # 任务帧编解码、FrameAssembler
android/app/src/main/java/com/pocketpilot/app/core/TaskTextProtocol.java     # 长度/ID/空白/换行约束（新增）
android/app/src/main/java/com/pocketpilot/app/core/TaskMessageGuard.java     # 消息 ID 去重（新增）
android/app/src/main/java/com/pocketpilot/app/core/CommandType.java          # 新增 TASK_TEXT
android/app/src/main/java/com/pocketpilot/app/core/PocketCommand.java        # createWithId / protocolError
android/app/src/main/java/com/pocketpilot/app/core/CommandValidator.java     # TASK_TEXT 校验分支
android/app/src/main/java/com/pocketpilot/app/runtime/CommandPipeline.java   # handleTaskText / TaskDispatcher
android/app/src/main/java/com/pocketpilot/app/PocketPilotApp.java            # 装配 TaskDispatcher
android/app/src/main/java/com/pocketpilot/app/debug/DebugInjectReceiver.java # 调试注入任务文本
android/app/src/main/java/com/pocketpilot/app/transport/GattBleTransport.java# 重组看门狗
```

固件：

```text
firmware/pocketpilot_ble/main/pocketpilot_ble_main.c   # TASK_TEXT 构造、MTU-3 分片、发送互斥、串口测试入口 4
```

测试与文档：

```text
android/tools/protocol-selftest/ProtocolSelfTest.java
android/tools/protocol-selftest/run-selftest.ps1
android/tools/planner-selftest/run-selftest.ps1
android/tools/emulator-test/run-emulator-regression.ps1
android/docs/BLE任务文本协议-阶段7A.md（本文件）
android/docs/emulator-regression-latest.txt（本轮回归日志）
```

> 仓库整体尚未 commit；`android/` 与 `firmware/pocketpilot_ble/` 仍是 untracked，因此上面的清单是按实际改动文件整理，而不是 `git diff` 输出。

---

## 10. 复现方法

### 10.1 构建

```powershell
cd 'D:\个人项目\android'
$env:JAVA_HOME = 'D:\Android\jdk17'
.\gradlew.bat assembleDebug
```

### 10.2 纯 Java 协议自测

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File 'D:\个人项目\android\tools\protocol-selftest\run-selftest.ps1'
```

### 10.3 纯 Java 规划层自测

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File 'D:\个人项目\android\tools\planner-selftest\run-selftest.ps1'
```

### 10.4 模拟器回归

前置条件：模拟器 `emulator-5554` 在线，已安装 debug APK 与 `com.pocketpilot.mocktarget`。

```powershell
D:\Android\Sdk\platform-tools\adb.exe devices -l
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File 'D:\个人项目\android\tools\emulator-test\run-emulator-regression.ps1'
```

### 10.5 ESP32 固件构建

```powershell
cd 'D:\个人项目\firmware\pocketpilot_ble'
.\build.ps1
```

### 10.6 手工复现单个协议用例

`DebugInjectReceiver` 的 action 是 `com.pocketpilot.app.DEBUG_INJECT`，
任务文本走 `task_text` + `message_id` 两个 extra（`address` 同时注入 MOCK 地址上下文）：

```powershell
$adb = 'D:\Android\Sdk\platform-tools\adb.exe'
# 中文任务 + 同时注入 MOCK 地址上下文
& $adb shell am broadcast -a com.pocketpilot.app.DEBUG_INJECT `
  -n com.pocketpilot.app/.debug.DebugInjectReceiver `
  --es message_id "manual-001" `
  --es address "北京市海淀区中关村大街1号" `
  --es task_text "把刚收到的地址打开地图"

# 同一 message_id 再发一次，验证 DUPLICATE_MESSAGE_ID 抑制
& $adb shell am broadcast -a com.pocketpilot.app.DEBUG_INJECT `
  -n com.pocketpilot.app/.debug.DebugInjectReceiver `
  --es message_id "manual-001" `
  --es task_text "把刚收到的地址打开地图"

# 查看协议与任务事件
& $adb logcat -d -s PocketPilot/DebugInject PocketPilot/Pipeline PocketPilot/ResultsStore
```

两种入口的区别，不要混用：

| 入口 | action / extra | 用途 |
| --- | --- | --- |
| 真实格式任务帧注入 | `DEBUG_INJECT` + `task_text` | 走 `injectFakeTaskText` → `CommandCodec.encodeTaskTextBytes` → FakeBleTransport，覆盖**协议层** |
| 原始帧注入 | `DEBUG_INJECT` + `raw` | 直接给 `injectFakeRaw`，用于构造畸形帧（错误版本、非法长度、截断等） |
| 规划层直连 | `DEBUG_TASK` + `utterance` | 跳过协议，直接进 `TaskController`，只测规划与执行 |

对照 `android/app/src/main/java/com/pocketpilot/app/debug/DebugInjectReceiver.java` 确认当前广播 action / extra 名称后再执行；不同构建可能略有差异。

固件侧手工触发（串口 `4`）：

```text
4 = COMMAND_TASK_TEXT (serial test: 把刚收到的地址打开地图, NOT voice)
```

---

## 11. 本阶段的明确不做

- 未实现 `NotificationListenerService`。
- 未接入任何云端模型（`CloudPlannerStub` 仍为 `UNAVAILABLE`）。
- 未新增语音识别模块；ESP-SR 命令词识别仍是阶段 5 的既有能力，未扩展到自然语言。
- 未 commit、未 push。
- 未修改与本阶段无关的文件。
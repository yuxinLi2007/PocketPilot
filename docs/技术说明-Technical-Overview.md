# PocketPilot 技术说明（Technical Overview）

> 面向工程师 / 技术合作方。与《产品说明（讲给任何人听的版本）》配套；所有数据均来自仓库内可复现的验证记录，未验证项如实标注。

---

## 一、系统架构总览

系统由三部分组成，形成"表达意图 → 理解执行 → 反馈结果"的闭环：

```
┌──────────────┐   BLE GATT    ┌──────────────────┐    Android API    ┌──────────┐
│  设备端（固件） │ ───────────→ │  手机端（App）     │ ────────────────→ │  目标应用  │
│ 按键/麦克风/ASR │ ←───────────  │ 校验/执行/状态回写  │ ←──────────────── │ (微信/地图) │
└──────────────┘   状态回传帧    └──────────────────┘   执行状态核验     └──────────┘
```

**三层执行体系**（决定"命令如何落地到手机"）：

| 层级 | 手段 | 约束 | 现状 |
|-|-|-|-
| 1. 标准 Intent / DeepLink | 打开应用等公开入口 | 只用 Android 标准 API，不依赖厂商私有接口 | 已实现（打开微信/地图） |
| 2. 应用公开 API | 目标应用主动暴露的能力 | 依赖应用方支持，不可假设存在 | 预留接口 |
| 3. 无障碍服务（AccessibilityService） | 读取/执行界面动作（返回上一页） | **严格受限**：仅白名单动作 + 授权 + 可核验 | 已实现（BACK） |

**三层语音智能架构**（决定"一句话如何变成命令"）：

| 层级 | 技术 | 延迟特征 | 现状 |
|-|-|-|-
| L1 规则/命令词匹配 | 设备端 ESP-SR MultiNet7 命令词 | 接近实时 | **已在设备端实现**（3/3 命令词） |
| L2 端侧小模型 | App 内意图识别，输出受约束的工具调用 JSON | +数百 ms | 未启动（待真机数据决定必要性） |
| L3 云端大模型 | 多步骤模糊任务规划 | 秒级 | 未接入（App 内 PlannerStub 预留） |

硬规则：**L2/L3 永远不挡 L1 的路**——解析超过阈值（目标 ≤800ms）直接回退规则层结果。

---

## 二、硬件层

### 2.1 原型：ESP32-S3-EYE

| 模块 | 实现 | 关键参数 |
|-|-|-|
| 主控 | ESP32-S3（Xtensa LX7 双核，AI 向量指令） | 固件基于 ESP-IDF + Bluedroid |
| 按键 | BOOT(GPIO0) 手势键 + 正面 ADC 分压四键（MENU/PLAY/UP/DOWN） | 单击/双击/长按 ≥1.2s；5/5 实测通过 |
| 麦克风 | 板载 I2S 数字麦 | 24bit 字节对齐修复后，拍手实测 rms 基线 42→481（11.5 倍） |
| ASR | ESP-SR MultiNet7（`ESP_MN_CHINESE`），命令词拼音注册，支持 pinyin 回退 | 按下 PLAY→派发 2.1–2.9s；无声正确拒识 |
| BLE | Bluedroid GATT 外设，广播名 "PocketPilot" | 实测 RSSI −36dBm（1m 内），MTU 协商 185 |

### 2.2 商业化：ESP32-S3-PICO-1（SiP）

- 7×7mm LGA56 封装，**高度仅 0.96mm**（对比 MINI-1 模组 2.4mm 省 1.4mm），内置晶振/Flash/PSRAM/射频匹配；
- **固件零改动迁移**：同为 ESP32-S3 家族，已验证的 ASR、BLE 协议、按键逻辑全部继承；
- 目标整机 68×56×4.2mm 圆角薄板：电池 2.5mm 薄型锂电（250-300mAh），铝盖仅做正面外观件（天线放塑料背壳侧，避免金属屏蔽）。

---

## 三、通信协议层

### 3.1 GATT 契约

自定义 128-bit UUID（ASCII 可读：`6f706f63-6b65-742d-7069-6c6f74…` = "pocket-pilot"）：

| 项 | UUID | 用途 |
|-|-|-
| Service | `6f706f63-6b65-742d-7069-6c6f74000001` | 按服务 UUID 过滤扫描，防串连 |
| RX Characteristic | `…0002` | 手机 → 设备（命令写入 / 状态回传写入） |
| TX Characteristic | `…0003` | 设备 → 手机（Notify，需订阅 CCCD 2902） |

### 3.2 帧格式与分片重组

- **应用层消息 = JSON 文本帧，UTF-8 编码，以 `\n` 结尾分帧**；字节流语义，单次 GATT write 不代表消息边界；
- **发送端** `GattWriteChunker` 按 `MTU-3` 分片（MTU 185 时每片 182 字节）；
- **接收端** `pp_rx_assembler` 字节流重组器：以 `\n` 判定帧边界、连接建立/断开清空半帧缓冲；主机测试 12/0 PASS，覆盖 26 片重组、UTF-8 多字节跨片、512 字节边界。

### 3.3 可靠性机制

| 机制 | 设计 |
|-|-
| 唯一命令 ID | 每条命令携带 `command_id`，接收端幂等去重（重复帧拒绝） |
| ACK + 状态机 | 六态回执：`RECEIVED → EXECUTED_UNCONFIRMED → CONFIRMED`，异常态 `REJECTED / NOT_INSTALLED / PERMISSION_REQUIRED` |
| 严格口径 | "发送了" ≠ "执行了" ≠ "确认了"；PC 端测试的 ACK 固定 `EXECUTED_UNCONFIRMED`，**不算手机动作成功** |
| 非法帧防护 | 格式错误、缺参数、超长帧一律 `REJECTED` 并回 `MALFORMED_FRAME` 等原因码 |
| 断连恢复 | 广播常开、断连清理状态、重连后重新协商 MTU 并重新订阅 CCCD |

---

## 四、Android 应用层

### 4.1 模块结构（`com.pocketpilot.app`）

| 包 | 职责 |
|-|-
| `core` | 协议核心：`PocketProtocol`（UUID/帧常量）、`CommandCodec`、`CommandValidator`、`GattWriteChunker`、`StatusFrameEncoder`、`TaskMessageGuard`（内容安全护栏） |
| `transport` | `GattBleTransport`（真机 GATT 客户端，按服务 UUID 扫描/连接/订阅/分片写）与 `FakeBleTransport`（模拟链路，回归测试用） |
| `exec` | 执行器注册表：`ExecutorRegistry` 按 CommandType 分发；`BackExecutor` 走无障碍 |
| `plan` | 任务规划器（规则版 Planner 已验证；云端 PlannerStub 预留） |
| `service` | `PocketAccessibilityService`（受限动作执行） |
| `verify` | 执行结果核验（区分"已执行"与"已确认"） |
| `data` | `ResultsStore`：执行记录落盘 JSONL |
| `debug` / `ui` / `runtime` | 调试注入、界面、运行时 |

### 4.2 权限模型

- BLE：扫描/连接运行时权限（Android 12+ BLUETOOTH_SCAN/CONNECT）；
- 无障碍：**仅 BACK 命令使用**，未授权时返回 `PERMISSION_REQUIRED` 而非静默失败；
- 敏感操作（发送消息、付款、删除类）：要求额外确认页，不进入免确认通道。

---

## 五、数据与可观测性

- **执行记录**：`ResultsStore` 输出 JSONL（字段含 command、command_id、status、elapsed_ms、evidence、source、device），当前模拟数据集 728 条记录 / 106 条命令链；
- **PocketPilot Lab**（`tools/lab/`）：零依赖 Python 服务端 + 单文件前端，读取 JSONL 与自测日志做实时监测、命令级成功率、耗时分布（avg/p95/max）、执行记录检索；3 秒自动刷新；
- **统计口径教训**：必须按命令链去重（一条命令产生 RECEIVED/EXECUTED/CONFIRMED 多条记录）；模拟器数据集中 37 条真执行 0 失败、37 条被环境阻塞（未装微信/未授权）、151 条注入异常全部正确拦截。

---

## 六、实测数据汇总（截至 2026-11-11）

| 项目 | 结果 | 证据位置 |
|-|-|-
| 协议纯 Java 自测 | 86/0 PASS | `android/docs/protocol-selftest-latest.txt` |
| Planner 测试 | 96 项 PASS | 同上目录 |
| 模拟器回归 | 46/48（2 FAIL：模拟器无真实地图应用、BACK 结果核验降级为 UNCONFIRMED） | `android/docs/emulator-regression-latest.txt` |
| 设备侧真机 BLE | 烧录/广播/GATT 契约/三命令通知（24/59/17 字节）/MTU 185/状态回写 3/3/非法帧拒绝 3/断连重连 | `android/docs/真实BLE验证记录.md` |
| 实体按键 | BOOT 手势 + ADC 四键 5/5，按键→BLE 帧 7/7 对应 | `firmware/.../button-input-verification.md` |
| 麦克风 | I2S 24bit 对齐修复，拍手 rms 42→481 | `mic-capture-verification.md` |
| 中文命令词 | mn7_cn 3/3 识别并进 BLE 发送路径，端到端 2.1–2.9s | `asr-command-verification.md` |
| 长帧重组 | 主机测试 12/0（26 片/UTF-8 跨片/512B 边界） | `test_pp_rx_assembler.c` |

---

## 七、验证状态与待验证清单（如实）

**已闭环**：设备侧全链路（按键→ASR→BLE 帧）+ 手机端模拟链路（协议/规划/执行/回写）+ 长帧重组。

**未验证（不能当结论）**：

| 项 | 状态 | 说明 |
|-|-|-
| **实体安卓手机端到端** | ⏳ 最大缺口 | 真机 BLE 双向收发、`GattBleTransport` 重连、CONFIRMED 回传均未实测（无实体手机） |
| ASR 识别率统计 | ⏳ | 每命令仅 1 样本；`score` 字段含义未定；误唤醒负样本未测 |
| T0 平台穿刺 18 项 / 配对重连 10 项 | ⏳ | 固件与记录表就绪，等手机 |
| L2 端侧小模型 | 未启动 | 延迟/内存/功耗无实测；是否需要由真机数据决定 |
| 薄版铝盖天线 | ⏳ | 4.2mm 铝盖内 IFA 天线 RSSI 需对比原型基线（−36dBm） |

---

## 八、商业化硬件迁移技术要点

1. **天线**：铝壳屏蔽射频——铝只做正面盖，IFA 天线置于塑料背壳远端，净空远离电池/磁铁/隔磁片；
2. **拾音**：薄机身（4.2mm）腔体变浅 → 侧向短声道 + 硅胶密封圈补偿，拾音距离目标 20-100cm；
3. **供电**：语音识别峰值约 300mA，但占空比 <5%（仅按住 3-5s），250-300mAh 可支撑 6-8 天；
4. **认证**：SRRC 型号核准 + CCC + 蓝牙 BQB，周期 2-3 个月，发布日前 4 个月启动；
5. **磁吸**：N52 磁铁 1.0mm 嵌槽 + 引磁环贴片，验收标准垂直剥离力 ≥3N。

---

*文档版本 v1.0（2026-11-11）· 数据来源：PocketPilot 仓库验证记录与模拟数据集 · 修订随验证进展更新*

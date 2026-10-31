# PocketPilot 真实 BLE 验证记录

- **记录日期**：2026-10-30（首版 2026-10-29）
- **结论**：**设备侧真实 BLE 已跑通**——固件已烧录到实体 ESP32-S3-EYE，电脑端能扫描到广播、完成服务发现、订阅通知、收到三条命令、写回执行状态，并能断开重连。
  **ESP32-S3-EYE ↔ 实体安卓手机的端到端仍未验证**（当前没有实体安卓手机）。

> 本文件中每条“通过”都只覆盖该行描述的范围。
> 电脑端脚本回执固定为 `EXECUTED_UNCONFIRMED`，它证明 GATT 契约可用，**不证明**任何手机动作成功。

---

## 1. 硬件与环境

| 项目 | 值 |
|---|---|
| 开发板 | ESP32-S3-EYE |
| 芯片 | `ESP32-S3 (QFN56) (revision v0.2)`，Embedded PSRAM 8MB |
| 下载口 | `USB mode: USB-Serial/JTAG`，Windows 串口 `COM5` |
| 设备 MAC | `94:a9:90:1c:6f:d4`（BLE 控制器地址 `94:a9:90:1c:6f:d6`） |
| 固件工程 | `firmware/pocketpilot_ble`，ESP-IDF 5.4.3 |
| PC 蓝牙 | Intel Wi-Fi 6E AX211 自带蓝牙，Windows BLE 扫描可用 |
| PC 客户端 | `bleak`（Python 3.11），`firmware/pocketpilot_ble/tools/ble_client_test.py` |

## 2. 已完成（可复现）

| 项目 | 结果 | 证据 |
|---|---|---|
| 固件构建 | 通过 | `artifacts/BUILD-INFO.txt`；app bin 729 136 字节，SHA-256 `2F4DC523897E964085CD817685250D571370718A7AA1BE109821CB81298FBF40` |
| 烧录到实体开发板 | 通过 | `firmware/pocketpilot_ble/artifacts/logs/flash_20261030_001022.log`：`Chip is ESP32-S3 (QFN56) (revision v0.2)`、`USB mode: USB-Serial/JTAG`、`MAC: 94:a9:90:1c:6f:d4`、`Hash of data verified`、`[flash] OK` |
| 上电启动、GATT 服务建立、按键任务运行、开始广播 | 通过 | `.../logs/boot_20261030_001039.log`：`PP_BOOT name=PocketPilot mtu_target=185`、`PP_BTN_READY gpio=0 ... led_gpio=3`、`PP_SERVICE_READY handle=40`、`PP_ADV_STARTED name=PocketPilot` |
| 真实无线广播可被扫描 | 通过 | `.../logs/scan_20261030_000913.log`：`PocketPilot`、`94:A9:90:1C:6F:D6`、RSSI −36 dBm，广播含 Service `6f706f63-6b65-742d-7069-6c6f74000001`（`tools\scan_pocketpilot.py` 生成） |
| 服务发现与特征属性 | 通过 | 通知特征 `...0002` 属性 `notify`；写入特征 `...0003` 属性 `write` / `write-without-response` |
| 通知订阅（CCCD） | 通过 | 固件日志 `PP_CCCD_WRITE subscribed=1 value=0x01`、`PP_SUBSCRIBED ready_for_commands` |
| 三条命令通知 | 通过 | `ble_test_20261030_000932.log`：`COMMAND_OPEN_WECHAT` 24 字节、`COMMAND_OPEN_MAP` 59 字节（含中文地址）、`COMMAND_BACK` 17 字节 |
| MTU 协商 | 通过 | 双方协商到 `mtu=185`（`PP_MTU mtu=185`） |
| 状态写回（手机 → 设备方向） | 通过 | 三条回执均被设备接收：`PP_RX_RESULT ... total=1/2/3`，`results_ok=3` |
| 非法帧拒绝 | 通过 | `rx_rejects=3`：`MALFORMED_FRAME`、`UNSUPPORTED_FRAME_TYPE=NOPE`、`UNKNOWN_STATUS=NOT_A_STATUS` |
| 断开后重新广播、可再次连接 | 通过 | `reconnect_20261030_000949.log`：断开后再次扫描到设备，第二次连接成功 |
| Android 协议自测 | 通过 29 / 失败 0 | `android/docs/protocol-selftest-latest.txt` |
| Android 模拟器回归（FakeBleTransport） | 通过 17 / 失败 0 | `android/docs/emulator-regression-latest.txt` |

## 3. 仍未验证

| 项目 | 状态 | 原因 |
|---|---|---|
| 实体安卓手机连接本开发板 | 未执行 | 当前无实体安卓手机 |
| Android App 真实 GATT 通道 ↔ 设备 | 未执行 | 模拟器无真实蓝牙射频；`FakeBleTransport` 与 `adb` 注入不经过无线链路 |
| 通知在真机上投递到 App 并解析 | 未执行 | 同上 |
| 手机实际执行动作后的状态回传（`CONFIRMED` 等真实结果） | 未执行 | PC 端回执固定 `EXECUTED_UNCONFIRMED` |
| 手机侧断连 / 自动重连 | 未执行 | `GattBleTransport` 的 3 次重连逻辑只做过静态与模拟器路径检查 |
| 实体按键的**机械按压** | 已实现，未物理验证 | 按键任务与 GPIO0 已按官方 BSP 落地并运行（`PP_BTN_READY`）；用 USB-Serial/JTAG 的 DTR 线替代按压的尝试失败（只触发复位，`button_line_20261030_000857.log`），仍需人手按下 BOOT 键取证 |
| 麦克风与中文语音识别 | 未实现 | 未配置 I2S 麦克风、未接入 ESP-SR |

## 4. 复现步骤

```powershell
# 1. 构建并烧录（ESP-IDF 5.4.3）
cd 'D:\个人项目\firmware\pocketpilot_ble'
.\build.ps1
.\flash.ps1 -Port COM5

# 2. 抓完整启动日志（复位 + 捕获 PP_BOOT / PP_BTN_READY / PP_ADV_STARTED）
python artifacts\capture_boot.py --port COM5 --seconds 10

# 3. 扫描并写成日志（退出码 0 = 找到设备）
python -m pip install bleak
python tools\scan_pocketpilot.py --timeout 15

# 4. 电脑端 GATT 契约测试（自动按 1/2/3 触发命令）
python tools\ble_client_test.py --serial-port COM5 --wait 12

# 5. 断开重连
python artifacts\reconnect_test.py

# 6. 人按 BOOT 键后取证（10 秒内按一次）
python artifacts\capture_boot.py --port COM5 --seconds 15
```

## 5. 实测中遇到过的问题（避免误判）

| 现象 | 原因 | 处理 |
|---|---|---|
| `bleak` 第一次扫描返回 0 个设备，但 Windows“添加设备”能看到很多 BLE 设备 | Windows BLE 扫描窗口过短 / 缓存尚未刷新 | 延长扫描时间（20 s 以上）后重试即可扫描到 `PocketPilot`，不是固件问题 |
| 串口只抓到启动日志的尾部，没有 `PP_BOOT` | 只用 `RTS` 脉冲没有真正复位芯片 | 必须走经典复位序列（`setDTR`/`setRTS` 方法调用，见 `artifacts/serial_console_test.py`） |
| 串口里的中文地址显示乱码 | 早期脚本按本地编码写入日志 | 统一按 UTF-8 读写；实测 `北京市海淀区中关村大街1号` 正常 |
| 想用脚本代替人手按 BOOT 键 | 板上 BOOT 键接 GPIO0；USB-Serial/JTAG 的 DTR 线并不等于把 GPIO0 拉低 | 尝试后只触发复位、无 `PP_BTN` 事件，不再当作按键验证；机械按压必须人工完成 |

## 6. 判定口径

- 设备侧 BLE：**已通过**（限于上表列出的 GATT 契约与设备行为）。
- 整机链路：只有“实体 ESP32-S3-EYE + 实体安卓手机 + 真实无线链路”同时满足才算通过，当前**尚未达到**。
- 模拟器、`FakeBleTransport`、`adb` 注入、PC 端 ACK 一律不计入真实手机验证。

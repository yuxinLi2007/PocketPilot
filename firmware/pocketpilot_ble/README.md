# PocketPilot BLE 固件（ESP32-S3-EYE）

这是 PocketPilot 原型的**设备端**：把用户指令通过 BLE GATT 发给 Android 配套应用，并接收应用回传的执行状态。

当前固件已完成“实体按键输入 + 协议 + 传输”。**未包含**麦克风采集、中文语音识别和 AI 任务规划。

---

## 1. 角色与边界

```text
[BOOT 键手势]      ->  ESP32-S3-EYE  --BLE notify-->  Android App
[串口 1/2/3 触发]  ->       |          <--BLE write--  Android App（执行状态）
                            |
                       板载 LED 指示连接状态
```

- 设备是 BLE **外设**（GATT Server），Android 是 Central。
- 固件不决定“能不能打开微信”，只负责输入与传输；授权、校验、执行都在 Android 端。
- 串口 `1/2/3` 是调试用的第二条触发路径，用来在不碰按键时复现同一条命令链路。

## 2. 硬件引脚（已核对官方 BSP，非猜测）

| 用途 | GPIO | 有效电平 | 出处 |
|---|---|---|---|
| BOOT 按键 | `GPIO0` | 低有效（按下接地） | `esp-bsp/bsp/esp32_s3_eye/include/bsp/esp32_s3_eye.h`：`BSP_BUTTON_5_IO`；`src/bsp_button.c`：`.active_level = 0` |
| 板载 LED | `GPIO3` | 高电平点亮 | 同文件：`BSP_LED_1_IO`；`src/bsp_led.c`：`.is_active_level_high = 1` |

ESP32-S3-EYE 上可供用户操作的按键只有 BOOT 键（另有 RST）。因此按键手势复用这一个键：

| 手势 | 发送的命令 |
|---|---|
| 单击 | `COMMAND_OPEN_MAP`（固定地址 `北京市海淀区中关村大街1号`） |
| 双击（两次间隔 ≤350 ms） | `COMMAND_OPEN_WECHAT` |
| 长按 ≥1.2 s | `COMMAND_BACK` |

实现参数：25 ms 消抖、`<40 ms` 的按下视为抖动忽略、长按在按住期间触发一次、松开不再补发（`main/pp_button.c`）。

LED 指示：未连接 1 Hz 慢闪；已连接未订阅 8 Hz 快闪；已订阅（可发命令）常亮。

> 语音接入后，按键会改为“按下即录音/松开即识别”的入口，上述手势只是当前无麦克风阶段的替代方案。

## 3. GATT 契约

| 项目 | 值 |
|---|---|
| 广播名 | `PocketPilot` |
| 目标 MTU | 185（PC 端实测协商到 185；协商失败不影响功能，仅影响分片次数） |
| Service | `6f706f63-6b65-742d-7069-6c6f74000001` |
| 通知特征（设备 → 手机） | `6f706f63-6b65-742d-7069-6c6f74000002`，属性 `notify`，带 CCCD，需订阅 |
| 写入特征（手机 → 设备） | `6f706f63-6b65-742d-7069-6c6f74000003`，属性 `write` + `write-without-response`；Android 客户端使用带响应写 |

UUID 为 128 位自定义值，广播包中携带 Service UUID；扫描端按 Service UUID 过滤。

## 4. 数据帧

命令（设备 → 手机），UTF-8，以 `\n` 结尾：

```text
PP1|COMMAND_OPEN_WECHAT
PP1|COMMAND_OPEN_MAP|北京市海淀区中关村大街1号
PP1|COMMAND_BACK
```

状态（手机 → 设备）：

```text
PP1|RESULT|COMMAND_OPEN_MAP-1a2b3c4d|EXECUTED_UNCONFIRMED|已把地址交给系统解析器
```

约束：

- 单帧上限 512 字节，超过则整段丢弃直到下一个换行，并计入 `rx_rejects`。
- 地址参数上限 120 字符（Android 端校验）。
- 只接受 `PP1` 版本、`RESULT` 类型、非空 `command_id` 与状态。
- 固件只在 Android 端回执后记录 `PP_RX_RESULT`；发送成功不等于手机动作成功。

## 5. 构建

要求：ESP-IDF 5.4.x（本工程验证于 `C:\Espressif\frameworks\esp-idf-v5.4.3`）。

```powershell
cd 'D:\个人项目\firmware\pocketpilot_ble'
.\build.ps1                    # 增量构建
.\build.ps1 -Clean             # 全量重建
.\build.ps1 -IdfPath 'C:\Espressif\frameworks\esp-idf-v5.4.3'
```

说明：

- 工程路径含非 ASCII 字符，ESP-IDF 工具链无法稳定处理，因此 `build.ps1` 会把源码镜像到 ASCII 路径（默认 `D:\pocketpilot_ble_build`）再构建，最后把二进制与构建记录复制回 `artifacts\`。
- 产物：`artifacts\pocketpilot_ble.bin`、`bootloader.bin`、`partition-table.bin`、`.elf`、`.map`。
- 构建记录：`artifacts\BUILD-INFO.txt`，日志：`artifacts\logs\build.log`。
- 校验口径：应用 bin、bootloader、分区表、ELF、map 全部存在才返回 0。

## 6. 烧录与监视

```powershell
.\flash.ps1                        # 自动探测串口并烧录
.\flash.ps1 -Port COM5 -Monitor    # 指定串口，烧录后进入串口监视
.\flash.ps1 -MonitorOnly           # 只监视，不烧录
.\flash.ps1 -Erase -Monitor        # 先擦除（会清除 BLE 绑定）
```

- 串口必须是开发板的 USB 口；Windows 上“蓝牙链接上的标准串行 (COMx)”不是开发板串口。本机为 `COM5`（`USB\VID_303A&PID_1001`，ESP32-S3 原生 USB-Serial/JTAG）。
- 退出监视：`Ctrl + ]`。
- 串口日志会同时写入 `artifacts\logs\serial_*.log`。

上电后应看到：

```text
PP_BOOT name=PocketPilot mtu_target=185
PP_BTN_READY gpio=0 active_low=1 led_gpio=3 click=SINGLE_MAP DOUBLE_WECHAT LONG_BACK
PP_ADV_STARTED name=PocketPilot
PP_SERVICE_READY handle=40
```

## 7. 串口测试命令

在串口监视中发送单个字符：

| 键 | 动作 |
|---|---|
| `1` | 发送 `PP1\|COMMAND_OPEN_WECHAT` |
| `2` | 发送带地址的 `PP1\|COMMAND_OPEN_MAP` |
| `3` | 发送 `PP1\|COMMAND_BACK` |
| `s` | 打印连接/订阅/MTU/拒绝计数/回执计数 |
| `h` 或 `?` | 打印菜单 |

未连接或未订阅时发送会被拒绝并打印 `PP_SEND_BLOCKED`，这是预期行为（按键触发同样如此，会打印 `PP_BTN_SEND_FAILED`）。

## 8. 电脑端测试（不需要手机）

```powershell
python -m pip install bleak

# 1) 扫描：确认广播名、地址、RSSI、Service UUID，并写成日志
python tools\scan_pocketpilot.py --timeout 15

# 2) GATT 契约：订阅通知 + 触发三条命令 + 状态回写 + 非法帧拒绝
python tools\ble_client_test.py --serial-port COM5 --wait 12

# 3) 断开后重新广播、可再次连接
python artifacts\reconnect_test.py
```

- `tools\scan_pocketpilot.py` 把广播结果写入 `artifacts\logs\scan_*.log`，退出码 0 = 找到设备。
- `--serial-port` 会在扫描前打开开发板串口并保持打开，连上后自动依次发送 `1/2/3`，
  这样“命令通知 / 状态回写 / 固件日志”会落在同一条时间线上；不传该参数时，也可以自己在串口里按 `1/2/3`，或直接按板上的 BOOT 键。
- 脚本会扫描 PocketPilot、订阅通知、触发命令、回写 `EXECUTED_UNCONFIRMED` 状态，并保存日志到 `artifacts\logs\ble_test_*.log`。
- 这些结果验证的是设备侧 GATT 契约，**不是**手机动作；其 ACK 固定为“已执行·未确认”。

## 9. 当前状态（2026-10-30，实机 `COM5`）

| 项目 | 状态 | 证据 |
|---|---|---|
| 固件构建（ESP-IDF 5.4.3 / esp32s3） | 通过 | `artifacts/BUILD-INFO.txt`（`app_bin_bytes = 729136`） |
| 烧录到实体 ESP32-S3-EYE | 通过 | `artifacts/logs/flash_20261030_001022.log`：`Chip is ESP32-S3 (QFN56) (revision v0.2)`、`USB mode: USB-Serial/JTAG`、`MAC: 94:a9:90:1c:6f:d4`、`Hash of data verified` |
| 上电启动、按键任务与广播 | 通过 | `artifacts/logs/boot_20261030_001039.log`：`PP_BOOT`、`PP_BTN_READY`、`PP_SERVICE_READY handle=40`、`PP_ADV_STARTED` |
| 电脑端扫描到外设 | 通过 | `artifacts/logs/scan_20261030_000913.log`：`PocketPilot` / `94:A9:90:1C:6F:D6` / `-36 dBm` / 广播含 Service UUID |
| 电脑端 GATT 契约：订阅、三条命令通知、状态回写、非法帧拒绝 | 通过（仅协议层） | `artifacts/logs/ble_test_20261030_000932.log`：MTU 185，`RX properties=['notify']`，`TX properties=['write','write-without-response']`，通知 24 / 59 / 17 字节，`results_ok=3`，`rx_rejects=3` |
| 断开后重新广播并可再次连接 | 通过 | `artifacts/logs/reconnect_20261030_000949.log` |
| ESP32 与实体安卓手机端到端 | 未执行 | 当前无实体安卓手机；PC 端回执固定 `EXECUTED_UNCONFIRMED`，不代表手机动作 |
| 麦克风 / 中文语音识别 | 未实现 | 未配置 I2S 麦克风、未接入 ESP-SR |
| 按键的**机械按压** | 尚未验证 | 代码与引脚已确认、任务已运行；尚未有人在板上实际按下 BOOT 键 |

### 按键验证的已知限制

- 板上 BOOT 键是本原型唯一的物理输入，必须由人按下才能证明开关本体导通。
- 已尝试用 USB-Serial/JTAG 的 DTR 控制线驱动 GPIO0 来替代人工按压（`artifacts/logs/button_line_20261030_000857.log`）：**未成功**，DTR 只触发了芯片复位，没有在固件里产生 `PP_BTN` 事件。因此该路径不可用作按键验证，也不能据此声称按键通过。
- 人工验证方法（板上电、串口可读时执行，然后 10 秒内按一次 BOOT 键）：

```powershell
python artifacts\capture_boot.py --port COM5 --seconds 15
```

日志中出现 `PP_BTN event=SINGLE_CLICK` 即说明按键电平路径有效；若同时有 BLE 订阅，还应在同一份日志里看到 `PP_SEND_DONE name=COMMAND_OPEN_MAP`。

> 上表的“通过”只覆盖设备自身行为与设备侧 GATT 契约，不代表任何手机操作成功。

`artifacts\` 是本机构建产物目录，已被 `.gitignore` 忽略；克隆仓库后需自行运行 `build.ps1` 生成。

固件二进制 SHA-256（构建于 2026-10-29T16:05:50Z）：

```text
2F4DC523897E964085CD817685250D571370718A7AA1BE109821CB81298FBF40
```

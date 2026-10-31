# 实体按键输入验证记录

> 规则：只有拿到可复现的串口证据才写 PASS。代码编译通过、工具存在都不算。

## 1. 硬件与引脚（来源：Espressif 官方 esp-bsp `esp32_s3_eye` 5.0.2）

| 输入 | 引脚 | 形态 |
| --- | --- | --- |
| 底部 BOOT | GPIO0 | 普通 GPIO，低有效，板上有外部上拉 |
| 正面 MENU / PLAY / UP / DOWN | GPIO1 | 共用一路 ADC 电阻梯（ADC1_CH0） |
| LED | GPIO3 | 高电平点亮 |

正面四个键不是独立 GPIO，必须读 ADC 电压再分档。官方窗口：

| 按键 | 电压窗口 (mV) |
| --- | --- |
| MENU | 2310–2510 |
| PLAY | 1880–2080 |
| DOWN | 720–920 |
| UP | 280–480 |

## 2. 当前固件

- 源码：`main/pp_button.c`、`main/pocketpilot_ble_main.c`
- 串口：COM5；产物：`artifacts/pocketpilot_ble.bin`
- 启动证据：`artifacts/logs/boot_20261030_004015.log`

```text
PP_ADC_CALI scheme=curve
PP_ADC_READY gpio=1 unit=0 channel=0
PP_BTN_READY boot_gpio=0 adc_gpio=1 led_gpio=3 ...
p = probe raw inputs for 30 s (press buttons while it runs)
```

结论：ADC 初始化成功，串口探针命令 `p` 已上线。（按键验证结果见第 4 节，已完成。）

## 3. 已测到的原始数据

释放态（没人按键）连续采样：

```text
PP_PROBE t=0ms gpio0=1 mv=3191 raw=4095 band=NONE
```

- `raw=4095` 是 12 bit 满量程，说明 GPIO1 在 3.3V 轨上，符合“电阻梯未被按下”的预期。
- 因此按下任意正面键时读数应明显下降；若**毫无变化**，说明按键电信号没有进入 GPIO1。

## 4. 按键验证结果（2026-10-30，已通过）

### 4.1 BOOT / GPIO0 手势

```text
PP_BTN_RAW level=0                     # 按下，低有效
PP_BTN_RAW level=1                     # 释放
PP_BTN event=SINGLE_CLICK clicks=1     # 单击
PP_BTN event=DOUBLE_CLICK clicks=2     # 双击
PP_BTN event=LONG_PRESS clicks=1       # 长按 >= 1.2 s
```

映射：单击 → `COMMAND_OPEN_MAP`，双击 → `COMMAND_OPEN_WECHAT`，长按 → `COMMAND_BACK`。

### 4.2 正面 ADC 四键实测值

| 按键 | 实测 (mV) | 官方窗口 (mV) | 事件 | 结果 |
| --- | ---: | ---: | --- | --- |
| MENU | 2389 | 2310–2510 | `PP_BTN event=MENU source=front_panel` | PASS |
| PLAY | 1963 | 1880–2080 | `PP_BTN event=PLAY source=front_panel` | PASS |
| UP+ | 367 | 280–480 | `PP_BTN event=UP source=front_panel` | PASS |
| DN- | 794/795 | 720–920 | `PP_BTN event=DOWN source=front_panel` | PASS |
| （释放） | 3191 | — | `band=NONE` | 符合预期 |

证据日志：

- `artifacts/logs/e2e_button_ble_20261030_010544.log`（按键 + BLE 联合采集，含全部 ADC 读数）
- `artifacts/logs/e2e_button_ble_run_20261030_010544.log`（同一轮的完整原始输出）

### 4.2.1 当前固件复测（2026-10-30 08:36，5/5）

当前烧录固件再次实测，五个输入均产生正确事件：

| 输入 | 串口实测 | 事件 | 结果 |
| --- | --- | --- | --- |
| BOOT | `level=0` → `level=1` | `LONG_PRESS clicks=1` | PASS |
| PLAY | 1949 mV | `PP_BTN event=PLAY source=front_panel` | PASS |
| MENU | 2364 mV | `PP_BTN event=MENU source=front_panel` | PASS |
| UP+ | 364 mV | `PP_BTN event=UP source=front_panel` | PASS |
| DN- | 790 mV | `PP_BTN event=DOWN source=front_panel` | PASS |

原始串口捕获（GB18030 控制台编码）：`artifacts/logs/live_bg_console_20261030_083439.log`。

边界：本节证明按键电信号与固件手势识别通过；按键到 Android 真机的端到端仍未验证。
### 4.3 仍未验证

| 项 | 状态 | 说明 |
| --- | --- | --- |
| 按键 → Android App 端到端 | 尚未验证 | 需要实体安卓手机；PC 端 BLE 客户端不能替代 |

## 5. 按键 → BLE 通知帧（2026-10-30，已通过）

方法：`tools/e2e_button_ble_test.py` 同时做两件事——串口旁路记录固件自己的按键/发送日志，
PC 端用 bleak 连接 `PocketPilot` 并订阅 RX (`...0002`) 通知，最后比对两侧。

本轮 7 个动作全部一一对应：

| # | 按键动作 | 固件事件 | BLE 侧收到 |
| --- | --- | --- | --- |
| 1 | BOOT 单击 | `SINGLE_CLICK` | `PP1\|COMMAND_OPEN_MAP|北京市海淀区中关村大街1号` |
| 2 | BOOT 双击 | `DOUBLE_CLICK` | `PP1\|COMMAND_OPEN_WECHAT` |
| 3 | BOOT 长按 | `LONG_PRESS` | `PP1\|COMMAND_BACK` |
| 4 | 正面 MENU | `MENU` | `PP1\|COMMAND_OPEN_MAP|北京市海淀区中关村大街1号` |
| 5 | 正面 PLAY | `PLAY` | `PP1\|COMMAND_OPEN_WECHAT` |
| 6 | 正面 UP+ | `UP` | `PP1\|COMMAND_BACK` |
| 7 | 正面 DN- | `DOWN` | `PP1\|COMMAND_BACK` |

汇总输出：

```text
板子侧：按键事件 15 条，PP_SEND_DONE 7 条
蓝牙侧：收到 7 个通知帧
PASS：7 次按键发送，蓝牙侧收到 7 帧，链路是通的。
```

另外用 `--auto-probe`（串口键 `1/2/3` 代替按键）单独验证过链路本身：
3 发 3 收，证据 `artifacts/logs/e2e_probe_20261030_010334.log`。

**边界**：以上证明的是「按键 → ESP32 → BLE 通知帧」。
接收方是 PC 上的 bleak 客户端，**不是** Android App，也没有触发任何手机动作。

## 6. 复现方法

```powershell
cd 'D:\个人项目\firmware\pocketpilot_ble'
.\build.ps1
.\flash.ps1 -Port COM5
python tools\input-probe.py --port COM5
```

脚本依次提示 BOOT / MENU / PLAY / UP / DOWN，每个键一个 7 秒窗口，
最后输出 PASS/FAIL 汇总，日志写到 `artifacts/logs/input_probe_*.log`。

PASS 只代表“按键电信号进入并被识别”，不代表 BLE 或 Android 端到端通过。

按键 + BLE 联合验证（PC 端客户端）：

```powershell
python tools\e2e_button_ble_test.py --port COM5 --scan-timeout 20 --seconds 75
python tools\e2e_button_ble_test.py --port COM5 --scan-timeout 20 --seconds 12 --auto-probe
```

第一条需要人在 75 秒窗口里按键；第二条用串口键 `1/2/3` 自动跑，用于单独确认蓝牙链路。

## 7. 板卡实物核对（2026-10-30）

用户提供的正反面照片：

- 正面丝印：`ESP32-S3-EYE_SUB_V1.1`，按键丝印为 `MENU` / `PLAY` / `UP+` / `DN-`（左上/左下/右上/右下），中间为摄像头，下方为 LCD。
- 背面丝印：`ESP32-S3-EYE-MB_V2.2`，ESP32-S3-WROOM-1 模组 + MicroSD 卡座 + USB。

核对结论：与 Espressif 官方 `esp32_s3_eye` BSP 描述的双板结构一致，
GPIO0(BOOT) / GPIO1(正面 4 键 ADC) / GPIO3(LED) 的假设成立，引用来源：

```c
// espressif__esp32_s3_eye_5.0.2 / esp32_s3_eye.c
static const bsp_button_config_t bsp_button_config[BSP_BUTTON_NUM] = {
    { .type = BSP_BUTTON_TYPE_ADC, .cfg.adc = { .adc_channel = ADC_CHANNEL_0, /* GPIO1 */
        .button_index = BSP_BUTTON_MENU, .min = 2310, .max = 2510 } },
    { ... BSP_BUTTON_PLAY, 1880, 2080 },
    { ... BSP_BUTTON_DOWN,  720,  920 },
    { ... BSP_BUTTON_UP,    280,  480 },
    { .type = BSP_BUTTON_TYPE_GPIO, .cfg.gpio = { .active_level = 0, .gpio_num = BSP_BUTTON_BOOT_IO } },
};
```

## 8. 2026-10-30 00:42 那次探测为什么全 FAIL

日志：`artifacts/logs/input_probe_20261030_004213.log`

```text
BOOT  FAIL  低电平样本 0/71  (样本 71)
MENU  FAIL  窗口 2310-2510 mV 命中 0/83，最低 3191 mV  (样本 83)
PLAY  FAIL  ... 最低 3191 mV
UP    FAIL  ... 最低 3191 mV
DOWN  FAIL  ... 最低 3191 mV
```

原因：**操作者整轮没有按下任何按键**（用户已确认），全部读数停留在释放态 3191 mV / raw 4095。
因此这一轮不构成"按键失效"的证据，只说明采样链路在跑。
当时的副作用是旧版脚本把 10 Hz 采样原样刷屏，提示文字被淹没，体验上像是"卡住了"。

已修复：`tools/input-probe.py` 现在静音固件日志，改为
「按回车 → 3 秒倒计时 → 单行实时读数 → 阶段结论」，并新增 `--free` 自由扫描模式。

## 9. 底部按键位置（用户现场确认）

底部 USB 口旁边并排两个小键：**左边 = BOOT（GPIO0）**，**右边 = RST（硬复位）**。

推论：验证 BOOT 时按左边那个；按到右边会导致板子重启，串口出现 boot log，探针窗口会中断——那不是故障。

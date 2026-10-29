# PocketPilot T0 — BLE HID 通道穿刺固件

> 目标：只验证一件事——**用串口命令让手机执行媒体键动作**。
> 不含语音识别、不含 App、不含实体按键成品逻辑。
> 适配板卡：ESP32-S3-EYE（8 MB Flash，带 PSRAM）｜参考机：市面主流安卓手机（具体型号待填）

---

## 1. 这个固件到底做什么

板子启动后作为 **BLE HID 复合设备**广播，设备名 `PocketPilot T0`，包含两类报告：

| Report ID | 类型 | 内容 |
|---|---|---|
| 1 | 标准键盘（8 字节） | Esc、方向键→（**仅技术探测，不算 L1 产品能力**） |
| 2 | Consumer Control（2 字节） | 音量+、音量−、播放/暂停、下一首、上一首、静音 |

电脑串口里敲一个字母，板子就发"按下 → 40 ms → 松开"这一组 HID 报文。

**连接方式（重要）**

- 开发板 USB 口 → 电脑：只负责 **供电 / 烧录 / 串口日志**。
- 手机 → 板子：**BLE 无线**。手机不插数据线，也不用 USB OTG。
- 手机不接线的状态下，手机能不能收到键，才是这个原型要验证的东西。

---

## 2. 环境

| 项 | 值 |
|---|---|
| ESP-IDF | v5.4.3（本机路径 `C:\Espressif\frameworks\esp-idf-v5.4.3`） |
| Target | esp32s3 |
| 蓝牙协议栈 | Bluedroid，BLE 4.2，SMP 配对已开启 |
| Flash | 8 MB，分区表 `Single factory app (large)` |
| 串口波特率 | 115200（日志）/ 460800（烧录用） |
| 板载 USB | 已实测枚举为 `VID_303A&PID_1001`（ESP32-S3 原生 USB-Serial/JTAG） |

> 工程路径若含非 ASCII 字符（例如中文目录名），ESP32 工具链会直接报
> `filesystem error: Cannot convert character sequence`。
> 所以 **不要手动 `idf.py build`**，用下面的脚本——它会自动把源码镜像到纯英文目录再编译。

---

## 3. 一条命令构建

```powershell
cd firmware\t0_hid_probe
.\build.ps1
```

脚本做的事：

1. 激活 ESP-IDF（路径可用 `-IdfPath` 覆盖）；
2. 把源码镜像到 `D:\pocketpilot_t0_build`（`-MirrorDir` 可改）；
3. 在英文路径下 `idf.py build`；
4. 校验 `esp_hid_device.bin / .elf / .map / bootloader.bin / partition-table.bin` **都真实存在**，
   否则报错退出（避免"看起来成功"）；
5. 把产物、`sdkconfig`、构建日志、构建记录（含 SHA256）复制回 `.\artifacts\`。

改完 `main/esp_hid_device_main.c` 里的 HID 报告描述符后，先跑一次静态校验（失败会返回非 0）：

```powershell
python .\tools\check_report_map.py
# input report 1 : 64 bits = 8 bytes  OK
# input report 2 : 16 bits = 2 bytes  OK
# RESULT: PASS
```

常用变体：

```powershell
.\build.ps1 -Clean          # 删掉 build 目录，从零重建
.\build.ps1 -Reconfigure    # 改过 sdkconfig 后重新跑 CMake
```

成功的标志（最后几行）：

```
[build] OK
  app     : firmware\t0_hid_probe\artifacts\esp_hid_device.bin  (721.5 KB)
  sha256  : AA780712...
```

产物目录：

```
artifacts\
  esp_hid_device.bin      应用固件
  esp_hid_device.elf      带符号，用于崩溃分析
  esp_hid_device.map      内存布局
  bootloader.bin
  partition-table.bin
  flasher_args.json
  BUILD-INFO.txt          时间 / IDF 版本 / 大小 / SHA256
  sdkconfig               实际生效的配置
  logs\build.log
```

---

## 4. 烧录 + 看串口

```powershell
# 插好板子后，自动找串口、烧录
.\flash.ps1

# 烧录并打开串口监视器，同时把日志存到 artifacts\logs\serial_*.log
.\flash.ps1 -Monitor

# 只开串口（不重新烧）
.\flash.ps1 -MonitorOnly -Monitor

# 先擦除（会清掉手机端的配对信息，蓝牙配对出问题时用）
.\flash.ps1 -Erase -Monitor
```

- 串口找不到时手动指定：`.\flash.ps1 -Port COM5 -Monitor`
- 退出监视器：**Ctrl + ]**
- 板子上的 USB 口在当前电脑上应显示为 `Silicon Labs CP210x` 一类串口设备（装好驱动即可）。

---

## 5. 和安卓手机配对

1. 手机：设置 → 蓝牙，打开蓝牙。
2. 板子上电后几秒内，扫描列表里出现 **`PocketPilot T0`**。
3. 点它配对。可能出现"配对"弹窗，确认即可（无需输入 PIN；若弹出 6 位码，确认两边一致）。
4. 串口会打印：

```
PP_STATE,ts_ms=12345,state=HID_START
PP_STATE,ts_ms=15230,state=HID_READY
PP_STATE,ts_ms=15310,state=HID_CONNECTED
```

5. 看到 `HID_CONNECTED` 才算连上。此时手机输入法/媒体音量应该能被这个"蓝牙键盘"控制。

**配对不上怎么办**

| 现象 | 处理 |
|---|---|
| 扫描列表里没有设备 | 看串口有没有 `HID_START`；复位板子；确认没被别的手机占用（HID 一般只连一台） |
| 点了没反应/超时 | 板子 `-Erase` 重烧，手机端"忽略此设备"后重扫 |
| 连上后按键无反应 | 先在手机蓝牙里确认设备状态是"已连接"；再用 `a`/`z` 试音量（最容易观察） |
| 反复断连 | 手机省电策略会杀后台；iOS/国产 ROM 差异大，先记录现象，别急着改代码 |

---

## 6. 串口命令表

| 键 | 动作 | HID 报告 |
|---|---|---|
| `a` | 音量 + | Consumer 0x00E9 |
| `z` | 音量 − | Consumer 0x00EA |
| `p` | 播放/暂停 | Consumer 0x00CD |
| `n` | 下一首 | Consumer 0x00B5 |
| `b` | 上一首 | Consumer 0x00B6 |
| `m` | 静音 | Consumer 0x00E2 |
| `e` | Esc（探测用） | Keyboard 0x29 |
| `r` | 方向键→（探测用） | Keyboard 0x4F |
| `?` | 帮助 | — |

> 串口里直接敲字母即可，不需要回车。
> `e` / `r` 只用于确认"键盘报告通道"通不通，**不作为 L1 验收项**——安卓上 Esc 不保证等于"返回"。

---

## 7. 日志格式（给 CSV 用）

每条事件一行，机器可读：

```
PP_EVT,ts_ms=48213,phase=press,action=VOLUME_UP,report_id=2,status=0
PP_EVT,ts_ms=48253,phase=release,action=VOLUME_UP,report_id=2,status=0
```

| 字段 | 含义 |
|---|---|
| `ts_ms` | 板子上电以来的毫秒数（`esp_timer`） |
| `phase` | `press` / `release` |
| `action` | `VOLUME_UP` `VOLUME_DOWN` `PLAY_PAUSE` `NEXT_TRACK` `PREV_TRACK` `MUTE` `ESC` `RIGHT_ARROW` |
| `report_id` | 1=键盘，2=Consumer |
| `status` | `0` = 已提交给协议栈；非 0 = 失败（常见 `ESP_ERR_INVALID_STATE` 表示手机没连上） |

连接状态单独用 `PP_STATE,...`：

```
PP_STATE,ts_ms=...,state=HID_START | HID_READY | HID_CONNECTED | HID_DISCONNECTED | HID_STOP | HID_CONTROL | HID_PROTOCOL_MODE
```

**注意 `status=0` 只代表"板子把报文发出去了"，不代表"手机执行了"。**
手机到底动没动，必须人眼观察 + 录屏，记进 CSV。这是本轮验收的关键区分。

把串口日志转成事件表：

```powershell
python .\tools\pp_log_to_csv.py .\artifacts\logs\serial_20261028_200000.log
# 输出同名 .events.csv，保留 connected / status / code（判定发送失败必需）
```

---

## 8. 实机测试流程（T0，必须你来跑）

1. 板子 USB 接电脑 → `.\flash.ps1 -Monitor`
2. 手机配对 `PocketPilot T0`，确认串口出现 `HID_CONNECTED`
3. 按三种状态各测一轮，每种状态 6 个动作 × 10 次：

   | 状态 | 说明 |
   |---|---|
   | `unlocked` | 亮屏解锁，停在桌面 |
   | `locked` | 锁屏，无媒体播放 |
   | `locked_background_music` | 锁屏，后台正在播放音乐 |

4. 每次按键记录：手机是否真的变化、从敲键到变化的延迟（手机 60/120 fps 录屏逐帧看，或用秒表粗测）。
5. 把结果填进 [`T0-平台通道穿刺记录表.csv`](../../T0-平台通道穿刺记录表.csv)（18 行，已按 3 状态 × 6 动作排好）。
6. 串口日志原样保留在 `artifacts\logs\`，不要手改。

**判定口径**

- `successes` 只统计"手机确实发生了预期变化"的次数，不计"板子发出去了"。
- 锁屏且无媒体会话时，媒体键可能被系统吞掉或只调音量——**记录实际现象**，这属于系统行为，不等于蓝牙通道失败。
- 只有 `status=0` 且手机有响应，才算这一格有效。

---

## 9. 已知限制（别当成 bug 反复查）

- **没有喇叭**：EYE 板子不能做语音播报，反馈只能靠屏幕/手机。
- **Esc / 方向键不等价于"返回/回桌面"**：安卓不保证；这是 L2 用 App + 无障碍才能做的事。
- **HID 一般只能连一台主机**：换手机测试前先在旧手机上"忽略设备"或给板子 `-Erase`。
- **国产 ROM 差异**：小米/OPPO/vivo 等的省电策略、后台限制会影响锁屏后的媒体响应，需按机型记录。
- **复合报告描述符只在源码层面核对过**，安卓各版本对 Consumer Report 的解析可能有差异；若整组媒体键都无反应，先怀疑报告描述符，再怀疑手机。

---

## 10. 当前验证状态（诚实声明）

| 项 | 状态 |
|---|---|
| 代码编译 + 链接 + 生成 bin | ✅ ESP-IDF v5.4.3 通过（见 `artifacts/BUILD-INFO.txt`） |
| 烧录到实机 | ✅ 已烧录并启动（COM5，2026-10-28 20:26） |
| 电脑 → 板子串口控制 | ✅ 已打通；未连接时按 `a` 返回 `action=VOLUME_UP, status=FAIL, code=259` |
| 手机能发现并配对 | ⬜ 未验证 |
| 6 个媒体键在安卓上生效 | ⬜ 未验证 |
| 三种锁屏状态行为 | ⬜ 未验证 |
| 180 次 T0 测试 | ⬜ 未执行（CSV 18 行仍为"未验证"） |

> `code=259`（`0x103`）= `ESP_ERR_INVALID_STATE`，在这里表示"手机还没连上"，是未连接状态下的**预期错误**，不是固件故障。

**2026-10-28 20:26 实机记录**

- 串口：COM5；BLE MAC：`94:a9:90:1c:6f:d6`
- App：`738,848` 字节（721.5 KB）
- SHA256：`AA7807122B7A35BCCAD27E7A43811BB85AC681EEE23086E7FE307AAFE74A6E73`
- 串口日志：`artifacts/logs/serial_20261028_202615.log`

> 在 `T0-平台通道穿刺记录表.csv` 有真实数据之前，**不能对外说"能用"或"通过验收"**。

---

## 11. 目录结构

```
t0_hid_probe/
  CMakeLists.txt
  sdkconfig.defaults
  sdkconfig.defaults.esp32s3
  main/
    esp_hid_device_main.c   业务逻辑：报告描述符 + 串口命令 + 日志
    esp_hid_gap.c/.h        来自 ESP-IDF 官方 esp_hid_device 示例（GAP/广播/安全）
  build.ps1                 构建（自动处理中文路径）
  flash.ps1                 烧录 + 串口记录
  tools/pp_log_to_csv.py    串口日志 → 事件 CSV（含 connected/status/code）
  tools/check_report_map.py HID 报告描述符静态校验（长度断言）
  artifacts/                构建产物与日志（不入库）
```




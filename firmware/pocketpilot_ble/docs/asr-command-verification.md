# ESP-SR 中文命令词识别验证（ESP32-S3-EYE / COM5）

记录时间：2026-10-30
最终固件：`artifacts/pocketpilot_ble.bin`（SHA256 `2F6D83548566FF8AE85C3222DA3F13D9DAEA95D8156B7321FC79080A859387DF`）
三条命令首测固件：SHA256 `3C7B6EFE6F40B6EAFD6A87A40DF92CC9390C8C487F9CFF6AD59BE7BB720C7D5A`
模型：`artifacts/srmodels.bin`，2,681,351 字节，SHA256 `DEC3196AAADCF4E6305434462F5735E1148E5DDFE1F6A0696776D666F9066D34`
硬件：ESP32-S3-EYE 板载麦克风（BCLK=GPIO41，WS=GPIO42，DIN=GPIO2）+ 正面 PLAY 键
工具链：ESP-IDF v5.4.3，esp-sr `espressif/esp-sr`（MultiNet7 中文量化模型 `mn7_cn`）

## 1. 结论

| 项目 | 状态 | 证据 |
| --- | --- | --- |
| `srmodels.bin` 生成 | **通过** | 2,681,351 字节，位于 `build/srmodels/srmodels.bin` |
| 模型烧录到 `model` 分区 | **通过** | `Wrote 2681352 bytes ... at 0x00310000`，`Hash of data verified` |
| 模型从分区加载 | **通过** | `MODEL_LOADER: The partition size is 4096 KB` / `Successfully load srmodels` |
| 中文命令词注册 | **通过（拼音回退）** | 汉字格式被模型拒绝，拼音 3/3 注册成功 |
| “打开地图”识别 | **通过** | `PP_ASR_RESULT id=2 text=" da kai di tu"` |
| “打开微信”识别 | **通过** | `PP_ASR_RESULT id=1 text=" da kai wei xin"` |
| “返回”识别 | **通过** | `PP_ASR_RESULT id=3 text=" fan hui"` |
| 识别结果进入 BLE 发送路径 | **通过** | `PP_ASR_DISPATCH id=.. name=..` 后进入 `pp_send_frame()` |
| 5 s 内无声/未命中 | **通过** | `PP_ASR_NO_MATCH`，无派发、无发送，`matched=0` |
| BLE 上报到安卓手机 | **尚未验证** | 无实体手机；`PP_ASR_SEND_FAILED connected=0 subscribed=0` 属预期 |
| 手机端动作执行 | **尚未验证** | 本文件只覆盖开发板侧 |
| 识别率、不同距离/噪声鲁棒性 | **尚未验证** | 每个命令仅 1 次有效样本，不足以统计 |
| 模型原始分数（`score`）含义 | **未确定** | 同一命令两次命中分别为 1.44 与 0.24，不能当置信度阈值使用 |

明确边界：本文件证明“按键 → 板上麦克风 → 本地中文命令词识别 → 生成对应 BLE 命令帧”这一步在真实硬件上可用。
**不证明**命令已送达手机，更不证明手机动作已执行。

## 2. `srmodels.bin` 不生成的根因

esp-sr 打包模型的条件写在组件 CMakeLists 中：

```cmake
if(CONFIG_PARTITION_TABLE_CUSTOM)
    partition_table_get_partition_info(size "--partition-name model" "size")
    ...
    add_custom_target(srmodels_bin ALL DEPENDS ${image_file})
```

只要没有启用自定义分区表，`srmodels_bin` 目标根本不会被创建，构建仍会“成功”，但产物里没有 `srmodels.bin`。

实际原因：`sdkconfig.defaults.esp32s3` 里残留了

```
CONFIG_PARTITION_TABLE_SINGLE_APP_LARGE=y
```

ESP-IDF 的加载顺序是 `sdkconfig.defaults` → `sdkconfig.defaults.<target>`，目标专属文件后加载，**覆盖**了 `sdkconfig.defaults` 里的 `CONFIG_PARTITION_TABLE_CUSTOM=y`。

修复：将该行改为 `CONFIG_PARTITION_TABLE_CUSTOM=y`，并用 `build.ps1 -Clean -FreshSdkconfig` 重新生成配置。

修复后启动日志确认分区已生效：

```
I (28) boot: Partition Table:
I (29) boot:  2 factory   factory app  00 00 00010000 00300000
I (30) boot:  3 model     Unknown data 01 82 00310000 00400000
```

`factory` 3 MB，`model` 4 MB（0x310000–0x710000），app 实际占用 0x16fc20，剩余 52%。

## 3. 命令词格式：汉字不可用，必须用拼音

启动时先用 `check_speech_command()` 实测两种写法，两种都试过才决定，不靠猜：

```
Command 打开微信 is invalid, please check format.
I (2267) PP_ASR_FMT id=1 native="打开微信"=0 fallback="da kai wei xin"=1
Command 打开地图 is invalid, please check format.
I (2267) PP_ASR_FMT id=2 native="打开地图"=0 fallback="da kai di tu"=1
Command 返回 is invalid, please check format.
I (2267) PP_ASR_FMT id=3 native="返回"=0 fallback="fan hui"=1
W (2267) PP_ASR_FMT_PICK fallback ok=3/3（模型不接受汉字）
```

最终注册状态：

```
I (2317) MN_COMMAND: 3 active speech commands:
Command 1: da kai wei xin
Command 2: da kai di tu
Command 3: fan hui
I (2317) PocketPilotASR: PP_ASR_CMD_READY format=pinyin
I (2317) PocketPilotASR: PP_ASR_READY model=mn7_cn feed_chunk=512 feed_ch=1 fetch_chunk=512
```

结论：这台设备 + 当前 esp-sr 版本下，命令行用汉字写命令词会全部被拒；用拼音空格分词可用。
**注意**：只是命令词用拼音登记，用户照常说中文，模型直接输出对应 ID。

## 4. 实测记录

触发方式统一为：按一次正面 **PLAY** 键 → 5 s 内对着板子说口令。
固件侧 `PP_ASR_LISTEN_START source=PLAY ms=5000`，监听期间 LED 快闪。

| # | 口令 | 结果 | 命令 ID | 派发命令名 | 按下→派发 | 日志文件 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 打开地图 | 通过 | 2 | `COMMAND_OPEN_MAP` | 2850 ms | `serial_asr_probe.log` |
| 2 | 打开微信 | 通过 | 1 | `COMMAND_OPEN_WECHAT` | 2480 ms | `serial_asr_probe.log` |
| 3 | 返回 | 通过 | 3 | `COMMAND_BACK` | 2094 ms | `serial_asr_probe.log` |
| 4 | 打开地图（回归） | 通过 | 2 | `COMMAND_OPEN_MAP` | 2570 ms | `serial_asr_regression2.log` |
| 5 | 不说话（串口 `v` 触发） | 通过（正确拒识） | — | 无派发 | 5 s 窗口结束 | `serial_asr_nomatch.log` |

第 4 次是修改 `score` 打印格式后的最终固件回归，确认改动没有影响识别。

原始日志片段（第 2 次，打开微信）：

```
I (134577) PocketPilotBtn: PP_BTN event=PLAY source=front_panel
I (134577) PocketPilotBLE: PP_ASR_LISTEN_START source=PLAY ms=5000
I (134577) PocketPilotASR: PP_ASR_LISTEN_BEGIN ms=5000 chunk=512 ch=1 raw_bytes=4608
I (136577) PocketPilotASR: PP_ASR_WAIT t_ms=2007 vol=-50.3 dBFS vad=1
I (137057) PocketPilotASR: PP_ASR_RESULT id=1 text=" da kai wei xin" raw="" prob=35% t_ms=2480
I (137057) PocketPilotBLE: PP_ASR_DISPATCH id=1 name=COMMAND_OPEN_WECHAT
W (137057) PocketPilotBLE: PP_SEND_BLOCKED name=COMMAND_OPEN_WECHAT ready=1 connected=0 subscribed=0
W (137057) PocketPilotBLE: PP_ASR_SEND_FAILED name=COMMAND_OPEN_WECHAT connected=0 subscribed=0
I (137057) PocketPilotASR: PP_ASR_LISTEN_END matched=1
```

第 4 次（最终固件，`score` 已改为原始分数）：

```
I (76607) PocketPilotBtn: PP_BTN event=PLAY source=front_panel
I (79177) PocketPilotASR: PP_ASR_RESULT id=2 text=" da kai di tu" raw="" score=0.24 t_ms=2162
I (79177) PocketPilotBLE: PP_ASR_DISPATCH id=2 name=COMMAND_OPEN_MAP text=" da kai di tu" score=0.24
```

第 5 次为无声对照，验证未命中路径不会误派发：

```
I (74437) PocketPilotASR: PP_ASR_LISTEN_BEGIN ms=5000 chunk=512 ch=1 raw_bytes=4608
W (79467) PocketPilotASR: PP_ASR_NO_MATCH 本次未识别到命令词
I (79467) PocketPilotASR: PP_ASR_LISTEN_END matched=0
I (79467) PocketPilotBLE: PP_ASR_WINDOW_END matched=0
```

`PP_SEND_BLOCKED` / `PP_ASR_SEND_FAILED` 是正确行为：没有手机连接时**不允许**把“识别成功”记成“命令已送达”。

## 5. 时延

| 区间 | 实测值 | 说明 |
| --- | --- | --- |
| 按下 PLAY → 开始喂音频 | 410 ms | 含 `PP_MIC_DRAIN ms=400` 丢弃启动瞬态 |
| 开始喂音频 → 输出识别结果 | 2162–2443 ms | `t_ms` 字段，**包含用户说话时长** |
| 按下 PLAY → 进入 BLE 发送 | 2094–2850 ms | 端到端（不含手机） |

这些数字包含人说话的时间，不是“模型推理延迟”，也**不是** README 中定义的端到端响应时间（那还需要手机动作与结果反馈）。

## 6. 复现步骤

```powershell
cd 'D:\个人项目\firmware\pocketpilot_ble'

# 1. 构建（分区表/PSRAM/模型配置变更后必须带这两个开关）
.\build.ps1 -Clean -FreshSdkconfig

# 2. 烧录（会一并写入 0x310000 的 srmodels.bin）
.\flash.ps1 -Port COM5 -Monitor

# 3. 看到 PP_ASR_READY 后，按一次正面 PLAY，5 s 内说“打开地图”
```

判断成功的标志：

```
PP_ASR_LISTEN_START source=PLAY ms=5000
PP_ASR_RESULT id=2 text=" da kai di tu" ...
PP_ASR_DISPATCH id=2 name=COMMAND_OPEN_MAP ...
```

串口单键 `v` 也能触发同样的 5 s 监听，用于不按键的调试。

## 7. 证据文件

- `artifacts/logs/serial_asr_probe.log`：三条命令首测（含启动、模型加载、格式探测全文）
- `artifacts/logs/serial_asr_regression2.log`：最终固件回归
- `artifacts/logs/serial_asr_nomatch.log`：无声对照（未命中路径）
- `artifacts/logs/flash.log`、`flash3.log`：烧录记录（含 0x310000 写入与 hash 校验）
- `artifacts/logs/build.log`、`build3.log`：构建记录
- `artifacts/BUILD-INFO.txt`：固件 SHA256 与构建环境
- `artifacts/srmodels.bin`：模型产物

## 8. 未验证项与下一步

1. **识别率**：每个命令只有 1 次样本。需要至少 10 次/命令，记录命中/未命中、距离（20/50/100 cm）与环境噪声，才能给出正确率。
2. **`score` 含义**：两次命中分别为 1.44 和 0.24，说明该字段不是可用置信度。在弄清其定义前，不设“低于阈值即拒识”的逻辑，也不对外宣称置信度。
3. **误唤醒**：未测试“说别的词”是否会误命中（应在每条命令旁加 5 条近似负样本）。
4. **BLE 端到端**：需要安卓手机连接后确认 `COMMAND_OPEN_MAP` 真的送达并被执行。
5. **LED 状态**：识别窗口结束后 LED 是否恢复慢闪，未逐帧核对。


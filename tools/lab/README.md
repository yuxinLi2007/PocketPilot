# PocketPilot Lab · 开发监测站

开发、调试、演示共用的本地 Web 仪表盘。读取 Android 端 `ResultsStore` 输出的
`results*.jsonl` 与自测日志，零外部依赖（仅 Python 标准库，无需安装任何包）。

## 启动

```powershell
# 在仓库根目录
python tools\lab\lab_server.py
# 浏览器打开 http://127.0.0.1:8787
```

可选参数：

| 参数 | 默认 | 说明 |
|---|---|---|
| `--data-dir` | `<仓库>/android/docs` | results*.jsonl 与自测日志所在目录 |
| `--port` | 8787 | 服务端口 |

页面每 3 秒自动刷新；改数据目录后无需重启服务。

## 展示内容

对应过程文档 4.6 节约定的开发者可见数据边界——**只看执行，不看用户生活**：

- **概览 KPI**：执行记录总数、执行成功率、拒绝数、命令来源/设备。
- **命令类型分布**：打开微信 / 打开地图 / 返回上一页 / 协议错误。
- **执行状态分布**：RECEIVED → EXECUTED_UNCONFIRMED → CONFIRMED，及 PERMISSION_REQUIRED / NOT_INSTALLED / REJECTED。
- **各阶段耗时**：按命令统计 avg / p95 / max（仅统计 elapsed_ms > 0 的记录）。
- **错误代码**：从 evidence/detail 自动提取（如 `MALFORMED_FRAME`、`MISSING_ARGUMENT`、`ACCESSIBILITY_DISABLED`）。
- **任务执行记录表**：时间、命令、状态、耗时、来源、错误代码、详情，支持按命令/状态筛选与关键字搜索（显示最近 300 条）。
- **自测摘要**：协议自测、模拟器回归（含设备版本与序列号）、Planner 自测的 PASS/FAIL 计数。

## 数据从哪里来

- **模拟器/真机**：`adb shell run-as com.pocketpilot.app cat files/results.jsonl`
  输出重定向到 `android/docs/results-<场景>.jsonl` 即可被读取（现有文件即为阶段 8 产物）。
- **自测日志**：`protocol-selftest-latest.txt`、`emulator-regression-latest.txt`、
  `planner-selftest-latest.txt` 放入同一数据目录。

## 边界

- 本工具只做本地只读展示，不上传、不监听外网（默认绑定 127.0.0.1）。
- 不收集、不展示语音录音、聊天记录、通知正文、屏幕截图、联系人（与过程文档隐私原则一致）。

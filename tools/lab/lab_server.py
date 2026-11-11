#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""PocketPilot Lab —— 开发监测站（本地 Web 仪表盘）

读取 Android 端 ResultsStore 输出的 results*.jsonl 与自测日志，
为开发者提供：命令类型、执行状态、各阶段耗时、错误代码、设备/来源信息。

用法（仅依赖 Python 3.8+ 标准库）：
    python tools/lab/lab_server.py                       # 默认读取 android/docs/
    python tools/lab/lab_server.py --data-dir 其他目录    # 指定数据目录
    python tools/lab/lab_server.py --port 8787

浏览器打开 http://127.0.0.1:8787 ，页面每 3 秒自动刷新。
"""

import argparse
import glob
import json
import os
import re
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent
INDEX_HTML = ROOT / "index.html"

STATUS_ORDER = [
    "RECEIVED", "EXECUTED_UNCONFIRMED", "CONFIRMED",
    "PERMISSION_REQUIRED", "NOT_INSTALLED", "REJECTED",
]

STATUS_LABEL = {
    "RECEIVED": "已接收",
    "EXECUTED_UNCONFIRMED": "已执行·未确认",
    "CONFIRMED": "已确认",
    "PERMISSION_REQUIRED": "需权限",
    "NOT_INSTALLED": "应用未安装",
    "REJECTED": "已拒绝",
}

COMMAND_LABEL = {
    "COMMAND_OPEN_MAP": "打开地图",
    "COMMAND_OPEN_WECHAT": "打开微信",
    "COMMAND_BACK": "返回上一页",
    "PROTOCOL_ERROR": "协议错误",
}


def extract_error_code(record):
    """从 evidence / detail 提取错误代码（如 MALFORMED_FRAME、MISSING_ARGUMENT）。"""
    evidence = record.get("evidence") or ""
    m = re.search(r"(?:frame_rejected|reason)=([A-Z][A-Z0-9_]*)", evidence)
    if m:
        return m.group(1)
    detail = record.get("detail") or ""
    head = detail.split("：")[0].split(":")[0].strip()
    if re.fullmatch(r"[A-Z][A-Z0-9_]*", head) and head not in ("PP1",):
        return head
    return ""


def load_records(data_dir):
    records = []
    for path in sorted(glob.glob(str(Path(data_dir) / "results*.jsonl"))):
        base = os.path.basename(path)
        with open(path, encoding="utf-8") as fh:
            for line_no, line in enumerate(fh, 1):
                line = line.strip()
                if not line:
                    continue
                try:
                    d = json.loads(line)
                except json.JSONDecodeError:
                    continue
                records.append({
                    "file": base,
                    "line": line_no,
                    "event": d.get("event", ""),
                    "command": d.get("command", ""),
                    "command_id": d.get("command_id", ""),
                    "argument": d.get("argument", ""),
                    "source": d.get("source", ""),
                    "status": d.get("status", ""),
                    "status_label": d.get("status_label", ""),
                    "detail": d.get("detail", ""),
                    "evidence": d.get("evidence", ""),
                    "elapsed_ms": d.get("elapsed_ms", 0),
                    "finished_at_ms": d.get("finished_at_ms", 0),
                    "error_code": extract_error_code(d),
                })
    records.sort(key=lambda r: r["finished_at_ms"])
    return records


def percentile(sorted_values, pct):
    if not sorted_values:
        return 0
    idx = min(len(sorted_values) - 1, max(0, round(pct / 100 * (len(sorted_values) - 1))))
    return sorted_values[idx]


# 这些错误代码对应的"被拒命令"是阶段测试中故意注入的异常用例
# （缺参数 / 超长地址 / 重复帧 / 非法帧 / 未知命令等），被拦截 = 防护有效
INJECTION_CODES = {
    "MISSING_ARGUMENT", "ARGUMENT_TOO_LONG", "DUPLICATE_SUPPRESSED",
    "UNKNOWN_COMMAND", "UNSUPPORTED_VERSION", "FRAME_TOO_LONG", "MALFORMED_FRAME",
}


def summarize_chains(records):
    """命令级统计：一条命令（command_id）从接收到最终状态算一条，
    避免"已接收→已执行→已确认"多条记录把成功率稀释。"""
    chains = {}
    guard_total = guard_blocked = 0
    for r in records:
        if r["command"] == "PROTOCOL_ERROR":
            guard_total += 1
            if r["status"] == "REJECTED":
                guard_blocked += 1
            continue
        cid = r["command_id"]
        if not cid:
            continue
        prev = chains.get(cid)
        if prev is None or r["finished_at_ms"] >= prev["finished_at_ms"]:
            chains[cid] = r

    by_final = {}
    executed = env_blocked = failed = injected = 0
    env_detail = {}
    for r in chains.values():
        st = r["status"]
        by_final[st] = by_final.get(st, 0) + 1
        if st in ("CONFIRMED", "EXECUTED_UNCONFIRMED"):
            executed += 1
        elif st in ("NOT_INSTALLED", "PERMISSION_REQUIRED"):
            env_blocked += 1
            env_detail[st] = env_detail.get(st, 0) + 1
        elif st == "REJECTED":
            if r["error_code"] in INJECTION_CODES:
                injected += 1
            else:
                failed += 1

    return {
        "total": len(chains),
        "executed": executed,
        "env_blocked": env_blocked,
        "env_detail": env_detail,
        "injected": injected,
        "failed": failed,
        "success_rate": round(executed * 100 / max(1, executed + failed)),
        "by_final_status": by_final,
        "guard": {"total": guard_total, "blocked": guard_blocked},
    }


def summarize(records):
    by_status = {s: 0 for s in STATUS_ORDER}
    by_command = {}
    by_error = {}
    by_source = {}
    latency = {}  # command -> list of elapsed_ms
    for r in records:
        by_status[r["status"]] = by_status.get(r["status"], 0) + 1
        by_command[r["command"]] = by_command.get(r["command"], 0) + 1
        by_source[r["source"]] = by_source.get(r["source"], 0) + 1
        if r["error_code"]:
            key = r["error_code"]
            by_error[key] = by_error.get(key, 0) + 1
        if r["elapsed_ms"] > 0:
            latency.setdefault(r["command"], []).append(r["elapsed_ms"])

    latency_stats = {}
    for cmd, values in latency.items():
        values.sort()
        latency_stats[cmd] = {
            "count": len(values),
            "avg_ms": round(sum(values) / len(values)),
            "p95_ms": percentile(values, 95),
            "max_ms": values[-1],
        }

    finished = [r["finished_at_ms"] for r in records if r["finished_at_ms"]]
    return {
        "total": len(records),
        "confirmed": by_status.get("CONFIRMED", 0),
        "executed_unconfirmed": by_status.get("EXECUTED_UNCONFIRMED", 0),
        "rejected": by_status.get("REJECTED", 0),
        "by_status": by_status,
        "by_command": by_command,
        "by_error": by_error,
        "by_source": by_source,
        "latency": latency_stats,
        "latest_ms": finished[-1] if finished else 0,
    }


def load_selftests(data_dir):
    """解析协议自测 / 模拟器回归 / Planner 自测的 PASS-FAIL 摘要与设备信息。"""
    out = {}
    p = Path(data_dir)
    protocol = p / "protocol-selftest-latest.txt"
    if protocol.exists():
        passed = failed = 0
        for line in protocol.read_text(encoding="utf-8", errors="replace").splitlines():
            if line.startswith("PASS"):
                passed += 1
            elif line.startswith("FAIL"):
                failed += 1
        out["protocol"] = {"name": "协议自测", "passed": passed, "failed": failed}

    emulator = p / "emulator-regression-latest.txt"
    if emulator.exists():
        text = emulator.read_text(encoding="utf-8", errors="replace")
        passed = len(re.findall(r"^\s*PASS\b", text, re.M))
        failed = len(re.findall(r"^\s*FAIL\b", text, re.M))
        info = {}
        for key in ("time", "serial", "device"):
            m = re.search(rf"^{key}\s*:\s*(.+)$", text, re.M)
            if m:
                info[key] = m.group(1).strip()
        out["emulator"] = {
            "name": "模拟器回归", "passed": passed, "failed": failed, **info,
        }

    planner = p / "planner-selftest-latest.txt"
    if planner.exists():
        text = planner.read_text(encoding="utf-8", errors="replace")
        passed = len(re.findall(r"\bPASS\b", text))
        failed = len(re.findall(r"\bFAIL\b", text))
        out["planner"] = {"name": "Planner 自测", "passed": passed, "failed": failed}
    return out


class Handler(BaseHTTPRequestHandler):
    data_dir = None

    def log_message(self, fmt, *args):  # 静默访问日志
        pass

    def _send(self, code, body, ctype):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path in ("/", "/index.html"):
            body = INDEX_HTML.read_bytes()
            self._send(200, body, "text/html; charset=utf-8")
        elif self.path == "/api/data":
            records = load_records(self.data_dir)
            payload = {
                "generated_at": time.strftime("%Y-%m-%d %H:%M:%S"),
                "summary": summarize(records),
                "chains": summarize_chains(records),
                "selftests": load_selftests(self.data_dir),
                "records": records[-1000:],
            }
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self._send(200, body, "application/json; charset=utf-8")
        else:
            self._send(404, b"not found", "text/plain; charset=utf-8")


def main():
    ap = argparse.ArgumentParser(description="PocketPilot Lab 本地监测站")
    repo_root = ROOT.parent.parent
    ap.add_argument("--data-dir", default=str(repo_root / "android" / "docs"),
                    help="results*.jsonl 与自测日志所在目录（默认 android/docs）")
    ap.add_argument("--port", type=int, default=8787)
    args = ap.parse_args()

    Handler.data_dir = args.data_dir
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"PocketPilot Lab 已启动: http://127.0.0.1:{args.port}")
    print(f"数据目录: {args.data_dir}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n已停止")


if __name__ == "__main__":
    main()

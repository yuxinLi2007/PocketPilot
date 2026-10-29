#!/usr/bin/env python3
"""PocketPilot T0 Go/No-Go evaluator (deterministic, no guessing).

Reads the 18-row key-state CSV, the 10-row pairing/reconnect CSV and the
serial logs, then applies the thresholds defined in
"PocketPilot-最小原型验收标准与开发流程.md" and writes a Markdown report.

Design rule: anything that cannot be derived from the raw files is reported
as "数据不足" - the tool never fills gaps with assumptions.

Usage:
    python tools/t0_evaluate.py
    python tools/t0_evaluate.py --csv <keys.csv> --link <link.csv> \
        --logs <log dir> --out <report.md>
"""
from __future__ import annotations

import argparse
import csv
import re
import statistics
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

STATES = ["unlocked", "locked", "locked_background_music"]
STATE_LABEL = {
    "unlocked": "解锁",
    "locked": "锁屏",
    "locked_background_music": "锁屏+后台音乐",
}
ACTIONS = ["音量+", "音量-", "播放/暂停", "下一首", "上一首", "静音"]
CORE_ACTIONS_LOCKED = {"播放/暂停", "音量+", "音量-"}
PLACEHOLDER = {"", "待填", "tbd", "TBD", "-"}
PASS, PARTIAL, FAIL, UNKNOWN = "通过", "部分通过", "不通过", "数据不足"

ANSI = re.compile(r"\x1b\[[0-9;?]*[A-Za-z]")


# ---------------------------------------------------------------- CSV reading
def read_csv(path: Path) -> list[dict]:
    if not path.is_file():
        return []
    data = path.read_bytes()
    if data.startswith((b"\xff\xfe", b"\xfe\xff")):
        text = data.decode("utf-16", errors="replace")
    else:
        text = data.decode("utf-8-sig", errors="replace")
    rows = list(csv.DictReader(text.splitlines()))
    return [{k.strip(): (v or "").strip() for k, v in r.items() if k} for r in rows]


def is_filled(value: str) -> bool:
    return value.strip() not in PLACEHOLDER


def as_float(value: str) -> float | None:
    if not is_filled(value):
        return None
    text = value.strip().lower().replace("ms", "").replace("毫秒", "").strip()
    try:
        return float(text)
    except ValueError:
        return None


def as_int(value: str) -> int | None:
    number = as_float(value)
    if number is None or number != int(number):
        return None
    return int(number)


# ------------------------------------------------------------- band verdicts
def band_success(successes: int, attempts: int) -> str:
    if attempts <= 0:
        return UNKNOWN
    ratio = successes / attempts
    if ratio >= 0.9:
        return PASS
    if ratio >= 0.6:
        return PARTIAL
    return FAIL


def band_latency(ms: float | None) -> str:
    if ms is None:
        return UNKNOWN
    if ms <= 300:
        return PASS
    if ms <= 1000:
        return PARTIAL
    return FAIL


def combine(success_band: str, latency_band: str) -> str:
    if UNKNOWN in (success_band, latency_band):
        return UNKNOWN
    if FAIL in (success_band, latency_band):
        return FAIL
    if success_band == PASS and latency_band == PASS:
        return PASS
    return PARTIAL


# ------------------------------------------------------------- serial logs
def read_text_auto(path: Path) -> str:
    data = path.read_bytes()
    if data.startswith((b"\xff\xfe", b"\xfe\xff")):
        return data.decode("utf-16", errors="replace")
    return data.decode("utf-8-sig", errors="replace")


EVT_RE = re.compile(r"^PP_EVT,(.*)$")
STATE_RE = re.compile(r"^PP_STATE,(.*)$")


def parse_log(path: Path) -> dict:
    """Return per-log facts: failures (raw + after first connect), connect events."""
    events: list[dict] = []
    for raw in read_text_auto(path).splitlines():
        line = ANSI.sub("", raw).strip()
        m = EVT_RE.match(line)
        if m:
            kv = {}
            for part in m.group(1).split(","):
                if "=" in part:
                    k, v = part.split("=", 1)
                    kv[k.strip()] = v.strip()
            events.append({"kind": "EVT", **kv})
            continue
        m = STATE_RE.match(line)
        if m:
            kv = {}
            for part in m.group(1).split(","):
                if "=" in part:
                    k, v = part.split("=", 1)
                    kv[k.strip()] = v.strip()
            events.append({"kind": "STATE", **kv})

    first_connect = next(
        (i for i, e in enumerate(events)
         if e["kind"] == "STATE" and e.get("state") == "HID_CONNECTED"),
        None,
    )
    if first_connect is None:
        post_start = len(events)
    else:
        post_start = first_connect

    pre_failures, post_failures = [], []
    for i, e in enumerate(events):
        if e["kind"] != "EVT":
            continue
        code = as_int(e.get("code", ""))
        status = e.get("status", "")
        bad = (status and status.upper() != "OK") or (code not in (None, 0))
        if not bad:
            continue
        item = {
            "action": e.get("action", ""),
            "phase": e.get("phase", ""),
            "code": e.get("code", ""),
            "connected": e.get("connected", ""),
        }
        (post_failures if i >= post_start else pre_failures).append(item)

    reconnects: list[float] = []
    last_disconnect_ms: int | None = None
    for e in events:
        if e["kind"] != "STATE":
            continue
        ts = as_int(e.get("ts_ms", ""))
        state = e.get("state", "")
        if state == "HID_DISCONNECTED":
            last_disconnect_ms = ts
        elif state == "HID_CONNECTED" and last_disconnect_ms is not None and ts is not None:
            reconnects.append(float(ts - last_disconnect_ms))
            last_disconnect_ms = None

    key_events = sum(1 for e in events if e["kind"] == "EVT")
    return {
        "file": path.name,
        "key_events": key_events,
        "pre_failures": pre_failures,
        "post_failures": post_failures,
        "reconnects": reconnects,
        "has_connect": first_connect is not None,
    }


# --------------------------------------------------------------- evaluation
def evaluate(args) -> tuple[str, str]:
    key_csv, link_csv = Path(args.csv), Path(args.link)
    key_rows = read_csv(key_csv)
    link_rows = read_csv(link_csv)
    log_dir = Path(args.logs)
    log_paths = sorted(log_dir.glob("serial_*.log")) if log_dir.is_dir() else []
    logs = [parse_log(p) for p in log_paths]

    issues: list[str] = []          # blocks a verdict
    warnings: list[str] = []        # worth noting, does not block

    # --- completeness: 18 rows, expected matrix
    if not key_rows:
        issues.append(f"未找到或无法读取按键记录表：{key_csv}")
    if len(key_rows) != 18:
        issues.append(f"按键记录表应为 18 行，实际 {len(key_rows)} 行")
    matrix = {(r.get("phone_state", ""), r.get("action", "")) for r in key_rows}
    missing_cells = [
        f"{STATE_LABEL.get(s, s)}/{a}"
        for s in STATES for a in ACTIONS if (s, a) not in matrix
    ]
    if missing_cells:
        issues.append("缺少测试格：" + "、".join(missing_cells))

    verdicts: list[dict] = []
    field_missing: dict[str, list[str]] = {}
    no_log_links: list[str] = []
    for row in key_rows:
        tid = row.get("test_id", "")
        state = row.get("phone_state", "")
        action = row.get("action", "")
        for field in ("phone_model", "android_version", "rom_build", "date"):
            if not is_filled(row.get(field, "")):
                field_missing.setdefault(field, []).append(tid)
        attempts = as_int(row.get("attempts", "")) or 10
        successes = as_int(row.get("successes", ""))
        latency = as_float(row.get("latency_ms_median", ""))
        s_band = band_success(successes, attempts) if successes is not None else UNKNOWN
        l_band = band_latency(latency)
        verdict = combine(s_band, l_band)
        if successes is None:
            field_missing.setdefault("successes", []).append(tid)
        if latency is None:
            field_missing.setdefault("latency_ms_median", []).append(tid)
        if not is_filled(row.get("log_file", "")):
            no_log_links.append(tid)
        declared = row.get("result", "")
        if declared in (PASS, PARTIAL, FAIL) and verdict != UNKNOWN:
            rank = {FAIL: 0, PARTIAL: 1, PASS: 2}
            if rank[declared] > rank[verdict]:
                issues.append(f"{tid} 人工填写“{declared}”比脚本判定“{verdict}”更乐观，需复核原始记录")
            elif declared != verdict:
                warnings.append(f"{tid} 人工填写“{declared}”，脚本判定“{verdict}”（以脚本为准）")

        verdicts.append({
            "test_id": tid, "state": state, "action": action,
            "successes": successes, "attempts": attempts,
            "latency": latency, "s_band": s_band, "l_band": l_band,
            "verdict": verdict, "failure_reason": row.get("failure_reason", ""),
        })

    field_label = {
        "phone_model": "手机型号", "android_version": "安卓版本", "rom_build": "ROM 类型",
        "date": "测试日期", "successes": "成功次数 successes",
        "latency_ms_median": "中位延迟 latency_ms_median",
    }
    for field, ids in field_missing.items():
        scope = "全部 18 行" if len(ids) == 18 else f"{len(ids)} 行（{'、'.join(ids)}）"
        issues.append(f"{scope}未填 {field_label.get(field, field)}")

    if no_log_links:
        scope = "全部 18 行" if len(no_log_links) == 18 else f"{len(no_log_links)} 行"
        warnings.append(f"{scope}未填 log_file（不影响阈值判定，但结论无法追溯到原始日志）")

    pass_count = {s: sum(1 for v in verdicts if v["state"] == s and v["verdict"] == PASS)
                  for s in STATES}
    unknown_count = sum(1 for v in verdicts if v["verdict"] == UNKNOWN)

    # --- board-side send failures
    post_failures = sum(len(l["post_failures"]) for l in logs)
    pre_failures = sum(len(l["pre_failures"]) for l in logs)
    connected_logs = [l for l in logs if l["has_connect"]]
    if not logs:
        f_band = UNKNOWN
        issues.append("未找到串口日志（artifacts/logs/serial_*.log），无法核对板子侧发送失败")
    elif not connected_logs:
        f_band = UNKNOWN
        issues.append("串口日志中没有任何一次 HID_CONNECTED，无法评估“已连接后”的发送失败")
    elif post_failures == 0:
        f_band = PASS
    elif post_failures <= 2:
        f_band = PARTIAL
    else:
        f_band = FAIL

    # --- pairing / reconnect
    link_filled = [r for r in link_rows if r.get("result", "") not in ("", "未验证")]
    reconnected = [r for r in link_filled if r.get("result", "") == "通过"]
    bad_link = [r for r in link_filled if r.get("result", "") == "不通过"]
    if not link_rows:
        r_band = UNKNOWN
        issues.append(f"未找到配对/重连记录表：{link_csv}")
    elif len(link_filled) < 10:
        r_band = UNKNOWN
        issues.append(f"配对/重连记录只有 {len(link_filled)}/10 次已判定")
    elif len(reconnected) >= 8:
        r_band = PASS
    elif len(reconnected) >= 5:
        r_band = PARTIAL
    else:
        r_band = FAIL

    deadlocks = [r for r in link_rows if r.get("deadlock", "") == "是"]
    deadlock_unknown = bool(link_rows) and any(not is_filled(r.get("deadlock", "")) for r in link_rows)
    if deadlocks:
        d_state = "有死机"
    elif deadlock_unknown or not link_rows:
        d_state = UNKNOWN
        if deadlock_unknown:
            issues.append("配对/重连记录表的 deadlock 列未全部填写")
    else:
        d_state = "无死机"

    # log-side reconnect evidence (secondary evidence only)
    log_reconnects = [x for l in logs for x in l["reconnects"]]
    fast_log_reconnects = [x for x in log_reconnects if x <= 5000]

    # --- Go conditions
    cond1 = pass_count["unlocked"] >= 4
    cond2 = sum(1 for v in verdicts
                if v["state"] == "locked_background_music"
                and v["action"] in CORE_ACTIONS_LOCKED
                and v["verdict"] == PASS) >= 2
    cond3 = (post_failures == 0) and (d_state == "无死机")

    # Any dimension judged "不通过" blocks Go even if the three named
    # conditions happen to hold (e.g. reconnect 3/10).
    precondition = f_band in (PASS, PARTIAL) and r_band in (PASS, PARTIAL)

    risks: list[str] = []
    if r_band == PARTIAL:
        risks.append(f"配对/重连为“部分通过”（{len(reconnected)}/10 次在 5 s 内），Go 成立但必须记为已知风险")
    partial_cells = [v["test_id"] for v in verdicts if v["verdict"] == PARTIAL]
    if partial_cells:
        risks.append("部分通过格：" + "、".join(partial_cells))

    blocked = bool(issues)
    if blocked:
        conclusion = UNKNOWN
    elif cond1 and cond2 and cond3 and precondition:
        conclusion = "Go"
    else:
        conclusion = "No-Go"

    one_liner = {
        "Go": "T0 通过：板子能稳定控制主流安卓手机，可以进入 L1 离线语音。",
        "No-Go": "T0 未通过：按失败模式先做 T0.5 修复，不要进入 L1。",
        UNKNOWN: "数据不足：还有必填项未完成，现在不能宣称“能用”，也不能判定 Go/No-Go。",
    }[conclusion]

    report = render(
        args=args, key_csv=key_csv, link_csv=link_csv, log_paths=log_paths,
        verdicts=verdicts, pass_count=pass_count, unknown_count=unknown_count,
        post_failures=post_failures, pre_failures=pre_failures, f_band=f_band,
        logs=logs, r_band=r_band, link_filled=len(link_filled),
        reconnected=len(reconnected), bad_link=len(bad_link),
        d_state=d_state, log_reconnects=log_reconnects,
        fast_log_reconnects=fast_log_reconnects,
        cond1=cond1, cond2=cond2, cond3=cond3, precond=precondition,
        conclusion=conclusion, one_liner=one_liner,
        issues=issues, warnings=warnings, risks=risks,
    )
    return report, conclusion


def render(**kw) -> str:
    v: list[dict] = kw["verdicts"]
    out: list[str] = []
    add = out.append

    add("# T0 Go/No-Go 报告")
    add("")
    add(f"- 生成时间：{datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    add(f"- 按键记录：`{kw['key_csv']}`")
    add(f"- 配对/重连记录：`{kw['link_csv']}`")
    add(f"- 串口日志：{len(kw['log_paths'])} 份"
        + ("（" + "、".join(p.name for p in kw["log_paths"]) + "）" if kw["log_paths"] else ""))
    add("- 判定依据：`PocketPilot-最小原型验收标准与开发流程.md` T0 阈值表（本报告由脚本按固定规则生成，不含人工推测）")
    add("")

    add("## 结论")
    add("")
    add(f"**{kw['conclusion']}** — {kw['one_liner']}")
    add("")

    add("## 1. 数据完整性")
    add("")
    if kw["issues"]:
        add("以下问题必须先解决，否则判定只能是“数据不足”：")
        add("")
        for item in kw["issues"]:
            add(f"- {item}")
    else:
        add("- 18 行按键记录、设备信息、配对/重连 10 次记录均齐全。")
    if kw["warnings"]:
        add("")
        add("提示（不阻塞判定）：")
        add("")
        for item in kw["warnings"]:
            add(f"- {item}")
    add("")

    add("## 2. 按格判定（6 键 × 3 状态）")
    add("")
    add("规则：成功数与延迟都“通过”才算该格“通过”；任一指标“不通过”则该格“不通过”；任一项缺数据则为“数据不足”。")
    add("")
    add("| 测试 | 状态 | 按键 | 成功/次数 | 中位延迟 | 成功判定 | 延迟判定 | 该格判定 |")
    add("|---|---|---|---|---|---|---|---|")
    for item in v:
        succ = "—" if item["successes"] is None else f"{item['successes']}/{item['attempts']}"
        lat = "—" if item["latency"] is None else f"{item['latency']:.0f} ms"
        add(f"| {item['test_id']} | {STATE_LABEL.get(item['state'], item['state'])} | {item['action']} "
            f"| {succ} | {lat} | {item['s_band']} | {item['l_band']} | **{item['verdict']}** |")
    add("")
    add("每状态“通过”格数："
        + "；".join(f"{STATE_LABEL[s]} {kw['pass_count'][s]}/6" for s in STATES))
    if kw["unknown_count"]:
        add(f"仍为“数据不足”的格：{kw['unknown_count']} 个")
    add("")

    add("## 3. 板子侧发送失败")
    add("")
    if kw["f_band"] == UNKNOWN:
        add("- 已连接后 `status≠0` 的按键事件：**无法评估**（日志中没有连接成功记录）")
    else:
        add(f"- 已连接后 `status≠0` 的按键事件：**{kw['post_failures']} 次**（判定：{kw['f_band']}）")
    add(f"- 连接建立前的失败事件：{kw['pre_failures']} 次（未配对时的 `code=259` 属预期，不计入）")
    bad = [(l["file"], f) for l in kw["logs"] for f in l["post_failures"]]
    if bad:
        add("")
        add("已连接后仍失败的事件：")
        add("")
        add("| 日志 | 动作 | 阶段 | code | connected |")
        add("|---|---|---|---|---|")
        for name, f in bad:
            add(f"| {name} | {f['action']} | {f['phase']} | {f['code']} | {f['connected']} |")
    add("")

    add("## 4. 配对与重连")
    add("")
    add(f"- 已判定次数：{kw['link_filled']}/10；5 秒内重连成功：{kw['reconnected']} 次；失败：{kw['bad_link']} 次")
    add(f"- 判定：{kw['r_band']}")
    if kw["log_reconnects"]:
        add(f"- 串口日志佐证：断开→重连 {len(kw['log_reconnects'])} 次，其中 ≤5 s 的 {len(kw['fast_log_reconnects'])} 次"
            f"（中位 {statistics.median(kw['log_reconnects']):.0f} ms）")
    add("")

    add("## 5. 死机与恢复")
    add("")
    add(f"- 记录表 deadlock 列结论：{kw['d_state']}")
    add("- 判定口径：出现“必须拔电/复位才能恢复”的情况即 T0 不通过。")
    add("")

    add("## 6. T0 Go 条件核对")
    add("")
    def mark(ok: bool) -> str:
        return "✅" if ok else "❌"
    add("| # | 条件 | 实测 | 结果 |")
    add("|---|---|---|---|")
    add(f"| 前置 | 四个维度均不得判“不通过”（板子发送失败 ≥3 或重连 ≤4/10 即否决） | 发送失败：{kw['f_band']}；重连：{kw['r_band']} | {mark(kw['precond'])} |")
    add(f"| 1 | 解锁状态下 ≥4 条媒体键“通过” | {kw['pass_count']['unlocked']} 条 | {mark(kw['cond1'])} |")
    cond2_n = sum(1 for i in v if i["state"] == "locked_background_music"
                  and i["action"] in CORE_ACTIONS_LOCKED and i["verdict"] == PASS)
    add(f"| 2 | 锁屏+后台音乐下 播放/暂停、音量± 中 ≥2 条“通过” | {cond2_n} 条 | {mark(kw['cond2'])} |")
    fail_text = "无法评估" if kw["f_band"] == UNKNOWN else f"{kw['post_failures']} 次"
    add(f"| 3 | 已连接后发送失败 = 0，且无死机 | {fail_text}；{kw['d_state']} | {mark(kw['cond3'])} |")
    add("")

    if kw["risks"]:
        add("## 6.1 已知风险（不否决 Go，但必须写进结论）")
        add("")
        for item in kw["risks"]:
            add(f"- {item}")
        add("")

    add("## 7. 失败模式")
    add("")
    fails = [i for i in v if i["verdict"] in (FAIL, PARTIAL)]
    if fails:
        add("| 测试 | 状态 | 按键 | 判定 | 记录的原因 |")
        add("|---|---|---|---|---|")
        for i in fails:
            reason = i["failure_reason"] or "（未填）"
            add(f"| {i['test_id']} | {STATE_LABEL.get(i['state'], i['state'])} | {i['action']} | {i['verdict']} | {reason} |")
    else:
        add("- 没有“不通过/部分通过”的格子（或数据尚未填写）。")
    add("")

    add("## 8. 下一步")
    add("")
    if kw["conclusion"] == UNKNOWN:
        add("1. 按第 1 节补齐缺失项，再重新运行本脚本；")
        add("2. 补齐前不得对外宣称“已能用”；")
        add("3. 不要提前开始 L1 语音开发。")
    elif kw["conclusion"] == "Go":
        add("1. 归档本报告与原始日志，冻结 T0 结论；")
        add("2. 进入任务 2：L1 离线语音方案调研（ADR-002），只调研不写固件；")
        add("3. 保留当前 T0 固件作为对照基线。")
    else:
        add("1. 按第 7 节的失败模式进入任务 1（T0.5 修复）；")
        add("2. 一次只改一个变量，改完复测同一组用例；")
        add("3. 复测后重新生成本报告，对比 before/after。")
    add("")
    add("> 一票否决项（高风险动作未经确认、停止超 300 ms、无原始记录）属于 L2/P 阶段，本报告不代替其验收。")
    add("")
    return "\n".join(out)


def main() -> int:
    parser = argparse.ArgumentParser(description="PocketPilot T0 Go/No-Go evaluator")
    parser.add_argument("--csv", default=str(ROOT / "T0-平台通道穿刺记录表.csv"))
    parser.add_argument("--link", default=str(ROOT / "T0-配对与重连记录表.csv"))
    parser.add_argument("--logs", default=str(ROOT / "firmware" / "t0_hid_probe" / "artifacts" / "logs"))
    parser.add_argument("--out", default=str(ROOT / "docs" / "T0-GoNoGo-报告.md"))
    parser.add_argument("--stdout", action="store_true", help="only print, do not write the report file")
    args = parser.parse_args()

    report, conclusion = evaluate(args)
    if args.stdout:
        print(report)
    else:
        out = Path(args.out)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(report, encoding="utf-8")
        print(f"conclusion: {conclusion}")
        print(f"written: {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

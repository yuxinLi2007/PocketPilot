#!/usr/bin/env python3
"""Self-test for tools/t0_evaluate.py using synthetic fixtures.

Guarantees the evaluator answers Go / No-Go / 数据不足 correctly, so a real
verdict is never produced by an untested rule set.

    python tools/t0_selftest.py
"""
from __future__ import annotations

import csv
import sys
import tempfile
from argparse import Namespace
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import t0_evaluate as ev  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
KEY_TEMPLATE = ROOT / "T0-平台通道穿刺记录表.csv"
LINK_TEMPLATE = ROOT / "T0-配对与重连记录表.csv"


def write_csv(path: Path, fields: list[str], rows: list[dict]) -> None:
    with path.open("w", encoding="utf-8-sig", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def key_rows(successes=10, latency=200, device=True, fill=True, declared="") -> list[dict]:
    rows = []
    for r in csv.DictReader(KEY_TEMPLATE.read_text(encoding="utf-8-sig").splitlines()):
        if fill:
            r["successes"] = str(successes)
            r["latency_ms_median"] = str(latency)
            r["date"] = "2026-10-29"
            r["log_file"] = "serial_test.log"
            r["result"] = declared
            if device:
                r["phone_model"] = "测试机"
                r["android_version"] = "14"
                r["rom_build"] = "原生"
        rows.append(dict(r))
    return rows


def link_rows(passes=10, deadlock="否") -> list[dict]:
    """passes = how many of the 10 attempts reconnected within 5 s."""
    rows = []
    for index, r in enumerate(csv.DictReader(LINK_TEMPLATE.read_text(encoding="utf-8-sig").splitlines())):
        r["result"] = "通过" if index < passes else "不通过"
        r["reconnect_seconds"] = "2"
        r["deadlock"] = deadlock
        r["date"] = "2026-10-29"
        r["phone_model"] = "测试机"
        rows.append(dict(r))
    return rows


GOOD_LOG = (
    "PP_STATE,ts_ms=100,state=HID_START\n"
    "PP_STATE,ts_ms=900,state=HID_CONNECTED\n"
    + "".join(
        f"PP_EVT,ts_ms={1000 + i * 10},phase=press,action=VOLUME_UP,report_id=2,connected=1,status=OK,code=0\n"
        for i in range(10)
    )
)

BAD_LOG = GOOD_LOG + "".join(
    f"PP_EVT,ts_ms={2000 + i},phase=press,action=MUTE,report_id=2,connected=1,status=FAIL,code=258\n"
    for i in range(3)
)


def run_case(tmp: Path, name: str, keys: list[dict], links: list[dict], log_text: str):
    case = tmp / name
    (case / "logs").mkdir(parents=True, exist_ok=True)
    write_csv(case / "keys.csv", list(keys[0].keys()) if keys else ["test_id"], keys)
    write_csv(case / "links.csv", list(links[0].keys()) if links else ["attempt_id"], links)
    (case / "logs" / "serial_test.log").write_text(log_text, encoding="utf-8")
    args = Namespace(
        csv=str(case / "keys.csv"), link=str(case / "links.csv"),
        logs=str(case / "logs"), out=str(case / "report.md"),
    )
    _, conclusion = ev.evaluate(args)
    return conclusion


def main() -> int:
    failures = []
    with tempfile.TemporaryDirectory() as tmp_str:
        tmp = Path(tmp_str)

        # 1. empty template -> must not produce a verdict
        got = run_case(tmp, "empty", key_rows(fill=False), ev.read_csv(LINK_TEMPLATE), GOOD_LOG)
        if got != ev.UNKNOWN:
            failures.append(f"空模板应判为“数据不足”，实际 {got}")

        # 2. all pass, no failures -> Go
        got = run_case(tmp, "go", key_rows(), link_rows(), GOOD_LOG)
        if got != "Go":
            failures.append(f"全通过应判 Go，实际 {got}")

        # 3. board-side failures >= 3 -> No-Go
        got = run_case(tmp, "no_go", key_rows(), link_rows(), BAD_LOG)
        if got != "No-Go":
            failures.append(f"发送失败 3 次应判 No-Go，实际 {got}")

        # 4. slow phone response (>1000 ms) -> No-Go
        got = run_case(tmp, "slow", key_rows(latency=1500), link_rows(), GOOD_LOG)
        if got != "No-Go":
            failures.append(f"延迟 1500 ms 应判 No-Go，实际 {got}")

        # 5. dead phone -> No-Go regardless of successes
        got = run_case(tmp, "deadlock", key_rows(), link_rows(deadlock="是"), GOOD_LOG)
        if got != "No-Go":
            failures.append(f"出现死机应判 No-Go，实际 {got}")

        # 6b. declared 通过 but only 2/10 really worked -> conflict, must not Go
        got = run_case(tmp, "inflated", key_rows(successes=2, declared="通过"), link_rows(), GOOD_LOG)
        if got != ev.UNKNOWN:
            failures.append(f"人工填“通过”但实测 2/10 属冲突数据，应判“数据不足”，实际 {got}")

        # 6. reconnect 3/10 -> 不通过 -> No-Go
        got = run_case(tmp, "weak_link", key_rows(), link_rows(passes=3), GOOD_LOG)
        if got != "No-Go":
            failures.append(f"重连 3/10 应判 No-Go，实际 {got}")

    if failures:
        for item in failures:
            print(f"FAIL: {item}")
        return 1
    print("PASS: 7/7 判定规则符合预期（数据不足 / Go / No-Go 三类均覆盖）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

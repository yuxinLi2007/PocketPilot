#!/usr/bin/env python3
"""Convert PocketPilot T0 serial logs into an event CSV.

Usage:
    python pp_log_to_csv.py .\artifacts\logs\serial_20261028_200000.log

Reads lines starting with PP_EVT / PP_STATE, ignores everything else
(boot banner, ESP_LOG noise, ANSI escapes), and writes <input>.events.csv
next to the input file. Never modifies the original log.
"""
from __future__ import annotations

import csv
import re
import sys
from pathlib import Path

ANSI = re.compile(r"\x1b\[[0-9;?]*[A-Za-z]")
FIELDS = ["line_no", "kind", "ts_ms", "state", "phase", "action",
          "report_id", "connected", "status", "code", "reason", "value"]


def read_text_auto(path: Path) -> str:
    """Read UTF-8 logs and older UTF-16 logs written by Windows PowerShell."""
    data = path.read_bytes()
    if data.startswith((b"\xff\xfe", b"\xfe\xff")):
        return data.decode("utf-16", errors="replace")
    return data.decode("utf-8-sig", errors="replace")


def parse(path: Path) -> list[dict]:
    rows: list[dict] = []
    for line_no, raw in enumerate(read_text_auto(path).splitlines(), 1):
        line = ANSI.sub("", raw).strip()
        if line.startswith("PP_EVT,"):
            kind, body = "PP_EVT", line[len("PP_EVT,"):]
        elif line.startswith("PP_STATE,"):
            kind, body = "PP_STATE", line[len("PP_STATE,"):]
        else:
            continue

        kv: dict[str, str] = {}
        for part in body.split(","):
            if "=" in part:
                k, v = part.split("=", 1)
                kv[k.strip()] = v.strip()

        rows.append({
            "line_no": line_no,
            "kind": kind,
            "ts_ms": kv.get("ts_ms", ""),
            "state": kv.get("state", ""),
            "phase": kv.get("phase", ""),
            "action": kv.get("action", ""),
            "report_id": kv.get("report_id", ""),
            "connected": kv.get("connected", ""),
            "status": kv.get("status", ""),
            "code": kv.get("code", ""),
            "reason": kv.get("reason", ""),
            "value": kv.get("value", ""),
        })
    return rows


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(__doc__)
        return 2
    src = Path(argv[1])
    if not src.is_file():
        print(f"file not found: {src}", file=sys.stderr)
        return 1

    rows = parse(src)
    out = src.with_suffix(src.suffix + ".events.csv")
    with out.open("w", encoding="utf-8-sig", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=FIELDS)
        writer.writeheader()
        writer.writerows(rows)

    evt = sum(1 for r in rows if r["kind"] == "PP_EVT")
    bad = sum(1 for r in rows
                     if r["kind"] == "PP_EVT"
                     and (r["status"] not in ("OK", "") or r["code"] not in ("0", "")))
    print(f"{src.name}: {evt} key events ({bad} reported non-zero status), {len(rows) - evt} state events")
    print(f"written: {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))

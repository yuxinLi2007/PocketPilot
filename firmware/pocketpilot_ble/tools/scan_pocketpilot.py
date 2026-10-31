"""Scan for the PocketPilot peripheral and save the raw result as a log.

This is the evidence-producing version of a plain bleak scan: it filters on
the PocketPilot service UUID (and falls back to the advertised name), prints
address / RSSI / advertised service UUIDs, and writes everything to
artifacts/logs/scan_<timestamp>.log so the claim "the device really
advertises" is backed by a file instead of a console session.

Usage:
    cd firmware/pocketpilot_ble
    python tools/scan_pocketpilot.py
    python tools/scan_pocketpilot.py --timeout 20 --expect-name PocketPilot

Exit codes: 0 = device found, 2 = not found, 1 = unexpected error.
"""
import argparse
import asyncio
import datetime as dt
import pathlib
import sys

from bleak import BleakScanner

SERVICE_UUID = "6f706f63-6b65-742d-7069-6c6f74000001"
LOG_DIR = pathlib.Path(__file__).resolve().parents[1] / "artifacts" / "logs"


class Logger:
    def __init__(self):
        LOG_DIR.mkdir(parents=True, exist_ok=True)
        stamp = dt.datetime.now().strftime("%Y%m%d_%H%M%S")
        self.path = LOG_DIR / f"scan_{stamp}.log"
        self.fh = self.path.open("w", encoding="utf-8")

    def __call__(self, msg):
        line = f"[{dt.datetime.now().strftime('%H:%M:%S.%f')[:-3]}] {msg}"
        print(line)
        self.fh.write(line + "\n")
        self.fh.flush()

    def close(self):
        self.fh.close()


async def run(args, log):
    log(f"scan start timeout={args.timeout}s filter=service:{SERVICE_UUID}")
    devices = await BleakScanner.discover(timeout=args.timeout, return_adv=True)
    log(f"scan done, {len(devices)} device(s) with advertisement data")

    hits = []
    for addr, (dev, adv) in sorted(devices.items(), key=lambda kv: -kv[1][1].rssi):
        uuids = [str(u).lower() for u in (adv.service_uuids or [])]
        name = dev.name or adv.local_name or ""
        tag = ""
        if SERVICE_UUID in uuids:
            tag = " <-- SERVICE_UUID match"
        elif args.expect_name and name == args.expect_name:
            tag = " <-- name match"
        log(f"  {addr} rssi={adv.rssi:>4} dBm name={name!r} uuids={uuids}{tag}")
        if tag:
            hits.append((addr, adv.rssi, name, uuids))

    if not hits:
        log("RESULT FAIL: no advertisement matched the PocketPilot service UUID or name")
        return 2

    addr, rssi, name, uuids = hits[0]
    log("")
    log(f"RESULT PASS: found {name!r} at {addr}, rssi={rssi} dBm")
    log(f"  advertised service uuids: {uuids}")
    log("  note: advertising proves the radio + GATT advertisement only; "
        "it does not prove a phone connected.")
    return 0


def main():
    ap = argparse.ArgumentParser(description="Scan for PocketPilot (evidence log)")
    ap.add_argument("--timeout", type=float, default=12.0)
    ap.add_argument("--expect-name", default="PocketPilot")
    args = ap.parse_args()

    log = Logger()
    try:
        code = asyncio.run(run(args, log))
    except Exception as exc:  # noqa: BLE001 - report, do not hide
        log(f"ERROR: {exc!r}")
        code = 1
    finally:
        log(f"log file: {log.path}")
        log.close()
    return code


if __name__ == "__main__":
    sys.exit(main())

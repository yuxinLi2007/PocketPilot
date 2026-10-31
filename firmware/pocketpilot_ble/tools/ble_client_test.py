"""PocketPilot BLE peripheral test client (PC-side, uses bleak).

This tool is NOT the Android app. It verifies the GATT contract:
  - service 6f706f63-6b65-742d-7069-6c6f74000001 is advertised
  - RX characteristic 6f706f63-6b65-742d-7069-6c6f74000002 notifies command frames
  - TX characteristic 6f706f63-6b65-742d-7069-6c6f74000003 accepts result frames
  - malformed frames are rejected by the peripheral (see its serial log)

It deliberately acknowledges commands with EXECUTED_UNCONFIRMED, because no
phone action happened here. Never report these acknowledgements as phone tests.

With --serial-port it also drives the firmware's serial bring-up keys (1/2/3)
while connected, so notifications, result write-back and firmware-side logging
can all be checked in one run. The serial port is opened before the BLE scan and
kept open to avoid an accidental reset of the board.

Usage:
    python tools/ble_client_test.py --serial-port COM5
    python tools/ble_client_test.py --address AA:BB:CC:DD:EE:FF
    python tools/ble_client_test.py --no-invalid-frame-test

Logs are written to artifacts/logs/ble_test_<timestamp>.log
"""

from __future__ import annotations

import argparse
import asyncio
import datetime as dt
import pathlib
import sys
import threading
import time

try:
    from bleak import BleakClient, BleakScanner
except ImportError:
    print("bleak is required: python -m pip install bleak", file=sys.stderr)
    raise SystemExit(2)

SERVICE_UUID = "6f706f63-6b65-742d-7069-6c6f74000001"
RX_UUID = "6f706f63-6b65-742d-7069-6c6f74000002"
TX_UUID = "6f706f63-6b65-742d-7069-6c6f74000003"

LOG_DIR = pathlib.Path(__file__).resolve().parents[1] / "artifacts" / "logs"


class Logger:
    def __init__(self) -> None:
        LOG_DIR.mkdir(parents=True, exist_ok=True)
        stamp = dt.datetime.now().strftime("%Y%m%d_%H%M%S")
        self.path = LOG_DIR / f"ble_test_{stamp}.log"
        self._fh = self.path.open("w", encoding="utf-8")
        self._lock = threading.Lock()

    def __call__(self, message: str) -> None:
        line = f"[{dt.datetime.now().strftime('%H:%M:%S.%f')[:-3]}] {message}"
        with self._lock:
            print(line)
            self._fh.write(line + "\n")
            self._fh.flush()

    def close(self) -> None:
        self._fh.close()


log = Logger()
received = {}
received_event = asyncio.Event()


class SerialTap:
    """Reads the ESP32 console in the background and mirrors it into the log."""

    def __init__(self, port: str, baud: int = 115200) -> None:
        import serial  # local import so a missing pyserial only affects this option

        self._serial = serial.Serial()
        self._serial.port = port
        self._serial.baudrate = baud
        self._serial.timeout = 0.1
        self._serial.dtr = False
        self._serial.rts = False
        self._serial.open()
        time.sleep(0.3)
        self._serial.reset_input_buffer()
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._pump, daemon=True)

    def start(self) -> None:
        self._thread.start()

    def _pump(self) -> None:
        buf = b""
        while not self._stop.is_set():
            data = self._serial.read(4096)
            if not data:
                continue
            buf += data
            while b"\n" in buf:
                line, buf = buf.split(b"\n", 1)
                text = line.decode("utf-8", "replace").strip()
                if text:
                    log(f"SERIAL: {text}")

    def send(self, keys: str) -> None:
        self._serial.write(keys.encode("ascii"))
        log(f"SERIAL-TX: {keys!r}")

    def close(self) -> None:
        self._stop.set()
        self._thread.join(timeout=2.0)
        try:
            self._serial.close()
        except Exception:  # noqa: BLE001 - best effort
            pass


async def run(args: argparse.Namespace) -> int:
    tap = None
    if args.serial_port:
        tap = SerialTap(args.serial_port)
        tap.start()
        log(f"serial tap attached to {args.serial_port}")

    log(f"scanning for service {SERVICE_UUID} ...")
    device = await BleakScanner.find_device_by_filter(
        lambda d, ad: SERVICE_UUID in {str(u).lower() for u in (ad.service_uuids or [])},
        timeout=args.timeout,
    )
    if device is None:
        log("RESULT FAIL: PocketPilot service not found in advertisement")
        if tap:
            tap.close()
        return 1
    if args.address and device.address.lower() != args.address.lower():
        log(f"WARN: found {device.address}, expected {args.address}")

    log(f"found {device.address} name={device.name!r}; connecting")

    async def send_ack(client: BleakClient, frame: str) -> None:
        try:
            await client.write_gatt_char(TX_UUID, frame.encode("utf-8"), response=True)
            log(f"WROTE RESULT: {frame.strip()}")
        except Exception as exc:  # noqa: BLE001 - surfaced in the log
            log(f"WRITE FAILED: {exc!r}")

    def on_notify(_sender, data: bytearray) -> None:
        text = bytes(data).decode("utf-8", errors="replace")
        log(f"NOTIFY ({len(data)} bytes): {text!r}")
        if text.startswith("PP1|COMMAND_"):
            parts = text.strip().split("|", 2)
            command = parts[1]
            received[command] = text
            received_event.set()
            ack = (f"PP1|RESULT|{command}|{args.ack_status}|"
                   "computer-side protocol test; no phone action\n")
            asyncio.create_task(send_ack(client_ref[0], ack))

    client_ref: list[BleakClient] = []

    async with BleakClient(device, timeout=20.0) as client:
        client_ref.append(client)
        log(f"connected={client.is_connected}")
        services = client.services
        service = services.get_service(SERVICE_UUID)
        if service is None:
            log("RESULT FAIL: service missing after discovery")
            if tap:
                tap.close()
            return 1
        rx = service.get_characteristic(RX_UUID)
        tx = service.get_characteristic(TX_UUID)
        if rx is None or tx is None:
            log(f"RESULT FAIL: characteristic missing rx={rx is not None} tx={tx is not None}")
            if tap:
                tap.close()
            return 1
        log(f"RX properties={sorted(rx.properties)}")
        log(f"TX properties={sorted(tx.properties)}")
        if "notify" not in rx.properties:
            log("RESULT FAIL: RX does not support notify")
            if tap:
                tap.close()
            return 1

        await client.start_notify(RX_UUID, on_notify)
        log("subscribed to RX notifications")

        if tap:
            for key in ("1", "2", "3"):
                await asyncio.sleep(args.key_gap)
                tap.send(key)
            log(f"waiting {args.wait:.0f}s for notifications")
        else:
            log("now press 1/2/3 in the ESP32 serial console (or send from the app)")

        deadline = time.monotonic() + args.wait
        while time.monotonic() < deadline:
            if len(received) >= 3:
                break
            await asyncio.sleep(0.2)

        missing = [c for c in ("COMMAND_OPEN_WECHAT", "COMMAND_OPEN_MAP", "COMMAND_BACK")
                   if c not in received]
        if missing:
            log(f"NOTE: no notification for {missing} during the wait window")
        else:
            log("all three command notifications received")

        if args.invalid_frame_test:
            for frame in ("garbage\n", "PP1|NOPE|x\n", "PP1|RESULT|COMMAND_BACK|NOT_A_STATUS|\n"):
                await client.write_gatt_char(TX_UUID, frame.encode("utf-8"), response=True)
                log(f"WROTE INVALID (expect peripheral rejection): {frame.strip()}")
                await asyncio.sleep(0.4)
            await asyncio.sleep(0.6)

        await client.stop_notify(RX_UUID)

    if tap:
        await asyncio.sleep(1.0)
        tap.send("s")
        await asyncio.sleep(1.0)
        tap.close()

    ok = not missing
    log("RESULT PASS: GATT contract verified at protocol level (not a phone test)"
        if ok else "RESULT FAIL: command notifications missing")
    return 0 if ok else 1


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--address", help="expected device address (informational)")
    parser.add_argument("--timeout", type=float, default=15.0)
    parser.add_argument("--wait", type=float, default=20.0,
                        help="seconds to wait for command notifications")
    parser.add_argument("--key-gap", type=float, default=2.0,
                        help="seconds between serial 1/2/3 key presses")
    parser.add_argument("--ack-status", default="EXECUTED_UNCONFIRMED")
    parser.add_argument("--serial-port", help="serial port of the board, e.g. COM5")
    parser.add_argument("--no-invalid-frame-test", action="store_true")
    args = parser.parse_args()
    args.invalid_frame_test = not args.no_invalid_frame_test
    try:
        return asyncio.run(run(args))
    finally:
        log(f"log saved to {log.path}")
        log.close()


if __name__ == "__main__":
    raise SystemExit(main())


"""实体按键 → BLE 通知帧 的端到端验证（PC 端 BLE 客户端 + 串口旁路）。

它回答一个问题：
  "我按下板子上的键，蓝牙上到底有没有真的发出命令帧？"

做法：
  1) 后台线程读 COM5，记录固件自己的按键/发送日志（板子视角）
  2) 主线程用 bleak 连接 PocketPilot，订阅 RX 通知（客户端视角）
  3) 你在中间按键，脚本把两边的时间戳对齐
  4) 结束时给出结论：每个 PP_SEND_DONE 是否都能在 BLE 侧找到对应帧

这不是安卓 App，也不是手机测试。它只证明"按键 → BLE 帧"这一段。

用法:
  python tools\e2e_button_ble_test.py --port COM5 --seconds 180
"""
from __future__ import annotations

import argparse
import asyncio
import datetime as dt
import pathlib
import re
import sys
import threading
import time

import serial
from bleak import BleakClient, BleakScanner

HERE = pathlib.Path(__file__).resolve().parent
LOG_DIR = HERE.parent / "artifacts" / "logs"

RX_UUID = "6f706f63-6b65-742d-7069-6c6f74000002"   # device -> phone (notify)
TX_UUID = "6f706f63-6b65-742d-7069-6c6f74000003"   # phone -> device (write)

SERIAL_PATTERNS = [
    (re.compile(r"PP_BTN_RAW level=(\d)"), "BOOT电平"),
    (re.compile(r"PP_BTN event=(\w+)(?: clicks=(\d+))?"), "按键事件"),
    (re.compile(r"PP_ADC_RAW mv=(-?\d+) button=(\w+)"), "ADC"),
    (re.compile(r"PP_SEND name=(\S+) bytes=(\d+)"), "发送开始"),
    (re.compile(r"PP_SEND_DONE name=(\S+)"), "发送完成"),
    (re.compile(r"PP_SEND_BLOCKED name=(\S+) ready=(\d) connected=(\d) subscribed=(\d)"), "发送被拦"),
    (re.compile(r"PP_RX_RESULT command=(\S+) status=(\S+)"), "手机回传"),
]


class SerialTap(threading.Thread):
    def __init__(self, port: str, baud: int, stop: threading.Event, log: list[str]):
        super().__init__(daemon=True)
        self.port, self.baud = port, baud
        self.stop = stop
        self.log = log
        self.events: list[tuple[float, str, str]] = []
        self.port_obj: serial.Serial | None = None

    def run(self) -> None:
        try:
            p = serial.Serial(self.port, self.baud, timeout=0.2)
            self.port_obj = p
        except Exception as exc:                      # noqa: BLE001
            self.log.append(f"# serial open failed: {exc}")
            print(f"[serial] 打不开 {self.port}: {exc}")
            return
        line_buf = ""
        try:
            while not self.stop.is_set():
                data = p.read(4096)
                if not data:
                    continue
                text = line_buf + data.decode("utf-8", "replace")
                lines = text.replace("\r", "\n").split("\n")
                line_buf = lines.pop()
                for line in lines:
                    line = line.strip()
                    if not line:
                        continue
                    self.log.append(line)
                    for rx, label in SERIAL_PATTERNS:
                        if rx.search(line):
                            self.events.append((time.time(), label, line))
                            print(f"  [串口] {dt.datetime.now():%H:%M:%S.%f}"[:-3]
                                  + f" {label}: {line.split(') ', 1)[-1]}")
                            sys.stdout.flush()
                            break
        finally:
            self.port_obj = None
            p.close()

    def send(self, data: bytes) -> None:
        if self.port_obj is not None:
            self.port_obj.write(data)
            self.port_obj.flush()


async def find_device(timeout: float):
    print(f"[ble] 扫描 PocketPilot（最多 {timeout:.0f} 秒）...")
    dev = await BleakScanner.find_device_by_filter(
        lambda d, ad: (d.name or "") == "PocketPilot"
        or "6f706f63" in [str(u).lower() for u in (ad.service_uuids or [])],
        timeout=timeout,
    )
    return dev


async def connect_and_subscribe(dev, on_notify, on_disconnect):
    """连接并订阅；Windows BLE 偶发 "操作已被用户取消"，这里做有限重试。"""
    last_err: Exception | None = None
    for attempt in range(1, 5):
        client = BleakClient(dev, disconnected_callback=on_disconnect)
        try:
            await client.connect()
            await asyncio.sleep(1.0)          # 等对端 ATT 就绪再写 CCCD
            await client.start_notify(RX_UUID, on_notify)
            print("[ble] 已连接，订阅 RX 通知 ...")
            return client
        except Exception as exc:              # noqa: BLE001
            last_err = exc
            print(f"[ble] 第 {attempt}/4 次连接失败：{type(exc).__name__}: {exc}")
            try:
                await client.disconnect()
            except Exception:                 # noqa: BLE001
                pass
            await asyncio.sleep(3.0)          # 让固件侧监督超时先释放旧连接
    print(f"[ble] 放弃：{last_err}")
    return None


async def main_async(args, serial_events_ref, serial_tap) -> int:
    frames: list[tuple[float, str]] = []

    dev = await find_device(args.scan_timeout)
    if dev is None:
        print("[ble] 没有扫到 PocketPilot。请确认板子已上电、LED 在慢闪。")
        return 3

    print(f"[ble] 找到 {dev.name}  {dev.address}")
    disconnected = asyncio.Event()

    def on_notify(_char, data: bytearray) -> None:
        now = time.time()
        text = data.decode("utf-8", "replace").strip()
        frames.append((now, text))
        print(f"  [蓝牙] {dt.datetime.now():%H:%M:%S.%f}"[:-3] + f" 收到的帧: {text}")
        sys.stdout.flush()

    def on_disconnect(_client) -> None:
        disconnected.set()
        print("[ble] 连接断开")

    client = await connect_and_subscribe(dev, on_notify, on_disconnect)
    if client is None:
        return 4
    try:
        print("")
        print("=" * 68)
        print("  >>> 现在请在板子上按键（每个动作之间停 2 秒）<<<")
        print("      BOOT 单击 → 双击 → 长按")
        print("      正面 MENU → PLAY → UP+ → DN-")
        print(f"      监听 {int(args.seconds)} 秒，按完可以按 Ctrl+C 提前结束")
        print("=" * 68)
        print("")

        if args.auto_probe:
            print("[e2e] --auto-probe：用串口键 1/2/3 代替按键，先验证蓝牙链路本身")
            await asyncio.sleep(0.5)
            serial_tap.send(b"1")
            await asyncio.sleep(0.6)
            serial_tap.send(b"2")
            await asyncio.sleep(0.6)
            serial_tap.send(b"3")

        t0 = time.time()
        while time.time() - t0 < args.seconds:
            if disconnected.is_set():
                break
            await asyncio.sleep(0.2)
    finally:
        try:
            await client.stop_notify(RX_UUID)
        except Exception:                                  # noqa: BLE001
            pass
        try:
            if client.is_connected:
                await client.disconnect()
        except Exception:                                  # noqa: BLE001
            pass

    # ---- 汇总 -------------------------------------------------------------
    se = serial_events_ref
    print("")
    print("=" * 68)
    print("  汇总")
    print("=" * 68)
    sends = sum(1 for _, l, _ in se if l == "发送完成")
    print(f"  板子侧：按键事件 {sum(1 for _, l, _ in se if l in ('按键事件', 'ADC'))} 条，"
          f"PP_SEND_DONE {sends} 条")
    print(f"  蓝牙侧：收到 {len(frames)} 个通知帧")
    for t, text in frames:
        print(f"    {dt.datetime.fromtimestamp(t):%H:%M:%S}  {text}")
    print("-" * 68)
    if sends and len(frames) >= sends:
        print(f"  PASS：{sends} 次按键发送，蓝牙侧收到 {len(frames)} 帧，链路是通的。")
        rc = 0
    elif sends and frames:
        print(f"  部分通过：发送 {sends} 次，收到 {len(frames)} 帧。")
        rc = 1
    elif sends and not frames:
        print("  FAIL：板子说发送完成，但蓝牙侧没收到 —— 协议层需要排查。")
        rc = 1
    else:
        print("  INCOMPLETE：没有触发任何发送。")
        rc = 2
    print("  注意：这是 PC 端蓝牙客户端的结果，不等于安卓 App / 手机动作已验证。")
    return rc


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", default="COM5")
    ap.add_argument("--baud", type=int, default=115200)
    ap.add_argument("--seconds", type=float, default=180)
    ap.add_argument("--scan-timeout", type=float, default=15)
    ap.add_argument("--auto-probe", action="store_true",
                    help="用串口键 1/2/3 代替实体按键，验证蓝牙链路")
    args = ap.parse_args()

    LOG_DIR.mkdir(parents=True, exist_ok=True)
    stamp = dt.datetime.now().strftime("%Y%m%d_%H%M%S")
    log_path = LOG_DIR / f"e2e_button_ble_{stamp}.log"

    print(f"[e2e] 实体按键 -> BLE 通知帧 验证   串口={args.port}")
    stop = threading.Event()
    log: list[str] = []
    tap = SerialTap(args.port, args.baud, stop, log)
    tap.start()
    time.sleep(0.8)

    rc = 2
    try:
        rc = asyncio.run(main_async(args, tap.events, tap))
    except KeyboardInterrupt:
        print("\n[e2e] 手动中断")
        rc = 130
    finally:
        stop.set()
        tap.join(timeout=2)
        log_path.write_text(
            f"# e2e button->ble port={args.port} seconds={int(args.seconds)} started={stamp}\n"
            + "\n".join(log) + "\n", encoding="utf-8")
        print(f"\n  LOG: {log_path}")
    return rc


if __name__ == "__main__":
    raise SystemExit(main())

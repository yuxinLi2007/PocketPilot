"""PocketPilot 长时间监听：等用户直接按板上的键。

不需要用户跑测试脚本，只要按一下键。本程序：
  - 以 115200 打开 COM5，持续读串口
  - 只在出现真实按键事件时打印一行（其余日志静音）
  - 每 10 秒打一个心跳，证明还在监听
  - 全文写进 artifacts/logs/live_*.log，供事后核对

用法:
  python tools\live-watch.py --port COM5 --seconds 600
"""
import argparse
import datetime
import pathlib
import re
import sys
import time

import serial

HERE = pathlib.Path(__file__).resolve().parent
LOG_DIR = HERE.parent / "artifacts" / "logs"

KEY_PATTERNS = [
    (re.compile(r"PP_BTN_RAW level=(\d)"), "BOOT 电平"),
    (re.compile(r"PP_BTN event=(\w+)"), "BOOT 手势"),
    (re.compile(r"PP_ADC_RAW mv=(-?\d+) button=(\w+)"), "ADC 变化"),
    (re.compile(r"PP_TX_SENT|PP_NOTIFY|PP_CMD"), "BLE 发送"),
    (re.compile(r"PP_RX_RESULT"), "手机回传"),
]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", default="COM5")
    ap.add_argument("--baud", type=int, default=115200)
    ap.add_argument("--seconds", type=float, default=600)
    args = ap.parse_args()

    LOG_DIR.mkdir(parents=True, exist_ok=True)
    stamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    log_path = LOG_DIR / f"live_{stamp}.log"

    print(f"[watch] 正在监听 {args.port} ，持续 {int(args.seconds)} 秒")
    print("[watch] 屏幕上平时是安静的，你按键出现事件时才会打印")
    print("[watch] 现在请直接按板子上的键，不需要运行别的程序")

    p = serial.Serial(args.port, args.baud, timeout=0.2)
    t0 = time.time()
    last_beat = t0
    events = 0
    raw_lines = []
    try:
        while time.time() - t0 < args.seconds:
            data = p.read(4096)
            if data:
                chunk = data.decode("utf-8", "replace")
                for line in chunk.replace("\r", "\n").split("\n"):
                    line = line.strip()
                    if not line:
                        continue
                    raw_lines.append(line)
                    for rx, label in KEY_PATTERNS:
                        if rx.search(line):
                            events += 1
                            print(f"  [{time.strftime('%H:%M:%S')}] {label}: {line}")
                            sys.stdout.flush()
                            break
            now = time.time()
            if now - last_beat >= 10:
                last_beat = now
                left = int(args.seconds - (now - t0))
                sys.stdout.write(f"\r[watch] 仍在监听… 已捕获 {events} 个事件，剩余 {left}s   ")
                sys.stdout.flush()
    finally:
        p.close()

    print("")
    print(f"[watch] 结束，共捕获 {events} 个事件")
    log_path.write_text(
        f"# live watch port={args.port} seconds={int(args.seconds)} started={stamp}\n"
        + "\n".join(raw_lines) + "\n", encoding="utf-8")
    print(f"  LOG: {log_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

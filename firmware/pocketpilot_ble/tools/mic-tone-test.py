"""用电脑扬声器播放 1 kHz 测试音，验证板载麦克风是否随声音变化。

这不是语音识别测试；它只验证“24 bit I2S -> 16 bit PCM”的采集链路。
运行前请确认电脑扬声器未静音、音量可听。板子放在电脑附近即可。

用法:
  python tools\\mic-tone-test.py --port COM5
"""
from __future__ import annotations

import argparse
import datetime as dt
import math
import pathlib
import re
import struct
import sys
import time
import wave

import serial

try:
    import winsound
except ImportError:
    winsound = None

HERE = pathlib.Path(__file__).resolve().parent
ART = HERE.parent / "artifacts"
LOG_DIR = ART / "logs"
TONE = ART / "tone_1khz.wav"

BEGIN = re.compile(r"PP_MIC_PHASE_BEGIN name=(\w+) ms=(\d+)")
PHASE = re.compile(
    r"PP_MIC_PHASE name=(\w+) n=(\d+) ac_rms=(-?\d+) peak=(-?\d+) p2p=(-?\d+) dc=(-?\d+) clipped=(\d+)"
)
VERDICT = re.compile(
    r"PP_MIC_VERDICT result=(\w+) reason=(\w+) quiet_ac_rms=(-?\d+) loud_ac_rms=(-?\d+) "
    r"ratio_pct=(\d+) loud_clip_pm=(\d+) quiet_dc=(-?\d+)"
)


def make_tone() -> None:
    ART.mkdir(parents=True, exist_ok=True)
    rate = 48000
    seconds = 3.0
    freq = 1000.0
    amp = 0.82 * 32767.0
    with wave.open(str(TONE), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        frames = bytearray()
        for i in range(int(rate * seconds)):
            v = int(amp * math.sin(2.0 * math.pi * freq * i / rate))
            frames += struct.pack("<h", v)
        w.writeframes(frames)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", default="COM5")
    ap.add_argument("--baud", type=int, default=115200)
    ap.add_argument("--timeout", type=float, default=30.0)
    args = ap.parse_args()

    if winsound is None:
        print("[FAIL] 当前 Python 不是 Windows 环境，无法自动播放测试音。", flush=True)
        return 2

    make_tone()
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    log_path = LOG_DIR / f"mic_tone_{dt.datetime.now():%Y%m%d_%H%M%S}.log"
    log = open(log_path, "w", encoding="utf-8")

    print("=" * 72, flush=True)
    print("  即将测试：3 秒安静 -> 3 秒 1 kHz 测试音（电脑扬声器播放）", flush=True)
    print("  请确认扬声器未静音，板子放在电脑附近。5 秒后自动开始。", flush=True)
    print("=" * 72, flush=True)
    for n in range(5, 0, -1):
        print(f"  {n}...", flush=True)
        time.sleep(1)

    phases: dict[str, tuple] = {}
    verdict = None
    tone_started = False

    with serial.Serial(args.port, args.baud, timeout=0.2) as ser:
        ser.reset_input_buffer()
        time.sleep(0.3)
        ser.write(b"t")
        ser.flush()
        deadline = time.time() + args.timeout
        while time.time() < deadline:
            raw = ser.readline()
            if not raw:
                if verdict is not None:
                    break
                continue
            line = raw.decode("utf-8", "replace").rstrip()
            log.write(line + "\n")
            log.flush()

            m = BEGIN.search(line)
            if m:
                name = m.group(1)
                print(f"[阶段] {name}", flush=True)
                if name == "loud" and not tone_started:
                    winsound.PlaySound(
                        str(TONE),
                        winsound.SND_FILENAME | winsound.SND_ASYNC | winsound.SND_NODEFAULT,
                    )
                    tone_started = True
                    print("[扬声器] 正在播放 1 kHz 测试音", flush=True)
                continue

            m = PHASE.search(line)
            if m:
                name = m.group(1)
                phases[name] = tuple(int(g) for g in m.groups()[1:])
                n, ac, pk, p2p, dc, clip = phases[name]
                print(
                    f"[{name:5}] n={n:<6} ac_rms={ac:<6} peak={pk:<6} "
                    f"p2p={p2p:<6} dc={dc:<6} clipped={clip}",
                    flush=True,
                )
                continue

            m = VERDICT.search(line)
            if m:
                verdict = m.groups()
                break

    print("", flush=True)
    if verdict is None:
        print("[结论] FAIL：没有收到 PP_MIC_VERDICT。", flush=True)
        print(f"LOG: {log_path}", flush=True)
        log.close()
        return 3

    result, reason, q_rms, l_rms, ratio, clip_pm, dc = verdict
    print(
        f"[统计] 安静 ac_rms={q_rms}  测试音 ac_rms={l_rms}  "
        f"倍数={int(ratio) / 100:.1f}x  削顶={int(clip_pm) / 10:.1f}%  安静 dc={dc}",
        flush=True,
    )
    if result == "PASS":
        print("[结论] PASS：板载麦克风对真实声音有明显响应，24 bit PCM 采集链路通过。", flush=True)
        print("       边界：尚未验证中文语音识别。", flush=True)
        rc = 0
    elif result == "UNDECIDED":
        print(f"[结论] 待定：{reason}。可提高系统音量后重试。", flush=True)
        rc = 5
    else:
        print(f"[结论] FAIL：{reason}。检查扬声器音量、板子距离或麦克风硬件。", flush=True)
        rc = 4
    print(f"TONE: {TONE}", flush=True)
    print(f"LOG: {log_path}", flush=True)
    log.close()
    return rc


if __name__ == "__main__":
    sys.exit(main())

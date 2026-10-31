"""把板载麦克风采到的 16 kHz PCM 存成 WAV，用耳朵做最终确认。

固件先打印 PP_WAV_BEGIN，再按二进制流输出 16 kHz / 16 bit / 单声道 PCM，
最后打印 PP_WAV_END。本脚本负责拼 WAV 头并算电平统计。

用法:
  python tools\\mic-record.py --port COM5
  python tools\\mic-record.py --port COM5 --seconds 5
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

HERE = pathlib.Path(__file__).resolve().parent
OUT_DIR = HERE.parent / "artifacts" / "recordings"
LOG_DIR = HERE.parent / "artifacts" / "logs"

BEGIN = re.compile(rb"PP_WAV_BEGIN rate=(\d+) channels=(\d+) bits=(\d+) samples=(\d+)")
END = re.compile(rb"PP_WAV_END samples=(\d+)")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", default="COM5")
    ap.add_argument("--baud", type=int, default=115200)
    ap.add_argument("--seconds", type=int, default=5)
    args = ap.parse_args()

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    stamp = f"{dt.datetime.now():%Y%m%d_%H%M%S}"
    wav_path = OUT_DIR / f"mic_{stamp}.wav"
    log_path = LOG_DIR / f"mic_record_{stamp}.log"

    print("=" * 70, flush=True)
    print(f"  {args.seconds} 秒后开始录音，请对着板子正常说话。", flush=True)
    print("=" * 70, flush=True)

    with serial.Serial(args.port, args.baud, timeout=0.2) as ser, open(log_path, "w", encoding="utf-8") as log:
        ser.reset_input_buffer()
        time.sleep(0.3)
        ser.write(b"w")
        ser.flush()

        buf = b""
        header = None
        deadline = time.time() + 10
        while time.time() < deadline and header is None:
            chunk = ser.read(1)
            if not chunk:
                continue
            buf += chunk
            m = BEGIN.search(buf)
            if m:
                header = tuple(int(g) for g in m.groups())
                log.write(f"header={header}\n")
                break
        if header is None:
            print("[结论] FAIL：没有收到 PP_WAV_BEGIN。", flush=True)
            print(f"LOG: {log_path}", flush=True)
            return 2

        rate, channels, bits, samples = header
        need = samples * channels * bits // 8
        print(f"[固件] {rate} Hz / {channels} ch / {bits} bit / {samples} 采样（{need} 字节，约 "
              f"{need / 11520:.1f} 秒）", flush=True)
        last = time.time()
        pcm = bytearray()
        ser.timeout = 30
        while len(pcm) < need:
            chunk = ser.read(min(4096, need - len(pcm)))
            if not chunk:
                if time.time() - last > 20:
                    break
                continue
            pcm += chunk
            last = time.time()

        tail = ser.read(64)
        m = END.search(tail)
        ok_end = int(m.group(1)) == samples if m else False
        log.write(f"received={len(pcm)} expect={need} end_marker={ok_end}\n")

        if len(pcm) < need:
            print(f"[结论] FAIL：只收到 {len(pcm)}/{need} 字节，串口中断或日志插入。", flush=True)
            print(f"LOG: {log_path}", flush=True)
            return 3

        n = len(pcm) // 2
        vals = struct.unpack(f"<{n}h", bytes(pcm[: n * 2]))
        mean = sum(vals) / n
        rms = math.sqrt(sum((v - mean) ** 2 for v in vals) / n)
        peak = max(abs(v) for v in vals)
        log.write(f"mean={mean:.1f} rms={rms:.1f} peak={peak}\n")

        with wave.open(str(wav_path), "wb") as w:
            w.setnchannels(channels)
            w.setsampwidth(bits // 8)
            w.setframerate(rate)
            w.writeframes(bytes(pcm))

    print(f"[统计] mean={mean:.0f} ac_rms={rms:.0f} peak={peak} （满量程 32768）", flush=True)
    if peak < 200:
        print("[结论] FAIL：样本几乎恒定，可能没采到声音。", flush=True)
        rc = 4
    elif peak >= 32000:
        print("[结论] 待定：有削顶，说明离得太近或太大声，建议 20-30 cm 重录。", flush=True)
        rc = 5
    else:
        print("[结论] PASS：已生成 WAV，请播放确认能听清自己的声音。", flush=True)
        rc = 0
    print(f"WAV: {wav_path}", flush=True)
    print(f"LOG: {log_path}", flush=True)
    return rc


if __name__ == "__main__":
    sys.exit(main())

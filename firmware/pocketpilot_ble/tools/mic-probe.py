"""麦克风链路验证（两段式）：安静段 vs 出声段。

回答的问题：ESP32-S3-EYE 的数字 MEMS 麦克风 + I2S 采集拿到的是真实 PCM，
还是字节错位产生的“看起来有电平”的噪声？

判定依据（全部来自固件实测统计，不靠猜测）：
  1. 安静段交流有效值必须足够低（<= 1500，满量程 32768 的 4.6%）
  2. 出声段交流有效值至少是安静段的 3 倍
  3. 出声段削顶样本占比 < 5%
  4. 安静段直流偏置 |dc| <= 3000

用法:
  python tools\\mic-probe.py --port COM5
  python tools\\mic-probe.py --port COM5 --phase-ms 4000
"""
from __future__ import annotations

import argparse
import datetime as dt
import pathlib
import re
import sys
import time

import serial

HERE = pathlib.Path(__file__).resolve().parent
LOG_DIR = HERE.parent / "artifacts" / "logs"

BEGIN = re.compile(r"PP_MIC_PHASE_BEGIN name=(\w+) ms=(\d+)")
PHASE = re.compile(
    r"PP_MIC_PHASE name=(\w+) n=(\d+) ac_rms=(-?\d+) peak=(-?\d+) p2p=(-?\d+) dc=(-?\d+) clipped=(\d+)"
)
VERDICT = re.compile(
    r"PP_MIC_VERDICT result=(\w+) reason=(\w+) quiet_ac_rms=(-?\d+) loud_ac_rms=(-?\d+) "
    r"ratio_pct=(\d+) loud_clip_pm=(\d+) quiet_dc=(-?\d+)"
)
READY = re.compile(r"PP_MIC_READY (.*)")
FAIL = re.compile(r"PP_MIC_(INIT_FAIL|READ_FAIL|NO_DATA|PHASE_FAIL) ?(.*)")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", default="COM5")
    ap.add_argument("--baud", type=int, default=115200)
    ap.add_argument("--phase-ms", type=int, default=3000)
    ap.add_argument("--timeout", type=float, default=30.0)
    args = ap.parse_args()

    LOG_DIR.mkdir(parents=True, exist_ok=True)
    log_path = LOG_DIR / f"mic_probe_{dt.datetime.now():%Y%m%d_%H%M%S}.log"
    log = open(log_path, "w", encoding="utf-8")

    def emit(msg: str = "") -> None:
        print(msg, flush=True)
        log.write(msg + "\n")
        log.flush()

    emit(f"[mic] 串口 {args.port}，两段各 {args.phase_ms} ms")
    print("=" * 70, flush=True)
    print("  准备开始。第一段请保持安静，第二段提示后再说话或拍手。", flush=True)
    print("=" * 70, flush=True)

    phases: dict[str, tuple] = {}
    verdict = None
    ready = None
    failure = None

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

            m = READY.search(line)
            if m:
                ready = m.group(1).strip()
                emit(f"[固件] PP_MIC_READY {ready}")
                continue

            m = BEGIN.search(line)
            if m:
                name = m.group(1)
                if name == "quiet":
                    emit("[提示] >>> 现在保持安静（不要说话、不要碰板子）")
                else:
                    emit("[提示] >>> 现在对着板子说话或拍手！")
                continue

            m = PHASE.search(line)
            if m:
                name = m.group(1)
                phases[name] = tuple(int(g) for g in m.groups()[1:])
                n, ac, pk, p2p, dc, clip = phases[name]
                emit(f"[{name:5}] n={n:<6} ac_rms={ac:<6} peak={pk:<6} p2p={p2p:<6} dc={dc:<6} clipped={clip}")
                continue

            m = VERDICT.search(line)
            if m:
                verdict = m.groups()
                continue

            m = FAIL.search(line)
            if m:
                failure = line

    print("", flush=True)
    if ready is None:
        emit("[结论] FAIL：固件没有报告 PP_MIC_READY，I2S 通道没起来。")
        emit(f"LOG: {log_path}")
        log.close()
        return 2
    if failure or verdict is None:
        emit(f"[结论] FAIL：采集或判定失败 -> {failure or 'no verdict'}")
        emit(f"LOG: {log_path}")
        log.close()
        return 3

    result, reason, q_rms, l_rms, ratio, clip_pm, dc = verdict
    emit("")
    emit(f"[统计] 安静 ac_rms={q_rms}  出声 ac_rms={l_rms}  倍数={int(ratio) / 100:.1f}x  "
         f"削顶={int(clip_pm) / 10:.1f}%  安静 dc={dc}")
    if result == "PASS":
        emit("[结论] PASS：I2S 采集到随声音变化的真实 PCM（安静段低、出声段明显抬升、无明显削顶）。")
        emit("       边界：只证明采集链路，不代表语音识别或命令理解已验证。")
        rc = 0
    elif result == "UNDECIDED":
        emit(f"[结论] 待定：{reason}。电平有变化但未达到判据，请重试或换更安静的环境。")
        rc = 5
    else:
        emit(f"[结论] FAIL：{reason}。")
        rc = 4
    emit(f"LOG: {log_path}")
    log.close()
    return rc


if __name__ == "__main__":
    sys.exit(main())

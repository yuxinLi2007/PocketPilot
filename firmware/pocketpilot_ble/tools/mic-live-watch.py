"""实时电平监看（可循环续采）：让用户在任意时刻出声都能被记录。

固件命令 'l' 一次采集 90 秒，每 500 ms 打印一行：
    PP_LEVEL t=500ms rms=34 peak=120
本脚本会自动续发 'l'，把多段结果拼成一条时间轴，日志写到 artifacts/logs。

判定（只用实测数据）：
  baseline = 所有窗口 rms 的中位数
  峰值窗口 rms >= max(3 * baseline, 400)  -> PASS（采集链路随声音变化）
阈值 400 是绝对下限：真实拍手/说话在 15-25 cm 处应远高于环境噪声。

用法:
  python tools\\mic-live-watch.py --port COM5 --total-seconds 600
"""
from __future__ import annotations

import argparse
import datetime as dt
import pathlib
import re
import statistics
import sys
import time

import serial

HERE = pathlib.Path(__file__).resolve().parent
LOG_DIR = HERE.parent / "artifacts" / "logs"

LEVEL = re.compile(r"PP_LEVEL t=(\d+)ms rms=(\d+) peak=(\d+)")
END = re.compile(r"PP_LEVEL_WATCH_END")
FAIL = re.compile(r"PP_LEVEL_WATCH_FAIL")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", default="COM5")
    ap.add_argument("--baud", type=int, default=115200)
    ap.add_argument("--total-seconds", type=int, default=600)
    ap.add_argument("--chunk-seconds", type=int, default=90)
    args = ap.parse_args()

    LOG_DIR.mkdir(parents=True, exist_ok=True)
    log_path = LOG_DIR / f"mic_live_{dt.datetime.now():%Y%m%d_%H%M%S}.log"
    log = open(log_path, "w", encoding="utf-8")

    def emit(msg: str) -> None:
        print(msg, flush=True)
        log.write(msg + "\n")
        log.flush()

    windows: list[tuple[int, int, int]] = []
    failed = False
    chunks = max(1, round(args.total_seconds / args.chunk_seconds))
    started = time.time()

    emit(f"[开始] 端口={args.port} 每段={args.chunk_seconds}s 共{chunks}段 "
         f"(约{chunks * args.chunk_seconds}s) 现在开始拍手/说话都算数")

    with serial.Serial(args.port, args.baud, timeout=0.2) as ser:
        for idx in range(chunks):
            ser.reset_input_buffer()
            time.sleep(0.2)
            ser.write(b"l")
            ser.flush()
            offset_ms = idx * args.chunk_seconds * 1000
            deadline = time.time() + args.chunk_seconds + 25
            while time.time() < deadline:
                raw = ser.readline()
                if not raw:
                    continue
                line = raw.decode("utf-8", "replace").rstrip()
                log.write(line + "\n")
                log.flush()
                m = LEVEL.search(line)
                if m:
                    t, rms, peak = (int(g) for g in m.groups())
                    windows.append((offset_ms + t, rms, peak))
                    if len(windows) % 40 == 0:
                        emit(f"[进度] {len(windows)} 个窗口 最新 rms={rms} peak={peak} "
                             f"最长已录 {(time.time() - started) / 60:.1f} 分钟")
                    continue
                if END.search(line):
                    break
                if FAIL.search(line):
                    failed = True
                    emit(f"[固件] {line.strip()}")
                    break
            if failed:
                break

    if not windows:
        emit("[结论] FAIL：没有收到任何 PP_LEVEL 行，采集任务没起来。")
        emit(f"LOG: {log_path}")
        log.close()
        return 2
    if failed:
        emit("[结论] FAIL：固件报告 PP_LEVEL_WATCH_FAIL。")
        emit(f"LOG: {log_path}")
        log.close()
        return 3

    rms_values = [w[1] for w in windows]
    baseline = statistics.median(rms_values)
    peak_win = max(windows, key=lambda w: w[1])
    quiet_win = min(windows, key=lambda w: w[1])
    threshold = max(3 * baseline, 400)
    loud = [w for w in windows if w[1] >= threshold]

    emit(f"[统计] 窗口数={len(windows)}  基线(中位) rms={baseline:.0f}  判据阈值 rms>={threshold:.0f}")
    emit(f"[统计] 最安静窗口 t={quiet_win[0] / 1000:.1f}s rms={quiet_win[1]} peak={quiet_win[2]}")
    emit(f"[统计] 最响窗口   t={peak_win[0] / 1000:.1f}s rms={peak_win[1]} peak={peak_win[2]}")
    emit(f"[统计] 超过阈值的窗口数={len(loud)}")

    if peak_win[1] >= threshold:
        emit("[结论] PASS：麦克风电平随真实声音明显抬升，24bit I2S -> 16bit PCM 采集链路可用。")
        emit("       边界：只证明采集链路，不代表 ESP-SR 语音识别已验证。")
        rc = 0
    elif peak_win[1] >= max(2 * baseline, 200):
        emit("[结论] 待定：电平有变化但幅度偏小，可能距离太远或声音太小，建议靠近到 10-15 cm 重测。")
        rc = 5
    else:
        emit("[结论] FAIL：全程电平几乎不变，麦克风没有接收到声音或硬件异常。")
        rc = 4
    emit(f"LOG: {log_path}")
    log.close()
    return rc


if __name__ == "__main__":
    sys.exit(main())

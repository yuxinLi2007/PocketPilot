"""PocketPilot 实体按键诊断探针（引导式，静音采样）。

为什么需要它：
  固件里的 PP_PROBE 是 10 Hz 连续采样，本来就该一直刷屏 —— 但刷屏会把
  "现在请按哪个键" 的提示淹没。本脚本把固件日志静音，只在屏幕上显示
  倒计时 + 单行实时读数 + 阶段结论，最后一次给出 PASS/FAIL 汇总。

前置条件：
  已烧录带串口 'p' 探针命令的固件。

用法：
  python tools\input-probe.py --port COM5            # 引导式，5 个键
  python tools\input-probe.py --port COM5 --free 30  # 自由扫描 30 秒
  python tools\input-probe.py --port COM5 --only BOOT

注意：PASS 只代表"按键电信号进入并被正确识别"，
      不代表 BLE / Android 端到端已经验证。
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

# 官方 esp32_s3_eye BSP / button 组件的 ADC 电压窗口 (mV)
ADC_BANDS = {
    "MENU": (2310, 2510),
    "PLAY": (1880, 2080),
    "DOWN": (720, 920),
    "UP": (280, 480),
}

PROBE_RE = re.compile(r"PP_PROBE t=\d+ms gpio0=(\d) mv=(-?\d+) raw=(-?\d+) band=(\w+)")

PHASES = [
    ("BOOT", "底部 USB 口旁 **左边**那个小键（右边那个是 RST，别按错）", "gpio0"),
    ("MENU", "正面左上角 MENU 键", "adc"),
    ("PLAY", "正面左下角 PLAY 键", "adc"),
    ("UP",   "正面右上角 UP+ 键", "adc"),
    ("DOWN", "正面右下角 DN- 键", "adc"),
]

WINDOW_S = 6.0
READY_S = 3.0
SAMPLE_HZ = 10


def read_until(p, deadline, on_line, sink):
    """读串口直到 deadline，按行回调；返回该窗口内的原始文本。"""
    buf = b""
    text_parts = []
    line_buf = ""
    while time.time() < deadline:
        data = p.read(4096)
        if not data:
            continue
        buf += data
        chunk = data.decode("utf-8", "replace")
        text_parts.append(chunk)
        line_buf += chunk
        while "\n" in line_buf:
            line, line_buf = line_buf.split("\n", 1)
            sink.append(line)
            on_line(line)
    return "".join(text_parts)


def status_line(mv, raw, gpio0, band, pressed):
    tag = "按下 " + band if pressed else "未按下"
    return (f"\r   实时: gpio0={gpio0}  mv={mv:>4}mV  raw={raw:>4}  "
            f"识别={band:<5} {tag}            ")


def run_phase(p, name, hint, kind, window, sink, idx=1, total=1, live=True):
    lo, hi = ADC_BANDS.get(name, (0, 0))
    stats = {"mv": [], "gpio0": [], "hits": 0}

    def on_line(line):
        m = PROBE_RE.search(line)
        if not m:
            # 非采样行（例如按键事件）原样输出，这正是我们要的证据
            if "PP_PROBE" not in line and ("PP_BTN" in line or "PP_ADC" in line):
                sys.stdout.write("\r" + line.strip() + "\n")
            return
        g0, mv, raw, band = int(m.group(1)), int(m.group(2)), int(m.group(3)), m.group(4)
        stats["mv"].append(mv)
        stats["gpio0"].append(g0)
        hit = (g0 == 0) if kind == "gpio0" else (lo <= mv <= hi)
        if hit:
            stats["hits"] += 1
        if live:
            sys.stdout.write(status_line(mv, raw, g0, band, hit))
            sys.stdout.flush()

    if sys.stdin.isatty():
        print("")
        try:
            input(f"  准备好后按 [回车]，开始测试「{name}」...")
        except EOFError:
            pass
    print("")
    print("=" * 66)
    print(f"  [{idx}/{total}] {name} —— {hint}")
    print(f"  倒计时 {int(READY_S)} 秒 → 开始采样 {int(window)} 秒")
    print("=" * 66)
    for i in range(int(READY_S), 0, -1):
        sys.stdout.write(f"\r  准备... {i}   ")
        sys.stdout.flush()
        time.sleep(1.0)

    print("\r   >>> 现在按住这个键约 1 秒，然后松开 <<<                    ")
    p.write(b"p")
    p.flush()
    read_until(p, time.time() + window, on_line, sink)
    sys.stdout.write("\r" + " " * 100 + "\r")

    mv_min = min(stats["mv"], default=None)
    ok = stats["hits"] > 0
    if kind == "gpio0":
        detail = f"低电平样本 {stats['hits']}/{len(stats['gpio0'])}"
    elif mv_min is None:
        detail = "没有拿到 ADC 采样（串口/固件异常）"
    else:
        detail = f"窗口 {lo}-{hi} mV 命中 {stats['hits']}/{len(stats['mv'])}，最低 {mv_min} mV"
    print(f"  结果: {'PASS' if ok else 'FAIL'} — {detail}")
    return (name, ok, detail, mv_min)


def free_scan(p, seconds, sink):
    print("")
    print("=" * 66)
    print(f"  自由扫描 {int(seconds)} 秒：随便按，哪个键先出结果就记录哪个")
    print("  屏幕上只在读数**变化**时打印一行，安静是正常的")
    print("=" * 66)
    last = None
    seen = {}

    def on_line(line):
        nonlocal last
        m = PROBE_RE.search(line)
        if not m:
            if "PP_PROBE" not in line and ("PP_BTN" in line or "PP_ADC" in line):
                sys.stdout.write("  " + line.strip() + "\n")
            return
        key = (m.group(1), m.group(4))
        if key != last:
            last = key
            sys.stdout.write(f"  {time.strftime('%H:%M:%S')}  gpio0={m.group(1)} mv={m.group(2):>5} raw={m.group(3):>4} band={m.group(4)}\n")
            sys.stdout.flush()
            if m.group(4) != "NONE" or m.group(1) == "0":
                seen[key] = seen.get(key, 0) + 1

    p.write(b"p")
    p.flush()
    read_until(p, time.time() + seconds, on_line, sink)
    print("")
    if seen:
        for (g0, band) in seen:
            print(f"  检测到: gpio0={g0} band={band}")
    else:
        print("  这段时间内没有检测到任何按键变化。")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", default="COM5")
    ap.add_argument("--baud", type=int, default=115200)
    ap.add_argument("--window", type=float, default=WINDOW_S)
    ap.add_argument("--free", type=float, default=0.0)
    ap.add_argument("--only", default="")
    args = ap.parse_args()

    LOG_DIR.mkdir(parents=True, exist_ok=True)
    stamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    log_path = LOG_DIR / f"input_probe_{stamp}.log"

    phases = PHASES
    if args.only:
        want = args.only.upper()
        phases = [x for x in PHASES if x[0] == want]
        if not phases:
            print(f"unknown key: {args.only}")
            return 2

    print(f"[probe] port={args.port} 固件日志已静音，只看结论")
    p = serial.Serial(args.port, args.baud, timeout=0.05)
    sink = []
    results = []
    try:
        # 清掉开机残留
        p.reset_input_buffer()
        time.sleep(0.3)
        if args.free > 0:
            free_scan(p, args.free, sink)
        else:
            for i, (name, hint, kind) in enumerate(phases, 1):
                results.append(run_phase(p, name, hint, kind, args.window, sink,
                                         idx=i, total=len(phases)))
    finally:
        p.close()

    if results:
        print("")
        print("=" * 66)
        print("  按键探针结果汇总")
        print("=" * 66)
        for name, ok, detail, _ in results:
            print(f"  {name:<5} {'PASS' if ok else 'FAIL':<5} {detail}")
        print("=" * 66)
        good = sum(1 for r in results if r[1])
        print(f"  {good}/{len(results)} 个按键检测到电信号")
        if good == 0:
            print("  全部未检测到 → 说明按键电信号没有进入 GPIO0/GPIO1。")
            print("  下一步：拍按键特写 + 确认是否真的按到了（可以听到咔哒声）。")
        else:
            print("  说明：PASS 仅代表按键电信号已进入并被识别，")
            print("        不代表 BLE / Android 端到端已经验证。")

    header = "\n".join([
        "# PocketPilot input probe",
        f"# port={args.port} baud={args.baud} window={args.window}s",
        f"# started={stamp}",
    ])
    log_path.write_text(header + "\n" + "\n".join(sink) + "\n", encoding="utf-8")
    print(f"  LOG: {log_path}")
    return 0 if (not results or all(r[1] for r in results)) else 1


if __name__ == "__main__":
    raise SystemExit(main())

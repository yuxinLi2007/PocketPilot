#!/usr/bin/env python3
"""Static check of the PocketPilot T0 HID report descriptor.

Parses pp_report_map[] out of main/esp_hid_device_main.c, walks the HID
item stream and verifies the input report sizes that the firmware sends:

    Report ID 1 (keyboard)  -> 8 bytes (1 modifier + 1 reserved + 6 keys)
    Report ID 2 (consumer)  -> 2 bytes (one 16-bit usage array)

Also fails if the descriptor ends unbalanced. Run this after touching the
descriptor so a size mismatch is caught on the PC instead of on the phone.

Usage:
    python tools/check_report_map.py
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
SRC = HERE.parent / "main" / "esp_hid_device_main.c"
EXPECTED_INPUT_BYTES = {1: 8, 2: 2}


def load_bytes(src: Path) -> list[int]:
    text = src.read_text(encoding="utf-8")
    match = re.search(r"pp_report_map\[\]\s*=\s*\{(.*?)\n\};", text, re.S)
    if not match:
        raise SystemExit(f"pp_report_map[] not found in {src}")
    body = re.sub(r"/\*.*?\*/", "", match.group(1), flags=re.S)
    return [int(x, 16) for x in re.findall(r"0x([0-9A-Fa-f]{2})", body)]


def walk(data: list[int]) -> dict:
    i = 0
    report_id = 0
    size = 0
    count = 0
    depth = 0
    inputs: dict[int, int] = {}
    outputs: dict[int, int] = {}
    collections = 0

    def unsigned(prefix_bits: int) -> int:
        nonlocal i
        n = prefix_bits // 8
        val = int.from_bytes(bytes(data[i:i + n]), "little")
        i += n
        return val

    while i < len(data):
        prefix = data[i]
        i += 1
        if prefix == 0xFE:          # long item - not used here
            size_bits = data[i]
            i += 1 + size_bits
            continue

        size_code = prefix & 0x03
        item_type = (prefix >> 2) & 0x03
        tag = (prefix >> 4) & 0x0F
        nbytes = 4 if size_code == 3 else size_code

        if item_type == 0:          # Main
            if tag in (0x8, 0x9, 0xB):      # Input / Output / Feature
                bits = size * count
                if tag == 0x8:
                    inputs[report_id] = inputs.get(report_id, 0) + bits
                elif tag == 0x9:
                    outputs[report_id] = outputs.get(report_id, 0) + bits
            elif tag == 0xA:        # Collection
                depth += 1
                collections += 1
            elif tag == 0xC:        # End Collection
                depth -= 1
                if depth < 0:
                    raise SystemExit("descriptor has an unbalanced End Collection")
            i += nbytes
        elif item_type == 1:        # Global
            value = int.from_bytes(bytes(data[i:i + nbytes]), "little") if nbytes else 0
            if tag == 0x7:          # Report Size
                size = value
            elif tag == 0x9:        # Report Count
                count = value
            elif tag == 0x8:        # Report ID
                report_id = value
            i += nbytes
        else:                       # Local / reserved
            i += nbytes

    if depth != 0:
        raise SystemExit(f"descriptor ends with depth={depth}, expected 0")

    return {"inputs": inputs, "outputs": outputs, "collections": collections}


def main() -> int:
    data = load_bytes(SRC)
    info = walk(data)
    print(f"descriptor bytes : {len(data)}")
    print(f"collections      : {info['collections']}")
    ok = True
    for rid, bits in sorted(info["inputs"].items()):
        nbytes = (bits + 7) // 8
        exp = EXPECTED_INPUT_BYTES.get(rid)
        status = "OK" if exp == nbytes else f"MISMATCH (expected {exp})"
        if exp != nbytes:
            ok = False
        print(f"input report {rid}   : {bits} bits = {nbytes} bytes  {status}")
    missing = set(EXPECTED_INPUT_BYTES) - set(info["inputs"])
    if missing:
        ok = False
        print(f"missing input reports: {sorted(missing)}")
    print("RESULT:", "PASS" if ok else "FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())

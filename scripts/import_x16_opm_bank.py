#!/usr/bin/env python3
"""
Import Commander X16 YM2151 patches and emit a test OPM GM bank.

This script does NOT replace the existing opm_gm.bin. It writes a separate
test bank by default:
  - src/main/resources/opm/opm_gm_x16.bin
  - src/main/resources/opm/opm_gm_x16.json

Inputs can be local files or HTTP(S) URLs.
"""

from __future__ import annotations

import argparse
import json
import re
import struct
import urllib.request
from pathlib import Path

OPM_MAGIC = b"OPMGM-BNK\x00"
OPM_VERSION = 0x01
PATCH_BYTES = 52
N_PROGRAMS = 128
N_PERC = 128

DEFAULT_FMPATCHTABLES = (
    "https://raw.githubusercontent.com/X16Community/x16-rom/"
    "fbe32a60704f555c0515fe8d3cb76857eb56d670/audio/fmpatchtables.s"
)
DEFAULT_FMPATCHES = (
    "https://raw.githubusercontent.com/X16Community/x16-rom/"
    "fbe32a60704f555c0515fe8d3cb76857eb56d670/audio/fmpatches.s"
)

NOTE_TO_KC = [0xE, 0x0, 0x1, 0x2, 0x4, 0x5, 0x6, 0x8, 0x9, 0xA, 0xC, 0xD]
KC_TO_SEMITONE = {kc: i for i, kc in enumerate(NOTE_TO_KC)}


def read_text(path_or_url: str) -> str:
    if path_or_url.startswith("http://") or path_or_url.startswith("https://"):
        with urllib.request.urlopen(path_or_url, timeout=30) as res:
            return res.read().decode("utf-8")
    return Path(path_or_url).read_text(encoding="utf-8")


def parse_byte_values(byte_expr: str) -> list[int]:
    values: list[int] = []
    for tok in byte_expr.split(","):
        t = tok.strip()
        if not t:
            continue
        if t.startswith("$"):
            values.append(int(t[1:], 16))
        else:
            values.append(int(t, 10))
    return values


def parse_patch_macros(patchtables_text: str) -> dict[str, list[str]]:
    macros: dict[str, list[str]] = {}
    lines = patchtables_text.splitlines()
    i = 0
    while i < len(lines):
        m = re.match(r"\.define\s+([A-Za-z0-9_]+)\s*\\\s*$", lines[i].strip())
        if not m:
            i += 1
            continue
        macro_name = m.group(1)
        labels: list[str] = []
        i += 1
        while i < len(lines):
            raw = lines[i].split(";")[0].strip()
            if not raw:
                i += 1
                continue
            ended = not raw.endswith("\\")
            token = raw.rstrip("\\").strip().rstrip(",").strip()
            if token:
                labels.append(token)
            i += 1
            if ended:
                break
        macros[macro_name] = labels
    return macros


def parse_fmpatches(fmpatches_text: str) -> tuple[dict[str, list[int]], dict[int, str]]:
    label_to_bytes: dict[str, list[int]] = {}
    id_to_label: dict[int, str] = {}
    current_label: str | None = None
    current_bytes: list[int] = []

    def flush() -> None:
        nonlocal current_label, current_bytes
        if current_label is None:
            return
        if len(current_bytes) != 26:
            raise ValueError(f"Patch {current_label} has {len(current_bytes)} bytes (expected 26)")
        label_to_bytes[current_label] = current_bytes[:]
        id_match = re.match(r"M(\d+)_", current_label)
        if id_match:
            patch_id = int(id_match.group(1))
            id_to_label.setdefault(patch_id, current_label)
        current_label = None
        current_bytes = []

    for line in fmpatches_text.splitlines():
        code = line.split(";")[0].strip()
        if not code:
            continue
        lm = re.match(r"([A-Za-z_][A-Za-z0-9_]*):\s*$", code)
        if lm:
            flush()
            current_label = lm.group(1)
            continue
        bm = re.match(r"\.byte\s+(.+)$", code)
        if bm and current_label is not None:
            current_bytes.extend(parse_byte_values(bm.group(1)))

    flush()
    return label_to_bytes, id_to_label


def parse_drum_tables(patchtables_text: str) -> tuple[list[int], list[int]]:
    drum_patches: list[int] = []
    drum_kc: list[int] = []
    mode: str | None = None

    for line in patchtables_text.splitlines():
        code = line.split(";")[0].strip()
        if not code:
            continue
        if code.startswith("drum_patches:"):
            mode = "patches"
            continue
        if code.startswith("drum_kc:"):
            mode = "kc"
            continue
        if mode in ("patches", "kc"):
            if code.startswith(".byte"):
                vals = parse_byte_values(code[5:].strip())
                if mode == "patches":
                    drum_patches.extend(vals)
                else:
                    drum_kc.extend(vals)
            elif code.endswith(":"):
                mode = None

    if len(drum_patches) != 128:
        raise ValueError(f"drum_patches has {len(drum_patches)} entries (expected 128)")
    if len(drum_kc) != 128:
        raise ValueError(f"drum_kc has {len(drum_kc)} entries (expected 128)")
    return drum_patches, drum_kc


def x16_patch_to_opm_patch(name: str, raw26: list[int], perc_key: int) -> dict:
    if len(raw26) != 26:
        raise ValueError("raw26 must have 26 bytes")
    ops = []
    for op in range(4):
        ops.append({
            "dt1mul": raw26[2 + op] & 0x7F,
            "tl": raw26[6 + op] & 0x7F,
            "ksatk": raw26[10 + op] & 0xFF,
            "amd1r": raw26[14 + op] & 0xFF,
            "dt2d2r": raw26[18 + op] & 0xFF,
            "d1lrr": raw26[22 + op] & 0xFF,
            "ssgeg": 0,
        })
    return {
        "name": name[:16],
        "note_offset": 0,
        "perc_key": perc_key & 0xFF,
        "fbalg": raw26[0] & 0x3F,
        "lfosens": raw26[1] & 0x77,
        "operators": ops,
    }


def label_to_name(label: str) -> str:
    m = re.match(r"M\d+_(.+)$", label)
    base = m.group(1) if m else label
    base = base.replace("_", " ")
    return base[:16]


def kc_to_midi_note(kc: int) -> int:
    if kc == 0:
        return 0
    octave = (kc >> 4) & 0x07
    code = kc & 0x0F
    if code not in KC_TO_SEMITONE:
        return 0
    semitone = KC_TO_SEMITONE[code]
    if semitone == 0:
        return (octave + 2) * 12
    return (octave + 1) * 12 + semitone


def encode_patch(bp: dict) -> bytes:
    name_b = bp["name"].encode("latin1", errors="replace")[:16].ljust(16, b"\x00")
    header = struct.pack(
        "16sBBBB",
        name_b,
        bp["note_offset"] & 0xFF,
        bp["perc_key"] & 0xFF,
        bp["fbalg"] & 0xFF,
        bp["lfosens"] & 0xFF,
    )
    ops_b = bytearray()
    for op in bp["operators"]:
        ops_b += struct.pack(
            "8B",
            op["dt1mul"] & 0xFF,
            op["tl"] & 0xFF,
            op["ksatk"] & 0xFF,
            op["amd1r"] & 0xFF,
            op["dt2d2r"] & 0xFF,
            op["d1lrr"] & 0xFF,
            op.get("ssgeg", 0) & 0xFF,
            0,
        )
    if len(header) + len(ops_b) != PATCH_BYTES:
        raise ValueError("Encoded patch size mismatch")
    return header + bytes(ops_b)


def write_opm_bank(melodic: list[dict], percussion: list[dict], path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("wb") as f:
        f.write(OPM_MAGIC)
        f.write(struct.pack("<BHH", OPM_VERSION, N_PROGRAMS, N_PERC))
        for p in melodic:
            f.write(encode_patch(p))
        for p in percussion:
            f.write(encode_patch(p))


def main() -> None:
    parser = argparse.ArgumentParser(description="Import X16 YM2151 patches to opm_gm_x16 bank.")
    parser.add_argument("--fmpatchtables", default=DEFAULT_FMPATCHTABLES)
    parser.add_argument("--fmpatches", default=DEFAULT_FMPATCHES)
    parser.add_argument("--out-bin", default="src/main/resources/opm/opm_gm_x16.bin")
    parser.add_argument("--out-json", default="src/main/resources/opm/opm_gm_x16.json")
    args = parser.parse_args()

    patchtables_text = read_text(args.fmpatchtables)
    fmpatches_text = read_text(args.fmpatches)

    macros = parse_patch_macros(patchtables_text)
    label_to_bytes, id_to_label = parse_fmpatches(fmpatches_text)
    drum_patches, drum_kc = parse_drum_tables(patchtables_text)

    melodic_macro_names = [
        "GM0_PIANO",
        "GM1_MALLET",
        "GM2_ORGAN",
        "GM3_GUITAR",
        "GM4_BASS",
        "GM5_STRINGS",
        "GM6_ENSEMBLE",
        "GM7_BRASS",
        "GM8_REED",
        "GM9_PIPE",
        "GMA_LEAD",
        "GMB_PAD",
        "GMC_SYNFX",
        "GMD_ETHNIC",
        "GME_PERC",
        "GMF_SFX",
    ]

    melodic_labels: list[str] = []
    for macro_name in melodic_macro_names:
        melodic_labels.extend(macros.get(macro_name, []))
    if len(melodic_labels) != 128:
        raise ValueError(f"Melodic program table has {len(melodic_labels)} entries (expected 128)")

    melodic: list[dict] = []
    for label in melodic_labels:
        if label not in label_to_bytes:
            raise ValueError(f"Missing patch label in fmpatches.s: {label}")
        melodic.append(x16_patch_to_opm_patch(label_to_name(label), label_to_bytes[label], 0))

    silent_label = id_to_label.get(128)
    if not silent_label:
        raise ValueError("Could not resolve silent patch id 128")
    silent_patch = x16_patch_to_opm_patch(label_to_name(silent_label), label_to_bytes[silent_label], 0)

    percussion: list[dict] = []
    for note in range(128):
        patch_id = drum_patches[note]
        label = id_to_label.get(patch_id)
        if not label:
            percussion.append(silent_patch)
            continue
        perc_key = kc_to_midi_note(drum_kc[note])
        p = x16_patch_to_opm_patch(label_to_name(label), label_to_bytes[label], perc_key)
        percussion.append(p)

    out_bin = Path(args.out_bin)
    out_json = Path(args.out_json)
    write_opm_bank(melodic, percussion, out_bin)
    out_json.parent.mkdir(parents=True, exist_ok=True)
    out_json.write_text(
        json.dumps(
            {
                "source": {
                    "fmpatchtables": args.fmpatchtables,
                    "fmpatches": args.fmpatches,
                },
                "melodic": melodic,
                "percussion": percussion,
            },
            indent=2,
        ),
        encoding="utf-8",
    )
    print(f"Wrote {out_bin}")
    print(f"Wrote {out_json}")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""
Measure relative RMS levels of GM instruments to calibrate inter-channel balance.

Generates a short sustain note for each instrument, renders via fluidsynth,
measures RMS level, and reports dB differences with suggested CC7 scale factors.

Usage:
  python3 scripts/measure_instrument_levels.py [--sf3 path/to/soundfont.sf3]
  python3 scripts/measure_instrument_levels.py --sf3 build/soundfonts/FluidR3_GM.sf3
"""

import argparse
import math
import os
import struct
import subprocess
import sys
import tempfile
import wave

import numpy as np

# Instruments used by VGM converters in VgmToMidiConverter.
# (name, GM program, MIDI channel)
INSTRUMENTS = [
    ("Square Lead (PSG ch0)",  80, 0),
    ("Square Lead (PSG ch1)",  80, 1),
    ("Square Lead (PSG ch2)",  80, 2),
    ("Rock Organ (SCC ch10)", 18, 10),
    ("Rock Organ (SCC ch11)", 18, 11),
    ("Rock Organ (SCC ch12)", 18, 12),
]

# Reference note: C4 (MIDI 60), sustained for SUSTAIN_SEC seconds.
NOTE         = 60
VELOCITY     = 127
CC7          = 127
SUSTAIN_SEC  = 2.0
PPQ          = 480
TEMPO_US     = 500_000   # 120 BPM → 960 ticks/sec
TICKS_PER_S  = 1_000_000 / TEMPO_US * PPQ  # 960
SAMPLE_RATE  = 44100
# Trim leading attack transient before measuring.
WARMUP_SEC   = 0.2


# ── MIDI writer ───────────────────────────────────────────────────────────────

def _vlq(n: int) -> bytes:
    """Encode n as MIDI variable-length quantity."""
    if n < 0x80:
        return bytes([n])
    out = [n & 0x7F]
    n >>= 7
    while n:
        out.append((n & 0x7F) | 0x80)
        n >>= 7
    return bytes(reversed(out))


def _meta(delta: int, mtype: int, data: bytes) -> bytes:
    return _vlq(delta) + bytes([0xFF, mtype, len(data)]) + data


def _short(delta: int, status: int, d1: int, d2: int) -> bytes:
    return _vlq(delta) + bytes([status, d1, d2])


def make_midi(program: int, channel: int) -> bytes:
    """Build a minimal Type-0 MIDI file: sustain one note on the given channel."""
    sustain_ticks = int(SUSTAIN_SEC * TICKS_PER_S)

    events = bytearray()
    # Tempo
    events += _meta(0, 0x51, struct.pack(">I", TEMPO_US)[1:])
    # Program Change
    events += _vlq(0) + bytes([0xC0 | channel, program])
    # CC7 volume
    events += _short(0, 0xB0 | channel, 7, CC7)
    # NoteOn
    events += _short(0, 0x90 | channel, NOTE, VELOCITY)
    # NoteOff (after sustain)
    events += _short(sustain_ticks, 0x80 | channel, NOTE, 0)
    # End of track
    events += _meta(0, 0x2F, b"")

    track_chunk = b"MTrk" + struct.pack(">I", len(events)) + bytes(events)
    header = b"MThd" + struct.pack(">I", 6) + struct.pack(">HHH", 0, 1, PPQ)
    return header + track_chunk


# ── audio measurement ─────────────────────────────────────────────────────────

def rms_dbfs(wav_path: str) -> float:
    """Return RMS level in dBFS (full-scale = 0 dBFS) after trimming attack."""
    with wave.open(wav_path, "rb") as wf:
        sr      = wf.getframerate()
        ch      = wf.getnchannels()
        depth   = wf.getsampwidth()
        raw     = wf.readframes(wf.getnframes())

    dtype = {1: np.int8, 2: np.int16}[depth]
    scale = {1: 128.0,  2: 32768.0}[depth]
    samples = np.frombuffer(raw, dtype=dtype).astype(np.float64) / scale
    mono = samples[0::ch]  # left channel

    warmup = int(sr * WARMUP_SEC)
    mono = mono[warmup:]

    rms = float(np.sqrt(np.mean(mono ** 2)))
    if rms < 1e-12:
        return -120.0
    return 20.0 * math.log10(rms)


def render_midi(midi_bytes: bytes, sf3: str, tmp_dir: str, name: str) -> float:
    mid_path = os.path.join(tmp_dir, f"{name}.mid")
    wav_path = os.path.join(tmp_dir, f"{name}.wav")

    with open(mid_path, "wb") as f:
        f.write(midi_bytes)

    result = subprocess.run(
        ["fluidsynth", "-q", "-F", wav_path, "-r", str(SAMPLE_RATE), sf3, mid_path],
        capture_output=True, text=True
    )
    if result.returncode != 0:
        raise RuntimeError(f"fluidsynth failed for {name}:\n{result.stderr}")

    return rms_dbfs(wav_path)


# ── main ──────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--sf3", default="build/soundfonts/FluidR3_GM.sf3",
                        help="Path to soundfont (default: build/soundfonts/FluidR3_GM.sf3)")
    args = parser.parse_args()

    if not os.path.exists(args.sf3):
        print(f"ERROR: Soundfont not found: {args.sf3}", file=sys.stderr)
        sys.exit(1)

    print(f"Soundfont: {args.sf3}")
    print(f"Note: C4 (MIDI {NOTE}), velocity={VELOCITY}, CC7={CC7}, duration={SUSTAIN_SEC}s")
    print()

    results = []
    with tempfile.TemporaryDirectory() as tmp:
        for name, program, channel in INSTRUMENTS:
            safe_name = name.replace(" ", "_").replace("(", "").replace(")", "")
            midi = make_midi(program, channel)
            try:
                db = render_midi(midi, args.sf3, tmp, safe_name)
                results.append((name, program, channel, db))
                print(f"  [{program:3d}] {name:<30}  {db:+7.2f} dBFS")
            except RuntimeError as e:
                print(f"  [{program:3d}] {name:<30}  ERROR: {e}", file=sys.stderr)

    if not results:
        sys.exit(1)

    print()

    # Find representative levels per program number (average across channels).
    by_prog: dict[int, list[float]] = {}
    for name, prog, ch, db in results:
        by_prog.setdefault(prog, []).append(db)
    avg_db = {prog: float(np.mean(vals)) for prog, vals in by_prog.items()}

    print("Average RMS by program:")
    for prog, db in sorted(avg_db.items()):
        print(f"  prog {prog:3d}: {db:+7.2f} dBFS")

    # Target = quietest instrument (don't boost, only cut the louder ones).
    target_db = min(avg_db.values())
    loudest_prog = max(avg_db, key=avg_db.__getitem__)
    print()
    print(f"Target level: {target_db:+.2f} dBFS  (quietest instrument, no boosting)")
    print()

    print("Suggested CC7 scale factors (multiply CC7 before sending):")
    for prog, db in sorted(avg_db.items()):
        diff = db - target_db   # dB above target
        factor = 10 ** (-diff / 20.0)
        max_cc7 = min(127, round(CC7 * factor))
        print(f"  prog {prog:3d}  diff={diff:+.2f} dB  factor={factor:.3f}  max CC7={max_cc7}")

    print()
    # Specific advice for PSG vs SCC.
    psg_db  = avg_db.get(80)
    scc_db  = avg_db.get(18)
    if psg_db is not None and scc_db is not None:
        gap = psg_db - scc_db
        print(f"PSG (prog 80) vs SCC (prog 18): PSG is {abs(gap):.1f} dB {'louder' if gap > 0 else 'quieter'}")
        if gap > 0:
            psg_factor = 10 ** (-gap / 20.0)
            psg_max_cc7 = min(127, round(CC7 * psg_factor))
            print(f"  → Scale PSG CC7 by {psg_factor:.3f}  (cap at {psg_max_cc7} instead of 127)")
            print(f"  → In Sn76489MidiConverter / Ay8910MidiConverter, multiply toVelocity() result by {psg_factor:.3f}")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""
Compare PSG vs SCC channel levels from a real VGM file.

Exports two MIDI files (PSG-only and SCC-only) using `midra vgm --export-midi --mute`,
renders both to WAV via fluidsynth, then measures RMS and spectral levels.

Usage:
  python3 scripts/compare_vgm_chips.py <file.vgm> [--sf3 path/to/soundfont.sf3]

Output: RMS difference in dB and suggested PSG_CC7_GAIN correction.
"""

import argparse
import math
import os
import subprocess
import sys
import tempfile
import wave

import numpy as np

DEFAULT_SF3    = "build/soundfonts/FluidR3_GM.sf3"
MIDRA_BIN      = "build/install/midrax/bin/midrax"
SAMPLE_RATE    = 44100
WARMUP_SEC     = 0.5   # skip attack transient before measuring
ANALYSIS_SEC   = 10.0  # analyse up to this many seconds (or full file if shorter)


# ── audio I/O ─────────────────────────────────────────────────────────────────

def load_wav_mono(path: str) -> tuple[np.ndarray, int]:
    with wave.open(path, "rb") as wf:
        sr    = wf.getframerate()
        ch    = wf.getnchannels()
        depth = wf.getsampwidth()
        raw   = wf.readframes(wf.getnframes())

    dtype = {1: np.int8, 2: np.int16}[depth]
    scale = {1: 128.0,  2: 32768.0}[depth]
    samples = np.frombuffer(raw, dtype=dtype).astype(np.float64) / scale

    # For stereo files, average L and R channels so that panned content (e.g. YM2612 with
    # per-channel CC10) is measured with the same energy weight as centred mono content.
    # Using left-channel only would misread a right-panned FM signal as near-silence.
    if ch == 2:
        mono = (samples[0::2] + samples[1::2]) / 2
    else:
        mono = samples

    warmup = int(sr * WARMUP_SEC)
    limit  = warmup + int(sr * ANALYSIS_SEC)
    return mono[warmup:limit], sr


def rms_dbfs(signal: np.ndarray) -> float:
    rms = float(np.sqrt(np.mean(signal ** 2)))
    return 20.0 * math.log10(rms) if rms > 1e-12 else -120.0


def spectral_centroid(signal: np.ndarray, sr: int) -> float:
    """Frequency at which spectral energy is centred."""
    n    = len(signal)
    amp  = np.abs(np.fft.rfft(signal * np.hanning(n)))
    freq = np.fft.rfftfreq(n, 1.0 / sr)
    total = amp.sum()
    return float(np.dot(freq, amp) / total) if total > 1e-12 else 0.0


def band_rms(signal: np.ndarray, sr: int, lo: float, hi: float) -> float:
    """RMS of the signal within the frequency band [lo, hi] Hz."""
    n     = len(signal)
    fft   = np.fft.rfft(signal * np.hanning(n))
    freqs = np.fft.rfftfreq(n, 1.0 / sr)
    mask  = (freqs >= lo) & (freqs <= hi)
    amp   = np.abs(fft[mask]) * 2 / n
    rms   = float(np.sqrt(np.mean(amp ** 2))) if mask.any() else 0.0
    return 20.0 * math.log10(rms) if rms > 1e-12 else -120.0


# ── VGM → MIDI export ─────────────────────────────────────────────────────────

def export_midi(vgm: str, mid_out: str, mute: str) -> None:
    """Call `midra vgm --export-midi <mid_out> --mute <mute> <vgm>`."""
    cmd = [MIDRA_BIN, "vgm", "--export-midi", mid_out, "--mute", mute, vgm]
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        raise RuntimeError(f"midra export failed ({mute}):\n{result.stderr}")


def render_wav(mid: str, wav_out: str, sf3: str) -> None:
    """Render a MIDI file to WAV via fluidsynth."""
    cmd = ["fluidsynth", "-q", "-F", wav_out, "-r", str(SAMPLE_RATE), sf3, mid]
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        raise RuntimeError(f"fluidsynth failed:\n{result.stderr}")


# ── main ───────────────────────────────────────────────────────────────────────

def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("vgm",  help="VGM/VGZ file to analyse")
    parser.add_argument("--sf3", default=DEFAULT_SF3,
                        help=f"SoundFont path (default: {DEFAULT_SF3})")
    parser.add_argument("--mode", choices=["msx", "genesis"], default="msx",
                        help="msx: compare PSG vs SCC (default); genesis: compare PSG(SN76489) vs FM(YM2612)")
    parser.add_argument("--build", action="store_true",
                        help="Run ./gradlew installDist before analysis")
    args = parser.parse_args()

    if not os.path.exists(args.vgm):
        sys.exit(f"ERROR: file not found: {args.vgm}")
    if not os.path.exists(args.sf3):
        sys.exit(f"ERROR: SoundFont not found: {args.sf3}")

    if args.build:
        print("Building…")
        subprocess.run(["./gradlew", "installDist", "-x", "test", "-q"], check=True)

    if not os.path.exists(MIDRA_BIN):
        sys.exit(f"ERROR: {MIDRA_BIN} not found. Run with --build or run ./gradlew installDist first.")

    # chip_a = louder reference group name, mute_b = what to mute to isolate chip_a
    if args.mode == "genesis":
        chip_a_name, chip_b_name = "PSG(SN76489)", "FM(YM2612)"
        mute_for_a, mute_for_b  = "fm", "psg"
    else:
        chip_a_name, chip_b_name = "PSG", "SCC"
        mute_for_a, mute_for_b  = "scc", "psg"

    print(f"VGM:   {args.vgm}")
    print(f"SF3:   {args.sf3}")
    print(f"Mode:  {args.mode}  ({chip_a_name} vs {chip_b_name})")
    print()

    with tempfile.TemporaryDirectory() as tmp:
        a_mid = os.path.join(tmp, "chip_a.mid")
        b_mid = os.path.join(tmp, "chip_b.mid")
        a_wav = os.path.join(tmp, "chip_a.wav")
        b_wav = os.path.join(tmp, "chip_b.wav")

        print(f"Exporting MIDI (muting {chip_b_name} → {chip_a_name}-only)…")
        export_midi(args.vgm, a_mid, mute_for_a)

        print(f"Exporting MIDI (muting {chip_a_name} → {chip_b_name}-only)…")
        export_midi(args.vgm, b_mid, mute_for_b)

        print(f"Rendering {chip_a_name}-only WAV via fluidsynth…")
        render_wav(a_mid, a_wav, args.sf3)

        print(f"Rendering {chip_b_name}-only WAV via fluidsynth…")
        render_wav(b_mid, b_wav, args.sf3)

        sig_a, sr = load_wav_mono(a_wav)
        sig_b, _  = load_wav_mono(b_wav)

    rms_a = rms_dbfs(sig_a)
    rms_b = rms_dbfs(sig_b)
    gap   = rms_a - rms_b

    cent_a = spectral_centroid(sig_a, sr)
    cent_b = spectral_centroid(sig_b, sr)

    bands = [
        ("Low  (  80–300 Hz)",  80,   300),
        ("Mid  ( 300–2k  Hz)",  300,  2000),
        ("High (  2k–8k  Hz)", 2000, 8000),
    ]

    W = max(len(chip_a_name), len(chip_b_name), 10)
    diff_label = f"{chip_a_name}−{chip_b_name}"
    print()
    print(f"{'':30}  {chip_a_name:>{W}}  {chip_b_name:>{W}}  {diff_label:>12}")
    print("─" * (30 + W*2 + 20))
    print(f"{'RMS level':30}  {rms_a:+{W}.2f}  {rms_b:+{W}.2f}  {gap:+11.2f} dB")
    print(f"{'Spectral centroid':30}  {cent_a:{W}.0f}  {cent_b:{W}.0f}  Hz")
    for label, lo, hi in bands:
        ba = band_rms(sig_a, sr, lo, hi)
        bb = band_rms(sig_b, sr, lo, hi)
        print(f"  {label:28}  {ba:+{W}.2f}  {bb:+{W}.2f}  {ba-bb:+11.2f} dB")

    # target_gap: how many dB chip_a (PSG) should sit relative to chip_b (FM/SCC).
    # genesis: FM is ~10 dB louder by hardware design → PSG should be −10 dB vs FM.
    # msx:     PSG and SCC play equal melodic roles → target 0 dB.
    target_gap = -10.0 if args.mode == "genesis" else 0.0

    print()
    print(f"Target gap ({chip_a_name} − {chip_b_name}): {target_gap:+.1f} dB")
    print(f"Measured gap:                   {gap:+.2f} dB")
    delta_db = target_gap - gap    # how much to adjust chip_a (PSG)
    factor   = 10 ** (delta_db / 20.0)

    if abs(delta_db) < 0.5:
        print(f"Levels are within 0.5 dB of target — no correction needed.")
    else:
        direction = "Reduce" if delta_db < 0 else "Increase"
        print(f"  → {direction} {chip_a_name} CC7 by {abs(delta_db):.1f} dB")
        print(f"  → Suggested PSG_CC7_GAIN = {factor:.3f}")


if __name__ == "__main__":
    main()

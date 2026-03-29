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
    mono    = samples[0::ch]   # left channel only

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

    print(f"VGM:   {args.vgm}")
    print(f"SF3:   {args.sf3}")
    print()

    with tempfile.TemporaryDirectory() as tmp:
        psg_mid = os.path.join(tmp, "psg_only.mid")
        scc_mid = os.path.join(tmp, "scc_only.mid")
        psg_wav = os.path.join(tmp, "psg_only.wav")
        scc_wav = os.path.join(tmp, "scc_only.wav")

        print("Exporting MIDI (muting SCC → PSG-only)…")
        export_midi(args.vgm, psg_mid, "scc")

        print("Exporting MIDI (muting PSG → SCC-only)…")
        export_midi(args.vgm, scc_mid, "psg")

        print("Rendering PSG-only WAV via fluidsynth…")
        render_wav(psg_mid, psg_wav, args.sf3)

        print("Rendering SCC-only WAV via fluidsynth…")
        render_wav(scc_mid, scc_wav, args.sf3)

        psg_sig, sr = load_wav_mono(psg_wav)
        scc_sig, _  = load_wav_mono(scc_wav)

    psg_rms = rms_dbfs(psg_sig)
    scc_rms = rms_dbfs(scc_sig)
    gap     = psg_rms - scc_rms

    psg_centroid = spectral_centroid(psg_sig, sr)
    scc_centroid = spectral_centroid(scc_sig, sr)

    # Band RMS breakdown
    bands = [
        ("Low  (  80–300 Hz)", 80,   300),
        ("Mid  ( 300–2k  Hz)", 300,  2000),
        ("High (  2k–8k  Hz)", 2000, 8000),
    ]

    print()
    print(f"{'':30}  {'PSG':>10}  {'SCC':>10}  {'PSG−SCC':>10}")
    print("─" * 68)
    print(f"{'RMS level':30}  {psg_rms:+9.2f}  {scc_rms:+9.2f}  {gap:+9.2f} dB")
    print(f"{'Spectral centroid':30}  {psg_centroid:9.0f}  {scc_centroid:9.0f}  Hz")
    for label, lo, hi in bands:
        pb = band_rms(psg_sig, sr, lo, hi)
        sb = band_rms(scc_sig, sr, lo, hi)
        print(f"  {label:28}  {pb:+9.2f}  {sb:+9.2f}  {pb-sb:+9.2f} dB")

    print()
    # Suggested correction
    if gap > 0:
        factor    = 10 ** (-gap / 20.0)
        psg_cc7   = min(127, round(127 * factor))
        print(f"PSG is {gap:.1f} dB louder than SCC on this track.")
        print(f"  → To match levels, scale PSG CC7 by {factor:.3f}")
        print(f"     (max CC7 {psg_cc7} instead of 127)")
        print(f"  → Set PSG_CC7_GAIN = {factor:.3f} in Sn76489MidiConverter")
        print()
        if abs(gap) < 1.0:
            print("Difference is < 1 dB — no correction needed.")
    elif gap < -1.0:
        factor  = 10 ** (gap / 20.0)
        scc_cc7 = min(127, round(127 * factor))
        print(f"SCC is {abs(gap):.1f} dB louder than PSG on this track.")
        print(f"  → To match levels, scale SCC CC7 by {factor:.3f}")
        print(f"     (max CC7 {scc_cc7} instead of 127)")
    else:
        print(f"PSG and SCC differ by only {abs(gap):.1f} dB — levels are balanced.")


if __name__ == "__main__":
    main()

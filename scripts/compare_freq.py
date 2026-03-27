#!/usr/bin/env python3
"""
Compare frequency response of two audio samples.

Usage:
  python3 scripts/compare_freq.py <file_a> <file_b> [--ref-freq 1000] [--freqs 100,500,1000,2000,5000,10000]

Supports WAV files and raw 16-bit LE stereo PCM (.raw).
Both signals are normalised to a reference frequency (default 1 kHz) and the
per-frequency difference (A − B) is reported in dB.
"""
import argparse, math, sys, wave
import numpy as np

DEFAULT_SR = 44100
WARMUP_SEC = 0.5
DEFAULT_FREQS = [100, 200, 500, 1000, 1500, 2000, 3000, 5000, 8000, 10000]


# ── loading ──────────────────────────────────────────────────────────────────

def load_wav(path, target_sr):
    with wave.open(path, 'rb') as wf:
        sr = wf.getframerate()
        ch = wf.getnchannels()
        depth = wf.getsampwidth()
        raw = wf.readframes(wf.getnframes())

    dtype = {1: np.int8, 2: np.int16}[depth]
    scale = {1: 128.0, 2: 32768.0}[depth]
    samples = np.frombuffer(raw, dtype=dtype).astype(np.float64) / scale
    mono = samples[0::ch]  # left channel

    warmup = int(sr * WARMUP_SEC)
    mono = mono[warmup:]

    if sr != target_sr:
        new_len = int(len(mono) * target_sr / sr)
        mono = np.interp(np.linspace(0, len(mono) - 1, new_len),
                         np.arange(len(mono)), mono)
    return mono, target_sr


def load_raw(path, target_sr):
    data = np.fromfile(path, dtype='<i2').astype(np.float64) / 32768.0
    mono = data[0::2]  # left channel of stereo interleaved

    warmup = int(target_sr * WARMUP_SEC)
    mono = mono[warmup:]
    return mono, target_sr


def load_audio(path, target_sr):
    if path.lower().endswith('.raw'):
        return load_raw(path, target_sr)
    return load_wav(path, target_sr)


# ── analysis ─────────────────────────────────────────────────────────────────

def spectrum(signal, sr):
    n = len(signal)
    amp = np.abs(np.fft.rfft(signal * np.hanning(n))) * 2 / n
    freqs = np.fft.rfftfreq(n, 1.0 / sr)
    return freqs, amp


def level_at(amp, freqs, freq, bw_pct=0.02):
    lo, hi = freq * (1 - bw_pct), freq * (1 + bw_pct)
    mask = (freqs >= lo) & (freqs <= hi)
    return float(np.max(amp[mask])) if mask.any() else 0.0


def relative_db(amp, freqs, freq, ref_level):
    lv = level_at(amp, freqs, freq)
    if ref_level < 1e-12 or lv < 1e-12:
        return None
    return 20 * math.log10(lv / ref_level)


# ── main ─────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(
        description='Compare frequency response of two audio samples.')
    parser.add_argument('file_a', help='first audio file (WAV or .raw)')
    parser.add_argument('file_b', help='second audio file (WAV or .raw)')
    parser.add_argument('--ref-freq', type=float, default=1000,
                        help='reference frequency for normalisation (default: 1000 Hz)')
    parser.add_argument('--freqs', type=str, default=None,
                        help='comma-separated list of frequencies to compare')
    parser.add_argument('--sr', type=int, default=DEFAULT_SR,
                        help='target sample rate (default: 44100)')
    args = parser.parse_args()

    freqs = (list(map(float, args.freqs.split(',')))
             if args.freqs else DEFAULT_FREQS)
    ref_freq = args.ref_freq

    sig_a, sr_a = load_audio(args.file_a, args.sr)
    sig_b, sr_b = load_audio(args.file_b, args.sr)

    name_a = args.file_a.rsplit('/', 1)[-1]
    name_b = args.file_b.rsplit('/', 1)[-1]

    print(f"A: {name_a}  ({len(sig_a)/args.sr:.1f}s)")
    print(f"B: {name_b}  ({len(sig_b)/args.sr:.1f}s)")
    print(f"Reference: {ref_freq:.0f} Hz")
    print()

    fa, amp_a = spectrum(sig_a, args.sr)
    fb, amp_b = spectrum(sig_b, args.sr)

    ref_a = level_at(amp_a, fa, ref_freq)
    ref_b = level_at(amp_b, fb, ref_freq)

    col_a = f"A ({name_a[:16]})"
    col_b = f"B ({name_b[:16]})"
    hdr = f"{'Freq':>10}  {col_a:>20}  {col_b:>20}  {'A − B':>8}"
    print(hdr)
    print("─" * len(hdr))

    for f in sorted(freqs):
        db_a = relative_db(amp_a, fa, f, ref_a)
        db_b = relative_db(amp_b, fb, f, ref_b)

        str_a = f"{db_a:+7.1f} dBr" if db_a is not None else "    N/A"
        str_b = f"{db_b:+7.1f} dBr" if db_b is not None else "    N/A"

        if db_a is not None and db_b is not None:
            gap = db_a - db_b
            str_gap = f"{gap:+6.1f} dB"
        else:
            str_gap = "   N/A"

        marker = " *" if f == ref_freq else ""
        print(f"{f:>9.0f}  {str_a:>20}  {str_b:>20}  {str_gap:>8}{marker}")

    print()
    print("dBr = dB relative to reference frequency. * = reference (always 0.0).")
    print("A − B > 0: A is relatively louder at that frequency.")


if __name__ == '__main__':
    main()

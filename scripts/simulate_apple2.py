#!/usr/bin/env python3
"""
Simulate OneBitHardwareFilter (apple2 mode) on a 440 Hz sine wave
and write the result as stereo interleaved 16-bit LE PCM for analyze_audio.py.

apple2 params from CommonOptions.wrapRetroPipeline():
  mode        = "pwm"
  carrierHz   = 22050.0  → carrierStep = 0.5
  levels      = 32.0
  smoothAlpha = 0.55
"""

import numpy as np
import sys

FS = 44100
DURATION = 2.0          # seconds
FREQ = 440.0
CARRIER_STEP = 22050.0 / FS   # 0.5
LEVELS = 32.0
ALPHA = 0.55


def integrate_pwm(start_phase, step, duty):
    end_phase = start_phase + step
    high_time = 0.0
    if end_phase > 1.0:
        if start_phase < duty:
            high_time += duty - start_phase
        remainder = end_phase - 1.0
        if remainder > duty:
            high_time += duty
        else:
            high_time += remainder
    else:
        if end_phase <= duty:
            high_time = step
        elif start_phase >= duty:
            high_time = 0.0
        else:
            high_time = duty - start_phase
    return (high_time / step) * 2.0 - 1.0


def simulate(signal_mono):
    """Apply OneBitHardwareFilter (pwm mode) to a mono float[-1,1] array."""
    n = len(signal_mono)
    out = np.zeros(n, dtype=np.float64)

    carrier_phase = 0.0
    smooth_l1 = 0.0
    smooth_l2 = 0.0

    for i in range(n):
        mono_in = signal_mono[i]

        if abs(mono_in) < 1e-4:
            filtered = 0.0
        else:
            raw_duty = max(0.0, min(1.0, (mono_in + 1.0) * 0.5))
            duty = round(raw_duty * LEVELS) / LEVELS
            filtered = integrate_pwm(carrier_phase, CARRIER_STEP, duty)
            carrier_phase = (carrier_phase + CARRIER_STEP) % 1.0

        smooth_l1 += ALPHA * (filtered - smooth_l1)
        smooth_l2 += ALPHA * (smooth_l1 - smooth_l2)
        if abs(smooth_l1) < 1e-10:
            smooth_l1 = 0.0
        if abs(smooth_l2) < 1e-10:
            smooth_l2 = 0.0

        out[i] = smooth_l2

    return out


def main():
    n = int(FS * DURATION)
    t = np.arange(n) / FS
    sine = np.sin(2.0 * np.pi * FREQ * t) * 0.8   # 0.8 amplitude (not full-scale)

    print(f"Input:  {FREQ} Hz sine, {DURATION}s, amplitude 0.8")
    print(f"Filter: PWM carrier {22050} Hz, {int(LEVELS)} levels, alpha={ALPHA}")
    print("Processing...", end=" ", flush=True)
    filtered = simulate(sine)
    print("done")

    # Write stereo interleaved 16-bit LE PCM (both channels identical)
    out_path = "apple2_440hz.raw"
    pcm = np.clip(filtered * 32767.0, -32768, 32767).astype(np.int16)
    stereo = np.column_stack([pcm, pcm]).flatten()
    stereo.tofile(out_path)
    print(f"Written: {out_path}  ({len(stereo)*2} bytes, stereo 16-bit LE @ {FS} Hz)")

    # Also write unprocessed sine for comparison
    raw_path = "raw_440hz.raw"
    raw_pcm = np.clip(sine * 32767.0, -32768, 32767).astype(np.int16)
    raw_stereo = np.column_stack([raw_pcm, raw_pcm]).flatten()
    raw_stereo.tofile(raw_path)
    print(f"Written: {raw_path}  (unprocessed reference)")

    print()
    print("=== Filter diagnostics ===")
    print(f"Peak output amplitude : {np.max(np.abs(filtered)):.4f}")
    print(f"RMS output            : {np.sqrt(np.mean(filtered**2)):.4f}")
    print(f"RMS input             : {np.sqrt(np.mean(sine**2)):.4f}")
    # Rough SNR estimate: signal power vs residual after removing fundamental
    from numpy.fft import rfft, rfftfreq
    freqs = rfftfreq(n, 1.0/FS)
    fft_out = np.abs(rfft(filtered))
    fund_idx = np.argmin(np.abs(freqs - FREQ))
    # sum power at fundamental ±3 bins
    sig_bins = slice(max(0, fund_idx-3), fund_idx+4)
    sig_power = np.sum(fft_out[sig_bins]**2)
    total_power = np.sum(fft_out**2)
    noise_power = total_power - sig_power
    if noise_power > 0:
        snr = 10 * np.log10(sig_power / noise_power)
        print(f"Approx SNR            : {snr:.1f} dB")
    print()
    print("Run: python3 scripts/analyze_audio.py apple2_440hz.raw")
    print("Run: python3 scripts/analyze_audio.py raw_440hz.raw")


if __name__ == "__main__":
    main()

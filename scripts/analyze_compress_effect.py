#!/usr/bin/env python3
"""
DynamicsCompressor + --retro pc 조합의 주파수 분석.

측정 항목:
  1. 입력 레벨별 (−6 ~ −24 dBFS) S/N 비율 변화: compressor 없음 vs 4개 preset
  2. 각 preset의 입출력 레벨 대응표 (compressor 단독 정상 상태)
  3. 440 Hz @ −18 dBFS 기준 스펙트럼 비교 (no-compress vs gentle vs aggressive)

모든 시뮬레이션은 Java 구현의 정확한 Python 미러.
"""

import numpy as np
from numpy.fft import rfft, rfftfreq

# ── PC speaker (OneBitHardwareFilter) 파라미터 ────────────────────────────────
FS         = 44100
OVERSAMPLE = 4
IRATE      = FS * OVERSAMPLE   # 176400
CARRIER    = 15200.0
LEVELS     = 78.0
TAU_M      = 37.9e-6
TAU_E      = 10.0e-6
AM         = 1.0 - np.exp(-1.0 / (IRATE * TAU_M))   # ≈ 0.1395
AE         = 1.0 - np.exp(-1.0 / (IRATE * TAU_E))   # ≈ 0.433
STEP       = CARRIER / IRATE
PRE        = 128
DRIVE      = 2.0
INVD       = 1.0 / DRIVE

# ── DynamicsCompressor preset 파라미터 ───────────────────────────────────────
PRESETS = {
    'none':       None,
    'soft':       dict(thresh_db=-3.0,  ratio=10.0, attack_ms=5.0,  release_ms=50.0,  knee_db=6.0, makeup_db=0.0),
    'gentle':     dict(thresh_db=-18.0, ratio=2.0,  attack_ms=50.0, release_ms=300.0, knee_db=6.0, makeup_db=3.0),
    'moderate':   dict(thresh_db=-18.0, ratio=4.0,  attack_ms=30.0, release_ms=200.0, knee_db=4.0, makeup_db=6.0),
    'aggressive': dict(thresh_db=-24.0, ratio=8.0,  attack_ms=20.0, release_ms=150.0, knee_db=3.0, makeup_db=9.0),
}


def compress(signal, p):
    """DynamicsCompressor (mono) — 완전히 Java 미러."""
    if p is None:
        return signal.copy()
    thresh_db  = p['thresh_db']
    inv_ratio  = 1.0 / p['ratio']
    att_coeff  = 1.0 - np.exp(-1.0 / (FS * p['attack_ms']  / 1000.0))
    rel_coeff  = 1.0 - np.exp(-1.0 / (FS * p['release_ms'] / 1000.0))
    half_knee  = p['knee_db'] / 2.0
    makeup     = 10.0 ** (p['makeup_db'] / 20.0)

    out      = np.zeros_like(signal)
    level_env = 0.0
    for i, x in enumerate(signal):
        peak = abs(x)
        coeff = att_coeff if peak > level_env else rel_coeff
        level_env += coeff * (peak - level_env)
        if level_env < 1e-10:
            gain = makeup
        else:
            ldb  = 20.0 * np.log10(level_env)
            over = ldb - thresh_db
            if over <= -half_knee:
                gr_db = 0.0
            elif over >= half_knee:
                gr_db = (inv_ratio - 1.0) * over
            else:
                t = over + half_knee
                gr_db = (inv_ratio - 1.0) * t * t / (4.0 * half_knee)
            gain = 10.0 ** (gr_db / 20.0) * makeup
        out[i] = x * gain
    return out


def pc_speaker(signal):
    """OneBitHardwareFilter (--retro pc) — 완전히 Java 미러."""
    n   = len(signal)
    out = np.zeros(n)
    ph  = pre = s1 = s2 = s3 = s4 = s5 = s6 = 0.0
    for i in range(n):
        x = max(-1., min(1., signal[i] * DRIVE))
        x = round(x * PRE) / PRE
        if abs(x) < 1e-4:
            for _ in range(OVERSAMPLE):
                pre += AE*(0.-pre); s1+=AM*(pre-s1); s2+=AM*(s1-s2)
                s3+=AM*(s2-s3); s4+=AM*(s3-s4); s5+=AM*(s4-s5); s6+=AM*(s5-s6)
                ph = (ph+STEP)%1.
            out[i] = s6*INVD; continue
        duty = round(max(0., min(1., (x+1.)*0.5)) * LEVELS) / LEVELS
        for _ in range(OVERSAMPLE):
            b = 1. if ph < duty else -1.
            pre+=AE*(b-pre); s1+=AM*(pre-s1); s2+=AM*(s1-s2)
            s3+=AM*(s2-s3); s4+=AM*(s3-s4); s5+=AM*(s4-s5); s6+=AM*(s5-s6)
            ph = (ph+STEP)%1.
        out[i] = s6*INVD
    return out


def spectrum(signal, nfft=2**17):
    seg = signal[:nfft] * np.hanning(nfft)
    sp  = np.abs(rfft(seg, n=nfft))
    fr  = rfftfreq(nfft, 1./FS)
    peak = sp.max()
    return fr, 20*np.log10(sp / peak + 1e-12)


def level_at(fr, sp_db, freq, bw=30):
    m = (fr >= freq-bw) & (fr <= freq+bw)
    return np.max(sp_db[m]) if np.any(m) else -120.0


def snr_at_440(fr, sp_db):
    """기본파(440 Hz) 대 사이드밴드(1560/2000/2440 Hz) 평균 차이."""
    fund = level_at(fr, sp_db, 440)
    sbs  = [level_at(fr, sp_db, f, bw=20) for f in [1560, 2000, 2440]]
    return fund, np.mean(sbs), fund - np.mean(sbs)


# ═══════════════════════════════════════════════════════════════════════════════
# 1. 입력 레벨별 S/N 변화
# ═══════════════════════════════════════════════════════════════════════════════
print("=" * 70)
print(" 분석 1 — 입력 레벨 × preset : 사이드밴드 S/N 비율")
print("=" * 70)
print(f"{'입력 dBFS':>10}  {'preset':>10}  {'기본파':>8}  {'SB 평균':>8}  {'S/N':>8}")
print("-" * 55)

INPUT_LEVELS_DB = [-6, -12, -18, -24]
N = int(FS * 4)
t = np.arange(N) / FS

for amp_db in INPUT_LEVELS_DB:
    amp   = 10 ** (amp_db / 20.0)
    sine  = np.sin(2*np.pi*440*t) * amp
    for name, p in PRESETS.items():
        sig  = compress(sine, p)
        sig  = pc_speaker(sig)
        fr, sp = spectrum(sig)
        fund, sb_avg, snr = snr_at_440(fr, sp)
        print(f"  {amp_db:+5d} dBFS  {name:>10}  {fund:+7.1f}  {sb_avg:+7.1f}  {snr:+7.1f} dB")
    print()

# ═══════════════════════════════════════════════════════════════════════════════
# 2. compressor 단독 — 입출력 레벨 대응표 (정상 상태)
# ═══════════════════════════════════════════════════════════════════════════════
print("=" * 70)
print(" 분석 2 — compressor 정상 상태 입출력 레벨 대응")
print("  (compressor만, PC speaker 없음 / 3초 warm-up 후 측정)")
print("=" * 70)
print(f"{'입력 dBFS':>10}", end="")
for name in list(PRESETS.keys())[1:]:   # none 제외
    print(f"  {name:>11}", end="")
print()
print("-" * 60)

N_LONG = int(FS * 5)
t_long = np.arange(N_LONG) / FS
WARMUP = int(FS * 3)   # 3초 warm-up

for amp_db in [-3, -6, -9, -12, -15, -18, -21, -24]:
    amp  = 10 ** (amp_db / 20.0)
    sine = np.sin(2*np.pi*440*t_long) * amp
    print(f"  {amp_db:+5d} dBFS", end="")
    for name, p in list(PRESETS.items())[1:]:
        out   = compress(sine, p)
        rms   = np.sqrt(np.mean(out[WARMUP:]**2))
        out_db = 20*np.log10(rms + 1e-12)
        print(f"  {out_db:+10.1f}", end="")
    print()

# ═══════════════════════════════════════════════════════════════════════════════
# 3. 스펙트럼 비교 — 440 Hz @ −18 dBFS
# ═══════════════════════════════════════════════════════════════════════════════
print()
print("=" * 70)
print(" 분석 3 — 440 Hz @ −18 dBFS 스펙트럼 비교 (주요 주파수)")
print("=" * 70)

AMP_18 = 10 ** (-18.0 / 20.0)
sine18 = np.sin(2*np.pi*440*t) * AMP_18

key_freqs = [
    (440,  "440 Hz 기본파"),
    (880,  "880 Hz (2차 하모닉)"),
    (1320, "1320 Hz (3차)"),
    (1560, "1560 Hz (캐리어 SB k=31)"),
    (1760, "1760 Hz (4차 하모닉)"),
    (2000, "2000 Hz (캐리어 SB k=30)"),
    (2200, "2200 Hz (5차 하모닉)"),
    (2440, "2440 Hz (캐리어 SB k=29)"),
]

print(f"{'주파수':>22}", end="")
for name in PRESETS:
    print(f"  {name:>10}", end="")
print()
print("-" * 75)

spectra = {}
for name, p in PRESETS.items():
    sig = compress(sine18, p)
    sig = pc_speaker(sig)
    fr, sp = spectrum(sig)
    spectra[name] = (fr, sp)

for freq, label in key_freqs:
    print(f"  {label:<20}", end="")
    for name in PRESETS:
        fr, sp = spectra[name]
        lv = level_at(fr, sp, freq, bw=25)
        print(f"  {lv:+9.1f}", end="")
    print()

print()
print("  ※ 레벨은 각 preset의 출력 peak에 대한 상대값 (dB rel peak)")
print()
print("  S/N 요약 (기본파 − 사이드밴드 평균):")
for name in PRESETS:
    fr, sp = spectra[name]
    fund, sb_avg, snr = snr_at_440(fr, sp)
    print(f"    {name:>10} : {snr:+.1f} dB")

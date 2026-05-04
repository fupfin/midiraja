#!/usr/bin/env python3
"""
gen_opm_bank_v5.py — Multi-bank seeded DE with ensemble mel reference for OPM GM bank.

Key changes from v4
-------------------
- All available WOPN banks (+ any OPMGM .bin banks) are loaded as the initial DE
  population, giving DE a diverse gene pool across banks.
- Fitness reference changed from single primary-bank OPM render to an ENSEMBLE
  log-mel spectrogram: every bank's patch is rendered through OPM (with ALG forced
  to the primary bank's ALG) and the log-mel spectrograms are averaged.  This
  removes the single-bank bias that previously penalised crossbred individuals that
  sounded better than gm.wopn but different from it.
- Modulator MUL bounds widened from ±1 to [0, 15] (full OPM range).  Carrier
  MUL and DT1 remain locked to the primary bank (gm.wopn) to prevent pitch drift.
- ALG is still locked from the primary bank.  Banks with a different ALG still
  contribute FB/PMS/AMS and modulator parameters as seeds; their ensemble render
  uses the primary ALG and is skipped if the result is inaudible.
- New --banks argument: comma-separated list of WOPN / OPMGM-bin paths.
  Defaults to all *.wopn files under ext/libOPNMIDI/fm_banks/ plus
  src/main/resources/opm/opm_gm_x16.bin if present.

Usage
-----
  python3 scripts/gen_opm_bank_v5.py [options]

  --wopn PATH         Primary WOPN bank (default: ext/libOPNMIDI/fm_banks/gm.wopn)
  --banks LIST        Comma-separated additional bank paths; may include *.wopn
                      and *.bin (OPMGM format). Default: all banks in fm_banks/
                      directory plus opm_gm_x16.bin if present.
  --out PATH          Output .bin path (default: ext/opm_gm_bank/opm_gm.bin)
  --programs LIST     Comma-separated program numbers or ranges  (default: 0-127)
  --percussion LIST   Comma-separated percussion note numbers    (default: none)
  --maxiter N         DE max iterations per patch               (default: 500)
  --popsize N         DE population size multiplier             (default: 15)
  --patience N        Early-stop stagnant generation limit      (default: 40)
  --min-delta F       Minimum fitness improvement for patience  (default: 5e-5)
  --sample-rate HZ    Render sample rate                        (default: 44100)
  --notes LIST        MIDI note numbers to evaluate             (default: 48,69,72)

Output
------
  ext/opm_gm_bank/opm_gm.bin   — binary bank (OPMGM v1 format)
  ext/opm_gm_bank/opm_gm.json  — human-readable JSON export
"""

import argparse
import glob
import json
import math
import multiprocessing
import os
import struct
import subprocess
import sys
import tempfile
import time
from concurrent.futures import ProcessPoolExecutor, as_completed

import numpy as np
from scipy.optimize import differential_evolution
from scipy.fft import fft as scipy_fft
from scipy.fft import dct as scipy_dct

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import opm_render


# ── Constants ──────────────────────────────────────────────────────────────────

SAMPLE_RATE  = 44_100
SUSTAIN_SEC  = 1.0
RELEASE_SEC  = 0.5
EVAL_NOTES   = [48, 69, 72]  # C3, A4, C5

# Fitness weights
ALPHA = 0.45  # log mel-spectral distance from OPM ensemble reference
BETA  = 0.40  # log mel-spectral distance from FluidSynth PCM reference
GAMMA = 0.30  # envelope similarity penalty (frac500 difference)
DELTA = 0.20  # level penalty: soft threshold ±3 dB, normalised to [0,1] at ±33 dB
# ALPHA guides DE toward FM-achievable sounds; BETA pulls toward natural GM instrument shape
# GAMMA penalises envelope mismatch (OPM decaying too fast vs PCM reference)
# DELTA penalises absolute level mismatch so DE converges to correct TL without post-correction

# Mel-spectrogram params
N_MELS       = 40
N_MFCC       = 13
FRAME_LEN    = 2048
HOP_LEN      = 512
MEL_FMIN     = 20.0
MEL_FMAX     = 8000.0
N_RMS_FRAMES = 64

# OPMGM binary layout
OPM_MAGIC   = b'OPMGM-BNK\x00'
OPM_VERSION = 0x01
PATCH_BYTES = 52
N_PROGRAMS  = 128
N_PERC      = 128

# Pitch-stability constants
_PITCH_DRIFT_CLIP_CENTS = 50.0
_MIN_AUDIBLE_RMS = 0.001
_AUDIBILITY_PENALTY = 5.0

# Global bounds: FB (0-7), PMS (0-7), AMS (0-3)
_BOUNDS_GLOBAL = [(0, 7), (0, 7), (0, 3)]

# Category-based DT2 upper bounds per GM program
# Brass 56-71: DT2 up to 2 (metallic/brass effect); Bells 8-15: DT2 up to 2 (bell shimmer)
# Others: DT2 up to 1 (mild detuning allowed)
_BRASS_PROGS = frozenset(range(56, 72))
_BELLS_PROGS = frozenset(range(8, 16))


def _dt2_max_for_program(prog: int) -> int:
    if prog in _BRASS_PROGS or prog in _BELLS_PROGS:
        return 2
    return 1


# ── PCM envelope extraction ────────────────────────────────────────────────────

def _ms_to_ym2151_rate(time_ms: float) -> int:
    """Convert an envelope time in milliseconds to a YM2151 rate (AR/D1R/D2R, 0-31)."""
    if time_ms <= 0.0:
        return 31
    # Empirical: rate=31 ≈ 1.5ms; each rate step roughly doubles the time
    rate = 31.0 - 2.0 * math.log2(max(time_ms / 1.5, 1e-9))
    return max(0, min(31, int(round(rate))))


def _ms_to_rr(time_ms: float) -> int:
    """Convert release time in milliseconds to a YM2151 RR value (0-15)."""
    # rate=15 ≈ 6ms; each step doubles
    rr = 15.0 - 2.0 * math.log2(max(time_ms / 6.0, 1e-9))
    return max(0, min(15, int(round(rr))))


def _extract_envelope_params(pcm: np.ndarray, sample_rate: int,
                              sustain_sec: float) -> dict | None:
    """
    Estimate YM2151 carrier envelope parameters from PCM.

    Returns dict with keys: ar, d1r, d1l, d2r, rr, tl
    Returns None if the signal is inaudible.
    """
    FRAME_MS = 5.0
    frame_len = max(1, int(sample_rate * FRAME_MS / 1000))
    n_frames = len(pcm) // frame_len
    if n_frames < 4:
        return None

    rms = np.array([
        float(np.sqrt(np.mean(pcm[i * frame_len:(i + 1) * frame_len] ** 2)))
        for i in range(n_frames)
    ])

    peak_rms = float(rms.max())
    if peak_rms < _MIN_AUDIBLE_RMS:
        return None

    peak_idx = int(np.argmax(rms))
    note_off_frame = int(sustain_sec * 1000.0 / FRAME_MS)

    # Attack time = elapsed frames to reach peak
    attack_ms = (peak_idx + 0.5) * FRAME_MS
    ar = _ms_to_ym2151_rate(attack_ms)

    # Sustain level measurement.
    # Fast-decay instruments (bass, plucked strings) drop to near-zero before the 1s window
    # ends.  Measuring sustain from the last 25% gives a noise-floor reading → D1L too high.
    # Detect fast-decay by checking RMS at 60% of note_off and use a 150-350 ms post-peak
    # window instead.
    sus_end = min(note_off_frame, n_frames)
    early_check_frame = max(peak_idx + 1, int(note_off_frame * 0.60))
    early_check_frame = min(early_check_frame, n_frames - 1)
    is_fast_decay = (float(rms[early_check_frame]) < peak_rms * 0.20)
    if is_fast_decay:
        fd_start = peak_idx + int(150.0 / FRAME_MS)
        fd_end   = peak_idx + int(350.0 / FRAME_MS)
        fd_end   = min(fd_end, note_off_frame, n_frames)
        fd_start = max(peak_idx + 1, min(fd_start, fd_end - 1))
        if fd_start < fd_end:
            sustain_rms = float(np.median(rms[fd_start:fd_end]))
        else:
            sustain_rms = peak_rms * 0.3
    else:
        sus_start = max(peak_idx, sus_end - max(1, (sus_end - peak_idx) // 4))
        if sus_start < sus_end:
            sustain_rms = float(np.median(rms[sus_start:sus_end]))
        else:
            sustain_rms = peak_rms * 0.3

    # D1L: logarithmic dB threshold (~3 dB per step).
    # D1L=0 means no drop; D1L=15 means ~45 dB down from peak.
    ratio = max(0.0, min(1.0, sustain_rms / (peak_rms + 1e-9)))
    db_drop = -20.0 * math.log10(max(ratio, 1e-9))
    d1l = max(0, min(15, int(round(db_drop / 3.0))))

    # D1R: time to decay from peak down to sustain level
    decay_frames = rms[peak_idx:sus_end]
    target_level = max(sustain_rms * 1.05, peak_rms * 0.05)
    reach = len(decay_frames)
    for j, v in enumerate(decay_frames):
        if v <= target_level:
            reach = j
            break
    d1r = _ms_to_ym2151_rate(max(FRAME_MS, reach * FRAME_MS))

    # D2R: slow decay in the last 300ms of sustain phase
    window_frames = int(300.0 / FRAME_MS)
    seg_end = min(note_off_frame, n_frames)
    seg_start = max(peak_idx, seg_end - window_frames)
    seg = rms[seg_start:seg_end]
    if len(seg) > 4 and float(seg[0]) > 1e-6 and float(seg[-1]) > 1e-7:
        half_life_ms = ((len(seg) * FRAME_MS) * math.log(2.0) /
                        max(math.log(float(seg[0]) / float(seg[-1])), 1e-9))
        d2r = _ms_to_ym2151_rate(half_life_ms * 2.0)
    else:
        d2r = 4

    # Release time = frames after note-off to drop to 10% of note-off level
    if note_off_frame < n_frames - 2:
        rel = rms[min(note_off_frame, n_frames - 1):]
        if len(rel) > 2 and float(rel[0]) > 1e-6:
            target_rel = float(rel[0]) * 0.1
            reach_rel = len(rel)
            for j, v in enumerate(rel):
                if v <= target_rel:
                    reach_rel = j
                    break
            release_ms = max(5.0, reach_rel * FRAME_MS)
        else:
            release_ms = 50.0
    else:
        release_ms = 100.0
    rr = _ms_to_rr(release_ms)

    # TL: 0 = loudest; estimate from peak RMS (−40dB reference → TL≈0)
    if peak_rms > 1e-9:
        tl = max(0, min(63, int(round(-40.0 * math.log10(peak_rms)))))
    else:
        tl = 63

    return {
        'ar':  max(1,  min(31, ar)),
        'd1r': max(0,  min(31, d1r)),
        'd1l': max(0,  min(15, d1l)),
        'd2r': max(0,  min(31, d2r)),
        'rr':  max(1,  min(15, rr)),
        'tl':  max(0,  min(127, tl)),
    }


# ── WOPN parser ────────────────────────────────────────────────────────────────

def _u16le(data, off): return struct.unpack_from('<H', data, off)[0]
def _u16be(data, off): return struct.unpack_from('>H', data, off)[0]
def _s16be(data, off): return struct.unpack_from('>h', data, off)[0]


def load_wopn(path: str) -> dict:
    """Parse a WOPN2 file → dict with melodic[128], percussion[128], lfo_freq."""
    with open(path, 'rb') as f:
        data = f.read()
    magic = data[:11]
    if magic not in (b'WOPN2-B2NK\x00', b'WOPN2-BANK\x00'):
        raise ValueError(f"Invalid WOPN magic: {magic!r}")
    version   = _u16le(data, 11)
    count_mel  = _u16be(data, 13)
    count_perc = _u16be(data, 15)
    lfo_freq   = data[17] & 0xFF
    HEADER_SIZE = 18
    INST_SIZE   = 69
    BANK_META   = 34
    bank_meta_bytes = BANK_META * (count_mel + count_perc) if version >= 2 else 0
    mel_off  = HEADER_SIZE + bank_meta_bytes
    perc_off = mel_off + INST_SIZE * 128 * count_mel

    def parse_bank(offset):
        patches = []
        for i in range(128):
            off = offset + i * INST_SIZE
            name  = data[off:off+32].split(b'\x00')[0].decode('latin1', errors='replace')
            note_offset = _s16be(data, off + 32)
            perc_key    = data[off + 34] & 0xFF
            fbalg       = data[off + 35] & 0xFF
            lfosens     = data[off + 36] & 0xFF
            ops = []
            for l in range(4):
                op_off  = off + 37 + l * 7
                dtfm    = data[op_off]     & 0xFF
                level   = data[op_off + 1] & 0xFF
                rsatk   = data[op_off + 2] & 0xFF
                amdecay1= data[op_off + 3] & 0xFF
                decay2  = data[op_off + 4] & 0xFF
                susrel  = data[op_off + 5] & 0xFF
                ops.append({
                    'DT1': (dtfm >> 4) & 0x07, 'MUL': dtfm & 0x0F,
                    'TL':  level & 0x7F,
                    'KS':  (rsatk >> 6) & 0x03, 'AR': rsatk & 0x1F,
                    'AM':  (amdecay1 >> 7) & 0x01, 'D1R': amdecay1 & 0x1F,
                    'DT2': 0, 'D2R': decay2 & 0x1F,
                    'D1L': (susrel >> 4) & 0x0F, 'RR': susrel & 0x0F,
                })
            alg = fbalg & 0x07
            fb  = (fbalg >> 3) & 0x07
            pms = (lfosens >> 4) & 0x07
            ams = lfosens & 0x03
            patches.append({
                'name': name, 'note_offset': note_offset, 'perc_key': perc_key,
                'ALG': alg, 'FB': fb, 'PMS': pms, 'AMS': ams,
                'operators': ops,
            })
        return patches

    return {
        'melodic':    parse_bank(mel_off),
        'percussion': parse_bank(perc_off) if count_perc > 0 else [None] * 128,
        'lfo_freq':   lfo_freq,
    }


# ── OPMGM binary → internal wopn-format ───────────────────────────────────────

def _opmgm_decode_patch(data: bytes, off: int) -> dict:
    """Decode one 52-byte OPMGM patch record to internal wopn_patch format."""
    name    = data[off:off+16].split(b'\x00')[0].decode('latin1', errors='replace')
    note_off_raw = data[off+16]
    note_offset  = note_off_raw - 256 if note_off_raw >= 128 else note_off_raw
    perc_key = data[off+17]
    fbalg    = data[off+18]
    lfosens  = data[off+19]
    alg = fbalg & 0x07
    fb  = (fbalg >> 3) & 0x07
    pms = (lfosens >> 4) & 0x07
    ams = lfosens & 0x03
    ops = []
    for l in range(4):
        o = off + 20 + l * 8
        dt1mul = data[o]
        tl     = data[o+1]
        ksatk  = data[o+2]
        amd1r  = data[o+3]
        dt2d2r = data[o+4]
        d1lrr  = data[o+5]
        ops.append({
            'DT1': (dt1mul >> 4) & 0x07, 'MUL': dt1mul & 0x0F,
            'TL':  tl & 0x7F,
            'KS':  (ksatk >> 6) & 0x03, 'AR': ksatk & 0x1F,
            'AM':  (amd1r >> 7) & 0x01,  'D1R': amd1r & 0x1F,
            'DT2': (dt2d2r >> 6) & 0x03, 'D2R': dt2d2r & 0x1F,
            'D1L': (d1lrr >> 4) & 0x0F,  'RR':  d1lrr & 0x0F,
        })
    return {
        'name': name, 'note_offset': note_offset, 'perc_key': perc_key,
        'ALG': alg, 'FB': fb, 'PMS': pms, 'AMS': ams,
        'operators': ops,
    }


def load_opmgm_as_patches(path: str) -> dict:
    """
    Load an OPMGM .bin file and return dict with melodic[128], percussion[128].
    Returns None if the file does not have the OPMGM magic.
    """
    with open(path, 'rb') as f:
        data = f.read()
    if data[:10] != OPM_MAGIC:
        return None
    version, n_mel, n_perc = struct.unpack_from('<BHH', data, 10)
    offset = 15
    melodic    = [_opmgm_decode_patch(data, offset + i * PATCH_BYTES) for i in range(n_mel)]
    percussion = [_opmgm_decode_patch(data, offset + n_mel * PATCH_BYTES + i * PATCH_BYTES)
                  for i in range(n_perc)]
    return {'melodic': melodic, 'percussion': percussion}


def _load_bank_file(path: str) -> dict | None:
    """Load either a WOPN or OPMGM bank file. Returns None on failure."""
    try:
        lower = path.lower()
        if lower.endswith('.wopn'):
            return load_wopn(path)
        if lower.endswith('.bin'):
            return load_opmgm_as_patches(path)
    except Exception as e:
        print(f"[warn] Could not load bank {path}: {e}", file=sys.stderr)
    return None


# ── Reference audio rendering (FluidSynth) ────────────────────────────────────

def render_reference_fluidsynth(sf3_path: str, program: int, note: int,
                                  sample_rate: int, is_perc: bool = False) -> np.ndarray:
    """Render GM program at MIDI note via FluidSynth. Returns float32 mono PCM."""
    channel = 9 if is_perc else 0

    def build_smf():
        tempo = 500_000
        ticks_per_q = 480

        def vlq(n):
            if n < 0x80:
                return bytes([n])
            result = bytearray()
            result.append(n & 0x7F)
            n >>= 7
            while n:
                result.insert(0, (n & 0x7F) | 0x80)
                n >>= 7
            return bytes(result)

        track = bytearray()
        track += bytes([0x00, 0xFF, 0x51, 0x03,
                        (tempo >> 16) & 0xFF, (tempo >> 8) & 0xFF, tempo & 0xFF])
        if not is_perc:
            track += bytes([0x00, 0xC0, program & 0x7F])
        vel = 100
        track += bytes([0x00, 0x90 | channel, note & 0x7F, vel])
        sustain_ticks = int(ticks_per_q * (SUSTAIN_SEC * 120 / 60))
        track += vlq(sustain_ticks)
        track += bytes([0x80 | channel, note & 0x7F, 0])
        release_ticks = int(ticks_per_q * (RELEASE_SEC * 120 / 60))
        track += vlq(release_ticks)
        track += bytes([0xFF, 0x2F, 0x00])
        header = struct.pack('>4sIHHH', b'MThd', 6, 0, 1, ticks_per_q)
        track_data = struct.pack('>4sI', b'MTrk', len(track)) + bytes(track)
        return header + track_data

    smf_bytes = build_smf()
    with tempfile.NamedTemporaryFile(suffix='.mid', delete=False) as mf:
        mf.write(smf_bytes)
        mid_path = mf.name
    try:
        result = subprocess.run(
            ['fluidsynth', '-ni', '-g', '5.0', '-r', str(sample_rate),
             '-F', '-', '--audio-file-type=raw', '--audio-file-format=s16',
             sf3_path, mid_path],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        )
        if result.returncode != 0:
            raise RuntimeError(f"fluidsynth failed: {result.stderr.decode()[:500]}")
        raw = np.frombuffer(result.stdout, dtype=np.int16)
        if len(raw) % 2 == 1:
            raw = raw[:-1]
        left  = raw[0::2].astype(np.float32)
        right = raw[1::2].astype(np.float32)
        return (left + right) * 0.5 / 32767.0
    finally:
        os.unlink(mid_path)


# ── Feature extraction ─────────────────────────────────────────────────────────

def _hz_to_mel(hz):  return 2595.0 * np.log10(1 + hz / 700.0)
def _mel_to_hz(mel): return 700.0 * (10 ** (mel / 2595.0) - 1)


def _mel_filterbank(n_mels, n_fft, sample_rate, fmin, fmax):
    mel_min = _hz_to_mel(fmin)
    mel_max = _hz_to_mel(fmax)
    mel_points = np.linspace(mel_min, mel_max, n_mels + 2)
    hz_points  = _mel_to_hz(mel_points)
    bin_points = np.floor((n_fft + 1) * hz_points / sample_rate).astype(int)
    fb = np.zeros((n_mels, n_fft // 2 + 1))
    for m in range(1, n_mels + 1):
        f_m_minus = bin_points[m - 1]
        f_m       = bin_points[m]
        f_m_plus  = bin_points[m + 1]
        for k in range(f_m_minus, f_m):
            fb[m-1, k] = (k - bin_points[m-1]) / (bin_points[m] - bin_points[m-1] + 1e-9)
        for k in range(f_m, f_m_plus):
            fb[m-1, k] = (bin_points[m+1] - k) / (bin_points[m+1] - bin_points[m] + 1e-9)
    return fb


_MEL_FB = None


def _get_mel_fb(sample_rate):
    global _MEL_FB
    if _MEL_FB is None:
        _MEL_FB = _mel_filterbank(N_MELS, FRAME_LEN, sample_rate, MEL_FMIN, MEL_FMAX)
    return _MEL_FB


def _stft_magnitude(pcm, n_fft=FRAME_LEN, hop=HOP_LEN):
    win = np.hanning(n_fft)
    frames = []
    for start in range(0, len(pcm) - n_fft + 1, hop):
        frame = pcm[start:start + n_fft] * win
        spec  = np.abs(scipy_fft(frame, n=n_fft)[:n_fft // 2 + 1])
        frames.append(spec)
    if not frames:
        return np.zeros((n_fft // 2 + 1, 1))
    return np.stack(frames, axis=1)


def _env_frac_at_ms(pcm: np.ndarray, sample_rate: int, t_ms: float) -> float:
    """RMS in a 20ms window at t_ms, normalised to peak RMS."""
    win_half = int(sample_rate * 0.010)
    centre   = int(sample_rate * t_ms / 1000.0)
    lo = max(0, centre - win_half)
    hi = min(len(pcm), centre + win_half + 1)
    if hi <= lo:
        return 0.0
    window_rms = float(np.sqrt(np.mean(pcm[lo:hi] ** 2)))
    peak_frame  = max(1, int(sample_rate * 0.005))
    n_frames    = len(pcm) // peak_frame
    if n_frames == 0:
        return 0.0
    peak_rms = float(max(
        np.sqrt(np.mean(pcm[i * peak_frame:(i + 1) * peak_frame] ** 2))
        for i in range(n_frames)
    ))
    return window_rms / (peak_rms + 1e-9)


def extract_features(pcm: np.ndarray, sample_rate: int) -> dict:
    """Compute log mel-spectral envelope, RMS, harmonic richness, and envelope fractions."""
    mag = _stft_magnitude(pcm)
    fb  = _get_mel_fb(sample_rate)
    mel = fb @ mag
    log_mel = np.log(mel + 1e-9)
    mfcc = scipy_dct(log_mel, axis=0, norm='ortho')[:N_MFCC]
    mfcc_mean = mfcc.mean(axis=1, keepdims=True)
    log_mel_mean = log_mel.mean(axis=1)
    rms_raw = mag.std(axis=0)
    if len(rms_raw) >= N_RMS_FRAMES:
        step = max(1, len(rms_raw) // N_RMS_FRAMES)
        rms = np.array([rms_raw[i:i+step].mean()
                        for i in range(0, N_RMS_FRAMES*step, step)])[:N_RMS_FRAMES]
    else:
        rms = np.pad(rms_raw, (0, N_RMS_FRAMES - len(rms_raw)))
    log_mel_centred = log_mel_mean - log_mel_mean.mean()
    n_half = N_MELS // 2
    richness = float(log_mel_mean[n_half:].mean() - log_mel_mean[:n_half].mean())
    return {
        'mfcc':    (mfcc - mfcc_mean).mean(axis=1),
        'log_mel': log_mel_centred,
        'rms':     rms.astype(np.float32),
        'raw_rms': float(np.sqrt(np.mean(pcm ** 2))),
        'richness': richness,
        'frac500': _env_frac_at_ms(pcm, sample_rate, 500.0),
    }


def _cos_sim(a, b):
    return float(np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b) + 1e-12))


def _l2_dist(a, b):
    return float(np.linalg.norm(a - b))


# Carrier masks per ALG (index = operator index 0-3; True = carrier)
_CARRIER_MASK = [
    [False, False, False, True],   # ALG 0
    [False, False, False, True],   # ALG 1
    [False, False, False, True],   # ALG 2
    [False, False, False, True],   # ALG 3
    [False, True,  False, True],   # ALG 4
    [False, True,  True,  True],   # ALG 5
    [False, True,  True,  True],   # ALG 6
    [True,  True,  True,  True],   # ALG 7
]


# ── Pitch stability ────────────────────────────────────────────────────────────

def _hps_f0(pcm, sample_rate, t_start, t_end, f_min, f_max, n_harmonics=5):
    i_start = int(t_start * sample_rate)
    i_end   = int(t_end   * sample_rate)
    frame   = pcm[i_start:i_end]
    if len(frame) < 512:
        return 0.0
    window   = np.hanning(len(frame))
    spectrum = np.abs(np.fft.rfft(frame * window))
    freqs    = np.fft.rfftfreq(len(frame), 1.0 / sample_rate)
    i_lo = int(np.searchsorted(freqs, f_min))
    i_hi = int(np.searchsorted(freqs, f_max))
    if i_hi <= i_lo:
        return 0.0
    candidate_freqs = freqs[i_lo:i_hi]
    hps = spectrum[i_lo:i_hi].copy().astype(np.float64)
    for h in range(2, n_harmonics + 1):
        hps *= np.interp(candidate_freqs * h, freqs, spectrum, left=0.0, right=0.0)
    if hps.max() == 0.0:
        return 0.0
    return float(candidate_freqs[int(np.argmax(hps))])


def compute_pitch_stability_penalty(pcm, note, sample_rate, sustain_sec):
    f_expected = 440.0 * 2.0 ** ((note - 69) / 12.0)
    f_min = max(f_expected * 0.7, 30.0)
    f_max = min(f_expected * 4.0, sample_rate * 0.5)
    f0_onset   = _hps_f0(pcm, sample_rate, 0.05, 0.20, f_min, f_max)
    mid        = sustain_sec * 0.5
    f0_sustain = _hps_f0(pcm, sample_rate, mid - 0.10, mid + 0.10, f_min, f_max)
    if f0_onset <= 0.0 or f0_sustain <= 0.0:
        return 0.0
    drift_cents = abs(1200.0 * np.log2(f0_sustain / f0_onset))
    return float(np.clip(drift_cents / _PITCH_DRIFT_CLIP_CENTS, 0.0, 1.0))


# ── Fitness ────────────────────────────────────────────────────────────────────

def compute_fitness(rendered_feats: dict, target_feats: dict) -> float:
    """Lower = better."""
    if rendered_feats['raw_rms'] < _MIN_AUDIBLE_RMS:
        return _AUDIBILITY_PENALTY
    ensemble_mel = target_feats['log_mel']
    spec_dist_norm = _l2_dist(rendered_feats['log_mel'], ensemble_mel) / (
        np.linalg.norm(ensemble_mel) + 1e-9)
    pcm_mel = target_feats.get('pcm_log_mel')
    if pcm_mel is not None:
        pcm_dist_norm = _l2_dist(rendered_feats['log_mel'], pcm_mel) / (
            np.linalg.norm(pcm_mel) + 1e-9)
    else:
        pcm_dist_norm = 0.0
    # Envelope penalty: penalise OPM that decays much faster than PCM at 500ms.
    # Only active when pcm frac500 is available and significant (sustained instruments).
    pcm_frac500 = target_feats.get('pcm_frac500', 0.0)
    opm_frac500 = rendered_feats.get('frac500', 0.0)
    if pcm_frac500 > 0.10:
        env_penalty = max(0.0, pcm_frac500 - opm_frac500)
    else:
        env_penalty = 0.0
    # Level penalty: soft ±3 dB dead zone, normalised so 33 dB error = 1.0.
    # Only active when pcm_rms target is available.
    pcm_rms = target_feats.get('pcm_rms')
    if pcm_rms is not None and pcm_rms > 1e-6:
        opm_rms = rendered_feats['raw_rms']
        if opm_rms > 1e-6:
            level_db_err = abs(20.0 * math.log10(opm_rms / pcm_rms))
            level_penalty = max(0.0, level_db_err - 3.0) / 30.0
        else:
            level_penalty = 1.0
    else:
        level_penalty = 0.0
    return (ALPHA * spec_dist_norm + BETA * pcm_dist_norm
            + GAMMA * env_penalty + DELTA * level_penalty)


# ── Parameter encoding ─────────────────────────────────────────────────────────

def params_to_patch(p, wopn_ops: list, alg: int) -> dict:
    """DE vector → opm_render patch dict.

    Vector layout (39 params):
      [FB, PMS, AMS,
       DT1_0, MUL_0, DT2_0, TL_0, AR_0, D1R_0, D1L_0, D2R_0, RR_0,
       ...repeated for ops 1-3...]
    KS and AM-EN are always locked from wopn_ops (not in DE vector).
    """
    p = [int(round(x)) for x in p]
    ops = []
    for l in range(4):
        # o = [DT1, MUL, DT2, TL, AR, D1R, D1L, D2R, RR]
        o  = p[3 + l * 9 : 3 + (l + 1) * 9]
        wp = wopn_ops[l]
        ops.append({
            'dt1mul': ((o[0] & 0x07) << 4) | (o[1] & 0x0F),
            'tl':      o[3] & 0x7F,
            'ksatk':  ((wp['KS'] & 0x03) << 6) | (o[4] & 0x1F),
            'amd1r':  ((wp['AM'] & 0x01) << 7) | (o[5] & 0x1F),
            'dt2d2r': ((o[2] & 0x03) << 6) | (o[7] & 0x1F),
            'd1lrr':  ((o[6] & 0x0F) << 4) | (o[8] & 0x0F),
        })
    return {
        'fbalg':   ((p[0] & 0x07) << 3) | (alg & 0x07),
        'lfosens': ((p[1] & 0x07) << 4) | (p[2] & 0x03),
        'operators': ops,
    }


def wopn_patch_to_params(wp: dict) -> list:
    """WOPN patch → DE parameter vector (39 params).

    Layout: [FB, PMS, AMS, DT1_0, MUL_0, DT2_0, TL_0, AR_0, D1R_0, D1L_0, D2R_0, RR_0, ...]
    """
    v = [wp['FB'], wp['PMS'], wp['AMS']]
    for op in wp['operators']:
        v += [op['DT1'], op['MUL'], op.get('DT2', 0),
              op['TL'], op['AR'], op['D1R'], op['D1L'], op['D2R'], op['RR']]
    return v  # 39 params total


def _wopn_to_render_patch(wp: dict, alg_override: int | None = None) -> dict:
    """Convert a wopn_patch dict to opm_render format, optionally overriding ALG."""
    alg = alg_override if alg_override is not None else wp['ALG']
    ops = []
    for op in wp['operators']:
        ops.append({
            'dt1mul': ((op['DT1'] & 0x07) << 4) | (op['MUL'] & 0x0F),
            'tl':      op['TL'] & 0x7F,
            'ksatk':  ((op['KS']  & 0x03) << 6) | (op['AR']  & 0x1F),
            'amd1r':  ((op['AM']  & 0x01) << 7) | (op['D1R'] & 0x1F),
            'dt2d2r': ((op.get('DT2', 0) & 0x03) << 6) | (op['D2R'] & 0x1F),
            'd1lrr':  ((op['D1L'] & 0x0F) << 4) | (op['RR'] & 0x0F),
        })
    return {
        'fbalg':    ((wp['FB'] & 0x07) << 3) | (alg & 0x07),
        'lfosens':  ((wp['PMS'] & 0x07) << 4) | (wp['AMS'] & 0x03),
        'operators': ops,
    }


def params_to_bank_patch(p, name: str, note_offset: int, perc_key: int,
                         wopn_ops: list, alg: int) -> dict:
    """DE vector → bank patch dict for serialisation."""
    p = [int(round(x)) for x in p]
    ops = []
    for l in range(4):
        o  = p[3 + l * 9 : 3 + (l + 1) * 9]  # DT1, MUL, DT2, TL, AR, D1R, D1L, D2R, RR
        wp = wopn_ops[l]
        tl = o[3] & 0x7F
        ops.append({
            'dt1mul': ((o[0] & 0x07) << 4) | (o[1] & 0x0F),
            'tl':      tl,
            'ksatk':  ((wp['KS'] & 0x03) << 6) | (o[4] & 0x1F),
            'amd1r':  ((wp['AM'] & 0x01) << 7) | (o[5] & 0x1F),
            'dt2d2r': ((o[2] & 0x03) << 6) | (o[7] & 0x1F),
            'd1lrr':  ((o[6] & 0x0F) << 4) | (o[8] & 0x0F),
            'ssgeg':   0,
        })
    return {
        'name':        name,
        'note_offset': note_offset,
        'perc_key':    perc_key,
        'fbalg':      ((p[0] & 0x07) << 3) | (alg & 0x07),
        'lfosens':    ((p[1] & 0x07) << 4) | (p[2] & 0x03),
        'operators':   ops,
    }


# ── Multi-bank initial population ─────────────────────────────────────────────

def build_multi_bank_init(primary_warm_params: list, wopn_ops: list, alg: int,
                          carriers: list, all_bank_patches: list,
                          bounds: list, pop_size: int, rng,
                          carrier_adsr_by_op: dict | None = None) -> np.ndarray:
    """
    Build a DE initial population seeded from multiple bank warm-starts.

    Per op (9 params: DT1, MUL, DT2, TL, AR, D1R, D1L, D2R, RR):
    - Carrier DT1/MUL/DT2: locked to primary bank (pitch stability).
    - Carrier TL/ADSR: locked to PCM-extracted values if available, else primary bank.
    - Modulator DT1/MUL/DT2: taken from each seed bank (diversity).
    - Modulator TL/ADSR: locked to primary bank (timbre consistency).
    """
    # Build the "overrides" dict: param_index → locked_value
    overrides = {}
    for op_idx, (op, is_carrier) in enumerate(zip(wopn_ops, carriers)):
        base = 3 + op_idx * 9
        if is_carrier:
            # DT1/MUL/DT2: from primary bank
            overrides[base]     = op['DT1']
            overrides[base + 1] = op['MUL']
            overrides[base + 2] = op.get('DT2', 0)
            # TL: PCM-derived if available, else primary bank
            overrides[base + 3] = (carrier_adsr_by_op[op_idx]['tl']
                                   if carrier_adsr_by_op and op_idx in carrier_adsr_by_op
                                   else op['TL'])
            # ADSR: always from primary bank (gm.wopn)
            overrides[base + 4] = op['AR']
            overrides[base + 5] = op['D1R']
            overrides[base + 6] = op['D1L']
            overrides[base + 7] = op['D2R']
            overrides[base + 8] = op['RR']
        else:
            # Modulator TL/ADSR: locked from primary bank
            overrides[base + 3] = op['TL']
            overrides[base + 4] = op['AR']
            overrides[base + 5] = op['D1R']
            overrides[base + 6] = op['D1L']
            overrides[base + 7] = op['D2R']
            overrides[base + 8] = op['RR']

    def patch_to_clipped_seed(wp):
        params = wopn_patch_to_params(wp)
        for idx, val in overrides.items():
            if idx < len(params):
                params[idx] = val
        return [float(np.clip(params[i], bounds[i][0], bounds[i][1]))
                for i in range(len(bounds))]

    # Primary seed: reconstruct a wopn_patch dict from primary_warm_params
    primary_wp = {
        'FB': primary_warm_params[0], 'PMS': primary_warm_params[1],
        'AMS': primary_warm_params[2], 'ALG': alg,
        'operators': [
            {**wopn_ops[l],
             'DT1': primary_warm_params[3 + l * 9],
             'MUL': primary_warm_params[3 + l * 9 + 1],
             'DT2': primary_warm_params[3 + l * 9 + 2],
             'TL':  primary_warm_params[3 + l * 9 + 3],
             'AR':  primary_warm_params[3 + l * 9 + 4],
             'D1R': primary_warm_params[3 + l * 9 + 5],
             'D1L': primary_warm_params[3 + l * 9 + 6],
             'D2R': primary_warm_params[3 + l * 9 + 7],
             'RR':  primary_warm_params[3 + l * 9 + 8]}
            for l in range(4)
        ],
    }
    seeds = [patch_to_clipped_seed(primary_wp)]

    for wp in all_bank_patches:
        if wp is None:
            continue
        try:
            seeds.append(patch_to_clipped_seed(wp))
        except Exception:
            continue

    init = np.array(seeds, dtype=float)
    n_seeds = len(seeds)

    if n_seeds < pop_size:
        mean_seed = init.mean(axis=0)
        perturb = rng.integers(-8, 9, size=(pop_size - n_seeds, len(bounds)))
        rest = np.clip(
            mean_seed[None, :] + perturb.astype(float),
            [b[0] for b in bounds],
            [b[1] for b in bounds]
        )
        init = np.vstack([init, rest])

    return init[:pop_size]


# ── OPMGM I/O ─────────────────────────────────────────────────────────────────

def _encode_patch(bp: dict) -> bytes:
    name_b = bp['name'].encode('latin1', errors='replace')[:16].ljust(16, b'\x00')
    header = struct.pack('16sBBBB', name_b,
                         bp['note_offset'] & 0xFF,
                         bp['perc_key']    & 0xFF,
                         bp['fbalg']       & 0xFF,
                         bp['lfosens']     & 0xFF)
    ops_b = bytearray()
    for op in bp['operators']:
        ops_b += struct.pack('8B',
                             op['dt1mul'] & 0xFF, op['tl']     & 0xFF,
                             op['ksatk']  & 0xFF, op['amd1r']  & 0xFF,
                             op['dt2d2r'] & 0xFF, op['d1lrr']  & 0xFF,
                             op.get('ssgeg', 0)   & 0xFF, 0)
    return header + bytes(ops_b)


def write_opm_bank(melodic: list, percussion: list, path: str) -> None:
    os.makedirs(os.path.dirname(path) or '.', exist_ok=True)
    with open(path, 'wb') as f:
        f.write(OPM_MAGIC)
        f.write(struct.pack('<BHH', OPM_VERSION, N_PROGRAMS, N_PERC))
        for bp in melodic:
            f.write(_encode_patch(bp))
        for bp in percussion:
            f.write(_encode_patch(bp))


def write_json(melodic: list, percussion: list, path: str) -> None:
    os.makedirs(os.path.dirname(path) or '.', exist_ok=True)
    with open(path, 'w') as f:
        json.dump({'melodic': melodic, 'percussion': percussion}, f, indent=2)


def _dummy_patch(name='', note_offset=0, perc_key=0) -> dict:
    op = {'dt1mul': 0x01, 'tl': 127, 'ksatk': 0x1F, 'amd1r': 0x00,
          'dt2d2r': 0x00, 'd1lrr': 0xFF, 'ssgeg': 0}
    return {'name': name, 'note_offset': note_offset, 'perc_key': perc_key,
            'fbalg': 0x07, 'lfosens': 0, 'operators': [dict(op) for _ in range(4)]}


def load_existing_bank(path: str):
    """Load existing OPMGM .bin → (melodic, perc) or (None, None)."""
    if not os.path.isfile(path):
        return None, None
    try:
        with open(path, 'rb') as f:
            data = f.read()
        if data[:10] != OPM_MAGIC:
            return None, None
        _, n_mel, n_perc = struct.unpack_from('<BHH', data, 10)
        offset = 15

        def decode_patch(off):
            name_b = data[off:off+16].split(b'\x00')[0].decode('latin1', errors='replace')
            r = data[off+16]
            ops = []
            for l in range(4):
                o = off + 20 + l * 8
                ops.append({'dt1mul': data[o], 'tl': data[o+1], 'ksatk': data[o+2],
                            'amd1r': data[o+3], 'dt2d2r': data[o+4], 'd1lrr': data[o+5],
                            'ssgeg': data[o+6]})
            return {'name': name_b, 'note_offset': r - 256 if r >= 128 else r,
                    'perc_key': data[off+17], 'fbalg': data[off+18], 'lfosens': data[off+19],
                    'operators': ops}

        melodic    = [decode_patch(offset + i * PATCH_BYTES) for i in range(n_mel)]
        percussion = [decode_patch(offset + n_mel * PATCH_BYTES + i * PATCH_BYTES)
                      for i in range(n_perc)]
        return melodic, percussion
    except Exception as e:
        print(f"[warn] Could not load existing bank: {e}", file=sys.stderr)
        return None, None


# ── DE optimiser ───────────────────────────────────────────────────────────────

def optimise_patch(target_feats_per_note: list, warm_params: list,
                   wopn_patch: dict, all_bank_patches: list,
                   notes: list, sample_rate: int,
                   maxiter: int, popsize: int,
                   patience: int = 40, min_delta: float = 5e-5,
                   verbose: bool = False, prefix: str = '',
                   dt2_max: int = 1,
                   carrier_adsr_by_op: dict | None = None) -> tuple:
    """
    Run Differential Evolution with a multi-bank initial population.

    warm_params         : primary bank parameter vector (39 params, seed for primary individual)
    wopn_patch          : primary WOPN patch (ALG locked from here; KS/AM-EN locked from here)
    all_bank_patches    : wopn_patch dicts from all other banks (modulator DT1/MUL/DT2 seeds)
    carrier_adsr_by_op  : {op_idx: {ar,d1r,d1l,d2r,rr,tl}} extracted from PCM; if None, falls
                          back to primary bank values
    Returns (best_param_vector, gen_log).
    """
    wopn_ops = wopn_patch['operators']
    alg      = wopn_patch['ALG']
    carriers = _CARRIER_MASK[alg & 0x07]

    # Per-op bounds (9 params: DT1, MUL, DT2, TL, AR, D1R, D1L, D2R, RR):
    #
    # Carrier:
    #   DT1/MUL/DT2 — tight ±0.4 around primary bank (pitch stability)
    #   TL/ADSR     — ±range around PCM-extracted values (or primary bank fallback)
    #
    # Modulator:
    #   DT1 [0,7], MUL [0,15], DT2 [0,dt2_max] — free (diversity from multi-bank seeds)
    #   TL/ADSR — tight ±0.4 around primary bank (timbre consistency, effectively locked)
    op_bounds      = []
    op_integrality = []
    for op_idx, (op, is_carrier) in enumerate(zip(wopn_ops, carriers)):
        dt2 = op.get('DT2', 0)
        if is_carrier:
            # DT1, MUL, DT2: narrow float range around primary
            op_bounds += [(op['DT1'] - 0.4, op['DT1'] + 0.4),
                          (op['MUL'] - 0.4, op['MUL'] + 0.4),
                          (dt2 - 0.4, dt2 + 0.4)]
            op_integrality += [False, False, False]
            # TL/ADSR: PCM-derived bounds
            tl = (carrier_adsr_by_op[op_idx]['tl']
                  if carrier_adsr_by_op and op_idx in carrier_adsr_by_op
                  else op['TL'])
            ar, d1r, d1l, d2r, rr = op['AR'], op['D1R'], op['D1L'], op['D2R'], op['RR']
            _ar_lo  = max(1,  ar  - 4)
            _ar_hi  = max(_ar_lo, min(31, ar  + 2))
            _d1l_lo = max(0, d1l - 3)
            _d1l_hi = min(15, max(_d1l_lo, d1l + 3))
            _d2r_lo = max(0, d2r - 8)
            _d2r_hi = min(31, max(_d2r_lo, d2r + 3))
            op_bounds += [
                (max(0,  tl  - 8), min(127, tl  + 8)),
                (_ar_lo,  _ar_hi),
                (max(0,  d1r - 4), min(31,  d1r + 4)),
                (_d1l_lo, _d1l_hi),
                (_d2r_lo, _d2r_hi),
                (max(1,  rr  - 3), min(15,  rr  + 3)),
            ]
            op_integrality += [True, True, True, True, True, True]
        else:
            # DT1, MUL free; DT2 locked to 0 for modulators (DT2 on modulators
            # causes inharmonic FM sidebands → pitch instability)
            # MUL capped at 6: MUL 7-15 creates metallic/bell sidebands harsh on GM melodic voices
            op_bounds += [(0, 5), (0, 6), (0, 0)]
            op_integrality += [True, True, True]
            # TL/ADSR: free around primary bank — gm.wopn calibrated for OPN2;
            # give DE room to find OPM-appropriate modulation depth
            tl, ar, d1r = op['TL'], op['AR'], op['D1R']
            d1l, d2r, rr = op['D1L'], op['D2R'], op['RR']
            _tl_lo = max(15, tl - 5)
            _tl_hi = max(_tl_lo, min(127, tl + 5))
            op_bounds += [
                (_tl_lo, _tl_hi),
                (max(1,  ar  - 4),  min(31,  ar  + 4)),
                (max(0,  d1r - 4),  min(31,  d1r + 4)),
                (max(0,  d1l - 3),  min(15,  d1l + 3)),
                (d2r, d2r),  # D2R locked to gm.wopn for sustain stability
                (max(1,  rr  - 3),  min(15,  rr  + 3)),
            ]
            op_integrality += [True, True, True, True, True, True]

    bounds = _BOUNDS_GLOBAL + op_bounds
    integrality = np.array([True] * len(_BOUNDS_GLOBAL) + op_integrality)

    call_count = [0]
    best_f      = [float('inf')]

    def fitness(p):
        call_count[0] += 1
        patch = params_to_patch(p, wopn_ops, alg)
        total = 0.0
        for note, tgt in zip(notes, target_feats_per_note):
            try:
                pcm   = opm_render.render_patch(patch, note, sample_rate,
                                                SUSTAIN_SEC, RELEASE_SEC)
                feats = extract_features(pcm, sample_rate)
                total += compute_fitness(feats, tgt)
            except Exception:
                total += 2.0
        val = total / len(notes)
        if val < best_f[0]:
            best_f[0] = val
        return val

    gen_best   = [float('inf')]
    no_improve = [0]
    gen_count  = [0]
    gen_log    = []

    def _early_stop_callback(intermediate_result):
        gen_count[0] += 1
        current = best_f[0]
        gen_log.append((gen_count[0], current))
        if verbose:
            improve = gen_best[0] - current
            print(f"{prefix}  gen {gen_count[0]:4d}  best={current:.6f}  "
                  f"delta={improve:+.2e}  stagnant={no_improve[0]}")
        if gen_best[0] - current >= min_delta:
            gen_best[0]   = current
            no_improve[0] = 0
        else:
            no_improve[0] += 1
        if no_improve[0] >= patience:
            print(f"{prefix}  [early stop] gen {gen_count[0]}: "
                  f"no improvement for {patience} gens (best={current:.6f})", flush=True)
            return True
        return False

    rng = np.random.default_rng(42)
    pop_size = popsize * len(bounds)
    init = build_multi_bank_init(warm_params, wopn_ops, alg, carriers,
                                 all_bank_patches, bounds, pop_size, rng,
                                 carrier_adsr_by_op=carrier_adsr_by_op)

    n_seeds_used = min(len(all_bank_patches) + 1, pop_size)
    pcm_carriers = len(carrier_adsr_by_op) if carrier_adsr_by_op else 0
    if verbose:
        print(f"{prefix}  init pop={pop_size}  bank_seeds={n_seeds_used}"
              f"  mod_MUL_range=[0,8]  dt2_max={dt2_max}  alg={alg}"
              f"  pcm_carriers={pcm_carriers}", flush=True)

    result = differential_evolution(
        fitness,
        bounds=bounds,
        integrality=integrality,
        maxiter=maxiter,
        popsize=popsize,
        init=init,
        tol=0,
        mutation=(0.5, 1.0),
        recombination=0.7,
        seed=42,
        workers=1,
        updating='immediate',
        polish=False,
        callback=_early_stop_callback,
    )
    return [int(round(x)) for x in result.x], gen_log


# ── Program-level parallel workers ────────────────────────────────────────────

def _process_one_program(prog, sf3_path, primary_patch, all_bank_patches,
                         notes, sample_rate, maxiter, popsize, patience, min_delta):
    """Optimise one melodic program.

    primary_patch     : wopn_patch from primary bank (gm.wopn; sets ALG, KS/AM-EN, modulator ref)
    all_bank_patches  : list of wopn_patch dicts for this program from all other banks
    """
    t0 = time.time()
    wopn_ops    = primary_patch['operators']
    alg         = primary_patch['ALG']
    carriers    = _CARRIER_MASK[alg & 0x07]
    warm_params = wopn_patch_to_params(primary_patch)
    warm_patch_dict = _wopn_to_render_patch(primary_patch)
    all_patches_for_ensemble = [primary_patch] + all_bank_patches

    # ── Extract carrier ADSR/TL from FluidSynth PCM ───────────────────────────
    pcm_adsr_list = []
    pcm_feats_by_note = {}
    for note in notes:
        try:
            ref_pcm = render_reference_fluidsynth(sf3_path, prog, note, sample_rate)
            extracted = _extract_envelope_params(ref_pcm, sample_rate, SUSTAIN_SEC)
            if extracted is not None:
                pcm_adsr_list.append(extracted)
            pcm_feats_by_note[note] = extract_features(ref_pcm, sample_rate)
        except Exception:
            pass

    if pcm_adsr_list:
        # Average extracted params across eval notes
        avg_adsr = {
            k: int(round(float(np.mean([d[k] for d in pcm_adsr_list]))))
            for k in pcm_adsr_list[0]
        }
        # Apply to every carrier op (all carriers share same PCM signal → same ADSR)
        carrier_adsr_by_op = {
            op_idx: avg_adsr
            for op_idx, is_c in enumerate(carriers) if is_c
        }
        print(f"[{prog:3d}] PCM ADSR: AR={avg_adsr['ar']} D1R={avg_adsr['d1r']}"
              f" D1L={avg_adsr['d1l']} D2R={avg_adsr['d2r']}"
              f" RR={avg_adsr['rr']} TL={avg_adsr['tl']}", flush=True)
    else:
        carrier_adsr_by_op = None
        print(f"[{prog:3d}] PCM extraction failed, falling back to primary bank ADSR",
              flush=True)

    # ── Build ensemble log-mel target ─────────────────────────────────────────
    target_feats = []
    for note in notes:
        note_log_mels = []
        for bp in all_patches_for_ensemble:
            try:
                render_dict = _wopn_to_render_patch(bp, alg_override=alg)
                pcm = opm_render.render_patch(render_dict, note, sample_rate,
                                              sustain_sec=1.0, release_sec=0.5)
                if np.sqrt(np.mean(pcm ** 2)) >= _MIN_AUDIBLE_RMS:
                    feats_i = extract_features(pcm, sample_rate)
                    note_log_mels.append(feats_i['log_mel'])
            except Exception:
                pass

        ref_pcm = opm_render.render_patch(warm_patch_dict, note, sample_rate,
                                          sustain_sec=1.0, release_sec=0.5)
        feats = extract_features(ref_pcm, sample_rate)
        if note_log_mels:
            feats['log_mel'] = np.mean(note_log_mels, axis=0)
        pcm_f = pcm_feats_by_note.get(note)
        feats['pcm_log_mel'] = pcm_f['log_mel'] if pcm_f is not None else None
        feats['pcm_frac500'] = pcm_f['frac500'] if pcm_f is not None else 0.0
        feats['pcm_rms']     = pcm_f['raw_rms'] if pcm_f is not None else None
        target_feats.append(feats)

    best_p, gen_log = optimise_patch(
        target_feats, warm_params, primary_patch, all_bank_patches,
        notes, sample_rate, maxiter, popsize, patience=patience, min_delta=min_delta,
        verbose=True, prefix=f"[{prog:3d} {primary_patch['name'][:12]:<12}]",
        dt2_max=_dt2_max_for_program(prog),
        carrier_adsr_by_op=carrier_adsr_by_op)

    # ── Post-optimisation level correction ────────────────────────────────────
    # DE optimises spectral shape but ignores absolute level.  If OPM renders
    # significantly louder or quieter than the FluidSynth PCM reference, adjust
    # carrier TL values uniformly.  Each OPM TL step ≈ 0.75 dB.
    try:
        best_render_dict = params_to_patch(best_p, wopn_ops, alg)
        opm_rms_vals = []
        pcm_rms_vals = []
        for note in notes:
            try:
                opm_pcm = opm_render.render_patch(best_render_dict, note, sample_rate,
                                                  SUSTAIN_SEC, RELEASE_SEC)
                opm_rms_vals.append(float(np.sqrt(np.mean(opm_pcm ** 2))))
            except Exception:
                pass
            if note in pcm_feats_by_note:
                pcm_rms_vals.append(pcm_feats_by_note[note]['raw_rms'])
        if opm_rms_vals and pcm_rms_vals:
            opm_rms_avg = float(np.mean(opm_rms_vals))
            pcm_rms_avg = float(np.mean(pcm_rms_vals))
            if opm_rms_avg > 1e-6 and pcm_rms_avg > 1e-6:
                db_diff = 20.0 * math.log10(pcm_rms_avg / opm_rms_avg)
                # clamp correction to ±30 dB (±40 TL steps)
                tl_adjust = max(-40, min(40, int(round(db_diff / 0.75))))
                opm_dbfs = 20.0 * math.log10(opm_rms_avg)
                pcm_dbfs = 20.0 * math.log10(pcm_rms_avg)
                print(f"[{prog:3d}] Level: OPM {opm_dbfs:+.1f} dBFS  "
                      f"PCM {pcm_dbfs:+.1f} dBFS  diff {db_diff:+.1f} dB", flush=True)
                if abs(tl_adjust) >= 1:
                    for op_idx, is_carrier in enumerate(carriers):
                        if is_carrier:
                            tl_idx = 3 + op_idx * 9 + 3
                            best_p[tl_idx] = max(0, min(127, best_p[tl_idx] - tl_adjust))
                    print(f"[{prog:3d}] TL corrected by {-tl_adjust:+d} steps "
                          f"({db_diff:+.1f} dB)", flush=True)
    except Exception as e:
        print(f"[{prog:3d}] Level correction skipped: {e}", flush=True)

    best_patch = params_to_bank_patch(best_p, primary_patch['name'],
                                      0, 0,
                                      wopn_ops, primary_patch['ALG'])
    return prog, best_patch, time.time() - t0, gen_log


_DEFAULT_PERC_OPS = [{'KS': 0, 'AR': 31, 'AM': 0, 'D1R': 5, 'D2R': 2, 'D1L': 8, 'RR': 7}
                     for _ in range(4)]


def _process_one_perc(perc_note, sf3_path, wopn_patch, all_bank_patches,
                      sample_rate, maxiter, popsize, patience, min_delta):
    """Optimise one percussion note in a worker process."""
    t0 = time.time()
    _DEFAULT_ALG = 4
    if wopn_patch is None:
        wopn_ops    = [dict(op, DT1=0, MUL=1) for op in _DEFAULT_PERC_OPS]
        fake_patch  = {'operators': wopn_ops, 'ALG': _DEFAULT_ALG,
                       'FB': 0, 'PMS': 0, 'AMS': 0}
        warm_params = wopn_patch_to_params(fake_patch)
        perc_key, name, note_offset = perc_note, '', 0
    else:
        fake_patch  = wopn_patch
        wopn_ops    = wopn_patch['operators']
        warm_params = wopn_patch_to_params(wopn_patch)
        perc_key    = wopn_patch['perc_key']
        name        = wopn_patch['name']
        note_offset = 0
    pcm = render_reference_fluidsynth(sf3_path, 0, perc_note, sample_rate, is_perc=True)
    target_feats = [extract_features(pcm, sample_rate)]
    best_p, gen_log = optimise_patch(
        target_feats, warm_params, fake_patch, all_bank_patches,
        [perc_note], sample_rate, maxiter, popsize,
        patience=patience, min_delta=min_delta, verbose=True)
    best_patch = params_to_bank_patch(best_p, name, note_offset, perc_key,
                                      wopn_ops, fake_patch['ALG'])
    return perc_note, best_patch, time.time() - t0, gen_log


# ── Main ───────────────────────────────────────────────────────────────────────

def parse_range_list(s: str) -> list:
    items = []
    for part in s.split(','):
        part = part.strip()
        if '-' in part:
            lo, hi = part.split('-', 1)
            items.extend(range(int(lo), int(hi) + 1))
        else:
            items.append(int(part))
    return sorted(set(items))


_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
_PROJECT_DIR = os.path.dirname(_SCRIPT_DIR)
_DEFAULT_FM_BANKS_DIR = os.path.join(_PROJECT_DIR, 'ext', 'libOPNMIDI', 'fm_banks')
_DEFAULT_X16_BIN = os.path.join(_PROJECT_DIR, 'src', 'main', 'resources', 'opm',
                                 'opm_gm_x16.bin')
_DEFAULT_OPM_RESOURCES_DIR = os.path.join(_PROJECT_DIR, 'src', 'main', 'resources', 'opm')


def _default_bank_paths(primary_path: str) -> list:
    """Return all WOPN banks in fm_banks/ plus all OPM .bin banks, excluding primary."""
    paths = []
    if os.path.isdir(_DEFAULT_FM_BANKS_DIR):
        paths += sorted(glob.glob(os.path.join(_DEFAULT_FM_BANKS_DIR, '*.wopn')))
    # Include all OPM .bin banks from resources — v3 has high modulator MUL (2-5)
    # which seeds richer timbres; x16 adds OPM-native diversity
    if os.path.isdir(_DEFAULT_OPM_RESOURCES_DIR):
        paths += sorted(glob.glob(os.path.join(_DEFAULT_OPM_RESOURCES_DIR, '*.bin')))
    primary_abs = os.path.abspath(primary_path)
    return [p for p in paths if os.path.abspath(p) != primary_abs]


def main():
    parser = argparse.ArgumentParser(description='Multi-bank seeded OPM GM bank via DE')
    parser.add_argument('--wopn',      default='ext/libOPNMIDI/fm_banks/gm.wopn',
                        help='Primary bank (.wopn or OPMGM .bin; sets ALG, KS/AM-EN, modulator ADSR reference)')
    parser.add_argument('--banks',     default='',
                        help='Comma-separated additional bank paths (.wopn or OPMGM .bin). '
                             'Defaults to all banks in ext/libOPNMIDI/fm_banks/ + opm_gm_x16.bin')
    parser.add_argument('--out',       default='ext/opm_gm_bank/opm_gm.bin')
    parser.add_argument('--programs',  default='0-127')
    parser.add_argument('--percussion',default='')
    parser.add_argument('--maxiter',   type=int,   default=500)
    parser.add_argument('--popsize',   type=int,   default=15)
    parser.add_argument('--patience',  type=int,   default=40)
    parser.add_argument('--min-delta', type=float, default=5e-5, dest='min_delta')
    parser.add_argument('--sample-rate', dest='sample_rate', type=int, default=SAMPLE_RATE)
    parser.add_argument('--notes',     default=','.join(str(n) for n in EVAL_NOTES))
    parser.add_argument('--sf3',       default='build/soundfonts/FluidR3_GM.sf3')
    args = parser.parse_args()

    if not os.path.isfile(args.wopn):
        sys.exit(f"Primary bank not found: {args.wopn}")

    notes      = [int(n) for n in args.notes.split(',')]
    programs   = parse_range_list(args.programs) if args.programs else []
    perc_notes = parse_range_list(args.percussion) if args.percussion else []

    print(f"Loading primary bank: {args.wopn}")
    primary_wopn = _load_bank_file(args.wopn)
    if primary_wopn is None:
        sys.exit(f"Could not parse primary bank: {args.wopn}")

    # Resolve additional banks
    if args.banks:
        extra_paths = [p.strip() for p in args.banks.split(',') if p.strip()]
    else:
        extra_paths = _default_bank_paths(args.wopn)

    extra_banks = []
    for path in extra_paths:
        bank = _load_bank_file(path)
        if bank is not None:
            extra_banks.append((os.path.basename(path), bank))
            print(f"  + loaded: {os.path.basename(path)}")
        else:
            print(f"  ! skipped (unrecognised format): {path}", file=sys.stderr)

    print(f"Total additional banks: {len(extra_banks)}")
    print(f"Evaluating notes: {notes}")
    print(f"Programs to optimise: {len(programs)}")
    print(f"Percussion notes to optimise: {len(perc_notes)}")

    json_path = args.out.replace('.bin', '.json')
    melodic_bank, perc_bank = load_existing_bank(args.out)
    if melodic_bank is None:
        melodic_bank = [_dummy_patch(primary_wopn['melodic'][i]['name'],
                                     primary_wopn['melodic'][i]['note_offset'])
                        for i in range(128)]
        perc_bank    = [_dummy_patch('', 0, i) for i in range(128)]

    n_workers = max(1, (os.cpu_count() or 4) - 1)
    print(f"Parallel workers: {n_workers}")

    ctx = multiprocessing.get_context('fork')

    if programs:
        with ProcessPoolExecutor(max_workers=n_workers, mp_context=ctx) as executor:
            futures = {}
            for prog in programs:
                primary_patch = primary_wopn['melodic'][prog]
                extra_patches = [bank['melodic'][prog]
                                 for _, bank in extra_banks
                                 if bank.get('melodic') and prog < len(bank['melodic'])]
                futures[executor.submit(
                    _process_one_program, prog, args.sf3,
                    primary_patch, extra_patches, notes, args.sample_rate,
                    args.maxiter, args.popsize, args.patience, args.min_delta
                )] = prog
            for future in as_completed(futures):
                prog, best_patch, elapsed, gen_log = future.result()
                melodic_bank[prog] = best_patch
                final_fitness = gen_log[-1][1] if gen_log else float('nan')
                print(f"\n[program {prog:3d}] {primary_wopn['melodic'][prog]['name']}"
                      f"  done in {elapsed:.1f}s"
                      f"  gens={len(gen_log)}  final_fitness={final_fitness:.6f}", flush=True)
                write_opm_bank(melodic_bank, perc_bank, args.out)
                write_json(melodic_bank, perc_bank, json_path)
                print(f"  checkpoint written → {args.out}", flush=True)

    if perc_notes:
        with ProcessPoolExecutor(max_workers=n_workers, mp_context=ctx) as executor:
            futures = {}
            for pn in perc_notes:
                primary_perc = primary_wopn['percussion'][pn]
                extra_patches = [bank['percussion'][pn]
                                 for _, bank in extra_banks
                                 if bank.get('percussion') and pn < len(bank['percussion'])]
                futures[executor.submit(
                    _process_one_perc, pn, args.sf3,
                    primary_perc, extra_patches, args.sample_rate,
                    args.maxiter, args.popsize, args.patience, args.min_delta
                )] = pn
            for future in as_completed(futures):
                pn, best_patch, elapsed, gen_log = future.result()
                perc_bank[pn] = best_patch
                name = (primary_wopn['percussion'][pn] or {}).get('name', '')
                final_fitness = gen_log[-1][1] if gen_log else float('nan')
                print(f"\n[perc note {pn:3d}] {name}  done in {elapsed:.1f}s"
                      f"  gens={len(gen_log)}  final_fitness={final_fitness:.6f}", flush=True)
                write_opm_bank(melodic_bank, perc_bank, args.out)
                write_json(melodic_bank, perc_bank, json_path)
                print(f"  checkpoint written → {args.out}", flush=True)

    print(f"\nDone. Bank written to: {args.out}")


if __name__ == '__main__':
    main()

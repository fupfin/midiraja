#!/usr/bin/env python3
"""
extract_drum_samples.py — Extract GM drum samples from FluidR3_GM.sf3.

Produces:
  src/main/resources/com/fupfin/midiraja/export/vgm/rf5c68_drum_{0-6}.bin
      RF5C68 sign-magnitude 8-bit PCM at RF5C68_SAMPLE_RATE (16 000 Hz)

  src/main/resources/com/fupfin/midiraja/export/vgm/msm6258_drum_{0-6}.bin
      OKI ADPCM 4-bit (packed, low-nibble-first) at MSM6258_SAMPLE_RATE (15 625 Hz)

Drum index → GM percussion note mapping (same as Rf5c68Handler / Msm6258Handler):
  0  Bass drum    GM 36
  1  Snare        GM 38
  2  Cymbal       GM 49
  3  Closed HH    GM 42
  4  Tom          GM 45
  5  Rim shot     GM 37
  6  Open HH      GM 46
"""

import os
import struct
import subprocess
import sys
import tempfile
import numpy as np
import scipy.signal

# ── Constants ────────────────────────────────────────────────────────────────

RF5C68_SAMPLE_RATE  = 16_000
MSM6258_SAMPLE_RATE = 15_625

# Output level relative to full-scale.  1.0 = maximum (peak-normalised); 0.5 = −6 dB.
# Drum samples peak-normalise to [-1,1] before encoding, which produces a very hot signal.
# Reducing to 0.5 brings them in line with the FM/PSG/wavetable synthesisers they accompany.
DRUM_GAIN = 0.5

# Drum index → (slot, GM note, name, max_duration_secs)
# max_duration limits RF5C68 wave RAM usage (64 KB total for 7 samples).
# Padded 256-byte boundaries: sum must stay well under 65,534 bytes.
DRUM_SLOTS = [
    (0, 36, "bass_drum",  0.30),   # ~4 800 bytes
    (1, 38, "snare",      0.30),   # ~4 800 bytes
    (2, 49, "cymbal",     1.50),   # ~24 000 bytes  (longest crash)
    (3, 42, "closed_hh",  0.20),   # ~3 200 bytes
    (4, 45, "tom",        0.40),   # ~6 400 bytes
    (5, 37, "rim_shot",   0.25),   # ~4 000 bytes
    (6, 46, "open_hh",    0.60),   # ~9 600 bytes
    # Padded totals: ≈ 56 800 bytes < 65 534 (RF5C68 wave RAM)
]

# ── SF3 / SF2 RIFF parser ────────────────────────────────────────────────────

def read_u16(data, off): return struct.unpack_from('<H', data, off)[0]
def read_u32(data, off): return struct.unpack_from('<I', data, off)[0]
def read_i16(data, off): return struct.unpack_from('<h', data, off)[0]

def parse_riff_chunks(data, base=0, limit=None):
    """Yield (tag, offset, size) for each RIFF chunk in data[base:]."""
    end = len(data) if limit is None else base + limit
    pos = base
    while pos + 8 <= end:
        tag = data[pos:pos+4].decode('latin1')
        size = read_u32(data, pos + 4)
        yield tag, pos + 8, size
        pos += 8 + size + (size & 1)  # word-align

def parse_sf3(path):
    """Return (smpl_data, phdr, pbag, pgen, inst, ibag, igen, shdr) from SF3."""
    with open(path, 'rb') as f:
        raw = f.read()

    # Outer RIFF "sfbk"
    assert raw[:4] == b'RIFF', "Not a RIFF file"
    assert raw[8:12] == b'sfbk', "Not an SF2/SF3 file"

    sdta_data = None
    pdta_data = None

    for tag, off, size in parse_riff_chunks(raw, 12):
        if tag == 'LIST':
            list_id = raw[off:off+4].decode('latin1')
            if list_id == 'sdta':
                sdta_data = (raw, off + 4, size - 4)
            elif list_id == 'pdta':
                pdta_data = (raw, off + 4, size - 4)

    assert sdta_data and pdta_data, "Missing sdta or pdta LIST chunks"

    # Extract smpl chunk from sdta
    smpl_bytes = None
    for tag, off, size in parse_riff_chunks(*sdta_data):
        if tag == 'smpl':
            smpl_bytes = raw[off:off+size]
            break
    assert smpl_bytes is not None, "smpl chunk not found in sdta"

    # Extract all pdta sub-chunks
    chunks = {}
    for tag, off, size in parse_riff_chunks(*pdta_data):
        chunks[tag] = raw[off:off+size]

    def parse_fixed(data, record_size):
        return [data[i:i+record_size] for i in range(0, len(data), record_size)]

    phdr = parse_fixed(chunks['phdr'], 38)
    pbag = parse_fixed(chunks['pbag'], 4)
    pgen = parse_fixed(chunks['pgen'], 4)
    inst = parse_fixed(chunks['inst'], 22)
    ibag = parse_fixed(chunks['ibag'], 4)
    igen = parse_fixed(chunks['igen'], 4)
    shdr = parse_fixed(chunks['shdr'], 46)

    return smpl_bytes, phdr, pbag, pgen, inst, ibag, igen, shdr

def find_sample_id(midi_note, phdr, pbag, pgen, inst, ibag, igen):
    """
    Find sample ID for bank=128, preset=0 at the given MIDI key.
    Returns the shdr index or None.
    """
    # Find preset: bank=128, preset=0
    target_preset = None
    for ph in phdr:
        p_name = ph[:20].rstrip(b'\x00').decode('latin1')
        p_preset = read_u16(ph, 20)
        p_bank   = read_u16(ph, 22)
        p_bag    = read_u16(ph, 24)
        if p_bank == 128 and p_preset == 0:
            target_preset = ph
            break
    assert target_preset is not None, "bank=128 preset=0 not found in phdr"

    p_bag_start = read_u16(target_preset, 24)
    # Next preset's bag index is the limit
    # Find next preset in phdr list
    p_idx = phdr.index(target_preset)
    p_bag_end = read_u16(phdr[p_idx + 1], 24) if p_idx + 1 < len(phdr) else len(pbag)

    # Search preset bags for instrument zone containing our key
    for bi in range(p_bag_start, p_bag_end):
        gen_start = read_u16(pbag[bi], 0)
        gen_end   = read_u16(pbag[bi + 1], 0) if bi + 1 < len(pbag) else len(pgen)

        # Parse generators in this zone
        key_lo, key_hi, inst_id = 0, 127, None
        for gi in range(gen_start, gen_end):
            oper   = read_u16(pgen[gi], 0)
            amount = pgen[gi][2:4]
            if oper == 43:  # keyRange
                key_lo = amount[0]
                key_hi = amount[1]
            elif oper == 41:  # instrument
                inst_id = read_u16(pgen[gi], 2)

        if inst_id is None or not (key_lo <= midi_note <= key_hi):
            continue

        # Found instrument — search ibag for sample containing our key
        i_rec = inst[inst_id]
        i_bag_start = read_u16(i_rec, 20)
        i_bag_end   = read_u16(inst[inst_id + 1], 20) if inst_id + 1 < len(inst) else len(ibag)

        best_sample_id = None
        best_range = 128  # prefer narrowest key range

        for ibi in range(i_bag_start, i_bag_end):
            igen_start = read_u16(ibag[ibi], 0)
            igen_end   = read_u16(ibag[ibi + 1], 0) if ibi + 1 < len(ibag) else len(igen)

            ik_lo, ik_hi, sample_id = 0, 127, None
            for igi in range(igen_start, igen_end):
                oper   = read_u16(igen[igi], 0)
                amount = igen[igi][2:4]
                if oper == 43:  # keyRange
                    ik_lo = amount[0]
                    ik_hi = amount[1]
                elif oper == 53:  # sampleID
                    sample_id = read_u16(igen[igi], 2)

            if sample_id is not None and ik_lo <= midi_note <= ik_hi:
                span = ik_hi - ik_lo
                if span < best_range:
                    best_range = span
                    best_sample_id = sample_id

        if best_sample_id is not None:
            return best_sample_id

    return None

# ── Audio decoding ────────────────────────────────────────────────────────────

def decode_ogg(ogg_bytes):
    """Decode Ogg Vorbis bytes → mono float32 array at source sample rate.
    Returns (samples_float32, sample_rate)."""
    with tempfile.NamedTemporaryFile(suffix='.ogg', delete=False) as f:
        f.write(ogg_bytes)
        tmp_path = f.name

    try:
        result = subprocess.run(
            ['ffmpeg', '-y', '-i', tmp_path,
             '-f', 'f32le', '-ac', '1', '-ar', '0', '-'],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE)

        if result.returncode != 0:
            raise RuntimeError(f"ffmpeg failed: {result.stderr.decode()}")

        # Parse sample rate from stderr
        sr = None
        for line in result.stderr.decode().split('\n'):
            if 'Audio:' in line:
                for part in line.split(','):
                    part = part.strip()
                    if part.endswith(' Hz'):
                        try:
                            sr = int(part[:-3].strip())
                            break
                        except ValueError:
                            pass

        if sr is None:
            # Fallback: probe with ffprobe
            probe = subprocess.run(
                ['ffprobe', '-v', 'quiet', '-show_streams', '-select_streams', 'a',
                 '-show_entries', 'stream=sample_rate', '-of', 'default=nw=1', tmp_path],
                capture_output=True, text=True)
            for line in probe.stdout.split('\n'):
                if line.startswith('sample_rate='):
                    sr = int(line.split('=')[1].strip())
                    break

        assert sr is not None, "Could not determine sample rate"

        samples = np.frombuffer(result.stdout, dtype=np.float32)
        return samples, sr
    finally:
        os.unlink(tmp_path)

def resample_to(samples, src_rate, dst_rate):
    """Resample float32 array from src_rate to dst_rate using scipy."""
    if src_rate == dst_rate:
        return samples
    gcd = np.gcd(src_rate, dst_rate)
    up = dst_rate // gcd
    down = src_rate // gcd
    return scipy.signal.resample_poly(samples, up, down).astype(np.float32)

# ── Target format encoders ────────────────────────────────────────────────────

def encode_rf5c68(samples_f32):
    """Encode float32 PCM [-1, 1] to RF5C68 sign-magnitude bytes.

    Encoding: bit 7 = sign (1 = positive), bits 6–0 = magnitude.
    0x80 = silence (+0). 0xFF = end-of-sample marker (must not appear as audio).
    """
    # Normalise then apply output gain
    peak = np.max(np.abs(samples_f32))
    if peak > 0:
        samples_f32 = samples_f32 / peak * DRUM_GAIN

    out = bytearray(len(samples_f32))
    for i, s in enumerate(samples_f32):
        magnitude = int(abs(s) * 0x7E + 0.5)
        if s >= 0:
            b = 0x80 | min(magnitude, 0x7E)  # positive: bit7=1, max 0x7E
        else:
            b = min(magnitude, 0x7F)          # negative: bit7=0
        out[i] = b
    return bytes(out)

def lowpass_for_oki(samples_f32, sample_rate, cutoff_hz=4000, order=4):
    """Apply Butterworth low-pass filter before OKI ADPCM encoding.

    OKI ADPCM cannot faithfully reproduce broadband high-frequency content
    (hi-hats, cymbal splash, snare noise); attempting to encode it causes the
    ADPCM step-size to chase rapidly changing values and produces audible
    tearing artifacts.  Band-limiting to ~4 kHz removes energy the codec
    cannot handle while preserving the perceptible attack and body of each
    drum hit at the 15 625 Hz playback rate.
    """
    nyq = sample_rate / 2.0
    if cutoff_hz >= nyq:
        return samples_f32
    b, a = scipy.signal.butter(order, cutoff_hz / nyq, btype='low')
    return scipy.signal.lfilter(b, a, samples_f32).astype(np.float32)


def encode_oki_adpcm(samples_f32, sample_rate=MSM6258_SAMPLE_RATE):
    """Encode float32 PCM [-1, 1] to OKI ADPCM 4-bit packed bytes.

    Algorithm matches okim6258.c from libvgm/MAME.
    Output: packed, low nibble first then high nibble.
    12-bit input range [-2048, 2047].
    """
    samples_f32 = lowpass_for_oki(samples_f32, sample_rate)
    STEP_VAL = [int(16.0 * (1.1 ** i)) for i in range(49)]

    def build_diff_lookup():
        table = [0] * (49 * 16)
        for step in range(49):
            sv = STEP_VAL[step]
            for nibble in range(16):
                diff = sv >> 3
                if nibble & 1: diff += sv >> 2
                if nibble & 2: diff += sv >> 1
                if nibble & 4: diff += sv
                if nibble & 8: diff = -diff
                table[step * 16 + nibble] = diff
        return table

    INDEX_SHIFT = [-1, -1, -1, -1, 2, 4, 6, 8]
    DIFF_LOOKUP = build_diff_lookup()

    # Normalise then apply output gain, scale to 12-bit range
    peak = np.max(np.abs(samples_f32))
    if peak > 0:
        pcm12 = np.clip(samples_f32 / peak * DRUM_GAIN * 2047, -2048, 2047).astype(np.int32)
    else:
        pcm12 = np.zeros(len(samples_f32), dtype=np.int32)

    out = bytearray((len(pcm12) + 1) // 2)
    signal = -2
    step = 0
    out_idx = 0
    low_nibble = True

    for target in pcm12:
        # Find best nibble
        best_nibble = 0
        best_err = 10**9
        for n in range(16):
            diff = DIFF_LOOKUP[step * 16 + n]
            nxt = max(-2048, min(2047, ((diff << 8) + (signal * 245)) >> 8))
            err = abs(nxt - target)
            if err < best_err:
                best_err = err
                best_nibble = n

        if low_nibble:
            out[out_idx] = best_nibble & 0x0F
        else:
            out[out_idx] |= (best_nibble & 0x0F) << 4
            out_idx += 1
        low_nibble = not low_nibble

        diff = DIFF_LOOKUP[step * 16 + best_nibble]
        signal = max(-2048, min(2047, ((diff << 8) + (signal * 245)) >> 8))
        step = max(0, min(48, step + INDEX_SHIFT[best_nibble & 7]))

    return bytes(out)

# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    project_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

    sf3_path = os.path.join(project_root, 'build', 'soundfonts', 'FluidR3_GM.sf3')
    if not os.path.exists(sf3_path):
        print(f"ERROR: SF3 not found at {sf3_path}", file=sys.stderr)
        print("Run: ./gradlew downloadFluidR3Sf3", file=sys.stderr)
        sys.exit(1)

    out_dir = os.path.join(project_root, 'src', 'main', 'resources',
                           'com', 'fupfin', 'midiraja', 'export', 'vgm')
    os.makedirs(out_dir, exist_ok=True)

    print(f"Parsing {sf3_path} …")
    smpl_bytes, phdr, pbag, pgen, inst, ibag, igen, shdr = parse_sf3(sf3_path)
    print(f"  smpl chunk: {len(smpl_bytes):,} bytes, {len(shdr)} sample headers")

    for drum_idx, midi_note, drum_name, max_dur in DRUM_SLOTS:
        print(f"\n[{drum_idx}] {drum_name} (GM note {midi_note})")

        sample_id = find_sample_id(midi_note, phdr, pbag, pgen, inst, ibag, igen)
        if sample_id is None:
            print(f"  WARNING: no sample found — using silence fallback")
            for fmt in ('rf5c68', 'msm6258'):
                path = os.path.join(out_dir, f'{fmt}_drum_{drum_idx}.bin')
                with open(path, 'wb') as f:
                    f.write(b'\x80')  # one silent byte
            continue

        sh = shdr[sample_id]
        s_name  = sh[:20].rstrip(b'\x00').decode('latin1')
        s_start = read_u32(sh, 20)
        s_end   = read_u32(sh, 24)
        s_rate  = read_u32(sh, 36)
        s_type  = read_u16(sh, 44)
        print(f"  Sample #{sample_id}: '{s_name}' offset [{s_start}:{s_end}] {s_rate} Hz type={s_type:#x}")

        # Extract Ogg bytes
        ogg_data = smpl_bytes[s_start:s_end]
        print(f"  Ogg bytes: {len(ogg_data):,}")

        # Decode Ogg → mono float32
        mono, src_rate = decode_ogg(ogg_data)
        mono = mono.copy()  # ensure writable

        # Trim to max duration (prevents RF5C68 wave RAM overflow)
        max_samples = int(max_dur * src_rate)
        if len(mono) > max_samples:
            mono = mono[:max_samples]
        print(f"  Decoded: {len(mono)} samples @ {src_rate} Hz, peak={np.max(np.abs(mono)):.3f}")

        # Apply gentle fade-out in last 5% of sample to avoid click
        fade_len = max(1, len(mono) // 20)
        fade = np.linspace(1.0, 0.0, fade_len)
        mono[-fade_len:] *= fade

        # RF5C68: resample → 16 000 Hz, encode sign-magnitude
        rf_samples = resample_to(mono, src_rate, RF5C68_SAMPLE_RATE)
        rf_data = encode_rf5c68(rf_samples)
        rf_path = os.path.join(out_dir, f'rf5c68_drum_{drum_idx}.bin')
        with open(rf_path, 'wb') as f:
            f.write(rf_data)
        print(f"  RF5C68:  {len(rf_data):,} bytes → {rf_path}")

        # MSM6258: resample → 15 625 Hz, low-pass filter, encode OKI ADPCM
        msm_samples = resample_to(mono, src_rate, MSM6258_SAMPLE_RATE)
        msm_data = encode_oki_adpcm(msm_samples, MSM6258_SAMPLE_RATE)
        msm_path = os.path.join(out_dir, f'msm6258_drum_{drum_idx}.bin')
        with open(msm_path, 'wb') as f:
            f.write(msm_data)
        print(f"  MSM6258: {len(msm_data):,} bytes → {msm_path}")

    print("\nDone.")

if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""
Analyze SN76489 PSG behavior in a Genesis VGM file and its corresponding WAV output.
"""

import gzip
import struct
import wave
import numpy as np
from collections import Counter, defaultdict

# ─────────────────────────────────────────────────────────────────────────────
# 1. VGM Parsing
# ─────────────────────────────────────────────────────────────────────────────

def parse_vgm(path):
    with gzip.open(path, 'rb') as f:
        data = f.read()

    magic = data[0:4]
    assert magic == b'Vgm ', f"Bad magic: {magic!r}"

    # Data offset: header+0x34, relative to 0x34; 0 means use default 0x40
    data_offset_rel = struct.unpack_from('<I', data, 0x34)[0]
    if data_offset_rel == 0:
        data_start = 0x40
    else:
        data_start = 0x34 + data_offset_rel

    version = struct.unpack_from('<I', data, 0x08)[0]
    print(f"VGM version: {version:#010x}")
    print(f"Data starts at: {data_start:#06x}")

    # Walk the command stream
    pos = data_start
    sample_clock = 0  # accumulates in samples (44100/s)

    # Per-channel tracking
    # ch 0-2 = tone, ch 3 = noise
    events = defaultdict(list)  # ch -> list of (sample, vol)  when vol written
    note_events = defaultdict(list)  # ch -> list of (start_sample, end_sample, vol)

    # Current state per channel
    cur_vol = {0: 15, 1: 15, 2: 15, 3: 15}  # 15 = silent
    note_start = {0: None, 1: None, 2: None, 3: None}
    note_start_vol = {0: None, 1: None, 2: None, 3: None}

    total_psg_cmds = 0
    all_vol_writes = []   # (sample, ch, vol)

    while pos < len(data):
        cmd = data[pos]
        pos += 1

        if cmd == 0x66:  # End of stream
            break
        elif cmd == 0x50:  # SN76489 write
            byte = data[pos]; pos += 1
            total_psg_cmds += 1
            if byte & 0x80:  # latch byte
                ch = (byte >> 5) & 0x03
                typ = (byte >> 4) & 0x01  # 0=tone freq, 1=volume
                val = byte & 0x0F
                if typ == 1:  # volume write
                    vol = val
                    all_vol_writes.append((sample_clock, ch, vol))
                    events[ch].append((sample_clock, vol))

                    # Detect note start/end
                    prev_vol = cur_vol[ch]
                    if prev_vol == 15 and vol < 15:
                        # Note on
                        note_start[ch] = sample_clock
                        note_start_vol[ch] = vol
                    elif prev_vol < 15 and vol == 15:
                        # Note off
                        if note_start[ch] is not None:
                            dur = sample_clock - note_start[ch]
                            note_events[ch].append((note_start[ch], sample_clock, dur, note_start_vol[ch]))
                        note_start[ch] = None
                    cur_vol[ch] = vol
        elif cmd == 0x52 or cmd == 0x53:  # YM2612 port 0/1
            pos += 2
        elif cmd == 0x61:  # Wait N samples
            n = struct.unpack_from('<H', data, pos)[0]; pos += 2
            sample_clock += n
        elif cmd == 0x62:  # Wait 735 samples (1/60 sec)
            sample_clock += 735
        elif cmd == 0x63:  # Wait 882 samples (1/50 sec)
            sample_clock += 882
        elif 0x70 <= cmd <= 0x7F:  # Wait (n&F)+1 samples
            sample_clock += (cmd & 0x0F) + 1
        elif cmd == 0x54:  # YM2151
            pos += 2
        elif cmd == 0x55:  # YM2203
            pos += 2
        elif cmd == 0x56 or cmd == 0x57:  # YM2608
            pos += 2
        elif cmd == 0x58 or cmd == 0x59:  # YM2610
            pos += 2
        elif cmd == 0x5A:  # YM3812
            pos += 2
        elif cmd == 0x5B:  # YM3526
            pos += 2
        elif cmd == 0x5C:  # Y8950
            pos += 2
        elif cmd == 0x5D:  # YMZ280B
            pos += 2
        elif cmd == 0x5E or cmd == 0x5F:  # YMF262
            pos += 2
        elif cmd == 0x67:  # Data block
            assert data[pos] == 0x66; pos += 1
            btype = data[pos]; pos += 1
            bsize = struct.unpack_from('<I', data, pos)[0]; pos += 4
            pos += bsize
        elif cmd == 0xE0:  # PCM seek
            pos += 4
        elif cmd == 0x80:  # YM2612 DAC + wait 0
            pass
        elif 0x80 <= cmd <= 0x8F:  # YM2612 DAC + wait N
            pass
        elif cmd == 0x90 or cmd == 0x91 or cmd == 0x92 or cmd == 0x93 or cmd == 0x94 or cmd == 0x95:
            # DAC stream commands
            if cmd == 0x90: pos += 4
            elif cmd == 0x91: pos += 4
            elif cmd == 0x92: pos += 5
            elif cmd == 0x93: pos += 10
            elif cmd == 0x94: pos += 1
            elif cmd == 0x95: pos += 4
        else:
            # Unknown command — skip 1 byte as fallback
            # (could be extended commands; handle common ones)
            if cmd in (0xA0,):  # AY8910
                pos += 2
            elif cmd in (0xB0, 0xB1, 0xB2, 0xB3, 0xB4, 0xB5, 0xB6, 0xB7, 0xB8,
                         0xB9, 0xBA, 0xBB, 0xBC, 0xBD, 0xBE, 0xBF):
                pos += 2
            elif 0xC0 <= cmd <= 0xD5:
                pos += 3
            else:
                # Skip and hope for the best
                pass

    total_samples = sample_clock
    duration_sec = total_samples / 44100.0

    print(f"\n=== VGM Analysis ===")
    print(f"Total samples: {total_samples} ({duration_sec:.2f} sec)")
    print(f"Total PSG commands (SN76489 writes): {total_psg_cmds}")
    print(f"Total PSG volume writes: {len(all_vol_writes)}")

    for ch in range(4):
        ch_name = f"Tone ch{ch}" if ch < 3 else "Noise"
        vols = [v for _, v in events[ch]]
        if not vols:
            print(f"\n  {ch_name}: no volume writes")
            continue
        cnt = Counter(vols)
        total = len(vols)
        print(f"\n  {ch_name}: {total} volume writes")
        print(f"    Distribution (vol: count, %):")
        for v in sorted(cnt.keys()):
            pct = 100.0 * cnt[v] / total
            bar = '#' * int(pct / 2)
            print(f"      vol={v:2d} ({15-v:+3d} dB-ish): {cnt[v]:4d}  {pct:5.1f}%  {bar}")
        print(f"    Min vol value: {min(vols)} (loudest active)")
        print(f"    Max vol value: {max(vols)} ({15 if max(vols)==15 else 'not silent'})")
        silent_count = cnt.get(15, 0)
        active_count = total - silent_count
        print(f"    Active writes: {active_count}, Silent (15) writes: {silent_count}")

    # Note duration analysis
    print(f"\n=== Note Duration Analysis (tone ch 0-2) ===")
    all_durations = []
    for ch in range(3):
        durations = [d for _, _, d, _ in note_events[ch]]
        if not durations:
            print(f"  ch{ch}: no complete notes detected")
            continue
        all_durations.extend(durations)
        d_arr = np.array(durations)
        print(f"\n  ch{ch}: {len(durations)} notes")
        print(f"    Duration stats (in samples, 44100/s):")
        print(f"      Min: {d_arr.min()} ({d_arr.min()/44100*1000:.1f} ms)")
        print(f"      Max: {d_arr.max()} ({d_arr.max()/44100*1000:.1f} ms)")
        print(f"      Mean: {d_arr.mean():.1f} ({d_arr.mean()/44100*1000:.1f} ms)")
        print(f"      Median: {np.median(d_arr):.1f} ({np.median(d_arr)/44100*1000:.1f} ms)")
        # Histogram of durations
        buckets = [(0,441,'<10ms'), (441,2205,'10-50ms'), (2205,4410,'50-100ms'),
                   (4410,8820,'100-200ms'), (8820,22050,'200-500ms'), (22050,999999,'>500ms')]
        for lo, hi, label in buckets:
            cnt_b = np.sum((d_arr >= lo) & (d_arr < hi))
            if cnt_b > 0:
                print(f"      {label}: {cnt_b} notes ({100.0*cnt_b/len(durations):.1f}%)")

    # Fast envelope detection
    print(f"\n=== Fast Volume Envelope Detection ===")
    for ch in range(3):
        ch_events = sorted(events[ch])
        if len(ch_events) < 2:
            continue
        rapid_changes = []
        for i in range(1, len(ch_events)):
            dt = ch_events[i][0] - ch_events[i-1][0]
            dv = abs(ch_events[i][1] - ch_events[i-1][1])
            if dt <= 441 and dv >= 4:  # within 10ms, 4+ steps
                rapid_changes.append((ch_events[i-1][0], dt, ch_events[i-1][1], ch_events[i][1]))
        if rapid_changes:
            print(f"  ch{ch}: {len(rapid_changes)} rapid vol changes (within 10ms, >=4 steps)")
            # Show a few examples
            for s, dt, v1, v2 in rapid_changes[:5]:
                print(f"    @ sample {s} ({s/44100:.3f}s): vol {v1}→{v2} in {dt} samples ({dt/44100*1000:.1f}ms)")
        else:
            print(f"  ch{ch}: no rapid volume envelopes detected")

    # Check for envelope-style patterns (vol ramp 0→15 or 15→0 quickly)
    print(f"\n=== Vol Ramp Sequences ===")
    for ch in range(3):
        ch_events = sorted(events[ch])
        if len(ch_events) < 4:
            continue
        # Look for sequences of 4+ consecutive vol changes trending in one direction
        ramps = []
        i = 0
        while i < len(ch_events) - 3:
            # Check if next 4 writes form a monotone ramp
            window = ch_events[i:i+5]
            vols_w = [v for _, v in window]
            times_w = [t for t, _ in window]
            total_dt = times_w[-1] - times_w[0]
            if total_dt <= 2205:  # within 50ms
                if vols_w == sorted(vols_w) or vols_w == sorted(vols_w, reverse=True):
                    dv = abs(vols_w[-1] - vols_w[0])
                    if dv >= 4:
                        ramps.append((times_w[0], total_dt, vols_w[0], vols_w[-1]))
                        i += 5
                        continue
            i += 1
        if ramps:
            print(f"  ch{ch}: {len(ramps)} vol ramp sequences (5 steps, within 50ms, >=4 dB steps)")
            for s, dt, v1, v2 in ramps[:5]:
                print(f"    @ {s/44100:.3f}s: vol {v1}→{v2} over {dt} samples ({dt/44100*1000:.1f}ms)")
        else:
            print(f"  ch{ch}: no ramp sequences")

    return all_vol_writes, events, note_events, total_samples


# ─────────────────────────────────────────────────────────────────────────────
# 2. WAV Analysis
# ─────────────────────────────────────────────────────────────────────────────

def analyze_wav(path):
    with wave.open(path, 'rb') as wf:
        n_channels = wf.getnchannels()
        sampwidth = wf.getsampwidth()
        framerate = wf.getframerate()
        n_frames = wf.getnframes()
        raw = wf.readframes(n_frames)

    duration = n_frames / framerate
    print(f"\n=== WAV Analysis ===")
    print(f"Sample rate: {framerate} Hz")
    print(f"Channels: {n_channels}")
    print(f"Bit depth: {sampwidth*8} bit")
    print(f"Frames: {n_frames}")
    print(f"Duration: {duration:.3f} sec")

    # Decode samples
    if sampwidth == 2:
        samples = np.frombuffer(raw, dtype=np.int16).astype(np.float64) / 32768.0
    elif sampwidth == 4:
        samples = np.frombuffer(raw, dtype=np.int32).astype(np.float64) / 2147483648.0
    else:
        samples = np.frombuffer(raw, dtype=np.uint8).astype(np.float64) / 128.0 - 1.0

    if n_channels == 2:
        left = samples[0::2]
        right = samples[1::2]
        mono = (left + right) / 2.0
    else:
        mono = samples

    # Overall RMS
    rms = np.sqrt(np.mean(mono**2))
    rms_db = 20 * np.log10(rms + 1e-12)
    peak = np.max(np.abs(mono))
    peak_db = 20 * np.log10(peak + 1e-12)
    print(f"\nRMS level: {rms:.5f} ({rms_db:.1f} dBFS)")
    print(f"Peak level: {peak:.5f} ({peak_db:.1f} dBFS)")

    # Frequency analysis — FFT of full mono signal
    # Use a larger FFT for better frequency resolution
    fft_size = min(len(mono), 65536 * 4)
    # Use Hann window on overlapping segments and average
    hop = fft_size // 2
    n_segs = max(1, (len(mono) - fft_size) // hop)
    power_spectrum = np.zeros(fft_size // 2 + 1)
    for seg_i in range(min(n_segs, 20)):  # Average up to 20 segments
        start = seg_i * hop
        segment = mono[start:start + fft_size]
        if len(segment) < fft_size:
            break
        window = np.hanning(fft_size)
        spectrum = np.fft.rfft(segment * window)
        power_spectrum += np.abs(spectrum)**2
    power_spectrum /= min(n_segs, 20)
    freqs = np.fft.rfftfreq(fft_size, 1.0 / framerate)

    # Band energy analysis
    bands = [
        (20,   250,   "Sub-bass (20-250 Hz)"),
        (250,  800,   "Bass (250-800 Hz)"),
        (800,  2000,  "Midrange (800-2000 Hz)"),
        (2000, 4000,  "Upper-mid (2-4 kHz)  [PSG square harmonics]"),
        (4000, 8000,  "Presence (4-8 kHz)"),
        (8000, 20000, "Brilliance (8-20 kHz)"),
    ]
    print(f"\n=== Frequency Band Energy ===")
    total_power = np.sum(power_spectrum)
    for lo, hi, label in bands:
        mask = (freqs >= lo) & (freqs < hi)
        band_power = np.sum(power_spectrum[mask])
        band_db = 10 * np.log10(band_power / (total_power + 1e-30) + 1e-30)
        print(f"  {label}: {band_db:.1f} dBr ({100.0*band_power/total_power:.1f}%)")

    # Spectral centroid
    spec_centroid = np.sum(freqs * power_spectrum) / (np.sum(power_spectrum) + 1e-30)
    print(f"\nSpectral centroid: {spec_centroid:.1f} Hz")

    # PSG vs FM band estimate
    # PSG square waves have strong harmonics at odd multiples of fundamental
    # Typical PSG fundamental 200-4000 Hz → harmonics up to 8-12 kHz
    # FM tends to be stronger in bass/midrange
    psg_band_mask = (freqs >= 2000) & (freqs < 8000)
    fm_band_mask  = (freqs >= 60)   & (freqs < 2000)
    psg_power = np.sum(power_spectrum[psg_band_mask])
    fm_power  = np.sum(power_spectrum[fm_band_mask])
    ratio_db = 10 * np.log10((psg_power / (fm_power + 1e-30)) + 1e-30)
    print(f"\nPSG proxy band (2-8 kHz) power: {10*np.log10(psg_power/(total_power+1e-30)+1e-30):.1f} dBr")
    print(f"FM proxy band (60-2000 Hz) power: {10*np.log10(fm_power/(total_power+1e-30)+1e-30):.1f} dBr")
    print(f"PSG/FM ratio: {ratio_db:+.1f} dB  (positive → PSG louder in its bands)")

    # Time-varying RMS: split into 200ms chunks
    chunk_size = int(framerate * 0.2)
    chunks = [mono[i:i+chunk_size] for i in range(0, len(mono), chunk_size) if len(mono[i:i+chunk_size]) == chunk_size]
    chunk_rms = [np.sqrt(np.mean(c**2)) for c in chunks]
    chunk_rms_db = [20*np.log10(r+1e-12) for r in chunk_rms]
    print(f"\nRMS over time (200ms chunks):")
    print(f"  Min: {min(chunk_rms_db):.1f} dBFS")
    print(f"  Max: {max(chunk_rms_db):.1f} dBFS")
    print(f"  Median: {np.median(chunk_rms_db):.1f} dBFS")
    print(f"  Std: {np.std(chunk_rms_db):.1f} dB")

    # Find the top-10 spectral peaks (candidate PSG fundamentals)
    print(f"\nTop spectral peaks (candidate PSG/FM tones):")
    # Smooth and find local maxima
    ps_log = 10 * np.log10(power_spectrum + 1e-30)
    # Simple local max with min spacing
    min_spacing = int(50 / (freqs[1] - freqs[0])) if len(freqs) > 1 else 1  # 50 Hz spacing
    min_spacing = max(1, min_spacing)
    peaks_idx = []
    for i in range(1, len(ps_log) - 1):
        if ps_log[i] > ps_log[i-1] and ps_log[i] > ps_log[i+1]:
            peaks_idx.append(i)
    # Sort by power
    peaks_idx.sort(key=lambda i: -power_spectrum[i])
    for i in peaks_idx[:10]:
        print(f"  {freqs[i]:.1f} Hz: {ps_log[i]:.1f} dB")

    return rms_db, peak_db, spec_centroid


# ─────────────────────────────────────────────────────────────────────────────
# 3. Main
# ─────────────────────────────────────────────────────────────────────────────

if __name__ == '__main__':
    import sys
    base = '/Users/sungchulpark/Documents/projects/midiraja/samples'
    vgm_path = f'{base}/sonic_data_select.vgz'
    wav_path = f'{base}/sonic_data_select.wav'

    all_vol_writes, events, note_events, total_samples = parse_vgm(vgm_path)
    rms_db, peak_db, spec_centroid = analyze_wav(wav_path)

    # Summary / recommendation
    print("\n" + "="*60)
    print("SUMMARY & RECOMMENDATIONS")
    print("="*60)

    # Collect all active vol values (not 15=silent) for ch 0-2
    active_vols = []
    for ch in range(3):
        active_vols.extend([v for _, v in events[ch] if v < 15])
    if active_vols:
        cnt = Counter(active_vols)
        most_common_vol = cnt.most_common(1)[0][0]
        print(f"\nMost common active PSG vol value: {most_common_vol} (0=loudest, 15=silent)")
        print(f"  SN76489 attenuation at vol={most_common_vol}: {most_common_vol * 2.0:.1f} dB below max")

    # Note duration for staccato threshold
    all_durs = []
    for ch in range(3):
        all_durs.extend([d for _, _, d, _ in note_events[ch]])
    if all_durs:
        d_arr = np.array(all_durs)
        short_notes = np.sum(d_arr < 2205)  # <50ms
        print(f"\nNote durations: {len(d_arr)} notes total")
        print(f"  Short notes (<50ms): {short_notes} ({100.0*short_notes/len(d_arr):.1f}%)")
        print(f"  Median duration: {np.median(d_arr)/44100*1000:.1f} ms")
        recommended_threshold_ms = max(30, min(150, int(np.percentile(d_arr, 25) / 44100 * 1000)))
        print(f"\nRecommended staccato threshold: ~{recommended_threshold_ms} ms")
        print(f"  (notes shorter than this should be played staccato in MIDI)")

    print(f"\nWAV RMS: {rms_db:.1f} dBFS, Peak: {peak_db:.1f} dBFS")
    print(f"Spectral centroid: {spec_centroid:.1f} Hz")
    print("\nGain recommendation:")
    print("  Target for PSG MIDI channel: adjust CC7 so output roughly matches")
    print(f"  current level. With RMS={rms_db:.1f} dBFS and centroid={spec_centroid:.0f} Hz,")
    print("  the mix appears to be FM-dominant (typical for Sonic).")
    print("  PSG may need boosting if centroid < 1500 Hz (FM-heavy),")
    print("  or cutting if centroid > 2500 Hz (PSG-heavy).")

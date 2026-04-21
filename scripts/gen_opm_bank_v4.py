#!/usr/bin/env python3
"""
gen_opm_bank_v4.py — Generate an OPM-native GM bank using Differential Evolution.

Usage
-----
  python3 scripts/gen_opm_bank_v4.py [options]

  --sf3 PATH          Path to FluidR3_GM.sf3  (default: build/soundfonts/FluidR3_GM.sf3)
  --wopn PATH         Path to gm.wopn warm-start bank (default: ext/libOPNMIDI/fm_banks/gm.wopn)
  --out PATH          Output .bin path         (default: ext/opm_gm_bank/opm_gm.bin)
  --programs LIST     Comma-separated program numbers or ranges, e.g. 0,1,2-5  (default: 0-127)
  --percussion LIST   Comma-separated percussion note numbers or ranges         (default: none)
  --maxiter N         DE max iterations per patch                               (default: 500)
  --popsize N         DE population size multiplier                             (default: 15)
  --patience N        Stop early if best fitness does not improve by
                      --min-delta for this many generations                     (default: 40)
  --min-delta F       Minimum fitness improvement to reset patience counter     (default: 5e-5)
  --sample-rate HZ    Render sample rate                                        (default: 44100)
  --notes LIST        Notes to evaluate, comma-separated MIDI numbers           (default: 48,69,72)

Output
------
  ext/opm_gm_bank/opm_gm.bin   — binary bank (see OpmBankReader.java)
  ext/opm_gm_bank/opm_gm.json  — human-readable JSON export

The script checkpoints after each program / percussion note so that long runs
can be resumed by re-running with the same arguments.
"""

import argparse
import json
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

# Add scripts/ to path so opm_render can be imported
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import opm_render

# ── CLAP neural embedding (lazy-loaded once per worker process) ────────────────

_CLAP_MODEL = None
_CLAP_PROCESSOR = None
_CLAP_DEVICE = None


def _get_clap():
    """Lazy-load the CLAP model (once per worker process)."""
    global _CLAP_MODEL, _CLAP_PROCESSOR, _CLAP_DEVICE
    if _CLAP_MODEL is None:
        try:
            from transformers import ClapModel, ClapProcessor
            import torch
            _CLAP_DEVICE = 'mps' if torch.backends.mps.is_available() else 'cpu'
            _CLAP_PROCESSOR = ClapProcessor.from_pretrained("laion/clap-htsat-unfused")
            _CLAP_MODEL = ClapModel.from_pretrained("laion/clap-htsat-unfused").to(_CLAP_DEVICE)
            _CLAP_MODEL.eval()
        except Exception:
            _CLAP_MODEL = 'unavailable'  # sentinel so we don't retry
    return _CLAP_MODEL, _CLAP_PROCESSOR, _CLAP_DEVICE


def _clap_embedding(pcm: np.ndarray, sample_rate: int) -> np.ndarray:
    """
    Compute a normalised CLAP audio embedding for perceptual similarity.
    Returns a zero vector if CLAP is unavailable (graceful fallback).
    """
    model, processor, device = _get_clap()
    if model == 'unavailable' or model is None:
        return np.zeros(512, dtype=np.float32)
    try:
        import torch
        import librosa as _librosa
        if sample_rate != 48000:
            pcm = _librosa.resample(pcm, orig_sr=float(sample_rate), target_sr=48000.0)
        inputs = processor(audios=pcm, sampling_rate=48000, return_tensors="pt").to(device)
        with torch.no_grad():
            emb = model.get_audio_features(**inputs)
        emb_np = emb.squeeze().cpu().numpy().astype(np.float32)
        norm = np.linalg.norm(emb_np)
        return emb_np / (norm + 1e-9)
    except Exception:
        return np.zeros(512, dtype=np.float32)


def _clap_text_embedding(text: str) -> np.ndarray:
    """
    Compute a normalised CLAP text embedding for text-guided OPM optimisation.
    The DE fitness compares OPM audio embeddings against this text embedding,
    guiding the search toward patches that sound like the named instrument
    without requiring reference audio.
    """
    model, processor, device = _get_clap()
    if model == 'unavailable' or model is None:
        return np.zeros(512, dtype=np.float32)
    try:
        import torch
        inputs = processor(text=[text], return_tensors="pt").to(device)
        with torch.no_grad():
            emb = model.get_text_features(**inputs)
        emb_np = emb.squeeze().cpu().numpy().astype(np.float32)
        norm = np.linalg.norm(emb_np)
        return emb_np / (norm + 1e-9)
    except Exception:
        return np.zeros(512, dtype=np.float32)


# ── GM instrument text prompts for CLAP text-guided optimisation ──────────────
# Each entry describes what the GM instrument should sound like in natural language.
# CLAP computes audio↔text similarity, guiding DE toward patches that perceptually
# match the description rather than imitating sample-based FluidSynth audio.
GM_TEXT_PROMPTS = [
    # Piano (0-7)
    "acoustic grand piano rich warm harmonics",
    "bright upright piano clear tone",
    "electric piano Rhodes warm bell tone",
    "electric piano Yamaha DX7 digital FM bell",
    "honky-tonk piano slightly detuned",
    "electric piano 1 soft bell tone",
    "harpsichord plucked keyboard bright",
    "clavinet funky electric keyboard",
    # Chromatic Percussion (8-15)
    "celesta delicate bright bell",
    "glockenspiel bright metallic bell",
    "music box delicate tiny bell",
    "vibraphone warm metallic resonance sustain",
    "marimba warm wooden mallet",
    "xylophone bright sharp wooden mallet",
    "tubular bells church bell long sustain",
    "dulcimer plucked string twang",
    # Organ (16-23)
    "Hammond organ drawbar warm sustained",
    "percussive organ click sharp attack",
    "rock organ gritty overdrive",
    "church pipe organ full resonant",
    "reed organ harmonium buzzy reedy",
    "accordion French reedy sustained",
    "harmonica mouth organ reedy breath",
    "tango accordion bandoneon reedy",
    # Guitar (24-31)
    "nylon string acoustic guitar warm pluck",
    "steel string acoustic guitar bright pluck",
    "jazz electric guitar hollow warm mellow tone",
    "clean electric guitar bright clear sustained",
    "muted electric guitar palm mute staccato",
    "electric guitar overdrive rock distortion",
    "electric guitar heavy metal distortion",
    "electric guitar harmonics feedback",
    # Bass (32-39)
    "acoustic double bass warm plucked deep",
    "electric bass finger pluck deep warm",
    "electric bass picked bright attack",
    "electric bass fretless smooth glide warm",
    "slap electric bass funky bright pop",
    "electric bass popped bright snappy",
    "synth bass 1 analog deep punchy FM",
    "synth bass 2 deep electronic FM",
    # Strings (40-47)
    "violin solo string bright singing",
    "viola string warm mid singing",
    "cello string deep warm singing",
    "contrabass string very deep low",
    "tremolo strings shimmering fast vibrato",
    "pizzicato strings plucked staccato short",
    "orchestral harp plucked glide",
    "timpani deep resonant kettle drum",
    # Ensemble (48-55)
    "string ensemble full lush sustained",
    "string ensemble slow attack warm pad",
    "synth strings warm pad sustained",
    "synth strings bright synth pad",
    "choir singing aahs vocal ensemble",
    "voice oohs soft breathy vocal",
    "synth voice vocoder choir",
    "orchestra hit stab dramatic accent",
    # Brass (56-63)
    "trumpet bright fanfare bold",
    "trombone warm slide brass",
    "tuba very deep low brass",
    "muted trumpet wah cup mute",
    "french horn warm mellow horn",
    "brass section bright bold ensemble",
    "synth brass 1 bright electronic",
    "synth brass 2 warm fat rich FM brass ensemble",
    # Reed (64-71)
    "soprano saxophone bright reedy jazz",
    "alto saxophone warm reedy jazz",
    "tenor saxophone warm breathy jazz",
    "baritone saxophone deep warm low reedy",
    "oboe nasal bright double reed",
    "english horn warm mellow double reed",
    "bassoon deep dark woodwind",
    "clarinet warm smooth single reed",
    # Pipe (72-79)
    "piccolo bright high-pitched flute",
    "concert flute airy pure clear tone",
    "recorder wooden gentle flute",
    "pan flute breathy gentle airy",
    "bottle blow breathy airy",
    "shakuhachi Japanese bamboo flute breathy",
    "whistle pure clear high tone",
    "ocarina ceramic warm round tone",
    # Synth Lead (80-87)
    "lead synth square wave buzzy",
    "lead synth sawtooth bright",
    "lead synth calliope pure flute-like",
    "lead synth chiff metallic attack",
    "lead synth charang guitar-like distortion",
    "lead synth voice singing",
    "lead synth fifths two-note parallel",
    "lead synth bass bright combined",
    # Synth Pad (88-95)
    "pad new age shimmer crystal",
    "pad warm analog soft sustained",
    "pad polysynth bright attack",
    "pad choir vocal airy sustained",
    "pad bowed glass eerie sustained",
    "pad metallic bright shimmering",
    "pad halo eerie drone",
    "pad sweep filter rising bright",
    # Synth Effects (96-103)
    "rain water drops bright",
    "soundtrack cinematic atmosphere",
    "crystal glass bell glassy bright",
    "atmosphere synth pad drone",
    "brightness synth very bright shimmer",
    "goblins eerie dark synth",
    "echoes echo delay repeat",
    "science fiction space synth",
    # Ethnic (104-111)
    "sitar Indian plucked string twang",
    "banjo plucked bright American folk",
    "shamisen Japanese plucked string sharp",
    "koto Japanese zither plucked gentle",
    "kalimba African thumb piano bright bell",
    "bagpipe drone reed sustained",
    "fiddle folk violin bright",
    "shanai Indian oboe nasal reedy",
    # Percussive (112-119)
    "tinkle bell tiny bright bell",
    "agogo metallic bright percussion",
    "steel drums Caribbean bright metallic",
    "woodblock click short wood percussion",
    "taiko drum deep resonant Japanese",
    "melodic tom resonant drum pitch",
    "synth drum electronic percussion",
    "reverse cymbal reverse wash",
    # Sound Effects (120-127)
    "guitar fret noise scratch",
    "breath noise air",
    "seashore ocean wave ambient",
    "bird tweet chirp high",
    "telephone ring bell electronic",
    "helicopter rotor mechanical",
    "applause crowd clapping noise",
    "gunshot explosion loud",
]


# ── Constants ──────────────────────────────────────────────────────────────────

SAMPLE_RATE  = 44_100
SUSTAIN_SEC  = 1.0
RELEASE_SEC  = 0.5
EVAL_NOTES   = [48, 69, 72]  # C3, A4, C5

# Fitness weights
ALPHA = 0.45  # log mel-spectral distance from OPM-rendered WOPN reference
BETA  = 0.40  # harmonic richness reward (upper-mel energy bonus; subtracted from fitness)
# GAMMA (RMS envelope shape) removed: ADSR is now fixed from WOPN, so the envelope
# shape of every candidate is identical to the reference — cosine-RMS distance is
# always ~0 and contributes nothing to the gradient.
# DELTA (level distance) removed: TL is now locked from WOPN, so carrier level drift
# cannot occur — DELTA always ≈ 0 and contributes nothing to the gradient.
# ETA (pitch stability) removed: the HPS onset-vs-sustain measurement is unreliable
# for FM synthesis. FM patches change their harmonic structure as ADSR envelopes
# evolve, so HPS detects envelope-driven timbral change as apparent pitch drift.
# In practice, ETA × pitch_penalty dominated the fitness (e.g. JazzGuitar note C3
# scored 0.877 penalty regardless of DT1 values), making the warm-start an
# unbeatable minimum — no DT1/MUL change could overcome the pitch_penalty floor.
# With TL+ADSR locked from WOPN, carrier pitch stability is structurally guaranteed
# (DT2=0, MUL is bounded, and extreme MUL values are penalised by spec_dist), so
# a separate audio-domain pitch penalty is not needed.
# ETA: pitch stability penalty — measured from rendered audio via Harmonic Product Spectrum.
# Replaces the v3 parameter-space carrier DT1/MUL penalty (_carrier_param_penalty).
# The HPS detects the actual F0 drift between onset and sustain, so DE can freely
# explore DT1 on carriers when the resulting pitch happens to be stable.
# (ETA constant is defined near the HPS implementation below.)

# Mel-spectrogram params
N_MELS       = 40
N_MFCC       = 13
FRAME_LEN    = 2048
HOP_LEN      = 512
MEL_FMIN     = 20.0
MEL_FMAX     = 8000.0

# Number of RMS frames (sub-sampled)
N_RMS_FRAMES = 64

# opm_gm.bin magic and layout
OPM_MAGIC    = b'OPMGM-BNK\x00'
OPM_VERSION  = 0x01
PATCH_BYTES  = 52   # per patch: name[16] + noteOff(1) + percKey(1) + fbalg(1) + lfosens(1) + 4*op(8) = 52
N_PROGRAMS   = 128
N_PERC       = 128


# ── WOPN parser ────────────────────────────────────────────────────────────────

def _u16le(data, off): return struct.unpack_from('<H', data, off)[0]
def _u16be(data, off): return struct.unpack_from('>H', data, off)[0]
def _s16be(data, off): return struct.unpack_from('>h', data, off)[0]


def load_wopn(path: str) -> dict:
    """
    Parse a WOPN2 file and return dict with:
      melodic[0-127]    list of patch-dicts in OPM parameter format
      percussion[0-127] list of patch-dicts in OPM parameter format (or None)
      lfo_freq          int
    """
    with open(path, 'rb') as f:
        data = f.read()

    magic = data[:11]
    if magic not in (b'WOPN2-B2NK\x00', b'WOPN2-BANK\x00'):
        raise ValueError(f"Invalid WOPN magic: {magic!r}")

    version = _u16le(data, 11)
    count_mel  = _u16be(data, 13)
    count_perc = _u16be(data, 15)
    lfo_freq   = data[17] & 0xFF

    HEADER_SIZE  = 18
    INST_SIZE    = 69
    BANK_META    = 34

    bank_meta_bytes = BANK_META * (count_mel + count_perc) if version >= 2 else 0
    mel_off  = HEADER_SIZE + bank_meta_bytes
    perc_off = mel_off + INST_SIZE * 128 * count_mel

    def parse_bank(offset):
        patches = []
        for i in range(128):
            off = offset + i * INST_SIZE
            name_bytes = data[off:off+32]
            name = name_bytes.split(b'\x00')[0].decode('latin1', errors='replace')
            note_offset = _s16be(data, off + 32)
            perc_key    = data[off + 34] & 0xFF
            fbalg       = data[off + 35] & 0xFF
            lfosens     = data[off + 36] & 0xFF
            ops = []
            for l in range(4):
                op_off = off + 37 + l * 7
                dtfm    = data[op_off]     & 0xFF
                level   = data[op_off + 1] & 0xFF
                rsatk   = data[op_off + 2] & 0xFF
                amdecay1= data[op_off + 3] & 0xFF
                decay2  = data[op_off + 4] & 0xFF
                susrel  = data[op_off + 5] & 0xFF
                # ssgeg ignored for OPM
                # Map WOPN→OPM
                dt1 = (dtfm >> 4) & 0x07
                mul = dtfm & 0x0F
                tl  = level & 0x7F
                ks  = (rsatk >> 6) & 0x03
                ar  = rsatk & 0x1F
                am  = (amdecay1 >> 7) & 0x01
                d1r = amdecay1 & 0x1F
                d2r = decay2 & 0x1F
                d1l = (susrel >> 4) & 0x0F
                rr  = susrel & 0x0F
                ops.append({
                    'DT1': dt1, 'MUL': mul, 'TL': tl,
                    'KS':  ks,  'AR':  ar,
                    'AM':  am,  'D1R': d1r,
                    'DT2': 0,   'D2R': d2r,
                    'D1L': d1l, 'RR':  rr,
                })
            alg = fbalg & 0x07
            fb  = (fbalg >> 3) & 0x07
            pms = (lfosens >> 4) & 0x07
            ams = lfosens & 0x03
            patches.append({
                'name':         name,
                'note_offset':  note_offset,
                'perc_key':     perc_key,
                'ALG': alg, 'FB': fb, 'PMS': pms, 'AMS': ams,
                'operators':    ops,
            })
        return patches

    return {
        'melodic':    parse_bank(mel_off),
        'percussion': parse_bank(perc_off) if count_perc > 0 else [None] * 128,
        'lfo_freq':   lfo_freq,
    }


# ── Reference audio rendering (FluidSynth) ────────────────────────────────────

def render_reference_fluidsynth(sf3_path: str, program: int, note: int,
                                  sample_rate: int, is_perc: bool = False) -> np.ndarray:
    """
    Render GM program `program` at MIDI note `note` using fluidsynth.
    Returns float32 mono PCM array.
    """
    # Build a tiny SMF (Standard MIDI File) in memory, then pipe through fluidsynth
    channel = 9 if is_perc else 0

    def build_smf():
        """Build a minimal SMF type 0 with program change + note on/off."""
        tempo = 500_000  # 120 BPM
        ticks_per_q = 480

        def delta(n): return bytes([n & 0x7F]) if n < 0x80 else (
            bytes([(n >> 7) | 0x80]) + delta(n & 0x7F))

        def vlq(n):
            """Variable-length quantity encoding."""
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
        # Tempo: set to 120 BPM
        track += bytes([0x00, 0xFF, 0x51, 0x03,
                        (tempo >> 16) & 0xFF, (tempo >> 8) & 0xFF, tempo & 0xFF])
        if not is_perc:
            # Program change on channel 0
            track += bytes([0x00, 0xC0, program & 0x7F])
        # Note on
        vel = 100
        track += bytes([0x00, 0x90 | channel, note & 0x7F, vel])
        # Wait 1 second = 2 quarter notes at 120 BPM = 960 ticks
        sustain_ticks = int(ticks_per_q * (SUSTAIN_SEC * 120 / 60))
        track += vlq(sustain_ticks)
        # Note off
        track += bytes([0x80 | channel, note & 0x7F, 0])
        # Wait release, then end of track
        release_ticks = int(ticks_per_q * (RELEASE_SEC * 120 / 60))
        track += vlq(release_ticks)
        track += bytes([0xFF, 0x2F, 0x00])  # end-of-track (delta-time already added above)

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
             '-F', '-',   # output to stdout as raw audio
             '--audio-file-type=raw',
             '--audio-file-format=s16',
             sf3_path, mid_path],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        if result.returncode != 0:
            raise RuntimeError(f"fluidsynth failed: {result.stderr.decode()[:500]}")

        raw = np.frombuffer(result.stdout, dtype=np.int16)
        # fluidsynth outputs stereo
        if len(raw) % 2 == 1:
            raw = raw[:-1]
        left  = raw[0::2].astype(np.float32)
        right = raw[1::2].astype(np.float32)
        return ((left + right) * 0.5 / 32767.0)
    finally:
        os.unlink(mid_path)


# ── Feature extraction ─────────────────────────────────────────────────────────

def _hz_to_mel(hz):  return 2595.0 * np.log10(1 + hz / 700.0)
def _mel_to_hz(mel): return 700.0 * (10 ** (mel / 2595.0) - 1)


def _mel_filterbank(n_mels, n_fft, sample_rate, fmin, fmax):
    """Return (n_mels, n_fft//2+1) mel filterbank matrix."""
    mel_min = _hz_to_mel(fmin)
    mel_max = _hz_to_mel(fmax)
    mel_points = np.linspace(mel_min, mel_max, n_mels + 2)
    hz_points   = _mel_to_hz(mel_points)
    bin_points  = np.floor((n_fft + 1) * hz_points / sample_rate).astype(int)

    fb = np.zeros((n_mels, n_fft // 2 + 1))
    for m in range(1, n_mels + 1):
        f_m_minus = bin_points[m - 1]
        f_m       = bin_points[m]
        f_m_plus  = bin_points[m + 1]
        for k in range(f_m_minus, f_m):
            fb[m - 1, k] = (k - bin_points[m - 1]) / (bin_points[m] - bin_points[m - 1] + 1e-9)
        for k in range(f_m, f_m_plus):
            fb[m - 1, k] = (bin_points[m + 1] - k) / (bin_points[m + 1] - bin_points[m] + 1e-9)
    return fb


# Pre-compute the mel filterbank once
_MEL_FB = None


def _get_mel_fb(sample_rate):
    global _MEL_FB
    if _MEL_FB is None:
        _MEL_FB = _mel_filterbank(N_MELS, FRAME_LEN, sample_rate, MEL_FMIN, MEL_FMAX)
    return _MEL_FB


def _stft_magnitude(pcm, n_fft=FRAME_LEN, hop=HOP_LEN):
    """Return magnitude spectrogram, shape (n_fft//2+1, n_frames)."""
    win = np.hanning(n_fft)
    frames = []
    for start in range(0, len(pcm) - n_fft + 1, hop):
        frame = pcm[start:start + n_fft] * win
        spec  = np.abs(scipy_fft(frame, n=n_fft)[:n_fft // 2 + 1])
        frames.append(spec)
    if not frames:
        return np.zeros((n_fft // 2 + 1, 1))
    return np.stack(frames, axis=1)


def extract_features(pcm: np.ndarray, sample_rate: int) -> dict:
    """
    Compute CLAP neural embedding, log mel-spectral envelope, and RMS envelope.

    Returns dict with keys 'clap', 'mfcc', 'log_mel', 'rms', 'raw_rms'.
    'raw_rms' is the overall RMS level of the PCM signal; used by compute_fitness
    to reject near-silent renders before comparing level-invariant features.
    'clap' is a normalised 512-dim neural audio embedding for perceptual similarity.
    """
    mag = _stft_magnitude(pcm)
    fb  = _get_mel_fb(sample_rate)

    mel = fb @ mag                   # (N_MELS, n_frames)
    log_mel = np.log(mel + 1e-9)    # log mel spectrogram

    # MFCC via DCT-II on log mel (kept for potential fallback use)
    mfcc = scipy_dct(log_mel, axis=0, norm='ortho')[:N_MFCC]  # (N_MFCC, n_frames)

    # Mean-normalise MFCC per coefficient
    mfcc_mean = mfcc.mean(axis=1, keepdims=True)

    # Global spectral envelope = mean log mel across time
    log_mel_mean = log_mel.mean(axis=1)  # (N_MELS,)

    # RMS envelope sub-sampled to N_RMS_FRAMES bins
    rms_raw = mag.std(axis=0)        # proxy for energy per frame
    if len(rms_raw) >= N_RMS_FRAMES:
        step = max(1, len(rms_raw) // N_RMS_FRAMES)
        rms  = np.array([rms_raw[i:i+step].mean() for i in range(0, N_RMS_FRAMES*step, step)])[:N_RMS_FRAMES]
    else:
        rms = np.pad(rms_raw, (0, N_RMS_FRAMES - len(rms_raw)))

    # Mean-centre log_mel envelope so L2 distance is level-invariant.
    log_mel_centred = log_mel_mean - log_mel_mean.mean()

    # Harmonic richness: energy difference between upper and lower mel bands.
    # Higher = more upper-harmonic content. Used as a reward term in compute_fitness.
    n_half = N_MELS // 2
    richness = float(log_mel_mean[n_half:].mean() - log_mel_mean[:n_half].mean())

    return {
        'clap':    _clap_embedding(pcm, sample_rate),  # perceptual neural embedding
        'mfcc':    (mfcc - mfcc_mean).mean(axis=1),
        'log_mel': log_mel_centred,
        'rms':     rms.astype(np.float32),
        'raw_rms': float(np.sqrt(np.mean(pcm ** 2))),  # absolute level guard
        'richness': richness,
    }


def _cos_sim(a, b):
    na = np.linalg.norm(a) + 1e-12
    nb = np.linalg.norm(b) + 1e-12
    return float(np.dot(a, b) / (na * nb))


def _l2_dist(a, b):
    return float(np.linalg.norm(a - b))


# Carrier operator masks per OPM algorithm (ALG 0-7).
# Bit i (0=OP1, 1=OP2, 2=OP3, 3=OP4) is set when that operator is a carrier
# (i.e., its output goes directly to the DAC).
# OPM and OPN2 share the same algorithm topology.
_CARRIER_MASK = [
    0b1000,  # ALG 0: OP4 only
    0b1000,  # ALG 1: OP4 only
    0b1000,  # ALG 2: OP4 only
    0b1000,  # ALG 3: OP4 only
    0b1010,  # ALG 4: OP2, OP4
    0b1110,  # ALG 5: OP2, OP3, OP4
    0b1110,  # ALG 6: OP2, OP3, OP4
    0b1111,  # ALG 7: all operators
]

# ── Pitch stability via Harmonic Product Spectrum ─────────────────────────────

# Maximum pitch drift (cents) between note onset and mid-sustain that is
# considered acceptable.  Anything beyond this is penalised linearly up to
# _PITCH_DRIFT_CLIP_CENTS, where the penalty saturates at 1.0.
_PITCH_DRIFT_CLIP_CENTS = 50.0

# ETA constant removed — see comment block near fitness weight constants above.


def _hps_f0(pcm: np.ndarray, sample_rate: int,
            t_start: float, t_end: float,
            f_min: float, f_max: float,
            n_harmonics: int = 5) -> float:
    """
    Estimate the fundamental frequency of a PCM segment using the
    Harmonic Product Spectrum (HPS) method.

    The HPS multiplies the magnitude spectrum by copies of itself
    downsampled (stretched) by integer factors 2…n_harmonics.  Because
    harmonics of F0 are equally spaced on a linear frequency axis, the
    product peaks sharply at F0 even when higher partials dominate.

    Returns the estimated F0 in Hz, or 0.0 when detection is unreliable
    (segment too short, spectrum too flat, or F0 outside [f_min, f_max]).
    """
    i_start = int(t_start * sample_rate)
    i_end   = int(t_end   * sample_rate)
    frame   = pcm[i_start:i_end]
    if len(frame) < 512:
        return 0.0

    window   = np.hanning(len(frame))
    spectrum = np.abs(np.fft.rfft(frame * window))
    freqs    = np.fft.rfftfreq(len(frame), 1.0 / sample_rate)

    # Restrict candidate range to [f_min, f_max]
    i_lo = int(np.searchsorted(freqs, f_min))
    i_hi = int(np.searchsorted(freqs, f_max))
    if i_hi <= i_lo:
        return 0.0

    candidate_freqs = freqs[i_lo:i_hi]
    hps = spectrum[i_lo:i_hi].copy().astype(np.float64)

    # Multiply by downsampled copies: for harmonic h, interpolate the
    # spectrum at positions h × candidate_freqs.
    for h in range(2, n_harmonics + 1):
        harmonic_freqs = candidate_freqs * h
        interpolated   = np.interp(harmonic_freqs, freqs, spectrum,
                                   left=0.0, right=0.0)
        hps *= interpolated

    if hps.max() == 0.0:
        return 0.0

    return float(candidate_freqs[int(np.argmax(hps))])


def compute_pitch_stability_penalty(pcm: np.ndarray, note: int,
                                    sample_rate: int,
                                    sustain_sec: float) -> float:
    """
    Measure how much the pitch drifts between note onset and mid-sustain.

    The expected fundamental is derived from the MIDI note number.  F0 is
    estimated in two windows:
      - onset   : 50 ms – 200 ms after the start of the render
      - sustain : centred on the middle of the sustain phase

    Returns a normalised penalty in [0, 1]:
      0.0 → perfectly stable pitch (drift ≤ 0 cents above threshold)
      1.0 → drift ≥ _PITCH_DRIFT_CLIP_CENTS cents

    When F0 cannot be detected in either window (near-silent or very
    complex spectrum) the function returns 0.0 to avoid false penalties.
    """
    f_expected = 440.0 * 2.0 ** ((note - 69) / 12.0)
    # Search window: half an octave below to two octaves above expected F0
    f_min = max(f_expected * 0.7, 30.0)
    f_max = min(f_expected * 4.0, sample_rate * 0.5)

    f0_onset   = _hps_f0(pcm, sample_rate, 0.05, 0.20, f_min, f_max)
    mid        = sustain_sec * 0.5
    f0_sustain = _hps_f0(pcm, sample_rate, mid - 0.10, mid + 0.10, f_min, f_max)

    if f0_onset <= 0.0 or f0_sustain <= 0.0:
        return 0.0  # detection failed — do not penalise

    drift_cents = abs(1200.0 * np.log2(f0_sustain / f0_onset))
    return float(np.clip(drift_cents / _PITCH_DRIFT_CLIP_CENTS, 0.0, 1.0))


_MIN_AUDIBLE_RMS = 0.001  # below this → near-silent carrier; apply large penalty
_AUDIBILITY_PENALTY = 5.0  # added to fitness when rendered audio is inaudible


def compute_fitness(rendered_feats: dict, target_feats: dict) -> float:
    """Composite fitness: lower = better.

    Reference is the OPM-rendered WOPN warm-start patch, not FluidSynth.
    This avoids the sample-vs-FM imitation problem.

    ALPHA (0.45): log-mel spectral distance — stay close to WOPN-OPM tonal character.
    BETA  (0.25): harmonic richness reward (subtracted) — reward upper-harmonic energy
                  gains relative to the WOPN-OPM reference baseline.
    RMS envelope shape (GAMMA) removed: ADSR is fixed from WOPN so envelope is
    always identical to reference — cosine-RMS contributes nothing to the gradient.
    Level distance (DELTA) removed: TL is now locked from WOPN so carrier level
    drift cannot occur — DELTA always ≈ 0 and contributes nothing to the gradient.
    Pitch stability (ETA) removed: see comment block near fitness weight constants.
    """
    # Audibility guard: penalise near-silent renders heavily
    if rendered_feats['raw_rms'] < _MIN_AUDIBLE_RMS:
        return _AUDIBILITY_PENALTY

    spec_dist = _l2_dist(rendered_feats['log_mel'], target_feats['log_mel'])
    # Normalise spec_dist to [0,2] range (comparable to former rms_dist scale).
    spec_dist_norm = spec_dist / (np.linalg.norm(target_feats['log_mel']) + 1e-9)

    # Richness reward: how much upper-harmonic energy increased vs the reference.
    ref_richness = target_feats.get('ref_richness', 0.0)
    richness_gain = rendered_feats.get('richness', 0.0) - ref_richness
    # Normalise by reference magnitude; clip to [0,1] so DE cannot exploit noise.
    richness_reward = np.clip(richness_gain, 0.0, 1.0)

    return ALPHA * spec_dist_norm - BETA * richness_reward


# ── Parameter encoding ─────────────────────────────────────────────────────────

# DE parameter vector layout (11 integers total):
#   [0]  FB   0-7
#   [1]  PMS  0-7
#   [2]  AMS  0-3
#   For each of 4 operators (2 params each, offset 3+op*2):
#   [0]  DT1  0-7
#   [1]  MUL  bounds are ±1 around WOPN warm-start per operator (see optimise_patch)
#
# ALG is fixed from the WOPN warm-start and NOT part of the DE search space.
# Observation (smoke test 2026-04-20): allowing DE to change ALG caused AcousticBass
# (prog 32) to move from ALG=2 to ALG=0 (serial chain) with MUL=8 on op0. In a serial
# chain the modulation index at low notes is extreme (β >> 1), which spreads energy
# across sidebands and suppresses the fundamental — resulting in significantly lower
# perceived volume vs the YM2612 reference. ALG is a structural design choice of the
# WOPN patch author; DE should not override it.
#
# MUL bounds are ±1 around the WOPN warm-start per operator (computed dynamically in
# optimise_patch). Same observation: full 0-8 range let DE select MUL=8 on modulators,
# destroying tonal character and perceived bass volume. A ±1 window allows subtle
# harmonic shifts without catastrophic restructuring.
#
# TL, ADSR (KS, AR, AM, D1R, D2R, D1L, RR) and DT2 are all fixed from the WOPN
# warm-start and injected at render/serialise time via wopn_ops — not part of the
# search space. Locking TL removes the need for the DELTA level-distance term.

_BOUNDS_GLOBAL = [(0, 7), (0, 7), (0, 3)]   # FB, PMS, AMS
# Per-operator DT1 and MUL bounds are built dynamically in optimise_patch:
#   carrier ops → DT1 and MUL locked to WOPN value (prevents pitch deviation)
#   modulator ops → DT1 free (0-7), MUL ±1 around WOPN


def params_to_patch(p, wopn_ops: list, alg: int) -> dict:
    """Convert DE parameter vector to opm_render patch dict.

    wopn_ops — list of 4 WOPN operator dicts (uppercase keys: TL, KS, AR, AM, D1R, D2R, D1L, RR).
    alg      — algorithm index, fixed from WOPN warm-start (not part of DE search space).
    TL and ADSR fields are taken from wopn_ops; DE controls DT1, MUL, FB, PMS, AMS.
    Vector layout: [FB, PMS, AMS, DT1_0, MUL_0, DT1_1, MUL_1, DT1_2, MUL_2, DT1_3, MUL_3].
    """
    p = [int(round(x)) for x in p]
    ops = []
    for l in range(4):
        o  = p[3 + l*2 : 3 + (l+1)*2]  # DT1, MUL
        wp = wopn_ops[l]
        ops.append({
            'dt1mul': ((o[0] & 0x07) << 4) | (o[1] & 0x0F),
            'tl':      wp['TL'] & 0x7F,  # locked from WOPN
            'ksatk':  ((wp['KS']  & 0x03) << 6) | (wp['AR']  & 0x1F),
            'amd1r':  ((wp['AM']  & 0x01) << 7) | (wp['D1R'] & 0x1F),
            'dt2d2r':  wp['D2R'] & 0x1F,  # DT2 always 0
            'd1lrr':  ((wp['D1L'] & 0x0F) << 4) | (wp['RR'] & 0x0F),
        })
    return {
        'fbalg':    ((p[0] & 0x07) << 3) | (alg & 0x07),   # p[0]=FB; ALG locked
        'lfosens':  ((p[1] & 0x07) << 4) | (p[2] & 0x03),  # p[1]=PMS, p[2]=AMS
        'operators': ops,
    }


def wopn_patch_to_params(wp: dict) -> list:
    """Convert a loaded WOPN patch dict to the DE parameter vector (tonal only).

    Returns 11 values: FB, PMS, AMS, then per-op DT1, MUL.
    ALG is locked from WOPN and NOT included — pass wp['ALG'] separately where needed.
    TL and ADSR are also not part of the search space — injected from wopn_ops at render time.
    """
    v = [wp['FB'], wp['PMS'], wp['AMS']]
    for op in wp['operators']:
        v += [op['DT1'], op['MUL']]
    return v


def params_to_bank_patch(p, name: str, note_offset: int, perc_key: int,
                         wopn_ops: list, alg: int) -> dict:
    """Convert DE parameter vector to the bank patch format for serialisation.

    wopn_ops — list of 4 WOPN operator dicts; TL and ADSR fields are taken from here.
    alg      — algorithm index, fixed from WOPN warm-start (not part of DE search space).
    Vector layout: [FB, PMS, AMS, DT1_0, MUL_0, DT1_1, MUL_1, DT1_2, MUL_2, DT1_3, MUL_3].
    """
    p = [int(round(x)) for x in p]
    ops = []
    for l in range(4):
        o  = p[3 + l*2 : 3 + (l+1)*2]  # DT1, MUL
        wp = wopn_ops[l]
        ops.append({
            'dt1mul': ((o[0] & 0x07) << 4) | (o[1] & 0x0F),
            'tl':      wp['TL'] & 0x7F,  # locked from WOPN
            'ksatk':  ((wp['KS']  & 0x03) << 6) | (wp['AR']  & 0x1F),
            'amd1r':  ((wp['AM']  & 0x01) << 7) | (wp['D1R'] & 0x1F),
            'dt2d2r':  wp['D2R'] & 0x1F,
            'd1lrr':  ((wp['D1L'] & 0x0F) << 4) | (wp['RR'] & 0x0F),
            'ssgeg':   0,
        })
    return {
        'name':        name,
        'note_offset': note_offset,
        'perc_key':    perc_key,
        'fbalg':      ((p[0] & 0x07) << 3) | (alg & 0x07),   # p[0]=FB; ALG locked
        'lfosens':    ((p[1] & 0x07) << 4) | (p[2] & 0x03),  # p[1]=PMS, p[2]=AMS
        'operators':   ops,
    }


# ── opm_gm.bin I/O ────────────────────────────────────────────────────────────

def _encode_patch(bp: dict) -> bytes:
    """Encode a bank patch dict to 53 bytes."""
    name_b = bp['name'].encode('latin1', errors='replace')[:16].ljust(16, b'\x00')
    note_off = bp['note_offset'] & 0xFF  # stored as signed, but pack as byte
    header = struct.pack('16sBBBB',
                         name_b,
                         note_off,
                         bp['perc_key'] & 0xFF,
                         bp['fbalg']    & 0xFF,
                         bp['lfosens']  & 0xFF)
    ops_b = bytearray()
    for op in bp['operators']:
        ops_b += struct.pack('8B',
                             op['dt1mul'] & 0xFF,
                             op['tl']     & 0xFF,
                             op['ksatk']  & 0xFF,
                             op['amd1r']  & 0xFF,
                             op['dt2d2r'] & 0xFF,
                             op['d1lrr']  & 0xFF,
                             op.get('ssgeg', 0) & 0xFF,
                             0)  # padding
    return header + bytes(ops_b)


def write_opm_bank(melodic: list, percussion: list, path: str) -> None:
    """Write opm_gm.bin file to `path`."""
    os.makedirs(os.path.dirname(path) or '.', exist_ok=True)
    with open(path, 'wb') as f:
        # Header: magic (10) + version (1) + nMelodic u16le + nPercussion u16le
        f.write(OPM_MAGIC)
        f.write(struct.pack('<BHH', OPM_VERSION, N_PROGRAMS, N_PERC))
        for bp in melodic:
            f.write(_encode_patch(bp))
        for bp in percussion:
            f.write(_encode_patch(bp))


def write_json(melodic: list, percussion: list, path: str) -> None:
    os.makedirs(os.path.dirname(path) or '.', exist_ok=True)
    data = {'melodic': melodic, 'percussion': percussion}
    with open(path, 'w') as f:
        json.dump(data, f, indent=2)


def _dummy_patch(name='', note_offset=0, perc_key=0) -> dict:
    """Return a silent patch (high TL on all operators)."""
    op = {'dt1mul': 0x01, 'tl': 127, 'ksatk': 0x1F, 'amd1r': 0x00,
          'dt2d2r': 0x00, 'd1lrr': 0xFF, 'ssgeg': 0}
    return {'name': name, 'note_offset': note_offset, 'perc_key': perc_key,
            'fbalg': 0x07, 'lfosens': 0, 'operators': [dict(op) for _ in range(4)]}


def load_existing_bank(path: str):
    """Load existing opm_gm.bin and return (melodic list, perc list) or (None, None)."""
    if not os.path.isfile(path):
        return None, None
    try:
        with open(path, 'rb') as f:
            data = f.read()
        magic = data[:10]
        if magic != OPM_MAGIC:
            return None, None
        # version u8, nMel u16le, nPerc u16le at offset 10
        version, n_mel, n_perc = struct.unpack_from('<BHH', data, 10)
        offset = 15
        def decode_patch(off):
            name_b = data[off:off+16].split(b'\x00')[0].decode('latin1', errors='replace')
            note_off_raw = data[off+16]
            if note_off_raw >= 128:
                note_off = note_off_raw - 256
            else:
                note_off = note_off_raw
            perc_key = data[off+17]
            fbalg    = data[off+18]
            lfosens  = data[off+19]
            ops = []
            for l in range(4):
                o_off = off + 20 + l * 8
                ops.append({'dt1mul': data[o_off], 'tl': data[o_off+1],
                            'ksatk':  data[o_off+2], 'amd1r': data[o_off+3],
                            'dt2d2r': data[o_off+4], 'd1lrr': data[o_off+5],
                            'ssgeg':  data[o_off+6]})
            return {'name': name_b, 'note_offset': note_off, 'perc_key': perc_key,
                    'fbalg': fbalg, 'lfosens': lfosens, 'operators': ops}

        melodic    = [decode_patch(offset + i * PATCH_BYTES) for i in range(n_mel)]
        percussion = [decode_patch(offset + n_mel * PATCH_BYTES + i * PATCH_BYTES)
                      for i in range(n_perc)]
        return melodic, percussion
    except Exception as e:
        print(f"[warn] Could not load existing bank: {e}", file=sys.stderr)
        return None, None


# ── DE optimiser ───────────────────────────────────────────────────────────────

def optimise_patch(target_feats_per_note: list, warm_params: list,
                   wopn_patch: dict,
                   notes: list, sample_rate: int,
                   maxiter: int, popsize: int,
                   patience: int = 40, min_delta: float = 5e-5,
                   verbose: bool = False, prefix: str = '') -> list:
    """
    Run Differential Evolution to find the best OPM patch params.

    target_feats_per_note : list of feature dicts, one per note in `notes`
    warm_params           : initial parameter vector (tonal only, from WOPN warm start)
    wopn_patch            : full WOPN patch dict; ALG is locked from wopn_patch['ALG'],
                            ADSR from wopn_patch['operators']
    patience              : stop early if best fitness does not improve by min_delta
                            for this many consecutive generations
    min_delta             : minimum improvement in best fitness to reset patience counter
    Returns the best parameter vector found.
    """
    wopn_ops = wopn_patch['operators']
    alg      = wopn_patch['ALG']

    # OPM carrier operator mask by ALG (operator order: OP1=index0, OP2=1, OP3=2, OP4=3).
    # Carriers produce the audible output; their MUL determines the output pitch directly.
    # Changing a carrier's MUL by ±1 shifts pitch by up to two octaves — unacceptable.
    # Modulators affect timbre only; ±1 MUL allows subtle harmonic shifts.
    # (2026-04-20: ±1 applied uniformly caused 1–2 octave pitch errors on melody instruments.)
    _CARRIER_MASK = [
        [False, False, False, True],   # ALG 0: OP4 only
        [False, False, False, True],   # ALG 1: OP4 only
        [False, False, False, True],   # ALG 2: OP4 only
        [False, False, False, True],   # ALG 3: OP4 only
        [False, True,  False, True],   # ALG 4: OP2, OP4
        [False, True,  True,  True],   # ALG 5: OP2, OP3, OP4
        [False, True,  True,  True],   # ALG 6: OP2, OP3, OP4
        [True,  True,  True,  True],   # ALG 7: all carriers
    ]
    carriers = _CARRIER_MASK[alg & 0x07]

    # Carrier operators are locked to WOPN DT1 and MUL values.
    # DT1 on a carrier shifts output pitch (observed: DT1=5 on carrier → audible pitch deviation,
    # 2026-04-20). MUL on a carrier shifts pitch by octaves (MUL=2 → +1 octave).
    # Modulators only affect timbre; DT1 (0-7) and MUL ±1 are safe to explore.
    op_bounds = []
    op_integrality = []
    for op, is_carrier in zip(wopn_ops, carriers):
        if is_carrier:
            # Locked to WOPN value. Use narrow float range so scipy doesn't apply
            # integrality validation (which rejects lower==upper). The final result.x
            # is rounded to int anyway, so DE always sees the locked integer.
            op_bounds += [(op['DT1'] - 0.4, op['DT1'] + 0.4),
                          (op['MUL'] - 0.4, op['MUL'] + 0.4)]
            op_integrality += [False, False]
        else:
            op_bounds += [(0, 7),
                          (max(0, op['MUL'] - 1), min(15, op['MUL'] + 1))]
            op_integrality += [True, True]
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
                total += 2.0  # penalty
        val = total / len(notes)
        if val < best_f[0]:
            best_f[0] = val
            if verbose and call_count[0] % 500 == 0:
                print(f"  iter ~{call_count[0]}  best={val:.4f}")
        return val

    # Early-stopping callback: track best fitness per generation.
    # Each callback invocation corresponds to one completed DE generation.
    # Stop when the best fitness hasn't improved by min_delta for `patience` generations.
    gen_best   = [float('inf')]  # best at last generation boundary
    no_improve = [0]             # consecutive stagnant generation count
    gen_count  = [0]
    gen_log    = []              # (gen, best_fitness) — one entry per generation

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
                  f"no improvement for {patience} generations "
                  f"(best={current:.6f})", flush=True)
            return True  # signal scipy to stop
        return False

    # Build initial population: WOPN warm start + random perturbations.
    # The warm_params already encode the WOPN tonal values (DT1, MUL, TL, ALG, FB, PMS, AMS)
    # which are good starting points.  No carrier overrides are applied here — pitch
    # stability is now enforced by the audio-domain HPS penalty in fitness().
    rng = np.random.default_rng(42)
    pop_size = popsize * len(bounds)  # scipy convention: popsize is a multiplier
    seed = list(warm_params)
    init = np.array([seed], dtype=float)
    # Fill remaining population with random perturbations around the fixed seed
    perturb = rng.integers(-16, 17, size=(pop_size - 1, len(bounds)))
    rest = np.clip(
        np.array(seed, dtype=float)[None, :] + perturb,
        [b[0] for b in bounds],
        [b[1] for b in bounds]
    )
    init = np.vstack([init, rest])

    result = differential_evolution(
        fitness,
        bounds=bounds,
        integrality=integrality,
        maxiter=maxiter,
        popsize=popsize,
        init=init,
        tol=0,          # disable scipy's built-in tolerance: early stopping via callback
        mutation=(0.5, 1.0),
        recombination=0.7,
        seed=42,
        workers=1,
        updating='immediate',
        polish=False,
        callback=_early_stop_callback,
    )
    return [int(round(x)) for x in result.x], gen_log


# ── Program-level parallel workers (must be top-level to be picklable) ────────

def _process_one_program(prog, sf3_path, wopn_patch, notes, sample_rate,
                         maxiter, popsize, patience, min_delta):
    """Optimise one melodic program using OPM-rendered warm-start as tonal reference."""
    t0 = time.time()
    wopn_ops    = wopn_patch['operators']
    warm_params = wopn_patch_to_params(wopn_patch)
    warm_patch_dict = params_to_patch(warm_params, wopn_ops, wopn_patch['ALG'])
    # Render the WOPN warm-start through OPM to get a valid FM reference.
    # Using this instead of FluidSynth avoids the sample-vs-FM imitation problem.
    target_feats = []
    for note in notes:
        ref_pcm = opm_render.render_patch(warm_patch_dict, note, sample_rate,
                                          sustain_sec=1.0, release_sec=0.5)
        feats = extract_features(ref_pcm, sample_rate)
        feats['ref_richness'] = feats['richness']  # baseline richness for reward computation
        target_feats.append(feats)
    best_p, gen_log = optimise_patch(target_feats, warm_params, wopn_patch, notes, sample_rate,
                                     maxiter, popsize, patience=patience, min_delta=min_delta,
                                     verbose=True,
                                     prefix=f"[{prog:3d} {wopn_patch['name'][:12]:<12}]")
    best_patch = params_to_bank_patch(best_p, wopn_patch['name'], wopn_patch['note_offset'], 0,
                                      wopn_ops, wopn_patch['ALG'])
    return prog, best_patch, time.time() - t0, gen_log


_DEFAULT_PERC_OPS = [{'KS': 0, 'AR': 31, 'AM': 0, 'D1R': 5, 'D2R': 2, 'D1L': 8, 'RR': 7}
                     for _ in range(4)]


def _process_one_perc(perc_note, sf3_path, wopn_patch, sample_rate,
                      maxiter, popsize, patience, min_delta):
    """Render reference audio and optimise one percussion note in a worker process."""
    t0 = time.time()
    _DEFAULT_ALG = 4  # ALG=4: two carriers (OP3+OP4), sensible default for percussion
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
    best_p, gen_log = optimise_patch([extract_features(pcm, sample_rate)], warm_params, fake_patch,
                                     [perc_note], sample_rate, maxiter, popsize,
                                     patience=patience, min_delta=min_delta, verbose=True)
    best_patch = params_to_bank_patch(best_p, name, note_offset, perc_key,
                                      wopn_ops, fake_patch['ALG'])
    return perc_note, best_patch, time.time() - t0, gen_log


# ── Main ───────────────────────────────────────────────────────────────────────

def parse_range_list(s: str) -> list:
    """Parse '0,1,4-6,10' → [0,1,4,5,6,10]."""
    items = []
    for part in s.split(','):
        part = part.strip()
        if '-' in part:
            lo, hi = part.split('-', 1)
            items.extend(range(int(lo), int(hi) + 1))
        else:
            items.append(int(part))
    return sorted(set(items))


def main():
    parser = argparse.ArgumentParser(description='Generate OPM GM bank via DE')
    parser.add_argument('--sf3',       default='build/soundfonts/FluidR3_GM.sf3')
    parser.add_argument('--wopn',      default='ext/libOPNMIDI/fm_banks/gm.wopn')
    parser.add_argument('--out',       default='ext/opm_gm_bank/opm_gm.bin')
    parser.add_argument('--programs',  default='0-127')
    parser.add_argument('--percussion',default='')
    parser.add_argument('--maxiter',   type=int,   default=500)
    parser.add_argument('--popsize',   type=int,   default=15)
    parser.add_argument('--patience',  type=int,   default=40,
                        help='Early-stop after this many stagnant generations (default: 40)')
    parser.add_argument('--min-delta', type=float, default=5e-5, dest='min_delta',
                        help='Minimum fitness improvement to reset patience counter (default: 5e-5)')
    parser.add_argument('--sample-rate', dest='sample_rate', type=int, default=SAMPLE_RATE)
    parser.add_argument('--notes',     default=','.join(str(n) for n in EVAL_NOTES))
    args = parser.parse_args()

    if not os.path.isfile(args.sf3):
        sys.exit(f"SF3 not found: {args.sf3}")
    if not os.path.isfile(args.wopn):
        sys.exit(f"WOPN not found: {args.wopn}")

    notes = [int(n) for n in args.notes.split(',')]
    programs   = parse_range_list(args.programs) if args.programs else []
    perc_notes = parse_range_list(args.percussion) if args.percussion else []

    print(f"Loading WOPN warm-start bank: {args.wopn}")
    wopn = load_wopn(args.wopn)

    # Load or initialise the output bank
    json_path = args.out.replace('.bin', '.json')
    melodic_bank, perc_bank = load_existing_bank(args.out)
    if melodic_bank is None:
        melodic_bank = [_dummy_patch(wopn['melodic'][i]['name'],
                                     wopn['melodic'][i]['note_offset']) for i in range(128)]
        perc_bank    = [_dummy_patch('', 0, i) for i in range(128)]

    print(f"Evaluating notes: {notes}")
    print(f"Programs to optimise: {len(programs)}")
    print(f"Percussion notes to optimise: {len(perc_notes)}")

    n_workers = max(1, (os.cpu_count() or 4) - 1)
    print(f"Parallel workers: {n_workers}")

    print(f"Early-stop patience: {args.patience} generations, min_delta: {args.min_delta}")

    # ── Melodic programs ──────────────────────────────────────────────────
    if programs:
        with ProcessPoolExecutor(max_workers=n_workers) as executor:
            futures = {
                executor.submit(_process_one_program, prog, args.sf3,
                                wopn['melodic'][prog], notes, args.sample_rate,
                                args.maxiter, args.popsize,
                                args.patience, args.min_delta): prog
                for prog in programs
            }
            for future in as_completed(futures):
                prog, best_patch, elapsed, gen_log = future.result()
                melodic_bank[prog] = best_patch
                final_fitness = gen_log[-1][1] if gen_log else float('nan')
                print(f"\n[program {prog:3d}] {wopn['melodic'][prog]['name']}"
                      f"  done in {elapsed:.1f}s"
                      f"  gens={len(gen_log)}  final_fitness={final_fitness:.6f}", flush=True)
                write_opm_bank(melodic_bank, perc_bank, args.out)
                write_json(melodic_bank, perc_bank, json_path)
                print(f"  checkpoint written → {args.out}", flush=True)

    # ── Percussion notes ──────────────────────────────────────────────────
    if perc_notes:
        with ProcessPoolExecutor(max_workers=n_workers) as executor:
            futures = {
                executor.submit(_process_one_perc, pn, args.sf3,
                                wopn['percussion'][pn], args.sample_rate,
                                args.maxiter, args.popsize,
                                args.patience, args.min_delta): pn
                for pn in perc_notes
            }
            for future in as_completed(futures):
                pn, best_patch, elapsed, gen_log = future.result()
                perc_bank[pn] = best_patch
                name = (wopn['percussion'][pn] or {}).get('name', '')
                final_fitness = gen_log[-1][1] if gen_log else float('nan')
                print(f"\n[perc note {pn:3d}] {name}  done in {elapsed:.1f}s"
                      f"  gens={len(gen_log)}  final_fitness={final_fitness:.6f}", flush=True)
                write_opm_bank(melodic_bank, perc_bank, args.out)
                write_json(melodic_bank, perc_bank, json_path)
                print(f"  checkpoint written → {args.out}", flush=True)

    print(f"\nDone. Bank written to: {args.out}")


if __name__ == '__main__':
    main()

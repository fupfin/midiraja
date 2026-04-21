#!/usr/bin/env python3
"""
opm_render.py — Render a YM2151 (OPM) patch to PCM using libmidiraja_vgm.

Wraps the existing libmidiraja_vgm shared library via ctypes so that the
gen_opm_bank.py optimiser can evaluate patches without any subprocess overhead.

Public API
----------
render_patch(patch, note, sample_rate=44100, sustain_sec=1.0, release_sec=0.5)
    -> numpy float32 array (mono, normalised to roughly ±1)

Patch dict keys (matching the opm_gm.bin field names):
    fbalg    int  — bits 5-3=FB, bits 2-0=ALG
    lfosens  int  — bits 6-4=PMS, bits 1-0=AMS
    operators list[4] of dicts:
        dt1mul   int  — bits 6-4=DT1, bits 3-0=MUL
        tl       int  — 0-127 (0=loudest)
        ksatk    int  — bits 7-6=KS, bits 4-0=AR
        amd1r    int  — bit 7=AM-EN, bits 4-0=D1R
        dt2d2r   int  — bits 7-6=DT2, bits 4-0=D2R
        d1lrr    int  — bits 7-4=D1L, bits 3-0=RR
"""

import ctypes
import os
import platform
import struct
import numpy as np

# ── Library loading ────────────────────────────────────────────────────────────

def _find_lib() -> str:
    """Locate libmidiraja_vgm built by scripts/build-native-libs.sh."""
    sys_name = platform.system().lower()
    arch = platform.machine().lower()
    if arch in ('arm64', 'aarch64'):
        arch = 'aarch64'
    elif arch in ('x86_64', 'amd64'):
        arch = 'x86_64'

    if sys_name == 'darwin':
        os_fam, ext = 'macos', 'dylib'
    elif sys_name == 'linux':
        os_fam, ext = 'linux', 'so'
    else:
        os_fam, ext = 'windows', 'dll'

    script_dir = os.path.dirname(os.path.abspath(__file__))
    project_root = os.path.dirname(script_dir)
    lib_path = os.path.join(
        project_root, 'build', 'native-libs',
        f'{os_fam}-{arch}', 'vgm',
        f'libmidiraja_vgm.{ext}',
    )
    if not os.path.isfile(lib_path):
        raise FileNotFoundError(
            f"libmidiraja_vgm not found at {lib_path}.\n"
            "Run: bash scripts/build-native-libs.sh"
        )
    return lib_path


def _load_lib():
    lib = ctypes.CDLL(_find_lib())

    lib.vgm_create.restype = ctypes.c_void_p
    lib.vgm_create.argtypes = [ctypes.c_int]

    lib.vgm_open_data.restype = ctypes.c_int
    lib.vgm_open_data.argtypes = [ctypes.c_void_p,
                                   ctypes.POINTER(ctypes.c_uint8),
                                   ctypes.c_size_t]

    lib.vgm_render.restype = ctypes.c_int
    lib.vgm_render.argtypes = [ctypes.c_void_p, ctypes.c_int,
                                ctypes.POINTER(ctypes.c_int16)]

    lib.vgm_is_done.restype = ctypes.c_int
    lib.vgm_is_done.argtypes = [ctypes.c_void_p]

    lib.vgm_close.restype = None
    lib.vgm_close.argtypes = [ctypes.c_void_p]

    return lib


_LIB = None


def _get_lib():
    global _LIB
    if _LIB is None:
        _LIB = _load_lib()
    return _LIB


# ── VGM builder ────────────────────────────────────────────────────────────────

# VGM standard YM2151 reference clock (must match VgmWriter.YM2151_CLOCK = 3_579_545)
# Note: X68000 hardware uses 4 MHz, but VGM/ymfm is calibrated for this reference.
YM2151_CLOCK = 3_579_545

# Maps MIDI semitone-within-octave (0-11) to OPM KC note nibble.
# OPM skips codes 3, 7, 11 to fit 12 notes into the 4-bit space.
# IMPORTANT: OPM's octave boundary falls at C# (not C). C (semitone 0)
# is encoded as code 0xE of the *previous* octave. The octave calculation
# in build_vgm therefore subtracts 1 when semitone == 0.
_NOTE_TO_KC = [0xE, 0x0, 0x1, 0x2, 0x4, 0x5, 0x6, 0x8, 0x9, 0xA, 0xC, 0xD]
#               C    C#   D    D#   E    F    F#   G    G#   A    A#   B

# Operator register offsets for channels — OPM addresses operators as
# ch + {0, 8, 16, 24} for S1/S3/S2/S4 (MAME/VGM operator ordering).
_OP_OFFSETS = [0, 8, 16, 24]


def _opm_cmd(reg: int, val: int) -> bytes:
    """VGM command 0x54: write YM2151 register."""
    return struct.pack('BBB', 0x54, reg & 0xFF, val & 0xFF)


def _wait_samples(n: int) -> bytes:
    """VGM command 0x61: wait n samples (n fits in uint16)."""
    chunks = []
    while n > 0:
        wait = min(n, 0xFFFF)
        chunks.append(struct.pack('<BH', 0x61, wait))
        n -= wait
    return b''.join(chunks)


def build_vgm(patch: dict, note: int, sample_rate: int,
              sustain_sec: float, release_sec: float) -> bytes:
    """
    Build a minimal VGM v1.60 byte sequence that plays `patch` at `note`
    on YM2151 channel 0 for sustain_sec seconds, then keys off and waits
    release_sec seconds.

    patch format — see module docstring.
    note — MIDI note number (0-127).
    """
    # ── Encode note to OPM KC ─────────────────────────────────────────────
    # OPM octave boundary is at C# (not C): C (semitone 0) lives at code
    # 0xE of the previous octave, so subtract an extra 1 from the octave.
    semitone = note % 12
    octave   = note // 12 - 1 - (1 if semitone == 0 else 0)
    octave   = max(0, min(7, octave))
    kc = (octave << 4) | _NOTE_TO_KC[semitone]

    # ── Patch register writes (channel 0) ─────────────────────────────────
    alg = patch['fbalg'] & 0x07
    fb  = (patch['fbalg'] >> 3) & 0x07

    data = bytearray()

    # Channel configuration: RL=11 (stereo), FL, CON
    data += _opm_cmd(0x20, 0xC0 | (fb << 3) | alg)

    # LFO sensitivity
    data += _opm_cmd(0x38, patch['lfosens'] & 0x77)

    # Operator registers
    ops = patch['operators']
    for l, op in enumerate(ops):
        reg_off = _OP_OFFSETS[l]  # channel 0: just the offset itself
        data += _opm_cmd(0x40 + reg_off, op['dt1mul'] & 0x7F)
        data += _opm_cmd(0x60 + reg_off, op['tl']    & 0x7F)
        data += _opm_cmd(0x80 + reg_off, op['ksatk'] & 0xFF)
        data += _opm_cmd(0xA0 + reg_off, op['amd1r'] & 0xFF)
        data += _opm_cmd(0xC0 + reg_off, op['dt2d2r']& 0xFF)
        data += _opm_cmd(0xE0 + reg_off, op['d1lrr'] & 0xFF)

    # KC / KF
    data += _opm_cmd(0x28, kc)        # KC
    data += _opm_cmd(0x30, 0)         # KF = 0

    # Key-on (all 4 operators, channel 0)
    data += _opm_cmd(0x08, 0xF8 | 0)  # opMask=0xF (all ops), ch=0

    # Sustain
    sustain_samples = int(sample_rate * sustain_sec)
    data += _wait_samples(sustain_samples)

    # Key-off
    data += _opm_cmd(0x08, 0)         # opMask=0 (key-off), ch=0

    # Release tail
    release_samples = int(sample_rate * release_sec)
    data += _wait_samples(release_samples)

    # End of data
    data += bytes([0x66])

    total_samples = sustain_samples + release_samples

    # ── VGM v1.60 header (256 bytes, little-endian) ───────────────────────
    HEADER_SIZE = 0x100
    header = bytearray(HEADER_SIZE)

    # Magic
    header[0:4] = b'Vgm '
    # Version 1.60
    struct.pack_into('<I', header, 0x08, 0x00000160)
    # YM2151 clock at 0x30
    struct.pack_into('<I', header, 0x30, YM2151_CLOCK)
    # VGM data offset (relative to position 0x34)
    struct.pack_into('<I', header, 0x34, HEADER_SIZE - 0x34)
    # Total samples
    struct.pack_into('<I', header, 0x18, total_samples)
    # Loop: none
    struct.pack_into('<I', header, 0x1C, 0)
    struct.pack_into('<I', header, 0x20, 0)

    vgm_body = bytearray(header) + bytearray(data)

    # EOF offset (relative to position 0x04): total_length - 4
    struct.pack_into('<I', vgm_body, 0x04, len(vgm_body) - 4)

    return bytes(vgm_body)


# ── Renderer ───────────────────────────────────────────────────────────────────

_CHUNK_FRAMES = 4096  # frames per render call (must be >= vgm_bridge buffer)


def render_patch(patch: dict, note: int,
                 sample_rate: int = 44100,
                 sustain_sec: float = 1.0,
                 release_sec: float = 0.5) -> np.ndarray:
    """
    Render `patch` at MIDI `note` to a float32 mono PCM array.

    The returned array is normalised so that the RMS of the loudest patch
    evaluated during a batch is comparable across patches (no per-patch
    peak normalisation — only /32767 integer→float conversion).

    Parameters
    ----------
    patch       : dict with keys fbalg, lfosens, operators (see module doc)
    note        : MIDI note number (0-127)
    sample_rate : Hz (default 44100)
    sustain_sec : seconds to hold key-on before releasing
    release_sec : seconds to record after key-off

    Returns
    -------
    numpy float32 array, shape (N,), mono
    """
    lib = _get_lib()
    vgm_bytes = build_vgm(patch, note, sample_rate, sustain_sec, release_sec)

    # ctypes buffer for VGM data
    buf = (ctypes.c_uint8 * len(vgm_bytes)).from_buffer_copy(vgm_bytes)

    ctx = lib.vgm_create(sample_rate)
    if not ctx:
        raise RuntimeError("vgm_create returned NULL")
    try:
        rc = lib.vgm_open_data(ctx, buf, len(vgm_bytes))
        if rc != 0:
            raise RuntimeError(f"vgm_open_data failed: {rc}")

        total_frames = int(sample_rate * (sustain_sec + release_sec))
        pcm_s16 = np.zeros(total_frames * 2, dtype=np.int16)  # stereo interleaved

        offset = 0
        chunk_samples = _CHUNK_FRAMES * 2  # stereo interleaved int16 values
        while not lib.vgm_is_done(ctx) and offset < len(pcm_s16):
            remaining = len(pcm_s16) - offset
            ask = min(chunk_samples, remaining)
            chunk = pcm_s16[offset:offset + ask]
            chunk_ptr = chunk.ctypes.data_as(ctypes.POINTER(ctypes.c_int16))
            lib.vgm_render(ctx, ask, chunk_ptr)
            offset += ask
    finally:
        lib.vgm_close(ctx)

    # Mix stereo → mono, convert to float32
    left  = pcm_s16[0::2].astype(np.float32)
    right = pcm_s16[1::2].astype(np.float32)
    mono = (left + right) * 0.5 / 32767.0
    return mono

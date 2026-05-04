#!/usr/bin/env python3
"""
check_opm_bank.py — Per-instrument quality report for an OPMGM bank.

For each melodic instrument, renders both the OPM patch (from bank) and a
FluidSynth PCM reference, then reports:

  level_db  : OPM peak-RMS relative to FluidSynth in dB (negative = OPM quieter)
  env200    : RMS at 200ms as fraction of peak — pcm→opm (decay-speed indicator)
  env500    : RMS at 500ms as fraction of peak — pcm→opm (sustain-level indicator)
  fitness   : spectral log-mel distance (lower = better)
  d1l       : D1L of the first carrier operator in the bank

Status flags:
  OK    : level within ±6 dB, fitness < 0.30, env500 difference < 0.25
  WARN  : level ±6-12 dB, fitness 0.30-0.50, or env500 difference 0.25-0.40
  FAIL  : level outside ±12 dB, fitness > 0.50, or env500 difference > 0.40

Usage:
  python3 scripts/check_opm_bank.py [--bank PATH] [--sf3 PATH] [--programs LIST] [--note N]
"""

import argparse
import math
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import opm_render
from gen_opm_bank_v5 import (
    EVAL_NOTES, SUSTAIN_SEC, RELEASE_SEC, SAMPLE_RATE,
    _CARRIER_MASK, _MIN_AUDIBLE_RMS,
    extract_features, compute_fitness,
    load_existing_bank, render_reference_fluidsynth,
    parse_range_list,
)


# ── GM program names ──────────────────────────────────────────────────────────

_GM_NAMES = [
    'Acoustic Grand Pi', 'Bright Acoustic Pi', 'Electric Grand Pi', 'Honky-tonk Pi',
    'Electric Piano 1',  'Electric Piano 2',  'Harpsichord',       'Clavinet',
    'Celesta',           'Glockenspiel',      'Music Box',         'Vibraphone',
    'Marimba',           'Xylophone',         'Tubular Bells',     'Dulcimer',
    'Drawbar Organ',     'Percussive Organ',  'Rock Organ',        'Church Organ',
    'Reed Organ',        'Accordion',         'Harmonica',         'Tango Accordion',
    'Acoustic Guitar N', 'Acoustic Guitar S', 'Electric Guitar J', 'Electric Guitar C',
    'Electric Guitar M', 'Electric Guitar D', 'Electric Guitar T', 'Guitar harmonics',
    'Acoustic Bass',     'Elec Bass Finger',  'Elec Bass Pick',    'Fretless Bass',
    'Slap Bass 1',       'Slap Bass 2',       'Synth Bass 1',      'Synth Bass 2',
    'Violin',            'Viola',             'Cello',             'Contrabass',
    'Tremolo Strings',   'Pizzicato Strings', 'Orchestral Harp',   'Timpani',
    'String Ensemble 1', 'String Ensemble 2', 'SynthStrings 1',    'SynthStrings 2',
    'Choir Aahs',        'Voice Oohs',        'Synth Voice',       'Orchestra Hit',
    'Trumpet',           'Trombone',          'Tuba',              'Muted Trumpet',
    'French Horn',       'Brass Section',     'SynthBrass 1',      'SynthBrass 2',
    'Soprano Sax',       'Alto Sax',          'Tenor Sax',         'Baritone Sax',
    'Oboe',              'English Horn',      'Bassoon',           'Clarinet',
    'Piccolo',           'Flute',             'Recorder',          'Pan Flute',
    'Blown Bottle',      'Shakuhachi',        'Whistle',           'Ocarina',
    'Lead 1 square',     'Lead 2 sawtooth',   'Lead 3 calliope',   'Lead 4 chiff',
    'Lead 5 charang',    'Lead 6 voice',      'Lead 7 fifths',     'Lead 8 bass+ld',
    'Pad 1 new age',     'Pad 2 warm',        'Pad 3 polysynth',   'Pad 4 choir',
    'Pad 5 bowed',       'Pad 6 metallic',    'Pad 7 halo',        'Pad 8 sweep',
    'FX 1 rain',         'FX 2 soundtrack',   'FX 3 crystal',      'FX 4 atmosphere',
    'FX 5 brightness',   'FX 6 goblins',      'FX 7 echoes',       'FX 8 sci-fi',
    'Sitar',             'Banjo',             'Shamisen',          'Koto',
    'Kalimba',           'Bag pipe',          'Fiddle',            'Shanai',
    'Tinkle Bell',       'Agogo',             'Steel Drums',       'Woodblock',
    'Taiko Drum',        'Melodic Tom',       'Synth Drum',        'Reverse Cymbal',
    'Guitar Fret Noise', 'Breath Noise',      'Seashore',          'Bird Tweet',
    'Telephone Ring',    'Helicopter',        'Applause',          'Gunshot',
]


# ── Envelope snapshot ─────────────────────────────────────────────────────────

def _env_at_ms(pcm: np.ndarray, sample_rate: int, t_ms: float) -> float:
    """RMS in a 20ms window centred at t_ms, normalised to [-1, 1] amplitude."""
    win_half = int(sample_rate * 0.010)  # 10ms half-window
    centre = int(sample_rate * t_ms / 1000.0)
    lo = max(0, centre - win_half)
    hi = min(len(pcm), centre + win_half + 1)
    if hi <= lo:
        return 0.0
    return float(np.sqrt(np.mean(pcm[lo:hi] ** 2)))


def _peak_rms(pcm: np.ndarray, sample_rate: int, frame_ms: float = 5.0) -> float:
    frame_len = max(1, int(sample_rate * frame_ms / 1000.0))
    n_frames  = len(pcm) // frame_len
    if n_frames == 0:
        return 0.0
    rms_frames = np.array([
        float(np.sqrt(np.mean(pcm[i * frame_len:(i + 1) * frame_len] ** 2)))
        for i in range(n_frames)
    ])
    return float(rms_frames.max())


def _snapshot(pcm: np.ndarray, sample_rate: int) -> dict:
    """Return envelope-snapshot metrics for a PCM signal."""
    peak = _peak_rms(pcm, sample_rate)
    overall_rms = float(np.sqrt(np.mean(pcm ** 2)))
    e200 = _env_at_ms(pcm, sample_rate, 200.0)
    e500 = _env_at_ms(pcm, sample_rate, 500.0)
    frac200 = e200 / (peak + 1e-9)
    frac500 = e500 / (peak + 1e-9)
    return {'peak': peak, 'rms': overall_rms, 'frac200': frac200, 'frac500': frac500}


def _db(rms: float) -> float:
    return 20.0 * math.log10(max(rms, 1e-9))


# ── First-carrier D1L extraction ──────────────────────────────────────────────

def _bank_patch_d1l(bank_patch: dict) -> int | None:
    """Return D1L of the first carrier operator, or None."""
    fbalg = bank_patch.get('fbalg', 0)
    alg   = fbalg & 0x07
    mask  = _CARRIER_MASK[alg]
    ops   = bank_patch.get('operators', [])
    for i, op in enumerate(ops):
        if i < len(mask) and mask[i]:
            d1lrr = op.get('d1lrr', 0)
            return (d1lrr >> 4) & 0x0F
    return None


# ── Per-program check ─────────────────────────────────────────────────────────

def _check_one(prog: int, bank_patch: dict, sf3_path: str,
               note: int, sample_rate: int) -> dict:
    """Check one program; return metrics dict."""
    result = {'prog': prog, 'status': 'FAIL', 'level_db': None,
              'frac200_pcm': None, 'frac200_opm': None,
              'frac500_pcm': None, 'frac500_opm': None,
              'fitness': None, 'd1l': _bank_patch_d1l(bank_patch)}

    # Render OPM
    opm_render_patch = {k: bank_patch[k] for k in ('fbalg', 'lfosens', 'operators')}
    try:
        opm_pcm = opm_render.render_patch(opm_render_patch, note, sample_rate,
                                          SUSTAIN_SEC, RELEASE_SEC)
    except Exception as e:
        result['error'] = str(e)
        return result

    opm_snap = _snapshot(opm_pcm, sample_rate)
    if opm_snap['peak'] < _MIN_AUDIBLE_RMS:
        result['error'] = 'OPM inaudible'
        return result

    # Render FluidSynth reference
    try:
        pcm_ref = render_reference_fluidsynth(sf3_path, prog, note, sample_rate)
    except Exception as e:
        result['error'] = f'FluidSynth: {e}'
        return result

    pcm_snap = _snapshot(pcm_ref, sample_rate)
    if pcm_snap['peak'] < _MIN_AUDIBLE_RMS:
        result['error'] = 'PCM inaudible'
        return result

    # Level delta
    result['level_db']     = _db(opm_snap['rms']) - _db(pcm_snap['rms'])
    result['frac200_pcm']  = pcm_snap['frac200']
    result['frac200_opm']  = opm_snap['frac200']
    result['frac500_pcm']  = pcm_snap['frac500']
    result['frac500_opm']  = opm_snap['frac500']

    # Spectral fitness
    opm_feats = extract_features(opm_pcm, sample_rate)
    pcm_feats = extract_features(pcm_ref, sample_rate)
    pcm_target = dict(pcm_feats)
    pcm_target['log_mel']     = pcm_feats['log_mel']
    pcm_target['pcm_log_mel'] = pcm_feats['log_mel']
    result['fitness'] = compute_fitness(opm_feats, pcm_target)

    # Status
    level_abs    = abs(result['level_db'])
    env500_diff  = abs(result['frac500_pcm'] - result['frac500_opm'])
    fitness      = result['fitness']

    if level_abs > 12.0 or fitness > 0.50 or env500_diff > 0.40:
        result['status'] = 'FAIL'
    elif level_abs > 6.0 or fitness > 0.30 or env500_diff > 0.25:
        result['status'] = 'WARN'
    else:
        result['status'] = 'OK'

    return result


# ── Main ───────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description='Per-instrument quality check for OPMGM bank')
    parser.add_argument('--bank',      default='ext/opm_gm_bank/opm_gm.bin')
    parser.add_argument('--sf3',       default='build/soundfonts/FluidR3_GM.sf3')
    parser.add_argument('--programs',  default='0-127')
    parser.add_argument('--note',      type=int, default=69,
                        help='MIDI note for comparison (default: 69 = A4)')
    parser.add_argument('--sample-rate', type=int, default=SAMPLE_RATE, dest='sample_rate')
    parser.add_argument('--show',      choices=['all', 'warn', 'fail'], default='all',
                        help='Filter output rows (default: all)')
    args = parser.parse_args()

    if not os.path.isfile(args.bank):
        sys.exit(f"Bank not found: {args.bank}")
    if not os.path.isfile(args.sf3):
        sys.exit(f"SF3 not found: {args.sf3}")

    programs = parse_range_list(args.programs) if args.programs else list(range(128))
    melodic_bank, _ = load_existing_bank(args.bank)
    if melodic_bank is None:
        sys.exit(f"Could not load bank: {args.bank}")

    print(f"OPMGM Bank Quality Report")
    print(f"  Bank : {args.bank}")
    print(f"  SF3  : {args.sf3}")
    print(f"  Note : {args.note}")
    print()
    header = (f"{'Prog':>4}  {'Name':<18}  {'Level dB':>8}  "
              f"{'Env200 pcm→opm':>14}  {'Env500 pcm→opm':>14}  "
              f"{'Fitness':>7}  {'D1L':>3}  {'Status'}")
    print(header)
    print('-' * len(header))

    n_ok = n_warn = n_fail = n_err = 0

    for prog in programs:
        if prog >= len(melodic_bank):
            continue
        patch = melodic_bank[prog]
        name = (patch.get('name') or _GM_NAMES[prog] if prog < len(_GM_NAMES) else '?')[:18]

        r = _check_one(prog, patch, args.sf3, args.note, args.sample_rate)

        if 'error' in r:
            n_err += 1
            if args.show in ('all', 'fail'):
                print(f"{prog:>4}  {name:<18}  ERROR: {r['error']}")
            continue

        status = r['status']
        if status == 'OK':
            n_ok += 1
        elif status == 'WARN':
            n_warn += 1
        else:
            n_fail += 1

        show_row = (
            args.show == 'all' or
            (args.show == 'warn' and status in ('WARN', 'FAIL')) or
            (args.show == 'fail' and status == 'FAIL')
        )
        if not show_row:
            continue

        level_str  = f"{r['level_db']:>+7.1f} dB"
        e200_str   = f"{r['frac200_pcm']:.2f}→{r['frac200_opm']:.2f}"
        e500_str   = f"{r['frac500_pcm']:.2f}→{r['frac500_opm']:.2f}"
        fitness_str = f"{r['fitness']:.4f}"
        d1l_str    = str(r['d1l']) if r['d1l'] is not None else '?'
        print(f"{prog:>4}  {name:<18}  {level_str}  "
              f"{e200_str:>14}  {e500_str:>14}  "
              f"{fitness_str:>7}  {d1l_str:>3}  {status}")

    print('-' * len(header))
    print(f"Programs checked: {len(programs)}  "
          f"OK={n_ok}  WARN={n_warn}  FAIL={n_fail}  ERROR={n_err}")


if __name__ == '__main__':
    main()

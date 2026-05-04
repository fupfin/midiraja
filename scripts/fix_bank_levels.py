#!/usr/bin/env python3
"""
fix_bank_levels.py — Apply post-optimisation TL level correction to an existing OPMGM bank.

For each melodic program, renders both the OPM patch and FluidSynth PCM reference,
measures the RMS difference, and adjusts carrier TL values uniformly.  No DE
optimisation is run — only the level correction step from gen_opm_bank_v5.py.

Usage:
  python3 scripts/fix_bank_levels.py [--bank PATH] [--sf3 PATH] [--programs LIST]
"""

import argparse
import math
import multiprocessing
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import opm_render
from gen_opm_bank_v5 import (
    EVAL_NOTES, SUSTAIN_SEC, RELEASE_SEC, SAMPLE_RATE,
    _CARRIER_MASK, _MIN_AUDIBLE_RMS,
    load_existing_bank, render_reference_fluidsynth, write_opm_bank, write_json,
    parse_range_list,
)

_TL_CLAMP = 40  # ±40 TL steps = ±30 dB


def _render_dict_from_bank_patch(bp: dict) -> dict:
    """Convert a bank patch (binary format) to opm_render dict."""
    fbalg   = bp['fbalg']
    lfosens = bp['lfosens']
    alg     = fbalg & 0x07
    fb      = (fbalg >> 3) & 0x07
    pms     = (lfosens >> 4) & 0x07
    ams     = lfosens & 0x03
    ops_out = []
    for op in bp['operators']:
        dt1mul  = op['dt1mul']
        tl      = op['tl']
        ksatk   = op['ksatk']
        amd1r   = op['amd1r']
        dt2d2r  = op['dt2d2r']
        d1lrr   = op['d1lrr']
        ops_out.append({
            'DT1': (dt1mul >> 4) & 0x07,
            'MUL': dt1mul & 0x0F,
            'TL':  tl & 0x7F,
            'KS':  (ksatk >> 6) & 0x03,
            'AR':  ksatk & 0x1F,
            'AM':  (amd1r >> 7) & 0x01,
            'D1R': amd1r & 0x1F,
            'DT2': (dt2d2r >> 6) & 0x03,
            'D2R': dt2d2r & 0x1F,
            'D1L': (d1lrr >> 4) & 0x0F,
            'RR':  d1lrr & 0x0F,
        })
    return {'fbalg': fbalg, 'lfosens': lfosens, 'operators': [
        {'dt1mul': o['DT1'] << 4 | o['MUL'],
         'tl':     o['TL'],
         'ksatk':  o['KS'] << 6 | o['AR'],
         'amd1r':  o['AM'] << 7 | o['D1R'],
         'dt2d2r': o['DT2'] << 6 | o['D2R'],
         'd1lrr':  o['D1L'] << 4 | o['RR']}
        for o in ops_out
    ]}


def fix_one_program(args):
    prog, bp, sf3_path, sample_rate, notes = args
    alg      = bp['fbalg'] & 0x07
    carriers = _CARRIER_MASK[alg]

    render_dict = _render_dict_from_bank_patch(bp)

    # Render OPM
    opm_rms_vals = []
    for note in notes:
        try:
            opm_pcm = opm_render.render_patch(render_dict, note, sample_rate,
                                              SUSTAIN_SEC, RELEASE_SEC)
            rms = float(np.sqrt(np.mean(opm_pcm ** 2)))
            if rms >= _MIN_AUDIBLE_RMS:
                opm_rms_vals.append(rms)
        except Exception:
            pass

    if not opm_rms_vals:
        return prog, bp, 0.0, 'OPM inaudible'

    # Render FluidSynth
    pcm_rms_vals = []
    for note in notes:
        try:
            pcm_ref = render_reference_fluidsynth(sf3_path, prog, note, sample_rate)
            rms = float(np.sqrt(np.mean(pcm_ref ** 2)))
            if rms >= _MIN_AUDIBLE_RMS:
                pcm_rms_vals.append(rms)
        except Exception:
            pass

    if not pcm_rms_vals:
        return prog, bp, 0.0, 'PCM inaudible'

    opm_rms_avg = float(np.mean(opm_rms_vals))
    pcm_rms_avg = float(np.mean(pcm_rms_vals))
    db_diff   = 20.0 * math.log10(pcm_rms_avg / opm_rms_avg)
    tl_adjust = max(-_TL_CLAMP, min(_TL_CLAMP, int(round(db_diff / 0.75))))

    if abs(tl_adjust) < 1:
        return prog, bp, db_diff, 'no adjustment needed'

    # Apply TL adjustment to carrier operators in-place
    import copy
    bp_new = copy.deepcopy(bp)
    for op_idx, is_carrier in enumerate(carriers):
        if is_carrier:
            old_tl = bp_new['operators'][op_idx]['tl']
            new_tl = max(0, min(127, old_tl - tl_adjust))
            bp_new['operators'][op_idx]['tl'] = new_tl

    return prog, bp_new, db_diff, f'TL {-tl_adjust:+d} steps ({db_diff:+.1f} dB)'


def main():
    parser = argparse.ArgumentParser(description='Fix OPMGM bank carrier TL levels')
    parser.add_argument('--bank',      default='ext/opm_gm_bank/opm_gm.bin')
    parser.add_argument('--sf3',       default='build/soundfonts/FluidR3_GM.sf3')
    parser.add_argument('--programs',  default='0-127')
    parser.add_argument('--out',       default=None,
                        help='Output path (default: overwrite input bank)')
    parser.add_argument('--workers',   type=int, default=max(1, multiprocessing.cpu_count() - 1))
    parser.add_argument('--sample-rate', type=int, default=SAMPLE_RATE, dest='sample_rate')
    args = parser.parse_args()

    if not os.path.isfile(args.bank):
        sys.exit(f'Bank not found: {args.bank}')
    if not os.path.isfile(args.sf3):
        sys.exit(f'SF3 not found: {args.sf3}')

    out_path = args.out or args.bank
    programs = parse_range_list(args.programs)
    notes    = EVAL_NOTES

    melodic_bank, perc_bank = load_existing_bank(args.bank)
    if melodic_bank is None:
        sys.exit(f'Could not load bank: {args.bank}')

    print(f'Fixing levels for {len(programs)} programs  workers={args.workers}')

    work = [
        (prog, melodic_bank[prog], args.sf3, args.sample_rate, notes)
        for prog in programs
        if prog < len(melodic_bank)
    ]

    with multiprocessing.Pool(args.workers) as pool:
        results = pool.map(fix_one_program, work)

    n_fixed = 0
    for prog, bp_new, db_diff, msg in sorted(results, key=lambda x: x[0]):
        melodic_bank[prog] = bp_new
        opm_dbfs = 20.0 * math.log10(max(1e-9, 1.0)) - abs(db_diff)  # rough
        print(f'  [{prog:3d}] {melodic_bank[prog]["name"][:18]:<18}  {msg}')
        if 'TL' in msg:
            n_fixed += 1

    write_opm_bank(melodic_bank, perc_bank, out_path)
    json_path = out_path.replace('.bin', '.json')
    write_json(melodic_bank, perc_bank, json_path)
    print(f'\nFixed {n_fixed}/{len(programs)} programs  →  {out_path}')


if __name__ == '__main__':
    main()

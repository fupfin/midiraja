# OPM Patch Optimizer

Offline tool that automatically optimises GM 128-instrument OPM (YM2151) FM patches,
starting from a `.wopn` (OPN) bank and using Differential Evolution to search the
FM parameter space.

---

## Background

OPM (YM2151) and OPN (YM2203/YM2612) share an almost identical 4-op FM structure,
so libOPNMIDI's `.wopn` bank provides a warm-start set of 128 GM patches after a
straightforward register conversion (DT2 = 0 for all operators).

The optimiser then searches for OPM-specific parameters — primarily DT1 (fine detune
between operators) and LFO — that improve harmonic richness while keeping the tonal
character close to the WOPN source.

---

## Pipeline

```
.wopn bank (OPN GM patches)
        ↓  OPN → OPM parameter conversion (DT2=0)
Initial OPM patch set (128 instruments)
        ↓
┌──────────────────────────────────────────┐
│  Optimisation loop (per instrument)      │
│                                          │
│  WOPN-OPM reference render (fixed)       │
│  DE candidate render (YM2151 via ymfm)   │
│           ↓                              │
│    Audio-domain fitness                  │
│           ↓                              │
│  Parameter mutation (Differential Evo.)  │
│           ↓                              │
│    Convergence check → next instrument   │
└──────────────────────────────────────────┘
        ↓
opm_gm.bin (52 bytes/patch binary bank)
```

---

## Fitness Function History

### v1 — FluidSynth SF2 reference + MFCC (abandoned)

**Reference**: FluidSynth sample-based rendering.
**Fitness**: MFCC distance + spectral envelope + RMS envelope.
**Result**: "Toy sound" — FM synthesis cannot converge toward sample-based audio.
The MFCC landscape is too noisy; DE found parameter combinations that scored well
numerically but sounded wrong perceptually.

---

### v2 — FluidSynth SF2 reference + CLAP audio embedding

**Reference**: FluidSynth audio embedded via `laion/clap-htsat-unfused`.
**Fitness**: α·CLAP cosine distance + γ·RMS envelope + β·spectral richness.
**Weights**: α=0.60, γ=0.20, β=0.20.
**Result**: Still "toy sound". CLAP embeds both timbre and recording conditions;
FM patches that sound clean in isolation are mapped far from wet SF2 samples.
The fundamental problem (sample-vs-FM) persists regardless of the embedding space.

---

### v3 — OPM-rendered WOPN reference + ADSR locked from WOPN + carrier DT1 penalty

**Backup**: `scripts/gen_opm_bank_v3_wopn_adsr_carrier_penalty.py`
**Bank snapshot**: `ext/opm_gm_bank/opm_gm.bin.v3_wopn_adsr_carrier_penalty`

**Key changes from v2**:
- Reference is the **WOPN patch rendered through OPM** (not FluidSynth).
  Avoids the sample-vs-FM imitation problem entirely.
- **ADSR locked**: KS, AR, AM, D1R, D2R, D1L, RR are taken directly from the WOPN
  warm-start and are not part of the DE search space. This preserves the attack
  character of the WOPN bank (solving the "weak attack / dull sound" problem on X68000).
- **DE vector reduced** from 48 → 16 integers: `[ALG, FB, PMS, AMS, (DT1, MUL, TL)×4]`.
- **GAMMA (RMS envelope cosine distance) removed**: with ADSR fixed, every candidate
  produces the same envelope shape as the reference — GAMMA always ≈ 0 and contributes
  no gradient.
- **`_carrier_param_penalty()`**: parameter-space penalty that discourages DT1 ≠ 0 and
  MUL ≠ 1 on carrier operators, added to prevent pitch drift/dissonance on sustained notes.

**Fitness**:
```
fitness = ALPHA(0.70) × log-mel spectral distance  (from OPM-rendered WOPN reference)
        + DELTA(0.10) × absolute level distance     (carrier TL drift prevention)
        − BETA(0.20)  × harmonic richness reward    (upper-mel energy bonus)
        + carrier_param_penalty(p)                  (parameter-space heuristic)
```

**Known limitation**: FM synthesis is highly nonlinear — a small parameter change can
produce a completely different sound. The `_carrier_param_penalty()` evaluates parameters
directly rather than rendered audio, which is philosophically inconsistent with the
audio-based fitness. DT1=1 on a carrier might be perfectly acceptable for some instruments,
but the rule penalises it unconditionally. A better approach would be to detect pitch
stability directly in the rendered audio (e.g., fundamental frequency drift measurement).

---

### v4 — ALG locked + carrier MUL and DT1 locked

**Key changes from v3**:
- **ALG locked from WOPN**: algorithm is not part of the DE search space.
- **Carrier MUL locked to WOPN value**: instead of ±1 window, carrier MUL is fixed exactly.
  Modulator MUL uses ±1 window. DE vector reduced from 11 to effectively fewer free variables
  depending on carrier count per ALG.
- **Carrier DT1 locked to WOPN value**: DT1 on carriers causes output pitch deviation (DT1=5
  on a carrier can shift pitch by hundreds of cents). Only modulator DT1 is free (0–7).
- **TL locked from WOPN**: removed from search space (DELTA level-distance term no longer needed).

**Observations that drove the change (2026-04-20)**:

1. *Bass volume loss*: AcousticBass (prog 32) moved from ALG=2 (2-carrier parallel) to
   ALG=0 (serial chain) with MUL=8 on the first modulator. In a serial chain, the FM
   modulation index β becomes extremely large at low notes, suppressing the fundamental
   via the Bessel J₀ factor → significantly lower perceived bass volume vs YM2612 reference.
   Fix: lock ALG from WOPN.

2. *Melody pitch 1–2 octaves too high (first)*: ±1 MUL constraint applied uniformly to all
   operators allowed carrier MUL to shift by 1 (e.g. 1→2 = +1 octave, 2→4 = +2 octaves).
   Fix: carrier MUL locked exactly to WOPN value; only modulator MUL uses ±1 window.
   Carriers are identified per ALG using the standard OPM carrier mask.

3. *Melody pitch still too high (second)*: Even with carrier MUL locked, DE changed carrier
   DT1 values (e.g. JazzGuitar OP4 carrier: DT1 WOPN=0 → DE=5). OPM DT1 values 4–7 are
   large negative fine-detune; at certain notes this becomes hundreds of cents of deviation.
   Fix: carrier DT1 also locked to WOPN value.

ALG is a structural design decision of the WOPN patch author encoding carrier/modulator
topology. DE has no musical knowledge and should not override it.

**Fitness** (same as v3, DELTA term removed):
```
fitness = ALPHA(0.70) × log-mel spectral distance  (from OPM-rendered WOPN reference)
        − BETA(0.20)  × harmonic richness reward    (upper-mel energy bonus)
        + HPS pitch penalty                         (audio-domain carrier pitch stability)
```

---

### v4 implementation bugs fixed (2026-04-21)

Two crashes were encountered when running the v4 full 128-instrument generation.
Both manifested as the same exception at program 125 ("* Helicopter") after all other
instruments had completed:

```
ValueError: One of the integrality constraints does not have any possible integer
values between the lower/upper bounds.
```

#### Bug 1 — carrier bounds lower == upper rejected by scipy

**Root cause**: When locking a carrier parameter (DT1 or MUL) to its WOPN value `v`,
the bounds were set to `(v, v)`. `scipy.differential_evolution` validates integer-
constrained parameters by checking `ceil(lower) <= floor(upper)`; for `lower == upper`
this is satisfied mathematically, but scipy rejected it in the version used.

**Fix**: Use a narrow float range `(v - 0.4, v + 0.4)` with `integrality=False`.
`int(round(x))` in the decode step rounds back to `v` for any value in that range,
so the parameter is still effectively locked.

#### Bug 2 — modulator MUL upper bound clipped to 8 (OPM MUL is 4-bit, 0–15)

**Root cause**: The bounds for modulator MUL used `min(8, op['MUL'] + 1)`. This was
copied from earlier code that assumed MUL was limited to 0–8. OPM MUL is a 4-bit field
(0–15). Program 125 ("* Helicopter") has modulator operators with MUL=15:

```
lower = max(0, 15 - 1) = 14
upper = min(8, 15 + 1) = 8   ← lower > upper → ValueError
```

This caused a crash in every run because the parallel workers reach program 125
deterministically at roughly the same time.

**Fix**: Change `min(8, op['MUL'] + 1)` to `min(15, op['MUL'] + 1)`.

#### Final bounds code (v4, after fixes)

```python
op_bounds = []
op_integrality = []
for op, is_carrier in zip(wopn_ops, carriers):
    if is_carrier:
        # scipy rejects lower==upper for integer constraints.
        # Use narrow float range; int(round(x)) still locks to the same integer.
        op_bounds += [(op['DT1'] - 0.4, op['DT1'] + 0.4),
                      (op['MUL'] - 0.4, op['MUL'] + 0.4)]
        op_integrality += [False, False]
    else:
        op_bounds += [(0, 7),
                      (max(0, op['MUL'] - 1), min(15, op['MUL'] + 1))]
        op_integrality += [True, True]
```

---

## Challenges

- **FM parameter space discontinuity** — ALG, MUL, and DT1 are discrete; small changes
  can cause large timbre jumps. The search landscape is highly non-convex.
- **Strings / Choir / Pad** — Long attacks and slow amplitude modulation are hard to
  approximate with FM. Separate strategies (LFO AM, longer envelopes) may be needed.
- **Optimisation time** — 128 instruments × 3 notes × hundreds of generations → offline
  batch execution with per-instrument parallelism.
- **Subjective quality** — A good fitness score does not guarantee a good-sounding patch.
  Human review and manual fine-tuning after automated optimisation is recommended.

# OPM Bank Optimization Methodology (v5)

## Overview

The OPMGM bank is a 128-program melodic synthesizer patch set for the YM2151 (OPM) FM chip, optimized to match FluidSynth PCM reference timbre using Differential Evolution (DE) with physically-grounded warm-start values.

**Goal:** Render OPM patches that sound subjectively similar to General MIDI (GM) instruments on standard soundfonts (FluidR3).

**Constraint:** OPM has 4 operators per voice with strict FM topology (carrier/modulator structure). Optimization must work within these hardware limits.

---

## Phase 1: Warm-Start Parameter Extraction

### Purpose
Initialize the DE optimizer with parameter values extracted from PCM reference, rather than random or default values. This accelerates convergence and biases the search toward regions likely to match FluidSynth.

### Strategy: Carrier TL from PCM, Carrier ADSR from gm.wopn

**Why separate TL and ADSR?**
- **TL (Total Level / Attenuation):** 0–127, logarithmically controls output level. RMS-mapped extraction from PCM reference is physically meaningful: OPM RMS → target RMS is a direct energy-matching constraint.
- **ADSR (Attack, Decay, Sustain, Release):** Control envelope timbre. PCM extraction is unreliable (FluidSynth's sampled instrument envelopes are instrument-specific, not generalizable FM parameters). Use **gm.wopn** (GM2 OPM standard) as the universal neutral reference — it's been tested across decades of OPM hardware and firmware.

### Warm-Start Extraction Process

For each melodic program (0–127):

1. **Render OPM patch at 4 evaluation notes** (C4, G4, C5, G5):
   - Use current `opm_gm.bin` or initialization patch
   - Settings: 1 second sustain, 0.5 second release, 44100 Hz sample rate
   - Output: mono PCM float32 array

2. **Render FluidSynth PCM reference at same notes:**
   - Program: `program_id`
   - Soundfont: FluidR3_GM.sf3
   - Same timing (1 sec sustain, 0.5 sec release, 44100 Hz)
   - Output: mono PCM array

3. **Measure RMS (Root Mean Square) per note:**
   - OPM RMS: `sqrt(mean(opm_pcm²))`
   - PCM RMS: `sqrt(mean(pcm_ref²))`
   - Only count notes with RMS ≥ `_MIN_AUDIBLE_RMS` (threshold ~0.001 to skip silence)

4. **Compute level difference:**
   - Average RMS across audible notes: `opm_rms_avg`, `pcm_rms_avg`
   - dB difference: `db_diff = 20 * log10(pcm_rms_avg / opm_rms_avg)`
   - TL adjustment in steps: `tl_adjust = round(db_diff / 0.75)` (1 TL step ≈ 0.75 dB)
   - Clamp: `max(-40, min(40, tl_adjust))` (±40 steps = ±30 dB range)

5. **Apply TL adjustment to carrier operators:**
   - Identify carrier operators using `_CARRIER_MASK[algorithm]`
   - Update: `new_tl = max(0, min(127, old_tl - tl_adjust))`
   - Modulators unchanged

6. **Keep ADSR from gm.wopn:**
   - AR, D1R, D1L, D2R, RR: Use values from `gm.wopn` reference (no PCM extraction)
   - These control envelope shape; gm.wopn is a proven baseline across hardware

**Output:** `carrier_adsr_by_op` dict: `{program_id: {op_idx: {tl, ar, d1r, d1l, d2r, rr}}}`

This becomes the DE warm-start center.

---

## Phase 2: Differential Evolution Optimization

### Objective

Minimize **multimodal fitness function** comparing OPM rendering to PCM reference across evaluation notes and timing phases.

### Fitness Components

**1. Sustain Phase RMS Loss** (weighted 0.5)
- Render OPM sustain tail (first 0.5 sec of 1 sec sustain)
- Compare mean RMS to PCM sustain RMS
- Loss: `abs(opm_sustain_rms - pcm_sustain_rms) / max(pcm_sustain_rms, threshold)`

**2. Release Phase RMS Loss** (weighted 0.25)
- Render OPM release tail (0.5 sec after key-off)
- Compare mean RMS to PCM release RMS
- Loss: `abs(opm_release_rms - pcm_release_rms) / max(pcm_release_rms, threshold)`

**3. Temporal Envelope Loss** (weighted 0.25)
- Divide sustain/release into temporal windows
- Compute local RMS in each window; penalize deviation from PCM envelope shape
- Captures attack sharpness, decay rate, release tail

**Combined:** `fitness = 0.5 * sustain_rms_loss + 0.25 * release_rms_loss + 0.25 * envelope_loss`

Lower = better.

### Optimization Bounds

Bounds are **centered on warm-start** with tight ±N-step ranges:

| Parameter | Bits | Range | Warm-Start | Bounds | Notes |
|-----------|------|-------|-----------|--------|-------|
| **Carrier TL** | 7 | 0–127 | PCM-extracted | ±5 steps | Level only; keeps spectral shape |
| **Carrier AR** | 5 | 0–31 | gm.wopn | ±2 steps | Attack rate (attack sharpness) |
| **Carrier D1R** | 5 | 0–31 | gm.wopn | ±2 steps | 1st decay rate |
| **Carrier D1L** | 4 | 0–15 | gm.wopn | ±1 step | Decay-1 sustain level |
| **Carrier D2R** | 5 | 0–31 | gm.wopn | ±2 steps | 2nd decay rate (fine tail) |
| **Carrier RR** | 4 | 0–15 | gm.wopn | ±1 step | Release rate |
| **Modulators** | — | — | Frozen gm.wopn | None | Not optimized (stable baseline) |

Bounds keep DE search **local**; prevents wild deviations that corrupt timbre.

### DE Configuration

- **Population:** 20–30 individuals (OPM parameter space is 24-dimensional per algorithm; population should be 10× dimensionality)
- **Generations:** 100–200 (convergence typically achieved by generation 150 on small programs)
- **F (differential weight):** 0.7 (balance exploration vs exploitation)
- **CR (crossover probability):** 0.9 (high CR favors inheritance of good parameters)
- **Selection:** Best-of-generation elitism
- **Termination:** Max generations or stalled best-fitness threshold

### Parallel Execution

- Multiprocessing: one Python process per CPU core
- Work unit: one program
- Each process: DE runs independently, renders OPM patches (ctypes call to libmidiraja_vgm), compares to cached PCM reference
- Result: optimized patch dict for that program
- Merge: sorted results back into full melodic bank

---

## Phase 3: Post-Optimization Level Correction

### Problem
After DE optimization, patches may drift in absolute level due to fitness function uncertainty or integer rounding. Some programs overshoot (too loud), others undershoot (too quiet).

### Solution: RMS Level Correction (fix_bank_levels.py)

For each program:

1. **Render OPM patch** at evaluation notes (sustain + release)
2. **Render FluidSynth reference** at same notes
3. **Measure RMS of each:**
   - `opm_rms_avg = mean(opm_rms_vals)` (geometric mean across notes)
   - `pcm_rms_avg = mean(pcm_rms_vals)`

4. **Compute correction:**
   - `db_diff = 20 * log10(pcm_rms_avg / opm_rms_avg)`
   - `tl_adjust = round(db_diff / 0.75)` (same formula as warm-start)
   - Clamp: ±40 steps

5. **Apply uniformly to all carrier TL:**
   - `new_tl = max(0, min(127, old_tl - tl_adjust))`

**Important:** No DE is re-run. Only the TL correction from Phase 1 is applied again, with tighter bounds.

---

## Phase 4: Validation

### Automated Scoring (check_opm_bank.py)

For each program:

1. **Render OPM** at full keyboard (C0–B8, 12 notes per octave)
2. **Render FluidSynth reference** at same notes
3. **Compute normalized RMS error** per note:
   - Threshold: notes with RMS < 0.001 are skipped (silence)
   - Error: `|opm_rms - pcm_rms| / max(pcm_rms, threshold)`

4. **Classify per program:**
   - **PASS** (< 0.25 error): Match is very good
   - **WARN** (0.25–0.50 error): Acceptable; some timbre mismatch
   - **FAIL** (> 0.50 error): Poor match; likely needs manual review or re-optimization

5. **Report:**
   - Summary: % of programs in each category
   - Sorted list: worst-scoring programs first (for manual tuning)
   - Per-note matrix: RMS error across keyboard registers

### Listening Test

Play MIDI files with the optimized bank:
- Single instrument at a time (isolate timbre)
- Multiple notes (test envelope and sustain character)
- Program transitions (test category consistency)
- A/B compare old vs new bank on critical programs

**Focus instruments:** Oboe (68), Clarinet (71), Flute (73) — historically harsh due to FM complexity; best validators.

---

## Parameter Tuning Reference

### Key Constants (scripts/gen_opm_bank_v5.py)

```python
EVAL_NOTES = [60, 67, 72, 79]           # C4, G4, C5, G5 (4 notes, 1 octave span)
SUSTAIN_SEC = 1.0                        # Hold sustain for 1 second
RELEASE_SEC = 0.5                        # Record release tail for 0.5 seconds
SAMPLE_RATE = 44100                      # PCM render sample rate (Hz)

_MIN_AUDIBLE_RMS = 0.001                 # Skip notes quieter than this
_TL_CLAMP = 40                           # ±40 TL steps max adjustment (±30 dB)
_CARRIER_MASK = {
    0: [1, 0, 0, 0],                     # Algorithm 0: op3 is carrier
    1: [0, 0, 1, 1],                     # Algorithm 1: ops 2,3 are carriers
    # ... (8 algorithms total)
}
```

### Adjust these to...

| Parameter | To achieve | Effect |
|-----------|-----------|--------|
| **EVAL_NOTES** | More register coverage | Wider note range → slower optimization, more uniform keyboard |
| **SUSTAIN_SEC** | Longer sustain match | Better sustain timbre, slower render |
| **DE population** | Faster convergence | Smaller population (12) = faster but less thorough; larger (40) = slower, more thorough |
| **DE generations** | Finer optimization | More generations = better fitness, slower; typical 100–150 sufficient |
| **Bounds width** | Exploration vs stability | Wider bounds = more variation (risk of bad timbre); tight bounds = local refinement |

---

## Workflow: Full Optimization Run

```bash
# Step 1: Extract warm-start values from FluidSynth
PYENV_VERSION=3.11.15 python3 scripts/gen_opm_bank_v5.py \
    --sf3 build/soundfonts/FluidR3_GM.sf3 \
    --maxiter 150 \
    --out /tmp/opm_v5_optimized.bin

# Step 2: Post-optimize level correction (optional, if RMS drifted)
python3 scripts/fix_bank_levels.py \
    --bank /tmp/opm_v5_optimized.bin \
    --sf3 build/soundfonts/FluidR3_GM.sf3

# Step 3: Validate and score
python3 scripts/check_opm_bank.py \
    --bank /tmp/opm_v5_optimized.bin \
    --sf3 build/soundfonts/FluidR3_GM.sf3

# Step 4: Listening test
./scripts/run.sh vgm --system x68000 path/to/test.mid

# Step 5: Copy to resource and commit
cp /tmp/opm_v5_optimized.bin src/main/resources/opm/opm_gm.bin
```

---

## Known Limitations & Tradeoffs

| Limitation | Reason | Workaround |
|-----------|--------|-----------|
| **4-note evaluation** | Full keyboard (96 notes) = 50× longer render time | Spectrum-based matching (Phase 1) or category-specific note ranges |
| **Carrier-only optimization** | Modulators are harder to evaluate; safe to keep gm.wopn | Modulator ADSR optimization (future Phase 3) |
| **Single-algorithm FM** | Each OPM program has fixed algorithm; can't optimize topology | Accept algorithm as given; optimize parameters within topology |
| **RMS fitness** | Ignores spectral shape (which harmonics are strong) | Spectrum-based matching (future enhancement) |
| **Integer TL steps** | TL resolution is discrete (0.75 dB per step) | Unavoidable; affects fine-tuning precision |

---

## Success Metrics

**Current (v5 baseline):**
- ~85% of melodic programs: PASS or WARN classification
- Oboe (68), Clarinet (71), Flute (73): WARN → improved from FAIL
- Subjective: Wind programs sound less harsh; attack/release character closer to FluidSynth

**Target (future):**
- 95%+ PASS + WARN
- 0% FAIL classification
- Spectrum-based envelope matching for timbral accuracy

---

## Future Enhancements

Six prioritized improvement ideas, ordered by feasibility and impact:

### 1. Spectrum-Based Matching (High Impact, Medium Feasibility)

**Current:** RMS-only level correction. Compares average energy only.

**Idea:** Add FFT spectrum envelope matching to improve timbral accuracy.

**Implementation:**
- Extract FFT spectrum envelope from PCM reference (FluidSynth rendering)
- Compute spectrum envelope from OPM patch rendering
- Add spectrum mismatch term to DE objective function alongside RMS
- Weight: ~0.3–0.5 spectrum loss, ~0.5–0.7 RMS loss
- Focus on 200 Hz–4 kHz fundamental and harmonics where perception is sensitive

**Expected benefit:** Fixes timbral "wrongness" that RMS alone misses (e.g., Clarinet stays thin despite correct level).

**Code entry point:** `optimise_patch()`, extend fitness calculation around line 1000.

---

### 2. Percussion Bank Optimization (Medium Impact, High Feasibility)

**Current:** Melodic bank (programs 0–127) optimized via DE. Percussion (programs 128–255, fixed pitch) uses generic fallback.

**Idea:** Optimize percussion programs with pitch-specific strategy.

**Implementation:**
- Percussion bank uses fixed MIDI keys per program (e.g., program 128 = kick drum on C1)
- Instead of EVAL_NOTES (chromatic sweep), optimize single pitch per percussion program
- Warm-start TL/ADSR extraction: render at fixed percussion pitch, compare to PCM ref
- DE bounds: center on extracted values; percussion dynamics simpler than melodic
- Separate percussion pass: `--optimise-percussion` flag, or automatic detection

**Expected benefit:** Drums, cymbals, and percussion hits match FluidSynth timbre and attack character.

**Code entry point:** `load_existing_bank()` and `main()` to detect/branch percussion vs melodic.

---

### 3. Modulator ADSR Expansion (Medium Impact, Medium Feasibility)

**Current:** Carrier operators optimized (TL + ADSR). Modulators use warm-start only.

**Idea:** Include modulator ADSR (AR, D1R, D1L, D2R, RR) in DE bounds.

**Implementation:**
- Extract modulator ADSR warm-start from PCM reference (like carrier)
- Add modulator AR/D1R/D1L/D2R/RR to DE bounds_center
- Adjust bounds tightness: modulators have less perceptual impact, can use wider bounds
- Optional: weight modulator fitness lower than carrier in multi-objective function

**Expected benefit:** Refines FM timbral character (spectral brightness, attack sharpness from modulation rate). Especially useful for strings, pads, leads where modulation sweep is audible.

**Code entry point:** `_CARRIER_MASK` usage in `optimise_patch()` — extend to modulator operators.

---

### 4. Feedback and LFO Sensitivity Optimization (Lower Impact, Medium Feasibility)

**Current:** FB (feedback) and PMS/AMS (LFO sensitivity) are fixed at warm-start values from `gm.wopn`.

**Idea:** Include feedback level and LFO sensitivity in DE bounds.

**Implementation:**
- Add FB (3-bit, 0–7) and PMS/AMS (3+2 bits) to DE search space
- Set bounds around `gm.wopn` values with ±1–2 step tolerance (subtle range)
- Fitness: feedback affects harmonic balance; LFO affects vibrato/chorus depth
- May require higher DE iteration count due to expanded parameter space

**Expected benefit:** Subtle but measurable improvement in harmonic richness and modulation character.

**Risk:** Expanded parameter space increases optimization time; test on small program subset first.

**Code entry point:** `_get_bounds()`, extend to include fbalg/lfosens fields.

---

### 5. Expanded Note Range Evaluation (Medium Impact, Low Feasibility)

**Current:** EVAL_NOTES fixed to [C4, G4, C5, G5] (4 notes spanning 1 octave).

**Idea:** Extend evaluation to full MIDI range (C0–B8) or instrument-specific ranges.

**Implementation:**
- Profile optimization time on current 4-note set
- Expand to 8–12 notes: [C1, C2, C3, C4, C5, C6, C7] to cover register transitions
- Expected cost: 2–3× optimization time per program
- Alternative: Instrument category rules (e.g., Bass programs: C0–C3 focus; Synth: C3–C7)

**Expected benefit:** Uniform quality across keyboard; less "good on C4, harsh on C6" variation.

**Risk:** Significantly longer optimization runs; test with small subset (e.g., 10 programs) first.

**Code entry point:** `EVAL_NOTES` constant at module top.

---

### 6. Category-Specific Optimization Strategies (Medium Impact, High Feasibility)

**Current:** Single uniform bounds/warm-start for all melodic programs.

**Idea:** Apply different optimization strategies by instrument family.

**Implementation:**

| Category | Programs | Strategy |
|----------|----------|----------|
| **Strings** | 40–51 | Higher D2R (faster decay), tighter ADSR bounds (smooth sustain) |
| **Winds** | 68–79 | Aggressive AR (sharp attack), tight RR bounds, elevated TL range |
| **Keys** | 0–7, 16–23 | Moderate ADSR, centered FB for harmonic balance |
| **Synth Leads** | 80–87 | Wide bounds, enable LFO/FB variation |
| **Drums/SFX** | 112–127 | Single-note optimization, relaxed bounds |

**Implementation details:**
- Create `PROGRAM_CATEGORIES` dict mapping program → category
- Pass category to `optimise_patch()`, adjust `_get_bounds()` and `_CARRIER_MASK` logic per category
- Warm-start: use gm.wopn for baseline; PCM extraction may be category-conditional

**Expected benefit:** Optimizations respect instrument physics (strings sustain, winds decay fast, synths are flexible).

**Code entry point:** New constant `PROGRAM_CATEGORIES`, extend `optimise_patch()` signature.

---

### Implementation Priority

**Phase 1 (Recommended next):**
1. **Spectrum-based matching** — highest impact per effort, validates fitness function design
2. **Category-specific bounds** — quick win, reuses existing infrastructure

**Phase 2:**
3. **Percussion bank optimization** — handles neglected half of bank, moderate complexity
4. **Modulator ADSR** — extends carrier logic, good learning task

**Phase 3:**
5. **Expanded note range** — substantial time investment, diminishing returns after Phase 2
6. **Feedback/LFO tuning** — low impact unless specifically targeting vibrato/chorus character

### Testing & Validation

For each enhancement:
1. **Validation subset:** Wind programs (68, 71, 73) — known harsh baseline
2. **Quantitative:** `check_opm_bank.py` vs baseline fitness scores
3. **Listening test:** Play MIDI files with enhancement enabled; A/B vs old bank
4. **Regression:** Full test suite `--programs 0-127` on stable subset

---

## References

- **gen_opm_bank_v5.py** — Main optimization script (warm-start, DE loop, parallel execution)
- **fix_bank_levels.py** — Post-optimization RMS correction
- **check_opm_bank.py** — Validation and fitness scoring
- **opm_render.py** — OPM patch rendering via libmidiraja_vgm C library
- **gm.wopn** — GM2 reference OPM patch set (WOPN format, stored in ext/opm_gm_bank/)
- **FluidSynth** — PCM reference implementation (FluidR3_GM.sf3 soundfont)

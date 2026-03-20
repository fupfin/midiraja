# Design: Apple II / PC Speaker — Oversampled Cone Simulation

**Date:** 2026-03-20
**Status:** Approved
**Scope:** `OneBitHardwareFilter`, `CommonOptions`, `docs/retro-audio-engineering.md` (update existing), `RetroFiltersTest`

---

## Problem

`--retro apple2` and `--retro pc` use `OneBitHardwareFilter`, which applies `integratePwm()` to
compute the exact time-average of the PWM bit over each 44.1 kHz output sample. This produces a
mathematically perfect linear DAC output: all 5-bit / 6.3-bit quantisation harmonics are
modulated onto carrier sidebands above 20 kHz, leaving the audible band clean.

Frequency analysis of a 440 Hz test tone through `--retro apple2` confirms:
- Fundamental at 440 Hz: magnitude 35,174
- Harmonics at 880, 1320, … Hz: below magnitude 10 (< −71 dB relative)
- Carrier sidebands at 21–22 kHz: only audible artefact

A real 1-bit speaker cannot compute a perfect time-average because its mechanical cone responds
continuously to each pulse with finite settling time. This imperfect integration is what creates
the characteristic harmonic texture of 1-bit audio. The simulation is too clean.

### Why `integratePwm()` is mathematically ideal

With `carrierStep = 0.5` (22,050 Hz at 44,100 Hz sample rate), every carrier period spans exactly
two output samples. For any duty cycle `d`:

```
phase=0   → integratePwm output = 4d − 1   (or 1.0 when d ≥ 0.5)
phase=0.5 → integratePwm output = 4d − 3   (or −1.0 when d ≤ 0.5)
long-term LP average = (out_A + out_B) / 2 = 2d − 1   ← identical to ideal DAC
```

Quantisation of `d` to 32 levels introduces harmonics, but `integratePwm()` shifts them to
carrier sidebands at `22050 ± n×440 Hz` — entirely above 20 kHz.

### Why RC integration (CompactMacSimulatorFilter approach) does NOT apply

`CompactMacSimulatorFilter` models a physical RC capacitor on the Macintosh logic board (τ = 30 µs,
verified by hardware capture). Neither the Apple II nor the IBM PC has such a capacitor: both
drive the speaker directly via logic-level toggling. Their low-pass behaviour comes from the
mechanical inertia of the speaker cone, not an RC circuit.

Applying the RC formula to these modes would be physically inaccurate. The mathematically
equivalent exponential-decay formula holds for mechanical cones as well, but the τ value must be
derived from the cone's acoustic rolloff frequency rather than a known circuit component.

---

## Solution: Internal 4× Oversampling

Generate the actual ±1 bit sequence at an internal rate of 176,400 Hz (4×), apply the
speaker-cone model at that rate, then decimate 4:1 back to 44,100 Hz.

### Why 4×

| | Apple II | PC |
|---|---|---|
| Carrier | 22,050 Hz | 15,200 Hz |
| 176,400 Hz sub-samples per carrier period | **8.0 (exact)** | ≈ 11.6 |
| Carrier / 176,400 Hz Nyquist | 25% | 17% |

At 4×, both carriers are well inside the representable band. The Apple II carrier at 22,050 Hz
divides the 176,400 Hz rate into exactly 8 sub-samples per carrier period — no rounding error.
The PC carrier at 15,200 Hz gives ≈ 11.6 sub-samples per carrier period — non-integer, meaning
the PWM pulse transition falls mid-sub-sample. This causes minor rounding artefacts for the PC
mode only, but they appear above 88 kHz and are inaudible.

### Decimation

Simple 4:1 sample-drop (take every 4th sub-sample). The IIR rolls off sharply above a few kHz,
and the PC biquad peaks top out at 6.7 kHz — both are well below the 22,050 Hz alias fold
frequency of the 44,100 Hz output rate. No explicit anti-aliasing filter is needed.

### Core loop (pseudo-code)

```
duty = quantise(input, levels)       // same as before
for each of 4 sub-samples:
    bit = (carrierPhase < duty) ? +1.0 : -1.0
    apply speaker model (IIR, or IIR + biquads) to bit
    carrierPhase = (carrierPhase + subCarrierStep) % 1.0
output sample = speaker-model state after 4th sub-sample
```

Where `subCarrierStep = carrierHz / (44100.0 × 4)` = `carrierHz / 176400.0`. The loop adds
`subCarrierStep` once per sub-sample, advancing the carrier phase at the internal rate.

---

## Speaker Models

### Apple II — smooth cone rolloff

Two-pole cascaded IIR (same structure as the current `smoothL1/smoothL2` filter), but
re-parameterised for 176,400 Hz.

Current `smoothAlpha = 0.55` at 44,100 Hz encodes a mechanical time constant:

```
τ = −1 / (44100 × ln(1 − 0.55)) ≈ 28.4 µs
```

Equivalent α at 176,400 Hz:

```
α_sub = 1 − e^(−1 / (176400 × 28.4 × 10⁻⁶)) ≈ 0.181
```

This is a mechanical approximation. The Apple II speaker is a small, unenclosed cone whose
rolloff is empirically modelled this way. No RC circuit exists in the hardware path.

### PC Speaker — cone rolloff + resonance peaks

The IBM PC 8253 PIT drives the speaker transistor directly. The speaker is a small unshielded
paper cone mounted on the motherboard. Spectral analysis of original PC speaker recordings
(referenced in `docs/retro-audio-engineering.md`) shows:

- Overall rolloff: steep cliff at ~8 kHz (−66.6 dB), modelled by 2-pole IIR
- **Resonant peaks**: 2.5 kHz (+3 dB, Q ≈ 3) and 6.7 kHz (+4 dB, Q ≈ 4)

The resonances are characteristic of the lightweight paper cone vibrating in a steel chassis
without acoustic enclosure. They give the PC speaker its "gritty crunch" texture and are absent
from Apple II output (which used a proper enclosure with softer damping).

Current `smoothAlpha = 0.45` at 44,100 Hz → τ ≈ 37.9 µs → α_sub ≈ 0.140 at 176,400 Hz.

**Signal chain (PC):**
```
bit → 2-pole IIR (base rolloff) → biquad peak 2.5 kHz → biquad peak 6.7 kHz → output
```

#### Biquad specification

Peaking EQ biquad, **Direct Form I**, from the Audio EQ Cookbook (Bristow-Johnson):

```
A  = 10^(dBgain/40)
ω0 = 2π × f0 / 176400
α  = sin(ω0) / (2 × Q)

b0 =  1 + α×A,   b1 = −2×cos(ω0),   b2 = 1 − α×A
a0 =  1 + α/A,   a1 = −2×cos(ω0),   a2 = 1 − α/A

y[n] = (b0/a0)×x[n] + (b1/a0)×x[n−1] + (b2/a0)×x[n−2]
                     − (a1/a0)×y[n−1] − (a2/a0)×y[n−2]
```

State per biquad: `x1`, `x2`, `y1`, `y2` (four doubles).

Pre-computed normalised coefficients at 176,400 Hz (informative; implementation must derive
them at construction time, not hard-code):

| Peak | b0/a0 | b1/a0 | b2/a0 | a1/a0 | a2/a0 |
|------|-------|-------|-------|-------|-------|
| 2.5 kHz, +3 dB, Q=3 | ≈ 1.00507 | ≈ −1.96886 | ≈ 0.97484 | ≈ −1.96886 | ≈ 0.97992 |
| 6.7 kHz, +4 dB, Q=4 | ≈ 1.01340 | ≈ −1.89818 | ≈ 0.94330 | ≈ −1.89818 | ≈ 0.95670 |

---

## Code Changes

### `OneBitHardwareFilter.java`

#### Fields — remove
- `carrierStep` (renamed; see below)
- `smoothL1`, `smoothL2`, `smoothAlpha`
- `integratePwm()` method

#### Fields — add / rename
- `OVERSAMPLE = 4` (int constant)
- `subCarrierStep` — `carrierHz / (44100.0 * OVERSAMPLE)`. Added to `carrierPhase` once per
  sub-sample. (Equivalent to the old `carrierStep / 4` but stored directly.)
- `iirAlpha` — derived from `tauUs` via `1 − exp(−1 / (176400 × tauUs × 10⁻⁶))`
- `iirState1`, `iirState2` (double) — cone IIR state
- `biquad1Coeffs`, `biquad2Coeffs` (double[5] each: `{b0/a0, b1/a0, b2/a0, a1/a0, a2/a0}`, or null for apple2)
- `biquad1State`, `biquad2State` (double[4] each: `{x1, x2, y1, y2}`, or null for apple2)

#### Constructor change

Current:
```java
public OneBitHardwareFilter(boolean enabled, String mode,
        double carrierHz, double levels, float smoothAlpha, AudioProcessor next)
```

New:
```java
// import org.jspecify.annotations.Nullable;  (project-standard null annotation)
public OneBitHardwareFilter(boolean enabled, String mode,
        double carrierHz, double levels, double tauUs,
        double @Nullable [] resonancePeaks, AudioProcessor next)
```

- `mode` is **retained**. The `"dsd"` branch of `processOneSample()` is unchanged.
  Only the `"pwm"` branch is replaced by the oversampled cone simulation.
- `resonancePeaks` is a flat array of `{f0, dBgain, Q}` triplets for up to two resonance
  peaks, or `null` / empty for no resonance (apple2). Exactly two peaks are supported (for
  the PC's 2.5 kHz and 6.7 kHz resonances); more than two is not needed per YAGNI.

#### `reset()` — updated

Must zero all state fields (`dsdErr` is retained because the `"dsd"` branch is unchanged):
```java
carrierPhase = 0.0;
dsdErr = 0;
iirState1 = 0.0;
iirState2 = 0.0;
if (biquad1State != null) Arrays.fill(biquad1State, 0.0);
if (biquad2State != null) Arrays.fill(biquad2State, 0.0);
next.reset();
```

#### Silence fast-path — removed for `"pwm"` mode

The current `processOneSample()` has an early-return `if (abs(monoIn) < 1e-4) { out = 0.0; }`.
This path bypasses carrier-phase advance, causing phase drift during silence followed by a pop
when audio resumes. In the new oversampled loop, the fast-path is **removed for the `"pwm"`
branch**: silent input produces duty = 0.5, the bit alternates ±1, and the IIR averages to 0
naturally. The fast-path is retained only in the `"dsd"` branch where it is harmless.

### `CommonOptions.wrapRetroPipeline()`

```java
case "apple2" -> new OneBitHardwareFilter(true, "pwm", 22050.0, 32.0, 28.4, null, pipeline);
case "pc"     -> new OneBitHardwareFilter(true, "pwm", 15200.0, 78.0, 37.9,
                         new double[]{ 2500.0, 3.0, 3.0, 6700.0, 4.0, 4.0 }, pipeline);
```

### `docs/retro-audio-engineering.md` (update existing file)

- **Section 2 (Apple II):** add subsection explaining the algorithm change — why
  `integratePwm()` was replaced, why 4× oversampling rather than RC integration, how τ was
  derived from `smoothAlpha`.
- **Section 3 (PC):** same, plus document the resonance peaks, their empirical Q/gain values,
  and the biquad topology used.
- **Section 7 (Common Challenges):** update the aliasing strategy table to show oversampling
  as the new approach for apple2 and pc, alongside the Mac RC solution.
- **Mode summary table (top):** update "Character" column for apple2 and pc to reflect the
  audible harmonic change.

### `RetroFiltersTest.java`

Add a new test (or extend existing) that feeds a 1-second 440 Hz sine wave through the
`apple2` filter, computes an FFT over the output, and asserts:

1. **Fundamental preserved:** magnitude of 440 Hz bin ≥ 90% of the input fundamental magnitude.
2. **2nd harmonic present:** magnitude of 880 Hz bin ≥ 1% of the 440 Hz bin magnitude.
   Physical basis: ideal 5-bit (32-level) quantisation of a 0.8-amplitude sine produces THD of
   approximately 1/(32 × 2) ≈ 1.6%, with the dominant harmonic around −36 dB. 1% (−40 dB)
   is a conservative initial lower bound that confirms harmonic content exists. After the first
   passing run, record the observed value and tighten the threshold to just below it as a
   regression guard.

Add an equivalent test for the `pc` mode, plus a separate assertion that the resonance peaks at
2.5 kHz and 6.7 kHz have higher relative magnitude in the pc output than in the apple2 output.

---

## Parameters at a Glance

| Parameter | Apple II | PC |
|---|---|---|
| Carrier | 22,050 Hz | 15,200 Hz |
| Sub-samples per carrier period | 8.0 (exact) | ≈ 11.6 |
| Duty levels | 32 | 78 |
| τ (cone time constant) | 28.4 µs | 37.9 µs |
| α at 176,400 Hz | ≈ 0.181 | ≈ 0.140 |
| Resonance peaks | none | 2.5 kHz / 6.7 kHz |
| Internal sample rate | 176,400 Hz | 176,400 Hz |
| Oversample factor | 4× | 4× |

---

## Out of Scope

- `--retro compactmac`: RC circuit is physically correct; no change needed.
- `--retro spectrum`, `--retro covox`, `--retro amiga`: different hardware models; not affected.
- Exact resonance peak measurement for PC (empirical values used; future hardware capture could
  refine Q and gain figures).

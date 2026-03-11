# Retro Hardware Audio Simulation

This document is the unified engineering reference for all `--retro` hardware simulation modes in Midiraja. Each mode digitally reconstructs the audio signal path of its target hardware — quantization, carrier dynamics, analog filtering — as faithfully as possible within a 44.1 kHz digital environment.

## Mode Summary

| CLI Flag | Aliases | Filter Class | Carrier | Levels | Character |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `--retro compactmac` | — | `CompactMacSimulatorFilter` | 22.25 kHz PWM | 256 (8-bit) | Warm, muffled, heavy mono |
| `--retro apple2` | — | `OneBitHardwareFilter` | 22.05 kHz PWM | 32 (5-bit) | Crisp DAC-style, noise-free |
| `--retro pc` | — | `OneBitHardwareFilter` | 15.2 kHz PWM | 78 (6.3-bit) | Gritty crunch, carrier whine |
| `--retro spectrum` | — | `SpectrumBeeperFilter` | N/A (direct toggle) | 128 (7-bit) | Buzzy Z80 texture, beeper resonance |
| `--retro covox` | — | `CovoxDacFilter` | 11 kHz ZOH | 256 (8-bit) | R-2R harmonic warmth |
| `--retro disneysound` | — | `CovoxDacFilter` | 11 kHz ZOH | 256 (8-bit) | Parallel port DAC (LPT) |

> **Note on `--speaker`:** All retro modes incorporate their hardware speaker model internally. Do not combine with `--speaker` — see [Section 7](#7-the---speaker-option-and-retro-modes) for the technical rationale.

---

## 1. Compact Mac (`--retro compactmac`)

### 1.1 Hardware Background

The original Macintosh (1984) had no PCM DAC. The 68000 CPU was interrupted at the horizontal video flyback frequency (~22.2545 kHz) to write an 8-bit value into a buffer. This value controlled the duty cycle of a high-speed 1-bit PWM pulse train, which was then integrated by a physical RC low-pass filter on the logic board before reaching the internal 2-inch speaker.

### 1.2 The Nyquist Barrier

Midiraja runs at 44.1 kHz. Simulating a 22+ kHz PWM carrier inside a 44.1 kHz buffer creates a fundamental problem: the Nyquist limit. Drawing raw ±1 pulses at 44.1 kHz causes aliasing — a "siren tone" at ~21 kHz (the mirror image of the 22 kHz carrier) that has no physical basis.

### 1.3 Solution: Event-Driven Analytical Integration

Instead of approximating PWM pulses, the filter treats the analog RC stage as a continuous-time system:

$$\frac{dx}{dt} = \frac{u(t) - x(t)}{\tau}$$

Where $u(t)$ is the 1-bit PWM input (+1 or -1) and $x(t)$ is the capacitor voltage. For any constant-input interval, the exact solution is:

$$x(t_2) = u + (x(t_1) - u) \cdot e^{-\Delta t / \tau}$$

The implementation tracks Mac clock events (~22.25 kHz) and PWM transition events at sub-microsecond precision, stepping through them analytically and reading the capacitor voltage $x$ at each 44.1 kHz output sample point. This eliminates aliasing mathematically without oversampling.

### 1.4 Authentic Hardware Measurement (Mac Plus Capture, March 2026)

Spectral analysis of a pristine recording from a real Mac Plus ("Hard Stars Studio Session") revealed:

- **Carrier suppression:** The 22.25 kHz carrier was at **−90.8 dB** — the analog stage was near-perfect.
- **No "grit":** No asymmetric truncation or zero-crossing distortion was found.
- **Steep roll-off:** Response drops sharply from 5 kHz, reaching **−84 dB at 10 kHz**.

| Frequency | Level | Character |
| :--- | :--- | :--- |
| 100 Hz | −28.6 dB | Solid bass support |
| 500 Hz | −31.5 dB | Fundamental body |
| 1,000 Hz | −41.3 dB | Lower mids |
| 3,000 Hz | −47.9 dB | Presence region |
| 5,000 Hz | −51.6 dB | Start of steep roll-off |
| 7,000 Hz | −64.1 dB | Aggressive attenuation |
| 10,000 Hz | **−83.8 dB** | Measured "muffle" point |
| 22,050 Hz | −99.3 dB | PWM carrier suppressed |

### 1.5 Parameters

| Parameter | Value | Purpose |
| :--- | :--- | :--- |
| Channel mode | Forced mono | Matches physical mono-only hardware |
| Quantization | Symmetrical 8-bit | Clean 8-bit buffer (Math.round) |
| RC τ | 30.0 µs | Matches the measured −84 dB @ 10 kHz curve |
| Mac sample rate | 22,254.5 Hz | Tied to horizontal video flyback |

---

## 2. Apple II (`--retro apple2`)

### 2.1 Hardware Background

The Apple II had no sound hardware at all. The CPU manually toggled a speaker I/O port ($C030) using cycle-counted 6502 machine code. Every audio operation consumed 100% of the 1.0205 MHz CPU.

The early SoftDAC technique (used in *Digitized Sound for the Apple II* and similar programs) drove the speaker at an ~11 kHz sample rate by counting CPU cycles:

$$1{,}020{,}500 \text{ Hz} \div 93 \text{ cycles} \approx 10{,}973 \text{ Hz} \approx 11 \text{ kHz}$$

Each 93-cycle period produced ~6.5 bits of effective resolution — but the 11 kHz carrier fell squarely in the audible range, producing a painful screech.

### 2.2 The DAC522 Technique

*DAC522* (described by Scott Alfter at KansasFest) solved the screech problem by halving the period from 93 cycles to 46 cycles, raising the carrier frequency to 22 kHz — above the human hearing limit — while preserving the ~11 kHz audio sample rate through a two-pulse encoding scheme:

$$46 \text{ cycles} \times 2 = 92 \text{ cycles} \approx 93 \text{ cycles} \approx 11 \text{ kHz}$$

Each audio sample is encoded as **two consecutive 46-cycle pulses**. The pair together approximates the original 93-cycle sample period. The 22 kHz carrier noise is inaudible; only the audio content at 11 kHz is heard.

Each pulse uses discrete widths of 6 to 37 cycles out of a 46-cycle period — **32 levels**, or approximately 5-bit resolution.

| | SoftDAC (original) | DAC522 |
| :--- | :--- | :--- |
| Period | 93 cycles | 46 cycles × 2 |
| Carrier | ~11 kHz (audible) | ~22 kHz (inaudible) |
| Levels per pulse | ~93 | 32 (6–37 cycle widths) |
| Screech | Yes | No |

### 2.3 Parameters

| Parameter | Value | Purpose |
| :--- | :--- | :--- |
| Carrier | 22,050 Hz | Above hearing limit |
| Levels | 32 | 5-bit: pulse widths 6–37/46 |
| Smooth α | 0.55 | Apple II speaker natural cone rolloff |

In the implementation, `carrierHz=22050` at `sampleRate=44100` means `carrierStep=0.5`: each carrier period spans two consecutive 44.1 kHz audio output samples, mirroring the two-pulse encoding of DAC522.

---

## 3. PC Speaker (`--retro pc`)

### 3.1 Hardware Background

The IBM PC internal speaker was a pure 1-bit device. To play sampled audio through it, software used the Intel 8253 PIT timer channel 2 (base clock 1.19318 MHz) as the timing reference for PWM generation. Each sample period, the CPU programmed the pulse width proportional to the sample amplitude — HIGH for `t₁` cycles, LOW for the remainder.

The theoretical maximum — using all 64 PIT ticks per sample period — yields 18.64 kHz at 6-bit (64 levels). However, empirical FFT analysis of original `.wav` demos found the actual rate at **15.2 kHz**, because developers used 78 steps per period instead of 64, giving the 8088 CPU more time between samples to decode compressed audio:

$$1{,}193{,}182 \text{ Hz} \div 78 \approx 15{,}297 \text{ Hz} \approx 15.2 \text{ kHz}, \quad \approx 6.3 \text{ bits}$$

### 3.2 Acoustic Characteristics

Spectral analysis of authentic PC speaker recordings (March 2026):
- **15.2 kHz carrier spike** — audible; the characteristic gritty "crunch"
- **Resonant peaks at 2.5 kHz and 6.7 kHz** — small unshielded paper cone resonance
- **Steep cliff at 8 kHz (−66.6 dB)** — mechanical cone inertia is the primary filter
- No dedicated analog filter; the cone itself does the integration

### 3.3 Parameters

| Parameter | Value | Purpose |
| :--- | :--- | :--- |
| Carrier | 15,200 Hz | Empirically measured from original demos |
| Levels | 78 | 1.19318 MHz / 78 ≈ 15.3 kHz, ~6.3-bit |
| Smooth α | 0.45 | Paper cone mechanical rolloff |

> For deeper technical background, see `docs/realsound-pwm-engineering.md`.

---

## 4. ZX Spectrum (`--retro spectrum`)

### 4.1 Hardware Background

The ZX Spectrum's speaker, like the Apple II, was a 1-bit device toggled directly by the Z80 CPU (bit 4 of port $FE). Software audio on the Spectrum relied on cycle-counted Z80 routines at a 3.5 MHz clock.

With a ~17.5 kHz carrier, the Z80 provides approximately:

$$3{,}500{,}000 \text{ Hz} \div 17{,}500 \text{ Hz} = 200 \text{ steps} \approx 7.6 \text{ bits}$$

### 4.2 Acoustic Characteristics

The Spectrum's tiny square speaker had a different resonance profile from the IBM PC's larger cone. Its output was more "buzzy" due to the speaker's physical construction.

### 4.3 Direct-Toggle Model

Unlike PWM-based hardware (PC speaker, Apple II), the Spectrum does not use a carrier frequency at all. The Z80 simply writes a bit to toggle the speaker voltage. `SpectrumBeeperFilter` models this directly: input audio is quantized to 128 discrete Z80 amplitude steps (~7-bit), then shaped through a physical beeper model with a high-pass and two-stage low-pass filter.

There is no carrier integration step; the aliasing problems that affect `OneBitHardwareFilter` do not apply here.

### 4.4 Parameters

| Parameter | Value | Purpose |
| :--- | :--- | :--- |
| Carrier | N/A | Direct bit toggle; no PWM carrier |
| Levels | 128 | Z80 3.5 MHz / ~27 cycles per step ≈ 7-bit |
| HP α | 0.930 | ~510 Hz high-pass; 22mm beeper bass limit |
| LP α | 0.600 | 2× ~4.5 kHz low-pass; small diaphragm inertia |

---

## 5. Covox Speech Thing (`--retro covox`, `8bit`)

### 5.1 Hardware Background

The Covox Speech Thing (1986) was an 8-bit R-2R resistor ladder DAC connected directly to the IBM PC parallel (LPT) port. Unlike PWM-based audio, it provided true 8-bit PCM output — but with characteristic limitations:

- **R-2R resistor variance:** Real-world ±3% component tolerances introduced gentle harmonic distortion across all 256 levels.
- **CPU-limited sample rate:** The LPT port driven by DOS software rarely exceeded ~11 kHz in practice (some sources cite ~22 kHz possible on fast 286/386 systems; 11 kHz was typical in 8088-era games).
- **Minimal analog filtering:** A simple RC filter smoothed the 8-bit steps, with a characteristic ~2.12 kHz cutoff (15 kΩ + 5 nF).

### 5.2 Simulation Model

The filter builds a fixed LUT of 256 R-2R DAC output levels using a seeded pseudo-random resistor variance (±3%), computed once at initialization:

- **ZOH at ~11 kHz** (hold for 4 samples at 44.1 kHz): simulates DOS CPU overhead bottleneck
- **R-2R nonlinearity via LUT**: produces authentic harmonic distortion
- **RC LPF α = 0.26** (≈ 2.12 kHz cutoff): simulates the physical capacitor

The Covox was mono. Both channels receive the same mixed-down signal.

> `--retro disneysound` uses the same filter. The Disney Sound Source was also a parallel port R-2R DAC, structurally identical to the Covox Speech Thing.

---

## 6. Common Engineering Challenges

### Aliasing at 44.1 kHz

All PWM-based modes face the same problem: the carrier frequency is comparable to or exceeds the 44.1 kHz output sample rate, causing Nyquist aliasing if raw pulses are rendered.

Two strategies are used:
- **Mac 128k**: Analytical integration (exact RC exponential formula — zero aliasing by definition)
- **PC, Apple II** (`OneBitHardwareFilter`): Analytical area integration via `integratePwm()`, which computes the exact time-averaged output over each output sample interval rather than evaluating the pulse at a single point
- **Spectrum** (`SpectrumBeeperFilter`): No carrier; direct amplitude quantization avoids the aliasing problem entirely

### The 44.1 kHz / Carrier Frequency Relationship

For `OneBitHardwareFilter`, `carrierStep = carrierHz / sampleRate`:

| Mode | carrierStep | Carrier periods per output sample |
| :--- | :--- | :--- |
| apple2 | 0.500 | 2 output samples per carrier cycle |
| pc | 0.345 | ~2.9 output samples per carrier cycle |

The apple2 mode's exact `carrierStep=0.5` is what makes the two-pulse encoding natural: each carrier period spans exactly two output samples, mirroring the DAC522 hardware behavior.

---

## 7. The `--speaker` Option and Retro Modes

The `--speaker` flag applies an `AcousticSpeakerFilter` — a post-processing acoustic coloration stage that models the frequency response of vintage speaker cabinets. It is a useful standalone effect for modern synthesis engines, but it interacts badly with `--retro` modes.

### Why They Conflict

Every `--retro` mode already models its physical speaker as an integral part of its signal chain. The speaker is not a separable add-on; it defines the mode's sound:

| Retro Mode | Speaker Model Built In |
| :--- | :--- |
| `compactmac` | RC integration (τ = 30 µs) models the 2-inch Mac speaker; −84 dB at 10 kHz |
| `spectrum` | HP (α = 0.930, ~510 Hz) + 2× LP (α = 0.600, ~4.5 kHz) models the 22mm 40Ω beeper |
| `pc` | Smooth α = 0.45 (`OneBitHardwareFilter`) models the IBM PC's unshielded paper cone |
| `apple2` | Smooth α = 0.55 models the Apple II speaker's natural cone rolloff |
| `covox` / `disneysound` | RC LPF (α = 0.26, ~2.12 kHz) models the parallel-port analog circuit |

Applying `--speaker` on top of any of these chains a second EQ/filter stage with no physical basis. The result is an over-filtered signal. For example:

- `--retro spectrum --speaker tin-can` applies the beeper's HP (~510 Hz) and dual LP (~4.5 kHz), then stacks the `tin-can` profile's additional band-limiting on top. No Spectrum hardware had two stacked speaker models.
- `--retro compactmac --speaker warm-radio` re-filters audio that `CompactMacSimulatorFilter` has already shaped against real Mac Plus measurements. The combined response departs from the measured curve.

### The Pipeline Execution Order

The `wrapRetroPipeline()` method in `CommonOptions` applies `--speaker` first (innermost), then `--retro` (outermost). In the pull-based pipeline, processing flows from outermost inward, so the actual execution order is:

```
retro filter → speaker filter → sink
```

This means the retro mode's quantization and carrier integration sees the already-speaker-coloured signal as its input — which is also physically incorrect. Real hardware quantized the raw audio signal before the speaker received it.

### When `--speaker` Is Appropriate

`--speaker` is designed for use **without** `--retro` — to add vintage character to a modern synthesis engine that produces a clean, full-range output:

```bash
# Correct: adds tin-can coloring to a clean FluidSynth render
midra fluidsynth piano.sf2 --speaker tin-can song.mid

# Correct: warm-radio coloring over OPL (no built-in speaker model)
midra opl --speaker warm-radio song.mid

# Not recommended: doubles the speaker model
midra opl --retro pc --speaker tin-can song.mid
```

Midiraja does not automatically suppress `--speaker` when `--retro` is active — the combination is permitted but physically inaccurate. For authentic period sound, use `--retro` alone and rely on its built-in speaker model.

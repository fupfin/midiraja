# Retro Hardware Audio Simulation

This document is the unified engineering reference for all `--retro` hardware simulation modes in Midiraja. Each mode digitally reconstructs the audio signal path of its target hardware — quantization, carrier dynamics, analog filtering — as faithfully as possible within a 44.1 kHz digital environment.

## Mode Summary

| CLI Flag | Aliases | Filter Class | Carrier | Levels | Character |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `--retro compactmac` | — | `CompactMacSimulatorFilter` | 22.25 kHz PWM | 256 (8-bit) | Warm, muffled, heavy mono |
| `--retro apple2` | — | `OneBitHardwareFilter` | 22.05 kHz PWM | 32 (5-bit) | 5-bit harmonic texture, cone rolloff |
| `--retro pc` | — | `OneBitHardwareFilter` | 15.2 kHz PWM | 78 (6.3-bit) | Gritty crunch, 6-pole cone IIR, carrier at −68 dB |
| `--retro spectrum` | — | `SpectrumBeeperFilter` | N/A (direct toggle) | 128 (7-bit) | Buzzy Z80 texture, beeper resonance |
| `--retro covox` | — | `CovoxDacFilter` | 11 kHz ZOH | 256 (8-bit) | R-2R harmonic warmth |
| `--retro disneysound` | — | `CovoxDacFilter` | 11 kHz ZOH | 256 (8-bit) | Parallel port DAC (LPT) |
| `--retro amiga`, `--retro a500` | — | `AmigaPaulaFilter` | 22 kHz ZOH | 256 (8-bit) | Warm stereo, LED-filtered, hard-pan |
| `--retro a1200` | — | `AmigaPaulaFilter` | 22 kHz ZOH | 256 (8-bit) | Bright stereo, AGA near-transparent |

> **Note on `--speaker`:** All retro modes incorporate their hardware speaker model internally. Do not combine with `--speaker` — see [Section 8](#8-the---speaker-option-and-retro-modes) for the technical rationale.

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
| τ (cone time constant) | 28.4 µs | Derived from original smoothAlpha=0.55 via τ = −1/(44100 × ln(1−0.55)) |
| IIR α (at 176,400 Hz) | 0.1487 | = 1 − exp(−1/(176400 × 28.4e-6)) |
| IIR poles | 6 (cascaded) | Shared with pc mode — see §3.5 |

In the implementation, `carrierHz=22050` at `sampleRate=44100` means `carrierStep=0.5`: each carrier period spans two consecutive 44.1 kHz audio output samples, mirroring the two-pulse encoding of DAC522.

### 2.4 Simulation Algorithm

Earlier versions used `integratePwm()`, which computes the exact time-average of the PWM duty
cycle over each 44.1 kHz output sample. This produces a mathematically perfect linear DAC
output: quantisation harmonics are modulated onto carrier sidebands above 20 kHz, leaving the
audible band completely clean — too clean for authentic 1-bit character.

The current implementation uses **4× internal oversampling** (176,400 Hz). At each sub-sample,
the raw ±1 PWM bit is evaluated directly and fed to a **six-pole IIR** (six cascaded one-pole
stages) modelling the mechanical cone (τ = 28.4 µs, derived from the empirical rolloff
frequency). The result is decimated 4:1 to 44,100 Hz. The pole count is shared with `--retro pc`
and was determined by carrier suppression requirements — see §3.5.

Why oversampling rather than RC integration (as in `--retro compactmac`)?
The Compact Mac had a physical RC capacitor on its logic board; τ was measured from hardware
captures. The Apple II has no such capacitor — the speaker is driven directly and filtered only
by the cone's mechanical inertia. Using the RC label for a mechanical system would be
physically inaccurate. Oversampling sidesteps this: it makes no assumptions about the filter
topology and lets the IIR model the cone empirically.

The choice of 4× is deliberate — see §2.5 below for the full analysis shared with the PC mode.

---

### 2.5 Design Decisions: Why 4× Oversampling (Apple II & PC)

`OneBitHardwareFilter` is shared by both `--retro apple2` and `--retro pc`. Three arguments
for the 4× oversampling factor were examined during development. Only one proved valid.

**❌ Argument 1: "Duty levels are not representable at 1×"**

Claim: with only ~2–3 output samples per carrier period, adjacent duty levels produce identical
±1 bit sequences and cannot be distinguished by the IIR.

- Apple II: 22,050 Hz carrier, 32 levels, 1/32 duty step. At 1× there are exactly 2.0
  samples per carrier period — the carrier sits exactly at Nyquist.
- PC: 15,200 Hz carrier, 78 levels, 1/78 duty step. At 1× there are ~2.9 samples per period.

This is incorrect. `duty = round(input × levels) / levels` is stored as a `double`, and
`carrierPhase` is also a `double` advancing by `carrierHz / sampleRate` per sample. The
comparison `carrierPhase < duty` uses full floating-point precision. Over many carrier cycles
the IIR's long-term average converges to a distinct value for every discrete duty level. No
integer sample alignment is required.

**❌ Argument 2: "Hardware clock resolution requires high oversampling"**

Claim: the original hardware generates duty cycle edges at the resolution of its master clock —
Apple II at 1.02 MHz (1/46 of the 22 kHz carrier period ≈ 980 ns), PC PIT at 1.19 MHz
(1/78 of the 15.2 kHz carrier period ≈ 838 ns). Reproducing this requires a sample rate of
~1 MHz, i.e., ~23–27× oversampling.

Also incorrect. The clock-tick resolution is the edge-timing precision of the *original analog
hardware*. In our simulation, `duty = k/levels` is a continuous floating-point threshold
compared against a floating-point phase accumulator. The transition point is not snapped to
integer sub-sample boundaries — it is evaluated with double precision at each step. The
floating-point representation already captures 1/levels precision (and far beyond) at 1×,
with no additional oversampling needed for timing fidelity.

**✅ Argument 3: "Cone IIR needs sufficient sub-samples per carrier period"**

The valid reason. The IIR models the speaker cone's mechanical inertia by filtering the ±1 PWM
bit stream. For accurate step response — and therefore the correct harmonic texture — the IIR
must be updated frequently enough within each carrier cycle. Too few steps per period produces
a coarse approximation that sounds wrong.

The rule of thumb for adequate IIR step response is 4–8 samples per period of the filtered signal:

| Oversampling | Internal rate | Steps / period (Apple II 22,050 Hz) | Steps / period (PC 15,200 Hz) | Assessment |
| :--- | :--- | :--- | :--- | :--- |
| 1× | 44,100 Hz | 2.0 ❌ (at Nyquist) | 2.9 ❌ | Inadequate |
| 2× | 88,200 Hz | 4.0 △ | 5.8 △ | Borderline |
| 4× | 176,400 Hz | **8.0 ✅** | **11.6 ✅** | Adequate |
| 8× | 352,800 Hz | 16.0 | 23.2 | Excessive |

4× is the minimum choice that clears the threshold for both modes. The Apple II carrier at
22,050 Hz divides 176,400 Hz exactly (8.0 sub-samples per period, no rounding), making 4× a
particularly clean fit. The PC's non-integer 11.6 produces rounding artefacts only above
88 kHz, which is inaudible.

---

## 3. PC Speaker (`--retro pc`)

### 3.1 Hardware Background

The IBM PC internal speaker was a pure 1-bit device. To play sampled audio through it, software used the Intel 8253 PIT timer channel 2 (base clock 1.19318 MHz) as the timing reference for PWM generation. Each sample period, the CPU programmed the pulse width proportional to the sample amplitude — HIGH for `t₁` cycles, LOW for the remainder.

The theoretical maximum — using all 64 PIT ticks per sample period — yields 18.64 kHz at 6-bit (64 levels). However, empirical FFT analysis of original `.wav` demos found the actual rate at **15.2 kHz**, because developers used 78 steps per period instead of 64, giving the 8088 CPU more time between samples to decode compressed audio:

$$1{,}193{,}182 \text{ Hz} \div 78 \approx 15{,}297 \text{ Hz} \approx 15.2 \text{ kHz}, \quad \approx 6.3 \text{ bits}$$

### 3.2 Acoustic Characteristics: Reference Recording Analysis

Welch PSD analysis of `samples/realsound_sample.wav` (original RealSound recording,
48,000 Hz stereo, 37.8 s, mono RMS −38 dBFS):

| Frequency | Level relative to 1 kHz | Interpretation |
| :--- | :--- | :--- |
| 100 Hz | −1.7 dB | Normal music content |
| 800 Hz | −2.1 dB | Near-flat |
| 1,000 Hz | 0 dB | Reference |
| 1,346 Hz | −2.4 dB | Rolloff beginning |
| 2,000 Hz | −18.7 dB | Steep cone rolloff |
| 2,500 Hz | −23.2 dB | Deep rolloff (no resonance peak) |
| 6,700 Hz | −22.4 dB | Slight recovery — but see §3.5 |
| 15,200 Hz (carrier) | −31.5 dB | ≈ noise floor at 18 kHz (−32.7 dB) |
| 18,000 Hz | −32.7 dB | Recording noise floor |
| 20,000 Hz | −77.4 dB | Recording chain cutoff |

Key findings:

- **The carrier is not a visible spike.** At 15,200 Hz the reference measures −31.5 dB, which
  is effectively the same level as the 18 kHz noise floor (−32.7 dB). The real hardware's
  cone mass suppressed the carrier well below the noise floor of the recording — it was not
  "audible" in the classic whistle sense when heard through the actual hardware cone.

- **The −3 dB rolloff is near 1.4 kHz.** Response drops steeply above 1–1.5 kHz: −18.7 dB
  at 2 kHz, −29.3 dB at 4 kHz. This matches the 6-pole IIR rolloff (calculated −3 dB at 1,435 Hz).

- **The bumps at 6,947 Hz and 9,626 Hz are music content, not speaker resonances.**
  STFT temporal analysis showed a coefficient of variation (CV) > 1.2 at both frequencies —
  meaning their energy varies strongly over time, tracking the music, not maintaining a steady
  resonance level. A physical speaker resonance would appear as a constant-amplitude peak
  independent of what music is playing.

- **No evidence for resonance peaks at 2.5 kHz or 6.7 kHz as speaker characteristics.**
  The reference records −23 dB at 2.5 kHz and −22 dB at 6.7 kHz — both in the heavily
  attenuated rolloff region. Adding peaking biquads at these frequencies would produce
  unphysical mid-frequency boosts in a region the real cone barely moved in.

### 3.3 Parameters

| Parameter | Value | Purpose |
| :--- | :--- | :--- |
| Carrier | 15,200 Hz | Empirically measured from original demos |
| Levels | 78 | 1.19318 MHz / 78 ≈ 15.3 kHz, ~6.3-bit |
| Pre-quantization | 8-bit (128 levels) | Source material bit-depth simulation — see §3.4 |
| Drive gain | 4.0× (default) | Improves PWM duty-cycle SNR — see §3.4 and §3.7 |
| τ_e (electrical, voice coil) | 10 µs | RL inductance of cheap speaker coil (L ≈ 0.1 mH, R ≈ 8 Ω) |
| IIR α_e (at 176,400 Hz) | 0.433 | = 1 − exp(−1/(176400 × 10e-6)) |
| τ_m (mechanical, cone) | 37.9 µs | Derived from original smoothAlpha=0.45 via τ = −1/(44100 × ln(1−0.45)) |
| IIR α_m (at 176,400 Hz) | 0.1395 | = 1 − exp(−1/(176400 × 37.9e-6)) |
| IIR poles | 7 total (1 electrical + 6 mechanical) | See §3.4 and §3.5 |
| Resonance biquads | none | See §3.2 — reference provides no evidence for speaker peaks |

> For deeper technical background on the PWM encoding, see `docs/realsound-pwm-engineering.md`.

### 3.4 Simulation Algorithm

Same 4× oversampling approach as apple2 (§2.4). Signal path per output sample:

```
monoIn × driveGain → clamp[−1,1] → 8-bit pre-quantize
  → PWM duty cycle (78 levels)
  → [4× oversampled loop]
      bit (±1)
        → iirStatePre  (electrical: τ_e = 10 µs, α_e = 0.433)
        → iirState1..6 (mechanical: τ_m = 37.9 µs, α_m = 0.1395)
  → iirState6 ÷ driveGain
  → output
```

**8-bit pre-quantization.** The source material available to 1980s PC Speaker developers was
8-bit PCM. Before being sent to the PWM encoder, audio was already limited to 256 discrete
amplitude levels (128 bipolar). Applying `round(monoIn × 128) / 128` before PWM encodes this
constraint: the signal arrives at the duty-cycle quantizer in coarser steps, producing the
characteristic "staircase" texture of period hardware. No dither is applied — 1980s tools used
simple rounding.

Note: since 128 pre-quantization levels > 78 PWM levels, the pre-quantization does not narrow
the effective resolution. Its role is temporal: it makes the duty cycle hold steady between
steps instead of tracking every floating-point fluctuation, which creates the audible step
texture without adding noise.

**Drive gain.** The input is scaled by driveGain before quantization and divided by the same
factor after the IIR. Higher gain forces the signal to use more of the 78-level PWM duty-cycle
range, reducing the PWM quantization error at carrier sideband frequencies (see §3.7). The
default is 4.0×, chosen to optimise S/N for typical synthesizer output levels around −18 dBFS
with no hard clipping. Signals above 1/driveGain (= 0.25, i.e. above −12 dBFS) are
hard-clipped before PWM encoding — which is authentic: real 1980s PC Speaker developers drove
at maximum amplitude. The gain can be overridden with `--retro-drive`.

**Electrical pre-filter (voice coil inductance).** A cheap PC speaker voice coil is an
inductor (L ≈ 0.1–0.3 mH, R ≈ 8 Ω). The current through an RL circuit does not follow a step
in voltage instantaneously — it rises exponentially with τ_e = L/R ≈ 10 µs. Because the cone
moves in proportion to current (not voltage), the ideal ±1 square wave voltage becomes a
triangle-wave-like current waveform before the mechanical system sees it. `iirStatePre` models
this: it is a single one-pole IIR at 176,400 Hz with α_e = 0.433 (τ_e = 10 µs), inserted
before the six mechanical poles.

**Six cascaded mechanical IIR poles** model the speaker cone's inertia — see §3.5 for the
carrier suppression analysis that determines the pole count.

The combined 7-pole response gives:
- −3 dB at ~1,400 Hz (cone rolloff beginning)
- −68 dB at 15,200 Hz carrier (inaudible even at −50 dBFS signal levels)

**No resonance biquads.** Earlier versions included peaking EQ biquads at 2.5 kHz and 6.7 kHz.
Welch PSD analysis of the reference recording found no evidence of speaker resonance at these
frequencies (§3.2). The spectral bumps observed near 6.9–9.6 kHz were confirmed to be music
harmonics via temporal variance analysis (CV > 1.2). The biquads were removed.

### 3.5 Carrier Noise Analysis: Why Six IIR Poles

The IIR model must suppress the 15.2 kHz carrier sufficiently that it remains inaudible even
during quiet passages. The pole count determines this suppression.

**Single-pole magnitude at 15,200 Hz** (α = 0.1395, fs = 176,400 Hz):

$$|H_1(15\,200)| = \frac{\alpha}{\sqrt{(1-(1-\alpha)\cos\omega)^2 + ((1-\alpha)\sin\omega)^2}} \approx 0.270$$

where $\omega = 2\pi \times 15200 / 176400$.

With $N$ cascaded poles, $|H_N| = 0.270^N$. Carrier suppression vs. audibility:

| Poles | Carrier attenuation | Carrier audible above | Engineering assessment |
| :---: | :---: | :---: | :--- |
| 2 | −23 dB | −23 dBFS signals | Overwhelms all quiet passages |
| 4 | −46 dB | −46 dBFS signals | Still audible over quiet segments (< −10 dBFS) |
| 6 | **−68 dB** | **−68 dBFS signals** | Inaudible even at −50 dBFS signal levels |

**Why 2 poles were insufficient (observed in testing):**

At duty cycle 50% (zero DC content), the PWM fundamental amplitude is 2/π ≈ 0.637. With
2-pole attenuation (−23 dB), the carrier residual is ~4.7% of full scale. For a signal at
−30 dBFS (a quiet passage), the carrier-to-signal ratio is only −7 dB — the carrier is
louder than the music.

**Why 6 poles suffice:**

The reference recording confirms the real hardware suppressed the carrier below the recording
noise floor (−31.5 dB at 15.2 kHz ≈ −32.7 dB noise floor). This means the actual hardware
attenuation was at least 32 dB, but the mechanical cone mass provided substantially more.
Six poles at −68 dB comfortably exceeds this threshold. The rolloff shape (−3 dB at 1,435 Hz)
also matches the reference spectrum shape well.

**Six poles also better models the physics:** A real PC speaker cone barely moves at 15 kHz.
The 36 dB/octave asymptotic slope of 6 cascaded first-order filters approximates the steep
mechanical rolloff above the cone's resonance frequency.

### 3.6 PWM Carrier Sideband Characteristics

#### What Sidebands Are

PWM modulation produces not just the carrier frequency but sum-and-difference intermodulation products. When a signal of frequency `f` is encoded as PWM with carrier `f_c`, spectral energy appears at:

$$f_\text{sideband} = f_c - k \cdot f \quad (k = 1, 2, 3, \ldots)$$

At carrier 15,200 Hz with A4 (440 Hz), sidebands at k=29,30,31 land in the audio band:

| k | f_c − k × 440 | In band? |
| :---: | :---: | :---: |
| 29 | 2,440 Hz | Yes |
| 30 | 2,000 Hz | Yes |
| 31 | 1,560 Hz | Yes |

These sidebands are a real artifact of the hardware. What matters perceptually is how close they fall to existing signal harmonics — if they fall on top of harmonics, they are masked and inaudible; if they fall between harmonics, they emerge as distinct noise.

#### The Sideband-Harmonic Distance

For a signal of fundamental frequency `f`, harmonics land at `k × f` (k = 1, 2, 3…). A sideband `f_c − k × f` falls at the same frequency as the nearest harmonic only if `f_c / f` is an integer. The maximum sideband-harmonic distance is:

$$d_\text{max} = \min\!\left(\text{frac}\!\left(\frac{f_c}{f}\right),\; 1 - \text{frac}\!\left(\frac{f_c}{f}\right)\right) \times f$$

where frac(x) is the fractional part. This is maximised when `f_c / f` is exactly a half-integer — the sidebands land precisely halfway between harmonics.

#### Why A4 Is Particularly Problematic

$$\frac{15{,}200}{440} = 34.545\ldots \approx 34.5$$

The ratio is nearly exactly a half-integer. Sidebands land ~200 Hz from the nearest harmonics (1760 Hz and 2200 Hz), and the 7-pole IIR barely attenuates them at 1,560–2,440 Hz — this is well below the −3 dB point. Spectral measurements of the simulation confirm sidebands at −47 dB, compared to a true noise floor of −137 to −145 dB between harmonics.

Note-by-note sideband exposure (carrier 15,200 Hz, 4× oversampled):

| Note | Freq (Hz) | 15200/f | Max sideband dist | Verdict |
| :--- | :---: | :---: | :---: | :--- |
| E1 | 41.2 | 368.9 | 37 Hz | Favorable — sidebands near harmonics |
| A2 | 110.0 | 138.2 | 22 Hz | Favorable |
| E2 | 82.4 | 184.5 | 41 Hz | Borderline |
| A3 | 220.0 | 69.1 | 22 Hz | Favorable |
| D4 | 293.7 | 51.8 | 176 Hz | Exposed |
| E4 | 329.6 | 46.1 | 33 Hz | Favorable (near-integer) |
| **A4** | **440.0** | **34.5** | **200 Hz** | **Maximally exposed** |
| B4 | 493.9 | 30.8 | 176 Hz | Exposed |
| E5 | 659.3 | 23.1 | 33 Hz | Favorable (near-integer) |

Notes whose `f_c / f` ratio is near an integer or half-integer are favorable or maximally exposed respectively. No single carrier frequency is simultaneously favorable for all notes in equal temperament, since the frequency ratios are irrational.

#### Why the Reference Recording Doesn't Show It

The reference WAV (`samples/realsound_sample.wav`) has a dominant fundamental near 82 Hz. For this note:

$$\frac{15{,}200}{82} \approx 185.4 \quad \Rightarrow \quad d_\text{max} = 0.4 \times 82 \approx 33 \text{ Hz}$$

The sidebands fall within 33 Hz of the 82 Hz harmonics — too close to separate from them perceptually or analytically. The reference recording was produced with bass-heavy musical content (lower register melody and accompaniment). RealSound composers apparently gravitated toward lower registers empirically for their warmer sound — which coincidentally kept carrier sidebands close to existing harmonics, hiding the effect.

#### Why Carrier Frequency Substitution Doesn't Help

A brute-force search over candidate carriers from 14 kHz to 22 kHz found that the optimal choice — 15,320 Hz — reduces the maximum sideband distance for A4 from 640 Hz to 520 Hz. The improvement is minor because equal temperament's irrational frequency ratios mean no single carrier can be simultaneously near-integer for all 12 semitone classes. The 15,200 Hz carrier is historically determined (8253 PIT, 78 ticks); changing it would be inauthentic.

#### Why 8-Bit Quantization Doesn't Mask It

8-bit pre-quantization produces its own noise at non-harmonic frequencies. For a single clean sinusoidal input (440 Hz, −6 dBFS), quantization noise in the 1.5–2.5 kHz band measures approximately −78 dB (relative to peak). The carrier sideband at the same location measures −47 dB. The sideband is 31 dB louder than quantization noise — masking is impossible. Even for complex multi-voice input where quantization noise approaches a quasi-white spectrum (−73 dB), the sideband remains 26 dB above.

#### Historical Interpretation

The audibility of carrier sidebands is authentic PC speaker hardware behaviour. It manifests most strongly when feeding high-quality, spectrally sparse audio (e.g. FluidSynth soundfont rendering) into the hardware model. Original PC game music — FM synthesis (OPL2), multi-voice arrangements, complex timbres — had dense harmonic coverage that filled the sideband positions naturally, masking them. This is not a simulation defect; it is the correct response of the hardware to clean input.

---

### 3.7 Drive Gain and S/N Optimisation

#### The Mechanism

The dominant noise source in the simulation is not broadband quantization noise but carrier sideband energy at specific frequencies (§3.6). These sidebands arise primarily from **PWM duty-cycle quantization error** — the error introduced by rounding the continuous duty cycle to one of 78 discrete levels. This is not a fixed-amplitude error; it is proportional to the inverse of the number of levels actually *used* in each cycle. When the signal is quiet and the duty cycle barely deviates from 0.5, only a few of the 78 levels are exercised and the quantization error is large relative to the signal.

Drive gain addresses this directly: scaling the signal up by D before PWM forces more duty-cycle levels into play. After the IIR filter the output is divided by D, restoring the original level. The carrier noise (which scales with the signal) cancels through the gain–invert pair; the duty-cycle quantization error (which is reduced by using more levels) does not fully cancel — so the net effect is improved S/N at sideband frequencies.

This is distinct from the simpler "8-bit pre-quantization SNR" argument (§3.4): that mechanism applies to the 128-level pre-quantizer, while the dominant effect here is on the 78-level PWM duty-cycle quantizer.

#### Measured S/N vs Drive Gain (440 Hz, 3 sideband frequencies: 1560/2000/2440 Hz)

| Input level | driveGain | S/N (fund − SB avg) | Hard-clip % |
| :---: | :---: | :---: | :---: |
| −6 dBFS | 2.0 | +51.7 dB | 4.4% |
| −6 dBFS | 4.0 | +56.2 dB | 66.8% |
| −12 dBFS | 2.0 | +47.6 dB | 0% |
| −12 dBFS | 4.0 | **+53.0 dB** | 6.2% |
| −18 dBFS | 2.0 | +37.6 dB | 0% |
| −18 dBFS | 4.0 | **+48.0 dB** | 0% |
| −18 dBFS | 8.0 | +53.3 dB | 7.6% |
| −24 dBFS | 8.0 | +46.9 dB | 0% |
| −24 dBFS | 16.0 | +53.0 dB | 8.8% |

The optimal drive gain for a given input level is approximately `1 / peak_amplitude`. For a sine wave at −18 dBFS (amplitude ≈ 0.126), driveGain = 4.0 maps the peak to 0.50 — well inside the [−1, 1] range — while using 50% of the available 78 duty-cycle levels.

#### Default and CLI Override

The default driveGain is **4.0**, optimised for typical synthesizer output levels near −18 dBFS. Override with `--retro-drive`:

```bash
# Default (−18 dBFS input, no clipping, good S/N)
midra munt --retro pc song.mid

# Loud input (−6 dBFS peaks) — lower gain to avoid clipping
midra munt --retro pc --retro-drive 2 song.mid

# Very quiet input (−24 dBFS) — higher gain for better S/N
midra munt --retro pc --retro-drive 8 song.mid
```

Rule of thumb: `--retro-drive` × peak amplitude ≤ 1.0 to avoid hard clipping.

#### Compressor as a Dynamic Drive Gain

`--compress` (§ Global DSP) achieves the same mechanism dynamically: it raises the signal level before the retro filter when the input is quiet and holds back when the input is loud, staying below the clipping threshold automatically. `--retro-drive` is the static, predictable version; `--compress` is the adaptive version.

| | `--retro-drive` | `--compress` |
| :--- | :--- | :--- |
| Quiet input | ✅ S/N improves | ✅ S/N improves |
| Loud input | ❌ clips if drive too high | ✅ handles gracefully |
| Behaviour | static, predictable | adaptive, level-dependent |
| Interaction | inside PWM loop | upstream of retro filter |

#### Measured S/N vs Compress Preset (--retro pc, driveGain 4.0)

Simulation: 440 Hz sine through `DynamicsCompressor` → `OneBitHardwareFilter`. S/N = fundamental level minus average of carrier sidebands at 1560/2000/2440 Hz. All levels relative to each preset's output peak.

**S/N by input level and preset:**

| Input level | none | soft | gentle | moderate | aggressive |
| :---: | :---: | :---: | :---: | :---: | :---: |
| −6 dBFS | +51.7 dB | +51.7 dB | +47.9 dB | +47.8 dB | +45.8 dB |
| −12 dBFS | +47.6 dB | +47.6 dB | +46.7 dB | **+50.1 dB** | +46.1 dB |
| −18 dBFS | +37.6 dB | +37.6 dB | +40.5 dB | **+48.3 dB** | +46.0 dB |
| −24 dBFS | +35.7 dB | +35.7 dB | +35.0 dB | +37.6 dB | **+43.8 dB** |

Key observations:
- `soft` mirrors `none` at all levels — its threshold of −3 dBFS is above typical synthesizer output, so compression rarely engages.
- `moderate` gives the best result at −12 and −18 dBFS (+10.7 dB improvement over `none` at −18 dBFS), the range where its threshold of −18 dBFS produces useful drive into the PWM quantizer.
- `aggressive` wins at very quiet levels (−24 dBFS) where its lower threshold of −24 dBFS kicks in.
- At −6 dBFS (already loud), all presets reduce S/N slightly because makeup gain pushes signal closer to the drive-gain clip boundary.

**Frequency detail at −18 dBFS (dB relative to each preset's output peak):**

| Frequency | none | soft | gentle | moderate | aggressive |
| :--- | :---: | :---: | :---: | :---: | :---: |
| 440 Hz (fundamental) | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| 880 Hz (2nd harmonic) | −37.5 | −37.5 | −44.3 | −47.4 | −41.3 |
| 1320 Hz (3rd harmonic) | −40.6 | −40.6 | −45.4 | −44.3 | −41.7 |
| 1560 Hz (carrier SB k=31) | −37.3 | −37.3 | −39.3 | −44.0 | −48.0 |
| 1760 Hz (4th harmonic) | −38.4 | −38.4 | −48.3 | −53.2 | −51.3 |
| 2000 Hz (carrier SB k=30) | −29.6 | −29.6 | −35.5 | −42.4 | −40.9 |
| 2200 Hz (5th harmonic) | −46.5 | −46.5 | −45.3 | −44.7 | −47.6 |
| 2440 Hz (carrier SB k=29) | −45.8 | −45.8 | −46.6 | −53.9 | −48.9 |

The 2000 Hz carrier sideband (k=30) is the loudest noise component in the uncompressed path (−29.6 dB). `moderate` suppresses it by 12.8 dB (to −42.4 dB), and `aggressive` by 11.3 dB (to −40.9 dB). Both also visibly suppress the even harmonics (880/1760 Hz) — a compression artefact from the soft-knee gain shaper reducing transient peaks.

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

## 6. Amiga Paula (`--retro amiga`, `--retro a500`, `--retro a1200`)

### 6.1 Hardware Background

The Commodore Amiga's Paula chip (MOS 8364) provided four independent 8-bit PCM playback channels. Each channel had its own dedicated R-2R ladder DAC — four separate 8-bit resistor networks in a single chip. The channels were hard-panned at the factory: channels 0 and 3 were routed to the left audio output, channels 1 and 2 to the right.

The hard panning was a deliberate hardware design choice that defined the Amiga's characteristic stereo sound: instruments were assigned to physical left or right channels, not panned in software. Classic Amiga game music and demoscene compositions were composed and mixed with this constraint in mind — a wide, ping-pong stereo image with no centre channel.

Both the Amiga 500 (OCS chipset, 1987) and the Amiga 1200 (AGA chipset, 1992) used Paula, but their analog output circuitry differed significantly:

- **Amiga 500:** Paula's raw R-2R output fed through a single passive RC low-pass filter (~4.5 kHz cutoff) on the audio board. This heavy filtering gave the A500 its warm, almost muffled character — the DAC's high-frequency aliasing products and staircase edges were suppressed before the signal reached the amplifier or headphone output.
- **Amiga 1200:** The AGA chipset revision improved the analog path. The RC filter was replaced by a near-transparent stage (~28 kHz cutoff), relying instead on the LED filter (enabled by the front-panel LED power button) for any intentional tonal shaping. The result is a significantly brighter, more detailed sound compared to the A500.

Both machines included a secondary **LED filter** — a two-pole active low-pass Sallen-Key stage with a ~3.3 kHz cutoff — that could be engaged by the software. `AmigaPaulaFilter` includes this stage in the signal chain for both profiles.

### 6.2 Signal Chain

Each channel (L and R) passes through the following stages independently:

```
Input (L or R)
  → ZOH at ~22 kHz          (hold for 2 samples at 44.1 kHz)
  → R-2R DAC LUT (256 levels, ±3% tolerance, seed=1985)
  → Static RC LPF            (A500: α=0.39, ~4.5 kHz / A1200: α=0.80, ~28 kHz)
  → LED filter (2-pole)      (α=0.32 × 2 cascades, ~3.3 kHz)
  → M/S stereo widening      (default width=1.6, i.e. 60%)
Output (L or R)
```

**ZOH (Zero-Order Hold):** Paula's DMA hardware fetched one 8-bit sample from chip RAM and held it for the duration of the DMA period before the next fetch. At typical Amiga playback rates (~22 kHz), this means each value was held for approximately two 44.1 kHz output samples. ZOH introduces a gentle ~sinc-shaped roll-off — a hallmark of Paula's sound.

**R-2R DAC LUT:** A pre-computed 256-entry lookup table maps each 8-bit quantization level to a slightly non-ideal voltage, simulating the gain mismatch inherent in real R-2R resistor ladders with ±3% component tolerances. The seed value (1985 — Amiga's launch year) ensures a reproducible, deterministic DAC nonlinearity. This produces gentle harmonic distortion that is characteristic of Paula's texture.

**Static RC LPF:** The profile-specific first-order low-pass filter that differentiates the A500 from the A1200:
- **A500 (α=0.39):** Aggressive roll-off, strongly attenuates above ~4.5 kHz. Produces the classic warm, bassy A500 character.
- **A1200 (α=0.80):** Near-transparent; passes almost all audio content up to ~28 kHz. Retains the ZOH and DAC texture without heavy tonal shaping.

**LED filter (2-pole cascade):** Two cascaded first-order stages, each with α=0.32, approximating the hardware Sallen-Key circuit. The combined response rolls off steeply above ~3.3 kHz. Applied equally to both profiles.

**M/S stereo widening:** See [Section 6.4](#64-stereo-model) and [Section 6.6](#66---paula-width).

### 6.3 A500 vs A1200 Profiles

| Aspect | A500 | A1200 |
| :--- | :--- | :--- |
| RC LPF cutoff | ~4.5 kHz (α=0.39) | ~28 kHz (α=0.80) |
| Character | Warm, bassy, muffled | Bright, detailed, near-transparent |
| LED filter | Yes (both profiles) | Yes (both profiles) |
| DAC model | 8-bit R-2R, ±3% | 8-bit R-2R, ±3% |
| ZOH | 2 samples | 2 samples |
| CLI flag | `--retro amiga` or `--retro a500` | `--retro a1200` |

The A500 profile is the default `amiga` alias because the warm, filtered sound is the one most associated with classic Amiga game music. Use `--retro a1200` when you want the cleaner AGA presentation, or when combining with other DSP effects where the A500's heavy roll-off would over-darken the signal.

### 6.4 Stereo Model

Real Amiga music is produced by assigning individual MIDI voices or tracker channels to physical Paula channels. Voices on channels 0 and 3 appear only in the left speaker; voices on channels 1 and 2 appear only in the right speaker. The hard-panning is part of the composition.

`AmigaPaulaFilter` receives a pre-mixed stereo signal (not four separate MIDI voices), so a literal four-channel simulation is not possible. Instead, the filter applies the ZOH → R-2R → RC LPF → LED chain to the left and right channels **independently** — matching Paula's hardware reality where each DAC path is completely separate — and then applies M/S stereo widening to approximate the pronounced channel separation of the original hard-panned output.

The M/S model encodes the input into Mid (L+R) and Side (L−R) components, scales the Side component by a width factor, then decodes back to L/R. With the default `width=1.6` (60%), the stereo image is broadened to resemble the hard-panned feel of authentic Amiga music without artificially inverting phase or creating mono-incompatible output.

### 6.5 Parameters

| Parameter | A500 value | A1200 value | Purpose |
| :--- | :--- | :--- | :--- |
| ZOH hold | 2 samples | 2 samples | Paula DMA fetch rate (~22 kHz) |
| DAC levels | 256 (8-bit) | 256 (8-bit) | R-2R ladder resolution |
| DAC tolerance | ±3%, seed=1985 | ±3%, seed=1985 | Resistor mismatch nonlinearity |
| RC LPF α | 0.39 | 0.80 | ~4.5 kHz / ~28 kHz hardware cutoff |
| LED filter α | 0.32 × 2 | 0.32 × 2 | 2-pole ~3.3 kHz Sallen-Key stage |
| Default width | 1.6 (60%) | 1.6 (60%) | M/S stereo widening |
| Channel mode | Independent L/R | Independent L/R | Matches separate Paula DAC paths |

### 6.6 `--paula-width`

The `--paula-width <PCT>` option (0–300) controls the M/S stereo widening as a percentage. It is only effective when `--retro amiga`, `--retro a500`, or `--retro a1200` is active.

| Value | Behaviour |
| :--- | :--- |
| `0` | No widening — output matches the filtered signal without M/S expansion |
| `60` *(default)* | Default Paula hard-pan approximation |
| `100` | Maximum safe widening — strong stereo image without clipping risk |
| `101–300` | Hyper-wide; may cause clipping on dense mixes |

```bash
# A500 profile, default widening
midra opl --retro a500 song.mid

# A1200 profile, strong stereo spread
midra opl --retro a1200 --paula-width 80 song.mid

# Narrow the widening for a more mono-compatible output
midra opl --retro amiga --paula-width 20 song.mid
```

---

## 7. Common Engineering Challenges

### Aliasing at 44.1 kHz

All PWM-based modes face the same problem: the carrier frequency is comparable to or exceeds the 44.1 kHz output sample rate, causing Nyquist aliasing if raw pulses are rendered.

Two strategies are used:
- **Mac 128k**: Analytical integration (exact RC exponential formula — zero aliasing by definition)
- **PC, Apple II** (`OneBitHardwareFilter`): 4× internal oversampling (176,400 Hz). The raw ±1 PWM bit is evaluated at each sub-sample and fed to a speaker-cone IIR model. No RC circuit is present in either machine; the low-pass behaviour comes from the mechanical cone. Oversampling avoids aliasing without the RC assumption. Apple II: 8 sub-samples per carrier period (exact). PC: ≈ 11.6 sub-samples (minor rounding artefacts > 88 kHz, inaudible).
- **Spectrum** (`SpectrumBeeperFilter`): No carrier; direct amplitude quantization avoids the aliasing problem entirely

### The 44.1 kHz / Carrier Frequency Relationship

For `OneBitHardwareFilter`, `carrierStep = carrierHz / sampleRate`:

| Mode | carrierStep | Carrier periods per output sample |
| :--- | :--- | :--- |
| apple2 | 0.500 | 2 output samples per carrier cycle |
| pc | 0.345 | ~2.9 output samples per carrier cycle |

The apple2 mode's exact `carrierStep=0.5` is what makes the two-pulse encoding natural: each carrier period spans exactly two output samples, mirroring the DAC522 hardware behavior.

---

## 8. The `--speaker` Option and Retro Modes

The `--speaker` flag applies an `AcousticSpeakerFilter` — a post-processing acoustic coloration stage that models the frequency response of vintage speaker cabinets. It is a useful standalone effect for modern synthesis engines, but it interacts badly with `--retro` modes.

### Why They Conflict

Every `--retro` mode already models its physical speaker as an integral part of its signal chain. The speaker is not a separable add-on; it defines the mode's sound:

| Retro Mode | Speaker Model Built In |
| :--- | :--- |
| `compactmac` | RC integration (τ = 30 µs) models the 2-inch Mac speaker; −84 dB at 10 kHz |
| `spectrum` | HP (α = 0.930, ~510 Hz) + 2× LP (α = 0.600, ~4.5 kHz) models the 22mm 40Ω beeper |
| `pc` | 7-pole IIR (1 electrical: τ_e=10 µs, α_e=0.433 + 6 mechanical: τ_m=37.9 µs, α_m=0.1395, all at 176,400 Hz) models voice-coil inductance and cone inertia — −3 dB at 1.4 kHz, −68 dB carrier suppression |
| `apple2` | Smooth α = 0.55 models the Apple II speaker's natural cone rolloff |
| `covox` / `disneysound` | RC LPF (α = 0.26, ~2.12 kHz) models the parallel-port analog circuit |
| `amiga` / `a500` | Static RC LPF (~4.5 kHz) + LED 2-pole (~3.3 kHz) models Paula's analog output stage |
| `a1200` | AGA DAC filter (~28 kHz, near-transparent) + LED 2-pole (~3.3 kHz) |

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

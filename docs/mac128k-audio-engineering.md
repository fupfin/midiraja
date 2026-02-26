# Macintosh 128k Audio Hardware Simulation

This document chronicles the engineering journey to create a highly authentic real-time simulation of the original 1984 Macintosh 128k audio circuitry.

## 1. Hardware Background (The "SWIM" and 68000 PWM)
The original Macintosh did not have a standard PCM DAC. Instead, it used a clever high-speed Pulse Width Modulation (PWM) scheme:
- **CPU Feed:** The 68000 CPU was interrupted exactly at the horizontal video flyback frequency (~22.2545 kHz) to stuff an 8-bit value into a buffer.
- **PWM Logic:** This 8-bit value (0-255) controlled the duty cycle of a very high-frequency 1-bit pulse train.
- **Analog Integration:** The 1-bit pulses passed through a physical resistor-capacitor (RC) low-pass filter on the logic board, which integrated the pulses back into an analog voltage before reaching the internal 2-inch speaker.

## 2. The Theoretical Challenge: The 44.1kHz Buffer Trap
Midiraja operates at a standard 44.1 kHz output. Simulating a 350kHz+ PWM signal inside a 44.1 kHz buffer presents a fundamental mathematical contradiction: **The Nyquist Limit.**

If we try to "draw" the 1-bit pulses directly, we create massive aliasing (artifacts) because the pulse edges don't line up with the 44.1kHz sample points. This results in a "siren" or "dog whistle" noise around 21kHz (the mirror image of the 22kHz sampling rate).

## 3. Evolutionary Attempts

### 3.1 Naive Average-based Model
- **Logic:** Convert 8-bit duty cycle to a proportional voltage (-1.0 to 1.0) and apply a digital LPF.
- **Evaluation:** Failed. Resulted in "clean" modern 8-bit PCM sound without physical warmth.

### 3.2 Discrete PWM Rendering
- **Logic:** Render raw +1/-1 pulses at 44.1kHz based on duty cycle.
- **Evaluation:** Failed. Created a deafening 21.05kHz aliasing tone (the "Siren"). 

### 3.3 The "Grit" Theory (Hardware Distortion)
- **Logic:** Assume naive asymmetrical truncation and weak filtering created a "sand-like" texture.
- **Evaluation:** Proven historically inaccurate by authentic hardware captures (see Section 6).

## 4. The Breakthrough: Event-Driven Analytical Integration

Instead of approximating the PWM pulses or averaging them, we moved to **Physical Event Simulation.** We treat the analog RC filter as a continuous-time system governed by a differential equation:

$$ \frac{dx}{dt} = \frac{u(t) - x(t)}{\tau} $$

Where $u(t)$ is the raw 1-bit PWM input (+1 or -1) and $x(t)$ is the voltage across the capacitor.

### 4.1 The Analytical Solution
For any time interval where the input $u$ is constant, the solution is:
$$ x(t_2) = u + (x(t_1) - u) \cdot e^{-\Delta t/\tau} $$

### 4.2 Implementation Strategy
1.  **Event Tracking:** We track Mac Clock events (~22.25kHz) and PWM Transition events in sub-microsecond precision.
2.  **Analytical Integration:** Step through events and update the capacitor voltage $x$ using the exponential decay formula.
3.  **Perfect Resampling:** Read the value of $x$ exactly at the output sample intervals.

## 5. Authentic Hardware Discovery (Mac Plus Capture Analysis)

In March 2026, a pristine 16-bit recording of an original Macintosh Plus ("Hard Stars Studio Session") was analyzed using high-resolution spectral density estimation. The findings revolutionized the simulation:

### 5.1 The "Perfect" Analog Stage
Contrary to the "gritty" theory, the physical Mac Plus hardware was mathematically excellent at its job:
- **Carrier Suppression:** The 22.25kHz PWM carrier was measured at **-90.8 dB** relative to the fundamental. The analog stage effectively eliminated the PWM ripple before it reached the audio jack.
- **Quantization Symmetry:** No evidence of asymmetrical truncation or zero-crossing "grit" was found. The signal followed a perfectly symmetrical 8-bit staircase smoothed into near-continuous analog.

### 5.2 The Steep Roll-Off (-84dB @ 10kHz)
The most striking characteristic was the frequency response. The Mac hardware exhibits an extremely steep low-pass curve starting around 5kHz and hitting **-84 dB at 10kHz**. This creates the signature "warm, muffled, and heavy" Macintosh sound.

### 5.3 Comparative Spectral Data
Spectral analysis of the authentic capture (normalized to 0 dB at the fundamental peak of 494.5 Hz):

| Frequency (Hz) | Authentic Mac Level (dB) | Characterization |
| :--- | :--- | :--- |
| **100** | -28.6 dB | Solid bass support |
| **500** | -31.5 dB | Fundamental body |
| **1,000** | -41.3 dB | Lower mids |
| **3,000** | -47.9 dB | Presence region |
| **5,000** | -51.6 dB | Start of steep roll-off |
| **7,000** | -64.1 dB | Aggressive attenuation |
| **9,000** | -78.8 dB | Deep attenuation |
| **10,000** | **-83.8 dB** | Measured "Muffle" point |
| **15,000** | -92.1 dB | Inaudible harmonics |
| **22,050** | -99.3 dB | PWM Carrier suppressed |

## 6. Final Mathematical Model

Based on the authentic capture, the `--mac128k` filter was redesigned with these parameters:

| Parameter | Value | Purpose |
| :--- | :--- | :--- |
| **Channel Mode** | **Forced Mono** | Matches physical mono-only hardware. |
| **Quantization** | **Symmetrical 8-bit** | Clean 8-bit buffer simulation (Math.round). |
| **RC Tau** | **35.0 µs** | Aggressive physical integration. |
| **Smooth Alpha** | **0.35** | Fits the measured -84dB @ 10kHz jack curve. |

## 7. Conclusion
The `--mac128k` simulator is a "Digital Twin" of the physical Mac Plus audio path. By abandoning speculative "grit" and curve-fitting the exact spectral roll-off of the real hardware, the simulation now produces the authentic, heavy, and warm acoustic profile of 1984.

## 8. Other Retro DAC Models

### 8.1 Covox Speech Thing (`--retro covox`)
- **Resistor Tolerance:** Simulates R-2R ladder variance (+/- 3%) for authentic harmonic distortion.
- **ZOH Smoothing:** Mimics the ~11kHz Zero-Order Hold bottleneck of old LPT ports.

### 8.2 IBM PC Speaker (`--retro ibmpc`)
- **PWM Carrier:** 18.6kHz analytical area integration.
- **Gritty Texture:** Unlike the Mac, the PC speaker lacks sophisticated analog filtering, allowing the raw PWM ripple to reach the speaker cone.

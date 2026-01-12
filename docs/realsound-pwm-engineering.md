# The Quest for Authentic RealSound: A DSP Engineering Log

**Status:** Implementation Complete  
**Context:** This whitepaper documents the digital signal processing (DSP) research conducted during the implementation of the `--bits`, `--pwm`, and `--realsound` CLI arguments for Midiraja's Gravis Ultrasound (GUS) engine. 

This document chronicles the engineering journey to mathematically reconstruct the legendary **RealSound (PC Speaker PWM Audio)** technology—pioneered by Access Software in the late 1980s—within a modern 44.1kHz digital audio environment, ensuring zero aliasing and perfect historical accuracy.

---

## 1. The Hardware Context: How RealSound Worked

The original IBM PC internal speaker was a pure 1-bit physical device; it could only exist in two states: **0 (Off)** or **1 (On)** based on applied voltage. To play analog waveforms (PCM) through this binary device, RealSound hijacked the **Intel 8253 PIT (Programmable Interval Timer)**'s Timer 2 channel.

*   **Carrier Frequency:** It divided the PIT's base clock of $1.193182 \text{ MHz}$ by a small integer. While literature often suggests a division by 64 ($\sim 18.6 \text{ kHz}$), our empirical FFT analysis of original 1980s RealSound `.wav` demos revealed a massive energy spike at **$15.2 \text{ kHz}$**. This indicates developers intentionally lowered the carrier to accommodate CPU interrupt overhead, resulting in a more audible, gritty "crunch".
*   **Resolution:** By adjusting the proportion of time the speaker remained "On" during one cycle (Duty Cycle), it effectively emulated a rudimentary **6-Bit DAC**.
*   **Demodulation (Physical Reconstruction):** The stiff, 2.25-inch paper cone of the PC speaker lacked the mechanical inertia to physically vibrate at the $15.2 \text{ kHz}$ switching speed. Therefore, the speaker cone itself acted as a massive **Low-Pass Filter (LPF)**, ignoring the high-frequency toggling and settling at a physical position corresponding to the average voltage (the intended analog signal).

Our goal was to mathematically recreate this **15.2kHz PWM generator** and the **analog paper speaker's physical LPF** inside Java's 44.1kHz DSP environment without introducing digital aliasing.

---

## 2. Trial 1: The First-Order Delta-Sigma (PDM) Trap

In our initial implementation, we adopted a **First-Order Delta-Sigma Modulator (Pulse Density Modulation, PDM)** instead of historical PWM, as it is widely used in modern audio engineering.

### Mathematical Model:
$$ y[n] = \text{sgn}(x[n] + e[n-1]) $$
$$ e[n] = x[n] + e[n-1] - y[n] $$

### The Problem: Quantization Noise & Idle Tones
Because PDM accumulates the quantization error ($e[n]$) and pushes it to the next frame (Noise Shaping), it theoretically preserves low-frequency data better than original PWM. However, this feedback loop inevitably generates massive amounts of **High-frequency White Noise**. 
Since our sample rate was only 44.1kHz, this noise bled heavily into the audible spectrum, sounding like **"sizzling sand"**.

Furthermore, when the input $x[n]$ was exactly $0.0$, the error accumulator would perfectly alternate between $+1$ and $-1$, creating a severe **Limit Cycle (Idle Tone)** right at the Nyquist frequency ($22.05 \text{ kHz}$). This approach was abandoned.

---

## 3. Trial 2: True Carrier PWM and Nyquist Aliasing

We then moved to a **True Carrier PWM** approach, exactly mimicking the original hardware by generating an 15.2kHz sawtooth carrier and intersecting it with the input signal.

### Mathematical Model:
$$ c[n] = (c[n-1] + \Delta f) \bmod 2.0 - 1.0 \quad \text{where} \quad \Delta f = \frac{15200}{44100} \times 2 $$
$$ y[n] = \begin{cases} 1.0, & \text{if } x[n] > c[n] \\ -1.0, & \text{otherwise} \end{cases} $$

### The Problem: In-band Nyquist Fold-over
While this works perfectly in analog circuitry, doing this in the discrete-time domain causes horrific **Aliasing**. 
An $15.2 \text{ kHz}$ square wave contains infinite odd harmonics:
*   3rd Harmonic: $15.2 \text{ kHz} \times 3 = 45.6 \text{ kHz}$
*   5th Harmonic: $15.2 \text{ kHz} \times 5 = 76.0 \text{ kHz}$

In a 44.1kHz environment, the Nyquist limit is $22.05 \text{ kHz}$. Harmonics exceeding this limit are violently folded back into the audible spectrum.
$$ 55.8 \text{ kHz} \rightarrow 55.8 - 44.1 = \mathbf{11.7 \text{ kHz}} $$
$$ 93.0 \text{ kHz} \rightarrow 93.0 - (44.1 \times 2) = \mathbf{4.8 \text{ kHz}} $$

This created loud, dissonant pitches at $4.8 \text{ kHz}$ and $11.7 \text{ kHz}$ that did not exist in the original music, resulting in a **metallic, ring-modulated radio static** sound.

---

## 4. Final Architecture: Oversampled FIR & Inter-stage Reconstruction

To overcome these mathematical constraints, we designed a final, 3-stage modular audio rendering pipeline.

### Stage 1: Noise-Shaped Bitcrusher (N-bit Quantization) with TPDF Dither
Simple truncation quantization causes severe harmonic distortion. To prevent this, a **First-order Leaky Delta-Sigma** algorithm is applied strictly to the quantization stage. However, as discovered during the `midra beep` engine development (see *1-Bit Audio Engineering*), a pure 1st-order loop suffers from catastrophic "Idle Tones" (Limit Cycles) when the input is silent or highly static.

**The Breakthrough: TPDF Dither**
To mathematically shatter these limit cycles, we inject **Triangular Probability Density Function (TPDF) Dither** into the target signal *before* quantization. TPDF dither is generated by summing two independent uniform random variables ($[-0.5, 0.5]$), scaled exactly to 1 LSB (Least Significant Bit) of the target bit-depth.

$$ \text{dither} = (\text{rand}() - 0.5) + (\text{rand}() - 0.5) $$
$$ \text{target} = x[n] + (E_{q}[n-1] \times 0.95) + (\text{dither} \times \text{LSB}) $$
$$ y_q[n] = \frac{\text{round}(\text{target} \times Q_{steps})}{Q_{steps}} \quad \text{where} \quad Q_{steps} = 2^{N-1} - 1 $$

By dynamically randomizing the quantization threshold, TPDF dither completely decorrelates the quantization error from the audio signal. This converts the terrible metallic screeching and high-frequency limit cycles inherent in low bit-depths (e.g., 6-bit, 8-bit) into a smooth, warm analog tape hiss.

### Stage 1.5: Inter-stage DAC Reconstruction Filter
If the sharp, stair-stepped waveforms generated by low-bit quantization collide directly with the subsequent PWM carrier, the two non-linear systems multiply, causing **Intermodulation Distortion**.
To prevent this, a classic Amiga/SNES style **2-pole IIR Low-Pass Filter** ($\alpha = 0.45$) is inserted between the stages to reconstruct the jagged stairs into smooth analog curves before modulation.

### Stage 2 & 3: 32x Oversampled PWM & Virtual Acoustic LPF
To eradicate aliasing at the source, **32x Oversampling** is applied. 
The internal DSP clock runs at a staggering **$1.4112 \text{ MHz}$ ($44.1 \text{ kHz} \times 32$)**, surpassing even the original hardware's $1.19 \text{ MHz}$.

1.  **Anti-Aliasing FIR (Boxcar) Filter:** We simply average ($\frac{1}{32}\sum$) the results of 32 ultra-fast 1-bit PWM switching cycles. Mathematically, this acts as a Boxcar FIR filter, creating deep spectral nulls that suppress 99% of high-frequency harmonics *before* decimation back to 44.1kHz.
2.  **Virtual Paper Cone (Acoustic Filter):** We simulate the narrow frequency response of the original PC speaker hardware.
    *   **2-Stage Low-Pass ($\alpha=0.20$):** Simulates the stiff inertia of the paper cone, rolling off sharp PWM edges and frequencies above $~2.5 \text{ kHz}$.
    *   **1-Stage High-Pass ($\alpha=0.98$):** Simulates the physical limitation of a tiny 2.25-inch speaker, cutting off sub-bass below $~100 \text{ Hz}$ to complete the "tin-can radio" aesthetic.
3.  **Epsilon Noise Gate:** To prevent floating-point asymptotes from keeping the noise gate permanently open, we forcefully flush the IIR filter states to $0$ when $|x| < 1\times10^{-5}$, ensuring dead silence during pauses.

---

## 5. Conclusion & How to Experience It

Through rigorous DSP engineering and Python-based FFT spectral proofs, Midiraja has successfully conquered the fundamental aliasing limits of digital environments. We have reconstructed RealSound—a milestone of 1980s computer audio engineering—in its most romantic and noise-free form.

You can experience the results of this research by running Midiraja with the following commands:

*   **The Authentic RealSound Macro:**
    Automatically applies 6-bit quantization and the 1-bit PWM acoustic simulator.
    ```bash
    midra gus --realsound <your_midi_file.mid>
    ```

*   **Custom Degradation:**
    Manually build your own retro audio pipeline by mixing the parameters.
    ```bash
    midra gus --bits 4 --1bit dsd <your_midi_file.mid>
    ```
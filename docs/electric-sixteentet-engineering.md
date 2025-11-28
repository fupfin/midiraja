# Electric Sixteentet: 8-Core Apple II Audio Engineering

This document outlines the architecture of the **Electric Sixteentet**, the core audio engine used in the `midra beep` command. This engine extends the software-based 1-bit polyphony techniques originally developed for the Apple II in the early 1980s, scaling them into a synchronized 8-core virtual cluster capable of 16-note polyphony.

---

## 1. Historical Context: 1-Bit Polyphony

The internal speaker of early 8-bit computers (such as the Apple II and IBM PC) was a 1-bit device, physically capable of only two voltage states (On/Off). While standard programming practices allowed only monophonic output, developers eventually discovered software techniques to multiplex multiple audio frequencies onto a single bit. 

In 1981, Paul Lutus released "Electric Duet," a program that utilized interleaved execution and logical mixing to output two distinct voices simultaneously from the Apple II speaker. The **Electric Sixteentet** scales this foundational technique to simulate 8 synchronized virtual Apple II units operating in parallel.

---

## 2. Technical Architecture

The engine is structured as a two-tier system: individual virtual cores that generate modulated 1-bit signals, and a global mixer that combines them.

### 2.1. 8-Core Cluster Model
The system consists of 8 independent `SixteentetSpeaker` units. Each unit represents a single virtual Apple II machine and is assigned exactly 2 audio voices (MIDI notes) to process simultaneously.

### 2.2. Core Unit Dynamics
Within each of the 8 virtual cores, the engine applies extreme constraints and modulation techniques to fuse the 2 assigned voices into a single 1-bit output stream.

#### 2.2.1. XOR Ring Modulation
To combine two distinct frequencies into a single 1-bit output without a DAC, the engine uses a bitwise XOR (Exclusive OR) operation rather than arithmetic addition.

*   **Mathematical Model:** 
    $Output = Square_{f1} \oplus Square_{f2}$
*   **Acoustic Property:** Performing a logical XOR on two square waves is mathematically equivalent to multiplying them ($f1 \times f2$). This generates **Ring Modulation**, producing sum and difference frequencies that add a characteristic fuzzy, inharmonic texture to the output.

#### 2.2.2. Dynamic Duty Cycle Sweep
To prevent the square waves from sounding static, the engine incorporates dynamic pulse-width modulation (PWM) techniques popularized by early Apple II games like *Karateka* (1984) and *Prince of Persia* (1989).

*   **Duty Cycle Sweep:** Instead of a fixed 50% duty cycle, the pulse width of each voice continuously sweeps between $0.1$ and $0.9$ while the note is active.
    $$ Duty(t) = 0.5 + 0.4 \times \sin(2\pi \cdot f_{sweep} \cdot t) $$
*   When two sweeping duty-cycle waves are XOR-mixed, it creates a shifting, FM-like timbre that mimics a hardware swept-filter or "wah" effect.

#### 2.2.3. LFO Vibrato
To simulate the natural pitch variation of acoustic instruments, an independent Low Frequency Oscillator (LFO) is applied to the phase accumulator of each voice.

*   **Frequency Modulation:** A $\sim 6 \text{ Hz}$ sine wave modulates the base frequency by approximately $\pm 1.5\%$.
    $$ f_{modulated} = f_{base} \times (1.0 + 0.015 \times \sin(2\pi \cdot 6 \cdot t)) $$

### 2.3. Global Mixing Pipeline
Once the 8 individual cores have generated their 1-bit outputs, the system must integrate them into a final audio buffer for playback. The rendering process follows a 4-stage pipeline:

1.  **Stage 1 (Generation):** 16 independent phase accumulators calculate the current state of their respective square waves, applying LFO vibrato and dynamic duty cycles.
2.  **Stage 2 (Local Multiplexing):** The 16 voices are paired into the 8 virtual Apple II cores. Each core performs the XOR operation (Section 2.2.1) on its two voices to produce a single 1-bit signal.
3.  **Stage 3 (Analog Summing):** The 8 individual 1-bit signals are summed and averaged to simulate the physical mixing of multiple independent audio sources in a room.
    $$ MasterOutput = \frac{1}{8} \sum_{i=1}^{8} AppleII_{core\_i} $$
4.  **Stage 4 (Mastering):** The averaged floating-point value is scaled and converted into a standard 16-bit PCM buffer for the native audio driver.

---

## 3. Conclusion

The **Electric Sixteentet** engine demonstrates how extreme hardware constraints can be bypassed using software-driven logical mixing and modulation. By combining XOR ring modulation, duty sweeps, and multi-core analog summing, it successfully reproduces the distinct, complex timbre of 1980s 1-bit audio programming.
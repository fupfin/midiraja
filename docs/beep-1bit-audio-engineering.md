# 1-Bit Audio Engineering (The 'Beep' Synth)

This document outlines the architecture of the `midra beep` command, a pure mathematical software synthesizer designed to recreate the extreme limitations of 1980s 1-bit audio hardware. Internally dubbed the **'Electric Sixteentet'**, this engine extends the software-based 1-bit polyphony techniques originally developed for the Apple II, scaling them into a synchronized 8-core virtual cluster capable of 16-note polyphony.

---

## 1. Historical Context: The Apple II "Bit-Banging" Legacy

The internal speaker of early 8-bit computers was a primitive 1-bit device, physically capable of only two voltage states (On/Off). However, there was a profound architectural difference between platforms:

*   **IBM PC:** Utilized the **Intel 8253 PIT** hardware timer, which could automatically generate square waves at a specified frequency without constant CPU intervention.
*   **Apple II:** Featured no dedicated sound hardware. To produce sound, the CPU had to manually toggle the speaker's memory-mapped I/O port at precise intervals using cycle-counted machine code. This "bit-banging" approach required 100% of the CPU's attention just to maintain a single steady pitch.

Because of this extreme hardware poverty, the Apple II became the ultimate laboratory for software-driven audio innovation. In 1981, Paul Lutus released **"Electric Duet,"** utilizing interleaved execution and logical mixing to multiplex two distinct voices onto the 1-bit speaker pin—a feat of engineering that bypassed the system's lack of a DAC or even a basic timer.

The **`midra beep`** engine is conceptually modeled after the Apple II's software-driven philosophy. It simulates a synchronized cluster of 8 virtual Apple II units, each performing the "impossible" task of generating polyphonic, modulated audio through pure mathematical logic.

---

## 2. Technical Architecture

The engine is structured as a two-tier system: individual virtual cores that generate modulated 1-bit signals, and a global mixer that combines them.

### 2.1. 8-Core Cluster Model
The system consists of 8 independent `SixteentetSpeaker` units. Each unit represents a single virtual Apple II machine and is assigned exactly 2 audio voices (MIDI notes) to process simultaneously.

### 2.2. Core Unit Dynamics: The Synergy of Three Modulations

Within each of the 8 virtual cores, the engine faces a severe physical limitation: it must output two distinct musical notes simultaneously through a single 1-bit wire. To accomplish this while avoiding a static, lifeless tone, the engine relies on the precise interlocking of three distinct modulation techniques. These techniques act as a sequential DSP pipeline where each stage mathematically influences the next.

**1. Destabilizing the Pitch (LFO Vibrato)**
The process begins at the fundamental frequency ($f_{base}$) of the note. Rather than generating a rigid pitch, the phase accumulator is driven by a Low Frequency Oscillator (LFO). Operating at approximately $6 	ext{ Hz}$, this LFO acts as a frequency modulator, constantly stretching and compressing the note's wavelength by $\pm 1.5\%$:
$$ f(t) = f_{base} 	imes (1.0 + 0.015 	imes \sin(2\pi \cdot 6 \cdot t)) $$
Visually, this places the horizontal period of the square wave into a state of continuous, elastic motion, injecting the organic pitch waver of a string instrument.

**2. Morphing the Shape (Dynamic Duty Sweep)**
While the horizontal width (pitch) is oscillating, the vertical architecture of the wave is also forced into motion. Drawing inspiration from virtuoso 8-bit soundtracks like *Karateka*, the engine rejects a static 50% square wave. Instead, it employs Pulse Width Modulation (PWM), continuously sweeping the duty cycle ($D$) between 10% and 90%:
$$ D(t) = 0.5 + 0.4 	imes \sin(2\pi \cdot f_{sweep} \cdot t) $$
As $D(t)$ sweeps, the amplitudes of the even and odd harmonics shift dramatically in real-time. This dynamic spectral envelope creates a "wah-wah" timbral morphing effect, transforming a simple buzzer into a swept-filter synth patch.

**3. The Collision (XOR Multiplexing & Ring Modulation)**
Finally, the core takes these two highly unstable, mutating signals ($Voice_1$ and $Voice_2$) and forces them to collide inside an Exclusive OR (XOR) logic gate to produce a single binary output stream:
$$ Output(t) = Voice_1(t) \oplus Voice_2(t) $$
The primary intent of the XOR gate is strictly functional: **Polyphonic Multiplexing**. It is a computational hack to force two melodies to share one pin because arithmetic addition ($V_1 + V_2$) is physically impossible on a binary speaker. 

However, mapping the logic states $\{0, 1\}$ to bipolar voltages $\{-1, 1\}$ reveals a profound acoustic byproduct: XOR logic is mathematically identical to amplitude multiplication ($V_1 	imes V_2$). This inadvertent **Ring Modulation** spawns complex sidebands at $(f_1 + f_2)$ and $|f_1 - f_2|$.

**The Final Synergy:**
If the engine simply XOR-mixed two static square waves, the resulting ring modulation would be a harsh, predictable drone. But because the two input waves are already alive—their pitches wobbling via $f(t)$ and their harmonic shapes morphing via $D(t)$—the mathematical intersections inside the XOR gate become wildly chaotic. The sideband frequencies are in a constant state of flux. The resulting 1-bit output erupts into a lush, shifting, and metallic texture that closely mimics the multi-operator algorithms of a hardware FM synthesizer (like the Yamaha DX7). This three-part mathematical synergy is the true secret behind the imposing, orchestral weight of the Electric Sixteentet.

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
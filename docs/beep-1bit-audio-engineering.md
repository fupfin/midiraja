# 1-Bit Audio Engineering (The 'Beep' Synth)

This document outlines the architecture of the `midra beep` command, a pure mathematical software synthesizer designed to explore the extreme physical limitations of 1-bit audio logic. Internally dubbed the **'1-Bit Digital Cluster'**, this engine extends the software-based bit-banging techniques of the 1980s (e.g., Apple II, ZX Spectrum), fusing them with True [Phase Modulation](https://en.wikipedia.org/wiki/Phase_modulation), Delta-Sigma noise shaping, and Heuristic AI Optimization to create a definitive, zero-dependency 1-bit digital instrument.

---

## 1. Historical Context: The Apple II "Bit-Banging" Legacy

The internal speaker of early 8-bit computers was a primitive 1-bit device, physically capable of only two voltage states (On/Off). However, there was a profound architectural difference between platforms:

*   **IBM PC:** Utilized the **Intel 8253 PIT** hardware timer, which could automatically generate square waves at a specified frequency without constant CPU intervention.
*   **Apple II:** Featured no dedicated sound hardware. To produce sound, the CPU had to manually toggle the speaker's memory-mapped I/O port at precise intervals using cycle-counted machine code. This "bit-banging" approach required 100% of the CPU's attention just to maintain a single steady pitch.

Because of this extreme hardware poverty, the Apple II became the ultimate laboratory for software-driven audio innovation. In 1981, Paul Lutus released **"Electric Duet."** He achieved the impossible by utilizing strict cycle-counted [Time-Division Multiplexing](https://en.wikipedia.org/wiki/Time-division_multiplexing) and `EOR` (Exclusive-OR) logic to rapidly switch the speaker between two virtual square waves at ~8 kHz, successfully multiplexing two distinct voices onto a single 1-bit speaker pin.

The `midra beep` engine pushes this philosophy to its absolute mathematical limit, simulating a dynamic cluster of 1-bit units capable of generating polyphonic 2-Operator FM synthesis through pure 1-bit pins.

---

## 2. Technical Architecture & Engineering Evolution

The engine's architecture is the result of rigorous acoustic engineering, overcoming severe mathematical constraints to achieve perfectly synchronized, polyphonic 1-bit audio.

### 2.1. The Synthesis Triad (Timbre Generation)
Before any notes can be multiplexed together, the engine must generate their fundamental timbre. The engine provides three distinct synthesis algorithms (`--synth`), representing different eras of digital audio engineering:

**1. Modern: Yamaha-Like [Phase Modulation](https://en.wikipedia.org/wiki/Phase_modulation) (`--synth pm`)**
*   Provides a clean, bell-like, pseudo-analog sound.
*   Instead of mathematically unstable Direct Frequency Modulation, it adds the modulator wave directly to the *lookup phase* of a mathematically perfect carrier accumulator. This guarantees absolute pitch stability at any frequency and prevents high-frequency chords from collapsing into dissonant noise.

**2. Hardcore 1980s: Timbral [Ring Modulation](https://en.wikipedia.org/wiki/Ring_modulation) (`--synth xor`)**
*   Mimics the brilliant '[Ring Modulation](https://en.wikipedia.org/wiki/Ring_modulation)' hacks used by 1-bit legends like Tim Follin on the ZX Spectrum.
*   It generates two raw 1-bit square waves (a Carrier and a slightly detuned Modulator) and logically crushes them together using an Exclusive-OR (`^`) gate. This carves out a gritty, aggressively buzzing, chainsaw-like chiptune timbre before the signal ever reaches the master multiplexer.

**3. Classic Apple II: Pure Square Wave (`--synth square` - Default)**
*   The original workhorse of 1980s computer games (e.g., *Karateka*, *Ultima*).
*   Uses a single square wave oscillator but keeps it "alive" by continuously wobbling its fundamental pitch via a 6Hz LFO (Vibrato) and mathematically sweeping its pulse width between 10% and 90% (Wah-Wah Duty Sweep). It is given a much longer decay envelope (1.5s) to compensate for the acoustic energy lost during narrow duty cycles.

### 2.2. The Multiplexing Evolution: From XOR to DSD
The most critical engineering challenge in 1-bit audio is mixing multiple polyphonic notes within a single, binary (On/Off) speaker pin. The engine documents the historical and mathematical evolution of this problem by providing four distinct multiplexing algorithms (`--mux`):

**1. The 1981 Hack: XOR Logic Gate (`--mux xor` - Default)**
*   Inspired by Paul Lutus's original "Electric Duet" (which used the 6502's `EOR` instruction to manage speaker phase toggling), this mode converts each analog sine wave into a discrete 1-bit [Pulse Width Modulated (PWM)](https://en.wikipedia.org/wiki/Pulse-width_modulation) stream, then crushes them together through a boolean Exclusive-OR (`^`) logic gate.
*   **Acoustic Result:** Severe, authentic [Ring Modulation](https://en.wikipedia.org/wiki/Ring_modulation). Creates a highly gritty, buzzing chiptune texture.
*   **Limitation:** XORing more than 2 voices causes catastrophic phase cancellation ($1 \oplus 1 = 0$), obliterating the fundamental pitch into white noise.

**2. The High-Speed Switch: [Time-Division Multiplexing](https://en.wikipedia.org/wiki/Time-division_multiplexing) (`--mux tdm`)**
*   An impossible feat for a 1MHz CPU, this algorithm switches between notes at over 1.4MHz. Instead of logically mixing them, the pin simply outputs the state of only **ONE** specific note per micro-tick.
*   **Acoustic Result:** Zero intermodulation distortion, allowing 4 voices per pin, but results in a slightly "thin" or "sliced" acoustic texture.

**3. The Analog Cheat: Pure PWM Multiplexing (`--mux pwm`)**
*   Abandoning logical mixing entirely, this mode mathematically sums all analog [Phase Modulation](https://en.wikipedia.org/wiki/Phase_modulation) sine waves together *first*, and then compares that massive combined chord against a 22kHz sawtooth carrier.
*   **Acoustic Result:** Flawless phase mixing and 0% intermodulation. However, it leaves a distinct, retro 22kHz "carrier whine" (a faint, high-pitched background hum) characteristic of early Class-D amplifiers.

**4. The Modern Pinnacle: [Delta-Sigma Modulation](https://en.wikipedia.org/wiki/Delta-sigma_modulation) (`--mux dsd`)**
*   The ultimate conclusion of 1-bit audio. It sums the analog waves perfectly, but replaces the 22kHz PWM carrier with a 1st-order Delta-Sigma error accumulator running at 1.4MHz.
*   **The TPDF Breakthrough:** To prevent the deadly "Idle Tones" (limit cycles) inherent to 1st-order DSD, a mathematically precise Triangular Probability Density Function (TPDF) [Dither](https://en.wikipedia.org/wiki/Dither) is injected directly into the error loop, effectively randomizing and decorrelating the quantization noise.
*   **Acoustic Result:** It pushes all quantization noise completely out of the human hearing range, replacing harsh carrier whines with warm analog hiss. It yields breathtaking, studio-grade Hi-Fi sound while technically remaining a pure 1-bit logic stream.

### 2.3. Psychoacoustic Routing: Bass Isolation
Even with advanced multiplexing, routing multiple deep bass notes into the same physical unit caused muddy, low-frequency beat frequencies (beating). 

To solve this, the engine implements a **Frequency-Weighted 'Bass Isolation' Allocator**:
*   The router actively analyzes the frequency content of each virtual unit. 
*   If a unit is already holding a bass note (<150Hz), it receives an extreme routing penalty, preventing it from accepting a second bass note.
*   Conversely, high-frequency treble notes are actively encouraged to pair with existing bass notes.
This acts as a dynamic crossover network, guaranteeing perfect bandwidth separation within each physical core and completely eradicating low-frequency muddiness during massive chords.

### 2.4. Wait-Free Concurrency
To prevent the JVM Garbage Collector from stuttering the audio thread during dense MIDI playback, the engine uses a **Wait-Free, Lock-Free Object Pool**:
*   A flat array of 128 `ActiveNote` slots is allocated once at startup.
*   Note events simply toggle a `volatile boolean active` flag in an $O(1)$ operation.
*   The audio render loop performs zero allocations (zero garbage) and requires zero thread locks, ensuring rock-solid, sample-accurate tempo synchronization.

---

### 2.5. Advanced DSP Pipeline
To transform the harsh, mathematically pure 1-bit logic outputs into a warm, listenable audio stream without introducing severe digital artifacts, the engine employs several critical Digital Signal Processing (DSP) techniques:

1.  **Exponential Oversampling ($1	imes \sim 32	imes$):** The core unit logic can run at up to 1.4MHz (32x the base 44.1kHz sample rate). This extreme temporal resolution pushes the noisy PWM carrier artifacts and XOR intermodulation sidebands far beyond the threshold of human hearing, yielding a studio-quality analog simulation.
2.  **Anti-Blowup DC Blocking:** When multiple asymmetric PWM streams collide (especially in XOR mode), they generate massive DC offsets. If fed directly into an IIR filter, this energy causes "Integral Windup," permanently corrupting the internal filter state (NaN or Infinity) and causing persistent clipping. To prevent this, every virtual unit enforces a strict High-Pass Filter ($R=0.995$) equipped with a hard mathematical clamp, instantly draining any DC accumulation before it can leak into the master bus.
3.  **Acoustic Paper Cone Simulation:** The raw 1-bit square pulses are incredibly harsh. A cascaded 2-pole Low-Pass Filter ($lpha=0.25$) is applied to the final analog sum. This gently rolls off the extreme high frequencies, physically simulating the sluggish transient response of an original 2.25-inch paper cone speaker found inside a 1980s computer chassis.

---

### 2.6. The Apple II Hardware Reality Check
While the `midra beep` engine provides a vast matrix of 1-bit audio possibilities, it is important to distinguish between historical fact and modern over-engineering. A stock 1980s Apple II, running a MOS Technology 6502 CPU at exactly 1.023 MHz, had severe physical limits.

**What was ACTUALLY possible on the Apple II?**
Only two distinct acoustic paths were possible within the constraints of a 1MHz processor:
1.  **The Synthesizer Path:** Generated via real-time CPU cycle-counting.
    *   **Generation:** Only pure Square Waves (`--synth square` - Default) were possible. LFO Vibrato and Duty Sweep were achievable by mathematically altering the delay loops.
    *   **Multiplexing:** Only Boolean XOR (`--mux xor` - Default) was possible, and strictly limited to 2 voices (e.g., *Electric Duet*). 
2.  **The Sampler Path (The "Real Sound" Hack):** 
    *   Pre-rendered 1-bit audio (essentially `--mux pwm` or PCM) could be played back. As documented by Michael J. Mahon in his seminal KansasFest presentations ("Real Sound for 8-bit Apple IIs"), by cycle-counting the 1.023 MHz 6502 CPU to drive the $C030 speaker pin at an ~11 kHz sample rate, the CPU effectively acted as a **Time-Domain DAC**.
    *   $1.023 	ext{ MHz (CPU)} \div 11 	ext{ kHz (Sample Rate)} pprox \mathbf{93 	ext{ Steps}}$. This brilliant hack squeezed roughly 6.5 bits of dynamic range out of a 1-bit pin.
    *   *Constraint:* Real-time **polyphonic** synthesis using PWM was mathematically impossible because the 6502 CPU lacked the speed to perform real-time analog summing of multiple channels before driving the high-frequency comparator logic. As Mahon demonstrated in his later *RTSynth* project (a Real-Time Synthesizer), achieving complex wavetable synthesis and dynamic envelopes via PWM consumed 100% of the CPU's 92-cycle execution window, strictly limiting it to a **monophonic (single-voice)** instrument.

**What is a Modern "Cheat"?**
*   **[Phase Modulation](https://en.wikipedia.org/wiki/Phase_modulation) (`--synth pm`):** Impossible. The 6502 had no floating-point unit (FPU) and lacked hardware multiplication/division, making real-time Sine wave generation and phase deviation impossible at audio rates.
*   **TDM, PWM, and DSD Multiplexing:** Impossible. These require switching the speaker pin at minimums of 44.1kHz up to 1.4MHz. The absolute fastest an Apple II could toggle a pin while doing nothing else was ~150kHz, and realistically ~10kHz when executing audio logic.

**The Purist Architectural View**
The engine's internal pipeline was heavily refactored to align with the strict limitations of 1980s hardware engineering. The architecture rigorously enforces the following purist constraints:
1.  **Pure Integer Synthesis:** Early CPUs lacked Floating-Point Units (FPUs). To emulate this, the engine translates the user's floating-point CLI inputs into strict **16-bit fixed-point phase accumulators** ($0 \sim 65535$) at the driver boundary. Inside the audio loop, [Phase Modulation](https://en.wikipedia.org/wiki/Phase_modulation) relies exclusively on integer addition, bitwise masking (`& 0xFFFF`), and fast bit-shifting (`>>> 8`) to query a 256-byte, 8-bit Sine LUT.
2.  **Strict Boolean Multiplexing:** In a true 1-bit hardware environment, intermediate analog summation between digital pins is physically impossible. The engine enforces a rule where all continuous waves (like FM) must be pushed through a hardware-style Quantizer (PWM or DSD) to become discrete boolean streams (0 or 1) *before* they are allowed to mix.
3.  **Taxonomic Accuracy:** Under this strict framework, PWM and DSD are correctly categorized as "1-Bit Translators", while only **XOR** (boolean collision) and **TDM** (sequential pin reading) are permitted to act as true digital multiplexers.

**Remaining Modern Concessions:**
While the logical data flow is historically accurate, the engine still relies on a few "modern cheats" to achieve listenable polyphony:
*   **The 1.4MHz Clock Speed:** Generating 32x oversampled TDM or DSD requires switching the speaker pin at ~1.4 million times per second. A stock 1MHz 6502 CPU could realistically only toggle a pin at ~15kHz while executing logic, making these high-fidelity modes physically impossible on original hardware.
*   **Floating-Point VCAs:** The final application of the volume decay envelope utilizes modern 64-bit floating-point multiplication rather than relying on a constrained 8-bit hardware multiplier.

By invoking the engine with `--synth square --mux xor --voices 2 -q 1`, the user actively disables these modern clock-speed concessions and oversampling layers, exactly replicating the gritty acoustic reality and absolute physical constraints of 1980s 1MHz hardware.

---

### 2.7. Heuristic DSP Optimization (Parameter Lookup Matrix)
The engine supports 36 distinct architectural permutations ($3$ Synthesis Modes $\times 4$ Multiplexers $\times 3$ Polyphony Levels). Empirical testing demonstrated that a static set of DSP parameters (LPF cutoff, [Dither](https://en.wikipedia.org/wiki/Dither) amplitude, Non-linear overdrive) was insufficient. For instance, parameters optimized for [Phase Modulation](https://en.wikipedia.org/wiki/Phase_modulation) produced severe Intermodulation Distortion (IMD) when applied to XOR multiplexing.

To objectively resolve these state-dependent acoustic conflicts, a **[Genetic Algorithm (GA)](https://en.wikipedia.org/wiki/Genetic_algorithm)** was implemented via a Python-based Hardware-in-the-Loop simulation to programmatically optimize the Java DSP engine's parameters.

**1. Objective Fitness Function (FFT-based)**
For each permutation, the GA rendered a test audio buffer and applied a Fast Fourier Transform (FFT) to evaluate the frequency spectrum. The heuristic fitness score ($S$) was formulated to maximize the Signal-to-Noise Ratio (SNR):
$$ S = \sum M_{fund} - (8.0 \times \sum M_{alias}) - (2.0 \times \sum M_{dc}) $$
*   **$M_{fund}$ (Fundamental Energy):** Sum of magnitudes within the $200\text{Hz} - 800\text{Hz}$ band.
*   **$M_{alias}$ (Aliasing/Quantization Noise):** Sum of magnitudes exceeding $8000\text{Hz}$.
*   **$M_{dc}$ (DC Offset):** Sum of magnitudes below $20\text{Hz}$.

**2. The Evolutionary Model**
The GA utilized a population size of 40 over 15 generations. It employed Roulette Wheel selection biased towards higher SNR scores, combined with elitism (retaining the top 2 candidates per generation) to prevent regression. Mutation operators were designed with a $10\%$ probability of executing a uniform random reset (catastrophe) to escape local minima. This rigorous process identified precise parameter sets that manually tuning could not isolate:
*   **FM + DSD + 4 Voices:** The algorithm established that applying an aggressive non-linear overdrive ($8.7\times$ via `Math.tanh`) to the analog sine wave, combined with high TPDF dither ($0.23$), was mathematically required to preserve the continuous phase information through the 1-bit Delta-Sigma quantizer. Without this pre-shaping, the high-density signal degraded into broadband noise.
*   **XOR + XOR + 2 Voices:** The algorithm determined that external [Dither](https://en.wikipedia.org/wiki/Dither) decreased overall SNR, as the high-frequency harmonics inherent to the XOR square waves acted as an adequate self-dithering mechanism. The GA minimized [Dither](https://en.wikipedia.org/wiki/Dither) to $0.0$ and reduced the Master LPF cutoff coefficient to $0.028$ to strictly attenuate high-frequency folding artifacts.

**3. Static Parameter Injection**
The optimal vectors for all 36 configurations were compiled into a constant-time ($O(1)$) `HashMap` within the Java architecture. Upon initialization, the engine parses the user's CLI flags and dynamically injects the corresponding DSP coefficients before instantiating the render loop. This ensures consistent, mathematically optimized audio output across all historical and modern configurations without introducing runtime overhead.

---

## 3. Global Mixing Pipeline

Once each virtual unit has generated its 1-bit signal, the master bus finalizes the sound:

1.  **Per-Unit DC Blocking:** Before leaving the unit, each 1-bit signal passes through a High-Pass Filter ($R=0.995$, with an anti-blowup clamp). This isolates asymmetric duty cycles caused by XOR logic, ensuring no unit can leak DC offset into the master mix.
2.  **Analog Summing:** The clean, discrete voltages from all active units (dynamically scaled to guarantee at least 16 total polyphony) are added together mathematically, simulating an analog console.
3.  **Acoustic Filtering:** A 2-pole Low-Pass Filter ($\alpha=0.25$) simulates the physical characteristics of a 2.25-inch paper cone speaker, rolling off the harsh 22kHz PWM carrier frequencies.

---

## 4. Conclusion

The `midra beep` engine is a testament to the power of constraint-driven engineering. By combining the brutal physical limitations of 1-bit audio with advanced DSP concepts—[Phase Modulation](https://en.wikipedia.org/wiki/Phase_modulation), high-speed [Time-Division Multiplexing](https://en.wikipedia.org/wiki/Time-division_multiplexing), and Frequency-Weighted Bass Isolation—it successfully resurrects the electrifying, gritty sound of 1980s computer audio without sacrificing modern tempo stability or orchestral polyphonic clarity.

---

## 5. References & Historical Documentation

1. **Paul Lutus, *Electric Duet* (1981)**: The foundational software that proved polyphonic (2-voice) music was possible on the 1-bit Apple II speaker. It utilized ~8kHz Time-Domain Multiplexing and perfectly balanced 6502 cycle-counting (using `NOP` padding) alongside `EOR` logic to toggle the speaker state without drifting out of tune. (Documented by arachnoid.com)
2. **Michael J. Mahon, *Real Sound for 8-bit Apple IIs***: A seminal presentation at KansasFest demonstrating how to achieve multi-bit digital-to-analog conversion (DAC) on a 1-bit speaker using CPU-bound Pulse Width Modulation (PWM), achieving 6.5-bit effective resolution.
3. **Michael J. Mahon, *RTSynth***: Documentation of an Apple II Real-Time Synthesizer utilizing Direct Digital Synthesis (DDS) via a 5-bit PWM DAC (DAC522). It serves as historical proof that generating high-fidelity wavetables on a 1MHz 6502 required dedicating 100% of the CPU to a rigid 92-cycle loop, strictly limiting the output to a single monophonic voice and validating our use of "modern cheats" to achieve polyphony.

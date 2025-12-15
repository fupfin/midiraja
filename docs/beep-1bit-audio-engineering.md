# 1-Bit Audio Engineering (The 'Beep' Synth)

This document outlines the architecture of the `midra beep` command, a pure mathematical software synthesizer designed to recreate the extreme limitations of 1980s 1-bit audio hardware. Internally dubbed the **'Apple II 1-Bit FM Cluster'**, this engine extends the software-based 1-bit polyphony techniques originally developed for the Apple II, fusing them with modern FM synthesis, high-speed Time-Division Multiplexing (TDM), and wait-free concurrency to create the ultimate 1-bit digital instrument.

---

## 1. Historical Context: The Apple II "Bit-Banging" Legacy

The internal speaker of early 8-bit computers was a primitive 1-bit device, physically capable of only two voltage states (On/Off). However, there was a profound architectural difference between platforms:

*   **IBM PC:** Utilized the **Intel 8253 PIT** hardware timer, which could automatically generate square waves at a specified frequency without constant CPU intervention.
*   **Apple II:** Featured no dedicated sound hardware. To produce sound, the CPU had to manually toggle the speaker's memory-mapped I/O port at precise intervals using cycle-counted machine code. This "bit-banging" approach required 100% of the CPU's attention just to maintain a single steady pitch.

Because of this extreme hardware poverty, the Apple II became the ultimate laboratory for software-driven audio innovation. In 1981, Paul Lutus released **"Electric Duet,"** utilizing interleaved execution and logical mixing to multiplex two distinct voices onto a single 1-bit speaker pin.

The `midra beep` engine pushes this philosophy to its absolute mathematical limit, simulating a dynamic cluster of Apple II units capable of generating polyphonic, 2-Operator FM synthesis through pure 1-bit pins.

---

## 2. Technical Architecture & Engineering Evolution

The engine's architecture is the result of rigorous acoustic engineering, overcoming severe mathematical constraints to achieve perfectly synchronized, polyphonic 1-bit audio.

### 2.1. The Synthesis Triad (Timbre Generation)
Before any notes can be multiplexed together, the engine must generate their fundamental timbre. The engine provides three distinct synthesis algorithms (`--synth`), representing different eras of digital audio engineering:

**1. Modern: Yamaha-Like Phase Modulation (`--synth pm` - Default)**
*   Provides a clean, bell-like, pseudo-analog sound.
*   Instead of mathematically unstable Direct Frequency Modulation, it adds the modulator wave directly to the *lookup phase* of a mathematically perfect carrier accumulator. This guarantees absolute pitch stability at any frequency and prevents high-frequency chords from collapsing into dissonant noise.

**2. Hardcore 1980s: Timbral Ring Modulation (`--synth xor`)**
*   Mimics the brilliant 'Ring Modulation' hacks used by 1-bit legends like Tim Follin on the ZX Spectrum.
*   It generates two raw 1-bit square waves (a Carrier and a slightly detuned Modulator) and logically crushes them together using an Exclusive-OR (`^`) gate. This carves out a gritty, aggressively buzzing, chainsaw-like chiptune timbre before the signal ever reaches the master multiplexer.

**3. Classic Apple II: Pure Square Wave (`--synth square`)**
*   The original workhorse of 1980s computer games (e.g., *Karateka*, *Ultima*).
*   Uses a single square wave oscillator but keeps it "alive" by continuously wobbling its fundamental pitch via a 6Hz LFO (Vibrato) and mathematically sweeping its pulse width between 10% and 90% (Wah-Wah Duty Sweep). It is given a much longer decay envelope (1.5s) to compensate for the acoustic energy lost during narrow duty cycles.

### 2.2. The Multiplexing Evolution: From XOR to DSD
The most critical engineering challenge in 1-bit audio is mixing multiple polyphonic notes within a single, binary (On/Off) speaker pin. The engine documents the historical and mathematical evolution of this problem by providing four distinct multiplexing algorithms (`--mux`):

**1. The 1981 Hack: XOR Logic Gate (`--mux xor`)**
*   Mimicking Paul Lutus's original "Electric Duet," this mode converts each analog sine wave into a discrete 1-bit Pulse Width Modulated (PWM) stream, then crushes them together through a boolean Exclusive-OR (`^`) logic gate.
*   **Acoustic Result:** Severe, authentic Ring Modulation. Creates a highly gritty, buzzing chiptune texture.
*   **Limitation:** XORing more than 2 voices causes catastrophic phase cancellation ($1 \oplus 1 = 0$), obliterating the fundamental pitch into white noise.

**2. The High-Speed Switch: Time-Division Multiplexing (`--mux tdm`)**
*   An impossible feat for a 1MHz CPU, this algorithm switches between notes at over 1.4MHz. Instead of logically mixing them, the pin simply outputs the state of only **ONE** specific note per micro-tick.
*   **Acoustic Result:** Zero intermodulation distortion, allowing 4 voices per pin, but results in a slightly "thin" or "sliced" acoustic texture.

**3. The Analog Cheat: Pure PWM Multiplexing (`--mux pwm`)**
*   Abandoning logical mixing entirely, this mode mathematically sums all analog Phase Modulation sine waves together *first*, and then compares that massive combined chord against a 22kHz sawtooth carrier.
*   **Acoustic Result:** Flawless phase mixing and 0% intermodulation. However, it leaves a distinct, retro 22kHz "carrier whine" (a faint, high-pitched background hum) characteristic of early Class-D amplifiers.

**4. The Modern Pinnacle: Delta-Sigma Modulation (`--mux dsd` - Default)**
*   The ultimate conclusion of 1-bit audio. It sums the analog waves perfectly, but replaces the 22kHz PWM carrier with a 1st-order Delta-Sigma error accumulator running at 1.4MHz.
*   **Acoustic Result:** It pushes all quantization noise (the carrier whine) completely out of the human hearing range. It yields breathtaking, studio-grade Hi-Fi sound while technically remaining a pure 1-bit logic stream.

### 2.3. Psychoacoustic Routing: Bass Isolation
Even with advanced multiplexing, routing multiple deep bass notes into the same physical Apple II unit caused muddy, low-frequency beat frequencies (beating). 

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

## 3. Global Mixing Pipeline

Once each virtual Apple II unit has generated its 1-bit signal, the master bus finalizes the sound:

1.  **Per-Unit DC Blocking:** Before leaving the unit, each 1-bit signal passes through a High-Pass Filter ($R=0.995$, with an anti-blowup clamp). This isolates asymmetric duty cycles caused by XOR logic, ensuring no unit can leak DC offset into the master mix.
2.  **Analog Summing:** The clean, discrete voltages from all active units (dynamically scaled to guarantee at least 16 total polyphony) are added together mathematically, simulating an analog console.
3.  **Acoustic Filtering:** A 2-pole Low-Pass Filter ($\alpha=0.25$) simulates the physical characteristics of a 2.25-inch paper cone speaker, rolling off the harsh 22kHz PWM carrier frequencies.

---

## 4. Conclusion

The `midra beep` engine is a testament to the power of constraint-driven engineering. By combining the brutal physical limitations of 1-bit audio with advanced DSP concepts—Phase Modulation, high-speed Time-Division Multiplexing, and Frequency-Weighted Bass Isolation—it successfully resurrects the electrifying, gritty sound of 1980s computer audio without sacrificing modern tempo stability or orchestral polyphonic clarity.
# PSG Audio Engineering & Tracker Hacks Whitepaper

**Status:** Research & Design Phase (Target: `midra psg`)

This document outlines the architectural blueprint for the upcoming Programmable Sound Generator (PSG) emulator in Midiraja. Our goal is not simply to emulate the hardware specifications of the AY-3-8910 or SN76489 chips, but to **emulate the software tricks (Tracker Techniques)** that 1980s demoscene hackers used to push this hardware far beyond its physical limits.

---

## 1. The Hardware Reality (The Constraint)

A standard PSG chip (like the Yamaha YM2149F found in MSX and Atari ST) provides extremely limited resources:
* **3 Tone Channels:** Capable of generating pure Square Waves.
* **1 Noise Generator:** A Pseudo-Random LFSR generator.
* **1 Hardware Envelope Generator:** A single, global timer that can apply geometric volume shapes (Sawtooth, Triangle, Decay) to any channel.
* **4-Bit Volume:** Each channel's volume can only be set to 16 discrete levels (0-15).

If a standard modern MIDI file (with 16 channels, 128-level velocity, and 10-note polyphony) is fed directly into this hardware, it will sound terrible. Notes will be dropped, chords will be impossible, and the volume will feel flat.

---

## 2. The Demoscene Tricks (The Architecture)

To bridge the gap between rich MIDI files and 3-channel hardware, the Midiraja `psg` engine will implement a **Tracker-Driven Interception Layer**. Instead of feeding MIDI directly to the chip, MIDI events will be caught by a virtual 50Hz/60Hz (VBLANK) software "Tracker" that applies the following historical hacks:

### 2.1. Fast Arpeggios (Fake Chords)
* **The Problem:** The chip only has 3 channels. A simple C-Major chord (C-E-G) consumes 100% of the chip's resources, leaving no room for bass or melody.
* **The Hack:** When polyphonic notes arrive on a single MIDI channel, the Tracker intercepts them. Instead of assigning them to multiple hardware pins, it assigns them to *one* pin and rapidly switches the frequency of that pin between the 3 notes every 1/60th of a second.
* **The Result:** The human ear blends the rapidly alternating notes into a single, cohesive chord. This creates the iconic, bubbling "chiptune arpeggio" texture.

### 2.2. Hardware Envelope as Audio (Buzzer/SID Voice)
* **The Problem:** PSGs only output square waves, making the basslines sound thin compared to the Commodore 64's legendary SID chip (which had sawtooth and triangle waves).
* **The Hack:** The hardware envelope generator is supposed to run slowly (e.g., 2Hz to fade out a note). Hackers realized that if you crank the envelope frequency up to 50Hz-400Hz (Audio Rate), the volume fades so fast that the envelope itself becomes a raw Sawtooth waveform!
* **The Implementation:** For MIDI notes below C3 (130Hz), the engine will disable the standard Tone generator and instead synchronize the Hardware Envelope frequency to the MIDI note pitch, generating a massive, aggressive Buzz/Sawtooth bassline.

### 2.3. Software Envelopes (4-Bit Stepped Decay)
* **The Problem:** There is only 1 hardware envelope generator for the whole chip. If you use it for the bassline, the melody and chords will have no volume decay.
* **The Hack:** Composers ignored the hardware envelope and manually updated the 4-bit volume registers (R8, R9, R10) using software interrupts.
* **The Implementation:** Midiraja will use a 50Hz software tick to manually decrement the volume of active notes from 15 down to 0. This creates a distinct, stepped, "zipper-like" fade-out effect that is the hallmark of MSX/ZX Spectrum soundtracks, completely rejecting smooth 64-bit floating-point volume curves.

### 2.4. Interleaved Noise (Pitched Snare Drums)
* **The Problem:** A snare drum needs both "Noise" (the rattle) and "Tone" (the body), but mixing them statically sounds muddy.
* **The Hack:** Rapidly toggle a channel's mixer register between Tone mode and Noise mode on alternate frames.
* **The Implementation:** When MIDI Channel 10 (Drums) triggers a snare, the Tracker will interleave 1 frame of pure white noise with 1 frame of a 200Hz square wave, creating a punchy, aggressive 8-bit drum hit.

## 3. The Expansion: Konami SCC+ (Sound Cartridge) Emulation

While the PSG is powerful when hacked, it remains limited by its pure square waves. To solve this, Midiraja fully emulates the **Konami SCC+ (Sound Cartridge)**. 
*Note on Hardware Accuracy:* The original SCC (K051649) had a hardware limitation where channels 4 and 5 were forced to share the same waveform memory. Midiraja bypasses this and emulates the upgraded **SCC+** cartridge (used in *SD Snatcher*), providing 5 truly independent waveform channels, which is vastly superior for complex MIDI polyphony.

### 3.1. MSX Pair Architecture (1x PSG + 1x SCC+)
* **The Concept:** The SCC lacks a noise generator and hardware envelopes, making it terrible for drums and aggressive bass. Historically, the SCC was *never* used alone; it was always plugged into an MSX machine containing a PSG.
* **The Implementation:** When the `--scc` flag is enabled, the Midiraja engine instantiates chips in **Pairs**. `System 1` consists of `[Chip 0: PSG] + [Chip 1: SCC]`. 
* **Strict Routing (Domain Isolation):**
  * **Drums (Channel 10):** Exclusively confined to the PSG (Chip 0) to utilize its Noise Generator for crisp snares and hi-hats. Drums will never spill over to the SCC.
  * **Melody/Chords:** Exclusively confined to the SCC (Chip 1) to utilize its 5 custom wavetable channels for rich, lush sounds. Melodies will never spill over to the harsh PSG square waves, preserving the aesthetic integrity of the lead track.

### 3.2. 32-Byte Procedural Wavetables & Interpolation
* **The Hardware:** The SCC uses a tiny 32-byte RAM buffer per channel to define a custom waveform (-128 to +127).
* **The Implementation:** Instead of violating copyright by dumping original Konami ROMs, Midiraja procedurally generates mathematically pure 32-byte waves based on the General MIDI instrument family (e.g., `sin(t)*0.8 + sin(3t)*0.2` for Strings/Pads, decaying Sawtooth for Pianos). 
* **Historical Accuracy (Aliasing):** By default, the SCC outputs raw, stepped waveforms without interpolation, perfectly reproducing the gritty quantization noise of the original 1980s hardware.
* **Modern Enhancement (`--smooth`):** If the `--smooth` flag is passed, the engine activates floating-point Linear Interpolation across a double-precision phase accumulator, wiping out the aliasing for a clean "studio" synthesizer sound.

### 3.3. Integer Bit-Shifting, 11-Bit DAC, & Volume Compensation
* **Hardware Quirk (Volume Truncation):** Analysis of the original SCC logic reveals that it lacks a floating-point multiplier. Volume is applied by multiplying the 8-bit sample by the 4-bit volume, and then *bit-shifting right by 4* (`(sample * vol) >> 4`). This harsh truncation is faithfully emulated to generate the authentic Konami 'crunch'.
* **The 11-bit DAC:** The original hardware outputs through an external 11-bit resistor network DAC (051650). Midiraja simulates the non-linear dB attenuation curve of this DAC by passing the bit-shifted integer through a pre-calculated non-linear `dacTable`, identical to the one used by the PSG.
* **The RMS Problem:** A pure square wave (PSG) has massive RMS energy compared to the complex dynamic curves of an SCC wavetable. If played together blindly, SCC melodies are completely drowned out by PSG percussion.
* **The Solution:** We apply a massive **2.6x Volume Boost (0.85 multiplier vs PSG's 0.33)** specifically to the SCC output stage. This ensures that the delicate SCC leads slice cleanly through the dense, aggressive PSG rhythm section.

### 3.5. Priority Voice Stealing (Eviction)
* **The Problem:** Due to the strict isolation rule (3.1), the SCC only has 5 channels. If background chords consume all 5 channels, a highly important lead melody note might be dropped entirely.
* **The Solution:** When a high-priority channel (MIDI channels 0-3) requests a note and the SCC is full, the engine scans all active SCC channels. It identifies the channel currently playing the *quietest* note (e.g., a decaying background pad). The engine ruthlessly terminates that background note, steals the hardware channel, and instantly reassigns it to play the new lead melody. This guarantees that critical tracks are never lost in dense mixes.

### 3.4. Smart Instrument-Matching Arpeggios
* **The Enhancement:** Building on the Fast Arpeggio hack (2.1), the engine now intelligently handles polyphony overflow across both PSG and SCC.
* **The Logic:** When a chord is played and channels run out, the engine searches the chip for a channel *already playing that exact MIDI instrument*. It then silently converts that single-note channel into an Arpeggio channel, sliding the new note into its 4-note buffer. This ensures Pianos arpeggiate with Pianos, and Strings with Strings, preventing chaotic "hard stealing" of unrelated tracks.

---

## 4. Data Flow Diagram

```text
[ Modern MIDI File (Polyphonic, Smooth Volume, 16 Channels) ]
       │
       ▼
[ The 50Hz Software Tracker Layer ]
   ├── Smart Polyphony Router ──> Groups identical instruments into Arpeggio Buffers
   ├── Drum Mapper            ──> Routes to PSG Noise Generator
   ├── Melody Mapper          ──> Routes to SCC Wavetables
   └── ADSR Quantizer         ──> Converts float velocity to Stepped Decay
       │
       ├──► [ System 0: PSG (AY-3-8910) ]
       │      ├── Ch 0 (Square / Buzzer)
       │      ├── Ch 1 (Square)
       │      └── Ch 2 (Square) + Noise Gen
       │
       └──► [ System 0: SCC+ (5 Independent Channels) ]
              ├── Ch 0 (32-Byte Wavetable + Bit-Shift Volume) <─ Steals channels if full
              ├── Ch 1 (32-Byte Wavetable + Bit-Shift Volume)
              ├── Ch 2 (32-Byte Wavetable + Bit-Shift Volume)
              ├── Ch 3 (32-Byte Wavetable + Bit-Shift Volume)
              └── Ch 4 (32-Byte Wavetable + Bit-Shift Volume)
                     │
                     ▼
[ Volume Compensation & 11-Bit Non-Linear DAC Mixing ]
       │
       ▼
[ Audio Output (44.1kHz PCM) ]
```


---

## 5. References & Hardware Documentation

The architectural decisions and DSP logic in this emulator were derived from analyzing historical documentation and open-source implementations of the MSX hardware:

1. **openMSX (`SCC.cc`)**: Analysis of the openMSX emulator source code revealed the raw integer bit-shifting logic `(sample * vol) >> 4` used for volume attenuation, correcting our initial floating-point assumptions.
   * *Source:* [openMSX GitHub Repository](https://github.com/openMSX/openMSX/blob/master/src/sound/SCC.cc)
2. **ARM Assembly SCC Emulator (`SCC.s`)**: Review of highly optimized embedded code by FluBBaOfWard confirmed the severe hardware limitation of the original K051649 chip (channels 4 and 5 sharing a single waveform buffer).
   * *Source:* [FluBBaOfWard/SCC GitHub Repository](https://github.com/FluBBaOfWard/SCC/blob/main/SCC.s)
3. **Konami Sound Cartridge (SCC+) Tech Docs**: Detailed the memory mapping and the crucial architectural upgrade of the SCC+ (used in *SD Snatcher*), which finally separated all 5 waveform channels, validating our decision to emulate the "Plus" version by default.
   * *Source:* [msxnet.org: Konami Sound Cartridge (SCC+)](http://bifi.msxnet.org/msxnet/tech/soundcartridge)
4. **MSX.org Wiki (Konami SCC)**: Confirmed the existence and specifications of the external 11-bit parallel resistor network DAC (Konami 051650), which we simulate using our non-linear `dacTable`.
   * *Source:* [MSX Wiki: Konami SCC](https://www.msx.org/wiki/Konami_SCC)

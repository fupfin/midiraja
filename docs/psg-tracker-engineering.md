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

---

## 3. Data Flow Diagram

```text
[ Modern MIDI File (Polyphonic, Smooth Volume) ]
       │
       ▼
[ The 50Hz Software Tracker Layer ]
   ├── Polyphony Detector  ──> Creates High-Speed Arpeggios
   ├── Bass Router         ──> Converts to Audio-Rate Hardware Envelope (Buzzer)
   ├── Drum Mapper         ──> Triggers Interleaved Noise/Tone frames
   └── ADSR Quantizer      ──> Converts float velocity to 4-Bit stepped decay
       │
       ▼
[ Pure Mathematical PSG Chip Emulation ]
   ├── Channel A (Square)
   ├── Channel B (Square)
   ├── Channel C (Square / Buzzer)
   └── Noise Generator
       │
       ▼
[ Audio Output (44.1kHz PCM) ]
```

# Midiraja Future Improvements

This document outlines the roadmap for future enhancements to the Midiraja project.

## 1. 🎹 Alternative Soft Synth Integrations
**Goal:** Expand the synthesis engine to support more formats and true zero-dependency rendering.
- **TinySoundFont (TSF):** A single-header C library for SoundFont rendering. If compiled into a tiny shared library and bundled inside the JAR, we could extract and link it at runtime to achieve true "Zero-Dependency" AOT software synthesis without requiring users to manually install FluidSynth.

## 2. 🌍 Automated Cross-Platform CI/CD
**Goal:** Automate native binary compilation for Mac, Linux, and Windows via GitHub Actions.
**Benefits:**
- Automatic release generation on tag pushes.
- Integration with Package Managers: `brew install midiraja` (Homebrew) and `scoop install midra` (Windows).
- Instant, JRE-free downloads for anyone, anywhere.

## 3. 💡 Experimental & Retro Audio Ideas
**Goal:** Explore extreme retro audio constraints and unique synthesizer architectures to expand the "museum of computer audio" experience.
- **PSG (Programmable Sound Generator) Mode (`midra psg`):** A pure Java synthesizer emulating early 8-bit consoles (AY-3-8910, SN76489, NES). It would mathematically generate basic waveforms (Square for melody, Triangle for bass) and map MIDI drum tracks (Channel 10) to a dedicated White Noise generator with ADSR envelopes to simulate classic 8-bit percussive hits.
- **Amiga "Paula" Simulation (`--paula` for GUS):** Add an option to the GUS engine to mathematically restrict playback to the harsh hardware limitations of the Commodore Amiga's Paula chip.
  - **4-Voice Polyphony Limit:** Aggressive voice stealing to replicate tracker limitations.
  - **Hard-Panned Stereo:** Force voices 0/3 to 100% Left and 1/2 to 100% Right, completely ignoring MIDI pan events.
  - **8-Bit Forced Resolution:** Route output through the existing 8-bit Noise Shaped Quantizer and Amiga-style DAC Reconstruction Filter.
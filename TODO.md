# Midiraja Future Improvements

This document outlines the roadmap for future enhancements to the Midiraja project.

---

## Roadmap (Feedback-Driven)

### "Just Works" Default Playback
**Goal:** Let new users run `midra song.mid` with no engine flag and hear audio instantly.
- Implement `EngineAutoSelector`: detect available engines in priority order (soundfont → 1bit)
- Auto-discover SF2 files from common system paths (Homebrew, apt, MuseScore)
- Fallback to `1bit` when no SF2 is found — always produces sound, zero dependencies

### `midra doctor` / `midra demo`
**Goal:** Reduce "why isn't this working?" support burden and improve onboarding.
- `midra doctor`: check audio output, ALSA/CoreAudio availability, optional library links, ROM paths
- `midra demo`: play a short built-in MIDI clip using each available engine in sequence — no MIDI file required

### Package Manager Distribution
**Goal:** `brew install midra` / `scoop install midra` / `aur` one-liner.
- Homebrew tap (`fupfin/tap`)
- Scoop bucket
- AUR package (community or official)

### Config / Preset System
**Goal:** Persist per-user defaults so common flags are not retyped.
- `midra config set default-engine soundfont`
- `midra config set default-sf2 ~/soundfonts/FluidR3_GM.sf2`
- TOML config file at `~/.config/midra/config.toml`

### Engine Comparison (`midra compare`)
**Goal:** Let users hear the same MIDI file across multiple engines side-by-side.
- `midra compare song.mid` — cycles through all available engines, pausing between each
- Useful for evaluating SF2 files or choosing a retro aesthetic

### Windows Completion
**Goal:** First-class Windows experience on par with macOS/Linux.
- CI smoke test on Windows (GitHub Actions)
- Chocolatey package
- Windows installer (`winget` manifest)

---

## Synthesis Engine Ideas

### 4. 🎹 Alternative Soft Synth Integrations
**Goal:** Expand the synthesis engine to support more formats and true zero-dependency rendering.
- **TinySoundFont (TSF):** A single-header C library for SoundFont rendering. If compiled into a tiny shared library and bundled inside the JAR, we could extract and link it at runtime to achieve true "Zero-Dependency" AOT software synthesis without requiring users to manually install FluidSynth.

### 5. ☕ Pure Java Audio & `midrax` Revival
**Goal:** Replace `libmidiraja_audio` (miniaudio) with a pure Java audio sink so that `midrax` becomes a truly cross-platform distribution requiring only Java 25+, with no native libraries.
- Implement a `javax.sound.sampled`-based `AudioEngine` as an alternative to `NativeAudioEngine`.
- Once complete, revive `midrax` as a standalone ZIP release — no `build-native-libs.sh` needed.

### 6. 🎵 Commodore 64 SID Synthesizer (`midra sid`)
**Goal:** Add cycle-accurate C64 SID chip emulation.
- **Option A — Pure Java:** Zero-dependency implementation like `beep`/`psg`. SID's resonant filters (LP/BP/HP) and ring modulation make it more complex than PSG.
- **Option B — libsidplayfp:** Dynamically link to the `libsidplayfp` C library for maximum accuracy, similar to the munt/fluid pattern.

### 7. 💡 Retro Audio Ideas
**Goal:** Explore extreme retro audio constraints and unique synthesizer architectures.
- **Amiga "Paula" Simulation (`--paula` for GUS):** Add an option to the GUS engine to mathematically restrict playback to the harsh hardware limitations of the Commodore Amiga's Paula chip.
  - **4-Voice Polyphony Limit:** Aggressive voice stealing to replicate tracker limitations.
  - **Hard-Panned Stereo:** Force voices 0/3 to 100% Left and 1/2 to 100% Right, completely ignoring MIDI pan events.
  - **8-Bit Forced Resolution:** Route output through the existing 8-bit Noise Shaped Quantizer and Amiga-style DAC Reconstruction Filter.

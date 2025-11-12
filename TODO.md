# Midiraja Future Improvements

This document outlines the roadmap for future enhancements to the Midiraja project.

## 1. 🎹 Alternative Soft Synth Integrations via FFM
**Goal:** Expand the successful FFM dynamic linking architecture beyond FluidSynth to support different MIDI standards and retro gaming aesthetics.
**Ideas to Explore:**
- **Munt (libmunt):** The ultimate Roland MT-32/CM-32L emulator. Essential for correctly playing early 90s DOS game MIDI files that sound incorrect on General MIDI/FluidSynth. Requires investigating its C API or writing a small C wrapper since Munt is primarily C++. (Target: `--munt /path/to/roms/`)
- **Pure Java TiMidity Port (Gravis Ultrasound `.pat` support):** Instead of using FFM to wrap the C `libTiMidity` (which does not support realtime MIDI event injection), we will undertake a clean-room Java port of the core TiMidity DSP engine. This aligns perfectly with our Zero-Dependency vision and allows full integration with our realtime `PlaybackEngine` and TUI.
    - *Phase 1:* Implement `GusPatchReader` to parse `.pat` file headers, envelopes, and 8-bit/16-bit PCM sample data into Java records.
    - *Phase 2:* Implement `TimidityJavaEngine` to handle frequency scaling (resampling), envelope application, and software audio mixing.
    - *Phase 3:* Integrate into `SoftSynthProvider` to allow playing MIDI files with authentic 90s GUS patch sets.
    - *Methodology:* Test-Driven Development (TDD) at the class level. Write the parsing tests first using dummy or minimal `.pat` structures, then implement the parser, refactoring heavily to leverage modern Java features (Records, MemorySegment, var) and eliminate technical debt.
- **OPL3 / AdLib Emulator:** FFM bindings for a Yamaha OPL3 FM synthesis emulator (like Nuked OPL3). Allows playing MIDI files using the iconic, gritty FM synth sound of early Sound Blaster cards without needing any external soundbank files. (Target: `--opl3`)
- **TinySoundFont (TSF):** A single-header C library for SoundFont rendering. If compiled into a tiny shared library and bundled inside the jar, we could extract and link it at runtime to achieve true "Zero-Dependency" AOT software synthesis without requiring users to `brew install fluidsynth`.

## 2. 🌍 Automated Cross-Platform CI/CD
**Goal:** Automate native binary compilation for Mac, Linux, and Windows via GitHub Actions.
**Benefits:**
- Automatic release generation on tag pushes.
- Integration with Package Managers: `brew install midiraja` (Homebrew) and `scoop install midra` (Windows).
- Instant, JRE-free downloads for anyone, anywhere.

# Changelog

## [0.2.1] - 2026-03-14

### Added
- Linux (x86_64, ARM64) release packages
- Bundle libmt32emu in release tarball — MT-32 emulation works out of the box without a separate Munt installation

### Fixed
- Segfault on Linux aarch64 caused by FFM upcall — replaced with C-side ring buffer
- FreePats missing from release tarball when built without running `setupFreepats` first
- Install directory not added to PATH automatically after installation
- Missing prerequisite checks (GraalVM, cmake, etc.) before build

### Changed
- Removed static linking of libADLMIDI / libOPNMIDI — unified to shared library bundle via rpath
- `LibraryPaths` now generated at build time to centralize OS-specific fallback library paths

## [0.2.0] - 2026-03-14

First public release.

In celebration of MARCHintosh, this release includes a set of DSP effects paying homage to
classic retro hardware: the compact Mac speaker, Apple II, ZX Spectrum, Covox Speech Thing,
Disney Sound Source, PC speaker, and more — each faithfully modeled after its original
acoustic character.

### Engines
- OPL2/OPL3 FM synthesis via libADLMIDI
- OPN2/OPNA FM synthesis via libOPNMIDI (Sega Genesis / PC-98)
- MT-32 / CM-32L emulation via Munt
- SF2/SF3 soundfonts via FluidSynth
- GUS wavetable synthesis with bundled FreePats
- Java built-in synthesizer

### Platform
- macOS (Apple Silicon, Intel)

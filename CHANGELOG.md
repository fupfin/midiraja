# Changelog

## [0.3.5] - 2026-03-17

### Fixed
- **Windows**: release package was missing all DLLs (`libmidiraja_audio.dll`, etc.) — `gradle clean` ran after native library build, wiping `build/native-libs/`; `clean` is now a separate step before native builds
- **Windows**: `midra munt` could not find `libmt32emu.dll` — CMake names the DLL `libmt32emu-2.dll` (SOVERSION suffix); packaging now copies whichever `libmt32emu*.dll` exists

## [0.3.4] - 2026-03-17

### Fixed
- **Windows**: passing a path ending with `\` in PowerShell caused a trailing `"` to appear in the argument (Windows command-line quoting artifact) — now stripped automatically
- **Windows / Linux**: directory arguments were not recognized as directories in GraalVM native image when the path contained spaces or special characters — switched to NIO2 `Files.isDirectory()`
- **Linux**: `libtsf.so` caused `undefined symbol: log` — fixed by building on a glibc < 2.29 host so that `-Wl,--no-as-needed -lm` correctly records `libm.so.6` in `DT_NEEDED`
- **`midra demo`**: classic mode (non-TUI terminal) skipped playback and moved to the next track immediately — `DemoTransitionScreen` now returns `FINISHED` instead of `NEXT`

### Changed
- `midra gus ./dir/` (directory as sole argument) now scans the directory for MIDI files instead of treating it as a GUS patch directory; pass `midra gus ./patches/ file.mid` to use a custom patch directory

## [0.3.3] - 2026-03-17

### Fixed
- **Windows**: `midra demo` (soundfont engine) failed with non-ASCII username paths — switched from `tsf_load_filename` (C `fopen()`, ANSI code page) to `tsf_load_memory` (Java NIO reads the file, bypassing encoding issues)
- **Linux**: `libtsf.so` caused `undefined symbol: log` on systems with glibc < 2.29 — switched to statically linking libm (`-Wl,--push-state,-Bstatic -lm -Wl,--pop-state`) so `log()` is embedded directly in `libtsf.so` with no runtime dependency on `libm.so.6`
- **Linux installer**: `tar` printed excessive future-timestamp warnings during installation — suppressed with `--warning=no-timestamp` (GNU tar)

## [0.3.2] - 2026-03-17

### Added
- **`midra demo`** — curated 10-track playlist that tours every built-in synthesis engine with no setup required; transition screen shows engine name and song title before each track

### Changed
- Bundled SoundFont switched from MuseScore General SF3 to **FluidR3 GM SF3** (MIT license) — more consistent volume across all GM instruments

## [0.3.1] - 2026-03-16

### Added
- **Engine selector** — running `midra song.mid` without a subcommand shows an interactive menu listing available OS MIDI ports and all built-in engines; arrow keys to navigate, Enter to select
- **Bundled FluidR3 GM SF3** — `midra soundfont song.mid` now plays immediately with no external file; bundled MIT-licensed SoundFont
- **Windows ARM64** — `midra-windows-arm64.zip` release package; `install.ps1` detects architecture automatically

### Changed
- Engine selector lists OS MIDI ports first, followed by built-in engines ordered by audio quality: `soundfont` → `patch` → `opn` → `opl` → `psg` → `1bit`
- `midra device` with no arguments lists available ports (replaces the removed `ports` subcommand); `--list` flag also supported
- Help screen now lists Commands before Options, with aliases shown in brackets

### Fixed
- Release workflow failed on Windows due to bash syntax in a PowerShell shell context

## [0.3.0] - 2026-03-15

### Added
- **Windows support** — native binary, `install.ps1` one-liner installer, and Windows CI build
- **`soundfont` engine** — built-in TinySoundFont plays SF2/SF3 files with no FluidSynth installation required; full DSP effects rack supported
- **`fm` unified subcommand** — `midra fm opl` / `midra fm genesis` replaces separate `opl` / `opn` commands; `opl`, `opn`, `adlib`, `genesis`, `pc98` remain as shortcuts

### Changed
- All built-in engines calibrated to a consistent −9 dBFS peak output level — switching engines no longer causes unexpected volume jumps
- `tsf` → `soundfont`, `gus` → `patch` (old names kept as aliases)

### Fixed
- JLine upgraded to 3.26.3 for correct keyboard input on Windows terminals
- Windows audio device selection and input handling bugs

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

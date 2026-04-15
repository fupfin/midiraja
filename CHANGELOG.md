# Changelog

## [0.4.0a] - 2026-04-14

### Added

- **VGM/VGZ native playback** (`midra vgm <file>`) — plays chiptune VGM files directly via libvgm; supports FF/BW seek (←/→ or F/B), volume and speed adjustment during playback
- **MIDI → VGM export** (`midra export vgm --system <chip>`) — converts MIDI to VGM register-stream format; supported chips: SN76489 (PSG), AY-3-8910, YM2413 (OPLL/MSX-Music), K051649 (SCC), YM3812 (OPL2/AdLib), YMF262 (OPL3), YM2612/OPN2 (Sega Genesis via libOPNMIDI VGMFileDumper)
- **Tracker format playback** — ProTracker `.mod`; format detected automatically from file extension
- **OS media key integration** — macOS (MPRemoteCommandCenter), Linux (MPRIS2 D-Bus), Windows (SystemMediaTransportControls); play/pause, prev/next, and seek via hardware media keys
- **`midra resume`** — interactive session history; auto-saves each playback session; resume from where you left off, bookmark favourites with `*`, delete entries in-TUI; press `R` during playback to enter the resume screen
- **Spectrum analyzer** — 8-band stereo FFT display in LineUI and DashboardUI
- **`midra midi-info <file>`** — prints MIDI file metadata (title, tempo, track count, duration)
- **`--quiet`** — suppresses TUI output for scripting
- **`--compress`** — priority-based DSP compressor in the signal chain
- **`--log LEVEL`** — unified log level flag (WARN/INFO/DEBUG/TRACE); replaces `--verbose`/`--debug`
- **Amiga Paula DAC filter** (`--retro amiga/a500/a1200`) — models the low-pass characteristic of the Amiga 500 and 1200 audio outputs
- **`--retro-drive`** — adjustable PWM drive gain for PC speaker and Apple II retro modes

### Changed

- **PC speaker / Apple II retro modes** now use 4× oversampled cone simulation instead of a plain integrator, suppressing step-rate aliasing harmonics
- **SCC alias suppression** — 14 kHz post-render LP filter applied after libvgm render to reduce wavetable step aliasing
- **FM MIDI→VGM conversion** uses 3-dimensional OPL patch mapping (timbre × note-range × envelope) and a 2-pass ensemble-aware GM program assignment
- **UI**: `Tr: off` shown (dimmed) for engines that do not support transpose (e.g. VGM playback); previously showed `+0`
- **NowPlayingPanel** redesigned — Vol/Tempo/Trans consolidated, Effects line added, MIDI copyright row added
- **Loop and shuffle** can be toggled live with `L`/`S` keys; icons shown in PLAYLIST panel header
- **Port name** now includes chip prefix, emulator in parentheses, and ROM version for MT-32

### Fixed

- **VGM FF/BW**: progress bar snapped back after seek — `playLoop()` wall-clock base is now rebased on each seek so `currentMicroseconds` stays consistent
- **Native binary** (`midra vgm`): `MissingForeignRegistrationError` on startup — two missing FFM descriptors for `vgm_create` and `vgm_get_duration_us` added to `reachability-metadata.json`
- **OPL2/OPL3 pitch**: note frequency calculation was 12 octaves too low — corrected
- **YM2413 (OPLL) pitch**: frequency formula was 1024× too high — corrected
- **FM carrier pitch**: carriers with MULT ≠ 1 produced wrong pitch — corrected
- **VGM loop**: files with loop points repeated indefinitely, preventing playlist advancement — `loopCount` set to 1
- **SCC staircase aliasing**: program-dependent waveforms suppress step-rate aliasing for notes above E5
- **Shuffle**: toggling shuffle mid-playlist only reshuffled remaining tracks instead of the full playlist
- **Ctrl+C**: terminal not fully restored (cursor hidden, alt screen active) — fixed by routing Ctrl+C through normal QUIT path with ISIG disabled

## [0.3.5] - 2026-03-18

### Added
- **`midra info`** — new subcommand that prints build version, native library paths, detected patch/soundfont locations, and available synthesis engines

### Changed
- **Improved audio quality for built-in synths** — internal DSP architecture was significantly restructured: signal levels, per-synth calibration, and volume are now unified into a single gain stage, reducing distortion and producing cleaner output across all effects (tube saturation, reverb, chorus)
- **Volume for internal synths now goes up to 150%** — since volume is applied as a PCM gain (not MIDI CC7), exceeding 100% boosts output above nominal level; values > 100 may clip

### Changed
- **Seek state**: `channelPrograms` array is now re-applied during seek chase, and `ChannelPressure` messages are forwarded, preventing silent channels and wrong patches after seeking

### Fixed
- **Windows**: argument ending with `\` in PowerShell caused a trailing `"` to be appended to the path; the validator now strips it

## [0.3.4] - 2026-03-17

### Fixed
- **Windows**: DLLs missing from release package — CI `gradle clean` was wiping native libs before packaging
- **Windows**: directory path not recognized as directory in GraalVM native image
- **Windows**: path ending with `\` in PowerShell caused a trailing `"` in the argument
- **Linux**: `libtsf.so` caused `undefined symbol: log` on glibc < 2.29
- **`midra demo`**: classic mode skipped playback and moved to next track immediately

### Changed
- `midra gus ./dir/` now scans the directory for MIDI files; pass patch dir explicitly as `midra gus ./patches/ file.mid`

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

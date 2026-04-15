# Midiraja Engineering Documentation

This is the index of all technical documentation for the Midiraja project.
For end-user instructions, see the **[User Guide](user_guide.md)** instead.

---

## Engineering Overview

Midiraja is a terminal-native MIDI player distributed as a **single self-contained native binary** — no JVM, no package manager, instant startup. Every architectural decision flows from that constraint.

### Native Binary via GraalVM AOT

The application is compiled ahead-of-time with **GraalVM Native Image**. This eliminates JVM startup overhead but imposes strict rules: no runtime reflection, no dynamic class loading, and all foreign function descriptors must be pre-registered in `reachability-metadata.json`. The `NativeMetadataConsistencyTest` enforces this registration at `./gradlew test` time, catching missing entries before the slow (~30s) native compile cycle.

### Native Library Binding via FFM API

Rather than JNI, Midiraja uses the **Java Foreign Function & Memory API** (JEP 454, stable in Java 22+) to bind C/C++ audio libraries at runtime. `MethodHandle.invokeExact()` provides zero-overhead downcalls with type-safe signatures enforced at the call site. Three distinct binding patterns are used depending on the thread-safety and latency characteristics of each library — see [Native Audio Bridge Engineering](native-bridge-engineering.md) for details.

### Synthesizer Architecture

All synthesizers share a common contract (`SoftSynthProvider` / `MidiNativeBridge`) but split into two groups:

- **Self-contained engines** (1-bit, PSG, GUS): Pure Java synthesis; no native library required. Rendered directly into a `short[]` ring buffer on a dedicated render thread.
- **Native-bridge engines** (FM/OPL, FM/OPN, SoundFont/TSF, Munt, FluidSynth): A thin Java FFM layer delegates to a C/C++ library. The bridge pattern chosen depends on the library's thread model — Queue-and-Drain for non-thread-safe state machines, Wall-Clock Sync for latency-sensitive timestamped APIs, Driver Delegation for self-managing libraries.

All built-in engines feed into the same **DSP pipeline** (`AudioProcessor` chain), which operates in-place on `float[]` arrays with zero per-frame allocation.

### Audio Pipeline

PCM flows as: `short[] → ShortToFloatFilter (÷32768) → [EQ → Tube → Chorus → Reverb] → FloatToShortSink (×32767) → miniaudio ring buffer → OS`.

The DSP filter chain is assembled once at startup by the CLI command and injected into the render thread — no runtime branching in the hot audio loop. All built-in engines are calibrated to **−9 dBFS peak** so that switching engines does not cause volume jumps and DSP effects behave predictably regardless of source.

### CLI Architecture

The command-line interface is built on **picocli 4.7.5** with the `picocli-codegen` annotation processor. The processor generates GraalVM reflection configuration at compile time, keeping `reachability-metadata.json` free of manual CLI entries.

The command tree is structured as:

```
midra (MidirajaCommand)
 ├── fm [opl|adlib|opn|genesis|pc98]  — FM synthesis; aliases: opl, opn, adlib, genesis, pc98
 ├── soundfont                         — built-in SoundFont (TSF); aliases: tsf, sf2, sf
 ├── patch                             — GUS patches; aliases: gus, pat, guspatch
 ├── psg / 1bit / mt32                — other built-in engine subcommands (library bundled)
 ├── fluidsynth                        — external engine subcommand (user-installed library)
 ├── device                            — OS MIDI routing
 └── ports                             — list available MIDI devices
```

Options are composed via **picocli `@Mixin`s** rather than inheritance, keeping each subcommand class focused:

| Mixin | Shared options |
|-------|---------------|
| `CommonOptions` | `--volume`, `--speed`, `--start`, `--transpose`, `--shuffle`, `--loop`, `--recursive`, `--verbose`, `--ignore-sysex`, `--reset`, UI mode |
| `FxOptions` | `--tube`, `--chorus`, `--reverb`, `--reverb-level`, `--bass`, `--mid`, `--treble`, `--lpf`, `--hpf` |
| `FmSynthOptions` | `--emulator`, `--chips` (shared by `opl` and `opn`) |
| `UiModeOptions` | `--classic` / `--mini` / `--full` (`-1` / `-2` / `-3`) |

### TUI Architecture

Terminal I/O uses **JLine 3.25.1** with the `jline-terminal-ffm` backend — JLine's own terminal abstraction layer built on the FFM API rather than JNI, consistent with the rest of the project. See [TUI Engineering](tui-engineering.md) for the full architecture.

**Raw mode & keyboard input**: `JLineTerminalIO` calls `terminal.enterRawMode()` and disables echo, then reads keystrokes via JLine's `NonBlockingReader`. This allows live tempo/volume/transpose control without blocking the playback thread.

**Three UI modes** are selected automatically based on terminal capability, or forced by the user:

| Mode | Class | Description |
|------|-------|-------------|
| `--full` (`-3`) | `DashboardUI` | Full-screen dashboard — now-playing panel, 16-channel VU meter strip, playlist, controls help |
| `--mini` (`-2`) | `LineUI` | Single-line interactive status bar with ANSI in-place update |
| `--classic` (`-1`) | `DumbUI` | Static line logging, pipe-friendly, no ANSI |

**Zero-allocation rendering**: `ScreenBuffer` wraps a pre-allocated `StringBuilder(4096)` that each panel writes into on every render tick. The complete frame is flushed as a single `print()` call, avoiding per-character terminal I/O overhead.

**Responsive layout**: `DashboardLayoutManager` recalculates panel heights each render based on live terminal dimensions. Panels have declared min/max content-line constraints; the manager allocates vertical space top-to-bottom with overflow gracefully collapsed. ANSI `DECAWM` (auto-wrap disable) and cursor hiding prevent visual tearing during rapid window resizes.

### Key Technology Choices

| Concern | Choice | Rationale |
|---------|--------|-----------|
| Native binary | GraalVM Native Image | Zero-startup, single-file distribution |
| C library binding | FFM API (Java 22+) | Type-safe, zero-overhead vs. JNI |
| Audio I/O | miniaudio (C, single-header) | Zero-dependency, cross-platform CoreAudio/ALSA/PulseAudio |
| SoundFont playback | TinySoundFont (C, single-header) | Bundleable SF2/SF3 with no transitive deps |
| FM synthesis | libADLMIDI / libOPNMIDI | Mature, cycle-accurate OPL/OPN emulation |
| MT-32 emulation | Munt (libmt32emu) | Only open-source MT-32 emulator with timestamped MIDI API |
| Build system | Gradle + cmake (for C libs) | Unified JVM + native build in one `./gradlew nativeCompile` |
| CLI framework | picocli 4.7.5 + codegen | Annotation-driven subcommands; codegen generates GraalVM reflection config at compile time |
| Terminal I/O | JLine 3 (`jline-terminal-ffm`) | Raw mode, non-blocking keyboard, FFM backend — no JNI |

---

## Synthesizer Engines

| Document | What it covers |
|----------|----------------|
| [FM Synthesis Engineering](fm-synthesis-engineering.md) | Architecture of the `opl` and `opn` FM engines — lock-free design, OPL/OPN chip emulation via libADLMIDI & libOPNMIDI, DSP volume normalisation |
| [PSG Tracker Hacks](psg-tracker-engineering.md) | `psg`/`msx` engine — Yamaha YM2149F/AY-3-8910 and Konami SCC emulation, arpeggio/envelope tracker tricks for polyphony |
| [Music Format Engineering](additional-format-engineering.md) | Non-MIDI music format survey — MOD (supported), IT/XM/S3M (future SF2 path), SNES SPC (hardware snapshot model), OS MIDI port limitations |
| [1-Bit Audio Engineering](beep-1bit-audio-engineering.md) | `1bit`/`beep` engine — phase modulation, delta-sigma modulation, XOR multiplexer, and the strict integer mathematics of single-bit audio |
| [VGM Playback](vgm_playback.md) | `vgm` engine — native emulation of 40+ sound chips via libvgm; C API bridge via Panama FFM; MIDI→VGM conversion path; supported chip list; performance and thread safety |
| [Retro Hardware Audio Simulation](retro/README.md) | `--retro` modes — cycle-accurate reconstruction of Macintosh 128k, ZX Spectrum beeper, IBM PC Speaker (RealSound), Covox, and Amiga Paula |

---

## Audio Pipeline

| Document | What it covers |
|----------|----------------|
| [DSP Pipeline Architecture](dsp-pipeline-engineering.md) | Zero-allocation in-place processing pipeline; tube saturation (tanh waveshaper), stereo chorus, Freeverb reverb, RBJ biquad EQ; output level calibration (−9 dBFS target) |

---

## TUI & Terminal

| Document | What it covers |
|----------|----------------|
| [TUI Engineering](tui-engineering.md) | 세 가지 UI 모드(Dashboard/Line/Dumb), 패널 아키텍처, ANSI 시퀀스 관리, 키 입력 처리, TerminalSelector 메뉴, PlaybackEngine 동시성 모델, Ctrl+C 터미널 복원 설계 (ISIG 비활성화), 셧다운 훅 한계 |

---

## Native Bridge & Distribution

| Document | What it covers |
|----------|----------------|
| [Native Audio Bridge Engineering](native-bridge-engineering.md) | Three FFM binding patterns — Queue-and-Drain (OPL, OPN, TSF), Wall-Clock Sync (Munt MT-32), Driver Delegation (FluidSynth); TinySoundFont single-header integration |
| [MT-32 Integration Architecture](mt32_integration.md) | Deep-dive on Munt integration — threading model, wall-clock MIDI timestamping, CoreAudio latency, FFM API usage, song-transition silence elimination |
| [Native Library Distribution](native-library-distribution.md) | Bundle-vs-install decision, `rpath` / `@executable_path` strategy, CI packaging for macOS / Linux |
| [Build & Release Engineering](build-and-release.md) | Complete build pipeline — Gradle task graph, C library compilation, GraalVM native image configuration, code quality gates, release archive structure, CI/CD matrix |

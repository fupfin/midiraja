# libvgm Integration Architecture

**Status:** Implementation Complete  
**Context:** Midiraja plays VGM (Video Game Music) files using **libvgm**, a mature, open-source emulation library maintained by ValleyBell. Rather than the older VGM→MIDI→SynthProvider pipeline, libvgm provides direct native emulation of 40+ sound chips with perfect cycle accuracy and lossless fidelity.

This document covers the architecture, C API surface, integration patterns, and the MIDI→VGM export path.

---

## Why libvgm Over VGM→MIDI Conversion?

The legacy approach converted VGM chip events into MIDI note sequences, then rendered them through SoundFont. This involved several lossy transformations:

1. **Timbre loss:** PSG instruments (square waves) had to map to GM instruments (no perfect square lead in SoundFont)
2. **Envelope flattening:** Hardware envelopes (attack/decay) were approximated by MIDI velocity and duration
3. **Polyphony constraints:** 16 MIDI channels max; some systems (OPL2, complex FM chips) operate as note pools beyond 16 notes
4. **Volume normalization:** Constant conversion between chip-specific levels and MIDI velocity (0-127)

**libvgm eliminates all of these:**

- **Cycle-accurate emulation** — Runs the actual chip simulation with precise timing
- **Lossless fidelity** — Outputs raw PCM at the original sample rate; no intermediate format
- **Full polyphony** — No channel limits; each chip operates exactly as designed
- **Direct integration** — PCM output feeds straight into Midiraja's DSP pipeline

The trade-off is size (~5 MB shared library) and slightly higher CPU cost (~5-10% per chip instance), but modern hardware easily handles this.

---

## Architecture Overview

```
┌─────────────────┐
│  VGM File      │
│  (or MIDI)      │
└────────┬────────┘
         │
    ┌────▼──────────────────────┐
    │ MusicFormatLoader          │
    │ .load(path/data)           │
    └────┬──────────────────────┘
         │
    ┌────▼──────────────────────────────────────┐
    │ if VGM → use LibvgmSynthProvider          │
    │ if MIDI + --system → convert via Exporter│
    │ if MIDI → existing SoundFont path        │
    └────┬───────────────────────────────────────┘
         │
    ┌────▼──────────────────────┐
    │ FFMLibvgmBridge (Panama)   │
    │ .vgm_create()              │
    │ .vgm_open_file()           │
    │ .vgm_open_data()           │
    │ .vgm_render()              │
    │ .vgm_is_done()             │
    │ .vgm_close()               │
    └────┬──────────────────────┘
         │
    ┌────▼──────────────────────┐
    │ vgm_bridge.cpp             │
    │ (native C wrapper)         │
    │ ↓                          │
    │ libvgm C API               │
    │ ↓                          │
    │ Chip emulators             │
    │ (SN76489, YM2612, etc.)    │
    └────┬──────────────────────┘
         │
    ┌────▼──────────────────────┐
    │ short[] PCM buffer         │
    │ (sample_rate, channels=2)  │
    └────┬──────────────────────┘
         │
    ┌────▼───────────────────────┐
    │ DSP Pipeline               │
    │ (ShortToFloat)             │
    │ + EQ, Tube, Reverb, etc.   │
    │ (FloatToShort)             │
    └────┬───────────────────────┘
         │
    ┌────▼──────────────────────┐
    │ Audio Output               │
    │ (miniaudio to OS)          │
    └────────────────────────────┘
```

### Key Components

**LibvgmSynthProvider** (`com.fupfin.midiraja.synth.libvgm`)
- Implements `SoftSynthProvider`
- Wraps FFM bridge and renders VGM data on the audio render thread
- Manages sample rate negotiation, state cleanup

**FFMLibvgmBridge** (`com.fupfin.midiraja.synth.libvgm`)
- Panama FFM binding to `vgm_bridge.c` shared library (`libmidiraja_vgm.dylib`/`.so`/`.dll`)
- Exposes `vgm_create()`, `vgm_open_file()`, `vgm_open_data()`, `vgm_render()`, `vgm_is_done()`, `vgm_close()`
- Manages arena allocation (thread-local per render context)

**vgm_bridge.cpp** (`src/main/c/libvgm/`)
- Thin C++ wrapper around libvgm public API
- Allocates VGM context, buffers, and state
- Exports simple C interface (no C++ name mangling, no stdlib dependencies)
- Compiled via CMakeLists.txt → `libmidiraja_vgm` shared library

**libvgm** (`ext/libvgm` git submodule)
- ValleyBell's open-source emulation library
- CMakeLists.txt builds as static library linked into `libmidiraja_vgm`
- Supports 40+ sound chip types and detailed VGM parsing

---

## C API Surface

The `vgm_bridge.c` wrapper exposes a minimal interface:

```c
/**
 * Create a new VGM playback context at the specified sample rate.
 * Returns an opaque context pointer (void*).
 * Returns NULL on failure (e.g., invalid sample rate).
 */
void* vgm_create(uint32_t sample_rate);

/**
 * Open a VGM/VGZ file from disk.
 * Returns 0 on success, non-zero on error (file not found, corrupt).
 */
int vgm_open_file(void* ctx, const char* path);

/**
 * Open VGM data from a memory buffer.
 * len = byte length of buffer; buffer is copied internally.
 * Returns 0 on success, non-zero on error (corrupt data).
 */
int vgm_open_data(void* ctx, const uint8_t* data, size_t len);

/**
 * Render the next block of samples.
 * buf = output short[] array (must hold at least frames * 2 shorts for stereo)
 * frames = number of stereo sample pairs to generate
 * Returns number of frames actually rendered (may be < requested if end reached).
 */
uint32_t vgm_render(void* ctx, short* buf, uint32_t frames);

/**
 * Query if playback is complete (reached end or looped).
 * Returns non-zero if done, 0 if more samples available.
 */
int vgm_is_done(void* ctx);

/**
 * Release all resources and close the context.
 * Safe to call multiple times; the context is invalid after this.
 */
void vgm_close(void* ctx);
```

All timestamps and sample counts use libvgm's internal precision (typically PCM sample index from start of file).

---

## MIDI → VGM Conversion Path

Midiraja can convert MIDI and MOD files to VGM format in memory, then play via libvgm. This lets you audition how a composition would sound on classic hardware, using either the `--system` shorthand or the explicit `--chips` flag.

### Chip Selection

#### `--system` presets

| Preset | Chip(s) | Notes |
|--------|---------|-------|
| `zxspectrum` | AY-3-8910 × 2 | ZX Spectrum: dual PSG |
| `fmpac` | YM2413 (OPLL) | MSX FM-PAC: 9 FM voices or 6 FM + rhythm |
| `msx` | YM2413 + AY-3-8910 | MSX2: FM + PSG combined |
| `msx-scc` | SCC + AY-3-8910 | MSX with wavetable SCC |
| `sb16` | YMF262 (OPL3) | Sound Blaster 16: 18 FM voices, 4-op support |
| `megadrive` / `genesis` | YM2612 + SN76489 | Sega Genesis: 5 FM + 1 FM percussion + 3 PSG tone voices. **Default.** |
| `adlib` | YM3812 (OPL2) | AdLib: 9 FM voices, 2-op |
| `pc98` | YM2608 (OPNA) | PC-98: 6 FM + 2 SSG voices + ADPCM-A native rhythm (6 built-in percussion samples) |
| `x68000` | YM2151 (OPM) | Sharp X68000: 8 FM voices; no percussion (ADPCM chip not yet supported) |
| `neogeo` | YM2610 (OPNB) | Neo Geo: 4 FM + 2 SSG voices + ADPCM-A native rhythm (6 built-in percussion samples) |
| `neogeo-b` | YM2610B (OPNB extended) | Neo Geo MVS: 6 FM + 2 SSG voices + ADPCM-A native rhythm (6 built-in percussion samples) |
| `pc88` | YM2203 (OPN) | PC-88: 3 FM + 2 SSG voices; no percussion |
| `gameboy` / `dmg` | DMG (LR35902 APU) | Game Boy: 2 pulse + 1 wave melody voices, CH4 noise percussion |
| `pce` / `huc6280` | HuC6280 (PC Engine PSG) | PC Engine / TurboGrafx-16: 6 wavetable voices |
| `nes` / `nesapu` | NES APU (RP2A03) | NES: 2 pulse + 1 triangle melody voices, CH4 noise percussion |

Passing an unrecognised value prints the valid choices.

#### `--chips` — explicit chip combinations

The `--chips` flag accepts one or more chip names with a separator that also determines the **routing mode**:

| Separator | Syntax example | Routing mode | Behaviour |
|-----------|----------------|--------------|-----------|
| `+` | `scc+ay8910` | CHANNEL | Round-robin assignment by MIDI channel |
| `,` | `ay8910,ym2413` | CHANNEL | Same as `+` |
| `>` | `scc>ay8910` | SEQUENTIAL | Fill first chip before spilling to next |

When `--chips` and `--system` are both absent the preset `megadrive` is used.

### PSG-Preferred Routing

When AY-3-8910 is present alongside other chips, **GM programs 112–127** (sound-effects / percussive timbres such as TinkBell, Gunshot, Bird Tweet) are always routed to the AY-3-8910 first, regardless of which chip the round-robin or fill-first algorithm would otherwise select. This reflects the historical convention that PSG chips handle noise-based sound effects better than FM synthesis.

### Percussion Routing

MIDI channel 9 (percussion) is always routed to the single handler with the highest `percussionPriority()` weight. When no chip supports percussion, channel 9 events are silently dropped.

| Priority | Meaning | Handlers |
|----------|---------|---------|
| 0 | No percussion support | SCC, YM2151, YM2203, HuC6280 |
| 1 | PSG noise channel (limited) | AY-3-8910, SN76489, DMG (CH4 noise), NES APU (CH4 noise) |
| 2 | FM synthesis patches | YM2413, OPL3, YM2612, YM3812 |
| 3 | ADPCM native rhythm | YM2608 (ADPCM-A, 6 channels), YM2610 (ADPCM-A, 6 channels), YM2610B (ADPCM-A, 6 channels) |

When multiple handlers share the same maximum priority, the first one in the handler list wins. The `megadrive`/`genesis` preset places YM2612 (priority 2) before SN76489 (priority 1), so percussion goes to YM2612.

### Voice Allocation

**CHANNEL mode** (`+` / `,`): each MIDI channel is permanently assigned to one chip handler in round-robin order. Channel 1 → handler 0, channel 2 → handler 1, etc., wrapping around.

**SEQUENTIAL mode** (`>`): voices are assigned to handler 0 until all of its polyphony slots are occupied, then overflow spills into handler 1, and so on. Good for maximising polyphony when two chips of the same type are combined.

### Supported Chips for MIDI → VGM Export

| Chip | Type | Voices | Percussion | Notes |
|------|------|--------|------------|-------|
| AY-3-8910 | PSG | 3 tone + noise | PSG noise (priority 1) | Handles noise/SFX programs by preference |
| YM2413 (OPLL) | FM | 9 melodic or 6 + rhythm | FM rhythm mode (priority 2) | Built-in patch ROM; user patch via CC |
| SCC (Konami) | Wavetable | 5 | None (priority 0) | No noise generator; percussion silently dropped |
| YMF262 (OPL3) | FM | 14 melodic + 4 drum round-robin | FM synthesis (priority 2) | Full GM patch mapping via WOPL bank |
| YM2612 (OPN2) | FM | 5 melodic + 1 percussion | FM WOPN patches (priority 2) | WOPN bank loaded from `ext/libOPNMIDI/fm_banks/gm.wopn` |
| SN76489 | PSG | 3 tone | PSG noise (priority 1) | Noise channel for drums; used in Mega Drive `megadrive`/`genesis` preset |
| YM3812 (OPL2) | FM | 9 melodic + 4 drum round-robin | FM synthesis (priority 2) | AdLib `adlib` preset; OPL3 subset, single bank only |
| YM2608 (OPNA) | FM + SSG + ADPCM-A | 8 melodic (6 FM + 2 SSG) | ADPCM-A native rhythm (priority 3) | PC-98 `pc98` preset; ADPCM-A ROM is internal to libvgm (no VGM data block needed); SSG via embedded `Ay8910Handler` |
| YM2151 (OPM) | FM | 8 melodic | None (priority 0) | X68000 `x68000` preset; ADPCM percussion chip not yet supported |
| YM2610 (OPNB) | FM + SSG + ADPCM-A | 6 melodic (4 FM + 2 SSG) | ADPCM-A native rhythm (priority 3) | Neo Geo `neogeo` preset; ADPCM-A ROM loaded via VGM data block type `0x82` (`ym2610_adpcm_a.bin`); SSG via embedded `Ay8910Handler`; ADPCM-B not yet supported |
| YM2610B (OPNB extended) | FM + SSG + ADPCM-A | 8 melodic (6 FM + 2 SSG) | ADPCM-A native rhythm (priority 3) | Neo Geo MVS `neogeo-b` preset; same VGM commands as YM2610 (`0x58`/`0x59`); bit 31 set in header clock field at `0x4C` activates YM2610B mode in libvgm; shares same `ym2610_adpcm_a.bin` ROM |
| YM2203 (OPN) | FM + SSG | 5 melodic (3 FM + 2 SSG) | None (priority 0) | PC-88 `pc88` preset; single-port VGM command `0x55`; SSG via embedded `Ay8910Handler`; `chAddr = slot` directly (0,1,2) |
| DMG (LR35902 APU) | Pulse × 2 + Wave + Noise | 3 melodic (CH1 pulse+sweep, CH2 pulse, CH3 wave) | CH4 noise (priority 1) | Game Boy `gameboy`/`dmg` preset; VGM command `0xB3`; requires v1.70 VGM header (clock at offset 0x80); wave RAM initialised with sine approximation; pulse freq `x = 2048 − round(131072 / hz)`, wave freq `x = 2048 − round(65536 / hz)` |
| HuC6280 (PC Engine PSG) | Wavetable × 6 | 6 melodic | None (priority 0) | PC Engine `pce`/`huc6280` preset; VGM command `0xB9`; requires v1.70 VGM header (clock 3,579,545 Hz at offset 0xA4); 32-sample 5-bit unsigned wave RAM per channel; `period = round(clock / (32 × hz))` |
| NES APU (RP2A03) | Pulse × 2 + Triangle + Noise | 3 melodic (CH1 pulse, CH2 pulse, CH3 triangle) | CH4 noise (priority 1) | NES `nes`/`nesapu` preset; VGM command `0xB4`; requires v1.70 VGM header (clock 1,789,773 Hz at offset 0x84); pulse timer `= round(clock / (16 × hz)) − 1`, triangle timer `= round(clock / (32 × hz)) − 1`; constant-volume mode (no envelope); GM noise map drives noise period index (0–15) |

### Conversion Architecture

```
MusicFormatLoader.load()
├─ if MIDI/MOD (non-VGM input):
│  ├─ load as Sequence
│  ├─ resolve ChipSpec (--system or --chips)
│  ├─ CompositeVgmExporter → byte[] VGM data
│  └─ vgm_open_data(ctx, bytes, len)
└─ if .vgm/.vgz file:
   └─ vgm_open_file(ctx, path)
```

Each handler (`Ay8910Handler`, `Ym2413Handler`, `SccHandler`, `Opl3Handler`, `Ym2612Handler`, `Sn76489Handler`, `Ym3812Handler`, `Ym2608Handler`, `Ym2151Handler`, `Ym2610Handler`, `Ym2610BHandler`, `Ym2203Handler`, `DmgHandler`, `HuC6280Handler`, `NesApuHandler`) translates MIDI note-on/note-off/CC events into the target chip's register writes, generating valid VGM data.

`Ym2610Handler` embeds a VGM ROM data block (type `0x82`, command `0x67 0x66`) at the start of the stream containing the 8192-byte ADPCM-A ROM (`ym2610_adpcm_a.bin`, identical to `fmopn_2608rom.h`). The block uses the ROM data block format: an 8-byte prefix `[romTotalSize:4LE][startOffset:4LE]` followed by the ROM payload, so `dblkLen = 8200`. This block must precede any ADPCM-A register writes so that VGM players can load the ROM into the emulated chip before samples are triggered. `Ym2608Handler` does **not** embed a data block because YM2608's ADPCM-A ROM is hardcoded inside libvgm (`fmopn_2608rom.h`).

**Key design points:**

- **In-memory:** No temporary file I/O; conversion and playback are seamless
- **Deterministic:** Same MIDI + chip spec = same VGM bytes every time
- **Lossless within target:** The VGM is properly formatted and playable by any VGM player
- **DSP effects compatible:** The resulting PCM can be processed by EQ, reverb, etc.

---

## Build & Integration

### Directory Structure

```
src/main/c/libvgm/
├── CMakeLists.txt              # Builds vgm_bridge.cpp + libvgm
├── vgm_bridge.cpp              # C++ wrapper around libvgm API
├── vgm_bridge.h                # Public C header (FFM binds here)
└── ... (vgm_bridge implementation)

ext/
└── libvgm/                      # Git submodule (https://github.com/ValleyBell/libvgm)
    ├── CMakeLists.txt          # libvgm's own build config
    ├── src/
    │   ├── vgm/vgmfmtfmt.c    # VGM file parser
    │   ├── chips/               # Emulator implementations
    │   │   ├── sn76489.c
    │   │   ├── ym2612.c
    │   │   ├── ym2151.c
    │   │   └── ... (40+ chips)
    │   └── utils/               # Helper utilities
    └── include/                 # Public headers
        └── vgm/vgmfmtfmt.h

gradle/
└── libvgm.gradle               # Task definitions for cmake build
```

### Build Process

During `./gradlew nativeCompile`:

1. **CMake invocation** (via `scripts/build-native-libs.sh`):
   - CMakeLists.txt in `src/main/c/libvgm/` configures build
   - Compiles `vgm_bridge.cpp` + all libvgm source files
   - Produces `libmidiraja_vgm.dylib` (macOS), `.so` (Linux), `.dll` (Windows)

2. **FFM metadata generation:**
   - `vgm_bridge.h` is scanned for C function signatures
   - Panama FFM binding code is auto-generated (or written by hand in `FFMLibvgmBridge`)

3. **Native image inclusion:**
   - Shared library is bundled in `app/build/distributions/midra-<os>-<arch>.tar.gz`
   - At runtime, `FFMLibvgmBridge.loadLibrary()` finds it via `@executable_path` (macOS) or `rpath` (Linux)

### Dependency Versions

- **libvgm:** Latest from ValleyBell (git submodule, pinned to commit)
- **CMake:** 3.16+ (uses modern syntax)
- **Compiler:** GCC 9+ or Clang 10+ (C++17 standard for vgm_bridge)

---

## Supported Chips (Complete List)

libvgm supports emulation of the following sound chips. Midiraja exposes all of them:

### Sega & Arcade

| Chip | Type | Notes |
|------|------|-------|
| SN76489 | PSG (3 tone + 1 noise) | Sega Genesis, Master System |
| YM2612 | FM (6 channels) | Genesis FM synthesizer; paired with SN76489 |
| YM2151 (OPM) | FM (8 channels, 4-op) | Arcade boards (CPS1, System 16, Neo Geo) |
| YM2203 (OPN) | FM (3 ch) + PSG SSG | PC-88, some arcade |
| YM2608 (OPNA) | FM (6 ch) + PSG SSG | PC-98 primary sound chip |
| YM2610 (OPNB) | FM (4 ch) + PSG SSG + ADPCM | Neo Geo |
| YM2610B | Extended YM2610 | Neo Geo MVS variant |

### MSX & Home Computers

| Chip | Type | Notes |
|------|------|-------|
| AY-3-8910 / YM2149 | PSG (3 tone + 1 noise) | MSX, ZX Spectrum, Atari ST |
| YM2413 (OPLL) | FM (9 voices, preset instruments) | MSX, SMS |
| K051649 (SCC) | Wavetable (5 channels) | Konami MSX cartridges |
| K051320 (Bubble System) | Custom wavetable | Konami arcade |

### Nintendo & Game Systems

| Chip | Type | Notes |
|------|------|-------|
| Game Boy DMG | 2 pulse + 1 wave + 1 noise | Original Game Boy; `gameboy`/`dmg` preset |
| NES APU | 2 pulse + 1 triangle + 1 noise + DMC | Nintendo Entertainment System; `nes`/`nesapu` preset |
| SNES SPC700 | 8-channel ADPCM sampler | Super NES |
| HuC6280 | 6-channel wavetable + stereo | PC Engine / TurboGrafx-16; `pce`/`huc6280` preset |

### DOS & PC

| Chip | Type | Notes |
|------|------|-------|
| YM3812 (OPL2) | FM (9 channels, 2-op) | Adlib, Sound Blaster, DOS games |
| YMF262 (OPL3) | FM (18 channels, 2-op or 4-op) | Sound Blaster Pro, later DOS cards |
| Covox (DAC) | 8-bit DAC | Speech Thing, other R-2R converters |

### Extra / Niche

| Chip | Type | Notes |
|------|------|-------|
| Namco (various) | Wavetable | Arcade (Pac-Man, Galaga, Dig Dug, etc.) |
| Konami VRC6 | 2 pulse + 1 sawtooth | NES cartridge (Castlevania) |
| Konami VRC7 | FM (6 channels) | NES cartridge |
| Sunsoft 5B | PSG with envelope | NES cartridge |
| Nuked OPL2/OPL3 | Cycle-accurate FM | Emulator cores included in libvgm |

For the most up-to-date and complete list, refer to libvgm's documentation at https://github.com/ValleyBell/libvgm.

---

## Performance Characteristics

### CPU Cost

Typical per-chip overhead at 48 kHz:

| Chip | Relative Cost | Notes |
|------|---------------|-------|
| SN76489 | ~0.5% | Simple PSG, minimal state |
| YM2612 + SN76489 | ~3-4% | Heavy FM synthesis |
| YM2151 | ~2-3% | 8 channels, 4-op FM |
| OPL3 | ~2% | Cycle-accurate emulation |
| HuC6280 | ~1-2% | Wavetable, 6 channels |
| Game Boy DMG | ~0.5% | Minimal emulation |

Running all chips simultaneously (e.g., a multi-chip VGM with Genesis + YM2151) stacks the costs. Modern CPUs easily handle 5-10% total overhead.

### Memory Cost

Per context: ~1-2 MB (including ROM, state, buffers). Minimal additional heap allocation after context creation.

### Latency

No added latency beyond the audio buffer size (typically 10-20 ms). Rendering is fully synchronous; no background threads.

---

## Thread Safety & Concurrency

**FFMLibvgmBridge behavior:**

- Each `LibvgmSynthProvider` instance gets its own `vgm_create()` context
- Contexts are thread-confined: created on the render thread, used exclusively by the render thread, destroyed on the render thread
- No shared state between contexts
- **Result:** Fully thread-safe; concurrent playback of multiple VGM files (e.g., preview + main) requires separate contexts

**Rendering loop** (in `LibvgmPlaybackEngine`):
```java
while (playback active) {
    int frames = bridge.renderFrames(pcmBuf, FRAMES_PER_RENDER);
    audioOut.processInterleaved(pcmBuf, frames, 2);
    fireSpectrumLevels();   // read SpectrumAnalyzerFilter.getLevels() → onSpectrumUpdate
    if (bridge.isDone()) break;
}
```

The render thread calls `vgm_render()` (via `bridge.renderFrames()`) in the hot loop. UI state updates from other threads are routed through `LibvgmPlaybackEngine`'s listener list; the engine itself is thread-confined to the render thread.

---

## Spectrum Analysis Integration

When spectrum display is enabled, `LibvgmPlaybackEngine` inserts a `SpectrumAnalyzerFilter` into the DSP pipeline ahead of the final audio output. After each render block the engine calls `fireSpectrumLevels()`, which reads the filter's latest snapshot and dispatches it to all registered `PlaybackEventListener`s via `onSpectrumUpdate(float[] levels)`.

### SpectrumAnalyzerFilter

`SpectrumAnalyzerFilter` (`com.fupfin.midiraja.dsp`) is a **transparent** `AudioProcessor` — it passes audio through unmodified while maintaining an 8-band stereo analysis. Key properties:

| Property | Value |
|----------|-------|
| FFT size | 1024 samples (Cooley-Tukey radix-2) |
| Window | Hann window (reduces spectral leakage) |
| Bands | 8 log-spaced: 63 Hz, 125 Hz, 250 Hz, 500 Hz, 1 kHz, 2 kHz, 4 kHz, 8 kHz |
| Output | 16 `float` values in interleaved order `[L0,R0,L1,R1,…,L7,R7]` |
| Level range | 0.0 (silence) – 1.0 (full scale), mapped from −40 dB to 0 dB |
| AGC | Asymmetric rise/fall: `MAX_RISE_RATE=0.1` per frame; `MAX_DECAY=0.99` per frame |
| Decay | `DECAY=0.85` per FFT frame applied to peak envelope |

### Thread Safety

`SpectrumAnalyzerFilter.levels` is `volatile float[]`. The render thread replaces the reference atomically after each FFT analysis (swap-on-write). UI threads reading `getLevels()` always see a consistent snapshot; they may observe the previous frame, but will never see a partially written array. No locking is required.

---

## Error Handling

### File Parse Errors

If `vgm_open_file()` returns non-zero:

1. **FileNotFoundException** or **AccessDeniedException** — File does not exist or is not readable
2. **InvalidVgmFileException** — Header corrupt, magic bytes invalid, unsupported version
3. **UnsupportedChipException** — VGM references a chip unsupported by this build of libvgm

Errors are logged at WARN level; playback gracefully stops with a brief silence.

### Memory Allocation Failures

If `vgm_create()` returns NULL:

- Extremely rare (would require <1 MB free heap)
- Caught as **OutOfMemoryError** → playback stops, user sees an error

### Data Corruption During Playback

If `vgm_render()` detects corrupt data mid-stream:

- Logs a warning
- Advances playback to next valid block
- User may hear a brief artifact or skip

---

## Related Documentation

- [VGM → MIDI Conversion Architecture](vgm_to_midi_conversion.md) — The older conversion pipeline (still available for non-libvgm engines)
- [Native Audio Bridge Engineering](native-bridge-engineering.md) — FFM API patterns; libvgm uses the Queue-and-Drain pattern (implicit via SoftSynthProvider contract)
- [DSP Pipeline Architecture](dsp-pipeline-engineering.md) — How libvgm PCM output feeds into EQ, reverb, and other effects
- [User Guide: VGM Playback](user_guide.md#7-vgm-chiptune-playback) — Usage examples and command reference

---

## Future Enhancements

Potential areas for expansion:

1. **Loop detection & automatic loop point insertion** — Analyze VGM loop metadata and auto-apply to playlist
2. **Chip visualization** — Real-time waveform display for each emulated chip (e.g., FM operators as live graphs)
3. **Register dump export** — Write raw chip register sequences as hex; useful for debugging or porting
4. **Custom chip combinations** — Load a VGM with SN76489 + YM2612, but swap in a different FM emulator for comparison
5. **Linux ARM support** — Currently tested on x86_64; ARM builds may require additional libvgm configuration

---

## Credits

- **libvgm** — ValleyBell (https://github.com/ValleyBell/libvgm)
  - Chip emulators based on detailed hardware analysis and reverse engineering
  - Active maintenance and community contributions
- **VGM specification** — Wotsit / VGM preservation community
- **Cycle-accurate emulation references** — Digital preservation communities (SMS Power!, Data Crystal, etc.)

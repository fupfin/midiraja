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

Midiraja can convert MIDI (and MOD/XM/etc.) files to VGM format in memory, then play via libvgm. This lets you audition how a composition would sound on classic hardware, using either the `--system` shorthand or the explicit `--chips` flag.

### Chip Selection

#### `--system` presets

| Preset | Chip(s) | Notes |
|--------|---------|-------|
| `ay8910` | AY-3-8910 | Default; 3-voice PSG |
| `ym2413` | YM2413 (OPLL) | 9 FM voices or 6 FM + rhythm |
| `msx` | AY-3-8910 + YM2413 | Combined PSG + FM (MSX2 profile) |
| `msx-scc` | AY-3-8910 + SCC + YM2413 | Full MSX combo with wavetable SCC |
| `opl3` | YMF262 (OPL3) | 18 FM voices, 4-op support |

Passing an unrecognised value prints the valid choices.

#### `--chips` — explicit chip combinations

The `--chips` flag accepts one or more chip names with a separator that also determines the **routing mode**:

| Separator | Syntax example | Routing mode | Behaviour |
|-----------|----------------|--------------|-----------|
| `+` | `scc+ay8910` | CHANNEL | Round-robin assignment by MIDI channel |
| `,` | `ay8910,ym2413` | CHANNEL | Same as `+` |
| `>` | `scc>ay8910` | SEQUENTIAL | Fill first chip before spilling to next |

When `--chips` and `--system` are both absent the preset `ay8910` is used.

### PSG-Preferred Routing

When AY-3-8910 is present alongside other chips, **GM programs 112–127** (sound-effects / percussive timbres such as TinkBell, Gunshot, Bird Tweet) are always routed to the AY-3-8910 first, regardless of which chip the round-robin or fill-first algorithm would otherwise select. This reflects the historical convention that PSG chips handle noise-based sound effects better than FM synthesis.

### Voice Allocation

**CHANNEL mode** (`+` / `,`): each MIDI channel is permanently assigned to one chip handler in round-robin order. Channel 1 → handler 0, channel 2 → handler 1, etc., wrapping around.

**SEQUENTIAL mode** (`>`): voices are assigned to handler 0 until all of its polyphony slots are occupied, then overflow spills into handler 1, and so on. Good for maximising polyphony when two chips of the same type are combined.

### Supported Chips for MIDI → VGM Export

| Chip | Type | Voices | Notes |
|------|------|--------|-------|
| AY-3-8910 | PSG | 3 tone + noise | Handles noise/SFX programs by preference |
| YM2413 (OPLL) | FM | 9 melodic or 6 + rhythm | Built-in patch ROM; user patch via CC |
| SCC (Konami) | Wavetable | 5 | Percussion mapped to slot 4 via synthesised waveforms |
| YMF262 (OPL3) | FM | 18 (2-op) / 6 (4-op) | Full GM patch mapping via WOPL bank |
| YM2612 (OPN2) | FM | 6 | libOPNMIDI VGMFileDumper backend; `--system ym2612` or `genesis` |

### Unsupported Chips (Future Implementation)

The following chips are planned but not yet implemented. Each entry describes the intended approach.

#### SN76489 — Texas Instruments PSG (Sega Master System / Game Gear)

- 3 square-wave tone channels + 1 noise channel
- Simpler than AY-3-8910 (no envelope hardware, only 4-bit volume)
- Planned: `sn76489` preset; noise channel used for drums, tone channels for melody

#### YM2151 — FM synthesis (arcade / Sharp X68000)

- 8 FM channels, 4-operator OPM algorithm
- Used in many Capcom/Konami arcade boards and the X68000 home computer
- VGM command: `0x54`
- Planned: `ym2151` or `x68000` preset

#### OPN Family — YM2203 / YM2608 / YM2610 (PC-88, Neo Geo, etc.)

- YM2203 (OPN): 3 FM + AY-3-8910 SSG
- YM2608 (OPNA): 6 FM + SSG + ADPCM rhythm + 6-channel ADPCM; used in PC-98
- YM2610 (OPNB): 4 FM + SSG + ADPCM; used in Neo Geo
- Planned: `ym2203`, `opna`, `neogeo` presets respectively

#### YM3812 — OPL2 (AdLib / Sound Blaster)

- 9 FM channels, 2-operator; OPL2 subset of OPL3
- Already partially covered by the OPL3 exporter, but a dedicated OPL2 target
  would honour the 9-channel limit and exclude 4-op / stereo features
- Planned: `opl2` or `adlib` preset

#### HuC6280 — Hudson Soft PSG (PC Engine / TurboGrafx-16)

- 6 wavetable channels with 32-sample waveforms; similar to SCC but stereo panning per channel
- VGM command: `0xB9`
- Planned: `huc6280` or `pce` preset

#### Game Boy DMG — LR35902 APU

- 2 pulse channels, 1 wavetable channel, 1 noise channel
- VGM command: `0xB3` (DMG registers)
- Planned: `dmg` or `gameboy` preset; pulse channels for melody, noise for percussion

### Conversion Architecture

```
MusicFormatLoader.load()
├─ if MIDI/MOD/etc. (non-VGM input):
│  ├─ load as Sequence
│  ├─ resolve ChipSpec (--system or --chips)
│  ├─ CompositeVgmExporter → byte[] VGM data
│  └─ vgm_open_data(ctx, bytes, len)
└─ if .vgm/.vgz file:
   └─ vgm_open_file(ctx, path)
```

Each handler (`Ay8910Handler`, `Ym2413Handler`, `SccHandler`, `Opl3Handler`) translates MIDI note-on/note-off/CC events into the target chip's register writes, generating valid VGM data.

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
| Game Boy DMG | 2 pulse + 1 wave + 1 noise | Original Game Boy |
| NES APU | 2 pulse + 1 triangle + 1 noise + DMC | Nintendo Entertainment System |
| SNES SPC700 | 8-channel ADPCM sampler | Super NES |
| HuC6280 | 6-channel wavetable + stereo | PC Engine / TurboGrafx-16 |

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

# Music Format Engineering

This document surveys non-MIDI music file formats that Midiraja has evaluated — covering
formats that are supported, formats that are intentionally not supported (with the rationale),
and formats that are out of scope with notes on future extension paths.

For end-user format information, see the **[User Guide](user_guide.md)** instead.

---

## 1. Supported Format: Amiga ProTracker MOD

Midiraja supports `.mod` files (4–32 channels, M.K./FLT4/FLT8/xCHN tags) via the
`ModParser` → `ModToMidiConverter` pipeline in `com.fupfin.midiraja.format.mod`.

MOD is a reasonable fit for MIDI approximation because:

- **Simple structure**: channels map 1-to-1 onto MIDI channels; no instrument envelopes.
- **Descriptive sample names**: composers typically name samples after real instruments
  ("piano", "bass", "strings"), making GM program-number guessing viable.
- **No note-to-sample table**: each channel plays one sample; no per-note remapping needed.
- **Minimal effects**: only a small subset (arpeggio, portamento, vibrato, volume) that have
  reasonable MIDI equivalents.

The conversion produces a standard MIDI `Sequence` that any downstream synth can play.

---

## 2. Unsupported Formats: S3M, XM, IT

ScreamTracker 3 (`.s3m`), FastTracker 2 (`.xm`), and Impulse Tracker (`.it`) are
intentionally not supported. The core obstacle is that **the sound identity is the sample**:
there is no meaningful GM program number for an arbitrary PCM waveform embedded in the file.

| Feature | MOD | S3M / XM / IT |
|---------|-----|---------------|
| Instrument identity | Sample name hint | Arbitrary PCM waveform |
| Note-to-sample table | Fixed (1 sample/channel) | Per-instrument, per-note-range |
| Volume envelope | None | AHDSR per instrument |
| Pitch envelope | None | Present in XM/IT |
| Panning envelope | None | Present in XM/IT |
| NNA (New Note Action) | None | IT-specific polyphony control |
| Effects | ~10 basic | 30+ including NNA, retrigger, filter |

Mapping these features to MIDI/GM produces results that range from degraded to unrecognisable.
Accurate playback requires a dedicated module renderer such as **libopenmpt**, which is outside
the current scope of Midiraja's MIDI-centric architecture.

---

## 3. Why Not Use a SoundFont to Carry the Embedded Samples?

A natural question arises: if FluidSynth and TinySoundFont (TSF) can play arbitrary SF2
SoundFonts, could we extract the PCM samples from an XM/IT file, build a custom SF2, and use
that as the instrument bank?

The answer is: **yes, in principle**, and it would eliminate the GM-mapping problem.

### 3.1. What Would Work

SF2 supports many tracker-originated features:

| Tracker feature | SF2 equivalent |
|-----------------|---------------|
| Note-to-sample table | Key-range splits per instrument |
| Sample loop points | Loop start/end in SF2 sample chunk |
| Volume envelope (AHDSR) | SF2 generator `volAttack`, `volDecay`, etc. |
| Stereo samples | SF2 stereo sample pairs |
| Sample fine-tune | SF2 `fineTune` and `coarseTune` generators |
| Per-instrument volume | SF2 `initialAttenuation` |

The pipeline would be:

```
XM/IT file
  │
  ├─ extract PCM samples + instrument definitions
  │
  ├─ generate SF2 in memory (no temp file needed)
  │
  ├─ load into TSF via tsf_load_memory(byte[], len)
  │        (TSF already accepts raw byte arrays)
  │
  ├─ convert pattern events → MIDI events
  │       (Program Change = instrument index, not GM number)
  │
  └─ play via TsfSynthProvider
```

TSF is the better fit here because `FFMTsfNativeBridge.loadSoundfontFile()` already reads the
file into a `byte[]` and calls `tsf_load_memory` — a memory-only SF2 could be passed the same
way with a one-method addition (`loadSoundfontMemory(byte[])`).

FluidSynth requires a file path (`fluid_synth_sfload`), so it would need a temporary file.

### 3.2. What Would Still Be Approximate

| Feature | Gap |
|---------|-----|
| NNA (New Note Action) | No SF2/MIDI equivalent; cut/continue/fade must be guessed |
| Pitch/panning envelopes | SF2 has vibrato but not general pitch/pan LFOs |
| Effect column (arpeggio, retrigger, etc.) | Partially expressible via MIDI CC/pitch-bend |
| Pattern-level control (Bxx, Dxx jumps) | Must be unrolled into a flat event stream at parse time |

The result would be noticeably better than GM guessing, but not bit-identical to a tracker
renderer.

### 3.3. Why It Has Not Been Implemented Yet

Scope. Implementing a correct SF2 encoder for tracker instruments is a significant undertaking
on its own, before any pattern-event conversion work begins. MOD support serves the common case
well enough for now.

---

## 4. OS-Native MIDI Port Limitation

Routing tracker audio through an OS MIDI port (CoreMIDI, WinMM, ALSA) to an external hardware
synthesiser is **not viable**. MIDI is a performance-event protocol; it carries no PCM audio
data. External synthesisers use their own built-in ROM banks and cannot receive custom sample
uploads over a standard MIDI connection.

The MIDI Sample Dump Standard (SDS) exists in theory but is impractical:

- Almost no modern hardware supports it.
- Transfer rate is limited to 31,250 baud (MIDI serial speed).
- It transfers individual samples, not a full instrument bank.

Therefore, the dynamic SF2 approach described in §3 is limited to **software synths (TSF,
FluidSynth)** that run in-process.

---

## 5. SNES SPC Format

SNES `.spc` files are **hardware memory snapshots**, not a sequencer format. A SPC file is a
verbatim dump of the SNES sound subsystem's 64 KB RAM at a moment during gameplay, capturing
the game's sound driver code, BRR-encoded PCM samples, and sequencer state all in one binary.

### 5.1. SNES Sound Architecture

The SNES sound subsystem is a self-contained unit isolated from the main CPU:

```
SPC700 CPU  ─── executes the game's sound driver code (proprietary per game company)
     │
S-DSP       ─── generates audio: 8-channel BRR sample playback, ADSR envelopes,
                 hardware echo/reverb (FIR filter), Gaussian interpolation
```

The S-DSP operates on BRR (Bit Rate Reduction) samples stored in the shared 64 KB RAM.
It is driven entirely by register writes from the SPC700 CPU.

### 5.2. Comparison with Tracker Formats

Looking at S-DSP alone, the structure resembles a hardware tracker:

| | Tracker (MOD/XM) | S-DSP |
|--|--|--|
| Sample storage | PCM/BRR embedded in file | BRR embedded in 64 KB RAM |
| Channels | 4–32 | 8 fixed |
| Playback | Per-channel sample with pitch | Per-channel BRR with pitch |
| Envelopes | Volume envelope | ADSR per channel |
| Effects | Echo, tremolo, etc. | Hardware echo/reverb |

The fundamental difference is **who controls the channels**:

- **Tracker**: a well-defined format spec describes pattern data; parsing the spec is sufficient
  to reproduce playback.
- **SPC**: the SPC700 CPU executes a proprietary driver binary. Nintendo, Konami, and Capcom
  each wrote different drivers — there is no single spec to parse.

Playback therefore requires **full SPC700 CPU emulation**, not just sample scheduling.

### 5.3. Relationship to VGM

SPC→VGM conversion is conceptually identical to how VGM files are created for other chips:

```
SPC700 emulation running
  → capture S-DSP register writes with timestamps
  → write to VGM file (with BRR sample data blocks)
  → replay via libvgm's S-DSP emulator
```

This is the same "CPU tells the chip what to do; we record what it said" model that VGM uses
for all other chips. libvgm contains an S-DSP emulator internally, but **SNES S-DSP is not
part of the official VGM specification**. The SPC ecosystem (dedicated players, composition
tools) was already established before VGM expanded beyond Sega hardware, leaving little
motivation to formalise SNES support in the VGM spec.

### 5.4. Implementation Options for Midiraja

**Option A — SPC→VGM conversion then vgm engine**

Convert SPC to VGM offline (using an existing tool), then play via the existing `vgm` engine.
Risk: loop handling is fragile; the SPC700's dynamic loop logic becomes a static stream that
may not loop correctly.

**Option B — Direct snes_spc binding**

`snes_spc` is a single-header C library (like miniaudio and TinySoundFont) that emulates the
full SPC700 + S-DSP subsystem. It could be bundled and bound via FFM as a new `midra spc`
subcommand, following the same pattern as the other native-bridge engines.

Option B is cleaner: no offline conversion step, correct loop handling, and no VGM format
ambiguity. The resulting engine would be entirely independent of MIDI — the SPC file plays
directly without any MIDI conversion step.

### 5.5. Why SPC Is Currently Out of Scope

SPC playback requires SPC700 CPU emulation, making it a self-contained engine with no MIDI
involvement. While technically feasible as a bundled single-header binding, it falls outside
Midiraja's current MIDI-centric architecture. A future `midra spc` subcommand is a viable
path when there is demand for it.

---

## 6. Extension Checklist (Future Work)

### IT/XM/S3M via Dynamic SF2

If IT/XM/S3M support is added in the future, the recommended path is:

- [ ] Implement a minimal SF2 encoder (`TrackerSf2Builder`) that writes the RIFF/SF2 binary
      from a list of `(name, pcmBytes, sampleRate, loopStart, loopEnd)` tuples plus SF2
      instrument/preset chunks.
- [ ] Add `TsfNativeBridge.loadSoundfontMemory(byte[])` (one line: calls existing
      `tsf_load_memory`; no new native descriptor needed).
- [ ] Write `XmParser`, `S3mParser`, `ItParser` that produce a `TrackerParseResult`
      (pattern events + instrument list with embedded PCM).
- [ ] Write `TrackerToMidiConverter` that converts pattern events to MIDI, using instrument
      index as program number (not GM mapping).
- [ ] Wire up in `MusicFormatLoader.load()` and `isSupportedFile()`.
- [ ] Add `isSupportedFile_xm`, `isSupportedFile_s3m`, `isSupportedFile_it` tests in
      `MusicFormatLoaderTest` (currently `assertFalse`; flip to `assertTrue` when implemented).

### SNES SPC

- [ ] Bundle `snes_spc` as a single-header C library under `src/main/c/`.
- [ ] Add `SpcNativeBridge` / `FFMSpcNativeBridge` using the FFM Queue-and-Drain pattern.
- [ ] Add `midra spc` subcommand; register FFM descriptors in `reachability-metadata.json`.
- [ ] Add `isSupportedFile_spc` test in `MusicFormatLoaderTest` (currently no entry; SPC
      bypasses MusicFormatLoader entirely since it does not convert to a MIDI Sequence).

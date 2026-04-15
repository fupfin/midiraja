# Tracker Format Engineering

This document explains Midiraja's approach to Amiga ProTracker MOD files, why S3M/XM/IT
formats are intentionally not supported, and what a future extension path would look like.

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

## 5. Extension Checklist (Future Work)

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

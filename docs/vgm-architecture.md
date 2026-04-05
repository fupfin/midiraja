# VGM → MIDI Conversion Architecture

## Overview

Converts VGM (Video Game Music) files to `javax.sound.midi.Sequence` for playback through
the existing MIDI pipeline. The conversion is injected in `PlaylistPlayer.play()` before the
`MidiUtils.loadSequence()` call: VGM files are detected, converted to a `Sequence`, and
handed off to the synth provider as if they were ordinary MIDI files.

Supported chips: **SN76489** (Sega PSG), **YM2612** (Sega FM), **YM2151** (Arcade OPM),
**YM2203** (PC-88 OPN), **YM2608** (PC-98 OPNA), **YM2610** (Neo Geo OPNB),
**AY-3-8910** (MSX PSG), **K051649** (Konami SCC wavetable), **YM2413** (MSX/SMS OPLL),
**YM3812** (DOS OPL2), **YMF262** (DOS OPL3), **HuC6280** (PC Engine), **Game Boy DMG**.

---

## Conversion Strategy

Cross-chip principles that guide the VGM → MIDI conversion.

### Chip classification

| Category | Chips | Characteristics |
|----------|-------|-----------------|
| **PSG** (square/pulse) | SN76489, AY-3-8910, Game Boy CH1-2 | Fixed waveform (square/pulse), no timbre variation |
| **Wavetable** | SCC, HuC6280, Game Boy CH3 | Programmable waveform per channel |
| **4-op FM** | YM2612, YM2151, YM2203, YM2608, YM2610 | 4 operators, 8 algorithms, stable channel roles |
| **2-op FM** | YM3812, YMF262 | 2 operators, volatile channel roles (note-pool) |
| **Preset FM** | YM2413 | ROM preset instruments, direct GM mapping |

### GM instrument assignment strategy

Each chip category uses a different instrument assignment approach:

**PSG:** Fixed Square Lead (GM 80) at tick 0. AY-3-8910 envelope shapes with
single-decay (R13=0-3,9) simulate note decay via automatic NoteOff insertion,
preserving the Square Lead timbre while producing short plucked notes.

**Wavetable (SCC, HuC6280):** Dynamic program selection based on waveform shape
analysis (steep-edge counting: sine → Calliope Lead, sawtooth → Sawtooth Lead,
square → Square Lead, complex → Synth Brass). Program Change emitted per note
when waveform changes.

**4-op FM (stable channels):** Per-note program selection based on note pitch and
carrier envelope (percussive vs sustained). Converters emit their own Program Change.
Silent carriers (average TL ≥ 55) are suppressed.

**2-op FM (volatile channels):** Patch dictionary approach via `OplPatchCatalog`.
First pass catalogs all unique FM patches with their note ranges, second pass
selects GM instrument per patch based on 3 dimensions: FM timbre, pitch range,
and carrier envelope. See [OPL Patch Catalog](#opl-patch-catalog) below.

**Preset FM (YM2413):** Direct mapping from 15 ROM presets to GM programs
(Violin → 40, Piano → 0, Flute → 73, etc.). Rhythm mode routes 5 drums to ch 9.

### Instrument selection principles

1. **Pitch-range awareness:** The same FM patch may serve as bass, melody, or
   accompaniment depending on the notes it plays. Instrument assignment should
   consider the actual note range, not just FM parameters.

2. **Ensemble coherence:** Instruments must blend well when played simultaneously.
   Avoid mixing harsh timbres (Overdriven Guitar, Distortion Guitar) with
   delicate ones (Calliope Lead, Recorder) in the same song.

3. **Fast attack required:** VGM chip music has tight timing. Slow-attack GM
   instruments (Clarinet, Synth Strings, Pads) create audible lag and should
   be avoided.

4. **Silent patch detection:** Patches with near-silent output (carrier TL ≥ 55,
   zero-feedback FM, or OPL2 modTL < 10 percussion effects) must be suppressed
   or rerouted to drums to prevent ghost notes and dissonance.

5. **Octave correction:** OPL2/OPL3 FM synthesis produces strong 2nd harmonics
   that raise the perceived pitch one octave above the mathematical fundamental.
   Piano playback requires +12 semitone correction to match the original perceived
   pitch. This does not apply to 4-op FM or PSG chips.

6. **Envelope simulation:** Hardware envelopes (AY-3-8910 single-decay, OPL2
   note-cut trick via silent patch) are simulated via MIDI note duration rather
   than instrument changes, preserving the original timbre character.

---

## Classes

### `VgmParser` — `com.fupfin.midiraja.vgm`

Reads a VGM file and produces a chip-event stream.

**Responsibilities:**
- Parse VGM header (magic `Vgm `, version, clock speeds, GD3 offset, loop offset)
- Walk command bytes from the data offset, accumulating wait samples
- Parse PCM data blocks (`0x67`) and count DAC write timing (`0x80`-`0x8F`)
- Play intro once then loop once on `0x66` if a loop offset is present in the header
- Return `VgmParseResult` containing a flat `List<VgmEvent>`

```java
record VgmEvent(long sampleOffset, int chip, byte[] rawData) {}

class VgmParser {
    VgmParseResult parse(File file) throws IOException;
}

record VgmParseResult(
    int vgmVersion,
    long sn76489Clock,   // Hz; 0 = chip absent (offset 0x0C)
    long ym2612Clock,    // Hz; 0 = chip absent (offset 0x2C)
    long ym2151Clock,    // Hz; 0 = chip absent (offset 0x30)
    long ym2203Clock,    // Hz; 0 = chip absent (offset 0x44)
    long ym2608Clock,    // Hz; 0 = chip absent (offset 0x48)
    long ym2610Clock,    // Hz; 0 = chip absent (offset 0x4C)
    long ay8910Clock,    // Hz; 0 = chip absent (offset 0x74)
    long sccClock,       // Hz; falls back to ay8910Clock×2 if K051649 header field is 0
    long ym2413Clock,    // Hz; 0 = chip absent (offset 0x10)
    long gameBoyDmgClock,// Hz; 0 = chip absent (offset 0x80)
    long huC6280Clock,   // Hz; 0 = chip absent (offset 0xA4)
    long ym3812Clock,    // Hz; 0 = chip absent (offset 0x50)
    long ymf262Clock,    // Hz; 0 = chip absent (offset 0x5C)
    List<VgmEvent> events,
    @Nullable String gd3Title
) {}
```

**Chip ID assignments** (stored in `VgmEvent.chip`):

| chip | Source command | Chip |
|------|----------------|------|
| 0 | `0x50` | SN76489 |
| 1 | `0x52` | YM2612 port 0 |
| 2 | `0x53` | YM2612 port 1 |
| 3 | `0xA0` | AY-3-8910 |
| 4 | `0xD2` | K051649 (SCC) |
| 5 | `0x54` | YM2151 (OPM) |
| 6 | `0x55` | YM2203 (OPN) |
| 7 | `0x56` | YM2608 (OPNA) port 0 |
| 8 | `0x57` | YM2608 (OPNA) port 1 |
| 9 | `0x58` | YM2610 (OPNB) port 0 |
| 10 | `0x59` | YM2610 (OPNB) port 1 |
| 11 | `0xB3` | Game Boy DMG |
| 12 | `0xB9` | HuC6280 |
| 13 | `0x51` | YM2413 (OPLL) |
| 14 | `0x5A` | YM3812 (OPL2) |
| 15 | `0x5E` | YMF262 (OPL3) port 0 |
| 16 | `0x5F` | YMF262 (OPL3) port 1 |

---

### `VgmToMidiConverter` — `com.fupfin.midiraja.vgm`

Top-level orchestrator that converts `VgmParseResult` to `javax.sound.midi.Sequence`.

**Responsibilities:**
- Set PPQ=480 and tempo=500000 µs (120 BPM) → 960 ticks/sec
- Emit Program Change at tick 0 for each MIDI channel
- Route events to the appropriate chip converter
- Insert GD3 title as MetaMessage (type 0x03) in track 0
- `buildTitle()` prepends chip names to GD3 title, e.g. `[YMF262] Introduction`

```java
class VgmToMidiConverter {
    Sequence convert(VgmParseResult parsed);
}
```

---

### `Sn76489MidiConverter` — `com.fupfin.midiraja.vgm`

Converts SN76489 PSG chip events to MIDI note events.

**Responsibilities:**
- Decode latch/data bytes; maintain per-channel tone/volume register state
- Convert tone frequency to MIDI note number
- Map volume (0-15) to CC7 (channel volume); NoteOn velocity is always 127
- Emit NoteOn/NoteOff on MIDI channels 0-2 (tone) and 9 (noise/GM percussion)

---

### `FmMidiUtil` — `com.fupfin.midiraja.vgm`

Shared utilities for all FM chip converters (YM2612, YM2151, OPN family).

**Provides:**
- `selectProgram(note, percussive)` — GM program selection based on pitch range (< C3 ->
  bass instruments, >= C3 -> melody instruments). The old `(alg, fb, modTl)` parameters are
  deprecated.
- `carrierOps(alg)` / `modulatorOps(alg)` — operator role lookup per algorithm
- `computeVelocity(tl, algorithm, feedback, ch)` — dB-based velocity from carrier TL + feedback
- `lrMaskToPan(lrMask)` — LR mask → MIDI CC10
- `addEvent(track, command, ch, d1, d2, tick)` — MIDI event helper

---

### `Ym2612MidiConverter` — `com.fupfin.midiraja.vgm`

Converts YM2612 FM chip events to MIDI note events. Also handles the OPN family
(YM2203/YM2608/YM2610) via configurable FM divider and port 1 chip ID.

**Constructor:** `Ym2612MidiConverter(fmDivider, port1ChipId)`
- `fmDivider`: 144 for YM2612/YM2608/YM2610, 72 for YM2203
- `port1ChipId`: chip ID indicating port 1 events; -1 for single-port chips

**Responsibilities:**
- Decode port 0/1 register writes (0x40-0x4F TL, 0xA0-0xA6, 0x28, 0xB0, 0xB4)
- Convert F-Number + Block to frequency using `f = FNum × clock / (divider × 2^(21-block))`
- Map key-on (0x28) to NoteOn with TL-based velocity, key-off to NoteOff
- Read carrier TL (0x40-0x4F) and compute MIDI velocity via `FmMidiUtil.computeVelocity()`
- Derive GM program from note pitch via `FmMidiUtil.selectProgram(note, percussive)`
- Decode stereo pan register (0xB4-0xB6 bits 7-6: L/R) → CC10 before NoteOn
- Use MIDI channels 3-8

---

### `Ym2151MidiConverter` — `com.fupfin.midiraja.vgm`

Converts YM2151 (OPM) FM chip events to MIDI note events.

**Constructor:** `Ym2151MidiConverter(midiChOffset)`
- `midiChOffset`: 0 for standalone arcade, 3 when sharing with YM2612

**Responsibilities:**
- 8 FM channels on a single port (VGM command 0x54)
- Decode KC/KF frequency registers (0x28-0x2F, 0x30-0x37) → MIDI note via
  `note = octave × 12 + KC_SEMITONE[noteCode] + 13`
- Read channel control register 0x20+ch (RL/FB/CONNECT = LR + feedback + algorithm)
- Read TL registers (0x60-0x7F, stride 8 per operator) for velocity computation
- Key on/off via register 0x08 (bits 2-0=channel, bits 6-3=operator mask)
- Share program selection, velocity, and pan logic with YM2612 via `FmMidiUtil`

---

### `Ym3812MidiConverter` — `com.fupfin.midiraja.vgm`

Converts YM3812 (OPL2) and YMF262 (OPL3) FM chip events to MIDI note events.
9-channel 2-operator FM. Handles OPL2 (0x5A) and OPL3 (0x5E/0x5F, via dual instances
with `midiChOffset`). Configurable clock and channel offset. +12 octave correction.

**Key behaviors:**
- Silent patch detection: `conn=0, fb=0` or carrier TL >= 55 suppresses output
- Percussive effect routing: `modTL < 10` routes to ch9 drums
- Note-cut trick: `conn=0, fb=0` triggers instant noteOff on active notes

---

### `Ym2413MidiConverter` — `com.fupfin.midiraja.vgm`

Converts YM2413 (OPLL) chip events to MIDI note events. 9-channel OPLL with 15 ROM
preset instruments mapped directly to GM programs (Violin -> 40, Piano -> 0, Flute -> 73,
etc.).

**Key behaviors:**
- Rhythm mode (0x0E bit5) routes 5 percussion sounds to MIDI ch9
- OPLL ch 0-5 -> MIDI ch 3-8, ch 6-8 -> MIDI ch 10-12

---

### `HuC6280MidiConverter` — `com.fupfin.midiraja.vgm`

Converts HuC6280 (PC Engine) 6-channel wavetable chip events to MIDI note events.
Channel-select register architecture.

**Key behaviors:**
- Stereo L/R balance -> CC10 pan
- 32-sample waveform classification by steep-edge counting (threshold > 80):
  0 edges -> Calliope Lead, 1 -> Sawtooth Lead, 2 -> Square Lead, 3+ -> Synth Brass
- No hardware envelope

---

### `GameBoyDmgMidiConverter` — `com.fupfin.midiraja.vgm`

Converts Game Boy DMG chip events to MIDI note events.

**Key behaviors:**
- CH1-2 pulse -> MIDI ch 0-1 (Square Lead), CH3 wave -> MIDI ch 2 (Recorder),
  CH4 noise -> MIDI ch 9 (drums, hi-hat)
- Trigger-based note gating (bit 7 of NR14/NR24/NR34/NR44)
- NR51 L/R panning -> CC10

---

### `TrackRoleAssigner` — `com.fupfin.midiraja.vgm`

Post-conversion GM program assignment. Operates in two modes:

- **Volatile FM mode:** When patch-change-to-key-on ratio exceeds 50%, assigns Grand Piano
  (GM 0) to all melodic channels for consistent playback.
- **Stable FM mode (`assignUnassigned()`):** Only assigns GM programs to channels that do
  not already have a Program Change emitted by chip converters. Stable FM converters
  (YM2612, YM2151, OPN) emit their own Program Change per note; TrackRoleAssigner fills
  in unassigned channels only.

---

### `Ay8910MidiConverter` — `com.fupfin.midiraja.vgm`

Converts AY-3-8910 / YM2149F PSG chip events (common on MSX, ZX Spectrum, Amstrad CPC)
to MIDI note events.

**Responsibilities:**
- Track 12-bit tone period per channel (R0-R5, fine/coarse pairs)
- Track noise period R6 and mixer R7 (inverted enable bits)
- Track volume R8-R10; detect envelope mode (bit4) and map R13 shape to effective velocity
- Emit tone NoteOn/NoteOff on MIDI channels 0-2
- Emit noise NoteOn/NoteOff on MIDI channel 9 using hi-hat GM notes based on R6 period
- Retrigger note on any frequency register write (R0-R5) when the resulting MIDI note changes;
  MSX games often update only the fine byte (R0/R2/R4) for small pitch changes and glissandi

**Envelope handling:**
The AY-3-8910 has a hardware envelope generator (R11/R12: period, R13: shape) that
time-varies the channel amplitude. Since MIDI has no equivalent, the effective volume
is approximated from the envelope shape:

| R13 shapes | Character | Effective vol | CC7 (after gain) |
|------------|-----------|---------------|-----------------|
| 0-3, 8, 9, 10, 15 | Decay / single-shot transient | 7/15 | ≈ 50 |
| 11, 13 | Single phase then hold at max | 11/15 | ≈ 63 |
| 12, 14 | Continuous sawtooth / triangle | 8/15 | ≈ 54 |

The most common shape in MSX games is R13=9 (single fall then silence), used to produce
plucked-bass or percussive tones. Treating it as vol=15 (velocity 127) makes sustained
bass notes too loud; vol=7 (velocity 59) better approximates the time-averaged amplitude.

**Noise → GM drum mapping:**
AY-3-8910 noise is an LFSR producing electrical white noise — no drum body or snare crack
exists. All noise periods therefore map to hi-hat variants rather than snare:

| R6 period | Noise frequency | GM note |
|-----------|----------------|---------|
| 0-12 | ≥ ~9 kHz | 42 — Closed Hi-Hat |
| 13-31 | < ~9 kHz | 46 — Open Hi-Hat |

GM snare (38) is intentionally avoided: its low-frequency body component does not exist
in the original noise signal and would make percussion sound too heavy.

---

### `SccMidiConverter` — `com.fupfin.midiraja.vgm`

Converts Konami SCC (K051649) wavetable chip events to MIDI note events.

**Responsibilities:**
- Decode D2 port/address/data triples
- Port 0 (waveform data, 0x00-0x7F): captured for waveform classification (see below)
- Port 1 (frequency, addr 0x00-0x09): two bytes per channel (lo, hi nibble)
- Port 2 (volume, addr 0x00-0x04): 4-bit per channel
- Port 3 addr 0 (channel enable, bit4-0): **not used as MIDI gate** — see design decision below
- Emit NoteOn/NoteOff on MIDI channels 10-14
- Retrigger note on any frequency byte write (lo or hi) when the resulting MIDI note changes
- MIN_NOTE=28 (E1 ≈ 41 Hz): notes below this threshold are tracked internally but no MIDI
  events are emitted; prevents infrasonic init garbage from masking melody

**Volume-only note gate:**
Volume alone drives note state: `vol > 0` → NoteOn, `vol = 0` → NoteOff. The enable register
(port 3) is intentionally ignored as a gate. MSX games must mute channels (enable=0) before
writing waveform data and immediately re-enable — this produces rapid enable-cycling within
a single MIDI tick. Using enable as a gate would generate inaudible sub-millisecond
NoteOn/Off pairs that silence the SCC output entirely.

**Infrasonic filter:**
Many MSX games leave SCC frequency registers at init values (e.g. N=2027 → 27.5 Hz, MIDI
note 21) before the music starts. Emitting NoteOns at MIDI 21 with Sawtooth Lead produces
audible harmonic artifacts that mask the melody for the first 10-15 seconds. Notes below
MIN_NOTE=28 (≈41 Hz) are tracked in `activeNote[]` but no MIDI events are emitted, so a
subsequent pitch change to an audible note retriggers correctly.

**Waveform classification:**
Port 0 waveform data is now captured (32 samples, 8-bit signed). Steep-edge counting
(threshold > 80) classifies waveforms into GM programs:

| Steep edges | GM program |
|-------------|------------|
| 0 | 82 — Calliope Lead |
| 1 | 81 — Sawtooth Lead |
| 2 | 80 — Square Lead |
| 3+ | 62 — Synth Brass |

---

### `VgmCommand` — `com.fupfin.midiraja.cli`

picocli subcommand; follows the same pattern as `OplCommand` and `FluidCommand`.

**Responsibilities:**
- Accept file/directory/M3U via positional argument
- Use the same MIDI output provider as `JavaSynthCommand`
- Delegate to `PlaybackRunner.run()`

```java
@Command(name = "vgm", mixinStandardHelpOptions = true,
         description = "VGM chiptune playback (SN76489 / YM2612 → MIDI conversion).")
class VgmCommand implements Callable<Integer> { ... }
```

**Options (defined in `CommonOptions`, available to all subcommands):**
- `--mute <CHANNELS>` — comma-separated 1-based MIDI channel numbers or ranges to silence
  (e.g. `--mute 4-9` for YM2612 only, `--mute 1-3,10` for PSG only). Channel numbers match
  the UI display (CH01–CH15). Muted channels route events to a sink track that is discarded.
- `--export-midi <FILE>` — write the converted MIDI to a file without playing.

---

## MIDI Channel Layout

| MIDI ch | Source | GM Program |
|---------|--------|------------|
| 0 | SN76489 Tone 0 / AY8910 ch A / YM2151 ch 0* | 80 (Square Lead) / dynamic |
| 1 | SN76489 Tone 1 / AY8910 ch B / YM2151 ch 1* | 80 (Square Lead) / dynamic |
| 2 | SN76489 Tone 2 / AY8910 ch C / YM2151 ch 2* | 80 (Square Lead) / dynamic |
| 3 | YM2612/OPN FM ch1 / YM2151 ch 3* | dynamic (via FmMidiUtil) |
| 4 | YM2612/OPN FM ch2 / YM2151 ch 4* | dynamic |
| 5 | YM2612/OPN FM ch3 / YM2151 ch 5* | dynamic |
| 6 | YM2612/OPN FM ch4 / YM2151 ch 6* | dynamic |
| 7 | YM2612/OPN FM ch5 / YM2151 ch 7* | dynamic |
| 8 | YM2612/OPN FM ch6 | dynamic |
| 9 | SN76489 / AY8910 / OPN SSG Noise (GM percussion) | 0 (Drums) |
| 10 | SCC ch0 | 18 (Rock Organ) |
| 11 | SCC ch1 | 18 (Rock Organ) |
| 12 | SCC ch2 | 18 (Rock Organ) |
| 13 | SCC ch3 | 18 (Rock Organ) |
| 14 | SCC ch4 | 18 (Rock Organ) |
| 3-8 | YM2413 melody ch 0-5 | dynamic (ROM preset -> GM) |
| 10-12 | YM2413 melody ch 6-8 | dynamic (ROM preset -> GM) |
| 9 | YM2413 rhythm drums | 0 (Drums) |
| 0-8 | YM3812/YMF262 port 0 ch 0-8 | dynamic / Grand Piano |
| 10+ | YMF262 (OPL3) port 1 | dynamic / Grand Piano |
| 0-5 | HuC6280 ch 0-5 | dynamic (waveform classification) |
| 0-1 | Game Boy DMG pulse CH1-2 | 80 (Square Lead) |
| 2 | Game Boy DMG wave CH3 | 74 (Recorder) |
| 9 | Game Boy DMG noise CH4 | 0 (Drums) |

*YM2151 uses ch 0-7 when standalone (no YM2612), otherwise ch 3-8 (shared offset).

Channel 9 is the GM drum channel. TSF requires Program Change 0 on channel 9 to activate
drum mode (`isDrums=1`); without it the channel is treated as melodic (piano).

SN76489, AY-3-8910, and OPN SSG share MIDI channels 0-2 and 9 — a VGM file contains at
most one PSG-type chip.

**OPN family SSG routing:** YM2203/YM2608/YM2610 port 0 events with addr ≤ 0x0D are routed
to `Ay8910MidiConverter`. SSG clock derivation: YM2203 = chipClock/2, YM2608/YM2610 = chipClock/4.

YM2612 ch6 DAC (PCM streaming) is currently ignored — its timing is counted but no MIDI
events are emitted.

---

## Timing Conversion

VGM is clocked at a fixed 44100 samples/sec.

```
PPQ              = 480
tempo            = 500000 µs/beat  (= 120 BPM)
TICKS_PER_SECOND = PPQ × (1,000,000 / tempo) = 480 × 2 = 960
tick             = round(sampleOffset × 960 / 44100)
```

Ratio: 960 / 44100 ≈ 1/46. One NTSC frame (735 samples) = 16 ticks.

**Rationale:** PPQ=4410 would give a 1:1 sample→tick mapping, but PPQ=480 at 120 BPM is
the standard MIDI timebase. Sub-frame intra-frame arpeggios (< 735 samples) collapse into
the same tick, eliminating the "note flood" artefact caused by rapid register writes.

### Sequence creation pattern

```java
var seq = new Sequence(Sequence.PPQ, 480);
var tempoTrack = seq.createTrack();
byte[] tempoBytes = { 0x07, (byte) 0xA1, 0x20 }; // 500000 µs = 120 BPM
tempoTrack.add(new MidiEvent(new MetaMessage(0x51, tempoBytes, 3), 0));
```

---

## Frequency → MIDI Note Conversion

Common formula:

```
note = round(12 × log2(f / 440.0) + 69)
note = clamp(note, 0, 127)
```

### SN76489 frequency

```
f = clock / (32 × N)
```
- `clock`: SN76489 clock (typically 3579545 Hz — NTSC)
- `N`: 10-bit tone register value (0 = DC bias, ignored)

```java
static int sn76489Note(long clock, int N) {
    if (N <= 0) return -1;
    double f = clock / (32.0 * N);
    return clampNote((int) Math.round(12 * Math.log(f / 440.0) / Math.log(2) + 69));
}
```

### YM2151 (OPM) frequency

The YM2151 encodes pitch directly as a key code rather than a frequency divider:

```
KC register 0x28+ch:
  bit 6-4: octave (0-7)
  bit 3-0: note code (non-linear: 0,1,2,4,5,6,8,9,10,12,13,14 valid)

MIDI note = octave × 12 + KC_SEMITONE[noteCode] + 13
```

`KC_SEMITONE` maps note codes to semitone offsets from C#:
`{0→0, 1→1, 2→2, 4→3, 5→4, 6→5, 8→6, 9→7, A→8, C→9, D→10, E→11}`.
Codes 3, 7, 11, 15 are invalid and suppress the note.

Example: octave=4, noteCode=0xE (C of next octave) → `4×12 + 11 + 13 = 72` (C5).

### OPN family frequency

YM2203/YM2608/YM2610 use the same F-Number/Block formula as YM2612 with different dividers:

```
f = FNum × clock / (divider × 2^(21 - block))
```

| Chip | FM Divider | SSG Clock |
|------|------------|-----------|
| YM2612 | 144 | — (no SSG) |
| YM2203 | 72 | chipClock / 2 |
| YM2608 | 144 | chipClock / 4 |
| YM2610 | 144 | chipClock / 4 |

### YM2612 frequency

```
f = FNum × clock / (144 × 2^(21 - block))
```
- `FNum`: 11-bit F-Number
- `block`: 3-bit octave block (0-7)
- `clock`: YM2612 clock (typically 7670453 Hz — Sega Genesis)

```java
static int opnNote(long clock, int fnum, int block, int divider) {
    double f = fnum * clock / ((double) divider * (1L << (21 - block)));
    return clampNote((int) Math.round(12 * Math.log(f / 440.0) / Math.log(2) + 69));
}
```

### AY-3-8910 frequency

```
f = clock / (16 × N)
```
- `clock`: AY-3-8910 clock (1789772 Hz on MSX)
- `N`: 12-bit tone period (0 = silence, ignored)

```java
static int ay8910Note(long clock, int period) {
    if (period <= 0) return -1;
    double f = clock / (16.0 * period);
    return clamp((int) Math.round(12 * Math.log(f / 440.0) / Math.log(2) + 69), 0, 127);
}
```

### SCC (K051649) frequency

```
f = clock / (32 × (N + 1))
```
- `clock`: K051649 clock (1789772 Hz on MSX — same oscillator as AY-3-8910)
- `N`: 12-bit frequency divider (0 = highest pitch)

The `+1` in the denominator distinguishes SCC from AY-3-8910. Verified: N=126 → 440.4 Hz (A4).

```java
static int sccNote(long clock, int fnum) {
    if (fnum <= 0) return -1;
    double f = clock / (32.0 * (fnum + 1));
    return clamp((int) Math.round(12 * Math.log(f / 440.0) / Math.log(2) + 69), 0, 127);
}
```

**Clock fallback:** The K051649 clock field in the VGM header (offset 0xAC) is 0 in most
MSX VGMs. When it is 0, `sccClock` falls back to `ay8910Clock` (same 1789772 Hz crystal).

### HuC6280 frequency

```
f = clock / (32 × period)
```
- `period`: 12-bit value
- `clock`: typically 3,579,545 Hz

### Game Boy DMG frequency

```
f = clock / (32 × (2048 - freq))
```
- `freq`: 11-bit value
- `clock`: typically 4,194,304 Hz

### YM2413 (OPLL) frequency

```
f = fnum × 2^block × clock / 73728
```
- `fnum`: 9-bit F-Number
- `block`: 3-bit octave
- `clock`: 3,579,545 Hz

### YM3812 (OPL2) / YMF262 (OPL3) frequency

```
f_fundamental = FNum × clock / (72 × 2^(20 - block))
MIDI note = round(12 × log2(f / 440) + 69) + 12
```
- `FNum`: 10-bit F-Number (0xA0 low 8 + 0xB0 bits 1-0)
- `block`: 3-bit octave (0xB0 bits 4-2)
- `clock`: YM3812 = 3,579,545 Hz; YMF262 = header clock / 4

**+12 octave correction:** OPL2 FM synthesis produces strong 2nd harmonics that raise
the perceived pitch above the mathematical fundamental. Grand Piano (used for volatile-FM
VGMs) lacks these harmonics, so playing at the fundamental sounds one octave too low.
WAV comparison of multiple OPL2 VGMs confirmed the consistent 1-octave gap. The +12
correction aligns MIDI output with the original chip's perceived pitch.

**OPL3 clock:** YMF262 master clock (typically 14,318,180 Hz) is divided by 4 for the
OPL2-compatible frequency formula. The phase accumulator runs at clock/72, same as OPL2.

---

## YM3812 / YMF262 Patch Filtering

OPL2/OPL3 game drivers reuse channels as a **note pool**, dynamically changing FM patch
parameters (connection, feedback, TL) on nearly every key-on (70-80% patch change rate).
Three categories of patches require special handling:

### Silent patches (suppressed)

| Condition | Reason |
|-----------|--------|
| `conn=0 AND fb=0` | Zero-feedback FM = near-silent init/reset state |
| `carrier TL ≥ 55` | Output attenuated by > 41 dB, inaudible on real hardware |

Silent patches produce no MIDI output. When `conn=0, fb=0` is written while a note is
active, the note is immediately cut off (NoteOff). Game drivers use this as an instant
note-cut trick to avoid the slow release phase of key-off.

### Percussive effect patches (routed to drums)

| Condition | Reason |
|-----------|--------|
| `modulator TL < 10` (and not silent) | Extreme modulation = metallic/percussive timbre |

These are routed to MIDI channel 9 as GM drum note 42 (Closed Hi-Hat) instead of being
played as melodic notes. On the original chip, strong modulation produces cymbal-like
or noise-like tones that serve as sound effects. Closed Hi-Hat was chosen for its
unobtrusive metallic character matching the original OPL2 timbre.

### Adaptive GM program assignment

`TrackRoleAssigner.isVolatileFm()` scans VGM events and measures the patch-change-to-
key-on ratio across all FM chips. When the ratio exceeds 50% (volatile), the OPL patch
catalog (`OplPatchCatalog`) is built and used for per-patch instrument assignment.
When ≤ 50% (stable channel roles), 4-op FM converters handle their own Program Change.
`TrackRoleAssigner.assignUnassigned()` fills in remaining channels (PSG, wavetable)
that have no Program Change.

---

## OPL Patch Catalog

`OplPatchCatalog` scans all OPL2/OPL3 events in a first pass, cataloging unique FM
patches by signature and assigning GM instruments based on 3 dimensions:
**timbre × pitch range × carrier envelope**.

### Patch signature

Each patch is identified by a quantized signature: `(connection, feedback,
modulatorTL band, carrierTL band, carrierAR, carrierDR)`. ModulatorTL and carrierTL
are quantized into 4 bands (0-9, 10-29, 30-49, 50+) to avoid excessive unique patches.

### Timbre classification

| Timbre | Condition | Character |
|--------|-----------|-----------|
| FM-soft | conn=0, fb < 5, modTL ≥ 30 | Warm, pure |
| FM-bright | conn=0, fb < 5, modTL < 30 | Bright, metallic |
| FM-harsh | conn=0, fb ≥ 5 | Aggressive, distorted |
| AM | conn=1 | Organ-like, bell-like |

### GM instrument mapping (timbre × range × envelope)

| | FM-soft sust. | FM-soft perc. | FM-bright sust. | FM-bright perc. | FM-harsh sust. | FM-harsh perc. | AM sust. | AM perc. |
|--|---|---|---|---|---|---|---|---|
| Bass (< C3) | Ac.Bass (32) | Slap Bass (36) | Elec.Bass (33) | Slap Bass (36) | Elec.Bass (33) | Slap Bass (36) | Ac.Bass (32) | Slap Bass (36) |
| Mid (C3-B4) | EP2 (5) | Marimba (12) | EP1 (4) | Vibraphone (11) | Clavinet (7) | Clavinet (7) | Rock Organ (18) | Rock Organ (18) |
| High (≥ C5) | EP2 (5) | Xylophone (13) | EP1 (4) | Xylophone (13) | Clavinet (7) | Xylophone (13) | Rock Organ (18) | Rock Organ (18) |

**AM percussive** uses Rock Organ (same as sustained) because AM's organ-like timbre
should be preserved; VGM key-off timing provides the short note duration naturally.

### Special patches

| Condition | Treatment |
|-----------|-----------|
| conn=0, fb=0 (zero-feedback FM) | Suppress — also triggers instant NoteOff (note-cut trick) |
| Carrier TL ≥ 55 (−41 dB) | Suppress — inaudible on real hardware |
| Modulator TL < 10 (extreme modulation) | Route to ch 9 as Closed Hi-Hat (42) |

---

## VGM Header Version Guards

Chip clock fields are only valid in specific VGM versions. Reading offsets beyond the
version's defined header causes garbage values (e.g., a v1.51 file reading 1.5 GHz at
the GB DMG offset 0x80).

| Version | Valid clock fields |
|---------|-------------------|
| v1.00+ | SN76489 (0x0C), YM2413 (0x10) |
| v1.10+ | YM2612 (0x2C), YM2151 (0x30) |
| v1.51+ | YM2203-YMF262 (0x44-0x5C), AY8910 (0x74) |
| v1.61+ | GB DMG (0x80), HuC6280 (0xA4), K051649 (0xAC) |

---

## SN76489 Register Structure

### Latch byte (bit 7 = 1)

```
bit 7:   1 (latch flag)
bit 6-5: channel (0-2 = tone ch1-3, 3 = noise)
bit 4:   type (0 = tone/noise register, 1 = volume register)
bit 3-0: data (low 4 bits of tone, noise control, or volume)
```

### Data byte (bit 7 = 0)

```
bit 7:   0 (data flag)
bit 5-0: high 6 bits of tone register
```

Tone register is 10 bits: `{data[5:0], latch[3:0]}`.

### Volume → CC7 mapping

SN76489 volume is 0 (loudest) to 15 (silent):

```java
int cc7 = (int) Math.round((15 - volume) / 15.0 * 127);
cc7 = (int) Math.round(cc7 * PSG_CC7_GAIN);   // PSG_CC7_GAIN = 0.708 ≈ −3 dB
```

Volume 15 (silent) → NoteOff. All other values → CC7 update.
NoteOn always sends CC7 immediately before the NoteOn message so the channel volume is set
correctly regardless of any CC7 value left over from a previous note.

`PSG_CC7_GAIN = 0.708` (≈ −3 dB) is a psychoacoustic correction: Square Lead (prog 80) sits
at a ~2.4 kHz spectral centroid; YM2612 FM sits at ~1.2 kHz. Equal-loudness contours make
the high-frequency PSG content perceptually louder at equal RMS. RMS analysis across 75
Genesis tracks matches the hardware ratio (FM ~9.8 dB louder), but PSG melody channels cut
through perceptually. The −3 dB correction compensates without changing the hardware-accurate
FM/PSG energy ratio.

---

## AY-3-8910 Register Map

| Register | Bits | Description |
|----------|------|-------------|
| R0 | 7-0 | Ch A tone period fine (low 8 bits) |
| R1 | 3-0 | Ch A tone period coarse (high 4 bits) |
| R2 | 7-0 | Ch B tone period fine |
| R3 | 3-0 | Ch B tone period coarse |
| R4 | 7-0 | Ch C tone period fine |
| R5 | 3-0 | Ch C tone period coarse |
| R6 | 4-0 | Noise period (5-bit divider) |
| R7 | 5-0 | Mixer (inverted): bit2-0 = tone disable A/B/C, bit5-3 = noise disable A/B/C |
| R8 | 4-0 | Ch A volume; bit4=1 → envelope mode |
| R9 | 4-0 | Ch B volume; bit4=1 → envelope mode |
| R10 | 4-0 | Ch C volume; bit4=1 → envelope mode |
| R11 | 7-0 | Envelope period fine |
| R12 | 7-0 | Envelope period coarse |
| R13 | 3-0 | Envelope shape (see envelope handling section) |

**Mixer R7 logic:** bits are *inverted* — 0 = enabled, 1 = disabled. A channel can have
tone and noise enabled simultaneously (their outputs are mixed on the real chip).

---

## SCC (K051649) Register Map

All writes arrive via VGM command `0xD2` with three bytes: `port`, `addr`, `data`.

| Port | Address range | Description |
|------|--------------|-------------|
| 0 | 0x00-0x7F | Waveform data (32-byte banks per channel) — ignored for MIDI |
| 1 | 0x00-0x09 | Frequency registers: addr `2*ch` = lo byte, `2*ch+1` = hi nibble (bits 3-0) |
| 2 | 0x00-0x04 | Volume: one byte per channel (bits 3-0, 0=silent, 15=loudest) |
| 3 | 0x00 | Channel enable: bit `ch` = 1 → enabled (5 channels, bits 4-0) |

Frequency divider N = `(hi << 8) | lo`; pitch formula: `f = clock / (32 × (N + 1))`.

---

## YM2612 Stereo Pan Registers (0xB4-0xB6)

Register 0xB4 (ch0), 0xB5 (ch1), 0xB6 (ch2) in port 0; same addresses in port 1 for ch3-5:

```
bit 7:   L enable (1 = left channel active)
bit 6:   R enable (1 = right channel active)
bit 5-4: AMS — amplitude modulation sensitivity (ignored for MIDI)
bit 2-0: FMS — frequency modulation sensitivity (ignored for MIDI)
```

Mapped to MIDI CC10 (pan):

| L | R | lrMask | CC10 |
|---|---|--------|------|
| 1 | 1 | 3 | 64 (center) |
| 1 | 0 | 2 | 0 (hard left) |
| 0 | 1 | 1 | 127 (hard right) |
| 0 | 0 | 0 | 64 (center — channel is silent anyway) |

CC10 is emitted immediately before NoteOn (like Program Change), and also mid-note if the
register changes while a note is active. The default `lrMask=3` (center) means CC10=64 is
always sent on the first NoteOn even if 0xB4 was never written.

---

## YM2612 Key-on / F-Number Registers

### Key-on register (0x28)

```
Address: 0x28
Data:
  bit 7-4: operator key-on flags (OP1-OP4)
  bit 3:   reserved
  bit 2-0: channel select (0-2 = ch1-3, 4-6 = ch4-6, port 0/1)
```

- `data & 0xF0 != 0` → NoteOn
- `data & 0xF0 == 0` → NoteOff

### F-Number registers (0xA0-0xA6)

```
0xA4+ch (port0), 0xAC+ch (port1): high byte
  bit 2-0: Block (3 bits)
  bit 5-3: FNum high (3 bits)
0xA0+ch (port0), 0xA8+ch (port1): low byte
  bit 7-0: FNum low (8 bits)
```

Full FNum = `{high[2:0], low[7:0]}` = 11 bits.
Write order: high register (0xA4) must be written before low (0xA0).

---

## VGM Loop Handling

Header offset 0x1C (4-byte LE): loop offset relative to 0x1C. Zero means no loop.
On reaching `0x66` (end), if a loop offset is present and the loop has not been taken yet,
the parser jumps to that position and plays through once more before terminating.

---

## YM2612 DAC (PCM channel)

YM2612 ch6 can be switched to DAC mode for raw PCM streaming:
- PCM data is stored in a `0x67` data block (type=0x00, 8-bit unsigned, ~8567 Hz on Sonic 3)
- `0xE0` seek commands reposition the current read pointer within the block
- `0x80`-`0x8F` write one PCM byte to the DAC and wait `cmd & 0x0F` additional samples

**Current implementation:** DAC events are used only for timing accumulation; no MIDI events
are emitted. The wait samples from `0x80`-`0x8F` are critical for correct playback speed —
omitting them causes the song to play at roughly 2× speed (e.g. Sonic 3 title screen).

---

## PlaylistPlayer Integration

### Before

```java
var sequence = MidiUtils.loadSequence(file);
```

### After

```java
var sequence = VgmFileDetector.isVgmFile(file)
    ? new VgmToMidiConverter().convert(new VgmParser().parse(file))
    : MidiUtils.loadSequence(file);
```

`VgmFileDetector.isVgmFile()` checks the file extension (`.vgm`, `.vgz`) or magic bytes
(`Vgm ` = 0x56 0x67 0x6D 0x20).

---

## VGM Command Reference

| Command | Extra bytes | Description |
|---------|-------------|-------------|
| `0x50` | 1 (data) | SN76489 register write |
| `0x52` | 2 (addr, data) | YM2612 port 0 register write |
| `0x53` | 2 (addr, data) | YM2612 port 1 register write |
| `0x61` | 2 (lo, hi) | Wait N samples; N = `lo \| hi<<8` |
| `0x62` | — | Wait 735 samples (NTSC 1/60 s) |
| `0x63` | — | Wait 882 samples (PAL 1/50 s) |
| `0x66` | — | End of stream. Loops once if header 0x1C is non-zero. |
| `0x67` | variable (type, size, data) | PCM data block. type=0x00 = 8-bit unsigned for YM2612 DAC. |
| `0x70`-`0x7F` | — | Wait `(cmd & 0x0F) + 1` samples |
| `0x80`-`0x8F` | — | Write one PCM byte to YM2612 DAC + wait `cmd & 0x0F` samples |
| `0x54` | 2 (addr, data) | YM2151 (OPM) register write |
| `0x55` | 2 (addr, data) | YM2203 (OPN) register write — addr ≤ 0x0D is SSG, else FM |
| `0x56` | 2 (addr, data) | YM2608 (OPNA) port 0 — addr ≤ 0x0D is SSG, else FM |
| `0x57` | 2 (addr, data) | YM2608 (OPNA) port 1 — FM only |
| `0x58` | 2 (addr, data) | YM2610 (OPNB) port 0 — addr ≤ 0x0D is SSG, else FM |
| `0x59` | 2 (addr, data) | YM2610 (OPNB) port 1 — FM only |
| `0xA0` | 2 (reg, data) | AY-3-8910 register write; reg bits 3-0 = register number |
| `0xB3` | 2 (addr, data) | Game Boy DMG register write |
| `0xB9` | 2 (addr, data) | HuC6280 register write |
| `0xD2` | 3 (port, addr, data) | K051649 (SCC) register write |
| `0xE0` | 4 (offset LE) | Seek PCM data bank to byte offset. Marks sample boundaries. |
| `0x51` | 2 (reg, data) | YM2413 (OPLL) register write |
| `0x5A` | 2 (addr, data) | YM3812 (OPL2) register write |
| `0x5E` | 2 (addr, data) | YMF262 (OPL3) port 0 register write |
| `0x5F` | 2 (addr, data) | YMF262 (OPL3) port 1 register write |

---

## File Layout

```
src/main/java/com/fupfin/midiraja/
├── vgm/
│   ├── VgmParser.java            -- header + command parsing
│   ├── VgmParseResult.java       -- parse result record
│   ├── VgmEvent.java             -- chip event record
│   ├── VgmToMidiConverter.java   -- Sequence orchestrator
│   ├── FmMidiUtil.java           -- shared FM utilities (program, velocity, pan)
│   ├── Sn76489MidiConverter.java -- SN76489 PSG → MIDI
│   ├── Ym2612MidiConverter.java  -- YM2612/OPN FM → MIDI (also YM2203/2608/2610)
│   ├── Ym2151MidiConverter.java  -- YM2151 OPM → MIDI (arcade)
│   ├── Ay8910MidiConverter.java  -- AY-3-8910 PSG → MIDI (MSX, also OPN SSG)
│   ├── SccMidiConverter.java     -- K051649 SCC → MIDI (MSX)
│   ├── Ym3812MidiConverter.java  -- YM3812 OPL2 / YMF262 OPL3 → MIDI
│   ├── Ym2413MidiConverter.java  -- YM2413 OPLL → MIDI (MSX/SMS)
│   ├── HuC6280MidiConverter.java -- HuC6280 wavetable → MIDI (PC Engine)
│   ├── GameBoyDmgMidiConverter.java -- Game Boy DMG → MIDI
│   ├── TrackRoleAssigner.java    -- post-conversion GM program assignment
│   └── VgmFileDetector.java      -- file detection utility
└── cli/
    └── VgmCommand.java           -- picocli subcommand
```

**Test files:**

```
src/test/java/com/fupfin/midiraja/vgm/
├── FmMidiUtilTest.java
├── TrackRoleAssignerTest.java
├── Ym2612MidiConverterTest.java
├── Ym3812MidiConverterTest.java
├── Ym2413MidiConverterTest.java
├── HuC6280MidiConverterTest.java
├── GameBoyDmgMidiConverterTest.java
└── ...
```

---

## GM Program Assignment (2-Pass)

GM programs are assigned in a post-conversion pass by `TrackRoleAssigner`, not by
individual chip converters. This decouples note generation from instrument selection.

### Pass 1: chip conversion (no Program Change)

All chip converters emit only NoteOn/NoteOff/CC events. No Program Change is emitted
during conversion. 4-op FM converters (YM2612, YM2151) still compute velocity from
carrier TL and feedback:

```
avgCarrierTL = average TL of carrier operators for the channel's algorithm
tlDb  = (avgCarrierTL − REF_TL) × 0.75      // REF_TL=20
fbDb  = feedback × 0.375                      // harmonic energy correction
velocity = clamp(round(127 × 10^(−(tlDb + fbDb) / 20)), 1, 127)
```

### Pass 2: adaptive program assignment

`TrackRoleAssigner` first checks FM patch volatility by scanning VGM events:

**Volatile FM (patch change rate > 50%):** OPL2/OPL3 game drivers reuse channels as a
note pool, changing connection/feedback on nearly every key-on. All melodic channels
receive Grand Piano (GM 0) for consistent playback.

**Stable FM (patch change rate ≤ 50%):** Segment-based role analysis (2-measure windows)
classifies each channel as lead, harmony, or bass per segment:

| Role | Sustained | Percussive |
|------|-----------|------------|
| Lead (highest median) | Electric Piano 1 (4) | Xylophone (13) |
| Harmony (other) | Electric Piano 2 (5) | Xylophone (13) |
| Bass (lowest median) | Electric Bass (33) | Slap Bass (36) |

Program Change is emitted at segment boundaries only when the role changes.

---

## Design Decisions

1. **Conversion point:** At playback time (PlaylistPlayer), not streaming. VGM files are
   hundreds of KB to a few MB — in-memory conversion is practical.
2. **FM pitch approximation:** YM2612 FM synthesis has complex harmonics. Only the carrier
   F-Number/Block is used to derive the fundamental pitch. Playability is preferred over
   accuracy.
3. **FM instrument selection:** GM program is derived from the algorithm and feedback fields
   of register 0xB0. This gives each channel its own timbre and adapts when the patch changes
   mid-song. The mapping is approximate — GM cannot model real FM synthesis — but produces
   noticeably more variety than a single fixed patch.
4. **Noise → hi-hat only:** AY-3-8910 noise is electrical white noise with no drum body.
   GM snare (38) introduces low-frequency content that does not exist in the original signal.
   All noise periods map to hi-hat variants (42, 46) based on R6 frequency.
5. **AY-3-8910 envelope approximation:** The hardware envelope generator (R11-R13) modulates
   amplitude over time. Rather than emitting CC11 ramps, an effective volume is estimated
   from the R13 shape. Decay shapes (R13=9, common in MSX games) use vol=7 instead of
   vol=15 to avoid sustaining loud bass notes that should be percussive plucks.
6. **Fine/coarse write retrigger (AY-3-8910 and SCC):** The 12-bit tone period is split
   across two register writes — fine (low byte) then coarse (high bits). Retrigger fires on
   *both* fine and coarse writes. MSX game music engines frequently update only the fine byte
   for small pitch changes and glissandi; triggering only on the coarse write silently drops
   those pitch changes, making entire melodies disappear. When both bytes are written in the
   same tick the intermediate (partial-period) note fires first then immediately retriggers to
   the final note — zero-duration, inaudible in MIDI.
7. **SCC enable register:** The K051649 channel enable (port 3) is not used as a MIDI note
   gate. MSX games toggle enable=0/1 rapidly around each waveform-data write (typically many
   times per tick) to suppress noise during wavetable updates. Treating enable=0 as NoteOff
   generates sub-millisecond NoteOn/Off pairs that effectively mute the channel in TSF. Volume
   alone gates notes: vol>0 → NoteOn, vol=0 → NoteOff.
8. **SCC clock fallback:** The K051649 clock field in the VGM header is 0 in most MSX VGMs.
   The SCC clock is reconstructed as `ay8910Clock × 2`. The K051649 runs at the full MSX CPU
   bus clock (3.579545 MHz NTSC); the AY-3-8910 has an internal /2 prescaler so its clock is
   half that. Using ay8910Clock directly produces notes one octave too low.
9. **SCC / AY8910 volume → CC7 square-root mapping:** SCC and AY-3-8910 vol 0-15 maps to
   CC7 via `√(vol / 15) × 127`. The 4-bit volume register on these chips behaves perceptually
   closer to a logarithmic scale — mid-range values (vol 4-8) represent significantly more
   than 27-54% of maximum amplitude. Linear mapping (vol/15×127) makes typical game volumes
   (vol 4-6) inaudibly quiet; sqrt mapping places vol=4 at CC7=85 which matches perceived
   loudness more faithfully.
10. **Compressed files:** `.vgz` is gzip-compressed VGM; handled transparently with
    `GZIPInputStream`.
11. **TSF drum activation:** TSF requires Program Change 0 on channel 9 to set `isDrums=1`.
    Omitting it leaves the channel in melodic (piano) mode.
12. **FM velocity from TL:** Carrier operator TL values are read and converted to MIDI velocity
    using dB-based scaling with `REF_TL=20` as the full-volume reference. Feedback is subtracted
    at 0.375 dB/step to compensate for harmonic energy dispersion in high-feedback patches.
13. **UI channel program display:** `ChannelActivityPanel.updatePrograms()` must be called
    inside the render loop. Calling it once before the loop starts means tick-0 Program Change
    events have not yet been processed, so all channels display as Piano (program 0).
14. **YM2612 stereo pan:** Register 0xB4-0xB6 bits 7-6 encode per-channel L/R enable. Mapped
    to MIDI CC10: L-only=0, R-only=127, L+R=64 (center). CC10 is deferred to key-on (not
    emitted on every 0xB4 write) to avoid events on silent channels, and re-emitted mid-note
    on pan changes. SN76489, AY-3-8910, and SCC are mono chips — no pan is applied.
15. **Cross-chip CC7 gain corrections:** Two independent gain constants balance perceptual
    loudness across chips rendered through FluidR3:
    - `Sn76489MidiConverter.PSG_CC7_GAIN = 0.708` (−3 dB): Square Lead (prog 80) sits at
      ~2.4 kHz, above YM2612 FM at ~1.2 kHz. Equal-loudness contours make it perceptually
      louder at equal RMS. Hardware energy ratio (FM ~9.8 dB louder) is preserved; only
      psychoacoustic prominence is corrected.
    - `Ay8910MidiConverter.PSG_CC7_GAIN = 0.580` (−4.7 dB): Square Lead is 4.7 dB louder
      than Rock Organ at equal CC7 in FluidR3 (measured via `scripts/measure_instrument_levels.py`).
      MSX games use AY-3-8910 and SCC in equal melodic roles, so the soundfont level difference
      is corrected. Genesis SN76489 uses a separate constant for the reason above.
16. **OPL2/OPL3 +12 octave correction:** FM synthesis produces strong 2nd harmonics that
    raise the perceived pitch above the mathematical fundamental. Grand Piano lacks these
    harmonics, so playing at the fundamental sounds one octave too low. WAV comparison of
    multiple OPL2 VGMs (campfire_DOS, monkey_intro) confirmed the consistent 1-octave gap.
    `opl2Note()` adds +12 to the computed MIDI note.
17. **OPL2 patch filtering:** Game drivers use `conn=0, fb=0` as an instant note-cut trick
    (avoids slow key-off release). Carrier TL ≥ 55 means inaudible output. Modulator TL < 10
    means extreme modulation producing metallic/percussive effects. These are either suppressed
    or routed to MIDI ch 9 (drums) to prevent dissonance from non-melodic patches.
18. **Adaptive GM assignment:** OPL2/OPL3 drivers change FM patches on 70-80% of key-ons,
    making per-channel instrument assignment ineffective. `TrackRoleAssigner.isVolatileFm()`
    detects this and falls back to uniform Grand Piano. Stable-channel VGMs (Genesis, MSX,
    arcade) use segment-based role analysis instead.
19. **VGM header version guards:** Chip clock fields are only valid in specific VGM versions
    (e.g. GB DMG at 0x80 requires v1.61+). Reading beyond the version's defined header
    produces garbage values that are misinterpreted as chip clocks.
20. **Per-note program selection restored for stable FM:** Stable FM chips (YM2612, YM2151,
    OPN) emit their own Program Change per note. OPL-specific changes (2-pass
    TrackRoleAssigner) no longer affect these chips. TrackRoleAssigner only fills in
    unassigned channels via `assignUnassigned()`.

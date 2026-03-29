# VGM → MIDI Conversion Architecture

## Overview

Converts VGM (Video Game Music) files to `javax.sound.midi.Sequence` for playback through
the existing MIDI pipeline. The conversion is injected in `PlaylistPlayer.play()` before the
`MidiUtils.loadSequence()` call: VGM files are detected, converted to a `Sequence`, and
handed off to the synth provider as if they were ordinary MIDI files.

Supported chips: **SN76489** (Sega PSG), **YM2612** (Sega FM), **AY-3-8910** (MSX PSG),
**K051649** (Konami SCC wavetable).

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
    long sn76489Clock,   // Hz; 0 = chip absent
    long ym2612Clock,    // Hz; 0 = chip absent
    long ay8910Clock,    // Hz; 0 = chip absent (header offset 0x74)
    long sccClock,       // Hz; falls back to ay8910Clock if K051649 header field is 0
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

---

### `VgmToMidiConverter` — `com.fupfin.midiraja.vgm`

Top-level orchestrator that converts `VgmParseResult` to `javax.sound.midi.Sequence`.

**Responsibilities:**
- Set PPQ=480 and tempo=500000 µs (120 BPM) → 960 ticks/sec
- Emit Program Change at tick 0 for each MIDI channel
- Route events to the appropriate chip converter
- Insert GD3 title as MetaMessage (type 0x03) in track 0

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

### `Ym2612MidiConverter` — `com.fupfin.midiraja.vgm`

Converts YM2612 FM chip events to MIDI note events.

**Responsibilities:**
- Decode port 0/1 register writes (0xA0-0xA6, 0x28, 0xB0)
- Convert F-Number + Block to frequency, then to MIDI note number
- Map key-on (0x28) to NoteOn and key-off to NoteOff
- Derive GM program from algorithm (0xB0 bit2-0) and feedback (0xB0 bit5-3)
- Use MIDI channels 3-8

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

| R13 shapes | Character | Effective vol |
|------------|-----------|---------------|
| 0-3, 8, 9, 10, 15 | Decay / single-shot transient | 7/15 (velocity ≈ 59) |
| 11, 13 | Single phase then hold at max | 11/15 (velocity ≈ 93) |
| 12, 14 | Continuous sawtooth / triangle | 8/15 (velocity ≈ 68) |

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
- Port 0 (waveform data, 0x00-0x7F): ignored for MIDI conversion
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

---

## MIDI Channel Layout

| MIDI ch | Source | GM Program |
|---------|--------|------------|
| 0 | SN76489 Tone 0 / AY8910 ch A | 80 (Square Lead) |
| 1 | SN76489 Tone 1 / AY8910 ch B | 80 (Square Lead) |
| 2 | SN76489 Tone 2 / AY8910 ch C | 80 (Square Lead) |
| 3 | YM2612 FM ch1 | dynamic (Program Change per 0xB0) |
| 4 | YM2612 FM ch2 | dynamic |
| 5 | YM2612 FM ch3 | dynamic |
| 6 | YM2612 FM ch4 | dynamic |
| 7 | YM2612 FM ch5 | dynamic |
| 8 | YM2612 FM ch6 | dynamic |
| 9 | SN76489 / AY8910 Noise (GM percussion) | 0 (Drums) |
| 10 | SCC ch0 | 81 (Sawtooth Lead) |
| 11 | SCC ch1 | 81 (Sawtooth Lead) |
| 12 | SCC ch2 | 81 (Sawtooth Lead) |
| 13 | SCC ch3 | 81 (Sawtooth Lead) |
| 14 | SCC ch4 | 81 (Sawtooth Lead) |

Channel 9 is the GM drum channel. TSF requires Program Change 0 on channel 9 to activate
drum mode (`isDrums=1`); without it the channel is treated as melodic (piano).

SN76489 and AY-3-8910 share MIDI channels 0-2 and 9 — a VGM file contains at most one
of these chips.

YM2612 ch6 DAC (PCM streaming) is currently ignored — its timing is counted but no MIDI
events are emitted. See TODO.md for the planned PCM → SoundFont approach.

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

### YM2612 frequency

```
f = FNum × clock / (144 × 2^(21 - block))
```
- `FNum`: 11-bit F-Number
- `block`: 3-bit octave block (0-7)
- `clock`: YM2612 clock (typically 7670453 Hz — Sega Genesis)

```java
static int ym2612Note(long clock, int fnum, int block) {
    double f = fnum * clock / (144.0 * (1L << (21 - block)));
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
```

Volume 15 (silent) → NoteOff. All other values → CC7 update.
NoteOn always sends CC7 immediately before the NoteOn message so the channel volume is set
correctly regardless of any CC7 value left over from a previous note.

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
| `0xA0` | 2 (reg, data) | AY-3-8910 register write; reg bits 3-0 = register number |
| `0xD2` | 3 (port, addr, data) | K051649 (SCC) register write |
| `0xE0` | 4 (offset LE) | Seek PCM data bank to byte offset. Marks sample boundaries. |

---

## File Layout

```
src/main/java/com/fupfin/midiraja/
├── vgm/
│   ├── VgmParser.java            -- header + command parsing
│   ├── VgmParseResult.java       -- parse result record
│   ├── VgmEvent.java             -- chip event record
│   ├── VgmToMidiConverter.java   -- Sequence orchestrator
│   ├── Sn76489MidiConverter.java -- SN76489 PSG → MIDI
│   ├── Ym2612MidiConverter.java  -- YM2612 FM → MIDI
│   ├── Ay8910MidiConverter.java  -- AY-3-8910 PSG → MIDI (MSX)
│   ├── SccMidiConverter.java     -- K051649 SCC → MIDI (MSX)
│   └── VgmFileDetector.java      -- file detection utility
└── cli/
    └── VgmCommand.java           -- picocli subcommand
```

---

## YM2612 Algorithm → GM Program Mapping

YM2612 register 0xB0 (one per channel, port 0 and port 1) encodes:

```
bit 5-3: feedback (0-7) — self-modulation depth of OP1
bit 2-0: algorithm (0-7) — operator topology
```

`Ym2612MidiConverter` reads 0xB0 writes and emits a Program Change on the corresponding
MIDI channel immediately before the next NoteOn, but only when the derived program number
changes. This keeps the MIDI stream lean while still adapting to mid-song patch changes.

### Algorithm topology summary

| Algorithm | Topology | Character |
|-----------|----------|-----------|
| 0 | OP1→OP2→OP3→OP4 (fully serial) | Deep FM modulation — bass, organ |
| 1 | (OP1+OP2)→OP3→OP4 | Heavy modulation — synth bass |
| 2 | (OP1+(OP2→OP3))→OP4 | Moderate modulation — lead |
| 3 | ((OP1→OP2)+OP3)→OP4 | Moderate modulation — lead |
| 4 | (OP1→OP2)+(OP3→OP4) | Two independent 2-op pairs — classic Genesis lead |
| 5 | OP1→(OP2+OP3+OP4) | One modulator, three carriers — pad/brass |
| 6 | (OP1→OP2)+(OP3+OP4) | Near-additive — organ |
| 7 | OP1+OP2+OP3+OP4 | Fully additive, four carriers — bright organ/bells |

### Program selection logic

```java
static int selectProgram(int alg, int fb) {
    if (fb >= 6) {           // high feedback → square-wave-like buzz
        return switch (alg) {
            case 0,1,2,3 -> 29;   // Overdriven Guitar
            case 4       -> 80;   // Square Lead
            default      -> 19;   // Rock Organ
        };
    }
    return switch (alg) {
        case 0 -> 33;   // Electric Bass (Finger)
        case 1 -> 38;   // Synth Bass 1
        case 2 -> 81;   // Sawtooth Lead
        case 3 -> 81;   // Sawtooth Lead
        case 4 -> 81;   // Sawtooth Lead (most common Genesis lead algorithm)
        case 5 -> 89;   // Pad 2 (Warm)
        case 6 -> 19;   // Rock Organ
        case 7 -> 16;   // Hammond Organ
        default -> 81;
    };
}
```

SN76489 / AY-3-8910 channels (0-2) keep a fixed GM 80 (Square Lead) set at tick 0.
YM2612 channels (3-8) receive no Program Change at tick 0; `Ym2612MidiConverter` issues
the first Program Change lazily on the first key-on after 0xB0 is seen.
SCC channels (10-14) keep a fixed GM 81 (Sawtooth Lead) set at tick 0.

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
12. **YM2612 volume:** Fixed velocity 100 is used for all FM channels. Mapping FM Total Level
    (TL) registers to MIDI velocity is not yet implemented.
13. **UI channel program display:** `ChannelActivityPanel.updatePrograms()` must be called
    inside the render loop. Calling it once before the loop starts means tick-0 Program Change
    events have not yet been processed, so all channels display as Piano (program 0).

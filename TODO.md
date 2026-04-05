# Midiraja Future Improvements

---

## VGM Playback Improvements

`midra vgm` converts SN76489/YM2612 → MIDI and plays back. The following items are
unimplemented quality improvements.

### YM2612 Volume (Total Level registers)
**Current:** All YM2612 channels play at a fixed velocity of 100.
**Goal:** Parse FM operator Total Level (TL) registers (0x40–0x4E per port) and map the
carrier operator's TL to CC7 or NoteOn velocity.
- TL range: 0 (loudest) to 127 (silent) — inverted relative to MIDI, so approximate as `127 - TL`.
- Carrier operator identification depends on the algorithm byte; simplification: always use OP4
  (TL at address 0x4C + ch) regardless of algorithm.

### YM2612 DAC PCM → SoundFont
**Current:** `0x80`-`0x8F` DAC writes are used only for timing accumulation. No PCM audio plays.
**Goal:** Extract sample data from the `0x67` PCM block, package it as an SF2 SoundFont, and
convert `0xE0` seek commands into NoteOn events on MIDI channel 9.

**Analysis (Sonic 3 title screen):**
- 1 PCM block: 8444 bytes, type=0x00 (8-bit unsigned), DAC sample rate ≈ 8567 Hz
- 56 `0xE0` seek commands → 5 distinct sample regions identified

| Sample | Byte offset | Size (bytes) | Duration (s) |
|--------|-------------|-------------|--------------|
| 0 | 0 | 3052 | ~0.36 |
| 1 | 3052 | 1618 | ~0.19 |
| 2 | 4670 | 2218 | ~0.26 |
| 3 | 6888 | 1532 | ~0.18 |
| 4 | 8420 | 24 | ~0.003 (negligible) |

**Implementation sketch:**
1. `VgmParser`: retain PCM byte array from `0x67`; emit a DAC event on each `0xE0` seek
2. `VgmToMidiConverter`: convert DAC events to ch9 NoteOn (seek offset → sample index → MIDI note)
3. `VgmSf2Builder`: extract PCM regions, write them as an SF2; `VgmCommand` creates a temp SF2 and loads it into TSF

### SN76489 Periodic Noise Pitch
**Current:** The noise channel always uses fixed note 38 (GM snare).
**Goal:** Decode noise register bits 1-0 to distinguish periodic noise (bit1=0) from white
noise (bit1=1). For periodic noise, reference the Tone 2 register frequency to derive a
pitched percussion note.

---

## Roadmap

### "Just Works" Default Playback
**Goal:** Let new users run `midra song.mid` with no engine flag and hear audio instantly.
- Implement `EngineAutoSelector`: detect available engines in priority order (soundfont → 1bit)
- Auto-discover SF2 files from common system paths (Homebrew, apt, MuseScore)
- Fallback to `1bit` when no SF2 is found — always produces sound, zero dependencies

### Config / Preset System
**Goal:** Persist per-user defaults so common flags are not retyped.
- `midra config set default-engine soundfont`
- `midra config set default-sf2 ~/soundfonts/FluidR3_GM.sf2`
- TOML config file at `~/.config/midra/config.toml`

### Package Manager Distribution
**Goal:** `brew install midra` / `scoop install midra` / `aur` one-liner.
- Homebrew tap (`fupfin/tap`)
- Scoop bucket
- AUR package (community or official)
- Chocolatey / `winget` manifest
- CI smoke test on Windows (GitHub Actions)

---

## Ideas

### Audio-Driven VU Meter (Frequency-Band Spectrum Analyser)
**Goal:** For internal soft-synths (OPL, OPN, Munt, TSF, GUS, PSG, Beep), replace the
MIDI-event-driven per-channel VU with a real-time spectrum analyser that reflects the actual
rendered PCM output.

**Problem with current VU:** reads NoteOn velocity from the MIDI stream — shows what *should*
play, not what *is* playing. Latency, voice stealing, and reverb tails are all invisible.

#### Design decisions
- **Toggle UI** — `b` key switches between channel VU (current) and frequency-band VU; no new
  mode flag required, state lives inside `ChannelActivityPanel`
- **Scale** — logarithmic (dB) with peak-hold markers (~500 ms hold, then decay); matches human
  auditory perception
- **Fallback** — native-MIDI path (CoreMIDI / ALSA) has no PCM; channel VU stays as-is and `b`
  key is disabled / no-op in that mode

#### Implementation sketch

| File | Change |
|------|--------|
| `dsp/FFT.java` | NEW — pure-Java radix-2 FFT (1024-point, in-place) |
| `dsp/SpectrumTapFilter.java` | NEW — `AudioFilter` inserted in the pipeline after FX; runs FFT each frame, maps bins to 7 perceptual bands, stores result in `volatile float[]` reference for lock-free render→UI hand-off |
| `ui/ChannelActivityPanel.java` | Add toggle mode field; `b` key handler; render frequency bars (dB scale + peak markers) when in band mode |
| `ui/DashboardUI.java` | Hold `SpectrumTapFilter` reference; wire `b` key to `ChannelActivityPanel`; disable toggle when filter is absent (native MIDI) |
| `cli/FmSynthOptions.java` | Instantiate `SpectrumTapFilter` and insert it into the `AudioProcessor` pipeline |
| `*Command.java` (OPL/OPN/Munt/…) | Pass `SpectrumTapFilter` through to `DashboardUI` at startup |

#### Signal path
```
render thread → AudioProcessor pipeline → SpectrumTapFilter → sink
                                               │
                                         (volatile ref swap)
                                               ↓
                                        UI repaint thread → ChannelActivityPanel
```

#### Band layout (7 bands, log-spaced)
| Band | Approx. range | Character |
|------|--------------|-----------|
| 0 | 20 – 80 Hz | Sub-bass |
| 1 | 80 – 250 Hz | Bass |
| 2 | 250 – 800 Hz | Low-mid |
| 3 | 800 – 2500 Hz | Mid |
| 4 | 2.5 – 5 kHz | Upper-mid |
| 5 | 5 – 10 kHz | Presence |
| 6 | 10 – 20 kHz | Air |

### Chip Emulator Library (`midiraja-chips`)
**Goal:** Extract pure-Java chip emulators from MIDI logic and develop them into a standalone
library. The current implementation accepts MIDI events and manages registers internally; the
library level should expose the hardware register interface directly.

**Common interface**
```java
interface ChipEmulator {
    void write(int address, int value); // register write
    int  read(int address);             // register read
    void render(short[] buf, int frames);
    void reset();
}
```

**Target chips and priority**

| Chip | Current file | Remaining work | Priority |
|------|-------------|----------------|----------|
| AY-3-8910 / YM2149F | `psg/PsgChip.java` | R0–R15 register interface, 8 envelope shapes, 17-bit LFSR, clock divider | High |
| Konami SCC / K051649 | `psg/SccChip.java` | Accurate register map, 10-bit period → freq, SCC vs SCC+ waveform-sharing rules | High |
| 1-bit cluster synth | `beep/BeepSynthProvider.java` | Position as a creative synthesis library rather than strict H/W accuracy — split separately | Medium |
| GUS / GF1 | `gus/GusEngine.java` | GF1 register map is complex → keep at current level | Low |

The midiraja core should be refactored so it only wraps this library from `MidiOutProvider`.

### Third-Party Synth Library Integration

New library candidates organized by integration pattern.

#### H/W MIDI Synth Emulators (Munt pattern)
Hardware emulators that receive MIDI messages and render PCM.
Integrate with the same structure as `FFMMuntNativeBridge` / `MuntSynthProvider`.

| Library | Emulates | Notes |
|---------|----------|-------|
| **Nuked-SC55** | Roland Sound Canvas SC-55 / SC-88 | Standard GM timbres of the 90s; has built-in GM so no channel-assignment issues unlike MT-32 |

#### FM Synthesis Libraries (libADLMIDI pattern)
Libraries that drive chips directly with built-in GM mapping.
Integrate with the same structure as `FFMAdlMidiNativeBridge` / `AdlMidiSynthProvider`.

| Library | Supported chips | Notes |
|---------|----------------|-------|
| **ymfm** (Aaron Giles) | OPL2/3, OPN/OPN2/OPNA, **OPM(YM2151)**, OPLL, OPL4 — full Yamaha FM lineup | See separate item below |
| **DX7 engine (msfa)** | Yamaha DX7 (6-op sine FM) | Synthesis core from Dexed; 80s FM timbres structurally different from OPL; `.syx` patch loading |

##### OPM Patch Auto-Optimizer (`opm-patch-optimizer`)

An offline tool that takes an OPN `.wopn` bank as a starting point and uses SF2 (FluidSynth) as a
reference to auto-optimize OPM FM patches, generating a GM 128-instrument bank.
Follows the same offline optimization pattern as the `BeepSynthProvider` God Table generator.
Detailed design: [`docs/opm-patch-optimizer.md`](docs/opm-patch-optimizer.md)

##### Two paths for ymfm integration

ymfm provides only the chip emulator core. GM bank mapping, voice allocation, and MIDI event
handling are currently handled by libADLMIDI / libOPNMIDI.

**Path A — Add only new chips via ymfm (incremental)**
- Keep libADLMIDI / libOPNMIDI as-is
- Use ymfm + custom MIDI layer only for chips unsupported by existing libraries (OPM/YM2151, OPLL/YM2413, etc.)
- Expose as `midra opm`, `midra opll` subcommands
- **Challenge**: unlike OPL/OPN, there is no GM bank file standard — must design the MIDI→FM patch mapping from scratch

**Path B — Full replacement with a single ymfm core (long-term refactor)**
- Remove libADLMIDI / libOPNMIDI dependencies; unify OPL, OPN, OPM, and OPLL under ymfm
- Aligns naturally with the `midiraja-chips` library extraction direction
- **Challenge**: must re-implement GM mapping, voice stealing, and effects layers from libADLMIDI/libOPNMIDI — considerable effort; hard to preserve existing timbre quality
- **Challenge**: need bank data to replace libADLMIDI's extensive built-in OPL bank collection (dozens of banks)

#### Chip Emulators (PSG/SCC pattern)
Register-interface-level emulators. MIDI mapping implemented separately.

| Library | Emulates | Notes |
|---------|----------|-------|
| **libsidplayfp** | C64 SID (MOS 6581/8580) | Already planned in TODO; mature API |

### Playlist Management
- **M3U export** — key press (e.g. `E`) saves the current in-memory playlist to a `.m3u` file
- **Runtime add / remove** — add files or directories to the running playlist; remove the current
  or any queued track (requires `PlaylistContext` to become mutable)

### `midra compare`
Cycle through all available engines for the same MIDI file, pausing between each — useful for
evaluating SF2 files or choosing a retro aesthetic.

### Pure Java Audio & `midrax` Revival
Replace `libmidiraja_audio` (miniaudio) with a `javax.sound.sampled`-based sink.
`midrax` would then be a standalone ZIP requiring only Java 25+, no native libraries.

### Retro DAC DSP Filters (`--covox`, `--paula`)
Global post-processing filters applicable to any engine via the AudioProcessor pipeline.
- `--covox`: mono, 8-bit requantization, no filter — raw parallel-port DAC sound
- `--paula`: stereo, 8-bit + RC low-pass (~4kHz), voice-index hard panning (even→L, odd→R)
- Stereo effect variations: ping-pong (alternating L/R per note), Haas delay,
  independent L/R quantization

### C64 SID Synthesizer (`midra sid`)
Oscillator-based synthesis like `psg`, extended with SID's additional features.
- Sawtooth / triangle / pulse / noise waveforms with ADSR per voice (3 voices)
- Ring modulation and oscillator sync between voices
- Resonant multimode filter (LP/BP/HP) — the most distinctive part of the SID sound
- Option B — libsidplayfp for cycle-accurate emulation if pure Java proves insufficient

### NES APU (`midra nes`)
5-voice oscillator synthesis (2× pulse, 1× triangle, 1× noise, 1× DPCM).
- **Pro**: iconic sound, pure Java feasible (no native lib needed), well-documented register set
- **Pro**: strong nostalgia value; most MIDI listeners have heard NES music
- **Challenge**: only 5 simultaneous voices — polyphonic MIDI will require aggressive voice stealing
- **Challenge**: DPCM channel needs sample data; can omit or substitute with noise

### Game Boy APU (`midra gb`)
4-voice synthesis: 2× pulse (with sweep), 1× 32-byte wavetable, 1× noise.
- **Pro**: extremely iconic; distinct lo-fi aesthetic different from NES
- **Pro**: pure Java feasible; register set is simple and well-documented
- **Challenge**: only 4 voices — even more polyphony-limited than NES
- **Challenge**: wavetable channel requires programming waveform data per instrument

### Atari POKEY (`midra pokey`)
4-channel oscillator with unusual distortion modes (pure tone, high-pass, square-wave divisions).
- **Pro**: distinctive buzzy character not found in other chips; beloved in Atari 8-bit demoscene
- **Pro**: pure Java feasible
- **Challenge**: niche audience; less recognizable than NES/SID to most users
- **Challenge**: POKEY's pitch table is non-standard (clock-divided counters, not direct frequency)

### YM2151 OPM FM Synthesizer (`midra opm`)
8-voice, 4-operator FM synthesis — used in arcade boards and the Sharp X68000.
- **Pro**: follows the same pattern as OPL/OPN; **ymfm supports OPM (YM2151)** → no separate library needed
- **Pro**: warm, classic arcade FM sound distinct from Sega/AdLib timbres
- **Challenge**: less name-recognition than OPL/OPN outside Japanese retro computing circles
- **Path**: OPM comes along automatically when ymfm is integrated — expose as `midra opm` subcommand

### SNES SPC700 (`midra spc`)
8-channel ADPCM (BRR) sample playback with hardware echo/reverb and a DSP mixer.
- **Pro**: extremely rich MIDI music library (SNES game soundtracks); strong nostalgia
- **Pro**: hardware reverb is a built-in feature, not an add-on
- **Challenge**: BRR is a proprietary sample format — no standard soundbank exists for MIDI
- **Challenge**: would need a custom instrument mapping from GM patches to BRR samples
- **Challenge**: accurate emulation requires cycle-exact SPC700 CPU; pure Java is possible but complex
- Option — libsnes-apu or similar for accurate emulation

### PC Engine HuC6280 PSG (`midra pce`)
6-channel 32-byte wavetable synthesis with per-channel DDA (direct D/A) mode.
- **Pro**: interesting hybrid between PSG and wavetable; subtle, soft sound
- **Pro**: pure Java feasible; register interface is straightforward
- **Challenge**: minor platform; PC Engine music is less universally recognized
- **Challenge**: 32-byte wavetables are very short — instrument design is highly constrained


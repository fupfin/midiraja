# Soft Synth Guide

`midra` supports four built-in software synthesizers in addition to the native OS MIDI output. Each is selected as a subcommand.

| Synthesizer | Command | Sound | External files |
|-------------|---------|-------|----------------|
| FluidSynth | `fluid` | General MIDI (SF2) | SoundFont (.sf2) |
| MT-32 / Munt | `munt` | Roland MT-32 (DOS-era game music) | 2 ROM files |
| GUS / Java | `gus` | Gravis Ultrasound (1990s Tracker/MIDI) | `.pat` Patch sets (gus.cfg) |
| PC Speaker | `beep` | 1-bit Apple II / DOS PC Speaker | None (Pure Math) |
| OPL / libADLMIDI | `opl` | AdLib / Sound Blaster FM (OPL2/OPL3) | Built-in (optional .wopl bank) |
| OPN2 / libOPNMIDI | `opn` | Sega Genesis / PC-98 FM (OPN2/OPNA) | Built-in (optional .wopn bank) |

---


---

## PC Speaker & Apple II / `beep`

The `beep` engine is a pure mathematical software synthesizer designed to recreate the extreme limitations of early 1980s computer audio, such as the IBM PC internal speaker and the Apple II. It relies on mathematically generating raw 1-bit square waves, requiring no soundbanks or external files.

### Usage

```bash
# Default: Electric Sixteentet (8-Core Apple II Cluster)
midra beep song.mid

# Pure PWM (Mathematical Delta-Sigma modulation)
midra beep --mode pwm song.mid
```

### The "Electric Sixteentet" Engine

By default, the `beep` command runs the **Electric Sixteentet** engine. This is a highly advanced simulation of the legendary "Electric Duet" software written by Paul Lutus for the Apple II in 1981.

Instead of simple arpeggios, `midra` simulates a cluster of **8 synchronized Apple II computers**. Each virtual machine computes two simultaneous voices and multiplexes them onto a single 1-bit output pin using **XOR Ring Modulation** and dynamic **Duty Cycle Sweeps**. The result is a surprisingly rich, fuzzy, 16-note polyphonic 8-bit chorus that must be heard to be believed.

## FluidSynth (General MIDI / SF2)

FluidSynth is a **sample-based synthesizer**. It plays back pre-recorded instrument samples stored in SF2 SoundFont files, blending and looping them to produce each note. Because the source material is real recorded audio, it can achieve highly realistic instrument tones. Sound quality depends almost entirely on the SoundFont used — a high-quality SF2 file with large multi-velocity samples will sound dramatically better than a minimal one.

### Installation

FluidSynth is loaded dynamically at runtime. Install it via your package manager.

**macOS:**
```bash
brew install fluid-synth
```

**Ubuntu / Debian:**
```bash
sudo apt install libfluidsynth3
```

**Fedora / RHEL:**
```bash
sudo dnf install fluidsynth
```

### SoundFont

FluidSynth requires an SF2 SoundFont file. Some freely available options:

- **GeneralUser GS** (`generaluser.sf2`) — lightweight, good quality. [Download](https://www.schristiancollins.com/generaluser.php)
- **MuseScore General** — bundled with MuseScore. macOS path: `/Applications/MuseScore 4.app/Contents/Resources/sound/MuseScore_General.sf2`
- **FluidR3 GM** — available as an Ubuntu package: `sudo apt install fluid-soundfont-gm`

### Usage

```bash
# Specify the path to a SoundFont file
midra fluid /path/to/soundfont.sf2 song.mid

# With playlist options
midra fluid /path/to/soundfont.sf2 --loop --shuffle *.mid
```

### Audio driver override (optional)

If FluidSynth fails to auto-detect the audio driver, specify it explicitly:

```bash
midra fluid --driver coreaudio soundfont.sf2 song.mid   # macOS
midra fluid --driver alsa soundfont.sf2 song.mid        # Linux
```

---

## MT-32 / Munt

The Roland MT-32 (1987) uses **LA (Linear Arithmetic) synthesis** — a hybrid approach that combines short PCM attack transient samples with digitally synthesized sustain waveforms, shaped by multi-stage envelopes and a built-in reverb unit. The result is a distinctive, lush sound that defined the audio of late-1980s and early-1990s DOS game music. Titles like Monkey Island, Wing Commander, Ultima, and many Sierra adventure games were scored specifically for MT-32 and sound substantially different — and often richer — on it than on General MIDI.

Munt is an open-source MT-32 emulator that requires ROM dumps from real hardware for accurate reproduction.

### Installation

**macOS:**
```bash
brew install munt
```

**Ubuntu / Debian:**
```bash
sudo apt install libmt32emu3
```

**Fedora / RHEL:**
```bash
sudo dnf install munt
```

### ROM files

Munt requires ROM dumps from real MT-32 hardware. Place both files in the same directory:

```
~/mt32-roms/
  MT32_CONTROL.ROM   (64 KB)
  MT32_PCM.ROM       (512 KB)
```

> ROM files are proprietary Roland intellectual property. Only use ROMs you have extracted from hardware you own or obtained through legally permitted means.

ROM files from CM-32L and LAPC-I variants are also supported.

### Usage

```bash
# Specify the directory containing the ROM files
midra munt ~/mt32-roms song.mid

# MT-32 playlist
midra munt ~/mt32-roms --loop monkey_island/*.mid
```

---

## Gravis Ultrasound / Pure Java GUS Synthesizer

The Advanced Gravis Ultrasound (1992) revolutionized PC audio by using hardware **wavetable synthesis** instead of FM synthesis. It loaded individual instrument samples (`.pat` files) into the sound card's onboard RAM. This made it a legendary platform for 1990s tracker music (MOD/S3M/XM) and high-quality MIDI playback in early DOS games. The `.pat` format became incredibly popular in the Linux/open-source scene, serving as the original patch format for TiMidity++ before SoundFonts dominated. 

`midra gus` features a **100% pure Java, zero-dependency software synthesizer** built from scratch specifically for Midiraja. It accurately parses multi-sampled 16-bit and 8-bit `.pat` files, handles dynamic Key Split mapping, looping, and release envelopes, and mixes them natively in real-time. 

### Patch Sets

You need a collection of Gravis Ultrasound `.pat` files and a configuration file (like `gus.cfg` or `timidity.cfg`) to map MIDI Program Numbers to the specific patch files. 

Some highly recommended, historically accurate patch sets:
- **Eawpats (Eric A. Welsh Patches)** — The gold standard for General MIDI playback with GUS patches. Widely used and beautifully balanced.
- **Freepats** — A completely free and open-source patch set. Midiraja uses the definitive 2006-02-19 release, as it is the most complete final collection of instruments ever produced in the legacy GUS .pat format before the project transitioned to modern SoundFonts.
- **Original Gravis Patches** — The original 1992 files bundled with the sound card.

### Installation & Zero-Dependency

No installation required! The GUS DSP engine is built directly into `midra`. 

Furthermore, `midra` is **zero-dependency** by default. It comes bundled with the **Freepats (2006-02-19)** instrument collection. If you don't provide a patch directory, it will automatically use the bundled patches to play MIDI files immediately.

### Usage

```bash
# Play using bundled Freepats (Default)
midra gus song.mid

# Point to an external patch set (e.g. Eawpats or Original Gravis patches)
midra gus -p ~/Downloads/eawpats/ song.mid
```

### Retro Lo-Fi & RealSound Effects

The `midra gus` engine includes advanced DSP filters to simulate various historical audio delivery methods:

#### Bitcrushing (`--bits <N>`)
Crush the audio resolution to $N$ bits (1--15). Unlike simple bitcrushers, `midra` uses **First-Order Delta-Sigma Noise Shaping** and an **Amiga-style DAC Reconstruction Filter**. This preserves the musicality and reverb tails of the music while adding that signature warm, harmonic grit of a vintage 8-bit or 12-bit sampler.

```bash
# 6-bit "Amiga/SNES" style sound
midra gus --bits 6 song.mid
```

#### RealSound Simulation (`--pwm`)
Simulates the legendary **Access Software RealSound** technology. It converts the audio into a 1-bit high-frequency switching signal (Pulse Width Modulation) to mimic the IBM PC internal speaker.

To achieve audiophile-grade historical accuracy, `midra` uses:
- **32x Oversampling (1.41 MHz):** Eliminates digital aliasing noise.
- **18.6 kHz True Carrier:** Mathematically identical to the Intel 8253 PIT timer.
- **Virtual Paper Speaker Filter:** A steep band-pass filter that emulates the physical muffled, tinny, and distorted acoustic properties of a 2.25-inch paper cone speaker.

```bash
# True 1980s PC Speaker "RealSound" Experience
midra gus --realsound song.mid
```

---

## OPL FM Synthesis / libADLMIDI

OPL chips (Yamaha OPL2/OPL3, 1985–1993) use **FM (Frequency Modulation) synthesis** — a technique where one oscillator (the modulator) modulates the frequency of another (the carrier), producing complex, harmonically rich timbres from simple sine waves alone. With no sample memory, the entire sound is computed mathematically in real time. This gives FM synthesis its characteristic bright, metallic, slightly buzzy quality: recognizable in the soundtracks of countless DOS games that targeted AdLib and Sound Blaster cards.

OPL2 provides 9 melodic channels (2-operator FM each); OPL3 adds stereo, 4-operator mode, and up to 18 channels. libADLMIDI maps General MIDI to OPL operators using instrument banks, and can emulate multiple chips simultaneously to increase polyphony.

libADLMIDI is **statically linked** into the `midra` binary — no installation required.

### Usage

```bash
# Default bank (General MIDI)
midra opl song.mid

# Built-in bank by number (0–75)
midra opl -b 58 song.mid    # TMB (Descent)
midra opl -b 14 song.mid    # Doom

# External .wopl bank file
midra opl -b /path/to/custom.wopl song.mid
```

### Built-in banks (selected)

libADLMIDI includes 76 built-in instrument banks. Notable ones:

| # | Name | Source |
|---|------|--------|
| 0 | GeneralMidi | General purpose |
| 14 | Doom | id Software Doom |
| 17 | Heretic | Raven Software Heretic |
| 32 | Warcraft2 | Blizzard Warcraft II |
| 47 | Duke3D | 3D Realms Duke Nukem 3D |
| 58 | TMB | Interplay Descent |
| 62 | Ys2 | Falcom Ys II |

For the full list see the [libADLMIDI documentation](https://github.com/Wohlstand/libADLMIDI#embedded-banks).

### OPL emulator selection

Several OPL emulator backends are available:

```bash
midra opl -e 0 song.mid   # Nuked OPL3 v1.8 (default, highest accuracy)
midra opl -e 1 song.mid   # Nuked OPL3 v1.7.4
midra opl -e 5 song.mid   # ESFMu (ESFM extension support)
midra opl -e 6 song.mid   # MAME OPL2 (suitable for OPL2-only songs)
midra opl -e 7 song.mid   # YMFM OPL2
midra opl -e 8 song.mid   # YMFM OPL3
```

### Chip count (polyphony)

More chips means more simultaneous voices:

```bash
midra opl -c 1 song.mid   # 1 chip = 18 channels (authentic single AdLib card)
midra opl -c 4 song.mid   # 4 chips = 72 channels (default)
```

---

## OPN2 FM Synthesis / libOPNMIDI

OPN chips (Yamaha YM2612/OPN2, YM2608/OPNA) use **FM (Frequency Modulation) synthesis** with 4-operator FM channels, producing the rich, warm timbres iconic to Sega Genesis game soundtracks and PC-98 computer music. YM2612 (used in the Sega Genesis/Mega Drive) provides 6 FM channels plus a PCM DAC channel (channel 6 can switch to 8-bit PCM mode); the chip's 9-bit ladder DAC introduces a characteristic slight distortion that gives Genesis music its gritty, aggressive edge. YM2608 (OPNA, used in NEC PC-88/PC-98) adds SSG (PSG-compatible) channels and ADPCM, producing a cleaner, more refined FM sound. libOPNMIDI maps General MIDI to OPN2 operators using `.wopn` instrument bank files.

libOPNMIDI is **statically linked** into the `midra` binary — no installation required.

### Usage

```bash
# Default OPN2 sound (uses libOPNMIDI's internal default bank)
midra opn song.mid

# External .wopn bank file
midra opn -b /path/to/custom.wopn song.mid
```

### OPN2 emulator selection

```bash
midra opn -e 0 song.mid   # MAME YM2612 (default)
midra opn -e 1 song.mid   # Nuked YM3438 (highest accuracy OPN2)
midra opn -e 2 song.mid   # GENS
midra opn -e 3 song.mid   # YMFM OPN2
midra opn -e 4 song.mid   # NP2 OPNA (PC-98 sound)
midra opn -e 5 song.mid   # MAME YM2608 OPNA
midra opn -e 6 song.mid   # YMFM OPNA
```

> **Tip:** Emulators 0--3 emulate the YM2612 (OPN2) and produce the Sega Genesis sound. Emulators 4--6 emulate the YM2608 (OPNA) and produce the NEC PC-98 sound character.

### Chip count (polyphony)

```bash
midra opn -c 1 song.mid   # 1 chip = 6 FM channels
midra opn -c 4 song.mid   # 4 chips = 24 FM channels (default)
```

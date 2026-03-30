# Midiraja (midra)

**Midiraja** (command: `midra`) is a terminal-native MIDI player for command-line use.

## Features

- **Terminal-native MIDI player** — from minimal line output to a fully interactive TUI with 16-channel VU meters and live controls for speed, transpose, and volume
- **Flexible synthesis** — route to OS MIDI ports (CoreMIDI, Windows GS, ALSA) or hardware synthesizers; or synthesize internally using built-in engines (OPL2/OPL3 FM, OPN2, PSG/MSX+SCC, 1-bit, GUS patches, TinySoundFont); or link to external engines (FluidSynth, Munt/Roland MT-32)
- **VGM chiptune playback** — plays VGM/VGZ files by converting chip register streams to MIDI in real time. Supports SN76489, YM2612, YM2151, YM2203/2608/2610, AY-3-8910, SCC, HuC6280, and Game Boy DMG
- **Retro hardware simulation (`--retro`)** — reconstructs the DAC quantization, analog filtering, and speaker acoustics of vintage hardware: Amiga Paula, Compact Mac, ZX Spectrum beeper, IBM PC Speaker, Covox, and more
- **DSP effects rack** — tube saturation, stereo chorus, algorithmic reverb (room/hall/plate/chamber/spring/cave), 3-band EQ, LPF/HPF, compressor — applies to all built-in engines
- **Vintage speaker simulation (`--speaker`)** — models the acoustic frequency response of physical speaker enclosures: tin-can, warm-radio, telephone, pc
- **Playlist support** — M3U files, recursive directory scan, shuffle, loop

---

## Installation

### Supported Platforms

| OS | Architecture | Native (`midra`) |
|---|---|---|
| macOS | Apple Silicon (arm64) | Available |
| Linux | amd64 / arm64 | Available (experimental) |
| Windows | amd64 | Available (experimental) |

> **Linux & Windows** builds are provided but have received limited real-world testing. Please [open an issue](https://github.com/fupfin/midiraja/issues) if you encounter problems.

### macOS & Linux

```bash
curl -sL https://raw.githubusercontent.com/fupfin/midiraja/main/install.sh | bash
```

Install to a custom prefix (e.g. `/usr/local`):

```bash
curl -sL https://raw.githubusercontent.com/fupfin/midiraja/main/install.sh | bash -s -- --prefix /usr/local
```

> **Linux prerequisite:** ALSA is required for audio output.
> `sudo apt install libasound2` (Debian/Ubuntu) · `sudo dnf install alsa-lib` (Fedora/RHEL)

### Windows (PowerShell)

```powershell
irm https://raw.githubusercontent.com/fupfin/midiraja/main/install.ps1 | iex
```

### Manual Download

Download the latest release from the [Releases](https://github.com/fupfin/midiraja/releases) page:

- `midra-darwin-arm64.tar.gz` — macOS Apple Silicon
- `midra-linux-amd64.tar.gz` — Linux x86_64
- `midra-linux-arm64.tar.gz` — Linux ARM64
- `midra-windows-amd64.zip` — Windows x86_64

Extract and place `midra` (or `midra.exe`) somewhere on your `PATH`.

---

## Quick Start

```bash
# Tour all synthesis engines — no setup needed
midra demo

# Play immediately — FreePats wavetable is bundled, no setup needed
midra patch song.mid

# Play with SoundFont — bundled FluidR3 GM SF3, no setup needed
midra soundfont song.mid

# Play with a custom SoundFont
midra soundfont ~/soundfonts/FluidR3_GM.sf2 song.mid

# Route to hardware (Roland SC-55, Yamaha MU100, etc.)
midra device song.mid
```

### Engine quick-pick

| I want … | Command |
|----------|---------|
| Best quality, no setup | `midra soundfont song.mid` |
| GUS wavetable, no setup | `midra patch song.mid` |
| Roland MT-32 (LucasArts / Sierra adventures) | `midra mt32 ~/roms/ song.mid` |
| Hardware synth or OS MIDI port | `midra device song.mid` |
| Retro chip sound | see below |
| VGM chiptune files (.vgm/.vgz) | `midra vgm song.vgz` |

**Built-in chip emulation engines** — zero-setup, no external files needed:

| Chip | Era | Command |
|------|-----|---------|
| OPL3 (AdLib / Sound Blaster) | DOS games (DOOM, TIE Fighter) | `midra opl song.mid` |
| OPN2 (Sega Genesis / PC-98) | Console / Japanese PC | `midra opn song.mid` |
| PSG (MSX / ZX Spectrum / Atari ST) | 8-bit home computers | `midra psg song.mid` |
| 1-bit (Apple II / PC Speaker) | Extreme lo-fi | `midra 1bit song.mid` |

**VGM chiptune playback** — plays .vgm/.vgz files by converting chip events to MIDI:

| Chip | Platform | Command |
|------|----------|---------|
| SN76489 + YM2612 | Sega Genesis | `midra vgm sonic.vgz` |
| YM2151 | Arcade (CPS1, System 16) | `midra vgm streetfighter.vgz` |
| YM2203 / YM2608 / YM2610 | PC-88 / PC-98 / Neo Geo | `midra vgm game.vgz` |
| AY-3-8910 + SCC | MSX | `midra vgm nemesis.vgz` |
| HuC6280 | PC Engine | `midra vgm rtype.vgz` |
| Game Boy DMG | Game Boy | `midra vgm pokemon.vgz` |

**`--retro` post-processing** — vintage hardware audio simulation applied on top of any engine:

| Mode | Hardware modelled | Example |
|------|-------------------|---------|
| `--retro amiga` / `--retro a500` | Amiga 500 Paula DAC + RC + LED filter | `midra opl --retro amiga song.mid` |
| `--retro a1200` | Amiga 1200 AGA DAC | `midra opn --retro a1200 song.mid` |
| `--retro compactmac` | Compact Mac PWM + 2-inch speaker | `midra psg --retro compactmac song.mid` |
| `--retro spectrum` | ZX Spectrum beeper | `midra 1bit --retro spectrum song.mid` |
| `--retro pc` | IBM PC internal speaker | `midra patch --retro pc song.mid` |
| `--retro covox` | Covox Speech Thing (R-2R DAC) | `midra soundfont --retro covox song.mid` |

See the full engine and retro guide in [docs/user_guide.md](docs/user_guide.md).

---

## Documentation

- [User Guide](docs/user_guide.md) — full engine reference, DSP effects, keyboard controls
- [Engineering Documentation](docs/engineering.md) — index of all technical whitepapers

---

## Motivation

This project started from three personal goals:

1. I own several retro MIDI tone generators (SC-55, SC-88, MT-32, MU100 and similar) and wanted a simple CLI player to audition MIDI files through them. Nothing that existed quite fit the workflow.

2. I needed a personal project of realistic scope to practice coding with AI agents.

3. Modern Java has been evolving toward leaner syntax and a lighter runtime — Project Loom, the Foreign Function & Memory API, GraalVM native image. I wanted a concrete target to validate how far that has come.

---

## License & Credits

Midiraja is licensed under the [BSD 3-Clause License](LICENSE). See [NOTICES.md](NOTICES.md) for third-party library attributions.

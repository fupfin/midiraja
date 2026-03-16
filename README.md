# Midiraja (midra)

**Midiraja** (command: `midra`) is a terminal-native MIDI player built for command-line use.

## Features

- **Retro synthesizer engines** — OPL2/OPL3 FM, OPN2, Gravis Ultrasound, PSG/MSX+SCC, 1-bit — all embedded in the binary, no external libraries needed
- **SoundFont playback** — built-in TinySoundFont (`soundfont`) engine plays `.sf2`/`.sf3` files with no FluidSynth installation; full DSP effects rack supported
- **External MIDI routing** — send events to OS native ports (CoreMIDI, Windows GS, ALSA) or hardware synthesizers via USB/MIDI
- **High-fidelity emulation** — optionally links against FluidSynth (SoundFont) or Munt (Roland MT-32) if installed on the system
- **Live playback control** — adjust speed, transpose key, and master volume while playing
- **DSP effects rack** — tube saturation, stereo chorus, algorithmic reverb (room/hall/plate/chamber/spring/cave), 3-band EQ, LPF/HPF — applies to any built-in engine
- **Three TUI modes** — full-screen dashboard with 16-channel VU meters (`--full`), compact single-line widget (`--mini`), plain console output (`--classic`)
- **Playlist support** — M3U files, recursive directory scan, shuffle, loop

---

## Quick Start

```bash
# Install (macOS & Linux)
curl -sL https://raw.githubusercontent.com/fupfin/midiraja/main/install.sh | bash

# Play immediately — FreePats wavetable is bundled, no setup needed
midra patch song.mid

# Play with SoundFont — bundled MuseScore General SF3, no setup needed
midra soundfont song.mid

# Play with a custom SoundFont
midra soundfont ~/soundfonts/FluidR3_GM.sf2 song.mid

# Route to hardware (Roland SC-55, Yamaha MU100, etc.)
midra device song.mid
```

### Engine quick-pick

| I want … | Command |
|----------|---------|
| Best quality, no setup | `midra patch song.mid` |
| SoundFont playback, no setup | `midra soundfont song.mid` |
| Retro hardware emulation | see below |
| Roland MT-32 (LucasArts / Sierra adventures) | `midra mt32 ~/roms/ song.mid` |
| Hardware synth or OS MIDI port | `midra device song.mid` |

**Retro emulation engines** — all zero-setup, no external files needed:

| Chip | Era | Command |
|------|-----|---------|
| OPL3 (AdLib / Sound Blaster) | DOS games (DOOM, TIE Fighter) | `midra opl song.mid` |
| OPN2 (Sega Genesis / PC-98) | Console / Japanese PC | `midra opn song.mid` |
| PSG (MSX / ZX Spectrum / Atari ST) | 8-bit home computers | `midra psg song.mid` |
| 1-bit (Apple II / PC Speaker) | Extreme lo-fi | `midra 1bit song.mid` |

See the full engine guide in [docs/quickstart.md](docs/quickstart.md).

---

## Installation

### Supported Platforms

| OS | Architecture | Native (`midra`) |
|---|---|---|
| macOS | Apple Silicon (arm64) | Available |
| Linux | amd64 / arm64 | Available (experimental) |
| Windows | amd64 | Available (experimental) |
| Windows | arm64 | Available (experimental) |

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

Install from a locally downloaded zip (e.g. a CI artifact):

```powershell
irm https://raw.githubusercontent.com/fupfin/midiraja/main/install.ps1 -OutFile install.ps1
Unblock-File .\install.ps1
.\install.ps1 -Local .\midra-windows-amd64.zip
```

Restart your terminal after installation, then run `midra --help`.

### Manual Download

Download the latest release from the [Releases](https://github.com/fupfin/midiraja/releases) page:

- `midra-darwin-arm64.tar.gz` — macOS Apple Silicon
- `midra-linux-amd64.tar.gz` — Linux x86_64
- `midra-linux-arm64.tar.gz` — Linux ARM64
- `midra-windows-amd64.zip` — Windows x86_64

Extract and place `midra` (or `midra.exe`) somewhere on your `PATH`.

---

## Documentation

- [Quick Start Guide](docs/quickstart.md) — install and play in 2 minutes
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

# Midiraja (midra)

**Midiraja** (command: `midra`) is a terminal-native MIDI player built for command-line use.

## Features

- **Retro synthesizer engines** — OPL2/OPL3 FM, OPN2, Gravis Ultrasound, PSG/MSX+SCC, 1-bit — all embedded in the binary, no external libraries needed
- **External MIDI routing** — send events to OS native ports (CoreMIDI, Windows GS, ALSA) or hardware synthesizers via USB/MIDI
- **High-fidelity emulation** — optionally links against FluidSynth (SoundFont) or Munt (Roland MT-32) if installed on the system
- **Live playback control** — adjust speed, transpose key, and master volume while playing
- **DSP effects rack** — tube saturation, stereo chorus, algorithmic reverb (room/hall/plate/chamber/spring/cave), 3-band EQ, LPF/HPF — applies to any built-in engine
- **Three TUI modes** — full-screen dashboard with 16-channel VU meters (`--full`), compact single-line widget (`--mini`), plain console output (`--classic`)
- **Playlist support** — M3U files, recursive directory scan, shuffle, loop

---

## Installation

### Supported Platforms

| OS | Architecture | Native (`midra`) | Cross-platform JAR (`midrax`) |
|---|---|---|---|
| macOS | Apple Silicon (arm64) | Available | Available |
| macOS | Intel (amd64) | Coming soon | Available |
| Linux | amd64 / arm64 | Coming soon | Available |
| Windows | amd64 | Coming soon | Available |

### Option 1: Curl (macOS Apple Silicon)

```bash
curl -sL https://raw.githubusercontent.com/fupfin/midiraja/main/install.sh | bash
```

Install to a custom prefix (e.g. `/usr/local`):

```bash
curl -sL https://raw.githubusercontent.com/fupfin/midiraja/main/install.sh | bash -s -- --prefix /usr/local
```

### Option 2: Manual Download

Download the latest release from the [Releases](https://github.com/fupfin/midiraja/releases) page:

- `midra-darwin-arm64.tar.gz` — macOS Apple Silicon native binary
- `midrax-vX.Y.Z.zip` — Cross-platform JAR (requires Java 25+)

Extract and place `midra` somewhere on your `PATH`.

---

## Documentation

- [Getting Started Guide](docs/user_guide.md)
- [1-Bit Audio Engineering](docs/beep-1bit-audio-engineering.md)
- [DSP Pipeline Architecture](docs/dsp-pipeline-engineering.md)
- [MT-32 Integration](docs/mt32_integration.md)

---

## Motivation

This project started from three personal goals:

1. I own several retro MIDI tone generators (SC-55, SC-88, MT-32, MU100 and similar) and wanted a simple CLI player to audition MIDI files through them. Nothing that existed quite fit the workflow.

2. I needed a personal project of realistic scope to practice coding with AI agents.

3. Modern Java has been evolving toward leaner syntax and a lighter runtime — Project Loom, the Foreign Function & Memory API, GraalVM native image. I wanted a concrete target to validate how far that has come.

---

## License & Credits

Midiraja is licensed under the [BSD 3-Clause License](LICENSE). See [NOTICES.md](NOTICES.md) for third-party library attributions.

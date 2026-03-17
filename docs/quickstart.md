# Midiraja Quick Start

Get from zero to playing MIDI in about 2 minutes.

---

## 1. Install

**macOS & Linux:**
```bash
curl -sL https://raw.githubusercontent.com/fupfin/midiraja/main/install.sh | bash
```

> **Linux:** ALSA must be present: `sudo apt install libasound2` (Debian/Ubuntu) or `sudo dnf install alsa-lib` (Fedora/RHEL)

**Windows (PowerShell):**
```powershell
irm https://raw.githubusercontent.com/fupfin/midiraja/main/install.ps1 | iex
```

Restart your terminal, then verify:
```bash
midra --help
```

---

## 2. Try the demo tour

The fastest way to hear what Midiraja can do — no MIDI files needed:

```bash
midra demo
```

This plays a 10-track curated playlist, one track per built-in engine: SoundFont, GUS patches, OPL3 FM, OPN2 FM, PSG chip, and 1-bit beep — all back-to-back. A transition screen with a 5-second countdown appears before each track; press `Enter` to skip ahead or `Q` to quit.

See the full demo reference in the [User Guide](user_guide.md#22-try-the-demo-tour).

---

## 3. Play your first file

You don't need to configure anything. Find any `.mid` file and run:

```bash
midra patch song.mid
```

The `patch` engine uses the FreePats wavetable set — bundled with Midiraja, no downloads needed. For something more retro, try `midra 1bit song.mid`.

---

## 4. Choose an engine

| I want … | Command |
|----------|---------|
| Best quality, no setup | `midra patch song.mid` |
| SoundFont playback, no setup | `midra soundfont song.mid` |
| Retro hardware emulation | see below |
| Roland MT-32 (LucasArts / Sierra) | `midra mt32 ~/roms/ song.mid` |
| Route to hardware synth | `midra device song.mid` |

**Retro emulation engines** — all zero-setup, no external files needed:

| Chip | Era | Command |
|------|-----|---------|
| OPL3 (AdLib / Sound Blaster) | DOS games (DOOM, TIE Fighter) | `midra opl song.mid` |
| OPN2 (Sega Genesis / PC-98) | Console / Japanese PC | `midra opn song.mid` |
| PSG (MSX / ZX Spectrum / Atari ST) | 8-bit home computers | `midra psg song.mid` |
| 1-bit (Apple II / PC Speaker) | Extreme lo-fi | `midra 1bit song.mid` |

The `patch` and `soundfont` engines bundle their instrument data (FreePats and FluidR3 GM SF3 respectively) — no downloads needed. To use a custom SoundFont:
```bash
midra soundfont ~/soundfonts/FluidR3_GM.sf2 song.mid
```

---

## 5. Keyboard controls

Once a song is playing, your terminal is interactive:

| Key | Action |
|-----|--------|
| `Up` / `Down` | Next / previous track |
| `Left` / `Right` | Seek ±10 seconds |
| `+` / `-` | Volume up / down |
| `>` / `<` | Speed up / slow down |
| `Space` | Pause / resume |
| `3` / `2` / `1` | Full dashboard / mini bar / classic text |
| `q` | Quit |

---

## 6. Common options

```bash
# Start at 1:30, play 1.5× speed, loop forever
midra opl --start 01:30 --speed 1.5 --loop song.mid

# Shuffle a whole folder
midra soundfont file.sf2 --shuffle --loop ~/midi/

# Add reverb and tube warmth
midra opl --reverb hall --tube 20 song.mid

# Play a DOOM-era MIDI with the DOOM instrument bank
midra opl -b 14 e1m1.mid
```

---

## Next steps

- [User Guide](user_guide.md) — full engine reference, DSP effects, playlists, and all options
- `midra --help` / `midra opl --help` — built-in help for every subcommand

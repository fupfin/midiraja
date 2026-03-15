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

## 2. Play your first file

You don't need to configure anything. Find any `.mid` file and run:

```bash
midra patch song.mid
```

The `patch` engine uses the FreePats wavetable set — bundled with Midiraja, no downloads needed. For something more retro, try `midra 1bit song.mid`.

---

## 3. Choose an engine

| I want … | Command | External file? |
|----------|---------|----------------|
| Best quality, no setup | `midra patch song.mid` | None (FreePats bundled) |
| Retro beeper sound, no setup | `midra 1bit song.mid` | None |
| Classic DOS sound (DOOM, AdLib) | `midra opl song.mid` | None |
| Sega Genesis / PC-98 sound | `midra opn song.mid` | None |
| 8-bit MSX / ZX Spectrum sound | `midra psg song.mid` | None |
| SoundFont playback (TinySoundFont, no install) | `midra soundfont file.sf2 song.mid` | `.sf2` file |
| Best SF2 compatibility / lowest latency | `midra fluidsynth file.sf2 song.mid` | FluidSynth + `.sf2` |
| Roland MT-32 (LucasArts / Sierra) | `midra mt32 ~/roms/ song.mid` | ROM files |
| Route to hardware synth | `midra device song.mid` | — |

**Getting a free SoundFont (`.sf2`) for the `soundfont` engine:**
- Ubuntu/Debian: `sudo apt install fluid-soundfont-gm` → `/usr/share/sounds/sf2/FluidR3_GM.sf2`
- macOS: `brew install fluid-soundfont-gm` → check `brew --prefix`
- Or download *GeneralUser GS* (free, search online)

---

## 4. Keyboard controls

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

## 5. Common options

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

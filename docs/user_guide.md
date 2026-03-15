# Midiraja (midra) User Guide

Welcome to the official user guide for **Midiraja**! This manual will walk you through everything from playing your first song to mastering the cycle-accurate retro synthesizers built into the engine.

---



## Chapter 1. Introduction

Midiraja (`midra`) is the ultimate **Terminal-Native** MIDI player. It is designed to bring a deeply interactive, visually rich audio experience directly into your command line—without ever touching a mouse.

**The Core Features:**
* **Built for Fun, Not Just Work:** Midiraja isn't a complex, sterile DAW for professionals. It’s built for hobbyists, retro-gamers, and CLI lovers who want a convenient, fun, and interactive way to explore MIDI files.
* **The Terminal as a Canvas:** We believe CLI tools can be beautiful. Midiraja features a lightning-fast Terminal UI (TUI) with real-time 16-channel VU meters, progress bars, and live keystroke controls for tempo and pitch.
* **The 3 Ways to View:** Choose how you see your music: a glorious full-screen dashboard, a single-line mini widget for background listening, or classic pipe-friendly logs.
* **The 3 Ways to Play:** Route your music anywhere. Send it to an external hardware keyboard, link it to modern C-libraries like FluidSynth, or synthesize it internally using built-in, zero-dependency retro engines.

---

## Chapter 2. Getting Started

### 2.1. Installation
Midiraja is distributed as a single, standalone native program. You do not need to install Java or any complex audio drivers to get started.

**macOS & Linux — Install Script:**

The installer auto-detects your OS and architecture and places `midra` under `~/.local/bin` (or a prefix you specify):

```bash
curl -sL https://raw.githubusercontent.com/fupfin/midiraja/main/install.sh | bash
```

To install to a custom location (e.g., `/usr/local`):
```bash
curl -sL https://raw.githubusercontent.com/fupfin/midiraja/main/install.sh | bash -s -- --prefix /usr/local
```

> **Linux prerequisite:** Midiraja uses ALSA for audio output. Install the runtime library if it is not already present:
> * Debian / Ubuntu: `sudo apt install libasound2`
> * Fedora / RHEL: `sudo dnf install alsa-lib`

**Manual Download:**

Download the archive for your platform from the [GitHub Releases page](https://github.com/fupfin/midiraja/releases):

| Platform | Archive |
|----------|---------|
| macOS Apple Silicon | `midra-darwin-arm64.tar.gz` |
| Linux x86_64 | `midra-linux-amd64.tar.gz` |
| Linux ARM64 | `midra-linux-arm64.tar.gz` |

Extract the archive and run the bundled `install.sh`, or manually copy the `bin/midra` binary somewhere on your `PATH` and set `LD_LIBRARY_PATH` to the `lib/` directory.

*(Windows support is coming soon.)*

### 2.2. The 10-Second Magic Command
The biggest hurdle with playing MIDI files is usually hunting down "Patch Banks" or configuring ports. Midiraja solves this by including built-in mathematical sound generators!

To hear music immediately without any setup, find any `.mid` file on your computer and type:
```bash
midra 1bit song.mid
```
*(This instantly fires up the Terminal UI alongside our built-in "1-Bit Digital Cluster" engine, generating classic retro sound using pure mathematics!)*

---

## Chapter 3. Basic Usage & UI Controls

### 3.1. How to Play Files and Playlists
Midiraja is designed to handle your entire music collection. The basic grammar is always:
`midra [engine_name] [options] <files>`

Here are some ways to feed music to the player:
```bash
# Play a single file
midra song.mid

# Play an entire folder! Midiraja will find all the MIDI files inside.
midra ~/my_game_music/

# Make a random, endless jukebox by adding shuffle and loop flags
midra --loop --shuffle ~/my_game_music/

# Play a standard M3U playlist file
midra my_playlist.m3u
```

### 3.2. Launch Options (Presetting the Player)
You can tell Midiraja exactly how to start playing before the UI even opens:
* **`--start 01:30`**: Skip the boring intro and start exactly at 1 minute and 30 seconds.
* **`--speed 1.5`**: Play the track 1.5x faster (great for practicing!).
* **`--transpose 12`**: Shift the entire song up one full octave (12 semitones).
* **`--volume 50`**: Start quietly at 50% master volume.

### 3.3. Live Terminal Controls (TUI)
Once the music starts, your terminal becomes an interactive dashboard. You don't need to quit the app to change the music—just press these keys:

| Key | What it does |
|-----|--------|
| **`Up` / `Down`** | Instantly skip to the Next or Previous Track in your playlist. |
| **`Left` / `Right`** | Fast-forward or Rewind the current song by 10 seconds. |
| **`+` / `-`** | Turn the master volume Up or Down. |
| **`>` / `<`** | Make the song play Faster or Slower in real-time. |
| **`'` / `/`** | Transpose the musical key Up or Down (great for singers!). |
| **`Space`** | Pause or Resume the music. |
| **`1`, `2`, `3`**| Change how the app looks! `3` is the full dashboard, `2` is a tiny mini-bar, and `1` is classic scrolling text. |
| **`q`** | Stop the music and safely quit the program. |

---

### 3.4. Global Audio Effects (DSP Rack)
Midiraja features a modular audio processing pipeline. You can apply high-quality global effects to the built-in pure-math synthesizers (`fm`, `psg`, `1bit`, `patch`) by simply adding flags to your launch command.

> ⚠️ **Important Note on Compatibility:**
> Global DSP Effects are *only* available for Midiraja's internal synthesis engines. They cannot be applied when routing audio to external hardware (`device` command) or when using the dynamically linked external engine (`fluidsynth`), because in that case audio generation happens outside of Midiraja's DSP control loop.

All effect intensities are controlled via intuitive percentages (0-100%).

**1. Analog Tube Saturation (`--tube <0-100>`)**
Adds harmonic distortion and warmth by simulating an overdriven vacuum tube amplifier (using a `Math.tanh` waveshaper and auto-gain compensation).
* *Recommended for Warmth:* `--tube 15` (Rounds off harsh digital edges)
* *Recommended for Punch:* `--tube 40` (Fattens up drum kicks and basslines)
* *Example:* `midra opl --tube 20 song.mid`

**2. Stereo Chorus (`--chorus <0-100>`)**
Thickens the sound and spreads it across the stereo field using modulated delay lines. Perfect for 80s synth-pop vibes.
* *Example:* `midra opn --chorus 50 song.mid`

**3. Algorithmic Reverb (`--reverb <preset>`)**
Places the synthesizer inside a simulated 3D acoustic space (based on the legendary Freeverb algorithm).
* **Presets:** `room` (small/punchy), `chamber` (warm/dense studio), `hall` (lush/orchestral), `plate` (bright/metallic), `spring` (bouncy vintage amp), `cave` (massive/ambient).
* **Intensity:** Control the wet/dry mix using `--reverb-level <0-100>` (Default is 50).
* *Example:* `midra psg --reverb chamber --reverb-level 70 song.mid`

**4. Vintage Speaker Simulation (`--speaker <profile>`)**
Applies an `AcousticSpeakerFilter` that models the acoustic frequency response of vintage speaker hardware, reshaping the output to sound like it is coming from a specific physical cabinet.
* **Profiles:** `tin-can` (narrow-band, telephone-like; heavy high and low rolloff for a mid-forward, hollow character), `warm-radio` (AM radio warmth; gentle mid-forward coloration with soft bass rolloff).
* *Example:* `midra fluidsynth piano.sf2 --speaker tin-can song.mid`

> ⚠️ **Do not combine `--speaker` with `--retro`:**
> Every `--retro` mode already contains a physically accurate model of its hardware speaker — the Mac's 2-inch cone, the Spectrum's 22mm beeper, the IBM PC's paper cone. Adding `--speaker` on top applies a second filter stage with no physical basis, producing an over-filtered result that does not match any real hardware. Use `--speaker` only when not using `--retro`. For the full technical explanation, see [The Retro Hardware Audio Simulation reference](retro-audio-engineering.md#7-the---speaker-option-and-retro-modes).

**5. 3-Band EQ & Filters**
Sculpt the frequency response using precision RBJ Biquad filters.
* **EQ (0-100%):** `--bass`, `--mid`, `--treble` (Default is 50 for neutral. Set to 100 for maximum boost, 0 to cut completely).
* **Cutoffs (Hz):** `--lpf <freq>` (Low-pass, cuts high frequencies), `--hpf <freq>` (High-pass, cuts low frequencies).
* *Example:* `midra opl --bass 80 --treble 70 --hpf 300 song.mid` (Boosts lows/highs but cuts extreme sub-bass rumble).

### 3.5. Consistent Volume Across Engines

All of Midiraja's **built-in** engines (1-bit, PSG, FM, GUS patches, SoundFont) are calibrated to the same output level — roughly **−9 dBFS peak**. This means you can freely switch between engines for the same MIDI file without unexpected volume jumps.

This consistent baseline also matters for DSP effects: because every engine enters the `--tube` waveshaper and `--reverb` wet/dry mix at the same signal level, the distortion character and reverb tail length will behave predictably regardless of which engine you pick.

> **Note on FluidSynth:** This external engine manages its own audio output and is not subject to Midiraja's internal level calibration. You can use `--volume` to balance it manually against the built-in engines.

---

## Chapter 4. The 3 Ways to Play (Synthesizer Engines)

What makes Midiraja a "Museum of Computer Audio" is its ability to radically change how a song sounds. You choose the "Engine" by typing its name right after `midra`.

### Method A: OS MIDI Devices (`device`)
Midiraja can act as a pure sequencer (traffic director), sending the raw MIDI sheet music directly to your operating system's built-in synthesizer or out through a USB cable to a physical hardware keyboard!
* **Usage:** `midra device [device_id_or_name] song.mid`
* **Aliases:** `dev`
* **What happens:** If you don't provide a device ID, a friendly menu will pop up asking you to select which physical or virtual device you want to send the music to.
* **Examples:**
  * `midra device song.mid` (Opens interactive selection menu)
  * `midra device 1 song.mid` (Instantly routes audio to Device Index #1)
  * `midra device "Yamaha" song.mid` (Instantly routes audio to the device named "Yamaha")

*(Note: For convenience, you can also just type `midra song.mid` without any subcommand, and it will default to this OS Device behavior!)*

### Method B: Built-in Retro Engines (Zero-Dependency)
These engines are baked directly into the Midiraja app. They require **absolutely zero external files or downloads**.

#### 1. 1-Bit Digital Cluster (`1bit` or `beep`)
* **What is it?** Back in 1981, computers like the Apple II didn't have sound cards; they just had a tiny beeper that could only be turned ON or OFF (1-Bit). This engine mathematically simulates that incredibly harsh, gritty, and charming sound constraint.
* **How to use it:** `midra 1bit song.mid`
* **🎛️ Advanced Options:**
  * `--synth <mode>`: Choose the waveform generation method.
    * `fm` *(Default)*: Smooth, Yamaha-style Phase Modulation. Sounds like a retro electric piano, clarinet, or bell.
    * `square`: The classic "Nintendo" 8-bit sound. Automatically applies a subtle LFO vibrato to simulate authentic 1980s chiptune tracking.
    * `xor`: A harsh, metallic Ring Modulation generator. Replicates the aggressive, buzzing synth-bass pioneered by legendary composer Tim Follin.
  * `--mux <mode>`: Choose the "Multiplexer" (how multiple notes are squished into a single 1-bit speaker pin).
    * `xor` *(Default)*: Historic 1981 Apple II logic. It crashes the notes together using a boolean Exclusive-OR gate. This causes beautiful, gritty phase-cancellations (intermodulation).
    * `tdm`: A modern "cheat". Switches the speaker between notes at 1.4 million times a second. Sounds incredibly clean with zero distortion.
  * `--voices <1-4>`: How many notes a single "virtual speaker" is allowed to play. Set this to `2` with `--mux xor` to perfectly emulate the exact physical limits of early 80s hardware!
  * `--fm-ratio <float>` & `--fm-index <float>`: When using `--synth fm`, these tweak the mathematics of the sound. `Ratio 1.0 / Index 2.5` sounds like a bright keyboard. `Ratio 3.5 / Index 1.8` sounds like a crystal bell.
* **Example:** `midra 1bit --synth square --mux xor --voices 2 song.mid` (Hardcore 1981 Apple II mode!)

#### 2. Programmable Sound Generator (`psg` or `msx`)
* **What is it?** Replicates the Yamaha YM2149F/AY-3-8910 chips found in 8-bit computers like the MSX, Atari ST, and ZX Spectrum. This engine uses "Tracker Hacks" to simulate polyphony and complex bass via arpeggios and high-speed hardware envelope modulation.
* **How to use it:** `midra psg song.mid`
* **🎛️ Advanced Options:**
  * `--chips <1-16>`: How many virtual PSG systems to instantiate. Default is `4`.
  * `--scc`: **The Konami Expansion!** Enabling this pairs every PSG chip with a 5-channel Konami SCC (K051649) wavetable chip. 
    * In this mode, drums stay on the PSG (for noise) while melodies move to the richer SCC wavetables.
    * Example: `midra psg --scc --chips 1` simulates a classic MSX machine with one SCC cartridge (8 channels total).
  * `--vibrato <0-100>`: Depth of the delayed software vibrato in parts per mille. Default: `5.0`.
  * `--duty-sweep <0-100>`: Width of the pulse-width modulation sweep (Fake FM). Default: `25.0`.

#### 3. FM Synthesis (`fm`)
* **What is it?** This perfectly replicates the famous Yamaha chips used in 1990s PC sound cards and Sega Genesis consoles. It gives everything that classic, twangy "DOOM" or "Sonic the Hedgehog" vibe.
* **How to use it:** `midra fm song.mid` (defaults to OPN2) or specify the chip as the first argument:
  * `midra fm opl song.mid` — OPL3 (AdLib / Sound Blaster PC DOS style)
  * `midra fm adlib song.mid` — same as above
  * `midra fm genesis song.mid` — OPN2 (Sega Genesis style)
  * `midra fm pc98 song.mid` — OPN2/OPNA (NEC PC-98 style)
* **Shortcuts:** `midra opl`, `midra opn`, `midra adlib`, `midra genesis`, `midra pc98` are all equivalent shortcuts.
* **🎛️ Advanced Options:**
  * `-b` or `--bank <bank>`: Changes the instrument "Bank". FM synthesis doesn't use real samples; it uses mathematical recipes to fake instruments. Different games used different recipes!
    * `-b 14` : The legendary DOOM (1993) soundbank. Heavy, twangy, and dark.
    * `-b 58` : Duke Nukem 3D soundbank.
    * `-b 0` : A standard, balanced General MIDI bank.
    * *(You can also provide a direct file path to your own `.wopl` or `.wopn` bank file!)*
  * `-e` or `--emulator <id>`: Switch the underlying emulation core (see `midra fm --help` for IDs).
* **Example:** `midra fm opl -b 14 song.mid` (Plays the song using the DOOM instrument bank!)

#### 4. GUS Patches (`patch`)
* **What is it?** In the mid-90s, the GUS card revolutionized PC audio by playing back actual recorded audio samples (wavetables) instead of synthesizing them.
* **How to use it:** `midra patch song.mid` (Auto-downloads a 27MB patch set on first run). Aliases: `gus`, `pat`, `guspatch`.
* **🎛️ Advanced Options:**
  * `<patch-dir>`: Optional first argument — a directory containing GUS `.pat` files. If a MIDI file path is given instead, FreePats is used automatically. Example: `midra patch ~/patches/eawpats song.mid`
  * `--1bit <mode>`: Chooses a 1-bit modulation strategy to simulate PC Speaker output.
    * `pwm` *(Default for RealSound)*: 32x oversampled Pulse Width Modulation. Inherently acts as a 6.5-bit DAC due to the 15.2kHz carrier restriction. Gritty and aliased, perfect for authentic 1989-style retro sound.
    * `dsd`: 32x oversampled Delta-Sigma Modulation with TPDF Dither. Audiophile-grade 1-bit sound with zero aliasing and warm analog hiss.
    * `tdm`: 32x oversampled Time-Division Multiplexing (Randomized switching).
  * `--realsound`: Turns on a mathematical simulation of the 1980s "RealSound" technique. It completely destroys the pristine wavetable audio and forces it out through a simulated 15.2kHz PWM PC Speaker, making it sound exactly like it's coming from a tiny, overloaded 1989 desktop computer chassis. (Equivalent to `--1bit pwm`).
* **Example:** `midra patch --realsound song.mid` (Simulates extreme low-fidelity retro hardware)

#### 5. Built-in SoundFont (`soundfont`)
* **What is it?** Plays standard SoundFont 2 and 3 (`.sf2` / `.sf3`) files using the embedded [TinySoundFont](https://github.com/schellingb/TinySoundFont) library — with **no FluidSynth installation required**. The synthesizer engine is bundled directly into the `midra` binary.
* **How to use it:** `midra soundfont /path/to/soundfont.sf2 song.mid`. Aliases: `tsf`, `sf2`, `sf`.
* **Where to get SoundFont files:** A SoundFont is a library of professionally recorded instrument samples. Good free options include:
  * *FluidR3 GM* — Ubuntu/Debian: `sudo apt install fluid-soundfont-gm`
  * *GeneralUser GS* — widely available as a free download
  * *MuseScore General* — bundled with MuseScore 4
* **🎛️ DSP Effects:** All standard effects are supported — `--tube`, `--chorus`, `--reverb`, EQ, LPF/HPF.
* **How it compares to `fluidsynth`:**

| | `soundfont` | `fluidsynth` |
|---|---|---|
| Requires separate install? | No (bundled) | Yes (`brew install fluid-synth`) |
| DSP effects (`--tube`, `--reverb` …) | ✅ | ❌ |
| Audio latency | Good | Lower — uses native OS audio driver (CoreAudio / ALSA) directly |
| SF2/SF3 compatibility | Most files work | Broader — supports advanced SF2 modulators and generators |
| Very large SoundFont files (1 GB+) | May use more memory | Handles efficiently (native streaming) |

* **Example:** `midra soundfont ~/soundfonts/FluidR3_GM.sf2 --reverb hall song.mid`

#### 6. Roland MT-32 (`mt32`)
* **What is it?** The "Holy Grail" of early 90s adventure game audio. If you ever played Monkey Island or King's Quest, this is the magical synthesizer that originally powered them. Midiraja bundles [Munt](https://github.com/munt/munt) — the definitive open-source MT-32 emulator — so no separate installation is required.
* **ROM Requirements:** You must legally acquire MT-32 ROM files and place them in a single directory (e.g., `MT32_CONTROL.ROM` and `MT32_PCM.ROM`). ROMs from CM-32L and LAPC-I variants are also supported.
* **How to use it:** `midra mt32 ~/my_rom_folder/ monkey_island.mid`
* **🎛️ DSP Effects:** All standard effects are supported — `--tube`, `--chorus`, `--reverb`, EQ, LPF/HPF.

---

### Method C: Shared Library Linking (External Engines)
If you want ultra-realistic SoundFont playback and already have FluidSynth installed, Midiraja can link to it directly.

#### 1. FluidSynth (`fluidsynth`)
* **What is it?** The industry standard for playing `.sf2` (SoundFont) files. SoundFonts are massive libraries of professionally recorded real instruments.
* **Official Site:** [fluidsynth.org](https://www.fluidsynth.org/) - Please refer to their official documentation for detailed information on advanced tuning, internal settings, and driver support.

* **Installation:** FluidSynth is loaded dynamically at runtime. Install it via your package manager:
  * macOS: `brew install fluid-synth`
  * Ubuntu/Debian: `sudo apt install libfluidsynth3`
  * Fedora/RHEL: `sudo dnf install fluidsynth`
* **SoundFont Sources:** You must download an `.sf2` file. Good free options include:
  * *GeneralUser GS* (`generaluser.sf2`)
  * *MuseScore General* (Bundled with MuseScore 4)
  * *FluidR3 GM* (Ubuntu: `sudo apt install fluid-soundfont-gm`)
* **How to use it:** `midra fluidsynth /path/to/my_piano.sf2 song.mid`
* **🎛️ Advanced Options:**
  * `--driver <name>`: Override the audio driver used by FluidSynth (e.g., `coreaudio`, `pulseaudio`, `dsound`) if the default fails on your machine.
* **Example:** `midra fluidsynth --driver coreaudio my_piano.sf2 song.mid`

---

## Chapter 4 Supplement: Which Engine Should I Use?

Not sure which engine to pick? Use the decision table below.

| I want … | Best engine | Notes |
|-----------|-------------|-------|
| Play a MIDI file right now, no setup | `1bit` | Zero dependencies, instant start |
| Classic DOS game sound (DOOM, TIE Fighter) | `fm opl` | Add `-b 14` for DOOM bank; shortcut: `midra opl` |
| Sega Genesis / PC-98 game sound | `fm genesis` | OPN2/OPNA chip emulation; shortcuts: `midra opn`, `midra genesis`, `midra pc98` |
| 8-bit MSX / ZX Spectrum / Atari ST sound | `psg` | Add `--scc` for richer MSX sound |
| General MIDI with sample quality, no installs | `soundfont` + an SF2 file | Good fidelity, full DSP effects rack |
| Best possible SoundFont quality, low latency | `fluidsynth` + an SF2 file | Requires `brew install fluid-synth` |
| Early 90s LucasArts / Sierra adventure games | `mt32` + ROM files | Bundled Munt emulator, no install |
| Route to hardware synth / external device | `device` | Sends raw MIDI to OS ports |
| Maximum retro weirdness | `1bit --synth xor --mux xor` | Tim Follin–style buzzing bass |
| Extreme low-fi (PC Speaker "RealSound") | `patch --realsound` | 15 kHz PWM through a paper cone |

### Quick comparison: `soundfont` vs `fluidsynth`

Use **`soundfont`** when you want zero-install SoundFont playback plus DSP effects.
Use **`fluidsynth`** when you need the best SF2 compatibility, lower audio latency, or efficient handling of 1 GB+ SoundFont files.

### Quick comparison: retro engines

| Engine | Era / Hardware | Polyphony ceiling | External file? |
|--------|---------------|-------------------|----------------|
| `1bit` | 1981 Apple II / BBC Micro | 4 voices | None |
| `psg` | 1980s MSX / ZX Spectrum / Atari ST | 3–48 voices (× chips) | None |
| `fm opl` (`opl`) | 1990s AdLib / Sound Blaster | 9–18 FM operators | Optional `.wopl` bank |
| `fm genesis` (`opn`) | Sega Genesis / PC-98 | 6 FM + 3 SSG voices | Optional `.wopn` bank |
| `patch` (`gus`) | 1994 Gravis Ultrasound | 32 wavetable voices | FreePats (auto-download) |
| `soundfont` (`tsf`) | Modern SoundFont | Polyphonic (SF2 limit) | Required `.sf2`/`.sf3` |
| `mt32` | 1987 Roland MT-32 | 32 partial generators | Required ROM files |

---

## Chapter 4 Supplement: Sound Banks & Instrument Libraries

Several engines need an external file that defines what instruments should sound like. Here is a guide to the three types used in Midiraja.

---

### SoundFont (`.sf2` / `.sf3`) — used by `soundfont` and `fluidsynth`

A **SoundFont** is a container file that bundles thousands of recorded instrument notes — each one an actual audio sample — along with playback rules (pitch range, volume envelope, modulation). When a MIDI file says "play note C4 on instrument Grand Piano", the engine looks up the matching sample and plays it back at the right pitch.

**Free sources:**

| File | How to get it |
|------|---------------|
| *FluidR3 GM* | macOS: `brew install fluid-soundfont-gm` · Linux: `sudo apt install fluid-soundfont-gm` · Path: `/usr/share/sounds/sf2/FluidR3_GM.sf2` |
| *GeneralUser GS* | Free download — search "GeneralUser GS soundfont" |
| *MuseScore General* | Bundled with [MuseScore 4](https://musescore.org/) |

The same `.sf2` file works with both `soundfont` and `fluidsynth`.

---

### GUS Patch Set — used by `patch`

The original Gravis Ultrasound card stored instruments as individual `.pat` (patch) files on a hard drive — one file per General MIDI instrument. A **patch set** is a folder containing all 128 of these files.

Midiraja automatically downloads the [FreePats](https://freepats.zenvoid.org/) patch set on first run. To use a different (often higher-quality) patch set, download it and point `--patch-dir` at the folder:

```bash
midra patch ~/patches/eawpats song.mid
```

**Recommended patch sets:**

| Patch Set | Notes |
|-----------|-------|
| *FreePats* (default) | Downloaded automatically on first run. Decent quality, legally free. |
| *eawpats* (Eric A. Welsh) | Widely regarded as the best freely available GUS patches. |
| *Unison* / *Timbres of Heaven* | More modern, higher-quality alternatives. |

---

### FM Bank File (`.wopl` / `.wopn`) — used by `fm opl` and `fm genesis`

FM synthesis does not record real instruments — it configures a set of mathematical oscillators to *approximate* a sound. A **bank file** contains up to 128 such recipes, one per General MIDI instrument slot.

libADLMIDI ships with over 60 built-in banks from famous games (use `-b <number>`). To use a custom bank file you have downloaded or edited:

```bash
midra fm opl --bank /path/to/custom.wopl song.mid
midra fm genesis --bank /path/to/custom.wopn song.mid
```

**Notable built-in banks (select with `-b <number>`):**

| Number | Bank | Character |
|--------|------|-----------|
| `0` | General MIDI | Balanced, suitable for most MIDI files |
| `14` | DOOM (1993) | Heavy, twangy, dark |
| `58` | Duke Nukem 3D | Punchy, aggressive |

You can browse and edit bank files with the free [OPL3BankEditor](https://github.com/Wohlstand/OPL3BankEditor) (`.wopl`) or [OPN2BankEditor](https://github.com/Wohlstand/OPN2BankEditor) (`.wopn`) tools.

---

## Chapter 5. Powered by Open Source (Appendices)

Midiraja stands on the shoulders of giants. While our UI, rendering pipelines, and the 1-Bit engine are custom-built, we proudly integrate several legendary open-source emulation cores to bring you the best retro sound possible. We highly encourage you to check out and support these amazing projects!

### The Emulation Cores
* **[libADLMIDI](https://github.com/Wohlstand/libADLMIDI):** An incredible C++ library that emulates the Yamaha OPL3 (YMF262) chip. This is the heart of our `midra fm opl` engine.
* **[libOPNMIDI](https://github.com/Wohlstand/libOPNMIDI):** A sister project that emulates the Yamaha OPN2 (YM2612) chip found in the Sega Genesis. This powers our `midra fm genesis` engine.
* **[Munt (MT-32 Emulator)](https://github.com/munt/munt):** The definitive, cycle-accurate software synthesizer replicating the Roland MT-32 and CM-32L hardware. The Munt library (`libmt32emu`) is bundled in the Midiraja release package and powers the `midra mt32` command.
* **[FluidSynth](https://www.fluidsynth.org/):** The world's leading real-time software synthesizer based on the SoundFont 2 specifications. We link to this for the `midra fluidsynth` command.

### Technical Documentation
If you are an audio engineer or a hardcore retro programming enthusiast, you might enjoy reading our deep-dive technical papers on how we built our custom engines:
* **[🎛️ The Global DSP Pipeline Architecture](dsp-pipeline-engineering.md)**: Explains the math and architecture behind our zero-allocation audio effect rack (Reverb, Chorus, Tube Saturation, EQ).
* **[🤖 The 1-Bit Audio Engineering Whitepaper](beep-1bit-audio-engineering.md)**: Discover the strict integer mathematics, fixed-point logic, and AI-driven DSP algorithms that power our zero-dependency `beep` engine.
* **[👾 The PSG Tracker Hacks Whitepaper](psg-tracker-engineering.md)**: The architectural blueprint for our Programmable Sound Generator (`psg`) engine, detailing the legendary software tricks (Fast Arpeggios, Envelope Buzzer) used by MSX and ZX Spectrum hackers.
* **[📻 The RealSound PWM Whitepaper](realsound-pwm-engineering.md)**: A deep dive into time-domain quantization and how we accurately recreated the legendary 1980s PC Speaker "RealSound" output using Delta-Sigma modulation.
* **[💾 The FM Synthesis Whitepaper](fm-synthesis-engineering.md)**: Details the architectural lock-free design and DSP volume normalization required to accurately render Yamaha OPL and OPN chips.
* **[🌉 The Native Bridge Architecture](native-bridge-engineering.md)**: Explores the three distinct architectural patterns (Queue-and-Drain, Wall-Clock Sync, and Driver Delegation) used to safely bind C/C++ audio engines like FluidSynth and Munt to the JVM using the modern FFM API.
* **[🎹 The MT-32 Integration Architecture](mt32_integration.md)**: A highly technical reference on integrating the C++ Munt emulator via Java's modern FFM API, including thread pacing, wall-clock MIDI event synchronization, and CoreAudio hardware latency calculations.

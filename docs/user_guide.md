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

**For macOS (The Easiest Way):**
If you use Homebrew, simply run:
```bash
brew tap YOUR_GITHUB_USERNAME/tap
brew install midra
```

**For Linux & macOS (Quick Script):**
You can also use this one-liner to download the app directly:
```bash
curl -sL https://raw.githubusercontent.com/YOUR_GITHUB_USERNAME/midiraja/master/install.sh | bash
```

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

#### 2. AdLib / Sound Blaster FM (`opl` & `opn`)
* **What is it?** This perfectly replicates the famous Yamaha chips used in 1990s PC sound cards and Sega Genesis consoles. It gives everything that classic, twangy "DOOM" or "Sonic the Hedgehog" vibe.
* **How to use it:** `midra opl song.mid` (PC DOS style) or `midra opn song.mid` (Sega Genesis style).
* **🎛️ Advanced Options:**
  * `-b` or `--bank <bank>`: Changes the instrument "Bank". FM synthesis doesn't use real samples; it uses mathematical recipes to fake instruments. Different games used different recipes!
    * `-b 14` : The legendary DOOM (1993) soundbank. Heavy, twangy, and dark.
    * `-b 58` : Duke Nukem 3D soundbank.
    * `-b 0` : A standard, balanced General MIDI bank.
    * *(You can also provide a direct file path to your own `.wopl` or `.wopn` bank file!)*
  * `-e` or `--emulator <name>`: Switch the underlying emulation core.
    * `nuked` *(Default)*: Cycle-accurate emulation of the Yamaha chip. Heaviest on the CPU, but mathematically perfect.
    * `dosbox`: The classic, fast, and slightly imperfect emulator used in DOSBox.
* **Example:** `midra opl -b 14 song.mid` (Plays the song using the DOOM instrument bank!)

#### 3. Gravis Ultrasound (`gus`)
* **What is it?** In the mid-90s, the GUS card revolutionized PC audio by playing back actual recorded audio samples (wavetables) instead of synthesizing them.
* **How to use it:** `midra gus song.mid` (Auto-downloads a 27MB patch set on first run).
* **🎛️ Advanced Options:**
  * `-p` or `--patch-dir <path>`: Tell the engine to use a custom folder of GUS patches (like the famous `eawpats`) instead of the default downloaded ones.
  * `--bits <1-16>`: A built-in Bitcrusher effect! High-end CD audio is 16-bit. If you drop this to `8` or `6`, the engine intentionally mathematically degrades the audio, creating that crunchy, hissing, low-fidelity texture of early 90s DOS games.
  * `--realsound`: Turns on a mathematical simulation of the 1980s "RealSound" technique. It completely destroys the pristine wavetable audio and forces it out through a simulated Pulse-Width-Modulation (PWM) PC Speaker, making it sound exactly like it's coming from a tiny, overloaded 1989 desktop computer chassis.
* **Example:** `midra gus --bits 6 --realsound song.mid` (Simulates extreme low-fidelity retro hardware)

### Method C: Shared Library Linking (External Engines)
If you want ultra-realistic modern audio or perfect emulation of specific high-end retro gear, Midiraja can "link" to popular tools you may have already installed on your Mac or Linux machine.

#### 1. FluidSynth (`fluidsynth`)
* **What is it?** The industry standard for playing `.sf2` (SoundFont) files. SoundFonts are massive libraries of professionally recorded real instruments.
* **Official Site:** [fluidsynth.org](https://www.fluidsynth.org/) - Please refer to their official documentation for detailed information on advanced tuning, internal settings, and driver support.
* **Requirements:** You must download your own `.sf2` file.
* **How to use it:** `midra fluidsynth /path/to/my_piano.sf2 song.mid`
* **🎛️ Advanced Options:**
  * `--driver <name>`: Override the audio driver used by FluidSynth (e.g., `coreaudio`, `pulseaudio`, `dsound`) if the default fails on your machine.
* **Example:** `midra fluidsynth --driver coreaudio my_piano.sf2 song.mid`

#### 2. Roland MT-32 (`mt32`)
* **What is it?** The "Holy Grail" of early 90s adventure game audio. If you ever played Monkey Island or King's Quest, this is the magical synthesizer that originally powered them.
* **Official Site:** [Munt (MT-32 Emulator)](https://github.com/munt/munt) - Visit their repository for in-depth information on installation, ROM requirements, and compatibility lists.
* **Requirements:** Because of copyright laws, you must legally acquire your own "MT-32 ROM" files and place them in a folder. You also need to install the `munt` emulator via Homebrew.
* **How to use it:** `midra mt32 ~/my_rom_folder/ monkey_island.mid`

---

## Chapter 5. Powered by Open Source (Appendices)

Midiraja stands on the shoulders of giants. While our UI, rendering pipelines, and the 1-Bit engine are custom-built, we proudly integrate several legendary open-source emulation cores to bring you the best retro sound possible. We highly encourage you to check out and support these amazing projects!

### The Emulation Cores
* **[libADLMIDI](https://github.com/Wohlstand/libADLMIDI):** An incredible C++ library that emulates the Yamaha OPL3 (YMF262) chip. This is the heart of our `midra opl` engine.
* **[libOPNMIDI](https://github.com/Wohlstand/libOPNMIDI):** A sister project that emulates the Yamaha OPN2 (YM2612) chip found in the Sega Genesis. This powers our `midra opn` engine.
* **[Munt (MT-32 Emulator)](https://github.com/munt/munt):** The definitive, cycle-accurate software synthesizer replicating the Roland MT-32 and CM-32L hardware. We dynamically link to this library for the `midra mt32` command.
* **[FluidSynth](https://www.fluidsynth.org/):** The world's leading real-time software synthesizer based on the SoundFont 2 specifications. We link to this for the `midra fluidsynth` command.

### Technical Documentation
If you are an audio engineer or a hardcore retro programming enthusiast, you might enjoy reading our deep-dive technical papers on how we built our custom engines:
* **[🤖 The 1-Bit Audio Engineering Whitepaper](beep-1bit-audio-engineering.md)**: Discover the strict integer mathematics, fixed-point logic, and AI-driven DSP algorithms that power our zero-dependency  engine.
* **[👾 The PSG Tracker Hacks Whitepaper](psg-tracker-engineering.md)**: The architectural blueprint for our upcoming Programmable Sound Generator engine, detailing the legendary software tricks (Fast Arpeggios, Envelope Buzzer) used by MSX and ZX Spectrum hackers.: Discover the strict integer mathematics, fixed-point logic, and AI-driven DSP algorithms that power our zero-dependency `beep` engine.
* **[🎹 Soft Synth Setup Guide](soft-synth-guide.md)**: Detailed configuration instructions and environment variable settings for integrating external libraries like FluidSynth and Munt.

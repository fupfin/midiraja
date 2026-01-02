# Midiraja (midra) User Guide

Welcome to the official user guide for **Midiraja**! This manual will walk you through everything from playing your first song to mastering the cycle-accurate retro synthesizers built into the engine.

---

## Chapter 1. Introduction

Midiraja (`midra`) is the ultimate **Terminal-Native** MIDI player. It is designed to bring a deeply interactive, visually rich audio experience directly into your command line—without ever touching a mouse.

**The Core Features:**
* **Built for Fun, Not Just Work:** Midiraja isn't a complex, sterile DAW for professionals. It’s built for hobbyists, retro-gamers, and CLI lovers who want a convenient, fun, and interactive way to explore MIDI files.
* **The Terminal as a Canvas:** We believe CLI tools can be beautiful. Midiraja features a lightning-fast Terminal UI (TUI) with real-time 16-channel VU meters, progress bars, and live keystroke controls for tempo and pitch.
* **The 3 Ways to View:** Choose how you see your music: a glorious full-screen dashboard, a single-line mini widget for background listening, or classic pipe-friendly logs.
* **The 3 Ways to Play:** Route your music anywhere. Send it to an external hardware keyboard, link it to modern C-libraries like FluidSynth, or synthesize it internally using built-in, zero-dependency retro engines (like OPL or 1-Bit logic).

---

## Chapter 2. Getting Started

### 2.1. Installation
Midiraja is distributed as a single, standalone native binary. It does not require Java to be installed on your system.

**macOS (Homebrew):**
```bash
brew tap YOUR_GITHUB_USERNAME/tap
brew install midra
```

**Linux & macOS (Quick Script):**
```bash
curl -sL https://raw.githubusercontent.com/YOUR_GITHUB_USERNAME/midiraja/master/install.sh | bash
```

### 2.2. The 10-Second Magic Command
Don't want to configure anything? Download any `.mid` file from the internet and run:

```bash
midra beep song.mid
```
*(This instantly fires up the Terminal UI alongside a built-in 1-Bit retro engine, generating sound using pure mathematics!)*

---

## Chapter 3. Basic Usage & UI Controls

### 3.1. Syntax & Playlists
The standard syntax for Midiraja is:
`midra [subcommand] [options] <files/folders/playlists...>`

You can pass single files, entire directories, or standard `.m3u` playlist files:
```bash
midra *.mid                            # Play all MIDI files in the current folder
midra --loop --shuffle ~/my_music/     # Play a folder endlessly in a random order
midra my_playlist.m3u                  # Play an M3U playlist file
```

### 3.2. Launch Options
You can preset the environment before the music even starts:
* **`--start 01:30`**: Jump directly to 1 minute and 30 seconds.
* **`--speed 1.5`**: Play the track 1.5x faster.
* **`--transpose -12`**: Shift the entire song down one octave.
* **`--volume 50`**: Start at 50% master volume.

### 3.3. Terminal UI (TUI) & Live Controls
While music is playing, Midiraja captures your keystrokes to control the playback in real-time.

**Toggle Display Modes:**
* **`1` (Classic):** Standard console text. Good for CI or background piping.
* **`2` (Mini):** A compact, single-line progress bar.
* **`3` (Full):** The glorious full-screen dashboard with 16-channel VU meters.

**Live Playback Controls:**
* **`Up` / `Down`**: Next / Previous track in the playlist
* **`Left` / `Right`**: Seek backward / forward 10 seconds
* **`+` / `-`**: Increase / Decrease master volume
* **`>` / `<`**: Speed up / Slow down the tempo
* **`'` / `/`**: Transpose the key up or down
* **`Space`**: Pause / Resume playback
* **`q`**: Stop and exit

---

## Chapter 4. The 3 Ways to Play (Synthesizer Engines)

Midiraja's architecture allows you to route audio in three fundamentally different ways. You choose the method by adding a "subcommand" (like `beep` or `gus`) right after `midra`.

### Method A: OS MIDI Ports (No Subcommand)
If you don't provide a subcommand, Midiraja acts as a "sequencer." It sends raw MIDI signals directly to your operating system's built-in synth or a USB-connected physical keyboard.
* **Usage:** `midra song.mid`
* **Behavior:** A menu will pop up asking you to select a hardware/software port (e.g., Apple DLS, Windows GS Wavetable).

### Method B: Built-in Retro Engines (Zero-Dependency)
These engines are baked directly into the Midiraja binary. They calculate audio mathematically and require NO external files.

#### 1-Bit Digital Cluster (`beep`)
Emulates the raw, gritty, boolean-logic sound of the 1981 Apple II speaker using strict integer math.
* **Classic FM:** `midra beep --synth fm song.mid`
* **Hacker Mode:** `midra beep --synth square --mux xor --voices 2 song.mid`

#### AdLib / Sound Blaster FM (`opl` & `opn`)
Perfectly replicates the FM synthesis chips found in 90s DOS sound cards and Sega consoles. Includes DOOM and Duke Nukem banks built-in.
* **PC-DOS Style:** `midra opl -b 14 song.mid` (Plays using the DOOM soundbank)
* **Sega Genesis Style:** `midra opn song.mid`

#### Gravis Ultrasound (`gus`)
High-end 1990s wavetable synthesis. On its first run, it will automatically download a lightweight 27MB `freepats` patch set.
* **Standard:** `midra gus song.mid`
* **Retro Bitcrusher:** `midra gus --bits 6 --realsound song.mid` (Simulates lower-fidelity 6-bit PWM)

### Method C: Shared Library Linking (External Engines)
If you have industry-standard C-libraries installed on your system (via Homebrew/apt), Midiraja can dynamically link to them for modern rendering.

#### FluidSynth (`fluidsynth`)
The industry standard for SoundFont rendering.
* **Requires:** A `.sf2` file.
* **Usage:** `midra fluidsynth /path/to/my_piano.sf2 song.mid`

#### Roland MT-32 (`mt32`)
The "Holy Grail" of early PC gaming audio (Monkey Island, King's Quest).
* **Requires:** MT-32 ROM files (User must provide).
* **Usage:** `midra mt32 ~/path_to_roms/ monkey_island.mid`

---

## Chapter 5. Technical Documentation & Appendices

For the deeply curious engineers and retro-audio enthusiasts, we maintain extensive architectural documentation on how Midiraja achieves its exact historical sound constraints.

* **[🤖 1-Bit Audio Engineering Whitepaper](beep-1bit-audio-engineering.md)**: A deep dive into the strict purist mathematics, fixed-point logic, and AI-driven DSP algorithms powering the `beep` engine.
* **[🎹 Advanced Soft Synth Configuration](soft-synth-guide.md)**: Troubleshooting, environment variables, and deep configurations for FluidSynth, OPL, and Munt.

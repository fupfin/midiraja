# Midiraja (midra) 🎵

**Midiraja** (command: `midra`) is the ultimate **Terminal-Native** MIDI player. It brings a rich, interactive audio dashboard directly to your command line. 

Whether you want to quickly preview a `.mid` file, practice an instrument by changing the key and tempo on the fly, or just listen to a folder full of retro game soundtracks through historically accurate software synthesizers, `midra` makes it incredibly easy.

## ✨ The 3 Ways to Play
Midiraja isn't just a player; it's a universal audio router. It can output your MIDI files in three fundamentally different ways depending on your needs:

1. **External OS Devices (`device`):** Midiraja can act as a pure sequencer, routing raw MIDI events directly to your OS's native ports (like Apple CoreMIDI or Windows GS Wavetable) or to your own external hardware synthesizers connected via USB/MIDI.
2. **Built-in Retro Engines (Zero-Dependency):** Want instant retro sound without installing anything? Midiraja contains pure-mathematical emulators built directly into the binary. Enjoy Gravis Ultrasound (`gus`), AdLib FM (`opl`/`opn`), Programmable Sound Generator (`psg`/`msx`) with Konami SCC expansion, and a custom purist 1-Bit Digital Cluster (`1bit` / `beep`) instantly.
3. **Shared Library Linking:** If you have industry-standard C-libraries installed on your system (like `fluid-synth` or `munt`), Midiraja will dynamically link to them, allowing for ultra-high-fidelity SoundFont rendering or cycle-accurate Roland MT-32 emulation.

## 🖥️ The 3 Ways to View
Experience your music with three adaptive Terminal UI modes:
* **`--full` (`-3`):** A glorious full-screen dashboard featuring 16-channel VU meters, progress bars, and a dynamic playlist queue.
* **`--mini` (`-2`):** A compact, single-line status widget perfect for background listening while you work in another split terminal.
* **`--classic` (`-1`):** Standard, pipe-friendly console output.

## 🎛️ Live Playback Control
Change the playback speed, transpose the musical key, or tweak the master volume *while* the music is playing—and keep those settings persistent across an entire folder of files!

## 🎚️ Global Audio Effects (DSP)
Midiraja features a built-in, modular DSP effect rack that can be applied to almost any built-in synthesizer (`opl`, `opn`, `psg`, `gus`). Sculpt your sound before it reaches your speakers:
* **Tube Saturation (`--tube`)**: Adds analog warmth, harmonic distortion, and punch using mathematical waveshaping. (e.g. `--tube 20`)
* **Stereo Chorus (`--chorus`)**: Spreads the sound wide across the stereo field with swirling 80s detuning. (e.g. `--chorus 50`)
* **Algorithmic Reverb (`--reverb`)**: Places your retro synths in realistic 3D spaces (`room`, `hall`, `plate`, `chamber`, `spring`, `cave`). (e.g. `--reverb hall --reverb-level 80`)
* **3-Band EQ & Cutoffs**: Sculpt the frequencies with precision Biquad filters (`--bass`, `--mid`, `--treble`, `--lpf`, `--hpf`).

---

## 🚀 Quick Install (macOS & Linux)

### Option 1: macOS via Homebrew (Recommended)
```bash
brew tap YOUR_GITHUB_USERNAME/tap
brew install midra
```

### Option 2: Curl Script (Mac & Linux)
```bash
curl -sL https://raw.githubusercontent.com/YOUR_GITHUB_USERNAME/midiraja/master/install.sh | bash
```

*(For Windows or manual downloads, see the [Releases](https://github.com/YOUR_GITHUB_USERNAME/midiraja/releases) page).*

---

## 📖 Documentation & Usage

Ready to make some noise? Check out our user manuals:

* **[🚀 Getting Started Guide](docs/user_guide.md)**: Learn how to play your first song in 10 seconds, master the TUI live controls, and discover all the built-in retro synthesizer engines.
* **[🤖 1-Bit Audio Engineering Whitepaper](docs/beep-1bit-audio-engineering.md)**: A deep dive into the purist mathematics and historical hardware constraints behind our flagship `1bit` engine.
* **[🎹 Soft Synth Guide](docs/soft-synth-guide.md)**: Detailed configuration instructions for FluidSynth and MT-32 emulators.

---

## ⚖️ License & Credits
* **Midiraja** is licensed under the [BSD 3-Clause License](LICENSE).
* This project uses several open-source libraries. Please see [NOTICES.md](NOTICES.md) for full third-party license information and attributions.

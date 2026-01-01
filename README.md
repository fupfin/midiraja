# Midiraja (midra) 🎵

**Midiraja** (command: `midra`) is a lightning-fast, zero-dependency MIDI player for your terminal. 

Whether you want to quickly preview a `.mid` file, practice an instrument by changing the key and tempo on the fly, or just listen to a folder full of retro game soundtracks through historically accurate software synthesizers, `midra` makes it incredibly easy.

## ✨ Features
* **Rich Terminal UI**: Experience MIDI like never before with three adaptive display modes:
  * `--full` (`-3`): A glorious full-screen dashboard with 16-channel VU meters, progress bars, and a dynamic playlist.
  * `--mini` (`-2`): A compact, single-line status widget perfect for background listening.
  * `--classic` (`-1`): Standard, pipe-friendly console output.
* **Zero Config Retro Audio**: Comes built-in with mathematical software synthesizers (1-Bit, AdLib OPL, OPN2, GUS) that require zero external patches to start playing.
* **Live Controls**: Change playback speed, transpose the key, or tweak the volume *while* the music is playing—and keep those settings across the entire playlist!
* **Playlists**: Toss in a folder full of files. Shuffle, loop, and skip tracks seamlessly.

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

* **[🚀 Getting Started Guide](docs/getting-started.md)**: Learn how to play your first song in 10 seconds, master the TUI live controls, and discover all the built-in retro synthesizer engines.
* **[🤖 1-Bit Audio Engineering Whitepaper](docs/beep-1bit-audio-engineering.md)**: A deep dive into the purist mathematics and historical hardware constraints behind our flagship `beep` engine.
* **[🎹 Soft Synth Guide](docs/soft-synth-guide.md)**: Detailed configuration instructions for FluidSynth and MT-32 emulators.

---

## ⚖️ License & Credits
* **Midiraja** is licensed under the [BSD 3-Clause License](LICENSE).
* This project uses several open-source libraries. Please see [NOTICES.md](NOTICES.md) for full third-party license information and attributions.

# Midiraja (midra) 🎵

**Midiraja** (command: `midra`) is a lightweight, interactive MIDI player for your terminal. 

Whether you want to quickly preview a `.mid` file, practice an instrument by changing the key and tempo on the fly, or just listen to a folder full of retro game soundtracks, `midra` makes it incredibly easy.

## ✨ Features
* **Zero Config**: Automatically detects and plays through your native OS MIDI synthesizers (like Apple DLS Synth on macOS or ALSA/FluidSynth on Linux).
* **Interactive UI**: No need to memorize port numbers. Just run `midra` and use your arrow keys to select where the sound should go.
* **Live Controls**: Change the playback speed, transpose the key, or tweak the volume *while* the music is playing.
* **Playlists**: Toss in a bunch of files. Shuffle, loop, and skip tracks with a single keystroke.

---

## 🚀 Installation (macOS & Linux)

### 🍺 Option 1: macOS via Homebrew (Recommended for Mac)
```bash
brew tap YOUR_GITHUB_USERNAME/tap
brew install midra
```

### ⚡ Option 2: Quick Install via Curl (Mac & Linux)
Run the following script in your terminal to automatically download and install the latest version:
```bash
curl -sL https://raw.githubusercontent.com/YOUR_GITHUB_USERNAME/midiraja/master/install.sh | bash
```
*(Note for Linux users: `midra` uses ALSA under the hood, which is pre-installed on almost all Linux distributions.)*

### 📦 Option 3: Manual Download
1. Go to the [Releases](https://github.com/YOUR_GITHUB_USERNAME/midiraja/releases) page.
2. Download the `.tar.gz` file for your computer (e.g., `darwin-arm64` for Apple Silicon Mac, `linux-amd64` for Intel/AMD Linux).
3. Extract it and move the `midra` file to `/usr/local/bin/`.

---

## 🎮 How to Use

### Quick Start
Just type `midra` followed by the file you want to play. If you have multiple MIDI devices, a menu will pop up asking you to choose one.
```bash
midra PASSPORT.MID
```

### Playlists & Folders
You can pass multiple files at once. `midra` will play them one after another.
```bash
# Play all MIDI files in the current folder
midra *.mid

# Play a playlist endlessly in a random order
midra --loop --shuffle *.mid
```

### Starting with Specific Settings
You can set the starting volume, tempo, key, or even jump to a specific time right from the command line:
```bash
# Start at 1 minute and 30 seconds
midra --start 01:30 song.mid

# Play at 1.5x speed
midra --speed 1.5 song.mid

# Transpose the song one octave up (+12 semitones) and lower volume
midra --transpose 12 --volume 50 song.mid
```

---

## 🎛️ Live Playback Controls

While a song is playing, you don't need to restart the app to make changes. Just press these keys:

* **`↑` / `↓`** : Volume Up / Down
* **`←` / `→`** : Skip backward / forward 10 seconds
* **`+` / `-`** : Speed Up / Down
* **`>` / `<`** : Transpose Key Up / Down (You can also use `.` / `,`)
* **`n` / `p`** : Next / Previous Track (You can also use `]` / `[`)
* **`q`** : Stop playback and Quit

---

## ⚖️ License & Credits
* **Midiraja** is licensed under the [BSD 3-Clause License](LICENSE).
* This project uses several open-source libraries. Please see [NOTICES.md](NOTICES.md) for full third-party license information and attributions.

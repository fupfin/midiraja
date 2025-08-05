# Midiraja (midra) 🎵

A high-performance, cross-platform CLI tool for playing MIDI files directly to native OS MIDI synthesizers using GraalVM Native Image.

## 🚀 Installation

### 🍺 Option 1: macOS via Homebrew (Recommended)
You can install `midra` using a custom Homebrew tap:

```bash
brew tap YOUR_GITHUB_USERNAME/tap
brew install midra
```

### ⚡ Option 2: Direct Install via Curl
Run the following script to automatically download and install the latest binary for your OS and architecture:

```bash
curl -sL https://raw.githubusercontent.com/YOUR_GITHUB_USERNAME/playmidi/master/install.sh | bash
```

### 📦 Option 3: Manual Download
1. Head over to the [Releases](https://github.com/YOUR_GITHUB_USERNAME/playmidi/releases) page.
2. Download the `.tar.gz` file for your platform (`darwin-arm64`, `linux-amd64`, etc.).
3. Extract the archive: `tar -xzf midra-*.tar.gz`.
4. Move the binary to your PATH: `sudo mv midra /usr/local/bin/`.

## 🎮 Usage
```bash
midra PASSPORT.MID
midra --volume 50 --transpose +2 my_song.mid
midra --list
```
Interactive Controls during playback:
* `UP` / `DOWN`: Adjust Volume
* `LEFT` / `RIGHT`: Seek ±10 seconds
* `q` or `ESC`: Quit instantly (Panic)

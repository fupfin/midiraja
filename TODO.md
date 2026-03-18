# Midiraja Future Improvements

---

## Roadmap

### "Just Works" Default Playback
**Goal:** Let new users run `midra song.mid` with no engine flag and hear audio instantly.
- Implement `EngineAutoSelector`: detect available engines in priority order (soundfont → 1bit)
- Auto-discover SF2 files from common system paths (Homebrew, apt, MuseScore)
- Fallback to `1bit` when no SF2 is found — always produces sound, zero dependencies

### Config / Preset System
**Goal:** Persist per-user defaults so common flags are not retyped.
- `midra config set default-engine soundfont`
- `midra config set default-sf2 ~/soundfonts/FluidR3_GM.sf2`
- TOML config file at `~/.config/midra/config.toml`

### Package Manager Distribution
**Goal:** `brew install midra` / `scoop install midra` / `aur` one-liner.
- Homebrew tap (`fupfin/tap`)
- Scoop bucket
- AUR package (community or official)
- Chocolatey / `winget` manifest
- CI smoke test on Windows (GitHub Actions)

---

## Ideas

### `midra compare`
Cycle through all available engines for the same MIDI file, pausing between each — useful for
evaluating SF2 files or choosing a retro aesthetic.

### Pure Java Audio & `midrax` Revival
Replace `libmidiraja_audio` (miniaudio) with a `javax.sound.sampled`-based sink.
`midrax` would then be a standalone ZIP requiring only Java 25+, no native libraries.

### Retro DAC DSP Filters (`--covox`, `--paula`)
Global post-processing filters applicable to any engine via the AudioProcessor pipeline.
- `--covox`: mono, 8-bit requantization, no filter — raw parallel-port DAC sound
- `--paula`: stereo, 8-bit + RC low-pass (~4kHz), voice-index hard panning (even→L, odd→R)
- Stereo effect variations: ping-pong (alternating L/R per note), Haas delay,
  independent L/R quantization

### C64 SID Synthesizer (`midra sid`)
Oscillator-based synthesis like `psg`, extended with SID's additional features.
- Sawtooth / triangle / pulse / noise waveforms with ADSR per voice (3 voices)
- Ring modulation and oscillator sync between voices
- Resonant multimode filter (LP/BP/HP) — the most distinctive part of the SID sound
- Option B — libsidplayfp for cycle-accurate emulation if pure Java proves insufficient

### NES APU (`midra nes`)
5-voice oscillator synthesis (2× pulse, 1× triangle, 1× noise, 1× DPCM).
- **Pro**: iconic sound, pure Java feasible (no native lib needed), well-documented register set
- **Pro**: strong nostalgia value; most MIDI listeners have heard NES music
- **Challenge**: only 5 simultaneous voices — polyphonic MIDI will require aggressive voice stealing
- **Challenge**: DPCM channel needs sample data; can omit or substitute with noise

### Game Boy APU (`midra gb`)
4-voice synthesis: 2× pulse (with sweep), 1× 32-byte wavetable, 1× noise.
- **Pro**: extremely iconic; distinct lo-fi aesthetic different from NES
- **Pro**: pure Java feasible; register set is simple and well-documented
- **Challenge**: only 4 voices — even more polyphony-limited than NES
- **Challenge**: wavetable channel requires programming waveform data per instrument

### Atari POKEY (`midra pokey`)
4-channel oscillator with unusual distortion modes (pure tone, high-pass, square-wave divisions).
- **Pro**: distinctive buzzy character not found in other chips; beloved in Atari 8-bit demoscene
- **Pro**: pure Java feasible
- **Challenge**: niche audience; less recognizable than NES/SID to most users
- **Challenge**: POKEY's pitch table is non-standard (clock-divided counters, not direct frequency)

### YM2151 OPM FM Synthesizer (`midra opm`)
8-voice, 4-operator FM synthesis — used in arcade boards and the Sharp X68000.
- **Pro**: follows the same pattern as OPL/OPN; libOPNMIDI may already support OPM output
- **Pro**: warm, classic arcade FM sound distinct from Sega/AdLib timbres
- **Challenge**: verify libOPNMIDI OPM support; may need a separate native library
- **Challenge**: less name-recognition than OPL/OPN outside Japanese retro computing circles

### SNES SPC700 (`midra spc`)
8-channel ADPCM (BRR) sample playback with hardware echo/reverb and a DSP mixer.
- **Pro**: extremely rich MIDI music library (SNES game soundtracks); strong nostalgia
- **Pro**: hardware reverb is a built-in feature, not an add-on
- **Challenge**: BRR is a proprietary sample format — no standard soundbank exists for MIDI
- **Challenge**: would need a custom instrument mapping from GM patches to BRR samples
- **Challenge**: accurate emulation requires cycle-exact SPC700 CPU; pure Java is possible but complex
- Option — libsnes-apu or similar for accurate emulation

### PC Engine HuC6280 PSG (`midra pce`)
6-channel 32-byte wavetable synthesis with per-channel DDA (direct D/A) mode.
- **Pro**: interesting hybrid between PSG and wavetable; subtle, soft sound
- **Pro**: pure Java feasible; register interface is straightforward
- **Challenge**: minor platform; PC Engine music is less universally recognized
- **Challenge**: 32-byte wavetables are very short — instrument design is highly constrained


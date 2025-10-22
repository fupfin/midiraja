# Initial Concept
A cross-platform CLI tool for playing MIDI files to external or virtual MIDI devices using GraalVM Native Image and the Java 22+ Foreign Function & Memory API (FFM).

## Target Audience
- **Software Developers:** Testing MIDI implementations or requiring a fast, scriptable player.
- **Musicians/Producers:** Needing a quick way to preview MIDI files directly in the terminal without opening heavy DAWs.

## Key Features
- **Native Port Playback:** Direct communication with OS-level MIDI subsystems (CoreMIDI on macOS, WinMM on Windows, ALSA on Linux) via the modern FFM API (Project Panama), removing the need for legacy C wrappers.
- **Embedded Soft Synths (FluidSynth & Munt):** Includes native FFM bindings for `libfluidsynth` (General MIDI via `--fluid <sf2_file>`) and `libmt32emu` (Roland MT-32 emulation via `--munt <rom_dir>`). Features a custom cross-platform PCM audio engine (`miniaudio`) to output synthesized sound with zero-latency directly within the CLI process, entirely bypassing GraalVM's `javax.sound` limitations.
- **Advanced CLI Controls & UI:** Multiple UI modes (`--ui`) including an event-driven responsive Dashboard (TUI), a single-line status bar, and a non-interactive mode. The Dashboard features real-time VU meters with autonomous decay, playlist tracking, and an intelligent layout manager that prioritizes content based on terminal dimensions. Supports live adjustments: volume, transposition, tempo scaling, and channel muting.
- **Smart & Interactive Port Selection:** Easily select MIDI ports by partial name matching, or use the interactive prompt when a port is not explicitly provided.

## Primary Design Goal
- **Performance & Speed:** Built with GraalVM Native Image to eliminate JVM cold start times, ensuring near-instantaneous execution and minimal resource consumption.

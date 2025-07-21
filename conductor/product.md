# Initial Concept
A cross-platform CLI tool for playing MIDI files to external or virtual MIDI devices using GraalVM Native Image and JNA.

## Target Audience
- **Software Developers:** Testing MIDI implementations or requiring a fast, scriptable player.
- **Musicians/Producers:** Needing a quick way to preview MIDI files directly in the terminal without opening heavy DAWs.

## Key Features
- **Native Port Playback:** Direct communication with OS-level MIDI subsystems (CoreMIDI on macOS, WinMM on Windows, ALSA on Linux) via JNA.
- **Advanced CLI Controls:** Support for adjusting playback parameters such as volume control (`--volume`), transposition (`--transpose`), tempo scaling, and channel muting.

## Primary Design Goal
- **Performance & Speed:** Built with GraalVM Native Image to eliminate JVM cold start times, ensuring near-instantaneous execution and minimal resource consumption.

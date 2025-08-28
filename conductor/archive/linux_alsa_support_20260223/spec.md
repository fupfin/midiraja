# Specification: Linux ALSA Sequencer Support

## Context
Currently, `midra` supports macOS (CoreMIDI) and Windows (WinMM) via JNA. Linux support is a stub. Linux requires integration with the **ALSA Sequencer API** (`libasound.so.2`) to interact with hardware and software synthesizers. Since development is on macOS, a Docker-based Linux environment is required for development, testing, and Native Image compilation.

## Requirements
1.  **ALSA Integration:**
    *   Map `libasound.so.2` functions and structures using JNA.
    *   Implement ALSA port enumeration (finding writable sequencer ports).
    *   Implement MIDI message delivery via `snd_seq_event_output`.
    *   Implement `panic()` (All Notes Off) using ALSA control events.
2.  **Architecture:**
    *   Adhere to existing `MidiOutProvider` and `MidiPort` abstractions.
    *   Maintain high performance and low latency.
3.  **Build Environment (Colima/Docker):**
    *   Define a `Dockerfile` with ALSA development headers and GraalVM.
    *   Enable GraalVM Native Image compilation for Linux (ELF binary).
    *   Automatic extraction of JNA reflection configuration using `native-image-agent` inside Docker.

## Success Criteria
1.  `midra --list` inside a Linux container (with ALSA dummy/virmidi) shows available ports.
2.  `midra` compiles to a standalone Linux ELF binary via Docker.
3.  Unit tests for ALSA mapping pass in the Linux environment.

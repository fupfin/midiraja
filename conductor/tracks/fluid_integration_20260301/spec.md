# Specification: FluidSynth Dynamic Linking via FFM

## 1. Overview
Integrate `libfluidsynth` dynamically using Java 22+ Foreign Function & Memory (FFM) API. This allows Midiraja to act as a zero-latency host for FluidSynth directly within the same memory space, completely bypassing GraalVM AOT conflicts associated with `javax.sound`. This provides a robust cross-platform soft-synth solution without relying on error-prone subprocess pipes.

## 2. Functional Requirements
*   **Abstraction:** Introduce a `SoftSynthProvider` interface (extending `MidiOutProvider`) to formalize software synthesizer initialization (e.g., loading a soundbank).
*   **FFM Integration (`FluidSynthProvider`):**
    *   Load `libfluidsynth` dynamically from the system's default library path (`SymbolLookup.libraryLookup`).
    *   Map essential C functions via FFM: `fluid_settings_new`, `fluid_synth_new`, `fluid_synth_sfload`, `fluid_audio_driver_new`, `fluid_synth_noteon`, `fluid_synth_noteoff`, `fluid_synth_cc`, `fluid_synth_sysex`, `fluid_synth_pitch_bend`, `fluid_synth_program_change`, and cleanup functions.
*   **CLI Options:**
    *   `--fluid <soundfont_path>`: Activates the `FluidSynthProvider` and loads the specified `.sf2` file.
    *   `--fluid-driver <driver_name>`: (Optional) Allows the user to override the auto-detected audio driver (e.g., `coreaudio`, `alsa`, `dsound`). If omitted, FluidSynth auto-detects the best driver.
*   **Message Routing:**
    *   Implement `sendMessage(byte[] data)` to parse raw MIDI bytes and map them to the corresponding `fluid_synth_*` C API calls to ensure perfect playback.
*   **Resource Management:**
    *   Ensure all C-allocated memory (settings, synth, driver) is safely freed using `Arena` or explicit cleanup calls during `closePort()`.

## 3. Non-Functional Requirements
*   **Zero Dependencies:** Do not introduce any Java dependencies (e.g., JNA). Rely strictly on `java.lang.foreign`.
*   **AOT Compatibility:** The code must compile correctly via GraalVM Native Image.
*   **Graceful Degradation:** If `libfluidsynth` is not found on the host system, fail gracefully with a clear message instructing the user to install it (e.g., `brew install fluidsynth`).

## 4. Out of Scope
*   Handling complex FluidSynth configurations (Chorus, Reverb, Polyphony limits) beyond the basic audio driver and SoundFont loading.
*   Embedding `.sf2` files directly inside the compiled binary.

## 5. Acceptance Criteria
*   Unit tests verify that FFM `MethodHandle` resolution handles missing libraries gracefully.
*   Running `midra --fluid ./font.sf2 song.mid` successfully plays audio with zero IPC latency.
*   Running with an explicit driver like `midra --fluid ./font.sf2 --fluid-driver coreaudio song.mid` configures the driver correctly.
*   GraalVM Native Image compilation succeeds without errors related to `java.desktop` or `javax.sound`.
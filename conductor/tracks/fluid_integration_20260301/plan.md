# Implementation Plan: FluidSynth Dynamic Linking via FFM

## Phase 1: Abstraction and Test Scaffolding
- [ ] Task: Create the `SoftSynthProvider.java` interface extending `MidiOutProvider` with a `loadSoundbank(String path)` method.
- [ ] Task: Create `FluidSynthProviderTest.java` and write failing tests for library loading detection (e.g., verifying it throws a clear exception if the library is not found or handles it gracefully).
- [ ] Task: Write failing tests for parsing raw MIDI messages (`byte[]`) into the correct FFM function calls (using a mock or spy for the `MethodHandles` if possible).
- [ ] Task: Conductor - User Manual Verification 'Phase 1: Abstraction and Test Scaffolding' (Protocol in workflow.md)

## Phase 2: FFM Bindings and Provider Implementation
- [ ] Task: Implement `FluidSynthProvider.java` implementing `SoftSynthProvider`.
- [ ] Task: Define the `MethodHandle` constants for the `libfluidsynth` C API using `SymbolLookup.libraryLookup` and `Linker.nativeLinker()`.
- [ ] Task: Implement `openPort()` to initialize the fluid settings, optionally set the `audio.driver` if provided, create the synth, and start the audio driver.
- [ ] Task: Implement `loadSoundbank()` to call `fluid_synth_sfload`.
- [ ] Task: Implement `sendMessage()` to parse the raw MIDI bytes (status, channel, data1, data2) and route them to `fluid_synth_noteon`, `fluid_synth_noteoff`, `fluid_synth_cc`, `fluid_synth_program_change`, `fluid_synth_pitch_bend`, or `fluid_synth_sysex`.
- [ ] Task: Implement `closePort()` to cleanly destroy the fluid driver, synth, and settings.
- [ ] Task: Refactor and ensure all memory allocations are handled via `Arena` where appropriate to prevent memory leaks.
- [ ] Task: Conductor - User Manual Verification 'Phase 2: FFM Bindings and Provider Implementation' (Protocol in workflow.md)

## Phase 3: CLI Integration
- [ ] Task: Update `MidirajaCommand.java` to replace the old `--soft-synth` option with `--fluid <soundfont_path>` and `--fluid-driver <driver_name>`.
- [ ] Task: Wire the CLI options to instantiate `FluidSynthProvider`, set the driver (if provided), load the soundfont, and set it as the active provider.
- [ ] Task: Update the CLI help text (`@Option` descriptions) to clearly explain the new FluidSynth integration.
- [ ] Task: Run integration tests (JVM mode and Native mode) to verify successful audio output using a local `.sf2` file.
- [ ] Task: Conductor - User Manual Verification 'Phase 3: CLI Integration' (Protocol in workflow.md)
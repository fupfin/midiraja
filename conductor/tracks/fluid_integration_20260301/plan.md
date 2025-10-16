# Implementation Plan: FluidSynth Dynamic Linking via FFM

## Phase 1: Abstraction and Test Scaffolding
- [x] Task: Create the `SoftSynthProvider.java` interface extending `MidiOutProvider` with a `loadSoundbank(String path)` method. [checkpoint: 726c836]
- [x] Task: Create `FluidSynthProviderTest.java` and write failing tests for library loading detection (e.g., verifying it throws a clear exception if the library is not found or handles it gracefully). [checkpoint: 726c836]
- [x] Task: Write failing tests for parsing raw MIDI messages (`byte[]`) into the correct FFM function calls (using a mock or spy for the `MethodHandles` if possible). [checkpoint: bf618d7]
- [x] Task: Conductor - User Manual Verification 'Phase 1: Abstraction and Test Scaffolding' (Protocol in workflow.md) [checkpoint: manual]

## Phase 2: FFM Bindings and Provider Implementation
- [x] Task: Implement `FluidSynthProvider.java` implementing `SoftSynthProvider`. [checkpoint: b01f6ae]
- [x] Task: Define the `MethodHandle` constants for the `libfluidsynth` C API using `SymbolLookup.libraryLookup` and `Linker.nativeLinker()`. [checkpoint: b01f6ae]
- [x] Task: Implement `openPort()` to initialize the fluid settings, optionally set the `audio.driver` if provided, create the synth, and start the audio driver. [checkpoint: b01f6ae]
- [x] Task: Implement `loadSoundbank()` to call `fluid_synth_sfload`. [checkpoint: b01f6ae]
- [x] Task: Implement `sendMessage()` to parse the raw MIDI bytes (status, channel, data1, data2) and route them to `fluid_synth_noteon`, `fluid_synth_noteoff`, `fluid_synth_cc`, `fluid_synth_program_change`, `fluid_synth_pitch_bend`, or `fluid_synth_sysex`. [checkpoint: b01f6ae]
- [x] Task: Implement `closePort()` to cleanly destroy the fluid driver, synth, and settings. [checkpoint: b01f6ae]
- [x] Task: Refactor and ensure all memory allocations are handled via `Arena` where appropriate to prevent memory leaks. [checkpoint: b01f6ae]
- [x] Task: Conductor - User Manual Verification 'Phase 2: FFM Bindings and Provider Implementation' (Protocol in workflow.md) [checkpoint: manual]

## Phase 3: CLI Integration
- [x] Task: Update `MidirajaCommand.java` to replace the old `--soft-synth` option with `--fluid <soundfont_path>` and `--fluid-driver <driver_name>`. [checkpoint: 2a8567f]
- [x] Task: Wire the CLI options to instantiate `FluidSynthProvider`, set the driver (if provided), load the soundfont, and set it as the active provider. [checkpoint: 2a8567f]
- [x] Task: Update the CLI help text (`@Option` descriptions) to clearly explain the new FluidSynth integration. [checkpoint: 2a8567f]
- [x] Task: Run integration tests (JVM mode and Native mode) to verify successful audio output using a local `.sf2` file. [checkpoint: f22dcfb]
- [x] Task: Conductor - User Manual Verification 'Phase 3: CLI Integration' (Protocol in workflow.md) [checkpoint: manual]
# Implementation Plan: Munt (MT-32) Emulator Integration

## Phase 1: Audio Playback Engine (`miniaudio`)
- [x] Task: Research `miniaudio` (a single-header C audio library) for cross-platform, GraalVM AOT-compatible PCM audio playback. [checkpoint: 8de1d23]
- [x] Task: Create a minimal C shared library (`libmidiraja_audio`) wrapping `miniaudio` to provide a simple API for pushing PCM float/short buffers to the OS default audio output. [checkpoint: 27011c9]
- [x] Task: Set up a basic Makefile/CMake to compile `libmidiraja_audio` for macOS, Linux, and Windows. [checkpoint: 27011c9]
- [x] Task: Create a `NativeAudioEngine` Java class that binds to `libmidiraja_audio` via FFM. [checkpoint: 27011c9]
- [x] Task: Conductor - User Manual Verification 'Phase 1: Audio Playback Engine' (Protocol in workflow.md) [checkpoint: manual]

## Phase 2: Munt FFM Bindings & Abstraction
- [x] Task: Write a CMake/Makefile script to build the official `mt32emu` library as a shared C library (`libmt32emu`) from the `ext/munt` submodule. [checkpoint: f62c4f2]
- [x] Task: Define a `MuntNativeBridge` interface for DIP and testability. [checkpoint: f62c4f2]
- [x] Task: Implement `FFMMuntNativeBridge.java` that uses `java.lang.foreign` to bind directly to the official `libmt32emu` C API (`c_interface.h`). [checkpoint: f62c4f2]
- [x] Task: Implement `MuntSynthProviderTest.java` with spec-level tests using a mocked bridge (verifying Note On/Off, CC, SysEx, and error handling). [checkpoint: f62c4f2]
- [~] Task: Conductor - User Manual Verification 'Phase 2: Munt FFM Bindings & Abstraction' (Protocol in workflow.md)

## Phase 3: Synthesizer Integration (`MuntSynthProvider`)
- [x] Task: Implement `MuntSynthProvider` implementing `SoftSynthProvider`. [checkpoint: f62c4f2]
- [x] Task: Wire the initialization: create the munt context, load ROMs from the provided directory, and open the synth. [checkpoint: f62c4f2]
- [x] Task: Implement a background daemon thread in `MuntSynthProvider` that continuously calls `mt32emu_render_bit16s` and pipes the resulting PCM audio buffer to the `NativeAudioEngine`. [checkpoint: a0b0823]
- [x] Task: Implement the `sendMessage` routing logic. [checkpoint: f62c4f2]
- [x] Task: Update `reachability-metadata.json` with the new FFM downcall signatures. [checkpoint: 996b02b]
- [x] Task: Conductor - User Manual Verification 'Phase 3: Synthesizer Integration' (Protocol in workflow.md) [checkpoint: manual]

## Phase 4: CLI Integration and Build Automation
- [x] Task: Update `MidirajaCommand.java` to add the `--munt <rom_dir>` option. [checkpoint: 3e5581b]
- [x] Task: Wire the `--munt` option to instantiate the `MuntSynthProvider`. [checkpoint: 3e5581b]
- [x] Task: Update the main `build.gradle` or shell scripts to automatically compile both `libmt32emu` and `libmidiraja_audio` before the Java build. [checkpoint: 4c5f6e9]
- [x] Task: Conductor - User Manual Verification 'Phase 4: CLI Integration and Build Automation' (Protocol in workflow.md) [checkpoint: manual]

## Phase: Review Fixes
- [x] Task: Apply review suggestions [checkpoint: fa9e944]
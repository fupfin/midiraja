# Implementation Plan: Munt (MT-32) Emulator Integration

## Phase 1: Audio Playback Engine (`miniaudio`)
- [ ] Task: Research `miniaudio` (a single-header C audio library) for cross-platform, GraalVM AOT-compatible PCM audio playback.
- [ ] Task: Create a minimal C shared library (`libmidiraja_audio`) wrapping `miniaudio` to provide a simple API for pushing PCM float/short buffers to the OS default audio output.
- [ ] Task: Set up a basic Makefile/CMake to compile `libmidiraja_audio` for macOS, Linux, and Windows.
- [ ] Task: Create a `NativeAudioEngine` Java class that binds to `libmidiraja_audio` via FFM.
- [ ] Task: Conductor - User Manual Verification 'Phase 1: Audio Playback Engine' (Protocol in workflow.md)

## Phase 2: Munt FFM Bindings & Abstraction
- [ ] Task: Write a CMake/Makefile script to build the official `mt32emu` library as a shared C library (`libmt32emu`) from the `ext/munt` submodule.
- [ ] Task: Define a `MuntNativeBridge` interface for DIP and testability.
- [ ] Task: Implement `FFMMuntNativeBridge.java` that uses `java.lang.foreign` to bind directly to the official `libmt32emu` C API (`c_interface.h`).
- [ ] Task: Implement `MuntSynthProviderTest.java` with spec-level tests using a mocked bridge (verifying Note On/Off, CC, SysEx, and error handling).
- [ ] Task: Conductor - User Manual Verification 'Phase 2: Munt FFM Bindings & Abstraction' (Protocol in workflow.md)

## Phase 3: Synthesizer Integration (`MuntSynthProvider`)
- [ ] Task: Implement `MuntSynthProvider` implementing `SoftSynthProvider`.
- [ ] Task: Wire the initialization: create the munt context, load ROMs from the provided directory, and open the synth.
- [ ] Task: Implement a background daemon thread in `MuntSynthProvider` that continuously calls `mt32emu_render_bit16s` and pipes the resulting PCM audio buffer to the `NativeAudioEngine`.
- [ ] Task: Implement the `sendMessage` routing logic.
- [ ] Task: Update `reachability-metadata.json` with the new FFM downcall signatures.
- [ ] Task: Conductor - User Manual Verification 'Phase 3: Synthesizer Integration' (Protocol in workflow.md)

## Phase 4: CLI Integration and Build Automation
- [ ] Task: Update `MidirajaCommand.java` to add the `--munt <rom_dir>` option.
- [ ] Task: Wire the `--munt` option to instantiate the `MuntSynthProvider`.
- [ ] Task: Update the main `build.gradle` or shell scripts to automatically compile both `libmt32emu` and `libmidiraja_audio` before the Java build.
- [ ] Task: Conductor - User Manual Verification 'Phase 4: CLI Integration and Build Automation' (Protocol in workflow.md)
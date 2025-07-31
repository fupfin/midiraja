# Implementation Plan: Interactive Playback Architecture

## Phase 1: Architectural Refactoring (DIP & IoC)
- [ ] Task: Create a `TerminalIO` interface with `init()`, `close()`, `readKey()`, `print(String)`.
- [ ] Task: Create `JLineTerminalIO` implementing `TerminalIO` using `org.jline:jline` (Terminal, LineReader). Add JLine dependency to `build.gradle`.
- [ ] Task: Create `MockTerminalIO` implementing `TerminalIO` for testing.
- [ ] Task: Extract the `UIThread` rendering logic from `MidirajaCommand` into a new `DisplayManager` class (depends on `TerminalIO`).
- [ ] Task: Refactor the heavy `playMidiWithProvider` loop into a `PlaybackEngine` class. The `MidirajaCommand` simply becomes a configurator that wires `TerminalIO`, `DisplayManager`, and `MidiOutProvider` into the `PlaybackEngine` and calls `engine.start()`.

## Phase 2: Event-Driven Inputs
- [ ] Task: Define a `KeyListener` interface or callback in `TerminalIO` to emit async key events (e.g., `UP`, `DOWN`, `LEFT`, `RIGHT`, `QUIT`).
- [ ] Task: Connect `PlaybackEngine` to listen to these key events.
- [ ] Task: Implement Volume Adjust (`UP`/`DOWN`). Modify the internal `volume` scale dynamically and instantly send CC 7 (Volume) updates across all 16 channels.

## Phase 3: MIDI Chasing (Seek Implementation)
- [ ] Task: Implement `seek(long targetTick)` in `PlaybackEngine`.
- [ ] Task: The `seek` method must perform a "Panic" (All Notes Off).
- [ ] Task: Instantly process all non-note events (Program Change `0xC0`, Control Change `0xB0`, Pitch Bend `0xE0`) from tick 0 to `targetTick` to restore the MIDI state.
- [ ] Task: Resume playback from `targetTick`.
- [ ] Task: Ensure GraalVM Native Image builds successfully with the JLine dependency (may need `--initialize-at-build-time=org.jline...` config).
- [ ] Task: Conductor - User Manual Verification 'Phase 3: MIDI Chasing (Seek Implementation)' (Protocol in workflow.md)
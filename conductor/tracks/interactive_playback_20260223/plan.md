# Implementation Plan: Interactive Playback Architecture

## Phase 1: Architectural Refactoring (DIP & IoC)
- [x] Task: Create a `TerminalIO` interface with `init()`, `close()`, `readKey()`, `print(String)`.
- [x] Task: Create `JLineTerminalIO` implementing `TerminalIO` using `org.jline:jline` (Terminal, LineReader). Add JLine dependency to `build.gradle`.
- [x] Task: Create `MockTerminalIO` implementing `TerminalIO` for testing.
- [x] Task: Extract the `UIThread` rendering logic from `MidirajaCommand` into a new `DisplayManager` class (depends on `TerminalIO`).
- [x] Task: Refactor the heavy `playMidiWithProvider` loop into a `PlaybackEngine` class. The `MidirajaCommand` simply becomes a configurator that wires `TerminalIO`, `DisplayManager`, and `MidiOutProvider` into the `PlaybackEngine` and calls `engine.start()`.

## Phase 2: Event-Driven Inputs
- [x] Task: Define a `KeyListener` interface or callback in `TerminalIO` to emit async key events (e.g., `UP`, `DOWN`, `LEFT`, `RIGHT`, `QUIT`).
- [x] Task: Connect `PlaybackEngine` to listen to these key events.
- [x] Task: Implement Volume Adjust (`UP`/`DOWN`). Modify the internal `volume` scale dynamically and instantly send CC 7 (Volume) updates across all 16 channels.

## Phase 3: MIDI Chasing (Seek Implementation)
- [ ] Task: Implement `seek(long targetTick)` in `PlaybackEngine`.
- [ ] Task: The `seek` method must perform a "Panic" (All Notes Off).
- [ ] Task: Instantly process all non-note events (Program Change `0xC0`, Control Change `0xB0`, Pitch Bend `0xE0`) from tick 0 to `targetTick` to restore the MIDI state.
- [ ] Task: Resume playback from `targetTick`.
- [ ] Task: Ensure GraalVM Native Image builds successfully with the JLine dependency (may need `--initialize-at-build-time=org.jline...` config).

## Phase 4: Quality & Verification (TDD & Review)
- [x] Task: Write comprehensive test cases for `PlaybackEngine` using `MockTerminalIO`.
- [ ] Task: Include edge cases (e.g., seeking beyond file end, volume at 0% or 100%, malformed MIDI events) and boundary conditions.
- [ ] Task: Measure test coverage and supplement missing areas to ensure logic completeness (not just for the numbers).
- [ ] Task: Perform a thorough code review focused on:
    - **Design:** Proper IoC/DIP implementation.
    - **Readability:** Clean naming and clear event flow.
    - **Security:** Safe handling of external file inputs and terminal raw mode cleanup.
- [ ] Task: Conductor - User Manual Verification 'Phase 4: Quality & Verification' (Protocol in workflow.md)
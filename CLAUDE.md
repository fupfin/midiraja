# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

```bash
# Build (compiles + runs tests)
./gradlew build

# Run tests
./gradlew test

# Run a single test
./gradlew test --tests "com.midiraja.engine.PlaybackEngineTest.testVolumeControl"

# Run the app (requires a MIDI file argument)
./gradlew run --args="PASSPORT.MID"

# Compile GraalVM native binary (output: build/native/nativeCompile/midra)
./gradlew nativeCompile

# Package native binary into dist/ tarball
./scripts/package-release.sh
```

## Architecture Overview

**Entry point:** `MidirajaCommand` (picocli `@Command`) — parses CLI args, selects MIDI port, constructs `PlaybackEngine`, and manages the playlist loop.

**Playback layer:** `PlaybackEngine` drives the MIDI event loop. It uses Java Structured Concurrency (`StructuredTaskScope`) to fork two concurrent tasks from within `start()`:
- `ui.runRenderLoop(engine)` — polls engine state and renders to terminal
- `ui.runInputLoop(engine)` — reads keystrokes and calls engine control methods

The engine exposes a set of `volatile` fields (e.g. `currentBpm`, `volumeScale`, `isPlaying`) for safe cross-thread reads, and mutation methods (`adjustVolume`, `seekRelative`, etc.) that are called from the input thread.

**MIDI abstraction:** `MidiOutProvider` interface decouples the engine from OS MIDI specifics. `MidiProviderFactory` selects `MacProvider`, `LinuxProvider`, or `WindowsProvider` at runtime. All OS-specific code lives under `com.midiraja.midi.os`.

**Terminal abstraction:** `TerminalIO` interface (backed by `JLineTerminalIO`) is injected via `ScopedValue<TerminalIO> CONTEXT` — not passed through constructors. This allows UI components anywhere in the call stack to access the terminal without parameter threading. Tests use `MockTerminalIO` which has an `injectKey()` queue.

**UI layer:** `PlaybackUI` interface has two implementations active at runtime:
- `DashboardUI` — full-screen TUI using alt screen buffer; hosts four `Panel` implementations
- `LineUI` — single-line status bar for narrower terminals or `--ui line`
- `DumbUI` — no-op for non-interactive environments (CI, pipes)

`MidirajaCommand` selects the UI mode based on `--ui` flag and terminal interactivity detection.

**Panel system (`DashboardUI`):** Each panel (`MetadataPanel`, `StatusPanel`, `ChannelActivityPanel`, `ControlsPanel`) implements the `Panel` interface, which extends both `LayoutListener` and `PlaybackEventListener`. Panels receive layout constraints from `DashboardLayoutManager.calculateLayout()` when the terminal is resized, and receive playback events (tick, tempo change, channel activity) pushed from `PlaybackEngine`. Panels cache their state and render into a `StringBuilder` on demand.

`DashboardLayoutManager` implements a priority-based responsive layout: two-column mode (channels + playlist side by side) when `contentHeight >= 19`, stacked mode otherwise.

## Key Constraints

**GraalVM Native Image:** All code must be AOT-compilable. No runtime reflection, dynamic proxies, or runtime bytecode generation. When using JNA (Linux ALSA), keep JNA interfaces in `midi/os/linux/` and away from domain logic.

**NullAway strict mode:** Enabled for all non-test source (`com.midiraja` package). NullAway runs at `ERROR` severity via errorprone. Use `@Nullable` from `org.jspecify` for fields/parameters that may be null; otherwise assume non-null.

**Java 25 preview features:** `--enable-preview` is required for compilation, tests, and the application JVM. The `build.gradle` adds this flag automatically.

**Threading rules:** Background tasks (`uiLoop`, `inputLoop`) must be daemon threads. Use `volatile` for state shared across the playback and UI threads. Always register a shutdown hook to call `provider.panic()` and restore the terminal (`\033[?25h\033[?1049l`).

**Hot-path allocations:** The `playLoop()` method is timing-sensitive. Avoid GC-heavy allocations inside this loop.

## Testing Patterns

Tests set up `PlaybackEngine` with a `MockMidiProvider` and `MockTerminalIO`, inject a sequence of `TerminalKey` values, then call `engine.start(new DumbUI())` inside `ScopedValue.where(TerminalIO.CONTEXT, mockIO).call(...)`. The engine will drain the injected keys and exit when it sees `QUIT`.

For `MidirajaCommand` integration tests, use `setTestEnvironment(provider, terminalIO, out, err)` to inject mocks and bypass interactive port selection.

# Midiraja Coding Standards 🎼

This document outlines the specific coding conventions, architectural patterns, and style rules for the Midiraja project. 

As a high-performance CLI tool built with Java and compiled natively using GraalVM, strict adherence to these standards is essential for maintainability, cross-platform compatibility, and optimal performance.

## 1. Core Principles
*   **Zero Dependencies When Possible:** Avoid introducing large frameworks or libraries unless absolutely necessary. Rely on standard Java APIs (`javax.sound.midi`) and native OS capabilities where possible to keep the final binary small and fast.
*   **GraalVM Native Image Compatibility:** All code MUST be compatible with Ahead-Of-Time (AOT) compilation.
    *   Avoid runtime reflection (`java.lang.reflect`) and dynamic class loading unless properly registered in the GraalVM configuration files (`reflect-config.json`, etc.).
    *   Do not use dynamic proxies or generate bytecode at runtime.
*   **Predictable Performance:** MIDI playback requires precise timing. Avoid heavy garbage collection (GC) allocations inside the hot paths (like `playLoop`).

## 2. Java Language & Style
*   **Language Level:** Java 25.
*   **Declarative Style:** Prefer declarative and functional expressions over imperative logic.
*   **Lightweight Representation:** Favor lightweight abstractions. Use Algebraic Data Types (Records, sealed interfaces) and higher-order functions over complex Object-Oriented hierarchies.
*   **Core Values (in order of priority):**
    1.  **Expressing Intent:** Code must clearly communicate *what* it is doing and *why*.
    2.  **Simplicity:** Prefer the most straightforward implementation that fulfills the intent.
    3.  **Flexibility:** Build for change, but do not sacrifice simplicity for speculative generality.
*   **Naming Conventions:**
    *   Classes and Interfaces: `PascalCase` (e.g., `PlaybackEngine`)
    *   Methods and Variables: `camelCase` (e.g., `playLoop`, `currentTick`)
    *   Constants (static final): `UPPER_SNAKE_CASE` (e.g., `DEFAULT_BPM`)
*   **Immutability:** Prefer `final` for variables, fields, and method parameters where the value is not expected to change.
*   **Visibility:** Always use the most restrictive access modifier possible (`private` > `package-private` > `protected` > `public`).
*   **Null Safety:** Assume parameters are non-null by default. Use `java.util.Optional` for return types where a value might be legitimately absent.

## 3. Architecture & Structure
*   **CLI Parsing:** We use `picocli` for command-line parsing. All CLI logic and option definitions should reside within `MidirajaCommand.java` or dedicated subcommands.
*   **TUI Rendering Architecture:** UI panels must implement the `Panel` interface and render their output strictly into the provided `ScreenBuffer`. Never use `System.out.print` directly inside a panel to avoid terminal ghosting and I/O bottlenecks.
*   **Dependency Inversion (DIP):** Concrete implementations should depend on abstractions.
    *   *Example:* `PlaybackEngine` depends on the `TerminalIO` interface, not directly on JLine or `System.out`. This allows for seamless unit testing with `MockTerminalIO`.
*   **Platform Specifics:** OS-specific code (Windows, macOS, Linux) must be isolated behind interfaces (e.g., `MidiOutProvider`) and instantiated via factories (`MidiProviderFactory`). Do not bleed OS-specific logic into the core engine.
*   **FFM API (Project Panama):** We strictly use `java.lang.foreign` for all native C-interop (CoreMIDI, ALSA, WinMM). JNA is completely prohibited to ensure zero external dependencies and optimal GraalVM Native Image compilation.
*   **Native Memory Management:** Always use `Arena.ofConfined()` or `Arena.ofShared()` appropriately and ensure native memory is freed securely in `try-with-resources` or `finally` blocks.

## 4. Concurrency & Threading
*   **Daemon Threads:** Background tasks like UI updates (`uiLoop`) and keyboard input listening (`inputLoop`) must be spawned as daemon threads (`setDaemon(true)`). This ensures they do not block the JVM from exiting when the main playback finishes or is interrupted.
*   **Volatile Variables:** Use `volatile` for primitive flags or state variables (like `isPlaying`, `currentBpm`, `volumeScale`) that are read/written across multiple threads (e.g., the input thread modifying speed while the playback loop reads it).
*   **Graceful Shutdown:** Always register a shutdown hook (`Runtime.getRuntime().addShutdownHook`) to silence MIDI notes (`provider.panic()`) and restore the terminal state (e.g., cursor visibility) when the user abruptly exits via `Ctrl+C`.

## 5. Error Handling & UI
*   **User-Facing Errors:** Print clear, actionable error messages to `System.err` (not standard out). Never expose raw stack traces to the end user unless running in a verbose/debug mode.
*   **Terminal State:** If altering the terminal state (like entering raw mode, hiding the cursor `\033[?25l`, or turning off echo), ensure a `try-finally` block absolutely restores the original state (`\033[?25h`).
*   **Clean Output:** When updating interactive UI elements (like progress bars), use carriage returns (`\r`) and ANSI "Erase in Line" (`\033[K`) to prevent trailing garbage characters, rather than clearing the entire screen unnecessarily.

## 6. Testing
*   **Executable Documentation:** Treat test code as comprehensive, executable documentation that defines the expected behavior of the system.
*   **Unit Tests:** All core logic (`PlaybackEngine`, `TerminalIO` mapping, etc.) must have corresponding unit tests using JUnit 5.
*   **Mocking:** Use standard mocking frameworks or manual mocks (like `MockMidiProvider`) to isolate the code under test from external side effects (like actual audio output).
*   **Test Naming:** Use clear, descriptive names for test methods (e.g., `testSeekBackwardToZero`, `testVolumeBoundaries`).
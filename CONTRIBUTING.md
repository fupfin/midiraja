# Midiraja Coding Standards 🎼

This document outlines the specific coding conventions, architectural patterns, and style rules for the Midiraja project. 

As a high-performance CLI tool built with Java and compiled natively using GraalVM, strict adherence to these standards is essential for maintainability, cross-platform compatibility, and optimal performance.

## 1. Core Principles
*   **Core Values (in order of priority):**
    1.  **Expressing Intent:** Code must clearly communicate *what* it is doing and *why*.
    2.  **Simplicity:** Prefer the most straightforward implementation that fulfills the intent.
    3.  **Flexibility:** Build for change, but do not sacrifice simplicity for speculative generality.
*   **Zero Dependencies When Possible:** Avoid introducing large frameworks or libraries unless absolutely necessary. Rely on standard Java APIs (`javax.sound.midi`) and native OS capabilities where possible to keep the final binary small and fast.

## 2. Technical Requirements
*   **Deep Native Integration:** The core value of this project is providing a fast, standalone CLI tool. To achieve this, we prioritize native integration over platform independence.
    *   **AOT & Native Build:** We aggressively target Ahead-Of-Time (AOT) compilation to eliminate JVM startup overhead and distribute single-file binaries.
    *   **FFM API:** We strictly use the modern Foreign Function & Memory API (`java.lang.foreign`) instead of JNI/JNA to communicate directly with OS audio subsystems (CoreMIDI, ALSA, WinMM) with zero external dependencies.
    *   **Native Memory Management:** Manual memory management via `Arena` is required when interfacing with C libraries. Ensure memory is freed securely in `try-with-resources` or `finally` blocks to prevent leaks.
*   **Predictable Performance:** MIDI playback requires precise timing. Avoid heavy garbage collection (GC) allocations inside the hot paths (like `playLoop`).

## 3. Language & Style
*   **Modern Java:** Actively embrace the latest Java features and platform enhancements.
*   **Declarative Style:** Prefer declarative and functional expressions over imperative logic.
*   **Lightweight Representation:** Favor lightweight abstractions. Use Algebraic Data Types (Records, sealed interfaces) and higher-order functions over complex object-oriented approaches.
*   **Immutability:** Prefer `final` for variables, fields, and method parameters where the value is not expected to change.
*   **Null Safety:** Assume parameters are non-null by default. Use `java.util.Optional` for return types where a value might be legitimately absent.
*   **Visibility:** Always use the most restrictive access modifier possible (`private` > `package-private` > `protected` > `public`).

## 4. System Design & Boundaries
*   **Dependency Inversion (DIP):** Concrete implementations should depend on abstractions.
    *   *Example:* `PlaybackEngine` depends on the `TerminalIO` interface, not directly on JLine or `System.out`. This allows for seamless unit testing with `MockTerminalIO`.
*   **Platform Specifics:** OS-specific code (Windows, macOS, Linux) must be isolated behind interfaces (e.g., `MidiOutProvider`) and instantiated via factories (`MidiProviderFactory`). Do not bleed OS-specific logic into the core engine.
*   **TUI Rendering:** We utilize JLine for robust, cross-platform terminal control (raw mode, window resizing, signal handling). However, UI panels must abstract their rendering logic to avoid coupling directly to terminal output streams, preventing ghosting and I/O bottlenecks.
*   **CLI Parsing:** We use `picocli` for command-line parsing. All CLI logic and option definitions should reside within `MidirajaCommand.java` or dedicated subcommands.
*   **GraalVM Native Image:** The build pipeline relies on GraalVM Native Image. 
    *   Code MUST avoid dynamic features like runtime reflection (`java.lang.reflect`), dynamic class loading, and dynamic proxies unless explicitly registered in the GraalVM configuration files (`reflect-config.json`, etc.).

## 5. Concurrency & Threading
*   **Graceful Shutdown:** Always register a shutdown hook (`Runtime.getRuntime().addShutdownHook`) to silence MIDI notes (`provider.panic()`) and restore the terminal state (e.g., cursor visibility) when the user abruptly exits via `Ctrl+C`.
*   **Volatile Variables:** Use `volatile` for primitive flags or state variables (like `isPlaying`, `currentBpm`, `volumeScale`) that are read/written across multiple threads (e.g., the input thread modifying speed while the playback loop reads it).
*   **Daemon Threads:** Background tasks like UI updates (`uiLoop`) and keyboard input listening (`inputLoop`) must be spawned as daemon threads (`setDaemon(true)`). This ensures they do not block the JVM from exiting when the main playback finishes or is interrupted.

## 6. Terminal UI & Error Handling
*   **Terminal State Safety:** If altering the terminal state (like entering raw mode, hiding the cursor `[?25l`, or turning off echo), ensure a `try-finally` block absolutely restores the original state (`[?25h`).
*   **Clean Output:** When updating interactive UI elements (like progress bars), use carriage returns (`\r`) and ANSI "Erase in Line" (`[K`) to prevent trailing garbage characters, rather than clearing the entire screen unnecessarily. Ensure DECAWM (`[?7l`) is managed correctly to prevent terminal reflow artifacts.
*   **User-Facing Errors:** Print clear, actionable error messages to `System.err` (not standard out). Never expose raw stack traces to the end user unless running in a verbose/debug mode.

## 7. Testing
*   **Executable Documentation:** Treat test code as comprehensive, executable documentation that defines the expected behavior of the system.
*   **Mocking:** Use standard mocking frameworks or manual mocks (like `MockMidiProvider`) to isolate the code under test from external side effects (like actual audio output).
*   **Unit Tests:** All core logic (`PlaybackEngine`, `TerminalIO` mapping, etc.) must have corresponding unit tests using JUnit 5.

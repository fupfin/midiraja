# Contributing to Midiraja 🎼

Welcome! We are thrilled that you are interested in contributing to Midiraja. 

## Our Vision
Midiraja isn't just another Java application—it's a mission to build a blazing-fast, standalone MIDI player entirely for the terminal. We want to prove that modern Java can deliver lightweight, zero-dependency CLI tools with instant startup times and beautiful Terminal User Interfaces (TUI). 

## The Technical Playground
If you love pushing Java to its limits and stepping outside the traditional enterprise environment, you will feel right at home here. Contributing to Midiraja offers a chance to get hands-on with some exciting systems-level technical challenges:

*   **Native Compilation:** We aggressively target GraalVM Ahead-Of-Time (AOT) compilation to distribute single-file binaries with zero JVM startup overhead.
*   **Direct OS Interop:** We bypass legacy JNI and use the modern Foreign Function & Memory API (Project Panama) to talk directly to OS native audio drivers (CoreMIDI, ALSA, WinMM).
*   **Low-Latency Audio:** MIDI playback requires strict timing. You'll work on lock-free concurrency and predictable performance, keeping the garbage collector out of the hot audio loop.
*   **Rich Terminal UIs:** We build interactive, responsive dashboards using raw terminal modes, JLine, and ANSI escape sequences.

---

## Engineering Standards

To ensure our codebase remains maintainable, lightning-fast, and natively compilable, we ask all contributors to adhere to the following guidelines.

### 1. Coding Style
We use a specific blend of styles to keep the code readable and consistent across Java and C/C++:
*   **Brace Style:** We use **Allman style** (opening braces on a new line) for classes, methods, and all control structures (if, for, while, etc.).
*   **Indentation:** Exactly **4 spaces**. No tabs.
*   **Java Base:** Follow [Google Java Style](https://google.github.io/styleguide/javaguide.html) for everything else.
*   **C/C++ Base:** Follow [Google C++ Style](https://google.github.io/styleguide/cppguide.html) for everything else.
*   **Static Imports:** Prefer `static import` for frequently used static utility methods (e.g., `UIUtils.formatTime`) and standard streams (`System.out`, `System.err`) to reduce verbosity.
*   **Modern IO:** Use `static import java.lang.IO.*` for simple console output (Java 25+ feature) where dependency injection of a `PrintStream` is not required.

### 2. Core Philosophy
*   **Core Values (in order of priority):**
    1.  **Expressing Intent:** Code must clearly communicate *what* it is doing and *why*.
    2.  **Simplicity:** Prefer the most straightforward implementation that fulfills the intent.
    3.  **Flexibility:** Build for change, but do not sacrifice simplicity for speculative generality.
*   **Zero Dependencies:** Avoid introducing large frameworks. Rely on standard Java APIs (`javax.sound.midi`) and native OS capabilities to keep the binary small.

### 3. Tri-State Deployment Architecture
To maximize both performance and compatibility, Midiraja is distributed via three separate pipelines:
*   **Native (`midra`)**: Built with GraalVM Native Image for the absolute fastest startup times. This is the primary distribution but is heavily tied to the build runner's system libraries (e.g., glibc).
*   **Compatible (`midrac`)**: Built using `jlink` to create a lightweight, self-contained JRE. It utilizes OpenJDK (GraalVM CE/Temurin) and is optimized using Project Leyden (AppCDS) to pre-warm the JVM and FFM bindings. This offers near-native startup speeds while ensuring bulletproof execution on older/fragmented OS environments where `midra` might fail due to C-runtime mismatches.
*   **Cross-Platform (`midrax`)**: The classic "Fat JAR" deployment. It expects the user to provide their own Java 25+ environment.

### 4. Architecture
*   **GraalVM Friendly:** Code MUST avoid dynamic features like runtime reflection (`java.lang.reflect`), dynamic class loading, and dynamic proxies unless explicitly registered in the GraalVM configuration files (`reflect-config.json`, etc.).
*   **Isolate OS Specifics:** Platform-specific code (Windows, macOS, Linux) must be hidden behind interfaces (e.g., `MidiOutProvider`) and instantiated via factories. Do not bleed OS logic into the core engine.
*   **Dependency Inversion:** Concrete implementations depend on abstractions. For example, UI panels must abstract their rendering logic to avoid coupling directly to terminal output streams.

### 5. Language Style & Testing
*   **Modern Java:** Prefer declarative and functional expressions. Use lightweight abstractions like Records and sealed interfaces.
*   **Declarative Style:** Prefer declarative and functional expressions over imperative logic.
*   **Null Safety:** Assume parameters are non-null by default. Use `java.util.Optional` for return types where a value might be legitimately absent.
*   **Immutability:** Prefer `final` for variables, fields, and parameters. Assume parameters are non-null by default, and use `Optional` for absent returns.
*   **Executable Specs:** Treat JUnit 5 tests as comprehensive, executable documentation. All core logic must have corresponding unit tests.
*   **Isolate Side Effects:** Use standard or manual mocks (like `MockMidiProvider` or `MockTerminalIO`) to isolate tests from actual audio hardware or terminal output.

### 6. Memory & Concurrency
*   **Native Memory (FFM):** When interfacing with C libraries, manual memory management via `Arena` is required. Ensure memory is freed securely in `try-with-resources` or `finally` blocks to prevent leaks.
*   **Thread Safety:** Use `volatile` for primitive state variables (like `isPlaying`, `currentBpm`) that are read/written across multiple threads (e.g., input thread vs. playback loop).
*   **Daemon Threads:** Background tasks like UI updates and keyboard listening must be spawned as daemon threads (`setDaemon(true)`) so they do not block the JVM from exiting.

### 7. Terminal UX & Safety
*   **Clean State Restoration:** If altering the terminal state (like entering raw mode or hiding the cursor `\033[?25l`), you must use a `try-finally` block to absolutely restore the original state (`\033[?25h`).
*   **Flicker-Free Rendering:** When updating interactive UI elements, use carriage returns (`\r`) and ANSI "Erase in Line" (`\033[K`) to prevent trailing garbage characters, rather than clearing the entire screen unnecessarily.
*   **Graceful Shutdown:** Always register a shutdown hook (`Runtime.getRuntime().addShutdownHook`) to silence MIDI notes (`provider.panic()`) and restore the terminal on `Ctrl+C`.
*   **User-Facing Errors:** Print actionable error messages to `System.err`. Never expose raw stack traces to the end user unless running in a debug mode.

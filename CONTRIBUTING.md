# Contributing to Midiraja 🎼

Welcome! We are thrilled that you are interested in contributing to Midiraja. 

## Our Vision
Midiraja isn't just another Java application—it's a mission to build a blazing-fast, standalone MIDI player entirely for the terminal. We want to prove that modern Java can deliver lightweight, zero-dependency CLI tools with instant startup times and beautiful Terminal User Interfaces (TUI).

Modern Java has significantly improved code expressiveness, allowing the same problems to be solved more concisely and elegantly. Additionally, the runtime has become leaner, and integration with native systems has been dramatically strengthened. Midiraja aggressively leverages these modern Java advancements to achieve its performance and portability goals. 

## Engineering Documentation

Before diving into the code, the **[Engineering Documentation index](docs/engineering.md)** is the best starting point. It links to whitepapers covering each subsystem — synthesizer engines, the DSP pipeline, FFM native bridges, and the native binary distribution strategy.

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
*   **Brace Style:** We use **Allman style** (opening braces on a new line) for classes, methods, and all control structures. However, **braces can be omitted** for single-line code blocks in `if`, `for`, and `while` loops.
*   **Indentation:** Exactly **4 spaces**. No tabs.
*   **Imports:** 
    *   When importing 3 or more classes from the same package, use a **wildcard import** (`*`).
    *   Prefer **`static import`** for utility classes and frequently used static methods (e.g., `System.out`, `UIUtils.formatTime`) to reduce verbosity.
*   **Java Base:** Follow [Google Java Style](https://google.github.io/styleguide/javaguide.html) for everything else.
*   **C/C++ Base:** Follow [Google C++ Style](https://google.github.io/styleguide/cppguide.html) for everything else.
*   **Modern IO:** Use `static import java.lang.IO.*` for simple console output (Java 25+ feature) where dependency injection of a `PrintStream` is not required.

### 2. Core Philosophy
*   **Core Values (in order of priority):**
    1.  **Expressing Intent:** Code must clearly communicate *what* it is doing and *why*.
    2.  **Simplicity:** Prefer the most straightforward implementation that fulfills the intent.
    3.  **Flexibility:** Build for change, but do not sacrifice simplicity for speculative generality.
*   **Zero Dependencies:** Avoid introducing large frameworks. Rely on standard Java APIs (`javax.sound.midi`) and native OS capabilities to keep the binary small.

### 3. Deployment Architecture
Midiraja is distributed as a native binary:
*   **Native (`midra`)**: Built with GraalVM Native Image for the absolute fastest startup times. Available for macOS Apple Silicon, Linux amd64, and Linux arm64.

### 4. Architecture
*   **GraalVM Friendly:** Code MUST avoid dynamic features like runtime reflection (`java.lang.reflect`), dynamic class loading, and dynamic proxies unless explicitly registered in the GraalVM configuration files (`reflect-config.json`, etc.).
*   **Isolate OS Specifics:** Platform-specific code (Windows, macOS, Linux) must be hidden behind interfaces (e.g., `MidiOutProvider`) and instantiated via factories. Do not bleed OS logic into the core engine.
*   **Dependency Inversion:** Concrete implementations depend on abstractions. For example, UI panels must abstract their rendering logic to avoid coupling directly to terminal output streams.

### 5. Language Style & Testing
*   **Expression over Statement:** Prefer expressions over statements. Favor `switch` expressions, ternary operators, and functional returns over traditional `if/else` statements or `switch` statements with `break`.
*   **Single-line Lambdas:** Lambda expressions must be a **single-line expression**. If you need a block with statements or multiple lines of sequential code, extract the logic into a separate method and use a **method reference** (`::`).
*   **Modern Java:** Use lightweight abstractions like Records and sealed interfaces.
*   **Null Safety:** Assume parameters are non-null by default. Use `java.util.Optional` for return types where a value might be legitimately absent.
*   **Immutability:** Prefer `final` for variables, fields, and parameters.
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

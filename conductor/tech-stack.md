# Technology Stack: Midiraja

## Core Technologies
-   **Language:** Java 25+
-   **Compiler:** GraalVM Native Image (AOT Compilation for minimal startup latency)

## Dependencies
-   **CLI Framework:** `info.picocli:picocli`
-   **Native Interop:** `net.java.dev.jna:jna` (direct mapping to macOS CoreMIDI, Windows WinMM, and Linux ALSA)

## Build System
-   **Tool:** Gradle
-   **Plugins:** `application`, `org.graalvm.buildtools.native`

## CI/CD
-   **Platform:** GitHub Actions
-   **Capabilities:** Automated testing, cross-platform native compilation (macOS, Windows, Ubuntu), and automated release generation.

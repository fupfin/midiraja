# Technology Stack: Midiraja

## Core Technologies
-   **Language:** Java 25+
-   **Compiler:** GraalVM Native Image (AOT Compilation for minimal startup latency)

## Dependencies
-   **CLI Framework:** `info.picocli:picocli`
-   **Native Interop:** Java 22+ Foreign Function & Memory API (`java.lang.foreign`), strictly zero external native wrapper dependencies (JNA has been completely removed).

## Build System
-   **Tool:** Gradle
-   **Plugins:** `application`, `org.graalvm.buildtools.native`
-   **Cross-Compilation Environment:** Docker/Colima with Ubuntu 24.04 for Linux ELF binary generation.

## CI/CD
-   **Platform:** GitHub Actions
-   **Capabilities:** Automated testing, cross-platform native compilation (macOS, Windows, Ubuntu), and automated release generation.

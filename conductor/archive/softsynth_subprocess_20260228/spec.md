# Specification: External Soft Synth Subprocess Integration

## 1. Overview
Introduce the ability to use external command-line software synthesizers (e.g., `fluidsynth`, `timidity`) as MIDI output providers. Midiraja will spawn the synthesizer as a subprocess and pipe raw MIDI bytes directly into its standard input, isolating the complex audio rendering logic from GraalVM's AOT compiler and FFM APIs.

## 2. Functional Requirements
*   **CLI Option:** Introduce a `--soft-synth <command>` option (e.g., `--soft-synth "fluidsynth -i font.sf2 -"`).
*   **Subprocess Management:** 
    *   Implement `SoftSynthProvider` (implementing `MidiOutProvider`) that uses `ProcessBuilder` to launch the provided command.
    *   OS-specific shell wrapping if necessary (e.g., `sh -c` on Unix, `cmd /c` on Windows) to correctly parse complex command strings with arguments.
*   **MIDI Pipelining:** Send all raw MIDI messages (`byte[]`) to the subprocess's `OutputStream` (`stdin`).
*   **Log Capture & TUI Integration:**
    *   Capture the subprocess's `stdout` and `stderr` streams.
    *   Forward this text data to the Midiraja TUI.
    *   Implement a new UI component (e.g., "Synth Log Panel") in the `DashboardUI` to display the external synthesizer's real-time logs without corrupting the terminal drawing state.
*   **Lifecycle Management:**
    *   On application exit or port closure, cleanly close the `stdin` pipe.
    *   Attempt graceful shutdown (`Process.destroy()`).
    *   Wait up to 500ms; if the process is still running, execute a forceful termination (`Process.destroyForcibly()`).

## 3. Testing Specification (Test-Driven Development)
Tests must be written **before** the core implementation and serve as the executable specification.
*   **`SoftSynthProviderTest`**:
    *   Verify that the provider correctly parses the shell command and launches the subprocess.
    *   Verify that `sendMessage` correctly writes bytes to the subprocess's `OutputStream`.
    *   Verify the graceful and forceful shutdown logic when `closePort` is called.
    *   Verify that text output from the subprocess is properly consumed and routed to the logging callback/queue.
*   **`SynthLogPanelTest`**:
    *   Verify the TUI element correctly buffers and renders incoming text lines without overflowing or breaking layout constraints.

## 4. Out of Scope
*   Automatic installation or management of external synthesizer software.
*   Parsing or reacting to the textual content of the logs (they are strictly for display).

## 5. Acceptance Criteria
*   **All tests defined in Section 3 must pass.**
*   User can play a MIDI file using `fluidsynth` via the `--soft-synth` command without JVM/AOT audio errors.
*   The TUI renders correctly and displays log messages emitted by the synthesizer in a dedicated area.
*   When Midiraja is closed (Ctrl+C or natural end), the `fluidsynth` subprocess is successfully terminated and leaves no zombie processes.
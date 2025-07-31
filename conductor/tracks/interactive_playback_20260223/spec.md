# Specification: Interactive Playback Architecture (IoC & DIP)

## Overview
Transform `Midiraja` from a simple, synchronous script-like CLI tool into an event-driven media player. This involves completely overhauling the architecture using Inversion of Control (IoC) to manage playback states asynchronously, and applying the Dependency Inversion Principle (DIP) to abstract Terminal I/O. The goal is to support real-time interactive controls (Seek Forward/Backward, Volume Up/Down) via keyboard arrow keys during playback.

## Functional Requirements

### 1. Architectural Overhaul (IoC & DIP)
- **`PlaybackEngine` (Controller):**
    - Manages the state of the MIDI sequence (Current Tick, Tempo, Playing/Paused).
    - Exposes asynchronous API methods for user input events (e.g., `seek(int ticks)`, `adjustVolume(int percent)`).
- **`TerminalIO` (Interface - DIP):**
    - Abstract representation of keyboard input and console output.
    - Fulfills the Dependency Inversion Principle, allowing tests to inject a `MockTerminalIO` without needing a real terminal or JLine.
- **`InputListener` (Observer):**
    - A mechanism to hook asynchronous keystroke events into the `PlaybackEngine`.

### 2. JLine3 Integration
- Integrate `org.jline:jline` to handle Raw Terminal Mode.
- Listen for non-blocking arrow key events:
    - `UP`: Volume Up (+5%)
    - `DOWN`: Volume Down (-5%)
    - `RIGHT`: Seek Forward (+5 seconds)
    - `LEFT`: Seek Backward (-5 seconds)
    - `q` or `ESC`: Quit gracefully.

### 3. MIDI Chasing Logic (Seek)
- **Problem:** Jumping forward or backward in a MIDI sequence breaks the state (missing Program Changes, stuck Sustain Pedals).
- **Solution:** When a user seeks to a new tick $T$:
    1. Send "All Notes Off" (Panic) to stop hanging notes.
    2. Instantly fast-forward from tick `0` to $T$, sending ONLY state-critical events (Program Change `0xC0`, Control Change `0xB0`, Pitch Bend `0xE0`). Skip all Note On (`0x90`) events.
    3. Resume normal playback from tick $T$.

## Acceptance Criteria
- Unit tests can mock `TerminalIO` to programmatically trigger seek and volume events, verifying the `PlaybackEngine` changes its internal tick without crashing.
- During actual playback, pressing UP/DOWN dynamically alters the volume scale.
- Pressing LEFT/RIGHT jumps the audio and SC-55 UI seamlessly without leaving stuck, ringing notes.
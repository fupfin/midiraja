# TUI MVC & Responsive Component Architecture Specification

## Overview
Currently, Midiraja's Full-Screen TUI is rendered using a single, hardcoded `uiLoop` containing procedural `StringBuilder` calls. As the TUI adds more complex layouts (two columns, dynamic hiding), this approach is unmaintainable. 

This track aims to refactor the UI layer into a true **MVC architecture** by separating the `PlaybackEngine` (Model) from the rendering logic (View). Furthermore, the View will be composed of independent, responsive **Panels**. Each panel will dynamically negotiate its height based on the total available terminal rows.

## Component (Panel) Responsibilities & Responsive Behavior

The Dashboard UI will be divided into modular panels. Each panel must support dynamic rendering based on the vertical space allocated to it by a layout manager.

### 1. Metadata Panel (Title, Copyright, Texts)
*   **Behavior:** Displays song metadata (extracted from the MIDI file).
*   **Responsive Rule:** Consumes whatever lines are allocated to it. If 3 lines are given, it prints the first 3 lines of metadata. If 1 line is given, it prints a truncated title.

### 2. Status Panel (Progress Bar, Speed, Volume)
*   **Behavior:** Displays the real-time playback state.
*   **Responsive Rules:**
    *   **Max (5 Lines):** Current full layout (Title, Tempo/Speed, Time/Bar, Transpose, Volume, Port).
    *   **Medium (2-3 Lines):** Compressed layout. Consolidates Tempo, Volume, Transpose into one line, and Time/Bar on another.
    *   **Min (1 Line):** Ultra-compressed layout. Mirrors the "Classic CLI" format: `[Playing] 01:20/03:40 [==>-] Vol:100% Spd:1.0x`.

### 3. MIDI Channels Activity Panel
*   **Behavior:** Displays real-time VU meters for the 16 MIDI channels.
*   **Responsive Rules:**
    *   **Primary (16 Lines):** Classic vertical list of 16 channels.
    *   **Fallback (3-4 Lines):** Horizontal arrangement. Channels are grouped horizontally (e.g., 4 columns of 4 rows, or simple horizontal blocks) to save vertical space when the terminal is short.

### 4. Controls Panel
*   **Behavior:** Displays keyboard shortcut instructions.
*   **Responsive Rules:**
    *   **Primary (3 Lines):** Current full layout with boxes.
    *   **Min (1 Line):** Condensed list: `[Space]Pause [< >]Skip [+-]Trans [^v]Vol [Q]Quit`.

## Layout Negotiation Rules (Priority Algorithm)
When the terminal size changes (or on every frame), the Layout Manager will calculate heights based on the following strict priority:

1.  **Attempt Primary Layout:**
    *   Assign the `Channels Panel` its Primary height (16 lines).
    *   Distribute the remaining lines equally among `Metadata`, `Status`, and `Controls`.
    *   *Condition:* If the distributed height for `Status` is >= 1 and `Controls` is >= 1, this layout is accepted.
2.  **Attempt Fallback Layout (Horizontal Channels):**
    *   If Rule 1 fails (terminal is too short), force the `Channels Panel` into Fallback mode (approx 4 lines).
    *   Recalculate remaining space and distribute it again.
3.  **Absolute Minimum Clamp:**
    *   If Rule 2 still results in negative or 0 lines for critical panels (e.g., terminal height < 10), assign every panel its absolute `Min` height (1 line) regardless of clipping. The user must expand the terminal to see everything, but the app will not crash or throw exceptions.

## Out of Scope
*   Adding new UI features (e.g., Lyrics, EQ) beyond refactoring existing components.
*   Modifying the MIDI playback logic or the core FFM integration.
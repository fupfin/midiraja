# TUI Responsive Layout & Dynamic Resizing Specification

## Overview
The current Full-Screen Terminal User Interface (TUI) for Midiraja is statically sized and prone to breaking on smaller terminals. It does not adapt to the user's terminal window dimensions, making it cumbersome for general use. This track aims to refactor the TUI to be fully responsive: it must define minimum viable dimensions, adapt its internal components based on available screen space, and dynamically re-render itself when the terminal window is resized during playback.

## Functional Requirements
1.  **Terminal Dimension Querying:** The `TerminalIO` abstraction must be capable of retrieving the current width (columns) and height (rows) of the terminal.
2.  **Dynamic Resize Handling:** The TUI rendering loop (`uiLoop`) must detect terminal size changes on the fly and adjust its layout calculations dynamically without breaking the UI or throwing exceptions.
3.  **Minimum Size Enforcement:**
    *   Establish a minimum viable terminal size (e.g., 80 columns x 24 rows).
    *   If the terminal is smaller than the minimum, display a graceful fallback message (e.g., "Terminal too small. Minimum size: 80x24. Current: [WxH]") instead of attempting to draw the full dashboard.
4.  **Responsive Layout Adaptation:**
    *   **Progress Bar:** The width of the playback progress bar must scale horizontally based on the available terminal columns.
    *   **Separators:** Horizontal separator lines (`----` and `====`) must scale to fill the exact terminal width.
    *   **Playlist Panel:** The number of visible playlist items should adapt vertically if there is excess vertical space, or be truncated/hidden if vertical space is constrained but above the absolute minimum.
    *   **Text Truncation:** Long strings (Titles, File names) must be truncated intelligently based on the terminal width to prevent line wrapping, which breaks the UI layout.

## Non-Functional Requirements
*   **Performance:** Terminal size querying must not introduce significant latency in the 20 FPS `uiLoop`. Caching or efficient polling via JLine should be utilized.
*   **Aesthetics:** The UI should maintain a clean, aligned, and professional look regardless of how wide or tall the terminal becomes.

## Acceptance Criteria
*   [ ] Running `midra` in an 80x24 terminal renders the dashboard correctly without text wrapping.
*   [ ] Running `midra` in a 120x40 terminal utilizes the extra width to show longer progress bars and longer titles.
*   [ ] Resizing the terminal window vertically or horizontally while a song is playing updates the UI elements seamlessly without restarting the application or leaving visual artifacts.
*   [ ] Shrinking the terminal below the minimum dimensions replaces the dashboard with a clear warning message.

## Out of Scope
*   Adding new functional panels to the UI.
*   Refactoring the PlaybackEngine to the new MVC architecture (This is handled in a separate track).
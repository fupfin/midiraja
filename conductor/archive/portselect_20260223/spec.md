# Specification: Interactive Port Selection & Smart Matching

## Overview
This track enhances the user experience of Midiraja by adding flexible MIDI port selection capabilities. Instead of strictly requiring an exact port index, users will be able to specify a port by partial name. Additionally, if the user omits the `--port` argument altogether, the CLI will enter an interactive mode, listing available ports and prompting the user to select one dynamically.

## Functional Requirements
- **Smart Port Matching:**
    - The `--port` (`-p`) CLI argument must accept both integer indices and string names.
    - If a string is provided, the tool must perform a case-insensitive search for a partial match against the names of all available MIDI output ports.
    - If multiple ports match the partial string, display an error indicating ambiguity.
- **Interactive Selection:**
    - If the user provides a MIDI file but omits the `--port` argument, the tool MUST NOT exit with an error.
    - Instead, it must print the list of available ports with their indices to the standard output.
    - It must then prompt the user (e.g., `Select port [0-N]: `) and read an integer input from the standard input (terminal).
    - If the user provides an invalid input or presses `Ctrl+C`, it should exit gracefully.

## Acceptance Criteria
- Running `midra song.mid -p "fluid"` successfully connects to "FluidSynth virtual port (36288)".
- Running `midra song.mid` displays a list of ports and waits for the user to type a number (e.g., `3`) and press Enter.
- An ambiguous partial name (e.g., "port" matching "port 1" and "port 2") results in an error message listing the matches.

## Out of Scope
- Arrow-key navigation (ncurses-style TUI) is considered out of scope for this track to keep the implementation simple and dependency-free, relying instead on standard integer input prompts.

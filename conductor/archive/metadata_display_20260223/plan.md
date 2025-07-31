# Implementation Plan: MIDI Metadata Extraction & Display

## Phase 1: Meta Event Parsing
- [x] Task: Create a private method `extractAndPrintMetadata(Sequence sequence)` in `MidirajaCommand.java`.
- [x] Task: Inside `extractAndPrintMetadata`, iterate over all tracks and events in the `Sequence`.
- [x] Task: Intercept `MetaMessage` events (status `0xFF`).
- [x] Task: Decode byte arrays to Strings for specific types: `0x03` (Sequence/Track Name), `0x02` (Copyright), and `0x01` (Text).

## Phase 2: Formatted Output & Integration
- [x] Task: Filter and deduplicate extracted strings (e.g., storing the first valid title, copyright, and up to 3 info texts).
- [x] Task: Print the collected metadata neatly to `System.out` with an indentation (e.g., `  Title: ...`) right after the `Started playing: ...` line in `playMidiWithProvider`.
- [x] Task: Ensure the `uiThread` for the SC-55 display starts *after* the metadata has been fully printed so the `` cursor reset doesn't overwrite the metadata.
- [x] Task: Conductor - User Manual Verification 'Phase 2: Formatted Output & Integration' (Protocol in workflow.md)
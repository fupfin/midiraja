# Product Guidelines: Midiraja

## UX Principles
1.  **Visual Feedback:** During playback, the CLI should provide a clear, non-intrusive progress bar or status indicator to keep the user informed.
2.  **Graceful Shutdown:** The tool MUST reliably trap interrupt signals (e.g., `Ctrl+C`). It must send "All Notes Off" (Panic) messages to the MIDI port to prevent hanging notes and ensure all native resources are cleanly released.
3.  **Discoverability:** The CLI should offer comprehensive help documentation (`--help`) with clear examples of all available flags and options.

## Tone & Output Style
-   **Concise and Direct:** Output should be limited to factual statements, such as the port being opened or the file being played. Avoid conversational filler or unnecessary verbosity.

## Error Handling
-   **Strict Failure:** If an invalid argument is provided (e.g., a missing file or an out-of-bounds port index), the tool should fail fast, print a clear error message to `stderr`, and exit with a non-zero status code.

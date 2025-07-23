# Implementation Plan: Interactive Port Selection & Smart Matching

## Phase 1: Smart Port Selection Logic
- [ ] Task: Modify `MidirajaCommand` to accept `--port` as a `String` instead of an `Integer`.
    - [ ] Update field type.
    - [ ] Add `findPortIndex` method that accepts the input string and a list of `MidiPort`s, and returns the matching index.
- [ ] Task: Implement partial matching (Smart Matching) logic in `findPortIndex`.
    - [ ] Handle exact integer matching.
    - [ ] Handle partial case-insensitive string matching.
    - [ ] Add validation to fail on ambiguous matches (multiple matches found).
- [ ] Task: Conductor - User Manual Verification 'Phase 1: Smart Port Selection Logic' (Protocol in workflow.md)

## Phase 2: Interactive CLI Prompts
- [ ] Task: Implement interactive port selection fallback.
    - [ ] If `portSpec` is null, list available ports to `System.out`.
    - [ ] Prompt the user using `System.console().readLine()` or `Scanner(System.in)`.
    - [ ] Read the user's input, parse it as an integer, and attempt to open the corresponding port.
    - [ ] Handle `Ctrl+C` or invalid integer input during the prompt.
- [ ] Task: Conductor - User Manual Verification 'Phase 2: Interactive CLI Prompts' (Protocol in workflow.md)

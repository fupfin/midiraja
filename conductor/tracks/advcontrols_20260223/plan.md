# Implementation Plan: Advanced MIDI Controls (Volume & Transpose)

## Phase 1: CLI Arguments & Parameter Validation
- [x] Task: Define new CLI options in `MidrajaCommand.java`. bae1e4c
    - [ ] Write Tests: Create `MidrajaCommandTest` to verify that `--volume` and `--transpose` arguments are correctly parsed.
    - [ ] Implement: Add `@Option` for volume and transpose in `MidrajaCommand.java`.
    - [ ] Code Review: Submit changes for review to improve design and naming.
- [x] Task: Implement input validation for volume and transpose. b1f5306
    - [ ] Write Tests: Add test cases for out-of-range volume (e.g., 150) and invalid transpose formats.
    - [ ] Implement: Add validation logic in the `call()` method or via Picocli validation.
    - [ ] Code Review: Submit changes for review to improve validation logic.
- [x] Task: Conductor - User Manual Verification 'Phase 1: CLI Arguments & Parameter Validation' (Protocol in workflow.md) [checkpoint: 89e614e]

## Phase 2: Volume Control Implementation
- [x] Task: Implement global volume setting in `MidiOutProvider`. c4f6875
    - [ ] Write Tests: Create a mock `MidiOutProvider` and verify that `openPort` sends CC 7 messages to all 16 channels when a volume is set.
    - [ ] Implement: Update `MidiOutProvider` interface and OS-specific implementations to handle initial volume.
    - [ ] Code Review: Submit changes for review to improve the abstraction and OS-specific implementations.
- [x] Task: Integrate volume control into playback flow. cc22ff1
    - [ ] Write Tests: Verify that the playback loop initializes volume before sending the first note.
    - [ ] Implement: Update `MidrajaCommand.playMidiWithProvider` to call volume initialization.
    - [ ] Code Review: Submit changes for review to ensure clean integration into the playback loop.
- [x] Task: Conductor - User Manual Verification 'Phase 2: Volume Control Implementation' (Protocol in workflow.md) [checkpoint: 7508a3b]

## Phase 3: Transposition Implementation
- [x] Task: Implement note transposition logic in the playback loop. 8ed1b92
    - [ ] Write Tests: Create a test case that verifies a Note On message (e.g., 60) is shifted to 62 when transpose is +2, and remains 60 on Channel 10.
    - [ ] Implement: Modify the message processing logic in `MidrajaCommand.playMidiWithProvider` to apply the transpose offset to Note On/Off messages.
    - [ ] Code Review: Submit changes for review to improve transposition logic and "smart" channel handling.
- [x] Task: Conductor - User Manual Verification 'Phase 3: Transposition Implementation' (Protocol in workflow.md) [checkpoint: f90ecb9]

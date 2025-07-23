# Specification: Advanced MIDI Controls (Volume & Transpose)

## Overview
This track adds advanced MIDI playback controls to the Midiraja CLI tool. Specifically, it introduces the ability to control global playback volume and transpose note pitches in real-time or via initial CLI arguments.

## Functional Requirements
- **Volume Control (`--volume`):**
    - The user can specify a volume level from 0 to 127.
    - Before playback starts, the tool should send a MIDI Control Change message (CC 7 - Main Volume) to all 16 MIDI channels with the specified value.
- **Transposition (`--transpose`):**
    - The user can specify a transposition offset in semitones (e.g., `+2`, `-5`).
    - During playback, the pitch value of all Note On and Note Off messages should be shifted by the specified offset.
    - **Smart Transposition:** MIDI Channel 10 (percussion) MUST NOT be transposed.
- **CLI Integration:**
    - Add `--volume` (or `-v`) option to the `midra` command.
    - Add `--transpose` (or `-t`) option to the `midra` command.

## Acceptance Criteria
- Running `midra song.mid --port 3 --volume 64` starts playback with half-volume across all channels.
- Running `midra song.mid --port 3 --transpose 12` plays the song one octave higher.
- Transposition does not affect drum sounds on Channel 10.
- Invalid inputs (e.g., volume 200 or transpose "abc") result in a clear error message.

## Out of Scope
- System Exclusive (SysEx) support.
- Real-time volume/transpose control via keyboard input during playback (this can be a separate track).

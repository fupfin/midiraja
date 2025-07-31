# Specification: MIDI Metadata Extraction & Display

## Overview
This track enhances the playback startup sequence by extracting and displaying useful metadata embedded within the MIDI file (e.g., Sequence/Track Name, Copyright Notice, and generic Text Events) before the real-time playback UI begins.

## Functional Requirements
- **Metadata Extraction:**
    - Parse the MIDI `Sequence` before entering the playback loop.
    - Specifically look for `MetaMessage` events (status byte `0xFF`).
    - Extract strings from the following common Meta event types:
        - `0x03` (Sequence/Track Name): Often the title of the song.
        - `0x02` (Copyright Notice): Often contains the creation year and author.
        - `0x01` (Text Event): General comments, author info, or lyrics (take the first few).
- **Console Display:**
    - Filter out empty, purely whitespace, or garbage strings.
    - Print the extracted metadata neatly formatted below the `Started playing: ...` line and above the dynamic `Vol:[...]` progress bar.
    - Example Output:
      ```
      Started playing: Ys2-OverDrive_GM-V2.mid to FluidSynth virtual port
        Title: Ys II - Over Drive
        Copyright: (C) Nihon Falcom 1988
        Info: Arranged by ...
      Vol:[▃ █   ▂ ▅   ▇       ▂   ]  0% (BPM: 156.0)
      ```
- **Robustness:**
    - Ensure string decoding handles common ASCII/UTF-8 gracefully without crashing on malformed binary data sometimes mistakenly stuffed into Text events by old sequencers.

## Acceptance Criteria
- Running a `.mid` file with known embedded metadata prints the Title and Copyright fields neatly.
- Files without metadata do not print blank lines or cause errors (they just silently skip the metadata section).
- The dynamic `Vol:[...]` progress bar still functions flawlessly below the metadata block.
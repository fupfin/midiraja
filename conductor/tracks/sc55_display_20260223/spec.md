# Specification: SC-55 Style Real-Time Part Level Meter

## Overview
This track replaces the static progress bar with a dynamic, real-time animation reminiscent of the Roland SC-55 LCD display. It will visually represent the playback activity of all 16 MIDI channels simultaneously using Unicode block characters to simulate audio level meters.

## Functional Requirements
- **Level Tracking:**
    - Capture `Note On` (0x90) events with velocity > 0 across all 16 channels.
    - Map the velocity (0-127) to a channel level (0.0 to 1.0).
- **UI Rendering:**
    - Spawn a dedicated, lightweight daemon thread for UI updates to decouple it from the strict timing requirements of the MIDI playback loop.
    - Render a single line in the terminal at approximately 30 FPS.
    - Represent levels using 8 tiers of Unicode vertical blocks: ` `, `▂`, `▃`, `▄`, `▅`, `▆`, `▇`, `█`.
    - Example Output: `[CH 1-16] ▃ █   ▂ ▅   ▇       ▂   | 50% (BPM: 120)`
- **Natural Decay (Envelope):**
    - Implement a natural decay over time (e.g., subtracting a fixed amount every 30ms) so the meters "bounce" back down realistically rather than just snapping to zero or staying at peak.

## Acceptance Criteria
- While a MIDI file is playing, 16 vertical bars bounce up and down corresponding to the active instruments.
- The UI does not cause flickering or scrolling (uses ``).
- The overall playback progress percentage and BPM are still visible alongside the meters.
- The UI thread cleanly stops when playback is finished.
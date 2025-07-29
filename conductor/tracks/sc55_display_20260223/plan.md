# Implementation Plan: SC-55 Style Real-Time Part Level Meter

## Phase 1: Track Note On Velocity
- [ ] Task: Remove old `printProgressBar` logic and variables.
- [ ] Task: Introduce an array `double[] channelLevels = new double[16]` and shared atomic/volatile variables for `currentTick` and `currentBPM` in `MidirajaCommand`.
- [ ] Task: Intercept `Note On` (`0x90` with velocity > 0) in the `playMidiWithProvider` loop to increase `channelLevels[channel]` based on `velocity / 127.0`.

## Phase 2: Render Real-Time SC-55 UI
- [ ] Task: Implement a daemon thread (`uiThread`) that starts before the playback loop.
- [ ] Task: The `uiThread` loop will run at ~30 FPS (sleep 33ms), build the SC-55 string using Unicode blocks (` , ▂, ▃, ▄, ▅, ▆, ▇, █`), decay the levels, and print it using ``.
- [ ] Task: Ensure `uiThread` is gracefully stopped using a shared boolean flag when the song finishes.
- [ ] Task: Conductor - User Manual Verification 'Phase 2: Render Real-Time SC-55 UI' (Protocol in workflow.md)
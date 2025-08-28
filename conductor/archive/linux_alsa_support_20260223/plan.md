# Plan: Linux ALSA Support & Docker Build

## Phase 1: Docker Build Environment Setup
- [x] Task: Create `Dockerfile.linux` based on Ubuntu 24.04 with GraalVM 25, Gradle, and `libasound2-dev`.
- [x] Task: Create a helper script `scripts/docker-build.sh` to build and test the project inside Colima.
- [x] Task: Verify that `MidirajaCommand` can run (as a stub) inside the container.

## Phase 2: ALSA JNA Mapping
- [x] Task: Define `AlsaLibrary` JNA interface for `libasound.so.2`. (Completed via FFM)
- [x] Task: Map core ALSA structures (e.g., `snd_seq_addr_t`, `snd_seq_ev_note_t`, `snd_seq_event_t`). (Completed via FFM)
- [x] Task: Implement `AlsaLibrary` functions: `snd_seq_open`, `snd_seq_create_simple_port`, `snd_seq_connect_to`, etc. (Completed via FFM)

## Phase 3: LinuxProvider Implementation
- [x] Task: Implement `getOutputPorts()` by querying ALSA clients and ports. (Completed via FFM)
- [x] Task: Implement `openPort()` to establish a connection between `midra`'s virtual port and the target device. (Completed via FFM)
- [x] Task: Implement `sendMessage()` using ALSA event encoding and `snd_seq_drain_output`. (Completed via FFM)
- [x] Task: Implement `panic()` using ALSA's `SND_SEQ_EVENT_CONTROLLER` (CC 120/123). (Completed via FFM)

## Phase 4: Linux Native Image & Verification
- [x] Task: Run `native-image-agent` inside Docker to generate `reflect-config.json` for JNA/ALSA structures. (Obsoleted by FFM reachability metadata)
- [x] Task: Perform Linux Native Image compilation via Docker.
- [x] Task: (Optional) Set up `snd-virmidi` in Colima to test actual MIDI message delivery if possible.
- [x] Task: Conductor - User Manual Verification (Protocol in workflow.md).

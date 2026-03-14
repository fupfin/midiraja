# Native Audio Bridge Engineering

**Status:** Implementation Complete  
**Context:** Midiraja integrates several industry-standard C/C++ audio emulation libraries to achieve authentic sound. To bind these libraries to the Java Virtual Machine without the immense overhead of traditional JNI, Midiraja leverages the modern **Java Foreign Function & Memory (FFM) API** (JEP 454).

This document outlines the three distinct architectural patterns used to safely and efficiently bridge the Java sequencer with these native C-libraries, solving unique problems of thread safety, synchronization, and latency.

---

## 1. The Queue-and-Drain Pattern
**Target Engines:** `OPL` (libADLMIDI), `OPN` (libOPNMIDI), `TSF` (TinySoundFont)

### The Problem
Both `libADLMIDI` and `libOPNMIDI` are highly optimized state machines that are strictly **not thread-safe**. If the high-priority Java MIDI sequencer thread sends a `noteOn` command while the background audio thread is concurrently rendering PCM samples via `generate()`, the native memory will immediately corrupt, resulting in a segmentation fault that crashes the entire JVM.

### The Solution
We implemented a non-blocking **Queue-and-Drain** architecture:
1. **The Interceptor:** The FFM bridge intercepts all incoming MIDI byte arrays from the sequencer thread and offers them to a lock-free `ConcurrentLinkedQueue<byte[]>`.
2. **The Drainer:** Before the render thread requests the next block of audio from the native C API, it aggressively polls the queue and dispatches all pending MIDI events into the C state.
3. **Result:** State mutation and PCM generation are serialized exclusively on the render thread, mathematically guaranteeing thread safety without a single mutex lock.

**TinySoundFont (`tsf`) note:** TinySoundFont follows exactly the same Queue-and-Drain pattern, but ships as a **single-header C library** (`tsf.h`) with no external dependencies and no cmake build. The wrapper is a three-line C file:
```c
#define TSF_IMPLEMENTATION
#include "tsf.h"
```
This is compiled to `libtsf.dylib` / `libtsf.so` by a single `gcc -shared` call in `scripts/build-native-libs.sh`. The FFM binding (`FFMTsfNativeBridge`) loads it via the standard `tryLoadLibrary` helper in `AbstractFFMBridge`, identical to how ADL and OPN bridges are loaded.

---

## 2. The Wall-Clock Synchronization Pattern
**Target Engine:** `Munt` (MT-32 Emulator)

### The Problem
The Roland MT-32 uses complex Linear Arithmetic (LA) synthesis. The attack envelopes of its instruments are heavily reliant on precise, sample-accurate timing. If MIDI events are dispatched asynchronously whenever the Java thread wakes up, OS jitter will cause the attack transients to sound "smeared" or slightly off-beat.

### The Solution
Munt provides a highly advanced C-API that accepts MIDI events with **future sample timestamps**. Midiraja utilizes this by calculating a deterministic wall-clock delta:
1. **Timestamp Calculation:** When the Java sequencer thread fires an event, the bridge calculates exactly how many nanoseconds have passed since the last audio block was rendered, and converts that time into a precise future sample offset.
2. **Queueing in the Future:** The event is sent to Munt's internal thread-safe queue via `mt32emu_play_msg_at(context, msg, future_timestamp)`.
3. **Result:** Even if the Java render thread is temporarily blocked or delayed by the OS, the C++ Munt engine will flawlessly place the note at the exact sample index requested, ensuring perfect, jitter-free rhythm regardless of JVM performance.

---

## 3. The Driver Delegation Pattern
**Target Engine:** `FluidSynth` (General MIDI / SoundFonts)

### The Problem
FluidSynth is a massive, highly complex sample-playback synthesizer. Re-implementing an audio streaming loop to pull PCM data from FluidSynth into Java just to push it back down to the OS audio driver would add unnecessary CPU overhead, memory marshaling costs, and latency.

### The Solution
Because FluidSynth is inherently thread-safe and includes its own robust native audio drivers (CoreAudio for Mac, ALSA for Linux, DirectSound for Windows), we use the **Driver Delegation** pattern.
1. **Direct FFM Binding (Zero-JNI):** We use `MethodHandle.invokeExact()` to bind directly to `fluid_synth_noteon` and `fluid_synth_noteoff`.
2. **Native Execution:** Midiraja never touches the PCM audio data. We simply construct the `fluid_synth_t` and `fluid_audio_driver_t` objects in native memory using FFM `Arena.ofShared()`, and tell FluidSynth to start its own internal audio thread.
3. **Result:** The Java sequencer acts merely as a lightweight remote control. When a note is played, the message travels directly from Java to the C API, and FluidSynth's internal native thread instantly mixes and sends the audio to the OS hardware. This achieves the absolute lowest possible latency and maximum performance.

---

## Conclusion
By utilizing the modern FFM API, Midiraja avoids the rigid constraints of traditional JNI bindings. By dynamically applying different architectural patterns—Queue-and-Drain for legacy state machines, Wall-Clock Sync for precise synthesizers, and Driver Delegation for self-contained giants—the engine achieves a perfect balance of safety, accuracy, and extreme low-latency performance.

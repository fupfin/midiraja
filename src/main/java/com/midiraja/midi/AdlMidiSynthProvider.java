/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.midiraja.midi;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * SoftSynthProvider backed by libADLMIDI (OPL2/OPL3 FM synthesis).
 *
 * <h3>Thread model</h3>
 * libADLMIDI is NOT thread-safe and has no timestamped MIDI API.
 * Solution: queue raw MIDI bytes from the playback thread, drain them in the
 * render thread before each {@code adl_generate()} call.
 *
 * <pre>
 * PlaybackEngine (playback thread)     Render thread
 *   │                                      │
 *   ├─ sendMessage(bytes)                  │
 *   │   └─ eventQueue.offer(bytes) ──────► │
 *   │                                 poll events → noteOn/noteOff/...
 *   │                                 adl_generate(device, 512, buffer)
 *   │                                 audio.push(buffer)
 * </pre>
 *
 * Latency: at most 1 render buffer (~11.6 ms at 512 frames / 44100 Hz).
 */
@SuppressWarnings({"ThreadPriorityCheck", "EmptyCatch"})
public class AdlMidiSynthProvider implements SoftSynthProvider
{
    private final AdlMidiNativeBridge bridge;
    private final @org.jspecify.annotations.Nullable AudioEngine audio;

    // Raw MIDI bytes queued by the playback thread; drained by the render thread.
    private final ConcurrentLinkedQueue<byte[]> eventQueue = new ConcurrentLinkedQueue<>();

    private @org.jspecify.annotations.Nullable Thread renderThread;
    private volatile boolean running = false;
    // Set to true while prepareForNewTrack() is cycling synth state.
    // The render thread spins instead of calling generate(), keeping it safe to
    // call bridge.panic() / bridge.reset() from the provider thread.
    private volatile boolean renderPaused = false;

    private final int emulatorId;
    private final int numChips;

    /** Uses Nuked OPL3 (emulator 0) and 4 chips by default. */
    public AdlMidiSynthProvider(
        AdlMidiNativeBridge bridge, @org.jspecify.annotations.Nullable AudioEngine audio)
    {
        this(bridge, audio, 0, 4);
    }

    public AdlMidiSynthProvider(AdlMidiNativeBridge bridge,
        @org.jspecify.annotations.Nullable AudioEngine audio, int emulatorId, int numChips)
    {
        this.bridge = bridge;
        this.audio = audio;
        this.emulatorId = emulatorId;
        this.numChips = numChips;
    }

    private static final int SAMPLE_RATE = 44100;
    private static final int FRAMES_PER_RENDER = 512; // ~11.6 ms per chunk
    private static final int RING_BUFFER_CAPACITY_FRAMES = 4096;

    @Override public long getAudioLatencyNanos()
    {
        if (audio == null)
            return 0L;
        long totalFrames = (long) RING_BUFFER_CAPACITY_FRAMES + audio.getDeviceLatencyFrames();
        return totalFrames * 1_000_000_000L / SAMPLE_RATE;
    }

    private static final String[] EMULATOR_NAMES = {
        "Nuked OPL3 v1.8", // 0
        "Nuked OPL3 v1.7.4", // 1
        "DosBox", // 2
        "Opal", // 3
        "Java", // 4
        "ESFMu", // 5
        "MAME OPL2", // 6
        "YMFM OPL2", // 7
        "YMFM OPL3", // 8
    };

    @Override public List<MidiPort> getOutputPorts()
    {
        String emuName = (emulatorId >= 0 && emulatorId < EMULATOR_NAMES.length)
            ? EMULATOR_NAMES[emulatorId]
            : "Emulator " + emulatorId;
        String portName = emuName + " · " + numChips + " chip" + (numChips > 1 ? "s" : "");
        return List.of(new MidiPort(0, portName));
    }

    @Override public void openPort(int portIndex) throws Exception
    {
        bridge.init(SAMPLE_RATE);
        bridge.switchEmulator(emulatorId);
        bridge.setNumChips(numChips);
    }

    @Override public void loadSoundbank(String path) throws Exception
    {
        if (path.startsWith("bank:"))
        {
            int bankNum = Integer.parseInt(path.substring(5));
            bridge.setBank(bankNum);
        }
        else
        {
            bridge.loadBankFile(path);
        }

        if (audio != null)
        {
            audio.init(SAMPLE_RATE, 2, RING_BUFFER_CAPACITY_FRAMES);
            startRenderThread();
        }
    }

    private void startRenderThread()
    {
        running = true;
        renderThread = new Thread(() -> {
            // Buffer: FRAMES_PER_RENDER stereo frames = FRAMES_PER_RENDER * 2 shorts
            short[] pcmBuffer = new short[FRAMES_PER_RENDER * 2];

            while (running)
            {
                // Spin while prepareForNewTrack() is cycling synth state.
                if (renderPaused)
                {
                    try
                    {
                        Thread.sleep(1);
                    }
                    catch (InterruptedException e)
                    {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }

                // Drain MIDI events before generating so notes land on the correct frame.
                byte[] event;
                while ((event = eventQueue.poll()) != null)
                {
                    dispatchToNative(event);
                }

                // Pull rendered PCM from libADLMIDI
                bridge.generate(pcmBuffer, FRAMES_PER_RENDER);

                // Push to miniaudio ring buffer (blocks if full, pacing the thread)
                if (audio != null)
                {
                    audio.push(pcmBuffer);
                }
                else
                {
                    try
                    {
                        Thread.sleep(10);
                    }
                    catch (InterruptedException e)
                    {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        });
        renderThread.setPriority(Thread.MAX_PRIORITY);
        renderThread.setDaemon(true);
        renderThread.start();
    }

    /**
     * Parses raw MIDI bytes and dispatches to the appropriate bridge method.
     * Called exclusively from the render thread.
     */
    private void dispatchToNative(byte[] data)
    {
        if (data == null || data.length == 0)
            return;

        int status = data[0] & 0xFF;
        if (status >= 0xF0)
        {
            if (data.length > 1)
                bridge.systemExclusive(data);
            return;
        }

        int command = status & 0xF0;
        int channel = status & 0x0F;

        if (data.length < 2)
            return;
        int data1 = data[1] & 0xFF;
        int data2 = (data.length >= 3) ? (data[2] & 0xFF) : 0;

        switch (command)
        {
            case 0x90 -> bridge.noteOn(channel, data1, data2);
            case 0x80 -> bridge.noteOff(channel, data1);
            case 0xB0 -> bridge.controlChange(channel, data1, data2);
            case 0xC0 -> bridge.patchChange(channel, data1);
            case 0xE0 -> bridge.pitchBend(channel, (data2 << 7) | data1);
        }
    }

    @Override public void sendMessage(byte[] data) throws Exception
    {
        if (data == null || data.length == 0)
            return;
        // Clone to prevent the caller from mutating the array after enqueue
        eventQueue.offer(data.clone());
    }

    @Override public void panic()
    {
        // Clear old-song events so they don't play at the start of the next song
        eventQueue.clear();
        // Queue note-offs for all channels; the render thread drains these on its next cycle.
        // Do NOT call bridge.panic() here — libADLMIDI is not thread-safe and the render thread
        // may be inside adl_generate(). bridge.panic() is called from prepareForNewTrack()
        // after the render thread is safely paused.
        for (int ch = 0; ch < 16; ch++)
        {
            try
            {
                eventQueue.offer(new byte[] {(byte) (0xB0 | ch), 64, 0}); // Sustain Off
                eventQueue.offer(new byte[] {(byte) (0xB0 | ch), 123, 0}); // All Notes Off
                eventQueue.offer(new byte[] {(byte) (0xB0 | ch), 120, 0}); // All Sound Off
                eventQueue.offer(new byte[] {(byte) (0xB0 | ch), 121, 0}); // Reset All Controllers
            }
            catch (Exception ignored)
            {
            }
        }
        if (audio != null)
        {
            audio.flush();
        }
    }

    @Override public void prepareForNewTrack(javax.sound.midi.Sequence sequence)
    {
        // Step 1: Pause render thread (gives it up to 20 ms to finish current generate)
        renderPaused = true;
        if (audio != null)
        {
            try
            {
                Thread.sleep(20);
            }
            catch (InterruptedException ignored)
            {
            }
        }

        // Step 2: Clear stale events from the previous song
        eventQueue.clear();

        // Step 3: Native panic — safe here because the render thread is paused (or not started)
        bridge.panic();

        // Step 4: Flush old audio from the ring buffer
        if (audio != null)
        {
            audio.flush();
        }

        // Step 5: Reset synth state for the new song
        bridge.reset();

        // Leave renderPaused = true; onPlaybackStarted() will resume the render thread
    }

    @Override public void onPlaybackStarted()
    {
        // Resume render thread — it will start filling the ring buffer with real audio
        // immediately, so the first note is heard within one render buffer (~11.6 ms)
        renderPaused = false;
    }

    @Override public void closePort()
    {
        running = false;
        if (renderThread != null)
        {
            renderThread.interrupt();
            try
            {
                renderThread.join(500);
            }
            catch (InterruptedException ignored)
            {
            }
        }
        bridge.close();
        if (audio != null)
        {
            audio.close();
        }
    }

    /**
     * Test-only: drains the event queue and dispatches all pending events to the bridge.
     * In production, the render thread does this automatically before each generate() call.
     */
    void flushEventQueueForTest()
    {
        byte[] event;
        while ((event = eventQueue.poll()) != null)
        {
            dispatchToNative(event);
        }
    }
}

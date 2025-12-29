/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.midiraja.midi;

import java.util.List;

@SuppressWarnings("ThreadPriorityCheck") public class MuntSynthProvider implements SoftSynthProvider
{
    private final MuntNativeBridge bridge;
    private final @org.jspecify.annotations.Nullable AudioEngine audio;
    private @org.jspecify.annotations.Nullable Thread renderThread;
    private volatile boolean running = false;
    // Set to true while prepareForNewTrack() is cycling the Munt synth context.
    // The render thread checks this flag and spins instead of calling renderAudio(),
    // ensuring close_synth / open_synth are never called concurrently with rendering.
    private volatile boolean renderPaused = false;

    public MuntSynthProvider(
        MuntNativeBridge bridge, @org.jspecify.annotations.Nullable AudioEngine audio)
    {
        this.bridge = bridge;
        this.audio = audio;
    }

    // Munt renders at 32000 Hz. Latency = queued samples / 32000 converted to nanoseconds.
    private static final int MUNT_SAMPLE_RATE = 32000;

    // Ring buffer capacity (must match audio.init call below).
    // The render thread keeps this buffer full at steady state, so using the
    // capacity gives a stable, conservative latency estimate vs a dynamic snapshot.
    private static final int RING_BUFFER_CAPACITY_FRAMES = 4096;

    @Override public long getAudioLatencyNanos()
    {
        if (audio == null)
            return 0L;
        // Ring buffer capacity + miniaudio pipeline + CoreAudio hardware latency
        long totalFrames = (long) RING_BUFFER_CAPACITY_FRAMES + audio.getDeviceLatencyFrames();
        return totalFrames * 1_000_000_000L / MUNT_SAMPLE_RATE;
    }

    @Override public List<MidiPort> getOutputPorts()
    {
        return List.of(new MidiPort(0, "Munt MT-32 Emulator (Embedded)"));
    }

    @Override public void openPort(int portIndex) throws Exception
    {
        bridge.createSynth();
    }

    private void startRenderThread()
    {
        running = true;
        renderThread = new Thread(() -> {
            // Reset the render-clock reference to "now" so events queued between
            // openSynth() and this first cycle don't get far-future timestamps.
            bridge.resetRenderTiming();

            // Buffer size: 512 frames = 1024 shorts (stereo)
            // 512 frames at 32kHz is 16ms of audio.
            final int framesToRender = 512;
            short[] pcmBuffer = new short[framesToRender * 2];

            while (running)
            {
                // Spin while prepareForNewTrack() is cycling the Munt synth context.
                // close_synth / open_synth are NOT thread-safe with render_bit16s.
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

                // Pull rendered PCM data from Munt (it fills the pcmBuffer)
                bridge.renderAudio(pcmBuffer, framesToRender);

                // Push it to the miniaudio ring buffer.
                // This call will safely block if the buffer is full, pacing the thread.
                if (audio != null)
                {
                    audio.push(pcmBuffer);
                }
                else
                {
                    // If no audio engine, just sleep to simulate time passing
                    try
                    {
                        Thread.sleep(16);
                    }
                    catch (InterruptedException e)
                    {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        });
        // AUDIO PRIORITY: We need high priority to prevent dropouts,
        // even if the thread scheduler is not guaranteed to honor it.
        renderThread.setPriority(Thread.MAX_PRIORITY);
        renderThread.setDaemon(true);
        renderThread.start();
    }

    @Override @SuppressWarnings("EmptyCatch") public void panic()
    {
        // For Munt, skip the default 200ms wait: all note-offs are timestamped and
        // processed by the render thread in the next render cycle (<16ms). A 20ms wait
        // is sufficient. Then flush the ring buffer immediately so the next song or seek
        // starts with an empty buffer instead of waiting ~128ms for queued silence to drain.
        for (int ch = 0; ch < 16; ch++)
        {
            try
            {
                sendMessage(new byte[] {(byte) (0xB0 | ch), 64, 0}); // Sustain Off
                sendMessage(new byte[] {(byte) (0xB0 | ch), 123, 0}); // All Notes Off
                sendMessage(new byte[] {(byte) (0xB0 | ch), 120, 0}); // All Sound Off
                sendMessage(new byte[] {(byte) (0xB0 | ch), 121, 0}); // Reset All Controllers
                for (int note = 0; note < 128; note++)
                {
                    sendMessage(new byte[] {(byte) (0x80 | ch), (byte) note, 0});
                }
            }
            catch (Exception ignored)
            {
            }
        }
        // No sleep needed: note-offs have future timestamps and are processed by the render
        // thread asynchronously. Flushing the ring buffer immediately discards any old audio;
        // the note-off decay will be rendered fresh into the now-empty ring buffer.
        if (audio != null)
        {
            audio.flush();
        }
    }

    @Override public void loadSoundbank(String path) throws Exception
    {
        bridge.loadRoms(path);
        bridge.openSynth();

        if (audio != null)
        {
            audio.init(32000, 2, RING_BUFFER_CAPACITY_FRAMES); // Munt renders at 32000Hz natively
            startRenderThread();
        }
    }

    @Override @SuppressWarnings("EmptyCatch") public void prepareForNewTrack(javax.sound.midi.Sequence sequence)
    {
        if (audio == null)
            return;

        // Step 1: Pause the render thread so we can call renderAudio() directly below.
        // The 20ms sleep guarantees any in-progress renderAudio() call has completed.
        renderPaused = true;
        try
        {
            Thread.sleep(20);
        }
        catch (InterruptedException ignored)
        {
        }

        // Step 2: Flush the ring buffer to discard audio from the previous song.
        audio.flush();

        // Step 3: Fast-drain the MT-32 reverb tail WITHOUT pushing to the ring buffer.
        //
        // After panic(), MT-32 LA-synthesis voices enter RELEASE state and occupy
        // partial generators for up to ~2 seconds while their envelopes decay.
        // New notes at the start of the next song call onNoteOnIgnored and are
        // silently dropped while those partial generators are still occupied.
        //
        // When the render thread is paused, Munt's internal time is also frozen —
        // the reverb tail doesn't decay unless we call renderAudio(). We fast-render
        // (discarding output) until hasActivePartials() returns false, freeing all
        // partial generators before the new song begins.
        //
        // CPU rendering runs at ~10-20x real-time, so a 2-second reverb tail drains
        // in roughly 100-200ms of wall-clock time.
        short[] drainBuf = new short[1024];
        int maxDrainChunks = (MUNT_SAMPLE_RATE / 512) * 2; // up to 2s = 125 chunks
        for (int i = 0; i < maxDrainChunks && bridge.hasActivePartials(); i++)
        {
            bridge.renderAudio(drainBuf, 512);
        }

        // Step 4: Leave renderPaused = true. onPlaybackStarted() will reset timing and
        // resume the render thread just before the first MIDI event is dispatched,
        // so the ring buffer starts filling with real audio (not silence) from the start.
    }

    @Override public void onPlaybackStarted()
    {
        // Reset the render-clock reference so the first events get near-zero future timestamps
        // instead of timestamps capped at 4096 from a stale reference. Safe to call here
        // because the render thread is still paused (renderPaused = true).
        bridge.resetRenderTiming();
        // Resume the render thread — it will now start filling the ring buffer with real audio
        // rather than silence, eliminating the 128ms ring-buffer drain delay between songs.
        renderPaused = false;
    }

    @Override public void sendMessage(byte[] data) throws Exception
    {
        if (data == null || data.length == 0)
            return;

        int status = data[0] & 0xFF;
        if (status >= 0xF0)
        {
            bridge.playSysex(data);
            return;
        }

        int command = status & 0xF0;
        int channel = status & 0x0F;

        if (data.length >= 2)
        {
            int data1 = data[1] & 0xFF;
            int data2 = (data.length >= 3) ? (data[2] & 0xFF) : 0;

            switch (command)
            {
                case 0x90:
                    bridge.playNoteOn(channel, data1, data2);
                    break;
                case 0x80:
                    bridge.playNoteOff(channel, data1);
                    break;
                case 0xB0:
                    bridge.playControlChange(channel, data1, data2);
                    break;
                case 0xC0:
                    bridge.playProgramChange(channel, data1);
                    break;
                case 0xE0:
                    int bend = (data2 << 7) | data1;
                    bridge.playPitchBend(channel, bend);
                    break;
            }
        }
    }

    @Override @SuppressWarnings("EmptyCatch") public void closePort()
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
                // Expected during shutdown
            }
        }

        bridge.close();
        if (audio != null)
        {
            audio.close();
        }
    }
}

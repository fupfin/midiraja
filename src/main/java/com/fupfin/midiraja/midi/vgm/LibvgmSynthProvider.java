/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.midi.vgm;

import java.util.List;

import org.jspecify.annotations.Nullable;

import com.fupfin.midiraja.dsp.AudioProcessor;
import com.fupfin.midiraja.midi.AbstractSoftSynthProvider;
import com.fupfin.midiraja.midi.MidiPort;

/**
 * SoftSynthProvider backed by libvgm.
 *
 * <p>
 * VGM playback does not use MIDI event dispatch; audio is produced directly by
 * {@link FFMLibvgmBridge#generate}. This class starts its own render loop (rather than the
 * standard {@code startRenderThread} loop) so that playback stops automatically when
 * {@link FFMLibvgmBridge#isDone()} returns {@code true}.
 */
@SuppressWarnings({ "ThreadPriorityCheck", "EmptyCatch" })
public class LibvgmSynthProvider extends AbstractSoftSynthProvider<FFMLibvgmBridge>
{
    public LibvgmSynthProvider(FFMLibvgmBridge bridge, @Nullable AudioProcessor audioOut)
    {
        super(bridge, audioOut);
    }

    @Override
    public List<MidiPort> getOutputPorts()
    {
        return List.of(new MidiPort(0, "libvgm"));
    }

    @Override
    public long getAudioLatencyNanos()
    {
        return 4096L * 1_000_000_000L / SAMPLE_RATE;
    }

    @Override
    public void openPort(int portIndex) throws Exception
    {
        bridge.init(SAMPLE_RATE);
    }

    /**
     * Opens a VGM/VGZ file from disk and starts the render thread.
     * {@link #openPort(int)} must be called first.
     */
    public void loadVgmFile(String path) throws Exception
    {
        stopCurrentRenderThread();
        bridge.openFile(path);
        renderPaused = true;
        startVgmRenderThread();
    }

    /**
     * Loads VGM data from a byte array (e.g. converted from MIDI) and starts the render thread.
     * {@link #openPort(int)} must be called first.
     */
    public void loadVgmData(byte[] data) throws Exception
    {
        stopCurrentRenderThread();
        bridge.openData(data);
        renderPaused = true;
        startVgmRenderThread();
    }

    /** Pauses the VGM render loop (audio output stops). */
    public void pauseRender()
    {
        renderPaused = true;
    }

    /** Resumes a paused VGM render loop. */
    public void resumeRender()
    {
        renderPaused = false;
    }

    /** Returns {@code true} when the VGM stream has finished playing. */
    public boolean isDone()
    {
        return bridge.isDone();
    }

    /**
     * Not used for VGM playback. Loads are performed via {@link #loadVgmFile} or
     * {@link #loadVgmData}.
     */
    @Override
    public void loadSoundbank(String path) throws Exception
    {
        // no-op
    }

    // ── Private render thread ─────────────────────────────────────────────────

    private void stopCurrentRenderThread()
    {
        running = false;
        if (renderThread != null)
        {
            renderThread.interrupt();
            try
            {
                renderThread.join(200);
            }
            catch (InterruptedException _)
            {
                Thread.currentThread().interrupt();
            }
            renderThread = null;
        }
    }

    private void startVgmRenderThread()
    {
        running = true;
        renderThread = new Thread(this::vgmRenderLoop, "LibvgmRenderThread");
        renderThread.setPriority(Thread.MAX_PRIORITY);
        renderThread.setDaemon(true);
        renderThread.start();
    }

    private void vgmRenderLoop()
    {
        short[] pcmBuffer = new short[FRAMES_PER_RENDER * 2];
        while (running && !bridge.isDone())
        {
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

            bridge.generate(pcmBuffer, FRAMES_PER_RENDER);

            if (audioOut != null)
            {
                audioOut.processInterleaved(pcmBuffer, FRAMES_PER_RENDER, 2);
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
        running = false;
    }
}

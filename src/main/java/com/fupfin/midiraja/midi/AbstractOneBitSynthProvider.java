/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.midi;

import java.util.Optional;
import javax.sound.midi.Sequence;

import org.jspecify.annotations.Nullable;

import com.fupfin.midiraja.dsp.AudioProcessor;
import com.fupfin.midiraja.dsp.MasterGainFilter;

/**
 * Base class for 1-bit/legacy hardware synth providers (Beep, PSG).
 *
 * <p>
 * Handles render-thread lifecycle: spin-wait loop, audioOut dispatch, thread priority. Subclasses
 * implement {@link #renderFrames} for the actual synthesis, and {@link #resetState} to clear
 * per-track state on song transition.
 */
public abstract class AbstractOneBitSynthProvider implements SoftSynthProvider
{
    protected static final int FRAMES_PER_CHUNK = 512;

    protected final @Nullable AudioProcessor audioOut;
    protected @Nullable Thread renderThread;
    protected volatile boolean running = false;
    protected volatile boolean renderPaused = false;
    private @Nullable MasterGainFilter masterGain = null;

    protected AbstractOneBitSynthProvider(@Nullable AudioProcessor audioOut)
    {
        this.audioOut = audioOut;
    }

    /** Per-synth calibration factor. Override in subclasses to normalize output level. */
    protected float calibrationGain()
    {
        return 1.0f;
    }

    public void setMasterGain(MasterGainFilter gain)
    {
        this.masterGain = gain;
        gain.setCalibration(calibrationGain());
    }

    @Override
    public Optional<MasterGainFilter> outputGain()
    {
        return Optional.ofNullable(masterGain);
    }

    @Override
    public void openPort(int portIndex) throws Exception
    {
        if (audioOut != null)
            startRenderThread();
    }

    @Override
    public void loadSoundbank(String path) throws Exception
    {
    }

    @Override
    public void closePort()
    {
        running = false;
        if (renderThread != null)
            renderThread.interrupt();
    }

    @Override
    public void onPlaybackStarted()
    {
        renderPaused = false;
    }

    @Override
    public void prepareForNewTrack(Sequence seq)
    {
        renderPaused = true;
        if (audioOut != null)
            audioOut.reset();
        resetState();
    }

    /** Synthesizes one chunk of mono audio into {@code pcmBuffer}. */
    protected abstract void renderFrames(short[] pcmBuffer, int framesToRender);

    /** Clears per-track synthesis state (notes, chip registers, etc.) on song transition. */
    protected abstract void resetState();

    protected void startRenderThread()
    {
        running = true;
        renderThread = new Thread(this::renderLoop);
        renderThread.setPriority(Thread.MAX_PRIORITY);
        renderThread.setDaemon(true);
        renderThread.start();
    }

    private void renderLoop()
    {
        short[] pcmBuffer = new short[FRAMES_PER_CHUNK];
        while (running)
        {
            if (renderPaused)
            {
                try
                {
                    Thread.sleep(1);
                }
                catch (InterruptedException e)
                {
                    break;
                }
                continue;
            }
            renderFrames(pcmBuffer, FRAMES_PER_CHUNK);
            if (audioOut != null)
                audioOut.processInterleaved(pcmBuffer, FRAMES_PER_CHUNK, 1);
        }
    }
}

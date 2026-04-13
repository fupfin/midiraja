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
 *
 * <p>
 * A cascaded 2-pole IIR low-pass filter (fc ≈ 14 kHz) is applied after each rendered buffer.
 * libvgm renders the SCC wavetable by stepping through a 32-sample table at 32 × Fout Hz.
 * For notes above ~E5 (689 Hz) this step rate exceeds the 22 050 Hz Nyquist limit and folds
 * back as an audible alias (e.g. A5 at 880 Hz → alias at 15 940 Hz). The LP filter suppresses
 * these digital aliases. Oversampling cannot help here: rendering at a higher rate faithfully
 * reproduces additional step harmonics (e.g. the 5th harmonic for A3 appears at 35 200 Hz),
 * which then create new aliases (8 900 Hz) when decimated back to 44 100 Hz. The post-render
 * LP at the final sample rate is the correct and simplest solution.
 */
@SuppressWarnings({ "ThreadPriorityCheck", "EmptyCatch" })
public class LibvgmSynthProvider extends AbstractSoftSynthProvider<FFMLibvgmBridge>
{
    /**
     * IIR 1-pole LP coefficient: α = 2π·fc / (2π·fc + fs), fc = 14 000 Hz, fs = 44 100 Hz.
     * Two poles cascaded give ≈ −11 dB at 15 940 Hz (A5 alias), preserving content below ~10 kHz.
     */
    private static final float LP_ALPHA =
            (float) (2 * Math.PI * 14_000 / (2 * Math.PI * 14_000 + 44_100));

    // Cascaded 2-pole LP filter state (L/R, pole-1 and pole-2)
    private float lpL1, lpL2, lpR1, lpR2;

    public LibvgmSynthProvider(FFMLibvgmBridge bridge, @Nullable AudioProcessor audioOut)
    {
        super(bridge, audioOut);
    }

    @Override
    public List<MidiPort> getOutputPorts()
    {
        return List.of(new MidiPort(0, "vgm"));
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

    /**
     * Applies the cascaded 2-pole IIR LP filter in-place to a stereo interleaved buffer.
     * Attenuates digital aliases above ~14 kHz introduced by SCC wavetable step-rate aliasing.
     */
    private void applyHardwareLpf(short[] pcm)
    {
        for (int i = 0; i < FRAMES_PER_RENDER; i++)
        {
            int li = i * 2;
            float sL = pcm[li] / 32768.0f;
            float sR = pcm[li + 1] / 32768.0f;
            lpL1 += LP_ALPHA * (sL - lpL1);
            lpL2 += LP_ALPHA * (lpL1 - lpL2);
            lpR1 += LP_ALPHA * (sR - lpR1);
            lpR2 += LP_ALPHA * (lpR1 - lpR2);
            pcm[li] = (short) Math.clamp(Math.round(lpL2 * 32768.0f), -32768, 32767);
            pcm[li + 1] = (short) Math.clamp(Math.round(lpR2 * 32768.0f), -32768, 32767);
        }
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
            applyHardwareLpf(pcmBuffer);

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

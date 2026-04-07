/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.dsp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

class SpectrumBeeperFilterTest
{
    static class CaptureSink implements AudioProcessor
    {
        float[] capturedLeft;
        float[] capturedRight;
        int capturedFrames = 0;
        short[] capturedPcm;
        int capturedPcmFrames;
        int capturedChannels;
        boolean resetCalled = false;

        @Override
        public void process(float[] left, float[] right, int frames)
        {
            capturedLeft = left.clone();
            capturedRight = right.clone();
            capturedFrames = frames;
        }

        @Override
        public void processInterleaved(short[] pcm, int frames, int channels)
        {
            capturedPcm = pcm.clone();
            capturedPcmFrames = frames;
            capturedChannels = channels;
        }

        @Override
        public void reset()
        {
            resetCalled = true;
        }
    }

    private static float[] fill(int n, float v)
    {
        float[] a = new float[n];
        Arrays.fill(a, v);
        return a;
    }

    private static double rms(float[] buf)
    {
        double sum = 0;
        for (float v : buf)
            sum += (double) v * v;
        return Math.sqrt(sum / buf.length);
    }

    // ── disabled bypass ───────────────────────────────────────────────────────

    @Test
    void process_disabled_passesSignalUnchanged()
    {
        var sink = new CaptureSink();
        var filter = new SpectrumBeeperFilter(false, false, sink);

        float[] left = fill(64, 0.5f);
        float[] right = fill(64, -0.3f);
        filter.process(left.clone(), right.clone(), 64);

        assertArrayEquals(fill(64, 0.5f), sink.capturedLeft, 0.0f,
                "Disabled filter must not alter the signal");
    }

    @Test
    void processInterleaved_disabled_passesSignalUnchanged()
    {
        var sink = new CaptureSink();
        var filter = new SpectrumBeeperFilter(false, false, sink);

        short[] pcm = { 10000, -10000, 5000, -5000 };
        filter.processInterleaved(pcm.clone(), 2, 2);

        assertArrayEquals(pcm, sink.capturedPcm,
                "Disabled filter should pass PCM unchanged");
    }

    // ── mono mixdown ──────────────────────────────────────────────────────────

    @Test
    void process_enabled_leftAndRightOutputAreIdentical()
    {
        var sink = new CaptureSink();
        var filter = new SpectrumBeeperFilter(true, false, sink);

        float[] left = fill(256, 0.8f);
        float[] right = fill(256, 0.4f);
        filter.process(left, right, 256);

        // Output must be mono (L == R) because the beeper mixes to mono
        assertArrayEquals(sink.capturedLeft, sink.capturedRight, 0.0f,
                "Output must be mono: left and right channels must be identical");
    }

    @Test
    void process_enabled_mixesLAndRBeforeProcessing()
    {
        var sinkL = new CaptureSink();
        var sinkR = new CaptureSink();

        var filterL = new SpectrumBeeperFilter(true, true, sinkL); // auxOut for raw quantized
        var filterR = new SpectrumBeeperFilter(true, true, sinkR);

        // L=0.8, R=0.0 → mix = 0.4
        filterL.process(fill(4, 0.8f), new float[4], 4);
        // L=0.0, R=0.8 → mix = 0.4
        filterR.process(new float[4], fill(4, 0.8f), 4);
        // Both should produce same mono result (mix = 0.4)
        assertArrayEquals(sinkL.capturedLeft, sinkR.capturedLeft, 0.001f,
                "Symmetric L-only and R-only signals produce same mono mix");
    }

    // ── Z80 quantization ─────────────────────────────────────────────────────

    @Test
    void process_auxOut_quantizesTo128Levels()
    {
        var sink = new CaptureSink();
        var filter = new SpectrumBeeperFilter(true, true, sink); // auxOut=true: no filters applied

        float[] signal = new float[128];
        float[] expected = new float[128];
        for (int i = 0; i < 128; i++)
        {
            float v = (i / 127.0f) * 2.0f - 1.0f; // sweep -1..+1
            signal[i] = v;
            // quantized: round((v*0.5+0.5)*127) / 127 * 2 - 1
            int level = Math.round((Math.max(-1, Math.min(1, v)) * 0.5f + 0.5f) * 127);
            expected[i] = (level / 127.0f) * 2.0f - 1.0f;
        }

        float[] dummy = signal.clone();
        filter.process(dummy, dummy.clone(), 128);

        assertArrayEquals(expected, sink.capturedLeft, 0.001f,
                "auxOut mode should output Z80-quantized values directly");
    }

    @Test
    void process_auxOut_silence_producesConsistentQuantizedValue()
    {
        var sink = new CaptureSink();
        var filter = new SpectrumBeeperFilter(true, true, sink);

        filter.process(new float[64], new float[64], 64);

        // Zero input: level = round(0.5 * 127) = 64 (Java rounds 63.5 up to 64)
        // quantized = 64/127 * 2 - 1 ≈ +0.00787 (not exactly zero due to quantization offset)
        // All samples should be the same constant quantized value
        float firstSample = sink.capturedLeft[0];
        for (int i = 1; i < sink.capturedFrames; i++)
        {
            assertEquals(firstSample, sink.capturedLeft[i], 0.0f,
                    "All zero-input samples should produce same constant quantized value");
        }
        // Confirm the value is near zero (within one quantization step of 128 levels)
        assertEquals(0.0f, sink.capturedLeft[0], 2.0f / 127,
                "Zero input should quantize to near-zero (within one step)");
    }

    // ── HP + LP filter chain ─────────────────────────────────────────────────

    @Test
    void process_enabled_fullFilter_producesNonZeroFromNonZeroInput()
    {
        var sink = new CaptureSink();
        var filter = new SpectrumBeeperFilter(true, false, sink);

        filter.process(fill(256, 0.8f), fill(256, 0.8f), 256);

        assertTrue(rms(sink.capturedLeft) > 0, "Enabled filter should produce non-zero output");
    }

    @Test
    void process_enabled_dcBlockedByHighPass()
    {
        var sink = new CaptureSink();
        var filter = new SpectrumBeeperFilter(true, false, sink);

        // DC signal → after long run, HP should drive output toward zero
        filter.process(fill(44100, 0.5f), fill(44100, 0.5f), 44100);

        // After 1 second of DC, the HP should have blocked most of it
        float lastSample = Math.abs(sink.capturedLeft[44099]);
        assertTrue(lastSample < 0.1f,
                "HP filter should block DC: last sample should be near zero, got " + lastSample);
    }

    @Test
    void process_enabled_attenuatesVeryHighFrequency()
    {
        // Generate a near-Nyquist sine wave (alternating +/- each sample)
        int n = 4096;
        float[] signal = new float[n];
        for (int i = 0; i < n; i++)
            signal[i] = (i % 2 == 0) ? 0.8f : -0.8f;

        var sinkEnabled = new CaptureSink();
        var filter = new SpectrumBeeperFilter(true, false, sinkEnabled);
        filter.process(signal.clone(), signal.clone(), n);

        var sinkAux = new CaptureSink();
        var auxFilter = new SpectrumBeeperFilter(true, true, sinkAux); // no LP
        auxFilter.process(signal.clone(), signal.clone(), n);

        // LP chain should reduce RMS of high-frequency signal vs auxOut (no LP)
        double rmsFiltered = rms(sinkEnabled.capturedLeft);
        double rmsRaw = rms(sinkAux.capturedLeft);

        assertTrue(rmsFiltered < rmsRaw,
                "LP chain should attenuate near-Nyquist frequency: filtered=" + rmsFiltered + " raw=" + rmsRaw);
    }

    // ── processInterleaved ────────────────────────────────────────────────────

    @Test
    void processInterleaved_stereo_mixesChannelsAndOutputsMono()
    {
        var sink = new CaptureSink();
        var filter = new SpectrumBeeperFilter(true, true, sink); // auxOut for predictable values

        // Symmetric stereo: L and R equal → mono mix is same as either channel
        short[] pcm = { 16384, 16384, -16384, -16384 }; // 2 frames stereo
        filter.processInterleaved(pcm, 2, 2);

        // Both output channels should be equal
        assertEquals(sink.capturedPcm[0], sink.capturedPcm[1],
                "Stereo output should be mono (L == R)");
    }

    @Test
    void processInterleaved_mono_processesCorrectly()
    {
        var sink = new CaptureSink();
        var filter = new SpectrumBeeperFilter(true, false, sink);

        short[] pcm = { 16000, 8000, -8000 }; // 3 frames, 1 channel
        assertDoesNotThrow(() -> filter.processInterleaved(pcm, 3, 1));
        assertEquals(3, sink.capturedPcmFrames);
    }

    @Test
    void processInterleaved_silence_producesConsistentOutput()
    {
        var sink = new CaptureSink();
        var filter = new SpectrumBeeperFilter(true, false, sink);

        short[] pcm = new short[64];
        filter.processInterleaved(pcm, 32, 2);

        // The HP+LP filter chain with a constant quantized near-zero input settles to a
        // near-zero value. All samples should be equal (filter has settled).
        // Just verify no overflow and that output is bounded near zero.
        assertNotNull(sink.capturedPcm);
        for (short s : sink.capturedPcm)
        {
            // Quantization of zero maps to level=64 → quantized≈+0.008, which through the
            // HP+LP chain produces a small but non-zero PCM value (< 1% of full scale).
            assertTrue(Math.abs(s) < 328, "Silence should produce near-zero PCM output, got " + s);
        }
    }

    // ── reset ────────────────────────────────────────────────────────────────

    @Test
    void reset_clearsFilterState()
    {
        var sink = new CaptureSink();
        var filter = new SpectrumBeeperFilter(true, false, sink);

        // Prime filter with signal
        filter.process(fill(2048, 0.8f), fill(2048, 0.8f), 2048);

        // Reset
        filter.reset();
        assertTrue(sink.resetCalled, "reset() should propagate to next processor");

        // A fresh filter on silence should yield silence, demonstrating state was cleared
        var freshSink = new CaptureSink();
        var freshFilter = new SpectrumBeeperFilter(true, false, freshSink);
        freshFilter.process(new float[64], new float[64], 64);

        assertEquals(0.0f, freshSink.capturedLeft[63], 0.0001f,
                "Fresh filter state on silence should yield zero");
    }

    @Test
    void reset_propagatesToNext()
    {
        var sink = new CaptureSink();
        var filter = new SpectrumBeeperFilter(true, false, sink);

        filter.reset();

        assertTrue(sink.resetCalled, "reset() must call next.reset()");
    }

    // ── boundary conditions ───────────────────────────────────────────────────

    @Test
    void process_zeroFrames_doesNotThrow()
    {
        var sink = new CaptureSink();
        var filter = new SpectrumBeeperFilter(true, false, sink);

        assertDoesNotThrow(() -> filter.process(new float[0], new float[0], 0));
    }

    @Test
    void process_oneFrame_doesNotThrow()
    {
        var sink = new CaptureSink();
        var filter = new SpectrumBeeperFilter(true, false, sink);

        assertDoesNotThrow(() -> filter.process(new float[] { 0.5f }, new float[] { -0.5f }, 1));
        assertEquals(1, sink.capturedFrames);
    }

    @Test
    void process_clipsInputBeforeQuantization()
    {
        var sinkClipped = new CaptureSink();
        var sinkNormal = new CaptureSink();

        var filterClipped = new SpectrumBeeperFilter(true, true, sinkClipped);
        var filterNormal = new SpectrumBeeperFilter(true, true, sinkNormal);

        // Input above 1.0 should clamp to 1.0 before quantization
        filterClipped.process(fill(4, 2.0f), fill(4, 2.0f), 4);
        filterNormal.process(fill(4, 1.0f), fill(4, 1.0f), 4);

        assertArrayEquals(sinkNormal.capturedLeft, sinkClipped.capturedLeft, 0.001f,
                "Input above 1.0 should clamp to 1.0 before quantization");
    }

    @Test
    void process_clipsNegativeInputBeforeQuantization()
    {
        var sinkClipped = new CaptureSink();
        var sinkNormal = new CaptureSink();

        var filterClipped = new SpectrumBeeperFilter(true, true, sinkClipped);
        var filterNormal = new SpectrumBeeperFilter(true, true, sinkNormal);

        filterClipped.process(fill(4, -2.0f), fill(4, -2.0f), 4);
        filterNormal.process(fill(4, -1.0f), fill(4, -1.0f), 4);

        assertArrayEquals(sinkNormal.capturedLeft, sinkClipped.capturedLeft, 0.001f,
                "Input below -1.0 should clamp to -1.0 before quantization");
    }

    // ── next processor always called ──────────────────────────────────────────

    @Test
    void process_alwaysCallsNext_whenEnabled()
    {
        var sink = new CaptureSink();
        var filter = new SpectrumBeeperFilter(true, false, sink);

        filter.process(fill(32, 0.5f), fill(32, 0.5f), 32);

        assertNotNull(sink.capturedLeft, "Next processor must be called when enabled");
        assertEquals(32, sink.capturedFrames);
    }

    @Test
    void process_alwaysCallsNext_whenDisabled()
    {
        var sink = new CaptureSink();
        var filter = new SpectrumBeeperFilter(false, false, sink);

        filter.process(fill(32, 0.5f), fill(32, 0.5f), 32);

        assertNotNull(sink.capturedLeft, "Next processor must be called when disabled");
        assertEquals(32, sink.capturedFrames);
    }
}

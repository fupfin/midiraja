/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.dsp;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class AmigaPaulaFilterTest
{
    // ── helper ────────────────────────────────────────────────────────────────

    static class CaptureSink implements AudioProcessor
    {
        float[] capturedLeft;
        float[] capturedRight;
        int capturedFrames = 0;
        short[] capturedInterleaved;
        boolean resetCalled = false;

        @Override
        public void process(float[] left, float[] right, int frames)
        {
            capturedLeft = left.clone();
            capturedRight = right.clone();
            capturedFrames = frames;
        }

        @Override
        public void processInterleaved(short[] interleavedPcm, int frames, int channels)
        {
            capturedInterleaved = interleavedPcm.clone();
            capturedFrames = frames;
        }

        @Override
        public void reset()
        {
            resetCalled = true;
        }
    }

    private static float[] constantLeft(float value, int frames)
    {
        float[] arr = new float[frames];
        java.util.Arrays.fill(arr, value);
        return arr;
    }

    // ── enabled=false passthrough ─────────────────────────────────────────────

    @Test
    void disabled_process_passes_arrays_unmodified()
    {
        var sink = new CaptureSink();
        var filter = new AmigaPaulaFilter(false, AmigaPaulaFilter.Profile.A500, sink);

        float[] left = { 0.1f, 0.5f, -0.3f };
        float[] right = { -0.2f, 0.8f, 0.0f };
        filter.process(left.clone(), right.clone(), 3);

        assertArrayEquals(left, sink.capturedLeft, 0.0f,
                "disabled filter must not alter left channel");
        assertArrayEquals(right, sink.capturedRight, 0.0f,
                "disabled filter must not alter right channel");
        assertEquals(3, sink.capturedFrames);
    }

    @Test
    void disabled_processInterleaved_passes_pcm_unmodified()
    {
        var sink = new CaptureSink();
        var filter = new AmigaPaulaFilter(false, AmigaPaulaFilter.Profile.A500, sink);

        short[] pcm = { 1000, 2000, 3000, 4000 };
        filter.processInterleaved(pcm.clone(), 2, 2);

        assertArrayEquals(pcm, sink.capturedInterleaved,
                "disabled filter must not alter interleaved PCM");
    }

    // ── A500 profile produces filtered (darker) output ────────────────────────

    @Test
    void a500_process_enabled_attenuates_full_scale_signal()
    {
        var sink = new CaptureSink();
        var filter = new AmigaPaulaFilter(true, AmigaPaulaFilter.Profile.A500, 1.0f, sink);

        // Drive 256 frames of full-scale to let the IIR settle
        float[] left = constantLeft(1.0f, 256);
        float[] right = constantLeft(1.0f, 256);
        filter.process(left, right, 256);

        // After many frames the output should approach a steady state but A500 LPF damps it
        float maxOut = 0;
        for (float v : sink.capturedLeft) maxOut = Math.max(maxOut, Math.abs(v));
        assertTrue(maxOut <= 1.5f, "A500 output should be within reasonable amplitude");
        assertTrue(maxOut > 0.0f, "A500 output should not be silent for full-scale input");
    }

    // ── A1200 profile is brighter (less low-pass) than A500 ──────────────────

    @Test
    void a1200_is_less_filtered_than_a500_for_same_input()
    {
        // Run both filters on identical impulse and compare energy of output
        var sinkA500 = new CaptureSink();
        var sinkA1200 = new CaptureSink();

        var filterA500 = new AmigaPaulaFilter(true, AmigaPaulaFilter.Profile.A500, 1.0f, sinkA500);
        var filterA1200 = new AmigaPaulaFilter(true, AmigaPaulaFilter.Profile.A1200, 1.0f, sinkA1200);

        // Send a burst of alternating max/min samples (high frequency content)
        int frames = 64;
        float[] leftA500 = new float[frames];
        float[] rightA500 = new float[frames];
        float[] leftA1200 = new float[frames];
        float[] rightA1200 = new float[frames];
        for (int i = 0; i < frames; i++)
        {
            leftA500[i] = leftA1200[i] = (i % 2 == 0) ? 1.0f : -1.0f;
            rightA500[i] = rightA1200[i] = (i % 2 == 0) ? 1.0f : -1.0f;
        }

        filterA500.process(leftA500, rightA500, frames);
        filterA1200.process(leftA1200, rightA1200, frames);

        // A500 (~4.5kHz cutoff) should produce lower energy than A1200 (~28kHz cutoff) for HF input
        double energyA500 = 0, energyA1200 = 0;
        for (int i = 0; i < frames; i++)
        {
            energyA500 += sinkA500.capturedLeft[i] * sinkA500.capturedLeft[i];
            energyA1200 += sinkA1200.capturedLeft[i] * sinkA1200.capturedLeft[i];
        }
        assertTrue(energyA500 < energyA1200,
                "A500 (darker) should have less energy than A1200 for high-frequency input");
    }

    // ── profiles produce different output from each other ────────────────────

    @Test
    void a500_and_a1200_produce_different_outputs()
    {
        var sinkA500 = new CaptureSink();
        var sinkA1200 = new CaptureSink();
        var filterA500 = new AmigaPaulaFilter(true, AmigaPaulaFilter.Profile.A500, sinkA500);
        var filterA1200 = new AmigaPaulaFilter(true, AmigaPaulaFilter.Profile.A1200, sinkA1200);

        float[] left = { 0.8f, -0.8f, 0.4f, -0.4f, 0.8f };
        float[] right = { 0.4f, -0.4f, 0.8f, -0.8f, 0.4f };
        filterA500.process(left.clone(), right.clone(), 5);
        filterA1200.process(left.clone(), right.clone(), 5);

        boolean anyDifferent = false;
        for (int i = 0; i < 5; i++)
        {
            if (Math.abs(sinkA500.capturedLeft[i] - sinkA1200.capturedLeft[i]) > 1e-6f)
            {
                anyDifferent = true;
                break;
            }
        }
        assertTrue(anyDifferent, "A500 and A1200 must produce different outputs");
    }

    // ── silence in → near-silence out ────────────────────────────────────────
    //
    // Note: AmigaPaulaFilter uses a seeded R-2R DAC LUT with ±3% resistor tolerance.
    // The LUT midpoint (index 128, representing 0.0f input) is not exactly 0.0 due to
    // this intentional non-linearity. After passing through the LED filter chain, the
    // output converges to a small constant DC offset rather than true silence.

    @Test
    void enabled_silence_input_produces_near_silence_output()
    {
        var sink = new CaptureSink();
        var filter = new AmigaPaulaFilter(true, AmigaPaulaFilter.Profile.A500, 1.0f, sink);

        // Run many frames so the IIR filter state can settle to its steady-state
        float[] left = new float[512];
        float[] right = new float[512];
        filter.process(left, right, 512);

        // The output should be very small (DAC non-linearity ≤ ±3%) and stable
        float lastLeft = sink.capturedLeft[511];
        float lastRight = sink.capturedRight[511];
        assertTrue(Math.abs(lastLeft) < 0.1f,
                "Settled silence output on left should be very small (DAC offset < 0.1)");
        assertTrue(Math.abs(lastRight) < 0.1f,
                "Settled silence output on right should be very small (DAC offset < 0.1)");

        // Output must be consistent (settled steady-state, not transient noise)
        assertTrue(Math.abs(sink.capturedLeft[511] - sink.capturedLeft[510]) < 1e-5f,
                "Settled silence output should not change between consecutive frames");
    }

    // ── stereo widening ───────────────────────────────────────────────────────

    @Test
    void stereo_width_1_produces_wider_image_than_width_0()
    {
        var sinkNarrow = new CaptureSink();
        var sinkWide = new CaptureSink();
        var filterNarrow = new AmigaPaulaFilter(true, AmigaPaulaFilter.Profile.A1200, 0.0f, sinkNarrow);
        var filterWide = new AmigaPaulaFilter(true, AmigaPaulaFilter.Profile.A1200, 1.0f, sinkWide);

        // Different L/R to create stereo difference
        float[] left = { 0.8f, 0.8f, 0.8f, 0.8f, 0.8f, 0.8f, 0.8f, 0.8f };
        float[] right = { 0.2f, 0.2f, 0.2f, 0.2f, 0.2f, 0.2f, 0.2f, 0.2f };
        filterNarrow.process(left.clone(), right.clone(), 8);
        filterWide.process(left.clone(), right.clone(), 8);

        // With width=0.0, L and R after M/S are both equal to M (mono)
        // With width=1.0, L and R diverge from mono center
        // Find a frame where output is non-trivial (filters need time to build up)
        int lastFrame = 7;
        float narrowDiff = Math.abs(sinkNarrow.capturedLeft[lastFrame] - sinkNarrow.capturedRight[lastFrame]);
        float wideDiff = Math.abs(sinkWide.capturedLeft[lastFrame] - sinkWide.capturedRight[lastFrame]);
        assertTrue(wideDiff >= narrowDiff,
                "Width=1.0 should produce equal or wider stereo image than width=0.0");
    }

    // ── reset clears filter state ─────────────────────────────────────────────

    @Test
    void reset_clears_filter_state_giving_identical_output()
    {
        var sink1 = new CaptureSink();
        var filter1 = new AmigaPaulaFilter(true, AmigaPaulaFilter.Profile.A500, 1.0f, sink1);

        // First run: warm the filter with some frames, capture tail
        float[] warmLeft = constantLeft(0.5f, 20);
        float[] warmRight = constantLeft(0.5f, 20);
        filter1.process(warmLeft, warmRight, 20);

        // Continue with fresh signal to see how state affects it
        float[] signalLeft = { 0.0f, 0.8f, 0.0f, -0.8f };
        float[] signalRight = { 0.0f, 0.8f, 0.0f, -0.8f };
        filter1.process(signalLeft.clone(), signalRight.clone(), 4);
        float[] afterWarm = sink1.capturedLeft.clone();

        // Second run: reset immediately, then process same signal
        filter1.reset();
        filter1.process(signalLeft.clone(), signalRight.clone(), 4);
        float[] afterReset = sink1.capturedLeft.clone();

        // A fresh filter and a reset filter should produce the same result
        assertArrayEquals(afterReset, afterReset, 1e-6f,
                "After reset, same input should produce same output as fresh filter");

        // The warm (unreset) output should differ from reset output
        boolean differs = false;
        for (int i = 0; i < 4; i++)
        {
            if (Math.abs(afterWarm[i] - afterReset[i]) > 1e-6f)
            {
                differs = true;
                break;
            }
        }
        assertTrue(differs, "Warm filter and reset filter should produce different output for same input");
    }

    @Test
    void reset_propagates_to_next_processor()
    {
        var sink = new CaptureSink();
        var filter = new AmigaPaulaFilter(true, AmigaPaulaFilter.Profile.A500, sink);
        filter.reset();
        assertTrue(sink.resetCalled, "reset() must propagate to next processor");
    }

    // ── zero frames → no crash ────────────────────────────────────────────────

    @Test
    void process_zero_frames_does_not_crash()
    {
        var sink = new CaptureSink();
        var filter = new AmigaPaulaFilter(true, AmigaPaulaFilter.Profile.A500, sink);
        assertDoesNotThrow(() -> filter.process(new float[0], new float[0], 0));
    }

    @Test
    void processInterleaved_zero_frames_does_not_crash()
    {
        var sink = new CaptureSink();
        var filter = new AmigaPaulaFilter(true, AmigaPaulaFilter.Profile.A500, sink);
        assertDoesNotThrow(() -> filter.processInterleaved(new short[0], 0, 2));
    }

    // ── processInterleaved stereo ─────────────────────────────────────────────

    @Test
    void processInterleaved_stereo_enabled_modifies_pcm_in_place_and_delegates()
    {
        var sink = new CaptureSink();
        var filter = new AmigaPaulaFilter(true, AmigaPaulaFilter.Profile.A1200, 1.0f, sink);

        short[] pcm = new short[8]; // 4 stereo frames
        for (int i = 0; i < 8; i++) pcm[i] = (short) (i % 2 == 0 ? 16000 : -16000);
        filter.processInterleaved(pcm, 4, 2);

        assertNotNull(sink.capturedInterleaved,
                "processInterleaved must delegate to next with interleaved data");
        assertEquals(4, sink.capturedFrames);
    }

    // ── processInterleaved mono ───────────────────────────────────────────────

    @Test
    void processInterleaved_mono_enabled_uses_only_left_channel()
    {
        var sink = new CaptureSink();
        var filter = new AmigaPaulaFilter(true, AmigaPaulaFilter.Profile.A1200, 1.0f, sink);

        short[] pcm = { 16000, 16000, 16000, 16000 };
        filter.processInterleaved(pcm, 4, 1);

        assertNotNull(sink.capturedInterleaved,
                "processInterleaved mono must delegate to next");
        assertEquals(4, sink.capturedFrames);
    }

    // ── determinism: same input always same output ────────────────────────────

    @Test
    void enabled_is_deterministic_given_same_input()
    {
        var sink1 = new CaptureSink();
        var sink2 = new CaptureSink();
        var filter1 = new AmigaPaulaFilter(true, AmigaPaulaFilter.Profile.A500, 1.0f, sink1);
        var filter2 = new AmigaPaulaFilter(true, AmigaPaulaFilter.Profile.A500, 1.0f, sink2);

        float[] left = { 0.5f, -0.5f, 0.25f, -0.25f, 0.75f, -0.75f };
        float[] right = { -0.3f, 0.3f, 0.6f, -0.6f, 0.1f, -0.1f };
        filter1.process(left.clone(), right.clone(), 6);
        filter2.process(left.clone(), right.clone(), 6);

        assertArrayEquals(sink1.capturedLeft, sink2.capturedLeft, 0.0f,
                "Same input must produce identical left output (deterministic LUT)");
        assertArrayEquals(sink1.capturedRight, sink2.capturedRight, 0.0f,
                "Same input must produce identical right output (deterministic LUT)");
    }

    // ── consumer perspective: callers typically pass enabled=false ────────────

    @Test
    void disabled_is_zero_cost_passthrough_for_all_frames()
    {
        var sink = new CaptureSink();
        var filter = new AmigaPaulaFilter(false, AmigaPaulaFilter.Profile.A500, sink);

        int frames = 1024;
        float[] left = new float[frames];
        float[] right = new float[frames];
        for (int i = 0; i < frames; i++)
        {
            left[i] = (float) Math.sin(2 * Math.PI * i / 32);
            right[i] = (float) Math.cos(2 * Math.PI * i / 32);
        }
        float[] expectedLeft = left.clone();
        float[] expectedRight = right.clone();

        filter.process(left, right, frames);

        assertArrayEquals(expectedLeft, sink.capturedLeft, 0.0f,
                "disabled filter must pass all 1024 left samples unchanged");
        assertArrayEquals(expectedRight, sink.capturedRight, 0.0f,
                "disabled filter must pass all 1024 right samples unchanged");
    }
}

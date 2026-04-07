/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.dsp;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ShortToFloatFilterTest
{
    static class CaptureSink implements AudioProcessor
    {
        float[] capturedLeft;
        float[] capturedRight;
        int capturedFrames = 0;
        boolean resetCalled = false;

        @Override
        public void process(float[] left, float[] right, int frames)
        {
            capturedLeft = left.clone();
            capturedRight = right.clone();
            capturedFrames = frames;
        }

        @Override
        public void reset()
        {
            resetCalled = true;
        }
    }

    private static final float INTERNAL_LEVEL = DspConstants.INTERNAL_LEVEL;

    // ── stereo (2-channel) interleaved tests ─────────────────────────────────

    @Test
    void processInterleaved_stereo_convertsCorrectly()
    {
        var sink = new CaptureSink();
        var filter = new ShortToFloatFilter(sink);

        // L=0, R=16383, L=32767, R=-16384, L=-32768, R=0
        short[] pcm = { 0, 16383, 32767, -16384, -32768, 0 };
        filter.processInterleaved(pcm, 3, 2);

        assertEquals(3, sink.capturedFrames);
        assertEquals(0.0f, sink.capturedLeft[0], 0.001f);
        assertEquals(32767f / 32768f * INTERNAL_LEVEL, sink.capturedLeft[1], 0.001f);
        assertEquals(-32768f / 32768f * INTERNAL_LEVEL, sink.capturedLeft[2], 0.001f);

        assertEquals(16383f / 32768f * INTERNAL_LEVEL, sink.capturedRight[0], 0.001f);
        assertEquals(-16384f / 32768f * INTERNAL_LEVEL, sink.capturedRight[1], 0.001f);
        assertEquals(0.0f, sink.capturedRight[2], 0.001f);
    }

    @Test
    void processInterleaved_stereo_fullScalePositive_convertsToNearOne()
    {
        var sink = new CaptureSink();
        var filter = new ShortToFloatFilter(sink);

        short[] pcm = { 32767, 32767 };
        filter.processInterleaved(pcm, 1, 2);

        float expected = 32767f / 32768f * INTERNAL_LEVEL;
        assertEquals(expected, sink.capturedLeft[0], 0.0001f);
        assertEquals(expected, sink.capturedRight[0], 0.0001f);
    }

    @Test
    void processInterleaved_stereo_fullScaleNegative_convertsToNegativeOne()
    {
        var sink = new CaptureSink();
        var filter = new ShortToFloatFilter(sink);

        short[] pcm = { -32768, -32768 };
        filter.processInterleaved(pcm, 1, 2);

        float expected = -32768f / 32768f * INTERNAL_LEVEL;
        assertEquals(expected, sink.capturedLeft[0], 0.0001f);
        assertEquals(expected, sink.capturedRight[0], 0.0001f);
    }

    @Test
    void processInterleaved_stereo_silence_producesZero()
    {
        var sink = new CaptureSink();
        var filter = new ShortToFloatFilter(sink);

        short[] pcm = { 0, 0, 0, 0 };
        filter.processInterleaved(pcm, 2, 2);

        assertEquals(0.0f, sink.capturedLeft[0], 0.0f);
        assertEquals(0.0f, sink.capturedLeft[1], 0.0f);
        assertEquals(0.0f, sink.capturedRight[0], 0.0f);
        assertEquals(0.0f, sink.capturedRight[1], 0.0f);
    }

    // ── mono (1-channel) interleaved tests ────────────────────────────────────

    @Test
    void processInterleaved_mono_mirrorsBothChannels()
    {
        var sink = new CaptureSink();
        var filter = new ShortToFloatFilter(sink);

        short[] pcm = { 16384, -16384 };
        filter.processInterleaved(pcm, 2, 1);

        float expectedPos = 16384f / 32768f * INTERNAL_LEVEL;
        float expectedNeg = -16384f / 32768f * INTERNAL_LEVEL;

        assertEquals(expectedPos, sink.capturedLeft[0], 0.001f);
        assertEquals(expectedPos, sink.capturedRight[0], 0.001f);
        assertEquals(expectedNeg, sink.capturedLeft[1], 0.001f);
        assertEquals(expectedNeg, sink.capturedRight[1], 0.001f);
    }

    @Test
    void processInterleaved_mono_silence_producesZero()
    {
        var sink = new CaptureSink();
        var filter = new ShortToFloatFilter(sink);

        filter.processInterleaved(new short[] { 0, 0, 0 }, 3, 1);

        assertEquals(0.0f, sink.capturedLeft[0], 0.0f);
        assertEquals(0.0f, sink.capturedRight[0], 0.0f);
    }

    // ── buffer growth tests ───────────────────────────────────────────────────

    @Test
    void processInterleaved_growsBufferWhenFrameCountIncreases()
    {
        var sink = new CaptureSink();
        var filter = new ShortToFloatFilter(sink);

        // First call with 2 frames
        filter.processInterleaved(new short[] { 100, 200, 300, 400 }, 2, 2);
        assertEquals(2, sink.capturedFrames);

        // Second call with more frames — buffer must grow
        short[] larger = new short[10];
        for (int i = 0; i < 10; i++)
            larger[i] = (short) (i * 3000);
        filter.processInterleaved(larger, 5, 2);
        assertEquals(5, sink.capturedFrames);
    }

    @Test
    void processInterleaved_reusesSameBuffer_whenFrameCountDoesNotGrow()
    {
        var sink = new CaptureSink();
        var filter = new ShortToFloatFilter(sink);

        short[] pcm = { 1000, 2000 };
        filter.processInterleaved(pcm, 1, 2);
        float leftFirst = sink.capturedLeft[0];

        short[] pcm2 = { 4000, 8000 };
        filter.processInterleaved(pcm2, 1, 2);
        float leftSecond = sink.capturedLeft[0];

        assertNotEquals(leftFirst, leftSecond, 0.0001f,
                "Second call with different data should produce different output");
    }

    // ── pass-through float process tests ─────────────────────────────────────

    @Test
    void process_floatArrays_delegatesToNext()
    {
        var sink = new CaptureSink();
        var filter = new ShortToFloatFilter(sink);

        float[] left = { 0.1f, 0.2f };
        float[] right = { 0.3f, 0.4f };
        filter.process(left, right, 2);

        assertEquals(2, sink.capturedFrames);
        assertArrayEquals(left, sink.capturedLeft, 0.0001f);
        assertArrayEquals(right, sink.capturedRight, 0.0001f);
    }

    // ── reset propagation ────────────────────────────────────────────────────

    @Test
    void reset_delegatesToNext()
    {
        var sink = new CaptureSink();
        var filter = new ShortToFloatFilter(sink);

        filter.reset();

        assertTrue(sink.resetCalled, "reset() should propagate to next processor");
    }

    // ── one-frame boundary ────────────────────────────────────────────────────

    @Test
    void processInterleaved_oneFrame_stereo_convertsCorrectly()
    {
        var sink = new CaptureSink();
        var filter = new ShortToFloatFilter(sink);

        short[] pcm = { (short) 16000, (short) -16000 };
        filter.processInterleaved(pcm, 1, 2);

        assertEquals(1, sink.capturedFrames);
        assertEquals(16000f / 32768f * INTERNAL_LEVEL, sink.capturedLeft[0], 0.001f);
        assertEquals(-16000f / 32768f * INTERNAL_LEVEL, sink.capturedRight[0], 0.001f);
    }

    @Test
    void processInterleaved_oneFrame_mono_convertsCorrectly()
    {
        var sink = new CaptureSink();
        var filter = new ShortToFloatFilter(sink);

        short[] pcm = { (short) 8192 };
        filter.processInterleaved(pcm, 1, 1);

        assertEquals(1, sink.capturedFrames);
        float expected = 8192f / 32768f * INTERNAL_LEVEL;
        assertEquals(expected, sink.capturedLeft[0], 0.001f);
        assertEquals(expected, sink.capturedRight[0], 0.001f);
    }

    // ── pipeline round-trip (headroom) ────────────────────────────────────────

    @Test
    void processInterleaved_fullScale_pipelineRoundTrip_preservesAmplitude()
    {
        // ShortToFloat(×0.25) → MasterGain(×4) → Sink should preserve full-scale
        var sink = new CaptureSink();
        AudioProcessor gain = new MasterGainFilter(sink, DspConstants.INTERNAL_LEVEL_INV);
        var filter = new ShortToFloatFilter(gain);

        short[] pcm = { 32767, 32767, -32768, -32768 };
        filter.processInterleaved(pcm, 2, 2);

        assertEquals(1.0f, sink.capturedLeft[0], 0.002f);
        assertEquals(-1.0f, sink.capturedLeft[1], 0.002f);
    }
}

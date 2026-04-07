/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.dsp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.fupfin.midiraja.midi.AudioEngine;

class FloatToShortSinkTest
{
    // ── stub AudioEngine ──────────────────────────────────────────────────────

    static class StubEngine implements AudioEngine
    {
        final List<short[]> pushes = new ArrayList<>();
        final List<Integer> offsets = new ArrayList<>();
        final List<Integer> lengths = new ArrayList<>();
        int flushCount = 0;
        int pushResult = -9999; // sentinel: must be set per-test

        /** Returns pushResult each time; if list provided, returns values in order. */
        final List<Integer> pushResults = new ArrayList<>();
        int pushCallCount = 0;

        void willReturnOnPush(int... results)
        {
            for (int r : results)
                pushResults.add(r);
        }

        @Override
        public void init(int sampleRate, int channels, int bufferSize)
        {
        }

        @Override
        public int push(short[] pcm)
        {
            return push(pcm, 0, pcm.length);
        }

        @Override
        public int push(short[] pcm, int offset, int length)
        {
            pushes.add(Arrays.copyOfRange(pcm, offset, offset + length));
            offsets.add(offset);
            lengths.add(length);
            if (!pushResults.isEmpty() && pushCallCount < pushResults.size())
                return pushResults.get(pushCallCount++);
            return pushResult == -9999 ? length : pushResult;
        }

        @Override
        public int getBufferCapacityFrames()
        {
            return 4096;
        }

        @Override
        public int getQueuedFrames()
        {
            return 0;
        }

        @Override
        public int getDeviceLatencyFrames()
        {
            return 0;
        }

        @Override
        public void flush()
        {
            flushCount++;
        }

        @Override
        public void close()
        {
        }

        @Override
        public void enableDump(String filename)
        {
        }
    }

    // ── process(): null engine is a no-op ─────────────────────────────────────

    @org.junit.jupiter.api.Test
    void process_null_engine_is_noop()
    {
        var sink = new FloatToShortSink(null);
        assertDoesNotThrow(() -> sink.process(new float[] { 1.0f }, new float[] { 1.0f }, 1));
    }

    // ── process(): float → short conversion ───────────────────────────────────

    @org.junit.jupiter.api.Test
    void process_positive_full_scale_maps_to_32767()
    {
        var engine = new StubEngine();
        var sink = new FloatToShortSink(engine, 2, 1);

        sink.process(new float[] { 1.0f }, new float[] { 1.0f }, 1);

        assertEquals(32767, engine.pushes.get(0)[0], "1.0f L should map to 32767");
        assertEquals(32767, engine.pushes.get(0)[1], "1.0f R should map to 32767");
    }

    @org.junit.jupiter.api.Test
    void process_negative_full_scale_maps_to_minus_32767()
    {
        var engine = new StubEngine();
        var sink = new FloatToShortSink(engine, 2, 1);

        sink.process(new float[] { -1.0f }, new float[] { -1.0f }, 1);

        assertEquals(-32767, engine.pushes.get(0)[0], "-1.0f L should map to -32767");
        assertEquals(-32767, engine.pushes.get(0)[1], "-1.0f R should map to -32767");
    }

    @org.junit.jupiter.api.Test
    void process_clipping_above_1_clamps_to_32767()
    {
        var engine = new StubEngine();
        var sink = new FloatToShortSink(engine, 2, 1);

        sink.process(new float[] { 2.0f }, new float[] { 2.0f }, 1);

        assertEquals(32767, engine.pushes.get(0)[0], "2.0f should clip to 32767");
    }

    @org.junit.jupiter.api.Test
    void process_clipping_below_minus1_clamps_to_minus_32767()
    {
        var engine = new StubEngine();
        var sink = new FloatToShortSink(engine, 2, 1);

        sink.process(new float[] { -2.0f }, new float[] { -2.0f }, 1);

        assertEquals(-32767, engine.pushes.get(0)[0], "-2.0f should clip to -32767");
    }

    // ── process(): stereo interleaving ────────────────────────────────────────

    @org.junit.jupiter.api.Test
    void process_stereo_interleaves_left_right_correctly()
    {
        var engine = new StubEngine();
        var sink = new FloatToShortSink(engine, 2, 1);

        // Two frames: (L0=0.5, R0=-0.5) and (L1=0.25, R1=-0.25)
        sink.process(new float[] { 0.5f, 0.25f }, new float[] { -0.5f, -0.25f }, 2);

        short[] pcm = engine.pushes.get(0);
        assertEquals(4, pcm.length, "2 stereo frames = 4 shorts");
        assertEquals((short) (0.5f * 32767.0f), pcm[0], "frame0 L");
        assertEquals((short) (-0.5f * 32767.0f), pcm[1], "frame0 R");
        assertEquals((short) (0.25f * 32767.0f), pcm[2], "frame1 L");
        assertEquals((short) (-0.25f * 32767.0f), pcm[3], "frame1 R");
    }

    // ── process(): mono output ────────────────────────────────────────────────

    @org.junit.jupiter.api.Test
    void process_mono_output_averages_left_and_right()
    {
        var engine = new StubEngine();
        var sink = new FloatToShortSink(engine, 1, 1);

        // L=0.6, R=0.4 → avg = 0.5
        sink.process(new float[] { 0.6f }, new float[] { 0.4f }, 1);

        short[] pcm = engine.pushes.get(0);
        assertEquals(1, pcm.length, "mono output should produce 1 short per frame");
        assertEquals((short) (0.5f * 32767.0f), pcm[0], 1, "mono average should be (L+R)/2");
    }

    @org.junit.jupiter.api.Test
    void process_mono_silence_produces_zero()
    {
        var engine = new StubEngine();
        var sink = new FloatToShortSink(engine, 1, 1);

        sink.process(new float[] { 0.0f }, new float[] { 0.0f }, 1);

        assertEquals(0, engine.pushes.get(0)[0]);
    }

    // ── process(): silence → all zeros ───────────────────────────────────────

    @org.junit.jupiter.api.Test
    void process_silence_produces_all_zero_pcm()
    {
        var engine = new StubEngine();
        var sink = new FloatToShortSink(engine, 2, 1);

        float[] zeros = new float[8];
        sink.process(zeros, zeros, 8);

        for (short s : engine.pushes.get(0))
            assertEquals(0, s, "silence should produce zero PCM");
    }

    // ── process(): engine called with correct offset / length ─────────────────

    @org.junit.jupiter.api.Test
    void process_engine_called_with_correct_offset_and_length()
    {
        var engine = new StubEngine();
        var sink = new FloatToShortSink(engine, 2, 1);

        sink.process(new float[] { 0.1f, 0.2f, 0.3f }, new float[] { 0.1f, 0.2f, 0.3f }, 3);

        assertEquals(0, engine.offsets.get(0), "initial push should start at offset 0");
        assertEquals(6, engine.lengths.get(0), "3 stereo frames = 6 samples total");
    }

    // ── process(): flush path — push returns 0 exactly flushThreshold times ──

    @org.junit.jupiter.api.Test
    void process_flush_called_after_threshold_zero_returns()
    {
        var engine = new StubEngine();
        // push returns 0 exactly flushThreshold(=2) times, then returns full length
        engine.willReturnOnPush(0, 0, 2); // threshold=2: flush after 2 stuck loops
        var sink = new FloatToShortSink(engine, 2, 2);

        sink.process(new float[] { 0.5f }, new float[] { 0.5f }, 1);

        assertEquals(1, engine.flushCount, "flush must be called once after threshold zero-returns");
    }

    @org.junit.jupiter.api.Test
    void process_negative_push_aborts_loop_without_flush()
    {
        var engine = new StubEngine();
        engine.willReturnOnPush(-1); // immediate error
        var sink = new FloatToShortSink(engine, 2, 1);

        sink.process(new float[] { 0.5f }, new float[] { 0.5f }, 1);

        assertEquals(0, engine.flushCount, "negative push return must not trigger flush");
    }

    @org.junit.jupiter.api.Test
    void process_zero_then_positive_completes_without_flush()
    {
        var engine = new StubEngine();
        engine.willReturnOnPush(0, 2); // stuck once, then succeeds
        var sink = new FloatToShortSink(engine, 2, 2); // threshold=2, so 1 zero is not enough

        sink.process(new float[] { 0.5f }, new float[] { 0.5f }, 1);

        assertEquals(0, engine.flushCount, "one zero return should not trigger flush (threshold=2)");
    }

    // ── reset() ───────────────────────────────────────────────────────────────

    @org.junit.jupiter.api.Test
    void reset_calls_engine_flush()
    {
        var engine = new StubEngine();
        var sink = new FloatToShortSink(engine, 2, 1);

        sink.reset();

        assertEquals(1, engine.flushCount, "reset() must call engine.flush()");
    }

    @org.junit.jupiter.api.Test
    void reset_null_engine_does_not_crash()
    {
        var sink = new FloatToShortSink(null);
        assertDoesNotThrow(sink::reset, "reset() with null engine must not throw");
    }

    // ── processInterleaved(): null engine is a no-op ──────────────────────────

    @org.junit.jupiter.api.Test
    void processInterleaved_null_engine_is_noop()
    {
        var sink = new FloatToShortSink(null);
        short[] pcm = { 1000, 2000 };
        assertDoesNotThrow(() -> sink.processInterleaved(pcm, 1, 2));
    }

    // ── processInterleaved(): engine called with correct data ─────────────────

    @org.junit.jupiter.api.Test
    void processInterleaved_engine_receives_data_with_correct_offset_and_length()
    {
        var engine = new StubEngine();
        var sink = new FloatToShortSink(engine, 2, 1);

        short[] pcm = { 100, 200, 300, 400 };
        sink.processInterleaved(pcm, 2, 2);

        assertFalse(engine.pushes.isEmpty(), "engine.push must be called");
        assertEquals(0, engine.offsets.get(0), "initial push must start at offset 0");
        assertEquals(4, engine.lengths.get(0), "2 stereo frames = 4 samples");
    }

    @org.junit.jupiter.api.Test
    void processInterleaved_engine_receives_correct_pcm_values()
    {
        var engine = new StubEngine();
        var sink = new FloatToShortSink(engine, 2, 1);

        short[] pcm = { 1000, -1000, 2000, -2000 };
        sink.processInterleaved(pcm, 2, 2);

        short[] sent = engine.pushes.get(0);
        assertEquals(1000, sent[0]);
        assertEquals(-1000, sent[1]);
        assertEquals(2000, sent[2]);
        assertEquals(-2000, sent[3]);
    }

    // ── processInterleaved(): error path ─────────────────────────────────────

    @org.junit.jupiter.api.Test
    void processInterleaved_negative_push_aborts_without_flush()
    {
        var engine = new StubEngine();
        engine.willReturnOnPush(-1);
        var sink = new FloatToShortSink(engine, 2, 1);

        short[] pcm = { 100, 200 };
        sink.processInterleaved(pcm, 1, 2);

        assertEquals(0, engine.flushCount, "negative push in processInterleaved must not flush");
    }

    @org.junit.jupiter.api.Test
    void processInterleaved_flush_called_after_threshold_zero_returns()
    {
        var engine = new StubEngine();
        // push returns 0 twice (threshold=2), then succeeds
        engine.willReturnOnPush(0, 0, 2);
        var sink = new FloatToShortSink(engine, 2, 2);

        short[] pcm = { 100, 200 };
        sink.processInterleaved(pcm, 1, 2);

        assertEquals(1, engine.flushCount,
                "flush must be called once after threshold zero-returns in processInterleaved");
    }

    // ── zero-frame boundary ───────────────────────────────────────────────────

    @org.junit.jupiter.api.Test
    void process_zero_frames_does_not_crash()
    {
        var engine = new StubEngine();
        var sink = new FloatToShortSink(engine, 2, 1);

        assertDoesNotThrow(() -> sink.process(new float[0], new float[0], 0));
        assertTrue(engine.pushes.isEmpty(), "zero frames must not push anything");
    }

    @org.junit.jupiter.api.Test
    void processInterleaved_zero_frames_does_not_crash()
    {
        var engine = new StubEngine();
        var sink = new FloatToShortSink(engine, 2, 1);

        assertDoesNotThrow(() -> sink.processInterleaved(new short[0], 0, 2));
        assertTrue(engine.pushes.isEmpty(), "zero frames must not push anything");
    }

    // ── DEFAULT_flushThreshold constant is accessible ─────────────────────────

    @org.junit.jupiter.api.Test
    void default_flush_threshold_constant_is_500()
    {
        assertEquals(500, FloatToShortSink.DEFAULT_flushThreshold,
                "DEFAULT_flushThreshold should be 500 (~500ms of 1ms sleeps)");
    }
}

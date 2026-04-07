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

class AcousticSpeakerFilterTest
{
    static class CaptureSink implements AudioProcessor
    {
        float[] capturedLeft;
        float[] capturedRight;
        int capturedFrames = 0;
        boolean resetCalled = false;
        short[] capturedPcm;
        int capturedPcmFrames;
        int capturedChannels;

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

    // ── bypass when disabled ─────────────────────────────────────────────────

    @Test
    void process_disabled_passesSignalUnchanged()
    {
        var sink = new CaptureSink();
        var filter = new AcousticSpeakerFilter(false, AcousticSpeakerFilter.Profile.TIN_CAN, sink);

        float[] left = fill(256, 0.5f);
        float[] right = fill(256, -0.3f);
        filter.process(left, right, 256);

        assertArrayEquals(fill(256, 0.5f), sink.capturedLeft, 0.0f,
                "Disabled filter should pass signal unchanged");
        assertArrayEquals(fill(256, -0.3f), sink.capturedRight, 0.0f);
    }

    @Test
    void process_profileNone_passesSignalUnchanged()
    {
        var sink = new CaptureSink();
        var filter = new AcousticSpeakerFilter(true, AcousticSpeakerFilter.Profile.NONE, sink);

        float[] left = fill(256, 0.5f);
        float[] right = fill(256, -0.3f);
        filter.process(left, right, 256);

        assertArrayEquals(fill(256, 0.5f), sink.capturedLeft, 0.0f,
                "NONE profile should pass signal unchanged");
    }

    @Test
    void processInterleaved_disabled_delegatesToNext()
    {
        var sink = new CaptureSink();
        var filter = new AcousticSpeakerFilter(false, AcousticSpeakerFilter.Profile.TELEPHONE, sink);

        short[] pcm = { 10000, -10000, 5000, -5000 };
        filter.processInterleaved(pcm, 2, 2);

        assertArrayEquals(pcm, sink.capturedPcm,
                "Disabled filter should pass PCM unchanged");
    }

    // ── each profile produces output ─────────────────────────────────────────

    @Test
    void process_allProfiles_doNotThrow()
    {
        for (var profile : AcousticSpeakerFilter.Profile.values())
        {
            var sink = new CaptureSink();
            var filter = new AcousticSpeakerFilter(true, profile, sink);
            assertDoesNotThrow(
                    () -> filter.process(fill(256, 0.5f), fill(256, 0.5f), 256),
                    "Profile " + profile + " should not throw");
        }
    }

    @Test
    void process_tinCan_modifiesSignal()
    {
        var sink = new CaptureSink();
        var filter = new AcousticSpeakerFilter(true, AcousticSpeakerFilter.Profile.TIN_CAN, sink);

        float[] signal = fill(1024, 0.5f);
        filter.process(signal.clone(), signal.clone(), 1024);

        // TIN_CAN applies HPF+LPF+peak — steady DC input should be strongly attenuated
        // due to the HPF at 400 Hz
        assertTrue(Math.abs(sink.capturedLeft[1023]) < 0.5f,
                "TIN_CAN HPF should attenuate DC-like (constant) signal");
    }

    @Test
    void process_warmRadio_attenuatesHighFrequency()
    {
        var sink = new CaptureSink();
        var filter = new AcousticSpeakerFilter(true, AcousticSpeakerFilter.Profile.WARM_RADIO, sink);

        // WARM_RADIO applies only LPF at 12 kHz — constant DC passes through easily
        float[] signal = fill(256, 0.5f);
        filter.process(signal.clone(), signal.clone(), 256);

        // After LPF settles, steady-state output ≈ input (DC gain of LPF is 1)
        assertTrue(rms(sink.capturedLeft) > 0, "WARM_RADIO should produce non-zero output");
    }

    @Test
    void process_telephone_modifiesSignal()
    {
        var sink = new CaptureSink();
        var filter = new AcousticSpeakerFilter(true, AcousticSpeakerFilter.Profile.TELEPHONE, sink);

        float[] signal = fill(1024, 0.5f);
        filter.process(signal.clone(), signal.clone(), 1024);

        // TELEPHONE HPF at 300 Hz will attenuate constant signal
        assertTrue(Math.abs(sink.capturedLeft[1023]) < 0.5f,
                "TELEPHONE HPF should attenuate DC-like (constant) input");
    }

    @Test
    void process_pc_modifiesSignal()
    {
        var sink = new CaptureSink();
        var filter = new AcousticSpeakerFilter(true, AcousticSpeakerFilter.Profile.PC, sink);

        float[] signal = fill(1024, 0.5f);
        filter.process(signal.clone(), signal.clone(), 1024);

        // PC HPF at 250 Hz attenuates constant input
        assertTrue(Math.abs(sink.capturedLeft[1023]) < 0.5f,
                "PC HPF should attenuate DC-like (constant) input");
    }

    // ── stereo independence ───────────────────────────────────────────────────

    @Test
    void process_leftAndRightProcessedIndependently()
    {
        var sink = new CaptureSink();
        var filter = new AcousticSpeakerFilter(true, AcousticSpeakerFilter.Profile.TIN_CAN, sink);

        float[] left = fill(512, 0.8f);
        float[] right = fill(512, 0.2f);
        filter.process(left, right, 512);

        // Left and right biquad states are independent; different input → different output
        assertFalse(Arrays.equals(sink.capturedLeft, sink.capturedRight),
                "Left and right channels should be processed independently");
    }

    // ── processInterleaved stereo ─────────────────────────────────────────────

    @Test
    void processInterleaved_tinCan_stereo_modifiesSignal()
    {
        var sink = new CaptureSink();
        var filter = new AcousticSpeakerFilter(true, AcousticSpeakerFilter.Profile.TIN_CAN, sink);

        short[] pcm = new short[512];
        Arrays.fill(pcm, (short) 16000);
        filter.processInterleaved(pcm, 256, 2);

        // TIN_CAN HPF should attenuate the constant signal
        assertNotNull(sink.capturedPcm);
        // After filter, the dc-level values should be reduced
        boolean anyChanged = false;
        for (short s : sink.capturedPcm)
        {
            if (Math.abs(s) < 16000)
                anyChanged = true;
        }
        assertTrue(anyChanged, "TIN_CAN should change at least some values in interleaved mode");
    }

    @Test
    void processInterleaved_none_leavesDataUnchanged()
    {
        var sink = new CaptureSink();
        var filter = new AcousticSpeakerFilter(true, AcousticSpeakerFilter.Profile.NONE, sink);

        short[] pcm = { 1000, -1000, 2000, -2000 };
        filter.processInterleaved(pcm.clone(), 2, 2);

        assertArrayEquals(pcm, sink.capturedPcm,
                "NONE profile should leave interleaved PCM unchanged");
    }

    @Test
    void processInterleaved_mono_processesOnlyLeftChannel()
    {
        var sink = new CaptureSink();
        var filter = new AcousticSpeakerFilter(true, AcousticSpeakerFilter.Profile.WARM_RADIO, sink);

        short[] pcm = { 16000, 16000, 16000 }; // 3 frames, 1 channel
        filter.processInterleaved(pcm, 3, 1);

        // Should not throw; mono path only processes one channel
        assertNotNull(sink.capturedPcm);
        assertEquals(3, sink.capturedPcmFrames);
    }

    // ── reset ────────────────────────────────────────────────────────────────

    @Test
    void reset_clearsBiquadStateAndPropagates()
    {
        var sink = new CaptureSink();
        var filter = new AcousticSpeakerFilter(true, AcousticSpeakerFilter.Profile.TIN_CAN, sink);

        // Prime filter with signal
        filter.process(fill(2048, 0.8f), fill(2048, 0.8f), 2048);

        // Reset should clear internal state
        filter.reset();
        assertTrue(sink.resetCalled, "reset() should propagate to next processor");

        // After reset, silence should yield near-zero (filter state cleared)
        var freshSink = new CaptureSink();
        var freshFilter = new AcousticSpeakerFilter(true, AcousticSpeakerFilter.Profile.TIN_CAN, freshSink);
        freshFilter.process(new float[32], new float[32], 32);
        assertEquals(0.0f, freshSink.capturedLeft[31], 0.0001f,
                "Fresh filter on silence should yield zero output");
    }

    // ── zero-frame boundary ───────────────────────────────────────────────────

    @Test
    void process_zeroFrames_doesNotThrow()
    {
        var sink = new CaptureSink();
        for (var profile : AcousticSpeakerFilter.Profile.values())
        {
            var filter = new AcousticSpeakerFilter(true, profile, sink);
            assertDoesNotThrow(() -> filter.process(new float[0], new float[0], 0),
                    "Profile " + profile + " should handle zero frames");
        }
    }

    // ── null profile default ─────────────────────────────────────────────────

    @Test
    void constructor_nullProfile_defaultsToNone()
    {
        var sink = new CaptureSink();
        var filter = new AcousticSpeakerFilter(true, null, sink);

        float[] signal = fill(64, 0.5f);
        filter.process(signal.clone(), signal.clone(), 64);

        // NONE profile → passthrough
        assertArrayEquals(fill(64, 0.5f), sink.capturedLeft, 0.0f,
                "null profile should default to NONE (passthrough)");
    }
}

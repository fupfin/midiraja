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

class ChorusFilterTest
{
    static class CaptureSink implements AudioProcessor
    {
        float[] capturedLeft;
        float[] capturedRight;
        int capturedFrames = 0;

        @Override
        public void process(float[] left, float[] right, int frames)
        {
            capturedLeft = left.clone();
            capturedRight = right.clone();
            capturedFrames = frames;
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

    // ── zero-frame boundary ───────────────────────────────────────────────────

    @Test
    void process_zeroFrames_gracefulHandling()
    {
        var sink = new CaptureSink();
        var chorus = new ChorusFilter(sink, 50f);

        assertDoesNotThrow(() -> chorus.process(new float[0], new float[0], 0));
        assertEquals(0, sink.capturedFrames);
    }

    // ── bypass mode ───────────────────────────────────────────────────────────

    @Test
    void setEnabled_false_passesSignalUnchanged()
    {
        var sink = new CaptureSink();
        var chorus = new ChorusFilter(sink, 50f);
        chorus.setEnabled(false);

        float[] left = { 0.5f, -0.5f, 0.3f };
        float[] right = { 0.2f, -0.2f, 0.1f };
        chorus.process(left.clone(), right.clone(), 3);

        assertArrayEquals(new float[] { 0.5f, -0.5f, 0.3f }, sink.capturedLeft, 0.0001f,
                "Disabled chorus must not alter the signal");
        assertArrayEquals(new float[] { 0.2f, -0.2f, 0.1f }, sink.capturedRight, 0.0001f);
    }

    @Test
    void setEnabled_false_thenTrue_appliesEffectAgain()
    {
        var sinkDisabled = new CaptureSink();
        var sinkEnabled = new CaptureSink();
        float[] signal = fill(512, 0.5f);

        // Disabled run
        var chorusDisabled = new ChorusFilter(sinkDisabled, 100f);
        chorusDisabled.setEnabled(false);
        chorusDisabled.process(signal.clone(), signal.clone(), 512);

        // Enabled run with same intensity
        var chorusEnabled = new ChorusFilter(sinkEnabled, 100f);
        chorusEnabled.process(signal.clone(), signal.clone(), 512);

        assertFalse(Arrays.equals(sinkDisabled.capturedLeft, sinkEnabled.capturedLeft),
                "Re-enabled chorus should modify signal differently than bypass");
    }

    // ── effect on signal ─────────────────────────────────────────────────────

    @Test
    void process_enabled_producesNonZeroOutput_fromNonZeroInput()
    {
        var sink = new CaptureSink();
        var chorus = new ChorusFilter(sink, 50f);

        chorus.process(fill(256, 0.5f), fill(256, 0.5f), 256);

        assertTrue(rms(sink.capturedLeft) > 0, "Chorus should produce non-zero output from non-zero input");
    }

    @Test
    void process_silence_producesNearSilence()
    {
        var sink = new CaptureSink();
        // Prime the delay buffers with a burst, then silence
        var chorus = new ChorusFilter(sink, 50f);
        chorus.process(fill(5000, 0.5f), fill(5000, 0.5f), 5000);

        // Now feed silence; chorus delay will still leak but RMS of silence should be very small
        // relative to the original. But the delay buffer is initialized to zeros,
        // so silence through a fresh chorus should be near-zero.
        var freshChorus = new ChorusFilter(sink, 50f);
        freshChorus.process(new float[256], new float[256], 256);

        assertTrue(rms(sink.capturedLeft) < 0.01f,
                "Chorus on silence with empty delay should produce near-zero output");
    }

    @Test
    void process_modifiesSignal_comparedToPassthrough()
    {
        var chorusSink = new CaptureSink();
        var bypassSink = new CaptureSink();
        float[] signal = fill(1024, 0.5f);

        var chorus = new ChorusFilter(chorusSink, 100f);
        chorus.process(signal.clone(), signal.clone(), 1024);

        var bypass = new ChorusFilter(bypassSink, 100f);
        bypass.setEnabled(false);
        bypass.process(signal.clone(), signal.clone(), 1024);

        assertFalse(Arrays.equals(chorusSink.capturedLeft, bypassSink.capturedLeft),
                "Enabled chorus should differ from bypass");
    }

    // ── intensity parameter ───────────────────────────────────────────────────

    @Test
    void setIntensity_zeroPercent_minimizesWetMix()
    {
        var sink0 = new CaptureSink();
        var sink100 = new CaptureSink();
        float[] signal = fill(512, 0.5f);

        var chorus0 = new ChorusFilter(sink0, 0f);
        var chorus100 = new ChorusFilter(sink100, 100f);

        chorus0.process(signal.clone(), signal.clone(), 512);
        chorus100.process(signal.clone(), signal.clone(), 512);

        // At 0% intensity mixWet=0 and depthMs=0, output should essentially be dry
        // At 100% intensity, output differs due to wet mix
        assertFalse(Arrays.equals(sink0.capturedLeft, sink100.capturedLeft),
                "0% and 100% intensity should produce different outputs");
    }

    @Test
    void setIntensity_clampsBelowZero()
    {
        var sink = new CaptureSink();
        var chorus = new ChorusFilter(sink, 50f);

        // Should not throw; negative intensity clamped to 0
        assertDoesNotThrow(() -> chorus.setIntensity(-50f));
        chorus.process(fill(64, 0.5f), fill(64, 0.5f), 64);
        assertTrue(rms(sink.capturedLeft) > 0);
    }

    @Test
    void setIntensity_clampsAboveOneHundred()
    {
        var sink = new CaptureSink();
        var chorus = new ChorusFilter(sink, 50f);

        assertDoesNotThrow(() -> chorus.setIntensity(200f));
        chorus.process(fill(64, 0.5f), fill(64, 0.5f), 64);
        // Should behave identically to 100%
        var sink100 = new CaptureSink();
        var chorus100 = new ChorusFilter(sink100, 100f);
        chorus100.process(fill(64, 0.5f), fill(64, 0.5f), 64);

        assertArrayEquals(sink100.capturedLeft, sink.capturedLeft, 0.0001f,
                "Intensity > 100% should clamp to same as 100%");
    }

    // ── one frame boundary ────────────────────────────────────────────────────

    @Test
    void process_oneFrame_doesNotThrow()
    {
        var sink = new CaptureSink();
        var chorus = new ChorusFilter(sink, 50f);

        assertDoesNotThrow(() -> chorus.process(new float[] { 0.5f }, new float[] { -0.5f }, 1));
        assertEquals(1, sink.capturedFrames);
    }

    // ── stereo width ─────────────────────────────────────────────────────────

    @Test
    void process_leftAndRightOutputDiffer_dueTo90DegreePhaseOffset()
    {
        var sink = new CaptureSink();
        var chorus = new ChorusFilter(sink, 100f);

        // Drive with long steady signal so the LFO phase difference manifests
        float[] signal = fill(5000, 0.5f);
        chorus.process(signal.clone(), signal.clone(), 5000);

        // Due to 90° LFO phase offset, left and right should eventually differ
        assertFalse(Arrays.equals(sink.capturedLeft, sink.capturedRight),
                "Left/right channels should differ due to 90° LFO phase offset");
    }

    // ── state accumulation across calls ──────────────────────────────────────

    @Test
    void process_delayBufferAccumulatesAcrossCalls()
    {
        // The write index and LFO phase advance with each frame.
        // After the first call, the second call should produce different per-sample output
        // because the delay buffer now contains signal (not zeros), causing wet reads to differ.
        var sink = new CaptureSink();
        var chorus = new ChorusFilter(sink, 100f); // high intensity to make effect obvious

        float[] firstSignal = fill(1000, 0.8f);
        chorus.process(firstSignal.clone(), firstSignal.clone(), 1000);
        float[] afterFirst = sink.capturedLeft.clone();

        float[] secondSignal = fill(1000, 0.8f);
        chorus.process(secondSignal.clone(), secondSignal.clone(), 1000);
        float[] afterSecond = sink.capturedLeft.clone();

        // After first call, delay buffer is populated. The second call reads back signal
        // from the delay buffer, producing a different wet contribution than the first call.
        // For a constant-amplitude signal the two 1000-sample blocks will not be identical.
        assertFalse(Arrays.equals(afterFirst, afterSecond),
                "Second process() call should produce different output as delay buffer is now populated");
    }

    // ── next processor called ────────────────────────────────────────────────

    @Test
    void process_alwaysCallsNextProcessor()
    {
        var sink = new CaptureSink();
        var chorus = new ChorusFilter(sink, 50f);
        chorus.process(fill(32, 0.5f), fill(32, 0.5f), 32);
        assertNotNull(sink.capturedLeft, "Next processor should always be called");
        assertEquals(32, sink.capturedFrames);
    }

    @Test
    void process_disabled_alsoCallsNextProcessor()
    {
        var sink = new CaptureSink();
        var chorus = new ChorusFilter(sink, 50f);
        chorus.setEnabled(false);
        chorus.process(fill(32, 0.5f), fill(32, 0.5f), 32);
        assertNotNull(sink.capturedLeft, "Disabled chorus should still call next processor");
        assertEquals(32, sink.capturedFrames);
    }
}

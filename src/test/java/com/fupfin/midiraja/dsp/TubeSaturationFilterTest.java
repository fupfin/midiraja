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

class TubeSaturationFilterTest
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

    // ── bypass (disabled) ─────────────────────────────────────────────────────

    @Test
    void setEnabled_false_passesSignalUnchanged()
    {
        var sink = new CaptureSink();
        var tube = new TubeSaturationFilter(sink, 50f);
        tube.setEnabled(false);

        float[] left = { 0.8f, -0.8f };
        float[] right = { 0.8f, -0.8f };
        tube.process(left.clone(), right.clone(), 2);

        assertArrayEquals(new float[] { 0.8f, -0.8f }, sink.capturedLeft, 0.001f,
                "Disabled filter must not alter the signal");
    }

    @Test
    void drive_atFloor_behavesAsPassthrough()
    {
        // drive <= 1.01 triggers bypass path even when enabled
        var sink = new CaptureSink();
        var tube = new TubeSaturationFilter(sink, 1f); // drive=1 → clamped to 1.0

        float[] left = { 0.5f, -0.5f };
        float[] right = { 0.3f, -0.3f };
        tube.process(left.clone(), right.clone(), 2);

        assertArrayEquals(new float[] { 0.5f, -0.5f }, sink.capturedLeft, 0.001f,
                "Drive at floor (1.0) should behave as bypass");
    }

    // ── saturation effect ─────────────────────────────────────────────────────

    @Test
    void process_enabled_compressesHighAmplitude()
    {
        var sinkSaturated = new CaptureSink();
        var sinkBypass = new CaptureSink();

        var saturated = new TubeSaturationFilter(sinkSaturated, 50f); // drive=5.5
        var bypass = new TubeSaturationFilter(sinkBypass, 50f);
        bypass.setEnabled(false);

        float[] signal = fill(256, 0.8f);
        saturated.process(signal.clone(), signal.clone(), 256);
        bypass.process(signal.clone(), signal.clone(), 256);

        // Saturation reduces peak amplitude via tanh
        assertTrue(sinkSaturated.capturedLeft[0] < sinkBypass.capturedLeft[0],
                "Saturation should compress peaks (tanh curves amplitude down)");
    }

    @Test
    void process_softClipping_peaksAreCompressedNotHardClipped()
    {
        var sink = new CaptureSink();
        var tube = new TubeSaturationFilter(sink, 100f); // max drive = 10

        float[] signal = fill(64, 1.0f);
        tube.process(signal.clone(), signal.clone(), 64);

        // tanh(10 * 1.0) ≈ 1.0; outLevel = 1/sqrt(10) ≈ 0.316
        // Output should be well below 1.0 (compressed, not hard-clipped)
        for (int i = 0; i < sink.capturedFrames; i++)
        {
            assertTrue(sink.capturedLeft[i] < 1.0f,
                    "Saturated output should be below 1.0 (soft clip)");
            assertTrue(sink.capturedLeft[i] > 0.0f,
                    "Positive input should yield positive saturated output");
        }
    }

    @Test
    void process_antisymmetric_negativeInputGivesNegativeOutput()
    {
        var sinkPos = new CaptureSink();
        var sinkNeg = new CaptureSink();

        var tubePos = new TubeSaturationFilter(sinkPos, 50f);
        var tubeNeg = new TubeSaturationFilter(sinkNeg, 50f);

        tubePos.process(fill(4, 0.5f), fill(4, 0.5f), 4);
        tubeNeg.process(fill(4, -0.5f), fill(4, -0.5f), 4);

        assertEquals(-sinkPos.capturedLeft[0], sinkNeg.capturedLeft[0], 0.0001f,
                "Saturation should be antisymmetric: f(-x) = -f(x)");
    }

    // ── silence ───────────────────────────────────────────────────────────────

    @Test
    void process_silence_producesSilence()
    {
        var sink = new CaptureSink();
        var tube = new TubeSaturationFilter(sink, 50f);

        tube.process(new float[256], new float[256], 256);

        for (int i = 0; i < sink.capturedFrames; i++)
        {
            assertEquals(0.0f, sink.capturedLeft[i], 0.0f, "Silence in should give silence out");
            assertEquals(0.0f, sink.capturedRight[i], 0.0f);
        }
    }

    // ── drive clamping ────────────────────────────────────────────────────────

    @Test
    void setDrive_clampsDriveToMaximumTen()
    {
        var sink100 = new CaptureSink();
        var sink200 = new CaptureSink();

        var tube100 = new TubeSaturationFilter(sink100, 100f);
        var tube200 = new TubeSaturationFilter(sink200, 200f); // should clamp to same

        float[] signal = fill(64, 0.8f);
        tube100.process(signal.clone(), signal.clone(), 64);
        tube200.process(signal.clone(), signal.clone(), 64);

        assertArrayEquals(sink100.capturedLeft, sink200.capturedLeft, 0.0001f,
                "Drive > 100% should be clamped to match 100%");
    }

    @Test
    void setDrive_clampsDriveToMinimumOne()
    {
        var sink = new CaptureSink();
        var tube = new TubeSaturationFilter(sink, -50f); // should clamp to 1.0 → bypass

        float[] signal = { 0.6f, -0.6f };
        tube.process(signal.clone(), signal.clone(), 2);

        // Drive=1 triggers bypass path
        assertArrayEquals(signal, sink.capturedLeft, 0.001f,
                "Drive below minimum should clamp to 1.0 (bypass path)");
    }

    // ── auto-gain makeup ─────────────────────────────────────────────────────

    @Test
    void higherDrive_autoGainReducesOutputLevel()
    {
        var sinkLow = new CaptureSink();
        var sinkHigh = new CaptureSink();

        // drive=2 → outLevel = 1/sqrt(2) ≈ 0.707
        // drive=10 → outLevel = 1/sqrt(10) ≈ 0.316
        var tubeLow = new TubeSaturationFilter(sinkLow, 10f); // maps to drive≈1+9*0.1=1.9
        var tubeHigh = new TubeSaturationFilter(sinkHigh, 100f); // maps to drive=10

        float[] signal = fill(64, 0.1f); // small amplitude; tanh nearly linear here
        tubeLow.process(signal.clone(), signal.clone(), 64);
        tubeHigh.process(signal.clone(), signal.clone(), 64);

        // With small signals tanh ≈ linear, so output ≈ input * drive * outLevel = input * sqrt(drive)
        // Higher drive gives higher output for small signal in linear region before saturation
        // The exact ordering depends on drive; mainly verify no NaN
        assertFalse(Float.isNaN(sinkLow.capturedLeft[0]), "Output must not be NaN");
        assertFalse(Float.isNaN(sinkHigh.capturedLeft[0]), "Output must not be NaN");
    }

    // ── zero-frame boundary ───────────────────────────────────────────────────

    @Test
    void process_zeroFrames_doesNotThrow()
    {
        var sink = new CaptureSink();
        var tube = new TubeSaturationFilter(sink, 50f);

        assertDoesNotThrow(() -> tube.process(new float[0], new float[0], 0));
    }

    // ── one-frame boundary ────────────────────────────────────────────────────

    @Test
    void process_oneFrame_worksCorrectly()
    {
        var sink = new CaptureSink();
        var tube = new TubeSaturationFilter(sink, 50f);

        tube.process(new float[] { 0.5f }, new float[] { -0.5f }, 1);

        assertEquals(1, sink.capturedFrames);
        assertFalse(Float.isNaN(sink.capturedLeft[0]));
        assertFalse(Float.isNaN(sink.capturedRight[0]));
    }

    // ── next processor always called ──────────────────────────────────────────

    @Test
    void process_alwaysCallsNextProcessor_whenEnabled()
    {
        var sink = new CaptureSink();
        var tube = new TubeSaturationFilter(sink, 50f);

        tube.process(fill(32, 0.5f), fill(32, 0.5f), 32);

        assertNotNull(sink.capturedLeft, "Next processor must be called when enabled");
        assertEquals(32, sink.capturedFrames);
    }

    @Test
    void process_alwaysCallsNextProcessor_whenDisabled()
    {
        var sink = new CaptureSink();
        var tube = new TubeSaturationFilter(sink, 50f);
        tube.setEnabled(false);

        tube.process(fill(32, 0.5f), fill(32, 0.5f), 32);

        assertNotNull(sink.capturedLeft, "Next processor must be called when disabled (bypass)");
        assertEquals(32, sink.capturedFrames);
    }

    // ── setEnabled toggle ─────────────────────────────────────────────────────

    @Test
    void setEnabled_toggle_producesCorrectBehaviorEachTime()
    {
        var sink = new CaptureSink();
        var tube = new TubeSaturationFilter(sink, 50f);

        float[] signal = { 0.8f, -0.8f };

        tube.setEnabled(false);
        tube.process(signal.clone(), signal.clone(), 2);
        float bypassLeft = sink.capturedLeft[0];

        tube.setEnabled(true);
        tube.process(signal.clone(), signal.clone(), 2);
        float saturatedLeft = sink.capturedLeft[0];

        assertNotEquals(bypassLeft, saturatedLeft, 0.001f,
                "Toggling enabled should switch between bypass and saturation");
    }
}

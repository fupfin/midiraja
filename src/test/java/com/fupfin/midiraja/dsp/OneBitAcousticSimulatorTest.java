/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.dsp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.Test;

class OneBitAcousticSimulatorTest
{
    private static final int SAMPLE_RATE = 44100;

    // ── helper utilities ──────────────────────────────────────────────────────

    private static float[] fill(int n, float v)
    {
        float[] a = new float[n];
        Arrays.fill(a, v);
        return a;
    }

    private static double mean(float[] buf)
    {
        double sum = 0;
        for (float v : buf)
            sum += v;
        return sum / buf.length;
    }

    // ── PWM mode: silence converges near zero after settling ─────────────────

    @Test
    void pwm_silenceInput_outputConvergesNearZeroAfterSettling()
    {
        var sim = new OneBitAcousticSimulator(SAMPLE_RATE, "pwm");

        // Feed a long silence so the filters settle
        int settle = 4096;
        float[] left = new float[settle];
        float[] right = new float[settle];
        sim.process(left, right, settle);

        // The mean of the settled output should be near zero
        assertTrue(Math.abs(mean(left)) < 0.1,
                "PWM silence output mean should be near zero after settling: " + mean(left));
        assertTrue(Math.abs(mean(right)) < 0.1,
                "PWM silence output mean should be near zero after settling: " + mean(right));
    }

    // ── PWM mode: polarity ───────────────────────────────────────────────────

    @Test
    void pwm_polarity_positiveInputBiasesOutputPositive()
    {
        float[] leftPos = fill(256, 0.5f);
        float[] rightPos = fill(256, 0.5f);
        var simPos = new OneBitAcousticSimulator(SAMPLE_RATE, "pwm");
        simPos.process(leftPos, rightPos, 256);

        float[] leftNeg = fill(256, -0.5f);
        float[] rightNeg = fill(256, -0.5f);
        var simNeg = new OneBitAcousticSimulator(SAMPLE_RATE, "pwm");
        simNeg.process(leftNeg, rightNeg, 256);

        // Positive input should produce higher average output than negative input
        double avgPos = 0, avgNeg = 0;
        for (int i = 128; i < 256; i++)
        {
            avgPos += leftPos[i];
            avgNeg += leftNeg[i];
        }
        assertTrue(avgPos > avgNeg, "Positive input must produce higher average than negative input");
        assertTrue(avgPos > 0, "Positive input must produce positive-biased output");
        assertTrue(avgNeg < 0, "Negative input must produce negative-biased output");
    }

    // ── PWM mode: deterministic (no randomness) ───────────────────────────────

    @Test
    void pwm_deterministicOutput_sameInputProducesSameOutput()
    {
        float[] left1 = fill(128, 0.3f);
        float[] right1 = fill(128, 0.3f);
        var sim1 = new OneBitAcousticSimulator(SAMPLE_RATE, "pwm");
        sim1.process(left1, right1, 128);

        float[] left2 = fill(128, 0.3f);
        float[] right2 = fill(128, 0.3f);
        var sim2 = new OneBitAcousticSimulator(SAMPLE_RATE, "pwm");
        sim2.process(left2, right2, 128);

        assertArrayEquals(left1, left2, 0.0f, "PWM output must be fully deterministic");
        assertArrayEquals(right1, right2, 0.0f, "PWM output must be fully deterministic");
    }

    // ── PWM mode: reset clears carrier phase ──────────────────────────────────

    @Test
    void pwm_reset_producesIdenticalOutputAfterReset()
    {
        var sim = new OneBitAcousticSimulator(SAMPLE_RATE, "pwm");

        float[] leftFirst = fill(64, 0.4f);
        float[] rightFirst = fill(64, 0.4f);
        sim.process(leftFirst, rightFirst, 64);

        sim.reset();

        float[] leftAfterReset = fill(64, 0.4f);
        float[] rightAfterReset = fill(64, 0.4f);
        sim.process(leftAfterReset, rightAfterReset, 64);

        // A fresh simulator with the same input should produce the same result as after reset
        var freshSim = new OneBitAcousticSimulator(SAMPLE_RATE, "pwm");
        float[] leftFresh = fill(64, 0.4f);
        float[] rightFresh = fill(64, 0.4f);
        freshSim.process(leftFresh, rightFresh, 64);

        assertArrayEquals(leftFresh, leftAfterReset, 0.0f,
                "Reset should restore simulator to initial state");
        assertArrayEquals(rightFresh, rightAfterReset, 0.0f,
                "Reset should restore simulator to initial state");
    }

    // ── DSD mode: seeded Random produces deterministic exact output ───────────

    @Test
    void dsd_seededRandom_deterministicExactOutput()
    {
        float[] left1 = fill(32, 0.3f);
        float[] right1 = fill(32, -0.2f);
        var sim1 = new OneBitAcousticSimulator(SAMPLE_RATE, "dsd", new Random(0));
        sim1.process(left1, right1, 32);

        float[] left2 = fill(32, 0.3f);
        float[] right2 = fill(32, -0.2f);
        var sim2 = new OneBitAcousticSimulator(SAMPLE_RATE, "dsd", new Random(0));
        sim2.process(left2, right2, 32);

        assertArrayEquals(left1, left2, 0.0f,
                "DSD with seeded Random(0) must produce identical output");
        assertArrayEquals(right1, right2, 0.0f,
                "DSD with seeded Random(0) must produce identical output");
    }

    // ── DSD mode: silence input long-run mean near zero ───────────────────────

    @Test
    void dsd_silenceInput_longRunMeanNearZero()
    {
        var sim = new OneBitAcousticSimulator(SAMPLE_RATE, "dsd", new Random(42));

        int n = 8192;
        float[] left = new float[n];
        float[] right = new float[n];
        sim.process(left, right, n);

        // Sigma-delta noise-shaping: long-run mean of silence should stay near zero
        double meanL = mean(left);
        double meanR = mean(right);
        assertTrue(Math.abs(meanL) < 0.05,
                "DSD silence long-run mean (left) should be near zero: " + meanL);
        assertTrue(Math.abs(meanR) < 0.05,
                "DSD silence long-run mean (right) should be near zero: " + meanR);
    }

    // ── DSD mode: reset clears accumulator state ──────────────────────────────

    @Test
    void dsd_reset_clearsAccumulatorState()
    {
        var sim = new OneBitAcousticSimulator(SAMPLE_RATE, "dsd", new Random(7));

        // Warm up with signal to build up accumulator state
        float[] leftWarm = fill(256, 0.8f);
        float[] rightWarm = fill(256, 0.8f);
        sim.process(leftWarm, rightWarm, 256);

        sim.reset();

        // After reset with silence, output should converge near zero — just as a fresh simulator
        var freshSim = new OneBitAcousticSimulator(SAMPLE_RATE, "dsd", new Random(99));

        int n = 512;
        float[] leftAfterReset = new float[n];
        float[] rightAfterReset = new float[n];
        float[] leftFresh = new float[n];
        float[] rightFresh = new float[n];

        sim.process(leftAfterReset, rightAfterReset, n);
        freshSim.process(leftFresh, rightFresh, n);

        // Both should settle near zero for silence input; verify they're in the same ballpark
        double meanAfterReset = 0, meanFresh = 0;
        for (int i = n / 2; i < n; i++)
        {
            meanAfterReset += leftAfterReset[i];
            meanFresh += leftFresh[i];
        }
        assertTrue(Math.abs(meanAfterReset) < 0.1, "DSD after reset: silence must settle near zero");
        assertTrue(Math.abs(meanFresh) < 0.1, "DSD fresh: silence must settle near zero");
    }

    // ── both modes: zero frames ───────────────────────────────────────────────

    @Test
    void pwm_zeroFrames_doesNotCrashAndArraysUnchanged()
    {
        var sim = new OneBitAcousticSimulator(SAMPLE_RATE, "pwm");
        float[] left = { 0.1f, 0.2f };
        float[] right = { 0.3f, 0.4f };

        assertDoesNotThrow(() -> sim.process(left, right, 0));

        assertEquals(0.1f, left[0], 0.0f, "Array contents must not be changed for zero frames");
        assertEquals(0.2f, left[1], 0.0f);
        assertEquals(0.3f, right[0], 0.0f);
        assertEquals(0.4f, right[1], 0.0f);
    }

    @Test
    void dsd_zeroFrames_doesNotCrashAndArraysUnchanged()
    {
        var sim = new OneBitAcousticSimulator(SAMPLE_RATE, "dsd", new Random(0));
        float[] left = { 0.1f, 0.2f };
        float[] right = { 0.3f, 0.4f };

        assertDoesNotThrow(() -> sim.process(left, right, 0));

        assertEquals(0.1f, left[0], 0.0f, "Array contents must not be changed for zero frames");
        assertEquals(0.2f, left[1], 0.0f);
        assertEquals(0.3f, right[0], 0.0f);
        assertEquals(0.4f, right[1], 0.0f);
    }

    // ── both modes: one frame ─────────────────────────────────────────────────

    @Test
    void pwm_oneFrame_doesNotThrow()
    {
        var sim = new OneBitAcousticSimulator(SAMPLE_RATE, "pwm");
        float[] left = { 0.5f };
        float[] right = { -0.5f };

        assertDoesNotThrow(() -> sim.process(left, right, 1));
        assertFalse(Float.isNaN(left[0]), "Single-frame PWM output must not be NaN");
        assertFalse(Float.isNaN(right[0]), "Single-frame PWM output must not be NaN");
    }

    @Test
    void dsd_oneFrame_doesNotThrow()
    {
        var sim = new OneBitAcousticSimulator(SAMPLE_RATE, "dsd", new Random(0));
        float[] left = { 0.5f };
        float[] right = { -0.5f };

        assertDoesNotThrow(() -> sim.process(left, right, 1));
        assertFalse(Float.isNaN(left[0]), "Single-frame DSD output must not be NaN");
        assertFalse(Float.isNaN(right[0]), "Single-frame DSD output must not be NaN");
    }

    // ── both modes: output bounded (soft-clipping; allow slight overshoot) ────

    @Test
    void pwm_outputBounded_noSampleExceedsHardLimit()
    {
        var sim = new OneBitAcousticSimulator(SAMPLE_RATE, "pwm");
        int n = 4096;
        float[] left = new float[n];
        float[] right = new float[n];
        for (int i = 0; i < n; i++)
        {
            left[i] = (float) Math.sin(i * 0.1);
            right[i] = (float) Math.cos(i * 0.1);
        }
        sim.process(left, right, n);

        for (int i = 0; i < n; i++)
        {
            assertTrue(left[i] >= -2.0f && left[i] <= 2.0f,
                    "PWM left[" + i + "] = " + left[i] + " exceeds hard bound ±2.0");
            assertTrue(right[i] >= -2.0f && right[i] <= 2.0f,
                    "PWM right[" + i + "] = " + right[i] + " exceeds hard bound ±2.0");
        }
    }

    @Test
    void dsd_outputBounded_noSampleExceedsHardLimit()
    {
        var sim = new OneBitAcousticSimulator(SAMPLE_RATE, "dsd", new Random(0));
        int n = 4096;
        float[] left = new float[n];
        float[] right = new float[n];
        for (int i = 0; i < n; i++)
        {
            left[i] = (float) Math.sin(i * 0.1);
            right[i] = (float) Math.cos(i * 0.1);
        }
        sim.process(left, right, n);

        for (int i = 0; i < n; i++)
        {
            assertTrue(left[i] >= -2.0f && left[i] <= 2.0f,
                    "DSD left[" + i + "] = " + left[i] + " exceeds hard bound ±2.0");
            assertTrue(right[i] >= -2.0f && right[i] <= 2.0f,
                    "DSD right[" + i + "] = " + right[i] + " exceeds hard bound ±2.0");
        }
    }

    // ── mode string case-insensitivity ────────────────────────────────────────

    @Test
    void modeString_caseInsensitive_PWM_uppercase_doesNotThrow()
    {
        assertDoesNotThrow(() -> {
            var sim = new OneBitAcousticSimulator(SAMPLE_RATE, "PWM");
            sim.process(fill(4, 0.5f), fill(4, 0.5f), 4);
        }, "Uppercase 'PWM' mode should be accepted");
    }

    @Test
    void modeString_caseInsensitive_DSD_uppercase_doesNotThrow()
    {
        assertDoesNotThrow(() -> {
            var sim = new OneBitAcousticSimulator(SAMPLE_RATE, "DSD");
            sim.process(fill(4, 0.5f), fill(4, 0.5f), 4);
        }, "Uppercase 'DSD' mode should be accepted");
    }

    @Test
    void modeString_caseInsensitive_Pwm_mixedCase_doesNotThrow()
    {
        assertDoesNotThrow(() -> {
            var sim = new OneBitAcousticSimulator(SAMPLE_RATE, "Pwm");
            sim.process(fill(4, 0.5f), fill(4, 0.5f), 4);
        }, "Mixed-case 'Pwm' mode should be accepted");
    }

    @Test
    void modeString_pwmAndPWM_produceSameOutput()
    {
        float[] leftLower = fill(64, 0.4f);
        float[] rightLower = fill(64, -0.3f);
        new OneBitAcousticSimulator(SAMPLE_RATE, "pwm").process(leftLower, rightLower, 64);

        float[] leftUpper = fill(64, 0.4f);
        float[] rightUpper = fill(64, -0.3f);
        new OneBitAcousticSimulator(SAMPLE_RATE, "PWM").process(leftUpper, rightUpper, 64);

        assertArrayEquals(leftLower, leftUpper, 0.0f,
                "'pwm' and 'PWM' should produce identical output");
        assertArrayEquals(rightLower, rightUpper, 0.0f,
                "'pwm' and 'PWM' should produce identical output");
    }
}

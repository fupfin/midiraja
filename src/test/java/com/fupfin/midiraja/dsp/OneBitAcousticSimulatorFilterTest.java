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

class OneBitAcousticSimulatorFilterTest
{
    // ── test doubles ─────────────────────────────────────────────────────────

    static class TrackingProcessor implements AudioProcessor
    {
        boolean processCalled = false;
        boolean processInterleavedCalled = false;
        boolean resetCalled = false;
        float[] capturedLeft;
        float[] capturedRight;
        int capturedFrames = 0;

        @Override
        public void process(float[] left, float[] right, int frames)
        {
            processCalled = true;
            capturedLeft = left.clone();
            capturedRight = right.clone();
            capturedFrames = frames;
        }

        @Override
        public void processInterleaved(short[] interleavedPcm, int frames, int channels)
        {
            processInterleavedCalled = true;
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

    // ── enabled=false: process() returns immediately without calling next ─────

    @Test
    void disabled_process_doesNotCallNext()
    {
        // Current impl: "if (!enabled) return;" — next.process() is never reached
        var next = new TrackingProcessor();
        var filter = new OneBitAcousticSimulatorFilter(false, "pwm", next);

        filter.process(fill(4, 0.5f), fill(4, 0.5f), 4);

        assertFalse(next.processCalled,
            "Disabled filter must return immediately without calling next.process()");
    }

    // ── enabled=false: processInterleaved() delegates to next ─────────────────

    @Test
    void disabled_processInterleaved_delegatesToNext()
    {
        var next = new TrackingProcessor();
        var filter = new OneBitAcousticSimulatorFilter(false, "pwm", next);

        filter.processInterleaved(new short[] { 1000, -1000 }, 1, 2);

        assertTrue(next.processInterleavedCalled,
            "Disabled filter must still delegate processInterleaved() to next");
    }

    // ── enabled=true: process() runs simulator then next ─────────────────────

    @Test
    void enabled_process_callsSimulatorThenNext()
    {
        var next = new TrackingProcessor();
        var filter = new OneBitAcousticSimulatorFilter(true, "pwm", next);
        float[] originalLeft = fill(64, 0.5f);
        float[] originalRight = fill(64, -0.5f);

        filter.process(originalLeft.clone(), originalRight.clone(), 64);

        assertTrue(next.processCalled, "Enabled filter must call next.process()");
        // The simulator modifies buffers in-place; next receives modified data
        assertFalse(Arrays.equals(originalLeft, next.capturedLeft),
            "Enabled filter should modify buffers before passing them to next");
    }

    // ── enabled=true: processInterleaved() passes through to next only ────────

    @Test
    void enabled_processInterleaved_doesNotCallSimulator_onlyDelegatesToNext()
    {
        var next = new TrackingProcessor();
        var filter = new OneBitAcousticSimulatorFilter(true, "pwm", next);

        short[] pcm = { 10000, -10000, 20000, -20000 };
        filter.processInterleaved(pcm, 2, 2);

        assertTrue(next.processInterleavedCalled,
            "processInterleaved() must always delegate to next");
        // PCM buffer should be unchanged because the simulator is not invoked
        assertEquals(10000, pcm[0], "processInterleaved must not modify the PCM buffer");
        assertEquals(-10000, pcm[1]);
    }

    // ── deterministic output via package-private constructor ──────────────────

    @Test
    void packagePrivateConstructor_withSeededSimulator_producesDeterministicOutput()
    {
        var sim1 = new OneBitAcousticSimulator(44100, "pwm");
        var sim2 = new OneBitAcousticSimulator(44100, "pwm");

        var next1 = new TrackingProcessor();
        var next2 = new TrackingProcessor();

        var filter1 = new OneBitAcousticSimulatorFilter(true, sim1, next1);
        var filter2 = new OneBitAcousticSimulatorFilter(true, sim2, next2);

        float[] input1 = fill(128, 0.3f);
        float[] input2 = fill(128, 0.3f);

        filter1.process(input1, fill(128, -0.3f), 128);
        filter2.process(input2, fill(128, -0.3f), 128);

        // Both filters use PWM (deterministic), so outputs must match
        assertArrayEquals(next1.capturedLeft, next2.capturedLeft, 0.0f,
            "Identical PWM simulators must produce identical output");
    }

    @Test
    void packagePrivateConstructor_withDsdSeededSimulator_producesDeterministicOutput()
    {
        var sim1 = new OneBitAcousticSimulator(44100, "dsd", new Random(42));
        var sim2 = new OneBitAcousticSimulator(44100, "dsd", new Random(42));

        var next1 = new TrackingProcessor();
        var next2 = new TrackingProcessor();

        var filter1 = new OneBitAcousticSimulatorFilter(true, sim1, next1);
        var filter2 = new OneBitAcousticSimulatorFilter(true, sim2, next2);

        float[] leftInput = fill(64, 0.4f);
        float[] rightInput = fill(64, -0.4f);

        filter1.process(leftInput.clone(), rightInput.clone(), 64);
        filter2.process(leftInput.clone(), rightInput.clone(), 64);

        assertArrayEquals(next1.capturedLeft, next2.capturedLeft, 0.0f,
            "DSD simulators with same seed must produce identical output");
    }

    // ── reset() calls both simulator.reset() and next.reset() ────────────────

    @Test
    void reset_callsBothSimulatorResetAndNextReset()
    {
        var next = new TrackingProcessor();

        // Use the package-private constructor so we can verify the simulator was reset
        // by comparing output before and after reset
        var sim = new OneBitAcousticSimulator(44100, "pwm");
        var filter = new OneBitAcousticSimulatorFilter(true, sim, next);

        // Process some data to dirty the simulator state
        filter.process(fill(128, 0.8f), fill(128, 0.8f), 128);

        // Reset
        filter.reset();

        assertTrue(next.resetCalled, "filter.reset() must call next.reset()");

        // After reset, simulator state should match a fresh instance
        var freshSim = new OneBitAcousticSimulator(44100, "pwm");
        var captureNext = new TrackingProcessor();
        var freshFilter = new OneBitAcousticSimulatorFilter(true, freshSim, captureNext);

        var captureAfterReset = new TrackingProcessor();
        var filterAfterReset = new OneBitAcousticSimulatorFilter(true, sim, captureAfterReset);

        float[] inputForFresh = fill(32, 0.4f);
        float[] inputAfterReset = fill(32, 0.4f);

        freshFilter.process(inputForFresh, fill(32, 0.4f), 32);
        filterAfterReset.process(inputAfterReset, fill(32, 0.4f), 32);

        assertArrayEquals(captureNext.capturedLeft, captureAfterReset.capturedLeft, 0.0f,
            "Simulator should be fully reset to initial state");
    }

    // ── null oneBitMode defaults to pwm ──────────────────────────────────────

    @Test
    void publicConstructor_nullOneBitMode_defaultsToPwm_noNpe()
    {
        var next = new TrackingProcessor();

        assertDoesNotThrow(() ->
        {
            var filter = new OneBitAcousticSimulatorFilter(true, (String) null, next);
            filter.process(fill(4, 0.5f), fill(4, 0.5f), 4);
        }, "null oneBitMode must not cause NPE — should default to pwm");

        assertTrue(next.processCalled, "next.process() must be called");
    }

    @Test
    void publicConstructor_nullOneBitMode_producesSameOutputAsPwmMode()
    {
        var nextNull = new TrackingProcessor();
        var nextPwm = new TrackingProcessor();

        var filterNull = new OneBitAcousticSimulatorFilter(true, (String) null, nextNull);
        var filterPwm = new OneBitAcousticSimulatorFilter(true, "pwm", nextPwm);

        float[] inputNull = fill(64, 0.35f);
        float[] inputPwm = fill(64, 0.35f);

        filterNull.process(inputNull, fill(64, -0.35f), 64);
        filterPwm.process(inputPwm, fill(64, -0.35f), 64);

        assertArrayEquals(nextPwm.capturedLeft, nextNull.capturedLeft, 0.0f,
            "null mode should produce the same output as explicit 'pwm'");
    }

    // ── enabled=false: input arrays left unchanged ────────────────────────────

    @Test
    void disabled_process_inputArraysLeftUnchanged()
    {
        var next = new TrackingProcessor();
        var filter = new OneBitAcousticSimulatorFilter(false, "pwm", next);

        float[] left = { 0.5f, -0.3f, 0.8f };
        float[] right = { -0.5f, 0.3f, -0.8f };
        float[] leftCopy = left.clone();
        float[] rightCopy = right.clone();

        filter.process(left, right, 3);

        assertArrayEquals(leftCopy, left, 0.0f,
            "Disabled filter must not modify input arrays");
        assertArrayEquals(rightCopy, right, 0.0f,
            "Disabled filter must not modify input arrays");
    }

    // ── zero-frame boundary ───────────────────────────────────────────────────

    @Test
    void enabled_process_zeroFrames_doesNotThrow()
    {
        var next = new TrackingProcessor();
        var filter = new OneBitAcousticSimulatorFilter(true, "pwm", next);

        assertDoesNotThrow(() -> filter.process(new float[0], new float[0], 0));
        assertTrue(next.processCalled, "next.process() should still be called for zero frames");
    }

    @Test
    void disabled_process_zeroFrames_doesNotThrow()
    {
        var next = new TrackingProcessor();
        var filter = new OneBitAcousticSimulatorFilter(false, "pwm", next);

        assertDoesNotThrow(() -> filter.process(new float[0], new float[0], 0));
    }
}

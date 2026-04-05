/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.engine;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MidiClockTest
{
    // ── SYSTEM singleton tests ────────────────────────────────────────────────

    @Test
    void system_nanoTime_returnsPositiveValue()
    {
        long t = MidiClock.SYSTEM.nanoTime();
        assertTrue(t > 0, "System nanoTime() should return a positive value");
    }

    @Test
    void system_nanoTime_isMonotonicallyNonDecreasing()
    {
        long t1 = MidiClock.SYSTEM.nanoTime();
        long t2 = MidiClock.SYSTEM.nanoTime();
        assertTrue(t2 >= t1, "Successive nanoTime() calls should be non-decreasing");
    }

    @Test
    void system_nanoTime_advancesOverTime() throws InterruptedException
    {
        long before = MidiClock.SYSTEM.nanoTime();
        Thread.sleep(5);
        long after = MidiClock.SYSTEM.nanoTime();
        assertTrue(after > before, "nanoTime() should advance after actual sleep");
    }

    @Test
    void system_sleepMillis_doesNotThrowForZero() throws InterruptedException
    {
        assertDoesNotThrow(() -> MidiClock.SYSTEM.sleepMillis(0));
    }

    @Test
    void system_sleepMillis_sleepsApproximatelyCorrectDuration() throws InterruptedException
    {
        long before = System.nanoTime();
        MidiClock.SYSTEM.sleepMillis(20);
        long after = System.nanoTime();
        long elapsedMs = (after - before) / 1_000_000L;

        // Allow generous slack: OS scheduling jitter can be significant
        assertTrue(elapsedMs >= 15, "sleepMillis(20) should sleep at least ~15 ms, got " + elapsedMs);
    }

    @Test
    void system_onSpinWait_doesNotThrow()
    {
        assertDoesNotThrow(() -> MidiClock.SYSTEM.onSpinWait());
    }

    @Test
    void system_onSpinWait_canBeCalledRepeatedly()
    {
        assertDoesNotThrow(() ->
        {
            for (int i = 0; i < 1000; i++)
            {
                MidiClock.SYSTEM.onSpinWait();
            }
        });
    }

    // ── test double / stub usage ──────────────────────────────────────────────

    @Test
    void stub_nanoTime_returnsConfiguredValue()
    {
        MidiClock stub = new MidiClock()
        {
            @Override
            public long nanoTime()
            {
                return 123_456_789L;
            }

            @Override
            public void sleepMillis(long ms) throws InterruptedException {}

            @Override
            public void onSpinWait() {}
        };

        assertEquals(123_456_789L, stub.nanoTime());
    }

    @Test
    void stub_sleepMillis_canSimulateTimeAdvance() throws InterruptedException
    {
        long[] simulatedNanos = { 0L };
        MidiClock stub = new MidiClock()
        {
            @Override
            public long nanoTime()
            {
                return simulatedNanos[0];
            }

            @Override
            public void sleepMillis(long ms) throws InterruptedException
            {
                simulatedNanos[0] += ms * 1_000_000L;
            }

            @Override
            public void onSpinWait() {}
        };

        assertEquals(0L, stub.nanoTime());
        stub.sleepMillis(100);
        assertEquals(100_000_000L, stub.nanoTime());
    }

    @Test
    void stub_sleepMillis_interruptedExceptionCanBeThrown()
    {
        MidiClock stub = new MidiClock()
        {
            @Override
            public long nanoTime()
            {
                return 0L;
            }

            @Override
            public void sleepMillis(long ms) throws InterruptedException
            {
                throw new InterruptedException("simulated interrupt");
            }

            @Override
            public void onSpinWait() {}
        };

        assertThrows(InterruptedException.class, () -> stub.sleepMillis(10),
                "Stub should propagate InterruptedException as declared");
    }

    @Test
    void stub_onSpinWait_canTrackCallCount()
    {
        int[] spinCount = { 0 };
        MidiClock stub = new MidiClock()
        {
            @Override
            public long nanoTime() { return 0L; }

            @Override
            public void sleepMillis(long ms) throws InterruptedException {}

            @Override
            public void onSpinWait()
            {
                spinCount[0]++;
            }
        };

        for (int i = 0; i < 5; i++) stub.onSpinWait();
        assertEquals(5, spinCount[0]);
    }

    // ── interrupt handling ────────────────────────────────────────────────────

    @Test
    void system_sleepMillis_throwsWhenInterrupted() throws InterruptedException
    {
        boolean[] interrupted = { false };
        Thread t = new Thread(() ->
        {
            try
            {
                MidiClock.SYSTEM.sleepMillis(5000);
            }
            catch (InterruptedException e)
            {
                interrupted[0] = true;
            }
        });
        t.start();
        Thread.sleep(20); // give it time to enter sleep
        t.interrupt();
        t.join(200);
        assertTrue(interrupted[0], "sleepMillis() should throw InterruptedException on interrupt");
    }
}

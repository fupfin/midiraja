/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.engine;

/**
 * Abstraction over wall-clock timing primitives used by {@link PlaybackEngine}.
 * Replace with a test double to drive timing-sensitive logic without real sleeps.
 */
interface MidiClock
{
    /** Returns the current value of the JVM's high-resolution timer, in nanoseconds. */
    long nanoTime();

    /**
     * Causes the calling thread to sleep for at least {@code ms} milliseconds.
     *
     * @throws InterruptedException
     *             if the thread is interrupted while sleeping
     */
    void sleepMillis(long ms) throws InterruptedException;

    /** Hints to the JVM that the calling thread is in a spin-wait loop. */
    void onSpinWait();

    /** Production implementation backed by {@link System#nanoTime()} and {@link Thread#sleep}. */
    MidiClock SYSTEM = new MidiClock()
    {
        @Override
        public long nanoTime()
        {
            return System.nanoTime();
        }

        @Override
        public void sleepMillis(long ms) throws InterruptedException
        {
            Thread.sleep(ms);
        }

        @Override
        public void onSpinWait()
        {
            Thread.onSpinWait();
        }
    };
}

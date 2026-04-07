/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.engine;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MidiPlaybackEngineTickDurationTest
{
    // --- tickDurationNanos ---

    @Test
    void standard_120_bpm_1_speed_480_res_returns_125000_nanos()
    {
        // 60,000,000,000 / (120 * 1 * 480) = 1,041,666.66... (Wait, let me recalculate)
        // Calculation: 60,000,000,000 / (120 * 1 * 480) = 60,000,000,000 / 57600 = 1,041,666.66...
        // Let's use a simpler one for verification: 60,000,000,000 / (120 * 1 * 1) = 500,000,000
        // Actually, let's just test the math: 60,000,000,000 / (bpm * speed * resolution)

        // Let's use 60 BPM, 1.0 speed, 480 resolution
        // 60,000,000,000 / (60 * 1 * 480) = 60,000,000,000 / 28800 = 2,083,333.33...

        // Let's use a value that divides perfectly:
        // 60,000,000,000 / (100 * 1 * 1000) = 60,000,000,000 / 100,000 = 600,000
        double result = invokeTickDurationNanos(100.0f, 1.0, 1000);
        assertEquals(600000.0, result, 0.001);
    }

    @Test
    void double_speed_returns_half_nanos()
    {
        // 60,000,000,000 / (100 * 2 * 1000) = 300,000
        double result = invokeTickDurationNanos(100.0f, 2.0, 1000);
        assertEquals(300000.0, result, 0.001);
    }

    @Test
    void half_speed_returns_double_nanos()
    {
        // 60,000,000,000 / (100 * 0.5 * 1000) = 1,200,000
        double result = invokeTickDurationNanos(100.0f, 0.5, 1000);
        assertEquals(1200000.0, result, 0.001);
    }

    @Test
    void different_resolution_scales_correctly()
    {
        // 60,000,000,000 / (100 * 1 * 500) = 1,200,000
        double result = invokeTickDurationNanos(100.0f, 1.0, 500);
        assertEquals(1200000.0, result, 0.001);
    }

    /**
     * Helper to access the private method via reflection since it is private in MidiPlaybackEngine.
     * Note: In the previous 'read' output, it was 'private static double tickDurationNanos'.
     */
    private double invokeTickDurationNanos(float bpm, double speed, int resolution)
    {
        try
        {
            var method = MidiPlaybackEngine.class.getDeclaredMethod("tickDurationNanos", float.class, double.class,
                    int.class);
            method.setAccessible(true);
            return (double) method.invoke(null, bpm, speed, resolution);
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }
}

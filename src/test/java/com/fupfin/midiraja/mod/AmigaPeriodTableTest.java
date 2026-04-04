/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.mod;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class AmigaPeriodTableTest
{
    @Test
    void period428_isC4()
    {
        // ProTracker reference: period 428 = C-2 = middle C = MIDI 60
        int note = AmigaPeriodTable.periodToMidiNote(428);
        assertEquals(60, note, "Period 428 should map to C4 (MIDI 60)");
    }

    @Test
    void period254_isA4()
    {
        // ProTracker A-2 = period 254 → A4 = MIDI 69
        int note = AmigaPeriodTable.periodToMidiNote(254);
        assertEquals(69, note, "Period 254 should map to A4 (MIDI 69)");
    }

    @Test
    void period214_isC5()
    {
        // Half the period = one octave up: period 214 = C-3 = MIDI 72
        int note = AmigaPeriodTable.periodToMidiNote(214);
        assertEquals(72, note, "Period 214 should map to C5 (MIDI 72)");
    }

    @Test
    void period856_isC3()
    {
        // Double the period = one octave down: period 856 = C-1 = MIDI 48
        int note = AmigaPeriodTable.periodToMidiNote(856);
        assertEquals(48, note, "Period 856 should map to C3 (MIDI 48)");
    }

    @Test
    void period0_returnsMinusOne()
    {
        assertEquals(-1, AmigaPeriodTable.periodToMidiNote(0), "Period 0 = no note");
    }

    @Test
    void negativePeriod_returnsMinusOne()
    {
        assertEquals(-1, AmigaPeriodTable.periodToMidiNote(-1));
    }

    @Test
    void veryHighPeriod_clampsTo0()
    {
        // Very large period = very low note, clamped to MIDI 0
        int note = AmigaPeriodTable.periodToMidiNote(100000);
        assertEquals(0, note, "Very large period should clamp to MIDI 0");
    }
}

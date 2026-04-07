/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.format.mod;

/**
 * Converts Amiga period values to MIDI note numbers.
 *
 * <p>
 * In ProTracker, period 428 corresponds to C-2 (middle C, MIDI note 60).
 * Pitch scales inversely with period: halving the period raises the note by one octave.
 *
 * <p>
 * The conversion formula is:
 *
 * <pre>
 *   midiNote = round(60 + 12 × log2(REFERENCE_PERIOD / period))
 * </pre>
 *
 * where REFERENCE_PERIOD = 428 (the period for C4 / MIDI 60 at finetune 0).
 */
public final class AmigaPeriodTable
{
    /** ProTracker period value for C4 (middle C, MIDI note 60) at finetune 0. */
    private static final double REFERENCE_PERIOD = 428.0;

    private AmigaPeriodTable()
    {
    }

    /**
     * Converts an Amiga period value to the nearest MIDI note number.
     *
     * @param period
     *            Amiga period value (must be > 0)
     * @return MIDI note 0–127, clamped to valid range; -1 if period is 0 or negative
     */
    public static int periodToMidiNote(int period)
    {
        if (period <= 0)
            return -1;
        double note = 60.0 + 12.0 * Math.log(REFERENCE_PERIOD / period) / Math.log(2.0);
        return Math.clamp(Math.round(note), 0, 127);
    }
}

/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.midi.psg;

/**
 * Pre-baked 32-byte SCC waveforms and GM program-number → waveform mapping, shared between
 * the live {@link SccChip} renderer and the VGM-export {@code SccHandler}.
 *
 * <p>
 * All waveforms use 8-bit signed values (-128..127). The mapping groups GM programs by timbral
 * family (groups of 8) and assigns the closest SCC equivalent.
 */
public final class SccWaveforms
{
    public static final byte[] PIANO = {
            127, 48, 15, -9, -29, -47, -62, -75, -86, -96, -104, -111, -117, -121, -124,
            -126, -127, 112, 96, 80, 64, 48, 32, 16, 0, -15, -31, -47, -63, -79, -95, -111
    };

    public static final byte[] STRINGS = {
            0, 35, 65, 85, 95, 95, 88, 78, 69, 64, 61, 60, 57, 50, 38, 20,
            0, -20, -38, -50, -57, -60, -61, -64, -69, -78, -88, -95, -95, -85, -65, -35
    };

    public static final byte[] BRASS = {
            -128, -116, -104, -92, -80, -68, -56, -44, -32, -20, -8, 3, 15, 27, 39, 51,
            63, 75, 87, 99, 111, 123, -120, -108, -96, -84, -72, -60, -48, -36, -24, -12
    };

    public static final byte[] BASS = {
            127, 127, 127, 127, -128, -128, -128, -128, -128, -128, -128, -128, -128, -128,
            -128, -128, -128, -128, -128, -128, -128, -128, -128, -128, -128, -128, -128, -128,
            -128, -128, -128, -128
    };

    public static final byte[] SQUARE = new byte[32];

    static
    {
        for (int i = 0; i < 32; i++)
            SQUARE[i] = (byte) (i < 16 ? 127 : -128);
    }

    private SccWaveforms()
    {
    }

    /**
     * Returns the waveform for the given GM program number (0–127).
     *
     * @param program
     *            GM program number
     * @return a 32-byte waveform array (must not be modified by the caller)
     */
    public static byte[] forProgram(int program)
    {
        return switch (program / 8)
        {
            case 0, 1, 3 -> PIANO;   // Piano, Chromatic Perc, Guitar
            case 4 -> BASS;           // Bass
            case 5, 11, 12 -> STRINGS; // Strings, Synth Pad, Synth FX
            case 7, 10 -> BRASS;      // Brass, Synth Lead
            default -> SQUARE;
        };
    }
}

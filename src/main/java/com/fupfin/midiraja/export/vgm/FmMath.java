/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.export.vgm;

/**
 * Shared FM math helpers for chip handlers.
 */
final class FmMath
{
    record PitchRegs(int block, int fnum) {}

    private static final double C1 = 11.541560327111707;
    private static final double C2 = 160.1379199767093;
    private static final long MIN_VOLUME = 1_108_075L; // 8725 * 127

    private FmMath()
    {
    }

    static int scaleTl(int tl, int velocity, int maxTl)
    {
        long vol = (long) velocity * 127L * 127L * 127L;
        int volume;
        if (vol > MIN_VOLUME)
        {
            double lv = Math.log((double) vol);
            volume = Math.clamp((int) (lv * C1 - C2) * 2, 0, 127);
        }
        else
        {
            volume = 0;
        }
        return Math.clamp(maxTl - volume * (maxTl - (tl & maxTl)) / 127, 0, maxTl);
    }

    static PitchRegs opnPitchRegs(double note, int fmClock)
    {
        double freq = 440.0 * Math.pow(2.0, (note - 69) / 12.0);
        int noteInt = (int) note;
        int block = Math.clamp(noteInt / 12 - 1, 0, 7);
        int fnum = (int) Math.round(freq * 144.0 * (1 << (21 - block)) / fmClock);
        return new PitchRegs(block, Math.clamp(fnum, 0, 0x7FF));
    }
}

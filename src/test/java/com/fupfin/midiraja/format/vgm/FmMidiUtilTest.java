/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.format.vgm;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class FmMidiUtilTest
{

    @Test
    void computeVelocity_tlZero_maxVelocity()
    {
        int[][] tl = { { 0, 0, 0, 0 } };
        int[] algorithm = { 7 }; // all 4 carriers
        int[] feedback = { 0 };

        int velocity = FmMidiUtil.computeVelocity(tl, algorithm, feedback, 0);

        assertEquals(127, velocity, "TL=0 across all carriers → velocity=127");
    }

    @Test
    void computeVelocity_tl127_minVelocity()
    {
        int[][] tl = { { 127, 127, 127, 127 } };
        int[] algorithm = { 7 }; // all 4 carriers
        int[] feedback = { 0 };

        int velocity = FmMidiUtil.computeVelocity(tl, algorithm, feedback, 0);

        assertEquals(1, velocity, "TL=127 → velocity=1 (clamped min)");
    }

    @Test
    void carrierOps_allAlgorithms()
    {
        // alg 0-3: 1 carrier (op 3)
        for (int alg = 0; alg <= 3; alg++)
        {
            assertEquals(1, FmMidiUtil.carrierOps(alg).length, "alg " + alg + " → 1 carrier");
        }
        // alg 4: 2 carriers
        assertEquals(2, FmMidiUtil.carrierOps(4).length, "alg 4 → 2 carriers");
        // alg 5-6: 3 carriers
        assertEquals(3, FmMidiUtil.carrierOps(5).length, "alg 5 → 3 carriers");
        assertEquals(3, FmMidiUtil.carrierOps(6).length, "alg 6 → 3 carriers");
        // alg 7: 4 carriers
        assertEquals(4, FmMidiUtil.carrierOps(7).length, "alg 7 → 4 carriers");
    }

    @Test
    void selectProgram_merge_cases2and3()
    {
        int programAlg2 = FmMidiUtil.selectProgram(2, 0, 50, false);
        int programAlg3 = FmMidiUtil.selectProgram(3, 0, 50, false);

        assertEquals(programAlg2, programAlg3, "alg 2 and 3 return the same program");
    }

    @Test
    void modulatorOps_alg4_returnsTwoOps()
    {
        assertArrayEquals(new int[] { 0, 1 }, FmMidiUtil.modulatorOps(4));
    }

    @Test
    void modulatorOps_alg5and6_returnOneOp()
    {
        assertArrayEquals(new int[] { 0 }, FmMidiUtil.modulatorOps(5));
        assertArrayEquals(new int[] { 0 }, FmMidiUtil.modulatorOps(6));
    }

    @Test
    void modulatorOps_alg7_returnsEmpty()
    {
        assertArrayEquals(new int[] {}, FmMidiUtil.modulatorOps(7));
    }

    @Test
    void modulatorOps_alg0to3_returnsThreeOps()
    {
        for (int alg = 0; alg <= 3; alg++)
            assertEquals(3, FmMidiUtil.modulatorOps(alg).length, "alg " + alg + " → 3 modulators");
    }

    @Test
    void avgModulatorTl_alg7_noModulators_returns127()
    {
        int[][] tl = { { 10, 20, 30, 40 } };
        int[] algorithm = { 7 };

        assertEquals(127, FmMidiUtil.avgModulatorTl(tl, algorithm, 0));
    }

    @Test
    void avgModulatorTl_alg0_returnsAverageOfFirstThreeOps()
    {
        // alg 0: modulatorOps = {0, 1, 2}
        int[][] tl = { { 30, 60, 90, 10 } };
        int[] algorithm = { 0 };

        assertEquals(60, FmMidiUtil.avgModulatorTl(tl, algorithm, 0));
    }
}

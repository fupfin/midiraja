/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.export.vgm;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class WopnBankReaderTest
{
    private static final String WOPN_PATH = "ext/libOPNMIDI/fm_banks/gm.wopn";

    @Test
    void load_parsesValidWopnFile() throws Exception
    {
        WopnBankReader reader = WopnBankReader.load(Path.of(WOPN_PATH));
        assertNotNull(reader);
    }

    @Test
    void melodicPatch_program0HasFourOperators() throws Exception
    {
        WopnBankReader reader = WopnBankReader.load(Path.of(WOPN_PATH));
        WopnBankReader.Patch patch = reader.melodicPatch(0);
        assertNotNull(patch);
        assertNotNull(patch.operators());
        assertEquals(4, patch.operators().length, "OPN2 patches must have 4 operators");
    }

    @Test
    void melodicPatch_program0HasValidFbalg() throws Exception
    {
        WopnBankReader reader = WopnBankReader.load(Path.of(WOPN_PATH));
        WopnBankReader.Patch patch = reader.melodicPatch(0);
        int alg = patch.fbalg() & 0x07;
        int fb = (patch.fbalg() >> 3) & 0x07;
        assertTrue(alg >= 0 && alg <= 7, "Algorithm must be 0-7, got " + alg);
        assertTrue(fb >= 0 && fb <= 7, "Feedback must be 0-7, got " + fb);
    }

    @Test
    void melodicPatch_allProgramsNonNull() throws Exception
    {
        WopnBankReader reader = WopnBankReader.load(Path.of(WOPN_PATH));
        for (int i = 0; i < 128; i++)
        {
            WopnBankReader.Patch patch = reader.melodicPatch(i);
            assertNotNull(patch, "Program " + i + " should not be null");
            assertNotNull(patch.operators(), "Program " + i + " operators should not be null");
            assertEquals(4, patch.operators().length, "Program " + i + " must have 4 operators");
        }
    }

    @Test
    void melodicPatch_operatorValuesInValidRange() throws Exception
    {
        WopnBankReader reader = WopnBankReader.load(Path.of(WOPN_PATH));
        WopnBankReader.Patch patch = reader.melodicPatch(0);
        for (int l = 0; l < 4; l++)
        {
            WopnBankReader.Operator op = patch.operators()[l];
            assertTrue(op.level() >= 0 && op.level() <= 127, "TL must be 0-127 for op " + l);
            assertTrue(op.dtfm() >= 0 && op.dtfm() <= 255, "DT/MULT must be 0-255 for op " + l);
        }
    }

    @Test
    void melodicPatch_outOfRangeThrows() throws Exception
    {
        WopnBankReader reader = WopnBankReader.load(Path.of(WOPN_PATH));
        assertThrows(IllegalArgumentException.class, () -> reader.melodicPatch(-1));
        assertThrows(IllegalArgumentException.class, () -> reader.melodicPatch(128));
    }

    @Test
    void percussionPatch_outOfRangeThrows() throws Exception
    {
        WopnBankReader reader = WopnBankReader.load(Path.of(WOPN_PATH));
        assertThrows(IllegalArgumentException.class, () -> reader.percussionPatch(-1));
        assertThrows(IllegalArgumentException.class, () -> reader.percussionPatch(128));
    }
}

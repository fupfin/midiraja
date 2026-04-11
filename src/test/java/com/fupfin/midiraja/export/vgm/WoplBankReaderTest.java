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

class WoplBankReaderTest
{
    private static final String WOPL_PATH = "ext/libADLMIDI/fm_banks/wopl_files/GM-By-J.A.Nguyen-and-Wohlstand.wopl";

    @Test
    void load_parsesValidWoplFile() throws Exception
    {
        Path path = Path.of(WOPL_PATH);
        WoplBankReader reader = WoplBankReader.load(path);
        assertNotNull(reader);
    }

    @Test
    void melodicPatch_program0HasValidOperators() throws Exception
    {
        WoplBankReader reader = WoplBankReader.load(Path.of(WOPL_PATH));
        WoplBankReader.Patch patch = reader.melodicPatch(0);
        assertNotNull(patch);
        assertNotNull(patch.modulator());
        assertNotNull(patch.carrier());
    }

    @Test
    void melodicPatch_program40HasValidOperators() throws Exception
    {
        WoplBankReader reader = WoplBankReader.load(Path.of(WOPL_PATH));
        WoplBankReader.Patch patch = reader.melodicPatch(40);
        assertNotNull(patch);
        assertNotNull(patch.modulator());
        assertNotNull(patch.carrier());
    }

    @Test
    void melodicPatch_allProgramsNonNull() throws Exception
    {
        WoplBankReader reader = WoplBankReader.load(Path.of(WOPL_PATH));
        for (int i = 0; i < 128; i++)
        {
            WoplBankReader.Patch patch = reader.melodicPatch(i);
            assertNotNull(patch, "Program " + i + " should not be null");
            assertNotNull(patch.modulator(), "Program " + i + " modulator should not be null");
            assertNotNull(patch.carrier(), "Program " + i + " carrier should not be null");
        }
    }

    @Test
    void percussionPatch_bassDrumExists() throws Exception
    {
        WoplBankReader reader = WoplBankReader.load(Path.of(WOPL_PATH));
        WoplBankReader.Patch patch = reader.percussionPatch(36); // MIDI note 36 = Bass Drum
        assertNotNull(patch);
        assertNotNull(patch.modulator());
        assertNotNull(patch.carrier());
    }

    @Test
    void melodicPatch_outOfRangeThrows() throws Exception
    {
        WoplBankReader reader = WoplBankReader.load(Path.of(WOPL_PATH));
        assertThrows(IllegalArgumentException.class, () -> reader.melodicPatch(-1));
        assertThrows(IllegalArgumentException.class, () -> reader.melodicPatch(128));
    }

    @Test
    void percussionPatch_outOfRangeThrows() throws Exception
    {
        WoplBankReader reader = WoplBankReader.load(Path.of(WOPL_PATH));
        assertThrows(IllegalArgumentException.class, () -> reader.percussionPatch(-1));
        assertThrows(IllegalArgumentException.class, () -> reader.percussionPatch(128));
    }

    @Test
    void patch_operatorValuesInValidRange() throws Exception
    {
        WoplBankReader reader = WoplBankReader.load(Path.of(WOPL_PATH));
        WoplBankReader.Patch patch = reader.melodicPatch(0);

        // All OPL3 register values should be 0-255
        assertTrue(patch.modulator().avekf() >= 0 && patch.modulator().avekf() <= 255);
        assertTrue(patch.modulator().ksltl() >= 0 && patch.modulator().ksltl() <= 255);
        assertTrue(patch.modulator().atdec() >= 0 && patch.modulator().atdec() <= 255);
        assertTrue(patch.modulator().susrel() >= 0 && patch.modulator().susrel() <= 255);
        assertTrue(patch.modulator().wave() >= 0 && patch.modulator().wave() <= 255);

        assertTrue(patch.carrier().avekf() >= 0 && patch.carrier().avekf() <= 255);
        assertTrue(patch.carrier().ksltl() >= 0 && patch.carrier().ksltl() <= 255);
        assertTrue(patch.carrier().atdec() >= 0 && patch.carrier().atdec() <= 255);
        assertTrue(patch.carrier().susrel() >= 0 && patch.carrier().susrel() <= 255);
        assertTrue(patch.carrier().wave() >= 0 && patch.carrier().wave() <= 255);

        assertTrue(patch.fbConn() >= 0 && patch.fbConn() <= 255);
    }

    @Test
    void patch_fieldsAreNotNull() throws Exception
    {
        WoplBankReader reader = WoplBankReader.load(Path.of(WOPL_PATH));
        WoplBankReader.Patch patch = reader.melodicPatch(0);
        assertNotNull(patch.name());
        assertNotNull(patch.modulator());
        assertNotNull(patch.carrier());
    }
}

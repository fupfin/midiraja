/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.format.mod;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ModInstrumentMapperTest
{
    private static ModInstrument instr(String name)
    {
        return new ModInstrument(name, 0, 0, 64, 0, 0);
    }

    @Test
    void bass_mapsToElectricBass()
    {
        assertEquals(33, ModInstrumentMapper.mapToGmProgram(instr("bass guitar")));
    }

    @Test
    void kick_mapsToPrecussion()
    {
        assertEquals(ModInstrumentMapper.PERCUSSION, ModInstrumentMapper.mapToGmProgram(instr("kick drum")));
    }

    @Test
    void snare_mapsToPercussion()
    {
        assertEquals(ModInstrumentMapper.PERCUSSION, ModInstrumentMapper.mapToGmProgram(instr("snare")));
    }

    @Test
    void piano_mapsToAcousticGrand()
    {
        assertEquals(0, ModInstrumentMapper.mapToGmProgram(instr("piano")));
    }

    @Test
    void strings_mapsToStringEnsemble()
    {
        assertEquals(48, ModInstrumentMapper.mapToGmProgram(instr("strings")));
    }

    @Test
    void unknown_returnsUnmatched()
    {
        assertEquals(ModInstrumentMapper.UNMATCHED, ModInstrumentMapper.mapToGmProgram(instr("sample 1")));
    }

    @Test
    void emptyName_returnsUnmatched()
    {
        assertEquals(ModInstrumentMapper.UNMATCHED, ModInstrumentMapper.mapToGmProgram(instr("")));
    }

    @Test
    void caseInsensitive()
    {
        assertEquals(33, ModInstrumentMapper.mapToGmProgram(instr("BASS")));
    }
}

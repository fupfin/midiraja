/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.export.vgm;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class SccHandlerTest
{
    // ── supportsRhythm ────────────────────────────────────────────────────────

    @Test
    void supportsRhythm_returnsFalse()
    {
        assertFalse(new SccHandler().supportsRhythm());
    }

    // ── slotCount ─────────────────────────────────────────────────────────────

    @Test
    void scc_hasFiveMelodicSlots()
    {
        assertEquals(5, new SccHandler().slotCount());
    }

    @Test
    void scci_hasFiveMelodicSlots()
    {
        assertEquals(5, new SccHandler(true).slotCount());
    }

    // ── initSilence waveform init ─────────────────────────────────────────────

    /** SCC (K051649): only channels 0-3 are written; ch4 inherits via shared waveram. */
    @Test
    void scc_initSilence_writesFourChannels() throws Exception
    {
        var out = new ByteArrayOutputStream();
        try (var w = new VgmWriter(out, List.of(ChipType.SCC, ChipType.AY8910)))
        {
            new SccHandler(false).initSilence(w);
        }
        byte[] vgm = out.toByteArray();
        long ch4Writes = sccWaveWrites(vgm).stream()
                .filter(reg -> reg >= 0x80 && reg < 0xA0)
                .count();
        assertEquals(0, ch4Writes, "SCC: ch4 waveform must not be written explicitly");
    }

    /** SCC-I (K052539): all five channels must be written (independent waveform RAM). */
    @Test
    void scci_initSilence_writesAllFiveChannels() throws Exception
    {
        var out = new ByteArrayOutputStream();
        try (var w = new VgmWriter(out, List.of(ChipType.SCCI, ChipType.AY8910)))
        {
            new SccHandler(true).initSilence(w);
        }
        byte[] vgm = out.toByteArray();
        long ch4Writes = sccWaveWrites(vgm).stream()
                .filter(reg -> reg >= 0x80 && reg < 0xA0)
                .count();
        assertEquals(32, ch4Writes, "SCC-I: all 32 bytes of ch4 waveform must be written");
    }

    /** Returns port-0 reg values from 0xD2 SCC waveform writes (header = 0xC0 bytes). */
    private static List<Integer> sccWaveWrites(byte[] vgm)
    {
        var regs = new ArrayList<Integer>();
        int pos = 0xC0;
        while (pos < vgm.length - 1)
        {
            int cmd = vgm[pos] & 0xFF;
            if (cmd == 0xD2 && pos + 3 < vgm.length)
            {
                if ((vgm[pos + 1] & 0xFF) == 0) // port 0 = waveform
                    regs.add(vgm[pos + 2] & 0xFF);
                pos += 4;
            }
            else if (cmd == 0x66)
                break;
            else
                pos += (cmd == 0x61 ? 3 : 1);
        }
        return regs;
    }
}

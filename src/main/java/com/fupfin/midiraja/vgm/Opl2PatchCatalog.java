/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.vgm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Patch catalog for 2-operator FM chips (OPL2, OPL3).
 *
 * <p>
 * <b>Silent detection:</b> {@code conn=0, fb=0} is a note-cut trick used by many OPL2
 * game drivers — the patch produces near-silence and is used for instant note termination.
 * Also, carrier TL ≥ 45 (band 3) is inaudible on real hardware.
 *
 * <p>
 * <b>Drum detection:</b> Strong modulation ({@code modTL < 10}) with non-zero feedback
 * produces metallic, percussive clicks that serve as drum/percussion effects.
 */
final class Opl2PatchCatalog extends FmPatchCatalog
{

    record PatchEvent(int connection, int feedback, int modTl, int carTl,
            int carAr, int carDr, int note)
    {
    }

    /**
     * Builds a catalog by scanning OPL2/OPL3 events.
     *
     * @param parsed
     *            the parsed VGM data
     * @param chipIds
     *            chip IDs to scan (14=OPL2, 15=OPL3 port0, 16=OPL3 port1)
     * @param effectiveClock
     *            the OPL clock used for note calculation
     */
    static Opl2PatchCatalog build(VgmParseResult parsed, int[] chipIds, long effectiveClock)
    {
        var state = new PortState[2];
        state[0] = new PortState();
        state[1] = new PortState();

        List<PatchEvent> events = new ArrayList<>();

        for (var event : parsed.events())
        {
            int chip = event.chip();
            int portIdx = -1;
            for (int id : chipIds)
            {
                if (chip == id)
                {
                    portIdx = (chip == 16) ? 1 : 0;
                    break;
                }
            }
            if (portIdx < 0)
                continue;

            var ps = state[portIdx];
            int reg = event.rawData()[0] & 0xFF;
            int val = event.rawData()[1] & 0xFF;

            if (reg >= 0x40 && reg <= 0x55)
            {
                int[] decoded = decodeSlot(reg, 0x40);
                if (decoded != null)
                {
                    if (decoded[1] == 1)
                        ps.carrierTl[decoded[0]] = val & 0x3F;
                    else
                        ps.modulatorTl[decoded[0]] = val & 0x3F;
                }
            }
            else if (reg >= 0x60 && reg <= 0x75)
            {
                int[] decoded = decodeSlot(reg, 0x60);
                if (decoded != null && decoded[1] == 1)
                {
                    ps.carrierAr[decoded[0]] = (val >> 4) & 0xF;
                    ps.carrierDr[decoded[0]] = val & 0xF;
                }
            }
            else if (reg >= 0xA0 && reg <= 0xA8)
            {
                ps.fnumLo[reg - 0xA0] = val;
            }
            else if (reg >= 0xB0 && reg <= 0xB8)
            {
                int ch = reg - 0xB0;
                ps.fnumHi[ch] = val & 0x03;
                ps.block[ch] = (val >> 2) & 0x07;
                boolean keyOn = (val & 0x20) != 0;
                if (keyOn && !ps.keyState[ch])
                {
                    int fnum = (ps.fnumHi[ch] << 8) | ps.fnumLo[ch];
                    int note = Ym3812MidiConverter.opl2Note(effectiveClock, fnum, ps.block[ch]);
                    if (note >= 0)
                    {
                        events.add(new PatchEvent(
                                ps.connection[ch], ps.feedback[ch],
                                ps.modulatorTl[ch], ps.carrierTl[ch],
                                ps.carrierAr[ch], ps.carrierDr[ch], note));
                    }
                }
                ps.keyState[ch] = keyOn;
            }
            else if (reg >= 0xC0 && reg <= 0xC8)
            {
                int ch = reg - 0xC0;
                ps.feedback[ch] = (val >> 1) & 0x07;
                ps.connection[ch] = val & 0x01;
            }
        }

        return classify(events);
    }

    private static Opl2PatchCatalog classify(List<PatchEvent> events)
    {
        var catalog = new Opl2PatchCatalog();
        Map<Integer, List<Integer>> sigNotes = new HashMap<>();

        for (var e : events)
        {
            int sig = signature(e.connection(), e.feedback(), e.modTl(), e.carTl(),
                    e.carAr(), e.carDr());
            if (e.note() >= 0)
            {
                sigNotes.computeIfAbsent(sig, k -> new ArrayList<>()).add(e.note());
            }
        }

        for (var entry : sigNotes.entrySet())
        {
            int sig = entry.getKey();
            var notes = entry.getValue();

            int hint = (sig >> 24) & 0xF;
            int fb = (sig >> 20) & 0xF;
            int modTlBand = (sig >> 16) & 0xF;
            int carTlBand = (sig >> 12) & 0xF;

            // conn=0, fb=0: note-cut trick (nearly silent init/reset state)
            // carrier TL band ≥ 3: output attenuated > 41 dB, inaudible
            if ((hint == 0 && fb == 0) || carTlBand >= 3)
            {
                catalog.signatureToProgram.put(sig, SILENT);
                continue;
            }

            // Strong modulation (modTL < 10) → metallic percussive effect → drums
            if (modTlBand == 0)
            {
                catalog.signatureToProgram.put(sig, DRUM_EFFECT);
                continue;
            }

            catalog.signatureToProgram.put(sig, assignGmProgram(sig, notes));
        }

        return catalog;
    }

    private static int @org.jspecify.annotations.Nullable [] decodeSlot(int addr, int base)
    {
        int offset = addr - base;
        int group = offset / 8;
        int slot = offset % 8;
        if (slot >= 6)
            return null;
        int ch = group * 3 + slot % 3;
        if (ch >= 9)
            return null;
        return new int[] { ch, slot >= 3 ? 1 : 0 };
    }

    private static class PortState
    {
        final int[] fnumLo = new int[9];
        final int[] fnumHi = new int[9];
        final int[] block = new int[9];
        final boolean[] keyState = new boolean[9];
        final int[] connection = new int[9];
        final int[] feedback = new int[9];
        final int[] modulatorTl = new int[9];
        final int[] carrierTl = new int[9];
        final int[] carrierAr = new int[9];
        final int[] carrierDr = new int[9];

        PortState()
        {
            Arrays.fill(modulatorTl, 63);
            Arrays.fill(carrierTl, 63);
        }
    }
}

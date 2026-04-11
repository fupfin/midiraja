/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.export.vgm;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class Ym2413PatchMapTest
{
    @Test
    void tableHas128Entries()
    {
        assertEquals(128, Ym2413PatchMap.GM_TO_YM2413.length);
    }

    @Test
    void allEntriesInValidRange()
    {
        for (int i = 0; i < 128; i++)
        {
            int patch = Ym2413PatchMap.GM_TO_YM2413[i];
            assertTrue(patch >= 1 && patch <= 15,
                "Program " + i + " maps to " + patch + " which is outside 1-15");
        }
    }

    @Test
    void lookupOutOfRange_returnsFallback()
    {
        assertEquals(1, Ym2413PatchMap.lookup(-1));
        assertEquals(1, Ym2413PatchMap.lookup(128));
    }

    // ── Key instrument family mappings ────────────────────────────────────────

    @Test
    void pianos_mapToPatch3()
    {
        // GM 0-5: Grand, Bright, E-Grand, Honky-tonk, Rhodes, Chorused
        for (int prog = 0; prog <= 5; prog++)
            assertEquals(3, Ym2413PatchMap.lookup(prog), "Piano prog " + prog + " should be patch 3");
    }

    @Test
    void harpsichordAndClavinet_mapToPatch11()
    {
        assertEquals(11, Ym2413PatchMap.lookup(6),  "Harpsichord");
        assertEquals(11, Ym2413PatchMap.lookup(7),  "Clavinet");
    }

    @Test
    void vibraphone_mapToPatch12()
    {
        assertEquals(12, Ym2413PatchMap.lookup(11), "Vibraphone");
    }

    @Test
    void organs_mapToPatch8()
    {
        for (int prog = 16; prog <= 23; prog++)
            assertEquals(8, Ym2413PatchMap.lookup(prog), "Organ prog " + prog + " should be patch 8");
    }

    @Test
    void acousticGuitars_mapToPatch2()
    {
        assertEquals(2, Ym2413PatchMap.lookup(24), "Nylon Guitar");
        assertEquals(2, Ym2413PatchMap.lookup(25), "Steel Guitar");
    }

    @Test
    void electricGuitars_mapToPatch15()
    {
        assertEquals(15, Ym2413PatchMap.lookup(26), "Jazz Guitar");
        assertEquals(15, Ym2413PatchMap.lookup(27), "Clean Electric");
        assertEquals(15, Ym2413PatchMap.lookup(29), "Overdrive");
    }

    @Test
    void acousticBass_mapToPatch14()
    {
        assertEquals(14, Ym2413PatchMap.lookup(32), "Acoustic Bass");
        assertEquals(14, Ym2413PatchMap.lookup(35), "Fretless Bass");
    }

    @Test
    void synthBass_mapToPatch13()
    {
        assertEquals(13, Ym2413PatchMap.lookup(38), "Synth Bass 1");
        assertEquals(13, Ym2413PatchMap.lookup(39), "Synth Bass 2");
    }

    @Test
    void strings_mapToPatch1()
    {
        assertEquals(1, Ym2413PatchMap.lookup(40), "Violin");
        assertEquals(1, Ym2413PatchMap.lookup(41), "Viola");
        assertEquals(1, Ym2413PatchMap.lookup(42), "Cello");
        assertEquals(1, Ym2413PatchMap.lookup(48), "String Ensemble 1");
    }

    @Test
    void brass_mapToTrumpetOrHorn()
    {
        assertEquals(7, Ym2413PatchMap.lookup(56), "Trumpet");
        assertEquals(7, Ym2413PatchMap.lookup(57), "Trombone");
        assertEquals(9, Ym2413PatchMap.lookup(58), "Tuba → Horn");
        assertEquals(9, Ym2413PatchMap.lookup(60), "French Horn");
    }

    @Test
    void reeds_mapToOboaOrClarinet()
    {
        assertEquals(6, Ym2413PatchMap.lookup(64), "Soprano Sax → Oboe");
        assertEquals(6, Ym2413PatchMap.lookup(68), "Oboe");
        assertEquals(5, Ym2413PatchMap.lookup(71), "Clarinet");
        assertEquals(5, Ym2413PatchMap.lookup(70), "Bassoon → Clarinet");
    }

    @Test
    void flutes_mapToPatch4()
    {
        assertEquals(4, Ym2413PatchMap.lookup(72), "Piccolo");
        assertEquals(4, Ym2413PatchMap.lookup(73), "Flute");
        assertEquals(4, Ym2413PatchMap.lookup(74), "Recorder");
    }

    @Test
    void fiddle_mapsToPatch1()
    {
        assertEquals(1, Ym2413PatchMap.lookup(110), "Fiddle → Violin");
    }
}

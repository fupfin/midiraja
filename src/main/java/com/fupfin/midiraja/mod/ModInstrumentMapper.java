/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.mod;

import java.util.List;
import java.util.Locale;

/**
 * Maps MOD instrument names to GM program numbers using keyword matching.
 *
 * <p>
 * Returns -2 for percussion instruments (route to MIDI channel 9),
 * -1 when no keyword matches (let {@code TrackRoleAssigner} handle it).
 */
public final class ModInstrumentMapper
{
    /** Sentinel: instrument should go to MIDI channel 9 (drums). */
    public static final int PERCUSSION = -2;
    /** Sentinel: no keyword match; let TrackRoleAssigner assign a program. */
    public static final int UNMATCHED = -1;

    // Ordered list: first match wins. Percussion keywords listed first so e.g. "drum bass"
    // is classified as percussion rather than bass.
    private static final List<int[]> KEYWORD_LIST; // [keyword_index, program]
    private static final String[] KEYWORDS = {
            "kick", "snare", "hihat", "hi-hat", "hat", "clap", "tom", "conga",
            "cym", "cymbal", "perc", "drum",
            "bass", "piano", "pno", "organ", "org", "guitar", "gtr",
            "string", "str", "violin", "cello", "brass", "trumpet",
            "sax", "flute", "lead", "pad", "bell", "marimba", "vibes", "vibe"
    };
    private static final int[] PROGRAMS = {
            PERCUSSION, PERCUSSION, PERCUSSION, PERCUSSION, PERCUSSION, PERCUSSION,
            PERCUSSION, PERCUSSION, PERCUSSION, PERCUSSION, PERCUSSION, PERCUSSION,
            33, 0, 0, 16, 16, 25, 25,
            48, 48, 40, 42, 56, 56,
            65, 73, 80, 88, 14, 12, 11, 11
    };

    static
    {
        var list = new java.util.ArrayList<int[]>();
        for (int i = 0; i < KEYWORDS.length; i++)
            list.add(new int[] { i, PROGRAMS[i] });
        KEYWORD_LIST = List.copyOf(list);
    }

    private ModInstrumentMapper()
    {
    }

    /**
     * Maps an instrument to a GM program.
     *
     * @return GM program 0–127, {@link #PERCUSSION}, or {@link #UNMATCHED}
     */
    /** Maps an instrument name to a GM program. */
    public static int mapNameToGmProgram(String name)
    {
        String lower = name.toLowerCase(Locale.ROOT);
        for (int i = 0; i < KEYWORDS.length; i++)
        {
            if (lower.contains(KEYWORDS[i]))
                return PROGRAMS[i];
        }
        return UNMATCHED;
    }

    public static int mapToGmProgram(ModInstrument instrument)
    {
        return mapNameToGmProgram(instrument.name());
    }
}

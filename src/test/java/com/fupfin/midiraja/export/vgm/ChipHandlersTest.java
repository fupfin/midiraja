/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.export.vgm;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

class ChipHandlersTest
{
    // ── parseChips — routing mode detection ──────────────────────────────────

    @Test
    void plusSeparator_returnsChannelMode()
    {
        ChipSpec spec = ChipHandlers.parseChips("scc+ay8910");
        assertEquals(RoutingMode.CHANNEL, spec.mode());
    }

    @Test
    void arrowSeparator_returnsSequentialMode()
    {
        ChipSpec spec = ChipHandlers.parseChips("scc>ay8910");
        assertEquals(RoutingMode.SEQUENTIAL, spec.mode());
    }

    @Test
    void commaSeparator_returnsChannelMode()
    {
        ChipSpec spec = ChipHandlers.parseChips("ay8910,ym2413");
        assertEquals(RoutingMode.CHANNEL, spec.mode());
    }

    @Test
    void singleChip_returnsChannelMode()
    {
        // No separator present → CHANNEL mode (same as + separator)
        ChipSpec spec = ChipHandlers.parseChips("opl3");
        assertEquals(RoutingMode.CHANNEL, spec.mode());
        assertEquals(List.of(ChipType.OPL3), spec.chips());
    }

    // ── parseChips — chip list ────────────────────────────────────────────────

    @Test
    void sccPlusAy8910_returnsTwoChips()
    {
        ChipSpec spec = ChipHandlers.parseChips("scc+ay8910");
        assertEquals(List.of(ChipType.SCC, ChipType.AY8910), spec.chips());
    }

    @Test
    void sccArrowAy8910_returnsTwoChips()
    {
        ChipSpec spec = ChipHandlers.parseChips("scc>ay8910");
        assertEquals(List.of(ChipType.SCC, ChipType.AY8910), spec.chips());
    }

    @Test
    void unknownChip_throwsIllegalArgument()
    {
        assertThrows(IllegalArgumentException.class, () -> ChipHandlers.parseChips("unknown"));
    }

    @Test
    void emptySpec_throwsIllegalArgument()
    {
        assertThrows(IllegalArgumentException.class, () -> ChipHandlers.parseChips(""));
    }

    // ── create ────────────────────────────────────────────────────────────────

    @Test
    void create_fromChipSpec_createsHandlers()
    {
        ChipSpec spec = ChipHandlers.parseChips("scc>ay8910");
        var handlers = ChipHandlers.create(spec);
        assertEquals(2, handlers.size());
        assertEquals(ChipType.SCC, handlers.get(0).chipType());
        assertEquals(ChipType.AY8910, handlers.get(1).chipType());
    }

    @Test
    void create_dualAy8910_createsTwoHandlers()
    {
        var handlers = ChipHandlers.create(List.of(ChipType.AY8910, ChipType.AY8910));
        assertEquals(2, handlers.size());
        handlers.forEach(h -> assertEquals(ChipType.AY8910, h.chipType()));
    }
}

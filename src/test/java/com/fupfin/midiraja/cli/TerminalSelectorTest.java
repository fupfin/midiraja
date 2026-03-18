/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fupfin.midiraja.cli.TerminalSelector.Item;
import com.fupfin.midiraja.cli.TerminalSelector.SelectionMode;
import java.util.List;
import org.junit.jupiter.api.Test;

class TerminalSelectorTest
{
    // ── resolveMode ────────────────────────────────────────────────────────────

    @Test
    void resolveMode_nonInteractive_returnsClassic()
    {
        assertEquals(SelectionMode.CLASSIC,
                TerminalSelector.resolveMode(false, 24, false, false, false));
    }

    @Test
    void resolveMode_preferClassic_returnsClassic()
    {
        assertEquals(SelectionMode.CLASSIC,
                TerminalSelector.resolveMode(true, 24, false, false, true));
    }

    @Test
    void resolveMode_preferClassicOverridesFull()
    {
        assertEquals(SelectionMode.CLASSIC,
                TerminalSelector.resolveMode(true, 24, true, false, true));
    }

    @Test
    void resolveMode_preferMini_returnsMini()
    {
        assertEquals(SelectionMode.MINI,
                TerminalSelector.resolveMode(true, 24, false, true, false));
    }

    @Test
    void resolveMode_preferFull_returnsFull()
    {
        assertEquals(SelectionMode.FULL,
                TerminalSelector.resolveMode(true, 24, true, false, false));
    }

    @Test
    void resolveMode_tallTerminal_returnsFull()
    {
        assertEquals(SelectionMode.FULL,
                TerminalSelector.resolveMode(true, 10, false, false, false));
    }

    @Test
    void resolveMode_shortTerminal_returnsMini()
    {
        assertEquals(SelectionMode.MINI,
                TerminalSelector.resolveMode(true, 9, false, false, false));
    }

    // ── firstSelectable ────────────────────────────────────────────────────────

    @Test
    void firstSelectable_skipsLeadingSeparator()
    {
        var items = List.of(
                Item.separator("header"),
                Item.of("a", "A", ""),
                Item.of("b", "B", ""));
        assertEquals(1, TerminalSelector.firstSelectable(items));
    }

    @Test
    void firstSelectable_firstItemSelectable()
    {
        var items = List.of(Item.of("a", "A", ""), Item.separator("sep"));
        assertEquals(0, TerminalSelector.firstSelectable(items));
    }

    @Test
    void firstSelectable_allSeparators_returnsZero()
    {
        var items = List.of(Item.separator("x"), Item.separator("y"));
        assertEquals(0, TerminalSelector.firstSelectable(items));
    }

    // ── nextSelectable ─────────────────────────────────────────────────────────

    @Test
    void nextSelectable_skipsDownSeparator()
    {
        var items = List.of(
                Item.of("a", "A", ""),
                Item.separator("sep"),
                Item.of("b", "B", ""));
        assertEquals(2, TerminalSelector.nextSelectable(items, 0, 1));
    }

    @Test
    void nextSelectable_skipsUpSeparator()
    {
        var items = List.of(
                Item.of("a", "A", ""),
                Item.separator("sep"),
                Item.of("b", "B", ""));
        assertEquals(0, TerminalSelector.nextSelectable(items, 2, -1));
    }

    @Test
    void nextSelectable_wrapsAroundDown()
    {
        var items = List.of(Item.of("a", "A", ""), Item.of("b", "B", ""));
        assertEquals(0, TerminalSelector.nextSelectable(items, 1, 1));
    }

    @Test
    void nextSelectable_wrapsAroundUp()
    {
        var items = List.of(Item.of("a", "A", ""), Item.of("b", "B", ""));
        assertEquals(1, TerminalSelector.nextSelectable(items, 0, -1));
    }

    // ── Item factory ───────────────────────────────────────────────────────────

    @Test
    void item_of_isNotSeparator()
    {
        var item = Item.of("val", "Label", "Desc");
        assertFalse(item.isSeparator());
        assertEquals("val", item.value());
        assertEquals("Label", item.label());
        assertEquals("Desc", item.description());
    }

    @Test
    void item_separator_isSeparator()
    {
        var item = Item.<String>separator("sep");
        assertTrue(item.isSeparator());
        assertNull(item.value());
        assertEquals("sep", item.label());
    }
}

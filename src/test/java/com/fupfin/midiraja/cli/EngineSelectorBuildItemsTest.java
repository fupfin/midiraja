/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fupfin.midiraja.midi.MidiPort;
import java.util.List;
import org.junit.jupiter.api.Test;

class EngineSelectorBuildItemsTest
{
    private static final int BUILTIN_COUNT = 6;

    @Test
    void noPorts_returnsOnlyBuiltins()
    {
        var items = EngineSelector.buildItems(List.of());

        assertEquals(BUILTIN_COUNT, items.size());
        assertTrue(items.stream().noneMatch(TerminalSelector.Item::isSeparator));
        assertTrue(items.stream().allMatch(i -> i.value() instanceof EngineSelector.Choice.Builtin));
    }

    @Test
    void onePort_prependsPortAndSeparator()
    {
        var items = EngineSelector.buildItems(List.of(new MidiPort(3, "Roland SC-55")));

        // port + separator + builtins
        assertEquals(1 + 1 + BUILTIN_COUNT, items.size());
        assertInstanceOf(EngineSelector.Choice.Port.class, items.get(0).value());
        assertEquals("Roland SC-55", items.get(0).label());
        assertTrue(items.get(1).isSeparator());
        assertNull(items.get(1).value());
    }

    @Test
    void multiplePorts_allPrependedBeforeSeparator()
    {
        var ports = List.of(new MidiPort(0, "Port A"), new MidiPort(1, "Port B"));
        var items = EngineSelector.buildItems(ports);

        assertEquals(2 + 1 + BUILTIN_COUNT, items.size());
        assertInstanceOf(EngineSelector.Choice.Port.class, items.get(0).value());
        assertInstanceOf(EngineSelector.Choice.Port.class, items.get(1).value());
        assertTrue(items.get(2).isSeparator());
    }

    @Test
    void portItem_portIndexPreserved()
    {
        var items = EngineSelector.buildItems(List.of(new MidiPort(7, "My Port")));
        var portChoice = (EngineSelector.Choice.Port) items.get(0).value();
        assertEquals(7, portChoice.portIndex());
    }

    @Test
    void builtinItem_engineNameIsStripped()
    {
        // Labels have padding spaces ("soundfont   "), but Choice.Builtin.engineName() must be stripped
        var items = EngineSelector.buildItems(List.of());
        items.forEach(item -> {
            var builtin = (EngineSelector.Choice.Builtin) item.value();
            assertFalse(builtin.engineName().isBlank(), "engineName should not be blank");
            assertEquals(builtin.engineName().strip(), builtin.engineName(),
                    "engineName should be stripped: '" + builtin.engineName() + "'");
        });
    }

    @Test
    void builtinItems_orderedByAudioQuality()
    {
        var items = EngineSelector.buildItems(List.of());
        // Best-first order as documented: soundfont, patch, opn, opl, psg, 1bit
        var names = items.stream()
                .map(i -> ((EngineSelector.Choice.Builtin) i.value()).engineName())
                .toList();
        assertEquals(List.of("soundfont", "patch", "opn", "opl", "psg", "1bit"), names);
    }
}

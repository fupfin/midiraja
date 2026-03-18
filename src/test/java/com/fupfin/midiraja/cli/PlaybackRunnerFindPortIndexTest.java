/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fupfin.midiraja.midi.MidiPort;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlaybackRunnerFindPortIndexTest
{
    private ByteArrayOutputStream errBytes;
    private PrintStream err;
    private List<MidiPort> ports;

    @BeforeEach
    void setUp()
    {
        errBytes = new ByteArrayOutputStream();
        err = new PrintStream(errBytes);
        ports = List.of(
                new MidiPort(0, "Roland MT-32"),
                new MidiPort(1, "General MIDI Synth"),
                new MidiPort(2, "Yamaha MIDI Out"));
    }

    @Test
    void integerQuery_matchesExistingIndex()
    {
        assertEquals(1, PlaybackRunner.findPortIndex(ports, "1", err));
    }

    @Test
    void integerQuery_indexNotInList_fallsBackToNameSearch()
    {
        // "99" is not a valid port index — falls through to name search, also no match
        assertEquals(-1, PlaybackRunner.findPortIndex(ports, "99", err));
    }

    @Test
    void nameQuery_exactMatch_caseInsensitive()
    {
        assertEquals(0, PlaybackRunner.findPortIndex(ports, "roland", err));
    }

    @Test
    void nameQuery_partialMatch_uppercase()
    {
        assertEquals(2, PlaybackRunner.findPortIndex(ports, "YAMAHA", err));
    }

    @Test
    void nameQuery_singleMatch_returnsIndex()
    {
        assertEquals(1, PlaybackRunner.findPortIndex(ports, "General", err));
    }

    @Test
    void nameQuery_noMatch_returnsMinusOne()
    {
        assertEquals(-1, PlaybackRunner.findPortIndex(ports, "Korg", err));
        assertEquals("", errBytes.toString(StandardCharsets.UTF_8).trim()); // no error printed
    }

    @Test
    void nameQuery_multipleMatches_returnsMinusOne_andPrintsWarning()
    {
        // "MIDI" matches "General MIDI Synth" and "Yamaha MIDI Out"
        assertEquals(-1, PlaybackRunner.findPortIndex(ports, "MIDI", err));
        assertTrue(errBytes.toString(StandardCharsets.UTF_8).contains("Ambiguous"));
    }

    @Test
    void emptyPortList_returnsMinusOne()
    {
        assertEquals(-1, PlaybackRunner.findPortIndex(List.of(), "0", err));
    }

    @Test
    void integerQuery_zeroIndex_found()
    {
        assertEquals(0, PlaybackRunner.findPortIndex(ports, "0", err));
    }
}

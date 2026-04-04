/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.it;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;

import org.junit.jupiter.api.Test;

class ItToMidiConverterTest
{
    private static ItParseResult emptyResult()
    {
        return new ItParseResult("", 4, List.of(), List.of());
    }

    private static List<ItInstrument> instruments(String... names)
    {
        var list = new java.util.ArrayList<ItInstrument>();
        for (String n : names) list.add(new ItInstrument(n, 64));
        return List.copyOf(list);
    }

    @Test
    void convert_empty_returnsValidSequence()
    {
        var seq = new ItToMidiConverter().convert(emptyResult());
        assertNotNull(seq);
        assertEquals(Sequence.PPQ, seq.getDivisionType());
        assertEquals(480, seq.getResolution());
    }

    @Test
    void convert_hasTempoTrack()
    {
        var seq = new ItToMidiConverter().convert(emptyResult());
        var t = seq.getTracks()[0];
        boolean found = false;
        for (int i = 0; i < t.size(); i++)
            if (t.get(i).getMessage() instanceof MetaMessage mm && mm.getType() == 0x51)
                found = true;
        assertTrue(found);
    }

    @Test
    void convert_titleInTempoTrack()
    {
        var seq = new ItToMidiConverter().convert(
                new ItParseResult("My IT Track", 4, List.of(), List.of()));
        var t = seq.getTracks()[0];
        boolean found = false;
        for (int i = 0; i < t.size(); i++)
            if (t.get(i).getMessage() instanceof MetaMessage mm && mm.getType() == 0x03)
                found = true;
        assertTrue(found);
    }

    @Test
    void convert_noteEvent_producesNoteOn()
    {
        // IT note 60 = MIDI 60 (middle C), instrument 1
        var events = List.of(new ItEvent(0, 0, 60, 1, -1, 0, 0));
        var result = new ItParseResult("", 4, instruments("lead"), events);
        var seq = new ItToMidiConverter().convert(result);

        boolean found = false;
        for (var track : seq.getTracks())
            for (int i = 0; i < track.size(); i++)
                if (track.get(i).getMessage() instanceof ShortMessage sm
                        && sm.getCommand() == ShortMessage.NOTE_ON && sm.getData1() == 60)
                    found = true;
        assertTrue(found, "Should produce NoteOn for MIDI note 60");
    }

    @Test
    void convert_percussionInstrument_routesToDrumChannel()
    {
        var events = List.of(new ItEvent(0, 0, 60, 1, -1, 0, 0));
        var result = new ItParseResult("", 4, instruments("hihat"), events);
        var seq = new ItToMidiConverter().convert(result);

        boolean found = false;
        for (var track : seq.getTracks())
            for (int i = 0; i < track.size(); i++)
                if (track.get(i).getMessage() instanceof ShortMessage sm
                        && sm.getCommand() == ShortMessage.NOTE_ON && sm.getChannel() == 9)
                    found = true;
        assertTrue(found, "Percussion instrument should route to MIDI channel 9");
    }

    @Test
    void convert_noteCut_emitsNoteOff()
    {
        var events = List.of(
                new ItEvent(0,       0, 60, 1, -1, 0, 0),
                new ItEvent(480_000, 0, -2, 0, -1, 0, 0));
        var result = new ItParseResult("", 4, instruments("lead"), events);
        var seq = new ItToMidiConverter().convert(result);

        boolean found = false;
        for (var track : seq.getTracks())
            for (int i = 0; i < track.size(); i++)
                if (track.get(i).getMessage() instanceof ShortMessage sm
                        && sm.getCommand() == ShortMessage.NOTE_OFF && sm.getData1() == 60)
                    found = true;
        assertTrue(found, "Note cut should emit NoteOff");
    }

    @Test
    void convert_volumeColumn_emitsCC7()
    {
        var events = List.of(new ItEvent(0, 0, -1, 0, 32, 0, 0));
        var result = new ItParseResult("", 4, List.of(), events);
        var seq = new ItToMidiConverter().convert(result);

        boolean found = false;
        for (var track : seq.getTracks())
            for (int i = 0; i < track.size(); i++)
                if (track.get(i).getMessage() instanceof ShortMessage sm
                        && sm.getCommand() == ShortMessage.CONTROL_CHANGE && sm.getData1() == 7)
                    found = true;
        assertTrue(found, "Volume column should emit CC7");
    }

    @Test
    void toTick_zeroPosIsZero()
    {
        assertEquals(0L, ItToMidiConverter.toTick(0));
    }

    @Test
    void toTick_oneSecond_is960Ticks()
    {
        assertEquals(960L, ItToMidiConverter.toTick(1_000_000));
    }

    @Test
    void buildMidiChannelMap_skipsDrumChannel()
    {
        int[] map = ItToMidiConverter.buildMidiChannelMap(10);
        for (int ch : map) assertNotEquals(9, ch);
    }
}

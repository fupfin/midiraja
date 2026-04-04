/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.s3m;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;

import org.junit.jupiter.api.Test;

class S3mToMidiConverterTest
{
    private static S3mParseResult emptyResult()
    {
        return new S3mParseResult("", 4, List.of(), List.of());
    }

    private static List<S3mInstrument> instruments(String... names)
    {
        var list = new java.util.ArrayList<S3mInstrument>();
        for (String n : names) list.add(new S3mInstrument(n, 64));
        return List.copyOf(list);
    }

    @Test
    void convert_empty_returnsValidSequence()
    {
        var seq = new S3mToMidiConverter().convert(emptyResult());
        assertNotNull(seq);
        assertEquals(Sequence.PPQ, seq.getDivisionType());
        assertEquals(480, seq.getResolution());
    }

    @Test
    void convert_hasTempoTrack()
    {
        var seq = new S3mToMidiConverter().convert(emptyResult());
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
        var seq = new S3mToMidiConverter().convert(
                new S3mParseResult("My Track", 4, List.of(), List.of()));
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
        // C5 = MIDI 60; instrument 1; volume -1 (column empty)
        var events = List.of(new S3mEvent(0, 0, 60, 1, -1, 0, 0));
        var result = new S3mParseResult("", 4, instruments("lead"), events);
        var seq = new S3mToMidiConverter().convert(result);

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
        var events = List.of(new S3mEvent(0, 0, 60, 1, -1, 0, 0));
        var result = new S3mParseResult("", 4, instruments("kick drum"), events);
        var seq = new S3mToMidiConverter().convert(result);

        boolean found = false;
        for (var track : seq.getTracks())
            for (int i = 0; i < track.size(); i++)
                if (track.get(i).getMessage() instanceof ShortMessage sm
                        && sm.getCommand() == ShortMessage.NOTE_ON && sm.getChannel() == 9)
                    found = true;
        assertTrue(found, "Percussion instrument should route to MIDI channel 9");
    }

    @Test
    void convert_volumeColumn_emitsCC7()
    {
        // volume=32 → 32/64*127 ≈ 63
        var events = List.of(new S3mEvent(0, 0, -1, 0, 32, 0, 0));
        var result = new S3mParseResult("", 4, List.of(), events);
        var seq = new S3mToMidiConverter().convert(result);

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
        assertEquals(0L, S3mToMidiConverter.toTick(0));
    }

    @Test
    void toTick_oneSecond_is960Ticks()
    {
        assertEquals(960L, S3mToMidiConverter.toTick(1_000_000));
    }

    @Test
    void buildMidiChannelMap_skipsDrumChannel()
    {
        int[] map = S3mToMidiConverter.buildMidiChannelMap(8);
        for (int ch : map) assertNotEquals(9, ch, "MIDI channel 9 must not be assigned");
    }
}

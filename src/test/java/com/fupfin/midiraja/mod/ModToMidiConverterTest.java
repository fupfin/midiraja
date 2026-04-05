/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.mod;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

import org.junit.jupiter.api.Test;

class ModToMidiConverterTest
{
    private static ModParseResult emptyResult()
    {
        return new ModParseResult("", 4, emptyInstruments(), List.of(), "M.K.");
    }

    private static List<ModInstrument> emptyInstruments()
    {
        var list = new java.util.ArrayList<ModInstrument>();
        for (int i = 0; i < 31; i++)
            list.add(new ModInstrument("", 0, 0, 64, 0, 0));
        return List.copyOf(list);
    }

    private static List<ModInstrument> instrumentList(String... names)
    {
        var list = new java.util.ArrayList<ModInstrument>();
        for (String name : names)
            list.add(new ModInstrument(name, 0, 0, 64, 0, 0));
        while (list.size() < 31)
            list.add(new ModInstrument("", 0, 0, 64, 0, 0));
        return List.copyOf(list);
    }

    private static ShortMessage findFirst(Track track, int command)
    {
        for (int i = 0; i < track.size(); i++)
        {
            var msg = track.get(i).getMessage();
            if (msg instanceof ShortMessage sm && sm.getCommand() == command)
                return sm;
        }
        return null;
    }

    @Test
    void convert_emptyEvents_returnsValidSequence()
    {
        var sequence = new ModToMidiConverter().convert(emptyResult());
        assertNotNull(sequence);
        assertEquals(Sequence.PPQ, sequence.getDivisionType());
        assertEquals(480, sequence.getResolution());
    }

    @Test
    void convert_hasTempoTrack()
    {
        var sequence = new ModToMidiConverter().convert(emptyResult());
        var tempoTrack = sequence.getTracks()[0];
        boolean hasTempo = false;
        for (int i = 0; i < tempoTrack.size(); i++)
        {
            var e = tempoTrack.get(i);
            if (e.getMessage() instanceof MetaMessage mm && mm.getType() == 0x51)
            {
                hasTempo = true;
                break;
            }
        }
        assertTrue(hasTempo, "Track 0 must contain a tempo meta-event");
    }

    @Test
    void convert_titleInTempoTrack()
    {
        var parsed = new ModParseResult("My Song", 4, emptyInstruments(), List.of(), "M.K.");
        var sequence = new ModToMidiConverter().convert(parsed);
        var tempoTrack = sequence.getTracks()[0];
        boolean hasTitle = false;
        for (int i = 0; i < tempoTrack.size(); i++)
        {
            var e = tempoTrack.get(i);
            if (e.getMessage() instanceof MetaMessage mm && mm.getType() == 0x03)
            {
                hasTitle = true;
                break;
            }
        }
        assertTrue(hasTitle, "Non-blank title should produce a sequence name meta-event");
    }

    @Test
    void convert_noteEvent_producesNoteOn() throws Exception
    {
        var events = List.of(new ModEvent(0, 0, 428, 1, 0, 0)); // period 428 = C4 = MIDI 60
        var parsed = new ModParseResult("", 4, instrumentList("lead"), events, "M.K.");
        var sequence = new ModToMidiConverter().convert(parsed);

        boolean foundNoteOn = false;
        for (var track : sequence.getTracks())
        {
            for (int i = 0; i < track.size(); i++)
            {
                var msg = track.get(i).getMessage();
                if (msg instanceof ShortMessage sm && sm.getCommand() == ShortMessage.NOTE_ON
                        && sm.getData1() == 60)
                {
                    foundNoteOn = true;
                }
            }
        }
        assertTrue(foundNoteOn, "Period 428 should produce NoteOn for MIDI note 60");
    }

    @Test
    void convert_percussionInstrument_routesToDrumChannel() throws Exception
    {
        // Instrument named "kick drum" should map to channel 9
        var events = List.of(new ModEvent(0, 0, 428, 1, 0, 0));
        var parsed = new ModParseResult("", 4, instrumentList("kick drum"), events, "M.K.");
        var sequence = new ModToMidiConverter().convert(parsed);

        boolean foundOnDrumCh = false;
        for (var track : sequence.getTracks())
        {
            for (int i = 0; i < track.size(); i++)
            {
                var msg = track.get(i).getMessage();
                if (msg instanceof ShortMessage sm && sm.getCommand() == ShortMessage.NOTE_ON
                        && sm.getChannel() == 9)
                {
                    foundOnDrumCh = true;
                }
            }
        }
        assertTrue(foundOnDrumCh, "Percussion instrument should route to MIDI channel 9");
    }

    @Test
    void convert_volumeEffect_emitsCC7() throws Exception
    {
        // Cxx effect: set volume. param=32 → 32/64*127 ≈ 63
        var events = List.of(new ModEvent(0, 0, 0, 0, 0xC, 32));
        var parsed = new ModParseResult("", 4, emptyInstruments(), events, "M.K.");
        var sequence = new ModToMidiConverter().convert(parsed);

        boolean foundCC7 = false;
        for (var track : sequence.getTracks())
        {
            for (int i = 0; i < track.size(); i++)
            {
                var msg = track.get(i).getMessage();
                if (msg instanceof ShortMessage sm && sm.getCommand() == ShortMessage.CONTROL_CHANGE
                        && sm.getData1() == 7)
                {
                    foundCC7 = true;
                }
            }
        }
        assertTrue(foundCC7, "Cxx effect should emit CC7 (volume)");
    }

    @Test
    void toTick_zeroPosIsZero()
    {
        assertEquals(0L, ModToMidiConverter.toTick(0));
    }

    @Test
    void toTick_oneSecond_is960Ticks()
    {
        // 1_000_000 µs → 960 ticks at 120 BPM / PPQ=480
        assertEquals(960L, ModToMidiConverter.toTick(1_000_000));
    }

    @Test
    void buildMidiChannelMap_skipsDrumChannel()
    {
        int[] map = ModToMidiConverter.buildMidiChannelMap(8);
        for (int ch : map)
            assertNotEquals(9, ch, "MIDI channel 9 must not be assigned to MOD channels");
    }

    @Test
    void buildMidiChannelMap_firstFour()
    {
        int[] map = ModToMidiConverter.buildMidiChannelMap(4);
        assertArrayEquals(new int[] { 0, 1, 2, 3 }, map);
    }
}

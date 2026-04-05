/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.vgm;

import static org.junit.jupiter.api.Assertions.*;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

import org.junit.jupiter.api.Test;

class TrackRoleAssignerTest
{

    /** Creates a Sequence with a tempo track (index 0) and 15 channel tracks (indices 1-15). */
    private static Sequence makeSequence() throws InvalidMidiDataException
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        for (int i = 0; i < 16; i++)
        {
            seq.createTrack(); // track 0 = tempo, 1-15 = channels
        }
        return seq;
    }

    private static void addNote(Track track, int ch, int note, long startTick, long duration)
            throws InvalidMidiDataException
    {
        track.add(new MidiEvent(
                new ShortMessage(ShortMessage.NOTE_ON, ch, note, 100), startTick));
        track.add(new MidiEvent(
                new ShortMessage(ShortMessage.NOTE_OFF, ch, note, 0), startTick + duration));
    }

    private static ShortMessage findProgramChange(Track track)
    {
        for (int i = 0; i < track.size(); i++)
        {
            var msg = track.get(i).getMessage();
            if (msg instanceof ShortMessage sm && sm.getCommand() == ShortMessage.PROGRAM_CHANGE)
            {
                return sm;
            }
        }
        return null;
    }

    @Test
    void bassTrack_getsElectricBass() throws Exception
    {
        var seq = makeSequence();
        var tracks = seq.getTracks();

        // Channel 0 (track 1): low notes → bass role
        addNote(tracks[1], 0, 36, 0, 480);
        addNote(tracks[1], 0, 40, 480, 480);
        // Channel 1 (track 2): high notes → lead (needed so ch0 becomes bass, not lead)
        addNote(tracks[2], 1, 72, 0, 480);
        addNote(tracks[2], 1, 76, 480, 480);

        TrackRoleAssigner.assign(seq);

        var pc = findProgramChange(tracks[1]);
        assertNotNull(pc, "Bass track must receive Program Change");
        assertEquals(33, pc.getData1(), "Bass track → Electric Bass (33)");
    }

    @Test
    void leadTrack_getsElectricPiano1() throws Exception
    {
        var seq = makeSequence();
        var tracks = seq.getTracks();

        // Channel 0: high notes → lead role (only non-bass track → becomes lead)
        addNote(tracks[1], 0, 72, 0, 480);
        addNote(tracks[1], 0, 76, 480, 480);

        TrackRoleAssigner.assign(seq);

        var pc = findProgramChange(tracks[1]);
        assertNotNull(pc, "Lead track must receive Program Change");
        assertEquals(4, pc.getData1(), "Lead track → Electric Piano 1 (4)");
    }

    @Test
    void harmonyTrack_getsElectricPiano2() throws Exception
    {
        var seq = makeSequence();
        var tracks = seq.getTracks();

        // Channel 0 (track 1): highest median → lead
        addNote(tracks[1], 0, 76, 0, 480);
        addNote(tracks[1], 0, 80, 480, 480);

        // Channel 1 (track 2): mid median → harmony (not bass, not lead)
        addNote(tracks[2], 1, 60, 0, 480);
        addNote(tracks[2], 1, 64, 480, 480);

        // Channel 2 (track 3): lowest median → bass
        addNote(tracks[3], 2, 36, 0, 480);
        addNote(tracks[3], 2, 40, 480, 480);

        TrackRoleAssigner.assign(seq);

        var leadPc = findProgramChange(tracks[1]);
        assertNotNull(leadPc);
        assertEquals(4, leadPc.getData1(), "Highest median → Electric Piano 1 (lead)");

        var harmonyPc = findProgramChange(tracks[2]);
        assertNotNull(harmonyPc);
        assertEquals(5, harmonyPc.getData1(), "Mid median → Electric Piano 2 (harmony)");
    }

    @Test
    void percussiveTrack_getsXylophone() throws Exception
    {
        var seq = makeSequence();
        var tracks = seq.getTracks();

        // Channel 0: very short note durations (< 200 ticks) → percussive melody → Xylophone
        addNote(tracks[1], 0, 72, 0, 100);
        addNote(tracks[1], 0, 76, 200, 100);
        addNote(tracks[1], 0, 74, 400, 100);

        TrackRoleAssigner.assign(seq);

        var pc = findProgramChange(tracks[1]);
        assertNotNull(pc, "Percussive track must receive Program Change");
        assertEquals(13, pc.getData1(), "Percussive melody → Xylophone (13)");
    }
}

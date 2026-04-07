/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.format.vgm;

import static org.junit.jupiter.api.Assertions.*;

import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

import org.junit.jupiter.api.Test;

class Ym3812MidiConverterTest
{

    private static final long CLOCK = 3_579_545L;

    private static VgmEvent opl2(int reg, int data)
    {
        return new VgmEvent(0, 14, new byte[] { (byte) reg, (byte) data });
    }

    private static Track[] makeTracks() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        var tracks = new Track[15];
        for (int i = 0; i < 15; i++)
            tracks[i] = seq.createTrack();
        return tracks;
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
    void opl2Note_a4()
    {
        // f = fnum * clock / (72 * 2^(20-block))
        // fnum = 440 * 72 * 2^(20-block) / clock
        // block=4: fnum = 440 * 72 * 65536 / 3579545 ≈ 580
        int note = Ym3812MidiConverter.opl2Note(CLOCK, 580, 4);
        // +12 octave correction: fundamental A4 → perceived A5
        assertTrue(note >= 80 && note <= 82, "fnum=580, block=4 → A5 (MIDI ~81 with +12), got " + note);
    }

    @Test
    void opl2Note_fnumZero_returnsMinusOne()
    {
        assertEquals(-1, Ym3812MidiConverter.opl2Note(CLOCK, 0, 3));
    }

    @Test
    void keyOn_producesNoteOn() throws Exception
    {
        var converter = new Ym3812MidiConverter();
        var tracks = makeTracks();

        converter.convert(opl2(0xC0, 0x08), tracks, CLOCK, 0); // conn=0, fb=4 (melodic patch)
        converter.convert(opl2(0x40, 30), tracks, CLOCK, 0); // modulator TL=30 (not effect)
        converter.convert(opl2(0x43, 10), tracks, CLOCK, 0);
        converter.convert(opl2(0xA0, 0x44), tracks, CLOCK, 0);
        converter.convert(opl2(0xB0, 0x32), tracks, CLOCK, 1);

        var noteOn = findFirst(tracks[0], ShortMessage.NOTE_ON);
        assertNotNull(noteOn, "Key-on should produce NoteOn");
    }

    @Test
    void keyOff_producesNoteOff() throws Exception
    {
        var converter = new Ym3812MidiConverter();
        var tracks = makeTracks();

        converter.convert(opl2(0xC0, 0x08), tracks, CLOCK, 0); // melodic patch
        converter.convert(opl2(0x40, 30), tracks, CLOCK, 0);
        converter.convert(opl2(0x43, 10), tracks, CLOCK, 0);
        converter.convert(opl2(0xA0, 0x44), tracks, CLOCK, 0);
        converter.convert(opl2(0xB0, 0x32), tracks, CLOCK, 1); // key-on
        converter.convert(opl2(0xB0, 0x12), tracks, CLOCK, 2); // key-off

        var noteOff = findFirst(tracks[0], ShortMessage.NOTE_OFF);
        assertNotNull(noteOff, "Key-off should produce NoteOff");
    }

    @Test
    void connectionFM_emitsNoteOn() throws Exception
    {
        // Converters no longer emit Program Change; verify NoteOn is produced.
        var converter = new Ym3812MidiConverter();
        var tracks = makeTracks();

        converter.convert(opl2(0xC0, 0x08), tracks, CLOCK, 0); // conn=0, fb=4 (melodic)
        converter.convert(opl2(0x40, 30), tracks, CLOCK, 0); // modTL=30 (not effect)
        converter.convert(opl2(0x43, 10), tracks, CLOCK, 0);
        converter.convert(opl2(0xA0, 0x44), tracks, CLOCK, 0);
        converter.convert(opl2(0xB0, 0x32), tracks, CLOCK, 1);

        var noteOn = findFirst(tracks[0], ShortMessage.NOTE_ON);
        assertNotNull(noteOn, "FM melodic patch key-on should produce NoteOn");
        assertNull(findFirst(tracks[0], ShortMessage.PROGRAM_CHANGE),
                "Individual converters must not emit Program Change");
    }

    @Test
    void connectionAM_percussive_emitsNoteOn() throws Exception
    {
        // Converters no longer emit Program Change; verify NoteOn is produced.
        var converter = new Ym3812MidiConverter();
        var tracks = makeTracks();

        converter.convert(opl2(0xC0, 0x01), tracks, CLOCK, 0);
        converter.convert(opl2(0x40, 20), tracks, CLOCK, 0);
        converter.convert(opl2(0x43, 10), tracks, CLOCK, 0);
        converter.convert(opl2(0x63, 0xF8), tracks, CLOCK, 0);
        converter.convert(opl2(0xA0, 0x44), tracks, CLOCK, 0);
        converter.convert(opl2(0xB0, 0x32), tracks, CLOCK, 1);

        var noteOn = findFirst(tracks[0], ShortMessage.NOTE_ON);
        assertNotNull(noteOn, "AM + percussive key-on should produce NoteOn");
        assertNull(findFirst(tracks[0], ShortMessage.PROGRAM_CHANGE),
                "Individual converters must not emit Program Change");
    }

    @Test
    void carrierTl_affectsVelocity() throws Exception
    {
        var converter = new Ym3812MidiConverter();
        var tracks = makeTracks();

        converter.convert(opl2(0xC0, 0x08), tracks, CLOCK, 0); // melodic patch
        converter.convert(opl2(0x40, 30), tracks, CLOCK, 0); // modTL=30 (not effect)
        converter.convert(opl2(0x43, 0), tracks, CLOCK, 0); // carrier TL=0 (loudest)
        converter.convert(opl2(0xA0, 0x44), tracks, CLOCK, 0);
        converter.convert(opl2(0xB0, 0x32), tracks, CLOCK, 1);

        var noteOn = findFirst(tracks[0], ShortMessage.NOTE_ON);
        assertNotNull(noteOn);
        assertEquals(127, noteOn.getData2(), "Carrier TL=0 (loudest) → velocity=127");
    }

    @Test
    void rhythmMode_bassDrum() throws Exception
    {
        var converter = new Ym3812MidiConverter();
        var tracks = makeTracks();

        // Write 0xBD with bit5=1 (rhythm mode) + bit4=1 (BD key-on)
        converter.convert(opl2(0xBD, 0x30), tracks, CLOCK, 1);

        var noteOn = findFirst(tracks[9], ShortMessage.NOTE_ON);
        assertNotNull(noteOn, "Rhythm BD should route to MIDI ch 9");
        assertEquals(36, noteOn.getData1(), "BD → GM 36 (Bass Drum 1)");
    }
}

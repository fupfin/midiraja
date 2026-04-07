/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.format.vgm;

import static org.junit.jupiter.api.Assertions.*;

import javax.sound.midi.MidiEvent;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

import org.junit.jupiter.api.Test;

class Sn76489MidiConverterTest
{

    private static final long CLOCK = 3_579_545L;

    @Test
    void sn76489Note_middleC()
    {
        // N=428 → f = 3579545 / (32*428) ≈ 261.2 Hz → MIDI ~60
        int note = Sn76489MidiConverter.sn76489Note(CLOCK, 428);
        assertEquals(60, note, 1);
    }

    @Test
    void sn76489Note_zeroN_returnsMinusOne()
    {
        assertEquals(-1, Sn76489MidiConverter.sn76489Note(CLOCK, 0));
    }

    @Test
    void volumeToVelocity_vol0_max() throws Exception
    {
        // vol=0 → loudest → velocity 127; vol=15 → silent → velocity 0
        // We test indirectly: set volume=0 on ch0 with a tone → expect NoteOn velocity=127
        var converter = new Sn76489MidiConverter();
        var seq = new Sequence(Sequence.PPQ, 4410);
        var tracks = new Track[] { seq.createTrack(), seq.createTrack(), seq.createTrack(), seq.createTrack() };

        // Latch ch0 tone low 4 bits = 0x0C (tone[0] low nibble)
        converter.convert(new VgmEvent(0, 0, new byte[] { (byte) 0x8C }), tracks, CLOCK, 0);
        // Data byte: high 6 bits → tone[0] = (0x1A << 4) | 0x0C = 0x1AC = 428
        converter.convert(new VgmEvent(0, 0, new byte[] { 0x1A }), tracks, CLOCK, 0);
        // Latch ch0 volume = 0 (loudest): 1_00_1_0000
        converter.convert(new VgmEvent(0, 0, new byte[] { (byte) 0x90 }), tracks, CLOCK, 0);

        // Find NoteOn in track 0
        var noteOn = findNoteOn(tracks[0]);
        assertNotNull(noteOn, "Expected a NoteOn event");
        assertEquals(127, noteOn.getData2()); // velocity
    }

    @Test
    void volumeToVelocity_vol15_noteOff() throws Exception
    {
        var converter = new Sn76489MidiConverter();
        var seq = new Sequence(Sequence.PPQ, 4410);
        var tracks = new Track[] { seq.createTrack(), seq.createTrack(), seq.createTrack(), seq.createTrack() };

        // First turn on: tone + volume=0
        converter.convert(new VgmEvent(0, 0, new byte[] { (byte) 0x8C }), tracks, CLOCK, 0);
        converter.convert(new VgmEvent(0, 0, new byte[] { 0x1A }), tracks, CLOCK, 0);
        converter.convert(new VgmEvent(0, 0, new byte[] { (byte) 0x90 }), tracks, CLOCK, 0);

        // Then set volume=15 (silent) → should produce NoteOff
        converter.convert(new VgmEvent(10, 0, new byte[] { (byte) 0x9F }), tracks, CLOCK, 0);

        var noteOff = findNoteOff(tracks[0]);
        assertNotNull(noteOff, "Expected a NoteOff event");
    }

    @Test
    void noiseChannel_usesGmPercussionChannel() throws Exception
    {
        // Noise (ch 3 internally) must emit on MIDI channel 9, the GM percussion channel.
        // Without this fix, noise went to ch 3 and played as piano.
        var converter = new Sn76489MidiConverter();
        var seq = new Sequence(Sequence.PPQ, 480);
        var tracks = new Track[10];
        for (int i = 0; i < 10; i++)
            tracks[i] = seq.createTrack();

        // 1_11_1_0000 = 0xF0: latch ch3, volume=0 (loudest) → triggers NoteOn
        converter.convert(new VgmEvent(0, 0, new byte[] { (byte) 0xF0 }), tracks, CLOCK, 0);

        assertNull(findNoteOn(tracks[3]), "Noise must not go to MIDI ch 3");
        var noteOn = findNoteOn(tracks[9]);
        assertNotNull(noteOn, "Noise must use GM percussion channel 9");
        assertEquals(9, noteOn.getChannel());
        assertEquals(38, noteOn.getData1()); // GM snare
    }

    @Test
    void noteOn_generatesEvent() throws Exception
    {
        var converter = new Sn76489MidiConverter();
        var seq = new Sequence(Sequence.PPQ, 4410);
        var tracks = new Track[] { seq.createTrack(), seq.createTrack(), seq.createTrack(), seq.createTrack() };

        // Latch ch0 tone low nibble
        converter.convert(new VgmEvent(0, 0, new byte[] { (byte) 0x8C }), tracks, CLOCK, 0);
        // Data byte for tone high bits
        converter.convert(new VgmEvent(0, 0, new byte[] { 0x1A }), tracks, CLOCK, 0);
        // Volume = 5 → should trigger NoteOn
        converter.convert(new VgmEvent(0, 0, new byte[] { (byte) 0x95 }), tracks, CLOCK, 0);

        var noteOn = findNoteOn(tracks[0]);
        assertNotNull(noteOn, "Expected NoteOn");
        assertTrue(noteOn.getData1() >= 0 && noteOn.getData1() <= 127);
        assertTrue(noteOn.getData2() > 0);
    }

    private static ShortMessage findNoteOn(Track track)
    {
        for (int i = 0; i < track.size(); i++)
        {
            MidiEvent e = track.get(i);
            if (e.getMessage() instanceof ShortMessage sm && sm.getCommand() == ShortMessage.NOTE_ON)
            {
                return sm;
            }
        }
        return null;
    }

    private static ShortMessage findNoteOff(Track track)
    {
        for (int i = 0; i < track.size(); i++)
        {
            MidiEvent e = track.get(i);
            if (e.getMessage() instanceof ShortMessage sm && sm.getCommand() == ShortMessage.NOTE_OFF)
            {
                return sm;
            }
        }
        return null;
    }
}

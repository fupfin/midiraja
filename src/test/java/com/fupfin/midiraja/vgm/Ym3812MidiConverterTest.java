/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.vgm;

import static org.junit.jupiter.api.Assertions.*;

import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

import org.junit.jupiter.api.Test;

class Ym3812MidiConverterTest {

    private static final long CLOCK = 3_579_545L;

    private static VgmEvent opl2(int reg, int data) {
        return new VgmEvent(0, 14, new byte[]{(byte) reg, (byte) data});
    }

    private static Track[] makeTracks() throws Exception {
        var seq = new Sequence(Sequence.PPQ, 480);
        var tracks = new Track[15];
        for (int i = 0; i < 15; i++) tracks[i] = seq.createTrack();
        return tracks;
    }

    private static ShortMessage findFirst(Track track, int command) {
        for (int i = 0; i < track.size(); i++) {
            var msg = track.get(i).getMessage();
            if (msg instanceof ShortMessage sm && sm.getCommand() == command) return sm;
        }
        return null;
    }

    @Test
    void opl2Note_a4() {
        // f = fnum * clock / (72 * 2^(20-block))
        // fnum = 440 * 72 * 2^(20-block) / clock
        // block=4: fnum = 440 * 72 * 65536 / 3579545 ≈ 580
        int note = Ym3812MidiConverter.opl2Note(CLOCK, 580, 4);
        assertTrue(note >= 68 && note <= 70, "fnum=580, block=4 ≈ A4 (MIDI ~69), got " + note);
    }

    @Test
    void opl2Note_fnumZero_returnsMinusOne() {
        assertEquals(-1, Ym3812MidiConverter.opl2Note(CLOCK, 0, 3));
    }

    @Test
    void keyOn_producesNoteOn() throws Exception {
        var converter = new Ym3812MidiConverter();
        var tracks = makeTracks();

        // Set carrier TL for ch 0 (addr 0x43, value 10)
        converter.convert(opl2(0x43, 10), tracks, CLOCK, 0);
        // Set F-Number low for ch 0 (addr 0xA0)
        converter.convert(opl2(0xA0, 0x44), tracks, CLOCK, 0); // fnum low = 0x44
        // Key-on: bit5=1, block=4, fnum_hi=0x02 → 0x20 | (4<<2) | 0x02 = 0x32
        converter.convert(opl2(0xB0, 0x32), tracks, CLOCK, 1);

        var noteOn = findFirst(tracks[0], ShortMessage.NOTE_ON);
        assertNotNull(noteOn, "Key-on should produce NoteOn");
    }

    @Test
    void keyOff_producesNoteOff() throws Exception {
        var converter = new Ym3812MidiConverter();
        var tracks = makeTracks();

        converter.convert(opl2(0x43, 10), tracks, CLOCK, 0);
        converter.convert(opl2(0xA0, 0x44), tracks, CLOCK, 0);
        converter.convert(opl2(0xB0, 0x32), tracks, CLOCK, 1); // key-on
        converter.convert(opl2(0xB0, 0x12), tracks, CLOCK, 2); // key-off (bit5=0)

        var noteOff = findFirst(tracks[0], ShortMessage.NOTE_OFF);
        assertNotNull(noteOff, "Key-off should produce NoteOff");
    }

    @Test
    void connectionFM_selectsCorrectProgram() throws Exception {
        var converter = new Ym3812MidiConverter();
        var tracks = makeTracks();

        // Set connection=0, feedback=0 for ch 0 (0xC0 bit0=0)
        converter.convert(opl2(0xC0, 0x00), tracks, CLOCK, 0);
        // Set modulator TL=30 for ch 0 (addr 0x40)
        converter.convert(opl2(0x40, 30), tracks, CLOCK, 0);
        // Set carrier TL for ch 0
        converter.convert(opl2(0x43, 10), tracks, CLOCK, 0);
        // Set F-Number low
        converter.convert(opl2(0xA0, 0x44), tracks, CLOCK, 0);
        // Key-on
        converter.convert(opl2(0xB0, 0x32), tracks, CLOCK, 1);

        var pc = findFirst(tracks[0], ShortMessage.PROGRAM_CHANGE);
        assertNotNull(pc, "FM connection should emit Program Change");
        assertEquals(71, pc.getData1(), "connection=0 (FM) → Clarinet (71)");
    }

    @Test
    void connectionAM_selectsVibraphone() throws Exception {
        var converter = new Ym3812MidiConverter();
        var tracks = makeTracks();

        // Set connection=1 (0xC0 bit0=1)
        converter.convert(opl2(0xC0, 0x01), tracks, CLOCK, 0);
        // Set modulator TL=20 for ch 0 (addr 0x40)
        converter.convert(opl2(0x40, 20), tracks, CLOCK, 0);
        // Set carrier TL for ch 0
        converter.convert(opl2(0x43, 10), tracks, CLOCK, 0);
        // Set F-Number low
        converter.convert(opl2(0xA0, 0x44), tracks, CLOCK, 0);
        // Key-on
        converter.convert(opl2(0xB0, 0x32), tracks, CLOCK, 1);

        var pc = findFirst(tracks[0], ShortMessage.PROGRAM_CHANGE);
        assertNotNull(pc, "AM connection should emit Program Change");
        assertEquals(11, pc.getData1(), "connection=1 (AM) → Vibraphone (11)");
    }

    @Test
    void carrierTl_affectsVelocity() throws Exception {
        var converter = new Ym3812MidiConverter();
        var tracks = makeTracks();

        // Set carrier TL=0 (loudest) for ch 0 (addr 0x43)
        converter.convert(opl2(0x43, 0), tracks, CLOCK, 0);
        // Set F-Number low
        converter.convert(opl2(0xA0, 0x44), tracks, CLOCK, 0);
        // Key-on
        converter.convert(opl2(0xB0, 0x32), tracks, CLOCK, 1);

        var noteOn = findFirst(tracks[0], ShortMessage.NOTE_ON);
        assertNotNull(noteOn);
        assertEquals(127, noteOn.getData2(), "Carrier TL=0 (loudest) → velocity=127");
    }

    @Test
    void rhythmMode_bassDrum() throws Exception {
        var converter = new Ym3812MidiConverter();
        var tracks = makeTracks();

        // Write 0xBD with bit5=1 (rhythm mode) + bit4=1 (BD key-on)
        converter.convert(opl2(0xBD, 0x30), tracks, CLOCK, 1);

        var noteOn = findFirst(tracks[9], ShortMessage.NOTE_ON);
        assertNotNull(noteOn, "Rhythm BD should route to MIDI ch 9");
        assertEquals(36, noteOn.getData1(), "BD → GM 36 (Bass Drum 1)");
    }
}

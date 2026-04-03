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

class Ym2413MidiConverterTest {

    private static final long CLOCK = 3_579_545L;

    private static VgmEvent opll(int reg, int data) {
        return new VgmEvent(0, 13, new byte[]{(byte) reg, (byte) data});
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
    void opllNote_a4() {
        // f = fnum * clock / (72 * 2^(20-block))
        // A4 (440 Hz), block=4: fnum = 440 * 72 * 65536 / 3579545 ≈ 580
        int note = Ym2413MidiConverter.opllNote(CLOCK, 580, 4);
        assertTrue(note >= 68 && note <= 70, "fnum=580, block=4 ≈ A4 (MIDI ~69), got " + note);
    }

    @Test
    void opllNote_fnumZero_returnsMinusOne() {
        assertEquals(-1, Ym2413MidiConverter.opllNote(CLOCK, 0, 3));
    }

    @Test
    void keyOn_producesNoteOn_withPresetProgram() throws Exception {
        var converter = new Ym2413MidiConverter();
        var tracks = makeTracks();

        // Set instrument=4 (Flute), volume=2 for ch 0
        converter.convert(opll(0x30, 0x42), tracks, CLOCK, 0); // inst=4, vol=2
        // Set F-Number low for ch 0
        converter.convert(opll(0x10, 0x80), tracks, CLOCK, 0);
        // Key-on: sustain=0, key=1, block=3, fnum_hi=1 → 0x17
        converter.convert(opll(0x20, 0x17), tracks, CLOCK, 1);

        // OPLL ch 0 → MIDI ch 3
        var pc = findFirst(tracks[3], ShortMessage.PROGRAM_CHANGE);
        assertNotNull(pc, "Preset instrument should emit Program Change");
        assertEquals(73, pc.getData1(), "Preset 4 (Flute) → GM 73");

        var noteOn = findFirst(tracks[3], ShortMessage.NOTE_ON);
        assertNotNull(noteOn, "Key-on should produce NoteOn");
    }

    @Test
    void keyOff_producesNoteOff() throws Exception {
        var converter = new Ym2413MidiConverter();
        var tracks = makeTracks();

        converter.convert(opll(0x30, 0x42), tracks, CLOCK, 0);
        converter.convert(opll(0x10, 0x80), tracks, CLOCK, 0);
        converter.convert(opll(0x20, 0x17), tracks, CLOCK, 1); // key-on
        converter.convert(opll(0x20, 0x07), tracks, CLOCK, 2); // key-off (bit4=0)

        var noteOff = findFirst(tracks[3], ShortMessage.NOTE_OFF);
        assertNotNull(noteOff, "Key-off should produce NoteOff");
    }

    @Test
    void channel5_routesToMidiCh8() throws Exception {
        var converter = new Ym2413MidiConverter();
        var tracks = makeTracks();

        converter.convert(opll(0x35, 0x72), tracks, CLOCK, 0); // ch 5: inst=7 (Trumpet), vol=2
        converter.convert(opll(0x15, 0x80), tracks, CLOCK, 0);
        converter.convert(opll(0x25, 0x17), tracks, CLOCK, 1);

        // OPLL ch 5 → MIDI ch 8
        var pc = findFirst(tracks[8], ShortMessage.PROGRAM_CHANGE);
        assertNotNull(pc);
        assertEquals(56, pc.getData1(), "Preset 7 (Trumpet) → GM 56");
    }

    @Test
    void channel7_routesToMidiCh11() throws Exception {
        var converter = new Ym2413MidiConverter();
        var tracks = makeTracks();

        converter.convert(opll(0x37, 0x32), tracks, CLOCK, 0); // ch 7: inst=3 (Piano), vol=2
        converter.convert(opll(0x17, 0x80), tracks, CLOCK, 0);
        converter.convert(opll(0x27, 0x17), tracks, CLOCK, 1);

        // OPLL ch 7 → MIDI ch 11
        var noteOn = findFirst(tracks[11], ShortMessage.NOTE_ON);
        assertNotNull(noteOn, "OPLL ch 7 should route to MIDI ch 11");
    }

    @Test
    void rhythmMode_bassDrum_routesToDrumChannel() throws Exception {
        var converter = new Ym2413MidiConverter();
        var tracks = makeTracks();

        // Enable rhythm mode + bass drum key-on (bit 4)
        converter.convert(opll(0x0E, 0x30), tracks, CLOCK, 1); // rhythm=1, BD=1

        var noteOn = findFirst(tracks[9], ShortMessage.NOTE_ON);
        assertNotNull(noteOn, "Rhythm BD should route to MIDI ch 9");
        assertEquals(36, noteOn.getData1(), "BD → GM 36 (Bass Drum 1)");
    }

    @Test
    void rhythmMode_snareDrum() throws Exception {
        var converter = new Ym2413MidiConverter();
        var tracks = makeTracks();

        converter.convert(opll(0x0E, 0x28), tracks, CLOCK, 1); // rhythm=1, SD=1 (bit 3)

        var noteOn = findFirst(tracks[9], ShortMessage.NOTE_ON);
        assertNotNull(noteOn);
        assertEquals(38, noteOn.getData1(), "SD → GM 38 (Acoustic Snare)");
    }

    @Test
    void volume_affectsVelocity() throws Exception {
        var converter = new Ym2413MidiConverter();
        var tracks = makeTracks();

        // vol=0 (loudest) → velocity≈127
        converter.convert(opll(0x30, 0x30), tracks, CLOCK, 0); // inst=3, vol=0
        converter.convert(opll(0x10, 0x80), tracks, CLOCK, 0);
        converter.convert(opll(0x20, 0x17), tracks, CLOCK, 1);

        var noteOn = findFirst(tracks[3], ShortMessage.NOTE_ON);
        assertNotNull(noteOn);
        assertEquals(127, noteOn.getData2(), "OPLL vol=0 (loudest) → velocity=127");
    }
}

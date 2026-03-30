/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.vgm;

import static org.junit.jupiter.api.Assertions.*;

import javax.sound.midi.MidiEvent;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

import org.junit.jupiter.api.Test;

class HuC6280MidiConverterTest {

    private static final long CLOCK = 3_579_545L;

    private static VgmEvent pce(int reg, int data) {
        return new VgmEvent(0, 12, new byte[]{(byte) reg, (byte) data});
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
    void noteFrequency_a4() {
        // f = 3579545 / (32 * period). For A4 (440 Hz): period = 3579545 / (32*440) ≈ 254
        int note = HuC6280MidiConverter.huC6280Note(CLOCK, 254);
        assertEquals(69, note, "Period 254 ≈ A4 (MIDI 69)");
    }

    @Test
    void noteFrequency_periodZero_returnsMinusOne() {
        assertEquals(-1, HuC6280MidiConverter.huC6280Note(CLOCK, 0));
    }

    @Test
    void enableAndVolume_producesNoteOn() throws Exception {
        var converter = new HuC6280MidiConverter();
        var tracks = makeTracks();

        // Select ch 0, set frequency (period ≈ 254 → A4)
        converter.convert(pce(0x00, 0), tracks, CLOCK, 0);   // select ch 0
        converter.convert(pce(0x02, 254), tracks, CLOCK, 0);  // freq lo
        converter.convert(pce(0x03, 0), tracks, CLOCK, 0);    // freq hi
        // Enable + volume=20
        converter.convert(pce(0x04, 0x80 | 20), tracks, CLOCK, 1);

        var noteOn = findFirst(tracks[0], ShortMessage.NOTE_ON);
        assertNotNull(noteOn, "Enabled + vol > 0 must produce NoteOn");
        assertEquals(69, noteOn.getData1(), "A4 = MIDI 69");
    }

    @Test
    void disableChannel_producesNoteOff() throws Exception {
        var converter = new HuC6280MidiConverter();
        var tracks = makeTracks();

        converter.convert(pce(0x00, 0), tracks, CLOCK, 0);
        converter.convert(pce(0x02, 254), tracks, CLOCK, 0);
        converter.convert(pce(0x03, 0), tracks, CLOCK, 0);
        converter.convert(pce(0x04, 0x80 | 20), tracks, CLOCK, 1); // on
        converter.convert(pce(0x04, 0x00), tracks, CLOCK, 2);      // off (enable=0)

        var noteOff = findFirst(tracks[0], ShortMessage.NOTE_OFF);
        assertNotNull(noteOff, "Disabling channel must produce NoteOff");
    }

    @Test
    void channelSelect_routesToCorrectMidiChannel() throws Exception {
        var converter = new HuC6280MidiConverter();
        var tracks = makeTracks();

        converter.convert(pce(0x00, 3), tracks, CLOCK, 0);   // select ch 3
        converter.convert(pce(0x02, 254), tracks, CLOCK, 0);
        converter.convert(pce(0x03, 0), tracks, CLOCK, 0);
        converter.convert(pce(0x04, 0x80 | 20), tracks, CLOCK, 1);

        var noteOn = findFirst(tracks[3], ShortMessage.NOTE_ON);
        assertNotNull(noteOn, "Ch 3 should route to MIDI ch 3");
    }

    @Test
    void balance_emitsPan() throws Exception {
        var converter = new HuC6280MidiConverter();
        var tracks = makeTracks();

        converter.convert(pce(0x00, 0), tracks, CLOCK, 0);
        // L=15, R=0 → hard left
        converter.convert(pce(0x05, 0xF0), tracks, CLOCK, 0);
        converter.convert(pce(0x02, 254), tracks, CLOCK, 0);
        converter.convert(pce(0x03, 0), tracks, CLOCK, 0);
        converter.convert(pce(0x04, 0x80 | 20), tracks, CLOCK, 1);

        ShortMessage cc = null;
        for (int i = 0; i < tracks[0].size(); i++) {
            var msg = tracks[0].get(i).getMessage();
            if (msg instanceof ShortMessage sm
                    && sm.getCommand() == ShortMessage.CONTROL_CHANGE
                    && sm.getData1() == 10) {
                cc = sm;
                break;
            }
        }
        assertNotNull(cc, "Balance should emit CC10");
        assertEquals(0, cc.getData2(), "L=15, R=0 → pan=0 (hard left)");
    }

    @Test
    void frequencyChange_retriggersNote() throws Exception {
        var converter = new HuC6280MidiConverter();
        var tracks = makeTracks();

        converter.convert(pce(0x00, 0), tracks, CLOCK, 0);
        converter.convert(pce(0x02, 254), tracks, CLOCK, 0); // A4
        converter.convert(pce(0x03, 0), tracks, CLOCK, 0);
        converter.convert(pce(0x04, 0x80 | 20), tracks, CLOCK, 1); // NoteOn

        // Change frequency to a different note
        converter.convert(pce(0x02, 127), tracks, CLOCK, 2); // higher pitch

        // Should have NoteOff for old note + NoteOn for new note
        int noteOnCount = 0;
        for (int i = 0; i < tracks[0].size(); i++) {
            var msg = tracks[0].get(i).getMessage();
            if (msg instanceof ShortMessage sm && sm.getCommand() == ShortMessage.NOTE_ON) {
                noteOnCount++;
            }
        }
        assertEquals(2, noteOnCount, "Frequency change should retrigger (2 NoteOns)");
    }
}

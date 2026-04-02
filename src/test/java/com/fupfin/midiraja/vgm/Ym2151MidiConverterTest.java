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

class Ym2151MidiConverterTest {

    // Helper: chip=5 (YM2151), addr, data
    private static VgmEvent opm(int addr, int data) {
        return new VgmEvent(0, 5, new byte[]{(byte) addr, (byte) data});
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
    void keyOn_producesNoteOn_onCorrectChannel() throws Exception {
        var converter = new Ym2151MidiConverter(0); // ch offset=0
        var tracks = makeTracks();

        // Set algorithm+feedback for ch 0
        converter.convert(opm(0x20, 0x04), tracks, 0, 0); // alg=4, fb=0, LR=0
        // Set TL for carriers (op2=C1 at 0x70, op3=C2 at 0x78)
        converter.convert(opm(0x70, 0), tracks, 0, 0); // C1 TL=0
        converter.convert(opm(0x78, 0), tracks, 0, 0); // C2 TL=0
        // Set KC: octave=4, note=0xE (C) → MIDI note = 4*12 + 11 + 13 = 72
        converter.convert(opm(0x28, 0x4E), tracks, 0, 0);
        // Key-on: ch=0, all operators (bits 6-3 = 0xF → 0x78)
        converter.convert(opm(0x08, 0x78), tracks, 0, 1);

        var noteOn = findFirst(tracks[0], ShortMessage.NOTE_ON);
        assertNotNull(noteOn, "NoteOn should be emitted on MIDI ch 0");
        assertEquals(72, noteOn.getData1(), "KC 0x4E (oct=4, C) → MIDI note 72");
    }

    @Test
    void keyOn_ch3_routesToCorrectMidiChannel() throws Exception {
        var converter = new Ym2151MidiConverter(0);
        var tracks = makeTracks();

        converter.convert(opm(0x23, 0x04), tracks, 0, 0); // alg=4, fb=0, ch 3
        converter.convert(opm(0x73, 0), tracks, 0, 0);     // C1 TL=0, ch 3
        converter.convert(opm(0x7B, 0), tracks, 0, 0);     // C2 TL=0, ch 3
        converter.convert(opm(0x2B, 0x4E), tracks, 0, 0);  // KC, ch 3
        converter.convert(opm(0x08, 0x7B), tracks, 0, 1);  // key-on ch=3

        var noteOn = findFirst(tracks[3], ShortMessage.NOTE_ON);
        assertNotNull(noteOn, "NoteOn should be on MIDI ch 3");
    }

    @Test
    void midiChOffset_shiftsChannels() throws Exception {
        var converter = new Ym2151MidiConverter(3); // offset=3 (shared with YM2612)
        var tracks = makeTracks();

        converter.convert(opm(0x20, 0x04), tracks, 0, 0);
        converter.convert(opm(0x70, 0), tracks, 0, 0);
        converter.convert(opm(0x78, 0), tracks, 0, 0);
        converter.convert(opm(0x28, 0x4E), tracks, 0, 0);
        converter.convert(opm(0x08, 0x78), tracks, 0, 1);

        // ch 0 + offset 3 = MIDI ch 3
        var noteOn = findFirst(tracks[3], ShortMessage.NOTE_ON);
        assertNotNull(noteOn, "With offset=3, ch 0 maps to MIDI ch 3");
    }

    @Test
    void tl_affectsVelocity() throws Exception {
        var converter = new Ym2151MidiConverter(0);
        var tracks = makeTracks();

        converter.convert(opm(0x20, 0x04), tracks, 0, 0); // alg=4, fb=0
        // Set carrier TL=25 for both C1 and C2
        converter.convert(opm(0x70, 25), tracks, 0, 0);
        converter.convert(opm(0x78, 25), tracks, 0, 0);
        converter.convert(opm(0x28, 0x4E), tracks, 0, 0);
        converter.convert(opm(0x08, 0x78), tracks, 0, 1);

        var noteOn = findFirst(tracks[0], ShortMessage.NOTE_ON);
        assertNotNull(noteOn);
        // Same formula as YM2612: TL=25, fb=0, REF_TL=20 → velocity≈82
        assertEquals(82, noteOn.getData2(), "TL=25 should give same velocity as YM2612");
    }

    @Test
    void programChange_emittedByConverter() throws Exception {
        // Stable FM converters (YM2151) emit per-note Program Change.
        var converter = new Ym2151MidiConverter(0);
        var tracks = makeTracks();

        converter.convert(opm(0x20, 0x34), tracks, 0, 0); // fb=6, alg=4
        converter.convert(opm(0x70, 0), tracks, 0, 0);
        converter.convert(opm(0x78, 0), tracks, 0, 0);
        converter.convert(opm(0x28, 0x4E), tracks, 0, 0);
        converter.convert(opm(0x08, 0x78), tracks, 0, 1);

        var noteOn = findFirst(tracks[0], ShortMessage.NOTE_ON);
        assertNotNull(noteOn, "NoteOn must be emitted");
        assertNotNull(findFirst(tracks[0], ShortMessage.PROGRAM_CHANGE),
                "Stable FM converters must emit Program Change per note");
    }

    @Test
    void pan_lrMask_emitsCc10() throws Exception {
        var converter = new Ym2151MidiConverter(0);
        var tracks = makeTracks();

        // Register 0x20: bits 7-6=LR. LR=2 (L only) = 0x80, alg=4 → 0x80|0x04=0x84
        converter.convert(opm(0x20, 0x84), tracks, 0, 0);
        converter.convert(opm(0x70, 0), tracks, 0, 0);
        converter.convert(opm(0x78, 0), tracks, 0, 0);
        converter.convert(opm(0x28, 0x4E), tracks, 0, 0);
        converter.convert(opm(0x08, 0x78), tracks, 0, 1);

        var cc = findFirst(tracks[0], ShortMessage.CONTROL_CHANGE);
        assertNotNull(cc);
        assertEquals(10, cc.getData1(), "CC10 = pan");
        assertEquals(0, cc.getData2(), "LR=2 (L only) → pan=0 (hard left)");
    }

    @Test
    void keyOff_producesNoteOff() throws Exception {
        var converter = new Ym2151MidiConverter(0);
        var tracks = makeTracks();

        converter.convert(opm(0x20, 0x04), tracks, 0, 0); // alg=4
        converter.convert(opm(0x70, 0), tracks, 0, 0);     // C1 TL=0
        converter.convert(opm(0x78, 0), tracks, 0, 0);     // C2 TL=0
        converter.convert(opm(0x28, 0x4E), tracks, 0, 0);  // KC oct=4, C
        converter.convert(opm(0x08, 0x78), tracks, 0, 1);  // key-on ch=0
        converter.convert(opm(0x08, 0x00), tracks, 0, 2);  // key-off ch=0 (operator mask=0)

        var noteOff = findFirst(tracks[0], ShortMessage.NOTE_OFF);
        assertNotNull(noteOff, "Key-off (operator mask=0) must produce NoteOff");
        assertEquals(72, noteOff.getData1(), "NoteOff note must match the active note");
    }

    @Test
    void invalidKcNoteCode_suppressesNote() throws Exception {
        var converter = new Ym2151MidiConverter(0);
        var tracks = makeTracks();

        converter.convert(opm(0x20, 0x04), tracks, 0, 0); // alg=4
        converter.convert(opm(0x70, 0), tracks, 0, 0);     // C1 TL=0
        converter.convert(opm(0x78, 0), tracks, 0, 0);     // C2 TL=0
        // KC with note code 3 (invalid — KC_SEMITONE[3] == -1)
        converter.convert(opm(0x28, 0x43), tracks, 0, 0);
        converter.convert(opm(0x08, 0x78), tracks, 0, 1);  // key-on ch=0

        var noteOn = findFirst(tracks[0], ShortMessage.NOTE_ON);
        assertNull(noteOn, "Invalid KC note code 3 should suppress NoteOn");
    }

    @Test
    void kcNote_cSharp_octave3() throws Exception {
        var converter = new Ym2151MidiConverter(0);
        var tracks = makeTracks();

        converter.convert(opm(0x20, 0x04), tracks, 0, 0);
        converter.convert(opm(0x70, 0), tracks, 0, 0);
        converter.convert(opm(0x78, 0), tracks, 0, 0);
        // KC: octave=3, note=0x0 (C#) → MIDI note = 3*12 + 0 + 13 = 49
        converter.convert(opm(0x28, 0x30), tracks, 0, 0);
        converter.convert(opm(0x08, 0x78), tracks, 0, 1);

        var noteOn = findFirst(tracks[0], ShortMessage.NOTE_ON);
        assertNotNull(noteOn);
        assertEquals(49, noteOn.getData1(), "KC oct=3, note=C# → MIDI 49");
    }
}

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

class Ym2612MidiConverterTest {

    private static final long CLOCK = 7_670_453L;

    // Helper: chip=1 (port0), addr, data
    private static VgmEvent port0(int addr, int data) {
        return new VgmEvent(0, 1, new byte[]{(byte) addr, (byte) data});
    }

    // Helper: chip=2 (port1), addr, data
    private static VgmEvent port1(int addr, int data) {
        return new VgmEvent(0, 2, new byte[]{(byte) addr, (byte) data});
    }

    private static Track[] makeTracks() throws Exception {
        var seq = new Sequence(Sequence.PPQ, 480);
        var tracks = new Track[10];
        for (int i = 0; i < 10; i++) tracks[i] = seq.createTrack();
        return tracks;
    }

    private static ShortMessage findFirst(Track track, int command) {
        for (int i = 0; i < track.size(); i++) {
            MidiEvent e = track.get(i);
            if (e.getMessage() instanceof ShortMessage sm && sm.getCommand() == command) {
                return sm;
            }
        }
        return null;
    }

    @Test
    void algorithm7_emitsProgramChange_beforeNoteOn() throws Exception {
        // Algorithm 7 (additive synthesis) → Hammond Organ (GM 16)
        // Register 0xB0: feedback=0, algorithm=7 → 0b000_111 = 0x07
        var converter = new Ym2612MidiConverter();
        var tracks = makeTracks();

        // Set algorithm=7, feedback=0 on ch0 (port0)
        converter.convert(port0(0xB0, 0x07), tracks, CLOCK, 0);
        // Set FNum: high byte (block=4, fnum_high=2) → 0xA4 ch0
        converter.convert(port0(0xA4, 0x22), tracks, CLOCK, 0);
        // FNum low byte → 0xA0 ch0
        converter.convert(port0(0xA0, 0x6A), tracks, CLOCK, 0);
        // Key-on ch0 (all operators) → 0x28 data=0xF0
        converter.convert(port0(0x28, 0xF0), tracks, CLOCK, 1);

        // MIDI ch 3 (YM2612 ch0 + offset 3)
        var pc = findFirst(tracks[3], ShortMessage.PROGRAM_CHANGE);
        assertNotNull(pc, "Program Change must be emitted for algorithm 7");
        assertEquals(62, pc.getData1(), "Algorithm 7 → GM 62 (Synth Brass 1) — fully additive, avoids church organ character");
    }

    @Test
    void algorithm0_emitsBassProgram() throws Exception {
        // Algorithm 0 (fully serial FM, deep modulation) → Electric Bass (GM 33)
        var converter = new Ym2612MidiConverter();
        var tracks = makeTracks();

        converter.convert(port0(0xB0, 0x00), tracks, CLOCK, 0); // alg=0, fb=0
        converter.convert(port0(0xA4, 0x22), tracks, CLOCK, 0);
        converter.convert(port0(0xA0, 0x6A), tracks, CLOCK, 0);
        converter.convert(port0(0x28, 0xF0), tracks, CLOCK, 1);

        var pc = findFirst(tracks[3], ShortMessage.PROGRAM_CHANGE);
        assertNotNull(pc);
        assertEquals(33, pc.getData1(), "Algorithm 0 → GM 33 (Electric Bass Finger)");
    }

    @Test
    void highFeedback_overridesProgram() throws Exception {
        // feedback=6 with algorithm=4 → Square Lead (GM 80)
        // Register 0xB0: feedback=6, algorithm=4 → 0b110_100 = 0x34
        var converter = new Ym2612MidiConverter();
        var tracks = makeTracks();

        converter.convert(port0(0xB0, 0x34), tracks, CLOCK, 0); // alg=4, fb=6
        converter.convert(port0(0xA4, 0x22), tracks, CLOCK, 0);
        converter.convert(port0(0xA0, 0x6A), tracks, CLOCK, 0);
        converter.convert(port0(0x28, 0xF0), tracks, CLOCK, 1);

        var pc = findFirst(tracks[3], ShortMessage.PROGRAM_CHANGE);
        assertNotNull(pc);
        assertEquals(80, pc.getData1(), "High feedback (fb=6) → GM 80 (Square Lead)");
    }

    @Test
    void programChange_notDuplicated_whenAlgorithmUnchanged() throws Exception {
        // Two consecutive key-ons with same algorithm → Program Change only once
        var converter = new Ym2612MidiConverter();
        var tracks = makeTracks();

        converter.convert(port0(0xB0, 0x07), tracks, CLOCK, 0);
        converter.convert(port0(0xA4, 0x22), tracks, CLOCK, 0);
        converter.convert(port0(0xA0, 0x6A), tracks, CLOCK, 0);
        converter.convert(port0(0x28, 0xF0), tracks, CLOCK, 1); // key-on
        converter.convert(port0(0x28, 0x00), tracks, CLOCK, 2); // key-off
        converter.convert(port0(0x28, 0xF0), tracks, CLOCK, 3); // key-on again

        long pcCount = 0;
        for (int i = 0; i < tracks[3].size(); i++) {
            if (tracks[3].get(i).getMessage() instanceof ShortMessage sm
                    && sm.getCommand() == ShortMessage.PROGRAM_CHANGE) {
                pcCount++;
            }
        }
        assertEquals(1, pcCount, "Program Change must not be duplicated if algorithm is unchanged");
    }

    @Test
    void port1_channel_usesCorrectMidiChannel() throws Exception {
        // Port 1, ch0 → internal ch3 → MIDI ch6 (offset 3)
        var converter = new Ym2612MidiConverter();
        var tracks = makeTracks();

        converter.convert(port1(0xB0, 0x07), tracks, CLOCK, 0); // port1 ch0 = internal ch3
        converter.convert(port1(0xA4, 0x22), tracks, CLOCK, 0);
        converter.convert(port1(0xA0, 0x6A), tracks, CLOCK, 0);
        converter.convert(port1(0x28, 0xF4), tracks, CLOCK, 1); // key-on port1 ch0 = chSelect=4

        var pc = findFirst(tracks[6], ShortMessage.PROGRAM_CHANGE);
        assertNotNull(pc, "Port 1 ch0 must emit Program Change on MIDI ch 6");
    }
}

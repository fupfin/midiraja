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

    private static ShortMessage findCC(Track track, int controller) {
        for (int i = 0; i < track.size(); i++) {
            MidiEvent e = track.get(i);
            if (e.getMessage() instanceof ShortMessage sm
                    && sm.getCommand() == ShortMessage.CONTROL_CHANGE
                    && sm.getData1() == controller) {
                return sm;
            }
        }
        return null;
    }

    @Test
    void algorithm7_emitsNoteOn() throws Exception {
        var converter = new Ym2612MidiConverter();
        var tracks = makeTracks();

        // Set all operator TL to 20 (audible) — default 127 triggers silent carrier filter
        for (int reg = 0x40; reg <= 0x4C; reg += 4)
            converter.convert(port0(reg, 20), tracks, CLOCK, 0);
        converter.convert(port0(0xB0, 0x07), tracks, CLOCK, 0);
        converter.convert(port0(0xA4, 0x22), tracks, CLOCK, 0);
        converter.convert(port0(0xA0, 0x6A), tracks, CLOCK, 0);
        converter.convert(port0(0x28, 0xF0), tracks, CLOCK, 1);

        var noteOn = findFirst(tracks[3], ShortMessage.NOTE_ON);
        assertNotNull(noteOn, "NoteOn must be emitted on MIDI ch 3");
        assertNotNull(findFirst(tracks[3], ShortMessage.PROGRAM_CHANGE),
                "Stable FM converters must emit Program Change per note");
    }

    @Test
    void algorithm0_emitsNoteOn() throws Exception {
        // Algorithm 0 (fully serial FM) — verify NoteOn is emitted; no Program Change from converter.
        var converter = new Ym2612MidiConverter();
        var tracks = makeTracks();

        converter.convert(port0(0xB0, 0x00), tracks, CLOCK, 0);
        converter.convert(port0(0x40, 30), tracks, CLOCK, 0);
        converter.convert(port0(0x44, 30), tracks, CLOCK, 0);
        converter.convert(port0(0x48, 30), tracks, CLOCK, 0);
        converter.convert(port0(0x4C, 20), tracks, CLOCK, 0); // carrier TL (audible)
        converter.convert(port0(0xA4, 0x22), tracks, CLOCK, 0);
        converter.convert(port0(0xA0, 0x6A), tracks, CLOCK, 0);
        converter.convert(port0(0x28, 0xF0), tracks, CLOCK, 1);

        var noteOn = findFirst(tracks[3], ShortMessage.NOTE_ON);
        assertNotNull(noteOn, "NoteOn must be emitted for algorithm 0");
        assertNotNull(findFirst(tracks[3], ShortMessage.PROGRAM_CHANGE),
                "Stable FM converters must emit Program Change per note");
    }

    @Test
    void highFeedback_emitsNoteOn() throws Exception {
        var converter = new Ym2612MidiConverter();
        var tracks = makeTracks();

        for (int reg = 0x40; reg <= 0x4C; reg += 4)
            converter.convert(port0(reg, 20), tracks, CLOCK, 0);
        converter.convert(port0(0xB0, 0x34), tracks, CLOCK, 0); // alg=4, fb=6
        converter.convert(port0(0xA4, 0x22), tracks, CLOCK, 0);
        converter.convert(port0(0xA0, 0x6A), tracks, CLOCK, 0);
        converter.convert(port0(0x28, 0xF0), tracks, CLOCK, 1);

        var noteOn = findFirst(tracks[3], ShortMessage.NOTE_ON);
        assertNotNull(noteOn, "NoteOn must be emitted for high-feedback algorithm");
        assertNotNull(findFirst(tracks[3], ShortMessage.PROGRAM_CHANGE),
                "Stable FM converters must emit Program Change per note");
    }

    @Test
    void programChange_notDuplicated() throws Exception {
        var converter = new Ym2612MidiConverter();
        var tracks = makeTracks();

        for (int reg = 0x40; reg <= 0x4C; reg += 4)
            converter.convert(port0(reg, 20), tracks, CLOCK, 0);
        converter.convert(port0(0xB0, 0x07), tracks, CLOCK, 0);
        converter.convert(port0(0xA4, 0x22), tracks, CLOCK, 0);
        converter.convert(port0(0xA0, 0x6A), tracks, CLOCK, 0);
        converter.convert(port0(0x28, 0xF0), tracks, CLOCK, 1); // key-on
        converter.convert(port0(0x28, 0x00), tracks, CLOCK, 2); // key-off
        converter.convert(port0(0x28, 0xF0), tracks, CLOCK, 3); // key-on again (same alg)

        long pcCount = 0;
        for (int i = 0; i < tracks[3].size(); i++) {
            if (tracks[3].get(i).getMessage() instanceof ShortMessage sm
                    && sm.getCommand() == ShortMessage.PROGRAM_CHANGE) {
                pcCount++;
            }
        }
        assertEquals(1, pcCount, "Same algorithm → Program Change only once");
    }

    @Test
    void panRegister_leftOnly_emitsCC10_0() throws Exception {
        var converter = new Ym2612MidiConverter();
        var tracks = makeTracks();

        for (int reg = 0x40; reg <= 0x4C; reg += 4)
            converter.convert(port0(reg, 20), tracks, CLOCK, 0);
        converter.convert(port0(0xB4, 0x80), tracks, CLOCK, 0); // L=1, R=0
        converter.convert(port0(0xB0, 0x07), tracks, CLOCK, 0);
        converter.convert(port0(0xA4, 0x22), tracks, CLOCK, 0);
        converter.convert(port0(0xA0, 0x6A), tracks, CLOCK, 0);
        converter.convert(port0(0x28, 0xF0), tracks, CLOCK, 1);

        ShortMessage pan = findCC(tracks[3], 10);
        assertNotNull(pan, "CC10 (pan) must be emitted");
        assertEquals(0, pan.getData2(), "L-only → CC10=0 (hard left)");
    }

    @Test
    void panRegister_rightOnly_emitsCC10_127() throws Exception {
        var converter = new Ym2612MidiConverter();
        var tracks = makeTracks();

        for (int reg = 0x40; reg <= 0x4C; reg += 4)
            converter.convert(port0(reg, 20), tracks, CLOCK, 0);
        converter.convert(port0(0xB4, 0x40), tracks, CLOCK, 0); // L=0, R=1
        converter.convert(port0(0xB0, 0x07), tracks, CLOCK, 0);
        converter.convert(port0(0xA4, 0x22), tracks, CLOCK, 0);
        converter.convert(port0(0xA0, 0x6A), tracks, CLOCK, 0);
        converter.convert(port0(0x28, 0xF0), tracks, CLOCK, 1);

        ShortMessage pan = findCC(tracks[3], 10);
        assertNotNull(pan, "CC10 (pan) must be emitted");
        assertEquals(127, pan.getData2(), "R-only → CC10=127 (hard right)");
    }

    @Test
    void panRegister_default_center_emitsCC10_64() throws Exception {
        var converter = new Ym2612MidiConverter();
        var tracks = makeTracks();

        for (int reg = 0x40; reg <= 0x4C; reg += 4)
            converter.convert(port0(reg, 20), tracks, CLOCK, 0);
        converter.convert(port0(0xB0, 0x07), tracks, CLOCK, 0);
        converter.convert(port0(0xA4, 0x22), tracks, CLOCK, 0);
        converter.convert(port0(0xA0, 0x6A), tracks, CLOCK, 0);
        converter.convert(port0(0x28, 0xF0), tracks, CLOCK, 1);

        ShortMessage pan = findCC(tracks[3], 10);
        assertNotNull(pan, "CC10 must be emitted even with default L+R pan");
        assertEquals(64, pan.getData2(), "L+R (default) → CC10=64 (center)");
    }

    @Test
    void tl_fullOutput_producesMaxVelocity() throws Exception {
        // alg=4: carriers are op2 (0x48) and op3 (0x4C). TL=0 → amplitude=1.0 → velocity=127.
        var converter = new Ym2612MidiConverter();
        var tracks = makeTracks();

        converter.convert(port0(0xB0, 0x04), tracks, CLOCK, 0); // alg=4, fb=0
        converter.convert(port0(0x48, 0x00), tracks, CLOCK, 0); // S2 (op2) TL=0
        converter.convert(port0(0x4C, 0x00), tracks, CLOCK, 0); // S4 (op3) TL=0
        converter.convert(port0(0xA4, 0x22), tracks, CLOCK, 0);
        converter.convert(port0(0xA0, 0x6A), tracks, CLOCK, 0);
        converter.convert(port0(0x28, 0xF0), tracks, CLOCK, 1);

        var noteOn = findFirst(tracks[3], ShortMessage.NOTE_ON);
        assertNotNull(noteOn);
        assertEquals(127, noteOn.getData2(), "TL=0 → velocity=127 (full output)");
    }

    @Test
    void tl_attenuated_producesLowerVelocity() throws Exception {
        // alg=4: TL=25 on both carriers → −18.75 dB → amplitude≈0.1155 → velocity≈15.
        var converter = new Ym2612MidiConverter();
        var tracks = makeTracks();

        converter.convert(port0(0xB0, 0x04), tracks, CLOCK, 0); // alg=4, fb=0
        converter.convert(port0(0x48, 25), tracks, CLOCK, 0);   // S2 (op2) TL=25
        converter.convert(port0(0x4C, 25), tracks, CLOCK, 0);   // S4 (op3) TL=25
        converter.convert(port0(0xA4, 0x22), tracks, CLOCK, 0);
        converter.convert(port0(0xA0, 0x6A), tracks, CLOCK, 0);
        converter.convert(port0(0x28, 0xF0), tracks, CLOCK, 1);

        var noteOn = findFirst(tracks[3], ShortMessage.NOTE_ON);
        assertNotNull(noteOn);
        // TL=25, fb=0: tlDb=(25−20)×0.75=3.75 dB, fbDb=0 → 10^(−3.75/20) × 127 ≈ 82
        assertEquals(82, noteOn.getData2(), "TL=25,fb=0 → velocity≈82 (−3.75 dB below REF_TL=20)");
    }

    @Test
    void highFeedback_reducesVelocity() throws Exception {
        // alg=4, fb=7 (max), TL=18: tlDb=(18−20)×0.75=−1.5 dB, fbDb=7×0.375=2.625 dB → total 1.125 dB
        // → 10^(−1.125/20) × 127 ≈ 112. fb=0 at same TL would give velocity=127 (capped).
        var converter = new Ym2612MidiConverter();
        var tracks = makeTracks();

        converter.convert(port0(0xB0, 0x38), tracks, CLOCK, 0); // alg=4, fb=7 (0b111_100=0x38)
        converter.convert(port0(0x48, 18), tracks, CLOCK, 0);   // S2 (op2) TL=18
        converter.convert(port0(0x4C, 18), tracks, CLOCK, 0);   // S4 (op3) TL=18
        converter.convert(port0(0xA4, 0x22), tracks, CLOCK, 0);
        converter.convert(port0(0xA0, 0x6A), tracks, CLOCK, 0);
        converter.convert(port0(0x28, 0xF0), tracks, CLOCK, 1);

        var noteOn = findFirst(tracks[3], ShortMessage.NOTE_ON);
        assertNotNull(noteOn);
        // TL=18, fb=7: tlDb=−1.5 dB, fbDb=2.625 dB → net 1.125 dB → 10^(−1.125/20) × 127 ≈ 112
        assertEquals(112, noteOn.getData2(), "TL=18,fb=7 → velocity≈112 (fb applies −2.625 dB correction)");
    }

    @Test
    void fnumZero_suppressesNote() throws Exception {
        var converter = new Ym2612MidiConverter();
        var tracks = makeTracks();

        converter.convert(port0(0xB0, 0x07), tracks, CLOCK, 0); // alg=7
        // fnum=0: high byte=0x00 (block=0, fnum_high=0), low byte=0x00
        converter.convert(port0(0xA4, 0x00), tracks, CLOCK, 0);
        converter.convert(port0(0xA0, 0x00), tracks, CLOCK, 0);
        converter.convert(port0(0x28, 0xF0), tracks, CLOCK, 1); // key-on ch0

        var noteOn = findFirst(tracks[3], ShortMessage.NOTE_ON);
        assertNull(noteOn, "fnum=0 should suppress NoteOn");
    }

    @Test
    void port1_channel_usesCorrectMidiChannel() throws Exception {
        var converter = new Ym2612MidiConverter();
        var tracks = makeTracks();

        // Set TL for port1 ch0 (internal ch3): op regs at port1 offsets
        for (int reg = 0x40; reg <= 0x4C; reg += 4)
            converter.convert(port1(reg, 20), tracks, CLOCK, 0);
        converter.convert(port1(0xB0, 0x07), tracks, CLOCK, 0); // port1 ch0 = internal ch3
        converter.convert(port1(0xA4, 0x22), tracks, CLOCK, 0);
        converter.convert(port1(0xA0, 0x6A), tracks, CLOCK, 0);
        converter.convert(port1(0x28, 0xF4), tracks, CLOCK, 1); // key-on port1 ch0 = chSelect=4

        var noteOn = findFirst(tracks[6], ShortMessage.NOTE_ON);
        assertNotNull(noteOn, "Port 1 ch0 must emit NoteOn on MIDI ch 6");
    }
}

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

class Ay8910MidiConverterTest {

    // MSX AY8910 clock (1.789772 MHz)
    private static final long CLOCK = 1_789_772L;

    private static VgmEvent reg(int r, int v) {
        return new VgmEvent(0, 3, new byte[]{(byte) r, (byte) v});
    }

    private static Track[] makeTracks() throws Exception {
        var seq = new Sequence(Sequence.PPQ, 480);
        var tracks = new Track[15];
        for (int i = 0; i < 15; i++) tracks[i] = seq.createTrack();
        return tracks;
    }

    private static ShortMessage findFirst(Track track, int command) {
        for (int i = 0; i < track.size(); i++) {
            if (track.get(i).getMessage() instanceof ShortMessage sm
                    && sm.getCommand() == command) {
                return sm;
            }
        }
        return null;
    }

    @Test
    void ay8910Note_middleC() {
        // N=255 → f = 1789772 / (16 × 255) ≈ 438.7 Hz → MIDI 69 (A4)
        // N=428 → f = 1789772 / (16 × 428) ≈ 261.5 Hz → MIDI 60 (middle C)
        assertEquals(60, Ay8910MidiConverter.ay8910Note(CLOCK, 428), 1);
    }

    @Test
    void ay8910Note_zeroN_returnsMinusOne() {
        assertEquals(-1, Ay8910MidiConverter.ay8910Note(CLOCK, 0));
    }

    @Test
    void volumeWrite_triggersNoteOn_onChannelA() throws Exception {
        var converter = new Ay8910MidiConverter();
        var tracks = makeTracks();

        // Set period for ch A: N=255 → A4
        converter.convert(reg(0, 0xFF), tracks, CLOCK, 0); // R0 fine
        converter.convert(reg(1, 0x00), tracks, CLOCK, 0); // R1 coarse
        // Set volume ch A = 10 → NoteOn
        converter.convert(reg(8, 10), tracks, CLOCK, 1);

        assertNotNull(findFirst(tracks[0], ShortMessage.NOTE_ON), "Expected NoteOn on ch 0");
    }

    @Test
    void volumeZero_triggersNoteOff() throws Exception {
        var converter = new Ay8910MidiConverter();
        var tracks = makeTracks();

        converter.convert(reg(0, 0xFF), tracks, CLOCK, 0);
        converter.convert(reg(1, 0x00), tracks, CLOCK, 0);
        converter.convert(reg(8, 10), tracks, CLOCK, 1);  // on
        converter.convert(reg(8, 0), tracks, CLOCK, 2);   // off

        assertNotNull(findFirst(tracks[0], ShortMessage.NOTE_OFF), "Expected NoteOff on ch 0");
    }

    @Test
    void mixer_toneDisable_triggersNoteOff() throws Exception {
        var converter = new Ay8910MidiConverter();
        var tracks = makeTracks();

        converter.convert(reg(0, 0xFF), tracks, CLOCK, 0);
        converter.convert(reg(1, 0x00), tracks, CLOCK, 0);
        converter.convert(reg(8, 10), tracks, CLOCK, 1);

        // R7 = 0b111001 → tone disabled for ch A (bit0=1), tone enabled B/C
        converter.convert(reg(7, 0b111001), tracks, CLOCK, 2);

        assertNotNull(findFirst(tracks[0], ShortMessage.NOTE_OFF),
                "Tone disable must produce NoteOff on ch 0");
    }

    @Test
    void noiseEnable_emitsDrumNoteOnChannel9() throws Exception {
        var converter = new Ay8910MidiConverter();
        var tracks = makeTracks();

        converter.convert(reg(8, 10), tracks, CLOCK, 0); // ch A volume > 0
        // R7 = 0b110110 → noise enabled for ch A (bit3=0), tone disabled
        converter.convert(reg(7, 0b110110), tracks, CLOCK, 1);

        assertNotNull(findFirst(tracks[9], ShortMessage.NOTE_ON),
                "Noise must emit NoteOn on MIDI ch 9");
    }

    @Test
    void noiseNote_highFreq_returnsClosedHiHat() {
        // period ≤ 12 → closed hi-hat (42)
        assertEquals(42, Ay8910MidiConverter.noiseNote(0));
        assertEquals(42, Ay8910MidiConverter.noiseNote(6));
        assertEquals(42, Ay8910MidiConverter.noiseNote(12));
    }

    @Test
    void noiseNote_coarseNoise_returnsOpenHiHat() {
        // period ≥ 13 → open hi-hat (46); no snare — AY8910 noise has no drum body/crack
        assertEquals(46, Ay8910MidiConverter.noiseNote(13));
        assertEquals(46, Ay8910MidiConverter.noiseNote(31));
    }

    @Test
    void noiseVelocity_reflectsChannelVolume() throws Exception {
        var converter = new Ay8910MidiConverter();
        var tracks = makeTracks();

        // ch A volume = 8 with noise enabled
        converter.convert(reg(8, 8), tracks, CLOCK, 0);
        converter.convert(reg(7, 0b110110), tracks, CLOCK, 1); // noise on ch A

        var noteOn = findFirst(tracks[9], ShortMessage.NOTE_ON);
        assertNotNull(noteOn, "Expected NoteOn on ch 9");
        // toVelocity(8) = round(round(sqrt(8/15)*127) * PSG_CC7_GAIN) = round(93*0.580) = 54
        assertEquals(54, noteOn.getData2(), "Noise velocity must reflect channel volume");
    }

    @Test
    void envelopeMode_triggersNoteOn_withReducedVelocity() throws Exception {
        // R8 bit4=1 means envelope mode → effective vol determined by R13 shape
        // Default envelopeShape=0 (decay) → envelopeEffectiveVol()=7 → velocity=59
        var converter = new Ay8910MidiConverter();
        var tracks = makeTracks();

        converter.convert(reg(0, 0xFF), tracks, CLOCK, 0);
        converter.convert(reg(1, 0x00), tracks, CLOCK, 0);
        converter.convert(reg(8, 0x10), tracks, CLOCK, 1); // envelope mode

        var noteOn = findFirst(tracks[0], ShortMessage.NOTE_ON);
        assertNotNull(noteOn, "Envelope mode must trigger NoteOn");
        // Default shape=0 (decay): effective vol=7 → CC7 velocity=round(sqrt(7/15)*127)=87
        ShortMessage cc7 = null;
        for (int i = 0; i < tracks[0].size(); i++) {
            if (tracks[0].get(i).getMessage() instanceof ShortMessage sm
                    && sm.getCommand() == ShortMessage.CONTROL_CHANGE && sm.getData1() == 7) {
                cc7 = sm; break;
            }
        }
        assertNotNull(cc7, "CC7 must be sent before NoteOn");
        // toVelocity(7) = round(round(sqrt(7/15)*127) * PSG_CC7_GAIN) = round(87*0.580) = 50
        assertEquals(50, cc7.getData2(), "Decay envelope (R13=0) → effective vol=7 → CC7=50");
    }

    @Test
    void envelopeShape13_sustainedHigh_usesHigherVelocity() throws Exception {
        // R13=13 (single rise, hold at max) → sustained amplitude → effective vol=11
        var converter = new Ay8910MidiConverter();
        var tracks = makeTracks();

        converter.convert(reg(0, 0xFF), tracks, CLOCK, 0);
        converter.convert(reg(1, 0x00), tracks, CLOCK, 0);
        converter.convert(new VgmEvent(0, 3, new byte[]{13, 13}), tracks, CLOCK, 0); // R13=13
        converter.convert(reg(8, 0x10), tracks, CLOCK, 1); // envelope mode

        ShortMessage cc7 = null;
        for (int i = 0; i < tracks[0].size(); i++) {
            if (tracks[0].get(i).getMessage() instanceof ShortMessage sm
                    && sm.getCommand() == ShortMessage.CONTROL_CHANGE && sm.getData1() == 7) {
                cc7 = sm; break;
            }
        }
        assertNotNull(cc7);
        // toVelocity(11) = round(round(sqrt(11/15)*127) * PSG_CC7_GAIN) = round(109*0.580) = 63
        assertEquals(63, cc7.getData2(), "Sustained envelope (R13=13) → effective vol=11 → CC7=63");
    }

    @Test
    void pitchChange_whilePlaying_retriggersNote() throws Exception {
        var converter = new Ay8910MidiConverter();
        var tracks = makeTracks();

        // Start with N=255 (A4)
        converter.convert(reg(0, 0xFF), tracks, CLOCK, 0);
        converter.convert(reg(1, 0x00), tracks, CLOCK, 0);
        converter.convert(reg(8, 10), tracks, CLOCK, 1);

        // Change to N=428 (middle C) via coarse write
        converter.convert(reg(0, 0xAC), tracks, CLOCK, 2); // 428 = 0x1AC → fine=0xAC
        converter.convert(reg(1, 0x01), tracks, CLOCK, 2); // coarse=0x01 → triggers retrigger

        long noteOnCount = 0;
        for (int i = 0; i < tracks[0].size(); i++) {
            if (tracks[0].get(i).getMessage() instanceof ShortMessage sm
                    && sm.getCommand() == ShortMessage.NOTE_ON) noteOnCount++;
        }
        // R0 (fine) changes period 0xFF→0xAC → intermediate note → retrigger
        // R1 (coarse) changes period 0x0AC→0x1AC (MIDI 60) → retrigger
        // Total: initial NoteOn + 2 retriggers = 3
        assertEquals(3, noteOnCount, "Both fine and coarse register writes trigger retrigger");
    }
}

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

class Nes2A03MidiConverterTest
{

    private static final long CLOCK = 1_789_773L; // NTSC NES CPU clock

    private static VgmEvent nes(int reg, int data)
    {
        return new VgmEvent(0, 17, new byte[] { (byte) reg, (byte) data });
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
    void pulseNote_a4()
    {
        // f = clock / (16 * (period + 1)). For A4 (440 Hz): period = 1789773/(16*440) - 1 ≈ 253
        int note = Nes2A03MidiConverter.pulseNote(CLOCK, 253);
        assertEquals(69, note, "Pulse period 253 ≈ A4 (MIDI 69)");
    }

    @Test
    void triangleNote_a3()
    {
        // Triangle is one octave lower: f = clock / (32 * (period + 1))
        // For A3 (220 Hz): period = 1789773/(32*220) - 1 ≈ 253
        int note = Nes2A03MidiConverter.triangleNote(CLOCK, 253);
        assertEquals(57, note, "Triangle period 253 ≈ A3 (MIDI 57)");
    }

    @Test
    void pulseNote_period_zero()
    {
        // period 0 is valid: f = clock / (16 * 1) — very high frequency
        int note = Nes2A03MidiConverter.pulseNote(CLOCK, 0);
        assertTrue(note >= 0, "Period 0 should produce a valid (clamped) note");
    }

    @Test
    void pulse1_trigger_producesNoteOn() throws Exception
    {
        var converter = new Nes2A03MidiConverter();
        var tracks = makeTracks();

        // $4000: duty=2, volume=12, constant volume
        converter.convert(nes(0x00, 0xBC), tracks, CLOCK, 0);
        // $4002: period lo = 253
        converter.convert(nes(0x02, 0xFD), tracks, CLOCK, 0);
        // $4003: period hi = 0, length counter load (trigger)
        converter.convert(nes(0x03, 0x00), tracks, CLOCK, 1);

        var noteOn = findFirst(tracks[0], ShortMessage.NOTE_ON);
        assertNotNull(noteOn, "Pulse1 trigger with volume > 0 must produce NoteOn");
        assertEquals(69, noteOn.getData1(), "A4 = MIDI 69");
    }

    @Test
    void pulse2_trigger_routesToMidiCh1() throws Exception
    {
        var converter = new Nes2A03MidiConverter();
        var tracks = makeTracks();

        converter.convert(nes(0x04, 0xBC), tracks, CLOCK, 0); // $4004: volume=12
        converter.convert(nes(0x06, 0xFD), tracks, CLOCK, 0); // $4006: period lo
        converter.convert(nes(0x07, 0x00), tracks, CLOCK, 1); // $4007: trigger

        var noteOn = findFirst(tracks[1], ShortMessage.NOTE_ON);
        assertNotNull(noteOn, "Pulse2 should route to MIDI ch 1");
    }

    @Test
    void triangle_trigger_routesToMidiCh2() throws Exception
    {
        var converter = new Nes2A03MidiConverter();
        var tracks = makeTracks();

        converter.convert(nes(0x08, 0xFF), tracks, CLOCK, 0); // $4008: linear counter (non-zero = enabled)
        converter.convert(nes(0x0A, 0xFD), tracks, CLOCK, 0); // $400A: period lo
        converter.convert(nes(0x0B, 0x00), tracks, CLOCK, 1); // $400B: trigger

        var noteOn = findFirst(tracks[2], ShortMessage.NOTE_ON);
        assertNotNull(noteOn, "Triangle should route to MIDI ch 2");
    }

    @Test
    void noise_trigger_routesToDrumChannel() throws Exception
    {
        var converter = new Nes2A03MidiConverter();
        var tracks = makeTracks();

        converter.convert(nes(0x0C, 0xBC), tracks, CLOCK, 0); // $400C: volume=12
        converter.convert(nes(0x0E, 0x00), tracks, CLOCK, 0); // $400E: mode=0 (long)
        converter.convert(nes(0x0F, 0x00), tracks, CLOCK, 1); // $400F: trigger

        var noteOn = findFirst(tracks[9], ShortMessage.NOTE_ON);
        assertNotNull(noteOn, "Noise must route to MIDI ch 9 (drums)");
    }

    @Test
    void noise_shortMode_closedHiHat() throws Exception
    {
        var converter = new Nes2A03MidiConverter();
        var tracks = makeTracks();

        converter.convert(nes(0x0C, 0xBC), tracks, CLOCK, 0);
        converter.convert(nes(0x0E, 0x80), tracks, CLOCK, 0); // bit 7 = short mode
        converter.convert(nes(0x0F, 0x00), tracks, CLOCK, 1);

        var noteOn = findFirst(tracks[9], ShortMessage.NOTE_ON);
        assertNotNull(noteOn);
        assertEquals(42, noteOn.getData1(), "Short mode → Closed Hi-Hat (42)");
    }

    @Test
    void dpcm_trigger_routesToDrumChannel() throws Exception
    {
        var converter = new Nes2A03MidiConverter();
        var tracks = makeTracks();

        // $4010: flags (non-zero = active)
        converter.convert(nes(0x10, 0x0F), tracks, CLOCK, 0);
        // $4012: sample address
        converter.convert(nes(0x12, 0x00), tracks, CLOCK, 0);
        // $4013: sample length (non-zero = trigger)
        converter.convert(nes(0x13, 0x01), tracks, CLOCK, 1);
        // $4015: enable DPCM channel (bit 4)
        converter.convert(nes(0x15, 0x10), tracks, CLOCK, 2);

        var noteOn = findFirst(tracks[9], ShortMessage.NOTE_ON);
        assertNotNull(noteOn, "DPCM should route to MIDI ch 9 (drums)");
    }

    @Test
    void zeroVolume_trigger_producesNoNote() throws Exception
    {
        var converter = new Nes2A03MidiConverter();
        var tracks = makeTracks();

        converter.convert(nes(0x00, 0x30), tracks, CLOCK, 0); // volume=0, constant
        converter.convert(nes(0x02, 0xFD), tracks, CLOCK, 0);
        converter.convert(nes(0x03, 0x00), tracks, CLOCK, 1);

        var noteOn = findFirst(tracks[0], ShortMessage.NOTE_ON);
        assertNull(noteOn, "Volume 0 trigger should not produce NoteOn");
    }
}

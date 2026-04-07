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

class SccMidiConverterTest
{

    // Simplified clock (= AY8910 clock) used for test consistency; real MSX SCC clock is 3_579_545L
    private static final long CLOCK = 1_789_772L;

    // D2 command: port, addr, data
    private static VgmEvent d2(int port, int addr, int data)
    {
        return new VgmEvent(0, 4, new byte[] { (byte) port, (byte) addr, (byte) data });
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
            if (track.get(i).getMessage() instanceof ShortMessage sm
                    && sm.getCommand() == command)
            {
                return sm;
            }
        }
        return null;
    }

    @Test
    void sccNote_middleC()
    {
        // fnum=212 → f = 1789772 / (32 × 213) ≈ 262.6 Hz → MIDI ~60 (middle C)
        assertEquals(60, SccMidiConverter.sccNote(CLOCK, 212), 1);
    }

    @Test
    void sccNote_a4()
    {
        // fnum=126 → f = 1789772 / (32 × 127) ≈ 440.4 Hz → MIDI 69 (A4)
        assertEquals(69, SccMidiConverter.sccNote(CLOCK, 126));
    }

    @Test
    void sccNote_zeroFnum_returnsMinusOne()
    {
        assertEquals(-1, SccMidiConverter.sccNote(CLOCK, 0));
    }

    @Test
    void volumeWrite_triggersNoteOn_onChannel10() throws Exception
    {
        var converter = new SccMidiConverter();
        var tracks = makeTracks();

        // Set frequency ch 0: fnum=126 (A4) — lo then hi
        converter.convert(d2(1, 0, 0x7E), tracks, CLOCK, 0); // freq lo = 0x7E
        converter.convert(d2(1, 1, 0x00), tracks, CLOCK, 0); // freq hi = 0x00
        // Set volume ch 0 = 10 → NoteOn (no enable required)
        converter.convert(d2(2, 0, 10), tracks, CLOCK, 1);

        var noteOn = findFirst(tracks[10], ShortMessage.NOTE_ON);
        assertNotNull(noteOn, "Expected NoteOn on MIDI ch 10 (SCC ch 0)");
        assertEquals(10, noteOn.getChannel());
        assertEquals(69, noteOn.getData1()); // A4
    }

    @Test
    void volumeZero_triggersNoteOff() throws Exception
    {
        var converter = new SccMidiConverter();
        var tracks = makeTracks();

        converter.convert(d2(1, 0, 0x7E), tracks, CLOCK, 0);
        converter.convert(d2(1, 1, 0x00), tracks, CLOCK, 0);
        converter.convert(d2(2, 0, 10), tracks, CLOCK, 1); // on
        converter.convert(d2(2, 0, 0), tracks, CLOCK, 2); // off

        assertNotNull(findFirst(tracks[10], ShortMessage.NOTE_OFF),
                "Expected NoteOff on MIDI ch 10");
    }

    @Test
    void channelEnable_doesNotGateNoteOn() throws Exception
    {
        // MSX games cycle SCC enable for waveform updates; enable must not gate NoteOn.
        // vol>0 + valid frequency is sufficient to start a note.
        var converter = new SccMidiConverter();
        var tracks = makeTracks();

        converter.convert(d2(1, 0, 0x7E), tracks, CLOCK, 0); // freq lo
        converter.convert(d2(1, 1, 0x00), tracks, CLOCK, 0); // freq hi
        // vol=10 → NoteOn fires immediately, without waiting for enable
        converter.convert(d2(2, 0, 10), tracks, CLOCK, 1);

        assertNotNull(findFirst(tracks[10], ShortMessage.NOTE_ON),
                "NoteOn must fire on vol write with valid frequency, without requiring enable");
    }

    @Test
    void channelDisable_doesNotInterruptPlayback() throws Exception
    {
        // SCC enable is used by MSX games to mute channels during waveform writes.
        // enable=0 must not produce NoteOff — the note must sustain across the waveform cycle.
        var converter = new SccMidiConverter();
        var tracks = makeTracks();

        converter.convert(d2(1, 0, 0x7E), tracks, CLOCK, 0);
        converter.convert(d2(1, 1, 0x00), tracks, CLOCK, 0);
        converter.convert(d2(2, 0, 10), tracks, CLOCK, 1); // NoteOn

        // Simulate waveform-write enable cycling: enable all, then disable all
        converter.convert(d2(3, 0, 0x1F), tracks, CLOCK, 2);
        converter.convert(d2(3, 0, 0x00), tracks, CLOCK, 2);

        assertNull(findFirst(tracks[10], ShortMessage.NOTE_OFF),
                "Channel disable must not produce NoteOff — enable is used for waveform writes");
    }

    @Test
    void ch4_mapsToMidiChannel14() throws Exception
    {
        var converter = new SccMidiConverter();
        var tracks = makeTracks();

        // SCC ch 4 (0-indexed): freq addr=8(lo), 9(hi)
        converter.convert(d2(1, 8, 0x7E), tracks, CLOCK, 0);
        converter.convert(d2(1, 9, 0x00), tracks, CLOCK, 0);
        // Volume ch 4: addr=4
        converter.convert(d2(2, 4, 10), tracks, CLOCK, 1);

        var noteOn = findFirst(tracks[14], ShortMessage.NOTE_ON);
        assertNotNull(noteOn, "SCC ch 4 must map to MIDI ch 14");
        assertEquals(14, noteOn.getChannel());
    }

    @Test
    void pitchChange_whilePlaying_retriggersNote() throws Exception
    {
        var converter = new SccMidiConverter();
        var tracks = makeTracks();

        converter.convert(d2(1, 0, 0x7E), tracks, CLOCK, 0); // A4 lo
        converter.convert(d2(1, 1, 0x00), tracks, CLOCK, 0); // A4 hi → freq set
        converter.convert(d2(2, 0, 10), tracks, CLOCK, 1); // NoteOn

        // Change frequency to middle C (fnum=212=0xD4): lo=0xD4, hi=0x00
        converter.convert(d2(1, 0, 0xD4), tracks, CLOCK, 2);
        converter.convert(d2(1, 1, 0x00), tracks, CLOCK, 2); // hi write triggers retrigger

        long noteOnCount = 0;
        for (int i = 0; i < tracks[10].size(); i++)
        {
            if (tracks[10].get(i).getMessage() instanceof ShortMessage sm
                    && sm.getCommand() == ShortMessage.NOTE_ON)
                noteOnCount++;
        }
        assertEquals(2, noteOnCount, "Pitch change while playing must retrigger NoteOn");
    }

    @Test
    void infrasonicNote_notEmittedButTracked() throws Exception
    {
        // fnum=2027 → 27.6 Hz → MIDI 21 (below MIN_NOTE=28): no MIDI event, but state tracked
        // so a subsequent freq change (retrigger) can fire a real NoteOn.
        var converter = new SccMidiConverter();
        var tracks = makeTracks();

        // fnum=2027: addr0=0x7B (lo), addr1=0x07 (hi) → (0x07<<8)|0x7B = 2043? Let's use fnum=2027=0x7EB:
        // lo=0xEB, hi=0x07 → (0x07<<8)|0xEB=2027, but hi is masked 0x0F → (0x07&0x0F)=0x07, fnum=(7<<8)|0xEB=2027
        converter.convert(d2(1, 0, 0xEB), tracks, CLOCK, 0); // freqLo[0]=0xEB
        converter.convert(d2(1, 1, 0x07), tracks, CLOCK, 0); // freqHi[0]=0x07 → fnum=0x7EB=2027
        converter.convert(d2(2, 0, 10), tracks, CLOCK, 1); // vol=10

        // Infrasonic: no NoteOn should be emitted
        assertNull(findFirst(tracks[10], ShortMessage.NOTE_ON),
                "Infrasonic note (MIDI 21) must not emit NoteOn");
    }

    @Test
    void infrasonicThenAudible_retriggersNoteOn() throws Exception
    {
        // Start with infrasonic fnum=2027 (MIDI 21); then change to A4 (fnum=126).
        // The retrigger must fire the audible NoteOn even though the infrasonic NoteOn was suppressed.
        var converter = new SccMidiConverter();
        var tracks = makeTracks();

        // Play infrasonic note (fnum=2027)
        converter.convert(d2(1, 0, 0xEB), tracks, CLOCK, 0); // lo=0xEB
        converter.convert(d2(1, 1, 0x07), tracks, CLOCK, 0); // hi=0x07 → fnum=2027
        converter.convert(d2(2, 0, 10), tracks, CLOCK, 1); // vol=10 — channel "playing" at MIDI 21

        // Change frequency to A4 (fnum=126 = 0x07E): lo=0x7E, hi=0x00
        converter.convert(d2(1, 0, 0x7E), tracks, CLOCK, 2); // lo=0x7E
        converter.convert(d2(1, 1, 0x00), tracks, CLOCK, 2); // hi=0x00 → fnum=126 → retrigger

        var noteOn = findFirst(tracks[10], ShortMessage.NOTE_ON);
        assertNotNull(noteOn, "Retrigger from infrasonic to audible must emit NoteOn");
        assertEquals(69, noteOn.getData1(), "Retrigger must emit A4 (MIDI 69)");

        // And there must be no NoteOff for the infrasonic note 21
        for (int i = 0; i < tracks[10].size(); i++)
        {
            if (tracks[10].get(i).getMessage() instanceof ShortMessage sm
                    && sm.getCommand() == ShortMessage.NOTE_OFF)
            {
                assertNotEquals(21, sm.getData1(), "No NoteOff should be emitted for filtered note 21");
            }
        }
    }

    @Test
    void waveformData_ignored() throws Exception
    {
        // Port 0 (waveform) writes must not produce any MIDI events
        var converter = new SccMidiConverter();
        var tracks = makeTracks();

        for (int addr = 0; addr < 0x80; addr++)
        {
            converter.convert(d2(0, addr, 0x55), tracks, CLOCK, 0);
        }

        for (int ch = 0; ch < 15; ch++)
        {
            // tracks have one default end-of-track meta event; size=1 means no MIDI events added
            assertEquals(1, tracks[ch].size(),
                    "Waveform writes must not produce MIDI events on ch " + ch);
        }
    }
}

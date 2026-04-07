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

class GameBoyDmgMidiConverterTest
{

    private static final long CLOCK = 4_194_304L;

    private static VgmEvent gb(int reg, int data)
    {
        return new VgmEvent(0, 11, new byte[] { (byte) reg, (byte) data });
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
    void dmgNote_a4()
    {
        // f = 4194304 / (32 * (2048 - freq)). For A4 (440 Hz): 2048 - freq = 4194304/(32*440) ≈ 298
        // freq = 2048 - 298 = 1750
        int note = GameBoyDmgMidiConverter.dmgNote(CLOCK, 1750);
        assertEquals(69, note, "DMG freq 1750 ≈ A4 (MIDI 69)");
    }

    @Test
    void dmgNote_period_zero_returnsMinusOne()
    {
        // freq = 2048 → period = 0 → invalid
        assertEquals(-1, GameBoyDmgMidiConverter.dmgNote(CLOCK, 2048));
    }

    @Test
    void ch1_trigger_producesNoteOn() throws Exception
    {
        var converter = new GameBoyDmgMidiConverter();
        var tracks = makeTracks();

        // NR12 (0x02): volume=12
        converter.convert(gb(0x02, 0xC0), tracks, CLOCK, 0);
        // NR13 (0x03): freq lo = 0xD6 (low byte of 1750)
        converter.convert(gb(0x03, 0xD6), tracks, CLOCK, 0);
        // NR14 (0x04): trigger=1, freq hi = 0x06 (1750 = 0x6D6 → hi=6)
        converter.convert(gb(0x04, 0x86), tracks, CLOCK, 1);

        var noteOn = findFirst(tracks[0], ShortMessage.NOTE_ON);
        assertNotNull(noteOn, "CH1 trigger with volume > 0 must produce NoteOn");
        assertEquals(69, noteOn.getData1(), "A4 = MIDI 69");
    }

    @Test
    void ch2_trigger_routesToMidiCh1() throws Exception
    {
        var converter = new GameBoyDmgMidiConverter();
        var tracks = makeTracks();

        converter.convert(gb(0x07, 0xC0), tracks, CLOCK, 0); // NR22: volume=12
        converter.convert(gb(0x08, 0xD6), tracks, CLOCK, 0); // NR23: freq lo
        converter.convert(gb(0x09, 0x86), tracks, CLOCK, 1); // NR24: trigger + freq hi

        var noteOn = findFirst(tracks[1], ShortMessage.NOTE_ON);
        assertNotNull(noteOn, "CH2 should route to MIDI ch 1");
    }

    @Test
    void ch3_wave_routesToMidiCh2() throws Exception
    {
        var converter = new GameBoyDmgMidiConverter();
        var tracks = makeTracks();

        converter.convert(gb(0x0C, 0x20), tracks, CLOCK, 0); // NR32: wave level=100%
        converter.convert(gb(0x0D, 0xD6), tracks, CLOCK, 0); // NR33: freq lo
        converter.convert(gb(0x0E, 0x86), tracks, CLOCK, 1); // NR34: trigger + freq hi

        var noteOn = findFirst(tracks[2], ShortMessage.NOTE_ON);
        assertNotNull(noteOn, "CH3 wave should route to MIDI ch 2");
    }

    @Test
    void ch4_noise_routesToDrumChannel() throws Exception
    {
        var converter = new GameBoyDmgMidiConverter();
        var tracks = makeTracks();

        converter.convert(gb(0x11, 0xC0), tracks, CLOCK, 0); // NR42: volume=12
        converter.convert(gb(0x12, 0x08), tracks, CLOCK, 0); // NR43: short LFSR
        converter.convert(gb(0x13, 0x80), tracks, CLOCK, 1); // NR44: trigger

        var noteOn = findFirst(tracks[9], ShortMessage.NOTE_ON);
        assertNotNull(noteOn, "CH4 noise must route to MIDI ch 9 (drums)");
        assertEquals(42, noteOn.getData1(), "Short LFSR → Closed Hi-Hat (42)");
    }

    @Test
    void zeroVolume_trigger_producesNoNote() throws Exception
    {
        var converter = new GameBoyDmgMidiConverter();
        var tracks = makeTracks();

        converter.convert(gb(0x02, 0x00), tracks, CLOCK, 0); // NR12: volume=0
        converter.convert(gb(0x03, 0xD6), tracks, CLOCK, 0);
        converter.convert(gb(0x04, 0x86), tracks, CLOCK, 1);

        var noteOn = findFirst(tracks[0], ShortMessage.NOTE_ON);
        assertNull(noteOn, "Volume 0 trigger should not produce NoteOn");
    }

    @Test
    void panRegister_emitsCC10() throws Exception
    {
        var converter = new GameBoyDmgMidiConverter();
        var tracks = makeTracks();

        // NR51 (0x15): CH1 left only → bit4=1, bit0=0 → 0x10
        converter.convert(gb(0x15, 0x10), tracks, CLOCK, 0);
        converter.convert(gb(0x02, 0xC0), tracks, CLOCK, 0);
        converter.convert(gb(0x03, 0xD6), tracks, CLOCK, 0);
        converter.convert(gb(0x04, 0x86), tracks, CLOCK, 1);

        ShortMessage cc = null;
        for (int i = 0; i < tracks[0].size(); i++)
        {
            var msg = tracks[0].get(i).getMessage();
            if (msg instanceof ShortMessage sm
                    && sm.getCommand() == ShortMessage.CONTROL_CHANGE
                    && sm.getData1() == 10)
            {
                cc = sm;
                break;
            }
        }
        assertNotNull(cc, "NR51 should emit CC10");
        assertEquals(0, cc.getData2(), "CH1 left only → pan=0");
    }
}

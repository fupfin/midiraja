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

class HuC6280MidiConverterTest
{

    private static final long CLOCK = 3_579_545L;

    private static VgmEvent pce(int reg, int data)
    {
        return new VgmEvent(0, 12, new byte[] { (byte) reg, (byte) data });
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
    void noteFrequency_a4()
    {
        // f = 3579545 / (32 * period). For A4 (440 Hz): period = 3579545 / (32*440) ≈ 254
        int note = HuC6280MidiConverter.huC6280Note(CLOCK, 254);
        assertEquals(69, note, "Period 254 ≈ A4 (MIDI 69)");
    }

    @Test
    void noteFrequency_periodZero_returnsMinusOne()
    {
        assertEquals(-1, HuC6280MidiConverter.huC6280Note(CLOCK, 0));
    }

    @Test
    void enableAndVolume_producesNoteOn() throws Exception
    {
        var converter = new HuC6280MidiConverter();
        var tracks = makeTracks();

        // Select ch 0, set frequency (period ≈ 254 → A4)
        converter.convert(pce(0x00, 0), tracks, CLOCK, 0); // select ch 0
        converter.convert(pce(0x02, 254), tracks, CLOCK, 0); // freq lo
        converter.convert(pce(0x03, 0), tracks, CLOCK, 0); // freq hi
        // Enable + volume=20
        converter.convert(pce(0x04, 0x80 | 20), tracks, CLOCK, 1);

        var noteOn = findFirst(tracks[0], ShortMessage.NOTE_ON);
        assertNotNull(noteOn, "Enabled + vol > 0 must produce NoteOn");
        assertEquals(69, noteOn.getData1(), "A4 = MIDI 69");
    }

    @Test
    void disableChannel_producesNoteOff() throws Exception
    {
        var converter = new HuC6280MidiConverter();
        var tracks = makeTracks();

        converter.convert(pce(0x00, 0), tracks, CLOCK, 0);
        converter.convert(pce(0x02, 254), tracks, CLOCK, 0);
        converter.convert(pce(0x03, 0), tracks, CLOCK, 0);
        converter.convert(pce(0x04, 0x80 | 20), tracks, CLOCK, 1); // on
        converter.convert(pce(0x04, 0x00), tracks, CLOCK, 2); // off (enable=0)

        var noteOff = findFirst(tracks[0], ShortMessage.NOTE_OFF);
        assertNotNull(noteOff, "Disabling channel must produce NoteOff");
    }

    @Test
    void channelSelect_routesToCorrectMidiChannel() throws Exception
    {
        var converter = new HuC6280MidiConverter();
        var tracks = makeTracks();

        converter.convert(pce(0x00, 3), tracks, CLOCK, 0); // select ch 3
        converter.convert(pce(0x02, 254), tracks, CLOCK, 0);
        converter.convert(pce(0x03, 0), tracks, CLOCK, 0);
        converter.convert(pce(0x04, 0x80 | 20), tracks, CLOCK, 1);

        var noteOn = findFirst(tracks[3], ShortMessage.NOTE_ON);
        assertNotNull(noteOn, "Ch 3 should route to MIDI ch 3");
    }

    @Test
    void balance_emitsPan() throws Exception
    {
        var converter = new HuC6280MidiConverter();
        var tracks = makeTracks();

        converter.convert(pce(0x00, 0), tracks, CLOCK, 0);
        // L=15, R=0 → hard left
        converter.convert(pce(0x05, 0xF0), tracks, CLOCK, 0);
        converter.convert(pce(0x02, 254), tracks, CLOCK, 0);
        converter.convert(pce(0x03, 0), tracks, CLOCK, 0);
        converter.convert(pce(0x04, 0x80 | 20), tracks, CLOCK, 1);

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
        assertNotNull(cc, "Balance should emit CC10");
        assertEquals(0, cc.getData2(), "L=15, R=0 → pan=0 (hard left)");
    }

    @Test
    void frequencyChange_retriggersNote() throws Exception
    {
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
        for (int i = 0; i < tracks[0].size(); i++)
        {
            var msg = tracks[0].get(i).getMessage();
            if (msg instanceof ShortMessage sm && sm.getCommand() == ShortMessage.NOTE_ON)
            {
                noteOnCount++;
            }
        }
        assertEquals(2, noteOnCount, "Frequency change should retrigger (2 NoteOns)");
    }

    // ── Waveform classification tests ────────────────────────────────────────

    @Test
    void classifyWaveform_squareWave_returnsSquareLead()
    {
        // 16 samples at 31, 16 samples at 0 → 2 steep edges
        int[] wave = new int[32];
        for (int i = 0; i < 16; i++)
            wave[i] = 31;
        // remaining 16 stay at 0
        assertEquals(80, HuC6280MidiConverter.classifyWaveform(wave), "Square wave → Square Lead (80)");
    }

    @Test
    void classifyWaveform_sawtoothWave_returnsSawtoothLead()
    {
        // Linear ramp 0→31 over 31 samples, then sharp drop → 1 steep edge
        int[] wave = new int[32];
        for (int i = 0; i < 32; i++)
            wave[i] = i;
        assertEquals(81, HuC6280MidiConverter.classifyWaveform(wave), "Sawtooth → Sawtooth Lead (81)");
    }

    @Test
    void classifyWaveform_sineWave_returnsCalliopeLead()
    {
        // Smooth sine-like: no steep edges
        int[] wave = new int[32];
        for (int i = 0; i < 32; i++)
        {
            wave[i] = (int) Math.round(15.5 + 15.5 * Math.sin(2 * Math.PI * i / 32));
        }
        assertEquals(82, HuC6280MidiConverter.classifyWaveform(wave), "Sine wave → Calliope Lead (82)");
    }

    @Test
    void classifyWaveform_complexWave_returnsSynthBrass()
    {
        // Rapid alternation → many steep edges
        int[] wave = new int[32];
        for (int i = 0; i < 32; i++)
            wave[i] = (i % 2 == 0) ? 31 : 0;
        assertEquals(62, HuC6280MidiConverter.classifyWaveform(wave), "Complex wave → Synth Brass 1 (62)");
    }

    @Test
    void waveformData_emitsNoteOn() throws Exception
    {
        // Converters no longer emit Program Change; verify NoteOn is produced after waveform write.
        var converter = new HuC6280MidiConverter();
        var tracks = makeTracks();

        converter.convert(pce(0x00, 0), tracks, CLOCK, 0); // select ch 0

        // Write a sawtooth waveform (32 samples: 0, 1, 2, ..., 31)
        for (int i = 0; i < 32; i++)
        {
            converter.convert(pce(0x06, i), tracks, CLOCK, 0);
        }

        // Set frequency and enable
        converter.convert(pce(0x02, 254), tracks, CLOCK, 0);
        converter.convert(pce(0x03, 0), tracks, CLOCK, 0);
        converter.convert(pce(0x04, 0x80 | 20), tracks, CLOCK, 1);

        var noteOn = findFirst(tracks[0], ShortMessage.NOTE_ON);
        assertNotNull(noteOn, "NoteOn must be emitted after waveform write");
        var pc = findFirst(tracks[0], ShortMessage.PROGRAM_CHANGE);
        assertNotNull(pc, "Waveform classification must emit Program Change");
        assertEquals(81, pc.getData1(), "Sawtooth waveform → Sawtooth Lead (81)");
    }
}

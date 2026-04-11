/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.export.vgm;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

import org.junit.jupiter.api.Test;

class Ay8910VgmExporterTest
{
    /** Builds a minimal MIDI Sequence with one track containing the given events at tick 0. */
    static Sequence sequence(MidiEvent... events) throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        Track track = seq.createTrack();
        for (var e : events)
            track.add(e);
        return seq;
    }

    static MidiEvent noteOn(int ch, int note, int vel) throws Exception
    {
        return new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, ch, note, vel), 0);
    }

    static MidiEvent noteOff(int ch, int note) throws Exception
    {
        return new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, ch, note, 0), 480);
    }

    static MidiEvent programChange(int ch, int prog) throws Exception
    {
        return new MidiEvent(new ShortMessage(ShortMessage.PROGRAM_CHANGE, ch, prog, 0), 0);
    }

    private static CompositeVgmExporter composite()
    {
        return new CompositeVgmExporter(ChipHandlers.create(ChipHandlers.PRESETS.get("ay8910")));
    }

    private static int readInt32Le(byte[] data, int offset)
    {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    // ── Header validation ─────────────────────────────────────────────────────

    @Test
    void export_producesValidVgmHeader() throws Exception
    {
        var seq = sequence(noteOn(0, 69, 100), noteOff(0, 69));
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        assertEquals('V', data[0]);
        assertEquals('g', data[1]);
        assertEquals('m', data[2]);
        assertEquals(' ', data[3]);
        assertEquals(0x66, data[data.length - 1] & 0xFF); // end marker
    }

    @Test
    void export_eofOffsetCorrect() throws Exception
    {
        var seq = sequence(noteOn(0, 69, 100));
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();
        assertEquals(data.length - 4, readInt32Le(data, 0x04));
    }

    // ── Tone write ────────────────────────────────────────────────────────────

    @Test
    void noteOn_writesAyTonePeriod() throws Exception
    {
        // A4 = 440 Hz, tp = round(1789772 / (16 * 440)) = 254
        var seq = sequence(noteOn(0, 69, 100));
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        // Scan data area for 0xA0 commands and find the tone period registers (0 or 1 for ch 0)
        List<int[]> ayWrites = findAyWrites(data, 0x80);
        boolean foundFinePeriod = ayWrites.stream()
                .anyMatch(w -> w[0] == 0 && w[1] == 254);
        assertTrue(foundFinePeriod, "Expected AY ch0 fine period register write with tp=254");
    }

    @Test
    void noteOn_enablesToneInMixer() throws Exception
    {
        var seq = sequence(noteOn(0, 69, 100));
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        // The last write to mixer register (7) should have tone bit for ch0 cleared (active low)
        List<int[]> mixerWrites = findAyWrites(data, 0x80).stream()
                .filter(w -> w[0] == 7)
                .toList();
        assertFalse(mixerWrites.isEmpty(), "Expected mixer register write");
        int lastMixer = mixerWrites.get(mixerWrites.size() - 1)[1];
        assertEquals(0, lastMixer & 0x01, "Tone bit for ch0 should be 0 (active low = enabled)");
    }

    @Test
    void noteOn_setsAmplitude() throws Exception
    {
        var seq = sequence(noteOn(0, 69, 127));
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        // Amplitude register for ch0 is reg 8; velocity 127 → amp 15
        List<int[]> ampWrites = findAyWrites(data, 0x80).stream()
                .filter(w -> w[0] == 8)
                .toList();
        assertFalse(ampWrites.isEmpty(), "Expected amplitude register write");
        int lastAmp = ampWrites.get(ampWrites.size() - 1)[1];
        assertEquals(15, lastAmp, "Velocity 127 → amp 15");
    }

    // ── Percussion ────────────────────────────────────────────────────────────

    @Test
    void percussionNoteOn_writesNoiseToChip0() throws Exception
    {
        // MIDI ch 9 = percussion; routes to first handler (AY chip 0)
        var seq = sequence(noteOn(9, 36, 100)); // kick drum
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        // Noise period goes to chip0 reg 6 → AY1 write (plain reg, no bit 7)
        boolean noiseWritten = findAyWrites(data, 0x80).stream()
                .anyMatch(w -> w[0] == 6 && w[1] == 31); // kick drum noise period = 31
        assertTrue(noiseWritten, "Expected noise period write 31 on chip0 reg6 for kick drum");
    }

    // ── Voice stealing ────────────────────────────────────────────────────────

    @Test
    void voiceStealing_doesNotCrashWhenAllSlotsFull() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        Track track = seq.createTrack();
        // Play 7 notes simultaneously — more than 6 dual-AY slots triggers stealing
        for (int i = 0; i < 7; i++)
            track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 60 + i, 80), 0));
        var out = new ByteArrayOutputStream();
        assertDoesNotThrow(() -> composite().export(seq, out));
        byte[] data = out.toByteArray();
        assertEquals('V', data[0]); // still produces valid VGM
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Collect all [reg, data] pairs from AY chip-0 write commands (0xA0 with reg < 0x80). */
    private static List<int[]> findAyWrites(byte[] data, int start)
    {
        var result = new ArrayList<int[]>();
        for (int i = start; i < data.length - 2; i++)
        {
            if ((data[i] & 0xFF) == 0xA0 && (data[i + 1] & 0x80) == 0)
                result.add(new int[] { data[i + 1] & 0xFF, data[i + 2] & 0xFF });
        }
        return result;
    }
}

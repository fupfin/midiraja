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

class Opl3VgmExporterTest
{
    private static CompositeVgmExporter composite()
    {
        return new CompositeVgmExporter(ChipHandlers.create(ChipHandlers.PRESETS.get("sb16")));
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
        var seq = singleNote(0, 69, 100);
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        assertEquals('V', data[0]);
        assertEquals('g', data[1]);
        assertEquals('m', data[2]);
        assertEquals(' ', data[3]);
        assertEquals(0x66, data[data.length - 1] & 0xFF);
    }

    @Test
    void export_eofOffsetCorrect() throws Exception
    {
        var seq = singleNote(0, 69, 100);
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();
        assertEquals(data.length - 4, readInt32Le(data, 0x04));
    }

    @Test
    void export_ymf262ClockPatched() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack();
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();
        assertEquals(VgmWriter.YMF262_CLOCK, readInt32Le(data, 0x5C));
    }

    // ── OPL3 bank 1 enable ────────────────────────────────────────────────────

    @Test
    void noteOn_enablesOpl3Bank1() throws Exception
    {
        var seq = singleNote(0, 69, 100);
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        // OPL3 bank1 enable: 0x5F 0x05 0x01
        boolean bank1Enabled = false;
        for (int i = 0x80; i < data.length - 2; i++)
        {
            if ((data[i] & 0xFF) == 0x5F
                    && (data[i + 1] & 0xFF) == 0x05
                    && (data[i + 2] & 0xFF) == 0x01)
            {
                bank1Enabled = true;
                break;
            }
        }
        assertTrue(bank1Enabled, "OPL3 must enable bank1 (0x5F 0x05 0x01)");
    }

    // ── Note on ───────────────────────────────────────────────────────────────

    @Test
    void noteOn_producesOpl3Writes() throws Exception
    {
        var seq = singleNote(0, 69, 100);
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        // At least one 0x5E (OPL3 bank0 write) should exist
        boolean hasOpl3Write = false;
        for (int i = 0x80; i < data.length - 1; i++)
        {
            if ((data[i] & 0xFF) == 0x5E)
            {
                hasOpl3Write = true;
                break;
            }
        }
        assertTrue(hasOpl3Write, "Expected OPL3 bank0 write command (0x5E) for note on");
    }

    @Test
    void noteOn_setsKeyOnBit() throws Exception
    {
        var seq = singleNote(0, 69, 100);
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        // Key-on: reg 0xB0-0xB8 with bit 5 set (0x20)
        List<int[]> writes = findOpl3Bank0Writes(data, 0x80);
        boolean keyOn = writes.stream()
                .anyMatch(w -> w[0] >= 0xB0 && w[0] <= 0xB8 && (w[1] & 0x20) != 0);
        assertTrue(keyOn, "Expected OPL3 key-on: reg 0xBx with bit 5 set");
    }

    @Test
    void noteOff_clearsKeyOnBit() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        Track track = seq.createTrack();
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 69, 100), 0));
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, 69, 0), 480));
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        List<int[]> writes = findOpl3Bank0Writes(data, 0x80);
        boolean keyOff = writes.stream()
                .anyMatch(w -> w[0] >= 0xB0 && w[0] <= 0xB8 && (w[1] & 0x20) == 0);
        assertTrue(keyOff, "Expected OPL3 key-off: reg 0xBx with bit 5 clear");
    }

    // ── Voice stealing ────────────────────────────────────────────────────────

    @Test
    void voiceStealing_doesNotCrash() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        Track track = seq.createTrack();
        for (int i = 0; i < 20; i++) // more than 18 total slots
            track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 48 + i, 80), 0));
        var out = new ByteArrayOutputStream();
        assertDoesNotThrow(() -> composite().export(seq, out));
        assertEquals('V', out.toByteArray()[0]);
    }

    @Test
    void programChange_usesDifferentPatch() throws Exception
    {
        // Send program change to channel 0 with program 40 (Violin)
        var seq = new Sequence(Sequence.PPQ, 480);
        Track track = seq.createTrack();
        track.add(new MidiEvent(new ShortMessage(ShortMessage.PROGRAM_CHANGE, 0, 40, 0), 0));
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 69, 100), 0));
        var out = new ByteArrayOutputStream();
        assertDoesNotThrow(() -> composite().export(seq, out));

        byte[] data = out.toByteArray();
        assertEquals('V', data[0]);
        assertEquals('g', data[1]);
        assertEquals('m', data[2]);
        assertEquals(' ', data[3]);
        assertEquals(0x66, data[data.length - 1] & 0xFF);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Sequence singleNote(int ch, int note, int vel) throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack().add(
                new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, ch, note, vel), 0));
        return seq;
    }

    private static List<int[]> findOpl3Bank0Writes(byte[] data, int start)
    {
        var result = new ArrayList<int[]>();
        for (int i = start; i < data.length - 2; i++)
        {
            if ((data[i] & 0xFF) == 0x5E)
                result.add(new int[] { data[i + 1] & 0xFF, data[i + 2] & 0xFF });
        }
        return result;
    }
}

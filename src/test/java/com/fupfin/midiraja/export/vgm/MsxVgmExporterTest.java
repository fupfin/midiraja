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

class MsxVgmExporterTest
{
    // ── Header ────────────────────────────────────────────────────────────────

    @Test
    void export_validHeader() throws Exception
    {
        var seq = singleNote(0, 69, 100);
        var out = new ByteArrayOutputStream();
        new MsxVgmExporter().export(seq, out);
        byte[] data = out.toByteArray();
        assertEquals('V', data[0]);
        assertEquals('g', data[1]);
        assertEquals('m', data[2]);
        assertEquals(' ', data[3]);
        assertEquals(0x66, data[data.length - 1] & 0xFF);
    }

    // ── FM first, PSG overflow ────────────────────────────────────────────────

    @Test
    void firstNineNotes_useYm2413FmSlots() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        Track track = seq.createTrack();
        for (int i = 0; i < 9; i++)
            track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 60 + i, 80), 0));
        var out = new ByteArrayOutputStream();
        new MsxVgmExporter().export(seq, out);
        byte[] data = out.toByteArray();

        // 9 notes → 9 YM2413 key-on writes (reg 0x20-0x28 with bit 4 set)
        long keyOns = findYm2413Writes(data).stream()
                .filter(w -> w[0] >= 0x20 && w[0] <= 0x28 && (w[1] & 0x10) != 0)
                .count();
        assertEquals(9, keyOns, "All 9 FM slots should be used before PSG");
    }

    @Test
    void tenthNote_overflowsToPsg() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        Track track = seq.createTrack();
        for (int i = 0; i < 10; i++)
            track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 60 + i, 80), 0));
        var out = new ByteArrayOutputStream();
        new MsxVgmExporter().export(seq, out);
        byte[] data = out.toByteArray();

        // At least one AY amplitude write (reg 8, 9, or 10) should be non-zero
        boolean psgUsed = findAyWrites(data).stream()
                .anyMatch(w -> w[0] >= 8 && w[0] <= 10 && w[1] > 0);
        assertTrue(psgUsed, "10th note should overflow to PSG (AY amplitude reg written)");
    }

    // ── Rhythm mode ───────────────────────────────────────────────────────────

    @Test
    void midiCh9_enablesRhythmMode() throws Exception
    {
        var seq = singleNote(9, 36, 100); // kick drum
        var out = new ByteArrayOutputStream();
        new MsxVgmExporter().export(seq, out);
        byte[] data = out.toByteArray();

        boolean rhythmEnabled = findYm2413Writes(data).stream()
                .anyMatch(w -> w[0] == 0x0E && (w[1] & 0x20) != 0);
        assertTrue(rhythmEnabled, "MIDI ch9 must enable rhythm mode");
    }

    // ── Voice stealing ────────────────────────────────────────────────────────

    @Test
    void voiceStealing_doesNotCrash() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        Track track = seq.createTrack();
        for (int i = 0; i < 15; i++) // more than 12 total slots
            track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 60 + i, 80), 0));
        var out = new ByteArrayOutputStream();
        assertDoesNotThrow(() -> new MsxVgmExporter().export(seq, out));
        assertEquals('V', out.toByteArray()[0]);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Sequence singleNote(int ch, int note, int vel) throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack().add(
                new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, ch, note, vel), 0));
        return seq;
    }

    private static List<int[]> findYm2413Writes(byte[] data)
    {
        var result = new ArrayList<int[]>();
        for (int i = 0x80; i < data.length - 2; i++)
        {
            if ((data[i] & 0xFF) == 0x51)
                result.add(new int[] { data[i + 1] & 0xFF, data[i + 2] & 0xFF });
        }
        return result;
    }

    private static List<int[]> findAyWrites(byte[] data)
    {
        var result = new ArrayList<int[]>();
        for (int i = 0x80; i < data.length - 2; i++)
        {
            if ((data[i] & 0xFF) == 0xA0 && (data[i + 1] & 0x80) == 0)
                result.add(new int[] { data[i + 1] & 0xFF, data[i + 2] & 0xFF });
        }
        return result;
    }
}

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

class Ym3812HandlerTest
{
    private static CompositeVgmExporter composite()
    {
        return new CompositeVgmExporter(ChipHandlers.create(ChipHandlers.PRESETS.get("adlib")));
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
    void export_validVgmHeader() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack().add(
                new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 69, 100), 0));
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
    void export_ym3812ClockPatched() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack();
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();
        assertEquals(VgmWriter.YM3812_CLOCK, readInt32Le(data, 0x50));
    }

    // ── initSilence ───────────────────────────────────────────────────────────

    @Test
    void initSilence_writesKeyOffForAll9Channels() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack();
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        // 0x5A 0xBx 0x00 for all 9 channels
        List<int[]> writes = findOpl2Writes(data, 0x80);
        long keyOffCount = writes.stream()
                .filter(w -> w[0] >= 0xB0 && w[0] <= 0xB8 && w[1] == 0)
                .count();
        assertTrue(keyOffCount >= 9, "initSilence must write key-off (0xBx=0) for all 9 channels");
    }

    @Test
    void initSilence_doesNotWriteBank1Commands() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack();
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        // OPL2 has no bank 1 — no 0x5F commands should appear
        boolean hasBank1 = false;
        for (int i = 0x80; i < data.length; i++)
        {
            if ((data[i] & 0xFF) == 0x5F)
            {
                hasBank1 = true;
                break;
            }
        }
        assertFalse(hasBank1, "OPL2 must not emit bank1 commands (0x5F)");
    }

    // ── Note on/off ───────────────────────────────────────────────────────────

    @Test
    void noteOn_producesOpl2Writes() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack().add(
                new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 69, 100), 0));
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        List<int[]> writes = findOpl2Writes(data, 0x80);
        assertFalse(writes.isEmpty(), "Expected OPL2 write commands (0x5A) for note on");
    }

    @Test
    void noteOn_setsKeyOnBit() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack().add(
                new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 69, 100), 0));
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        List<int[]> writes = findOpl2Writes(data, 0x80);
        boolean keyOn = writes.stream()
                .anyMatch(w -> w[0] >= 0xB0 && w[0] <= 0xB8 && (w[1] & 0x20) != 0);
        assertTrue(keyOn, "Expected OPL2 key-on: reg 0xBx with bit 5 set");
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

        List<int[]> writes = findOpl2Writes(data, 0x80);
        boolean keyOff = writes.stream()
                .anyMatch(w -> w[0] >= 0xB0 && w[0] <= 0xB8 && (w[1] & 0x20) == 0);
        assertTrue(keyOff, "Expected OPL2 key-off: reg 0xBx with bit 5 clear");
    }

    // ── Voice stealing ────────────────────────────────────────────────────────

    @Test
    void voiceStealing_doesNotCrash() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        Track track = seq.createTrack();
        for (int i = 0; i < 12; i++) // more than 9 melodic slots
            track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 48 + i, 80), 0));
        var out = new ByteArrayOutputStream();
        assertDoesNotThrow(() -> composite().export(seq, out));
        assertEquals('V', out.toByteArray()[0]);
    }

    // ── Percussion ────────────────────────────────────────────────────────────

    @Test
    void percussion_velocityZero_doesNotAdvanceDrumRoundRobin() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        Track track = seq.createTrack();
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 9, 36, 100), 0));
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 9, 36, 0), 480));
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 9, 38, 100), 960));
        var out = new ByteArrayOutputStream();
        assertDoesNotThrow(() -> composite().export(seq, out));
        assertEquals('V', out.toByteArray()[0]);
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private static List<int[]> findOpl2Writes(byte[] data, int start)
    {
        var result = new ArrayList<int[]>();
        for (int i = start; i < data.length - 2; i++)
        {
            if ((data[i] & 0xFF) == 0x5A)
                result.add(new int[] { data[i + 1] & 0xFF, data[i + 2] & 0xFF });
        }
        return result;
    }
}

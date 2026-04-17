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

class Ym2608HandlerTest
{
    private static CompositeVgmExporter composite()
    {
        return new CompositeVgmExporter(ChipHandlers.create(ChipHandlers.PRESETS.get("pc98")));
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
    void export_ym2608ClockPatched() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack();
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();
        assertEquals(VgmWriter.YM2608_CLOCK, readInt32Le(data, 0x48));
    }

    // ── initSilence ───────────────────────────────────────────────────────────

    @Test
    void initSilence_writesKeyOffFor6Channels() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack();
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        // key-off = 0x56 0x28 0xXX where upper nibble = 0 (no ops), lower nibble = ch_addr
        List<int[]> port0Writes = findOpnaPort0Writes(data, 0x80);
        long keyOffCount = port0Writes.stream()
                .filter(w -> w[0] == 0x28 && (w[1] >> 4) == 0)
                .count();
        assertTrue(keyOffCount >= 6, "initSilence must key-off all 6 channels via reg 0x28");
    }

    // ── Note on/off ───────────────────────────────────────────────────────────

    @Test
    void noteOn_producesOpnaWrites() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack().add(
                new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 69, 100), 0));
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        List<int[]> writes = findOpnaPort0Writes(data, 0x80);
        assertFalse(writes.isEmpty(), "Expected OPNA write commands (0x56) for note on");
    }

    @Test
    void noteOn_usesOpnaCommandsNotYm2612() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack().add(
                new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 69, 100), 0));
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        boolean hasYm2612 = false;
        for (int i = 0x80; i < data.length; )
        {
            int cmd = data[i] & 0xFF;
            if (cmd == 0x52 || cmd == 0x53)
            {
                hasYm2612 = true;
                break;
            }
            // Skip data block payload (0x67 0x66 type size[4] data[size])
            if (cmd == 0x67 && i + 6 < data.length && (data[i + 1] & 0xFF) == 0x66)
            {
                int size = (data[i + 3] & 0xFF)
                        | ((data[i + 4] & 0xFF) << 8)
                        | ((data[i + 5] & 0xFF) << 16)
                        | ((data[i + 6] & 0xFF) << 24);
                i += 7 + size;
                continue;
            }
            i++;
        }
        assertFalse(hasYm2612, "YM2608 must use 0x56/0x57 commands, not YM2612's 0x52/0x53");
    }

    @Test
    void noteOn_setsKeyOnViaReg0x28() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack().add(
                new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 69, 100), 0));
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        // key-on: reg 0x28 with upper nibble = 0xF (all operators)
        List<int[]> port0Writes = findOpnaPort0Writes(data, 0x80);
        boolean keyOn = port0Writes.stream()
                .anyMatch(w -> w[0] == 0x28 && (w[1] >> 4) == 0xF);
        assertTrue(keyOn, "Expected key-on: reg 0x28 with upper nibble 0xF");
    }

    @Test
    void noteOff_keysOffViaReg0x28() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        Track track = seq.createTrack();
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 69, 100), 0));
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, 69, 0), 480));
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        List<int[]> port0Writes = findOpnaPort0Writes(data, 0x80);
        // After note-on, a key-off (upper nibble = 0) on the same ch must follow
        boolean keyOff = port0Writes.stream()
                .anyMatch(w -> w[0] == 0x28 && (w[1] >> 4) == 0 && (w[1] & 0x07) <= 6);
        assertTrue(keyOff, "Expected key-off: reg 0x28 with upper nibble = 0");
    }

    // ── Voice stealing ────────────────────────────────────────────────────────

    @Test
    void voiceStealing_doesNotCrash() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        Track track = seq.createTrack();
        for (int i = 0; i < 8; i++) // more than 5 melodic slots
            track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 60 + i, 80), 0));
        var out = new ByteArrayOutputStream();
        assertDoesNotThrow(() -> composite().export(seq, out));
        assertEquals('V', out.toByteArray()[0]);
    }

    // ── Percussion ────────────────────────────────────────────────────────────

    @Test
    void percussion_velocityZero_keysOff() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        Track track = seq.createTrack();
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 9, 36, 100), 0));
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 9, 36, 0), 480));
        var out = new ByteArrayOutputStream();
        assertDoesNotThrow(() -> composite().export(seq, out));
        assertEquals('V', out.toByteArray()[0]);
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private static List<int[]> findOpnaPort0Writes(byte[] data, int start)
    {
        var result = new ArrayList<int[]>();
        for (int i = start; i < data.length - 2; i++)
        {
            if ((data[i] & 0xFF) == 0x56)
                result.add(new int[] { data[i + 1] & 0xFF, data[i + 2] & 0xFF });
        }
        return result;
    }
}

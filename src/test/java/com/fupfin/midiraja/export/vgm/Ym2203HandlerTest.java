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

class Ym2203HandlerTest
{
    private static CompositeVgmExporter composite()
    {
        return new CompositeVgmExporter(ChipHandlers.create(ChipHandlers.PRESETS.get("pc88")));
    }

    private static int readInt32Le(byte[] data, int offset)
    {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    // ── Chip metadata ─────────────────────────────────────────────────────────

    @Test
    void chipType_isYm2203()
    {
        assertEquals(ChipType.YM2203, new Ym2203Handler().chipType());
    }

    @Test
    void slotCount_is5()
    {
        // 3 FM + 2 SSG melodic channels
        assertEquals(5, new Ym2203Handler().slotCount());
    }

    @Test
    void percussionPriority_is0()
    {
        assertEquals(0, new Ym2203Handler().percussionPriority());
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
    void export_ym2203ClockPatched() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack();
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();
        assertEquals(VgmWriter.YM2203_CLOCK, readInt32Le(data, 0x44));
    }

    // ── initSilence ───────────────────────────────────────────────────────────

    @Test
    void initSilence_writesKeyOffFor3Channels() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack();
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        // key-off: 0x55 0x28 0xXX where upper nibble = 0 (no ops on), lower nibble = ch (0-2)
        List<int[]> opnWrites = findOpnWrites(data, 0x40);
        long keyOffCount = opnWrites.stream()
                .filter(w -> w[0] == 0x28 && (w[1] >> 4) == 0)
                .count();
        assertTrue(keyOffCount >= 3, "initSilence must key-off all 3 channels via reg 0x28");
    }

    // ── Note on/off ───────────────────────────────────────────────────────────

    @Test
    void noteOn_producesOpnWrites() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack().add(
                new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 69, 100), 0));
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        List<int[]> writes = findOpnWrites(data, 0x40);
        assertFalse(writes.isEmpty(), "Expected OPN write commands (0x55) for note on");
    }

    @Test
    void noteOn_usesOpnCommandNotOpna() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack().add(
                new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 69, 100), 0));
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        boolean hasOpna = false;
        for (int i = 0x40; i < data.length; i++)
        {
            int cmd = data[i] & 0xFF;
            if (cmd == 0x56 || cmd == 0x57)
            {
                hasOpna = true;
                break;
            }
        }
        assertFalse(hasOpna, "YM2203 must use 0x55 commands, not OPNA's 0x56/0x57");
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

        List<int[]> writes = findOpnWrites(data, 0x40);
        boolean keyOn = writes.stream().anyMatch(w -> w[0] == 0x28 && (w[1] >> 4) == 0xF);
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

        List<int[]> writes = findOpnWrites(data, 0x40);
        boolean keyOff = writes.stream()
                .anyMatch(w -> w[0] == 0x28 && (w[1] >> 4) == 0 && (w[1] & 0x07) <= 2);
        assertTrue(keyOff, "Expected key-off: reg 0x28 with upper nibble = 0, ch 0-2");
    }

    // ── Voice stealing ────────────────────────────────────────────────────────

    @Test
    void voiceStealing_doesNotCrash() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        Track track = seq.createTrack();
        for (int i = 0; i < 6; i++) // more than 5 slots
            track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 60 + i, 80), 0));
        var out = new ByteArrayOutputStream();
        assertDoesNotThrow(() -> composite().export(seq, out));
        assertEquals('V', out.toByteArray()[0]);
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    /** Finds all YM2203 (OPN) register writes (command 0x55) after {@code start}. */
    private static List<int[]> findOpnWrites(byte[] data, int start)
    {
        var result = new ArrayList<int[]>();
        for (int i = start; i < data.length - 2; i++)
        {
            if ((data[i] & 0xFF) == 0x55)
                result.add(new int[] { data[i + 1] & 0xFF, data[i + 2] & 0xFF });
        }
        return result;
    }
}

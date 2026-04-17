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

class Ym2610BHandlerTest
{
    private static CompositeVgmExporter composite()
    {
        return new CompositeVgmExporter(ChipHandlers.create(ChipHandlers.PRESETS.get("neogeo-b")));
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
    void chipType_isYm2610B()
    {
        // Use reflection-free check via the handler's chipType()
        var handler = new Ym2610BHandler();
        assertEquals(ChipType.YM2610B, handler.chipType());
    }

    @Test
    void slotCount_is8()
    {
        // 6 FM + 2 SSG melodic channels
        assertEquals(8, new Ym2610BHandler().slotCount());
    }

    @Test
    void percussionPriority_is3()
    {
        assertEquals(3, new Ym2610BHandler().percussionPriority());
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
    void export_ym2610BClockPatched_bit31Set() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack();
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        // Bit 31 must be set to activate YM2610B mode in libvgm
        int clockField = readInt32Le(data, 0x4C);
        assertEquals((long) (VgmWriter.YM2610_CLOCK | 0x80000000) & 0xFFFFFFFFL,
                clockField & 0xFFFFFFFFL,
                "YM2610B clock at 0x4C must have bit 31 set");
    }

    @Test
    void export_ym2610B_clockValue_matchesYm2610() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack();
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        int clockField = readInt32Le(data, 0x4C);
        assertEquals(VgmWriter.YM2610_CLOCK, clockField & 0x7FFFFFFF,
                "YM2610B base clock must equal YM2610_CLOCK (bit 31 excluded)");
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

        List<int[]> port0Writes = findYm2610Port0Writes(data, 0x80);
        long keyOffCount = port0Writes.stream()
                .filter(w -> w[0] == 0x28 && (w[1] >> 4) == 0)
                .count();
        assertTrue(keyOffCount >= 6, "initSilence must key-off all 6 FM channels via reg 0x28");
    }

    @Test
    void initSilence_embedsAdpcmRomDataBlock() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack();
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        int headerSize = 0x80; // v1.61 header
        boolean foundDataBlock = false;
        for (int i = headerSize; i < data.length - 6; i++)
        {
            if ((data[i] & 0xFF) == 0x67
                    && (data[i + 1] & 0xFF) == 0x66
                    && (data[i + 2] & 0xFF) == 0x82)
            {
                foundDataBlock = true;
                break;
            }
        }
        assertTrue(foundDataBlock, "initSilence must embed ADPCM-A ROM data block (type 0x82)");
    }

    // ── Note on/off ───────────────────────────────────────────────────────────

    @Test
    void noteOn_producesYm2610Writes() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack().add(
                new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 69, 100), 0));
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        List<int[]> writes = findYm2610Port0Writes(data, 0x80);
        assertFalse(writes.isEmpty(), "Expected YM2610 write commands (0x58) for note on");
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

        List<int[]> port0Writes = findYm2610Port0Writes(data, 0x80);
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

        List<int[]> port0Writes = findYm2610Port0Writes(data, 0x80);
        boolean keyOff = port0Writes.stream()
                .anyMatch(w -> w[0] == 0x28 && (w[1] >> 4) == 0);
        assertTrue(keyOff, "Expected key-off: reg 0x28 with upper nibble = 0");
    }

    // ── Percussion ────────────────────────────────────────────────────────────

    @Test
    void percussion_bassDrum_triggersAdpcmKeyOn() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack().add(
                new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 9, 36, 100), 0));
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        // ADPCM-A key-on: 0x59 (port 1) 0x00 0x01 (bit 0 = ch0)
        List<int[]> port1Writes = findYm2610Port1Writes(data, 0x80);
        boolean keyOn = port1Writes.stream()
                .anyMatch(w -> w[0] == 0x00 && (w[1] & 0x01) != 0);
        assertTrue(keyOn, "Bass drum (note 36) must trigger ADPCM-A ch0 key-on via port 1 reg 0x00");
    }

    @Test
    void percussion_velocityZero_doesNotKeyOn() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack().add(
                new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 9, 36, 0), 0));
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        List<int[]> port1Writes = findYm2610Port1Writes(data, 0x80);
        // After initSilence (which writes 0xBF key-off), no small-mask key-on for ch0
        boolean illegalKeyOn = port1Writes.stream()
                .anyMatch(w -> w[0] == 0x00 && (w[1] & 0x3F) != 0 && (w[1] & 0x80) == 0);
        assertFalse(illegalKeyOn, "velocity=0 must not trigger ADPCM-A key-on");
    }

    // ── Voice stealing ────────────────────────────────────────────────────────

    @Test
    void voiceStealing_doesNotCrash() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        Track track = seq.createTrack();
        for (int i = 0; i < 10; i++) // more than 8 slots
            track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 60 + i, 80), 0));
        var out = new ByteArrayOutputStream();
        assertDoesNotThrow(() -> composite().export(seq, out));
        assertEquals('V', out.toByteArray()[0]);
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private static List<int[]> findYm2610Port0Writes(byte[] data, int start)
    {
        var result = new ArrayList<int[]>();
        for (int i = start; i < data.length - 2; i++)
        {
            if ((data[i] & 0xFF) == 0x58)
                result.add(new int[] { data[i + 1] & 0xFF, data[i + 2] & 0xFF });
        }
        return result;
    }

    private static List<int[]> findYm2610Port1Writes(byte[] data, int start)
    {
        var result = new ArrayList<int[]>();
        for (int i = start; i < data.length - 2; i++)
        {
            if ((data[i] & 0xFF) == 0x59)
                result.add(new int[] { data[i + 1] & 0xFF, data[i + 2] & 0xFF });
        }
        return result;
    }
}

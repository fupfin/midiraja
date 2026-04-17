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

class NesApuHandlerTest
{
    private static CompositeVgmExporter composite()
    {
        return new CompositeVgmExporter(ChipHandlers.create(ChipHandlers.PRESETS.get("nes")));
    }

    private static int readInt32Le(byte[] data, int offset)
    {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    /** Finds all NES APU register writes (command 0xB4) after {@code start}. */
    private static List<int[]> findNesWrites(byte[] data, int start)
    {
        var result = new ArrayList<int[]>();
        for (int i = start; i < data.length - 2; i++)
        {
            if ((data[i] & 0xFF) == 0xB4)
            {
                result.add(new int[] { data[i + 1] & 0xFF, data[i + 2] & 0xFF });
                i += 2;
            }
        }
        return result;
    }

    // ── Chip metadata ─────────────────────────────────────────────────────────

    @Test
    void chipType_isNesApu()
    {
        assertEquals(ChipType.NES_APU, new NesApuHandler().chipType());
    }

    @Test
    void slotCount_is3()
    {
        // CH1 pulse, CH2 pulse, CH3 triangle
        assertEquals(3, new NesApuHandler().slotCount());
    }

    @Test
    void percussionPriority_is1()
    {
        // CH4 noise channel, PSG-quality percussion
        assertEquals(1, new NesApuHandler().percussionPriority());
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
    void export_nesClockPatched() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack();
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        assertEquals(VgmWriter.NES_APU_CLOCK, readInt32Le(data, 0x84));
    }

    @Test
    void export_versionIs170() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack();
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        assertEquals(0x00000170, readInt32Le(data, 0x08), "NES APU requires VGM version 1.70");
    }

    @Test
    void export_vgmDataOffsetPointsPastV170Header() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack();
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        int relOffset = readInt32Le(data, 0x34);
        int absOffset = 0x34 + relOffset;
        assertEquals(0xC0, absOffset, "VGM data must start after v1.70 header (0xC0)");
    }

    // ── initSilence ───────────────────────────────────────────────────────────

    @Test
    void initSilence_enablesChannels() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack();
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        List<int[]> writes = findNesWrites(data, 0xC0);
        // Status register 0x15 must have channels enabled (bits 0-3 for CH1-CH4)
        boolean statusEnabled = writes.stream()
                .anyMatch(w -> w[0] == 0x15 && (w[1] & 0x0F) != 0);
        assertTrue(statusEnabled, "initSilence must enable channels via status register 0x15");
    }

    @Test
    void initSilence_silencesCh1() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack();
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        List<int[]> writes = findNesWrites(data, 0xC0);
        // CH1 pulse vol register (0x00): constant vol = 0 means bits [3:0]=0 and bit 4=1
        boolean silenced = writes.stream()
                .anyMatch(w -> w[0] == 0x00 && (w[1] & 0x1F) == 0x10); // constant=1, vol=0
        assertTrue(silenced, "initSilence must write CH1 vol register (0x00) with constant vol=0");
    }

    @Test
    void initSilence_silencesCh2() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack();
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        List<int[]> writes = findNesWrites(data, 0xC0);
        // CH2 pulse vol register (0x04): constant vol = 0
        boolean silenced = writes.stream()
                .anyMatch(w -> w[0] == 0x04 && (w[1] & 0x1F) == 0x10);
        assertTrue(silenced, "initSilence must write CH2 vol register (0x04) with constant vol=0");
    }

    // ── Note on ───────────────────────────────────────────────────────────────

    @Test
    void noteOn_ch1_producesMesWrites() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack().add(
                new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 69, 100), 0));
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        List<int[]> writes = findNesWrites(data, 0xC0);
        assertFalse(writes.isEmpty(), "Expected NES APU write commands (0xB4) for note on");
    }

    @Test
    void noteOn_ch1_a4_frequencyEncoded() throws Exception
    {
        // A4 = 440 Hz; pulse timer = round(NES_APU_CLOCK / (16.0 * 440)) - 1
        // = round(1789773 / 7040) - 1 = round(254.23) - 1 = 254 - 1 = 253 = 0xFD
        int expectedTimer = (int) Math.round(VgmWriter.NES_APU_CLOCK / (16.0 * 440.0)) - 1;
        int expectedLo = expectedTimer & 0xFF;
        int expectedHiBits = (expectedTimer >> 8) & 0x07;

        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack().add(
                new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 69, 100), 0));
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        List<int[]> writes = findNesWrites(data, 0xC0);
        // CH1 timer low in reg 0x02
        boolean hasTimerLo = writes.stream().anyMatch(w -> w[0] == 0x02 && w[1] == expectedLo);
        assertTrue(hasTimerLo, "A4 CH1 timer low (reg 0x02) must be 0x" + Integer.toHexString(expectedLo));
        // CH1 timer high bits in reg 0x03
        boolean hasTimerHi = writes.stream().anyMatch(w -> w[0] == 0x03 && (w[1] & 0x07) == expectedHiBits);
        assertTrue(hasTimerHi, "A4 CH1 timer high bits in reg 0x03 must be 0x" + Integer.toHexString(expectedHiBits));
    }

    @Test
    void noteOn_ch2_a4_frequencyEncoded() throws Exception
    {
        int expectedTimer = (int) Math.round(VgmWriter.NES_APU_CLOCK / (16.0 * 440.0)) - 1;
        int expectedLo = expectedTimer & 0xFF;

        var seq = new Sequence(Sequence.PPQ, 480);
        Track track = seq.createTrack();
        // Two notes to push second to slot 1 (CH2)
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 69, 100), 0));
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 1, 69, 100), 0));
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        List<int[]> writes = findNesWrites(data, 0xC0);
        // CH2 timer low in reg 0x06
        boolean hasTimerLo = writes.stream().anyMatch(w -> w[0] == 0x06 && w[1] == expectedLo);
        assertTrue(hasTimerLo, "A4 CH2 timer low (reg 0x06) must be 0x" + Integer.toHexString(expectedLo));
    }

    @Test
    void noteOn_ch3_triangle_frequencyEncoded() throws Exception
    {
        // A4 = 440 Hz; triangle timer = round(NES_APU_CLOCK / (32.0 * 440)) - 1
        // = round(1789773 / 14080) - 1 = round(127.12) - 1 = 127 - 1 = 126 = 0x7E
        int expectedTimer = (int) Math.round(VgmWriter.NES_APU_CLOCK / (32.0 * 440.0)) - 1;
        int expectedLo = expectedTimer & 0xFF;

        var seq = new Sequence(Sequence.PPQ, 480);
        Track track = seq.createTrack();
        // Three notes to push third to slot 2 (CH3 triangle)
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 69, 100), 0));
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 1, 69, 100), 0));
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 2, 69, 100), 0));
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        List<int[]> writes = findNesWrites(data, 0xC0);
        // CH3 triangle timer low in reg 0x0A
        boolean hasTimerLo = writes.stream().anyMatch(w -> w[0] == 0x0A && w[1] == expectedLo);
        assertTrue(hasTimerLo, "A4 CH3 triangle timer low (reg 0x0A) must be 0x" + Integer.toHexString(expectedLo));
    }

    // ── Note off ──────────────────────────────────────────────────────────────

    @Test
    void noteOff_silencesCh1() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        Track track = seq.createTrack();
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 69, 100), 0));
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, 69, 0), 480));
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        List<int[]> writes = findNesWrites(data, 0xC0);
        // After note-off, CH1 vol register (0x00) must be written with constant vol=0
        long silenceWrites = writes.stream()
                .filter(w -> w[0] == 0x00 && (w[1] & 0x1F) == 0x10)
                .count();
        assertTrue(silenceWrites >= 1, "After note-off, CH1 must be silenced via reg 0x00");
    }

    // ── Percussion ────────────────────────────────────────────────────────────

    @Test
    void percussion_bassDrum_triggersNoiseCh4() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack().add(
                new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 9, 36, 100), 0));
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        List<int[]> writes = findNesWrites(data, 0xC0);
        // CH4 noise trigger: reg 0x0F with non-zero length counter load
        boolean triggered = writes.stream()
                .anyMatch(w -> w[0] == 0x0F && w[1] != 0x00);
        assertTrue(triggered, "Bass drum must trigger CH4 noise via reg 0x0F with length load");
    }

    @Test
    void percussion_velocityZero_doesNotTriggerCh4() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack().add(
                new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 9, 36, 0), 0));
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        List<int[]> writes = findNesWrites(data, 0xC0);
        // No CH4 trigger (reg 0x0F) should appear after header
        boolean illegalTrigger = writes.stream()
                .anyMatch(w -> w[0] == 0x0F && w[1] != 0x00);
        assertFalse(illegalTrigger, "velocity=0 must not trigger CH4 noise");
    }

    // ── Voice stealing ────────────────────────────────────────────────────────

    @Test
    void voiceStealing_doesNotCrash() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        Track track = seq.createTrack();
        for (int i = 0; i < 5; i++) // more than 3 slots
            track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 60 + i, 80), 0));
        var out = new ByteArrayOutputStream();
        assertDoesNotThrow(() -> composite().export(seq, out));
        assertEquals('V', out.toByteArray()[0]);
    }

    // ── Presets ───────────────────────────────────────────────────────────────

    @Test
    void preset_nes_returnsNesApuChip()
    {
        var chips = ChipHandlers.PRESETS.get("nes");
        assertNotNull(chips);
        assertEquals(1, chips.size());
        assertEquals(ChipType.NES_APU, chips.get(0));
    }

}

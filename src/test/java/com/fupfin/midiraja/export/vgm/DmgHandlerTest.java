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

class DmgHandlerTest
{
    private static CompositeVgmExporter composite()
    {
        return new CompositeVgmExporter(ChipHandlers.create(ChipHandlers.PRESETS.get("gameboy")));
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
    void chipType_isDmg()
    {
        assertEquals(ChipType.DMG, new DmgHandler().chipType());
    }

    @Test
    void slotCount_is3()
    {
        // CH1 pulse, CH2 pulse, CH3 wave
        assertEquals(3, new DmgHandler().slotCount());
    }

    @Test
    void percussionPriority_is1()
    {
        // CH4 noise channel, PSG-quality percussion
        assertEquals(1, new DmgHandler().percussionPriority());
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
    void export_dmgClockPatched() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack();
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        assertEquals(VgmWriter.DMG_CLOCK, readInt32Le(data, 0x80));
    }

    @Test
    void export_versionIs170() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack();
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        assertEquals(0x00000170, readInt32Le(data, 0x08), "DMG requires VGM version 1.70");
    }

    @Test
    void export_vgmDataOffsetPointsPastV170Header() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack();
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        // VGM data offset at 0x34 is relative to 0x34; data starts at 0x34 + value
        int relOffset = readInt32Le(data, 0x34);
        int absOffset = 0x34 + relOffset;
        assertEquals(0xC0, absOffset, "VGM data must start after v1.70 header (0xC0)");
    }

    // ── initSilence ───────────────────────────────────────────────────────────

    @Test
    void initSilence_powersOnApu() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack();
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        List<int[]> writes = findDmgWrites(data, 0xC0);
        // NR52 (reg 0x16) must be written with bit 7 set (APU power on = 0x80)
        boolean apuOn = writes.stream()
                .anyMatch(w -> w[0] == 0x16 && (w[1] & 0x80) != 0);
        assertTrue(apuOn, "initSilence must power on APU via NR52 (reg 0x16) = 0x80");
    }

    @Test
    void initSilence_silencesCh1() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack();
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        List<int[]> writes = findDmgWrites(data, 0xC0);
        // NR12 (reg 0x02) = 0x00 for CH1 silence (vol 0, no envelope)
        boolean nr12Zero = writes.stream().anyMatch(w -> w[0] == 0x02 && w[1] == 0x00);
        assertTrue(nr12Zero, "initSilence must write NR12 = 0x00 to silence CH1");
    }

    @Test
    void initSilence_silencesCh2() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack();
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        List<int[]> writes = findDmgWrites(data, 0xC0);
        // NR22 (reg 0x07) = 0x00 for CH2 silence
        boolean nr22Zero = writes.stream().anyMatch(w -> w[0] == 0x07 && w[1] == 0x00);
        assertTrue(nr22Zero, "initSilence must write NR22 = 0x00 to silence CH2");
    }

    @Test
    void initSilence_silencesCh3() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack();
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        List<int[]> writes = findDmgWrites(data, 0xC0);
        // NR32 (reg 0x0C) = 0x00 for CH3 output level = mute
        boolean nr32Zero = writes.stream().anyMatch(w -> w[0] == 0x0C && w[1] == 0x00);
        assertTrue(nr32Zero, "initSilence must write NR32 = 0x00 to silence CH3 (mute)");
    }

    @Test
    void initSilence_writesWaveRam() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack();
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        List<int[]> writes = findDmgWrites(data, 0xC0);
        // Wave RAM is at registers 0x20-0x2F (16 bytes)
        long waveRamWrites = writes.stream()
                .filter(w -> w[0] >= 0x20 && w[0] <= 0x2F)
                .count();
        assertEquals(16, waveRamWrites, "initSilence must write all 16 wave RAM bytes");
    }

    // ── Note on/off ───────────────────────────────────────────────────────────

    @Test
    void noteOn_ch1_producesDmgWrites() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack().add(
                new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 69, 100), 0));
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        List<int[]> writes = findDmgWrites(data, 0xC0);
        assertFalse(writes.isEmpty(), "Expected DMG write commands (0xB3) for note on");
    }

    @Test
    void noteOn_ch1_triggersViaReg0x04() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack().add(
                new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 69, 100), 0));
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        List<int[]> writes = findDmgWrites(data, 0xC0);
        // CH1 trigger: NR14 (reg 0x04) with bit 7 set
        boolean triggered = writes.stream().anyMatch(w -> w[0] == 0x04 && (w[1] & 0x80) != 0);
        assertTrue(triggered, "CH1 note-on must trigger via NR14 (reg 0x04) with bit 7 set");
    }

    @Test
    void noteOn_ch1_a4_frequencyEncoded() throws Exception
    {
        // A4 = 440 Hz; x = 2048 - round(131072 / 440) = 2048 - 298 = 1750 = 0x6D6
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack().add(
                new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 69, 100), 0));
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        List<int[]> writes = findDmgWrites(data, 0xC0);
        // NR13 (reg 0x03) = 0xD6 (low 8 bits of 1750)
        boolean freqLo = writes.stream().anyMatch(w -> w[0] == 0x03 && w[1] == 0xD6);
        assertTrue(freqLo, "A4 CH1 freq low byte (NR13 reg 0x03) must be 0xD6");
        // NR14 (reg 0x04) high bits = (1750 >> 8) & 0x07 = 0x06, trigger bit = 0x86
        boolean freqHi = writes.stream().anyMatch(w -> w[0] == 0x04 && (w[1] & 0x07) == 0x06);
        assertTrue(freqHi, "A4 CH1 freq high bits in NR14 (reg 0x04) must be 0x06");
    }

    @Test
    void noteOn_ch2_triggersViaReg0x09() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        Track track = seq.createTrack();
        // Two notes to push to slot 1 (CH2)
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 69, 100), 0));
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 1, 72, 100), 0));
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        List<int[]> writes = findDmgWrites(data, 0xC0);
        // CH2 trigger: NR24 (reg 0x09) with bit 7 set
        boolean triggered = writes.stream().anyMatch(w -> w[0] == 0x09 && (w[1] & 0x80) != 0);
        assertTrue(triggered, "CH2 note-on must trigger via NR24 (reg 0x09) with bit 7 set");
    }

    @Test
    void noteOn_ch3_triggersViaReg0x0e() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        Track track = seq.createTrack();
        // Three notes to push to slot 2 (CH3 wave)
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 69, 100), 0));
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 1, 72, 100), 0));
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 2, 60, 100), 0));
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        List<int[]> writes = findDmgWrites(data, 0xC0);
        // CH3 trigger: NR34 (reg 0x0E) with bit 7 set
        boolean triggered = writes.stream().anyMatch(w -> w[0] == 0x0E && (w[1] & 0x80) != 0);
        assertTrue(triggered, "CH3 note-on must trigger via NR34 (reg 0x0E) with bit 7 set");
    }

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

        List<int[]> writes = findDmgWrites(data, 0xC0);
        // After note-off, NR12 (reg 0x02) must be written with 0x00 (vol 0, trigger)
        long silenceWrites = writes.stream()
                .filter(w -> w[0] == 0x02 && w[1] == 0x00)
                .count();
        assertTrue(silenceWrites >= 1, "After note-off, NR12 must be written to 0x00 to silence CH1");
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

        List<int[]> writes = findDmgWrites(data, 0xC0);
        // CH4 trigger: NR44 (reg 0x13) with bit 7 set
        boolean triggered = writes.stream().anyMatch(w -> w[0] == 0x13 && (w[1] & 0x80) != 0);
        assertTrue(triggered, "Bass drum must trigger CH4 noise via NR44 (reg 0x13) with bit 7 set");
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

        List<int[]> writes = findDmgWrites(data, 0xC0);
        // No key-on trigger for CH4 with velocity 0
        boolean illegalTrigger = writes.stream()
                .anyMatch(w -> w[0] == 0x13 && (w[1] & 0x80) != 0);
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

    // ── Helper ────────────────────────────────────────────────────────────────

    /** Finds all DMG register writes (command 0xB3) after {@code start}. */
    private static List<int[]> findDmgWrites(byte[] data, int start)
    {
        var result = new ArrayList<int[]>();
        for (int i = start; i < data.length - 2; i++)
        {
            if ((data[i] & 0xFF) == 0xB3)
                result.add(new int[] { data[i + 1] & 0xFF, data[i + 2] & 0xFF });
        }
        return result;
    }
}

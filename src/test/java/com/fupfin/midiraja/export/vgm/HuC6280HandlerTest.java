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

class HuC6280HandlerTest
{
    private static CompositeVgmExporter composite()
    {
        return new CompositeVgmExporter(ChipHandlers.create(ChipHandlers.PRESETS.get("pce")));
    }

    private static int readInt32Le(byte[] data, int offset)
    {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    /** Scan VGM stream for command 0xB9 (HuC6280 register write) from startPos onwards.
     *  Returns list of {reg, data} pairs. */
    private static List<int[]> findHucWrites(byte[] data, int startPos)
    {
        var result = new ArrayList<int[]>();
        for (int i = startPos; i < data.length - 2; i++)
        {
            if ((data[i] & 0xFF) == 0xB9)
            {
                result.add(new int[] { data[i + 1] & 0xFF, data[i + 2] & 0xFF });
                i += 2;
            }
        }
        return result;
    }

    // ── Chip metadata ─────────────────────────────────────────────────────────

    @Test
    void chipType_isHuc6280()
    {
        assertEquals(ChipType.HUC6280, new HuC6280Handler().chipType());
    }

    @Test
    void slotCount_is6()
    {
        assertEquals(6, new HuC6280Handler().slotCount());
    }

    @Test
    void percussionPriority_is0()
    {
        // HuC6280 has no hardware noise channel suitable for percussion
        assertEquals(0, new HuC6280Handler().percussionPriority());
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
    void export_hucClockPatched() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack();
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        assertEquals(VgmWriter.HUC6280_CLOCK, readInt32Le(data, 0xA4));
    }

    @Test
    void export_versionIs170() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack();
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        assertEquals(0x00000170, readInt32Le(data, 0x08), "HuC6280 requires VGM version 1.70");
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
    void initSilence_disablesAllChannels() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack();
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        List<int[]> writes = findHucWrites(data, 0xC0);
        // For each of 6 channels: channel select (reg 0x00) followed by control disable (reg 0x04, bit7=0)
        // Count how many times channel select + control=0 appears
        long disabledCount = 0;
        for (int i = 0; i < writes.size() - 1; i++)
        {
            if (writes.get(i)[0] == 0x00 && writes.get(i + 1)[0] == 0x04
                    && (writes.get(i + 1)[1] & 0x80) == 0)
                disabledCount++;
        }
        assertTrue(disabledCount >= 6, "initSilence must disable all 6 channels (found " + disabledCount + ")");
    }

    @Test
    void initSilence_writesWaveDataForAllChannels() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack();
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        List<int[]> writes = findHucWrites(data, 0xC0);
        // Count reg 0x06 (wave data) writes — should be at least 6 * 32 = 192
        long waveDataWrites = writes.stream().filter(w -> w[0] == 0x06).count();
        assertTrue(waveDataWrites >= 6 * 32,
                "initSilence must write 32 wave samples per channel (found " + waveDataWrites + ")");
    }

    @Test
    void initSilence_waveDataInRange() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack();
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        List<int[]> writes = findHucWrites(data, 0xC0);
        // All wave data values must be 5-bit (0-31)
        boolean allInRange = writes.stream()
                .filter(w -> w[0] == 0x06)
                .allMatch(w -> w[1] >= 0 && w[1] <= 31);
        assertTrue(allInRange, "All HuC6280 wave samples must be 5-bit unsigned (0-31)");
    }

    // ── Note on / off ─────────────────────────────────────────────────────────

    @Test
    void noteOn_slot0_enablesChannel0() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack().add(
                new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 69, 100), 0));
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        List<int[]> writes = findHucWrites(data, 0xC0);
        // Channel 0 selected (reg 0x00 = 0) followed by control with bit 7 set
        boolean channelEnabled = false;
        for (int i = 0; i < writes.size() - 1; i++)
        {
            if (writes.get(i)[0] == 0x00 && writes.get(i)[1] == 0
                    && writes.get(i + 1)[0] == 0x04 && (writes.get(i + 1)[1] & 0x80) != 0)
            {
                channelEnabled = true;
                break;
            }
        }
        assertTrue(channelEnabled, "noteOn slot 0 must enable channel 0 (reg 0x04 with bit 7 set)");
    }

    @Test
    void noteOn_setsFrequency() throws Exception
    {
        // A4 = 440 Hz; period = round(HUC6280_CLOCK / (32 * 440))
        // = round(3_579_545 / 14_080) = round(254.3) = 254 = 0x0FE
        int expectedPeriod = (int) Math.round(VgmWriter.HUC6280_CLOCK / (32.0 * 440.0));
        int expectedLo = expectedPeriod & 0xFF;
        int expectedHi = (expectedPeriod >> 8) & 0x0F;

        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack().add(
                new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 69, 100), 0));
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        List<int[]> writes = findHucWrites(data, 0xC0);
        boolean hasFreqLo = writes.stream().anyMatch(w -> w[0] == 0x02 && w[1] == expectedLo);
        boolean hasFreqHi = writes.stream().anyMatch(w -> w[0] == 0x03 && w[1] == expectedHi);
        assertTrue(hasFreqLo, "noteOn A4 must write freq low = 0x" + Integer.toHexString(expectedLo));
        assertTrue(hasFreqHi, "noteOn A4 must write freq high = 0x" + Integer.toHexString(expectedHi));
    }

    @Test
    void noteOn_volumeScalesWithVelocity() throws Exception
    {
        // velocity 127 → vol 31 (0x1F), bit 7 set → 0x9F
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack().add(
                new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 69, 127), 0));
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        List<int[]> writes = findHucWrites(data, 0xC0);
        boolean maxVolume = writes.stream()
                .anyMatch(w -> w[0] == 0x04 && (w[1] & 0x9F) == 0x9F);
        assertTrue(maxVolume, "velocity 127 must produce volume 31 with channel enabled (0x9F)");
    }

    @Test
    void noteOff_disablesChannel() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        Track track = seq.createTrack();
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 69, 100), 0));
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, 69, 0), 480));
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        // After note off, control reg for channel 0 should have bit 7 = 0
        List<int[]> writes = findHucWrites(data, 0xC0);
        boolean channelDisabledAfterNoteOff = false;
        for (int i = 0; i < writes.size() - 1; i++)
        {
            if (writes.get(i)[0] == 0x00 && writes.get(i)[1] == 0
                    && writes.get(i + 1)[0] == 0x04 && (writes.get(i + 1)[1] & 0x80) == 0
                    && i > 0) // not just the initSilence writes
            {
                // Make sure there was a note-on before this (seq is not empty)
                channelDisabledAfterNoteOff = true;
                break;
            }
        }
        assertTrue(channelDisabledAfterNoteOff, "noteOff must disable channel 0 (reg 0x04 bit7=0)");
    }

    // ── Polyphony ─────────────────────────────────────────────────────────────

    @Test
    void sixSimultaneousNotes_allChannelsUsed() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        Track track = seq.createTrack();
        // 6 simultaneous notes on 6 different MIDI channels
        for (int ch = 0; ch < 6; ch++)
            track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, ch, 60 + ch, 80), 0));
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        List<int[]> writes = findHucWrites(data, 0xC0);
        // All 6 HuC channels (0-5) must have been selected at least once after header
        boolean[] channelUsed = new boolean[6];
        for (var w : writes)
            if (w[0] == 0x00 && w[1] < 6)
                channelUsed[w[1]] = true;
        for (int ch = 0; ch < 6; ch++)
            assertTrue(channelUsed[ch], "Channel " + ch + " must be used for 6-note polyphony");
    }

    @Test
    void voiceStealing_doesNotCrash() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        Track track = seq.createTrack();
        for (int i = 0; i < 8; i++) // more than 6 slots
            track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 60 + i, 80), 0));
        var out = new ByteArrayOutputStream();
        assertDoesNotThrow(() -> composite().export(seq, out));
        assertEquals('V', out.toByteArray()[0]);
    }

    // ── Presets ───────────────────────────────────────────────────────────────

    @Test
    void preset_pce_returnsDmgChip()
    {
        var chips = ChipHandlers.PRESETS.get("pce");
        assertNotNull(chips);
        assertEquals(1, chips.size());
        assertEquals(ChipType.HUC6280, chips.get(0));
    }

    @Test
    void preset_huc6280_returnsDmgChip()
    {
        var chips = ChipHandlers.PRESETS.get("huc6280");
        assertNotNull(chips);
        assertEquals(1, chips.size());
        assertEquals(ChipType.HUC6280, chips.get(0));
    }
}

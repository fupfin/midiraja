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

/**
 * Spec-by-example tests for YM2610 ADPCM-A native percussion.
 *
 * <p>YM2610 ADPCM-A register mapping — all via port 1 (VGM command 0x59), addresses 0x00-0x2F:
 * <ul>
 *   <li>0x00 → key on/off (bit 7=0: key-on bits 5-0; 0xBF = all off)
 *   <li>0x01 → master total level (0x3F = max volume)
 *   <li>0x08+ch → per-channel L/R flags (bits 7-6) + individual level (bits 4-0)
 *   <li>0x10+ch → per-channel start address low (ROM byte addr >> 8)
 *   <li>0x18+ch → per-channel start address high (ROM byte addr >> 16; all 0 for 8KB ROM)
 *   <li>0x20+ch → per-channel end address low
 *   <li>0x28+ch → per-channel end address high
 * </ul>
 * <p>Note: port 0 (0x58) addresses 0x10-0x1C are DeltaT (ADPCM-B) — not ADPCM-A.
 */
class Ym2610AdpcmPercussionTest
{
    private static CompositeVgmExporter composite()
    {
        return new CompositeVgmExporter(ChipHandlers.create(ChipHandlers.PRESETS.get("neogeo")));
    }

    // ── Group 1: VGM data block structure ─────────────────────────────────────

    @Test
    void romBlock_precedesRegisterWrites() throws Exception
    {
        byte[] data = exportToBytes(emptySeq());

        // Type 0x82 = YM2610 ADPCM-A ROM (type 0x83 would be YM2610 ADPCM-B/DeltaT)
        int blockOffset = findDataBlockOffset(data, 0x82);
        assertTrue(blockOffset >= 0, "VGM data block type 0x82 (YM2610 ADPCM-A ROM) not found");

        int firstOpnbOffset = findFirstOpnbWrite(data);
        assertTrue(firstOpnbOffset >= 0, "No OPNB write commands found");
        assertTrue(blockOffset < firstOpnbOffset,
                "ROM data block (0x67 0x66 0x82) must appear before first OPNB register write");
    }

    @Test
    void romBlock_hasCorrectSize() throws Exception
    {
        byte[] data = exportToBytes(emptySeq());

        int blockOffset = findDataBlockOffset(data, 0x82);
        assertTrue(blockOffset >= 0, "VGM data block type 0x82 not found");

        // Structure: 0x67 0x66 type(1) dblkLen(4LE) [romTotalSize(4LE) startOfs(4LE)] data[romLen]
        // dblkLen = 8 (prefix) + 8960 (ROM repacked to 256-byte-aligned layout) = 8968
        int dblkLen = readInt32Le(data, blockOffset + 3);
        assertEquals(8968, dblkLen, "ADPCM-A ROM data block dblkLen must be 8968 (8-byte prefix + 8960 ROM)");
    }

    @Test
    void romBlock_dataIsNonZero() throws Exception
    {
        byte[] data = exportToBytes(emptySeq());

        int blockOffset = findDataBlockOffset(data, 0x82);
        assertTrue(blockOffset >= 0, "VGM data block type 0x82 not found");

        // skip: 0x67(1) 0x66(1) type(1) dblkLen(4) romTotalSize(4) startOfs(4) = 15 bytes
        int romStart = blockOffset + 15;
        boolean hasNonZero = false;
        for (int i = romStart; i < romStart + 8960 && i < data.length; i++)
        {
            if (data[i] != 0)
            {
                hasNonZero = true;
                break;
            }
        }
        assertTrue(hasNonZero, "ADPCM-A ROM payload must not be all zeros");
    }

    // ── Group 2: initSilence ADPCM setup (spec by example) ────────────────────

    @Test
    void initSilence_masterLevelIsMax() throws Exception
    {
        byte[] data = exportToBytes(emptySeq());
        boolean found = findOpnbPort1Writes(data, 0x80).stream()
                .anyMatch(w -> w[0] == 0x01 && w[1] == 0x3F);
        assertTrue(found, "initSilence must write master level 0x3F to port-1 reg 0x01");
    }

    @Test
    void initSilence_allChannelsKeyOff() throws Exception
    {
        byte[] data = exportToBytes(emptySeq());
        boolean found = findOpnbPort1Writes(data, 0x80).stream()
                .anyMatch(w -> w[0] == 0x00 && w[1] == 0xBF);
        assertTrue(found, "initSilence must write 0xBF to port-1 reg 0x00 (all channels key-off)");
    }

    @Test
    void initSilence_ch0StartAddrLow() throws Exception
    {
        // ch0 Bass Drum: ROM addr 0x0000 → start low = 0x0000 >> 8 = 0x00
        boolean found = findOpnbPort1Writes(exportToBytes(emptySeq()), 0x80).stream()
                .anyMatch(w -> w[0] == 0x10 && w[1] == 0x00);
        assertTrue(found, "ch0 Bass Drum start low (port-1 reg 0x10) must be 0x00");
    }

    @Test
    void initSilence_ch1StartAddrLow() throws Exception
    {
        // ch1 Snare: packed at 0x0200 (256-byte aligned) → start low = 0x0200 >> 8 = 0x02
        boolean found = findOpnbPort1Writes(exportToBytes(emptySeq()), 0x80).stream()
                .anyMatch(w -> w[0] == 0x11 && w[1] == 0x02);
        assertTrue(found, "ch1 Snare start low (port-1 reg 0x11) must be 0x02");
    }

    @Test
    void initSilence_ch2StartAddrLow() throws Exception
    {
        // ch2 Top Cymbal: packed at 0x0500 (256-byte aligned) → start low = 0x0500 >> 8 = 0x05
        boolean found = findOpnbPort1Writes(exportToBytes(emptySeq()), 0x80).stream()
                .anyMatch(w -> w[0] == 0x12 && w[1] == 0x05);
        assertTrue(found, "ch2 Top Cymbal start low (port-1 reg 0x12) must be 0x05");
    }

    @Test
    void initSilence_ch3StartAddrLow() throws Exception
    {
        // ch3 High Hat: packed at 0x1D00 (256-byte aligned) → start low = 0x1D00 >> 8 = 0x1D
        boolean found = findOpnbPort1Writes(exportToBytes(emptySeq()), 0x80).stream()
                .anyMatch(w -> w[0] == 0x13 && w[1] == 0x1D);
        assertTrue(found, "ch3 High Hat start low (port-1 reg 0x13) must be 0x1D");
    }

    @Test
    void initSilence_ch4StartAddrLow() throws Exception
    {
        // ch4 Tom Tom: packed at 0x1F00 (256-byte aligned) → start low = 0x1F00 >> 8 = 0x1F
        boolean found = findOpnbPort1Writes(exportToBytes(emptySeq()), 0x80).stream()
                .anyMatch(w -> w[0] == 0x14 && w[1] == 0x1F);
        assertTrue(found, "ch4 Tom Tom start low (port-1 reg 0x14) must be 0x1F");
    }

    @Test
    void initSilence_ch5StartAddrLow() throws Exception
    {
        // ch5 Rim Shot: packed at 0x2200 (256-byte aligned) → start low = 0x2200 >> 8 = 0x22
        boolean found = findOpnbPort1Writes(exportToBytes(emptySeq()), 0x80).stream()
                .anyMatch(w -> w[0] == 0x15 && w[1] == 0x22);
        assertTrue(found, "ch5 Rim Shot start low (port-1 reg 0x15) must be 0x22");
    }

    @Test
    void initSilence_ch2EndAddrLow() throws Exception
    {
        // ch2 Top Cymbal end: packed data ends at 0x1C3F → end low = 0x1C3F >> 8 = 0x1C
        boolean found = findOpnbPort1Writes(exportToBytes(emptySeq()), 0x80).stream()
                .anyMatch(w -> w[0] == 0x22 && w[1] == 0x1C);
        assertTrue(found, "ch2 Top Cymbal end low (port-1 reg 0x22) must be 0x1C");
    }

    // ── Group 3: GM note → ADPCM channel mapping (spec by example) ────────────

    @Test
    void noteOn_acousticBassDrum_ch0() throws Exception
    {
        assertAdpcmKeyOn(35, 0x01); // bit 0 → ch0
    }

    @Test
    void noteOn_electricBassDrum_ch0() throws Exception
    {
        assertAdpcmKeyOn(36, 0x01);
    }

    @Test
    void noteOn_acousticSnare_ch1() throws Exception
    {
        assertAdpcmKeyOn(38, 0x02); // bit 1 → ch1
    }

    @Test
    void noteOn_electricSnare_ch1() throws Exception
    {
        assertAdpcmKeyOn(40, 0x02);
    }

    @Test
    void noteOn_closedHiHat_ch3() throws Exception
    {
        assertAdpcmKeyOn(42, 0x08); // bit 3 → ch3
    }

    @Test
    void noteOn_footHiHat_ch3() throws Exception
    {
        assertAdpcmKeyOn(44, 0x08);
    }

    @Test
    void noteOn_openHiHat_ch3() throws Exception
    {
        assertAdpcmKeyOn(46, 0x08);
    }

    @Test
    void noteOn_lowFloorTom_ch4() throws Exception
    {
        assertAdpcmKeyOn(41, 0x10); // bit 4 → ch4
    }

    @Test
    void noteOn_crashCymbal_ch2() throws Exception
    {
        assertAdpcmKeyOn(49, 0x04); // bit 2 → ch2
    }

    @Test
    void noteOn_rideCymbal_ch2() throws Exception
    {
        assertAdpcmKeyOn(51, 0x04);
    }

    @Test
    void noteOn_sideStick_ch5() throws Exception
    {
        assertAdpcmKeyOn(37, 0x20); // bit 5 → ch5
    }

    @Test
    void noteOn_handClap_ch5() throws Exception
    {
        assertAdpcmKeyOn(39, 0x20);
    }

    // ── Group 4: Volume/velocity scaling ──────────────────────────────────────

    @Test
    void velocity_max127_levelIs31() throws Exception
    {
        byte[] data = exportPercussion(36, 127);
        int level = findAdpcmChannelLevel(data, 0); // ch0 = Bass Drum
        assertEquals(31, level, "velocity=127 must produce max level 31 (0x1F)");
    }

    @Test
    void velocity_min1_levelIsNonZero() throws Exception
    {
        byte[] data = exportPercussion(36, 1);
        int level = findAdpcmChannelLevel(data, 0);
        assertTrue(level >= 1, "velocity=1 must produce non-zero level (got " + level + ")");
    }

    @Test
    void velocity_64_levelIsMidRange() throws Exception
    {
        byte[] data = exportPercussion(36, 64);
        int level = findAdpcmChannelLevel(data, 0);
        assertTrue(level >= 14 && level <= 16,
                "velocity=64 must produce approximately mid-range level 14-16 (got " + level + ")");
    }

    @Test
    void channelReg_hasStereoFlagsSet() throws Exception
    {
        byte[] data = exportPercussion(36, 100);
        // port-1 reg 0x08+0 = 0x08 for Bass Drum (ch0); bits 7-6 must be 0b11 (L+R enabled)
        boolean stereoSet = findOpnbPort1Writes(data, 0x80).stream()
                .filter(w -> w[0] == 0x08)
                .anyMatch(w -> (w[1] & 0xC0) == 0xC0);
        assertTrue(stereoSet, "Per-channel level port-1 reg 0x08 must have L+R stereo flags (bits 7-6 = 0b11)");
    }

    // ── Group 5: Boundary & exception conditions ───────────────────────────────

    @Test
    void velocityZero_noKeyOn() throws Exception
    {
        byte[] data = exportPercussion(36, 0);
        // velocity=0 must not trigger key-on for ch0 (bit 0 of port-1 reg 0x00)
        boolean hasKeyOn = findOpnbPort1Writes(data, 0x80).stream()
                .anyMatch(w -> w[0] == 0x00 && w[1] == 0x01);
        assertFalse(hasKeyOn, "velocity=0 must not write key-on 0x01 to port-1 reg 0x00");
    }

    @Test
    void unmappedNote_60_ignored() throws Exception
    {
        byte[] data = exportPercussion(60, 100);
        // Middle C has no ADPCM mapping — no key-on write to port-1 reg 0x00 (initSilence writes 0xBF = key-off)
        boolean hasUnexpectedKeyOn = findOpnbPort1Writes(data, 0x80).stream()
                .anyMatch(w -> w[0] == 0x00 && w[1] != 0xBF);
        assertFalse(hasUnexpectedKeyOn, "Unmapped note 60 must not trigger ADPCM key-on");
    }

    @Test
    void unmappedNote_0_ignored() throws Exception
    {
        byte[] data = exportPercussion(0, 100);
        boolean hasUnexpectedKeyOn = findOpnbPort1Writes(data, 0x80).stream()
                .anyMatch(w -> w[0] == 0x00 && w[1] != 0xBF);
        assertFalse(hasUnexpectedKeyOn, "Unmapped note 0 must not trigger ADPCM key-on");
    }

    @Test
    void unmappedNote_127_ignored() throws Exception
    {
        byte[] data = exportPercussion(127, 100);
        boolean hasUnexpectedKeyOn = findOpnbPort1Writes(data, 0x80).stream()
                .anyMatch(w -> w[0] == 0x00 && w[1] != 0xBF);
        assertFalse(hasUnexpectedKeyOn, "Unmapped note 127 must not trigger ADPCM key-on");
    }

    @Test
    void noteOff_velocityZero_noKeyOn() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        Track track = seq.createTrack();
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 9, 36, 100), 0));
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 9, 36, 0), 480));
        var out = new ByteArrayOutputStream();
        assertDoesNotThrow(() -> composite().export(seq, out));
        assertEquals('V', out.toByteArray()[0], "Must produce valid VGM after note-off");
    }

    @Test
    void percussionDoesNotUseSlot3() throws Exception
    {
        // With ADPCM percussion, all 4 FM slots are melodic; BD + 4 melodic notes should all fit
        var seq = new Sequence(Sequence.PPQ, 480);
        Track track = seq.createTrack();
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 9, 36, 100), 0)); // Bass Drum
        for (int i = 0; i < 4; i++)
            track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 60 + i, 80), 0));
        var out = new ByteArrayOutputStream();
        assertDoesNotThrow(() -> composite().export(seq, out));
        assertEquals('V', out.toByteArray()[0]);
    }

    @Test
    void slotCount_is4_allMelodic() throws Exception
    {
        var ym2610 = ChipHandlers.create(ChipHandlers.PRESETS.get("neogeo")).stream()
                .filter(h -> h.chipType() == ChipType.YM2610)
                .findFirst()
                .orElseThrow();
        assertEquals(6, ym2610.slotCount(),
                "YM2610 must expose 6 melodic slots (4 FM + 2 SSG; ADPCM-A percussion is separate hardware)");
    }

    @Test
    void percussionPriority_is3() throws Exception
    {
        var ym2610 = ChipHandlers.create(ChipHandlers.PRESETS.get("neogeo")).stream()
                .filter(h -> h.chipType() == ChipType.YM2610)
                .findFirst()
                .orElseThrow();
        assertEquals(3, ym2610.percussionPriority(),
                "YM2610 must have percussion priority 3 (ADPCM-A native rhythm section)");
    }

    @Test
    void multipleNotes_sameChannel_noCrash() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        Track track = seq.createTrack();
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 9, 36, 100), 0));
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 9, 36, 100), 480));
        var out = new ByteArrayOutputStream();
        assertDoesNotThrow(() -> composite().export(seq, out));
        assertEquals('V', out.toByteArray()[0]);
    }

    @Test
    void exportEmptySequence_stillValidVgm() throws Exception
    {
        byte[] data = exportToBytes(emptySeq());
        assertEquals('V', data[0]);
        assertEquals('g', data[1]);
        assertEquals('m', data[2]);
        assertEquals(' ', data[3]);
        assertEquals(0x66, data[data.length - 1] & 0xFF);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static Sequence emptySeq() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack();
        return seq;
    }

    private static byte[] exportToBytes(Sequence seq) throws Exception
    {
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        return out.toByteArray();
    }

    private static byte[] exportPercussion(int note, int velocity) throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        Track track = seq.createTrack();
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 9, note, velocity), 0));
        return exportToBytes(seq);
    }

    private static void assertAdpcmKeyOn(int gmNote, int expectedKeyOnValue) throws Exception
    {
        byte[] data = exportPercussion(gmNote, 100);
        // ADPCM-A key-on is on port 1 (0x59), reg 0x00
        boolean keyOn = findOpnbPort1Writes(data, 0x80).stream()
                .anyMatch(w -> w[0] == 0x00 && w[1] == expectedKeyOnValue);
        assertTrue(keyOn, "GM note " + gmNote + " must produce key-on: port-1 reg 0x00 = 0x"
                + Integer.toHexString(expectedKeyOnValue));
    }

    /** Returns level bits 4-0 from the per-channel level register (port-1 0x08+ch). -1 if not found. */
    private static int findAdpcmChannelLevel(byte[] data, int ch)
    {
        return findOpnbPort1Writes(data, 0x80).stream()
                .filter(w -> w[0] == 0x08 + ch)
                .mapToInt(w -> w[1] & 0x1F)
                .findFirst()
                .orElse(-1);
    }

    /** Finds the VGM data offset (including the 0x67 byte) of a data block with the given type. */
    private static int findDataBlockOffset(byte[] data, int type)
    {
        for (int i = 0x80; i < data.length - 6; i++)
        {
            if ((data[i] & 0xFF) == 0x67
                    && (data[i + 1] & 0xFF) == 0x66
                    && (data[i + 2] & 0xFF) == type)
                return i;
        }
        return -1;
    }

    /** Returns the offset of the first OPNB write (0x58 or 0x59) in the data section. */
    private static int findFirstOpnbWrite(byte[] data)
    {
        for (int i = 0x80; i < data.length - 2; i++)
        {
            int cmd = data[i] & 0xFF;
            if (cmd == 0x58 || cmd == 0x59)
                return i;
        }
        return -1;
    }

    /** Collects all OPNB port-1 writes (0x59 reg val), skipping over data blocks. */
    private static List<int[]> findOpnbPort1Writes(byte[] data, int start)
    {
        var result = new ArrayList<int[]>();
        int i = start;
        while (i < data.length - 2)
        {
            // Skip data blocks (0x67 0x66 type size[4] data[size])
            if ((data[i] & 0xFF) == 0x67 && i + 1 < data.length && (data[i + 1] & 0xFF) == 0x66)
            {
                // This is a data block; skip it
                i += 2; // skip 0x67 0x66
                if (i >= data.length)
                    break;
                i++; // skip type byte
                if (i + 3 >= data.length)
                    break;
                int blockSize = readInt32Le(data, i);
                i += 4 + blockSize; // skip size field and data
                continue;
            }

            if ((data[i] & 0xFF) == 0x59)
            {
                if (i + 2 < data.length)
                    result.add(new int[] { data[i + 1] & 0xFF, data[i + 2] & 0xFF });
                i += 3;
            }
            else
            {
                i++;
            }
        }
        return result;
    }

    private static int readInt32Le(byte[] data, int offset)
    {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }
}

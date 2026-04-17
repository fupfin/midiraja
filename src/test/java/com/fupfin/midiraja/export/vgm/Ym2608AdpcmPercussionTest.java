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
 * Spec-by-example tests for YM2608 ADPCM-A native percussion.
 *
 * <p>Register address mapping (VGM reg → ADPCM-A internal function):
 * <ul>
 *   <li>0x10 → key on/off (bit 7=0: key-on bits 5-0; 0xBF = all off)
 *   <li>0x11 → master total level (0x3F = max volume)
 *   <li>0x18+ch → per-channel L/R flags (bits 7-6) + individual level (bits 4-0)
 *   <li>0x20+ch → per-channel start address low (ROM byte addr >> 8)
 *   <li>0x28+ch → per-channel start address high (ROM byte addr >> 16; all 0 for 8KB ROM)
 *   <li>0x30+ch → per-channel end address low
 *   <li>0x38+ch → per-channel end address high
 * </ul>
 */
class Ym2608AdpcmPercussionTest
{
    private static CompositeVgmExporter composite()
    {
        return new CompositeVgmExporter(ChipHandlers.create(ChipHandlers.PRESETS.get("pc98")));
    }

    // ── Group 1: VGM data block structure ─────────────────────────────────────
    //
    // YM2608 ADPCM-A uses an INTERNAL ROM hardcoded inside libvgm (fmopn_2608rom.h).
    // No VGM data block is needed or valid; the emulator ignores type 0x82 for a pc98 VGM
    // because the chip map resolves 0x82 to YM2610 (not YM2608). The correct behaviour
    // is to emit NO data block of any type in the data section.

    @Test
    void noRomDataBlock_emitted() throws Exception
    {
        byte[] data = exportToBytes(emptySeq());
        // Scan data section for any 0x67 0x66 data block command
        boolean hasDataBlock = false;
        for (int i = 0x80; i < data.length - 2; i++)
        {
            if ((data[i] & 0xFF) == 0x67 && (data[i + 1] & 0xFF) == 0x66)
            {
                hasDataBlock = true;
                break;
            }
        }
        assertFalse(hasDataBlock,
                "YM2608 ADPCM-A ROM is internal; VGM stream must not contain any ROM data block");
    }

    // ── Group 2: initSilence ADPCM setup (spec by example) ────────────────────

    @Test
    void initSilence_masterLevelIsMax() throws Exception
    {
        byte[] data = exportToBytes(emptySeq());
        boolean found = findOpnaWrites(data, 0x80).stream()
                .anyMatch(w -> w[0] == 0x11 && w[1] == 0x3F);
        assertTrue(found, "initSilence must write master level 0x3F to reg 0x11");
    }

    @Test
    void initSilence_allChannelsKeyOff() throws Exception
    {
        byte[] data = exportToBytes(emptySeq());
        boolean found = findOpnaWrites(data, 0x80).stream()
                .anyMatch(w -> w[0] == 0x10 && w[1] == 0xBF);
        assertTrue(found, "initSilence must write 0xBF to reg 0x10 (all channels key-off)");
    }

    @Test
    void initSilence_ch0StartAddrLow() throws Exception
    {
        // ch0 Bass Drum: ROM addr 0x0000 → start low = 0x0000 >> 8 = 0x00
        boolean found = findOpnaWrites(exportToBytes(emptySeq()), 0x80).stream()
                .anyMatch(w -> w[0] == 0x20 && w[1] == 0x00);
        assertTrue(found, "ch0 Bass Drum start low (reg 0x20) must be 0x00");
    }

    @Test
    void initSilence_ch1StartAddrLow() throws Exception
    {
        // ch1 Snare: ROM addr 0x01C0 → start low = 0x01C0 >> 8 = 0x01
        boolean found = findOpnaWrites(exportToBytes(emptySeq()), 0x80).stream()
                .anyMatch(w -> w[0] == 0x21 && w[1] == 0x01);
        assertTrue(found, "ch1 Snare start low (reg 0x21) must be 0x01");
    }

    @Test
    void initSilence_ch2StartAddrLow() throws Exception
    {
        // ch2 Top Cymbal: ROM addr 0x0440 → start low = 0x0440 >> 8 = 0x04
        boolean found = findOpnaWrites(exportToBytes(emptySeq()), 0x80).stream()
                .anyMatch(w -> w[0] == 0x22 && w[1] == 0x04);
        assertTrue(found, "ch2 Top Cymbal start low (reg 0x22) must be 0x04");
    }

    @Test
    void initSilence_ch3StartAddrLow() throws Exception
    {
        // ch3 High Hat: ROM addr 0x1B80 → start low = 0x1B80 >> 8 = 0x1B
        boolean found = findOpnaWrites(exportToBytes(emptySeq()), 0x80).stream()
                .anyMatch(w -> w[0] == 0x23 && w[1] == 0x1B);
        assertTrue(found, "ch3 High Hat start low (reg 0x23) must be 0x1B");
    }

    @Test
    void initSilence_ch4StartAddrLow() throws Exception
    {
        // ch4 Tom Tom: ROM addr 0x1D00 → start low = 0x1D00 >> 8 = 0x1D
        boolean found = findOpnaWrites(exportToBytes(emptySeq()), 0x80).stream()
                .anyMatch(w -> w[0] == 0x24 && w[1] == 0x1D);
        assertTrue(found, "ch4 Tom Tom start low (reg 0x24) must be 0x1D");
    }

    @Test
    void initSilence_ch5StartAddrLow() throws Exception
    {
        // ch5 Rim Shot: ROM addr 0x1F80 → start low = 0x1F80 >> 8 = 0x1F
        boolean found = findOpnaWrites(exportToBytes(emptySeq()), 0x80).stream()
                .anyMatch(w -> w[0] == 0x25 && w[1] == 0x1F);
        assertTrue(found, "ch5 Rim Shot start low (reg 0x25) must be 0x1F");
    }

    @Test
    void initSilence_ch2EndAddrLow() throws Exception
    {
        // ch2 Top Cymbal end: ROM addr 0x1B7F → end low = 0x1B7F >> 8 = 0x1B
        boolean found = findOpnaWrites(exportToBytes(emptySeq()), 0x80).stream()
                .anyMatch(w -> w[0] == 0x32 && w[1] == 0x1B);
        assertTrue(found, "ch2 Top Cymbal end low (reg 0x32) must be 0x1B");
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
        // reg 0x18+0 = 0x18 for Bass Drum (ch0); bits 7-6 must be 0b11 (L+R enabled)
        boolean stereoSet = findOpnaWrites(data, 0x80).stream()
                .filter(w -> w[0] == 0x18)
                .anyMatch(w -> (w[1] & 0xC0) == 0xC0);
        assertTrue(stereoSet, "Per-channel level reg 0x18 must have L+R stereo flags (bits 7-6 = 0b11)");
    }

    // ── Group 5: Boundary & exception conditions ───────────────────────────────

    @Test
    void velocityZero_noKeyOn() throws Exception
    {
        byte[] data = exportPercussion(36, 0);
        // velocity=0 must not trigger key-on for ch0 (bit 0 of reg 0x10)
        boolean hasKeyOn = findOpnaWrites(data, 0x80).stream()
                .anyMatch(w -> w[0] == 0x10 && w[1] == 0x01);
        assertFalse(hasKeyOn, "velocity=0 must not write key-on 0x01 to reg 0x10");
    }

    @Test
    void unmappedNote_60_ignored() throws Exception
    {
        byte[] data = exportPercussion(60, 100);
        // Middle C has no ADPCM mapping — no key-on write to reg 0x10 beyond initSilence 0xBF
        boolean hasUnexpectedKeyOn = findOpnaWrites(data, 0x80).stream()
                .anyMatch(w -> w[0] == 0x10 && w[1] != 0xBF);
        assertFalse(hasUnexpectedKeyOn, "Unmapped note 60 must not trigger ADPCM key-on");
        assertEquals('V', data[0], "Must still produce valid VGM");
    }

    @Test
    void unmappedNote_0_ignored() throws Exception
    {
        byte[] data = exportPercussion(0, 100);
        boolean hasUnexpectedKeyOn = findOpnaWrites(data, 0x80).stream()
                .anyMatch(w -> w[0] == 0x10 && w[1] != 0xBF);
        assertFalse(hasUnexpectedKeyOn, "Unmapped note 0 must not trigger ADPCM key-on");
    }

    @Test
    void unmappedNote_127_ignored() throws Exception
    {
        byte[] data = exportPercussion(127, 100);
        boolean hasUnexpectedKeyOn = findOpnaWrites(data, 0x80).stream()
                .anyMatch(w -> w[0] == 0x10 && w[1] != 0xBF);
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
    void percussionDoesNotUseSlot5() throws Exception
    {
        // With ADPCM percussion, all 6 FM slots are melodic; BD + 6 melodic notes should all fit
        var seq = new Sequence(Sequence.PPQ, 480);
        Track track = seq.createTrack();
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 9, 36, 100), 0)); // Bass Drum
        for (int i = 0; i < 6; i++)
            track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 60 + i, 80), 0));
        var out = new ByteArrayOutputStream();
        assertDoesNotThrow(() -> composite().export(seq, out));
        assertEquals('V', out.toByteArray()[0]);
    }

    @Test
    void slotCount_is6_allMelodic() throws Exception
    {
        var ym2608 = ChipHandlers.create(ChipHandlers.PRESETS.get("pc98")).stream()
                .filter(h -> h.chipType() == ChipType.YM2608)
                .findFirst()
                .orElseThrow();
        assertEquals(8, ym2608.slotCount(),
                "YM2608 must expose 8 melodic slots (6 FM + 2 SSG; ADPCM-A percussion is separate hardware)");
    }

    @Test
    void percussionPriority_is3() throws Exception
    {
        var ym2608 = ChipHandlers.create(ChipHandlers.PRESETS.get("pc98")).stream()
                .filter(h -> h.chipType() == ChipType.YM2608)
                .findFirst()
                .orElseThrow();
        assertEquals(3, ym2608.percussionPriority(),
                "YM2608 must have percussion priority 3 (ADPCM-A native rhythm section)");
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
        boolean keyOn = findOpnaWrites(data, 0x80).stream()
                .anyMatch(w -> w[0] == 0x10 && w[1] == expectedKeyOnValue);
        assertTrue(keyOn, "GM note " + gmNote + " must produce key-on: reg 0x10 = 0x"
                + Integer.toHexString(expectedKeyOnValue));
    }

    /** Returns level bits 4-0 from the per-channel level register (0x18+ch). -1 if not found. */
    private static int findAdpcmChannelLevel(byte[] data, int ch)
    {
        return findOpnaWrites(data, 0x80).stream()
                .filter(w -> w[0] == 0x18 + ch)
                .mapToInt(w -> w[1] & 0x1F)
                .findFirst()
                .orElse(-1);
    }

    /** Collects all OPNA port-0 writes (0x56 reg val) found after the given start offset. */
    private static List<int[]> findOpnaWrites(byte[] data, int start)
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

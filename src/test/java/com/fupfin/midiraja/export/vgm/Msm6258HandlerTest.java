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

class Msm6258HandlerTest
{
    private static CompositeVgmExporter composite()
    {
        return new CompositeVgmExporter(ChipHandlers.create(List.of(ChipType.MSM6258)));
    }

    private static byte[] export(Sequence seq) throws Exception
    {
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        return out.toByteArray();
    }

    private static int readInt32Le(byte[] data, int offset)
    {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    private static Sequence emptySeq() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack();
        return seq;
    }

    private static Sequence percSeq(int note, int velocity) throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        Track track = seq.createTrack();
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 9, note, velocity), 0));
        return seq;
    }

    // ── Header validation ─────────────────────────────────────────────────────

    @Test
    void export_validVgmHeader() throws Exception
    {
        byte[] data = export(emptySeq());
        assertEquals('V', data[0]);
        assertEquals('g', data[1]);
        assertEquals('m', data[2]);
        assertEquals(' ', data[3]);
        assertEquals(0x66, data[data.length - 1] & 0xFF);
    }

    @Test
    void export_msm6258ClockPatched() throws Exception
    {
        byte[] data = export(emptySeq());
        assertEquals(VgmWriter.MSM6258_CLOCK, readInt32Le(data, 0x90));
    }

    @Test
    void export_msm6258FlagsSet() throws Exception
    {
        byte[] data = export(emptySeq());
        assertEquals(0x02, data[0x94] & 0xFF);
    }

    // ── initSilence structure ─────────────────────────────────────────────────

    @Test
    void initSilence_writesDacStreamSetup() throws Exception
    {
        byte[] data = export(emptySeq());
        List<int[]> cmds = parseCommands(data);
        boolean found = cmds.stream().anyMatch(c -> c[0] == 0x90);
        assertTrue(found, "Expected DAC stream setup command 0x90 in VGM data");
    }

    @Test
    void initSilence_writesFrequency() throws Exception
    {
        byte[] data = export(emptySeq());
        List<int[]> cmds = parseCommands(data);
        boolean found = cmds.stream().anyMatch(c -> c[0] == 0x92
                && readInt32Le(data, c[1] + 2) == VgmWriter.MSM6258_SAMPLE_RATE);
        assertTrue(found, "Expected 0x92 command with 15625 Hz frequency");
    }

    @Test
    void initSilence_writesCommandPlay() throws Exception
    {
        byte[] data = export(emptySeq());
        // 0xB7 <reg=0x00> <data=0x02> — COMMAND_PLAY, required to enable audio output
        boolean found = false;
        for (int i = 0xC0; i < data.length - 2; i++)
        {
            if ((data[i] & 0xFF) == 0xB7
                    && (data[i + 1] & 0xFF) == 0x00
                    && (data[i + 2] & 0xFF) == 0x02)
            {
                found = true;
                break;
            }
        }
        assertTrue(found, "Expected 0xB7 0x00 0x02 (MSM6258 COMMAND_PLAY) in VGM data");
    }

    @Test
    void initSilence_writes7PcmDataBlocks() throws Exception
    {
        byte[] data = export(emptySeq());
        // Count data block commands with type=0x04
        int count = 0;
        for (int i = 0xC0; i < data.length - 6; i++)
        {
            if ((data[i] & 0xFF) == 0x67
                    && (data[i + 1] & 0xFF) == 0x66
                    && (data[i + 2] & 0xFF) == 0x04)
            {
                count++;
                int len = readInt32Le(data, i + 3);
                i += 6 + len - 1; // skip past payload; loop i++ advances by 1 more
            }
        }
        assertEquals(7, count, "Expected 7 PCM data blocks (0x67 0x66 0x04) in VGM data");
    }

    // ── Note on / block index mapping ─────────────────────────────────────────

    @Test
    void noteOn_bassDrum_playsBlock0() throws Exception
    {
        assertPlaysBlock(35, 0);
        assertPlaysBlock(36, 0);
    }

    @Test
    void noteOn_snare_playsBlock1() throws Exception
    {
        assertPlaysBlock(38, 1);
        assertPlaysBlock(40, 1);
    }

    @Test
    void noteOn_crash_playsBlock2() throws Exception
    {
        assertPlaysBlock(49, 2);
    }

    @Test
    void noteOn_closedHiHat_playsBlock3() throws Exception
    {
        assertPlaysBlock(42, 3);
        assertPlaysBlock(44, 3);
    }

    @Test
    void noteOn_tom_playsBlock4() throws Exception
    {
        assertPlaysBlock(41, 4);
    }

    @Test
    void noteOn_rimShot_playsBlock5() throws Exception
    {
        assertPlaysBlock(37, 5);
        assertPlaysBlock(39, 5);
    }

    @Test
    void noteOn_openHiHat_playsBlock6() throws Exception
    {
        assertPlaysBlock(46, 6);
    }

    // ── Velocity-zero stop ────────────────────────────────────────────────────

    @Test
    void velocityZero_stopsStream() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        Track track = seq.createTrack();
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 9, 36, 100), 0));
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 9, 36, 0), 480));

        byte[] data = export(seq);

        // After the note-off, 0x94 (stop) must appear
        List<int[]> cmds = parseCommands(data);
        boolean found = cmds.stream().anyMatch(c -> c[0] == 0x94);
        assertTrue(found, "Expected DAC stream stop command 0x94");
    }

    // ── Unmapped note ─────────────────────────────────────────────────────────

    @Test
    void unmappedNote_ignored() throws Exception
    {
        byte[] data = export(percSeq(60, 100));

        // The only 0x95 commands should be absent — note 60 has no drum mapping
        List<int[]> cmds = parseCommands(data);
        boolean found = cmds.stream().anyMatch(c -> c[0] == 0x95);
        assertFalse(found, "Unmapped note 60 must not emit a 0x95 play-block command");
    }

    // ── slotCount / percussionPriority ────────────────────────────────────────

    @Test
    void slotCount_isZero()
    {
        assertEquals(0, new Msm6258Handler().slotCount());
    }

    @Test
    void percussionPriority_is3()
    {
        assertEquals(3, new Msm6258Handler().percussionPriority());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void assertPlaysBlock(int note, int expectedBlock) throws Exception
    {
        byte[] data = export(percSeq(note, 100));
        List<int[]> cmds = parseCommands(data);

        for (int[] cmd : cmds)
        {
            if (cmd[0] == 0x95)
            {
                int pos = cmd[1];
                int blockIdx = (data[pos + 2] & 0xFF) | ((data[pos + 3] & 0xFF) << 8);
                assertEquals(expectedBlock, blockIdx,
                        "Note " + note + " should play block " + expectedBlock);
                return;
            }
        }
        fail("No 0x95 play-block command found for note " + note);
    }

    /**
     * Parses the VGM command stream (starting at offset 0xC0) and returns a list of
     * {@code int[]{command, offset}} entries. Data block payloads are skipped so that
     * arbitrary byte values inside them are never mistaken for commands.
     */
    private static List<int[]> parseCommands(byte[] data)
    {
        var result = new ArrayList<int[]>();
        int i = 0xC0; // v1.70 header is 0xC0 bytes
        while (i < data.length)
        {
            int cmd = data[i] & 0xFF;
            result.add(new int[] { cmd, i });

            if (cmd == 0x66)
                break; // end of data

            if (cmd == 0x67)
            {
                // Data block: 0x67 0x66 <type> <len:4LE> <payload>
                if (i + 6 < data.length)
                {
                    int len = readInt32Le(data, i + 3);
                    i += 7 + len; // 0x67 0x66 type(1) len(4) + payload
                }
                else break;
                continue;
            }

            // Advance by command size
            i += commandSize(cmd);
        }
        return result;
    }

    /** Returns the total byte size (including command byte) of a VGM command. */
    private static int commandSize(int cmd)
    {
        return switch (cmd & 0xF0)
        {
            case 0x30 -> 2; // 0x30-0x3F: 1 operand
            case 0x40 -> 2; // 0x4F, 0x50: 1 operand
            case 0x50 -> 2; // PSG write
            case 0x60 ->
            {
                if (cmd == 0x61) yield 3; // wait nn nn
                if (cmd == 0x62 || cmd == 0x63) yield 1; // wait 735/882
                if (cmd == 0x66) yield 1; // end
                if (cmd == 0x68) yield 12; // PCM RAM write
                yield 1; // 0x64, 0x65 reserved
            }
            case 0x70 -> 1; // 0x70-0x7F: wait n+1 samples
            case 0x80 -> 1; // 0x80-0x8F: YM2612 write + wait
            case 0x90 ->
            {
                if (cmd == 0x90) yield 5;
                if (cmd == 0x91) yield 5;
                if (cmd == 0x92) yield 6;
                if (cmd == 0x93) yield 11;
                if (cmd == 0x94) yield 2;
                if (cmd == 0x95) yield 5;
                yield 1;
            }
            case 0xA0 -> 3; // AY8910 / generic 2-operand
            case 0xB0 -> 3; // OPL2, RF5C68, etc.
            case 0xC0, 0xD0 -> 4; // 3-operand commands
            case 0xE0, 0xF0 -> 5; // 4-operand commands
            default -> 1;
        };
    }
}

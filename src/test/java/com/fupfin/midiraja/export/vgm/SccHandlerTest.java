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

class SccHandlerTest
{
    private static final int GM_BASS_DRUM_1 = 35;
    private static final int GM_BASS_DRUM_2 = 36;
    private static final int GM_SNARE_1 = 38;
    private static final int GM_CLOSED_HIHAT = 42;

    // ── supportsRhythm ────────────────────────────────────────────────────────

    @Test
    void supportsRhythm_returnsTrue()
    {
        assertTrue(new SccHandler().supportsRhythm());
    }

    // ── handlePercussion — waveform written to slot 4 ────────────────────────

    /**
     * Exports a MIDI percussion note and returns the raw VGM bytes.
     * The VGM header for SCC is 0xC0 bytes; chip writes start at 0xC0.
     */
    private static byte[] percussionVgm(int midiNote, int velocity) throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        Track track = seq.createTrack();
        // Channel 9 = MIDI percussion channel
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 9, midiNote, velocity), 0));

        var out = new ByteArrayOutputStream();
        new CompositeVgmExporter(
                ChipHandlers.create(List.of(ChipType.SCC)),
                RoutingMode.SEQUENTIAL).export(seq, out);
        return out.toByteArray();
    }

    /** Collects (reg, data) pairs for 0xD2 (SCC write) commands from VGM bytes. */
    private static List<int[]> sccWrites(byte[] vgm)
    {
        var writes = new ArrayList<int[]>();
        // SCC header size = 0xC0 bytes
        int pos = 0xC0;
        while (pos < vgm.length - 1)
        {
            int cmd = vgm[pos] & 0xFF;
            if (cmd == 0xD2) // SCC write: D2 pp rr dd
            {
                if (pos + 3 < vgm.length)
                {
                    int reg = vgm[pos + 2] & 0xFF;
                    int data = vgm[pos + 3] & 0xFF;
                    writes.add(new int[] { reg, data });
                }
                pos += 4;
            }
            else if (cmd == 0x66) // end-of-data
            {
                break;
            }
            else if (cmd == 0x61) // wait nn samples (16-bit)
            {
                pos += 3;
            }
            else
            {
                pos += 1;
            }
        }
        return writes;
    }

    @Test
    void bassDrum_writesToPercSlotWaveformMemory() throws Exception
    {
        byte[] vgm = percussionVgm(GM_BASS_DRUM_1, 100);
        var writes = sccWrites(vgm);

        // Waveform for slot 4 starts at reg 0x80 (= PERC_SLOT * 32 = 4 * 32 = 128)
        boolean hasPercWaveWrite = writes.stream().anyMatch(w -> w[0] >= 0x80 && w[0] < 0xA0);
        assertTrue(hasPercWaveWrite, "Bass drum should write waveform to slot 4 (regs 0x80-0x9F)");
    }

    @Test
    void snare_writesToPercSlotWaveformMemory() throws Exception
    {
        byte[] vgm = percussionVgm(GM_SNARE_1, 80);
        var writes = sccWrites(vgm);

        boolean hasPercWaveWrite = writes.stream().anyMatch(w -> w[0] >= 0x80 && w[0] < 0xA0);
        assertTrue(hasPercWaveWrite, "Snare should write waveform to slot 4 (regs 0x80-0x9F)");
    }

    @Test
    void hihat_writesToPercSlotWaveformMemory() throws Exception
    {
        byte[] vgm = percussionVgm(GM_CLOSED_HIHAT, 70);
        var writes = sccWrites(vgm);

        boolean hasPercWaveWrite = writes.stream().anyMatch(w -> w[0] >= 0x80 && w[0] < 0xA0);
        assertTrue(hasPercWaveWrite, "Hi-hat should write waveform to slot 4 (regs 0x80-0x9F)");
    }

    @Test
    void bassDrum_setsVolumeForPercSlot() throws Exception
    {
        byte[] vgm = percussionVgm(GM_BASS_DRUM_1, 127);
        var writes = sccWrites(vgm);

        // Volume register for slot 4 = 0xAA + 4 = 0xAE
        boolean hasVolWrite = writes.stream().anyMatch(w -> w[0] == 0xAE && w[1] > 0);
        assertTrue(hasVolWrite, "Bass drum should set non-zero volume at reg 0xAE");
    }

    @Test
    void bassDrum_enablesPercSlotInMask() throws Exception
    {
        byte[] vgm = percussionVgm(GM_BASS_DRUM_1, 100);
        var writes = sccWrites(vgm);

        // Enable mask at 0xAF; bit 4 = slot 4
        boolean hasEnableMask = writes.stream()
                .anyMatch(w -> w[0] == 0xAF && (w[1] & 0x10) != 0);
        assertTrue(hasEnableMask, "Bass drum should set bit 4 in enable mask (reg 0xAF)");
    }

    @Test
    void zeroVelocity_silencesPercSlot() throws Exception
    {
        byte[] vgm = percussionVgm(GM_BASS_DRUM_2, 0);
        // velocity=0 should be treated as note-off and not trigger percussion;
        // no waveform write expected
        var writes = sccWrites(vgm);
        boolean hasPercWaveWrite = writes.stream().anyMatch(w -> w[0] >= 0x80 && w[0] < 0xA0);
        assertFalse(hasPercWaveWrite, "Zero-velocity percussion note-on should not write waveform");
    }
}

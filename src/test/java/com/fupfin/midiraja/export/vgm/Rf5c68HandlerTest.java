/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.export.vgm;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.util.List;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

import org.junit.jupiter.api.Test;

class Rf5c68HandlerTest
{
    private static CompositeVgmExporter composite()
    {
        return new CompositeVgmExporter(ChipHandlers.create(ChipHandlers.parseChips("rf5c68")));
    }

    private static CompositeVgmExporter fmTowns()
    {
        return new CompositeVgmExporter(ChipHandlers.create(new ChipSpec(
                List.of(ChipType.YM3812, ChipType.RF5C68), RoutingMode.SEQUENTIAL)));
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
                new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 9, 36, 100), 0));
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
    void export_rf5c68ClockPatched() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack();
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();
        assertEquals(VgmWriter.RF5C68_CLOCK, readInt32Le(data, 0x40));
    }

    @Test
    void fmTowns_rf5c68ClockPatched() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack();
        var out = new ByteArrayOutputStream();
        fmTowns().export(seq, out);
        byte[] data = out.toByteArray();
        assertEquals(VgmWriter.RF5C68_CLOCK, readInt32Le(data, 0x40));
    }

    // ── PCM data blocks ───────────────────────────────────────────────────────

    @Test
    void initSilence_writesRamDataBlock_type0xC0() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack();
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        // RAM data block header: 0x67 0x66 0xC0 (type=0xC0 = RF5C68 wave RAM write)
        boolean foundBlock = false;
        int headerEnd = 0x80; // v1.61 header size
        for (int i = headerEnd; i < data.length - 2; i++)
        {
            if ((data[i] & 0xFF) == 0x67 && (data[i + 1] & 0xFF) == 0x66
                    && (data[i + 2] & 0xFF) == 0xC0)
            {
                foundBlock = true;
                break;
            }
        }
        assertTrue(foundBlock, "Expected RAM data block type 0xC0 for RF5C68 wave RAM write");
    }

    @Test
    void initSilence_writesChipEnable() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack();
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        // Chip enable: [0xB0, 0x07, 0x80] — RF5C68 reg 0x07 bit7=1 enables the chip
        boolean foundEnable = false;
        for (int i = 0; i < data.length - 2; i++)
        {
            if ((data[i] & 0xFF) == 0xB0 && (data[i + 1] & 0xFF) == 0x07
                    && (data[i + 2] & 0xFF) == 0x80)
            {
                foundEnable = true;
                break;
            }
        }
        assertTrue(foundEnable, "Expected RF5C68 chip enable write [0xB0, 0x07, 0x80]");
    }

    @Test
    void initSilence_writesChannelConfig() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack();
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        // Channel select for channel 0: [0xB0, 0x07, 0xC0] — bit7=chip_enable, bit6=ch_sel, ch=0
        boolean foundChSel = false;
        for (int i = 0; i < data.length - 2; i++)
        {
            if ((data[i] & 0xFF) == 0xB0 && (data[i + 1] & 0xFF) == 0x07
                    && (data[i + 2] & 0xFF) == 0xC0)
            {
                foundChSel = true;
                break;
            }
        }
        assertTrue(foundChSel, "Expected RF5C68 channel-select write [0xB0, 0x07, 0xC0] for ch 0");
    }

    // ── Percussion ────────────────────────────────────────────────────────────

    @Test
    void percussion_bassDrum_enablesChannel0() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack().add(
                new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 9, 36, 100), 0));
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        // Bass drum → channel 0; triggering must write [0xB0, 0x08, X] where bit 0 of X is clear
        boolean foundEnable = false;
        for (int i = 0; i < data.length - 2; i++)
        {
            if ((data[i] & 0xFF) == 0xB0 && (data[i + 1] & 0xFF) == 0x08
                    && (data[i + 2] & 0x01) == 0)
            {
                foundEnable = true;
                break;
            }
        }
        assertTrue(foundEnable,
                "Bass drum (MIDI 36) must write [0xB0, 0x08, X] with bit 0 clear (channel 0 enabled)");
    }

    @Test
    void percussion_snare_enablesChannel1() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack().add(
                new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 9, 38, 100), 0));
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        // Snare → channel 1; triggering must write [0xB0, 0x08, X] where bit 1 of X is clear
        boolean foundEnable = false;
        for (int i = 0; i < data.length - 2; i++)
        {
            if ((data[i] & 0xFF) == 0xB0 && (data[i + 1] & 0xFF) == 0x08
                    && (data[i + 2] & 0x02) == 0)
            {
                foundEnable = true;
                break;
            }
        }
        assertTrue(foundEnable,
                "Snare (MIDI 38) must write [0xB0, 0x08, X] with bit 1 clear (channel 1 enabled)");
    }

    @Test
    void percussion_noteOff_disablesChannel() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        Track track = seq.createTrack();
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 9, 36, 100), 0));
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 9, 36, 0), 480));
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        // Note-off must write [0xB0, 0x08, X] where bit 0 of X is set (channel 0 disabled)
        // after a wait command — look for the pattern after any wait
        boolean foundDisable = false;
        boolean seenWait = false;
        for (int i = 0; i < data.length - 2; i++)
        {
            int b = data[i] & 0xFF;
            if ((b >= 0x61 && b <= 0x63) || (b >= 0x70 && b <= 0x7F))
                seenWait = true;
            if (seenWait && b == 0xB0 && (data[i + 1] & 0xFF) == 0x08
                    && (data[i + 2] & 0x01) != 0)
            {
                foundDisable = true;
                break;
            }
        }
        assertTrue(foundDisable,
                "Note-off must emit [0xB0, 0x08, X] with bit 0 set (channel 0 disabled)");
    }

    @Test
    void percussion_unmappedNote_isIgnored() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack().add(
                new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 9, 100, 100), 0));
        var out = new ByteArrayOutputStream();
        assertDoesNotThrow(() -> composite().export(seq, out),
                "Unmapped percussion note must not throw");
    }

    // ── FM Towns preset ───────────────────────────────────────────────────────

    @Test
    void fmTowns_preset_hasYm3812AndRf5c68()
    {
        var chips = ChipHandlers.PRESETS.get("fm-towns");
        assertNotNull(chips, "fm-towns preset must exist");
        assertEquals(List.of(ChipType.YM3812, ChipType.RF5C68), chips);
    }

    @Test
    void fmTowns_doesNotCrash() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        Track track = seq.createTrack();
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 60, 80), 0));
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 9, 36, 100), 0));
        var out = new ByteArrayOutputStream();
        assertDoesNotThrow(() -> fmTowns().export(seq, out));
        assertEquals('V', out.toByteArray()[0]);
    }
}

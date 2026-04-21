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

class Ym2151HandlerTest
{
    private static CompositeVgmExporter composite()
    {
        return new CompositeVgmExporter(ChipHandlers.create(List.of(ChipType.YM2151)));
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
    void export_ym2151ClockPatched() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack();
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();
        assertEquals(VgmWriter.YM2151_CLOCK, readInt32Le(data, 0x30));
    }

    // ── initSilence ───────────────────────────────────────────────────────────

    @Test
    void initSilence_writesKeyOffForAll8Channels() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack();
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        // key-off = 0x54 0x08 0xXX where upper bits = 0 (opMask=0), lower 3 bits = ch
        List<int[]> writes = findOpmWrites(data, 0x80);
        long keyOffCount = writes.stream()
                .filter(w -> w[0] == 0x08 && (w[1] >> 3) == 0)
                .count();
        assertTrue(keyOffCount >= 8, "initSilence must key-off all 8 channels via reg 0x08");
    }

    // ── Note on/off ───────────────────────────────────────────────────────────

    @Test
    void noteOn_producesOpmWrites() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack().add(
                new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 69, 100), 0));
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        List<int[]> writes = findOpmWrites(data, 0x80);
        assertFalse(writes.isEmpty(), "Expected OPM write commands (0x54) for note on");
    }

    @Test
    void noteOn_doesNotUseYm2612OrOpl3Commands() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack().add(
                new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 69, 100), 0));
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        // Walk the VGM command stream properly so that data bytes inside valid 0x54
        // commands are not mistaken for command bytes.
        for (int i = 0x80; i < data.length; )
        {
            int cmd = data[i] & 0xFF;
            if (cmd == 0x66)
                break; // end of data
            // YM2612 commands: 0x52/0x53; OPL3 commands: 0x5E/0x5F
            assertFalse(cmd == 0x52 || cmd == 0x53 || cmd == 0x5E || cmd == 0x5F,
                    "YM2151 must use 0x54 commands only, found unexpected command: 0x"
                            + Integer.toHexString(cmd) + " at offset " + i);
            // Advance by command length:
            // 3-byte chip-write commands: 0x50-0x5F
            // 3-byte wait: 0x61
            // 1-byte short wait: 0x62/0x63/0x70-0x7F
            if (cmd >= 0x50 && cmd <= 0x5F) i += 3;
            else if (cmd == 0x61) i += 3;
            else i += 1;
        }
    }

    @Test
    void noteOn_setsKeyOnViaReg0x08() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack().add(
                new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 69, 100), 0));
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        // key-on: reg 0x08, upper 4 bits (opMask) = 0xF (all operators)
        List<int[]> writes = findOpmWrites(data, 0x80);
        boolean keyOn = writes.stream()
                .anyMatch(w -> w[0] == 0x08 && (w[1] >> 3) == 0xF);
        assertTrue(keyOn, "Expected key-on: reg 0x08 with opMask=0xF (upper bits)");
    }

    @Test
    void noteOff_keysOffViaReg0x08() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        Track track = seq.createTrack();
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 69, 100), 0));
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, 69, 0), 480));
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        List<int[]> writes = findOpmWrites(data, 0x80);
        // After note-on, a key-off (opMask=0) on a valid channel must follow
        boolean keyOff = writes.stream()
                .anyMatch(w -> w[0] == 0x08 && (w[1] >> 3) == 0 && (w[1] & 0x07) < 8);
        assertTrue(keyOff, "Expected key-off: reg 0x08 with opMask=0");
    }

    // ── Voice stealing ────────────────────────────────────────────────────────

    @Test
    void voiceStealing_doesNotCrash() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        Track track = seq.createTrack();
        for (int i = 0; i < 10; i++) // more than 8 slots
            track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 48 + i, 80), 0));
        var out = new ByteArrayOutputStream();
        assertDoesNotThrow(() -> composite().export(seq, out));
        assertEquals('V', out.toByteArray()[0]);
    }

    // ── Percussion ────────────────────────────────────────────────────────────

    @Test
    void percussion_playsFmPatch() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        Track track = seq.createTrack();
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 9, 36, 100), 0));
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 9, 36, 0), 480));

        var out = new ByteArrayOutputStream();
        assertDoesNotThrow(() -> composite().export(seq, out));
        byte[] data = out.toByteArray();

        // Valid VGM header
        assertEquals('V', data[0]);

        // Percussion produces key-on via reg 0x08 with opMask=0xF
        List<int[]> writes = findOpmWrites(data, 0x80);
        boolean keyOn = writes.stream().anyMatch(w -> w[0] == 0x08 && (w[1] >> 3) == 0xF);
        assertTrue(keyOn, "Percussion note on drum ch9 should produce key-on (reg 0x08, opMask=0xF)");
    }

    // ── updatePitch ───────────────────────────────────────────────────────────

    @Test
    void updatePitch_changesKcKfWithoutKeyOff() throws Exception
    {
        var handler = new Ym2151Handler();
        var chips = List.of(ChipType.YM2151);
        var out = new ByteArrayOutputStream();
        try (var w = new VgmWriter(out, chips))
        {
            handler.startNote(0, 69, 100, 0, w); // A4
        }
        List<int[]> before = findOpmWrites(out.toByteArray(), 0x80);
        int kcCountBefore = (int) before.stream().filter(wr -> wr[0] == 0x28).count();

        out = new ByteArrayOutputStream();
        try (var w = new VgmWriter(out, chips))
        {
            handler.startNote(0, 69, 100, 0, w);
            handler.updatePitch(0, 69, 8192 + 4096, 2, w); // ~1 semitone up
        }
        List<int[]> after = findOpmWrites(out.toByteArray(), 0x80);

        // Additional KC write (reg 0x28) from updatePitch
        int kcCountAfter = (int) after.stream().filter(wr -> wr[0] == 0x28).count();
        assertTrue(kcCountAfter > kcCountBefore,
                "updatePitch must write KC register (0x28) again");

        // Must not emit a second key-on (reg 0x08, opMask=0xF)
        long extraKeyOns = after.stream()
                .filter(wr -> wr[0] == 0x08 && (wr[1] >> 3) == 0xF).count();
        assertEquals(1, extraKeyOns,
                "updatePitch must not retrigger the envelope (only one key-on)");
    }

    @Test
    void updateVolume_changesTlWithoutKeyOff() throws Exception
    {
        var handler = new Ym2151Handler();
        var chips = List.of(ChipType.YM2151);
        var out = new ByteArrayOutputStream();
        try (var w = new VgmWriter(out, chips))
        {
            handler.startNote(0, 69, 127, 0, w);
            handler.updateVolume(0, 64, w);
        }
        List<int[]> writes = findOpmWrites(out.toByteArray(), 0x80);

        // TL register base is 0x60; operators at offsets 0, 8, 16, 24 + slot
        boolean hasTlWrite = writes.stream().anyMatch(wr -> wr[0] >= 0x60 && wr[0] <= 0x77);
        assertTrue(hasTlWrite, "updateVolume must write OPM TL registers (0x60+)");

        // No second key-on
        long keyOns = writes.stream().filter(wr -> wr[0] == 0x08 && (wr[1] >> 3) == 0xF).count();
        assertEquals(1, keyOns, "updateVolume must not retrigger the envelope");
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private static List<int[]> findOpmWrites(byte[] data, int start)
    {
        var result = new ArrayList<int[]>();
        for (int i = start; i < data.length - 2; i++)
        {
            if ((data[i] & 0xFF) == 0x54)
                result.add(new int[] { data[i + 1] & 0xFF, data[i + 2] & 0xFF });
        }
        return result;
    }
}

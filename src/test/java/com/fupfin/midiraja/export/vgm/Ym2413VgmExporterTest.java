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

class Ym2413VgmExporterTest
{
    private static CompositeVgmExporter composite()
    {
        return new CompositeVgmExporter(ChipHandlers.create(ChipHandlers.PRESETS.get("fmpac")));
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
    void export_ym2413ClockPatched() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack();
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();
        assertEquals(VgmWriter.YM2413_CLOCK, readInt32Le(data, 0x10));
    }

    // ── Note on/off ───────────────────────────────────────────────────────────

    @Test
    void noteOn_writesInstrumentAndVolume() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack().add(
                new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 69, 127), 0));
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        // 0x30 slot0 = instrument|vol; velocity 127 → vol=0 (0 = max on YM2413)
        List<int[]> writes = findYm2413Writes(data, 0x40);
        boolean foundInstVol = writes.stream()
                .anyMatch(w -> w[0] == 0x30); // slot 0 instrument/volume register
        assertTrue(foundInstVol, "Expected YM2413 register 0x30 write for instrument/volume");
    }

    @Test
    void noteOn_setsKeyOnBit() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack().add(
                new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 69, 100), 0));
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        // 0x20 slot0 with bit 4 (0x10) set = key on
        List<int[]> writes = findYm2413Writes(data, 0x40);
        boolean keyOn = writes.stream()
                .anyMatch(w -> w[0] == 0x20 && (w[1] & 0x10) != 0);
        assertTrue(keyOn, "Expected YM2413 register 0x20 with key-on bit set");
    }

    @Test
    void noteOff_clearsKeyOnBit() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        Track track = seq.createTrack();
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 69, 100), 0));
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, 69, 0), 480));
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        // After note off, there should be a write to 0x20 with 0 (key off)
        List<int[]> writes = findYm2413Writes(data, 0x40);
        boolean keyOff = writes.stream()
                .anyMatch(w -> w[0] == 0x20 && w[1] == 0);
        assertTrue(keyOff, "Expected YM2413 register 0x20 = 0 (key off)");
    }

    @Test
    void noteOnVelocityZero_treatedAsNoteOff() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        Track track = seq.createTrack();
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 69, 100), 0));
        // NOTE_ON with vel=0 is note-off
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 69, 0), 480));
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        List<int[]> writes = findYm2413Writes(data, 0x40);
        boolean keyOff = writes.stream()
                .anyMatch(w -> w[0] == 0x20 && w[1] == 0);
        assertTrue(keyOff, "NOTE_ON velocity=0 must act as NOTE_OFF");
    }

    @Test
    void programChange_affectsInstrument() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        Track track = seq.createTrack();
        // Program 8 (Celesta) → YM2413 instrument 12 (Vibraphone)
        track.add(new MidiEvent(new ShortMessage(ShortMessage.PROGRAM_CHANGE, 0, 8, 0), 0));
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 69, 100), 0));
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        List<int[]> writes = findYm2413Writes(data, 0x40);
        // Instrument 12 → upper nibble of 0x30 = 12 << 4 = 0xC0
        boolean foundInst12 = writes.stream()
                .anyMatch(w -> w[0] == 0x30 && ((w[1] >> 4) & 0x0F) == 12);
        assertTrue(foundInst12, "Program 8 (Celesta) should map to YM2413 instrument 12 (Vibraphone)");
    }

    // ── Rhythm mode ───────────────────────────────────────────────────────────

    @Test
    void midiCh9_enablesRhythmMode() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack().add(
                new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 9, 36, 100), 0));
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        // Rhythm mode enable = reg 0x0E = 0x20
        List<int[]> writes = findYm2413Writes(data, 0x40);
        boolean rhythmEnabled = writes.stream()
                .anyMatch(w -> w[0] == 0x0E && (w[1] & 0x20) != 0);
        assertTrue(rhythmEnabled, "MIDI ch9 should enable rhythm mode (reg 0x0E bit5)");
    }

    @Test
    void percussion_noteOff_clearsKeyOnBit() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        Track track = seq.createTrack();
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 9, 36, 100), 0));
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 9, 36, 0), 480));
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        // After note-off, 0x0E must be written with bass drum bit (bit4) cleared
        List<int[]> writes = findYm2413Writes(data, 0x40);
        // Find the last 0x0E write — should NOT have bit4 set
        var lastRhythm = writes.stream()
                .filter(w -> w[0] == 0x0E)
                .reduce((a, b) -> b); // last one
        assertTrue(lastRhythm.isPresent(), "Expected 0x0E writes for percussion");
        assertEquals(0x20, lastRhythm.get()[1] & 0xFF,
                "After note-off, 0x0E must equal 0x20 (rhythm enabled, no key-on bits)");
    }

    @Test
    void percussion_retrigger_producesRisingEdge() throws Exception
    {
        // Two consecutive bass drum hits without a note-off between them must each trigger
        var seq = new Sequence(Sequence.PPQ, 480);
        Track track = seq.createTrack();
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 9, 36, 100), 0));
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 9, 36, 100), 480));
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        // For a rising edge on the second hit, 0x0E must be written with bit4=0 before bit4=1
        List<int[]> writes = findYm2413Writes(data, 0x40);
        List<int[]> rhythmWrites = writes.stream().filter(w -> w[0] == 0x0E).toList();
        // Expect at least 3 writes: initial set, clear, set-again
        assertTrue(rhythmWrites.size() >= 3,
                "Retrigger requires at least 3 0x0E writes (set, clear, set); got " + rhythmWrites.size());
    }

    @Test
    void percussion_snare_writesVolume0x37() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack().add(
                new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 9, 38, 100), 0));
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        List<int[]> writes = findYm2413Writes(data, 0x40);
        boolean found0x37 = writes.stream().anyMatch(w -> w[0] == 0x37);
        assertTrue(found0x37, "Snare drum note must write volume register 0x37");
    }

    @Test
    void percussion_cymbal_writesVolume0x38() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack().add(
                new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 9, 49, 100), 0));
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        List<int[]> writes = findYm2413Writes(data, 0x40);
        boolean found0x38 = writes.stream().anyMatch(w -> w[0] == 0x38);
        assertTrue(found0x38, "Cymbal note must write volume register 0x38");
    }

    @Test
    void voiceStealing_doesNotCrash() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        Track track = seq.createTrack();
        for (int i = 0; i < 8; i++) // >6 melodic slots → stealing
            track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 60 + i, 80), 0));
        var out = new ByteArrayOutputStream();
        assertDoesNotThrow(() -> composite().export(seq, out));
        assertEquals('V', out.toByteArray()[0]);
    }

    // ── Volume range ──────────────────────────────────────────────────────────

    @Test
    void highVelocity127_producesMinimumVolume0() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack().add(
                new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 69, 127), 0));
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        // velocity=127 → vol = 15 - (int)round(127 * 15.0 / 127.0) = 15 - 15 = 0
        List<int[]> writes = findYm2413Writes(data, 0x40);
        boolean foundMaxVol = writes.stream()
                .anyMatch(w -> w[0] == 0x30 && (w[1] & 0x0F) == 0);
        assertTrue(foundMaxVol, "velocity=127 should produce volume=0 (max on YM2413)");
    }

    @Test
    void lowVelocity1_producesHighVolume15() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack().add(
                new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 69, 1), 0));
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        // velocity=1 → vol = 15 - (int)round(1 * 15.0 / 127.0) = 15 - 0 = 15
        List<int[]> writes = findYm2413Writes(data, 0x40);
        boolean foundMinVol = writes.stream()
                .anyMatch(w -> w[0] == 0x30 && (w[1] & 0x0F) == 15);
        assertTrue(foundMinVol, "velocity=1 should produce volume=15 (near silent on YM2413)");
    }

    @Test
    void mediumVelocity64_producesMiddleVolume() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack().add(
                new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 69, 64), 0));
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        // velocity=64 → vol = 15 - (int)round(64 * 15.0 / 127.0) = 15 - 8 = 7
        List<int[]> writes = findYm2413Writes(data, 0x40);
        boolean foundMidVol = writes.stream()
                .anyMatch(w -> w[0] == 0x30 && (w[1] & 0x0F) == 7);
        assertTrue(foundMidVol, "velocity=64 should produce volume=7 (middle range)");
    }

    @Test
    void rhythmHighVelocity127_producesMinimumVolume0() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack().add(
                new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 9, 36, 127), 0));
        var out = new ByteArrayOutputStream();
        composite().export(seq, out);
        byte[] data = out.toByteArray();

        // For bass drum, register 0x36 has vol in both nibbles
        // vol = 15 - (int)round(127 * 15.0 / 127.0) = 0
        List<int[]> writes = findYm2413Writes(data, 0x40);
        boolean foundMaxVol = writes.stream()
                .anyMatch(w -> w[0] == 0x36 && (w[1] & 0x0F) == 0);
        assertTrue(foundMaxVol, "rhythm velocity=127 should produce volume=0 (max)");
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private static List<int[]> findYm2413Writes(byte[] data, int start)
    {
        var result = new ArrayList<int[]>();
        for (int i = start; i < data.length - 2; i++)
        {
            if ((data[i] & 0xFF) == 0x51)
                result.add(new int[] { data[i + 1] & 0xFF, data[i + 2] & 0xFF });
        }
        return result;
    }
}

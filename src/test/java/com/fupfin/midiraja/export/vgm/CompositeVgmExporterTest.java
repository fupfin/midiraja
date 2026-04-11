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
 * Tests for {@link CompositeVgmExporter} routing strategies.
 *
 * <p>Chip write VGM commands used here:
 * <ul>
 *   <li>0xA0 rr dd — AY8910 register write
 *   <li>0xD2 pp rr dd — SCC register write (port=0)
 * </ul>
 * <p>SCC melodic slot frequency dividers are at regs 0xA0-0xA9.
 * SCC initSilence only writes to 0x00-0x7F (waveforms) and 0xAA-0xAF (volumes/mask), so any
 * 0xD2 write to reg 0xA0-0xA9 after init means a note was routed to SCC.
 */
class CompositeVgmExporterTest
{
    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Sequence sequence(MidiEvent... events) throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        Track track = seq.createTrack();
        for (var e : events)
            track.add(e);
        return seq;
    }

    private static MidiEvent noteOn(int ch, int note, int vel) throws Exception
    {
        return new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, ch, note, vel), 0);
    }

    private static MidiEvent programChange(int ch, int prog) throws Exception
    {
        return new MidiEvent(new ShortMessage(ShortMessage.PROGRAM_CHANGE, ch, prog, 0), 0);
    }

    /**
     * Returns (reg, data) pairs for all AY8910 writes (VGM 0xA0 commands) found in {@code vgm}
     * starting from the data area offset.
     * The 0xA0 commands for "register write to chip 0" have bit 7 of the register byte = 0.
     */
    private static List<int[]> ayWrites(byte[] vgm, int headerSize)
    {
        var result = new ArrayList<int[]>();
        for (int i = headerSize; i < vgm.length - 2; i++)
        {
            if ((vgm[i] & 0xFF) == 0xA0 && (vgm[i + 1] & 0x80) == 0)
                result.add(new int[] { vgm[i + 1] & 0xFF, vgm[i + 2] & 0xFF });
        }
        return result;
    }

    /**
     * Returns (reg, data) pairs for all SCC register writes (VGM 0xD2 port-0 commands)
     * found in {@code vgm} starting from the data area offset.
     */
    private static List<int[]> sccWrites(byte[] vgm, int headerSize)
    {
        var result = new ArrayList<int[]>();
        for (int i = headerSize; i < vgm.length - 3; i++)
        {
            if ((vgm[i] & 0xFF) == 0xD2 && (vgm[i + 1] & 0xFF) == 0x00)
            {
                result.add(new int[] { vgm[i + 2] & 0xFF, vgm[i + 3] & 0xFF });
                i += 3;
            }
        }
        return result;
    }

    // ── PSG-preferred routing (program 112-127 → AY8910 first) ───────────────

    @Test
    void psgPreferred_program112_routesToAy8910() throws Exception
    {
        // Chips: SCC + AY8910 in CHANNEL mode (SCC is handler 0)
        // Without PSG-preferred routing, channel 0 would go to SCC (first handler).
        // With PSG-preferred routing, program 112 should override this and go to AY8910.
        var handlers = ChipHandlers.create(List.of(ChipType.SCC, ChipType.AY8910));
        var exporter = new CompositeVgmExporter(handlers, RoutingMode.CHANNEL);

        var seq = sequence(programChange(0, 112), noteOn(0, 69, 100));
        var out = new ByteArrayOutputStream();
        exporter.export(seq, out);
        byte[] vgm = out.toByteArray();

        // SCC uses v1.70 header (0xC0 bytes)
        int headerSize = 0xC0;

        // No SCC melodic frequency write (regs 0xA0-0xA9) should appear — note went to AY8910
        boolean sccMelodicFreqWritten = sccWrites(vgm, headerSize).stream()
                .anyMatch(w -> w[0] >= 0xA0 && w[0] <= 0xA9);
        assertFalse(sccMelodicFreqWritten,
                "Program 112 note should route to AY8910, not SCC frequency registers");

        // AY8910 amplitude write (reg 8, 9, or 10) should appear with non-zero value
        boolean ayAmplitudeWritten = ayWrites(vgm, headerSize).stream()
                .anyMatch(w -> w[0] >= 8 && w[0] <= 10 && w[1] > 0);
        assertTrue(ayAmplitudeWritten, "Program 112 note should write non-zero amplitude on AY8910");
    }

    @Test
    void psgPreferred_program0_routesToSccInChannelMode() throws Exception
    {
        // Program 0 (not PSG-preferred) with CHANNEL mode: channel 0 → SCC (handler 0)
        var handlers = ChipHandlers.create(List.of(ChipType.SCC, ChipType.AY8910));
        var exporter = new CompositeVgmExporter(handlers, RoutingMode.CHANNEL);

        var seq = sequence(programChange(0, 0), noteOn(0, 69, 100));
        var out = new ByteArrayOutputStream();
        exporter.export(seq, out);
        byte[] vgm = out.toByteArray();

        int headerSize = 0xC0;

        // SCC frequency register write should appear — note went to SCC
        boolean sccMelodicFreqWritten = sccWrites(vgm, headerSize).stream()
                .anyMatch(w -> w[0] >= 0xA0 && w[0] <= 0xA9);
        assertTrue(sccMelodicFreqWritten,
                "Program 0 with channel mode should route channel 0 to SCC (handler 0)");
    }

    // ── SEQUENTIAL mode ───────────────────────────────────────────────────────

    @Test
    void sequentialMode_fillsFirstHandlerBeforeSecond() throws Exception
    {
        // SCC has 5 melodic slots + 1 percussion slot (but perc slot is slot 4).
        // With SEQUENTIAL, first 4 melodic notes should go to SCC (slots 0-3), not AY8910.
        var handlers = ChipHandlers.create(List.of(ChipType.SCC, ChipType.AY8910));
        var exporter = new CompositeVgmExporter(handlers, RoutingMode.SEQUENTIAL);

        // Program 0 to avoid PSG-preferred routing
        var seq = sequence(
                programChange(0, 0), programChange(1, 0), programChange(2, 0), programChange(3, 0),
                noteOn(0, 60, 80), noteOn(1, 62, 80), noteOn(2, 64, 80), noteOn(3, 65, 80));
        var out = new ByteArrayOutputStream();
        exporter.export(seq, out);
        byte[] vgm = out.toByteArray();

        int headerSize = 0xC0;

        // Should find SCC frequency writes for multiple SCC slots (0xA0, 0xA2, 0xA4, 0xA6)
        long sccSlotCount = sccWrites(vgm, headerSize).stream()
                .filter(w -> w[0] >= 0xA0 && w[0] <= 0xA7)
                .mapToInt(w -> w[0])
                .distinct()
                .count();
        assertTrue(sccSlotCount >= 4,
                "SEQUENTIAL mode should fill at least 4 SCC slots for 4 simultaneous notes");
    }

    @Test
    void sequentialMode_overflowNotesGoToSecondHandler() throws Exception
    {
        // SCC has 5 slots (4 melodic + 1 perc), fill them all then overflow to AY8910
        // Use 6 notes on distinct channels with program 0
        var handlers = ChipHandlers.create(List.of(ChipType.SCC, ChipType.AY8910));
        var exporter = new CompositeVgmExporter(handlers, RoutingMode.SEQUENTIAL);

        List<MidiEvent> events = new ArrayList<>();
        for (int i = 0; i < 6; i++)
        {
            events.add(programChange(i, 0));
            events.add(noteOn(i, 60 + i, 80));
        }
        var seq = new Sequence(Sequence.PPQ, 480);
        Track track = seq.createTrack();
        for (var e : events)
            track.add(e);

        var out = new ByteArrayOutputStream();
        exporter.export(seq, out);
        byte[] vgm = out.toByteArray();

        int headerSize = 0xC0;

        // The 6th note should overflow to AY8910; look for AY8910 amplitude write
        boolean ayAmplitudeWritten = ayWrites(vgm, headerSize).stream()
                .anyMatch(w -> w[0] >= 8 && w[0] <= 10 && w[1] > 0);
        assertTrue(ayAmplitudeWritten,
                "SEQUENTIAL mode: 6th note should overflow to AY8910 when SCC is full");
    }

    // ── CHANNEL mode ──────────────────────────────────────────────────────────

    @Test
    void channelMode_differentChannelsRouteToSeparateHandlers() throws Exception
    {
        // Chips: SCC (handler 0) + AY8910 (handler 1), CHANNEL mode
        // Channel 0 → first round-robin → handler 0 (SCC)
        // Channel 1 → second round-robin → handler 1 (AY8910)
        var handlers = ChipHandlers.create(List.of(ChipType.SCC, ChipType.AY8910));
        var exporter = new CompositeVgmExporter(handlers, RoutingMode.CHANNEL);

        // Use program 0 (not PSG-preferred) to test pure channel routing
        var seq = sequence(
                programChange(0, 0), programChange(1, 0),
                noteOn(0, 60, 80), // ch0 → SCC
                noteOn(1, 64, 80)  // ch1 → AY8910
        );
        var out = new ByteArrayOutputStream();
        exporter.export(seq, out);
        byte[] vgm = out.toByteArray();

        int headerSize = 0xC0;

        // Both SCC (ch0) and AY8910 (ch1) writes should appear for their respective notes
        boolean sccFreqWritten = sccWrites(vgm, headerSize).stream()
                .anyMatch(w -> w[0] >= 0xA0 && w[0] <= 0xA9);
        boolean ayAmplitudeWritten = ayWrites(vgm, headerSize).stream()
                .anyMatch(w -> w[0] >= 8 && w[0] <= 10 && w[1] > 0);

        assertTrue(sccFreqWritten, "Channel 0 note should route to SCC in CHANNEL mode");
        assertTrue(ayAmplitudeWritten, "Channel 1 note should route to AY8910 in CHANNEL mode");
    }
}

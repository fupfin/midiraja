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
 *   <li>0xD2 pp rr dd — SCC register write (port, reg, data)
 * </ul>
 * <p>K051649 (SCC) uses port-based dispatch:
 * <ul>
 *   <li>port 0 — waveform RAM (reg 0x00-0x9F)
 *   <li>port 1 — frequency dividers (reg 0-9, 2 bytes per channel)
 *   <li>port 2 — volumes (reg 0-4)
 *   <li>port 3 — channel enable mask
 * </ul>
 * SCC initSilence only writes port=0 (waveforms) and port=2/3 (volumes/mask), so any
 * 0xD2 write with port=1 after init means a note was routed to SCC.
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
     * Returns (port, reg, data) triples for all SCC register writes (VGM 0xD2 commands)
     * found in {@code vgm} starting from the data area offset.
     */
    private static List<int[]> sccWrites(byte[] vgm, int headerSize)
    {
        var result = new ArrayList<int[]>();
        for (int i = headerSize; i < vgm.length - 3; i++)
        {
            if ((vgm[i] & 0xFF) == 0xD2)
            {
                result.add(new int[] { vgm[i + 1] & 0xFF, vgm[i + 2] & 0xFF, vgm[i + 3] & 0xFF });
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

        // No SCC frequency write (port=1) should appear — note went to AY8910
        boolean sccMelodicFreqWritten = sccWrites(vgm, headerSize).stream()
                .anyMatch(w -> w[0] == 1);
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

        // SCC frequency write (port=1) should appear — note went to SCC
        boolean sccMelodicFreqWritten = sccWrites(vgm, headerSize).stream()
                .anyMatch(w -> w[0] == 1);
        assertTrue(sccMelodicFreqWritten,
                "Program 0 with channel mode should route channel 0 to SCC (handler 0)");
    }

    // ── SEQUENTIAL mode ───────────────────────────────────────────────────────

    @Test
    void sequentialMode_fillsFirstHandlerBeforeSecond() throws Exception
    {
        // SCC has 5 melodic slots; AY8910 handles percussion via its noise channel.
        // With SEQUENTIAL, first 3 melodic notes should go to SCC (slots 0-2), not AY8910.
        var handlers = ChipHandlers.create(List.of(ChipType.SCC, ChipType.AY8910));
        var exporter = new CompositeVgmExporter(handlers, RoutingMode.SEQUENTIAL);

        // Program 0 to avoid PSG-preferred routing
        var seq = sequence(
                programChange(0, 0), programChange(1, 0), programChange(2, 0),
                noteOn(0, 60, 80), noteOn(1, 62, 80), noteOn(2, 64, 80));
        var out = new ByteArrayOutputStream();
        exporter.export(seq, out);
        byte[] vgm = out.toByteArray();

        int headerSize = 0xC0;

        // Should find SCC frequency writes (port=1) for 3 SCC slots (reg 0,2,4 = lo bytes)
        long sccSlotCount = sccWrites(vgm, headerSize).stream()
                .filter(w -> w[0] == 1 && w[1] % 2 == 0) // port=1, even reg = lo-byte of slot
                .mapToInt(w -> w[1])
                .distinct()
                .count();
        assertTrue(sccSlotCount >= 3,
                "SEQUENTIAL mode should fill at least 3 SCC slots for 3 simultaneous notes");
    }

    @Test
    void sequentialMode_overflowNotesGoToSecondHandler() throws Exception
    {
        // SCC has 5 melodic slots; overflow to AY8910's 2 melodic slots; use 7 notes total
        var handlers = ChipHandlers.create(List.of(ChipType.SCC, ChipType.AY8910));
        var exporter = new CompositeVgmExporter(handlers, RoutingMode.SEQUENTIAL);

        List<MidiEvent> events = new ArrayList<>();
        for (int i = 0; i < 7; i++)
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

        // Notes 6-7 overflow to AY8910 once SCC's 5 slots are full
        boolean ayAmplitudeWritten = ayWrites(vgm, headerSize).stream()
                .anyMatch(w -> w[0] >= 8 && w[0] <= 10 && w[1] > 0);
        assertTrue(ayAmplitudeWritten,
                "SEQUENTIAL mode: notes beyond SCC's 5 slots should overflow to AY8910");
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
                .anyMatch(w -> w[0] == 1);
        boolean ayAmplitudeWritten = ayWrites(vgm, headerSize).stream()
                .anyMatch(w -> w[0] >= 8 && w[0] <= 10 && w[1] > 0);

        assertTrue(sccFreqWritten, "Channel 0 note should route to SCC in CHANNEL mode");
        assertTrue(ayAmplitudeWritten, "Channel 1 note should route to AY8910 in CHANNEL mode");
    }
}

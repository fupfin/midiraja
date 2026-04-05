/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.vgm;

import static com.fupfin.midiraja.vgm.FmMidiUtil.*;
import static com.fupfin.midiraja.vgm.VgmToMidiConverter.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Patch catalog for 4-operator FM chips (YM2612, YM2151, OPN family).
 *
 * <p>
 * <b>Silent detection:</b> Carrier TL ≥ 45 (band 3) is inaudible on real hardware.
 * Unlike 2-op OPL2, {@code alg 0-4 + fb=0} is a valid patch in 4-op FM.
 *
 * <p>
 * <b>Drum detection:</b> FM drums on 4-op chips use high self-feedback (≥ 5) for
 * noise-like timbres with very short key-on/off cycles. Detection combines three signals:
 * high feedback (noise texture), fast attack (AR ≥ 10), and short median duration
 * (&lt; 150 ticks). All three must be present to avoid false positives from
 * staccato melody or harsh-timbred lead patches.
 */
final class FourOpPatchCatalog extends FmPatchCatalog
{

    /** Duration threshold for drum detection (MIDI ticks at 960 ticks/sec). */
    private static final long DRUM_DURATION_THRESHOLD = 150;

    record PatchEvent(int timbreHint, int feedback, int modTl, int carTl,
            int carAr, int carDr, int note)
    {
    }

    /**
     * Builds a catalog by scanning 4-op FM chip events (YM2612, YM2151, OPN family).
     *
     * @param parsed
     *            the parsed VGM data
     * @param chipIds
     *            chip IDs to scan
     * @param clocks
     *            per-chip clocks indexed by chip ID
     * @param dividers
     *            per-chip FM frequency dividers indexed by chip ID
     */
    static FourOpPatchCatalog build(VgmParseResult parsed, int[] chipIds, long[] clocks,
            int[] dividers)
    {
        var chipStates = new ChipState[11];
        for (int id : chipIds)
        {
            if (id < chipStates.length)
            {
                chipStates[id] = new ChipState(id == CHIP_YM2151 ? 8 : 6);
            }
        }

        List<PatchEvent> events = new ArrayList<>();
        Map<Integer, List<Long>> sigDurLists = new HashMap<>();

        for (var event : parsed.events())
        {
            int chip = event.chip();
            ChipState st = (chip < chipStates.length) ? chipStates[chip] : null;
            if (st == null)
                continue;

            int addr = event.rawData()[0] & 0xFF;
            int data = event.rawData()[1] & 0xFF;
            long clock = (chip < clocks.length) ? clocks[chip] : 0;
            int divider = (chip < dividers.length) ? dividers[chip] : 144;
            long sampleOffset = event.sampleOffset();

            if (chip == CHIP_YM2151)
            {
                scanOpm(st, addr, data, sampleOffset, events, sigDurLists);
            }
            else
            {
                int portOffset = isPort1(chip) ? 3 : 0;
                scanOpn(st, addr, data, portOffset, clock, divider, sampleOffset,
                        events, sigDurLists);
            }
        }

        // Compute median duration per signature
        Map<Integer, Long> sigMedianDur = new HashMap<>();
        for (var entry : sigDurLists.entrySet())
        {
            var durs = entry.getValue();
            durs.sort(Long::compareTo);
            sigMedianDur.put(entry.getKey(), durs.get(durs.size() / 2));
        }

        return classify(events, sigMedianDur);
    }

    private static FourOpPatchCatalog classify(List<PatchEvent> events,
            Map<Integer, Long> sigDurations)
    {
        var catalog = new FourOpPatchCatalog();
        Map<Integer, List<Integer>> sigNotes = new HashMap<>();

        for (var e : events)
        {
            int sig = signature(e.timbreHint(), e.feedback(), e.modTl(), e.carTl(),
                    e.carAr(), e.carDr());
            if (e.note() >= 0)
            {
                sigNotes.computeIfAbsent(sig, k -> new ArrayList<>()).add(e.note());
            }
        }

        for (var entry : sigNotes.entrySet())
        {
            int sig = entry.getKey();
            var notes = entry.getValue();

            int fb = (sig >> 20) & 0xF;
            int carTlBand = (sig >> 12) & 0xF;
            int ar = (sig >> 8) & 0xF;

            // Carrier TL band ≥ 3: output attenuated > 41 dB, inaudible
            if (carTlBand >= 3)
            {
                catalog.signatureToProgram.put(sig, SILENT);
                continue;
            }

            // FM drum: noise-like timbre (high fb) + fast attack + short notes.
            // All three conditions prevent false positives:
            // fb ≥ 5 alone: could be harsh lead (e.g. distorted guitar)
            // short duration alone: could be staccato melody
            // high AR alone: could be any fast-attack melodic instrument
            long medianDur = sigDurations.getOrDefault(sig, Long.MAX_VALUE);
            if (fb >= 5 && ar >= 10 && medianDur < DRUM_DURATION_THRESHOLD)
            {
                catalog.signatureToProgram.put(sig, DRUM_EFFECT);
                continue;
            }

            catalog.signatureToProgram.put(sig, assignGmProgram(sig, notes));
        }

        return catalog;
    }

    // ---- OPN register scanning ----

    private static boolean isPort1(int chipId)
    {
        return chipId == CHIP_YM2612_PORT1
                || chipId == CHIP_YM2608_PORT1
                || chipId == CHIP_YM2610_PORT1;
    }

    private static void scanOpn(ChipState st, int addr, int data, int portOffset,
            long clock, int divider, long sampleOffset,
            List<PatchEvent> events,
            Map<Integer, List<Long>> sigDurLists)
    {
        if (addr >= 0x40 && addr <= 0x4F)
        {
            int op = (addr - 0x40) >> 2;
            int ch = (addr & 0x03) + portOffset;
            if (ch < st.channels)
                st.tl[ch][op] = data & 0x7F;
        }
        else if (addr >= 0x50 && addr <= 0x5F)
        {
            int op = (addr - 0x50) >> 2;
            int ch = (addr & 0x03) + portOffset;
            if (ch < st.channels)
                st.ar[ch][op] = data & 0x1F;
        }
        else if (addr >= 0x60 && addr <= 0x6F)
        {
            int op = (addr - 0x60) >> 2;
            int ch = (addr & 0x03) + portOffset;
            if (ch < st.channels)
                st.d1r[ch][op] = data & 0x1F;
        }
        else if (addr >= 0xB0 && addr <= 0xB2)
        {
            int ch = (addr - 0xB0) + portOffset;
            if (ch < st.channels)
            {
                st.algorithm[ch] = data & 0x07;
                st.feedback[ch] = (data >> 3) & 0x07;
            }
        }
        else if (addr >= 0xA4 && addr <= 0xA6)
        {
            int ch = (addr - 0xA4) + portOffset;
            if (ch < st.channels)
                st.fnumHigh[ch] = data;
        }
        else if (addr >= 0xA0 && addr <= 0xA2)
        {
            int ch = (addr - 0xA0) + portOffset;
            if (ch < st.channels)
                st.fnumLow[ch] = data;
        }
        else if (addr == 0x28)
        {
            int chSelect = data & 0x07;
            int ch = switch (chSelect)
            {
                case 0, 1, 2 -> chSelect;
                case 4, 5, 6 -> chSelect - 1;
                default -> -1;
            };
            if (ch < 0 || ch >= st.channels)
                return;
            boolean keyOn = (data & 0xF0) != 0;
            if (keyOn && !st.keyState[ch])
            {
                if (!isSilentCarrier(st.tl, st.algorithm, ch))
                {
                    int fnum = (st.fnumHigh[ch] & 0x07) << 8 | st.fnumLow[ch];
                    int block = (st.fnumHigh[ch] >> 3) & 0x07;
                    int note = Ym2612MidiConverter.opnNote(clock, fnum, block, divider);
                    if (note >= 0)
                    {
                        collectEvent(st, ch, note, events);
                    }
                }
                st.keyOnSample[ch] = sampleOffset;
                st.keyOnSig[ch] = computeSig(st, ch);
            }
            else if (!keyOn && st.keyState[ch])
            {
                recordDuration(st, ch, sampleOffset, sigDurLists);
            }
            st.keyState[ch] = keyOn;
        }
    }

    // ---- OPM register scanning ----

    private static void scanOpm(ChipState st, int addr, int data,
            long sampleOffset, List<PatchEvent> events,
            Map<Integer, List<Long>> sigDurLists)
    {
        if (addr >= 0x60 && addr <= 0x7F)
        {
            int op = (addr - 0x60) >> 3;
            int ch = addr & 0x07;
            if (ch < st.channels)
                st.tl[ch][op] = data & 0x7F;
        }
        else if (addr >= 0x80 && addr <= 0x9F)
        {
            int op = (addr - 0x80) >> 3;
            int ch = addr & 0x07;
            if (ch < st.channels)
                st.ar[ch][op] = data & 0x1F;
        }
        else if (addr >= 0xA0 && addr <= 0xBF)
        {
            int op = (addr - 0xA0) >> 3;
            int ch = addr & 0x07;
            if (ch < st.channels)
                st.d1r[ch][op] = data & 0x1F;
        }
        else if (addr >= 0x20 && addr <= 0x27)
        {
            int ch = addr & 0x07;
            st.algorithm[ch] = data & 0x07;
            st.feedback[ch] = (data >> 3) & 0x07;
        }
        else if (addr >= 0x28 && addr <= 0x2F)
        {
            int ch = addr & 0x07;
            if (ch < st.channels)
                st.kc[ch] = data;
        }
        else if (addr == 0x08)
        {
            int ch = data & 0x07;
            if (ch >= st.channels)
                return;
            boolean keyOn = (data & 0x78) != 0;
            if (keyOn && !st.keyState[ch])
            {
                if (!isSilentCarrier(st.tl, st.algorithm, ch))
                {
                    int note = opmNote(st.kc[ch]);
                    if (note >= 0)
                    {
                        collectEvent(st, ch, note, events);
                    }
                }
                st.keyOnSample[ch] = sampleOffset;
                st.keyOnSig[ch] = computeSig(st, ch);
            }
            else if (!keyOn && st.keyState[ch])
            {
                recordDuration(st, ch, sampleOffset, sigDurLists);
            }
            st.keyState[ch] = keyOn;
        }
    }

    // ---- Shared helpers ----

    private static int opmNote(int kcVal)
    {
        int octave = (kcVal >> 4) & 0x07;
        int noteCode = kcVal & 0x0F;
        int semitone = switch (noteCode)
        {
            case 0 -> 0;
            case 1 -> 1;
            case 2 -> 2;
            case 4 -> 3;
            case 5 -> 4;
            case 6 -> 5;
            case 8 -> 6;
            case 9 -> 7;
            case 10 -> 8;
            case 12 -> 9;
            case 13 -> 10;
            case 14 -> 11;
            default -> -1;
        };
        if (semitone < 0)
            return -1;
        int midiNote = octave * 12 + semitone + 13;
        return Math.clamp(midiNote, 0, 127);
    }

    private static int computeSig(ChipState st, int ch)
    {
        int alg = st.algorithm[ch];
        int hint = algorithmToTimbreHint(alg);
        int avgModTl = avgModulatorTl(st.tl, st.algorithm, ch);
        int[] cops = carrierOps(alg);
        int totalCarTl = 0, totalAr = 0, totalDr = 0;
        for (int op : cops)
        {
            totalCarTl += st.tl[ch][op];
            totalAr += st.ar[ch][op];
            totalDr += st.d1r[ch][op];
        }
        return signature(hint, st.feedback[ch], avgModTl,
                totalCarTl / cops.length, totalAr / cops.length / 2, totalDr / cops.length / 2);
    }

    private static void recordDuration(ChipState st, int ch, long sampleOffset,
            Map<Integer, List<Long>> sigDurLists)
    {
        long durSamples = sampleOffset - st.keyOnSample[ch];
        long durTicks = VgmToMidiConverter.toTick(durSamples);
        if (durTicks > 0)
        {
            sigDurLists.computeIfAbsent(st.keyOnSig[ch], k -> new ArrayList<>()).add(durTicks);
        }
    }

    private static void collectEvent(ChipState st, int ch, int note,
            List<PatchEvent> events)
    {
        int alg = st.algorithm[ch];
        int hint = algorithmToTimbreHint(alg);
        int avgModTl = avgModulatorTl(st.tl, st.algorithm, ch);
        int[] cops = carrierOps(alg);
        int totalCarTl = 0, totalAr = 0, totalDr = 0;
        for (int op : cops)
        {
            totalCarTl += st.tl[ch][op];
            totalAr += st.ar[ch][op];
            totalDr += st.d1r[ch][op];
        }
        events.add(new PatchEvent(hint, st.feedback[ch], avgModTl,
                totalCarTl / cops.length, totalAr / cops.length / 2,
                totalDr / cops.length / 2, note));
    }

    // ---- Inner state ----

    private static class ChipState
    {
        final int channels;
        final int[] fnumHigh;
        final int[] fnumLow;
        final int[] kc;
        final boolean[] keyState;
        final int[] algorithm;
        final int[] feedback;
        final int[][] tl;
        final int[][] ar;
        final int[][] d1r;
        final long[] keyOnSample;
        final int[] keyOnSig;

        ChipState(int channels)
        {
            this.channels = channels;
            fnumHigh = new int[channels];
            fnumLow = new int[channels];
            kc = new int[channels];
            keyState = new boolean[channels];
            algorithm = new int[channels];
            feedback = new int[channels];
            tl = new int[channels][4];
            ar = new int[channels][4];
            d1r = new int[channels][4];
            keyOnSample = new long[channels];
            keyOnSig = new int[channels];
            for (int[] row : tl)
                Arrays.fill(row, 127);
        }
    }
}

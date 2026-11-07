/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.format.vgm;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

/** Orchestrates VGM-to-MIDI conversion using chip-specific converters. */
public class VgmToMidiConverter
{

    // Chip IDs assigned by VgmParser based on the VGM command byte.
    // MIDI channel mapping: SN76489/AY8910 → ch 0-2 (tone), 9 (noise);
    // YM2612 → ch 3-8; SCC → ch 10-14;
    // YM2151 → ch 0-7 (standalone) or ch 3-8 (with YM2612);
    // YM2203/YM2608/YM2610 → FM on ch 3-8, SSG on ch 0-2 + 9.
    public static final int CHIP_SN76489 = 0; // 0x50
    public static final int CHIP_YM2612_PORT0 = 1; // 0x52
    public static final int CHIP_YM2612_PORT1 = 2; // 0x53
    public static final int CHIP_AY8910 = 3; // 0xA0
    public static final int CHIP_SCC = 4; // 0xD2
    public static final int CHIP_YM2151 = 5; // 0x54
    public static final int CHIP_YM2203 = 6; // 0x55 (OPN: 3 FM + SSG)
    public static final int CHIP_YM2608_PORT0 = 7; // 0x56 (OPNA: 6 FM + SSG)
    public static final int CHIP_YM2608_PORT1 = 8; // 0x57
    public static final int CHIP_YM2610_PORT0 = 9; // 0x58 (OPNB: 4 FM + SSG)
    public static final int CHIP_YM2610_PORT1 = 10; // 0x59
    public static final int CHIP_GAMEBOY_DMG = 11; // 0xB3
    public static final int CHIP_HUC6280 = 12; // 0xB9
    public static final int CHIP_YM2413 = 13; // 0x51 (OPLL)
    public static final int CHIP_YM3812 = 14; // 0x5A (OPL2)
    public static final int CHIP_YMF262_PORT0 = 15; // 0x5E (OPL3 port 0)
    public static final int CHIP_YMF262_PORT1 = 16; // 0x5F (OPL3 port 1)
    public static final int CHIP_NES_2A03 = 17; // 0xB4
    public static final int CHIP_YM2413_2 = 18; // 0xA1
    public static final int CHIP_YM2612_PORT0_2 = 19; // 0xA2
    public static final int CHIP_YM2612_PORT1_2 = 20; // 0xA3
    public static final int CHIP_YM2151_2 = 21; // 0xA4
    public static final int CHIP_YM2203_2 = 22; // 0xA5
    public static final int CHIP_YM2608_PORT0_2 = 23; // 0xA6
    public static final int CHIP_YM2608_PORT1_2 = 24; // 0xA7
    public static final int CHIP_YM2610_PORT0_2 = 25; // 0xA8
    public static final int CHIP_YM2610_PORT1_2 = 26; // 0xA9
    public static final int CHIP_YM3812_2 = 27; // 0xAA
    public static final int CHIP_YMF262_PORT0_2 = 28; // 0xAE
    public static final int CHIP_YMF262_PORT1_2 = 29; // 0xAF

    // PPQ=480 at 120 BPM → 960 ticks/second.
    // VGM timebase = 44100 Hz. Scale: tick = round(sampleOffset × 960 / 44100).
    // One NTSC frame (735 samples) = 735 × 960 / 44100 = exactly 16 ticks.
    // Sub-frame intra-frame register writes (arpeggios, vibrato) collapse into the same tick,
    // eliminating the "notes pouring" effect caused by rapid NoteOn/Off pairs.
    private static final int SECONDARY_SSG_CHANNEL_OFFSET = 10;
    private static final int PPQ = 480;
    private static final byte[] TEMPO_BYTES = { 0x07, (byte) 0xA1, 0x20 }; // 500000 µs = 120 BPM
    private static final long VGM_SAMPLE_RATE = 44100L;
    private static final long TICKS_PER_SECOND = 960L; // PPQ * (1_000_000 / TEMPO_µS) = 480 * 2

    public VgmToMidiConverter()
    {
        this(Set.of());
    }

    /**
     * @param mutedChannels
     *            retained for API compatibility; muting is now applied in playback/export
     *            pipelines instead of during VGM→MIDI conversion.
     */
    public VgmToMidiConverter(Set<Integer> mutedChannels)
    {
        // no-op
    }

    static long toTick(long sampleOffset)
    {
        return Math.round(sampleOffset * (double) TICKS_PER_SECOND / VGM_SAMPLE_RATE);
    }

    public Sequence convert(VgmParseResult parsed)
    {
        try
        {
            var sequence = new Sequence(Sequence.PPQ, PPQ);

            // Track 0: tempo + optional title
            var tempoTrack = sequence.createTrack();
            tempoTrack.add(new MidiEvent(new MetaMessage(0x51, TEMPO_BYTES, 3), 0));

            var title = buildTitle(parsed);
            if (title != null)
            {
                byte[] titleBytes = title.getBytes(StandardCharsets.UTF_8);
                tempoTrack.add(new MidiEvent(
                        new MetaMessage(0x03, titleBytes, titleBytes.length), 0));
            }

            // Tracks 1-15 for MIDI channels 0-14
            var tracks = new Track[15];
            for (int i = 0; i < 15; i++)
            {
                tracks[i] = sequence.createTrack();
            }

            // PSG channels (0-2): Square Lead (80) — closest GM match for square/pulse waves.
            // ch 9: GM 0 with isDrums flag for TSF drum mode activation.
            // FM and wavetable channels are assigned by converters or TrackRoleAssigner.
            for (int ch = 0; ch < 3; ch++)
            {
                tracks[ch].add(new MidiEvent(
                        new ShortMessage(ShortMessage.PROGRAM_CHANGE, ch, 80, 0), 0));
            }
            tracks[9].add(new MidiEvent(
                    new ShortMessage(ShortMessage.PROGRAM_CHANGE, 9, 0, 0), 0));

            var snConverter = new Sn76489MidiConverter();
            var ymConverter = new Ym2612MidiConverter();
            var ayConverter = new Ay8910MidiConverter();
            var sccConverter = new SccMidiConverter();
            // YM2151: 8 channels. When no YM2612 is present, use ch 0-7 (standalone arcade).
            int opmOffset = (parsed.ym2612Clock() == 0) ? 0 : 3;
            var opmConverter = new Ym2151MidiConverter(opmOffset);
            // OPN family: reuse Ym2612MidiConverter with chip-specific divider and port1 ID.
            var ym2203Conv = new Ym2612MidiConverter(72, -1); // single port, divider=72
            var ym2203Ssg = new Ay8910MidiConverter();
            var ym2608Conv = new Ym2612MidiConverter(144, CHIP_YM2608_PORT1);
            var ym2608Ssg = new Ay8910MidiConverter();
            var ym2610Conv = new Ym2612MidiConverter(144, CHIP_YM2610_PORT1);
            var ym2610Ssg = new Ay8910MidiConverter();
            var pceConverter = new HuC6280MidiConverter();
            var opllConverter = new Ym2413MidiConverter();
            var opllConverter2 = new Ym2413MidiConverter();
            var nesConverter = new Nes2A03MidiConverter();
            var gbConverter = new GameBoyDmgMidiConverter();
            var ymConverter2 = new Ym2612MidiConverter(144, CHIP_YM2612_PORT1_2);
            var opmConverter2 = new Ym2151MidiConverter(opmOffset);
            var ym2203Conv2 = new Ym2612MidiConverter(72, -1);
            var ym2203Ssg2 = new Ay8910MidiConverter(SECONDARY_SSG_CHANNEL_OFFSET);
            var ym2608Conv2 = new Ym2612MidiConverter(144, CHIP_YM2608_PORT1_2);
            var ym2608Ssg2 = new Ay8910MidiConverter(SECONDARY_SSG_CHANNEL_OFFSET);
            var ym2610Conv2 = new Ym2612MidiConverter(144, CHIP_YM2610_PORT1_2);
            var ym2610Ssg2 = new Ay8910MidiConverter(SECONDARY_SSG_CHANNEL_OFFSET);
            long opl2Clock = (parsed.ym3812Clock() != 0) ? parsed.ym3812Clock() : 3_579_545L;
            var opl2Converter = new Ym3812MidiConverter();
            var opl2Converter2 = new Ym3812MidiConverter();
            // OPL3: use clock/4 for OPL2-compatible frequency formula.
            // Very low notes (< 40 Hz) are shifted up by octaves in Ym3812MidiConverter.
            long opl3Clock = parsed.ymf262Clock() / 4;
            var opl3Port0Conv = new Ym3812MidiConverter(opl3Clock, 0);
            var opl3Port1Conv = new Ym3812MidiConverter(opl3Clock, 10);
            var opl3Port0Conv2 = new Ym3812MidiConverter(opl3Clock, 0);
            var opl3Port1Conv2 = new Ym3812MidiConverter(opl3Clock, 10);
            var opnDrums = new OpnAdpcmARhythmMidiConverter.DrumOutput();
            var ym2608Rhythm = new OpnAdpcmARhythmMidiConverter(0x10, opnDrums);
            var ym2610Rhythm = new OpnAdpcmARhythmMidiConverter(0x00, opnDrums);
            var ym2608Rhythm2 = new OpnAdpcmARhythmMidiConverter(0x10, opnDrums);
            var ym2610Rhythm2 = new OpnAdpcmARhythmMidiConverter(0x00, opnDrums);

            // Build FmPatchCatalog for all FM chips present
            if (parsed.ym3812Clock() > 0 || parsed.ymf262Clock() > 0)
            {
                int[] oplChips = { CHIP_YM3812, CHIP_YMF262_PORT0, CHIP_YMF262_PORT1 };
                long catalogClock = (parsed.ym3812Clock() != 0)
                        ? parsed.ym3812Clock()
                        : (parsed.ymf262Clock() != 0) ? parsed.ymf262Clock() / 4 : 3_579_545L;
                var oplCatalog = Opl2PatchCatalog.build(parsed, oplChips, catalogClock);
                opl2Converter.setPatchCatalog(oplCatalog);
                opl3Port0Conv.setPatchCatalog(oplCatalog);
                opl3Port1Conv.setPatchCatalog(oplCatalog);
                opl2Converter2.setPatchCatalog(oplCatalog);
                opl3Port0Conv2.setPatchCatalog(oplCatalog);
                opl3Port1Conv2.setPatchCatalog(oplCatalog);
            }
            if (parsed.ym2612Clock() > 0 || parsed.ym2203Clock() > 0
                    || parsed.ym2608Clock() > 0 || parsed.ym2610Clock() > 0)
            {
                int[] opnChips = { CHIP_YM2612_PORT0, CHIP_YM2612_PORT1,
                        CHIP_YM2203, CHIP_YM2608_PORT0, CHIP_YM2608_PORT1,
                        CHIP_YM2610_PORT0, CHIP_YM2610_PORT1 };
                long[] clocks = new long[11];
                int[] dividers = new int[11];
                clocks[CHIP_YM2612_PORT0] = parsed.ym2612Clock();
                clocks[CHIP_YM2612_PORT1] = parsed.ym2612Clock();
                clocks[CHIP_YM2203] = parsed.ym2203Clock();
                clocks[CHIP_YM2608_PORT0] = parsed.ym2608Clock();
                clocks[CHIP_YM2608_PORT1] = parsed.ym2608Clock();
                clocks[CHIP_YM2610_PORT0] = parsed.ym2610Clock();
                clocks[CHIP_YM2610_PORT1] = parsed.ym2610Clock();
                java.util.Arrays.fill(dividers, 144);
                dividers[CHIP_YM2203] = 72;
                var opnCatalog = FourOpPatchCatalog.build(parsed, opnChips, clocks, dividers);
                ymConverter.setPatchCatalog(opnCatalog);
                ym2203Conv.setPatchCatalog(opnCatalog);
                ym2608Conv.setPatchCatalog(opnCatalog);
                ym2610Conv.setPatchCatalog(opnCatalog);
                ymConverter2.setPatchCatalog(opnCatalog);
                ym2203Conv2.setPatchCatalog(opnCatalog);
                ym2608Conv2.setPatchCatalog(opnCatalog);
                ym2610Conv2.setPatchCatalog(opnCatalog);
            }
            if (parsed.ym2151Clock() > 0)
            {
                int[] opmChips = { CHIP_YM2151 };
                long[] clocks = new long[6];
                int[] dividers = new int[6];
                clocks[CHIP_YM2151] = parsed.ym2151Clock();
                dividers[CHIP_YM2151] = 64; // not used for OPM (KC-based)
                var opmCatalog = FourOpPatchCatalog.build(parsed, opmChips, clocks, dividers);
                opmConverter.setPatchCatalog(opmCatalog);
                opmConverter2.setPatchCatalog(opmCatalog);
            }

            var routed = tracks;

            // AY8910 and SN76489 share MIDI channels 0-2 and 9; a given VGM file contains
            // at most one of the two chips (MSX uses AY8910, Sega uses SN76489).
            for (var event : parsed.events())
            {
                long tick = toTick(event.sampleOffset());
                switch (event.chip())
                {
                    case CHIP_SN76489 -> snConverter.convert(event, routed, parsed.sn76489Clock(), tick);
                    case CHIP_YM2612_PORT0, CHIP_YM2612_PORT1 ->
                        ymConverter.convert(event, routed, parsed.ym2612Clock(), tick);
                    case CHIP_AY8910 -> ayConverter.convert(event, routed, parsed.ay8910Clock(), tick);
                    case CHIP_SCC -> sccConverter.convert(event, routed, parsed.sccClock(), tick);
                    case CHIP_YM2151 -> opmConverter.convert(event, routed, parsed.ym2151Clock(), tick);
                    case CHIP_YM2203 -> routeOpn(event, routed, tick, ym2203Conv, ym2203Ssg,
                            null, parsed.ym2203Clock(), parsed.ym2203Clock() / 2);
                    case CHIP_YM2608_PORT0 -> routeOpn(event, routed, tick, ym2608Conv, ym2608Ssg,
                            ym2608Rhythm, parsed.ym2608Clock(), parsed.ym2608Clock() / 4);
                    case CHIP_YM2608_PORT1 -> ym2608Conv.convert(event, routed, parsed.ym2608Clock(), tick);
                    case CHIP_YM2610_PORT0 -> routeOpn(event, routed, tick, ym2610Conv, ym2610Ssg,
                            null, parsed.ym2610Clock(), parsed.ym2610Clock() / 4);
                    case CHIP_YM2610_PORT1 -> routeOpnRhythmOrFm(event, routed, tick,
                            ym2610Conv, ym2610Rhythm, parsed.ym2610Clock());
                    case CHIP_HUC6280 -> pceConverter.convert(event, routed, parsed.huC6280Clock(), tick);
                    case CHIP_GAMEBOY_DMG -> gbConverter.convert(event, routed, parsed.gameBoyDmgClock(), tick);
                    case CHIP_NES_2A03 -> nesConverter.convert(event, routed, parsed.nes2A03Clock(), tick);
                    case CHIP_YM2413 -> opllConverter.convert(event, routed, parsed.ym2413Clock(), tick);
                    case CHIP_YM3812 -> opl2Converter.convert(event, routed, opl2Clock, tick);
                    case CHIP_YMF262_PORT0 -> opl3Port0Conv.convert(event, routed, opl3Clock, tick);
                    case CHIP_YMF262_PORT1 -> opl3Port1Conv.convert(event, routed, opl3Clock, tick);
                    case CHIP_YM2413_2 -> opllConverter2.convert(event, routed, parsed.ym2413Clock(), tick);
                    case CHIP_YM2612_PORT0_2, CHIP_YM2612_PORT1_2 ->
                        ymConverter2.convert(event, routed, parsed.ym2612Clock(), tick);
                    case CHIP_YM2151_2 -> opmConverter2.convert(event, routed, parsed.ym2151Clock(), tick);
                    case CHIP_YM2203_2 -> routeOpn(event, routed, tick, ym2203Conv2, ym2203Ssg2,
                            null, parsed.ym2203Clock(), parsed.ym2203Clock() / 2);
                    case CHIP_YM2608_PORT0_2 -> routeOpn(event, routed, tick, ym2608Conv2, ym2608Ssg2,
                            ym2608Rhythm2, parsed.ym2608Clock(), parsed.ym2608Clock() / 4);
                    case CHIP_YM2608_PORT1_2 -> ym2608Conv2.convert(event, routed, parsed.ym2608Clock(), tick);
                    case CHIP_YM2610_PORT0_2 -> routeOpn(event, routed, tick, ym2610Conv2, ym2610Ssg2,
                            null, parsed.ym2610Clock(), parsed.ym2610Clock() / 4);
                    case CHIP_YM2610_PORT1_2 -> routeOpnRhythmOrFm(event, routed, tick,
                            ym2610Conv2, ym2610Rhythm2, parsed.ym2610Clock());
                    case CHIP_YM3812_2 -> opl2Converter2.convert(event, routed, opl2Clock, tick);
                    case CHIP_YMF262_PORT0_2 -> opl3Port0Conv2.convert(event, routed, opl3Clock, tick);
                    case CHIP_YMF262_PORT1_2 -> opl3Port1Conv2.convert(event, routed, opl3Clock, tick);
                    default ->
                        {
                        } // unknown chip, skip
                }
            }

            // 2nd pass: assign GM programs.
            // Volatile FM (OPL2/OPL3 note-pool drivers) → uniform Grand Piano on all channels.
            // Stable FM (YM2612/YM2151/OPN) → converters already emitted per-note Program Change;
            // TrackRoleAssigner fills in remaining channels (PSG, wavetable) that have no PC.
            // Volatile FM with patch catalog: OPL converters handle their own PC.
            // Stable FM: 4-op converters handle their own PC.
            // Both cases: fill in remaining channels (PSG, wavetable) without PC.
            TrackRoleAssigner.assignUnassigned(sequence);

            return sequence;
        }
        catch (InvalidMidiDataException e)
        {
            throw new IllegalStateException("Failed to create MIDI sequence", e);
        }
    }

    private static @org.jspecify.annotations.Nullable String buildTitle(VgmParseResult parsed)
    {
        var chips = new java.util.ArrayList<String>();
        if (parsed.sn76489Clock() > 0)
            chips.add("SN76489");
        if (parsed.ym2612Clock() > 0)
            chips.add("YM2612");
        if (parsed.ym2151Clock() > 0)
            chips.add("YM2151");
        if (parsed.ym2413Clock() > 0)
            chips.add("YM2413");
        if (parsed.ym2203Clock() > 0)
            chips.add("YM2203");
        if (parsed.ym2608Clock() > 0)
            chips.add("YM2608");
        if (parsed.ym2610Clock() > 0)
            chips.add("YM2610");
        if (parsed.ym3812Clock() > 0)
            chips.add("YM3812");
        if (parsed.ymf262Clock() > 0)
            chips.add("YMF262");
        if (parsed.ay8910Clock() > 0)
            chips.add("AY-3-8910");
        // SCC clock falls back to ay8910Clock×2 even when no SCC events exist.
        // Only show SCC if there are actual 0xD2 events in the VGM.
        if (parsed.events().stream().anyMatch(e -> e.chip() == CHIP_SCC))
            chips.add("SCC");
        if (parsed.huC6280Clock() > 0)
            chips.add("HuC6280");
        if (parsed.nes2A03Clock() > 0)
            chips.add("NES 2A03");
        if (parsed.gameBoyDmgClock() > 0)
            chips.add("GB DMG");

        var chipStr = chips.isEmpty() ? null : String.join("+", chips);
        var gd3 = parsed.gd3Title();

        if (chipStr != null && gd3 != null)
            return "[" + chipStr + "] " + gd3;
        if (chipStr != null)
            return "[" + chipStr + "]";
        return gd3;
    }

    /** Routes OPN port-0 events to the SSG, rhythm, or FM converter. */
    private static void routeOpn(VgmEvent event, Track[] routed, long tick,
            Ym2612MidiConverter fmConv, Ay8910MidiConverter ssgConv,
            @org.jspecify.annotations.Nullable OpnAdpcmARhythmMidiConverter adpcmRhythmConv,
            long fmClock, long ssgClock)
    {
        int addr = event.rawData()[0] & 0xFF;
        if (addr <= 0x0D)
        {
            ssgConv.convert(event, routed, ssgClock, tick);
        }
        else
        {
            routeOpnRhythmOrFm(event, routed, tick, fmConv, adpcmRhythmConv, fmClock);
        }
    }

    private static void routeOpnRhythmOrFm(VgmEvent event, Track[] routed, long tick,
            Ym2612MidiConverter fmConv,
            @org.jspecify.annotations.Nullable OpnAdpcmARhythmMidiConverter rhythmConv,
            long fmClock)
    {
        if (rhythmConv == null || !rhythmConv.convert(event, routed, tick))
            fmConv.convert(event, routed, fmClock, tick);
    }
}

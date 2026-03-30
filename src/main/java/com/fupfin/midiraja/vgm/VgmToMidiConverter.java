/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.vgm;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

/** Orchestrates VGM-to-MIDI conversion using chip-specific converters. */
public class VgmToMidiConverter {

    // Chip IDs assigned by VgmParser based on the VGM command byte.
    // MIDI channel mapping: SN76489/AY8910 → ch 0-2 (tone), 9 (noise);
    //                       YM2612 → ch 3-8; SCC → ch 10-14;
    //                       YM2151 → ch 0-7 (standalone) or ch 3-8 (with YM2612);
    //                       YM2203/YM2608/YM2610 → FM on ch 3-8, SSG on ch 0-2 + 9.
    public static final int CHIP_SN76489       = 0; // 0x50
    public static final int CHIP_YM2612_PORT0  = 1; // 0x52
    public static final int CHIP_YM2612_PORT1  = 2; // 0x53
    public static final int CHIP_AY8910        = 3; // 0xA0
    public static final int CHIP_SCC           = 4; // 0xD2
    public static final int CHIP_YM2151        = 5; // 0x54
    public static final int CHIP_YM2203        = 6; // 0x55 (OPN: 3 FM + SSG)
    public static final int CHIP_YM2608_PORT0  = 7; // 0x56 (OPNA: 6 FM + SSG)
    public static final int CHIP_YM2608_PORT1  = 8; // 0x57
    public static final int CHIP_YM2610_PORT0  = 9; // 0x58 (OPNB: 4 FM + SSG)
    public static final int CHIP_YM2610_PORT1  = 10; // 0x59
    public static final int CHIP_GAMEBOY_DMG   = 11; // 0xB3
    public static final int CHIP_HUC6280       = 12; // 0xB9
    public static final int CHIP_YM2413       = 13; // 0x51 (OPLL)

    // PPQ=480 at 120 BPM → 960 ticks/second.
    // VGM timebase = 44100 Hz. Scale: tick = round(sampleOffset × 960 / 44100).
    // One NTSC frame (735 samples) = 735 × 960 / 44100 = exactly 16 ticks.
    // Sub-frame intra-frame register writes (arpeggios, vibrato) collapse into the same tick,
    // eliminating the "notes pouring" effect caused by rapid NoteOn/Off pairs.
    private static final int PPQ = 480;
    private static final byte[] TEMPO_BYTES = {0x07, (byte) 0xA1, 0x20}; // 500000 µs = 120 BPM
    private static final long VGM_SAMPLE_RATE = 44100L;
    private static final long TICKS_PER_SECOND = 960L; // PPQ * (1_000_000 / TEMPO_µS) = 480 * 2

    private final Set<Integer> mutedChannels;

    public VgmToMidiConverter() {
        this(Set.of());
    }

    /**
     * @param mutedChannels MIDI channel indices (0-based) to silence. Events routed to these
     *                      channels are discarded rather than written to the output sequence.
     */
    public VgmToMidiConverter(Set<Integer> mutedChannels) {
        this.mutedChannels = mutedChannels;
    }

    static long toTick(long sampleOffset) {
        return Math.round(sampleOffset * (double) TICKS_PER_SECOND / VGM_SAMPLE_RATE);
    }

    public Sequence convert(VgmParseResult parsed) {
        try {
            var sequence = new Sequence(Sequence.PPQ, PPQ);

            // Track 0: tempo + optional title
            var tempoTrack = sequence.createTrack();
            tempoTrack.add(new MidiEvent(new MetaMessage(0x51, TEMPO_BYTES, 3), 0));

            if (parsed.gd3Title() != null) {
                byte[] titleBytes = parsed.gd3Title().getBytes(StandardCharsets.UTF_8);
                tempoTrack.add(new MidiEvent(
                        new MetaMessage(0x03, titleBytes, titleBytes.length), 0));
            }

            // Tracks 1-15 for MIDI channels 0-14
            var tracks = new Track[15];
            for (int i = 0; i < 15; i++) {
                tracks[i] = sequence.createTrack();
            }

            // Program Change at tick 0:
            // ch 0-2: GM 80 (Square Lead) — SN76489 / AY8910 square waves
            // ch 3-8: omitted — Ym2612MidiConverter emits per-channel Program Change
            //         dynamically based on algorithm+feedback registers (0xB0).
            // ch 9:   GM 0 with isDrums flag — TSF requires an explicit patchChange(9,0,drums=1)
            //         to activate drum mode; omitting it leaves the channel as melodic (piano).
            // ch 10-14: omitted — SccMidiConverter emits dynamic Program Change based on
            //           waveform analysis (classifyWaveform).
            int[] programs = {80, 80, 80, -1, -1, -1, -1, -1, -1, 0, -1, -1, -1, -1, -1};
            for (int ch = 0; ch < 15; ch++) {
                if (programs[ch] >= 0) {
                    tracks[ch].add(new MidiEvent(
                            new ShortMessage(ShortMessage.PROGRAM_CHANGE, ch, programs[ch], 0), 0));
                }
            }

            var snConverter    = new Sn76489MidiConverter();
            var ymConverter    = new Ym2612MidiConverter();
            var ayConverter    = new Ay8910MidiConverter();
            var sccConverter   = new SccMidiConverter();
            // YM2151: 8 channels. When no YM2612 is present, use ch 0-7 (standalone arcade).
            int opmOffset = (parsed.ym2612Clock() == 0) ? 0 : 3;
            var opmConverter   = new Ym2151MidiConverter(opmOffset);
            // OPN family: reuse Ym2612MidiConverter with chip-specific divider and port1 ID.
            var ym2203Conv     = new Ym2612MidiConverter(72, -1);  // single port, divider=72
            var ym2608Conv     = new Ym2612MidiConverter(144, CHIP_YM2608_PORT1);
            var ym2610Conv     = new Ym2612MidiConverter(144, CHIP_YM2610_PORT1);
            var pceConverter   = new HuC6280MidiConverter();
            var opllConverter  = new Ym2413MidiConverter();
            var gbConverter    = new GameBoyDmgMidiConverter();

            // Muted channels are redirected to a sink track that is not part of the output sequence.
            // This discards all events destined for those channels without modifying converter logic.
            var routed = tracks.clone();
            if (!mutedChannels.isEmpty()) {
                var sinkSeq = new Sequence(Sequence.PPQ, PPQ);
                var sink = sinkSeq.createTrack();
                for (int ch : mutedChannels) {
                    if (ch >= 0 && ch < routed.length) routed[ch] = sink;
                }
            }

            // AY8910 and SN76489 share MIDI channels 0-2 and 9; a given VGM file contains
            // at most one of the two chips (MSX uses AY8910, Sega uses SN76489).
            for (var event : parsed.events()) {
                long tick = toTick(event.sampleOffset());
                switch (event.chip()) {
                    case CHIP_SN76489                      -> snConverter.convert(event, routed, parsed.sn76489Clock(), tick);
                    case CHIP_YM2612_PORT0, CHIP_YM2612_PORT1 -> ymConverter.convert(event, routed, parsed.ym2612Clock(), tick);
                    case CHIP_AY8910                       -> ayConverter.convert(event, routed, parsed.ay8910Clock(), tick);
                    case CHIP_SCC                          -> sccConverter.convert(event, routed, parsed.sccClock(), tick);
                    case CHIP_YM2151                       -> opmConverter.convert(event, routed, parsed.ym2151Clock(), tick);
                    case CHIP_YM2203 -> routeOpn(event, routed, tick, ym2203Conv, ayConverter,
                            parsed.ym2203Clock(), parsed.ym2203Clock() / 2);
                    case CHIP_YM2608_PORT0 -> routeOpn(event, routed, tick, ym2608Conv, ayConverter,
                            parsed.ym2608Clock(), parsed.ym2608Clock() / 4);
                    case CHIP_YM2608_PORT1 -> ym2608Conv.convert(event, routed, parsed.ym2608Clock(), tick);
                    case CHIP_YM2610_PORT0 -> routeOpn(event, routed, tick, ym2610Conv, ayConverter,
                            parsed.ym2610Clock(), parsed.ym2610Clock() / 4);
                    case CHIP_YM2610_PORT1 -> ym2610Conv.convert(event, routed, parsed.ym2610Clock(), tick);
                    case CHIP_HUC6280      -> pceConverter.convert(event, routed, parsed.huC6280Clock(), tick);
                    case CHIP_GAMEBOY_DMG  -> gbConverter.convert(event, routed, parsed.gameBoyDmgClock(), tick);
                    case CHIP_YM2413       -> opllConverter.convert(event, routed, parsed.ym2413Clock(), tick);
                    default -> {} // unknown chip, skip
                }
            }

            return sequence;
        } catch (InvalidMidiDataException e) {
            throw new IllegalStateException("Failed to create MIDI sequence", e);
        }
    }

    /** Routes OPN port-0 events: addr ≤ 0x0D → SSG (AY8910), otherwise → FM. */
    private static void routeOpn(VgmEvent event, Track[] routed, long tick,
                                 Ym2612MidiConverter fmConv, Ay8910MidiConverter ssgConv,
                                 long fmClock, long ssgClock) {
        int addr = event.rawData()[0] & 0xFF;
        if (addr <= 0x0D) {
            ssgConv.convert(event, routed, ssgClock, tick);
        } else {
            fmConv.convert(event, routed, fmClock, tick);
        }
    }
}

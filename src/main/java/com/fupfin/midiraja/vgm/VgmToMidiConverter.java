/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.vgm;

import java.nio.charset.StandardCharsets;
import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

/** Orchestrates VGM-to-MIDI conversion using chip-specific converters. */
public class VgmToMidiConverter {

    // PPQ=480 at 120 BPM → 960 ticks/second.
    // VGM timebase = 44100 Hz. Scale: tick = round(sampleOffset × 960 / 44100).
    // One NTSC frame (735 samples) = exactly 8 ticks.
    // Sub-frame intra-frame arpeggios (<735 samples) are absorbed into the same tick,
    // eliminating the "notes pouring" effect from rapid register updates.
    private static final int PPQ = 480;
    private static final byte[] TEMPO_BYTES = {0x07, (byte) 0xA1, 0x20}; // 500000 µs = 120 BPM
    private static final long VGM_SAMPLE_RATE = 44100L;
    private static final long TICKS_PER_SECOND = 960L; // PPQ * (1_000_000 / TEMPO_µS) = 480 * 2

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
            // ch 10-14: GM 80 (Square Lead) — SCC wavetable channels
            int[] programs = {80, 80, 80, -1, -1, -1, -1, -1, -1, 0, 80, 80, 80, 80, 80};
            for (int ch = 0; ch < 15; ch++) {
                if (programs[ch] >= 0) {
                    tracks[ch].add(new MidiEvent(
                            new ShortMessage(ShortMessage.PROGRAM_CHANGE, ch, programs[ch], 0), 0));
                }
            }

            var snConverter = new Sn76489MidiConverter();
            var ymConverter = new Ym2612MidiConverter();
            var ayConverter = new Ay8910MidiConverter();
            var sccConverter = new SccMidiConverter();

            for (var event : parsed.events()) {
                long tick = toTick(event.sampleOffset());
                switch (event.chip()) {
                    case 0 -> snConverter.convert(event, tracks, parsed.sn76489Clock(), tick);
                    case 1, 2 -> ymConverter.convert(event, tracks, parsed.ym2612Clock(), tick);
                    case 3 -> ayConverter.convert(event, tracks, parsed.ay8910Clock(), tick);
                    case 4 -> sccConverter.convert(event, tracks, parsed.sccClock(), tick);
                    default -> {} // unknown chip, skip
                }
            }

            return sequence;
        } catch (InvalidMidiDataException e) {
            throw new IllegalStateException("Failed to create MIDI sequence", e);
        }
    }
}

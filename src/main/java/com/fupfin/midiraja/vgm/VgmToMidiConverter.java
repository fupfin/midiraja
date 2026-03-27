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
import javax.sound.midi.Track;

/** Orchestrates VGM-to-MIDI conversion using chip-specific converters. */
public class VgmToMidiConverter {

    private static final int PPQ = 4410;
    private static final byte[] TEMPO_BYTES = {0x01, (byte) 0x86, (byte) 0xA0}; // 100000 µs

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

            // Tracks 1-10 for MIDI channels 0-9
            var tracks = new Track[10];
            for (int i = 0; i < 10; i++) {
                tracks[i] = sequence.createTrack();
            }

            var snConverter = new Sn76489MidiConverter();
            var ymConverter = new Ym2612MidiConverter();

            for (var event : parsed.events()) {
                switch (event.chip()) {
                    case 0 -> snConverter.convert(event, tracks, parsed.sn76489Clock());
                    case 1, 2 -> ymConverter.convert(event, tracks, parsed.ym2612Clock());
                    default -> {} // unknown chip, skip
                }
            }

            return sequence;
        } catch (InvalidMidiDataException e) {
            throw new IllegalStateException("Failed to create MIDI sequence", e);
        }
    }
}

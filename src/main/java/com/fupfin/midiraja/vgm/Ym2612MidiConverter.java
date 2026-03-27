/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.vgm;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

/** Converts YM2612 FM chip events to MIDI note events on channels 4-9. */
public class Ym2612MidiConverter {

    private static final int DEFAULT_VELOCITY = 100;
    private static final int MIDI_CH_OFFSET = 4;

    private final int[] fnumHigh = new int[6];
    private final int[] fnumLow = new int[6];
    private final int[] activeNote = {-1, -1, -1, -1, -1, -1};

    public void convert(VgmEvent event, Track[] tracks, long clock) {
        int addr = event.rawData()[0] & 0xFF;
        int data = event.rawData()[1] & 0xFF;
        long tick = event.sampleOffset();
        int portOffset = (event.chip() == 2) ? 3 : 0; // port1 = ch 3-5

        if (addr >= 0xA4 && addr <= 0xA6) {
            int ch = (addr - 0xA4) + portOffset;
            fnumHigh[ch] = data;
        } else if (addr >= 0xA0 && addr <= 0xA2) {
            int ch = (addr - 0xA0) + portOffset;
            fnumLow[ch] = data;
        } else if (addr == 0x28) {
            handleKeyOn(data, tick, tracks, clock);
        }
    }

    private void handleKeyOn(int data, long tick, Track[] tracks, long clock) {
        int chSelect = data & 0x07;
        int ch = switch (chSelect) {
            case 0, 1, 2 -> chSelect;       // port0 ch 0-2
            case 4, 5, 6 -> chSelect - 1;   // port1 ch 3-5
            default -> -1;
        };
        if (ch < 0) return;

        int midiCh = ch + MIDI_CH_OFFSET;
        boolean keyOn = (data & 0xF0) != 0;

        if (keyOn) {
            // Note off first if already on
            if (activeNote[ch] >= 0) {
                addNote(tracks[midiCh], ShortMessage.NOTE_OFF, midiCh, activeNote[ch], 0, tick);
                activeNote[ch] = -1;
            }
            int note = computeNote(ch, clock);
            if (note >= 0) {
                addNote(tracks[midiCh], ShortMessage.NOTE_ON, midiCh, note, DEFAULT_VELOCITY, tick);
                activeNote[ch] = note;
            }
        } else {
            if (activeNote[ch] >= 0) {
                addNote(tracks[midiCh], ShortMessage.NOTE_OFF, midiCh, activeNote[ch], 0, tick);
                activeNote[ch] = -1;
            }
        }
    }

    private int computeNote(int ch, long clock) {
        int fnum = (fnumHigh[ch] & 0x07) << 8 | fnumLow[ch];
        int block = (fnumHigh[ch] >> 3) & 0x07;
        return ym2612Note(clock, fnum, block);
    }

    static int ym2612Note(long clock, int fnum, int block) {
        if (fnum <= 0) return -1;
        double f = fnum * clock / (144.0 * (1L << (21 - block)));
        return clampNote((int) Math.round(12 * Math.log(f / 440.0) / Math.log(2) + 69));
    }

    private static void addNote(Track track, int command, int ch, int note, int velocity, long tick) {
        try {
            var msg = new ShortMessage(command, ch, note, velocity);
            track.add(new MidiEvent(msg, tick));
        } catch (InvalidMidiDataException e) {
            throw new IllegalStateException("Bad MIDI data", e);
        }
    }

    private static int clampNote(int note) {
        return Sn76489MidiConverter.clamp(note, 0, 127);
    }
}

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

/** Converts SN76489 PSG chip events to MIDI note events on channels 0-3. */
public class Sn76489MidiConverter {

    private static final int NOISE_NOTE = 38; // GM percussion snare

    private final int[] tone = new int[3];
    private final int[] volume = {15, 15, 15, 15};
    private final int[] activeNote = {-1, -1, -1, -1};

    private int latchChannel;
    private boolean latchIsVolume;

    public void convert(VgmEvent event, Track[] tracks, long clock) {
        int value = event.rawData()[0] & 0xFF;
        long tick = event.sampleOffset();

        if ((value & 0x80) != 0) {
            latchChannel = (value >> 5) & 0x03;
            latchIsVolume = (value & 0x10) != 0;
            int data4 = value & 0x0F;

            if (latchIsVolume) {
                handleVolume(latchChannel, data4, tick, tracks, clock);
            } else if (latchChannel < 3) {
                tone[latchChannel] = (tone[latchChannel] & 0x3F0) | data4;
                handleTone(latchChannel, tick, tracks, clock);
            }
        } else {
            int data6 = value & 0x3F;
            if (latchIsVolume) {
                handleVolume(latchChannel, data6 & 0x0F, tick, tracks, clock);
            } else if (latchChannel < 3) {
                tone[latchChannel] = (data6 << 4) | (tone[latchChannel] & 0x0F);
                handleTone(latchChannel, tick, tracks, clock);
            }
        }
    }

    private void handleVolume(int ch, int vol, long tick, Track[] tracks, long clock) {
        int oldVol = volume[ch];
        volume[ch] = vol;

        if (vol == 15) {
            noteOff(ch, tick, tracks);
        } else if (oldVol == 15) {
            noteOn(ch, tick, tracks, clock);
        } else if (activeNote[ch] >= 0) {
            noteOff(ch, tick, tracks);
            noteOn(ch, tick, tracks, clock);
        }
    }

    private void handleTone(int ch, long tick, Track[] tracks, long clock) {
        if (volume[ch] == 15 || activeNote[ch] < 0) return;

        int newNote = sn76489Note(clock, tone[ch]);
        if (newNote < 0) return;
        if (newNote != activeNote[ch]) {
            noteOff(ch, tick, tracks);
            noteOn(ch, tick, tracks, clock);
        }
    }

    private void noteOn(int ch, long tick, Track[] tracks, long clock) {
        int note = (ch == 3) ? NOISE_NOTE : sn76489Note(clock, tone[ch]);
        if (note < 0) return;
        int velocity = toVelocity(volume[ch]);
        addNote(tracks[ch], ShortMessage.NOTE_ON, ch, note, velocity, tick);
        activeNote[ch] = note;
    }

    private void noteOff(int ch, long tick, Track[] tracks) {
        if (activeNote[ch] >= 0) {
            addNote(tracks[ch], ShortMessage.NOTE_OFF, ch, activeNote[ch], 0, tick);
            activeNote[ch] = -1;
        }
    }

    private static void addNote(Track track, int cmd, int ch, int note, int vel, long tick) {
        try {
            track.add(new MidiEvent(new ShortMessage(cmd, ch, note, vel), tick));
        } catch (InvalidMidiDataException e) {
            throw new IllegalStateException("Bad MIDI data", e);
        }
    }

    static int sn76489Note(long clock, int n) {
        if (n <= 0) return -1;
        double f = clock / (32.0 * n);
        return clamp((int) Math.round(12 * Math.log(f / 440.0) / Math.log(2) + 69), 0, 127);
    }

    private static int toVelocity(int vol) {
        return clamp((int) Math.round((15 - vol) / 15.0 * 127), 0, 127);
    }

    static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}

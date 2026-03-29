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

/**
 * Converts SN76489 PSG chip events to MIDI note events on channels 0-2 and 9.
 *
 * <p>The SN76489 has 3 tone generators and 1 noise generator. <b>Volume encoding is inverted:</b>
 * 0=maximum, 15=silent. VGM command 0x50 delivers a single byte with a two-pass latch/data
 * encoding:
 * <ul>
 *   <li>Latch byte (bit7=1): selects channel (bits 6-5) and type (bit4: 0=tone, 1=volume);
 *       carries low 4 bits of tone period or full 4-bit volume
 *   <li>Data byte (bit7=0): carries high 6 bits of the latched channel's 10-bit tone period;
 *       ignored for volume writes
 * </ul>
 *
 * <p>Frequency formula: {@code f = clock / (32 × N)} where N is the 10-bit tone counter.
 *
 * <p><b>Noise channel → GM percussion ch 9:</b> The SN76489 noise channel (chip channel 3)
 * maps to MIDI channel 9 (GM drums), not channel 3. The physical chip channel index and the
 * MIDI channel are unrelated; {@code midiCh()} performs the remapping. Note 38 (snare) is
 * used as a fixed mapping for the noise output.
 *
 * <p><b>Volume changes use CC7, not re-trigger:</b> When volume changes while a note is
 * already playing, CC7 is sent rather than NoteOff+NoteOn. Re-triggering on every volume
 * step would produce audible clicks at each attack transient.
 */
public class Sn76489MidiConverter {

    private static final int NOISE_NOTE = 38; // GM percussion snare
    private static final int NOISE_MIDI_CH = 9; // GM percussion channel

    private final int[] tone = new int[3];
    private final int[] volume = {15, 15, 15, 15};
    private final int[] activeNote = {-1, -1, -1, -1};

    private int latchChannel;
    private boolean latchIsVolume;

    public void convert(VgmEvent event, Track[] tracks, long clock, long tick) {
        int value = event.rawData()[0] & 0xFF;

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
            // Volume envelope change while note is playing: use CC7 to avoid note re-triggering
            int midiCh = midiCh(ch);
            addNote(tracks[midiCh], ShortMessage.CONTROL_CHANGE, midiCh, 7, toVelocity(vol), tick);
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
        int midiCh = midiCh(ch);
        // Send CC7 before NoteOn so the channel volume is correct regardless of any
        // CC7 value left over from a previous note on this channel.
        addNote(tracks[midiCh], ShortMessage.CONTROL_CHANGE, midiCh, 7, toVelocity(volume[ch]), tick);
        addNote(tracks[midiCh], ShortMessage.NOTE_ON, midiCh, note, 127, tick);
        activeNote[ch] = note;
    }

    private void noteOff(int ch, long tick, Track[] tracks) {
        if (activeNote[ch] >= 0) {
            int midiCh = midiCh(ch);
            addNote(tracks[midiCh], ShortMessage.NOTE_OFF, midiCh, activeNote[ch], 0, tick);
            activeNote[ch] = -1;
        }
    }

    private static int midiCh(int ch) {
        return ch == 3 ? NOISE_MIDI_CH : ch;
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

    // FluidR3 Square Lead (prog 80) renders 6.2 dB louder than Rock Organ (prog 18) at CC7=127.
    // Scale PSG CC7 by 0.490 so all chip channels reach the same perceived loudness.
    // Measured with scripts/measure_instrument_levels.py on FluidR3_GM.sf3.
    static final double PSG_CC7_GAIN = 0.490;

    private static int toVelocity(int vol) {
        // SN76489 volume is inverted: 0=loudest, 15=silent. The (15-vol) term normalises
        // it to a 0-15 scale where 15=loudest before mapping to MIDI CC7 range.
        // Linear mapping is acceptable here because Sega games typically use full-volume
        // (vol=0) or silence (vol=15), with few intermediate steps.
        int cc7 = clamp((int) Math.round((15 - vol) / 15.0 * 127), 0, 127);
        return clamp((int) Math.round(cc7 * PSG_CC7_GAIN), 0, 127);
    }

    static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}

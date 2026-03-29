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
 * Converts Konami SCC (K051649) wavetable chip events to MIDI note events on channels 10-14.
 *
 * <p>The VGM D2 command carries three bytes: port, address, data. Port selects the register bank:
 * <ul>
 *   <li>Port 0: Waveform data (0x00-0x7F, four 32-byte banks) — ignored for MIDI conversion
 *   <li>Port 1: Frequency registers (addr 0x00-0x09, two bytes per channel: low then high nibble)
 *   <li>Port 2: Volume registers (addr 0x00-0x04, one byte per channel, bits 3-0, 0=silent)
 *   <li>Port 3: Channel enable (addr 0x00, bit4-0) — not used for MIDI gating; see below
 * </ul>
 *
 * <p>Frequency formula: {@code f = clock / (32 × (N + 1))} where N is the 12-bit divider.
 *
 * <p><b>Enable register design decision:</b> The K051649 enable register is not used as a MIDI
 * note gate. MSX games must mute SCC channels (enable=0) before writing waveform data and
 * re-enable immediately after — producing rapid enable-cycling within a single MIDI tick. Treating
 * enable=0 as NoteOff would generate inaudible sub-millisecond NoteOn/Off pairs that silence the
 * SCC output entirely. Volume alone drives note state: vol&gt;0 → NoteOn, vol=0 → NoteOff.
 */
public class SccMidiConverter {

    private static final int MIDI_CH_OFFSET = 10; // SCC ch 0-4 → MIDI ch 10-14

    /**
     * Minimum MIDI note to emit. Notes below this threshold (≈ 41 Hz) are sub-bass artifacts
     * from SCC register initialisation (e.g. fnum=0x7EB → MIDI 21 / 27 Hz) that produce
     * infrasonic rumble without musical content. The note is still <em>tracked</em> internally
     * so that a subsequent frequency change (retrigger) fires the correct audible NoteOn.
     */
    private static final int MIN_NOTE = 28; // E1 ≈ 41 Hz

    private final int[] freqLo = new int[5];
    private final int[] freqHi = new int[5]; // only bits 3-0 are significant
    private final int[] volume = new int[5]; // 0=silent, 15=loudest
    private final int[] activeNote = {-1, -1, -1, -1, -1};

    public void convert(VgmEvent event, Track[] tracks, long clock, long tick) {
        int port = event.rawData()[0] & 0xFF;
        int addr = event.rawData()[1] & 0xFF;
        int val  = event.rawData()[2] & 0xFF;

        switch (port) {
            case 0 -> {} // waveform data — not used for MIDI conversion
            case 1 -> handleFrequency(addr, val, tick, tracks, clock);
            case 2 -> handleVolume(addr, val, tick, tracks, clock);
            case 3 -> {} // channel enable — not used for MIDI gating (see class javadoc)
        }
    }

    private void handleFrequency(int addr, int val, long tick, Track[] tracks, long clock) {
        int ch = addr / 2;
        if (ch >= 5) return;
        if (addr % 2 == 0) {
            freqLo[ch] = val;
        } else {
            freqHi[ch] = val & 0x0F;
        }
        // Check for note change whenever either frequency byte changes.
        // MSX games often update only the low byte for small pitch changes
        // (e.g. glissando), so both bytes must trigger the retrigger check.
        if (activeNote[ch] >= 0) {
            int newNote = sccNote(clock, fnum(ch));
            if (newNote >= 0 && newNote != activeNote[ch]) {
                noteOff(ch, tick, tracks);
                noteOn(ch, tick, tracks, clock);
            }
        }
    }

    private void handleVolume(int addr, int val, long tick, Track[] tracks, long clock) {
        int ch = addr;
        if (ch >= 5) return;
        int newVol = val & 0x0F;
        volume[ch] = newVol;

        boolean shouldPlay = newVol > 0;
        boolean wasPlaying = activeNote[ch] >= 0;

        if (!shouldPlay) {
            noteOff(ch, tick, tracks);
        } else if (!wasPlaying) {
            noteOn(ch, tick, tracks, clock);
        } else {
            int midiCh = ch + MIDI_CH_OFFSET;
            addNote(tracks[midiCh], ShortMessage.CONTROL_CHANGE, midiCh, 7,
                    toVelocity(newVol), tick);
        }
    }

    private void noteOn(int ch, long tick, Track[] tracks, long clock) {
        int note = sccNote(clock, fnum(ch));
        if (note < 0) return;
        // Always track the active note so retrigger detection works even for infrasonic
        // frequencies; only emit MIDI events for audible notes.
        activeNote[ch] = note;
        if (note < MIN_NOTE) return;
        int midiCh = ch + MIDI_CH_OFFSET;
        addNote(tracks[midiCh], ShortMessage.CONTROL_CHANGE, midiCh, 7, toVelocity(volume[ch]),
                tick);
        addNote(tracks[midiCh], ShortMessage.NOTE_ON, midiCh, note, 127, tick);
    }

    private void noteOff(int ch, long tick, Track[] tracks) {
        if (activeNote[ch] >= 0) {
            // Only emit NoteOff if we previously emitted a NoteOn for this note
            if (activeNote[ch] >= MIN_NOTE) {
                int midiCh = ch + MIDI_CH_OFFSET;
                addNote(tracks[midiCh], ShortMessage.NOTE_OFF, midiCh, activeNote[ch], 0, tick);
            }
            activeNote[ch] = -1;
        }
    }

    private int fnum(int ch) {
        return (freqHi[ch] << 8) | freqLo[ch];
    }

    /** Converts a 12-bit SCC frequency divider to a MIDI note number. */
    static int sccNote(long clock, int fnum) {
        if (fnum <= 0) return -1;
        double f = clock / (32.0 * (fnum + 1));
        return Sn76489MidiConverter.clamp(
                (int) Math.round(12 * Math.log(f / 440.0) / Math.log(2) + 69), 0, 127);
    }

    private static int toVelocity(int vol) {
        return Sn76489MidiConverter.clamp((int) Math.round(Math.sqrt(vol / 15.0) * 127), 0, 127);
    }

    private static void addNote(Track track, int cmd, int ch, int note, int vel, long tick) {
        try {
            track.add(new MidiEvent(new ShortMessage(cmd, ch, note, vel), tick));
        } catch (InvalidMidiDataException e) {
            throw new IllegalStateException("Bad MIDI data", e);
        }
    }
}

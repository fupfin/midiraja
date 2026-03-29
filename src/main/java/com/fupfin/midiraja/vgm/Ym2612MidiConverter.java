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
 * Converts YM2612 FM chip events to MIDI note events on channels 3-8.
 *
 * <p>The YM2612 has 6 FM channels split across two ports. VGM commands 0x52 (port 0, chip id 1)
 * and 0x53 (port 1, chip id 2) each carry an address byte and a data byte. Port 0 addresses
 * FM channels 0-2; port 1 addresses channels 3-5 (portOffset=3 added to the decoded channel).
 *
 * <p><b>Key-on register 0x28 encoding:</b> Bits 3-0 select the channel using a non-contiguous
 * scheme: values 0/1/2 = port0 ch 0-2, values 4/5/6 = port1 ch 3-5. Values 3 and 7 are
 * reserved (ch3 in YM2612 is used for special 3-slot mode; 7 is unused). The upper nibble
 * (bits 7-4) carries the operator key-on flags; any non-zero value means key-on.
 *
 * <p><b>F-Number write order:</b> The YM2612 requires the high byte (0xA4-0xA6) to be written
 * before the low byte (0xA0-0xA2) for the frequency to take effect on the chip. The converter
 * mirrors this by storing both bytes but only computing the note at key-on time, so ordering
 * does not affect the MIDI output.
 *
 * <p><b>Dynamic Program Change:</b> FM timbre is determined by operator parameters (TL, AR,
 * DR, etc.) that are not captured in MIDI. Instead, the algorithm+feedback register (0xB0)
 * is used to approximate the tonal character. The Program Change is deferred to key-on time
 * (not emitted on every 0xB0 write) because the complete patch is often written just before
 * the first note and a premature Program Change on an otherwise-silent channel wastes events.
 */
public class Ym2612MidiConverter {

    private static final int DEFAULT_VELOCITY = 100;
    private static final int MIDI_CH_OFFSET = 3;

    private final int[] fnumHigh = new int[6];
    private final int[] fnumLow = new int[6];
    private final int[] activeNote = {-1, -1, -1, -1, -1, -1};
    private final int[] algorithm = new int[6];
    private final int[] feedback = new int[6];
    private final int[] currentProgram = {-1, -1, -1, -1, -1, -1};

    public void convert(VgmEvent event, Track[] tracks, long clock, long tick) {
        int addr = event.rawData()[0] & 0xFF;
        int data = event.rawData()[1] & 0xFF;
        int portOffset = (event.chip() == 2) ? 3 : 0; // port1 = ch 3-5

        if (addr >= 0xB0 && addr <= 0xB2) {
            int ch = (addr - 0xB0) + portOffset;
            algorithm[ch] = data & 0x07;
            feedback[ch] = (data >> 3) & 0x07;
        } else if (addr >= 0xA4 && addr <= 0xA6) {
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
        // Channel select uses values 0-2 (port 0) and 4-6 (port 1); 3 and 7 are invalid.
        // Subtracting 1 from port1 values maps them to the flat 0-5 channel array.
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
            if (activeNote[ch] >= 0) {
                addNote(tracks[midiCh], ShortMessage.NOTE_OFF, midiCh, activeNote[ch], 0, tick);
                activeNote[ch] = -1;
            }
            emitProgramChangeIfNeeded(ch, midiCh, tracks, tick);
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

    private void emitProgramChangeIfNeeded(int ch, int midiCh, Track[] tracks, long tick) {
        int program = selectProgram(algorithm[ch], feedback[ch]);
        if (program != currentProgram[ch]) {
            addNote(tracks[midiCh], ShortMessage.PROGRAM_CHANGE, midiCh, program, 0, tick);
            currentProgram[ch] = program;
        }
    }

    /**
     * Maps YM2612 algorithm + feedback to a GM program number.
     *
     * <p>Algorithm determines operator topology (how many carriers vs modulators):
     * <ul>
     *   <li>0-3: Serial/complex FM — deep modulation, bass/lead timbres
     *   <li>4: Two independent 2-op FM pairs — classic Genesis lead sound
     *   <li>5: One modulator driving three carriers — bright pad/brass
     *   <li>6: Near-additive — organ-like
     *   <li>7: Fully additive (4 carriers) — bright organ/bells
     * </ul>
     * High feedback (≥6) adds strong odd harmonics, producing a square-wave character.
     */
    static int selectProgram(int alg, int fb) {
        if (fb >= 6) {
            return switch (alg) {
                case 0, 1, 2, 3 -> 29; // Overdriven Guitar — buzzy serial FM
                case 4           -> 80; // Square Lead — two buzzy pairs
                default          -> 19; // Rock Organ — additive + harmonics
            };
        }
        return switch (alg) {
            case 0 -> 33; // Electric Bass (Finger) — deep series FM
            case 1 -> 38; // Synth Bass 1
            case 2 -> 81; // Sawtooth Lead
            case 3 -> 81; // Sawtooth Lead
            case 4 -> 81; // Sawtooth Lead — two 2-op FM pairs (most common Genesis lead)
            case 5 -> 89; // Pad 2 (Warm) — one modulator, three carriers
            case 6 -> 19; // Rock Organ
            case 7 -> 16; // Hammond Organ — fully additive
            default -> 81;
        };
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

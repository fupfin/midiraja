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

    private static final int PROGRAM_SAWTOOTH_LEAD    = 81;
    private static final int PROGRAM_SQUARE_LEAD      = 80;
    private static final int PROGRAM_CALLIOPE_LEAD    = 82; // whistle-like, cleaner than sawtooth for single-carrier leads
    private static final int PROGRAM_ELECTRIC_BASS    = 33;
    private static final int PROGRAM_SYNTH_BASS       = 38;
    private static final int PROGRAM_OVERDRIVEN_GUITAR = 29;
    private static final int PROGRAM_SYNTH_BRASS1     = 62; // replaces Drawbar Organ (alg 7): brighter, game-appropriate
    private static final int PROGRAM_SYNTH_STRINGS1   = 50; // replaces Rock Organ (alg 6): ensemble pad without organ character
    private static final int PROGRAM_SYNTH_PAD2       = 89; // warm pad for alg 5 (1 mod → 3 carriers)

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
     *   <li>6: Near-additive (3 carriers + 1 shared modulator) — ensemble/string character
     *   <li>7: Fully additive (4 independent carriers) — bright synth brass/bells
     * </ul>
     * High feedback (≥6) on operator 1 adds strong odd harmonics, producing a square-wave
     * character regardless of algorithm.
     *
     * <p>Organ instruments (Drawbar, Church, Rock) were avoided: they introduce a thick,
     * ecclesiastical timbre that clashes with fast game music. Synth Brass and Synth Strings
     * better approximate the crisp, slightly harsh character of real FM output in FluidR3.
     */
    static int selectProgram(int alg, int fb) {
        if (fb >= 6) {
            return switch (alg) {
                case 0, 1, 2, 3 -> PROGRAM_OVERDRIVEN_GUITAR; // buzzy serial FM → Overdriven Guitar
                case 4           -> PROGRAM_SQUARE_LEAD;       // two buzzy 2-op pairs → Square Lead
                default          -> PROGRAM_SYNTH_BRASS1;      // additive + harmonics → Synth Brass 1
            };
        }
        return switch (alg) {
            case 0 -> PROGRAM_ELECTRIC_BASS;    // deepest series FM → Electric Bass (Finger)
            case 1 -> PROGRAM_SYNTH_BASS;       // series FM, brighter → Synth Bass 1
            case 2 -> PROGRAM_SAWTOOTH_LEAD;    // 2+1+1 chain → Sawtooth Lead
            case 3 -> PROGRAM_SAWTOOTH_LEAD;    // 2+2 parallel → Sawtooth Lead
            case 4 -> PROGRAM_CALLIOPE_LEAD;    // two 2-op pairs — cleaner lead tone than sawtooth
            case 5 -> PROGRAM_SYNTH_PAD2;       // 1 mod → 3 carriers — warm pad
            case 6 -> PROGRAM_SYNTH_STRINGS1;   // near-additive — string ensemble avoids organ character
            case 7 -> PROGRAM_SYNTH_BRASS1;     // fully additive — bright synth brass, not church organ
            default -> PROGRAM_SAWTOOTH_LEAD;
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

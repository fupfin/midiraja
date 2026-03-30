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
    private static final int PROGRAM_CHIFF_LEAD        = 83; // fast chiff attack, thin sustain; used for alg4+high-fb
    private static final int PROGRAM_CALLIOPE_LEAD    = 82; // whistle-like, cleaner than sawtooth for single-carrier leads
    private static final int PROGRAM_ELECTRIC_BASS    = 33;
    private static final int PROGRAM_SYNTH_BASS       = 38;
    private static final int PROGRAM_OVERDRIVEN_GUITAR = 29;
    private static final int PROGRAM_SYNTH_BRASS1     = 62; // replaces Drawbar Organ (alg 7): brighter, game-appropriate
    private static final int PROGRAM_SYNTH_STRINGS1   = 50; // replaces Rock Organ (alg 6): ensemble pad without organ character
    private static final int PROGRAM_SYNTH_PAD2       = 89; // warm pad for alg 5 (1 mod → 3 carriers)

    private static final int MIDI_CH_OFFSET = 3;
    // Reference TL for velocity scaling: carrier TL == REF_TL plays at velocity=127.
    // Typical Genesis FM carrier TL values cluster around 18–25; REF_TL=20 keeps them
    // audible and balanced against the SN76489 PSG channels.
    private static final int REF_TL = 20;
    // Additional attenuation per feedback step (0–7). High feedback spreads energy into
    // upper harmonics, reducing the perceived loudness of the fundamental. Using half the
    // TL step size (0.375 dB) gives a gentle correction without over-attenuating.
    private static final double FB_DB_PER_STEP = 0.375;

    private final int[] fnumHigh = new int[6];
    private final int[] fnumLow = new int[6];
    private final int[] activeNote = {-1, -1, -1, -1, -1, -1};
    private final int[] algorithm = new int[6];
    private final int[] feedback = new int[6];
    private final int[] currentProgram = {-1, -1, -1, -1, -1, -1};
    // TL (Total Level) per channel per operator, op index: 0=0x40(S1), 1=0x44(S3), 2=0x48(S2), 3=0x4C(S4).
    // 127 = max attenuation (silent); 0 = full output. Each step = 0.75 dB.
    private final int[][] tl = {
        {127, 127, 127, 127}, {127, 127, 127, 127}, {127, 127, 127, 127},
        {127, 127, 127, 127}, {127, 127, 127, 127}, {127, 127, 127, 127}
    };
    // lrMask: bits from register 0xB4-0xB6 bits 7-6: bit1=L, bit0=R. Default 3 = L+R (center).
    private final int[] lrMask = {3, 3, 3, 3, 3, 3};
    private final int[] currentPan = {-1, -1, -1, -1, -1, -1};

    public void convert(VgmEvent event, Track[] tracks, long clock, long tick) {
        int addr = event.rawData()[0] & 0xFF;
        int data = event.rawData()[1] & 0xFF;
        int portOffset = (event.chip() == 2) ? 3 : 0; // port1 = ch 3-5

        if (addr >= 0x40 && addr <= 0x4F) {
            int op = (addr - 0x40) >> 2;
            int ch = (addr & 0x03) + portOffset;
            if (ch < 6) tl[ch][op] = data & 0x7F;
        } else if (addr >= 0xB0 && addr <= 0xB2) {
            int ch = (addr - 0xB0) + portOffset;
            algorithm[ch] = data & 0x07;
            feedback[ch] = (data >> 3) & 0x07;
        } else if (addr >= 0xB4 && addr <= 0xB6) {
            int ch = (addr - 0xB4) + portOffset;
            lrMask[ch] = (data >> 6) & 0x03; // bit7=L, bit6=R
            if (activeNote[ch] >= 0) {
                emitPanIfNeeded(ch, ch + MIDI_CH_OFFSET, tracks, tick);
            }
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
            emitPanIfNeeded(ch, midiCh, tracks, tick);
            emitProgramChangeIfNeeded(ch, midiCh, tracks, tick);
            int note = computeNote(ch, clock);
            if (note >= 0) {
                addNote(tracks[midiCh], ShortMessage.NOTE_ON, midiCh, note, computeVelocity(ch), tick);
                activeNote[ch] = note;
            }
        } else {
            if (activeNote[ch] >= 0) {
                addNote(tracks[midiCh], ShortMessage.NOTE_OFF, midiCh, activeNote[ch], 0, tick);
                activeNote[ch] = -1;
            }
        }
    }

    private void emitPanIfNeeded(int ch, int midiCh, Track[] tracks, long tick) {
        // lrMask bit1=L, bit0=R. Map to MIDI CC10: 0=hard left, 64=center, 127=hard right.
        int pan = switch (lrMask[ch]) {
            case 2 -> 0;   // L only → hard left
            case 1 -> 127; // R only → hard right
            default -> 64; // L+R (3) or off (0) → center
        };
        if (pan != currentPan[ch]) {
            addNote(tracks[midiCh], ShortMessage.CONTROL_CHANGE, midiCh, 10, pan, tick);
            currentPan[ch] = pan;
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
                case 4           -> PROGRAM_CHIFF_LEAD;        // two 2-op pairs + high fb → Chiff Lead (thin sustain)
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

    /**
     * Maps carrier operator TL values to MIDI velocity using dB-based amplitude scaling.
     *
     * <p>TL=0 is full output (0 dB), each step attenuates by 0.75 dB (TL=127 ≈ −95 dB ≈ silence).
     * The average TL of all carrier operators for the current algorithm is converted to a linear
     * amplitude ratio (10^((REF_TL − avgTL − fb)×0.75/20)), then scaled to MIDI velocity 1–127.
     * Feedback (0–7) is subtracted in the same dB scale: high self-feedback spreads energy into
     * upper harmonics, reducing the perceived loudness of the fundamental tone.
     */
    private int computeVelocity(int ch) {
        int[] ops = carrierOps(algorithm[ch]);
        int totalTl = 0;
        for (int op : ops) totalTl += tl[ch][op];
        double avgTl = (double) totalTl / ops.length;
        double tlDb  = (avgTl - REF_TL) * 0.75;        // dB attenuation from carrier TL
        double fbDb  = feedback[ch] * FB_DB_PER_STEP;   // dB attenuation from feedback harmonics
        double amplitude = Math.pow(10.0, -(tlDb + fbDb) / 20.0);
        return Math.min(127, Math.max(1, (int) Math.round(amplitude * 127)));
    }

    /**
     * Returns the operator indices (0=S1/0x40, 1=S3/0x44, 2=S2/0x48, 3=S4/0x4C) that are
     * carrier operators for the given YM2612 algorithm.
     */
    static int[] carrierOps(int alg) {
        return switch (alg) {
            case 4    -> new int[]{2, 3};
            case 5, 6 -> new int[]{1, 2, 3};
            case 7    -> new int[]{0, 1, 2, 3};
            default   -> new int[]{3};
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

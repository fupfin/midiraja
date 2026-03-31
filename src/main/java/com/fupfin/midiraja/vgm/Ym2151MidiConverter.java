/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.vgm;

import static com.fupfin.midiraja.vgm.FmMidiUtil.*;

import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

/**
 * Converts YM2151 (OPM) FM chip events to MIDI note events.
 *
 * <p>The YM2151 has 8 FM channels on a single port. VGM command 0x54 carries an address byte
 * and a data byte.
 *
 * <p><b>Register layout differences from YM2612:</b>
 * <ul>
 *   <li>Channel control (RL/FB/CONNECT): 0x20+ch (vs 0xB0+ch)
 *   <li>Frequency: KC (key code) at 0x28+ch and KF (key fraction) at 0x30+ch,
 *       instead of F-Number/Block
 *   <li>TL: 0x60+ch (M1), 0x68+ch (M2), 0x70+ch (C1), 0x78+ch (C2),
 *       stride 8 per operator (vs stride 4 in YM2612)
 *   <li>Key on/off: register 0x08, bits 2-0=channel, bits 6-3=operator mask
 *   <li>8 channels (vs 6), single port (vs dual)
 * </ul>
 *
 * <p>The eight FM algorithms are identical to YM2612, so program selection and carrier operator
 * mapping are shared via {@link FmMidiUtil}.
 */
public class Ym2151MidiConverter {

    static final int CHANNELS = 8;

    // OPM octave 0, note C# (KC=0x00) = MIDI note 13 (C#0).
    private static final int KC_MIDI_BASE = 13;
    // KC note code (bits 3-0) → semitone offset from C# within the octave.
    // Values 3, 7, 11, 15 are invalid (not used by real hardware).
    private static final int[] KC_SEMITONE = {
        0, 1, 2, -1, 3, 4, 5, -1, 6, 7, 8, -1, 9, 10, 11, -1
    };

    private final int midiChOffset;

    private final int[] kc = new int[CHANNELS];
    private final int[] activeNote = {-1, -1, -1, -1, -1, -1, -1, -1};
    private final int[] algorithm = new int[CHANNELS];
    private final int[] feedback = new int[CHANNELS];
    private final int[] currentProgram = {-1, -1, -1, -1, -1, -1, -1, -1};
    // TL per channel per operator. Op index: 0=M1(0x60), 1=M2(0x68), 2=C1(0x70), 3=C2(0x78).
    private final int[][] tl = {
        {127, 127, 127, 127}, {127, 127, 127, 127}, {127, 127, 127, 127}, {127, 127, 127, 127},
        {127, 127, 127, 127}, {127, 127, 127, 127}, {127, 127, 127, 127}, {127, 127, 127, 127}
    };
    // AR per channel per operator (5-bit, 0-31). KS/AR at 0x80-0x9F.
    private final int[][] ar = new int[CHANNELS][4];
    // D1R per channel per operator (5-bit, 0-31). AM/D1R at 0xA0-0xBF.
    private final int[][] d1r = new int[CHANNELS][4];
    private final int[] lrMask = {3, 3, 3, 3, 3, 3, 3, 3};
    private final int[] currentPan = {-1, -1, -1, -1, -1, -1, -1, -1};

    public Ym2151MidiConverter(int midiChOffset) {
        this.midiChOffset = midiChOffset;
    }

    public void convert(VgmEvent event, Track[] tracks, long clock, long tick) {
        int addr = event.rawData()[0] & 0xFF;
        int data = event.rawData()[1] & 0xFF;

        if (addr >= 0x60 && addr <= 0x7F) {
            // TL registers: M1=0x60+ch, M2=0x68+ch, C1=0x70+ch, C2=0x78+ch
            int op = (addr - 0x60) >> 3;
            int ch = addr & 0x07;
            tl[ch][op] = data & 0x7F;
        } else if (addr >= 0x80 && addr <= 0x9F) {
            // KS/AR: bits 4-0 = AR
            int op = (addr - 0x80) >> 3;
            int ch = addr & 0x07;
            ar[ch][op] = data & 0x1F;
        } else if (addr >= 0xA0 && addr <= 0xBF) {
            // AM/D1R: bits 4-0 = D1R
            int op = (addr - 0xA0) >> 3;
            int ch = addr & 0x07;
            d1r[ch][op] = data & 0x1F;
        } else if (addr >= 0x20 && addr <= 0x27) {
            // RL/FB/CONNECT: bits 7-6=LR, bits 5-3=FB, bits 2-0=algorithm
            int ch = addr & 0x07;
            algorithm[ch] = data & 0x07;
            feedback[ch] = (data >> 3) & 0x07;
            lrMask[ch] = (data >> 6) & 0x03;
            if (activeNote[ch] >= 0) {
                emitPanIfNeeded(ch, tracks, tick);
            }
        } else if (addr >= 0x28 && addr <= 0x2F) {
            // KC (Key Code): bits 6-4=octave, bits 3-0=note
            int ch = addr & 0x07;
            kc[ch] = data;
        } else if (addr == 0x08) {
            // Key on/off: bits 2-0=channel, bits 6-3=operator mask
            handleKeyOn(data, tick, tracks);
        }
    }

    private void handleKeyOn(int data, long tick, Track[] tracks) {
        int ch = data & 0x07;
        if (ch >= CHANNELS) return;

        int midiCh = ch + midiChOffset;
        boolean keyOn = (data & 0x78) != 0; // bits 6-3: operator mask

        if (keyOn) {
            if (activeNote[ch] >= 0) {
                addEvent(tracks[midiCh], ShortMessage.NOTE_OFF, midiCh, activeNote[ch], 0, tick);
                activeNote[ch] = -1;
            }
            int note = computeNote(ch);
            if (note >= 0) {
                emitPanIfNeeded(ch, tracks, tick);
                addEvent(tracks[midiCh], ShortMessage.NOTE_ON, midiCh, note,
                        computeVelocity(tl, algorithm, feedback, ch), tick);
                activeNote[ch] = note;
            }
        } else {
            if (activeNote[ch] >= 0) {
                addEvent(tracks[midiCh], ShortMessage.NOTE_OFF, midiCh, activeNote[ch], 0, tick);
                activeNote[ch] = -1;
            }
        }
    }

    private void emitPanIfNeeded(int ch, Track[] tracks, long tick) {
        int midiCh = ch + midiChOffset;
        int pan = lrMaskToPan(lrMask[ch]);
        if (pan != currentPan[ch]) {
            addEvent(tracks[midiCh], ShortMessage.CONTROL_CHANGE, midiCh, 10, pan, tick);
            currentPan[ch] = pan;
        }
    }

    private void emitProgramIfNeeded(int ch, int midiCh, int note, Track[] tracks, long tick) {
        int[] cops = carrierOps(algorithm[ch]);
        int totalAr = 0, totalDr = 0;
        for (int op : cops) { totalAr += ar[ch][op]; totalDr += d1r[ch][op]; }
        boolean perc = isPercussive(totalAr / cops.length / 2, totalDr / cops.length / 2);
        int program = selectProgram(note, perc);
        if (program != currentProgram[ch]) {
            addEvent(tracks[midiCh], ShortMessage.PROGRAM_CHANGE, midiCh, program, 0, tick);
            currentProgram[ch] = program;
        }
    }

    /**
     * Converts KC (Key Code) to a MIDI note number.
     *
     * <p>KC encodes octave in bits 6-4 and a non-linear note code in bits 3-0.
     * Within each octave the sequence starts at C# (semitone 0) and wraps to C of
     * the next octave (semitone 11). MIDI note = octave × 12 + semitone + 13.
     */
    private int computeNote(int ch) {
        int octave = (kc[ch] >> 4) & 0x07;
        int noteCode = kc[ch] & 0x0F;
        int semitone = KC_SEMITONE[noteCode];
        if (semitone < 0) return -1;
        int midiNote = octave * 12 + semitone + KC_MIDI_BASE;
        return Math.clamp(midiNote, 0, 127);
    }
}

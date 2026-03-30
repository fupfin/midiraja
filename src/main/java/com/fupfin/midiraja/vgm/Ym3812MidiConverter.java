/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.vgm;

import static com.fupfin.midiraja.vgm.FmMidiUtil.addEvent;

import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

/**
 * Converts YM3812 (OPL2 / AdLib) VGM events to MIDI note events on channels 0-8.
 *
 * <p>The YM3812 has 9 channels, each with 2 operators (modulator + carrier). VGM command 0x5A
 * carries 2 bytes: register address and data.
 *
 * <p><b>Operator register decoding:</b> Registers in the ranges 0x20-0x35, 0x40-0x55,
 * 0x60-0x75, 0x80-0x95, and 0xE0-0xF5 are per-operator. The offset from the range base is
 * decoded as: {@code group = offset / 8; slot = offset % 8}. Slots 6-7 are unused.
 * Channel = group*3 + slot%3; the operator is the carrier if slot >= 3, modulator otherwise.
 *
 * <p><b>Frequency:</b> Registers 0xA0-0xA8 hold the F-Number low byte; 0xB0-0xB8 hold
 * key-on (bit 5), block (bits 4-2), and F-Number high bits (bits 1-0).
 *
 * <p><b>Rhythm mode:</b> Register 0xBD bit 5 enables rhythm mode, where channels 6-8 become
 * percussion. Bits 4-0 control individual drum key-on (BD, SD, TT, CY, HH), routed to
 * MIDI channel 9.
 *
 * <p><b>GM program selection:</b> Based on connection mode (FM vs AM), feedback, and
 * modulator TL, since OPL2 has no instrument presets.
 */
public class Ym3812MidiConverter {

    private static final int CHANNELS = 9;
    private static final long DEFAULT_CLOCK = 3_579_545L;

    // Rhythm GM drum notes: HH, CY, TT, SD, BD (bit 0 → bit 4)
    private static final int[] RHYTHM_GM_NOTE = {42, 49, 45, 38, 36};

    private final long defaultClock;

    private final int[] fnumLo = new int[CHANNELS];
    private final int[] block = new int[CHANNELS];
    private final int[] fnumHi = new int[CHANNELS];     // bits 1-0 of 0xB0-0xB8
    private final boolean[] keyState = new boolean[CHANNELS];
    private final int[] activeNote = {-1, -1, -1, -1, -1, -1, -1, -1, -1};
    private final int[] currentProgram = {-1, -1, -1, -1, -1, -1, -1, -1, -1};

    private final int[] carrierTl = new int[CHANNELS];
    private final int[] modulatorTl = new int[CHANNELS];
    private final int[] feedback = new int[CHANNELS];
    private final int[] connection = new int[CHANNELS];

    private boolean rhythmMode = false;
    private int rhythmKeyState = 0;
    private final int[] activeRhythm = {-1, -1, -1, -1, -1};

    public Ym3812MidiConverter() {
        this(DEFAULT_CLOCK);
    }

    public Ym3812MidiConverter(long defaultClock) {
        this.defaultClock = defaultClock;
        java.util.Arrays.fill(carrierTl, 63);
        java.util.Arrays.fill(modulatorTl, 63);
    }

    public void convert(VgmEvent event, Track[] tracks, long clock, long tick) {
        int addr = event.rawData()[0] & 0xFF;
        int data = event.rawData()[1] & 0xFF;
        long effectiveClock = (clock > 0) ? clock : defaultClock;

        if (addr >= 0x40 && addr <= 0x55) {
            handleOperatorTl(addr, data, 0x40);
        } else if (addr >= 0xA0 && addr <= 0xA8) {
            fnumLo[addr - 0xA0] = data;
        } else if (addr >= 0xB0 && addr <= 0xB8) {
            handleKeyOnBlock(addr - 0xB0, data, tick, tracks, effectiveClock);
        } else if (addr >= 0xC0 && addr <= 0xC8) {
            int ch = addr - 0xC0;
            feedback[ch] = (data >> 1) & 0x07;
            connection[ch] = data & 0x01;
        } else if (addr == 0xBD) {
            handleRhythmControl(data, tick, tracks);
        }
        // Other operator ranges (0x20-0x35, 0x60-0x75, 0x80-0x95, 0xE0-0xF5) are
        // tracked for completeness but only TL affects MIDI output.
    }

    private void handleOperatorTl(int addr, int data, int base) {
        int offset = addr - base;
        int group = offset / 8;
        int slot = offset % 8;
        if (slot >= 6) return;

        int ch = group * 3 + slot % 3;
        if (ch >= CHANNELS) return;

        boolean isCarrier = slot >= 3;
        int tl = data & 0x3F;
        if (isCarrier) {
            carrierTl[ch] = tl;
        } else {
            modulatorTl[ch] = tl;
        }
    }

    private void handleKeyOnBlock(int ch, int data, long tick, Track[] tracks, long clock) {
        fnumHi[ch] = data & 0x03;
        block[ch] = (data >> 2) & 0x07;
        boolean newKeyOn = (data & 0x20) != 0;
        boolean wasKeyOn = keyState[ch];
        keyState[ch] = newKeyOn;

        // In rhythm mode, channels 6-8 key-on is handled by 0xBD register
        if (rhythmMode && ch >= 6) return;

        if (!wasKeyOn && newKeyOn) {
            noteOff(ch, tick, tracks);
            int note = computeNote(ch, clock);
            noteOn(ch, note, tick, tracks);
        } else if (wasKeyOn && !newKeyOn) {
            noteOff(ch, tick, tracks);
        }
    }

    private void handleRhythmControl(int data, long tick, Track[] tracks) {
        rhythmMode = (data & 0x20) != 0;
        if (!rhythmMode) return;

        int newKeyState = data & 0x1F;
        for (int bit = 0; bit < 5; bit++) {
            boolean wasOn = (rhythmKeyState & (1 << bit)) != 0;
            boolean isOn = (newKeyState & (1 << bit)) != 0;
            if (!wasOn && isOn) {
                int drumNote = RHYTHM_GM_NOTE[bit];
                addEvent(tracks[9], ShortMessage.NOTE_ON, 9, drumNote, 100, tick);
                activeRhythm[bit] = drumNote;
            } else if (wasOn && !isOn) {
                if (activeRhythm[bit] >= 0) {
                    addEvent(tracks[9], ShortMessage.NOTE_OFF, 9, activeRhythm[bit], 0, tick);
                    activeRhythm[bit] = -1;
                }
            }
        }
        rhythmKeyState = newKeyState;
    }

    private void noteOn(int ch, int note, long tick, Track[] tracks) {
        if (note < 0) return;
        emitProgramIfNeeded(ch, tick, tracks);
        int vel = Math.clamp(Math.round((63 - carrierTl[ch]) / 63.0f * 127), 1, 127);
        addEvent(tracks[ch], ShortMessage.NOTE_ON, ch, note, vel, tick);
        activeNote[ch] = note;
    }

    private void noteOff(int ch, long tick, Track[] tracks) {
        if (activeNote[ch] < 0) return;
        addEvent(tracks[ch], ShortMessage.NOTE_OFF, ch, activeNote[ch], 0, tick);
        activeNote[ch] = -1;
    }

    private void emitProgramIfNeeded(int ch, long tick, Track[] tracks) {
        int program = selectOpl2Program(connection[ch], feedback[ch], modulatorTl[ch]);
        if (program != currentProgram[ch]) {
            addEvent(tracks[ch], ShortMessage.PROGRAM_CHANGE, ch, program, 0, tick);
            currentProgram[ch] = program;
        }
    }

    private int computeNote(int ch, long clock) {
        int fnum = (fnumHi[ch] << 8) | fnumLo[ch];
        return opl2Note(clock, fnum, block[ch]);
    }

    /**
     * Converts OPL2 F-Number + block to MIDI note.
     * f = fnum * clock / (72 * 2^(20 - block))
     */
    static int opl2Note(long clock, int fnum, int block) {
        if (fnum <= 0) return -1;
        double f = fnum * clock / (72.0 * (1L << (20 - block)));
        return Math.clamp(Math.round(12 * Math.log(f / 440.0) / Math.log(2) + 69), 0, 127);
    }

    /**
     * Maps OPL2 connection mode, feedback, and modulator TL to a GM program number.
     *
     * <p>Connection 1 (AM/additive) produces organ-like or bell-like timbres.
     * Connection 0 (FM) ranges from bright lead sounds (low modTl) to soft tones (high modTl).
     */
    static int selectOpl2Program(int connection, int feedback, int modTl) {
        if (connection == 1) {
            return (modTl <= 30) ? 11 : 82; // Vibraphone or Calliope Lead
        }
        // FM mode
        if (feedback >= 6) return (modTl <= 50) ? 29 : 62; // Overdriven Guitar or Synth Brass
        if (modTl <= 20) return 81;  // Sawtooth Lead
        if (modTl <= 50) return 71;  // Clarinet
        return 82;                    // Calliope Lead
    }
}

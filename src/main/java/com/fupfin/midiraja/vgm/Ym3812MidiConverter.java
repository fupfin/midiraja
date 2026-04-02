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
 * Converts YM3812 (OPL2) and YMF262 (OPL3) VGM events to MIDI note events.
 *
 * <p>OPL2 has 9 channels (single port, VGM 0x5A). OPL3 has 18 channels across two ports
 * (VGM 0x5E / 0x5F) — each port is handled by a separate instance with a MIDI channel offset.
 * The register layout per port is identical.
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
    private final int midiChOffset; // 0 for OPL2/OPL3 port 0, 10 for OPL3 port 1

    private final int[] fnumLo = new int[CHANNELS];
    private final int[] block = new int[CHANNELS];
    private final int[] fnumHi = new int[CHANNELS];     // bits 1-0 of 0xB0-0xB8
    private final boolean[] keyState = new boolean[CHANNELS];
    private final int[] activeNote = {-1, -1, -1, -1, -1, -1, -1, -1, -1};
    private final int[] currentProgram = {-1, -1, -1, -1, -1, -1, -1, -1, -1};

    private final int[] carrierTl = new int[CHANNELS];
    private final int[] modulatorTl = new int[CHANNELS];
    private final int[] carrierAr = new int[CHANNELS];   // 4-bit, 0-15
    private final int[] carrierDr = new int[CHANNELS];   // 4-bit, 0-15
    private final int[] feedback = new int[CHANNELS];
    private final int[] connection = new int[CHANNELS];

    private final boolean[] activeDrum = new boolean[CHANNELS];
    private @org.jspecify.annotations.Nullable OplPatchCatalog patchCatalog;
    private boolean rhythmMode = false;
    private int rhythmKeyState = 0;
    private final int[] activeRhythm = {-1, -1, -1, -1, -1};

    public Ym3812MidiConverter() {
        this(DEFAULT_CLOCK, 0);
    }

    /** Sets a pre-built patch catalog for per-patch GM program assignment. */
    public void setPatchCatalog(OplPatchCatalog catalog) {
        this.patchCatalog = catalog;
    }

    public Ym3812MidiConverter(long defaultClock, int midiChOffset) {
        this.defaultClock = defaultClock;
        this.midiChOffset = midiChOffset;
        java.util.Arrays.fill(carrierTl, 63);
        java.util.Arrays.fill(modulatorTl, 63);
    }

    public void convert(VgmEvent event, Track[] tracks, long clock, long tick) {
        int addr = event.rawData()[0] & 0xFF;
        int data = event.rawData()[1] & 0xFF;
        long effectiveClock = (clock > 0) ? clock : defaultClock;

        if (addr >= 0x40 && addr <= 0x55) {
            handleOperatorTl(addr, data, 0x40);
        } else if (addr >= 0x60 && addr <= 0x75) {
            handleOperatorArDr(addr, data, 0x60);
        } else if (addr >= 0xA0 && addr <= 0xA8) {
            fnumLo[addr - 0xA0] = data;
        } else if (addr >= 0xB0 && addr <= 0xB8) {
            handleKeyOnBlock(addr - 0xB0, data, tick, tracks, effectiveClock);
        } else if (addr >= 0xC0 && addr <= 0xC8) {
            int ch = addr - 0xC0;
            feedback[ch] = (data >> 1) & 0x07;
            connection[ch] = data & 0x01;
            // Silent patch (conn=0, fb=0) used as instant note-cut trick:
            // immediately kill the active note instead of waiting for key-off.
            if (isSilentPatch(ch) && activeNote[ch] >= 0) {
                noteOff(ch, tick, tracks);
            }
        } else if (addr == 0xBD) {
            handleRhythmControl(data, tick, tracks);
        }
        // Other operator ranges (0x20-0x35, 0x80-0x95, 0xE0-0xF5): not needed for MIDI output.
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

    private void handleOperatorArDr(int addr, int data, int base) {
        int offset = addr - base;
        int group = offset / 8;
        int slot = offset % 8;
        if (slot >= 6) return;
        int ch = group * 3 + slot % 3;
        if (ch >= CHANNELS) return;
        boolean isCarrier = slot >= 3;
        if (isCarrier) {
            carrierAr[ch] = (data >> 4) & 0x0F;
            carrierDr[ch] = data & 0x0F;
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

    // conn=0, fb=0 with strong modulation (modTL < 10) is a percussive/effect patch
    // in many OPL2 game drivers. Route to MIDI ch 9 (drums) instead of melody.
    private static final int DRUM_NOTE_EFFECT = 42; // Closed Hi-Hat — metallic, unobtrusive

    /** Strong modulation → metallic/percussive effect → route to drums. */
    private boolean isPercussiveEffect(int ch) {
        return modulatorTl[ch] < 10 && !(connection[ch] == 0 && feedback[ch] == 0);
    }

    /** Patch is effectively silent → suppress entirely. */
    private boolean isSilentPatch(int ch) {
        // Zero-feedback FM: nearly silent init/reset state
        // Carrier TL >= 55: output attenuated by > 41 dB, inaudible on real hardware
        return (connection[ch] == 0 && feedback[ch] == 0)
                || carrierTl[ch] >= 55;
    }

    private void noteOn(int ch, int note, long tick, Track[] tracks) {
        if (note < 0) return;

        int sig = OplPatchCatalog.signature(
                connection[ch], feedback[ch], modulatorTl[ch], carrierTl[ch],
                carrierAr[ch], carrierDr[ch]);

        // Use catalog if available, otherwise fall back to hardcoded rules
        if (patchCatalog != null) {
            if (patchCatalog.isSilent(sig)) return;
            if (patchCatalog.isDrumEffect(sig)) {
                if (9 < tracks.length) {
                    int vel = Math.clamp(Math.round((63 - carrierTl[ch]) / 63.0f * 127), 1, 127);
                    addEvent(tracks[9], ShortMessage.NOTE_ON, 9, DRUM_NOTE_EFFECT, vel, tick);
                    activeNote[ch] = note;
                    activeDrum[ch] = true;
                }
                return;
            }
            int midiCh = ch + midiChOffset;
            if (midiCh >= tracks.length || midiCh == 9) return;
            int program = patchCatalog.program(sig);
            if (program != currentProgram[ch]) {
                addEvent(tracks[midiCh], ShortMessage.PROGRAM_CHANGE, midiCh, program, 0, tick);
                currentProgram[ch] = program;
            }
            int vel = Math.clamp(Math.round((63 - carrierTl[ch]) / 63.0f * 127), 1, 127);
            addEvent(tracks[midiCh], ShortMessage.NOTE_ON, midiCh, note, vel, tick);
            activeNote[ch] = note;
            activeDrum[ch] = false;
        } else {
            // Legacy hardcoded path
            if (isSilentPatch(ch)) return;
            if (isPercussiveEffect(ch)) {
                if (9 < tracks.length) {
                    int vel = Math.clamp(Math.round((63 - carrierTl[ch]) / 63.0f * 127), 1, 127);
                    addEvent(tracks[9], ShortMessage.NOTE_ON, 9, DRUM_NOTE_EFFECT, vel, tick);
                    activeNote[ch] = note;
                    activeDrum[ch] = true;
                }
                return;
            }
            int midiCh = ch + midiChOffset;
            if (midiCh >= tracks.length || midiCh == 9) return;
            int vel = Math.clamp(Math.round((63 - carrierTl[ch]) / 63.0f * 127), 1, 127);
            addEvent(tracks[midiCh], ShortMessage.NOTE_ON, midiCh, note, vel, tick);
            activeNote[ch] = note;
            activeDrum[ch] = false;
        }
    }

    private void noteOff(int ch, long tick, Track[] tracks) {
        if (activeNote[ch] < 0) return;
        if (activeDrum[ch]) {
            if (9 < tracks.length) {
                addEvent(tracks[9], ShortMessage.NOTE_OFF, 9, DRUM_NOTE_EFFECT, 0, tick);
            }
        } else {
            int midiCh = ch + midiChOffset;
            if (midiCh >= tracks.length || midiCh == 9) return;
            addEvent(tracks[midiCh], ShortMessage.NOTE_OFF, midiCh, activeNote[ch], 0, tick);
        }
        activeNote[ch] = -1;
    }

    private void emitProgramIfNeeded(int ch, int midiCh, int note, long tick, Track[] tracks) {
        boolean perc = FmMidiUtil.isPercussive(carrierAr[ch], carrierDr[ch]);
        int program = FmMidiUtil.selectProgram(note, perc);
        if (program != currentProgram[ch]) {
            addEvent(tracks[midiCh], ShortMessage.PROGRAM_CHANGE, midiCh, program, 0, tick);
            currentProgram[ch] = program;
        }
    }

    private int computeNote(int ch, long clock) {
        int fnum = (fnumHi[ch] << 8) | fnumLo[ch];
        return opl2Note(clock, fnum, block[ch]);
    }

    /**
     * Converts OPL2/OPL3 F-Number + block to MIDI note.
     *
     * <p>Formula: {@code f = fnum * clock / (72 * 2^(20 - block))}.
     * When the fundamental frequency falls below the audible threshold (40 Hz),
     * the note is shifted up by octaves. FM synthesis with strong modulation produces
     * harmonics far stronger than the fundamental; at sub-bass frequencies the perceived
     * pitch is typically 1-2 octaves above the fundamental.
     */
    static int opl2Note(long clock, int fnum, int block) {
        if (fnum <= 0) return -1;
        double f = fnum * clock / (72.0 * (1L << (20 - block)));
        int note = (int) Math.round(12 * Math.log(f / 440.0) / Math.log(2) + 69);
        // OPL2 FM synthesis produces strong harmonics (especially 2nd) that raise the
        // perceived pitch above the fundamental. Piano lacks these harmonics, so playing
        // at the fundamental frequency sounds one octave too low. +12 compensates.
        note += 12;
        return Math.clamp(note, 0, 127);
    }

    /**
     * Maps OPL2 parameters to a GM program from the same 7-instrument ensemble palette.
     *
     * <p>AM (additive) mode → harmony instruments (Vibraphone/Sawtooth Lead).
     * FM mode → lead instruments (Electric Piano/Square Lead).
     * High feedback → aggressive variants (Clavinet/Sawtooth Lead).
     */
    static int selectOpl2Program(int connection, int feedback, int modTl, boolean percussive) {
        if (feedback >= 6) return percussive ? 7 : 81;     // Clavinet or Sawtooth Lead
        if (connection == 1) return percussive ? 11 : 81;  // AM: Vibraphone or Sawtooth Lead
        return percussive ? 4 : 80;                         // FM: Electric Piano 1 or Square Lead
    }
}

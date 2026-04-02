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

    private static final int MIDI_CH_OFFSET = 3;
    private static final int DEFAULT_DIVIDER = 144; // YM2612/YM2608/YM2610

    private final int fmDivider;
    private final int port1ChipId;

    /** Default constructor for YM2612 (divider=144, port1 chip ID=2). */
    public Ym2612MidiConverter() {
        this(DEFAULT_DIVIDER, 2);
    }

    /**
     * @param fmDivider  FM frequency divider: 144 for YM2612/YM2608/YM2610, 72 for YM2203
     * @param port1ChipId chip ID that indicates port 1 events; -1 for single-port chips (YM2203)
     */
    public Ym2612MidiConverter(int fmDivider, int port1ChipId) {
        this.fmDivider = fmDivider;
        this.port1ChipId = port1ChipId;
    }

    private final int[] fnumHigh = new int[6];
    private final int[] fnumLow = new int[6];
    private final int[] activeNote = {-1, -1, -1, -1, -1, -1};
    private final int[] algorithm = new int[6];
    private final int[] feedback = new int[6];
    private final int[] currentProgram = {-1, -1, -1, -1, -1, -1};
    // TL per channel per operator. Op index: 0=S1(0x40), 1=S3(0x44), 2=S2(0x48), 3=S4(0x4C).
    private final int[][] tl = {
        {127, 127, 127, 127}, {127, 127, 127, 127}, {127, 127, 127, 127},
        {127, 127, 127, 127}, {127, 127, 127, 127}, {127, 127, 127, 127}
    };
    // AR per channel per operator (5-bit, 0-31). Used for envelope classification.
    private final int[][] ar = new int[6][4];
    // D1R per channel per operator (5-bit, 0-31).
    private final int[][] d1r = new int[6][4];
    private final int[] lrMask = {3, 3, 3, 3, 3, 3};
    private final int[] currentPan = {-1, -1, -1, -1, -1, -1};
    private @org.jspecify.annotations.Nullable FmPatchCatalog patchCatalog;

    /** Sets a pre-built patch catalog for per-patch GM program assignment. */
    public void setPatchCatalog(FmPatchCatalog catalog) {
        this.patchCatalog = catalog;
    }

    public void convert(VgmEvent event, Track[] tracks, long clock, long tick) {
        int addr = event.rawData()[0] & 0xFF;
        int data = event.rawData()[1] & 0xFF;
        int portOffset = (event.chip() == port1ChipId) ? 3 : 0;

        if (addr >= 0x40 && addr <= 0x4F) {
            int op = (addr - 0x40) >> 2;
            int ch = (addr & 0x03) + portOffset;
            if (ch < 6) tl[ch][op] = data & 0x7F;
        } else if (addr >= 0x50 && addr <= 0x5F) {
            // AR (Attack Rate): bits 4-0
            int op = (addr - 0x50) >> 2;
            int ch = (addr & 0x03) + portOffset;
            if (ch < 6) ar[ch][op] = data & 0x1F;
        } else if (addr >= 0x60 && addr <= 0x6F) {
            // D1R (First Decay Rate): bits 4-0
            int op = (addr - 0x60) >> 2;
            int ch = (addr & 0x03) + portOffset;
            if (ch < 6) d1r[ch][op] = data & 0x1F;
        } else if (addr >= 0xB0 && addr <= 0xB2) {
            int ch = (addr - 0xB0) + portOffset;
            algorithm[ch] = data & 0x07;
            feedback[ch] = (data >> 3) & 0x07;
        } else if (addr >= 0xB4 && addr <= 0xB6) {
            int ch = (addr - 0xB4) + portOffset;
            lrMask[ch] = (data >> 6) & 0x03;
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
        int chSelect = data & 0x07;
        int ch = switch (chSelect) {
            case 0, 1, 2 -> chSelect;
            case 4, 5, 6 -> chSelect - 1;
            default -> -1;
        };
        if (ch < 0) return;

        int midiCh = ch + MIDI_CH_OFFSET;
        boolean keyOn = (data & 0xF0) != 0;

        if (keyOn) {
            if (activeNote[ch] >= 0) {
                addEvent(tracks[midiCh], ShortMessage.NOTE_OFF, midiCh, activeNote[ch], 0, tick);
                activeNote[ch] = -1;
            }
            int note = computeNote(ch, clock);
            if (note >= 0 && !isSilentCarrier(tl, algorithm, ch)) {
                emitPanIfNeeded(ch, midiCh, tracks, tick);
                if (patchCatalog != null) {
                    emitCatalogProgramIfNeeded(ch, midiCh, tracks, tick);
                } else {
                    emitProgramIfNeeded(ch, midiCh, note, tracks, tick);
                }
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

    private void emitPanIfNeeded(int ch, int midiCh, Track[] tracks, long tick) {
        int pan = lrMaskToPan(lrMask[ch]);
        if (pan != currentPan[ch]) {
            addEvent(tracks[midiCh], ShortMessage.CONTROL_CHANGE, midiCh, 10, pan, tick);
            currentPan[ch] = pan;
        }
    }

    private void emitCatalogProgramIfNeeded(int ch, int midiCh, Track[] tracks, long tick) {
        var catalog = patchCatalog;
        if (catalog == null) return;
        int timbreHint = FmPatchCatalog.algorithmToTimbreHint(algorithm[ch]);
        int avgModTl = avgModulatorTl(tl, algorithm, ch);
        int[] cops = carrierOps(algorithm[ch]);
        int totalCarTl = 0, totalAr = 0, totalDr = 0;
        for (int op : cops) {
            totalCarTl += tl[ch][op];
            totalAr += ar[ch][op];
            totalDr += d1r[ch][op];
        }
        int avgCarTl = totalCarTl / cops.length;
        int avgAr = totalAr / cops.length / 2;
        int avgDr = totalDr / cops.length / 2;
        int sig = FmPatchCatalog.signature(timbreHint, feedback[ch], avgModTl, avgCarTl, avgAr, avgDr);
        int program = catalog.program(sig);
        if (program != currentProgram[ch]) {
            addEvent(tracks[midiCh], ShortMessage.PROGRAM_CHANGE, midiCh, program, 0, tick);
            currentProgram[ch] = program;
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

    private int computeNote(int ch, long clock) {
        int fnum = (fnumHigh[ch] & 0x07) << 8 | fnumLow[ch];
        int block = (fnumHigh[ch] >> 3) & 0x07;
        return opnNote(clock, fnum, block, fmDivider);
    }

    static int opnNote(long clock, int fnum, int block, int divider) {
        if (fnum <= 0) return -1;
        double f = fnum * clock / ((double) divider * (1L << (21 - block)));
        return clampNote((int) Math.round(12 * Math.log(f / 440.0) / Math.log(2) + 69));
    }

    private static int clampNote(int note) {
        return Math.clamp(note, 0, 127);
    }
}

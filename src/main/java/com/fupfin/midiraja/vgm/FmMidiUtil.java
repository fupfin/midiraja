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
 * Shared utilities for FM chip → MIDI converters (YM2612, YM2151, etc.).
 *
 * <p>All OPN/OPM-family chips share the same eight algorithm topologies and four-operator
 * architecture. This class provides common GM program selection, carrier operator lookup,
 * velocity computation, and MIDI event helpers.
 */
final class FmMidiUtil {

    private static final int PROGRAM_SAWTOOTH_LEAD     = 81;
    private static final int PROGRAM_CHIFF_LEAD        = 83;
    private static final int PROGRAM_CALLIOPE_LEAD     = 82;
    private static final int PROGRAM_CLARINET          = 71;
    private static final int PROGRAM_ELECTRIC_BASS     = 33;
    private static final int PROGRAM_SYNTH_BASS        = 38;
    private static final int PROGRAM_OVERDRIVEN_GUITAR = 29;
    private static final int PROGRAM_SYNTH_BRASS1      = 62;
    private static final int PROGRAM_SYNTH_STRINGS1    = 50;
    private static final int PROGRAM_SYNTH_PAD2        = 89;

    /** Reference TL for velocity scaling: carrier TL == REF_TL plays at velocity=127. */
    static final int REF_TL = 20;
    /** dB attenuation per feedback step (0–7) for perceived-loudness correction. */
    static final double FB_DB_PER_STEP = 0.375;

    private FmMidiUtil() {}

    /**
     * Maps FM algorithm + feedback + modulator TL to a GM program number.
     *
     * <p>The eight algorithms are identical across OPN (YM2612) and OPM (YM2151) families.
     * Modulator TL indicates modulation depth: low TL = strong modulation (bright/metallic),
     * high TL = weak modulation (soft/pure).
     *
     * @param modTl average TL of modulator operators (0–127). 127 means no modulators (alg 7).
     */
    static int selectProgram(int alg, int fb, int modTl) {
        if (fb >= 6) {
            return switch (alg) {
                case 0, 1, 2, 3 -> PROGRAM_OVERDRIVEN_GUITAR;
                case 4           -> PROGRAM_CHIFF_LEAD;
                default          -> PROGRAM_SYNTH_BRASS1;
            };
        }
        return switch (alg) {
            case 0 -> PROGRAM_ELECTRIC_BASS;
            case 1 -> PROGRAM_SYNTH_BASS;
            case 2, 3 -> PROGRAM_SAWTOOTH_LEAD;
            case 4 -> selectAlg4(modTl);
            case 5 -> PROGRAM_SYNTH_PAD2;
            case 6 -> PROGRAM_SYNTH_STRINGS1;
            case 7 -> PROGRAM_SYNTH_BRASS1;
            default -> PROGRAM_SAWTOOTH_LEAD;
        };
    }

    private static int selectAlg4(int modTl) {
        if (modTl <= 20) return PROGRAM_SAWTOOTH_LEAD;  // strong modulation → bright, metallic
        if (modTl <= 50) return PROGRAM_CLARINET;        // moderate → warm lead
        return PROGRAM_CALLIOPE_LEAD;                    // weak modulation → pure, sine-like
    }

    /**
     * Returns operator indices that are modulators for the given algorithm
     * (complement of {@link #carrierOps}).
     */
    static int[] modulatorOps(int alg) {
        return switch (alg) {
            case 4    -> new int[]{0, 1};
            case 5, 6 -> new int[]{0};
            case 7    -> new int[]{};
            default   -> new int[]{0, 1, 2};
        };
    }

    /** Average TL of modulator operators. Returns 127 when no modulators exist (alg 7). */
    static int avgModulatorTl(int[][] tl, int[] algorithm, int ch) {
        int[] ops = modulatorOps(algorithm[ch]);
        if (ops.length == 0) return 127;
        int total = 0;
        for (int op : ops) total += tl[ch][op];
        return total / ops.length;
    }

    /**
     * Returns operator indices that are carriers for the given algorithm.
     *
     * <p>Operator index ordering: 0=first slot, 1=second slot, 2=third slot, 3=fourth slot.
     * The physical register-to-slot mapping differs between chips (e.g. YM2612 uses
     * S1/S3/S2/S4 ordering while YM2151 uses M1/M2/C1/C2), but both converters normalize
     * to the same 0–3 index space before calling this method.
     */
    static int[] carrierOps(int alg) {
        return switch (alg) {
            case 4    -> new int[]{2, 3};
            case 5, 6 -> new int[]{1, 2, 3};
            case 7    -> new int[]{0, 1, 2, 3};
            default   -> new int[]{3};
        };
    }

    /** Computes MIDI velocity from carrier TL average and feedback, using dB-based scaling. */
    static int computeVelocity(int[][] tl, int[] algorithm, int[] feedback, int ch) {
        int[] ops = carrierOps(algorithm[ch]);
        int totalTl = 0;
        for (int op : ops) totalTl += tl[ch][op];
        double avgTl = (double) totalTl / ops.length;
        double tlDb  = (avgTl - REF_TL) * 0.75;
        double fbDb  = feedback[ch] * FB_DB_PER_STEP;
        double amplitude = Math.pow(10.0, -(tlDb + fbDb) / 20.0);
        return Math.min(127, Math.max(1, (int) Math.round(amplitude * 127)));
    }

    /** Maps LR mask (bit1=L, bit0=R) to MIDI CC10 pan value. */
    static int lrMaskToPan(int lrMask) {
        return switch (lrMask) {
            case 2 -> 0;    // L only → hard left
            case 1 -> 127;  // R only → hard right
            default -> 64;  // L+R (3) or off (0) → center
        };
    }

    /** Adds a MIDI event to the track. */
    static void addEvent(Track track, int command, int ch, int d1, int d2, long tick) {
        try {
            track.add(new MidiEvent(new ShortMessage(command, ch, d1, d2), tick));
        } catch (InvalidMidiDataException e) {
            throw new IllegalStateException("Bad MIDI data", e);
        }
    }
}

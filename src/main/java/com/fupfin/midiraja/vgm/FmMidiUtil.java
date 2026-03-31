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

    // A small set of fast-attack instruments that blend well in chip music ensembles.
    // Slow-attack instruments (Clarinet, Recorder, Strings, Pads) are avoided — they
    // don't match the tight timing of VGM playback and create a muddy mix.
    private static final int PROGRAM_ELECTRIC_PIANO1   = 4;   // default percussive: clean, fast
    private static final int PROGRAM_VIBRAPHONE        = 11;  // bright percussive: bell-like
    private static final int PROGRAM_ELECTRIC_BASS     = 33;  // sustained bass
    private static final int PROGRAM_SLAP_BASS         = 36;  // percussive bass
    private static final int PROGRAM_SYNTH_BRASS1      = 62;  // default sustained: bright, blends well

    /** Reference TL for velocity scaling: carrier TL == REF_TL plays at velocity=127. */
    static final int REF_TL = 20;
    /** dB attenuation per feedback step (0–7) for perceived-loudness correction. */
    static final double FB_DB_PER_STEP = 0.375;

    private FmMidiUtil() {}

    /**
     * Maps FM algorithm + envelope character to a GM program.
     *
     * <p>Uses a small set of fast-attack instruments that blend in chip music ensembles.
     * Bass algorithms (0, 1) get bass programs; all others get either a percussive
     * instrument (Electric Piano, Vibraphone) or a sustained one (Synth Brass).
     *
     * @param percussive true if carrier envelope has fast attack + fast decay
     */
    static int selectProgram(int alg, int fb, int modTl, boolean percussive) {
        return switch (alg) {
            case 0, 1 -> percussive ? PROGRAM_SLAP_BASS : PROGRAM_ELECTRIC_BASS;
            default   -> percussive ? PROGRAM_ELECTRIC_PIANO1 : PROGRAM_SYNTH_BRASS1;
        };
    }

    /**
     * Returns true if the carrier envelope has fast attack and non-trivial decay (percussive).
     * AR and DR are normalized to 0–15 range before calling.
     */
    static boolean isPercussive(int ar15, int dr15) {
        return ar15 >= 10 && dr15 >= 4;
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

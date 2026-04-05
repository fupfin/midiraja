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
public final class FmMidiUtil {

    // 4-instrument ensemble: smooth, range-appropriate, blends well in any combination.
    private static final int PROGRAM_ELECTRIC_PIANO2   = 5;   // melody sustained: warm, smooth
    private static final int PROGRAM_VIBRAPHONE        = 11;  // melody percussive: bell-like, clean
    private static final int PROGRAM_ELECTRIC_BASS     = 33;  // bass sustained
    private static final int PROGRAM_SLAP_BASS         = 36;  // bass percussive

    /** Reference TL for velocity scaling: carrier TL == REF_TL plays at velocity=127. */
    static final int REF_TL = 20;
    /** dB attenuation per feedback step (0–7) for perceived-loudness correction. */
    static final double FB_DB_PER_STEP = 0.375;

    private FmMidiUtil() {}

    private static final int BASS_THRESHOLD = 48; // C3: notes below this → bass instruments

    /**
     * Selects a GM program based on note range and envelope character.
     *
     * <p>Uses the first note's pitch to determine the channel's role (bass vs melody),
     * then envelope character (percussive vs sustained) to pick within that role.
     * Only 4 smooth instruments that blend well in any combination.
     *
     * @param note MIDI note number of the current note (0–127)
     * @param percussive true if carrier envelope has fast attack + fast decay
     */
    static int selectProgram(int note, boolean percussive) {
        if (note < BASS_THRESHOLD) {
            return percussive ? PROGRAM_SLAP_BASS : PROGRAM_ELECTRIC_BASS;
        }
        return percussive ? PROGRAM_VIBRAPHONE : PROGRAM_ELECTRIC_PIANO2;
    }

    /**
     * @deprecated Use {@link #selectProgram(int, boolean)} instead. This overload exists
     * only for backward compatibility during transition; alg/fb/modTl are no longer used.
     */
    static int selectProgram(int alg, int fb, int modTl, boolean percussive) {
        // Default to melody range when note is unknown at program selection time
        return selectProgram(60, percussive);
    }

    /**
     * Returns true if the carrier envelope has fast attack and fast decay (percussive).
     * AR and DR are normalized to 0–15 range before calling.
     * DR 4–6 has moderate decay with clear sustain character (piano/clavinet);
     * DR ≥ 7 is truly percussive (marimba/xylophone).
     */
    static boolean isPercussive(int ar15, int dr15) {
        return ar15 >= 10 && dr15 >= 7;
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

    /**
     * Returns true if all carrier operators are near-silent (average TL ≥ 55 = −41 dB).
     * Such patches produce inaudible output on real hardware and should be suppressed
     * to avoid ghost notes in the MIDI conversion.
     */
    static boolean isSilentCarrier(int[][] tl, int[] algorithm, int ch) {
        int[] ops = carrierOps(algorithm[ch]);
        int total = 0;
        for (int op : ops) total += tl[ch][op];
        return total / ops.length >= 55;
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
    public static void addEvent(Track track, int command, int ch, int d1, int d2, long tick) {
        try {
            track.add(new MidiEvent(new ShortMessage(command, ch, d1, d2), tick));
        } catch (InvalidMidiDataException e) {
            throw new IllegalStateException("Bad MIDI data", e);
        }
    }
}

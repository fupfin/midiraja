/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.vgm;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Catalogs unique FM patches from a VGM file and assigns GM programs.
 *
 * <p>
 * Sealed abstract base for chip-family-specific implementations:
 * <ul>
 * <li>{@link Opl2PatchCatalog} — 2-operator (OPL2, OPL3)
 * <li>{@link FourOpPatchCatalog} — 4-operator (YM2612, YM2151, OPN family)
 * </ul>
 *
 * <p>
 * Built during the first pass over VGM events. Each unique patch signature is assigned
 * a GM program based on timbre, pitch range, and envelope character. The converter queries
 * {@link #program(int)} during the second pass (actual MIDI conversion).
 */
sealed abstract class FmPatchCatalog permits Opl2PatchCatalog, FourOpPatchCatalog
{

    static final int DRUM_EFFECT = -2;
    static final int SILENT = -1;

    // [timbre][range][envelope] — timbre: 0=FM-soft, 1=FM-bright, 2=FM-harsh, 3=AM
    // range: 0=bass, 1=mid, 2=high
    // envelope: 0=sustained, 1=percussive
    static final int[][][] GM_TABLE = {
            // FM-soft (hint=0, fb<5, effectiveModTL≥30): warm, pure
            { { 32, 36 }, { 5, 12 }, { 5, 13 } },
            // FM-bright (hint=0, fb<5, effectiveModTL<30): bright, metallic
            { { 33, 36 }, { 4, 11 }, { 4, 13 } },
            // FM-harsh (hint=0, fb≥5): aggressive, distorted
            { { 33, 36 }, { 7, 7 }, { 7, 13 } },
            // AM (hint=1): organ-like
            { { 32, 36 }, { 18, 18 }, { 18, 18 } },
    };

    static final int BASS_THRESHOLD = 48;
    static final int HIGH_THRESHOLD = 72;

    protected final Map<Integer, Integer> signatureToProgram = new HashMap<>();

    /** Returns the GM program for the given signature, or 0 (Grand Piano) if unknown. */
    int program(int signature)
    {
        return signatureToProgram.getOrDefault(signature, 0);
    }

    /** Returns true if this signature should be suppressed (silent patch). */
    boolean isSilent(int signature)
    {
        return signatureToProgram.getOrDefault(signature, 0) == SILENT;
    }

    /** Returns true if this signature should be routed to drums. */
    boolean isDrumEffect(int signature)
    {
        return signatureToProgram.getOrDefault(signature, 0) == DRUM_EFFECT;
    }

    /**
     * Computes a patch signature from current channel state.
     * Quantizes modTL and carrierTL into bands to avoid excessive unique patches.
     */
    static int signature(int timbreHint, int fb, int modTl, int carTl, int carAr, int carDr)
    {
        int modBand = modTl < 10 ? 0 : modTl < 30 ? 1 : modTl < 50 ? 2 : 3;
        int carBand = carTl < 15 ? 0 : carTl < 30 ? 1 : carTl < 45 ? 2 : 3;
        return (timbreHint & 0xF) << 24
                | (fb & 0xF) << 20
                | (modBand & 0xF) << 16
                | (carBand & 0xF) << 12
                | (carAr & 0xF) << 8
                | (carDr & 0xF);
    }

    /** Maps 4-op algorithm to timbre hint: 0=FM-serial (alg 0-4), 1=AM-parallel (alg 5-7). */
    static int algorithmToTimbreHint(int alg)
    {
        return alg >= 5 ? 1 : 0;
    }

    /**
     * Assigns a GM program from signature fields and median note range.
     * Shared classification: timbre × pitch-range × envelope → GM_TABLE.
     */
    protected static int assignGmProgram(int sig, List<Integer> notes)
    {
        int hint = (sig >> 24) & 0xF;
        int fb = (sig >> 20) & 0xF;
        int modTlBand = (sig >> 16) & 0xF;
        int carTlBand = (sig >> 12) & 0xF;
        int ar = (sig >> 8) & 0xF;
        int dr = sig & 0xF;

        notes.sort(Integer::compareTo);
        int median = notes.get(notes.size() / 2);

        // Effective modulation strength
        int approxModTl = modTlBand == 0 ? 5 : modTlBand == 1 ? 20 : modTlBand == 2 ? 40 : 55;
        int approxCarTl = carTlBand == 0 ? 8 : carTlBand == 1 ? 22 : carTlBand == 2 ? 37 : 50;
        int effectiveModTl = approxModTl + Math.max(0, approxCarTl - 15);
        int effModBand = effectiveModTl < 10
                ? 0
                : effectiveModTl < 30
                        ? 1
                        : effectiveModTl < 50 ? 2 : 3;

        // Timbre: 0=FM-soft, 1=FM-bright, 2=FM-harsh, 3=AM
        int timbre;
        if (hint == 1)
        {
            timbre = 3;
        }
        else if (fb >= 5)
        {
            timbre = 2;
        }
        else if (effModBand <= 1)
        {
            timbre = 1;
        }
        else
        {
            timbre = 0;
        }

        int range = (median < BASS_THRESHOLD) ? 0 : (median >= HIGH_THRESHOLD) ? 2 : 1;
        // Percussive: fast attack + fast decay. DR 0-15 scale:
        // 0-3 = near-sustained, 4-6 = moderate decay (sustain character),
        // 7+ = fast decay (truly percussive: marimba, xylophone).
        // DR 4-6 patches have clear sustain and suit piano/clavinet, not xylophone.
        int env = (ar >= 10 && dr >= 7) ? 1 : 0;

        return GM_TABLE[timbre][range][env];
    }
}

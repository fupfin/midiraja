/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.vgm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Catalogs unique OPL2/OPL3 FM patches from a VGM file and assigns GM programs
 * based on FM characteristics and the typical note range of each patch.
 *
 * <p>Built during the first pass over VGM events. Each unique patch signature
 * (connection, feedback, quantized modulator TL, carrier AR, carrier DR) is
 * associated with the notes it plays. After scanning, each signature is assigned
 * a GM program based on its percussive/sustained character and median note range.
 *
 * <p>During the second pass (actual MIDI conversion), the converter queries
 * {@link #program(int)} with the current patch signature to get the GM program.
 */
final class OplPatchCatalog {

    // GM instrument palette — timbre × pitch-range × envelope (3 dimensions)
    // Timbre: FM-soft (conn=0,fb<5,modTL≥30), FM-bright (conn=0,fb<5,modTL<30),
    //         FM-harsh (conn=0,fb≥5), AM (conn=1)
    private static final int DRUM_EFFECT = -2; // sentinel: route to ch 9
    private static final int SILENT      = -1; // sentinel: suppress

    // [timbre][range][envelope] — timbre: 0=FM-soft, 1=FM-bright, 2=FM-harsh, 3=AM
    //                             range: 0=bass, 1=mid, 2=high
    //                             envelope: 0=sustained, 1=percussive
    private static final int[][][] GM_TABLE = {
        // FM-soft (conn=0, fb<5, modTL≥30): warm, pure
        {{32, 36}, {5, 12}, {5, 13}},         // Ac.Bass/Slap, EP2/Marimba, EP2/Xylophone
        // FM-bright (conn=0, fb<5, modTL<30): bright, metallic
        {{33, 36}, {4, 11}, {4, 13}},         // Elec.Bass/Slap, EP1/Vibraphone, EP1/Xylophone
        // FM-harsh (conn=0, fb≥5): aggressive, distorted
        {{33, 36}, {7, 7}, {7, 13}},          // Elec.Bass/Slap, Clavinet/Clavinet, Clavinet/Xylophone
        // AM (conn=1): organ-like — use Rock Organ for both sustained and percussive
        // (VGM key-off timing provides short notes; no need to switch instrument)
        {{32, 36}, {18, 18}, {18, 18}},       // Ac.Bass/Slap, Rock Organ/Rock Organ, Rock Organ/Rock Organ
    };

    private static final int BASS_THRESHOLD = 48;  // < C3
    private static final int HIGH_THRESHOLD = 72;  // >= C5

    private final Map<Integer, Integer> signatureToProgram = new HashMap<>();

    /**
     * Builds a catalog by scanning OPL2/OPL3 events from a VgmParseResult.
     *
     * @param parsed the parsed VGM data
     * @param chipIds chip IDs to scan (14=OPL2, 15=OPL3 port0, 16=OPL3 port1)
     * @param effectiveClock the OPL clock used for note calculation
     */
    static OplPatchCatalog build(VgmParseResult parsed, int[] chipIds, long effectiveClock) {
        var catalog = new OplPatchCatalog();

        // Simulate register state per port (9 channels each)
        var state = new PortState[2]; // 0=port0, 1=port1
        state[0] = new PortState();
        state[1] = new PortState();

        // Collect notes per signature
        Map<Integer, List<Integer>> sigNotes = new HashMap<>();

        for (var event : parsed.events()) {
            int chip = event.chip();
            int portIdx = -1;
            for (int id : chipIds) {
                if (chip == id) {
                    // OPL2(14) and OPL3-port0(15) → port 0; OPL3-port1(16) → port 1
                    portIdx = (chip == 16) ? 1 : 0;
                    break;
                }
            }
            if (portIdx < 0) continue;

            var ps = state[portIdx];
            int reg = event.rawData()[0] & 0xFF;
            int val = event.rawData()[1] & 0xFF;

            if (reg >= 0x40 && reg <= 0x55) {
                int[] decoded = decodeSlot(reg, 0x40);
                if (decoded != null) {
                    if (decoded[1] == 1) ps.carrierTl[decoded[0]] = val & 0x3F;
                    else ps.modulatorTl[decoded[0]] = val & 0x3F;
                }
            } else if (reg >= 0x60 && reg <= 0x75) {
                int[] decoded = decodeSlot(reg, 0x60);
                if (decoded != null && decoded[1] == 1) {
                    ps.carrierAr[decoded[0]] = (val >> 4) & 0xF;
                    ps.carrierDr[decoded[0]] = val & 0xF;
                }
            } else if (reg >= 0xA0 && reg <= 0xA8) {
                ps.fnumLo[reg - 0xA0] = val;
            } else if (reg >= 0xB0 && reg <= 0xB8) {
                int ch = reg - 0xB0;
                ps.fnumHi[ch] = val & 0x03;
                ps.block[ch] = (val >> 2) & 0x07;
                boolean keyOn = (val & 0x20) != 0;
                if (keyOn && !ps.keyState[ch]) {
                    int sig = ps.signature(ch);
                    int fnum = (ps.fnumHi[ch] << 8) | ps.fnumLo[ch];
                    int note = Ym3812MidiConverter.opl2Note(effectiveClock, fnum, ps.block[ch]);
                    if (note >= 0) {
                        sigNotes.computeIfAbsent(sig, k -> new ArrayList<>()).add(note);
                    }
                }
                ps.keyState[ch] = keyOn;
            } else if (reg >= 0xC0 && reg <= 0xC8) {
                int ch = reg - 0xC0;
                ps.feedback[ch] = (val >> 1) & 0x07;
                ps.connection[ch] = val & 0x01;
            }
        }

        // Assign GM program to each signature
        for (var entry : sigNotes.entrySet()) {
            int sig = entry.getKey();
            var notes = entry.getValue();

            int conn = (sig >> 24) & 0xF;
            int fb = (sig >> 20) & 0xF;
            int modTlBand = (sig >> 16) & 0xF;
            int ar = (sig >> 8) & 0xF;
            int dr = sig & 0xF;
            // Carrier TL band is encoded in bits 12-15
            int carTlBand = (sig >> 12) & 0xF;

            // Silent?
            boolean silent = (conn == 0 && fb == 0) || carTlBand >= 3; // band 3 = TL 45+
            if (silent) {
                catalog.signatureToProgram.put(sig, SILENT);
                continue;
            }

            // Percussive effect?
            if (modTlBand == 0 && !(conn == 0 && fb == 0)) { // modTL 0-9
                catalog.signatureToProgram.put(sig, DRUM_EFFECT);
                continue;
            }

            // Compute median note
            notes.sort(Integer::compareTo);
            int median = notes.get(notes.size() / 2);

            // Timbre classification: 0=FM-soft, 1=FM-bright, 2=FM-harsh, 3=AM
            int timbre;
            if (conn == 1) {
                timbre = 3; // AM
            } else if (fb >= 5) {
                timbre = 2; // FM-harsh
            } else if (modTlBand <= 1) { // modTL < 30
                timbre = 1; // FM-bright
            } else {
                timbre = 0; // FM-soft
            }

            // Pitch range: 0=bass, 1=mid, 2=high
            int range = (median < BASS_THRESHOLD) ? 0 : (median >= HIGH_THRESHOLD) ? 2 : 1;

            // Envelope: 0=sustained, 1=percussive
            int env = (ar >= 10 && dr >= 4) ? 1 : 0;

            catalog.signatureToProgram.put(sig, GM_TABLE[timbre][range][env]);
        }

        return catalog;
    }

    /** Returns the GM program for the given signature, or 0 (Grand Piano) if unknown. */
    int program(int signature) {
        return signatureToProgram.getOrDefault(signature, 0);
    }

    /** Returns true if this signature should be suppressed (silent patch). */
    boolean isSilent(int signature) {
        return signatureToProgram.getOrDefault(signature, 0) == SILENT;
    }

    /** Returns true if this signature should be routed to drums. */
    boolean isDrumEffect(int signature) {
        return signatureToProgram.getOrDefault(signature, 0) == DRUM_EFFECT;
    }

    /**
     * Computes a patch signature from current channel state.
     * Quantizes modTL and carrierTL into bands to avoid excessive unique patches.
     */
    static int signature(int conn, int fb, int modTl, int carTl, int carAr, int carDr) {
        int modBand = modTl < 10 ? 0 : modTl < 30 ? 1 : modTl < 50 ? 2 : 3;
        int carBand = carTl < 15 ? 0 : carTl < 30 ? 1 : carTl < 45 ? 2 : 3;
        return (conn & 0xF) << 24
                | (fb & 0xF) << 20
                | (modBand & 0xF) << 16
                | (carBand & 0xF) << 12
                | (carAr & 0xF) << 8
                | (carDr & 0xF);
    }

    private static int @org.jspecify.annotations.Nullable [] decodeSlot(int addr, int base) {
        int offset = addr - base;
        int group = offset / 8;
        int slot = offset % 8;
        if (slot >= 6) return null;
        int ch = group * 3 + slot % 3;
        if (ch >= 9) return null;
        return new int[]{ch, slot >= 3 ? 1 : 0};
    }

    private static class PortState {
        final int[] fnumLo = new int[9];
        final int[] fnumHi = new int[9];
        final int[] block = new int[9];
        final boolean[] keyState = new boolean[9];
        final int[] connection = new int[9];
        final int[] feedback = new int[9];
        final int[] modulatorTl = new int[9];
        final int[] carrierTl = new int[9];
        final int[] carrierAr = new int[9];
        final int[] carrierDr = new int[9];

        PortState() {
            java.util.Arrays.fill(modulatorTl, 63);
            java.util.Arrays.fill(carrierTl, 63);
        }

        int signature(int ch) {
            return OplPatchCatalog.signature(
                    connection[ch], feedback[ch], modulatorTl[ch], carrierTl[ch],
                    carrierAr[ch], carrierDr[ch]);
        }
    }
}

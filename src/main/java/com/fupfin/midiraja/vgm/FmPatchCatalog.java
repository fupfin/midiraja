/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.vgm;

import static com.fupfin.midiraja.vgm.FmMidiUtil.*;
import static com.fupfin.midiraja.vgm.VgmToMidiConverter.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Catalogs unique FM patches from a VGM file and assigns GM programs based on FM
 * characteristics and the typical note range of each patch.
 *
 * <p>Supports all FM chip families: 2-op (OPL2/OPL3) and 4-op (YM2612, YM2151, OPN).
 * Built during the first pass over VGM events. Each unique patch signature
 * (timbre hint, feedback, quantized modulator TL, carrier TL, carrier AR, carrier DR)
 * is associated with the notes it plays. After scanning, each signature is assigned
 * a GM program based on its percussive/sustained character and median note range.
 *
 * <p>During the second pass (actual MIDI conversion), the converter queries
 * {@link #program(int)} with the current patch signature to get the GM program.
 */
final class FmPatchCatalog {

    /**
     * Represents a single key-on event with pre-extracted patch parameters.
     * Each chip converter computes these from its own register state.
     *
     * @param timbreHint 0=FM-serial (2-op conn=0, or 4-op alg 0-4), 1=AM-parallel (2-op conn=1, or 4-op alg 5-7)
     * @param feedback   feedback level (0-7)
     * @param modTl      modulator total level (2-op: single operator; 4-op: average of modulator operators)
     * @param carTl      carrier total level (2-op: single operator; 4-op: average of carrier operators)
     * @param carAr      carrier attack rate, normalized to 0-15
     * @param carDr      carrier decay rate, normalized to 0-15
     * @param note       MIDI note number (0-127)
     */
    record PatchEvent(int timbreHint, int feedback, int modTl, int carTl, int carAr, int carDr, int note) {}

    // GM instrument palette — timbre × pitch-range × envelope (3 dimensions)
    // Timbre: FM-soft (hint=0,fb<5,modTL≥30), FM-bright (hint=0,fb<5,modTL<30),
    //         FM-harsh (hint=0,fb≥5), AM (hint=1)
    private static final int DRUM_EFFECT = -2; // sentinel: route to ch 9
    private static final int SILENT      = -1; // sentinel: suppress

    // [timbre][range][envelope] — timbre: 0=FM-soft, 1=FM-bright, 2=FM-harsh, 3=AM
    //                             range: 0=bass, 1=mid, 2=high
    //                             envelope: 0=sustained, 1=percussive
    private static final int[][][] GM_TABLE = {
        // FM-soft (hint=0, fb<5, modTL≥30): warm, pure
        {{32, 36}, {5, 12}, {5, 13}},         // Ac.Bass/Slap, EP2/Marimba, EP2/Xylophone
        // FM-bright (hint=0, fb<5, modTL<30): bright, metallic
        {{33, 36}, {4, 11}, {4, 13}},         // Elec.Bass/Slap, EP1/Vibraphone, EP1/Xylophone
        // FM-harsh (hint=0, fb≥5): aggressive, distorted
        {{33, 36}, {7, 7}, {7, 13}},          // Elec.Bass/Slap, Clavinet/Clavinet, Clavinet/Xylophone
        // AM (hint=1): organ-like — use Rock Organ for both sustained and percussive
        // (VGM key-off timing provides short notes; no need to switch instrument)
        {{32, 36}, {18, 18}, {18, 18}},       // Ac.Bass/Slap, Rock Organ/Rock Organ, Rock Organ/Rock Organ
    };

    private static final int BASS_THRESHOLD = 48;  // < C3
    private static final int HIGH_THRESHOLD = 72;  // >= C5

    private final Map<Integer, Integer> signatureToProgram = new HashMap<>();

    /**
     * Builds a catalog from pre-extracted patch events.
     *
     * <p>Each converter pre-filters silent/drum patches and computes the timbre hint,
     * feedback, TL, AR, DR, and note from its own register state. This method only
     * classifies the collected events and assigns GM programs.
     *
     * @param events list of patch events collected during the first pass
     */
    static FmPatchCatalog build(List<PatchEvent> events) {
        var catalog = new FmPatchCatalog();
        Map<Integer, List<Integer>> sigNotes = new HashMap<>();

        for (var e : events) {
            int sig = signature(e.timbreHint(), e.feedback(), e.modTl(), e.carTl(),
                    e.carAr(), e.carDr());
            if (e.note() >= 0) {
                sigNotes.computeIfAbsent(sig, k -> new ArrayList<>()).add(e.note());
            }
        }

        // Assign GM program to each signature
        for (var entry : sigNotes.entrySet()) {
            int sig = entry.getKey();
            var notes = entry.getValue();

            int hint = (sig >> 24) & 0xF;
            int fb = (sig >> 20) & 0xF;
            int modTlBand = (sig >> 16) & 0xF;
            int carTlBand = (sig >> 12) & 0xF;
            int ar = (sig >> 8) & 0xF;
            int dr = sig & 0xF;

            // Silent?
            boolean silent = (hint == 0 && fb == 0) || carTlBand >= 3; // band 3 = TL 45+
            if (silent) {
                catalog.signatureToProgram.put(sig, SILENT);
                continue;
            }

            // Percussive effect?
            if (modTlBand == 0 && !(hint == 0 && fb == 0)) { // modTL 0-9
                catalog.signatureToProgram.put(sig, DRUM_EFFECT);
                continue;
            }

            // Compute median note
            notes.sort(Integer::compareTo);
            int median = notes.get(notes.size() / 2);

            // Timbre classification: 0=FM-soft, 1=FM-bright, 2=FM-harsh, 3=AM
            int timbre;
            if (hint == 1) {
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

    /**
     * Builds a catalog by scanning OPL2/OPL3 events from a VgmParseResult.
     *
     * <p>Parses OPL2-specific registers to extract patch parameters and key-on events,
     * then delegates to {@link #build(List)} for classification.
     *
     * @param parsed the parsed VGM data
     * @param chipIds chip IDs to scan (14=OPL2, 15=OPL3 port0, 16=OPL3 port1)
     * @param effectiveClock the OPL clock used for note calculation
     */
    static FmPatchCatalog buildForOpl(VgmParseResult parsed, int[] chipIds, long effectiveClock) {
        // Simulate register state per port (9 channels each)
        var state = new OplPortState[2]; // 0=port0, 1=port1
        state[0] = new OplPortState();
        state[1] = new OplPortState();

        List<PatchEvent> events = new ArrayList<>();

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
                    int fnum = (ps.fnumHi[ch] << 8) | ps.fnumLo[ch];
                    int note = Ym3812MidiConverter.opl2Note(effectiveClock, fnum, ps.block[ch]);
                    if (note >= 0) {
                        events.add(new PatchEvent(
                                ps.connection[ch], ps.feedback[ch],
                                ps.modulatorTl[ch], ps.carrierTl[ch],
                                ps.carrierAr[ch], ps.carrierDr[ch], note));
                    }
                }
                ps.keyState[ch] = keyOn;
            } else if (reg >= 0xC0 && reg <= 0xC8) {
                int ch = reg - 0xC0;
                ps.feedback[ch] = (val >> 1) & 0x07;
                ps.connection[ch] = val & 0x01;
            }
        }

        return build(events);
    }

    /**
     * Builds a catalog by scanning 4-op FM chip events (YM2612, YM2151, OPN family).
     *
     * <p>Tracks algorithm, feedback, TL per operator, AR/DR, and computes the timbre hint
     * from the algorithm number (alg 0-4 → FM-serial, alg 5-7 → AM-parallel).
     *
     * @param parsed the parsed VGM data
     * @param chipIds chip IDs to scan (1,2=YM2612, 5=YM2151, 6-10=OPN family)
     * @param clocks per-chip clocks indexed by chip ID (only entries matching chipIds are used)
     * @param dividers per-chip FM frequency dividers indexed by chip ID
     */
    static FmPatchCatalog buildFor4Op(VgmParseResult parsed, int[] chipIds, long[] clocks,
                                       int[] dividers) {
        // YM2612/OPN: 6 channels, YM2151: 8 channels. Use 8 for all.
        var opnState = new Opn4OpState[11]; // indexed by chip ID (max 10)
        for (int id : chipIds) {
            if (id < opnState.length) {
                opnState[id] = new Opn4OpState(id == CHIP_YM2151 ? 8 : 6);
            }
        }

        List<PatchEvent> events = new ArrayList<>();

        for (var event : parsed.events()) {
            int chip = event.chip();
            Opn4OpState st = (chip < opnState.length) ? opnState[chip] : null;
            if (st == null) continue;

            int addr = event.rawData()[0] & 0xFF;
            int data = event.rawData()[1] & 0xFF;
            long clock = (chip < clocks.length) ? clocks[chip] : 0;
            int divider = (chip < dividers.length) ? dividers[chip] : 144;

            if (chip == CHIP_YM2151) {
                scan4OpOpm(st, addr, data, events);
            } else {
                // YM2612 port0 (chip 1), OPN family port0 (chips 6,7,9)
                int portOffset = isPort1(chip) ? 3 : 0;
                scan4OpOpn(st, addr, data, portOffset, clock, divider, chip, events);
            }
        }

        return build(events);
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
     *
     * @param timbreHint 0=FM-serial, 1=AM-parallel (from connection or algorithm mapping)
     * @param fb         feedback level (0-7)
     * @param modTl      modulator total level
     * @param carTl      carrier total level
     * @param carAr      carrier attack rate (0-15)
     * @param carDr      carrier decay rate (0-15)
     */
    static int signature(int timbreHint, int fb, int modTl, int carTl, int carAr, int carDr) {
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
    static int algorithmToTimbreHint(int alg) {
        return alg >= 5 ? 1 : 0;
    }

    // ---- OPL2-specific register parsing ----

    private static int @org.jspecify.annotations.Nullable [] decodeSlot(int addr, int base) {
        int offset = addr - base;
        int group = offset / 8;
        int slot = offset % 8;
        if (slot >= 6) return null;
        int ch = group * 3 + slot % 3;
        if (ch >= 9) return null;
        return new int[]{ch, slot >= 3 ? 1 : 0};
    }

    // ---- 4-op (OPN/OPM) register scanning ----

    private static boolean isPort1(int chipId) {
        return chipId == CHIP_YM2612_PORT1
                || chipId == CHIP_YM2608_PORT1
                || chipId == CHIP_YM2610_PORT1;
    }

    /**
     * Scans an OPN-family register write (YM2612, YM2203, YM2608, YM2610).
     * Collects PatchEvent on key-on.
     */
    private static void scan4OpOpn(Opn4OpState st, int addr, int data, int portOffset,
                                    long clock, int divider, int chip,
                                    List<PatchEvent> events) {
        if (addr >= 0x40 && addr <= 0x4F) {
            int op = (addr - 0x40) >> 2;
            int ch = (addr & 0x03) + portOffset;
            if (ch < st.channels) st.tl[ch][op] = data & 0x7F;
        } else if (addr >= 0x50 && addr <= 0x5F) {
            int op = (addr - 0x50) >> 2;
            int ch = (addr & 0x03) + portOffset;
            if (ch < st.channels) st.ar[ch][op] = data & 0x1F;
        } else if (addr >= 0x60 && addr <= 0x6F) {
            int op = (addr - 0x60) >> 2;
            int ch = (addr & 0x03) + portOffset;
            if (ch < st.channels) st.d1r[ch][op] = data & 0x1F;
        } else if (addr >= 0xB0 && addr <= 0xB2) {
            int ch = (addr - 0xB0) + portOffset;
            if (ch < st.channels) {
                st.algorithm[ch] = data & 0x07;
                st.feedback[ch] = (data >> 3) & 0x07;
            }
        } else if (addr >= 0xA4 && addr <= 0xA6) {
            int ch = (addr - 0xA4) + portOffset;
            if (ch < st.channels) st.fnumHigh[ch] = data;
        } else if (addr >= 0xA0 && addr <= 0xA2) {
            int ch = (addr - 0xA0) + portOffset;
            if (ch < st.channels) st.fnumLow[ch] = data;
        } else if (addr == 0x28) {
            int chSelect = data & 0x07;
            int ch = switch (chSelect) {
                case 0, 1, 2 -> chSelect;
                case 4, 5, 6 -> chSelect - 1;
                default -> -1;
            };
            if (ch < 0 || ch >= st.channels) return;
            boolean keyOn = (data & 0xF0) != 0;
            if (keyOn && !st.keyState[ch]) {
                if (!isSilentCarrier(st.tl, st.algorithm, ch)) {
                    int fnum = (st.fnumHigh[ch] & 0x07) << 8 | st.fnumLow[ch];
                    int block = (st.fnumHigh[ch] >> 3) & 0x07;
                    int note = Ym2612MidiConverter.opnNote(clock, fnum, block, divider);
                    if (note >= 0) {
                        collectOpnEvent(st, ch, note, events);
                    }
                }
            }
            st.keyState[ch] = keyOn;
        }
    }

    /**
     * Scans a YM2151 (OPM) register write. Collects PatchEvent on key-on.
     */
    private static void scan4OpOpm(Opn4OpState st, int addr, int data,
                                    List<PatchEvent> events) {
        if (addr >= 0x60 && addr <= 0x7F) {
            int op = (addr - 0x60) >> 3;
            int ch = addr & 0x07;
            if (ch < st.channels) st.tl[ch][op] = data & 0x7F;
        } else if (addr >= 0x80 && addr <= 0x9F) {
            int op = (addr - 0x80) >> 3;
            int ch = addr & 0x07;
            if (ch < st.channels) st.ar[ch][op] = data & 0x1F;
        } else if (addr >= 0xA0 && addr <= 0xBF) {
            int op = (addr - 0xA0) >> 3;
            int ch = addr & 0x07;
            if (ch < st.channels) st.d1r[ch][op] = data & 0x1F;
        } else if (addr >= 0x20 && addr <= 0x27) {
            int ch = addr & 0x07;
            st.algorithm[ch] = data & 0x07;
            st.feedback[ch] = (data >> 3) & 0x07;
        } else if (addr >= 0x28 && addr <= 0x2F) {
            int ch = addr & 0x07;
            if (ch < st.channels) st.kc[ch] = data;
        } else if (addr == 0x08) {
            int ch = data & 0x07;
            if (ch >= st.channels) return;
            boolean keyOn = (data & 0x78) != 0;
            if (keyOn && !st.keyState[ch]) {
                if (!isSilentCarrier(st.tl, st.algorithm, ch)) {
                    int note = opmNote(st.kc[ch]);
                    if (note >= 0) {
                        collectOpnEvent(st, ch, note, events);
                    }
                }
            }
            st.keyState[ch] = keyOn;
        }
    }

    /** Converts OPM KC (Key Code) to a MIDI note number. */
    private static int opmNote(int kcVal) {
        int octave = (kcVal >> 4) & 0x07;
        int noteCode = kcVal & 0x0F;
        // KC note code → semitone: values 3, 7, 11, 15 are invalid
        int semitone = switch (noteCode) {
            case 0 -> 0; case 1 -> 1; case 2 -> 2;
            case 4 -> 3; case 5 -> 4; case 6 -> 5;
            case 8 -> 6; case 9 -> 7; case 10 -> 8;
            case 12 -> 9; case 13 -> 10; case 14 -> 11;
            default -> -1;
        };
        if (semitone < 0) return -1;
        // OPM octave 0, note C# (KC=0x00) = MIDI note 13
        int midiNote = octave * 12 + semitone + 13;
        return Math.clamp(midiNote, 0, 127);
    }

    /** Collects a PatchEvent from 4-op channel state (shared by OPN and OPM scanners). */
    private static void collectOpnEvent(Opn4OpState st, int ch, int note,
                                         List<PatchEvent> events) {
        int alg = st.algorithm[ch];
        int hint = algorithmToTimbreHint(alg);
        int avgModTl = avgModulatorTl(st.tl, st.algorithm, ch);
        int[] cops = carrierOps(alg);
        int totalCarTl = 0, totalAr = 0, totalDr = 0;
        for (int op : cops) {
            totalCarTl += st.tl[ch][op];
            totalAr += st.ar[ch][op];
            totalDr += st.d1r[ch][op];
        }
        int avgCarTl = totalCarTl / cops.length;
        // Normalize AR/DR from 5-bit (0-31) to 4-bit (0-15)
        int ar15 = totalAr / cops.length / 2;
        int dr15 = totalDr / cops.length / 2;
        events.add(new PatchEvent(hint, st.feedback[ch], avgModTl, avgCarTl, ar15, dr15, note));
    }

    // ---- Inner state classes ----

    private static class OplPortState {
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

        OplPortState() {
            Arrays.fill(modulatorTl, 63);
            Arrays.fill(carrierTl, 63);
        }
    }

    private static class Opn4OpState {
        final int channels;
        final int[] fnumHigh;
        final int[] fnumLow;
        final int[] kc; // YM2151 only
        final boolean[] keyState;
        final int[] algorithm;
        final int[] feedback;
        final int[][] tl;
        final int[][] ar;
        final int[][] d1r;

        Opn4OpState(int channels) {
            this.channels = channels;
            fnumHigh = new int[channels];
            fnumLow = new int[channels];
            kc = new int[channels];
            keyState = new boolean[channels];
            algorithm = new int[channels];
            feedback = new int[channels];
            tl = new int[channels][4];
            ar = new int[channels][4];
            d1r = new int[channels][4];
            for (int[] row : tl) Arrays.fill(row, 127);
        }
    }
}

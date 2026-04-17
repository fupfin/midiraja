/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.export.vgm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Parses WOPN2 OPN2/YM2612 bank files and provides GM patch data.
 *
 * <p>
 * WOPN (Wohlstand's OPN2 Bank File) is a binary format for storing YM2612/OPN2 FM synthesizer
 * patch data. This reader extracts melodic and percussion instrument patches from bank 0.
 * Each patch contains four operator FM parameters sufficient for 4-operator synthesis.
 *
 * <p>
 * File format support: WOPN2-B2NK versions 1 and 2.
 */
public final class WopnBankReader
{
    private static final String MAGIC_V2 = "WOPN2-B2NK";
    private static final String MAGIC_V1 = "WOPN2-BANK";
    private static final int HEADER_SIZE = 18;
    private static final int INST_SIZE_V2 = 69;
    private static final int BANK_META_SIZE = 34;
    private static final int LFO_FREQ_OFFSET = 17;

    /**
     * YM2612 operator register values for one operator.
     *
     * @param dtfm register 0x30: DT/MULT (detuning and frequency multiplier)
     * @param level register 0x40: TL (total level; 0 = max output, 127 = silent)
     * @param rsatk register 0x50: RS/AR (rate scaling, attack rate)
     * @param amdecay1 register 0x60: AM/D1R (AM enable, 1st decay rate)
     * @param decay2 register 0x70: D2R (2nd decay rate)
     * @param susrel register 0x80: SL/RR (sustain level, release rate)
     * @param ssgeg register 0x90: SSG-EG (sound software generator envelope)
     */
    public record Operator(int dtfm, int level, int rsatk, int amdecay1, int decay2, int susrel, int ssgeg)
    {
    }

    /**
     * FM patch data for one YM2612 instrument (4-operator voice).
     *
     * @param name Instrument name (e.g., "Acoustic Grand Piano")
     * @param percussionKeyNumber MIDI note to use for percussion tuning (0 for melodic patches)
     * @param noteOffset Semitone transposition to apply on playback (positive = up, negative = down)
     * @param fbalg Feedback and algorithm byte: bits 5-3 = feedback (FB), bits 2-0 = algorithm
     * @param lfosens LFO sensitivity byte
     * @param operators Four operators in WOPN slot order: [S1, S3, S2, S4]
     */
    public record Patch(String name, int percussionKeyNumber, int noteOffset, int fbalg, int lfosens,
            Operator[] operators)
    {
    }

    private final Patch[] melodicPatches;
    private final Patch[] percussionPatches;
    private int lfoFreq;

    private WopnBankReader(byte[] data) throws IOException
    {
        this.melodicPatches = new Patch[128];
        this.percussionPatches = new Patch[128];
        parse(data);
    }

    /**
     * Load WOPN2 bank file from path.
     *
     * @param path Path to the WOPN file
     * @return WopnBankReader instance ready to query patches
     * @throws IOException if the file cannot be read or is invalid
     */
    public static WopnBankReader load(Path path) throws IOException
    {
        return new WopnBankReader(Files.readAllBytes(path));
    }

    /**
     * Returns the global LFO frequency byte for this bank (raw value for YM2612 register 0x22).
     * Bit 3 = LFO enable, bits 2-0 = LFO frequency (0-7).
     */
    public int lfoFreq()
    {
        return lfoFreq;
    }

    /**
     * Get melodic patch for GM program number.
     *
     * @param program Program number (0-127)
     * @return The patch data
     * @throws IllegalArgumentException if program is out of range or not found
     */
    public Patch melodicPatch(int program)
    {
        if (program < 0 || program >= 128)
            throw new IllegalArgumentException("Program must be 0-127, got " + program);
        Patch patch = melodicPatches[program];
        if (patch == null)
            throw new IllegalArgumentException("No patch found for program " + program);
        return patch;
    }

    /**
     * Get percussion patch for GM note number.
     *
     * @param note MIDI note number (0-127)
     * @return The patch data
     * @throws IllegalArgumentException if note is out of range or not found
     */
    public Patch percussionPatch(int note)
    {
        if (note < 0 || note >= 128)
            throw new IllegalArgumentException("Note must be 0-127, got " + note);
        Patch patch = percussionPatches[note];
        if (patch == null)
            throw new IllegalArgumentException("No patch found for note " + note);
        return patch;
    }

    private void parse(byte[] data) throws IOException
    {
        if (data.length < HEADER_SIZE)
            throw new IOException("WOPN file too small: " + data.length + " bytes");

        String magic = new String(data, 0, 11);
        if (!magic.equals(MAGIC_V2 + "\0") && !magic.equals(MAGIC_V1 + "\0"))
            throw new IOException("Invalid WOPN magic: " + magic.trim());

        int version = toUint16LE(data, 11);
        if (version < 1 || version > 2)
            throw new IOException("Unsupported WOPN version: " + version);

        int countMelodic = toUint16BE(data, 13);
        int countPercussive = toUint16BE(data, 15);
        lfoFreq = data[LFO_FREQ_OFFSET] & 0xFF;

        // Version 2 includes 34-byte bank metadata before instrument data
        int bankMetaBytes = version >= 2 ? BANK_META_SIZE * (countMelodic + countPercussive) : 0;
        int melodicDataOffset = HEADER_SIZE + bankMetaBytes;
        int percussionDataOffset = melodicDataOffset + INST_SIZE_V2 * 128 * countMelodic;

        parseBank(data, melodicDataOffset, melodicPatches);
        if (countPercussive > 0)
            parseBank(data, percussionDataOffset, percussionPatches);
    }

    private static void parseBank(byte[] data, int bankOffset, Patch[] patches) throws IOException
    {
        for (int i = 0; i < 128; i++)
        {
            int off = bankOffset + i * INST_SIZE_V2;
            if (off + INST_SIZE_V2 > data.length)
                throw new IOException("Truncated WOPN file at instrument " + i);

            String name = parseName(data, off);
            int noteOffset = toSint16BE(data, off + 32);
            int percKeyNum = data[off + 34] & 0xFF;
            int fbalg = data[off + 35] & 0xFF;
            int lfosens = data[off + 36] & 0xFF;
            Operator[] ops = new Operator[4];
            for (int l = 0; l < 4; l++)
            {
                int opOff = off + 37 + l * 7;
                ops[l] = new Operator(
                    data[opOff] & 0xFF,
                    data[opOff + 1] & 0xFF,
                    data[opOff + 2] & 0xFF,
                    data[opOff + 3] & 0xFF,
                    data[opOff + 4] & 0xFF,
                    data[opOff + 5] & 0xFF,
                    data[opOff + 6] & 0xFF);
            }
            patches[i] = new Patch(name, percKeyNum, noteOffset, fbalg, lfosens, ops);
        }
    }

    private static String parseName(byte[] data, int offset)
    {
        int end = offset;
        while (end < offset + 32 && data[end] != 0)
            end++;
        return new String(data, offset, end - offset);
    }

    private static int toUint16LE(byte[] data, int offset)
    {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private static int toUint16BE(byte[] data, int offset)
    {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private static int toSint16BE(byte[] data, int offset)
    {
        return (short) toUint16BE(data, offset);
    }
}

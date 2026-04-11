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
 * Parses WOPL OPL3 bank files and provides GM patch data.
 *
 * <p>
 * WOPL (Wohlstand's OPL3 Bank File) is a binary format for storing OPL3 FM synthesizer
 * patch data. This reader extracts melodic and percussion instrument patches from bank 0.
 * Each patch contains two operator FM parameters (modulator and carrier) sufficient for
 * 2-operator synthesis mode.
 *
 * <p>
 * File format support: WOPL versions 1, 2, and 3.
 * - Version 1: Header (19 bytes) + melodic instruments + percussion instruments
 * - Version 2+: Header (19 bytes) + bank metadata (34 bytes per bank) + instruments
 * - Version 3: Same as V2, with delay_on_ms and delay_off_ms per instrument
 */
public final class WoplBankReader
{
    private static final String MAGIC = "WOPL3-BANK";
    private static final int HEADER_SIZE = 19;

    /**
     * OPL3 operator register values for one operator (modulator or carrier).
     *
     * @param avekf register 0x20+: AM, Vibrato, EG type, KSR, Multiplier
     * @param ksltl register 0x40+: Key Scale Level, Total Level
     * @param atdec register 0x60+: Attack rate, Decay rate
     * @param susrel register 0x80+: Sustain level, Release rate
     * @param wave register 0xE0+: Waveform select
     */
    public record Operator(int avekf, int ksltl, int atdec, int susrel, int wave)
    {
    }

    /**
     * FM patch data for one instrument (2-operator voice).
     *
     * @param name Instrument name (e.g., "Acoustic Grand Piano")
     * @param fbConn Feedback and connection byte (register 0xC0)
     * @param percussionKeyNumber MIDI note number to use for frequency when playing a percussion
     *            patch (0 for melodic patches). libADLMIDI stores the tuned note here rather than
     *            using the incoming MIDI note directly.
     * @param modulator Modulator operator parameters
     * @param carrier Carrier operator parameters
     */
    public record Patch(String name, int fbConn, int percussionKeyNumber,
            Operator modulator, Operator carrier)
    {
    }

    private final byte[] data;
    private final int version;
    private final Patch[] melodicPatches;
    private final Patch[] percussionPatches;

    private WoplBankReader(byte[] data) throws IOException
    {
        this.data = data;
        this.version = parseHeader();
        this.melodicPatches = new Patch[128];
        this.percussionPatches = new Patch[128];
        parseBanks();
    }

    /**
     * Load WOPL bank file from path.
     *
     * @param path Path to the WOPL file
     * @return WoplBankReader instance ready to query patches
     * @throws IOException if file cannot be read
     * @throws IllegalArgumentException if file is invalid or unsupported
     */
    public static WoplBankReader load(Path path) throws IOException
    {
        byte[] fileData = Files.readAllBytes(path);
        return new WoplBankReader(fileData);
    }

    /**
     * Get melodic patch for GM program number.
     *
     * @param program Program number (0-127)
     * @return The patch data
     * @throws IllegalArgumentException if program is out of range
     */
    public Patch melodicPatch(int program)
    {
        if (program < 0 || program >= 128)
        {
            throw new IllegalArgumentException("Program must be 0-127, got " + program);
        }
        Patch patch = melodicPatches[program];
        if (patch == null)
        {
            throw new IllegalArgumentException("No patch found for program " + program);
        }
        return patch;
    }

    /**
     * Get percussion patch for GM note number.
     *
     * @param note MIDI note number (0-127)
     * @return The patch data
     * @throws IllegalArgumentException if note is out of range
     */
    public Patch percussionPatch(int note)
    {
        if (note < 0 || note >= 128)
        {
            throw new IllegalArgumentException("Note must be 0-127, got " + note);
        }
        Patch patch = percussionPatches[note];
        if (patch == null)
        {
            throw new IllegalArgumentException("No patch found for note " + note);
        }
        return patch;
    }

    private int parseHeader() throws IOException
    {
        if (data.length < HEADER_SIZE)
        {
            throw new IOException("WOPL file too small: " + data.length + " bytes");
        }

        // Check magic "WOPL3-BANK\0"
        String magic = new String(data, 0, 11);
        if (!magic.equals(MAGIC + "\0"))
        {
            throw new IOException("Invalid WOPL magic: " + magic);
        }

        int ver = toUint16LE(11);
        if (ver > 3 || ver < 1)
        {
            throw new IOException("Unsupported WOPL version: " + ver);
        }

        return ver;
    }

    private void parseBanks() throws IOException
    {
        int melodicBankCount = toUint16BE(13);
        int percussionBankCount = toUint16BE(15);

        if (melodicBankCount == 0 && percussionBankCount == 0)
        {
            throw new IOException("No banks found in WOPL file");
        }

        int insSize = version >= 3 ? 66 : 62;

        // Calculate offsets
        int melodicMetaOffset;
        int melodicDataOffset;
        int percussionDataOffset;

        if (version == 1)
        {
            melodicMetaOffset = 0;
            melodicDataOffset = HEADER_SIZE;
            percussionDataOffset = melodicDataOffset + (insSize * 128 * melodicBankCount);
        }
        else
        {
            melodicMetaOffset = HEADER_SIZE;
            int bankMetaSize = 34;
            melodicDataOffset = HEADER_SIZE + (bankMetaSize * melodicBankCount) + (bankMetaSize * percussionBankCount);
            percussionDataOffset = melodicDataOffset + (insSize * 128 * melodicBankCount);
        }

        // Parse melodic bank 0 (GM bank)
        parseBank(melodicDataOffset, melodicPatches, insSize);

        // Parse percussion bank 0
        if (percussionBankCount > 0)
        {
            parseBank(percussionDataOffset, percussionPatches, insSize);
        }
    }

    private void parseBank(int offset, Patch[] patches, int insSize) throws IOException
    {
        for (int i = 0; i < 128; i++)
        {
            int insOffset = offset + (i * insSize);
            if (insOffset + insSize > data.length)
            {
                throw new IOException("Truncated WOPL file at instrument " + i);
            }

            // Parse instrument name (32 bytes, null-terminated)
            String name = parseName(insOffset);

            // Parse note offsets (bytes 32-35)
            int noteOffset1 = toSint16BE(insOffset + 32);
            int noteOffset2 = toSint16BE(insOffset + 34);

            // Parse operator registers
            // WOPL_OP_CARRIER1=0 → offset 42, WOPL_OP_MODULATOR1=1 → offset 47
            Operator car1 = parseOperator(insOffset + 42);
            Operator mod1 = parseOperator(insOffset + 47);

            // WOPL_OP_CARRIER2=2 → offset 52, WOPL_OP_MODULATOR2=3 → offset 57 (4OP)
            Operator car2 = parseOperator(insOffset + 52);
            Operator mod2 = parseOperator(insOffset + 57);

            // For 2-OP mode, use voice 1 operators
            int percussionKeyNumber = data[insOffset + 38] & 0xFF;
            int fbConn = data[insOffset + 40] & 0xFF;

            patches[i] = new Patch(name, fbConn, percussionKeyNumber, mod1, car1);
        }
    }

    private String parseName(int offset)
    {
        int endIdx = offset;
        while (endIdx < offset + 32 && data[endIdx] != 0)
        {
            endIdx++;
        }
        return new String(data, offset, endIdx - offset);
    }

    private Operator parseOperator(int offset)
    {
        int avekf = data[offset] & 0xFF;
        int ksltl = data[offset + 1] & 0xFF;
        int atdec = data[offset + 2] & 0xFF;
        int susrel = data[offset + 3] & 0xFF;
        int wave = data[offset + 4] & 0xFF;
        return new Operator(avekf, ksltl, atdec, susrel, wave);
    }

    private int toUint16LE(int offset)
    {
        return ((data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8));
    }

    private int toUint16BE(int offset)
    {
        return (((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF));
    }

    private int toSint16BE(int offset)
    {
        return (short) toUint16BE(offset);
    }
}

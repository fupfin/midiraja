/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.export.vgm;

import static org.junit.jupiter.api.Assertions.*;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OpmBankReaderTest
{
    private static final int PATCH_BYTES = 52;
    private static final int N_PROGRAMS  = 128;
    private static final int N_PERC      = 128;

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Build a raw 52-byte patch block. */
    private static byte[] encodePatch(String name, int noteOffset, int percKey,
            int fbalg, int lfosens, int[][] ops)
    {
        byte[] buf = new byte[PATCH_BYTES];
        byte[] nameBytes = name.getBytes(UTF_8);
        System.arraycopy(nameBytes, 0, buf, 0, Math.min(nameBytes.length, 16));
        buf[16] = (byte) noteOffset;
        buf[17] = (byte) percKey;
        buf[18] = (byte) fbalg;
        buf[19] = (byte) lfosens;
        for (int l = 0; l < 4; l++)
        {
            int base = 20 + l * 8;
            buf[base]     = (byte) ops[l][0]; // dt1mul
            buf[base + 1] = (byte) ops[l][1]; // tl
            buf[base + 2] = (byte) ops[l][2]; // ksatk
            buf[base + 3] = (byte) ops[l][3]; // amd1r
            buf[base + 4] = (byte) ops[l][4]; // dt2d2r
            buf[base + 5] = (byte) ops[l][5]; // d1lrr
            buf[base + 6] = 0;                // ssgeg (reserved)
            buf[base + 7] = 0;                // pad
        }
        return buf;
    }

    /**
     * Write a minimal but complete opm_gm.bin with 128 melodic and 128 percussion patches.
     * Program {@code p} gets a distinct fbalg, lfosens and operator values so we can verify
     * round-trip fidelity. All other patches are silent defaults.
     */
    private static Path writeBankFile(Path dir, int testProgram, byte[] testPatch,
            int testPercNote, byte[] testPercPatch) throws IOException
    {
        Path file = dir.resolve("opm_gm.bin");
        try (OutputStream out = Files.newOutputStream(file))
        {
            // Header: magic(10) + version(u8=1) + nMelodic(u16le=128) + nPercussion(u16le=128)
            out.write("OPMGM-BNK\0".getBytes(UTF_8));
            out.write(new byte[] { 0x01 }); // version
            ByteBuffer hdr = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
            hdr.putShort((short) N_PROGRAMS);
            hdr.putShort((short) N_PERC);
            out.write(hdr.array());

            // Silent default melodic patch
            int[][] silentOps = {
                { 0x01, 127, 0x1F, 0x00, 0x00, 0xFF, },
                { 0x01, 127, 0x1F, 0x00, 0x00, 0xFF, },
                { 0x01, 127, 0x1F, 0x00, 0x00, 0xFF, },
                { 0x01, 127, 0x1F, 0x00, 0x00, 0xFF, }
            };
            byte[] silent = encodePatch("", 0, 0, 0x07, 0, silentOps);

            for (int i = 0; i < N_PROGRAMS; i++)
                out.write(i == testProgram ? testPatch : silent);
            for (int i = 0; i < N_PERC; i++)
                out.write(i == testPercNote ? testPercPatch : silent);
        }
        return file;
    }

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    void roundTrip_melodicPatch(@TempDir Path tmp) throws IOException
    {
        int[][] ops = {
            { 0x23, 40,  0x41, 0x80, 0xC5, 0x37 },
            { 0x01, 75,  0x1F, 0x00, 0x00, 0xF7 },
            { 0x03, 60,  0x31, 0x40, 0x82, 0x57 },
            { 0x01, 127, 0x1F, 0x00, 0x00, 0xFF }
        };
        byte[] patch = encodePatch("Grand Piano", -2, 0, 0x15, 0x34, ops);

        Path file = writeBankFile(tmp, 0, patch, 0, encodePatch("", 0, 0, 0x07, 0,
            new int[][] { {0,127,0,0,0,0xFF}, {0,127,0,0,0,0xFF}, {0,127,0,0,0,0xFF}, {0,127,0,0,0,0xFF} }));

        OpmBankReader reader = OpmBankReader.load(file);
        OpmBankReader.Patch p = reader.melodicPatch(0);

        assertEquals("Grand Piano", p.name());
        assertEquals(-2, p.noteOffset());
        assertEquals(0, p.percKey());
        assertEquals(0x15, p.fbalg());
        assertEquals(0x34, p.lfosens());

        OpmBankReader.Operator[] op = p.operators();
        assertEquals(4, op.length);
        assertEquals(0x23, op[0].dt1mul());
        assertEquals(40,   op[0].tl());
        assertEquals(0x41, op[0].ksatk());
        assertEquals(0x80, op[0].amd1r());
        assertEquals(0xC5, op[0].dt2d2r()); // DT2=3 (bits 7-6), D2R=5 (bits 4-0)
        assertEquals(0x37, op[0].d1lrr());
    }

    @Test
    void roundTrip_percussionPatch(@TempDir Path tmp) throws IOException
    {
        int[][] ops = {
            { 0x11, 20, 0x5F, 0x80, 0x40, 0x7F },
            { 0x11, 20, 0x5F, 0x80, 0x40, 0x7F },
            { 0x11, 20, 0x5F, 0x80, 0x40, 0x7F },
            { 0x11, 20, 0x5F, 0x80, 0x40, 0x7F }
        };
        byte[] perc = encodePatch("Kick", 3, 36, 0x02, 0x00, ops);
        byte[] silent = encodePatch("", 0, 0, 0x07, 0,
            new int[][] { {0,127,0,0,0,0xFF}, {0,127,0,0,0,0xFF}, {0,127,0,0,0,0xFF}, {0,127,0,0,0,0xFF} });

        Path file = writeBankFile(tmp, 0, silent, 36, perc);

        OpmBankReader reader = OpmBankReader.load(file);
        OpmBankReader.Patch p = reader.percussionPatch(36);

        assertEquals("Kick", p.name());
        assertEquals(3, p.noteOffset());
        assertEquals(36, p.percKey());
        assertEquals(0x02, p.fbalg());
        assertEquals(0x40, p.operators()[0].dt2d2r()); // DT2=1, D2R=0
    }

    @Test
    void noteOffset_signedByte(@TempDir Path tmp) throws IOException
    {
        int[][] ops = new int[][] { {0,127,0,0,0,0xFF}, {0,127,0,0,0,0xFF}, {0,127,0,0,0,0xFF}, {0,127,0,0,0,0xFF} };
        // Store negative note offset: -12 = 0xF4 as unsigned byte
        byte[] patch = encodePatch("test", -12, 0, 0x07, 0, ops);
        byte[] silent = encodePatch("", 0, 0, 0x07, 0, ops);

        Path file = writeBankFile(tmp, 5, patch, 0, silent);

        OpmBankReader reader = OpmBankReader.load(file);
        assertEquals(-12, reader.melodicPatch(5).noteOffset());
    }

    @Test
    void invalidMagic_throwsIOException(@TempDir Path tmp) throws IOException
    {
        byte[] bad = new byte[15 + 256 * PATCH_BYTES];
        System.arraycopy("BADMAGIC\0\0".getBytes(UTF_8), 0, bad, 0, 10);
        Path file = tmp.resolve("bad.bin");
        Files.write(file, bad);
        assertThrows(IOException.class, () -> OpmBankReader.load(file));
    }

    @Test
    void outOfRangeProgramThrows(@TempDir Path tmp) throws IOException
    {
        int[][] ops = new int[][] { {0,127,0,0,0,0xFF}, {0,127,0,0,0,0xFF}, {0,127,0,0,0,0xFF}, {0,127,0,0,0,0xFF} };
        byte[] silent = encodePatch("", 0, 0, 0x07, 0, ops);
        Path file = writeBankFile(tmp, 0, silent, 0, silent);

        OpmBankReader reader = OpmBankReader.load(file);
        assertThrows(IllegalArgumentException.class, () -> reader.melodicPatch(-1));
        assertThrows(IllegalArgumentException.class, () -> reader.melodicPatch(128));
    }
}

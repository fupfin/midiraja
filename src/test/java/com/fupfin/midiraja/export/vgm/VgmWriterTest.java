/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.export.vgm;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.util.List;

import org.junit.jupiter.api.Test;

class VgmWriterTest
{
    private static int readInt32Le(byte[] data, int offset)
    {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    private static byte[] write(ChipType... types)
    {
        var out = new ByteArrayOutputStream();
        try (var w = new VgmWriter(out, List.of(types)))
        {
            // intentionally empty
        }
        return out.toByteArray();
    }

    // ── Header ────────────────────────────────────────────────────────────────

    @Test
    void header_hasVgmMagic()
    {
        byte[] data = write(ChipType.AY8910, ChipType.AY8910);
        assertEquals('V', data[0]);
        assertEquals('g', data[1]);
        assertEquals('m', data[2]);
        assertEquals(' ', data[3]);
    }

    @Test
    void header_eofOffset_isCorrect()
    {
        byte[] data = write(ChipType.AY8910, ChipType.AY8910);
        int eofOffset = readInt32Le(data, 0x04);
        assertEquals(data.length - 4, eofOffset);
    }

    @Test
    void header_version_ay8910_is161()
    {
        byte[] data = write(ChipType.AY8910, ChipType.AY8910);
        assertEquals(0x161, readInt32Le(data, 0x08));
    }

    @Test
    void header_version_ym2413_is150()
    {
        byte[] data = write(ChipType.YM2413);
        assertEquals(0x150, readInt32Le(data, 0x08));
    }

    @Test
    void header_version_sn76489_is150()
    {
        byte[] data = write(ChipType.SN76489);
        assertEquals(0x150, readInt32Le(data, 0x08));
    }

    @Test
    void header_version_msx_is161()
    {
        byte[] data = write(ChipType.YM2413, ChipType.AY8910);
        assertEquals(0x161, readInt32Le(data, 0x08));
    }

    @Test
    void header_version_opl3_is161()
    {
        byte[] data = write(ChipType.OPL3);
        assertEquals(0x161, readInt32Le(data, 0x08));
    }

    @Test
    void header_ay8910Clock_at0x74()
    {
        byte[] data = write(ChipType.AY8910, ChipType.AY8910);
        assertEquals(VgmWriter.AY8910_CLOCK, readInt32Le(data, 0x74));
    }

    @Test
    void header_ay8910DualChip_at0x78_hasBit31Set()
    {
        byte[] data = write(ChipType.AY8910, ChipType.AY8910);
        int clock2 = readInt32Le(data, 0x78);
        assertTrue((clock2 & 0x80000000) != 0, "Second AY8910 clock must have bit 31 set");
    }

    @Test
    void header_msx_ay8910Clock_at0x74_noDualChipFlag()
    {
        byte[] data = write(ChipType.YM2413, ChipType.AY8910);
        assertEquals(VgmWriter.AY8910_CLOCK, readInt32Le(data, 0x74));
        assertEquals(0, readInt32Le(data, 0x78), "MSX has only one AY chip");
    }

    @Test
    void header_ym2413Clock_at0x10()
    {
        byte[] data = write(ChipType.YM2413);
        assertEquals(VgmWriter.YM2413_CLOCK, readInt32Le(data, 0x10));
    }

    @Test
    void header_sn76489Clock_at0x0C()
    {
        byte[] data = write(ChipType.SN76489);
        assertEquals(VgmWriter.SN76489_CLOCK, readInt32Le(data, 0x0C));
    }

    @Test
    void header_opl3Clock_at0x5C()
    {
        byte[] data = write(ChipType.OPL3);
        assertEquals(VgmWriter.YMF262_CLOCK, readInt32Le(data, 0x5C));
    }

    @Test
    void header_sccClock_at0x9C()
    {
        byte[] data = write(ChipType.SCC, ChipType.AY8910);
        assertEquals(VgmWriter.K051649_CLOCK, readInt32Le(data, 0x9C));
    }

    @Test
    void header_vgmDataOffset_at0x34()
    {
        // For AY8910 mode, header is 0x80, so data offset relative to 0x34 = 0x80 - 0x34 = 0x4C
        byte[] data = write(ChipType.AY8910, ChipType.AY8910);
        assertEquals(0x4C, readInt32Le(data, 0x34));
    }

    @Test
    void header_totalSamples_zero_whenNoWait()
    {
        byte[] data = write(ChipType.AY8910, ChipType.AY8910);
        assertEquals(0, readInt32Le(data, 0x18));
    }

    @Test
    void header_totalSamples_sumsAllWaits()
    {
        var out = new ByteArrayOutputStream();
        try (var w = new VgmWriter(out, List.of(ChipType.AY8910, ChipType.AY8910)))
        {
            w.waitSamples(1000);
            w.waitSamples(500);
        }
        byte[] data = out.toByteArray();
        assertEquals(1500, readInt32Le(data, 0x18));
    }

    // ── End marker ────────────────────────────────────────────────────────────

    @Test
    void close_writesEndOfDataMarker()
    {
        byte[] data = write(ChipType.AY8910, ChipType.AY8910);
        assertEquals(0x66, data[data.length - 1] & 0xFF);
    }

    // ── Wait command ─────────────────────────────────────────────────────────

    @Test
    void waitSamples_emitsCorrectBytes()
    {
        var out = new ByteArrayOutputStream();
        try (var w = new VgmWriter(out, List.of(ChipType.AY8910, ChipType.AY8910)))
        {
            w.waitSamples(300);
        }
        byte[] data = out.toByteArray();
        int headerSize = 0x80;
        assertEquals(0x61, data[headerSize] & 0xFF, "wait command byte");
        int samples = (data[headerSize + 1] & 0xFF) | ((data[headerSize + 2] & 0xFF) << 8);
        assertEquals(300, samples);
    }

    @Test
    void waitSamples_zero_emitsNothing()
    {
        var out = new ByteArrayOutputStream();
        try (var w = new VgmWriter(out, List.of(ChipType.AY8910, ChipType.AY8910)))
        {
            w.waitSamples(0);
            w.waitSamples(-1);
        }
        byte[] data = out.toByteArray();
        // Only header (0x80) + end marker (1) = 0x81 bytes
        assertEquals(0x80 + 1, data.length);
    }

    @Test
    void waitSamples_chunksOver65535()
    {
        var out = new ByteArrayOutputStream();
        try (var w = new VgmWriter(out, List.of(ChipType.AY8910, ChipType.AY8910)))
        {
            w.waitSamples(70000); // > 65535, requires two wait commands
        }
        byte[] data = out.toByteArray();
        int headerSize = 0x80;
        // First chunk: 0x61 0xFF 0xFF (65535)
        assertEquals(0x61, data[headerSize] & 0xFF);
        assertEquals(65535, (data[headerSize + 1] & 0xFF) | ((data[headerSize + 2] & 0xFF) << 8));
        // Second chunk: remaining 4465
        assertEquals(0x61, data[headerSize + 3] & 0xFF);
        assertEquals(4465, (data[headerSize + 4] & 0xFF) | ((data[headerSize + 5] & 0xFF) << 8));
    }

    // ── Chip write commands ───────────────────────────────────────────────────

    @Test
    void writeAy_emitsCommandA0()
    {
        var out = new ByteArrayOutputStream();
        try (var w = new VgmWriter(out, List.of(ChipType.AY8910, ChipType.AY8910)))
        {
            w.writeAy(7, 0x3F);
        }
        byte[] data = out.toByteArray();
        int pos = 0x80;
        assertEquals(0xA0, data[pos] & 0xFF);
        assertEquals(7, data[pos + 1] & 0xFF);
        assertEquals(0x3F, data[pos + 2] & 0xFF);
    }

    @Test
    void writeAy2_setsRegBit7()
    {
        var out = new ByteArrayOutputStream();
        try (var w = new VgmWriter(out, List.of(ChipType.AY8910, ChipType.AY8910)))
        {
            w.writeAy2(7, 0x3F);
        }
        byte[] data = out.toByteArray();
        int pos = 0x80;
        assertEquals(0xA0, data[pos] & 0xFF);
        assertEquals(7 | 0x80, data[pos + 1] & 0xFF); // reg bit 7 set
        assertEquals(0x3F, data[pos + 2] & 0xFF);
    }

    @Test
    void writePsg_emitsCommand50()
    {
        var out = new ByteArrayOutputStream();
        try (var w = new VgmWriter(out, List.of(ChipType.SN76489)))
        {
            w.writePsg(0x9F);
        }
        byte[] data = out.toByteArray();
        int pos = 0x40; // SN76489 uses v1.50 header (0x40 bytes)
        assertEquals(0x50, data[pos] & 0xFF);
        assertEquals(0x9F, data[pos + 1] & 0xFF);
    }

    @Test
    void writePsg2_emitsCommand30()
    {
        var out = new ByteArrayOutputStream();
        try (var w = new VgmWriter(out, List.of(ChipType.SN76489)))
        {
            w.writePsg2(0x9F);
        }
        byte[] data = out.toByteArray();
        int pos = 0x40;
        assertEquals(0x30, data[pos] & 0xFF);
        assertEquals(0x9F, data[pos + 1] & 0xFF);
    }

    @Test
    void writeYm2413_emitsCommand51()
    {
        var out = new ByteArrayOutputStream();
        try (var w = new VgmWriter(out, List.of(ChipType.YM2413)))
        {
            w.writeYm2413(0x20, 0x15);
        }
        byte[] data = out.toByteArray();
        int pos = 0x40; // YM2413 uses v1.50 header
        assertEquals(0x51, data[pos] & 0xFF);
        assertEquals(0x20, data[pos + 1] & 0xFF);
        assertEquals(0x15, data[pos + 2] & 0xFF);
    }

    @Test
    void writeScc_emitsCommandD2()
    {
        var out = new ByteArrayOutputStream();
        try (var w = new VgmWriter(out, List.of(ChipType.SCC, ChipType.AY8910)))
        {
            w.writeScc(0x00, 0x7F);
        }
        byte[] data = out.toByteArray();
        int pos = 0xC0; // SCC uses v1.70 header (0xC0 bytes)
        assertEquals(0xD2, data[pos] & 0xFF);
        assertEquals(0x00, data[pos + 1] & 0xFF); // port 0
        assertEquals(0x00, data[pos + 2] & 0xFF); // reg 0
        assertEquals(0x7F, data[pos + 3] & 0xFF); // data
    }

    @Test
    void writeOpl3_emitsCommand5E()
    {
        var out = new ByteArrayOutputStream();
        try (var w = new VgmWriter(out, List.of(ChipType.OPL3)))
        {
            w.writeOpl3(0xA0, 0x42);
        }
        byte[] data = out.toByteArray();
        int pos = 0x80;
        assertEquals(0x5E, data[pos] & 0xFF);
        assertEquals(0xA0, data[pos + 1] & 0xFF);
        assertEquals(0x42, data[pos + 2] & 0xFF);
    }

    @Test
    void writeOpl3Bank1_emitsCommand5F()
    {
        var out = new ByteArrayOutputStream();
        try (var w = new VgmWriter(out, List.of(ChipType.OPL3)))
        {
            w.writeOpl3Bank1(0x05, 0x01);
        }
        byte[] data = out.toByteArray();
        int pos = 0x80;
        assertEquals(0x5F, data[pos] & 0xFF);
        assertEquals(0x05, data[pos + 1] & 0xFF);
        assertEquals(0x01, data[pos + 2] & 0xFF);
    }
}

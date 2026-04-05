/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.vgm;

import static org.junit.jupiter.api.Assertions.*;

import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VgmParserTest
{

    @TempDir
    Path tmp;

    @Test
    void parse_minimalVgm_returnsResult() throws Exception
    {
        byte[] data = buildMinimalVgm();
        var file = tmp.resolve("test.vgm").toFile();
        try (var out = new FileOutputStream(file))
        {
            out.write(data);
        }

        var result = new VgmParser().parse(file);

        assertEquals(0x151, result.vgmVersion());
        assertEquals(3_579_545L, result.sn76489Clock());
        assertEquals(0L, result.ym2612Clock());
        assertEquals(1, result.events().size());

        var event = result.events().getFirst();
        assertEquals(0, event.sampleOffset());
        assertEquals(0, event.chip()); // SN76489
    }

    @Test
    void parse_dacWriteCommands_accumulateSampleOffset() throws Exception
    {
        // 0x80-0x8F: YM2612 DAC write + wait (cmd & 0x0F) samples.
        // Sonic 3 title screen uses these extensively (6.83s out of 15.72s total).
        // Without this fix the song played at ~2x speed.
        byte[] data = buildVgmWith0x8nCommands();
        var file = tmp.resolve("dac.vgm").toFile();
        try (var out = new FileOutputStream(file))
        {
            out.write(data);
        }

        var result = new VgmParser().parse(file);

        // 0x80 → wait 0, 0x81 → wait 1, 0x82 → wait 2, 0x8F → wait 15 (total = 18 samples)
        // The SN76489 event after all waits should carry sampleOffset = 18
        assertEquals(1, result.events().size());
        assertEquals(18, result.events().getFirst().sampleOffset());
    }

    private static byte[] buildVgmWith0x8nCommands()
    {
        var buf = ByteBuffer.allocate(0x47).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(0x00, 0x206D6756); // magic
        buf.putInt(0x04, 0x47 - 0x04);
        buf.putInt(0x08, 0x00000151);
        buf.putInt(0x0C, 3_579_545);
        buf.putInt(0x34, 0x0C); // data at 0x40

        // DAC writes with waits: 0x80(0) + 0x81(1) + 0x82(2) + 0x8F(15) = 18 samples
        buf.put(0x40, (byte) 0x80);
        buf.put(0x41, (byte) 0x81);
        buf.put(0x42, (byte) 0x82);
        buf.put(0x43, (byte) 0x8F);
        buf.put(0x44, (byte) 0x50); // SN76489 event at sampleOffset=18
        buf.put(0x45, (byte) 0x00);
        buf.put(0x46, (byte) 0x66); // end

        return buf.array();
    }

    private static byte[] buildMinimalVgm()
    {
        var buf = ByteBuffer.allocate(0x43).order(ByteOrder.LITTLE_ENDIAN);

        // 0x00: "Vgm " magic
        buf.putInt(0x00, 0x206D6756);
        // 0x04: EOF offset (relative to 0x04)
        buf.putInt(0x04, 0x43 - 0x04);
        // 0x08: version 1.51
        buf.putInt(0x08, 0x00000151);
        // 0x0C: SN76489 clock
        buf.putInt(0x0C, 3_579_545);
        // 0x10: total samples (unused for this test)
        buf.putInt(0x10, 0);
        // 0x14: GD3 offset = 0 (no GD3)
        buf.putInt(0x14, 0);
        // 0x2C: YM2612 clock = 0
        buf.putInt(0x2C, 0);
        // 0x34: data offset = 0x0C (relative to 0x34 → data at 0x40)
        buf.putInt(0x34, 0x0C);

        // Commands at 0x40
        buf.put(0x40, (byte) 0x50); // SN76489 write
        buf.put(0x41, (byte) 0x00); // data byte
        buf.put(0x42, (byte) 0x66); // end of data

        return buf.array();
    }
}

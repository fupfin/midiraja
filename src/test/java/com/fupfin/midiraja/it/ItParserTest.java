/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.it;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class ItParserTest
{
    /**
     * Builds a minimal valid IT byte array:
     * 192-byte header + OrdNum orders + 0 ins + 0 smp + 1 pat offset + 1 empty pattern.
     */
    static byte[] minimalIt(String title, int speed, int tempo, int activeChannels)
    {
        int ordNum = 1;
        int insNum = 0;
        int smpNum = 0;
        int patNum = 1;

        // Offset tables: after 192-byte header
        int orderOffset = 192;
        int insOffBase = orderOffset + ordNum;
        int smpOffBase = insOffBase;
        int patOffBase = smpOffBase;
        int patDataOffset = patOffBase + patNum * 4;

        // Pattern: 2-byte packed length + 2-byte row count + 4 reserved + 1 end-of-row byte
        int rowCount = 1;
        int packedSize = 1; // just one end-of-row byte (0x00)
        int patTotalSize = 8 + packedSize;

        int totalLen = patDataOffset + patTotalSize;
        var buf = ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN);

        // Magic
        buf.put(0, (byte) 'I');
        buf.put(1, (byte) 'M');
        buf.put(2, (byte) 'P');
        buf.put(3, (byte) 'M');

        // Title
        byte[] tb = title.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < Math.min(26, tb.length); i++)
            buf.put(4 + i, tb[i]);

        // Counts
        buf.putShort(32, (short) ordNum);
        buf.putShort(34, (short) insNum);
        buf.putShort(36, (short) smpNum);
        buf.putShort(38, (short) patNum);
        buf.putShort(42, (short) 0x0214); // cmwt — new format
        buf.put(50, (byte) speed);
        buf.put(51, (byte) tempo);

        // Channel panning (offset 64): first `activeChannels` enabled (panning=32), rest disabled
        for (int i = 0; i < 64; i++)
            buf.put(64 + i, (byte) (i < activeChannels ? 32 : 0x80));

        // Order list
        buf.put(orderOffset, (byte) 0); // pattern 0

        // Pattern offset
        buf.putInt(patOffBase, patDataOffset);

        // Pattern data
        buf.putShort(patDataOffset, (short) packedSize);
        buf.putShort(patDataOffset + 2, (short) rowCount);
        // reserved 4 bytes already zero
        buf.put(patDataOffset + 8, (byte) 0); // end-of-row

        return buf.array();
    }

    @Test
    void parse_title() throws Exception
    {
        var result = new ItParser().parseBytes(minimalIt("Hello IT", 6, 125, 4));
        assertEquals("Hello IT", result.title());
    }

    @Test
    void parse_channelCount() throws Exception
    {
        var result = new ItParser().parseBytes(minimalIt("", 6, 125, 6));
        assertEquals(6, result.channelCount());
    }

    @Test
    void parse_emptyPattern_noEvents() throws Exception
    {
        var result = new ItParser().parseBytes(minimalIt("", 6, 125, 4));
        assertTrue(result.events().isEmpty());
    }

    @Test
    void parse_tooSmall_throwsIOException()
    {
        assertThrows(java.io.IOException.class,
                () -> new ItParser().parseBytes(new byte[10]));
    }

    @Test
    void parse_badMagic_throwsIOException()
    {
        assertThrows(java.io.IOException.class,
                () -> new ItParser().parseBytes(new byte[192]));
    }
}

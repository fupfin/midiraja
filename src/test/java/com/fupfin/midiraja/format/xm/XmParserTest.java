/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.format.xm;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class XmParserTest
{
    /**
     * Builds a minimal valid XM byte array with one empty pattern and no instruments.
     */
    static byte[] minimalXm(String title, int speed, int bpm, int channelCount)
    {
        // Header: 60 fixed + 276 variable (= headerSize) → patterns start at 336
        int headerSize = 276; // standard: 20 fields + 256 order table
        int patternBase = 60 + headerSize; // 336

        // One pattern: 9-byte header + 0 packed bytes (empty rows = 1 row, all zero)
        // We'll store 1 row of channelCount channels, each cell as compressed empty (0x80 | 0x00 = just 0x80)
        // But that still needs 1 terminating byte per channel... let's just use packed=0 rows=1 dataSize=0
        int patHdrSize = 9;
        int rows = 1;
        int packedSize = 0; // 0 bytes of packed data = all cells empty
        int patTotalSize = patHdrSize + packedSize;

        int totalLen = patternBase + patTotalSize;

        var buf = ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN);

        // Magic + title
        buf.put("Extended Module: ".getBytes(StandardCharsets.US_ASCII));
        byte[] tb = title.getBytes(StandardCharsets.US_ASCII);
        buf.put(tb, 0, Math.min(20, tb.length));
        buf.position(37);
        buf.put((byte) 0x1A);
        // tracker name at 38 (20 bytes) — leave blank

        // Version
        buf.putShort(58, (short) 0x0104);

        // Header size
        buf.putInt(60, headerSize);

        // Song length = 1, restart = 0, channels, patterns = 1, instruments = 0
        buf.putShort(64, (short) 1);
        buf.putShort(66, (short) 0);
        buf.putShort(68, (short) channelCount);
        buf.putShort(70, (short) 1);
        buf.putShort(72, (short) 0);
        buf.putShort(74, (short) 0); // flags
        buf.putShort(76, (short) speed);
        buf.putShort(78, (short) bpm);

        // Order table at offset 80: order[0] = pattern 0
        buf.put(80, (byte) 0);

        // Pattern at offset 336
        buf.putInt(patternBase, patHdrSize); // header size
        buf.put(patternBase + 4, (byte) 0); // packing type
        buf.putShort(patternBase + 5, (short) rows);
        buf.putShort(patternBase + 7, (short) packedSize);

        return buf.array();
    }

    @Test
    void parse_title() throws Exception
    {
        var result = new XmParser().parseBytes(minimalXm("Hello XM", 6, 125, 4));
        assertEquals("Hello XM", result.title());
    }

    @Test
    void parse_channelCount() throws Exception
    {
        var result = new XmParser().parseBytes(minimalXm("", 6, 125, 8));
        assertEquals(8, result.channelCount());
    }

    @Test
    void parse_emptyPattern_noEvents() throws Exception
    {
        var result = new XmParser().parseBytes(minimalXm("", 6, 125, 4));
        assertTrue(result.events().isEmpty());
    }

    @Test
    void xmNoteToMidi_middleC()
    {
        // XM note 49 = C4 = MIDI 60
        assertEquals(60, XmParser.xmNoteToMidi(49));
    }

    @Test
    void xmNoteToMidi_lowestC()
    {
        // XM note 1 = C0 = MIDI 12
        assertEquals(12, XmParser.xmNoteToMidi(1));
    }

    @Test
    void parse_tooSmall_throwsIOException()
    {
        assertThrows(java.io.IOException.class,
                () -> new XmParser().parseBytes(new byte[10]));
    }

    @Test
    void parse_badMagic_throwsIOException()
    {
        assertThrows(java.io.IOException.class,
                () -> new XmParser().parseBytes(new byte[80]));
    }
}

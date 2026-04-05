/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.s3m;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.jupiter.api.Test;

import com.fupfin.midiraja.tracker.TrackerParseResult;

class S3mParserTest
{
    /**
     * Builds a minimal valid S3M byte array with the given parameters.
     * Includes one empty pattern and no instruments.
     */
    static byte[] minimalS3m(String title, int speed, int tempo, int channelCount)
    {
        // We need: 96-byte header + ordNum orders + 0 ins paras + 1 pat para
        // + 1 pattern (64 empty rows)
        int ordNum  = 1;
        int insNum  = 0;
        int patNum  = 1;

        int orderOffset   = 96;
        int insParaOffset = orderOffset + ordNum;
        int patParaOffset = insParaOffset; // no ins paras
        int patDataOffset = patParaOffset + patNum * 2;
        // Align to 16-byte paragraph
        int paraOffset = (patDataOffset + 15) & ~15;

        // Each pattern: 2-byte packed length + 64 end-of-row bytes (0x00)
        int patLen = 2 + 64;
        int totalLen = paraOffset + patLen;

        var buf = ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN);

        // Title
        byte[] titleBytes = title.getBytes();
        buf.put(0, titleBytes, 0, Math.min(28, titleBytes.length));

        // Header fields
        buf.put(28, (byte) 0x1A);
        buf.put(29, (byte) 0x10);
        buf.putShort(32, (short) ordNum);
        buf.putShort(34, (short) insNum);
        buf.putShort(36, (short) patNum);
        buf.put(44, (byte) 'S'); buf.put(45, (byte) 'C'); buf.put(46, (byte) 'R'); buf.put(47, (byte) 'M');
        buf.put(49, (byte) speed);
        buf.put(50, (byte) tempo);

        // Channel settings: first channelCount channels are left stereo (0-7), rest disabled
        for (int i = 0; i < 32; i++)
            buf.put(64 + i, (byte) (i < channelCount ? i : 0xFF));

        // Orders
        buf.put(orderOffset, (byte) 0); // pattern 0

        // Pattern parapointer (paraOffset / 16)
        buf.putShort(patParaOffset, (short) (paraOffset / 16));

        // Pattern data: packed length + 64 end-of-row markers
        buf.putShort(paraOffset, (short) 64);
        for (int r = 0; r < 64; r++) buf.put(paraOffset + 2 + r, (byte) 0);

        return buf.array();
    }

    @Test
    void parse_title() throws Exception
    {
        byte[] data = minimalS3m("Hello S3M", 6, 125, 4);
        var result = new S3mParser().parseBytes(data);
        assertEquals("Hello S3M", result.title());
    }

    @Test
    void parse_channelCount() throws Exception
    {
        byte[] data = minimalS3m("", 6, 125, 6);
        var result = new S3mParser().parseBytes(data);
        assertEquals(6, result.channelCount());
    }

    @Test
    void parse_emptyPattern_noEvents() throws Exception
    {
        byte[] data = minimalS3m("", 6, 125, 4);
        var result = new S3mParser().parseBytes(data);
        assertTrue(result.events().isEmpty());
    }

    @Test
    void s3mNoteToMidi_c5_is60()
    {
        // C5 = octave 5, semitone 0 → (5 << 4) | 0 = 0x50
        assertEquals(60, S3mParser.s3mNoteToMidi(0x50));
    }

    @Test
    void s3mNoteToMidi_a4_is57()
    {
        // A4 = octave 4, semitone 9 → (4 << 4) | 9 = 0x49
        assertEquals(57, S3mParser.s3mNoteToMidi(0x49));
    }

    @Test
    void parse_tooSmall_throwsIOException()
    {
        assertThrows(java.io.IOException.class,
                () -> new S3mParser().parseBytes(new byte[10]));
    }
}

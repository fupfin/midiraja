/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.mod;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class ModParserTest
{
    /**
     * Builds a minimal valid 4-channel M.K. MOD binary:
     * - 1 pattern with a single non-empty row
     * - 1 instrument with given name
     */
    private static byte[] buildMinimalMod(String title, String instrName, int period,
            int instrument, int speed, int tempo)
    {
        // Size: 1084 header + 1 pattern (64 rows × 4 channels × 4 bytes) = 1084 + 1024 = 2108
        byte[] data = new byte[1084 + 64 * 4 * 4];

        // Title (20 bytes)
        byte[] titleBytes = title.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(titleBytes, 0, data, 0, Math.min(titleBytes.length, 20));

        // Instrument 1 name (starts at offset 20, 22-byte name field)
        byte[] instrBytes = instrName.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(instrBytes, 0, data, 20, Math.min(instrBytes.length, 22));
        // volume = 64
        data[20 + 25] = 64;

        // Song length = 1 (play order position 0 only)
        data[950] = 1;
        // Pattern order table: position 0 = pattern 0
        data[952] = 0;

        // Format tag "M.K."
        byte[] tag = "M.K.".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(tag, 0, data, 1080, 4);

        // Pattern 0, row 0, channel 0: write note cell
        int cellOffset = 1084;
        // Encode: instrument bits 7-4, period hi bits 3-0 in byte0
        // period = period, instrument = instrument
        data[cellOffset]     = (byte) (((instrument & 0xF0) | ((period >> 8) & 0x0F)));
        data[cellOffset + 1] = (byte) (period & 0xFF);
        data[cellOffset + 2] = (byte) ((instrument & 0x0F) << 4); // effect = 0
        data[cellOffset + 3] = 0; // effectParam = 0

        // If speed/tempo are non-default, write Fxx in channel 1 of row 0
        if (speed != 6)
        {
            int ch1Offset = cellOffset + 4; // channel 1
            data[ch1Offset + 2] = 0x0F; // effect F
            data[ch1Offset + 3] = (byte) speed;
        }
        if (tempo != 125)
        {
            int ch2Offset = cellOffset + 8; // channel 2
            data[ch2Offset + 2] = 0x0F;
            data[ch2Offset + 3] = (byte) tempo;
        }

        return data;
    }

    @Test
    void parse_title() throws Exception
    {
        byte[] data = buildMinimalMod("My Song", "bass", 428, 1, 6, 125);
        var result = new ModParser().parseBytes(data);
        assertEquals("My Song", result.title());
    }

    @Test
    void parse_channelCount() throws Exception
    {
        byte[] data = buildMinimalMod("Test", "instr", 428, 1, 6, 125);
        var result = new ModParser().parseBytes(data);
        assertEquals(4, result.channelCount());
    }

    @Test
    void parse_formatTag() throws Exception
    {
        byte[] data = buildMinimalMod("Test", "instr", 428, 1, 6, 125);
        var result = new ModParser().parseBytes(data);
        assertEquals("M.K.", result.formatTag());
    }

    @Test
    void parse_instrumentName() throws Exception
    {
        byte[] data = buildMinimalMod("Test", "bass guitar", 428, 1, 6, 125);
        var result = new ModParser().parseBytes(data);
        assertEquals("bass guitar", result.instruments().get(0).name());
    }

    @Test
    void parse_noteEvent_period() throws Exception
    {
        byte[] data = buildMinimalMod("Test", "instr", 428, 1, 6, 125);
        var result = new ModParser().parseBytes(data);
        // Row 0, channel 0 should have period 428
        var noteEvent = result.events().stream()
                .filter(e -> e.channel() == 0 && e.period() > 0)
                .findFirst();
        assertTrue(noteEvent.isPresent(), "Should have a note event on channel 0");
        assertEquals(428, noteEvent.get().period());
    }

    @Test
    void parse_noteEvent_instrument() throws Exception
    {
        byte[] data = buildMinimalMod("Test", "instr", 428, 5, 6, 125);
        var result = new ModParser().parseBytes(data);
        var noteEvent = result.events().stream()
                .filter(e -> e.channel() == 0 && e.period() > 0)
                .findFirst().orElseThrow();
        assertEquals(5, noteEvent.instrument());
    }

    @Test
    void parse_noteEvent_microsecond_isZeroForFirstRow() throws Exception
    {
        byte[] data = buildMinimalMod("Test", "instr", 428, 1, 6, 125);
        var result = new ModParser().parseBytes(data);
        var noteEvent = result.events().stream()
                .filter(e -> e.channel() == 0 && e.period() > 0)
                .findFirst().orElseThrow();
        assertEquals(0L, noteEvent.microsecond(), "First row events start at t=0");
    }

    @Test
    void parse_tooSmall_throwsIOException()
    {
        assertThrows(java.io.IOException.class,
                () -> new ModParser().parseBytes(new byte[100]));
    }
}

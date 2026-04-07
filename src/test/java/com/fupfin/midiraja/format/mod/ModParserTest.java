/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.format.mod;

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
        data[cellOffset] = (byte) (((instrument & 0xF0) | ((period >> 8) & 0x0F)));
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

    // ── Navigation effect helpers ─────────────────────────────────────────────

    /** Allocate a blank N-pattern 4-channel M.K. MOD with the given order table. */
    private static byte[] blankMod(int nPatterns, int[] order, int songLength)
    {
        int headerSize = 1084;
        int bytesPerPat = 64 * 4 * 4; // 64 rows × 4 ch × 4 bytes
        byte[] data = new byte[headerSize + nPatterns * bytesPerPat];
        data[950] = (byte) songLength;
        for (int i = 0; i < Math.min(order.length, 128); i++)
            data[952 + i] = (byte) order[i];
        data[1080] = 'M';
        data[1081] = '.';
        data[1082] = 'K';
        data[1083] = '.';
        return data;
    }

    /** Write a 4-byte MOD cell at pattern/row/channel. */
    private static void writeCell(byte[] data, int pat, int row, int ch,
            int period, int instrument, int effect, int param)
    {
        int offset = 1084 + pat * (64 * 4 * 4) + row * 4 * 4 + ch * 4;
        data[offset] = (byte) ((instrument & 0xF0) | ((period >> 8) & 0x0F));
        data[offset + 1] = (byte) (period & 0xFF);
        data[offset + 2] = (byte) (((instrument & 0x0F) << 4) | (effect & 0x0F));
        data[offset + 3] = (byte) param;
    }

    // ── Bxx position jump ─────────────────────────────────────────────────────

    /**
     * Pattern 0 row 1 has B02 (jump to order 2). Pattern 1 should be skipped.
     * Order: [0, 1, 2], songLength=3. Each pattern has a unique period.
     */
    @Test
    void positionJump_Bxx_skipsIntermediatePattern() throws Exception
    {
        // 3 patterns, order=[0,1,2], songLength=3
        byte[] data = blankMod(3, new int[] { 0, 1, 2 }, 3);

        // Pat0 row0 ch0: note period=428
        writeCell(data, 0, 0, 0, 428, 1, 0, 0);
        // Pat0 row1 ch0: B02 (jump to order 2)
        writeCell(data, 0, 1, 0, 0, 0, 0xB, 2);
        // Pat1 row0 ch0: period=200 — should be skipped
        writeCell(data, 1, 0, 0, 200, 1, 0, 0);
        // Pat2 row0 ch0: period=356
        writeCell(data, 2, 0, 0, 356, 1, 0, 0);

        var result = new ModParser().parseBytes(data);
        var events = result.events();

        boolean hasPeriod200 = events.stream().anyMatch(e -> e.period() == 200);
        boolean hasPeriod356 = events.stream().anyMatch(e -> e.period() == 356);
        assertFalse(hasPeriod200, "Pattern 1 should be skipped by B02 position jump");
        assertTrue(hasPeriod356, "Pattern 2 should be reached after B02 jump");
    }

    // ── Dxx pattern break ─────────────────────────────────────────────────────

    /**
     * Pattern 0 row 1 has D05 (break to row 5 of next pattern).
     * The note in pattern 1 row 5 must appear at t = 2 × rowDuration.
     * Default: speed=6, tempo=125 → rowDuration = 6 × 2_500_000 / 125 = 120_000 µs.
     */
    @Test
    void patternBreak_Dxx_startsNextPatternAtSpecifiedRow() throws Exception
    {
        byte[] data = blankMod(2, new int[] { 0, 1 }, 2);

        // Pat0 row0 ch0: note
        writeCell(data, 0, 0, 0, 428, 1, 0, 0);
        // Pat0 row1 ch0: D05 (break to row 5) — param 0x05 → (0)*10+5 = row 5
        writeCell(data, 0, 1, 0, 0, 0, 0xD, 0x05);
        // Pat1 row5 ch1: distinguishing note on channel 1
        writeCell(data, 1, 5, 1, 428, 1, 0, 0);

        var result = new ModParser().parseBytes(data);

        long rowDuration = 6L * 2_500_000L / 125L; // 120_000 µs
        // Row 0 of pat0 → t=0, row 1 (Dxx) → t=120_000; after Dxx row advances → t=240_000
        long expectedMicros = 2 * rowDuration;

        var ch1Event = result.events().stream()
                .filter(e -> e.channel() == 1 && e.period() > 0)
                .findFirst();
        assertTrue(ch1Event.isPresent(), "Should have event on channel 1 in pattern 1");
        assertEquals(expectedMicros, ch1Event.get().microsecond(),
                "Pattern-break target row must start at 2×rowDuration");
    }

    @Test
    void patternBreak_Dxx_rowsBeforeTargetAreSkipped() throws Exception
    {
        byte[] data = blankMod(2, new int[] { 0, 1 }, 2);

        // Pat0 row0 ch0: D05
        writeCell(data, 0, 0, 0, 0, 0, 0xD, 0x05);
        // Pat1 row0 ch0: period=200 — should be skipped (before row 5)
        writeCell(data, 1, 0, 0, 200, 1, 0, 0);
        // Pat1 row5 ch0: period=356 — should appear
        writeCell(data, 1, 5, 0, 356, 1, 0, 0);

        var result = new ModParser().parseBytes(data);
        assertFalse(result.events().stream().anyMatch(e -> e.period() == 200),
                "Rows before pattern-break target must be skipped");
        assertTrue(result.events().stream().anyMatch(e -> e.period() == 356),
                "Pattern-break target row must be played");
    }

    // ── Loop detection ────────────────────────────────────────────────────────

    /**
     * B00 on row 0 of the only pattern creates an infinite self-loop.
     * The parser must detect (orderPos=0,row=0) visited twice and stop.
     */
    @Test
    void loopDetection_Bxx_selfJump_terminates() throws Exception
    {
        byte[] data = blankMod(1, new int[] { 0 }, 1);

        // Pat0 row0 ch0: note
        writeCell(data, 0, 0, 0, 428, 1, 0, 0);
        // Pat0 row0 ch1: B00 (jump to order 0)
        writeCell(data, 0, 0, 1, 0, 0, 0xB, 0);

        var result = new ModParser().parseBytes(data);
        // Only row 0 of pattern 0 should be emitted — exactly once
        long row0Count = result.events().stream().filter(e -> e.microsecond() == 0L).count();
        // After second visit to (0,0) the loop is broken; only one pass through row 0
        assertTrue(row0Count >= 1 && row0Count <= 4 /* 4 channels */,
                "Self-loop must terminate after one pass");
    }
}

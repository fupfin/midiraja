/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.mod;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Parses ProTracker MOD files into a {@link ModParseResult}.
 *
 * <p>
 * Binary layout:
 * <ul>
 * <li>Offset 0: 20-byte song title
 * <li>Offset 20: 31 × 30-byte instrument headers
 * <li>Offset 950: 1-byte song length (number of pattern positions to play)
 * <li>Offset 951: 1-byte restart position (unused)
 * <li>Offset 952: 128-byte pattern order table
 * <li>Offset 1080: 4-byte format tag ("M.K.", "8CHN", …)
 * <li>Offset 1084: pattern data (64 rows × channels × 4 bytes/cell)
 * </ul>
 *
 * <p>
 * Each 4-byte note cell encodes:
 *
 * <pre>
 *   instrument = (byte0 &amp; 0xF0) | ((byte2 &amp; 0xF0) &gt;&gt; 4)
 *   period     = ((byte0 &amp; 0x0F) &lt;&lt; 8) | byte1
 *   effect     = byte2 &amp; 0x0F
 *   effectParam = byte3
 * </pre>
 */
public class ModParser
{
    private static final int ROWS_PER_PATTERN = 64;
    private static final int BYTES_PER_CELL = 4;
    private static final int DEFAULT_SPEED = 6;
    private static final int DEFAULT_TEMPO = 125; // BPM

    public ModParseResult parse(File file) throws IOException
    {
        byte[] data = new FileInputStream(file).readAllBytes();
        return parseBytes(data);
    }

    /** Parse from a raw byte array (useful for testing). */
    public ModParseResult parseBytes(byte[] data) throws IOException
    {
        if (data.length < 1084)
            throw new IOException("File too small to be a valid MOD file");

        String title = readAsciiTrimmed(data, 0, 20);

        List<ModInstrument> instruments = new ArrayList<>(31);
        for (int i = 0; i < 31; i++)
        {
            int base = 20 + i * 30;
            instruments.add(readInstrument(data, base));
        }

        int songLength = data[950] & 0xFF;
        // byte 951: restart position (ignored)

        byte[] orderTable = new byte[128];
        System.arraycopy(data, 952, orderTable, 0, 128);

        String tagStr = new String(data, 1080, 4, StandardCharsets.US_ASCII);
        String formatTag = ModFileDetector.isKnownTag(tagStr) ? tagStr : null;
        int channelCount = formatTag != null ? ModFileDetector.detectChannelCount(tagStr) : 4;

        int patternCount = 0;
        for (int i = 0; i < songLength; i++)
            patternCount = Math.max(patternCount, (orderTable[i] & 0xFF) + 1);

        int patternDataOffset = 1084;
        int bytesPerPattern = ROWS_PER_PATTERN * channelCount * BYTES_PER_CELL;

        List<ModEvent> events = linearize(data, orderTable, songLength, patternDataOffset,
                bytesPerPattern, channelCount);

        return new ModParseResult(title, channelCount, List.copyOf(instruments), events, formatTag);
    }

    private List<ModEvent> linearize(byte[] data, byte[] orderTable, int songLength,
            int patternDataOffset, int bytesPerPattern, int channelCount)
    {
        var events = new ArrayList<ModEvent>();
        int speed = DEFAULT_SPEED;
        int tempo = DEFAULT_TEMPO;
        long currentMicrosecond = 0;

        // Loop-break: track (orderPos, row) pairs visited to detect infinite loops
        Set<Long> visited = new HashSet<>();

        int orderPos = 0;
        int row = 0;

        while (orderPos < songLength)
        {
            long key = ((long) orderPos << 16) | row;
            if (!visited.add(key))
                break; // loop detected

            int patternNum = orderTable[orderPos] & 0xFF;
            int patternOffset = patternDataOffset + patternNum * bytesPerPattern;
            int rowOffset = patternOffset + row * channelCount * BYTES_PER_CELL;

            if (rowOffset + channelCount * BYTES_PER_CELL > data.length)
                break;

            // Scan for tempo effects first (Fxx) to get correct row duration
            int nextSpeed = speed;
            int nextTempo = tempo;
            for (int ch = 0; ch < channelCount; ch++)
            {
                int cellOffset = rowOffset + ch * BYTES_PER_CELL;
                int effect = data[cellOffset + 2] & 0x0F;
                int param = data[cellOffset + 3] & 0xFF;
                if (effect == 0xF && param > 0)
                {
                    if (param <= 0x1F)
                        nextSpeed = param;
                    else
                        nextTempo = param;
                }
            }

            // Emit events for this row
            for (int ch = 0; ch < channelCount; ch++)
            {
                int cellOffset = rowOffset + ch * BYTES_PER_CELL;
                int b0 = data[cellOffset] & 0xFF;
                int b1 = data[cellOffset + 1] & 0xFF;
                int b2 = data[cellOffset + 2] & 0xFF;
                int b3 = data[cellOffset + 3] & 0xFF;

                int instrument = (b0 & 0xF0) | ((b2 & 0xF0) >> 4);
                int period = ((b0 & 0x0F) << 8) | b1;
                int effect = b2 & 0x0F;
                int param = b3;

                if (period > 0 || instrument > 0 || effect != 0 || param != 0)
                    events.add(new ModEvent(currentMicrosecond, ch, period, instrument, effect, param));
            }

            speed = nextSpeed;
            tempo = nextTempo;

            long rowDurationMicros = (long) speed * 2_500_000L / tempo;
            currentMicrosecond += rowDurationMicros;

            // Check for pattern-break (Dxx) and position-jump (Bxx) in any channel
            int jumpToOrder = -1;
            int breakToRow = -1;
            for (int ch = 0; ch < channelCount; ch++)
            {
                int cellOffset = rowOffset + ch * BYTES_PER_CELL;
                int effect = data[cellOffset + 2] & 0x0F;
                int param = data[cellOffset + 3] & 0xFF;
                if (effect == 0xD) // Pattern break: jump to next pattern, row param
                {
                    breakToRow = ((param >> 4) * 10) + (param & 0x0F);
                    if (breakToRow >= ROWS_PER_PATTERN)
                        breakToRow = 0;
                }
                if (effect == 0xB) // Position jump: jump to order param
                {
                    jumpToOrder = param;
                }
            }

            if (jumpToOrder >= 0 || breakToRow >= 0)
            {
                orderPos = jumpToOrder >= 0 ? jumpToOrder : orderPos + 1;
                row = breakToRow >= 0 ? breakToRow : 0;
            }
            else
            {
                row++;
                if (row >= ROWS_PER_PATTERN)
                {
                    row = 0;
                    orderPos++;
                }
            }
        }

        return List.copyOf(events);
    }

    private static ModInstrument readInstrument(byte[] data, int offset)
    {
        String name = readAsciiTrimmed(data, offset, 22);
        int length = ((data[offset + 22] & 0xFF) << 8) | (data[offset + 23] & 0xFF);
        int finetuneByte = data[offset + 24] & 0x0F;
        int finetune = finetuneByte >= 8 ? finetuneByte - 16 : finetuneByte; // signed nibble
        int volume = data[offset + 25] & 0xFF;
        int loopStart = ((data[offset + 26] & 0xFF) << 8) | (data[offset + 27] & 0xFF);
        int loopLength = ((data[offset + 28] & 0xFF) << 8) | (data[offset + 29] & 0xFF);
        return new ModInstrument(name, length, finetune, volume, loopStart, loopLength);
    }

    private static String readAsciiTrimmed(byte[] data, int offset, int maxLen)
    {
        int end = offset;
        for (int i = offset; i < offset + maxLen && i < data.length; i++)
        {
            if (data[i] != 0)
                end = i + 1;
        }
        return new String(data, offset, end - offset, StandardCharsets.US_ASCII).trim();
    }
}

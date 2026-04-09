/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.format.tracker;

import java.nio.charset.StandardCharsets;

/**
 * Shared parsing utilities for tracker format parsers (IT, XM, S3M).
 */
public final class TrackerParserUtils
{
    private TrackerParserUtils()
    {
    }

    /**
     * Reads a null-terminated ASCII string from a byte array and trims whitespace.
     *
     * @param data
     *            raw bytes
     * @param offset
     *            start offset
     * @param maxLen
     *            maximum number of bytes to consider
     * @return trimmed ASCII string
     */
    public static String readAsciiTrimmed(byte[] data, int offset, int maxLen)
    {
        int end = offset;
        for (int i = offset; i < offset + maxLen && i < data.length; i++)
            if (data[i] != 0)
                end = i + 1;
        return new String(data, offset, end - offset, StandardCharsets.US_ASCII).trim();
    }

    /**
     * Decodes a tracker pattern-break effect parameter into a row number.
     *
     * <p>
     * Both S3M (effect C) and IT (effect C) and XM (effect D) encode the break row as two
     * decimal digits packed in BCD: high nibble = tens, low nibble = ones.
     * E.g., {@code 0x23} → row 23.
     *
     * @param effectParam
     *            raw effect parameter byte
     * @return decoded row number
     */
    public static int decodeBreakRow(int effectParam)
    {
        return ((effectParam >> 4) * 10) + (effectParam & 0x0F);
    }
}

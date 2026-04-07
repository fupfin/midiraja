/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.format.mod;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;

/**
 * Detects ProTracker MOD files by extension or magic bytes.
 *
 * <p>
 * The format tag is located at byte offset 1080 (0x438) and identifies the variant:
 * "M.K." and "M!K!" are the original 4-channel tags; "4CHN", "6CHN", "8CHN" etc. are extended.
 */
public final class ModFileDetector
{
    // Known 4-byte format tags and their channel counts
    private static final Set<String> TAGS_4CH = Set.of("M.K.", "M!K!", "FLT4", "NSMS", "LARD");
    private static final Set<String> TAGS_6CH = Set.of("6CHN", "FLT6");
    private static final Set<String> TAGS_8CH = Set.of("8CHN", "FLT8", "OCTA", "CD81");

    private ModFileDetector()
    {
    }

    /** Returns true if the file is a MOD file (by extension or magic bytes). */
    public static boolean isModFile(File file)
    {
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".mod"))
            return true;
        return hasMagicTag(file);
    }

    /**
     * Detects channel count from the 4-byte format tag string.
     * Returns 4 for unknown/unrecognized tags.
     */
    public static int detectChannelCount(String tag)
    {
        if (TAGS_4CH.contains(tag))
            return 4;
        if (TAGS_6CH.contains(tag))
            return 6;
        if (TAGS_8CH.contains(tag))
            return 8;
        // "xCHN" pattern: first char is digit
        if (tag.length() == 4 && tag.endsWith("CHN") && Character.isDigit(tag.charAt(0)))
            return Character.getNumericValue(tag.charAt(0));
        // "xxCH" pattern: first two chars are digits
        if (tag.length() == 4 && tag.endsWith("CH")
                && Character.isDigit(tag.charAt(0)) && Character.isDigit(tag.charAt(1)))
            return Integer.parseInt(tag.substring(0, 2));
        return 4;
    }

    /** Returns whether the file is a known MOD format tag. */
    public static boolean isKnownTag(String tag)
    {
        if (TAGS_4CH.contains(tag) || TAGS_6CH.contains(tag) || TAGS_8CH.contains(tag))
            return true;
        if (tag.length() == 4 && tag.endsWith("CHN") && Character.isDigit(tag.charAt(0)))
            return true;
        if (tag.length() == 4 && tag.endsWith("CH")
                && Character.isDigit(tag.charAt(0)) && Character.isDigit(tag.charAt(1)))
            return true;
        return false;
    }

    private static boolean hasMagicTag(File file)
    {
        if (!file.isFile() || file.length() < 1084)
            return false;
        try (var fis = new FileInputStream(file))
        {
            long skipped = fis.skip(1080);
            if (skipped < 1080)
                return false;
            byte[] tag = new byte[4];
            if (fis.read(tag) != 4)
                return false;
            String tagStr = new String(tag, java.nio.charset.StandardCharsets.US_ASCII);
            return isKnownTag(tagStr);
        }
        catch (IOException e)
        {
            return false;
        }
    }
}

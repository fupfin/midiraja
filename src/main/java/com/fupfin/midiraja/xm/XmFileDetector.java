/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.xm;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Detects FastTracker 2 XM files by extension or magic bytes.
 *
 * <p>
 * The XM magic {@code "Extended Module: "} (17 bytes) appears at offset 0.
 */
public final class XmFileDetector
{
    private static final byte[] MAGIC = "Extended Module: ".getBytes(StandardCharsets.US_ASCII);

    private XmFileDetector()
    {
    }

    public static boolean isXmFile(File file)
    {
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".xm"))
            return true;
        return hasMagic(file);
    }

    private static boolean hasMagic(File file)
    {
        try (var in = new FileInputStream(file))
        {
            byte[] buf = in.readNBytes(MAGIC.length);
            if (buf.length < MAGIC.length)
                return false;
            for (int i = 0; i < MAGIC.length; i++)
                if (buf[i] != MAGIC[i])
                    return false;
            return true;
        }
        catch (IOException e)
        {
            return false;
        }
    }
}

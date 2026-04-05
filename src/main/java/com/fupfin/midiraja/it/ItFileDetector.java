/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.it;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Locale;

/**
 * Detects Impulse Tracker (.it) files by extension or magic bytes.
 *
 * <p>
 * The IT magic {@code "IMPM"} appears at byte offset 0.
 */
public final class ItFileDetector
{
    private static final byte[] MAGIC = { 'I', 'M', 'P', 'M' };

    private ItFileDetector()
    {
    }

    public static boolean isItFile(File file)
    {
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".it"))
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

/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.format.s3m;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Locale;

/**
 * Detects Scream Tracker 3 (.s3m) files by extension or magic bytes.
 *
 * <p>
 * The S3M magic "SCRM" appears at byte offset 44 in a valid module.
 */
public final class S3mFileDetector
{
    private static final int MAGIC_OFFSET = 44;
    private static final byte[] MAGIC = { 'S', 'C', 'R', 'M' };

    private S3mFileDetector()
    {
    }

    public static boolean isS3mFile(File file)
    {
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".s3m"))
            return true;
        return hasMagic(file);
    }

    private static boolean hasMagic(File file)
    {
        try (var in = new FileInputStream(file))
        {
            if (in.skip(MAGIC_OFFSET) < MAGIC_OFFSET)
                return false;
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

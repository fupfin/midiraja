/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.format.xm;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class XmFileDetectorTest
{
    @TempDir
    java.nio.file.Path tmp;

    @Test
    void isXmFile_byExtension() throws Exception
    {
        var f = tmp.resolve("song.xm").toFile();
        f.createNewFile();
        assertTrue(XmFileDetector.isXmFile(f));
    }

    @Test
    void isXmFile_byMagic() throws Exception
    {
        var f = tmp.resolve("song.dat").toFile();
        byte[] data = new byte[20];
        System.arraycopy("Extended Module: ".getBytes(StandardCharsets.US_ASCII), 0, data, 0, 17);
        Files.write(f.toPath(), data);
        assertTrue(XmFileDetector.isXmFile(f));
    }

    @Test
    void isXmFile_wrongMagic() throws Exception
    {
        var f = tmp.resolve("song.dat").toFile();
        Files.write(f.toPath(), new byte[20]);
        assertFalse(XmFileDetector.isXmFile(f));
    }
}

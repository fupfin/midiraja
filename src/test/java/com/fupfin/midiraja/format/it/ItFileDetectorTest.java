/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.format.it;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ItFileDetectorTest
{
    @TempDir
    java.nio.file.Path tmp;

    @Test
    void isItFile_byExtension() throws Exception
    {
        var f = tmp.resolve("song.it").toFile();
        f.createNewFile();
        assertTrue(ItFileDetector.isItFile(f));
    }

    @Test
    void isItFile_byMagic() throws Exception
    {
        var f = tmp.resolve("song.dat").toFile();
        Files.write(f.toPath(), new byte[] { 'I', 'M', 'P', 'M', 0, 0, 0, 0 });
        assertTrue(ItFileDetector.isItFile(f));
    }

    @Test
    void isItFile_wrongMagic() throws Exception
    {
        var f = tmp.resolve("song.dat").toFile();
        Files.write(f.toPath(), new byte[] { 0, 0, 0, 0 });
        assertFalse(ItFileDetector.isItFile(f));
    }
}

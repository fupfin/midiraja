/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.s3m;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class S3mFileDetectorTest
{
    @TempDir
    Path tmp;

    @Test
    void isS3mFile_byExtension() throws Exception
    {
        var f = tmp.resolve("song.s3m").toFile();
        f.createNewFile();
        assertTrue(S3mFileDetector.isS3mFile(f));
    }

    @Test
    void isS3mFile_byMagic() throws Exception
    {
        var f = tmp.resolve("song.dat").toFile();
        byte[] data = new byte[48];
        data[44] = 'S';
        data[45] = 'C';
        data[46] = 'R';
        data[47] = 'M';
        Files.write(f.toPath(), data);
        assertTrue(S3mFileDetector.isS3mFile(f));
    }

    @Test
    void isS3mFile_wrongMagic() throws Exception
    {
        var f = tmp.resolve("song.dat").toFile();
        Files.write(f.toPath(), new byte[48]);
        assertFalse(S3mFileDetector.isS3mFile(f));
    }
}

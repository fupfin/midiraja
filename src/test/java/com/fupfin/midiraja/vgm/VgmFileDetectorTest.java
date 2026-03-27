/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.vgm;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VgmFileDetectorTest {

    @TempDir Path tmp;

    @Test
    void isVgmFile_byExtension_vgm() throws Exception {
        var file = tmp.resolve("song.vgm").toFile();
        file.createNewFile();
        assertTrue(VgmFileDetector.isVgmFile(file));
    }

    @Test
    void isVgmFile_byExtension_vgz() throws Exception {
        var file = tmp.resolve("song.vgz").toFile();
        file.createNewFile();
        assertTrue(VgmFileDetector.isVgmFile(file));
    }

    @Test
    void isVgmFile_byMagic() throws Exception {
        var file = tmp.resolve("song.dat").toFile();
        try (var out = new FileOutputStream(file)) {
            out.write(new byte[]{0x56, 0x67, 0x6D, 0x20, 0x00, 0x00, 0x00, 0x00});
        }
        assertTrue(VgmFileDetector.isVgmFile(file));
    }

    @Test
    void isVgmFile_false_midi() throws Exception {
        var file = tmp.resolve("song.mid").toFile();
        try (var out = new FileOutputStream(file)) {
            out.write(new byte[]{0x4D, 0x54, 0x68, 0x64}); // "MThd"
        }
        assertFalse(VgmFileDetector.isVgmFile(file));
    }
}

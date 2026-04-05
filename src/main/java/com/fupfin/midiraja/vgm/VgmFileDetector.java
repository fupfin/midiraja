/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.vgm;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/** Detects VGM files by extension or magic bytes. */
public final class VgmFileDetector {

    private static final byte[] VGM_MAGIC = {0x56, 0x67, 0x6D, 0x20}; // "Vgm "

    private VgmFileDetector() {}

    /** Returns true if the file is a VGM/VGZ file (by extension or magic bytes). */
    public static boolean isVgmFile(File file) {
        String name = file.getName().toLowerCase();
        if (name.endsWith(".vgm") || name.endsWith(".vgz")) return true;
        return hasMagicBytes(file);
    }

    private static boolean hasMagicBytes(File file) {
        if (!file.isFile() || file.length() < 4) return false;
        try (var in = new FileInputStream(file)) {
            byte[] header = new byte[4];
            if (in.read(header) < 4) return false;
            for (int i = 0; i < 4; i++) {
                if (header[i] != VGM_MAGIC[i]) return false;
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}

/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.mod;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.FileOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModFileDetectorTest
{
    @TempDir
    File tempDir;

    @Test
    void extensionMod_detected()
    {
        assertTrue(ModFileDetector.isModFile(new File("song.mod")));
    }

    @Test
    void extensionMOD_detected()
    {
        assertTrue(ModFileDetector.isModFile(new File("SONG.MOD")));
    }

    @Test
    void extensionMid_notDetected()
    {
        assertFalse(ModFileDetector.isModFile(new File("song.mid")));
    }

    @Test
    void magicMK_detected() throws Exception
    {
        File f = writeMagicFile("M.K.");
        assertTrue(ModFileDetector.isModFile(f));
    }

    @Test
    void magic8CHN_detected() throws Exception
    {
        File f = writeMagicFile("8CHN");
        assertTrue(ModFileDetector.isModFile(f));
    }

    @Test
    void unknownTag_notDetected() throws Exception
    {
        File f = writeMagicFile("MIDI");
        assertFalse(ModFileDetector.isModFile(f));
    }

    @Test
    void channelCount_MK_is4()
    {
        assertEquals(4, ModFileDetector.detectChannelCount("M.K."));
    }

    @Test
    void channelCount_8CHN_is8()
    {
        assertEquals(8, ModFileDetector.detectChannelCount("8CHN"));
    }

    @Test
    void channelCount_6CHN_is6()
    {
        assertEquals(6, ModFileDetector.detectChannelCount("6CHN"));
    }

    private File writeMagicFile(String tag) throws Exception
    {
        File f = new File(tempDir, "test.unknown");
        byte[] data = new byte[1084];
        byte[] tagBytes = tag.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(tagBytes, 0, data, 1080, 4);
        try (var fos = new FileOutputStream(f))
        {
            fos.write(data);
        }
        return f;
    }
}

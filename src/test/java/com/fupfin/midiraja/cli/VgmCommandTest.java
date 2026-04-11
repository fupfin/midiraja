/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class VgmCommandTest
{

    // ── File recognition ─────────────────────────────────────────────────────

    @Test
    void vgmFile_isRecognized()
    {
        assertTrue(VgmCommand.isVgmFile(new File("game.vgm")), ".vgm files should be recognized");
        assertTrue(VgmCommand.isVgmFile(new File("song.VGM")), ".vgm file extension matching is case-insensitive");
    }

    @Test
    void vgzFile_isRecognized()
    {
        assertTrue(VgmCommand.isVgmFile(new File("game.vgz")), ".vgz files should be recognized");
        assertTrue(VgmCommand.isVgmFile(new File("song.VGZ")), ".vgz file extension matching is case-insensitive");
    }

    @Test
    void midiFile_isNotRecognized()
    {
        assertFalse(VgmCommand.isVgmFile(new File("song.mid")), ".mid files should not be treated as VGM");
    }

    @Test
    void fileWithoutExtension_isNotRecognized()
    {
        assertFalse(VgmCommand.isVgmFile(new File("game")), "Files without extension should not be recognized");
    }

    // ── System option defaults ────────────────────────────────────────────────

    @Test
    void system_defaultIsAy8910()
    {
        VgmCommand cmd = new VgmCommand();
        assertEquals("ay8910", cmd.getSystem(), "Default --system value should be 'ay8910'");
    }

    // ── System option validation ──────────────────────────────────────────────

    @Test
    void ay8910System_isValid()
    {
        VgmCommand cmd = new VgmCommand();
        cmd.setSystem("ay8910");
        assertDoesNotThrow(() -> cmd.validateSystem(), "ay8910 system should be valid");
    }

    @Test
    void ym2413System_isValid()
    {
        VgmCommand cmd = new VgmCommand();
        cmd.setSystem("ym2413");
        assertDoesNotThrow(() -> cmd.validateSystem(), "ym2413 system should be valid");
    }

    @Test
    void msxSystem_isValid()
    {
        VgmCommand cmd = new VgmCommand();
        cmd.setSystem("msx");
        assertDoesNotThrow(() -> cmd.validateSystem(), "msx system should be valid");
    }

    @Test
    void opl3System_isValid()
    {
        VgmCommand cmd = new VgmCommand();
        cmd.setSystem("opl3");
        assertDoesNotThrow(() -> cmd.validateSystem(), "opl3 system should be valid");
    }

    @Test
    void unknownSystem_throwsIllegalArgument()
    {
        VgmCommand cmd = new VgmCommand();
        cmd.setSystem("xyz");
        assertThrows(IllegalArgumentException.class, () -> cmd.validateSystem(),
                "Unknown system value 'xyz' should throw IllegalArgumentException");
    }

    @Test
    void emptySystem_throwsIllegalArgument()
    {
        VgmCommand cmd = new VgmCommand();
        cmd.setSystem("");
        assertThrows(IllegalArgumentException.class, () -> cmd.validateSystem(),
                "Empty system value should throw IllegalArgumentException");
    }

    @Test
    void systemValidation_isCaseInsensitive()
    {
        VgmCommand cmd = new VgmCommand();
        cmd.setSystem("AY8910");
        assertDoesNotThrow(() -> cmd.validateSystem(), "System validation should be case-insensitive");

        cmd.setSystem("YM2413");
        assertDoesNotThrow(() -> cmd.validateSystem(), "System validation should be case-insensitive");

        cmd.setSystem("MSX");
        assertDoesNotThrow(() -> cmd.validateSystem(), "System validation should be case-insensitive");

        cmd.setSystem("OPL3");
        assertDoesNotThrow(() -> cmd.validateSystem(), "System validation should be case-insensitive");
    }

    // ── System exporter mapping ───────────────────────────────────────────────

    @Test
    void ay8910System_returnsAy8910Exporter()
    {
        VgmCommand cmd = new VgmCommand();
        cmd.setSystem("ay8910");
        assertEquals("Ay8910VgmExporter", cmd.getExporterClassName(),
                "ay8910 system should use Ay8910VgmExporter");
    }

    @Test
    void ym2413System_returnsYm2413Exporter()
    {
        VgmCommand cmd = new VgmCommand();
        cmd.setSystem("ym2413");
        assertEquals("Ym2413VgmExporter", cmd.getExporterClassName(),
                "ym2413 system should use Ym2413VgmExporter");
    }

    @Test
    void msxSystem_returnsMsxExporter()
    {
        VgmCommand cmd = new VgmCommand();
        cmd.setSystem("msx");
        assertEquals("MsxVgmExporter", cmd.getExporterClassName(),
                "msx system should use MsxVgmExporter");
    }

    @Test
    void opl3System_returnsOpl3Exporter()
    {
        VgmCommand cmd = new VgmCommand();
        cmd.setSystem("opl3");
        assertEquals("Opl3VgmExporter", cmd.getExporterClassName(),
                "opl3 system should use Opl3VgmExporter");
    }

    // ── File list handling ────────────────────────────────────────────────────

    @Test
    void emptyFileList_throwsIllegalArgument()
    {
        VgmCommand cmd = new VgmCommand();
        cmd.setFiles(new ArrayList<>());
        assertThrows(IllegalArgumentException.class, () -> cmd.validateFiles(),
                "Empty file list should throw IllegalArgumentException");
    }

    @Test
    void singleFile_isValid()
    {
        VgmCommand cmd = new VgmCommand();
        List<File> files = new ArrayList<>();
        files.add(new File("song.vgm"));
        cmd.setFiles(files);
        assertDoesNotThrow(() -> cmd.validateFiles(), "Single file list should be valid");
    }

    @Test
    void multipleFiles_areValid()
    {
        VgmCommand cmd = new VgmCommand();
        List<File> files = new ArrayList<>();
        files.add(new File("song1.vgm"));
        files.add(new File("song2.vgm"));
        files.add(new File("song3.vgz"));
        cmd.setFiles(files);
        assertDoesNotThrow(() -> cmd.validateFiles(), "Multiple files should be valid");
    }

}

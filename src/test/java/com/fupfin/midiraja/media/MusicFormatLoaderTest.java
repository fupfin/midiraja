/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.media;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MusicFormatLoaderTest
{
    // ── isSupportedFile ───────────────────────────────────────────────────────

    @Test
    void isSupportedFile_mid()
    {
        assertTrue(MusicFormatLoader.isSupportedFile("song.mid"));
    }

    @Test
    void isSupportedFile_midi()
    {
        assertTrue(MusicFormatLoader.isSupportedFile("song.midi"));
    }

    @Test
    void isSupportedFile_vgm()
    {
        assertTrue(MusicFormatLoader.isSupportedFile("song.vgm"));
    }

    @Test
    void isSupportedFile_vgz()
    {
        assertTrue(MusicFormatLoader.isSupportedFile("song.vgz"));
    }

    @Test
    void isSupportedFile_mod()
    {
        assertTrue(MusicFormatLoader.isSupportedFile("song.mod"));
    }

    @Test
    void isSupportedFile_s3m()
    {
        assertTrue(MusicFormatLoader.isSupportedFile("song.s3m"));
    }

    @Test
    void isSupportedFile_xm()
    {
        assertTrue(MusicFormatLoader.isSupportedFile("song.xm"));
    }

    @Test
    void isSupportedFile_it()
    {
        assertTrue(MusicFormatLoader.isSupportedFile("song.it"));
    }

    @Test
    void isSupportedFile_mp3_returnsFalse()
    {
        assertFalse(MusicFormatLoader.isSupportedFile("song.mp3"));
    }

    @Test
    void isSupportedFile_txt_returnsFalse()
    {
        assertFalse(MusicFormatLoader.isSupportedFile("notes.txt"));
    }

    @Test
    void isSupportedFile_isCaseInsensitive()
    {
        assertTrue(MusicFormatLoader.isSupportedFile("SONG.S3M"));
        assertTrue(MusicFormatLoader.isSupportedFile("Song.Mod"));
        assertTrue(MusicFormatLoader.isSupportedFile("TRACK.IT"));
    }

    // ── load dispatches to correct parser ─────────────────────────────────────

    @Test
    void load_s3mFile_returnsValidSequence(@TempDir File tmpDir) throws Exception
    {
        File s3mFile = new File(tmpDir, "test.s3m");
        Files.write(s3mFile.toPath(), buildMinimalS3m());

        var seq = MusicFormatLoader.load(s3mFile, Set.of());
        assertNotNull(seq, "load() must return a Sequence for a valid S3M file");
    }

    @Test
    void load_modFile_returnsValidSequence(@TempDir File tmpDir) throws Exception
    {
        File modFile = new File(tmpDir, "test.mod");
        Files.write(modFile.toPath(), buildMinimalMod());

        var seq = MusicFormatLoader.load(modFile, Set.of());
        assertNotNull(seq, "load() must return a Sequence for a valid MOD file");
    }

    // ── Minimal binary builders ───────────────────────────────────────────────

    /** Builds a minimal valid S3M binary with no instruments and one empty pattern. */
    private static byte[] buildMinimalS3m()
    {
        int ordNum = 1;
        int patNum = 1;

        int orderOffset = 96;
        int patParaOffset = orderOffset + ordNum; // no instrument paras
        int patDataOffset = patParaOffset + patNum * 2;
        int paraOffset = (patDataOffset + 15) & ~15; // align to 16-byte paragraph
        int patLen = 2 + 64; // 2B packed length + 64 end-of-row bytes
        int totalLen = paraOffset + patLen;

        var buf = ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN);

        // Magic SCRM at offset 44
        buf.put(44, (byte) 'S');
        buf.put(45, (byte) 'C');
        buf.put(46, (byte) 'R');
        buf.put(47, (byte) 'M');
        buf.put(28, (byte) 0x1A);
        buf.put(29, (byte) 0x10); // type
        buf.putShort(32, (short) ordNum);
        buf.putShort(34, (short) 0); // insNum
        buf.putShort(36, (short) patNum);
        buf.put(49, (byte) 6); // speed
        buf.put(50, (byte) 125); // tempo

        // Channel settings: 4 channels enabled (0-3), rest disabled
        for (int i = 0; i < 32; i++)
            buf.put(64 + i, (byte) (i < 4 ? i : 0xFF));

        // Orders
        buf.put(orderOffset, (byte) 0); // pattern 0
        // Pattern parapointer
        buf.putShort(patParaOffset, (short) (paraOffset / 16));

        // Pattern data: packed length + 64 end-of-row markers
        buf.putShort(paraOffset, (short) 64);
        for (int r = 0; r < 64; r++)
            buf.put(paraOffset + 2 + r, (byte) 0);

        return buf.array();
    }

    /** Builds a minimal valid 4-channel M.K. MOD binary with one empty pattern. */
    private static byte[] buildMinimalMod()
    {
        // 1084-byte header + 64 rows × 4 channels × 4 bytes = 1084 + 1024
        byte[] data = new byte[1084 + 64 * 4 * 4];

        // Song length = 1
        data[950] = 1;
        // Order table: position 0 = pattern 0
        data[952] = 0;

        // Format tag "M.K."
        byte[] tag = "M.K.".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(tag, 0, data, 1080, 4);

        return data;
    }
}

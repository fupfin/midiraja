/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.format;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Set;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

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

    @Test
    void load_vgmFile_returnsValidSequence(@TempDir File tmpDir) throws Exception
    {
        File vgmFile = new File(tmpDir, "test.vgm");
        Files.write(vgmFile.toPath(), buildMinimalVgm());

        var seq = MusicFormatLoader.load(vgmFile, Set.of());
        assertNotNull(seq, "load() must return a Sequence for a valid VGM file");
    }

    @Test
    void load_xmFile_returnsValidSequence(@TempDir File tmpDir) throws Exception
    {
        File xmFile = new File(tmpDir, "test.xm");
        Files.write(xmFile.toPath(), buildMinimalXm());

        var seq = MusicFormatLoader.load(xmFile, Set.of());
        assertNotNull(seq, "load() must return a Sequence for a valid XM file");
    }

    @Test
    void load_itFile_returnsValidSequence(@TempDir File tmpDir) throws Exception
    {
        File itFile = new File(tmpDir, "test.it");
        Files.write(itFile.toPath(), buildMinimalIt());

        var seq = MusicFormatLoader.load(itFile, Set.of());
        assertNotNull(seq, "load() must return a Sequence for a valid IT file");
    }

    @Test
    void load_midiFile_returnsValidSequence(@TempDir File tmpDir) throws Exception
    {
        File midiFile = new File(tmpDir, "test.mid");
        Sequence seq = new Sequence(Sequence.PPQ, 480);
        Track track = seq.createTrack();
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 60, 64), 0));
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, 60, 0), 480));
        try (var fos = new FileOutputStream(midiFile))
        {
            MidiSystem.write(seq, 1, fos);
        }

        var loaded = MusicFormatLoader.load(midiFile, Set.of());
        assertNotNull(loaded, "load() must return a Sequence for a valid MIDI file");
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

    /** Builds a minimal valid VGM 1.51 binary with a single SN76489 command. */
    private static byte[] buildMinimalVgm()
    {
        var buf = ByteBuffer.allocate(0x43).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(0x00, 0x206D6756); // "Vgm " magic
        buf.putInt(0x04, 0x43 - 0x04); // EOF offset
        buf.putInt(0x08, 0x00000151); // version 1.51
        buf.putInt(0x0C, 3_579_545); // SN76489 clock
        buf.putInt(0x34, 0x0C); // data at 0x40
        buf.put(0x40, (byte) 0x50); // SN76489 write
        buf.put(0x41, (byte) 0x00);
        buf.put(0x42, (byte) 0x66); // end of data
        return buf.array();
    }

    /** Builds a minimal valid XM (Extended Module) binary with one empty pattern. */
    private static byte[] buildMinimalXm()
    {
        int headerSize = 276;
        int patternBase = 60 + headerSize;
        int patHdrSize = 9;
        int totalLen = patternBase + patHdrSize;

        var buf = ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN);
        buf.put("Extended Module: ".getBytes(StandardCharsets.US_ASCII));
        buf.position(37);
        buf.put((byte) 0x1A);
        buf.putShort(58, (short) 0x0104); // version
        buf.putInt(60, headerSize);
        buf.putShort(64, (short) 1); // song length = 1
        buf.putShort(68, (short) 4); // channels
        buf.putShort(70, (short) 1); // patterns
        buf.putShort(76, (short) 6); // speed
        buf.putShort(78, (short) 125); // BPM
        buf.put(80, (byte) 0); // order[0] = pattern 0
        buf.putInt(patternBase, patHdrSize); // pattern header size
        buf.putShort(patternBase + 5, (short) 1); // rows = 1
        return buf.array();
    }

    /** Builds a minimal valid IT (Impulse Tracker) binary with one empty pattern. */
    private static byte[] buildMinimalIt()
    {
        int ordNum = 1;
        int patNum = 1;
        int orderOffset = 192;
        int patOffBase = orderOffset + ordNum;
        int patDataOffset = patOffBase + patNum * 4;
        int totalLen = patDataOffset + 8 + 1;

        var buf = ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(0, (byte) 'I');
        buf.put(1, (byte) 'M');
        buf.put(2, (byte) 'P');
        buf.put(3, (byte) 'M');
        buf.putShort(32, (short) ordNum);
        buf.putShort(38, (short) patNum);
        buf.putShort(42, (short) 0x0214); // cmwt
        buf.put(50, (byte) 6); // speed
        buf.put(51, (byte) 125); // tempo
        for (int i = 0; i < 4; i++)
            buf.put(64 + i, (byte) 32); // 4 channels enabled
        for (int i = 4; i < 64; i++)
            buf.put(64 + i, (byte) 0x80); // rest disabled
        buf.put(orderOffset, (byte) 0);
        buf.putInt(patOffBase, patDataOffset);
        buf.putShort(patDataOffset, (short) 1); // packed size = 1
        buf.putShort(patDataOffset + 2, (short) 1); // rows = 1
        buf.put(patDataOffset + 8, (byte) 0); // end-of-row
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

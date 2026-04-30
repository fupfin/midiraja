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
    void isSupportedFile_s3m_returnsFalse()
    {
        assertFalse(MusicFormatLoader.isSupportedFile("song.s3m"));
    }

    @Test
    void isSupportedFile_xm_returnsFalse()
    {
        assertFalse(MusicFormatLoader.isSupportedFile("song.xm"));
    }

    @Test
    void isSupportedFile_it_returnsFalse()
    {
        assertFalse(MusicFormatLoader.isSupportedFile("song.it"));
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
        assertTrue(MusicFormatLoader.isSupportedFile("Song.Mod"));
        assertTrue(MusicFormatLoader.isSupportedFile("TRACK.MID"));
    }

    // ── load dispatches to correct parser ─────────────────────────────────────

    @Test
    void load_modFile_returnsValidSequence(@TempDir File tmpDir) throws Exception
    {
        File modFile = new File(tmpDir, "test.mod");
        Files.write(modFile.toPath(), buildMinimalMod());

        var seq = MusicFormatLoader.load(modFile, Set.of());
        assertNotNull(seq, "load() must return a Sequence for a valid MOD file");
        assertTrue(Files.deleteIfExists(modFile.toPath()),
                "load() must not keep the MOD file locked after parsing");
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

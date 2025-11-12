/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.midiraja.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.sound.midi.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlaylistParserTest
{
    private PlaylistParser parser;
    private ByteArrayOutputStream errBytes;
    private CommonOptions common;

    @BeforeEach void setUp()
    {
        errBytes = new ByteArrayOutputStream();
        parser = new PlaylistParser(new PrintStream(errBytes), false);
        common = new CommonOptions();
    }

    // ── Basic file/directory handling ────────────────────────────────────────────

    @Test void testParseMidiFile(@TempDir Path tempDir) throws Exception
    {
        File midiFile = createTestMidi(tempDir, "song.mid");

        List<File> result = parser.parse(List.of(midiFile), common);

        assertEquals(1, result.size());
        assertEquals(midiFile, result.get(0));
    }

    @Test void testParseDirectory(@TempDir Path tempDir) throws Exception
    {
        createTestMidi(tempDir, "a.mid");
        createTestMidi(tempDir, "b.mid");
        // Non-MIDI file should be ignored
        Files.writeString(tempDir.resolve("readme.txt"), "not a midi file");

        List<File> result = parser.parse(List.of(tempDir.toFile()), common);

        assertEquals(2, result.size());
    }

    @Test void testParseDirectoryNonRecursive(@TempDir Path tempDir) throws Exception
    {
        createTestMidi(tempDir, "top.mid");
        Path subDir = tempDir.resolve("sub");
        Files.createDirectory(subDir);
        createTestMidi(subDir, "nested.mid");

        common.recursive = false;
        List<File> result = parser.parse(List.of(tempDir.toFile()), common);

        assertEquals(1, result.size());
    }

    @Test void testParseDirectoryRecursive(@TempDir Path tempDir) throws Exception
    {
        createTestMidi(tempDir, "top.mid");
        Path subDir = tempDir.resolve("sub");
        Files.createDirectory(subDir);
        createTestMidi(subDir, "nested.mid");

        common.recursive = true;
        List<File> result = parser.parse(List.of(tempDir.toFile()), common);

        assertEquals(2, result.size());
    }

    // ── M3U playlist with #MIDRA: directives ────────────────────────────────────

    @Test void testM3uShuffleDirectiveLongFlag(@TempDir Path tempDir) throws Exception
    {
        File midi = createTestMidi(tempDir, "track.mid");
        File m3u = tempDir.resolve("playlist.m3u").toFile();
        Files.writeString(m3u.toPath(), "#MIDRA: --shuffle\n" + midi.getAbsolutePath() + "\n");

        assertFalse(common.shuffle);
        List<File> result = parser.parse(List.of(m3u), common);

        assertTrue(common.shuffle, "#MIDRA: --shuffle should set shuffle=true");
        assertEquals(1, result.size());
    }

    @Test void testM3uShuffleDirectiveShortFlag(@TempDir Path tempDir) throws Exception
    {
        File midi = createTestMidi(tempDir, "track.mid");
        File m3u = tempDir.resolve("playlist.m3u").toFile();
        Files.writeString(m3u.toPath(), "#MIDRA: -s\n" + midi.getAbsolutePath() + "\n");

        assertFalse(common.shuffle);
        parser.parse(List.of(m3u), common);

        assertTrue(common.shuffle, "#MIDRA: -s should set shuffle=true (new flag mapping)");
    }

    @Test void testM3uLoopDirectiveLongFlag(@TempDir Path tempDir) throws Exception
    {
        File midi = createTestMidi(tempDir, "track.mid");
        File m3u = tempDir.resolve("playlist.m3u").toFile();
        Files.writeString(m3u.toPath(), "#MIDRA: --loop\n" + midi.getAbsolutePath() + "\n");

        assertFalse(common.loop);
        parser.parse(List.of(m3u), common);

        assertTrue(common.loop, "#MIDRA: --loop should set loop=true");
    }

    @Test void testM3uLoopDirectiveShortFlag(@TempDir Path tempDir) throws Exception
    {
        File midi = createTestMidi(tempDir, "track.mid");
        File m3u = tempDir.resolve("playlist.m3u").toFile();
        Files.writeString(m3u.toPath(), "#MIDRA: -r\n" + midi.getAbsolutePath() + "\n");

        assertFalse(common.loop);
        parser.parse(List.of(m3u), common);

        assertTrue(common.loop, "#MIDRA: -r should set loop=true");
    }

    @Test void testM3uRecursiveDirectiveLongFlag(@TempDir Path tempDir) throws Exception
    {
        File midi = createTestMidi(tempDir, "track.mid");
        File m3u = tempDir.resolve("playlist.m3u").toFile();
        Files.writeString(m3u.toPath(), "#MIDRA: --recursive\n" + midi.getAbsolutePath() + "\n");

        assertFalse(common.recursive);
        parser.parse(List.of(m3u), common);

        assertTrue(common.recursive, "#MIDRA: --recursive should set recursive=true");
    }

    @Test void testM3uRecursiveDirectiveShortFlag(@TempDir Path tempDir) throws Exception
    {
        File midi = createTestMidi(tempDir, "track.mid");
        File m3u = tempDir.resolve("playlist.m3u").toFile();
        Files.writeString(m3u.toPath(), "#MIDRA: -R\n" + midi.getAbsolutePath() + "\n");

        assertFalse(common.recursive);
        parser.parse(List.of(m3u), common);

        assertTrue(common.recursive, "#MIDRA: -R should set recursive=true");
    }

    // ── M3U key-value directives ────────────────────────────────────────────────

    @Test void testM3uVolumeDirective(@TempDir Path tempDir) throws Exception
    {
        File midi = createTestMidi(tempDir, "track.mid");
        File m3u = tempDir.resolve("playlist.m3u").toFile();
        Files.writeString(m3u.toPath(), "#MIDRA: --volume 75\n" + midi.getAbsolutePath() + "\n");

        assertEquals(100, common.volume);
        parser.parse(List.of(m3u), common);

        assertEquals(75, common.volume, "#MIDRA: --volume 75 should set volume=75");
    }

    @Test void testM3uVolumeDirectiveEqualsForm(@TempDir Path tempDir) throws Exception
    {
        File midi = createTestMidi(tempDir, "track.mid");
        File m3u = tempDir.resolve("playlist.m3u").toFile();
        Files.writeString(m3u.toPath(), "#MIDRA: --volume=60\n" + midi.getAbsolutePath() + "\n");

        parser.parse(List.of(m3u), common);

        assertEquals(60, common.volume);
    }

    @Test void testM3uVolumeDirectiveShortFlag(@TempDir Path tempDir) throws Exception
    {
        File midi = createTestMidi(tempDir, "track.mid");
        File m3u = tempDir.resolve("playlist.m3u").toFile();
        Files.writeString(m3u.toPath(), "#MIDRA: -v 80\n" + midi.getAbsolutePath() + "\n");

        parser.parse(List.of(m3u), common);

        assertEquals(80, common.volume, "#MIDRA: -v 80 should set volume=80");
    }

    @Test void testM3uSpeedDirective(@TempDir Path tempDir) throws Exception
    {
        File midi = createTestMidi(tempDir, "track.mid");
        File m3u = tempDir.resolve("playlist.m3u").toFile();
        Files.writeString(m3u.toPath(), "#MIDRA: --speed 1.5\n" + midi.getAbsolutePath() + "\n");

        assertEquals(1.0, common.speed);
        parser.parse(List.of(m3u), common);

        assertEquals(1.5, common.speed, 0.001, "#MIDRA: --speed 1.5 should set speed=1.5");
    }

    @Test void testM3uSpeedDirectiveEqualsForm(@TempDir Path tempDir) throws Exception
    {
        File midi = createTestMidi(tempDir, "track.mid");
        File m3u = tempDir.resolve("playlist.m3u").toFile();
        Files.writeString(m3u.toPath(), "#MIDRA: -x=2.0\n" + midi.getAbsolutePath() + "\n");

        parser.parse(List.of(m3u), common);

        assertEquals(2.0, common.speed, 0.001);
    }

    @Test void testM3uMultipleDirectives(@TempDir Path tempDir) throws Exception
    {
        File midi = createTestMidi(tempDir, "track.mid");
        File m3u = tempDir.resolve("playlist.m3u").toFile();
        Files.writeString(
            m3u.toPath(), "#MIDRA: --shuffle --loop --volume 50\n" + midi.getAbsolutePath() + "\n");

        parser.parse(List.of(m3u), common);

        assertTrue(common.shuffle, "shuffle should be set");
        assertTrue(common.loop, "loop should be set");
        assertEquals(50, common.volume, "volume should be 50");
    }

    // ── M3U comment and blank line handling ─────────────────────────────────────

    @Test void testM3uIgnoresComments(@TempDir Path tempDir) throws Exception
    {
        File midi = createTestMidi(tempDir, "track.mid");
        File m3u = tempDir.resolve("playlist.m3u").toFile();
        Files.writeString(
            m3u.toPath(), "#EXTM3U\n# This is a comment\n\n" + midi.getAbsolutePath() + "\n");

        List<File> result = parser.parse(List.of(m3u), common);

        assertEquals(1, result.size());
    }

    @Test void testM3uRelativePaths(@TempDir Path tempDir) throws Exception
    {
        createTestMidi(tempDir, "track.mid");
        File m3u = tempDir.resolve("playlist.m3u").toFile();
        Files.writeString(m3u.toPath(), "track.mid\n");

        List<File> result = parser.parse(List.of(m3u), common);

        assertEquals(1, result.size());
        assertTrue(result.get(0).exists());
    }

    @Test void testM3uMissingTrackSkipped(@TempDir Path tempDir) throws Exception
    {
        File m3u = tempDir.resolve("playlist.m3u").toFile();
        Files.writeString(m3u.toPath(), "nonexistent.mid\n");

        List<File> result = parser.parse(List.of(m3u), common);

        assertEquals(0, result.size());
    }

    @Test void testTxtPlaylistExtension(@TempDir Path tempDir) throws Exception
    {
        File midi = createTestMidi(tempDir, "track.mid");
        File txt = tempDir.resolve("playlist.txt").toFile();
        Files.writeString(txt.toPath(), "#MIDRA: --shuffle\n" + midi.getAbsolutePath() + "\n");

        parser.parse(List.of(txt), common);

        assertTrue(common.shuffle, ".txt playlists should also process #MIDRA: directives");
    }

    // ── Utility ─────────────────────────────────────────────────────────────────

    private File createTestMidi(Path tempDir, String fileName) throws Exception
    {
        File midiFile = tempDir.resolve(fileName).toFile();
        Sequence seq = new Sequence(Sequence.PPQ, 24);
        Track t = seq.createTrack();
        t.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 60, 64), 0));
        t.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, 60, 0), 24));

        try (FileOutputStream fos = new FileOutputStream(midiFile))
        {
            MidiSystem.write(seq, 1, fos);
        }
        return midiFile;
    }
}

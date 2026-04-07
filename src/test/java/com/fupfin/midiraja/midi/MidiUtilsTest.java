/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.midi;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.Sequence;
import javax.sound.midi.Track;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MidiUtilsTest
{
    @TempDir
    Path tempDir;

    // ── isMidiFile() ──────────────────────────────────────────────────────────

    @Test
    void isMidiFile_valid_MThd_header_returns_true() throws IOException
    {
        Path file = tempDir.resolve("test.mid");
        Files.write(file, new byte[] { 'M', 'T', 'h', 'd', 0, 0, 0, 6 });

        assertTrue(MidiUtils.isMidiFile(file.toFile()));
    }

    @Test
    void isMidiFile_wrong_magic_returns_false() throws IOException
    {
        Path file = tempDir.resolve("not_midi.bin");
        Files.write(file, new byte[] { 'R', 'I', 'F', 'F' });

        assertFalse(MidiUtils.isMidiFile(file.toFile()));
    }

    @Test
    void isMidiFile_only_3_bytes_returns_false() throws IOException
    {
        Path file = tempDir.resolve("short.bin");
        Files.write(file, new byte[] { 'M', 'T', 'h' });

        assertFalse(MidiUtils.isMidiFile(file.toFile()));
    }

    @Test
    void isMidiFile_empty_file_returns_false() throws IOException
    {
        Path file = tempDir.resolve("empty.bin");
        Files.write(file, new byte[0]);

        assertFalse(MidiUtils.isMidiFile(file.toFile()));
    }

    @Test
    void isMidiFile_nonexistent_file_returns_false()
    {
        File nonexistent = tempDir.resolve("ghost.mid").toFile();

        assertFalse(MidiUtils.isMidiFile(nonexistent));
    }

    @Test
    void isMidiFile_partial_match_only_MTh_plus_wrong_byte_returns_false() throws IOException
    {
        Path file = tempDir.resolve("partial.bin");
        Files.write(file, new byte[] { 'M', 'T', 'h', 'x' });

        assertFalse(MidiUtils.isMidiFile(file.toFile()));
    }

    // ── loadSequence() ────────────────────────────────────────────────────────

    @Test
    void loadSequence_invalid_header_throws_InvalidMidiDataException() throws IOException
    {
        Path file = tempDir.resolve("bad.mid");
        Files.write(file, new byte[] { 'R', 'I', 'F', 'F', 0, 0, 0, 0 });

        var ex = assertThrows(InvalidMidiDataException.class,
            () -> MidiUtils.loadSequence(file.toFile()));
        assertTrue(ex.getMessage().contains(file.toFile().getPath()),
            "exception message must include file path");
    }

    @Test
    void loadSequence_nonexistent_file_throws_InvalidMidiDataException()
    {
        // isMidiFile() returns false for nonexistent files (catches IOException internally),
        // so loadSequence() throws InvalidMidiDataException before reaching MidiSystem.
        File nonexistent = tempDir.resolve("ghost.mid").toFile();

        assertThrows(InvalidMidiDataException.class, () -> MidiUtils.loadSequence(nonexistent));
    }

    // ── extractSequenceTitle() ────────────────────────────────────────────────

    @Test
    void extractSequenceTitle_returns_track_name_meta_type_3() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        Track track = seq.createTrack();
        addTrackName(track, "My Song");

        assertEquals("My Song", MidiUtils.extractSequenceTitle(seq));
    }

    @Test
    void extractSequenceTitle_no_meta_message_returns_null() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack(); // empty track

        assertNull(MidiUtils.extractSequenceTitle(seq));
    }

    @Test
    void extractSequenceTitle_whitespace_only_returns_null() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        Track track = seq.createTrack();
        addTrackName(track, "   ");

        assertNull(MidiUtils.extractSequenceTitle(seq));
    }

    @Test
    void extractSequenceTitle_empty_data_returns_null() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        Track track = seq.createTrack();
        var meta = new MetaMessage(0x03, new byte[0], 0);
        track.add(new MidiEvent(meta, 0));

        assertNull(MidiUtils.extractSequenceTitle(seq));
    }

    @Test
    void extractSequenceTitle_returns_first_valid_title() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        Track track1 = seq.createTrack();
        addTrackName(track1, "First Title");
        Track track2 = seq.createTrack();
        addTrackName(track2, "Second Title");

        assertEquals("First Title", MidiUtils.extractSequenceTitle(seq));
    }

    @Test
    void extractSequenceTitle_trims_surrounding_whitespace() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        Track track = seq.createTrack();
        addTrackName(track, "  Padded  ");

        assertEquals("Padded", MidiUtils.extractSequenceTitle(seq));
    }

    @Test
    void extractSequenceTitle_no_tracks_returns_null() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        // No tracks added

        assertNull(MidiUtils.extractSequenceTitle(seq));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static void addTrackName(Track track, String name) throws Exception
    {
        byte[] data = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var meta = new MetaMessage(0x03, data, data.length);
        track.add(new MidiEvent(meta, 0));
    }
}

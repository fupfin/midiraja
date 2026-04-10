/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.export.midi;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.Set;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MidiExporterTest
{
    @TempDir
    File tempDir;

    /** Writes a minimal one-note MIDI sequence to a temp file and returns it. */
    private File createMidiFile() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack().add(
                new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 69, 100), 0));
        File file = new File(tempDir, "test.mid");
        MidiSystem.write(seq, 1, file);
        return file;
    }

    // ── Export to OutputStream ────────────────────────────────────────────────

    @Test
    void export_toOutputStream_writesMidiHeader() throws Exception
    {
        File input = createMidiFile();
        var out = new ByteArrayOutputStream();
        new MidiExporter().export(input, out, Set.of());
        byte[] data = out.toByteArray();

        // MIDI file magic: "MThd"
        assertEquals('M', data[0]);
        assertEquals('T', data[1]);
        assertEquals('h', data[2]);
        assertEquals('d', data[3]);
    }

    @Test
    void export_toOutputStream_producesNonEmptyOutput() throws Exception
    {
        File input = createMidiFile();
        var out = new ByteArrayOutputStream();
        new MidiExporter().export(input, out, Set.of());
        assertTrue(out.size() > 0, "Output must not be empty");
    }

    // ── Export to File ────────────────────────────────────────────────────────

    @Test
    void export_toFile_createsMidiFile() throws Exception
    {
        File input = createMidiFile();
        File output = new File(tempDir, "out.mid");
        new MidiExporter().export(input, output, Set.of());

        assertTrue(output.exists(), "Output file must be created");
        assertTrue(output.length() > 0, "Output file must not be empty");

        byte[] data = Files.readAllBytes(output.toPath());
        assertEquals('M', data[0]);
        assertEquals('T', data[1]);
        assertEquals('h', data[2]);
        assertEquals('d', data[3]);
    }

    // ── Error handling ────────────────────────────────────────────────────────

    @Test
    void export_nonExistentFile_throwsRuntimeException()
    {
        File missing = new File(tempDir, "does-not-exist.mid");
        var out = new ByteArrayOutputStream();
        assertThrows(RuntimeException.class,
                () -> new MidiExporter().export(missing, out, Set.of()));
    }
}

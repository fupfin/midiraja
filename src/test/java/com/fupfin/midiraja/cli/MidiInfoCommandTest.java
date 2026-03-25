/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import javax.sound.midi.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import picocli.CommandLine;

import com.fupfin.midiraja.MidirajaCommand;

class MidiInfoCommandTest {

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Creates a minimal valid MIDI file. */
    private static java.io.File createMidi(Path tempDir, String name) throws Exception {
        java.io.File f = tempDir.resolve(name).toFile();
        Sequence seq = new Sequence(Sequence.PPQ, 480);
        Track t = seq.createTrack();
        t.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 60, 64), 0L));
        t.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, 60, 0), 480L));
        try (var fos = new FileOutputStream(f)) { MidiSystem.write(seq, 1, fos); }
        return f;
    }

    /** Creates a MIDI file with title, copyright, instrument, and lyrics meta. */
    private static java.io.File createRichMidi(Path tempDir, String name) throws Exception {
        java.io.File f = tempDir.resolve(name).toFile();
        Sequence seq = new Sequence(Sequence.PPQ, 480);
        Track t = seq.createTrack();

        byte[] titleData = "My Song".getBytes(StandardCharsets.UTF_8);
        MetaMessage title = new MetaMessage(); title.setMessage(0x03, titleData, titleData.length);
        t.add(new MidiEvent(title, 0L));

        byte[] cpData = "(c) 1991 LucasArts".getBytes(StandardCharsets.UTF_8);
        MetaMessage copyright = new MetaMessage(); copyright.setMessage(0x02, cpData, cpData.length);
        t.add(new MidiEvent(copyright, 0L));

        byte[] instrData = "Piano".getBytes(StandardCharsets.UTF_8);
        MetaMessage instr = new MetaMessage(); instr.setMessage(0x07, instrData, instrData.length);
        t.add(new MidiEvent(instr, 0L));

        byte[] lyricData = "Hello world".getBytes(StandardCharsets.UTF_8);
        MetaMessage lyric = new MetaMessage(); lyric.setMessage(0x05, lyricData, lyricData.length);
        t.add(new MidiEvent(lyric, 100L));

        t.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, 60, 0), 960L));
        try (var fos = new FileOutputStream(f)) { MidiSystem.write(seq, 1, fos); }
        return f;
    }

    private record RunResult(String out, String err, int exitCode) {}

    private static RunResult run(String... args) {
        var outBytes = new ByteArrayOutputStream();
        var errBytes = new ByteArrayOutputStream();
        CommandLine cmd = new CommandLine(new MidirajaCommand());
        cmd.setOut(new PrintWriter(outBytes, true));
        cmd.setErr(new PrintWriter(errBytes, true));
        int code = cmd.execute(args);
        return new RunResult(outBytes.toString(), errBytes.toString(), code);
    }

    // ── text output ───────────────────────────────────────────────────────────

    @Test void textOutput_durationAndTotalPresent(@TempDir Path tmp) throws Exception {
        java.io.File f = createMidi(tmp, "song.mid");
        RunResult r = run("midi-info", f.toString());
        assertTrue(r.out().contains("Duration:"), "should contain Duration line, got:\n" + r.out());
        assertTrue(r.out().contains("Total:"), "should contain Total line, got:\n" + r.out());
    }

    @Test void textOutput_richMetaShown(@TempDir Path tmp) throws Exception {
        java.io.File f = createRichMidi(tmp, "rich.mid");
        RunResult r = run("midi-info", f.toString());
        assertTrue(r.out().contains("My Song"), "title should appear");
        assertTrue(r.out().contains("(c) 1991 LucasArts"), "copyright should appear");
        assertTrue(r.out().contains("Piano"), "instrument should appear");
        assertTrue(r.out().contains("Hello world"), "lyrics should appear");
    }

    @Test void textOutput_noMetadata_showsPlaceholder(@TempDir Path tmp) throws Exception {
        java.io.File f = createMidi(tmp, "bare.mid");
        RunResult r = run("midi-info", f.toString());
        assertTrue(r.out().contains("(no metadata)"), "bare file should show placeholder");
    }

    @Test void textOutput_twoFiles_totalCountCorrect(@TempDir Path tmp) throws Exception {
        java.io.File f1 = createMidi(tmp, "a.mid");
        java.io.File f2 = createMidi(tmp, "b.mid");
        RunResult r = run("midi-info", f1.toString(), f2.toString());
        assertTrue(r.out().contains("(2 files)"), "should report 2 files, got:\n" + r.out());
    }

    @Test void textOutput_failedFilesExcludedFromTotal(@TempDir Path tmp) throws Exception {
        java.io.File good = createMidi(tmp, "good.mid");
        RunResult r = run("midi-info", good.toString(), "/nonexistent/missing.mid");
        assertTrue(r.out().contains("(1 file)"),
                "failed files should not count in total, got:\n" + r.out());
        assertTrue(r.err().contains("Warning:"), "should print warning for failed file");
    }

    // ── CSV / TSV output ──────────────────────────────────────────────────────

    @Test void csvOutput_headerRowPresent(@TempDir Path tmp) throws Exception {
        java.io.File f = createMidi(tmp, "song.mid");
        RunResult r = run("midi-info", "--format", "csv", f.toString());
        String firstLine = r.out().split("\\R", 2)[0];
        assertEquals("path,duration_sec,title,copyright,instruments,lyrics", firstLine);
    }

    @Test void csvOutput_dataRowCount(@TempDir Path tmp) throws Exception {
        java.io.File f = createMidi(tmp, "song.mid");
        RunResult r = run("midi-info", "--format", "csv", f.toString());
        long lineCount = r.out().lines().filter(l -> !l.isBlank()).count();
        assertEquals(2, lineCount, "header + 1 data row expected, got:\n" + r.out());
    }

    @Test void csvOutput_durationFieldIsNumeric(@TempDir Path tmp) throws Exception {
        java.io.File f = createMidi(tmp, "song.mid");
        RunResult r = run("midi-info", "--format", "csv", f.toString());
        assertTrue(r.out().matches("(?s).*\\d+\\.\\d.*"),
                "CSV output should contain a decimal number for duration, got:\n" + r.out());
    }

    @Test void tsvOutput_usesTabSeparator(@TempDir Path tmp) throws Exception {
        java.io.File f = createMidi(tmp, "song.mid");
        RunResult r = run("midi-info", "--format", "tsv", f.toString());
        assertTrue(r.out().contains("\t"), "TSV output should contain tabs");
    }

    @Test void csvOutput_noTotalLine(@TempDir Path tmp) throws Exception {
        java.io.File f = createMidi(tmp, "song.mid");
        RunResult r = run("midi-info", "--format", "csv", f.toString());
        assertFalse(r.out().contains("Total:"), "CSV mode should not include Total line");
    }

    // ── csvField helper ───────────────────────────────────────────────────────

    @Test void csvField_quoting() {
        assertEquals("\"a,b\"", MidiInfoCommand.csvField("a,b", ','));
        assertEquals("\"a\"\"b\"", MidiInfoCommand.csvField("a\"b", ','));
        assertEquals("hello", MidiInfoCommand.csvField("hello", ','));
        assertEquals("", MidiInfoCommand.csvField("", ','));
        assertEquals("\"a\nb\"", MidiInfoCommand.csvField("a\nb", ','));
        assertEquals("\"a\tb\"", MidiInfoCommand.csvField("a\tb", '\t'));
    }

    // ── error handling ────────────────────────────────────────────────────────

    @Test void exitCode1_onMissingFile() {
        RunResult r = run("midi-info", "/nonexistent/path/song.mid");
        assertEquals(1, r.exitCode());
    }

    @Test void exitCode1_onEmptyDirectory(@TempDir Path tmp) {
        RunResult r = run("midi-info", tmp.toString());
        assertEquals(1, r.exitCode());
        assertTrue(r.err().contains("No MIDI files found."),
                "should print 'No MIDI files found.' to stderr");
    }

    // ── formatDuration ────────────────────────────────────────────────────────

    @Test void formatDuration_roundTrip() {
        assertEquals("0:00", MidiInfoCommand.formatDuration(0L));
        assertEquals("0:01", MidiInfoCommand.formatDuration(1_000_000L));
        assertEquals("1:00", MidiInfoCommand.formatDuration(60_000_000L));
        assertEquals("3:42", MidiInfoCommand.formatDuration(222_000_000L));
    }
}

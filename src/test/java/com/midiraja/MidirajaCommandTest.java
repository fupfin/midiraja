/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.midiraja;

import static org.junit.jupiter.api.Assertions.*;

import com.midiraja.io.MockTerminalIO;
import com.midiraja.midi.MidiOutProvider;
import com.midiraja.midi.MidiPort;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.sound.midi.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class MidirajaCommandTest
{
    private MidirajaCommand app;
    private MockMidiProvider provider;
    private ByteArrayOutputStream outBytes;
    private ByteArrayOutputStream errBytes;
    private PrintStream out;
    private PrintStream err;

    static class MockMidiProvider implements MidiOutProvider
    {
        @Override public List<MidiPort> getOutputPorts()
        {
            return List.of(new MidiPort(0, "Mock Port"));
        }

        @Override public void openPort(int portIndex) throws Exception
        {
        }

        @Override public void sendMessage(byte[] data) throws Exception
        {
        }

        @Override public void closePort()
        {
        }

        @Override public void panic()
        {
        }
    }

    @BeforeEach void setUp()
    {
        app = new MidirajaCommand();
        provider = new MockMidiProvider();
        outBytes = new ByteArrayOutputStream();
        errBytes = new ByteArrayOutputStream();
        out = new PrintStream(outBytes);
        err = new PrintStream(errBytes);
        app.setTestEnvironment(provider, new MockTerminalIO(), out, err);
    }

    // ── Root command: native MIDI playback ──────────────────────────────────────

    @Test void testPlayMidiWithVolumeScaling(@TempDir Path tempDir) throws Exception
    {
        File midiFile = createTestMidi(tempDir, "test.mid");

        CommandLine cmd = new CommandLine(app);
        int exitCode = cmd.execute(midiFile.getAbsolutePath(), "--volume", "50", "--port", "0");

        assertEquals(0, exitCode);
    }

    @Test void testPlayMidiWithSpeedOption(@TempDir Path tempDir) throws Exception
    {
        File midiFile = createTestMidi(tempDir, "test.mid");

        CommandLine cmd = new CommandLine(app);
        int exitCode = cmd.execute(midiFile.getAbsolutePath(), "--speed", "1.5", "--port", "0");

        assertEquals(0, exitCode);
    }

    @Test void testNoFilesReturnsError()
    {
        CommandLine cmd = new CommandLine(app);
        int exitCode = cmd.execute();

        assertEquals(1, exitCode);
        assertTrue(errBytes.toString(java.nio.charset.StandardCharsets.UTF_8).contains("No MIDI files specified"));
    }

    @Test void testShuffleFlag(@TempDir Path tempDir) throws Exception
    {
        File midiFile = createTestMidi(tempDir, "test.mid");

        CommandLine cmd = new CommandLine(app);
        int exitCode = cmd.execute(midiFile.getAbsolutePath(), "--shuffle", "--port", "0");

        assertEquals(0, exitCode);
    }

    @Test void testShortShuffleFlag(@TempDir Path tempDir) throws Exception
    {
        File midiFile = createTestMidi(tempDir, "test.mid");

        CommandLine cmd = new CommandLine(app);
        int exitCode = cmd.execute(midiFile.getAbsolutePath(), "-s", "--port", "0");

        assertEquals(0, exitCode);
    }

    @Test void testStartTimeFlag(@TempDir Path tempDir) throws Exception
    {
        File midiFile = createTestMidi(tempDir, "test.mid");

        CommandLine cmd = new CommandLine(app);
        int exitCode = cmd.execute(midiFile.getAbsolutePath(), "-S", "00:05", "--port", "0");

        assertEquals(0, exitCode);
    }

    // ── Port index lookup ───────────────────────────────────────────────────────

    @Test void testFindPortIndex()
    {
        List<MidiPort> ports = new ArrayList<>();
        ports.add(new MidiPort(0, "IAC Driver Bus 1"));
        ports.add(new MidiPort(1, "FluidSynth virtual port"));
        ports.add(new MidiPort(2, "Real Time Port"));

        // Exact match by index
        assertEquals(0, app.findPortIndex(ports, "0"));
        assertEquals(1, app.findPortIndex(ports, "1"));

        // Partial name match
        assertEquals(1, app.findPortIndex(ports, "fluid"));
        assertEquals(1, app.findPortIndex(ports, "FLUIDSYNTH"));
        assertEquals(2, app.findPortIndex(ports, "Real"));

        // Ambiguous match
        assertEquals(-1, app.findPortIndex(ports, "Port"));

        // No match
        assertEquals(-1, app.findPortIndex(ports, "NonExistent"));
    }

    // ── Help output ─────────────────────────────────────────────────────────────

    @Test void testRootHelpMentionsSubcommands()
    {
        CommandLine cmd = new CommandLine(app);
        cmd.setOut(new java.io.PrintWriter(out, true, java.nio.charset.StandardCharsets.UTF_8));
        cmd.execute("--help");

        String helpOutput = outBytes.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(helpOutput.contains("opl"), "Help should mention 'opl' subcommand");
        assertTrue(helpOutput.contains("opn"), "Help should mention 'opn' subcommand");
        assertTrue(helpOutput.contains("munt"), "Help should mention 'munt' subcommand");
        assertTrue(helpOutput.contains("fluid"), "Help should mention 'fluid' subcommand");
        assertTrue(helpOutput.contains("java"), "Help should mention 'java' subcommand");
        assertTrue(helpOutput.contains("ports"), "Help should mention 'ports' subcommand");
    }

    @Test void testOplHelpShowsEmulatorIds()
    {
        CommandLine cmd = new CommandLine(app);
        cmd.setOut(new java.io.PrintWriter(out, true, java.nio.charset.StandardCharsets.UTF_8));
        cmd.execute("opl", "--help");

        String helpOutput = outBytes.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(helpOutput.contains("OPL"), "opl help should mention OPL");
        assertTrue(helpOutput.contains("--bank"), "opl help should mention --bank");
    }

    @Test void testOpnHelpShowsEmulatorIds()
    {
        CommandLine cmd = new CommandLine(app);
        cmd.setOut(new java.io.PrintWriter(out, true, java.nio.charset.StandardCharsets.UTF_8));
        cmd.execute("opn", "--help");

        String helpOutput = outBytes.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(helpOutput.contains("OPN2"), "opn help should mention OPN2");
    }

    @Test void testMuntHelp()
    {
        CommandLine cmd = new CommandLine(app);
        cmd.setOut(new java.io.PrintWriter(out, true, java.nio.charset.StandardCharsets.UTF_8));
        cmd.execute("munt", "--help");

        String helpOutput = outBytes.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(helpOutput.contains("MT-32"), "munt help should mention MT-32");
    }

    @Test void testFluidHelp()
    {
        CommandLine cmd = new CommandLine(app);
        cmd.setOut(new java.io.PrintWriter(out, true, java.nio.charset.StandardCharsets.UTF_8));
        cmd.execute("fluid", "--help");

        String helpOutput = outBytes.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(helpOutput.contains("FluidSynth"), "fluid help should mention FluidSynth");
    }

    @Test void testJavaHelp()
    {
        CommandLine cmd = new CommandLine(app);
        cmd.setOut(new java.io.PrintWriter(out, true, java.nio.charset.StandardCharsets.UTF_8));
        cmd.execute("java", "--help");

        String helpOutput = outBytes.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(helpOutput.contains("Java"), "java help should mention Java");
    }

    @Test void testPortsHelp()
    {
        CommandLine cmd = new CommandLine(app);
        cmd.setOut(new java.io.PrintWriter(out, true, java.nio.charset.StandardCharsets.UTF_8));
        cmd.execute("ports", "--help");

        String helpOutput = outBytes.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(helpOutput.contains("List"), "ports help should mention listing ports");
    }

    // ── Subcommand: missing files argument ──────────────────────────────────────

    @Test void testOplWithNoFilesReturnsError()
    {
        CommandLine cmd = new CommandLine(app);
        cmd.setErr(new java.io.PrintWriter(err, true, java.nio.charset.StandardCharsets.UTF_8));
        int exitCode = cmd.execute("opl");

        // picocli returns 2 for missing required positional parameters
        assertEquals(2, exitCode);
    }

    @Test void testOpnWithNoFilesReturnsError()
    {
        CommandLine cmd = new CommandLine(app);
        cmd.setErr(new java.io.PrintWriter(err, true, java.nio.charset.StandardCharsets.UTF_8));
        int exitCode = cmd.execute("opn");

        assertEquals(2, exitCode);
    }

    @Test void testMuntWithNoArgsReturnsError()
    {
        CommandLine cmd = new CommandLine(app);
        cmd.setErr(new java.io.PrintWriter(err, true, java.nio.charset.StandardCharsets.UTF_8));
        int exitCode = cmd.execute("munt");

        assertEquals(2, exitCode);
    }

    @Test void testFluidWithNoArgsReturnsError()
    {
        CommandLine cmd = new CommandLine(app);
        cmd.setErr(new java.io.PrintWriter(err, true, java.nio.charset.StandardCharsets.UTF_8));
        int exitCode = cmd.execute("fluid");

        assertEquals(2, exitCode);
    }

    @Test void testJavaWithNoFilesReturnsError()
    {
        CommandLine cmd = new CommandLine(app);
        cmd.setErr(new java.io.PrintWriter(err, true, java.nio.charset.StandardCharsets.UTF_8));
        int exitCode = cmd.execute("java");

        assertEquals(2, exitCode);
    }

    // ── UI mode flags ───────────────────────────────────────────────────────────

    @Test void testClassicUiFlag(@TempDir Path tempDir) throws Exception
    {
        File midiFile = createTestMidi(tempDir, "test.mid");

        CommandLine cmd = new CommandLine(app);
        int exitCode = cmd.execute(midiFile.getAbsolutePath(), "--classic", "--port", "0");

        assertEquals(0, exitCode);
    }

    @Test void testMiniUiFlag(@TempDir Path tempDir) throws Exception
    {
        File midiFile = createTestMidi(tempDir, "test.mid");

        CommandLine cmd = new CommandLine(app);
        int exitCode = cmd.execute(midiFile.getAbsolutePath(), "--mini", "--port", "0");

        assertEquals(0, exitCode);
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

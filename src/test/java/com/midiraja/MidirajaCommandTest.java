/*
 * Copyright (c) 2026, Park, Sungchul
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.midiraja;

import com.midiraja.midi.MidiOutProvider;
import com.midiraja.midi.MidiPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import javax.sound.midi.*;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MidirajaCommandTest {

    private MidirajaCommand app;
    private MockMidiProvider provider;

    static class MockMidiProvider implements MidiOutProvider {
        @Override public List<MidiPort> getOutputPorts() { 
            return List.of(new MidiPort(0, "Mock Port")); 
        }
        @Override public void openPort(int portIndex) throws Exception { }
        @Override public void sendMessage(byte[] data) throws Exception { }
        @Override public void closePort() { }
    }

    @BeforeEach
    void setUp() {
        app = new MidirajaCommand();
        provider = new MockMidiProvider();
        app.setProvider(provider);
        app.setTerminalIO(new com.midiraja.io.MockTerminalIO());
    }

    @Test
    void testPlayMidiWithVolumeScaling(@TempDir Path tempDir) throws Exception {
        File midiFile = createTestMidi(tempDir, "test.mid");
        
        CommandLine cmd = new CommandLine(app);
        app.setTestMode(true);
        int exitCode = cmd.execute(midiFile.getAbsolutePath(), "--volume", "50", "--port", "0");
        
        assertEquals(0, exitCode);
    }

    @Test
    void testFindPortIndex() {
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

    private File createTestMidi(Path tempDir, String fileName) throws Exception {
        File midiFile = tempDir.resolve(fileName).toFile();
        Sequence seq = new Sequence(Sequence.PPQ, 24);
        Track t = seq.createTrack();
        t.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 60, 64), 0));
        t.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, 60, 0), 24));
        
        try (FileOutputStream fos = new FileOutputStream(midiFile)) {
            MidiSystem.write(seq, 1, fos);
        }
        return midiFile;
    }
}
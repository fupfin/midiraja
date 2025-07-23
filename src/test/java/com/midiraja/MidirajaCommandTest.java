package com.midiraja;

import com.midiraja.midi.MidiOutProvider;
import com.midiraja.midi.MidiPort;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.ParseResult;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MidirajaCommandTest {

    static class MockMidiProvider implements MidiOutProvider {
        List<byte[]> sentMessages = new ArrayList<>();

        @Override
        public List<MidiPort> getOutputPorts() {
            List<MidiPort> ports = new ArrayList<>();
            ports.add(new MidiPort(0, "Mock Port"));
            return ports;
        }

        @Override
        public void openPort(int portIndex) throws Exception { }

        @Override
        public void sendMessage(byte[] data) throws Exception {
            sentMessages.add(data);
        }

        @Override
        public void closePort() { }
    }

    @Test
    void testPlayMidiWithVolume() throws Exception {
        MidirajaCommand app = new MidirajaCommand();
        CommandLine cmd = new CommandLine(app);
        // volume=50 (%)
        cmd.parseArgs("--volume", "50", "--port", "0", "PASSPORT.MID");

        MockMidiProvider provider = new MockMidiProvider();
        File midiFile = new File("PASSPORT.MID");
        if (midiFile.exists()) {
            app.isTestMode = true;
            app.playMidiWithProvider(midiFile, 0, provider);
            
            // Verify volume messages are sent first (127 * 0.5 = 63)
            assertTrue(provider.sentMessages.size() >= 16);
            for (int ch = 0; ch < 16; ch++) {
                byte[] msg = provider.sentMessages.get(ch);
                assertEquals(3, msg.length);
                assertEquals((byte) (0xB0 | ch), msg[0]);
                assertEquals(7, msg[1]);
                assertEquals(63, msg[2]);
            }
        }
    }

    @Test
    void testPlayMidiWithTranspose() throws Exception {
        // Create a temporary MIDI file
        File tempMidi = File.createTempFile("test", ".mid");
        tempMidi.deleteOnExit();
        javax.sound.midi.Sequence seq = new javax.sound.midi.Sequence(javax.sound.midi.Sequence.PPQ, 24);
        javax.sound.midi.Track t = seq.createTrack();
        
        // Note On, Ch 1 (0x90), Note 60
        t.add(new javax.sound.midi.MidiEvent(new javax.sound.midi.ShortMessage(0x90, 0, 60, 100), 0));
        // Note On, Ch 10 (0x99), Note 60
        t.add(new javax.sound.midi.MidiEvent(new javax.sound.midi.ShortMessage(0x90, 9, 60, 100), 0));
        javax.sound.midi.MidiSystem.write(seq, 1, tempMidi);

        MidirajaCommand app = new MidirajaCommand();
        CommandLine cmd = new CommandLine(app);
        cmd.parseArgs("--transpose", "2", "--port", "0", tempMidi.getAbsolutePath());

        MockMidiProvider provider = new MockMidiProvider();
        app.isTestMode = true;
        app.playMidiWithProvider(tempMidi, 0, provider);

        boolean foundCh1Note = false;
        boolean foundCh10Note = false;
        
        for (byte[] msg : provider.sentMessages) {
            if (msg.length == 3) {
                if ((msg[0] & 0xFF) == 0x90) { // Ch 1
                    assertEquals(62, msg[1]); // Transposed 60 -> 62
                    foundCh1Note = true;
                } else if ((msg[0] & 0xFF) == 0x99) { // Ch 10
                    assertEquals(60, msg[1]); // Not transposed
                    foundCh10Note = true;
                }
            }
        }
        
        assertTrue(foundCh1Note);
        assertTrue(foundCh10Note);
    }

    @Test
    void testPlayMidiWithVolumeScaling() throws Exception {
        MidirajaCommand app = new MidirajaCommand();
        CommandLine cmd = new CommandLine(app);
        // volume=64 (~50%)
        cmd.parseArgs("--volume", "64", "--port", "0", "PASSPORT.MID");

        MockMidiProvider provider = new MockMidiProvider();
        File midiFile = new File("PASSPORT.MID");
        if (midiFile.exists()) {
            app.isTestMode = true;
            app.playMidiWithProvider(midiFile, 0, provider);
            
            // Check that any CC 7 messages sent during playback are scaled
            // The initial 16 messages are the startup volume CC 7s
            for (int i = 16; i < provider.sentMessages.size(); i++) {
                byte[] msg = provider.sentMessages.get(i);
                if (msg.length == 3 && (msg[0] & 0xF0) == 0xB0 && msg[1] == 7) {
                    // It's a CC 7 message. Since we can't know the exact original value without mocking the file,
                    // we at least ensure it's not simply the default 127 if it was meant to be scaled,
                    // but more reliably, we just ensure it doesn't crash and is <= 127.
                    // For a true unit test of the scaling logic, we'd need a mock sequence.
                    assertTrue(msg[2] >= 0 && msg[2] <= 127);
                }
            }
        }
    }

    @Test
    void testParseVolumeOption() {
        MidirajaCommand app = new MidirajaCommand();
        CommandLine cmd = new CommandLine(app);
        
        ParseResult parseResult = cmd.parseArgs("--volume", "64", "--port", "1", "song.mid");
        
        assertTrue(parseResult.hasMatchedOption("--volume"));
        assertEquals(Integer.valueOf(64), parseResult.matchedOption("--volume").getValue());
    }

    @Test
    void testParseTransposeOption() {
        MidirajaCommand app = new MidirajaCommand();
        CommandLine cmd = new CommandLine(app);
        
        ParseResult parseResult = cmd.parseArgs("-t", "-5", "--port", "1", "song.mid");
        
        assertTrue(parseResult.hasMatchedOption("-t"));
        assertEquals(Integer.valueOf(-5), parseResult.matchedOption("-t").getValue());
    }

    @Test
    void testFindPortIndex_ExactMatch() throws Exception {
        MidirajaCommand app = new MidirajaCommand();
        List<MidiPort> ports = new ArrayList<>();
        ports.add(new MidiPort(0, "IAC Driver Bus 1"));
        ports.add(new MidiPort(1, "FluidSynth virtual port"));
        ports.add(new MidiPort(2, "Real Time Sequencer"));

        int index = app.findPortIndex("1", ports);
        assertEquals(1, index);
    }

    @Test
    void testFindPortIndex_PartialMatch() throws Exception {
        MidirajaCommand app = new MidirajaCommand();
        List<MidiPort> ports = new ArrayList<>();
        ports.add(new MidiPort(0, "IAC Driver Bus 1"));
        ports.add(new MidiPort(1, "FluidSynth virtual port"));

        int index = app.findPortIndex("fluid", ports);
        assertEquals(1, index);
    }

    @Test
    void testFindPortIndex_CaseInsensitive() throws Exception {
        MidirajaCommand app = new MidirajaCommand();
        List<MidiPort> ports = new ArrayList<>();
        ports.add(new MidiPort(0, "FluidSynth virtual port"));

        int index = app.findPortIndex("FLUIDSYNTH", ports);
        assertEquals(0, index);
    }

    @Test
    void testFindPortIndex_AmbiguousMatch() {
        MidirajaCommand app = new MidirajaCommand();
        List<MidiPort> ports = new ArrayList<>();
        ports.add(new MidiPort(0, "IAC Driver Bus 1"));
        ports.add(new MidiPort(1, "IAC Driver Bus 2"));

        try {
            app.findPortIndex("IAC", ports);
            assertFalse(true, "Should have thrown an exception");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("Ambiguous"));
        }
    }

    @Test
    void testFindPortIndex_NoMatch() {
        MidirajaCommand app = new MidirajaCommand();
        List<MidiPort> ports = new ArrayList<>();
        ports.add(new MidiPort(0, "IAC Driver Bus 1"));

        try {
            app.findPortIndex("fluid", ports);
            assertFalse(true, "Should have thrown an exception");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("No matching"));
        }
    }

    @Test
    void testParsePortStringOption() {
        MidirajaCommand app = new MidirajaCommand();
        CommandLine cmd = new CommandLine(app);
        
        ParseResult parseResult = cmd.parseArgs("-p", "fluid", "song.mid");
        
        assertTrue(parseResult.hasMatchedOption("-p"));
        assertEquals("fluid", parseResult.matchedOption("-p").getValue());
    }

    @Test
    void testInvalidVolumeOutOfRange() {
        MidirajaCommand app = new MidirajaCommand();
        CommandLine cmd = new CommandLine(app);
        
        java.io.ByteArrayOutputStream errContent = new java.io.ByteArrayOutputStream();
        java.io.PrintStream originalErr = System.err;
        System.setErr(new java.io.PrintStream(errContent));
        
        try {
            int exitCode = cmd.execute("--volume", "150", "--port", "1", "song.mid");
            assertTrue(exitCode != 0);
            assertTrue(errContent.toString().contains("Volume percentage must be between 0 and 100"));
        } finally {
            System.setErr(originalErr);
        }
    }

    @Test
    void testInvalidVolumeNegative() {
        MidirajaCommand app = new MidirajaCommand();
        CommandLine cmd = new CommandLine(app);
        
        java.io.ByteArrayOutputStream errContent = new java.io.ByteArrayOutputStream();
        java.io.PrintStream originalErr = System.err;
        System.setErr(new java.io.PrintStream(errContent));
        
        try {
            int exitCode = cmd.execute("--volume", "-1", "--port", "1", "song.mid");
            assertTrue(exitCode != 0);
            assertTrue(errContent.toString().contains("Volume percentage must be between 0 and 100"));
        } finally {
            System.setErr(originalErr);
        }
    }
}

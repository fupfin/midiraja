package com.midraja;

import com.midraja.midi.MidiOutProvider;
import com.midraja.midi.MidiPort;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.ParseResult;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MidrajaCommandTest {

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
        MidrajaCommand app = new MidrajaCommand();
        CommandLine cmd = new CommandLine(app);
        // volume=64
        cmd.parseArgs("--volume", "64", "--port", "0", "PASSPORT.MID");

        MockMidiProvider provider = new MockMidiProvider();
        File midiFile = new File("PASSPORT.MID");
        if (midiFile.exists()) {
            app.isTestMode = true;
            app.playMidiWithProvider(midiFile, 0, provider);
            
            // Verify volume messages are sent first
            assertTrue(provider.sentMessages.size() >= 16);
            for (int ch = 0; ch < 16; ch++) {
                byte[] msg = provider.sentMessages.get(ch);
                assertEquals(3, msg.length);
                assertEquals((byte) (0xB0 | ch), msg[0]);
                assertEquals(7, msg[1]);
                assertEquals(64, msg[2]);
            }
        }
    }

    @Test
    void testParseVolumeOption() {
        MidrajaCommand app = new MidrajaCommand();
        CommandLine cmd = new CommandLine(app);
        
        ParseResult parseResult = cmd.parseArgs("--volume", "64", "--port", "1", "song.mid");
        
        assertTrue(parseResult.hasMatchedOption("--volume"));
        assertEquals(Integer.valueOf(64), parseResult.matchedOption("--volume").getValue());
    }

    @Test
    void testParseTransposeOption() {
        MidrajaCommand app = new MidrajaCommand();
        CommandLine cmd = new CommandLine(app);
        
        ParseResult parseResult = cmd.parseArgs("-t", "-5", "--port", "1", "song.mid");
        
        assertTrue(parseResult.hasMatchedOption("-t"));
        assertEquals(Integer.valueOf(-5), parseResult.matchedOption("-t").getValue());
    }

    @Test
    void testInvalidVolumeOutOfRange() {
        MidrajaCommand app = new MidrajaCommand();
        CommandLine cmd = new CommandLine(app);
        
        java.io.ByteArrayOutputStream errContent = new java.io.ByteArrayOutputStream();
        java.io.PrintStream originalErr = System.err;
        System.setErr(new java.io.PrintStream(errContent));
        
        try {
            int exitCode = cmd.execute("--volume", "150", "--port", "1", "song.mid");
            assertTrue(exitCode != 0);
            assertTrue(errContent.toString().contains("Volume must be between 0 and 127"));
        } finally {
            System.setErr(originalErr);
        }
    }

    @Test
    void testInvalidVolumeNegative() {
        MidrajaCommand app = new MidrajaCommand();
        CommandLine cmd = new CommandLine(app);
        
        java.io.ByteArrayOutputStream errContent = new java.io.ByteArrayOutputStream();
        java.io.PrintStream originalErr = System.err;
        System.setErr(new java.io.PrintStream(errContent));
        
        try {
            int exitCode = cmd.execute("--volume", "-1", "--port", "1", "song.mid");
            assertTrue(exitCode != 0);
            assertTrue(errContent.toString().contains("Volume must be between 0 and 127"));
        } finally {
            System.setErr(originalErr);
        }
    }
}

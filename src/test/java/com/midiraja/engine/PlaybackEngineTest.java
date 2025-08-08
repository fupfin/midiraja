package com.midiraja.engine;

import com.midiraja.io.MockTerminalIO;
import com.midiraja.io.TerminalIO;
import com.midiraja.midi.MidiOutProvider;
import com.midiraja.midi.MidiPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sound.midi.Sequence;
import javax.sound.midi.Track;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlaybackEngineTest {
    private Sequence mockSequence;
    private MockMidiProvider mockProvider;
    private MockTerminalIO mockIO;

    static class MockMidiProvider implements MidiOutProvider {
        @Override public List<MidiPort> getOutputPorts() { return new ArrayList<>(); }
        @Override public void openPort(int portIndex) throws Exception { }
        @Override public void sendMessage(byte[] data) throws Exception { }
        @Override public void closePort() { }
    }

    @BeforeEach
    void setUp() throws Exception {
        mockSequence = new Sequence(Sequence.PPQ, 24);
        mockSequence.createTrack();
        mockProvider = new MockMidiProvider();
        mockIO = new MockTerminalIO();
    }

    @Test
    void testVolumeControl() throws Exception {
        PlaybackEngine engine = new PlaybackEngine(mockSequence, mockProvider, mockIO, 100, 1.0);
        
        mockIO.injectKey(TerminalIO.TerminalKey.VOLUME_DOWN);
        mockIO.injectKey(TerminalIO.TerminalKey.QUIT);
        
        assertDoesNotThrow(() -> engine.start());
    }

    @Test
    void testSeekBeyondBoundary() throws Exception {
        mockSequence = new Sequence(Sequence.PPQ, 24);
        mockSequence.createTrack();
        
        PlaybackEngine engine = new PlaybackEngine(mockSequence, mockProvider, mockIO, 100, 1.0);
        
        // Seek forward multiple times beyond end
        mockIO.injectKey(TerminalIO.TerminalKey.SEEK_FORWARD);
        mockIO.injectKey(TerminalIO.TerminalKey.SEEK_FORWARD);
        mockIO.injectKey(TerminalIO.TerminalKey.QUIT);
        
        assertDoesNotThrow(() -> engine.start());
    }

    @Test
    void testSeekBackwardToZero() throws Exception {
        mockSequence = new Sequence(Sequence.PPQ, 24);
        mockSequence.createTrack();
        
        PlaybackEngine engine = new PlaybackEngine(mockSequence, mockProvider, mockIO, 100, 1.0);
        
        // Seek backward early in the song (should stay at 0)
        mockIO.injectKey(TerminalIO.TerminalKey.SEEK_BACKWARD);
        mockIO.injectKey(TerminalIO.TerminalKey.QUIT);
        
        assertDoesNotThrow(() -> engine.start());
    }

    @Test
    void testVolumeBoundaries() throws Exception {
        PlaybackEngine engine = new PlaybackEngine(mockSequence, mockProvider, mockIO, 100, 1.0);
        
        // Volume down multiple times (should clamp at 0%)
        for(int i=0; i<30; i++) mockIO.injectKey(TerminalIO.TerminalKey.VOLUME_DOWN);
        // Volume up multiple times (should clamp at 100%)
        for(int i=0; i<30; i++) mockIO.injectKey(TerminalIO.TerminalKey.VOLUME_UP);
        
        mockIO.injectKey(TerminalIO.TerminalKey.QUIT);
        
        assertDoesNotThrow(() -> engine.start());
    }
}
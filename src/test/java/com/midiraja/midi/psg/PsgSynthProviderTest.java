package com.midiraja.midi.psg;

import com.midiraja.midi.AudioEngine;
import com.midiraja.midi.MidiPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PsgSynthProviderTest {

    private class DummyAudioEngine implements AudioEngine {
        @Override public void init(int sampleRate, int channels, int bufferSize) throws Exception {}
        @Override public void push(short[] pcm) {}
        @Override public int getQueuedFrames() { return 0; }
        @Override public int getDeviceLatencyFrames() { return 0; }
        @Override public void flush() {}
        @Override public void close() {}
    }

    private DummyAudioEngine audioEngine;
    private PsgSynthProvider provider;

    @BeforeEach
    void setUp() {
        audioEngine = new DummyAudioEngine();
        provider = new PsgSynthProvider(audioEngine, 1, 5.0, 25.0, true);
    }

    @Test
    void testGetOutputPorts() {
        List<MidiPort> ports = provider.getOutputPorts();
        assertFalse(ports.isEmpty());
        assertTrue(ports.get(0).name().contains("MSX System"));
    }

    @Test
    void testOpenPort() throws Exception {
        provider.openPort(0);
        // Verify init was called - we could add a flag to DummyAudioEngine if needed
    }

    @Test
    void testSendMessageNoteOn() throws Exception {
        byte[] noteOn = new byte[]{(byte) 0x90, 60, 100};
        provider.sendMessage(noteOn);
    }

    @Test
    void testSendMessageDrumsOnPsgOnly() throws Exception {
        provider = new PsgSynthProvider(audioEngine, 1, 0, 0, true);
        byte[] drumOn = new byte[]{(byte) 0x99, 36, 100};
        provider.sendMessage(drumOn);
    }

    @Test
    void testVoiceStealing() throws Exception {
        provider = new PsgSynthProvider(audioEngine, 1, 0, 0, true);
        for (int i = 0; i < 5; i++) {
            provider.sendMessage(new byte[]{(byte) 0x94, (byte)(60 + i), 100});
        }
        provider.sendMessage(new byte[]{(byte) 0x90, 72, 127});
    }

    @Test
    void testSendMessageProgramChange() throws Exception {
        // Ch 0, Program 1 (Bright Piano)
        byte[] progChange = new byte[]{(byte) 0xC0, 1};
        provider.sendMessage(progChange);
    }

    @Test
    void testSendMessageControlChange() throws Exception {
        // Ch 0, CC 7 (Volume), Value 100
        byte[] ctrlChange = new byte[]{(byte) 0xB0, 7, 100};
        provider.sendMessage(ctrlChange);
        
        // All Notes Off
        byte[] allNotesOff = new byte[]{(byte) 0xB0, 123, 0};
        provider.sendMessage(allNotesOff);
    }

    @Test
    void testSendMessageNoteOff() throws Exception {
        // Note On then Note Off
        provider.sendMessage(new byte[]{(byte) 0x90, 60, 100});
        provider.sendMessage(new byte[]{(byte) 0x80, 60, 0});
    }

    @Test
    void testPrepareForNewTrack() {
        provider.prepareForNewTrack(null);
    }

    @Test
    void testOnPlaybackStarted() {
        provider.onPlaybackStarted();
    }

    @Test
    void testPanic() {
        provider.panic();
    }
}

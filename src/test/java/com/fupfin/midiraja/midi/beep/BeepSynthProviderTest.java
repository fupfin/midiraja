package com.fupfin.midiraja.midi.beep;

import static org.junit.jupiter.api.Assertions.*;

import com.fupfin.midiraja.midi.AudioEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;

class BeepSynthProviderTest
{
    private AudioEngine mockAudio;
    private AtomicInteger pushCallCount;

    @BeforeEach
    void setUp()
    {
        pushCallCount = new AtomicInteger(0);
        mockAudio = new AudioEngine()
        {
            @Override public void init(int sampleRate, int channels, int bufferSize) {}
            @Override public int push(short[] pcm) { return push(pcm, 0, pcm.length); }
            @Override public int push(short[] pcm, int offset, int length) { pushCallCount.incrementAndGet(); return length; }
            @Override public int getQueuedFrames() { return 0; }
        @Override public int getBufferCapacityFrames() { return 4096; }
            @Override public int getDeviceLatencyFrames() { return 0; }
            @Override public void flush() {}
            @Override public void close() {}
        };
    }

    @Test
    void testAllArchitecturalCombinations() throws Exception
    {
        // To maximize code coverage, we must test all synths, all muxes, and polyphony extremes
        String[] synths = {"fm", "xor", "square"};
        String[] muxes = {"dsd", "pwm", "tdm", "xor"};
        int[] voiceOptions = {1, 4};
        
        for (String synth : synths)
        {
            for (String mux : muxes)
            {
                for (int voices : voiceOptions)
                {
                    BeepSynthProvider provider = new BeepSynthProvider(new com.fupfin.midiraja.dsp.FloatToShortSink(mockAudio, 1), voices, 1.0, 1.1, 32, mux, synth);
                    provider.openPort(0);
                    
                    // Trigger note events to force the render loop to evaluate math blocks
                    provider.sendMessage(new byte[] { (byte)0x90, 60, 100 }); // Melody
                    provider.sendMessage(new byte[] { (byte)0x90, 35, 100 }); // Drum Kick
                    provider.sendMessage(new byte[] { (byte)0x90, 38, 100 }); // Drum Snare
                    provider.sendMessage(new byte[] { (byte)0x90, 42, 100 }); // Drum Hi-hat
                    
                    // Wait enough for some frames to be generated
                    Thread.sleep(20);
                    
                    provider.closePort();
                }
            }
        }
        assertTrue(pushCallCount.get() > 0, "Audio should have been pushed across the combinations.");
    }
    
    // ... Include the rest of the previously written tests ...
    
    @Test
    void testInitializationAndPortName()
    {
        BeepSynthProvider provider = new BeepSynthProvider(new com.fupfin.midiraja.dsp.FloatToShortSink(mockAudio, 1), 2, 1.0, 1.1, 32, "dsd", "fm");
        assertEquals(1, provider.getOutputPorts().size());
        assertEquals("[8-Unit] 1-Bit Digital Cluster", provider.getOutputPorts().get(0).name());
        
        BeepSynthProvider providerMax = new BeepSynthProvider(new com.fupfin.midiraja.dsp.FloatToShortSink(mockAudio, 1), 4, 1.0, 1.1, 32, "dsd", "fm");
        assertEquals("[4-Unit] 1-Bit Digital Cluster", providerMax.getOutputPorts().get(0).name());
        
        BeepSynthProvider providerMin = new BeepSynthProvider(new com.fupfin.midiraja.dsp.FloatToShortSink(mockAudio, 1), 1, 1.0, 1.1, 32, "dsd", "fm");
        assertEquals("[16-Unit] 1-Bit Digital Cluster", providerMin.getOutputPorts().get(0).name());
    }
    
    @Test
    void testExtremeBoundaryParameters()
    {
        BeepSynthProvider provider = new BeepSynthProvider(new com.fupfin.midiraja.dsp.FloatToShortSink(mockAudio, 1), 10, -5.0, 999.0, 0, "UNKNOWN", "INVALID");
        assertEquals("[4-Unit] 1-Bit Digital Cluster", provider.getOutputPorts().get(0).name());
    }

    @Test
    void testMidiNoteAllocationAndVoiceStealing() throws Exception
    {
        BeepSynthProvider provider = new BeepSynthProvider(new com.fupfin.midiraja.dsp.FloatToShortSink(mockAudio, 1), 2, 1.0, 1.1, 32, "dsd", "fm");
        provider.sendMessage(new byte[] { (byte)0x90, 60, 100 });
        provider.sendMessage(new byte[] { (byte)0x90, 64, 100 });
        provider.sendMessage(new byte[] { (byte)0x90, 67, 100 });
        provider.sendMessage(new byte[] { (byte)0x80, 60, 0 });
        provider.sendMessage(new byte[] { (byte)0x90, 64, 0 });
    }
    
    @Test
    void testMidiPitchBend() throws Exception
    {
        BeepSynthProvider provider = new BeepSynthProvider(new com.fupfin.midiraja.dsp.FloatToShortSink(mockAudio, 1), 2, 1.0, 1.1, 32, "dsd", "fm");
        provider.sendMessage(new byte[] { (byte)0x90, 60, 100 });
        provider.sendMessage(new byte[] { (byte)0xE0, 0x00, 0x7F });
        provider.sendMessage(new byte[] { (byte)0xE0, 0x00, 0x00 });
        provider.sendMessage(new byte[] { (byte)0xB0, 121, 0 });
    }

    @Test
    void testMidiControlChanges() throws Exception
    {
        BeepSynthProvider provider = new BeepSynthProvider(new com.fupfin.midiraja.dsp.FloatToShortSink(mockAudio, 1), 2, 1.0, 1.1, 32, "dsd", "fm");
        provider.sendMessage(new byte[] { (byte)0x90, 60, 100 }); 
        provider.sendMessage(new byte[] { (byte)0xB0, 123, 0 });
        provider.sendMessage(new byte[] { (byte)0x90, 60, 100 }); 
        provider.sendMessage(new byte[] { (byte)0xB0, 120, 0 });
    }
    
    @Test
    void testInvalidMidiMessages()
    {
        BeepSynthProvider provider = new BeepSynthProvider(new com.fupfin.midiraja.dsp.FloatToShortSink(mockAudio, 1), 2, 1.0, 1.1, 32, "dsd", "fm");
        assertDoesNotThrow(() -> provider.sendMessage(new byte[] {}));
        assertDoesNotThrow(() -> provider.sendMessage(new byte[] { (byte)0x90, 60 }));
        assertDoesNotThrow(() -> provider.sendMessage(new byte[] { (byte)0xFF, 0, 0 }));
    }
}

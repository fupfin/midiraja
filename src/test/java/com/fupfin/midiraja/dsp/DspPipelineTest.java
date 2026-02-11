package com.fupfin.midiraja.dsp;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;

class DspPipelineTest {

    static class CaptureSink implements AudioProcessor {
        float[] capturedLeft;
        float[] capturedRight;
        int capturedFrames = 0;

        @Override
        public void process(float[] left, float[] right, int frames) {
            capturedLeft = left.clone();
            capturedRight = right.clone();
            capturedFrames = frames;
        }
    }

    @Test
    void testFormatConversionAndHardClipping() {
        short[] outPcm = new short[4];
        AudioProcessor dummyNext = new AudioProcessor() {
            @Override public void process(float[] l, float[] r, int f) {}
            @Override public void processInterleaved(short[] pcm, int f, int c) {
                System.arraycopy(pcm, 0, outPcm, 0, f * c);
            }
        };

        FloatToShortSink f2s = new FloatToShortSink(new com.fupfin.midiraja.midi.AudioEngine() {
            public void init(int sr, int ch, int buf) {}
            public int push(short[] pcm) { return push(pcm, 0, pcm.length); }
            @Override public int push(short[] pcm, int offset, int length) {
                short[] slice = java.util.Arrays.copyOfRange(pcm, offset, offset + length);
                dummyNext.processInterleaved(slice, length/2, 2);
                return length;
            }
            public int getQueuedFrames() { return 0; }
        @Override public int getBufferCapacityFrames() { return 4096; }
            public int getDeviceLatencyFrames() { return 0; }
            public void flush() {}
            public void close() {}
        }, 2);

        float[] left = { 2.0f, -1.5f };
        float[] right = { 0.5f, 0.0f };
        f2s.process(left, right, 2);

        assertEquals(32767, outPcm[0], "L0 should clip to max positive");
        assertEquals(16383, outPcm[1], "R0 should scale normally (0.5 * 32767)");
        assertEquals(-32767, outPcm[2], "L1 should clip to max negative");
        assertEquals(0, outPcm[3], "R1 should be 0");
    }

    @Test
    void testTubeSaturationBoundaryAndBypass() {
        CaptureSink sink = new CaptureSink();
        TubeSaturationFilter tube = new TubeSaturationFilter(sink, 50f);

        float[] left = { 0.8f, -0.8f };
        float[] right = { 0.8f, -0.8f };

        tube.setEnabled(false);
        tube.process(left.clone(), right.clone(), 2);
        assertEquals(0.8f, sink.capturedLeft[0], 0.001f, "Bypass should not alter signal");

        tube.setEnabled(true);
        tube.setDrive(200f); 
        tube.process(left.clone(), right.clone(), 2);
        float extremeOut = sink.capturedLeft[0];

        tube.setDrive(100f); 
        tube.process(left.clone(), right.clone(), 2);
        float maxOut = sink.capturedLeft[0];
        
        assertEquals(maxOut, extremeOut, 0.001f, "Drive > 100% should be clamped to 100%");
        assertTrue(maxOut < 0.8f, "Auto-gain should reduce peak amplitude of heavy saturation");
    }

    @Test
    void testEqFilterNeutralAndMute() {
        CaptureSink sink = new CaptureSink();
        EqFilter eq = new EqFilter(sink);

        eq.setParams(50f, 50f, 50f);
        
        float[] left = { 1.0f, 1.0f, 1.0f, 1.0f };
        float[] right = { 1.0f, 1.0f, 1.0f, 1.0f };
        eq.process(left.clone(), right.clone(), 4);
        
        assertTrue(sink.capturedLeft[3] > 0.9f && sink.capturedLeft[3] < 1.1f, "Neutral EQ should not drastically alter amplitude");

        eq.setParams(0f, 50f, 50f);
        float[] dcLeft = new float[100]; 
        Arrays.fill(dcLeft, 1.0f);
        eq.process(dcLeft, dcLeft.clone(), 100);
        
        assertTrue(Math.abs(sink.capturedLeft[99]) < 0.1f, "0% Bass should heavily attenuate low frequency (DC) signals");
    }

    @Test
    void testEqFilterExtremeCutoffs() {
        CaptureSink sink = new CaptureSink();
        EqFilter eq = new EqFilter(sink);

        eq.setLpf(999999f); 
        eq.setHpf(-100f);   
        
        float[] left = { 0.5f, -0.5f };
        assertDoesNotThrow(() -> {
            eq.process(left.clone(), left.clone(), 2);
        }, "Extreme cutoffs should not throw exceptions or generate NaNs");
        
        assertFalse(Float.isNaN(sink.capturedLeft[0]), "Output should not be NaN");
    }

    @Test
    void testReverbImpulseResponse() {
        CaptureSink sink = new CaptureSink();
        ReverbFilter reverb = new ReverbFilter(sink, ReverbFilter.Preset.ROOM);

        float[] left = { 1.0f, 0.0f, 0.0f, 0.0f, 0.0f };
        float[] right = { 0.0f, 0.0f, 0.0f, 0.0f, 0.0f };
        
        reverb.process(left, right, 5);
        
        float[] silence = new float[2000];
        reverb.process(silence, silence.clone(), 2000);
        
        double energy = 0;
        for (float v : sink.capturedLeft) energy += Math.abs(v);
        
        assertTrue(energy > 0.0f, "Reverb should produce a tail after the input stops");
        
        reverb.setPreset(ReverbFilter.Preset.ROOM, 0f);
        silence = new float[10];
        reverb.process(silence, silence.clone(), 10);
        assertEquals(0.0f, sink.capturedLeft[9], 0.0001f, "0% Reverb level should yield pure silence if input is silent");
    }
    
    @Test
    void testEmptyBufferGracefulHandling() {
        CaptureSink sink = new CaptureSink();
        ChorusFilter chorus = new ChorusFilter(sink, 50f);
        
        float[] empty = new float[0];
        assertDoesNotThrow(() -> {
            chorus.process(empty, empty, 0);
        }, "Filters must gracefully handle 0-length frame requests");
        
        assertEquals(0, sink.capturedFrames, "Sink should receive 0 frames");
    }
}

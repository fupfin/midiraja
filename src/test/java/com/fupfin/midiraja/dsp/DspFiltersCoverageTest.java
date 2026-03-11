package com.fupfin.midiraja.dsp;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.Collections;

class DspFiltersCoverageTest {

    static class CaptureSink implements AudioSink {
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
    void testCompactMacSimulatorFilter() {
        CaptureSink sink = new CaptureSink();
        CompactMacSimulatorFilter filter = new CompactMacSimulatorFilter(true, sink);
        
        float[] left = new float[256];
        float[] right = new float[256];
        Arrays.fill(left, 0.5f);
        Arrays.fill(right, 0.5f);
        
        assertDoesNotThrow(() -> filter.process(left, right, 256));
        assertTrue(sink.capturedFrames == 256);
        assertNotNull(sink.capturedLeft);
        
        CompactMacSimulatorFilter filterDisabled = new CompactMacSimulatorFilter(false, sink);
        assertDoesNotThrow(() -> filterDisabled.process(left, right, 256));
    }

    @Test
    void testMasterGainFilter() {
        CaptureSink sink = new CaptureSink();
        MasterGainFilter filter = new MasterGainFilter(sink, 0.5f);
        
        float[] left = {1.0f, -1.0f};
        float[] right = {1.0f, -1.0f};
        
        filter.process(left.clone(), right.clone(), 2);
        assertEquals(0.5f, sink.capturedLeft[0], 0.001f);
        assertEquals(-0.5f, sink.capturedLeft[1], 0.001f);
        
        filter.setGain(2.0f);
        filter.process(left.clone(), right.clone(), 2);
        assertEquals(2.0f, sink.capturedLeft[0], 0.001f);
    }

    @Test
    void testEightBitQuantizerFilter() {
        CaptureSink sink = new CaptureSink();
        EightBitQuantizerFilter filter = new EightBitQuantizerFilter(true, sink);
        
        float[] left = {0.0f, 0.5f, 1.0f, -0.5f, -1.0f};
        float[] right = {0.0f, 0.5f, 1.0f, -0.5f, -1.0f};
        
        assertDoesNotThrow(() -> filter.process(left, right, 5));
        assertTrue(sink.capturedFrames == 5);
        for (float v : sink.capturedLeft) {
            assertTrue(v >= -1.0f && v <= 1.0f);
        }
    }

    @Test
    void testCovoxFilter() {
        CaptureSink sink = new CaptureSink();
        CovoxFilter filter = new CovoxFilter(true, sink);
        
        float[] left = new float[256];
        float[] right = new float[256];
        for(int i=0; i<256; i++) {
            left[i] = (float) Math.sin(i * 0.1);
            right[i] = (float) Math.cos(i * 0.1);
        }
        
        assertDoesNotThrow(() -> filter.process(left, right, 256));
        assertTrue(sink.capturedFrames == 256);
        
        CovoxFilter filterDisabled = new CovoxFilter(false, sink);
        assertDoesNotThrow(() -> filterDisabled.process(left, right, 256));
    }

    @Test
    void testShortToFloatFilter() {
        CaptureSink sink = new CaptureSink();
        ShortToFloatFilter filter = new ShortToFloatFilter(sink);
        
        short[] pcm = { 0, 16383, 32767, -16384, -32768, 0 };
        // 3 frames, 2 channels
        filter.processInterleaved(pcm, 3, 2);
        
        assertTrue(sink.capturedFrames == 3);
        assertEquals(0.0f, sink.capturedLeft[0], 0.001f);
        assertEquals(32767f / 32768f, sink.capturedLeft[1], 0.001f);
        assertEquals(-32768f / 32768f, sink.capturedLeft[2], 0.001f);

        assertEquals(16383f / 32768f, sink.capturedRight[0], 0.001f);
        assertEquals(-16384f / 32768f, sink.capturedRight[1], 0.001f);
        assertEquals(0.0f, sink.capturedRight[2], 0.001f);
        
        // test 1 channel
        filter.processInterleaved(new short[]{16384, -16384}, 2, 1);
        assertEquals(16384f / 32768f, sink.capturedLeft[0], 0.001f);
        assertEquals(16384f / 32768f, sink.capturedRight[0], 0.001f);
    }
    
    @Test
    void testLegacyProcessorSink() {
        boolean[] called = {false};
        LegacyProcessorSink sink = new LegacyProcessorSink(new AudioProcessor() {
            @Override
            public void process(float[] left, float[] right, int frames) {
                called[0] = true;
            }
        }, Collections.emptyList());
        
        float[] left = {0.5f, 0.5f};
        float[] right = {0.5f, 0.5f};
        sink.process(left, right, 2);
        assertTrue(called[0], "LegacyProcessorSink should call process on downstream");
    }

    @Test
    void testOneBitAcousticSimulatorFilter() {
        CaptureSink sink = new CaptureSink();
        OneBitAcousticSimulatorFilter filter = new OneBitAcousticSimulatorFilter(true, "pwm", sink);
        
        float[] left = new float[1024];
        float[] right = new float[1024];
        Arrays.fill(left, 1.0f);
        Arrays.fill(right, -1.0f);
        
        assertDoesNotThrow(() -> filter.process(left, right, 1024));
        assertTrue(sink.capturedFrames == 1024);
        
        OneBitAcousticSimulatorFilter disabled = new OneBitAcousticSimulatorFilter(false, "pwm", sink);
        assertDoesNotThrow(() -> disabled.process(left, right, 1024));
    }

    @Test
    void testOneBitAcousticSimulator() {
        OneBitAcousticSimulator sim = new OneBitAcousticSimulator(44100, "pwm");
        float[] in = {1.0f, 1.0f, -1.0f, -1.0f};
        float[] out = new float[4];
        
        assertDoesNotThrow(() -> sim.process(in, out, 4));
        assertDoesNotThrow(() -> sim.reset());
        
        OneBitAcousticSimulator sim2 = new OneBitAcousticSimulator(44100, "dsd");
        assertDoesNotThrow(() -> sim2.process(in, out, 4));
    }
}

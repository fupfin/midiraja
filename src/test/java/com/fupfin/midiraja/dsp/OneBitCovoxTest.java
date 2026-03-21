package com.fupfin.midiraja.dsp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

public class OneBitCovoxTest {

    private static class DummyAudioProcessor implements AudioProcessor {
        public boolean processCalled = false;
        public boolean processInterleavedCalled = false;
        public boolean resetCalled = false;

        @Override
        public void process(float[] left, float[] right, int frames) {
            processCalled = true;
        }

        @Override
        public void processInterleaved(short[] interleavedPcm, int frames, int channels) {
            processInterleavedCalled = true;
        }

        @Override
        public void reset() {
            resetCalled = true;
        }
    }

    @Test
    public void testCovoxFilterDisabled() {
        DummyAudioProcessor next = new DummyAudioProcessor();
        CovoxFilter filter = new CovoxFilter(false, next);

        float[] l = new float[]{0.5f, -0.5f};
        float[] r = new float[]{0.5f, -0.5f};
        filter.process(l, r, 2);
        assertTrue(next.processCalled);
        assertEquals(0.5f, l[0]); // Unmodified

        next.processInterleavedCalled = false;
        short[] interleaved = new short[]{10000, -10000, 20000, -20000};
        filter.processInterleaved(interleaved, 2, 2);
        assertTrue(next.processInterleavedCalled);
        assertEquals(10000, interleaved[0]); // Unmodified

        filter.reset();
        assertTrue(next.resetCalled);
    }

    @Test
    public void testCovoxFilterEnabled() {
        DummyAudioProcessor next = new DummyAudioProcessor();
        CovoxFilter filter = new CovoxFilter(true, next);

        float[] l = new float[]{0.5f, -0.5f, 1.2f, -1.2f, 0.0f};
        float[] r = new float[]{0.5f, -0.5f, 1.2f, -1.2f, 0.0f};
        filter.process(l, r, 5);
        assertTrue(next.processCalled);
        assertNotEquals(0.5f, l[0]); // Should be modified

        short[] interleaved = new short[]{10000, -10000, 32767, -32768, 0, 0};
        filter.processInterleaved(interleaved, 3, 2);
        assertTrue(next.processInterleavedCalled);
        assertNotEquals(10000, interleaved[0]); // Should be modified

        // Test mono interleaved
        short[] monoInterleaved = new short[]{10000, -10000, 0};
        filter.processInterleaved(monoInterleaved, 3, 1);
        
        filter.reset();
        assertTrue(next.resetCalled);
    }

    @Test
    public void testOneBitHardwareFilterDisabled() {
        DummyAudioProcessor next = new DummyAudioProcessor();
        OneBitHardwareFilter filter = new OneBitHardwareFilter(false, "pwm", 1000.0, 256.0, 32.8, 0, 1.0, null, next);

        float[] l = new float[]{0.5f, -0.5f};
        float[] r = new float[]{0.5f, -0.5f};
        filter.process(l, r, 2);
        assertTrue(next.processCalled);

        short[] interleaved = new short[]{10000, -10000};
        filter.processInterleaved(interleaved, 1, 2);
        assertTrue(next.processInterleavedCalled);
    }

    @Test
    public void testOneBitHardwareFilterPWM() {
        DummyAudioProcessor next = new DummyAudioProcessor();
        OneBitHardwareFilter filter = new OneBitHardwareFilter(true, null, 15000.0, 256.0, 32.8, 0, 1.0, null, next);

        float[] l = new float[]{0.0f, 0.5f, -0.5f, 1.2f, -1.2f, 0.0f};
        float[] r = new float[]{0.0f, 0.5f, -0.5f, 1.2f, -1.2f, 0.0f};
        filter.process(l, r, 6);
        assertTrue(next.processCalled);

        short[] interleaved = new short[]{0, 0, 10000, 10000, -10000, -10000, 32767, 32767, -32768, -32768};
        filter.processInterleaved(interleaved, 5, 2);
        assertTrue(next.processInterleavedCalled);

        filter.reset();
        assertTrue(next.resetCalled);
    }
    
    @Test
    public void testOneBitHardwareFilterPWM_Mono() {
        DummyAudioProcessor next = new DummyAudioProcessor();
        OneBitHardwareFilter filter = new OneBitHardwareFilter(true, "pwm", 15000.0, 256.0, 32.8, 0, 1.0, null, next);

        short[] interleaved = new short[]{0, 10000, -10000, 32767, -32768};
        filter.processInterleaved(interleaved, 5, 1);
        assertTrue(next.processInterleavedCalled);
    }

    @Test
    public void testOneBitHardwareFilterDSD() {
        DummyAudioProcessor next = new DummyAudioProcessor();
        OneBitHardwareFilter filter = new OneBitHardwareFilter(true, "dsd", 15000.0, 256.0, 32.8, 0, 1.0, null, next);

        float[] l = new float[]{0.0f, 0.5f, -0.5f};
        float[] r = new float[]{0.0f, 0.5f, -0.5f};
        filter.process(l, r, 3);
        assertTrue(next.processCalled);

        short[] interleaved = new short[]{0, 0, 10000, 10000, -10000, -10000};
        filter.processInterleaved(interleaved, 3, 2);
        assertTrue(next.processInterleavedCalled);
    }
    
    @Test
    public void testOneBitAcousticSimulatorFilterDisabled() {
        DummyAudioProcessor next = new DummyAudioProcessor();
        OneBitAcousticSimulatorFilter filter = new OneBitAcousticSimulatorFilter(false, "pwm", next);

        float[] l = new float[]{0.5f, -0.5f};
        float[] r = new float[]{0.5f, -0.5f};
        filter.process(l, r, 2);
        assertFalse(next.processCalled); // "if (!enabled) return;" so it actually doesn't call next.process
        
        short[] interleaved = new short[]{10000, -10000};
        filter.processInterleaved(interleaved, 1, 2);
        assertTrue(next.processInterleavedCalled); // "next.processInterleaved(...);" is called regardless
    }

    @Test
    public void testOneBitAcousticSimulatorFilterPWM() {
        DummyAudioProcessor next = new DummyAudioProcessor();
        OneBitAcousticSimulatorFilter filter = new OneBitAcousticSimulatorFilter(true, null, next);

        float[] l = new float[]{0.0f, 0.5f, -0.5f, 1.2f, -1.2f};
        float[] r = new float[]{0.0f, 0.5f, -0.5f, 1.2f, -1.2f};
        filter.process(l, r, 5);
        assertTrue(next.processCalled);

        filter.reset();
        assertTrue(next.resetCalled);
    }

    @Test
    public void testOneBitAcousticSimulatorFilterDSD() {
        DummyAudioProcessor next = new DummyAudioProcessor();
        OneBitAcousticSimulatorFilter filter = new OneBitAcousticSimulatorFilter(true, "dsd", next);

        float[] l = new float[]{0.0f, 0.5f, -0.5f};
        float[] r = new float[]{0.0f, 0.5f, -0.5f};
        filter.process(l, r, 3);
        assertTrue(next.processCalled);
        
        filter.reset();
        assertTrue(next.resetCalled);
    }
}

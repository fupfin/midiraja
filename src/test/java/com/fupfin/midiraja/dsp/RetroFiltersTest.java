package com.fupfin.midiraja.dsp;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

class RetroFiltersTest {

    private static class MockProcessor implements AudioProcessor {
        float[] lastLeft, lastRight;
        int lastFrames;
        short[] lastInterleaved;
        boolean processCalled = false;
        boolean processInterleavedCalled = false;
        boolean resetCalled = false;

        @Override
        public void process(float[] left, float[] right, int frames) {
            this.lastLeft = left.clone();
            this.lastRight = right.clone();
            this.lastFrames = frames;
            this.processCalled = true;
        }

        @Override
        public void processInterleaved(short[] interleavedPcm, int frames, int channels) {
            this.lastInterleaved = interleavedPcm.clone();
            this.lastFrames = frames;
            this.processInterleavedCalled = true;
        }

        @Override
        public void reset() {
            this.resetCalled = true;
        }
    }

    private MockProcessor mock;

    @BeforeEach
    void setUp() {
        mock = new MockProcessor();
    }

    @Test
    void testCovoxDacBasic() {
        CovoxDacFilter filter = new CovoxDacFilter(true, mock);
        float[] left = {0.0f, 1.0f, -1.0f, 2.0f, -2.0f};
        float[] right = {0.0f, 1.0f, -1.0f, 2.0f, -2.0f};
        
        filter.process(left, right, 5);
        
        assertTrue(mock.processCalled);
        assertEquals(5, mock.lastFrames);
        
        // Clipping check: values should be within [-1.1, 1.1] approx (due to LPF and non-linearity)
        for (int i = 0; i < 5; i++) {
            assertTrue(Math.abs(mock.lastLeft[i]) <= 1.1f, "Value at index " + i + " was " + mock.lastLeft[i]);
        }
    }

    @Test
    void testIbmPcDacBoundary() {
        IbmPcDacFilter filter = new IbmPcDacFilter(true, "pwm", mock);
        float[] left = new float[512];
        float[] right = new float[512];
        
        filter.process(left, right, 512);
        assertTrue(mock.processCalled);
        
        for (int i = 0; i < 512; i++) {
            float val = mock.lastLeft[i];
            assertTrue(val >= -1.0f && val <= 1.0f, "IBM PC PWM output out of bounds: " + val);
        }
    }

    @Test
    void testApple2DacToggle() {
        Apple2DacFilter filter = new Apple2DacFilter(true, mock);
        float[] left = {0.5f, 0.5f, -0.5f, -0.5f};
        float[] right = {0.5f, 0.5f, -0.5f, -0.5f};
        
        filter.process(left, right, 4);
        assertTrue(mock.processCalled);
        
        // Should also be 1-bit
        for (int i = 0; i < 4; i++) {
            float val = mock.lastLeft[i];
            assertTrue(val == 1.0f || val == -1.0f, "Apple II output must be 1-bit");
        }
    }

    @Test
    void testAcousticSpeakerProfile() {
        AcousticSpeakerFilter filter = new AcousticSpeakerFilter(true, AcousticSpeakerFilter.Profile.TIN_CAN, mock);
        
        // 10Hz sine wave (subsonic, should be killed by 400Hz HPF)
        float[] left = new float[1000];
        float[] right = new float[1000];
        for (int i = 0; i < 1000; i++) {
            left[i] = (float) Math.sin(2.0 * Math.PI * 10.0 * i / 44100.0);
            right[i] = left[i];
        }
        
        filter.process(left, right, 1000);
        
        // Check attenuation
        float maxVal = 0;
        for (float v : mock.lastLeft) maxVal = Math.max(maxVal, Math.abs(v));
        assertTrue(maxVal < 0.1f, "10Hz should be heavily attenuated by PC speaker model, got max: " + maxVal);
    }

    @Test
    void testResetClearsState() {
        CovoxDacFilter filter = new CovoxDacFilter(true, mock);
        float[] left = {1.0f, 1.0f};
        float[] right = {1.0f, 1.0f};
        
        filter.process(left, right, 2);
        filter.reset();
        assertTrue(mock.resetCalled);
        
        // After reset, processing silence should not have "tails" from previous 1.0 input
        float[] silence = {0.0f, 0.0f};
        filter.process(silence, silence, 2);
        // Expect output near 0 (R-2R center)
        assertTrue(Math.abs(mock.lastLeft[0]) < 0.1f);
    }
}

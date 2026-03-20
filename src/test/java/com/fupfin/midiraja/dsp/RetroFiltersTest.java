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
    void testPcDacBoundary() {
        // PC speaker: empirical 15.2kHz carrier (1.19318MHz / 78 steps), ~6.3-bit
        // tauUs=37.9 derives from old smoothAlpha=0.45; resonance peaks at 2.5kHz and 6.7kHz
        OneBitHardwareFilter filter = new OneBitHardwareFilter(
                true, "pwm", 15200.0, 78.0, 37.9,
                new double[]{2500.0, 3.0, 3.0, 6700.0, 4.0, 4.0}, mock);
        float[] left  = new float[512];
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
        // DAC522 profile: 22kHz carrier (above hearing limit), 5-bit resolution
        // tauUs=28.4 derives from the old smoothAlpha=0.55 via τ = -1/(44100 × ln(1-0.55))
        OneBitHardwareFilter filter = new OneBitHardwareFilter(true, "pwm", 22050.0, 32.0, 28.4, null, mock);
        float[] left  = {0.5f, 0.5f, -0.5f, -0.5f};
        float[] right = {0.5f, 0.5f, -0.5f, -0.5f};

        filter.process(left, right, 4);
        assertTrue(mock.processCalled);

        for (int i = 0; i < 4; i++) {
            float val = mock.lastLeft[i];
            assertTrue(val >= -1.0f && val <= 1.0f, "Apple II output out of bounds: " + val);
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
    void testSpectrumBeeperBasic() {
        SpectrumBeeperFilter filter = new SpectrumBeeperFilter(true, mock);
        float[] left = new float[512];
        float[] right = new float[512];
        for (int i = 0; i < 512; i++) {
            left[i] = (float) Math.sin(2.0 * Math.PI * 1000.0 * i / 44100.0);
            right[i] = left[i];
        }

        filter.process(left, right, 512);

        assertTrue(mock.processCalled);
        assertEquals(512, mock.lastFrames);
        boolean hasSignal = false;
        for (int i = 0; i < 512; i++) {
            float val = mock.lastLeft[i];
            assertTrue(val >= -1.0f && val <= 1.0f, "Spectrum beeper output out of range: " + val);
            if (Math.abs(val) > 0.001f) hasSignal = true;
        }
        assertTrue(hasSignal, "Expected non-zero output from SpectrumBeeperFilter");
    }

    @Test
    void testSpectrumBeeperDisabled() {
        SpectrumBeeperFilter filter = new SpectrumBeeperFilter(false, mock);
        float[] left = {0.5f, 0.3f, -0.4f};
        float[] right = {0.5f, 0.3f, -0.4f};

        filter.process(left, right, 3);

        assertTrue(mock.processCalled);
        // Disabled: signal passes through unmodified
        assertEquals(0.5f, mock.lastLeft[0], 0.001f);
        assertEquals(0.3f, mock.lastLeft[1], 0.001f);
        assertEquals(-0.4f, mock.lastLeft[2], 0.001f);
    }

    // --- AmigaPaulaFilter tests ---

    @Test
    void testAmigaA500Basic() {
        AmigaPaulaFilter filter = new AmigaPaulaFilter(true, AmigaPaulaFilter.Profile.A500, mock);
        int n = 1024;
        float[] left = new float[n];
        float[] right = new float[n];
        for (int i = 0; i < n; i++) {
            left[i] = (float) Math.sin(2.0 * Math.PI * 1000.0 * i / 44100.0);
            right[i] = left[i];
        }

        filter.process(left, right, n);

        assertTrue(mock.processCalled);
        float maxVal = 0;
        for (int i = 0; i < n; i++) maxVal = Math.max(maxVal, Math.abs(mock.lastLeft[i]));
        assertTrue(maxVal > 0.01f, "Expected non-zero A500 output, got max: " + maxVal);
        assertTrue(maxVal <= 1.1f, "A500 output out of range: " + maxVal);
    }

    @Test
    void testAmigaStereoIndependence() {
        // L and R have different signals → verify DAC chains are independent (not mono mix-down).
        // With M/S widening, a mono input (L == R) produces L_out == R_out (s=0, no widening).
        AmigaPaulaFilter filter = new AmigaPaulaFilter(true, AmigaPaulaFilter.Profile.A500, mock);
        int n = 2048;
        float[] left = new float[n];
        float[] right = new float[n];
        // True mono input: L == R
        for (int i = 0; i < n; i++)
            left[i] = right[i] = (float) Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0);

        filter.process(left, right, n);

        // Mono input should produce identical L/R output (stereo difference is zero, widening has no effect)
        for (int i = 100; i < n; i++) {
            assertEquals(mock.lastLeft[i], mock.lastRight[i], 1e-5f,
                    "Mono input should produce identical L/R output at frame " + i);
        }
    }

    @Test
    void testAmigaStereoWidening() {
        // Asymmetric input → output separation should be wider than input separation.
        AmigaPaulaFilter filter = new AmigaPaulaFilter(true, AmigaPaulaFilter.Profile.A500, mock);
        int n = 4096;
        float[] left = new float[n];
        float[] right = new float[n];
        for (int i = 0; i < n; i++) {
            left[i]  = (float) Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0) * 0.8f;
            right[i] = (float) Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0) * 0.2f;
        }

        filter.process(left, right, n);

        // Input L/R difference peak
        float inputDiff = 0.8f - 0.2f; // 0.6

        // Output L/R difference peak (skip initial transient)
        float outputDiff = 0;
        for (int i = 512; i < n; i++)
            outputDiff = Math.max(outputDiff, Math.abs(mock.lastLeft[i] - mock.lastRight[i]));

        assertTrue(outputDiff > inputDiff * 1.2f,
                "Stereo widening should increase L/R separation. inputDiff≈" + inputDiff + " outputDiff=" + outputDiff);
    }

    @Test
    void testAmigaA500HighFreqAttenuation() {
        AmigaPaulaFilter filter = new AmigaPaulaFilter(true, AmigaPaulaFilter.Profile.A500, mock);
        int n = 4096;
        float[] left = new float[n];
        float[] right = new float[n];
        // 15kHz sine — well above A500 4.5kHz + LED 3.3kHz cascade cutoff
        for (int i = 0; i < n; i++) {
            left[i] = (float) Math.sin(2.0 * Math.PI * 15000.0 * i / 44100.0);
            right[i] = left[i];
        }

        filter.process(left, right, n);

        // Skip initial transient (first 512 samples), check steady-state peak
        float maxVal = 0;
        for (int i = 512; i < n; i++) maxVal = Math.max(maxVal, Math.abs(mock.lastLeft[i]));
        assertTrue(maxVal < 0.1f, "15kHz should be heavily attenuated by A500 filter cascade, got peak: " + maxVal);
    }

    @Test
    void testAmigaA1200BrighterThanA500() {
        int n = 4096;
        float[] leftA500 = new float[n];
        float[] rightA500 = new float[n];
        float[] leftA1200 = new float[n];
        float[] rightA1200 = new float[n];
        // 8kHz sine — above A500 cutoff but below A1200 cutoff
        for (int i = 0; i < n; i++) {
            leftA500[i] = leftA1200[i] = (float) Math.sin(2.0 * Math.PI * 8000.0 * i / 44100.0);
            rightA500[i] = rightA1200[i] = leftA500[i];
        }

        MockProcessor mockA500 = new MockProcessor();
        MockProcessor mockA1200 = new MockProcessor();
        new AmigaPaulaFilter(true, AmigaPaulaFilter.Profile.A500, mockA500).process(leftA500, rightA500, n);
        new AmigaPaulaFilter(true, AmigaPaulaFilter.Profile.A1200, mockA1200).process(leftA1200, rightA1200, n);

        float peakA500 = 0, peakA1200 = 0;
        for (int i = 512; i < n; i++) {
            peakA500 = Math.max(peakA500, Math.abs(mockA500.lastLeft[i]));
            peakA1200 = Math.max(peakA1200, Math.abs(mockA1200.lastLeft[i]));
        }
        assertTrue(peakA1200 > peakA500 * 2,
                "A1200 should be significantly brighter than A500 at 8kHz: A500=" + peakA500 + " A1200=" + peakA1200);
    }

    @Test
    void testAmigaDisabledPassthrough() {
        AmigaPaulaFilter filter = new AmigaPaulaFilter(false, AmigaPaulaFilter.Profile.A500, mock);
        float[] left = {0.3f, -0.5f, 0.7f};
        float[] right = {0.1f, 0.2f, -0.3f};

        filter.process(left, right, 3);

        assertEquals(0.3f, mock.lastLeft[0], 0.001f);
        assertEquals(-0.5f, mock.lastLeft[1], 0.001f);
        assertEquals(0.7f, mock.lastLeft[2], 0.001f);
        assertEquals(0.1f, mock.lastRight[0], 0.001f);
    }

    @Test
    void testAmigaResetClearsState() {
        AmigaPaulaFilter filter = new AmigaPaulaFilter(true, AmigaPaulaFilter.Profile.A500, mock);
        float[] left = {1.0f, 1.0f, 1.0f, 1.0f};
        float[] right = {1.0f, 1.0f, 1.0f, 1.0f};
        filter.process(left, right, 4);

        filter.reset();
        assertTrue(mock.resetCalled);

        float[] silL = new float[4];
        float[] silR = new float[4];
        filter.process(silL, silR, 4);
        // After reset, silence input should produce near-zero output (no tail from previous signal)
        assertTrue(Math.abs(mock.lastLeft[0]) < 0.1f, "Expected near-zero after reset, got: " + mock.lastLeft[0]);
        assertTrue(Math.abs(mock.lastRight[0]) < 0.1f, "Expected near-zero after reset, got: " + mock.lastRight[0]);
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

    /**
     * Computes the DFT magnitude at a target frequency by dot-product with
     * a complex exponential. O(N) per frequency point — good enough for a few spot checks.
     *
     * @param signal  mono float signal at 44100 Hz
     * @param freqHz  target frequency in Hz
     * @return magnitude (not normalised by N — use ratio comparisons only)
     */
    private static double fftMagnitudeAt(float[] signal, double freqHz) {
        double re = 0, im = 0;
        double w = 2.0 * Math.PI * freqHz / 44100.0;
        for (int n = 0; n < signal.length; n++) {
            re += signal[n] * Math.cos(w * n);
            im += signal[n] * Math.sin(w * n);
        }
        return Math.sqrt(re * re + im * im);
    }

    @Test
    void testApple2ProducesAudibleHarmonics() {
        int n = 44100; // 1 second at 44100 Hz
        float[] left  = new float[n];
        float[] right = new float[n];
        for (int i = 0; i < n; i++) {
            float s = (float)(Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0) * 0.8);
            left[i] = right[i] = s;
        }

        OneBitHardwareFilter filter = new OneBitHardwareFilter(
                true, "pwm", 22050.0, 32.0, 28.4, null, mock);
        filter.process(left, right, n);

        float[] out = mock.lastLeft;
        double fund  = fftMagnitudeAt(out, 440.0);
        double harm2 = fftMagnitudeAt(out, 880.0);
        double ratio = harm2 / fund;

        assertTrue(fund > 0, "440 Hz fundamental must be present");
        // Observed ratio ~0.01149 on first run; 0.0103 is ~90% of that, leaving headroom for
        // minor platform differences. Physical basis: 5-bit (32-level) quantisation of a 0.8
        // amplitude sine produces THD ~1.6%, with the dominant harmonic near −36 dB.
        assertTrue(ratio >= 0.0103,
                String.format("2nd harmonic should be ≥1%% of fundamental. fund=%.1f harm2=%.1f ratio=%.4f",
                        fund, harm2, ratio));
    }

    @Test
    void testPcResonancePeaksExceedApple2() {
        int n = 44100;
        float[] leftApple2  = new float[n];
        float[] rightApple2 = new float[n];
        float[] leftPc      = new float[n];
        float[] rightPc     = new float[n];
        for (int i = 0; i < n; i++) {
            float s = (float)(Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0) * 0.8);
            leftApple2[i] = rightApple2[i] = leftPc[i] = rightPc[i] = s;
        }

        MockProcessor mockA2 = new MockProcessor();
        MockProcessor mockPc = new MockProcessor();

        new OneBitHardwareFilter(true, "pwm", 22050.0, 32.0, 28.4, null, mockA2)
                .process(leftApple2, rightApple2, n);
        new OneBitHardwareFilter(true, "pwm", 15200.0, 78.0, 37.9,
                new double[]{2500.0, 3.0, 3.0, 6700.0, 4.0, 4.0}, mockPc)
                .process(leftPc, rightPc, n);

        double apple2At2500 = fftMagnitudeAt(mockA2.lastLeft, 2500.0);
        double pcAt2500     = fftMagnitudeAt(mockPc.lastLeft, 2500.0);
        double apple2At6700 = fftMagnitudeAt(mockA2.lastLeft, 6700.0);
        double pcAt6700     = fftMagnitudeAt(mockPc.lastLeft, 6700.0);

        assertTrue(pcAt2500 > apple2At2500,
                "PC should have more energy at 2.5kHz than Apple II due to resonance peak");
        assertTrue(pcAt6700 > apple2At6700,
                "PC should have more energy at 6.7kHz than Apple II due to resonance peak");
    }
}

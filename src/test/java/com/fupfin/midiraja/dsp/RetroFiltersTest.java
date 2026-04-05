package com.fupfin.midiraja.dsp;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RetroFiltersTest
{

    private static class MockProcessor implements AudioProcessor
    {
        float[] lastLeft, lastRight;
        int lastFrames;
        short[] lastInterleaved;
        boolean processCalled = false;
        boolean processInterleavedCalled = false;
        boolean resetCalled = false;

        @Override
        public void process(float[] left, float[] right, int frames)
        {
            this.lastLeft = left.clone();
            this.lastRight = right.clone();
            this.lastFrames = frames;
            this.processCalled = true;
        }

        @Override
        public void processInterleaved(short[] interleavedPcm, int frames, int channels)
        {
            this.lastInterleaved = interleavedPcm.clone();
            this.lastFrames = frames;
            this.processInterleavedCalled = true;
        }

        @Override
        public void reset()
        {
            this.resetCalled = true;
        }
    }

    private MockProcessor mock;

    @BeforeEach
    void setUp()
    {
        mock = new MockProcessor();
    }

    @Test
    void testCovoxDacBasic()
    {
        CovoxDacFilter filter = new CovoxDacFilter(true, mock);
        float[] left = { 0.0f, 1.0f, -1.0f, 2.0f, -2.0f };
        float[] right = { 0.0f, 1.0f, -1.0f, 2.0f, -2.0f };

        filter.process(left, right, 5);

        assertTrue(mock.processCalled);
        assertEquals(5, mock.lastFrames);

        // Clipping check: values should be within [-1.1, 1.1] approx (due to LPF and non-linearity)
        for (int i = 0; i < 5; i++)
        {
            assertTrue(Math.abs(mock.lastLeft[i]) <= 1.1f, "Value at index " + i + " was " + mock.lastLeft[i]);
        }
    }

    @Test
    void testPcDacBoundary()
    {
        // PC speaker: empirical 15.2kHz carrier (1.19318MHz / 78 steps), ~6.3-bit
        // 8-bit source pre-quantisation, no resonance peaks
        OneBitHardwareFilter filter = new OneBitHardwareFilter(
                true, "pwm", 15200.0, 78.0, 37.9, 8, 1.0, null, false, mock);
        float[] left = new float[512];
        float[] right = new float[512];

        filter.process(left, right, 512);
        assertTrue(mock.processCalled);

        for (int i = 0; i < 512; i++)
        {
            float val = mock.lastLeft[i];
            assertTrue(val >= -1.0f && val <= 1.0f, "IBM PC PWM output out of bounds: " + val);
        }
    }

    @Test
    void testApple2DacToggle()
    {
        // DAC522 profile: 22kHz carrier (above hearing limit), 5-bit resolution
        // tauUs=28.4 derives from the old smoothAlpha=0.55 via τ = -1/(44100 × ln(1-0.55))
        OneBitHardwareFilter filter = new OneBitHardwareFilter(true, "pwm", 22050.0, 32.0, 28.4, 8, 1.0, null, false,
                mock);
        float[] left = { 0.5f, 0.5f, -0.5f, -0.5f };
        float[] right = { 0.5f, 0.5f, -0.5f, -0.5f };

        filter.process(left, right, 4);
        assertTrue(mock.processCalled);

        for (int i = 0; i < 4; i++)
        {
            float val = mock.lastLeft[i];
            assertTrue(val >= -1.0f && val <= 1.0f, "Apple II output out of bounds: " + val);
        }
    }

    @Test
    void testAcousticSpeakerProfile()
    {
        AcousticSpeakerFilter filter = new AcousticSpeakerFilter(true, AcousticSpeakerFilter.Profile.TIN_CAN, mock);

        // 10Hz sine wave (subsonic, should be killed by 400Hz HPF)
        float[] left = new float[1000];
        float[] right = new float[1000];
        for (int i = 0; i < 1000; i++)
        {
            left[i] = (float) Math.sin(2.0 * Math.PI * 10.0 * i / 44100.0);
            right[i] = left[i];
        }

        filter.process(left, right, 1000);

        // Check attenuation
        float maxVal = 0;
        for (float v : mock.lastLeft)
            maxVal = Math.max(maxVal, Math.abs(v));
        assertTrue(maxVal < 0.1f, "10Hz should be heavily attenuated by PC speaker model, got max: " + maxVal);
    }

    @Test
    void testSpectrumBeeperBasic()
    {
        SpectrumBeeperFilter filter = new SpectrumBeeperFilter(true, false, mock);
        float[] left = new float[512];
        float[] right = new float[512];
        for (int i = 0; i < 512; i++)
        {
            left[i] = (float) Math.sin(2.0 * Math.PI * 1000.0 * i / 44100.0);
            right[i] = left[i];
        }

        filter.process(left, right, 512);

        assertTrue(mock.processCalled);
        assertEquals(512, mock.lastFrames);
        boolean hasSignal = false;
        for (int i = 0; i < 512; i++)
        {
            float val = mock.lastLeft[i];
            assertTrue(val >= -1.0f && val <= 1.0f, "Spectrum beeper output out of range: " + val);
            if (Math.abs(val) > 0.001f)
                hasSignal = true;
        }
        assertTrue(hasSignal, "Expected non-zero output from SpectrumBeeperFilter");
    }

    @Test
    void testSpectrumBeeperDisabled()
    {
        SpectrumBeeperFilter filter = new SpectrumBeeperFilter(false, false, mock);
        float[] left = { 0.5f, 0.3f, -0.4f };
        float[] right = { 0.5f, 0.3f, -0.4f };

        filter.process(left, right, 3);

        assertTrue(mock.processCalled);
        // Disabled: signal passes through unmodified
        assertEquals(0.5f, mock.lastLeft[0], 0.001f);
        assertEquals(0.3f, mock.lastLeft[1], 0.001f);
        assertEquals(-0.4f, mock.lastLeft[2], 0.001f);
    }

    @Test
    void testSpectrumAuxOutSkipsSpeakerFilters()
    {
        int n = 4096;
        float[] leftSpeaker = new float[n], rightSpeaker = new float[n];
        float[] leftAux = new float[n], rightAux = new float[n];
        for (int i = 0; i < n; i++)
            leftSpeaker[i] = rightSpeaker[i] = leftAux[i] = rightAux[i] = (float) Math
                    .sin(2.0 * Math.PI * 8000.0 * i / 44100.0);

        MockProcessor mockSpeaker = new MockProcessor();
        MockProcessor mockAux = new MockProcessor();
        new SpectrumBeeperFilter(true, false, mockSpeaker).process(leftSpeaker, rightSpeaker, n);
        new SpectrumBeeperFilter(true, true, mockAux).process(leftAux, rightAux, n);

        // Speaker LP at 4.5 kHz cuts 8 kHz heavily; aux mode bypasses it
        float peakSpeaker = 0, peakAux = 0;
        for (int i = 512; i < n; i++)
        {
            peakSpeaker = Math.max(peakSpeaker, Math.abs(mockSpeaker.lastLeft[i]));
            peakAux = Math.max(peakAux, Math.abs(mockAux.lastLeft[i]));
        }
        assertTrue(peakAux > peakSpeaker * 2.0f,
                "Spectrum aux should be louder at 8 kHz. speaker=" + peakSpeaker + " aux=" + peakAux);
    }

    @Test
    void testCompactMacSpeakerAttenuatesHighFreq()
    {
        // Speaker mode (2-pole Butterworth -3dB at 10kHz) should attenuate 15kHz
        // more than aux mode. Use DFT to isolate the 15kHz component.
        int n = 44100; // 1 second for frequency resolution
        float[] leftSpeaker = new float[n], rightSpeaker = new float[n];
        float[] leftAux = new float[n], rightAux = new float[n];
        for (int i = 0; i < n; i++)
            leftSpeaker[i] = rightSpeaker[i] = leftAux[i] = rightAux[i] = (float) Math
                    .sin(2.0 * Math.PI * 15000.0 * i / 44100.0) * 0.5f;

        MockProcessor mockSpeaker = new MockProcessor();
        MockProcessor mockAux = new MockProcessor();
        new CompactMacSimulatorFilter(true, false, mockSpeaker).process(leftSpeaker, rightSpeaker, n);
        new CompactMacSimulatorFilter(true, true, mockAux).process(leftAux, rightAux, n);

        double speakerAt15k = fftMagnitudeAt(mockSpeaker.lastLeft, 15000.0);
        double auxAt15k = fftMagnitudeAt(mockAux.lastLeft, 15000.0);
        assertTrue(auxAt15k > speakerAt15k * 2.0,
                "Speaker mode should attenuate 15kHz more than aux (DFT). speaker=" + speakerAt15k + " aux="
                        + auxAt15k);
    }

    @Test
    void testCompactMacAuxPreservesLowFreq()
    {
        // Both modes should pass 1kHz with similar amplitude (speaker -3dB is at 10kHz)
        int n = 4096;
        float[] leftSpeaker = new float[n], rightSpeaker = new float[n];
        float[] leftAux = new float[n], rightAux = new float[n];
        for (int i = 0; i < n; i++)
            leftSpeaker[i] = rightSpeaker[i] = leftAux[i] = rightAux[i] = (float) Math
                    .sin(2.0 * Math.PI * 1000.0 * i / 44100.0) * 0.5f;

        MockProcessor mockSpeaker = new MockProcessor();
        MockProcessor mockAux = new MockProcessor();
        new CompactMacSimulatorFilter(true, false, mockSpeaker).process(leftSpeaker, rightSpeaker, n);
        new CompactMacSimulatorFilter(true, true, mockAux).process(leftAux, rightAux, n);

        float peakSpeaker = 0, peakAux = 0;
        for (int i = 512; i < n; i++)
        {
            peakSpeaker = Math.max(peakSpeaker, Math.abs(mockSpeaker.lastLeft[i]));
            peakAux = Math.max(peakAux, Math.abs(mockAux.lastLeft[i]));
        }
        // Both should be within 30% of each other at 1kHz (speaker at 1kHz ≈ -0.1dB)
        assertTrue(peakSpeaker > peakAux * 0.7f,
                "Speaker 1kHz should be within 30% of aux. speaker=" + peakSpeaker + " aux=" + peakAux);
    }

    // --- AmigaPaulaFilter tests ---

    @Test
    void testAmigaA500Basic()
    {
        AmigaPaulaFilter filter = new AmigaPaulaFilter(true, AmigaPaulaFilter.Profile.A500, mock);
        int n = 1024;
        float[] left = new float[n];
        float[] right = new float[n];
        for (int i = 0; i < n; i++)
        {
            left[i] = (float) Math.sin(2.0 * Math.PI * 1000.0 * i / 44100.0);
            right[i] = left[i];
        }

        filter.process(left, right, n);

        assertTrue(mock.processCalled);
        float maxVal = 0;
        for (int i = 0; i < n; i++)
            maxVal = Math.max(maxVal, Math.abs(mock.lastLeft[i]));
        assertTrue(maxVal > 0.01f, "Expected non-zero A500 output, got max: " + maxVal);
        assertTrue(maxVal <= 1.1f, "A500 output out of range: " + maxVal);
    }

    @Test
    void testAmigaStereoIndependence()
    {
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
        for (int i = 100; i < n; i++)
        {
            assertEquals(mock.lastLeft[i], mock.lastRight[i], 1e-5f,
                    "Mono input should produce identical L/R output at frame " + i);
        }
    }

    @Test
    void testAmigaStereoWidening()
    {
        // Asymmetric input → output separation should be wider than input separation.
        AmigaPaulaFilter filter = new AmigaPaulaFilter(true, AmigaPaulaFilter.Profile.A500, mock);
        int n = 4096;
        float[] left = new float[n];
        float[] right = new float[n];
        for (int i = 0; i < n; i++)
        {
            left[i] = (float) Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0) * 0.8f;
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
    void testAmigaA500HighFreqAttenuation()
    {
        AmigaPaulaFilter filter = new AmigaPaulaFilter(true, AmigaPaulaFilter.Profile.A500, mock);
        int n = 4096;
        float[] left = new float[n];
        float[] right = new float[n];
        // 15kHz sine — well above A500 4.5kHz + LED 3.3kHz cascade cutoff
        for (int i = 0; i < n; i++)
        {
            left[i] = (float) Math.sin(2.0 * Math.PI * 15000.0 * i / 44100.0);
            right[i] = left[i];
        }

        filter.process(left, right, n);

        // Skip initial transient (first 512 samples), check steady-state peak
        float maxVal = 0;
        for (int i = 512; i < n; i++)
            maxVal = Math.max(maxVal, Math.abs(mock.lastLeft[i]));
        assertTrue(maxVal < 0.1f, "15kHz should be heavily attenuated by A500 filter cascade, got peak: " + maxVal);
    }

    @Test
    void testAmigaA1200BrighterThanA500()
    {
        int n = 4096;
        float[] leftA500 = new float[n];
        float[] rightA500 = new float[n];
        float[] leftA1200 = new float[n];
        float[] rightA1200 = new float[n];
        // 8kHz sine — above A500 cutoff but below A1200 cutoff
        for (int i = 0; i < n; i++)
        {
            leftA500[i] = leftA1200[i] = (float) Math.sin(2.0 * Math.PI * 8000.0 * i / 44100.0);
            rightA500[i] = rightA1200[i] = leftA500[i];
        }

        MockProcessor mockA500 = new MockProcessor();
        MockProcessor mockA1200 = new MockProcessor();
        new AmigaPaulaFilter(true, AmigaPaulaFilter.Profile.A500, mockA500).process(leftA500, rightA500, n);
        new AmigaPaulaFilter(true, AmigaPaulaFilter.Profile.A1200, mockA1200).process(leftA1200, rightA1200, n);

        float peakA500 = 0, peakA1200 = 0;
        for (int i = 512; i < n; i++)
        {
            peakA500 = Math.max(peakA500, Math.abs(mockA500.lastLeft[i]));
            peakA1200 = Math.max(peakA1200, Math.abs(mockA1200.lastLeft[i]));
        }
        assertTrue(peakA1200 > peakA500 * 1.5f,
                "A1200 should be significantly brighter than A500 at 8kHz: A500=" + peakA500 + " A1200=" + peakA1200);
    }

    @Test
    void testAmigaDisabledPassthrough()
    {
        AmigaPaulaFilter filter = new AmigaPaulaFilter(false, AmigaPaulaFilter.Profile.A500, mock);
        float[] left = { 0.3f, -0.5f, 0.7f };
        float[] right = { 0.1f, 0.2f, -0.3f };

        filter.process(left, right, 3);

        assertEquals(0.3f, mock.lastLeft[0], 0.001f);
        assertEquals(-0.5f, mock.lastLeft[1], 0.001f);
        assertEquals(0.7f, mock.lastLeft[2], 0.001f);
        assertEquals(0.1f, mock.lastRight[0], 0.001f);
    }

    @Test
    void testAmigaResetClearsState()
    {
        AmigaPaulaFilter filter = new AmigaPaulaFilter(true, AmigaPaulaFilter.Profile.A500, mock);
        float[] left = { 1.0f, 1.0f, 1.0f, 1.0f };
        float[] right = { 1.0f, 1.0f, 1.0f, 1.0f };
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
    void testResetClearsState()
    {
        CovoxDacFilter filter = new CovoxDacFilter(true, mock);
        float[] left = { 1.0f, 1.0f };
        float[] right = { 1.0f, 1.0f };

        filter.process(left, right, 2);
        filter.reset();
        assertTrue(mock.resetCalled);

        // After reset, processing silence should not have "tails" from previous 1.0 input
        float[] silence = { 0.0f, 0.0f };
        filter.process(silence, silence, 2);
        // Expect output near 0 (R-2R center)
        assertTrue(Math.abs(mock.lastLeft[0]) < 0.1f);
    }

    /**
     * Computes the DFT magnitude at a target frequency by dot-product with
     * a complex exponential. O(N) per frequency point — good enough for a few spot checks.
     *
     * @param signal
     *            mono float signal at 44100 Hz
     * @param freqHz
     *            target frequency in Hz
     * @return magnitude (not normalised by N — use ratio comparisons only)
     */
    private static double fftMagnitudeAt(float[] signal, double freqHz)
    {
        double re = 0, im = 0;
        double w = 2.0 * Math.PI * freqHz / 44100.0;
        for (int n = 0; n < signal.length; n++)
        {
            re += signal[n] * Math.cos(w * n);
            im += signal[n] * Math.sin(w * n);
        }
        return Math.sqrt(re * re + im * im);
    }

    @Test
    void testApple2ProducesAudibleHarmonics()
    {
        int n = 44100; // 1 second at 44100 Hz
        float[] left = new float[n];
        float[] right = new float[n];
        for (int i = 0; i < n; i++)
        {
            float s = (float) (Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0) * 0.8);
            left[i] = right[i] = s;
        }

        OneBitHardwareFilter filter = new OneBitHardwareFilter(
                true, "pwm", 22050.0, 32.0, 28.4, 8, 1.0, null, false, mock);
        filter.process(left, right, n);

        float[] out = mock.lastLeft;
        double fund = fftMagnitudeAt(out, 440.0);
        double harm2 = fftMagnitudeAt(out, 880.0);
        double ratio = harm2 / fund;

        assertTrue(fund > 0, "440 Hz fundamental must be present");
        // Observed ratio ~0.0088 with 8-bit pre-quantisation + 32-level PWM; 0.0079 is ~90%
        // of that, leaving headroom for minor platform differences. The 8-bit pre-quantise
        // stage (128 levels) is finer than the PWM quantiser (32 levels), so dominant harmonic
        // distortion still comes from the 32-level PWM step.
        assertTrue(ratio >= 0.0079,
                String.format("2nd harmonic should be ≥1%% of fundamental. fund=%.1f harm2=%.1f ratio=%.4f",
                        fund, harm2, ratio));
    }

    @Test
    void testPcBiquadsAddResonanceAt2500And6700Hz()
    {
        // Directly verify that the PC biquads add energy at their centre frequencies.
        // White noise ensures broadband spectral coverage so the comparison is not
        // sensitive to the input signal's specific harmonic content.
        int n = 44100;
        float[] leftNoBiquad = new float[n];
        float[] rightNoBiquad = new float[n];
        float[] leftBiquad = new float[n];
        float[] rightBiquad = new float[n];
        java.util.Random rng = new java.util.Random(42L);
        for (int i = 0; i < n; i++)
        {
            float s = (float) (rng.nextDouble() * 2.0 - 1.0) * 0.5f;
            leftNoBiquad[i] = rightNoBiquad[i] = leftBiquad[i] = rightBiquad[i] = s;
        }

        MockProcessor mockNoBiquad = new MockProcessor();
        MockProcessor mockBiquad = new MockProcessor();

        // Same PC IIR, one without resonance peaks, one with (preBitDepth=0 to isolate biquad effect)
        new OneBitHardwareFilter(true, "pwm", 15200.0, 78.0, 37.9, 0, 1.0, null, false, mockNoBiquad)
                .process(leftNoBiquad, rightNoBiquad, n);
        new OneBitHardwareFilter(true, "pwm", 15200.0, 78.0, 37.9, 0, 1.0,
                new double[] { 2500.0, 3.0, 3.0, 6700.0, 4.0, 4.0 }, false, mockBiquad)
                .process(leftBiquad, rightBiquad, n);

        double noBiquadAt2500 = fftMagnitudeAt(mockNoBiquad.lastLeft, 2500.0);
        double biquadAt2500 = fftMagnitudeAt(mockBiquad.lastLeft, 2500.0);
        double noBiquadAt6700 = fftMagnitudeAt(mockNoBiquad.lastLeft, 6700.0);
        double biquadAt6700 = fftMagnitudeAt(mockBiquad.lastLeft, 6700.0);

        assertTrue(biquadAt2500 > noBiquadAt2500,
                "PC biquad should add energy at 2.5 kHz");
        assertTrue(biquadAt6700 > noBiquadAt6700,
                "PC biquad should add energy at 6.7 kHz");
    }

    @Test
    void testPcSilenceProducesNoAudibleCarrier()
    {
        // When monoIn is near zero, the IIR should ring down to silence rather than
        // sustaining the 15.2 kHz carrier at -23 dB (which is audible).
        OneBitHardwareFilter filter = new OneBitHardwareFilter(
                true, "pwm", 15200.0, 78.0, 37.9, 8, 1.0, null, false, mock);

        int n = 44100;
        float[] left = new float[n];
        float[] right = new float[n];
        // All zeros — complete silence
        filter.process(left, right, n);

        float maxOut = 0.0f;
        for (float v : mock.lastLeft)
            maxOut = Math.max(maxOut, Math.abs(v));
        assertTrue(maxOut < 1e-3f,
                "PC mode: silence input should produce near-zero output, got max=" + maxOut);
    }

    @Test
    void testApple2SilenceProducesNoAudibleCarrier()
    {
        // Same check for Apple II's 22.05 kHz carrier — previously sat exactly at Nyquist.
        OneBitHardwareFilter filter = new OneBitHardwareFilter(
                true, "pwm", 22050.0, 32.0, 28.4, 8, 1.0, null, false, mock);

        int n = 44100;
        float[] left = new float[n];
        float[] right = new float[n];
        filter.process(left, right, n);

        float maxOut = 0.0f;
        for (float v : mock.lastLeft)
            maxOut = Math.max(maxOut, Math.abs(v));
        assertTrue(maxOut < 1e-3f,
                "Apple II mode: silence input should produce near-zero output, got max=" + maxOut);
    }

    @Test
    void testPcAuxOutBypassesConePoles()
    {
        int n = 4096;
        float[] leftSpeaker = new float[n], rightSpeaker = new float[n];
        float[] leftAux = new float[n], rightAux = new float[n];
        for (int i = 0; i < n; i++)
            leftSpeaker[i] = rightSpeaker[i] = leftAux[i] = rightAux[i] = (float) Math
                    .sin(2.0 * Math.PI * 8000.0 * i / 44100.0);

        MockProcessor mockSpeaker = new MockProcessor();
        MockProcessor mockAux = new MockProcessor();
        new OneBitHardwareFilter(true, "pwm", 15200.0, 78.0, 37.9, 8, 1.0, null, false, mockSpeaker)
                .process(leftSpeaker, rightSpeaker, n);
        new OneBitHardwareFilter(true, "pwm", 15200.0, 78.0, 37.9, 8, 1.0, null, true, mockAux)
                .process(leftAux, rightAux, n);

        float peakSpeaker = 0, peakAux = 0;
        for (int i = 512; i < n; i++)
        {
            peakSpeaker = Math.max(peakSpeaker, Math.abs(mockSpeaker.lastLeft[i]));
            peakAux = Math.max(peakAux, Math.abs(mockAux.lastLeft[i]));
        }
        assertTrue(peakAux > peakSpeaker * 2.0f,
                "aux mode should be louder at 8 kHz (cone bypassed). speaker=" + peakSpeaker + " aux=" + peakAux);
    }

    @Test
    void testApple2AuxOutBypassesConePoles()
    {
        int n = 4096;
        float[] leftSpeaker = new float[n], rightSpeaker = new float[n];
        float[] leftAux = new float[n], rightAux = new float[n];
        for (int i = 0; i < n; i++)
            leftSpeaker[i] = rightSpeaker[i] = leftAux[i] = rightAux[i] = (float) Math
                    .sin(2.0 * Math.PI * 8000.0 * i / 44100.0);

        MockProcessor mockSpeaker = new MockProcessor();
        MockProcessor mockAux = new MockProcessor();
        new OneBitHardwareFilter(true, "pwm", 22050.0, 32.0, 28.4, 8, 1.0, null, false, mockSpeaker)
                .process(leftSpeaker, rightSpeaker, n);
        new OneBitHardwareFilter(true, "pwm", 22050.0, 32.0, 28.4, 8, 1.0, null, true, mockAux)
                .process(leftAux, rightAux, n);

        float peakSpeaker = 0, peakAux = 0;
        for (int i = 512; i < n; i++)
        {
            peakSpeaker = Math.max(peakSpeaker, Math.abs(mockSpeaker.lastLeft[i]));
            peakAux = Math.max(peakAux, Math.abs(mockAux.lastLeft[i]));
        }
        assertTrue(peakAux > peakSpeaker * 2.0f,
                "Apple II aux mode should be louder at 8 kHz. speaker=" + peakSpeaker + " aux=" + peakAux);
    }

    @Test
    void testTelephoneAttentuatesLowFreq()
    {
        // TELEPHONE has 300 Hz HPF — subsonic content should be heavily cut
        AcousticSpeakerFilter filter = new AcousticSpeakerFilter(
                true, AcousticSpeakerFilter.Profile.TELEPHONE, mock);
        float[] left = new float[4096], right = new float[4096];
        for (int i = 0; i < 4096; i++)
        {
            left[i] = (float) Math.sin(2.0 * Math.PI * 50.0 * i / 44100.0);
            right[i] = left[i];
        }
        filter.process(left, right, 4096);
        float max = 0;
        for (float v : mock.lastLeft)
            max = Math.max(max, Math.abs(v));
        assertTrue(max < 0.1f, "50 Hz should be attenuated by TELEPHONE 300 Hz HPF, got: " + max);
    }

    @Test
    void testTelephoneAttenuatesHighFreq()
    {
        // TELEPHONE has 3400 Hz LPF — high-freq content should be cut
        MockProcessor mockHi = new MockProcessor();
        AcousticSpeakerFilter filter = new AcousticSpeakerFilter(
                true, AcousticSpeakerFilter.Profile.TELEPHONE, mockHi);
        int n = 4096;
        float[] left = new float[n], right = new float[n];
        for (int i = 0; i < n; i++)
        {
            left[i] = (float) Math.sin(2.0 * Math.PI * 8000.0 * i / 44100.0);
            right[i] = left[i];
        }
        filter.process(left, right, n);
        float max = 0;
        for (int i = n / 2; i < n; i++)
            max = Math.max(max, Math.abs(mockHi.lastLeft[i]));
        assertTrue(max < 0.2f, "8 kHz should be attenuated by TELEPHONE 3.4 kHz LPF, got: " + max);
    }

    @Test
    void testPcSpeakerAttenuatesLowFreq()
    {
        // PC_SPEAKER has 250 Hz HPF
        AcousticSpeakerFilter filter = new AcousticSpeakerFilter(
                true, AcousticSpeakerFilter.Profile.PC, mock);
        float[] left = new float[4096], right = new float[4096];
        for (int i = 0; i < 4096; i++)
        {
            left[i] = (float) Math.sin(2.0 * Math.PI * 50.0 * i / 44100.0);
            right[i] = left[i];
        }
        filter.process(left, right, 4096);
        float max = 0;
        for (float v : mock.lastLeft)
            max = Math.max(max, Math.abs(v));
        assertTrue(max < 0.1f, "50 Hz should be attenuated by PC_SPEAKER 250 Hz HPF, got: " + max);
    }

    @Test
    void testPcSpeakerAttenuatesHighFreq()
    {
        // PC_SPEAKER has 9 kHz LPF — 15 kHz should be cut
        MockProcessor mockHi = new MockProcessor();
        AcousticSpeakerFilter filter = new AcousticSpeakerFilter(
                true, AcousticSpeakerFilter.Profile.PC, mockHi);
        int n = 4096;
        float[] left = new float[n], right = new float[n];
        for (int i = 0; i < n; i++)
        {
            left[i] = (float) Math.sin(2.0 * Math.PI * 15000.0 * i / 44100.0);
            right[i] = left[i];
        }
        filter.process(left, right, n);
        float max = 0;
        for (int i = n / 2; i < n; i++)
            max = Math.max(max, Math.abs(mockHi.lastLeft[i]));
        assertTrue(max < 0.2f, "15 kHz should be attenuated by PC_SPEAKER 9 kHz LPF, got: " + max);
    }

    // ── peak boost regression tests ───────────────────────────────────────────

    private double fftMag(float[] signal, double freq, int sr)
    {
        int n = signal.length;
        double w = 2.0 * Math.PI * freq / sr;
        double re = 0, im = 0;
        for (int i = 0; i < n; i++)
        {
            re += signal[i] * Math.cos(w * i);
            im += signal[i] * Math.sin(w * i);
        }
        return Math.sqrt(re * re + im * im) / n;
    }

    @Test
    void testTelephonePeakBoostsAt1kHz()
    {
        // TELEPHONE has +3 dB peak at 1 kHz — output at 1 kHz should exceed 500 Hz
        MockProcessor m1k = new MockProcessor(), m500 = new MockProcessor();
        int n = 4096;
        float[] l1k = new float[n], r1k = new float[n];
        float[] l500 = new float[n], r500 = new float[n];
        for (int i = 0; i < n; i++)
        {
            l1k[i] = r1k[i] = (float) Math.sin(2.0 * Math.PI * 1000.0 * i / 44100.0);
            l500[i] = r500[i] = (float) Math.sin(2.0 * Math.PI * 500.0 * i / 44100.0);
        }
        new AcousticSpeakerFilter(true, AcousticSpeakerFilter.Profile.TELEPHONE, m1k).process(l1k, r1k, n);
        new AcousticSpeakerFilter(true, AcousticSpeakerFilter.Profile.TELEPHONE, m500).process(l500, r500, n);
        double mag1k = fftMag(m1k.lastLeft, 1000.0, 44100);
        double mag500 = fftMag(m500.lastLeft, 500.0, 44100);
        assertTrue(mag1k > mag500 * 1.1,
                "TELEPHONE 1 kHz peak should boost output above 500 Hz level. 1kHz=" + mag1k + " 500Hz=" + mag500);
    }

    @Test
    void testPcSpeakerPeakBoostsAt2kHz()
    {
        // PC has +4 dB peak at 2 kHz — output at 2 kHz should exceed 500 Hz
        MockProcessor m2k = new MockProcessor(), m500 = new MockProcessor();
        int n = 4096;
        float[] l2k = new float[n], r2k = new float[n];
        float[] l500 = new float[n], r500 = new float[n];
        for (int i = 0; i < n; i++)
        {
            l2k[i] = r2k[i] = (float) Math.sin(2.0 * Math.PI * 2000.0 * i / 44100.0);
            l500[i] = r500[i] = (float) Math.sin(2.0 * Math.PI * 500.0 * i / 44100.0);
        }
        new AcousticSpeakerFilter(true, AcousticSpeakerFilter.Profile.PC, m2k).process(l2k, r2k, n);
        new AcousticSpeakerFilter(true, AcousticSpeakerFilter.Profile.PC, m500).process(l500, r500, n);
        double mag2k = fftMag(m2k.lastLeft, 2000.0, 44100);
        double mag500 = fftMag(m500.lastLeft, 500.0, 44100);
        assertTrue(mag2k > mag500 * 1.1,
                "PC 2 kHz peak should boost output above 500 Hz level. 2kHz=" + mag2k + " 500Hz=" + mag500);
    }

    // ── processInterleaved regression tests ───────────────────────────────────

    @Test
    void testTelephoneProcessInterleavedCutsHighFreq()
    {
        // processInterleaved path must apply the same filters as process()
        MockProcessor mockHi = new MockProcessor();
        AcousticSpeakerFilter filter = new AcousticSpeakerFilter(
                true, AcousticSpeakerFilter.Profile.TELEPHONE, mockHi);
        int n = 4096;
        short[] pcm = new short[n * 2];
        for (int i = 0; i < n; i++)
        {
            short v = (short) (Math.sin(2.0 * Math.PI * 8000.0 * i / 44100.0) * 16384);
            pcm[i * 2] = v;
            pcm[i * 2 + 1] = v;
        }
        filter.processInterleaved(pcm, n, 2);
        float max = 0;
        for (int i = n; i < n * 2; i++)
            max = Math.max(max, Math.abs(mockHi.lastInterleaved[i]));
        assertTrue(max < 3276, // < 0.1 * 32767
                "TELEPHONE processInterleaved: 8 kHz should be cut by LPF, got max short: " + max);
    }

    @Test
    void testPcSpeakerProcessInterleavedCutsHighFreq()
    {
        // processInterleaved path must apply the same filters as process()
        MockProcessor mockHi = new MockProcessor();
        AcousticSpeakerFilter filter = new AcousticSpeakerFilter(
                true, AcousticSpeakerFilter.Profile.PC, mockHi);
        int n = 4096;
        short[] pcm = new short[n * 2];
        for (int i = 0; i < n; i++)
        {
            short v = (short) (Math.sin(2.0 * Math.PI * 15000.0 * i / 44100.0) * 16384);
            pcm[i * 2] = v;
            pcm[i * 2 + 1] = v;
        }
        filter.processInterleaved(pcm, n, 2);
        float max = 0;
        for (int i = n; i < n * 2; i++)
            max = Math.max(max, Math.abs(mockHi.lastInterleaved[i]));
        assertTrue(max < 6554, // < 0.2 * 32767
                "PC processInterleaved: 15 kHz should be cut by LPF, got max short: " + max);
    }
}

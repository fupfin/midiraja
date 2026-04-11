/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.dsp;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SpectrumAnalyzerFilterTest
{
    /** Levels are stored interleaved: even index = L band, odd index = R band. */
    private static final int BAND_COUNT = 8;
    private static final int SAMPLE_RATE = 44100;
    private static final int FFT_SIZE = 1024;

    /** Sink that discards audio — used to terminate the processor chain. */
    private static final AudioProcessor SINK = new AudioProcessor()
    {
        @Override
        public void process(float[] left, float[] right, int frames)
        {
        }

        @Override
        public void processInterleaved(short[] pcm, int frames, int channels)
        {
        }

        @Override
        public void reset()
        {
        }
    };

    // ── FFT ──────────────────────────────────────────────────────────────────

    @Test
    void fft_dcInput_concentratesEnergyAtBinZero()
    {
        float[] re = new float[FFT_SIZE];
        float[] im = new float[FFT_SIZE];
        java.util.Arrays.fill(re, 1.0f);

        SpectrumAnalyzerFilter.fft(re, im);

        double dcMag = Math.sqrt(re[0] * re[0] + im[0] * im[0]);
        assertTrue(dcMag > 100, "DC magnitude at bin 0 should dominate");
        for (int i = 1; i < FFT_SIZE; i++)
        {
            double mag = Math.sqrt(re[i] * re[i] + im[i] * im[i]);
            assertTrue(mag < 1e-3, "Bin " + i + " should be ~0 for DC input, was " + mag);
        }
    }

    @Test
    void fft_pureIntegerBinSine_concentratesEnergyAtThatBin()
    {
        int targetBin = 10;
        float[] re = new float[FFT_SIZE];
        float[] im = new float[FFT_SIZE];
        for (int i = 0; i < FFT_SIZE; i++)
            re[i] = (float) Math.sin(2 * Math.PI * targetBin * i / FFT_SIZE);

        SpectrumAnalyzerFilter.fft(re, im);

        double peakMag = Math.sqrt(re[targetBin] * re[targetBin] + im[targetBin] * im[targetBin]);
        assertTrue(peakMag > 100, "Target bin " + targetBin + " magnitude should be large");
        for (int i = 0; i < FFT_SIZE / 2; i++)
        {
            if (i == targetBin || i == FFT_SIZE - targetBin)
                continue;
            double mag = Math.sqrt(re[i] * re[i] + im[i] * im[i]);
            assertTrue(mag < peakMag * 0.01,
                    "Bin " + i + " should be much less than the target bin");
        }
    }

    @Test
    void fft_impulse_flatMagnitudeSpectrum()
    {
        float[] re = new float[FFT_SIZE];
        float[] im = new float[FFT_SIZE];
        re[0] = 1.0f; // unit impulse at t=0

        SpectrumAnalyzerFilter.fft(re, im);

        // All bins should have magnitude ≈ 1.0
        for (int i = 0; i < FFT_SIZE; i++)
        {
            double mag = Math.sqrt(re[i] * re[i] + im[i] * im[i]);
            assertEquals(1.0, mag, 1e-5, "Impulse FFT bin " + i + " magnitude should be 1.0");
        }
    }

    // ── Transparency ─────────────────────────────────────────────────────────

    @Test
    void process_passesAudioThroughUnmodified()
    {
        float[] refL = new float[256];
        float[] refR = new float[256];
        for (int i = 0; i < 256; i++)
        {
            refL[i] = (float) Math.sin(i * 0.1);
            refR[i] = (float) Math.cos(i * 0.1);
        }

        float[][] captured = {null, null};
        AudioProcessor captureSink = new AudioProcessor()
        {
            @Override
            public void process(float[] l, float[] r, int frames)
            {
                captured[0] = l.clone();
                captured[1] = r.clone();
            }

            @Override
            public void processInterleaved(short[] pcm, int frames, int channels)
            {
            }

            @Override
            public void reset()
            {
            }
        };

        SpectrumAnalyzerFilter filter = new SpectrumAnalyzerFilter(captureSink);
        filter.process(refL.clone(), refR.clone(), 256);

        assertArrayEquals(refL, captured[0], 1e-6f, "Left channel must pass through unmodified");
        assertArrayEquals(refR, captured[1], 1e-6f, "Right channel must pass through unmodified");
    }

    @Test
    void processInterleaved_passesAudioThroughUnmodified()
    {
        short[] pcm = new short[512]; // 256 stereo frames
        for (int i = 0; i < 512; i++)
            pcm[i] = (short) (i * 50);

        short[][] captured = {null};
        AudioProcessor captureSink = new AudioProcessor()
        {
            @Override
            public void process(float[] l, float[] r, int frames)
            {
            }

            @Override
            public void processInterleaved(short[] p, int frames, int channels)
            {
                captured[0] = p.clone();
            }

            @Override
            public void reset()
            {
            }
        };

        SpectrumAnalyzerFilter filter = new SpectrumAnalyzerFilter(captureSink);
        filter.processInterleaved(pcm.clone(), 256, 2);

        assertArrayEquals(pcm, captured[0], "Interleaved PCM must pass through unmodified");
    }

    // ── Spectrum levels ──────────────────────────────────────────────────────

    @Test
    void process_silence_allLevelsNearZero()
    {
        SpectrumAnalyzerFilter filter = new SpectrumAnalyzerFilter(SINK);
        float[] silence = new float[FFT_SIZE];
        filter.process(silence, silence, FFT_SIZE);

        float[] levels = filter.getLevels();
        for (int i = 0; i < 16; i++)
            assertTrue(levels[i] < 0.01f, "Band " + i + " should be near zero during silence");
    }

    @Test
    void processInterleaved_silence_allLevelsNearZero()
    {
        SpectrumAnalyzerFilter filter = new SpectrumAnalyzerFilter(SINK);
        short[] silence = new short[FFT_SIZE * 2]; // FFT_SIZE stereo frames
        filter.processInterleaved(silence, FFT_SIZE, 2);

        float[] levels = filter.getLevels();
        for (int i = 0; i < 16; i++)
            assertTrue(levels[i] < 0.01f, "Band " + i + " should be near zero during silence");
    }

    @Test
    void process_sineAt1kHz_elevatesBand4()
    {
        // Band 4: 720–1440 Hz; indices 8 (L) and 9 (R) in the interleaved levels array
        float freq = 1000.0f;
        float[] signal = sineWave(freq, FFT_SIZE);

        SpectrumAnalyzerFilter filter = new SpectrumAnalyzerFilter(SINK);
        filter.process(signal.clone(), signal.clone(), FFT_SIZE);

        float[] levels = filter.getLevels();
        float band4L = levels[2 * 4];     // even index = L for band 4
        float band4R = levels[2 * 4 + 1]; // odd  index = R for band 4

        assertTrue(band4L > 0.1f, "Band 4 L should be elevated at 1 kHz, was " + band4L);
        assertTrue(band4R > 0.1f, "Band 4 R should be elevated at 1 kHz, was " + band4R);

        // Low-frequency bands should be much quieter
        assertTrue(levels[0] < band4L, "Band 0 L (63 Hz) should be lower than band 4");
        assertTrue(levels[2] < band4L, "Band 1 L (125 Hz) should be lower than band 4");
    }

    @Test
    void process_sineAt4kHz_elevatesBand6()
    {
        // Band 6: 2880–5760 Hz; indices 12 (L) and 13 (R)
        float freq = 4000.0f;
        float[] signal = sineWave(freq, FFT_SIZE);

        SpectrumAnalyzerFilter filter = new SpectrumAnalyzerFilter(SINK);
        filter.process(signal.clone(), signal.clone(), FFT_SIZE);

        float[] levels = filter.getLevels();
        float band6L = levels[2 * 6];
        float band6R = levels[2 * 6 + 1];

        assertTrue(band6L > 0.1f, "Band 6 L should be elevated at 4 kHz, was " + band6L);
        assertTrue(band6R > 0.1f, "Band 6 R should be elevated at 4 kHz, was " + band6R);

        // Band 0 (63 Hz) should be much quieter
        assertTrue(levels[0] < band6L, "Band 0 should be lower than band 6 for 4 kHz input");
    }

    @Test
    void process_stereoIndependence_leftAndRightLevelsIndependent()
    {
        // Left: 2 kHz (band 5: 1440–2880 Hz), Right: silence
        float freq = 2000.0f;
        float[] left = sineWave(freq, FFT_SIZE);
        float[] right = new float[FFT_SIZE];

        SpectrumAnalyzerFilter filter = new SpectrumAnalyzerFilter(SINK);
        filter.process(left, right, FFT_SIZE);

        float[] levels = filter.getLevels();
        float band5L = levels[2 * 5];     // index 10
        float band5R = levels[2 * 5 + 1]; // index 11

        assertTrue(band5L > 0.1f, "Left band 5 should be elevated for 2 kHz left input");
        assertTrue(band5R < band5L * 0.1f, "Right band 5 should be near zero for silent right channel");
    }

    @Test
    void processInterleaved_monoInput_mirroredToLeftAndRight()
    {
        // Mono: channels=1 → both L and R receive the same sample
        float freq = 1000.0f;
        float[] signal = sineWave(freq, FFT_SIZE);
        short[] pcm = new short[FFT_SIZE]; // mono — one sample per frame
        for (int i = 0; i < FFT_SIZE; i++)
            pcm[i] = (short) (signal[i] * 32767);

        SpectrumAnalyzerFilter filter = new SpectrumAnalyzerFilter(SINK);
        filter.processInterleaved(pcm, FFT_SIZE, 1);

        float[] levels = filter.getLevels();
        // L and R of the same band should be equal (both receive identical mono input)
        for (int b = 0; b < BAND_COUNT; b++)
            assertEquals(levels[2 * b], levels[2 * b + 1], 1e-6f,
                    "Mono input: L and R of band " + b + " should be equal");
    }

    // ── Decay ────────────────────────────────────────────────────────────────

    @Test
    void process_afterLoudThenSilence_levelsDecay()
    {
        float freq = 1000.0f;
        float[] loud = sineWave(freq, FFT_SIZE);
        float[] silence = new float[FFT_SIZE];

        SpectrumAnalyzerFilter filter = new SpectrumAnalyzerFilter(SINK);

        // Build up levels with 10 loud frames
        for (int f = 0; f < 10; f++)
            filter.process(loud.clone(), loud.clone(), FFT_SIZE);

        float sumAfterLoud = levelSum(filter.getLevels());

        // Then feed 10 silent frames
        for (int f = 0; f < 10; f++)
            filter.process(silence, silence, FFT_SIZE);

        float sumAfterSilence = levelSum(filter.getLevels());

        assertTrue(sumAfterSilence < sumAfterLoud,
                "Levels should decay after switching from loud to silence");
    }

    // ── Buffer buffering and wraparound ───────────────────────────────────────

    @Test
    void process_smallChunks_accumulates()
    {
        // Feed FFT_SIZE samples in small 32-sample chunks; exactly one analysis should trigger
        float freq = 1000.0f;
        float[] full = sineWave(freq, FFT_SIZE);

        SpectrumAnalyzerFilter filter = new SpectrumAnalyzerFilter(SINK);

        int chunk = 32;
        float[] l = new float[chunk];
        float[] r = new float[chunk];
        for (int offset = 0; offset < FFT_SIZE; offset += chunk)
        {
            System.arraycopy(full, offset, l, 0, chunk);
            System.arraycopy(full, offset, r, 0, chunk);
            filter.process(l, r, chunk);
        }

        assertTrue(levelSum(filter.getLevels()) > 0, "Levels should be non-zero after full FFT_SIZE samples fed in small chunks");
    }

    @Test
    void process_moreThanFftSize_triggersMultipleAnalyses()
    {
        float freq = 1000.0f;
        // Feed 2.5 × FFT_SIZE samples to force two complete analyses and a partial fill
        int total = FFT_SIZE * 2 + FFT_SIZE / 2;
        float[] signal = sineWave(freq, total);
        float[] right = new float[total];

        SpectrumAnalyzerFilter filter = new SpectrumAnalyzerFilter(SINK);
        filter.process(signal, right, total);

        assertTrue(levelSum(filter.getLevels()) > 0, "Levels should be non-zero after multiple FFT frames");
    }

    // ── Reset ─────────────────────────────────────────────────────────────────

    @Test
    void reset_clearsLevels()
    {
        float[] signal = sineWave(1000.0f, FFT_SIZE);
        SpectrumAnalyzerFilter filter = new SpectrumAnalyzerFilter(SINK);
        filter.process(signal.clone(), signal.clone(), FFT_SIZE);

        assertTrue(levelSum(filter.getLevels()) > 0, "Precondition: some levels must be non-zero");

        filter.reset();

        float[] after = filter.getLevels();
        for (int i = 0; i < 16; i++)
            assertEquals(0.0f, after[i], "Level index " + i + " must be zero after reset");
    }

    @Test
    void reset_clearsBuffer_thenRebuildsFromFresh()
    {
        // Fill half the buffer, reset, feed a full FFT_SIZE — levels should reflect only post-reset data
        float[] half = sineWave(8000.0f, FFT_SIZE / 2);
        float[] silence = new float[FFT_SIZE / 2];

        SpectrumAnalyzerFilter filter = new SpectrumAnalyzerFilter(SINK);
        filter.process(half, silence, FFT_SIZE / 2); // partial fill
        filter.reset();

        // Now feed 1 kHz to produce a clean analysis
        float[] oneKhz = sineWave(1000.0f, FFT_SIZE);
        filter.process(oneKhz.clone(), oneKhz.clone(), FFT_SIZE);

        float[] levels = filter.getLevels();
        float band4L = levels[2 * 4];
        assertTrue(band4L > 0.1f, "After reset, band 4 L should respond to 1 kHz input");
        // High-frequency band should not be contaminated by the pre-reset 8 kHz data
        assertTrue(levels[2 * 7] < band4L, "Band 7 should be lower than band 4 for 1 kHz post-reset");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static float[] sineWave(float freq, int length)
    {
        float[] buf = new float[length];
        for (int i = 0; i < length; i++)
            buf[i] = (float) Math.sin(2 * Math.PI * freq * i / SAMPLE_RATE);
        return buf;
    }

    private static float levelSum(float[] levels)
    {
        float sum = 0;
        for (float v : levels)
            sum += v;
        return sum;
    }
}

/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.dsp;

/**
 * Transparent {@link AudioProcessor} that computes an 8-band stereo spectrum.
 *
 * <p>
 * Produces 16 normalised level values stored in interleaved order: even indices hold left-channel
 * band levels (L0, L1, …, L7 at indices 0, 2, 4, …, 14) and odd indices hold right-channel band
 * levels (R0, R1, …, R7 at indices 1, 3, 5, …, 15). Each value is in [0, 1]. The filter is
 * transparent — audio passes through unmodified.
 *
 * <p>
 * Bands are log-spaced with centre frequencies approximately 63 Hz, 125 Hz, 250 Hz, 500 Hz,
 * 1 kHz, 2 kHz, 4 kHz, 8 kHz. Level is computed via a 1024-point windowed FFT applied to
 * accumulated input samples; a per-frame exponential decay prevents abrupt level drops.
 */
public class SpectrumAnalyzerFilter implements AudioProcessor
{
    /** Human-readable label for each of the 8 frequency bands. */
    public static final String[] BAND_LABELS = {
            "63Hz", "125Hz", "250Hz", "500Hz", "1kHz", "2kHz", "4kHz", "8kHz"
    };

    private static final int SAMPLE_RATE = 44100;
    private static final int FFT_SIZE = 1024;
    private static final int BANDS = 8;
    /** Lower and upper Hz boundaries for each band. */
    private static final float[] BAND_EDGES = { 40, 100, 180, 360, 720, 1440, 2880, 5760, 20000 };
    /** Per-FFT-frame decay applied to the previous peak level. */
    private static final float DECAY = 0.85f;
    /** dB range mapped to [0, 1]: −40 dB → 0, 0 dB → 1. */
    private static final float DB_RANGE = 40.0f;
    /**
     * AGC headroom: reference = recentMax × HEADROOM, targeting the loudest band at ~75%.
     * 10^0.5 ≈ 3.16 → peak maps to (−10 dB + 40) / 40 = 0.75.
     */
    private static final float REFERENCE_HEADROOM = (float) Math.pow(10.0, 0.5);
    /**
     * AGC rise rate per FFT frame. Controls transient rejection (≈ P95 approximation):
     * recentMax moves this fraction toward a new higher peak each frame.
     * At 0.1, reaching 95% of a sustained new level takes ~28 frames (~650 ms),
     * so brief transients (kick drum, etc.) are largely ignored.
     */
    private static final float MAX_RISE_RATE = 0.1f;
    /** Per-FFT-frame decay factor for the AGC peak tracker. */
    private static final float MAX_DECAY = 0.99f;
    /** Minimum reference floor to avoid division by zero during silence. */
    private static final float MAX_FLOOR = 1e-5f;

    private final AudioProcessor next;

    private final float[] bufL = new float[FFT_SIZE];
    private final float[] bufR = new float[FFT_SIZE];
    private int bufPos = 0;
    /** Levels in interleaved order [L0,R0,L1,R1,…,L7,R7]. Updated atomically by replacing the array reference. */
    private volatile float[] levels = new float[16];
    /** AGC: tracks recent peak magnitude; updated only from the audio-processing thread. */
    private float recentMax = 0.1f;

    public SpectrumAnalyzerFilter(AudioProcessor next)
    {
        this.next = next;
    }

    /**
     * Returns the current 16-element level array (L0–L7, R0–R7). Values are in [0, 1].
     * The returned array is a snapshot; the reference may be replaced on each FFT frame.
     */
    public float[] getLevels()
    {
        return levels;
    }

    @Override
    public void process(float[] left, float[] right, int frames)
    {
        for (int i = 0; i < frames; i++)
        {
            bufL[bufPos] = left[i];
            bufR[bufPos] = right[i];
            bufPos++;
            if (bufPos == FFT_SIZE)
            {
                analyzeAndUpdate();
                bufPos = 0;
            }
        }
        next.process(left, right, frames);
    }

    @Override
    public void processInterleaved(short[] pcm, int frames, int channels)
    {
        for (int i = 0; i < frames; i++)
        {
            int idx = i * channels;
            float l = pcm[idx] / 32768.0f;
            float r = channels > 1 ? pcm[idx + 1] / 32768.0f : l;
            bufL[bufPos] = l;
            bufR[bufPos] = r;
            bufPos++;
            if (bufPos == FFT_SIZE)
            {
                analyzeAndUpdate();
                bufPos = 0;
            }
        }
        next.processInterleaved(pcm, frames, channels);
    }

    @Override
    public void reset()
    {
        bufPos = 0;
        recentMax = 0.1f;
        levels = new float[16];
        next.reset();
    }

    // ── Analysis ──────────────────────────────────────────────────────────────

    private void analyzeAndUpdate()
    {
        float[] magL = computeMagnitudes(bufL);
        float[] magR = computeMagnitudes(bufR);
        float framePeak = Math.max(peakMagnitude(magL), peakMagnitude(magR));
        if (framePeak > recentMax)
            recentMax = recentMax + (framePeak - recentMax) * MAX_RISE_RATE;
        else
            recentMax = recentMax * MAX_DECAY;
        recentMax = Math.max(recentMax, MAX_FLOOR);
        float ref = recentMax * REFERENCE_HEADROOM;
        float[] newLevels = new float[16];
        for (int b = 0; b < BANDS; b++)
        {
            newLevels[b * 2] = bandEnergy(magL, b, ref);
            newLevels[b * 2 + 1] = bandEnergy(magR, b, ref);
        }
        applyDecay(newLevels);
        levels = newLevels;
    }

    private static float peakMagnitude(float[] mag)
    {
        float peak = 0;
        for (float m : mag)
            if (m > peak)
                peak = m;
        return peak;
    }

    private void applyDecay(float[] newLevels)
    {
        float[] old = levels;
        for (int i = 0; i < 16; i++)
        {
            float decayed = old[i] * DECAY;
            if (newLevels[i] < decayed)
                newLevels[i] = decayed;
        }
    }

    private static float[] computeMagnitudes(float[] samples)
    {
        float[] re = applyHannWindow(samples);
        float[] im = new float[FFT_SIZE];
        fft(re, im);
        return computeMagnitudeSpectrum(re, im);
    }

    private static float[] applyHannWindow(float[] samples)
    {
        float[] re = new float[FFT_SIZE];
        for (int i = 0; i < FFT_SIZE; i++)
        {
            float w = 0.5f * (1 - (float) Math.cos(2 * Math.PI * i / (FFT_SIZE - 1)));
            re[i] = samples[i] * w;
        }
        return re;
    }

    private static float[] computeMagnitudeSpectrum(float[] re, float[] im)
    {
        float[] mag = new float[FFT_SIZE / 2];
        // Divide by FFT_SIZE/2 so a full-scale sine at any frequency produces magnitude ≈ 1.
        for (int i = 0; i < FFT_SIZE / 2; i++)
            mag[i] = (float) Math.sqrt(re[i] * re[i] + im[i] * im[i]) / (FFT_SIZE / 2);
        return mag;
    }

    private static float bandEnergy(float[] mag, int band, float ref)
    {
        float binWidth = (float) SAMPLE_RATE / FFT_SIZE;
        int lo = Math.max(1, (int) (BAND_EDGES[band] / binWidth));
        int hi = Math.min(FFT_SIZE / 2 - 1, (int) (BAND_EDGES[band + 1] / binWidth));
        if (lo > hi)
            return 0;
        float peak = 0;
        for (int i = lo; i <= hi; i++)
            if (mag[i] > peak)
                peak = mag[i];
        if (peak <= 0)
            return 0;
        float db = 20 * (float) Math.log10(peak / ref);
        return Math.max(0, Math.min(1.0f, (db + DB_RANGE) / DB_RANGE));
    }

    // ── Cooley-Tukey radix-2 FFT ──────────────────────────────────────────────

    /** In-place radix-2 Cooley-Tukey FFT on arrays of length power-of-two. */
    static void fft(float[] re, float[] im)
    {
        int n = re.length;
        bitReversalPermutation(re, im, n);
        butterflyStages(re, im, n);
    }

    private static void bitReversalPermutation(float[] re, float[] im, int n)
    {
        for (int i = 1, j = 0; i < n; i++)
        {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1)
                j ^= bit;
            j ^= bit;
            if (i < j)
            {
                float t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
    }

    private static void butterflyStages(float[] re, float[] im, int n)
    {
        for (int len = 2; len <= n; len <<= 1)
        {
            float ang = (float) (-2 * Math.PI / len);
            float wRe = (float) Math.cos(ang);
            float wIm = (float) Math.sin(ang);
            for (int i = 0; i < n; i += len)
                butterflyGroup(re, im, i, len, wRe, wIm);
        }
    }

    private static void butterflyGroup(float[] re, float[] im, int i, int len,
            float wRe, float wIm)
    {
        float curRe = 1, curIm = 0;
        for (int j = 0; j < len / 2; j++)
        {
            int a = i + j;
            int b = i + j + len / 2;
            float vRe = re[b] * curRe - im[b] * curIm;
            float vIm = re[b] * curIm + im[b] * curRe;
            re[b] = re[a] - vRe;
            im[b] = im[a] - vIm;
            re[a] = re[a] + vRe;
            im[a] = im[a] + vIm;
            float newCurRe = curRe * wRe - curIm * wIm;
            curIm = curRe * wIm + curIm * wRe;
            curRe = newCurRe;
        }
    }
}

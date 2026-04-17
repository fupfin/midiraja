/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.export.vgm;

/**
 * OKI MSM6258 4-bit ADPCM encoder and synthetic drum sample generator.
 *
 * <p>
 * Algorithm matches {@code okim6258.c} from libvgm / MAME:
 * <ul>
 *   <li>Step table: {@code stepval[i] = floor(16 * (11/10)^i)}, i = 0..48
 *   <li>Diff from nibble bits: {@code sv/8 + (b0?sv/4:0) + (b1?sv/2:0) + (b2?sv:0)}, sign = b3
 *   <li>Signal update: {@code signal = ((diff << 8) + (signal * 245)) >> 8}, clamped to [-2048, 2047]
 *   <li>Step update: {@code step += {-1,-1,-1,-1,2,4,6,8}[nibble & 7]}, clamped to [0, 48]
 *   <li>Initial encoder state: signal = -2, step = 0
 *   <li>Output format: packed — two nibbles per byte, low nibble first, then high nibble
 * </ul>
 */
final class OkiAdpcmCodec
{
    /** OKIM6258 sample rate for X68000: 8 MHz clock / 512 divider. */
    static final int SAMPLE_RATE = 15_625;

    /** Number of synthetic GM drum types this codec can generate. */
    static final int DRUM_COUNT = 7;

    private static final int[] STEP_VAL = buildStepTable();
    private static final int[] DIFF_LOOKUP = buildDiffLookup();
    private static final int[] INDEX_SHIFT = { -1, -1, -1, -1, 2, 4, 6, 8 };

    private OkiAdpcmCodec()
    {
    }

    // ── Encoding ──────────────────────────────────────────────────────────────

    /**
     * Encodes 12-bit signed PCM samples (range [-2048, 2047]) to OKI 4-bit ADPCM bytes.
     *
     * <p>
     * Output format is <em>packed</em>: two nibbles per byte, low nibble first, then high nibble.
     * This matches the stream format expected by the MSM6258 chip emulator in libvgm/MAME, which
     * alternates between bits 3..0 and bits 7..4 of each byte using an internal {@code nibble_shift}
     * variable. Input values outside [-2048, 2047] are clamped.
     *
     * @param pcm
     *            12-bit signed PCM samples (use range [-2048, 2047])
     * @return ADPCM-encoded bytes; length equals {@code (pcm.length + 1) / 2}
     */
    static byte[] encode(short[] pcm)
    {
        if (pcm.length == 0)
            return new byte[0];
        byte[] out = new byte[(pcm.length + 1) / 2];
        int signal = -2;
        int step = 0;
        int outIdx = 0;
        boolean lowNibble = true;

        for (int i = 0; i < pcm.length; i++)
        {
            int target = Math.clamp((int) pcm[i], -2048, 2047);
            int nibble = bestNibble(signal, step, target);
            if (lowNibble)
                out[outIdx] = (byte) (nibble & 0x0F);
            else
            {
                out[outIdx] |= (byte) ((nibble & 0x0F) << 4);
                outIdx++;
            }
            lowNibble = !lowNibble;

            int diff = DIFF_LOOKUP[step * 16 + nibble];
            signal = Math.clamp(((diff << 8) + (signal * 245)) >> 8, -2048, 2047);
            step = Math.clamp(step + INDEX_SHIFT[nibble & 7], 0, 48);
        }
        return out;
    }

    // ── Drum sample generation ────────────────────────────────────────────────

    /**
     * Generates 7 synthetic GM drum samples as OKI ADPCM byte arrays.
     *
     * <p>
     * Index mapping:
     * <ol start="0">
     *   <li>Bass drum (GM notes 35, 36)</li>
     *   <li>Snare drum (GM notes 38, 40)</li>
     *   <li>Crash / cymbal (GM notes 49, 51-53, 55, 57, 59)</li>
     *   <li>Closed hi-hat (GM notes 42, 44)</li>
     *   <li>Tom (GM notes 41, 43, 45, 47, 48, 50)</li>
     *   <li>Rim shot (GM notes 37, 39)</li>
     *   <li>Open hi-hat (GM note 46)</li>
     * </ol>
     *
     * @return array of {@value #DRUM_COUNT} ADPCM byte arrays, one per drum type
     */
    static byte[][] generateDrumSamples()
    {
        return new byte[][] {
                encode(bassDrum()),
                encode(snare()),
                encode(cymbal()),
                encode(closedHiHat()),
                encode(tom()),
                encode(rimShot()),
                encode(openHiHat())
        };
    }

    // ── Private: nibble selection ─────────────────────────────────────────────

    private static int bestNibble(int signal, int step, int target)
    {
        int best = 0;
        int bestErr = Integer.MAX_VALUE;
        for (int n = 0; n < 16; n++)
        {
            int diff = DIFF_LOOKUP[step * 16 + n];
            int next = Math.clamp(((diff << 8) + (signal * 245)) >> 8, -2048, 2047);
            int err = Math.abs(next - target);
            if (err < bestErr)
            {
                bestErr = err;
                best = n;
            }
        }
        return best;
    }

    // ── Private: synthetic PCM generators ────────────────────────────────────

    /** Bass drum: low-frequency sine sweep (80 Hz → 20 Hz) with punchy exponential decay. */
    private static short[] bassDrum()
    {
        int n = (int) (0.20 * SAMPLE_RATE); // 200 ms = 3125 samples
        short[] pcm = new short[n];
        double phase = 0;
        for (int i = 0; i < n; i++)
        {
            double t = (double) i / SAMPLE_RATE;
            double freq = 80 * Math.pow(20.0 / 80.0, t / 0.20);
            phase += 2 * Math.PI * freq / SAMPLE_RATE;
            // env peak at t=0: (1 + 2) = 3.0 → amplitude 600 keeps peak ≤ 1800 < 2047
            double env = Math.exp(-t * 10) * (1 + 2 * Math.exp(-t * 40));
            pcm[i] = (short) Math.clamp((int) (env * 600 * Math.sin(phase)), -2048, 2047);
        }
        return pcm;
    }

    /**
     * Snare: two decaying sine waves — no noise.
     *
     * <p>
     * OKI ADPCM cannot faithfully reproduce broadband noise; attempts result in tearing artifacts.
     * Instead: a fast-decaying high-frequency sine (380 Hz, ~11 ms) creates the initial snap/crack,
     * and a slower-decaying low-frequency sine (220 Hz, ~36 ms) provides the body ring.
     */
    private static short[] snare()
    {
        int n = (int) (0.08 * SAMPLE_RATE); // 80 ms = 1250 samples
        short[] pcm = new short[n];
        for (int i = 0; i < n; i++)
        {
            double t = (double) i / SAMPLE_RATE;
            double snap = Math.sin(2 * Math.PI * 380 * t); // snap/crack transient
            double body = Math.sin(2 * Math.PI * 220 * t); // body ring
            double snapEnv = Math.exp(-t * 90); // ~11 ms → provides the "crack"
            double bodyEnv = Math.exp(-t * 28); // ~36 ms → body ring
            // Normalize by theoretical peak (1.0 + 0.65 = 1.65) to prevent ADPCM clipping artifacts
            double sample = (snapEnv * snap + bodyEnv * 0.65 * body) / 1.65;
            pcm[i] = (short) Math.clamp((int) (1700 * sample), -2048, 2047);
        }
        return pcm;
    }

    /** Crash / cymbal: three non-harmonic sines (230/390/570 Hz) with slow decay — no noise. */
    private static short[] cymbal()
    {
        int n = (int) (0.25 * SAMPLE_RATE); // 250 ms = 3906 samples
        short[] pcm = new short[n];
        for (int i = 0; i < n; i++)
        {
            double t = (double) i / SAMPLE_RATE;
            double s1 = Math.sin(2 * Math.PI * 230 * t);
            double s2 = Math.sin(2 * Math.PI * 390 * t);
            double s3 = Math.sin(2 * Math.PI * 570 * t);
            double env = Math.exp(-t * 10);
            double sample = env * (s1 + 0.7 * s2 + 0.4 * s3) / 2.1;
            pcm[i] = (short) Math.clamp((int) (1500 * sample), -2048, 2047);
        }
        return pcm;
    }

    /**
     * Closed hi-hat: two non-harmonic sines (450 Hz + 760 Hz) with very fast decay — no noise.
     *
     * <p>
     * OKI ADPCM cannot faithfully reproduce broadband noise; attempts result in tearing artifacts.
     * Instead: two high-frequency inharmonic sines with extremely fast decay (~8 ms) create a
     * sharp metallic tick without requiring noise.
     */
    private static short[] closedHiHat()
    {
        int n = (int) (0.04 * SAMPLE_RATE); // 40 ms = 625 samples
        short[] pcm = new short[n];
        for (int i = 0; i < n; i++)
        {
            double t = (double) i / SAMPLE_RATE;
            double s1 = Math.sin(2 * Math.PI * 450 * t);
            double s2 = Math.sin(2 * Math.PI * 760 * t);
            double env = Math.exp(-t * 120); // ~8 ms → sharp metallic tick
            double sample = env * (0.6 * s1 + 0.5 * s2) / 1.1;
            pcm[i] = (short) Math.clamp((int) (1700 * sample), -2048, 2047);
        }
        return pcm;
    }

    /** Tom: mid-frequency sine sweep (150 Hz → 60 Hz) with moderate decay. */
    private static short[] tom()
    {
        int n = (int) (0.20 * SAMPLE_RATE); // 200 ms = 3125 samples
        short[] pcm = new short[n];
        double phase = 0;
        for (int i = 0; i < n; i++)
        {
            double t = (double) i / SAMPLE_RATE;
            double freq = 150 * Math.pow(60.0 / 150.0, t / 0.20);
            phase += 2 * Math.PI * freq / SAMPLE_RATE;
            // env peak at t=0: (1 + 1) = 2.0 → amplitude 900 keeps peak ≤ 1800 < 2047
            double env = Math.exp(-t * 14) * (1 + Math.exp(-t * 30));
            pcm[i] = (short) Math.clamp((int) (env * 900 * Math.sin(phase)), -2048, 2047);
        }
        return pcm;
    }

    /** Rim shot: two non-harmonic sines (320 Hz + 540 Hz) with fast decay — no noise. */
    private static short[] rimShot()
    {
        int n = (int) (0.05 * SAMPLE_RATE); // 50 ms = 781 samples
        short[] pcm = new short[n];
        for (int i = 0; i < n; i++)
        {
            double t = (double) i / SAMPLE_RATE;
            double s1 = Math.sin(2 * Math.PI * 320 * t);
            double s2 = Math.sin(2 * Math.PI * 540 * t);
            double env = Math.exp(-t * 60);
            double sample = env * (0.7 * s1 + 0.55 * s2) / 1.25;
            pcm[i] = (short) Math.clamp((int) (1700 * sample), -2048, 2047);
        }
        return pcm;
    }

    /**
     * Open hi-hat: three non-harmonic sines (420/710/970 Hz) with moderate decay — no noise.
     *
     * <p>
     * OKI ADPCM cannot faithfully reproduce broadband noise; attempts result in tearing artifacts.
     * Instead: three inharmonic sines with moderate decay (~90 ms) create an open metallic shimmer
     * without requiring noise, and ADPCM can track all frequencies cleanly.
     */
    private static short[] openHiHat()
    {
        int n = (int) (0.18 * SAMPLE_RATE); // 180 ms = 2812 samples
        short[] pcm = new short[n];
        for (int i = 0; i < n; i++)
        {
            double t = (double) i / SAMPLE_RATE;
            double s1 = Math.sin(2 * Math.PI * 420 * t);
            double s2 = Math.sin(2 * Math.PI * 710 * t);
            double s3 = Math.sin(2 * Math.PI * 970 * t);
            double env = Math.exp(-t * 11); // ~90 ms → open shimmer
            double sample = env * (s1 + 0.7 * s2 + 0.4 * s3) / 2.1;
            pcm[i] = (short) Math.clamp((int) (1500 * sample), -2048, 2047);
        }
        return pcm;
    }

    // ── Private: lookup table builders ───────────────────────────────────────

    /** Builds the 49-entry step-size table: {@code floor(16 * 1.1^i)}. */
    private static int[] buildStepTable()
    {
        int[] table = new int[49];
        for (int i = 0; i < 49; i++)
            table[i] = (int) (16.0 * Math.pow(1.1, i));
        return table;
    }

    /** Builds the 49×16 diff-lookup table from step-size and nibble bits. */
    private static int[] buildDiffLookup()
    {
        int[] table = new int[49 * 16];
        for (int step = 0; step < 49; step++)
        {
            int sv = STEP_VAL[step];
            for (int nibble = 0; nibble < 16; nibble++)
            {
                int diff = sv / 8;
                if ((nibble & 1) != 0) diff += sv / 4;
                if ((nibble & 2) != 0) diff += sv / 2;
                if ((nibble & 4) != 0) diff += sv;
                if ((nibble & 8) != 0) diff = -diff;
                table[step * 16 + nibble] = diff;
            }
        }
        return table;
    }
}

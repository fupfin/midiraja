/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.dsp;

import static java.lang.Math.*;

/**
 * Feed-forward dynamics compressor with soft-knee gain computer, stereo-linked
 * peak detector, and per-preset makeup gain.
 *
 * <h2>DSP chain position (priority 200)</h2>
 * Applied <em>before</em> retro DAC simulation so that quiet passages use more
 * of the quantiser / PWM dynamic range, improving the perceived S/N ratio of
 * hardware simulations. Also useful standalone (no {@code --retro}) as a general
 * loudness-levelling stage.
 *
 * <h2>Signal flow per sample</h2>
 *
 * <pre>
 * stereo-linked peak detector
 *   → peak envelope follower (attack/release)
 *   → soft-knee gain computer (dB domain)
 *   → gain applied to left + right
 *   → makeup gain applied
 * </pre>
 *
 * <h2>Gain computer formula (Audio EQ Cookbook soft-knee)</h2>
 * Let {@code over = levelDb − threshold}:
 * <ul>
 * <li>{@code over ≤ −knee/2} → 0 dB (no reduction)</li>
 * <li>{@code over ≥ +knee/2} → {@code (1/R − 1) × over} dB</li>
 * <li>in knee: {@code (1/R − 1) × (over + knee/2)² / (2 × knee)} dB (quadratic)</li>
 * </ul>
 */
public class DynamicsCompressor implements AudioProcessor
{
    /**
     * Compressor preset — bundles threshold, ratio, attack, release, knee, and makeup.
     *
     * <p>
     * Presets are ordered from least to most aggressive. Choose based on the
     * degree of dynamic levelling desired:
     * </p>
     * <ul>
     * <li>{@link #SOFT} – transparent limiter, −3 dBFS threshold, 0 dB makeup</li>
     * <li>{@link #GENTLE} – 2:1 light compression, +3 dB makeup</li>
     * <li>{@link #MODERATE} – 4:1 noticeable compression, +6 dB makeup</li>
     * <li>{@link #AGGRESSIVE} – 8:1 loudness maximising, +9 dB makeup</li>
     * </ul>
     */
    public enum Preset
    {
        /** Transparent limiter. Clips peaks above −3 dBFS, no makeup. Preserves full dynamics. */
        SOFT(-3.0, 10.0, 5.0, 50.0, 6.0, 0.0),

        /** Light 2:1 compression above −18 dBFS, +3 dB makeup. Gentle levelling. */
        GENTLE(-18.0, 2.0, 50.0, 300.0, 6.0, 3.0),

        /** Moderate 4:1 compression above −18 dBFS, +6 dB makeup. Noticeable levelling. */
        MODERATE(-18.0, 4.0, 30.0, 200.0, 4.0, 6.0),

        /** Aggressive 8:1 compression above −24 dBFS, +9 dB makeup. Loudness maximising. */
        AGGRESSIVE(-24.0, 8.0, 20.0, 150.0, 3.0, 9.0);

        final double threshDb;
        final double ratio;
        final double attackMs;
        final double releaseMs;
        final double kneeDb;
        final double makeupDb;

        Preset(double threshDb, double ratio, double attackMs, double releaseMs,
                double kneeDb, double makeupDb)
        {
            this.threshDb = threshDb;
            this.ratio = ratio;
            this.attackMs = attackMs;
            this.releaseMs = releaseMs;
            this.kneeDb = kneeDb;
            this.makeupDb = makeupDb;
        }
    }

    private static final double FS = 44100.0;

    private final double threshDb;
    private final double invRatio; // 1/ratio — used directly in gain formula
    private final double attackCoeff; // 1 − exp(−1 / (fs × attackMs/1000))
    private final double releaseCoeff;
    private final double halfKneeDb;
    private final double makeupGain; // linear makeup gain

    private final AudioProcessor next;

    /** Stereo-linked peak envelope follower state. */
    private double levelEnv = 0.0;

    public DynamicsCompressor(Preset preset, AudioProcessor next)
    {
        this.threshDb = preset.threshDb;
        this.invRatio = 1.0 / preset.ratio;
        this.attackCoeff = 1.0 - exp(-1.0 / (FS * preset.attackMs / 1000.0));
        this.releaseCoeff = 1.0 - exp(-1.0 / (FS * preset.releaseMs / 1000.0));
        this.halfKneeDb = preset.kneeDb / 2.0;
        this.makeupGain = pow(10.0, preset.makeupDb / 20.0);
        this.next = next;
    }

    @Override
    public void process(float[] left, float[] right, int frames)
    {
        for (int i = 0; i < frames; i++)
        {
            double gain = nextGain(max(abs(left[i]), abs(right[i])));
            left[i] = (float) (left[i] * gain);
            right[i] = (float) (right[i] * gain);
        }
        next.process(left, right, frames);
    }

    @Override
    public void processInterleaved(short[] pcm, int frames, int channels)
    {
        for (int i = 0; i < frames; i++)
        {
            int base = i * channels;
            double l = pcm[base] / 32768.0;
            double r = channels > 1 ? pcm[base + 1] / 32768.0 : l;
            double gain = nextGain(max(abs(l), abs(r)));
            pcm[base] = clampShort(l * gain);
            if (channels > 1)
                pcm[base + 1] = clampShort(r * gain);
        }
        next.processInterleaved(pcm, frames, channels);
    }

    @Override
    public void reset()
    {
        levelEnv = 0.0;
        next.reset();
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * Advances the peak envelope follower with {@code peak} and returns the
     * linear gain to apply (compression gain × makeup gain).
     */
    private double nextGain(double peak)
    {
        double coeff = peak > levelEnv ? attackCoeff : releaseCoeff;
        levelEnv += coeff * (peak - levelEnv);
        if (levelEnv < 1e-10)
            return makeupGain;
        double levelDb = 20.0 * log10(levelEnv);
        return pow(10.0, gainReductionDb(levelDb) / 20.0) * makeupGain;
    }

    /**
     * Soft-knee gain reduction in dB (always ≤ 0).
     * Quadratic interpolation inside the knee for a smooth transition.
     */
    private double gainReductionDb(double levelDb)
    {
        double over = levelDb - threshDb;
        if (over <= -halfKneeDb)
            return 0.0;
        if (over >= halfKneeDb)
            return (invRatio - 1.0) * over;
        // Quadratic knee: 0 dB at (thresh - knee/2), smoothly joins full slope at (thresh + knee/2)
        double t = over + halfKneeDb; // 0 .. kneeDb
        return (invRatio - 1.0) * t * t / (4.0 * halfKneeDb);
    }

    private static short clampShort(double v)
    {
        return (short) max(-32768, min(32767, (int) (v * 32768.0)));
    }
}

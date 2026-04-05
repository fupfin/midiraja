package com.fupfin.midiraja.dsp;

import java.util.Random;

/**
 * Simulates the Amiga Paula chip's independent stereo DAC output.
 *
 * Key characteristics modeled:
 * 1. Independent L/R channels — no mono mix-down, matching Paula's 4-channel stereo architecture.
 * 2. R-2R LUT with ±3% resistor tolerance (seed=1985).
 * 3. Zero-Order Hold at ~22kHz (hold 2 samples at 44.1kHz).
 * 4. Static RC low-pass filter: A500 ~4.5kHz (α≈0.39), A1200 ~28kHz (α≈0.80).
 * 5. LED filter: cascaded 2-pole IIR LPF at ~3.3kHz (α≈0.32 × 2).
 * 6. M/S stereo widening: emulates Paula's hard-panned channel layout (A500: 1.6, A1200: 1.4).
 */
public class AmigaPaulaFilter implements AudioProcessor
{
    public enum Profile
    {
        A500, A1200
    }

    // A500: Paula output RC filter ~4.5kHz
    private static final float STATIC_LPF_ALPHA_A500 = 0.39f;
    // A1200: AGA DAC filter ~28kHz (near-transparent)
    private static final float STATIC_LPF_ALPHA_A1200 = 0.80f;
    // LED filter: cascaded 2 × 1-pole IIR at ~3.3kHz
    private static final float LED_LPF_ALPHA = 0.32f;
    // M/S stereo width default: Paula channels are hard-panned (ch0,ch3→L / ch1,ch2→R)
    public static final float DEFAULT_STEREO_WIDTH = 1.6f;

    private final boolean enabled;
    private final float staticAlpha;
    private final float stereoWidth;
    private final AudioProcessor next;

    private final float[] dacLut = new float[256];

    // Left channel state
    private int holdCounterL = 0;
    private float heldValL = 0;
    private float prevHeldValL = 0;
    private float staticLpfL = 0;
    private float ledLp1L = 0;
    private float ledLp2L = 0;

    // Right channel state
    private int holdCounterR = 0;
    private float heldValR = 0;
    private float prevHeldValR = 0;
    private float staticLpfR = 0;
    private float ledLp1R = 0;
    private float ledLp2R = 0;

    public AmigaPaulaFilter(boolean enabled, Profile profile, float stereoWidth, AudioProcessor next)
    {
        this.enabled = enabled;
        this.staticAlpha = profile == Profile.A500 ? STATIC_LPF_ALPHA_A500 : STATIC_LPF_ALPHA_A1200;
        this.stereoWidth = stereoWidth;
        this.next = next;
        buildLut();
    }

    public AmigaPaulaFilter(boolean enabled, Profile profile, AudioProcessor next)
    {
        this(enabled, profile, DEFAULT_STEREO_WIDTH, next);
    }

    private void buildLut()
    {
        Random rand = new Random(1985);
        float[] weights = new float[8];
        float total = 0;
        for (int i = 0; i < 8; i++)
        {
            float ideal = (float) Math.pow(2, i);
            float variance = 1.0f + ((rand.nextFloat() * 2.0f - 1.0f) * 0.03f);
            weights[i] = ideal * variance;
            total += weights[i];
        }
        for (int i = 0; i < 256; i++)
        {
            float sum = 0;
            for (int bit = 0; bit < 8; bit++)
            {
                if ((i & (1 << bit)) != 0)
                    sum += weights[bit];
            }
            dacLut[i] = (sum / total) * 2.0f - 1.0f;
        }
    }

    private int dacIndex(float in)
    {
        float clamped = Math.max(-1.0f, Math.min(1.0f, in));
        return Math.max(0, Math.min(255, Math.round((clamped * 0.5f + 0.5f) * 255f)));
    }

    @Override
    public void process(float[] left, float[] right, int frames)
    {
        if (!enabled)
        {
            next.process(left, right, frames);
            return;
        }
        for (int i = 0; i < frames; i++)
        {
            // Left channel
            if (holdCounterL == 0)
            {
                prevHeldValL = heldValL;
                heldValL = dacLut[dacIndex(left[i])];
            }
            float interpL = prevHeldValL + (holdCounterL / 2.0f) * (heldValL - prevHeldValL);
            holdCounterL = (holdCounterL + 1) % 2;
            staticLpfL += staticAlpha * (interpL - staticLpfL);
            ledLp1L += LED_LPF_ALPHA * (staticLpfL - ledLp1L);
            ledLp2L += LED_LPF_ALPHA * (ledLp1L - ledLp2L);
            // Right channel
            if (holdCounterR == 0)
            {
                prevHeldValR = heldValR;
                heldValR = dacLut[dacIndex(right[i])];
            }
            float interpR = prevHeldValR + (holdCounterR / 2.0f) * (heldValR - prevHeldValR);
            holdCounterR = (holdCounterR + 1) % 2;
            staticLpfR += staticAlpha * (interpR - staticLpfR);
            ledLp1R += LED_LPF_ALPHA * (staticLpfR - ledLp1R);
            ledLp2R += LED_LPF_ALPHA * (ledLp1R - ledLp2R);

            // M/S stereo widening
            float m = (ledLp2L + ledLp2R) * 0.5f;
            float s = (ledLp2L - ledLp2R) * 0.5f;
            left[i] = m + s * stereoWidth;
            right[i] = m - s * stereoWidth;
        }
        next.process(left, right, frames);
    }

    @Override
    public void processInterleaved(short[] interleavedPcm, int frames, int channels)
    {
        if (!enabled)
        {
            next.processInterleaved(interleavedPcm, frames, channels);
            return;
        }
        for (int i = 0; i < frames; i++)
        {
            int li = i * channels;

            // Left channel
            float inL = interleavedPcm[li] / 32768.0f;
            if (holdCounterL == 0)
            {
                prevHeldValL = heldValL;
                heldValL = dacLut[dacIndex(inL)];
            }
            float interpL = prevHeldValL + (holdCounterL / 2.0f) * (heldValL - prevHeldValL);
            holdCounterL = (holdCounterL + 1) % 2;
            staticLpfL += staticAlpha * (interpL - staticLpfL);
            ledLp1L += LED_LPF_ALPHA * (staticLpfL - ledLp1L);
            ledLp2L += LED_LPF_ALPHA * (ledLp1L - ledLp2L);
            if (channels > 1)
            {
                // Right channel
                float inR = interleavedPcm[li + 1] / 32768.0f;
                if (holdCounterR == 0)
                {
                    prevHeldValR = heldValR;
                    heldValR = dacLut[dacIndex(inR)];
                }
                float interpR = prevHeldValR + (holdCounterR / 2.0f) * (heldValR - prevHeldValR);
                holdCounterR = (holdCounterR + 1) % 2;
                staticLpfR += staticAlpha * (interpR - staticLpfR);
                ledLp1R += LED_LPF_ALPHA * (staticLpfR - ledLp1R);
                ledLp2R += LED_LPF_ALPHA * (ledLp1R - ledLp2R);

                // M/S stereo widening
                float m = (ledLp2L + ledLp2R) * 0.5f;
                float s = (ledLp2L - ledLp2R) * 0.5f;
                interleavedPcm[li] = (short) Math.max(-32768, Math.min(32767, (m + s * stereoWidth) * 32768.0f));
                interleavedPcm[li + 1] = (short) Math.max(-32768, Math.min(32767, (m - s * stereoWidth) * 32768.0f));
            }
            else
            {
                interleavedPcm[li] = (short) Math.max(-32768, Math.min(32767, ledLp2L * 32768.0f));
            }
        }
        next.processInterleaved(interleavedPcm, frames, channels);
    }

    @Override
    public void reset()
    {
        holdCounterL = 0;
        heldValL = 0;
        prevHeldValL = 0;
        staticLpfL = 0;
        ledLp1L = 0;
        ledLp2L = 0;
        holdCounterR = 0;
        heldValR = 0;
        prevHeldValR = 0;
        staticLpfR = 0;
        ledLp1R = 0;
        ledLp2R = 0;
        next.reset();
    }
}

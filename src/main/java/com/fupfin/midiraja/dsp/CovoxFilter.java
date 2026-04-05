package com.fupfin.midiraja.dsp;

import java.util.Random;

/**
 * Simulates the sound of early 8-bit R-2R resistor ladder DACs like the Covox Speech Thing. Instead
 * of perfect linear quantization, it uses a pre-calculated Look-Up Table (LUT) with simulated
 * resistor tolerance variances, producing authentic era-accurate harmonic distortion. It also
 * applies a gentle low-pass filter to simulate the physical output smoothing capacitors.
 */
public class CovoxFilter implements AudioProcessor
{
    private final boolean enabled;
    private final AudioProcessor next;

    // The pre-calculated R-2R non-linear look-up table.
    private final float[] dacLut = new float[256];

    // Simple 1-pole Low-Pass Filter state for output smoothing (~4.5kHz to ~6kHz cutoff)
    // Alpha = 1 - exp(-2 * PI * fc / fs) -> e.g. fc=5000, fs=44100 => ~0.5
    private static final float LPF_ALPHA = 0.5f;
    private float lastOutL = 0.0f;
    private float lastOutR = 0.0f;

    public CovoxFilter(boolean enabled, AudioProcessor next)
    {
        this.enabled = enabled;
        this.next = next;
        buildResistorLadderLUT();
    }

    private void buildResistorLadderLUT()
    {
        Random rand = new Random(1987); // Seeded for deterministic but "random" resistor values

        // Simulating 8 resistors with +/- 3% tolerance
        float[] weights = new float[8];
        float totalWeight = 0;

        for (int i = 0; i < 8; i++)
        {
            // Ideal weight is 2^i
            float idealWeight = (float) Math.pow(2, i);
            // Add a random tolerance variance between -3% and +3%
            float variance = 1.0f + ((rand.nextFloat() * 2.0f - 1.0f) * 0.03f);
            weights[i] = idealWeight * variance;
            totalWeight += weights[i];
        }

        // Build the 256-level LUT
        for (int i = 0; i < 256; i++)
        {
            float val = 0;
            for (int bit = 0; bit < 8; bit++)
            {
                if ((i & (1 << bit)) != 0)
                {
                    val += weights[bit];
                }
            }
            // Normalize to -1.0 .. 1.0
            dacLut[i] = (val / totalWeight) * 2.0f - 1.0f;
        }
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
            // Quantize Left
            float inL = Math.max(-1.0f, Math.min(1.0f, left[i]));
            int idxL = Math.max(0, Math.min(255, Math.round((inL * 0.5f + 0.5f) * 255f)));
            float outL = dacLut[idxL];

            // Apply smoothing LPF
            lastOutL += LPF_ALPHA * (outL - lastOutL);
            left[i] = lastOutL;

            // Quantize Right
            float inR = Math.max(-1.0f, Math.min(1.0f, right[i]));
            int idxR = Math.max(0, Math.min(255, Math.round((inR * 0.5f + 0.5f) * 255f)));
            float outR = dacLut[idxR];

            // Apply smoothing LPF
            lastOutR += LPF_ALPHA * (outR - lastOutR);
            right[i] = lastOutR;
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
            int leftIdx = i * channels;

            // Left
            float inL = interleavedPcm[leftIdx] / 32768.0f;
            int idxL = Math.max(0, Math.min(255, Math.round((inL * 0.5f + 0.5f) * 255f)));
            float outL = dacLut[idxL];
            lastOutL += LPF_ALPHA * (outL - lastOutL);
            interleavedPcm[leftIdx] = (short) Math.max(-32768, Math.min(32767, lastOutL * 32768.0f));

            // Right
            if (channels > 1)
            {
                int rightIdx = leftIdx + 1;
                float inR = interleavedPcm[rightIdx] / 32768.0f;
                int idxR = Math.max(0, Math.min(255, Math.round((inR * 0.5f + 0.5f) * 255f)));
                float outR = dacLut[idxR];
                lastOutR += LPF_ALPHA * (outR - lastOutR);
                interleavedPcm[rightIdx] = (short) Math.max(-32768, Math.min(32767, lastOutR * 32768.0f));
            }
        }

        next.processInterleaved(interleavedPcm, frames, channels);
    }

    @Override
    public void reset()
    {
        lastOutL = 0.0f;
        lastOutR = 0.0f;
        next.reset();
    }
}

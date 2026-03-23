package com.fupfin.midiraja.dsp;

import java.util.Random;

/**
 * Simulates the sound of early 8-bit R-2R resistor ladder DACs like the Covox Speech Thing.
 *
 * Key characteristics modeled: 1. R-2R Resistor Variance: Simulated +/- 3% tolerance variance in
 * the resistor ladder to produce authentic era-accurate harmonic distortion via a Look-Up Table
 * (LUT). 2. Limited Effective Sampling Rate: Covox was typically driven via the LPT port, rarely
 * exceeding 22kHz in practical use due to CPU overhead. This filter implements a Zero-Order Hold
 * (ZOH) at 22.05kHz. 3. Analog Smoothing: A gentle low-pass filter simulates the physical capacitor
 * usually present in Covox circuits to tame the 8-bit steps.
 */
public class CovoxDacFilter implements AudioProcessor
{
    private final boolean enabled;
    private final AudioProcessor next;

    private final float[] dacLut = new float[256];

    // Covox physical RC filter: 15 kOhm resistor + 5 nF capacitor = ~2.12 kHz cutoff
    // Alpha for 2122Hz at 44.1kHz is approx 0.26.
    private static final float LPF_ALPHA = 0.26f;
    private float lastOut = 0.0f;

    // Linear-interpolated hold state for ~11kHz simulation (1/4 of 44.1kHz).
    // Wikipedia notes 80286 systems typically reached ~12 kHz.
    // We hold for 4 frames (11.025 kHz) to represent typical 286/386 gameplay,
    // with linear interpolation between DAC steps instead of a ZOH staircase.
    private int holdCounter = 0;
    private float heldVal = 0;
    private float prevHeldVal = 0;

    public CovoxDacFilter(boolean enabled, AudioProcessor next)
    {
        this.enabled = enabled;
        this.next = next;
        buildLut();
    }

    private void buildLut()
    {
        Random rand = new Random(1987);
        float[] weights = new float[8];
        float total = 0;
        for (int i = 0; i < 8; i++)
        {
            float ideal = (float) Math.pow(2, i);
            // +/- 3% resistor tolerance
            float variance = 1.0f + ((rand.nextFloat() * 2.0f - 1.0f) * 0.03f);
            weights[i] = ideal * variance;
            total += weights[i];
        }
        for (int i = 0; i < 256; i++)
        {
            float sum = 0;
            for (int bit = 0; bit < 8; bit++)
            {
                if ((i & (1 << bit)) != 0) sum += weights[bit];
            }
            dacLut[i] = (sum / total) * 2.0f - 1.0f;
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
            if (holdCounter == 0)
            {
                // Covox is a MONO device. Mix down before DAC.
                float monoIn = (left[i] + right[i]) * 0.5f;

                // Quantize and convert through non-linear R-2R LUT
                float inClamp = Math.max(-1.0f, Math.min(1.0f, monoIn));
                int idx = Math.max(0, Math.min(255, Math.round((inClamp * 0.5f + 0.5f) * 255f)));
                prevHeldVal = heldVal;
                heldVal = dacLut[idx];
            }

            // Linear interpolation over 4 samples (~11.025 kHz typical DOS CPU limit)
            float interp = prevHeldVal + (holdCounter / 4.0f) * (heldVal - prevHeldVal);
            holdCounter++;
            if (holdCounter >= 4) holdCounter = 0;

            // Apply analog RC smoothing LPF (2.12 kHz)
            lastOut += LPF_ALPHA * (interp - lastOut);

            // Output mono signal to both channels
            left[i] = lastOut;
            right[i] = lastOut;
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

            if (holdCounter == 0)
            {
                float monoIn = interleavedPcm[leftIdx] / 32768.0f;
                if (channels > 1)
                {
                    monoIn = (monoIn + (interleavedPcm[leftIdx + 1] / 32768.0f)) * 0.5f;
                }

                int idx = Math.max(0, Math.min(255, Math.round((monoIn * 0.5f + 0.5f) * 255f)));
                prevHeldVal = heldVal;
                heldVal = dacLut[idx];
            }

            float interp = prevHeldVal + (holdCounter / 4.0f) * (heldVal - prevHeldVal);
            holdCounter++;
            if (holdCounter >= 4) holdCounter = 0;

            lastOut += LPF_ALPHA * (interp - lastOut);
            short outShort = (short) Math.max(-32768, Math.min(32767, lastOut * 32768.0f));

            interleavedPcm[leftIdx] = outShort;
            if (channels > 1)
            {
                interleavedPcm[leftIdx + 1] = outShort;
            }
        }
        next.processInterleaved(interleavedPcm, frames, channels);
    }

    @Override
    public void reset()
    {
        lastOut = 0;
        heldVal = 0;
        prevHeldVal = 0;
        holdCounter = 0;
        next.reset();
    }
}

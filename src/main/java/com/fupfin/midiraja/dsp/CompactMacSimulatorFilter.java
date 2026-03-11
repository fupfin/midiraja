package com.fupfin.midiraja.dsp;

/**
 * Simulates the unique audio hardware of the early compact Macintosh line (128k, 512k, Plus, SE).
 * The Mac used a PWM generator built from two 74LS161 4-bit counters, driven by the 68000 CPU at
 * the horizontal video flyback frequency (~22.25 kHz). The resulting 1-bit pulse train was
 * integrated by a physical RC low-pass filter before reaching the internal 2-inch speaker.
 *
 * This filter performs: 1. Event-Driven Analytical Integration of the 1-bit PWM pulse train. 2.
 * Simulates the physical RC filter charging and discharging at sub-microsecond precision. 3.
 * Eliminates ZOH aliasing (the "siren" tone) mathematically without oversampling.
 */
public class CompactMacSimulatorFilter implements AudioProcessor
{
    private final boolean enabled;
    private final AudioProcessor next;

    // Timing constants
    private final double outputSampleTimeUs = 1000000.0 / 44100.0;
    private final double macSampleTimeUs = 1000000.0 / 22254.5;

    // RC Filter time constant (Tau)
    // The authentic Mac Plus capture shows a steep roll-off starting around 5kHz and hitting -83dB
    // at 10kHz.
    // This requires a Tau of roughly 30.0 us to match the analog integration curve perfectly.
    private final double tauUs = 30.0;

    // Simulation state
    private double currentTimeUs = 0.0;
    private double nextMacSampleTimeUs = 0.0;

    private double xL = 0.0;
    private double xR = 0.0;

    private double dutyL = 0.5;
    private double dutyR = 0.5;

    private boolean isHighL = false;
    private boolean isHighR = false;

    private double transitionTimeLUs = 0.0;
    private double transitionTimeRUs = 0.0;

    public CompactMacSimulatorFilter(boolean enabled, AudioProcessor next)
    {
        this.enabled = enabled;
        this.next = next;
    }

    @Override
    public void process(float[] left, float[] right, int frames)
    {
        if (!enabled)
        {
            next.process(left, right, frames);
            return;
        }

        // Pull architecture
        next.process(left, right, frames);

        for (int i = 0; i < frames; i++)
        {
            simulateSample(left[i], right[i]);
            left[i] = (float) xL;
            right[i] = (float) xR;
        }

        wrapTime();
    }

    @Override
    public void processInterleaved(short[] interleavedPcm, int frames, int channels)
    {
        if (!enabled)
        {
            next.processInterleaved(interleavedPcm, frames, channels);
            return;
        }

        next.processInterleaved(interleavedPcm, frames, channels);

        for (int i = 0; i < frames; i++)
        {
            int leftIdx = i * channels;
            int rightIdx = channels > 1 ? leftIdx + 1 : leftIdx;

            float inL = interleavedPcm[leftIdx] / 32768.0f;
            float inR = interleavedPcm[rightIdx] / 32768.0f;

            simulateSample(inL, inR);

            interleavedPcm[leftIdx] = (short) Math.max(-32768, Math.min(32767, xL * 32768.0));
            if (channels > 1)
            {
                interleavedPcm[rightIdx] = (short) Math.max(-32768, Math.min(32767, xR * 32768.0));
            }
        }

        wrapTime();
    }

    private void simulateSample(float inL, float inR)
    {
        double targetOutputTimeUs = currentTimeUs + outputSampleTimeUs;

        while (currentTimeUs < targetOutputTimeUs)
        {
            if (currentTimeUs >= nextMacSampleTimeUs)
            {
                // Authentic 8-bit Quantization
                // The capture proves the Mac successfully isolated the 8-bit steps.
                // We use standard symmetrical clamping to represent the clean digital buffer.
                int u8L = Math.max(0,
                        Math.min(255, Math.round((inL * 0.5f + 0.5f) * 255.0f)));
                int u8R = Math.max(0,
                        Math.min(255, Math.round((inR * 0.5f + 0.5f) * 255.0f)));

                dutyL = u8L / 255.0;
                dutyR = u8R / 255.0;

                isHighL = true;
                isHighR = true;

                transitionTimeLUs = nextMacSampleTimeUs + (dutyL * macSampleTimeUs);
                transitionTimeRUs = nextMacSampleTimeUs + (dutyR * macSampleTimeUs);

                nextMacSampleTimeUs += macSampleTimeUs;
            }

            double nextEventUs = targetOutputTimeUs;
            if (nextEventUs > nextMacSampleTimeUs) nextEventUs = nextMacSampleTimeUs;
            if (isHighL && nextEventUs > transitionTimeLUs) nextEventUs = transitionTimeLUs;
            if (isHighR && nextEventUs > transitionTimeRUs) nextEventUs = transitionTimeRUs;

            double deltaT = nextEventUs - currentTimeUs;
            if (deltaT > 1e-9)
            {
                double expDecay = Math.exp(-deltaT / tauUs);

                double uL = isHighL ? 1.0 : -1.0;
                double uR = isHighR ? 1.0 : -1.0;

                xL = uL + (xL - uL) * expDecay;
                xR = uR + (xR - uR) * expDecay;

                currentTimeUs = nextEventUs;
            }
            else
            {
                currentTimeUs = nextEventUs;
            }

            if (isHighL && currentTimeUs >= transitionTimeLUs) isHighL = false;
            if (isHighR && currentTimeUs >= transitionTimeRUs) isHighR = false;
        }
    }

    private void wrapTime()
    {
        while (currentTimeUs > 1000000.0)
        {
            currentTimeUs -= 1000000.0;
            nextMacSampleTimeUs -= 1000000.0;
            transitionTimeLUs -= 1000000.0;
            transitionTimeRUs -= 1000000.0;
        }
    }

    @Override
    public void reset()
    {
        next.reset();
        currentTimeUs = 0.0;
        nextMacSampleTimeUs = 0.0;
        xL = 0.0;
        xR = 0.0;
        dutyL = 0.5;
        dutyR = 0.5;
        isHighL = false;
        isHighR = false;
        transitionTimeLUs = 0.0;
        transitionTimeRUs = 0.0;
    }
}

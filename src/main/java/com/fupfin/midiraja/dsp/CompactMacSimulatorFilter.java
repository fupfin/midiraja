package com.fupfin.midiraja.dsp;

/**
 * Simulates the unique audio hardware of the early compact Macintosh line (128k, 512k, Plus, SE).
 * The Mac used a PWM generator built from two 74LS161 4-bit counters, driven by the 68000 CPU at
 * the horizontal video flyback frequency (~22.25 kHz). The resulting 1-bit pulse train was
 * integrated by a physical RC low-pass filter before reaching the internal 2-inch speaker.
 *
 * <p>
 * The Mac had a single mono speaker and a mono audio path. Stereo input is summed to mono
 * before PWM encoding, and the mono output is written to both output channels.
 *
 * <p>
 * This filter performs:
 * 1. Stereo-to-mono sum (L+R)/2 — matches physical mono-only hardware.
 * 2. Event-Driven Analytical Integration of the 1-bit PWM pulse train.
 * 3. Exact RC filter charging/discharging at sub-microsecond precision.
 * 4. Aliasing-free reconstruction without oversampling.
 */
public class CompactMacSimulatorFilter implements AudioProcessor
{
    private final boolean enabled;
    private final AudioProcessor next;

    // Timing constants
    private final double outputSampleTimeUs = 1000000.0 / 44100.0;
    private final double macSampleTimeUs = 1000000.0 / 22254.5; // authentic Mac carrier

    // RC filter time constant: f_-3dB = 1/(2π×30µs) ≈ 5,300 Hz
    private final double tauUs = 30.0;

    // Post-RC 18 kHz LPF (bilinear transform, 1-pole) — suppresses aliased carrier
    // At 44.1 kHz output rate the Mac carrier (22,254.5 Hz) aliases to ~21,845 Hz.
    // The bilinear transform places an exact zero at Nyquist, giving ~26 dB suppression there.
    private static final double LPF_K = Math.tan(Math.PI * 18000.0 / 44100.0); // ≈ 3.309
    private static final double LPF_B0 = LPF_K / (1.0 + LPF_K); // ≈ 0.768
    private static final double LPF_A1 = (LPF_K - 1.0) / (LPF_K + 1.0); // ≈ 0.536
    // difference equation: y[n] = B0*(x[n] + x[n-1]) - A1*y[n-1]

    // Mac internal speaker: 2-pole Butterworth LPF at 10 kHz
    // Based on measured hard rolloff in compact_mac_sample.wav (cliff at 10–11 kHz).
    // Bilinear transform at 44,100 Hz. Q = 1/√2 ≈ 0.7071 (Butterworth).
    private static final double SPK_K = Math.tan(Math.PI * 10000.0 / 44100.0);
    private static final double SPK_NORM = 1.0 + SPK_K / 0.7071 + SPK_K * SPK_K;
    private static final double SPK_B0 = (SPK_K * SPK_K) / SPK_NORM;
    private static final double SPK_A1 = 2.0 * (SPK_K * SPK_K - 1.0) / SPK_NORM;
    private static final double SPK_A2 = (1.0 - SPK_K / 0.7071 + SPK_K * SPK_K) / SPK_NORM;
    // b1 = 2*SPK_B0, b2 = SPK_B0 (standard 2-pole LP biquad)

    // Simulation state (mono — single RC path)
    private double currentTimeUs = 0.0;
    private double nextMacSampleTimeUs = 0.0;
    private double x = 0.0; // RC capacitor voltage
    private double duty = 0.5;
    private boolean isHigh = false;
    private double transitionTimeUs = 0.0;
    private double lpfX = 0.0; // LPF: previous input
    private double lpfY = 0.0; // LPF: previous output
    private final boolean auxOut;
    private double spkX1 = 0.0, spkX2 = 0.0;
    private double spkY1 = 0.0, spkY2 = 0.0;

    public CompactMacSimulatorFilter(boolean enabled, boolean auxOut, AudioProcessor next)
    {
        this.enabled = enabled;
        this.auxOut = auxOut;
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

        for (int i = 0; i < frames; i++)
        {
            float mono = (left[i] + right[i]) * 0.5f;
            simulateSample(mono);
            double yLpf = LPF_B0 * (x + lpfX) - LPF_A1 * lpfY;
            lpfX = x;
            lpfY = yLpf;
            double out = yLpf;
            if (!auxOut)
            {
                double y = SPK_B0 * out + 2.0 * SPK_B0 * spkX1 + SPK_B0 * spkX2
                        - SPK_A1 * spkY1 - SPK_A2 * spkY2;
                spkX2 = spkX1;
                spkX1 = out;
                spkY2 = spkY1;
                spkY1 = y;
                out = y;
            }
            left[i] = (float) out;
            right[i] = (float) out;
        }

        wrapTime();
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
            int rightIdx = channels > 1 ? leftIdx + 1 : leftIdx;

            float inL = interleavedPcm[leftIdx] / 32768.0f;
            float inR = channels > 1 ? interleavedPcm[rightIdx] / 32768.0f : inL;

            simulateSample((inL + inR) * 0.5f);
            double yLpf = LPF_B0 * (x + lpfX) - LPF_A1 * lpfY;
            lpfX = x;
            lpfY = yLpf;

            double outD = yLpf;
            if (!auxOut)
            {
                double y = SPK_B0 * outD + 2.0 * SPK_B0 * spkX1 + SPK_B0 * spkX2
                        - SPK_A1 * spkY1 - SPK_A2 * spkY2;
                spkX2 = spkX1;
                spkX1 = outD;
                spkY2 = spkY1;
                spkY1 = y;
                outD = y;
            }
            short out = (short) Math.max(-32768, Math.min(32767, outD * 32768.0));
            interleavedPcm[leftIdx] = out;
            if (channels > 1)
                interleavedPcm[rightIdx] = out;
        }

        wrapTime();
        next.processInterleaved(interleavedPcm, frames, channels);
    }

    private void simulateSample(float in)
    {
        double targetOutputTimeUs = currentTimeUs + outputSampleTimeUs;

        while (currentTimeUs < targetOutputTimeUs)
        {
            if (currentTimeUs >= nextMacSampleTimeUs)
            {
                // 8-bit duty cycle quantization: maps [-1,1] → [0,255] → [0,1]
                int u8 = Math.max(0, Math.min(255, Math.round((in * 0.5f + 0.5f) * 255.0f)));
                duty = u8 / 255.0;
                isHigh = true;
                transitionTimeUs = nextMacSampleTimeUs + (duty * macSampleTimeUs);
                nextMacSampleTimeUs += macSampleTimeUs;
            }

            double nextEventUs = targetOutputTimeUs;
            if (nextEventUs > nextMacSampleTimeUs)
                nextEventUs = nextMacSampleTimeUs;
            if (isHigh && nextEventUs > transitionTimeUs)
                nextEventUs = transitionTimeUs;

            double deltaT = nextEventUs - currentTimeUs;
            if (deltaT > 1e-9)
            {
                double u = isHigh ? 1.0 : -1.0;
                double expDecay = Math.exp(-deltaT / tauUs);
                x = u + (x - u) * expDecay;
                currentTimeUs = nextEventUs;
            }
            else
            {
                currentTimeUs = nextEventUs;
            }

            if (isHigh && currentTimeUs >= transitionTimeUs)
                isHigh = false;
        }
    }

    private void wrapTime()
    {
        while (currentTimeUs > 1000000.0)
        {
            currentTimeUs -= 1000000.0;
            nextMacSampleTimeUs -= 1000000.0;
            transitionTimeUs -= 1000000.0;
        }
    }

    @Override
    public void reset()
    {
        next.reset();
        currentTimeUs = 0.0;
        nextMacSampleTimeUs = 0.0;
        x = 0.0;
        duty = 0.5;
        isHigh = false;
        transitionTimeUs = 0.0;
        lpfX = 0.0;
        lpfY = 0.0;
        spkX1 = 0.0;
        spkX2 = 0.0;
        spkY1 = 0.0;
        spkY2 = 0.0;
    }
}

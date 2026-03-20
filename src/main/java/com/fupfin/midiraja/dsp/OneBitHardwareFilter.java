package com.fupfin.midiraja.dsp;

import static java.lang.Math.*;
import static java.util.Locale.ROOT;
import java.util.Arrays;
import java.util.Random;
import org.jspecify.annotations.Nullable;

/**
 * Simulates the 1-bit PWM audio output of the Apple II (DAC522 technique) and IBM PC speaker.
 *
 * <h2>Why oversampling, not integratePwm()</h2>
 * The original implementation used {@code integratePwm()} to compute the exact time-average of
 * the PWM duty cycle over each 44.1 kHz output sample. This produces a mathematically perfect
 * linear DAC output: quantisation harmonics are modulated onto carrier sidebands above 20 kHz,
 * leaving the audible band completely clean. A real speaker cone does not compute a perfect
 * time-average — its finite mechanical settling time causes imperfect integration, which is what
 * creates the characteristic harmonic texture of 1-bit audio.
 *
 * <h2>Why not RC integration (as in CompactMacSimulatorFilter)?</h2>
 * {@link CompactMacSimulatorFilter} models a physical RC capacitor on the Macintosh logic board
 * (τ = 30 µs, verified by hardware capture). Neither the Apple II nor the IBM PC has such a
 * capacitor: both drive the speaker directly via logic-level toggling. The low-pass behaviour
 * comes from the mechanical inertia of the speaker cone. Applying the RC label to these modes
 * would be physically inaccurate.
 *
 * <h2>Solution: 4× internal oversampling</h2>
 * The filter operates internally at 176,400 Hz (4×). At this rate, the Apple II 22,050 Hz carrier
 * is at exactly 12.5% of the Nyquist frequency (8 sub-samples per carrier period — no rounding).
 * The PC 15,200 Hz carrier is at ~8.6% (≈11.6 sub-samples per carrier period — minor rounding
 * for the PC mode only, producing inaudible artefacts above 88 kHz). Each sub-sample evaluates
 * the raw ±1 PWM bit directly and feeds it to the speaker-cone IIR model.
 */
public class OneBitHardwareFilter implements AudioProcessor
{
    private static final int OVERSAMPLE = 4;
    private static final double INTERNAL_RATE = 44100.0 * OVERSAMPLE; // 176400.0 Hz

    private final boolean enabled;
    private final AudioProcessor next;
    private final String mode;

    // Carrier phase, advanced by subCarrierStep each sub-sample
    private double carrierPhase = 0.0;
    private final double subCarrierStep; // = carrierHz / INTERNAL_RATE

    // Duty-cycle quantisation resolution
    private final double levels;

    // DSD (delta-sigma) error accumulator — used only in "dsd" mode, untouched otherwise
    private double dsdErr = 0.0;
    private final Random rand = new Random();

    // Cone IIR state (two cascaded one-pole low-pass filters at 176,400 Hz)
    private double iirState1 = 0.0;
    private double iirState2 = 0.0;
    private final double iirAlpha; // = 1 - exp(-1 / (INTERNAL_RATE * tauUs * 1e-6))

    // PC-speaker resonance biquads (Direct Form I, Audio EQ Cookbook peaking EQ).
    // null for apple2 (no resonance peaks). At most two biquads are allocated.
    // Each coeffs array: {b0/a0, b1/a0, b2/a0, a1/a0, a2/a0}
    // Each state array:  {x1, x2, y1, y2}
    private final double @Nullable [] biquad1Coeffs;
    private final double @Nullable [] biquad1State;
    private final double @Nullable [] biquad2Coeffs;
    private final double @Nullable [] biquad2State;

    /**
     * @param enabled        whether the filter is active (pass-through when false)
     * @param mode           "pwm" (cone simulation) or "dsd" (delta-sigma, unchanged)
     * @param carrierHz      PWM carrier frequency in Hz (22050 for apple2, 15200 for pc)
     * @param levels         number of discrete duty-cycle levels (32 for apple2, 78 for pc)
     * @param tauUs          speaker-cone mechanical time constant in microseconds.
     *                       Derived from the original smoothAlpha via
     *                       τ = −1 / (44100 × ln(1 − smoothAlpha)).
     *                       apple2: 28.4 µs (from α=0.55), pc: 37.9 µs (from α=0.45).
     * @param resonancePeaks flat array of {f0Hz, dBgain, Q} triplets for peaking biquads,
     *                       or null/empty for no resonance (apple2). At most two triplets
     *                       (six elements) are used; extras are ignored per YAGNI.
     * @param next           next processor in the chain
     */
    public OneBitHardwareFilter(boolean enabled, String mode,
            double carrierHz, double levels, double tauUs,
            double @Nullable [] resonancePeaks, AudioProcessor next)
    {
        this.enabled = enabled;
        this.next = next;
        this.mode = mode != null ? mode.toLowerCase(ROOT) : "pwm";
        this.subCarrierStep = carrierHz / INTERNAL_RATE;
        this.levels = levels;
        this.iirAlpha = 1.0 - exp(-1.0 / (INTERNAL_RATE * tauUs * 1e-6));

        if (resonancePeaks != null && resonancePeaks.length >= 3) {
            biquad1Coeffs = computePeakingBiquad(resonancePeaks[0], resonancePeaks[1], resonancePeaks[2]);
            biquad1State  = new double[4];
        } else {
            biquad1Coeffs = null;
            biquad1State  = null;
        }
        if (resonancePeaks != null && resonancePeaks.length >= 6) {
            biquad2Coeffs = computePeakingBiquad(resonancePeaks[3], resonancePeaks[4], resonancePeaks[5]);
            biquad2State  = new double[4];
        } else {
            biquad2Coeffs = null;
            biquad2State  = null;
        }
    }

    @Override
    public void process(float[] left, float[] right, int frames)
    {
        if (!enabled) { next.process(left, right, frames); return; }

        for (int i = 0; i < frames; i++) {
            float filtered = processOneSample((left[i] + right[i]) * 0.5);
            left[i] = filtered;
            right[i] = filtered;
        }
        next.process(left, right, frames);
    }

    @Override
    public void processInterleaved(short[] interleavedPcm, int frames, int channels)
    {
        if (!enabled) { next.processInterleaved(interleavedPcm, frames, channels); return; }

        for (int i = 0; i < frames; i++) {
            int lIdx = i * channels;
            float l = interleavedPcm[lIdx] / 32768.0f;
            float r = channels > 1 ? interleavedPcm[lIdx + 1] / 32768.0f : l;
            float filtered = processOneSample((l + r) * 0.5);
            short out = (short) max(-32768, min(32767, (int)(filtered * 32768.0)));
            interleavedPcm[lIdx] = out;
            if (channels > 1) interleavedPcm[lIdx + 1] = out;
        }
        next.processInterleaved(interleavedPcm, frames, channels);
    }

    private float processOneSample(double monoIn)
    {
        if ("dsd".equals(mode)) {
            // Delta-sigma: unchanged from original implementation
            if (abs(monoIn) < 1e-4) return 0.0f;
            dsdErr += monoIn + (rand.nextDouble() - 0.5) * 0.1;
            double out = dsdErr > 0.0 ? 1.0 : -1.0;
            dsdErr -= out;
            iirState1 += iirAlpha * (out - iirState1);
            iirState2 += iirAlpha * (iirState1 - iirState2);
            return (float) iirState2;
        }

        // PWM mode: 4× oversampled cone simulation.
        // The fast-path for silent input (abs < 1e-4) is intentionally absent here:
        // skipping the loop would stall carrierPhase, causing a pop when audio resumes.
        // At duty=0.5 (silence), the IIR naturally averages the alternating ±1 bits to ~0.
        double rawDuty = max(0.0, min(1.0, (monoIn + 1.0) * 0.5));
        double duty    = round(rawDuty * levels) / levels;

        for (int s = 0; s < OVERSAMPLE; s++) {
            double bit = (carrierPhase < duty) ? 1.0 : -1.0;
            iirState1 += iirAlpha * (bit - iirState1);
            iirState2 += iirAlpha * (iirState1 - iirState2);
            carrierPhase = (carrierPhase + subCarrierStep) % 1.0;
        }

        double out = iirState2;
        double[] c1 = biquad1Coeffs, s1 = biquad1State;
        if (c1 != null && s1 != null) out = applyBiquad(c1, s1, out);
        double[] c2 = biquad2Coeffs, s2 = biquad2State;
        if (c2 != null && s2 != null) out = applyBiquad(c2, s2, out);
        return (float) out;
    }

    /**
     * Computes normalised Direct Form I coefficients for a peaking EQ biquad
     * (Audio EQ Cookbook, R. Bristow-Johnson) at the internal sample rate 176,400 Hz.
     *
     * The bilinear transform is applied at INTERNAL_RATE, so the peak frequency is
     * accurate up to the pre-warping limit. At 2.5 kHz and 6.7 kHz, pre-warping error
     * is negligible (< 0.2% frequency shift).
     *
     * @return double[5] {b0/a0, b1/a0, b2/a0, a1/a0, a2/a0}
     */
    private static double[] computePeakingBiquad(double f0Hz, double dBgain, double Q)
    {
        double A     = pow(10.0, dBgain / 40.0);
        double w0    = 2.0 * PI * f0Hz / INTERNAL_RATE;
        double alpha = sin(w0) / (2.0 * Q);

        double b0 =  1.0 + alpha * A;
        double b1 = -2.0 * cos(w0);
        double b2 =  1.0 - alpha * A;
        double a0 =  1.0 + alpha / A;
        double a1 = -2.0 * cos(w0);
        double a2 =  1.0 - alpha / A;

        return new double[]{ b0/a0, b1/a0, b2/a0, a1/a0, a2/a0 };
    }

    /**
     * Applies one Direct Form I biquad step.
     * state: {x1, x2, y1, y2} — updated in place.
     * coeffs: {b0/a0, b1/a0, b2/a0, a1/a0, a2/a0}
     */
    private static double applyBiquad(double[] coeffs, double[] state, double x)
    {
        double y = coeffs[0]*x + coeffs[1]*state[0] + coeffs[2]*state[1]
                               - coeffs[3]*state[2] - coeffs[4]*state[3];
        state[1] = state[0]; state[0] = x;
        state[3] = state[2]; state[2] = y;
        return y;
    }

    @Override
    public void reset()
    {
        carrierPhase = 0.0;
        dsdErr       = 0.0;
        iirState1    = 0.0;
        iirState2    = 0.0;
        if (biquad1State != null) Arrays.fill(biquad1State, 0.0);
        if (biquad2State != null) Arrays.fill(biquad2State, 0.0);
        next.reset();
    }
}

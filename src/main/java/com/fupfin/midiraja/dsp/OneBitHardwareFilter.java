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
 * <h2>Solution: 4× internal oversampling + 7-pole speaker model (1 electrical + 6 mechanical)</h2>
 * The filter operates internally at 176,400 Hz (4×). The cone IIR needs at least 4–8 sub-samples
 * per carrier period to accurately track PWM transitions; 4× satisfies this for both modes:
 * the Apple II 22,050 Hz carrier gets exactly 8 sub-samples per period (no rounding), and the
 * PC 15,200 Hz carrier gets ≈11.6 (minor rounding artefacts appear above 88 kHz, inaudible).
 * Each sub-sample first passes the ±1 PWM bit through an electrical pre-filter (voice-coil
 * inductance, τ_e ≈ 10 µs), then feeds the result to the 6-pole mechanical cone IIR model.
 *
 * <h2>Electrical pre-filter — voice coil inductance</h2>
 * A cheap PC speaker voice coil is an inductor (L ≈ 0.1–0.3 mH, R ≈ 8 Ω). The current through
 * an RL circuit responds to a voltage step as i(t) = V/R × (1 − e^{−t/τ_e}) where τ_e = L/R.
 * For a 0.1 mH coil at 8 Ω: τ_e = 12.5 µs; we use τ_e = 10 µs as a conservative estimate.
 * The cone moves in proportion to current, not voltage, so the ±1 square wave voltage first
 * becomes a triangle-wave-like waveform (exponential edges instead of perfectly sharp ones)
 * before the mechanical system integrates it further. This softens the initial transient and
 * rounds off the sharp corners of the ideal square wave, adding a subtle "warm" distortion
 * character. At 176,400 Hz: α_e = 1 − exp(−1 / (176400 × 10e−6)) ≈ 0.433.
 *
 * <h2>Why six IIR poles</h2>
 * With two cascaded one-pole filters (τ = 37.9 µs) the attenuation at the 15.2 kHz PC carrier is
 * only −23 dB, leaving a carrier residual of ≈4.7% that overwhelms quiet signals. Four poles
 * improve this to −46 dB (residual ≈0.34%), but the 15.2 kHz tone is a pure sinusoid and remains
 * audible over quiet passages at −40 dB or softer. Six poles reach −68 dB (residual ≈0.025%),
 * keeping the carrier inaudible even at −50 dB signal levels. Six poles also better approximates
 * the steep mechanical roll-off of a real PC speaker cone, which barely moves at 15 kHz.
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

    // Source audio pre-quantisation (0 = disabled). Applied before PWM encoding to replicate
    // the bit-depth of 1980s source material (8-bit PCM, no dither — historically accurate).
    private final int preLevels;

    // Input drive gain and its reciprocal.
    // Applied as: monoIn × driveGain → clamp[-1,1] → PWM → IIR → out ÷ driveGain.
    // Driving harder uses more of the quantiser's dynamic range, reducing quantisation noise
    // by ~6 dB per doubling. Carrier noise scales proportionally with the input so it cancels
    // out, leaving only quantisation SNR improvement. Output level is preserved.
    private final double driveGain;
    private final double invDriveGain;

    // DSD (delta-sigma) error accumulator — used only in "dsd" mode, untouched otherwise
    private double dsdErr = 0.0;
    private final Random rand = new Random();

    // Electrical pre-filter: voice-coil RL inductance (τ_e = 10 µs).
    // Converts the ±1 square wave voltage into an exponentially-edged current waveform
    // before the mechanical system sees it. α_e = 1 - exp(-1/(176400 × 10e-6)) ≈ 0.433.
    private double iirStatePre = 0.0;
    private final double iirAlphaPre; // = 1 - exp(-1 / (INTERNAL_RATE * tauElecUs * 1e-6))

    // Cone IIR state (six cascaded one-pole low-pass filters at 176,400 Hz).
    // Pole count vs. carrier attenuation at 15.2 kHz (|H_single| = 0.270):
    //   2 poles → -23 dB  (carrier residual 4.7%, overwhelms quiet signals)
    //   4 poles → -46 dB  (residual 0.34%, marginal at -40 dB signals)
    //   6 poles → -68 dB  (residual 0.025%, inaudible even at -50 dB signals)
    // Six mechanical poles plus the electrical pre-filter pole give 7 poles total.
    private double iirState1 = 0.0;
    private double iirState2 = 0.0;
    private double iirState3 = 0.0;
    private double iirState4 = 0.0;
    private double iirState5 = 0.0;
    private double iirState6 = 0.0;
    private final double iirAlpha;    // = 1 - exp(-1 / (INTERNAL_RATE * tauUs * 1e-6)) — for PWM loop at 176,400 Hz
    private final double iirAlphaDsd; // = 1 - exp(-1 / (44100 * tauUs * 1e-6))         — for DSD mode at 44,100 Hz

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
     * @param preBitDepth    source audio bit depth to pre-quantise to before PWM encoding
     *                       (e.g. 8 for 8-bit PCM), or 0 to disable. Models the limited
     *                       bit depth of original source material. No dither applied —
     *                       historically accurate: 1980s tools used simple rounding.
     * @param driveGain      input gain applied before PWM and removed after IIR (1.0 = unity).
     *                       Drives the signal harder into the quantiser, reducing quantisation
     *                       noise by ~6 dB per doubling. Output level is preserved.
     * @param resonancePeaks flat array of {f0Hz, dBgain, Q} triplets for peaking biquads,
     *                       or null/empty for no resonance (apple2, pc). At most two triplets
     *                       (six elements) are used; extras are ignored per YAGNI.
     * @param next           next processor in the chain
     */
    public OneBitHardwareFilter(boolean enabled, String mode,
            double carrierHz, double levels, double tauUs, int preBitDepth, double driveGain,
            double @Nullable [] resonancePeaks, AudioProcessor next)
    {
        this.enabled = enabled;
        this.next = next;
        this.mode = mode != null ? mode.toLowerCase(ROOT) : "pwm";
        this.subCarrierStep = carrierHz / INTERNAL_RATE;
        this.levels = levels;
        this.preLevels   = preBitDepth > 0 ? (1 << (preBitDepth - 1)) : 0;
        this.driveGain   = driveGain > 0.0 ? driveGain : 1.0;
        this.invDriveGain = 1.0 / this.driveGain;
        this.iirAlpha    = 1.0 - exp(-1.0 / (INTERNAL_RATE * tauUs * 1e-6));
        this.iirAlphaDsd = 1.0 - exp(-1.0 / (44100.0 * tauUs * 1e-6));
        // Voice-coil electrical time constant: τ_e = 10 µs (L/R ≈ 0.1mH / 8Ω = 12.5 µs, conservative)
        this.iirAlphaPre = 1.0 - exp(-1.0 / (INTERNAL_RATE * 10e-6));

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
            iirState1 += iirAlphaDsd * (out - iirState1);
            iirState2 += iirAlphaDsd * (iirState1 - iirState2);
            return (float) iirState2;
        }

        // Drive gain: scale up before quantisation, scale back down after IIR.
        // Reduces quantisation noise by ~6 dB per doubling; output level is preserved.
        if (driveGain != 1.0) {
            monoIn = max(-1.0, min(1.0, monoIn * driveGain));
        }

        // Pre-quantise to source bit depth (e.g. 8-bit PCM). Models the limited resolution of
        // 1980s source material fed into the hardware. No dither — historically accurate.
        if (preLevels > 0) {
            monoIn = round(monoIn * preLevels) / (double) preLevels;
        }

        // PWM mode: 4× oversampled cone simulation.
        // Silence fast-path: real hardware stops toggling when no audio is playing — the speaker
        // pin holds its last level and the cone rings down to equilibrium. Feeding zero to the IIR
        // reproduces this. Carrier phase still advances so there is no phase discontinuity (pop)
        // when audio resumes. Note: running at duty=0.5 does NOT average to ~0 — the 2-pole IIR
        // only attenuates the ~15 kHz carrier by ~23 dB, leaving audible residual noise.
        if (abs(monoIn) < 1e-4) {
            for (int s = 0; s < OVERSAMPLE; s++) {
                iirStatePre += iirAlphaPre * (0.0 - iirStatePre);
                iirState1 += iirAlpha * (iirStatePre - iirState1);
                iirState2 += iirAlpha * (iirState1 - iirState2);
                iirState3 += iirAlpha * (iirState2 - iirState3);
                iirState4 += iirAlpha * (iirState3 - iirState4);
                iirState5 += iirAlpha * (iirState4 - iirState5);
                iirState6 += iirAlpha * (iirState5 - iirState6);
                carrierPhase = (carrierPhase + subCarrierStep) % 1.0;
            }
            double out = iirState6 * invDriveGain;
            double[] c1 = biquad1Coeffs, s1 = biquad1State;
            if (c1 != null && s1 != null) out = applyBiquad(c1, s1, out);
            double[] c2 = biquad2Coeffs, s2 = biquad2State;
            if (c2 != null && s2 != null) out = applyBiquad(c2, s2, out);
            return (float) out;
        }

        double rawDuty = max(0.0, min(1.0, (monoIn + 1.0) * 0.5));
        double duty    = round(rawDuty * levels) / levels;

        for (int s = 0; s < OVERSAMPLE; s++) {
            double bit = (carrierPhase < duty) ? 1.0 : -1.0;
            iirStatePre += iirAlphaPre * (bit - iirStatePre);
            iirState1 += iirAlpha * (iirStatePre - iirState1);
            iirState2 += iirAlpha * (iirState1 - iirState2);
            iirState3 += iirAlpha * (iirState2 - iirState3);
            iirState4 += iirAlpha * (iirState3 - iirState4);
            iirState5 += iirAlpha * (iirState4 - iirState5);
            iirState6 += iirAlpha * (iirState5 - iirState6);
            carrierPhase = (carrierPhase + subCarrierStep) % 1.0;
        }

        double out = iirState6 * invDriveGain;
        double[] c1 = biquad1Coeffs, s1 = biquad1State;
        if (c1 != null && s1 != null) out = applyBiquad(c1, s1, out);
        double[] c2 = biquad2Coeffs, s2 = biquad2State;
        if (c2 != null && s2 != null) out = applyBiquad(c2, s2, out);
        return (float) out;
    }

    /**
     * Computes normalised Direct Form I coefficients for a peaking EQ biquad
     * (Audio EQ Cookbook, R. Bristow-Johnson) at the output sample rate 44,100 Hz.
     *
     * The biquad is called once per output sample (not per sub-sample), so it must
     * be designed at 44,100 Hz. At 2.5 kHz and 6.7 kHz, pre-warping error is
     * negligible (< 0.2% frequency shift).
     *
     * @return double[5] {b0/a0, b1/a0, b2/a0, a1/a0, a2/a0}
     */
    private static double[] computePeakingBiquad(double f0Hz, double dBgain, double Q)
    {
        double A     = pow(10.0, dBgain / 40.0);
        double w0    = 2.0 * PI * f0Hz / 44100.0;
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
        iirStatePre  = 0.0;
        iirState1    = 0.0;
        iirState2    = 0.0;
        iirState3    = 0.0;
        iirState4    = 0.0;
        iirState5    = 0.0;
        iirState6    = 0.0;
        if (biquad1State != null) Arrays.fill(biquad1State, 0.0);
        if (biquad2State != null) Arrays.fill(biquad2State, 0.0);
        next.reset();
    }
}

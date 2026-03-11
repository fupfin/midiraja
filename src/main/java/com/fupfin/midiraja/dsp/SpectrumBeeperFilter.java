package com.fupfin.midiraja.dsp;

/**
 * Simulates the ZX Spectrum 48K's 22mm 40Ω beeper.
 *
 * Unlike PWM-based hardware (PC speaker, Apple II), the Spectrum's Z80 CPU directly toggled bit 4
 * of port $FE to drive the speaker. Software audio routines ran at ~17.5 kHz with up to 200 discrete
 * amplitude levels (~7.6-bit), using cycle-counted Z80 loops.
 *
 * Signal path:
 *   Input (L+R) → Mono mixdown → Z80 quantization (128 levels) → HP (~510 Hz) → 2× LP (~4.5 kHz) → Output (mono)
 *
 * Physical modeling rationale:
 * - LEVELS=128: Z80 3.5 MHz / ~27 cycles per step ≈ 128 discrete amplitude steps (~7-bit)
 * - HP_ALPHA=0.930: ~510 Hz high-pass; the 22mm beeper physically cannot reproduce bass
 * - LP_ALPHA=0.600: Two-stage ~4.5 kHz low-pass; small diaphragm inertia limits high-frequency response
 */
public class SpectrumBeeperFilter implements AudioProcessor
{
    private static final int LEVELS = 128;
    private static final float HP_ALPHA = 0.930f;
    private static final float LP_ALPHA = 0.600f;

    private final boolean enabled;
    private final AudioProcessor next;

    private float hpPrev = 0.0f;
    private float hpOut = 0.0f;
    private float lp1 = 0.0f;
    private float lp2 = 0.0f;

    public SpectrumBeeperFilter(boolean enabled, AudioProcessor next)
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
        for (int i = 0; i < frames; i++)
        {
            float mono = processSample((left[i] + right[i]) * 0.5f);
            left[i] = mono;
            right[i] = mono;
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
            float monoIn = interleavedPcm[leftIdx] / 32768.0f;
            if (channels > 1)
            {
                monoIn = (monoIn + (interleavedPcm[leftIdx + 1] / 32768.0f)) * 0.5f;
            }
            float mono = processSample(monoIn);
            short outShort = (short) Math.max(-32768, Math.min(32767, mono * 32768.0f));
            interleavedPcm[leftIdx] = outShort;
            if (channels > 1)
            {
                interleavedPcm[leftIdx + 1] = outShort;
            }
        }
        next.processInterleaved(interleavedPcm, frames, channels);
    }

    private float processSample(float monoIn)
    {
        // Z80 direct-toggle quantization: 128 discrete levels
        float clamped = Math.max(-1.0f, Math.min(1.0f, monoIn));
        int level = Math.round((clamped * 0.5f + 0.5f) * (LEVELS - 1));
        float quantized = (level / (float) (LEVELS - 1)) * 2.0f - 1.0f;

        // High-pass filter (~510 Hz): removes DC and sub-bass (physical beeper limitation)
        hpOut = HP_ALPHA * (hpOut + quantized - hpPrev);
        hpPrev = quantized;

        // Two-stage low-pass (~4.5 kHz): models small diaphragm inertia
        lp1 += LP_ALPHA * (hpOut - lp1);
        lp2 += LP_ALPHA * (lp1 - lp2);

        return lp2;
    }

    @Override
    public void reset()
    {
        hpPrev = 0.0f;
        hpOut = 0.0f;
        lp1 = 0.0f;
        lp2 = 0.0f;
        next.reset();
    }
}

package com.fupfin.midiraja.dsp;

import static com.fupfin.midiraja.dsp.DspConstants.INTERNAL_LEVEL_INV;

/**
 * A DSP filter that applies output gain to the audio stream.
 *
 * <p>
 * In the DSP pipeline, gain is composed of two independent factors:
 * <ul>
 * <li><b>calibration</b> — set once by the synth provider to normalize its raw output level.
 * <li><b>volumeScale</b> — adjusted by the user at runtime (0.0–1.5).
 * </ul>
 * Effective gain = {@code INTERNAL_LEVEL_INV × calibration × volumeScale}.
 *
 * <p>
 * The legacy {@link #setGain}/{@link #getGain} API bypasses the volume/calibration model and
 * sets the raw multiplier directly (used by tests and non-pipeline contexts).
 */
public class MasterGainFilter extends AudioFilter
{
    private volatile float gain = 1.0f;
    private volatile float calibration = 1.0f;

    public MasterGainFilter(AudioProcessor next)
    {
        super(next);
    }

    public MasterGainFilter(AudioProcessor next, float initialGain)
    {
        super(next);
        this.gain = Math.max(0.0f, initialGain);
    }

    public MasterGainFilter(AudioSink next, float initialGain)
    {
        super(next);
        this.gain = Math.max(0.0f, initialGain);
    }

    // --- Calibration (set once by the synth provider) ---

    /**
     * Sets the provider-specific calibration factor, preserving the current volume scale.
     * Call this after the initial volume is set (i.e. inside the provider's setMasterGain).
     */
    public void setCalibration(float cal)
    {
        float currentScale = getVolumeScale();
        this.calibration = Math.max(0.0f, cal);
        this.gain = INTERNAL_LEVEL_INV * this.calibration * currentScale;
    }

    // --- Volume scale (user-adjustable 0.0–1.5) ---

    /** Returns the user volume as a linear scale (1.0 = 100%). */
    public float getVolumeScale()
    {
        float denom = INTERNAL_LEVEL_INV * calibration;
        return denom == 0.0f ? 0.0f : gain / denom;
    }

    /** Sets the user volume scale, clamped to [0.0, 1.5]. */
    public void setVolumeScale(float scale)
    {
        scale = Math.max(0.0f, Math.min(1.5f, scale));
        this.gain = INTERNAL_LEVEL_INV * calibration * scale;
    }

    // --- Raw gain access (backward compat / tests) ---

    public float getGain()
    {
        return gain;
    }

    public void setGain(float gain)
    {
        this.gain = Math.max(0.0f, gain);
    }

    @Override
    public void process(float[] left, float[] right, int frames)
    {
        if (gain != 1.0f)
        {
            for (int i = 0; i < frames; i++)
            {
                left[i] *= gain;
                right[i] *= gain;
            }
        }
        next.process(left, right, frames);
    }
}

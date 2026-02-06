package com.midiraja.dsp;

/**
 * A global audio effect that simulates the harmonic distortion and soft clipping
 * of an analog vacuum tube amplifier or magnetic tape saturation.
 * 
 * It uses a mathematically optimized waveshaping curve to add warmth, 
 * thickness, and punch to digital synthesizers like OPL and PSG.
 */
public class TubeSaturationFilter extends AudioFilter {
    private volatile float drive;      // Input gain multiplier (1.0 = clean)
    private volatile float outLevel;   // Output volume makeup
    private volatile boolean enabled = true;

    /**
     * @param next The next node in the audio pipeline
     * @param drive Amount of overdrive (e.g., 2.0f to 5.0f for heavy saturation)
     */
    public TubeSaturationFilter(AudioProcessor next, float drive) {
        super(next);
        setDrive(drive);
    }

    public void setDrive(float drive) {
        this.drive = Math.max(1.0f, Math.min(10.0f, drive));
        
        // Improved Auto-makeup gain:
        // We use a square root scaling to counteract the massive energy boost 
        // that comes from squaring off the waveforms. 
        // This keeps the perceived loudness more consistent.
        this.outLevel = 1.0f / (float) Math.sqrt(this.drive);
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public void process(float[] left, float[] right, int frames) {
        if (!enabled || drive <= 1.01f) {
            // Bypass mode: do nothing, pass through cleanly
            if (next != null) next.process(left, right, frames);
            return;
        }

        final float d = this.drive;
        final float level = this.outLevel;

        for (int i = 0; i < frames; i++) {
            // --- The Tube Saturation Algorithm ---
            // 1. Boost the input signal (Drive)
            float inL = left[i] * d;
            float inR = right[i] * d;

            // 2. Apply Soft Clipping (Waveshaping)
            // Math.tanh is the gold standard for analog saturation modeling.
            // It curves smoothly near 1.0 (odd harmonics, "tube" sound) 
            // without hard digital clipping.
            float outL = (float) Math.tanh(inL);
            float outR = (float) Math.tanh(inR);

            // 3. Compensate output volume and write back (In-place)
            left[i] = outL * level;
            right[i] = outR * level;
        }

        if (next != null) {
            next.process(left, right, frames);
        }
    }
}

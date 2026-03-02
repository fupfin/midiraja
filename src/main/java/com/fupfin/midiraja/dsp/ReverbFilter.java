package com.fupfin.midiraja.dsp;

/**
 * A classic Schroeder/Moorer algorithmic reverb based on the Freeverb model.
 * Includes highly tuned presets for different musical spaces.
 */
public final class ReverbFilter extends AudioFilter {

    public enum Preset {
        ROOM(0.5f, 0.4f, 0.25f, 1.0f),       // Small, punchy, low damping
        CHAMBER(0.75f, 0.7f, 0.3f, 1.0f),    // Medium-large, dense, high damping (warm)
        HALL(0.85f, 0.5f, 0.35f, 1.0f),      // Large, lush, medium damping
        PLATE(0.7f, 0.1f, 0.3f, 1.0f),       // Bright, metallic, very low damping
        SPRING(0.6f, 0.2f, 0.35f, 1.0f),     // Bouncy, metallic, moderate length
        CAVE(0.98f, 0.6f, 0.45f, 1.0f);      // Massive, dark, highly washed out

        final float roomSize;
        final float damp;
        final float wet;
        final float width; // Placeholder for future stereo widening

        Preset(float roomSize, float damp, float wet, float width) {
            this.roomSize = roomSize;
            this.damp = damp;
            this.wet = wet;
            this.width = width;
        }
    }

    private static final int numCombs = 8;
    private static final int numAllpasses = 4;
    private static final float fixedGain = 0.015f;
    
    private float scaleRoom;
    private float scaleDamp;
    private float scaleWet;
    private float scaleDry;

    // Freeverb default tuning parameters (scaled for 44.1kHz)
    private static final int[] combTuningL = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
    private static final int[] combTuningR = {1116+23, 1188+23, 1277+23, 1356+23, 1422+23, 1491+23, 1557+23, 1617+23};
    private static final int[] allpassTuningL = {556, 441, 341, 225};
    private static final int[] allpassTuningR = {556+23, 441+23, 341+23, 225+23};

    // Sub-components
    private static class Comb {
        float[] buffer;
        int bufIdx;
        float feedback;
        float filterStore;
        float damp1;
        float damp2;

        Comb(int size) {
            buffer = new float[size];
            bufIdx = 0;
        }

        void setDamp(float val) {
            damp1 = val;
            damp2 = 1.0f - val;
        }

        void setFeedback(float val) {
            feedback = val;
        }

        float process(float input) {
            float output = buffer[bufIdx];
            filterStore = (output * damp2) + (filterStore * damp1);
            buffer[bufIdx] = input + (filterStore * feedback);
            if (++bufIdx >= buffer.length) bufIdx = 0;
            return output;
        }
    }

    private static class Allpass {
        float[] buffer;
        int bufIdx;
        final float feedback = 0.5f;

        Allpass(int size) {
            buffer = new float[size];
            bufIdx = 0;
        }

        float process(float input) {
            float bufferedValue = buffer[bufIdx];
            float output = -input + bufferedValue;
            buffer[bufIdx] = input + (bufferedValue * feedback);
            if (++bufIdx >= buffer.length) bufIdx = 0;
            return output;
        }
    }

    private final Comb[] combsL = new Comb[numCombs];
    private final Comb[] combsR = new Comb[numCombs];
    private final Allpass[] allpassesL = new Allpass[numAllpasses];
    private final Allpass[] allpassesR = new Allpass[numAllpasses];

    private volatile boolean enabled = true;

    /**
     * @param next Pipeline destination.
     * @param preset The tuned environment preset.
     */
    public ReverbFilter(AudioProcessor next, Preset preset, float levelScale) {
        super(next);
        
        for (int i = 0; i < numCombs; i++) {
            combsL[i] = new Comb(combTuningL[i]);
            combsR[i] = new Comb(combTuningR[i]);
        }
        for (int i = 0; i < numAllpasses; i++) {
            allpassesL[i] = new Allpass(allpassTuningL[i]);
            allpassesR[i] = new Allpass(allpassTuningR[i]);
        }

        setPreset(preset, levelScale);
    }
    
    public ReverbFilter(AudioProcessor next, Preset preset) {
        this(next, preset, 1.0f);
    }

    public void setPreset(Preset preset, float levelScale) {
        this.scaleRoom = (preset.roomSize * 0.28f) + 0.7f;
        this.scaleDamp = preset.damp * 0.4f;
        
        // Scale the wet volume (clamped to a reasonable max to prevent blowing out speakers)
        this.scaleWet = Math.min(1.0f, preset.wet * levelScale);
        
        // If wet is boosted heavily, we reduce the dry signal slightly more to keep the overall volume balanced.
        this.scaleDry = Math.max(0.1f, 1.0f - (this.scaleWet * 0.6f));

        for (int i = 0; i < numCombs; i++) {
            combsL[i].setFeedback(scaleRoom);
            combsR[i].setFeedback(scaleRoom);
            combsL[i].setDamp(scaleDamp);
            combsR[i].setDamp(scaleDamp);
        }
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public void process(float[] left, float[] right, int frames) {
        if (!enabled) {
            if (next != null) next.process(left, right, frames);
            return;
        }

        for (int i = 0; i < frames; i++) {
            float inL = left[i];
            float inR = right[i];
            
            float monoInput = (inL + inR) * fixedGain;
            float outL = 0;
            float outR = 0;

            for (int j = 0; j < numCombs; j++) {
                outL += combsL[j].process(monoInput);
                outR += combsR[j].process(monoInput);
            }

            for (int j = 0; j < numAllpasses; j++) {
                outL = allpassesL[j].process(outL);
                outR = allpassesR[j].process(outR);
            }

            left[i] = (outL * scaleWet) + (inL * scaleDry);
            right[i] = (outR * scaleWet) + (inR * scaleDry);
        }

        if (next != null) {
            next.process(left, right, frames);
        }
    }
}

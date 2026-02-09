package com.midiraja.dsp;

/**
 * A 3-band Equalizer (Bass, Mid, Treble) using Biquad IIR filters.
 * Each band can be boosted or cut using percentage values (0-200%).
 */
public class EqFilter extends AudioFilter {

    private final Biquad bassFilterL, bassFilterR;
    private final Biquad midFilterL, midFilterR;
    private final Biquad trebleFilterL, trebleFilterR;
    private final Biquad lpfL, lpfR;
    private final Biquad hpfL, hpfR;
    private boolean useLpf = false;
    private boolean useHpf = false;

    private final float sampleRate = 44100.0f;

    public EqFilter(AudioProcessor next) {
        super(next);
        bassFilterL = new Biquad(); bassFilterR = new Biquad();
        midFilterL = new Biquad(); midFilterR = new Biquad();
        trebleFilterL = new Biquad(); trebleFilterR = new Biquad();
        lpfL = new Biquad(); lpfR = new Biquad();
        hpfL = new Biquad(); hpfR = new Biquad();
        
        // Initial setup at 100% (Neutral)
        setParams(50, 50, 50);
    }

    public void setParams(float bassPct, float midPct, float treblePct) {
        // Frequency bands: Bass < 250Hz, Mid ~ 1kHz, Treble > 4kHz
        bassFilterL.setLowShelf(sampleRate, 250.0f, 1.0f, pctToDb(bassPct));
        bassFilterR.setLowShelf(sampleRate, 250.0f, 1.0f, pctToDb(bassPct));
        
        midFilterL.setPeaking(sampleRate, 1000.0f, 1.0f, pctToDb(midPct));
        midFilterR.setPeaking(sampleRate, 1000.0f, 1.0f, pctToDb(midPct));
        
        trebleFilterL.setHighShelf(sampleRate, 4000.0f, 1.0f, pctToDb(treblePct));
        trebleFilterR.setHighShelf(sampleRate, 4000.0f, 1.0f, pctToDb(treblePct));
    }

    
    public void setLpf(float cutoffHz) {
        if (cutoffHz >= 20000.0f) {
            useLpf = false;
        } else {
            useLpf = true;
            lpfL.setLowPass(sampleRate, cutoffHz, 0.707f);
            lpfR.setLowPass(sampleRate, cutoffHz, 0.707f);
        }
    }

    public void setHpf(float cutoffHz) {
        if (cutoffHz <= 20.0f) {
            useHpf = false;
        } else {
            useHpf = true;
            hpfL.setHighPass(sampleRate, cutoffHz, 0.707f);
            hpfR.setHighPass(sampleRate, cutoffHz, 0.707f);
        }
    }

    private float pctToDb(float pct) {
        if (pct <= 0) return -60.0f; // Mute
        // 100% -> 0dB, 200% -> +12dB, 50% -> -6dB
        return (float) (20.0 * Math.log10(pct / 50.0));
    }

    @Override
    public void process(float[] left, float[] right, int frames) {
        for (int i = 0; i < frames; i++) {
            float l = left[i];
            float r = right[i];

            // Run through filters in series
            l = bassFilterL.process(l);
            l = midFilterL.process(l);
            l = trebleFilterL.process(l);
            if (useLpf) l = lpfL.process(l);
            if (useHpf) l = hpfL.process(l);

            r = bassFilterR.process(r);
            r = midFilterR.process(r);
            r = trebleFilterR.process(r);
            if (useLpf) r = lpfR.process(r);
            if (useHpf) r = hpfR.process(r);

            left[i] = l;
            right[i] = r;
        }
        if (next != null) next.process(left, right, frames);
    }

    /**
     * RBJ Biquad Filter Implementation
     */
    private static class Biquad {
        float b0, b1, b2, a1, a2;
        float x1, x2, y1, y2;

        void setLowShelf(float fs, float f0, float q, float dbGain) {
            float a = (float) Math.pow(10, dbGain / 40.0);
            float w0 = (float) (2.0 * Math.PI * f0 / fs);
            float cosW0 = (float) Math.cos(w0);
            float sinW0 = (float) Math.sin(w0);
            float alpha = sinW0 / 2.0f * (float) Math.sqrt((a + 1.0f / a) * (1.0f / q - 1.0f) + 2.0f);
            float sqrtA2 = 2.0f * (float) Math.sqrt(a) * alpha;

            b0 = a * ((a + 1.0f) - (a - 1.0f) * cosW0 + sqrtA2);
            b1 = 2.0f * a * ((a - 1.0f) - (a + 1.0f) * cosW0);
            b2 = a * ((a + 1.0f) - (a - 1.0f) * cosW0 - sqrtA2);
            float a0 = (a + 1.0f) + (a - 1.0f) * cosW0 + sqrtA2;
            a1 = -2.0f * ((a - 1.0f) + (a + 1.0f) * cosW0);
            a2 = (a + 1.0f) + (a - 1.0f) * cosW0 - sqrtA2;

            normalize(a0);
        }

        void setHighShelf(float fs, float f0, float q, float dbGain) {
            float a = (float) Math.pow(10, dbGain / 40.0);
            float w0 = (float) (2.0 * Math.PI * f0 / fs);
            float cosW0 = (float) Math.cos(w0);
            float sinW0 = (float) Math.sin(w0);
            float alpha = sinW0 / 2.0f * (float) Math.sqrt((a + 1.0f / a) * (1.0f / q - 1.0f) + 2.0f);
            float sqrtA2 = 2.0f * (float) Math.sqrt(a) * alpha;

            b0 = a * ((a + 1.0f) + (a - 1.0f) * cosW0 + sqrtA2);
            b1 = -2.0f * a * ((a - 1.0f) + (a + 1.0f) * cosW0);
            b2 = a * ((a + 1.0f) + (a - 1.0f) * cosW0 - sqrtA2);
            float a0 = (a + 1.0f) - (a - 1.0f) * cosW0 + sqrtA2;
            a1 = 2.0f * ((a - 1.0f) - (a + 1.0f) * cosW0);
            a2 = (a + 1.0f) - (a - 1.0f) * cosW0 - sqrtA2;

            normalize(a0);
        }

        void setPeaking(float fs, float f0, float q, float dbGain) {
            float a = (float) Math.pow(10, dbGain / 40.0);
            float w0 = (float) (2.0 * Math.PI * f0 / fs);
            float cosW0 = (float) Math.cos(w0);
            float alpha = (float) (Math.sin(w0) / (2.0 * q));

            b0 = 1.0f + alpha * a;
            b1 = -2.0f * cosW0;
            b2 = 1.0f - alpha * a;
            float a0 = 1.0f + alpha / a;
            a1 = -2.0f * cosW0;
            a2 = 1.0f - alpha / a;

            normalize(a0);
        }

        
        void setLowPass(float fs, float f0, float q) {
            float w0 = (float) (2.0 * Math.PI * f0 / fs);
            float cosW0 = (float) Math.cos(w0);
            float sinW0 = (float) Math.sin(w0);
            float alpha = sinW0 / (2.0f * q);

            b0 = (1.0f - cosW0) / 2.0f;
            b1 = 1.0f - cosW0;
            b2 = (1.0f - cosW0) / 2.0f;
            float a0 = 1.0f + alpha;
            a1 = -2.0f * cosW0;
            a2 = 1.0f - alpha;

            normalize(a0);
        }

        void setHighPass(float fs, float f0, float q) {
            float w0 = (float) (2.0 * Math.PI * f0 / fs);
            float cosW0 = (float) Math.cos(w0);
            float sinW0 = (float) Math.sin(w0);
            float alpha = sinW0 / (2.0f * q);

            b0 = (1.0f + cosW0) / 2.0f;
            b1 = -(1.0f + cosW0);
            b2 = (1.0f + cosW0) / 2.0f;
            float a0 = 1.0f + alpha;
            a1 = -2.0f * cosW0;
            a2 = 1.0f - alpha;

            normalize(a0);
        }
        private void normalize(float a0) {
            b0 /= a0; b1 /= a0; b2 /= a0;
            a1 /= a0; a2 /= a0;
        }

        float process(float x) {
            float y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1; x1 = x;
            y2 = y1; y1 = y;
            return y;
        }
    }
}

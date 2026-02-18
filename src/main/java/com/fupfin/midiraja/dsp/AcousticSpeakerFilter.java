package com.fupfin.midiraja.dsp;

/**
 * Simulates the physical acoustics of vintage speakers and computer enclosures.
 * Provides profiles for various era-accurate hardware like the internal IBM PC Speaker.
 */
public class AcousticSpeakerFilter implements AudioProcessor {
    public enum Profile {
        NONE,
        TIN_CAN,
        WARM_RADIO
    }

    private final boolean enabled;
    private final AudioProcessor next;
    private final Profile profile;

    private final Biquad hpfL = new Biquad(), hpfR = new Biquad();
    private final Biquad lpfL = new Biquad(), lpfR = new Biquad();
    private final Biquad peakL = new Biquad(), peakR = new Biquad();

    public AcousticSpeakerFilter(boolean enabled, Profile profile, AudioProcessor next) {
        this.enabled = enabled;
        this.profile = profile != null ? profile : Profile.NONE;
        this.next = next;
        setupFilters();
    }

    private void setupFilters() {
        float fs = 44100.0f;
        if (profile == Profile.TIN_CAN) {
            hpfL.setHighPass(fs, 400.0f, 0.707f);
            hpfR.setHighPass(fs, 400.0f, 0.707f);
            lpfL.setLowPass(fs, 6000.0f, 0.707f);
            lpfR.setLowPass(fs, 6000.0f, 0.707f);
            peakL.setPeaking(fs, 2500.0f, 2.0f, 12.0f);
            peakR.setPeaking(fs, 2500.0f, 2.0f, 12.0f);
        } else if (profile == Profile.WARM_RADIO) {
            // Macintosh 128k speaker inertia (~12kHz soft roll-off)
            lpfL.setLowPass(fs, 12000.0f, 0.5f);
            lpfR.setLowPass(fs, 12000.0f, 0.5f);
        }
    }

    @Override
    public void process(float[] left, float[] right, int frames) {
        if (!enabled || profile == Profile.NONE) {
            next.process(left, right, frames);
            return;
        }
        for (int i = 0; i < frames; i++) {
            float l = left[i], r = right[i];
            if (profile == Profile.TIN_CAN) {
                l = lpfL.process(hpfL.process(peakL.process(l)));
                r = lpfR.process(hpfR.process(peakR.process(r)));
            } else if (profile == Profile.WARM_RADIO) {
                l = lpfL.process(l);
                r = lpfR.process(r);
            }
            left[i] = l;
            right[i] = r;
        }
        next.process(left, right, frames);
    }

    @Override
    public void processInterleaved(short[] interleavedPcm, int frames, int channels) {
        if (!enabled || profile == Profile.NONE) {
            next.processInterleaved(interleavedPcm, frames, channels);
            return;
        }
        for (int i = 0; i < frames; i++) {
            int lIdx = i * channels;
            float l = interleavedPcm[lIdx] / 32768.0f;
            if (profile == Profile.TIN_CAN) l = lpfL.process(hpfL.process(peakL.process(l)));
            else if (profile == Profile.WARM_RADIO) l = lpfL.process(l);
            interleavedPcm[lIdx] = (short) (l * 32767.0f);

            if (channels > 1) {
                int rIdx = lIdx + 1;
                float r = interleavedPcm[rIdx] / 32768.0f;
                if (profile == Profile.TIN_CAN) r = lpfR.process(hpfR.process(peakR.process(r)));
                else if (profile == Profile.WARM_RADIO) r = lpfR.process(r);
                interleavedPcm[rIdx] = (short) (r * 32767.0f);
            }
        }
        next.processInterleaved(interleavedPcm, frames, channels);
    }

    @Override
    public void reset() {
        hpfL.reset(); hpfR.reset();
        lpfL.reset(); lpfR.reset();
        peakL.reset(); peakR.reset();
        next.reset();
    }

    private static class Biquad {
        float b0, b1, b2, a1, a2, x1, x2, y1, y2;
        void setPeaking(float fs, float f0, float q, float dbGain) {
            float a = (float) Math.pow(10, dbGain / 40.0), w0 = (float) (2.0 * Math.PI * f0 / fs);
            float cosW0 = (float) Math.cos(w0), alpha = (float) (Math.sin(w0) / (2.0 * q));
            float a0 = 1.0f + alpha / a;
            b0 = (1.0f + alpha * a) / a0; b1 = (-2.0f * cosW0) / a0; b2 = (1.0f - alpha * a) / a0;
            a1 = (-2.0f * cosW0) / a0; a2 = (1.0f - alpha / a) / a0;
        }
        void setLowPass(float fs, float f0, float q) {
            float w0 = (float) (2.0 * Math.PI * f0 / fs), cosW0 = (float) Math.cos(w0), alpha = (float) (Math.sin(w0) / (2.0f * q));
            float a0 = 1.0f + alpha;
            b0 = (1.0f - cosW0) / 2.0f / a0; b1 = (1.0f - cosW0) / a0; b2 = (1.0f - cosW0) / 2.0f / a0;
            a1 = -2.0f * cosW0 / a0; a2 = (1.0f - alpha) / a0;
        }
        void setHighPass(float fs, float f0, float q) {
            float w0 = (float) (2.0 * Math.PI * f0 / fs), cosW0 = (float) Math.cos(w0), alpha = (float) (Math.sin(w0) / (2.0f * q));
            float a0 = 1.0f + alpha;
            b0 = (1.0f + cosW0) / 2.0f / a0; b1 = -(1.0f + cosW0) / a0; b2 = (1.0f + cosW0) / 2.0f / a0;
            a1 = -2.0f * cosW0 / a0; a2 = (1.0f - alpha) / a0;
        }
        float process(float x) {
            float y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1; x1 = x; y2 = y1; y1 = y; return y;
        }
        void reset() { x1 = x2 = y1 = y2 = 0; }
    }
}

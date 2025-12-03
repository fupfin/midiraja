package com.midiraja.dsp;

public class PwmAcousticSimulator implements AudioProcessor {
    private double carrierPhase = -1.0;
    private final double carrierStep;
    private final int oversampleFactor;
    
    private double lp1L = 0, lp1R = 0, lp2L = 0, lp2R = 0;
    private double hpL = 0, hpR = 0, prevL = 0, prevR = 0;
    
    private final double lpAlpha = 0.20; // High-frequency paper cone attenuation
    private final double hpAlpha = 0.98; // Low-frequency small diameter attenuation

    public PwmAcousticSimulator(int sampleRate) {
        this(sampleRate, 32);
    }
    
    public PwmAcousticSimulator(int sampleRate, int oversampleFactor) {
        this.oversampleFactor = Math.max(1, oversampleFactor);
        // Apple II DAC522 style: 22.184kHz (92 cycles/sample @ 1.023MHz)
        this.carrierStep = (18600.0 / sampleRate) * 2.0;
    }

    @Override
    public void process(float[] left, float[] right, int frames) {
        for (int i = 0; i < frames; i++) {
            double l = left[i];
            double r = right[i];

            double sumL = 0.0;
            double sumR = 0.0;
            
            // Oversampling loop to prevent Nyquist fold-over
            for (int over = 0; over < oversampleFactor; over++) {
                carrierPhase += carrierStep / oversampleFactor;
                if (carrierPhase > 1.0) carrierPhase -= 2.0;
                sumL += (l > carrierPhase ? 1.0 : -1.0);
                sumR += (r > carrierPhase ? 1.0 : -1.0);
            }
            
            double bitL = sumL / oversampleFactor;
            double bitR = sumR / oversampleFactor;

            // Strict noise gate to kill the carrier whine on absolute silence
            if (l == 0.0 && r == 0.0) {
                bitL = 0.0; bitR = 0.0;
                lp1L *= 0.9; lp1R *= 0.9; lp2L *= 0.9; lp2R *= 0.9;
                hpL = 0.0; hpR = 0.0;
            }

            // Virtual Speaker Filters
            lp1L += lpAlpha * (bitL - lp1L);
            lp1R += lpAlpha * (bitR - lp1R);
            lp2L += lpAlpha * (lp1L - lp2L);
            lp2R += lpAlpha * (lp1R - lp2R);

            hpL = hpAlpha * (hpL + lp2L - prevL);
            hpR = hpAlpha * (hpR + lp2R - prevR);
            prevL = lp2L; prevR = lp2R;

            // Output safely scaled
            left[i] = (float) Math.max(-1.0, Math.min(1.0, hpL * 1.5));
            right[i] = (float) Math.max(-1.0, Math.min(1.0, hpR * 1.5));
        }
    }

    @Override
    public void reset() {
        carrierPhase = -1.0;
        lp1L = 0; lp1R = 0; lp2L = 0; lp2R = 0;
        hpL = 0; hpR = 0; prevL = 0; prevR = 0;
    }
}

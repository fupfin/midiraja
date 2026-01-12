package com.midiraja.dsp;

import java.util.Random;

public class OneBitAcousticSimulator implements AudioProcessor {
    private final String oneBitMode;
    private final int oversampleFactor;
    
    // PWM State
    private double carrierPhase = -1.0;
    private final double carrierStep;
    
    // DSD State
    private double dsdErrL = 0.0;
    private double dsdErrR = 0.0;
    private final Random rand = new Random();
    
    // Acoustic Filters
    private double lp1L = 0, lp1R = 0, lp2L = 0, lp2R = 0;
    private double hpL = 0, hpR = 0, prevL = 0, prevR = 0;
    
    private final double lpAlpha; // High-frequency paper cone attenuation
    private final double hpAlpha; // Low-frequency small diameter attenuation

    public OneBitAcousticSimulator(int sampleRate, String oneBitMode) {
        this.oneBitMode = oneBitMode.toLowerCase(java.util.Locale.ROOT);
        
        // Characteristic filters. Oversampling is universally 32x (~1.4MHz) 
        // to match the original 1980s hardware switching speeds.
        this.oversampleFactor = 32; 
        
        if ("pwm".equals(this.oneBitMode)) {
            // Empirical analysis of RealSound demos shows the carrier was actually ~15.2kHz,
            // not 18.6kHz. This gives it that characteristic gritty "crunch".
            // lpAlpha 0.40 strikes the perfect balance: it tames the piercing 15.2kHz whistle 
            // to match the original 10% magnitude while preserving the punchy mid-bass.
            this.lpAlpha = 0.40; 
            this.hpAlpha = 0.995; // Allow more bass through
            this.carrierStep = (15200.0 / sampleRate) * 2.0;
        } else if ("tdm".equals(this.oneBitMode)) {
            this.lpAlpha = 0.15; 
            this.hpAlpha = 0.99;
            this.carrierStep = (18600.0 / sampleRate) * 2.0;
        } else {
            // "dsd" or default
            this.lpAlpha = 0.85; 
            this.hpAlpha = 0.999; 
            this.carrierStep = (18600.0 / sampleRate) * 2.0;
        }
    }

    @Override
    public void process(float[] left, float[] right, int frames) {
        for (int i = 0; i < frames; i++) {
            double l = left[i];
            double r = right[i];

            double sumL = 0.0;
            double sumR = 0.0;
            
            for (int over = 0; over < oversampleFactor; over++) {
                double outBitL = 0.0;
                double outBitR = 0.0;
                
                if ("dsd".equals(oneBitMode)) {
                    // 1st-Order Delta-Sigma with TPDF Dither
                    double ditherL = (rand.nextDouble() - 0.5) + (rand.nextDouble() - 0.5);
                    double ditherR = (rand.nextDouble() - 0.5) + (rand.nextDouble() - 0.5);
                    
                    // Accumulate signal and dither
                    dsdErrL += l + (ditherL * 0.05);
                    dsdErrR += r + (ditherR * 0.05);
                    
                    outBitL = dsdErrL > 0.0 ? 1.0 : -1.0;
                    outBitR = dsdErrR > 0.0 ? 1.0 : -1.0;
                    
                    // Feedback the quantized output
                    dsdErrL -= outBitL;
                    dsdErrR -= outBitR;
                    
                } else if ("tdm".equals(oneBitMode)) {
                    // TDM in a generic PCM context is just super-fast randomized 1-bit switching
                    // that averages out to the analog value.
                    outBitL = rand.nextDouble() * 2.0 - 1.0 < l ? 1.0 : -1.0;
                    outBitR = rand.nextDouble() * 2.0 - 1.0 < r ? 1.0 : -1.0;
                } else {
                    // PWM (Default Retro)
                    carrierPhase += carrierStep / oversampleFactor;
                    if (carrierPhase > 1.0) carrierPhase -= 2.0;
                    outBitL = l > carrierPhase ? 1.0 : -1.0;
                    outBitR = r > carrierPhase ? 1.0 : -1.0;
                }
                
                sumL += outBitL;
                sumR += outBitR;
            }
            
            double bitL = sumL / oversampleFactor;
            double bitR = sumR / oversampleFactor;

            // Strict noise gate to kill carrier whine / DSD hiss on absolute silence
            if (l == 0.0 && r == 0.0) {
                bitL = 0.0; bitR = 0.0;
                lp1L *= 0.9; lp1R *= 0.9; lp2L *= 0.9; lp2R *= 0.9;
                hpL = 0.0; hpR = 0.0;
                dsdErrL = 0.0; dsdErrR = 0.0;
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
        dsdErrL = 0.0; dsdErrR = 0.0;
        lp1L = 0; lp1R = 0; lp2L = 0; lp2R = 0;
        hpL = 0; hpR = 0; prevL = 0; prevR = 0;
    }
}

package com.fupfin.midiraja.dsp;

import java.util.Random;

/**
 * Global DSP Filter version of the 1-bit PC Speaker acoustic simulator.
 */
public class OneBitAcousticSimulatorFilter implements AudioProcessor {
    private final boolean enabled;
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
    
    private final double lpAlpha; 
    private final double hpAlpha; 

    private final AudioProcessor next;

    public OneBitAcousticSimulatorFilter(boolean enabled, String oneBitMode, AudioProcessor next) {
        this.enabled = enabled;
        this.next = next;
        this.oneBitMode = oneBitMode != null ? oneBitMode.toLowerCase(java.util.Locale.ROOT) : "pwm";
        
        int sampleRate = 44100; // Assume global sample rate
        this.oversampleFactor = 32; 
        
        if ("pwm".equals(this.oneBitMode)) {
            this.lpAlpha = 0.30; 
            this.hpAlpha = 0.995; 
            this.carrierStep = (15200.0 / sampleRate) * 2.0;
        } else {
            this.lpAlpha = 0.85; 
            this.hpAlpha = 0.999; 
            this.carrierStep = (18600.0 / sampleRate) * 2.0;
        }
    }

    @Override
    public void process(float[] left, float[] right, int frames) {
        if (!enabled) return;
        
        for (int i = 0; i < frames; i++) {
            double l = left[i];
            double r = right[i];
            double sumL = 0.0;
            double sumR = 0.0;
            
            for (int over = 0; over < oversampleFactor; over++) {
                double outBitL = 0.0;
                double outBitR = 0.0;
                
                if ("dsd".equals(oneBitMode)) {
                    double ditherL = (rand.nextDouble() - 0.5) + (rand.nextDouble() - 0.5);
                    double ditherR = (rand.nextDouble() - 0.5) + (rand.nextDouble() - 0.5);
                    
                    dsdErrL += l + (ditherL * 0.05);
                    dsdErrR += r + (ditherR * 0.05);
                    
                    outBitL = dsdErrL > 0.0 ? 1.0 : -1.0;
                    outBitR = dsdErrR > 0.0 ? 1.0 : -1.0;
                    
                    dsdErrL -= outBitL;
                    dsdErrR -= outBitR;
                } else {
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

            lp1L += lpAlpha * (bitL - lp1L);
            lp1R += lpAlpha * (bitR - lp1R);
            lp2L += lpAlpha * (lp1L - lp2L);
            lp2R += lpAlpha * (lp1R - lp2R);

            hpL = hpAlpha * (hpL + lp2L - prevL);
            hpR = hpAlpha * (hpR + lp2R - prevR);
            prevL = lp2L; prevR = lp2R;

            left[i] = (float) hpL;
            right[i] = (float) hpR;
        }
        next.process(left, right, frames);
    }
    
    @Override
    public void processInterleaved(short[] interleavedPcm, int frames, int channels) {
        next.processInterleaved(interleavedPcm, frames, channels);
    }

    @Override
    public void reset() {
        next.reset();
        carrierPhase = -1.0;
        dsdErrL = 0.0; dsdErrR = 0.0;
        lp1L = 0; lp1R = 0; lp2L = 0; lp2R = 0;
        hpL = 0; hpR = 0; prevL = 0; prevR = 0;
    }
}

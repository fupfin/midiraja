package com.fupfin.midiraja.dsp;

/**
 * Simulates the Apple II 1-bit speaker toggle logic.
 * In hardware, this was a simple flip-flop toggled by accessing memory address $C030.
 * In a signal chain, this maps to a zero-crossing 1-bit quantization.
 */
public class Apple2DacFilter implements AudioProcessor {
private final boolean enabled;
    private final AudioProcessor next;

    // Software PWM carrier state
    private double carrierPhaseL = -1.0;
    private double carrierPhaseR = -1.0;
    
    // Apple II software PWM was typically very slow due to the 1MHz 6502 CPU overhead.
    // A ~11kHz carrier is historically accurate for advanced 1-bit software players on the Apple II.
    private final double carrierStep = (11025.0 / 44100.0) * 2.0;

    // Oversampling to reduce digital aliasing in the PWM generation
    private final int oversampleFactor = 8;

    public Apple2DacFilter(boolean enabled, AudioProcessor next) {
        this.enabled = enabled;
        this.next = next;
    }

    @Override
    public void process(float[] left, float[] right, int frames) {
        if (!enabled) {
            next.process(left, right, frames);
            return;
        }
        
        for (int i = 0; i < frames; i++) {
            double l = left[i], r = right[i];
            double sumL = 0, sumR = 0;
            
            for (int over = 0; over < oversampleFactor; over++) {
                carrierPhaseL += carrierStep / oversampleFactor;
                if (carrierPhaseL > 1.0) carrierPhaseL -= 2.0;
                
                carrierPhaseR += carrierStep / oversampleFactor;
                if (carrierPhaseR > 1.0) carrierPhaseR -= 2.0;
                
                sumL += l > carrierPhaseL ? 1.0 : -1.0;
                sumR += r > carrierPhaseR ? 1.0 : -1.0;
            }
            left[i] = (float) (sumL / oversampleFactor);
            right[i] = (float) (sumR / oversampleFactor);
        }
        
        next.process(left, right, frames);
    }

    @Override
    public void processInterleaved(short[] interleavedPcm, int frames, int channels) {
        if (!enabled) {
            next.processInterleaved(interleavedPcm, frames, channels);
            return;
        }
        
        for (int i = 0; i < frames; i++) {
            int lIdx = i * channels;
            float l = interleavedPcm[lIdx] / 32768.0f;
            float r = channels > 1 ? interleavedPcm[lIdx + 1] / 32768.0f : l;
            
            double sumL = 0, sumR = 0;
            for (int over = 0; over < oversampleFactor; over++) {
                carrierPhaseL += carrierStep / oversampleFactor;
                if (carrierPhaseL > 1.0) carrierPhaseL -= 2.0;
                
                carrierPhaseR += carrierStep / oversampleFactor;
                if (carrierPhaseR > 1.0) carrierPhaseR -= 2.0;
                
                sumL += l > carrierPhaseL ? 1.0 : -1.0;
                sumR += r > carrierPhaseR ? 1.0 : -1.0;
            }
            
            interleavedPcm[lIdx] = (short) (sumL / oversampleFactor * 32767.0f);
            if (channels > 1) {
                interleavedPcm[lIdx + 1] = (short) (sumR / oversampleFactor * 32767.0f);
            }
        }
        
        next.processInterleaved(interleavedPcm, frames, channels);
    }

    @Override
    public void reset() {
        carrierPhaseL = -1.0;
        carrierPhaseR = -1.0;
        next.reset();
    }
}

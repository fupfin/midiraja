package com.fupfin.midiraja.dsp;

import java.util.Random;

/**
 * Simulates the IBM PC 1-bit DAC (PC Speaker) conversion logic.
 * Primarily uses Pulse Width Modulation (PWM) driven by the PIT timer.
 */
public class IbmPcDacFilter implements AudioProcessor {
    private final boolean enabled;
    private final AudioProcessor next;
    private final String mode; 
    
    // IBM PC Speaker is a MONO device.
    private double carrierPhase = 0.0;
    private final double carrierStep;
    
    private double dsdErr = 0.0;
    private final Random rand = new Random();

    public IbmPcDacFilter(boolean enabled, String mode, AudioProcessor next) {
        this.enabled = enabled;
        this.next = next;
        this.mode = mode != null ? mode.toLowerCase(java.util.Locale.ROOT) : "pwm";
        // IBM PC PIT drives PWM at ~18.6kHz.
        this.carrierStep = 18600.0 / 44100.0; 
    }

    @Override
    public void process(float[] left, float[] right, int frames) {
        if (!enabled) {
            next.process(left, right, frames);
            return;
        }
        
        for (int i = 0; i < frames; i++) {
            // Forced Mono mixdown before DAC
            double monoIn = (left[i] + right[i]) * 0.5;
            double out;
            
            if ("dsd".equals(mode)) {
                dsdErr += monoIn + (rand.nextDouble() - 0.5) * 0.1;
                out = dsdErr > 0.0 ? 1.0 : -1.0;
                dsdErr -= out;
            } else {
                // Analytical PWM Integration (Area under the curve)
                double duty = Math.max(0.0, Math.min(1.0, (monoIn + 1.0) * 0.5));
                out = integratePwm(carrierPhase, carrierStep, duty);
                carrierPhase = (carrierPhase + carrierStep) % 1.0;
            }
            
            left[i] = (float) out;
            right[i] = (float) out;
        }
        
        // Push architecture: pass processed signal to the next filter
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
            
            // Forced Mono mixdown
            double monoIn = (l + r) * 0.5;
            double out;
            
            if ("dsd".equals(mode)) {
                dsdErr += monoIn + (rand.nextDouble() - 0.5) * 0.1;
                out = dsdErr > 0.0 ? 1.0 : -1.0;
                dsdErr -= out;
            } else {
                double duty = Math.max(0.0, Math.min(1.0, (monoIn + 1.0) * 0.5));
                out = integratePwm(carrierPhase, carrierStep, duty);
                carrierPhase = (carrierPhase + carrierStep) % 1.0;
            }
            
            short outPcm = (short) Math.max(-32768, Math.min(32767, out * 32767.0));
            interleavedPcm[lIdx] = outPcm;
            if (channels > 1) {
                interleavedPcm[lIdx + 1] = outPcm;
            }
        }
        
        next.processInterleaved(interleavedPcm, frames, channels);
    }

    private double integratePwm(double startPhase, double step, double duty) {
        double endPhase = startPhase + step;
        double highTime = 0.0;
        
        if (endPhase > 1.0) {
            if (startPhase < duty) highTime += (duty - startPhase);
            double remainder = endPhase - 1.0;
            if (remainder > duty) highTime += duty;
            else highTime += remainder;
        } else {
            if (endPhase <= duty) highTime = step;
            else if (startPhase >= duty) highTime = 0.0;
            else highTime = duty - startPhase;
        }
        
        return ((highTime / step) * 2.0) - 1.0;
    }

    @Override
    public void reset() {
        carrierPhase = 0.0;
        dsdErr = 0;
        next.reset();
    }
}

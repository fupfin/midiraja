package com.fupfin.midiraja.dsp;
import java.util.Random;

public class IbmPcDacFilter implements AudioProcessor {
    private final boolean enabled;
    private final AudioProcessor next;
    private final String mode; 
    
    private double carrierPhase = 0.0;
    private final double carrierStep;
    
    private double dsdErr = 0.0;
    private final Random rand = new Random();
    
    // Physical speaker cone inertia acts as a steep low-pass filter.
    // A 2-pole Biquad LPF provides the steep roll-off (-12dB/octave) needed to kill the 18.6kHz PWM carrier whine
    // without completely destroying the mid-range tone.
    // PC Speaker acts as a heavy mechanical LPF (8kHz cliff).
    // We cascade two 1-pole filters to get a steeper -12dB/octave roll-off
    // which effectively kills the 18.6kHz whine without muffling the sound too much.
    private float smoothL1 = 0.0f, smoothL2 = 0.0f;
    private final float smoothAlpha = 0.45f;


    public IbmPcDacFilter(boolean enabled, String mode, AudioProcessor next) {
        this.enabled = enabled;
        this.next = next;
        this.mode = mode != null ? mode.toLowerCase(java.util.Locale.ROOT) : "pwm";
        this.carrierStep = 18600.0 / 44100.0; 
        
        // PC speaker has a steep drop-off after 7-8kHz

    }

    @Override
    public void process(float[] left, float[] right, int frames) {
        if (!enabled) {
            next.process(left, right, frames);
            return;
        }
        
        for (int i = 0; i < frames; i++) {
            double monoIn = (left[i] + right[i]) * 0.5;
            double out;
            
            if (Math.abs(monoIn) < 1e-4) {
                out = 0.0;
            } else if ("dsd".equals(mode)) {
                dsdErr += monoIn + (rand.nextDouble() - 0.5) * 0.1;
                out = dsdErr > 0.0 ? 1.0 : -1.0;
                dsdErr -= out;
            } else {
                double duty = Math.max(0.0, Math.min(1.0, (monoIn + 1.0) * 0.5));
                out = integratePwm(carrierPhase, carrierStep, duty);
                carrierPhase = (carrierPhase + carrierStep) % 1.0;
            }
            
            smoothL1 += smoothAlpha * ((float) out - smoothL1);
            smoothL2 += smoothAlpha * (smoothL1 - smoothL2);
            if (Math.abs(smoothL1) < 1e-10f) smoothL1 = 0;
            if (Math.abs(smoothL2) < 1e-10f) smoothL2 = 0;
            float smoothOut = smoothL2; 
            
            left[i] = smoothOut;
            right[i] = smoothOut;
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
            
            double monoIn = (l + r) * 0.5;
            double out;
            
            if (Math.abs(monoIn) < 1e-4) {
                out = 0.0;
            } else if ("dsd".equals(mode)) {
                dsdErr += monoIn + (rand.nextDouble() - 0.5) * 0.1;
                out = dsdErr > 0.0 ? 1.0 : -1.0;
                dsdErr -= out;
            } else {
                double duty = Math.max(0.0, Math.min(1.0, (monoIn + 1.0) * 0.5));
                out = integratePwm(carrierPhase, carrierStep, duty);
                carrierPhase = (carrierPhase + carrierStep) % 1.0;
            }
            
            smoothL1 += smoothAlpha * ((float) out - smoothL1);
            smoothL2 += smoothAlpha * (smoothL1 - smoothL2);
            if (Math.abs(smoothL1) < 1e-10f) smoothL1 = 0;
            if (Math.abs(smoothL2) < 1e-10f) smoothL2 = 0;
            float smoothOut = smoothL2; 
            
            short outPcm = (short) Math.max(-32768, Math.min(32767, smoothOut * 32768.0));
            interleavedPcm[lIdx] = outPcm;
            if (channels > 1) interleavedPcm[lIdx + 1] = outPcm;
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
        smoothL1 = 0; smoothL2 = 0;
        next.reset();
    }
}

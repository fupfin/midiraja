package com.fupfin.midiraja.dsp;

/**
 * Simulates the Apple II 1-bit speaker toggle logic.
 * In hardware, this was a simple flip-flop toggled by accessing memory address $C030.
 * In a signal chain, this maps to a zero-crossing 1-bit quantization.
 */
public class Apple2DacFilter implements AudioProcessor {
    private final boolean enabled;
    private final AudioProcessor next;

    // Apple II is a MONO device.
    // Software PWM carrier state (11kHz typical for Apple II software)
    private double carrierPhase = 0.0;
    private final double carrierStep = 11025.0 / 44100.0;
    
    // Analog bypass capacitor smoothing
    private float smoothL = 0.0f;
    // A very gentle RC filter (Alpha 0.6 = ~10kHz roll-off) 
    // to simulate basic parasitic capacitance or a tiny noise-reduction capacitor.
    private final float smoothAlpha = 0.6f;

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
            // Forced Mono mixdown before DAC
            double monoIn = (left[i] + right[i]) * 0.5;
            double duty = Math.max(0.0, Math.min(1.0, (monoIn + 1.0) * 0.5));
            
            float out = (float) integratePwm(carrierPhase, carrierStep, duty);
            carrierPhase = (carrierPhase + carrierStep) % 1.0;
            
            // Apply gentle analog smoothing
            smoothL += smoothAlpha * (out - smoothL);
            if (Math.abs(smoothL) < 1e-10f) smoothL = 0;
            
            left[i] = smoothL;
            right[i] = smoothL;
        }
        
        // Push architecture
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
            double duty = Math.max(0.0, Math.min(1.0, (monoIn + 1.0) * 0.5));
            
            double out = integratePwm(carrierPhase, carrierStep, duty);
            carrierPhase = (carrierPhase + carrierStep) % 1.0;
            
            // Apply gentle analog smoothing
            smoothL += smoothAlpha * ((float) out - smoothL);
            if (Math.abs(smoothL) < 1e-10f) smoothL = 0;
            
            short outPcm = (short) Math.max(-32768, Math.min(32767, smoothL * 32768.0));
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
        smoothL = 0.0f;
        next.reset();
    }
}

package com.fupfin.midiraja.dsp;
public class Apple2DacFilter implements AudioProcessor {
    private final boolean enabled;
    private final AudioProcessor next;

    // Apple II is a MONO device.
    private double carrierPhase = 0.0;
    private final double carrierStep = 11025.0 / 44100.0;
    
    // Analog bypass capacitor smoothing
    private float smoothL = 0.0f;
    // Increased smoothing (Alpha 0.4 = ~4kHz roll-off) 
    // to simulate the heavy filtering required to make 11kHz PWM listenable.
    private final float smoothAlpha = 0.4f;

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
            double monoIn = (left[i] + right[i]) * 0.5;
            double duty = Math.max(0.0, Math.min(1.0, (monoIn + 1.0) * 0.5));
            
            float out;
            if (Math.abs(monoIn) < 1e-4) {
                out = 0.0f;
            } else {
                out = (float) integratePwm(carrierPhase, carrierStep, duty);
                carrierPhase = (carrierPhase + carrierStep) % 1.0;
            }
            
            smoothL += smoothAlpha * (out - smoothL);
            if (Math.abs(smoothL) < 1e-10f) smoothL = 0;
            
            left[i] = smoothL;
            right[i] = smoothL;
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
            double duty = Math.max(0.0, Math.min(1.0, (monoIn + 1.0) * 0.5));
            
            double out;
            if (Math.abs(monoIn) < 1e-4) {
                out = 0.0;
            } else {
                out = integratePwm(carrierPhase, carrierStep, duty);
                carrierPhase = (carrierPhase + carrierStep) % 1.0;
            }
            
            smoothL += smoothAlpha * ((float) out - smoothL);
            if (Math.abs(smoothL) < 1e-10f) smoothL = 0;
            
            short outPcm = (short) Math.max(-32768, Math.min(32767, smoothL * 32768.0));
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
        smoothL = 0.0f;
        next.reset();
    }
}

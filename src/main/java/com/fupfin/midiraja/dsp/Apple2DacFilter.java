package com.fupfin.midiraja.dsp;

public class Apple2DacFilter implements AudioProcessor {
    private final boolean enabled;
    private final AudioProcessor next;

    private double carrierPhase = 0.0;
    private final double carrierStep = 11025.0 / 44100.0;
    
    // Apple II 11kHz carrier is extremely piercing. 
    // We use a Biquad LPF to heavily suppress the 11kHz whine while keeping the crunch.
    private float smoothL1 = 0.0f, smoothL2 = 0.0f;
    private final float smoothAlpha = 0.35f; // Heavy smoothing for 11kHz


    public Apple2DacFilter(boolean enabled, AudioProcessor next) {
        this.enabled = enabled;
        this.next = next;
        // The Apple II speaker was very muffled. Suppress strongly above 4.5kHz.

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
            
            smoothL1 += smoothAlpha * (out - smoothL1);
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
            double duty = Math.max(0.0, Math.min(1.0, (monoIn + 1.0) * 0.5));
            
            float out;
            if (Math.abs(monoIn) < 1e-4) {
                out = 0.0f;
            } else {
                out = (float) integratePwm(carrierPhase, carrierStep, duty);
                carrierPhase = (carrierPhase + carrierStep) % 1.0;
            }
            
            smoothL1 += smoothAlpha * (out - smoothL1);
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
        smoothL1 = 0; smoothL2 = 0;
        next.reset();
    }
}

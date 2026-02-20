package com.fupfin.midiraja.dsp;

/**
 * Approximates the original compact Macintosh PWM-based mono audio path.
 * 
 * Model:
 * - 8-bit sample updates at ~22.2545 kHz
 * - 1-bit PWM pulse generation
 * - analytical 1-pole RC integration
 * - optional additional speaker smoothing
 */
public class Mac128kSimulatorFilter implements AudioProcessor {
    private final boolean enabled;
    private final AudioProcessor next;

    // Timing constants
    private final double outputSampleTimeUs = 1000000.0 / 44100.0;
    private final double macSampleTimeUs = 1000000.0 / 22254.5;
    
    // RC Filter time constant (Tau) for approx 7kHz physical roll-off
    private final double tauUs = 22.7; 

    // Simulation state
    private double currentTimeUs = 0.0;
    private double nextMacSampleTimeUs = 0.0;
    
    private double xL = 0.0;
    private double xR = 0.0;
    
    private double dutyL = 0.5;
    private double dutyR = 0.5;
    
    private boolean isHighL = false;
    private boolean isHighR = false;
    
    private double transitionTimeLUs = 0.0;
    private double transitionTimeRUs = 0.0;
    
    private float speakerL = 0.0f;
    private float speakerR = 0.0f;
    
    // Calculate alpha for ~12kHz cutoff to gently tame the carrier without muffling the sound too much
    // fc = -ln(1-alpha) * fs / 2PI => alpha = 1 - exp(-2PI * fc / fs)
    // alpha for 12kHz @ 44.1k is approx 0.81
    private final float speakerAlpha = 0.8f; 

    public Mac128kSimulatorFilter(boolean enabled, AudioProcessor next) {
        this.enabled = enabled;
        this.next = next;
    }

    @Override
    public void process(float[] left, float[] right, int frames) {
        if (!enabled) {
            next.process(left, right, frames);
            return;
        }

        // Pull architecture: fill buffers from upstream source first
        next.process(left, right, frames);

        for (int i = 0; i < frames; i++) {
            double targetOutputTimeUs = currentTimeUs + outputSampleTimeUs;
            
            while (currentTimeUs < targetOutputTimeUs) {
                if (currentTimeUs >= nextMacSampleTimeUs) {
                    // Reverted back to asymmetrical truncation for gritty texture.
                    // This mathematically introduces a harsh, sand-like non-linear distortion 
                    // around the zero-crossing, characteristic of naive early digital conversions.
                    int intL = (int) (left[i] * 127.0f);
                    int intR = (int) (right[i] * 127.0f);
                    
                    dutyL = (intL + 128) / 255.0; 
                    dutyR = (intR + 128) / 255.0;

                    
                    isHighL = true;
                    isHighR = true;
                    
                    transitionTimeLUs = nextMacSampleTimeUs + (dutyL * macSampleTimeUs);
                    transitionTimeRUs = nextMacSampleTimeUs + (dutyR * macSampleTimeUs);
                    
                    nextMacSampleTimeUs += macSampleTimeUs;
                }
                
                double nextEventUs = targetOutputTimeUs;
                if (nextEventUs > nextMacSampleTimeUs) nextEventUs = nextMacSampleTimeUs;
                if (isHighL && nextEventUs > transitionTimeLUs) nextEventUs = transitionTimeLUs;
                if (isHighR && nextEventUs > transitionTimeRUs) nextEventUs = transitionTimeRUs;
                
                double deltaT = nextEventUs - currentTimeUs;
                if (deltaT > 1e-9) { // Epsilon safety check
                    double expDecay = Math.exp(-deltaT / tauUs);
                    
                    double uL = isHighL ? 1.0 : -1.0;
                    double uR = isHighR ? 1.0 : -1.0;
                    
                    xL = uL + (xL - uL) * expDecay;
                    xR = uR + (xR - uR) * expDecay;
                    
                    currentTimeUs = nextEventUs;
                } else {
                    currentTimeUs = nextEventUs;
                }
                
                if (isHighL && currentTimeUs >= transitionTimeLUs) isHighL = false;
                if (isHighR && currentTimeUs >= transitionTimeRUs) isHighR = false;
            }
            
            // Apply gentle speaker inertia
            speakerL += speakerAlpha * ((float) xL - speakerL);
            speakerR += speakerAlpha * ((float) xR - speakerR);
            
            if (Math.abs(speakerL) < 1e-10f) speakerL = 0;
            if (Math.abs(speakerR) < 1e-10f) speakerR = 0;

            // Output the simulated analog voltage
            left[i] = speakerL;
            right[i] = speakerR;
        }
        
        // Prevent currentTimeUs from growing to infinity and losing float precision
        while (currentTimeUs > 1000000.0) {
            currentTimeUs -= 1000000.0;
            nextMacSampleTimeUs -= 1000000.0;
            transitionTimeLUs -= 1000000.0;
            transitionTimeRUs -= 1000000.0;
        }
    }
    
    @Override
    public void processInterleaved(short[] interleavedPcm, int frames, int channels) {
        if (!enabled) {
            next.processInterleaved(interleavedPcm, frames, channels);
            return;
        }
        
        // Pull architecture
        next.processInterleaved(interleavedPcm, frames, channels);
        
        for (int i = 0; i < frames; i++) {
            int leftIdx = i * channels;
            int rightIdx = channels > 1 ? leftIdx + 1 : leftIdx;
            
            float inL = interleavedPcm[leftIdx] / 32768.0f;
            float inR = interleavedPcm[rightIdx] / 32768.0f;
            
            double targetOutputTimeUs = currentTimeUs + outputSampleTimeUs;
            
            while (currentTimeUs < targetOutputTimeUs) {
                if (currentTimeUs >= nextMacSampleTimeUs) {
                    // Reverted back to asymmetrical truncation for gritty texture.
                    // This mathematically introduces a harsh, sand-like non-linear distortion 
                    // around the zero-crossing, characteristic of naive early digital conversions.
                    int intL = (int) (inL * 127.0f);
                    int intR = (int) (inR * 127.0f);
                    
                    dutyL = (intL + 128) / 255.0; 
                    dutyR = (intR + 128) / 255.0;

                    
                    isHighL = true;
                    isHighR = true;
                    
                    transitionTimeLUs = nextMacSampleTimeUs + (dutyL * macSampleTimeUs);
                    transitionTimeRUs = nextMacSampleTimeUs + (dutyR * macSampleTimeUs);
                    
                    nextMacSampleTimeUs += macSampleTimeUs;
                }
                
                double nextEventUs = targetOutputTimeUs;
                if (nextEventUs > nextMacSampleTimeUs) nextEventUs = nextMacSampleTimeUs;
                if (isHighL && nextEventUs > transitionTimeLUs) nextEventUs = transitionTimeLUs;
                if (isHighR && nextEventUs > transitionTimeRUs) nextEventUs = transitionTimeRUs;
                
                double deltaT = nextEventUs - currentTimeUs;
                if (deltaT > 1e-9) {
                    double expDecay = Math.exp(-deltaT / tauUs);
                    
                    double uL = isHighL ? 1.0 : -1.0;
                    double uR = isHighR ? 1.0 : -1.0;
                    
                    xL = uL + (xL - uL) * expDecay;
                    xR = uR + (xR - uR) * expDecay;
                    
                    currentTimeUs = nextEventUs;
                } else {
                    currentTimeUs = nextEventUs;
                }
                
                if (isHighL && currentTimeUs >= transitionTimeLUs) isHighL = false;
                if (isHighR && currentTimeUs >= transitionTimeRUs) isHighR = false;
            }
            
            speakerL += speakerAlpha * ((float) xL - speakerL);
            speakerR += speakerAlpha * ((float) xR - speakerR);
            
            if (Math.abs(speakerL) < 1e-10f) speakerL = 0;
            if (Math.abs(speakerR) < 1e-10f) speakerR = 0;

            interleavedPcm[leftIdx] = (short) Math.max(-32768, Math.min(32767, speakerL * 32768.0f));
            if (channels > 1) {
                interleavedPcm[rightIdx] = (short) Math.max(-32768, Math.min(32767, speakerR * 32768.0f));
            }
        }
        
        while (currentTimeUs > 1000000.0) {
            currentTimeUs -= 1000000.0;
            nextMacSampleTimeUs -= 1000000.0;
            transitionTimeLUs -= 1000000.0;
            transitionTimeRUs -= 1000000.0;
        }
    }

    @Override
    public void reset() {
        next.reset();
        currentTimeUs = 0.0;
        nextMacSampleTimeUs = 0.0;
        xL = 0.0;
        xR = 0.0;
        speakerL = 0.0f;
        speakerR = 0.0f;
        dutyL = 0.5;
        dutyR = 0.5;
        isHighL = false;
        isHighR = false;
        transitionTimeLUs = 0.0;
        transitionTimeRUs = 0.0;
    }
}

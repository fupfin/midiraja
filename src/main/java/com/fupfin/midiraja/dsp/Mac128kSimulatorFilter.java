package com.fupfin.midiraja.dsp;

/**
 * Simulates the unique audio hardware of the original Macintosh 128k (1984).
 * The Mac used a custom Sony sound chip (or SWIM later) but initially relied on 
 * the 68000 CPU stuffing 8-bit values into a PWM generator built from two 74LS161
 * 4-bit counters. The sample rate was strictly tied to the horizontal video 
 * flyback frequency: exactly 22.25 kHz.
 * 
 * This filter performs:
 * 1. Event-Driven Analytical Integration of the 1-bit PWM pulse train.
 * 2. Simulates the physical RC filter charging and discharging at sub-microsecond precision.
 * 3. Eliminates ZOH aliasing (the "siren" tone) mathematically without oversampling.
 */
public class Mac128kSimulatorFilter implements AudioProcessor {
    private final boolean enabled;
    private final AudioProcessor next;

    // Timing constants
    private final double outputSampleTimeUs = 1000000.0 / 44100.0;
    private final double macSampleTimeUs = 1000000.0 / 22254.5;
    
    // RC Filter time constant (Tau)
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
    
    // Physical speaker cone inertia (Additional gentle LPF to kill the 21.8kHz whine)
    // alpha = 0.5 acts as a 7kHz LPF
    private float speakerL = 0.0f;
    private float speakerR = 0.0f;
    private final float speakerAlpha = 0.15f; // Lower = more muffled, kills the whine better

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

        for (int i = 0; i < frames; i++) {
            double targetOutputTimeUs = currentTimeUs + outputSampleTimeUs;
            
            while (currentTimeUs < targetOutputTimeUs) {
                if (currentTimeUs >= nextMacSampleTimeUs) {
                    // HERE IS THE MAGIC TEXTURE:
                    // Do NOT use Math.round(). We MUST use (int) truncation to preserve
                    // the gritty zero-crossing asymmetry that gives the raw PWM texture!
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
            
            // Apply speaker inertia (gentle 1-pole LPF) to suppress the 22kHz carrier whine
            speakerL += speakerAlpha * ((float) xL - speakerL);
            speakerR += speakerAlpha * ((float) xR - speakerR);
            
            // Prevent denormalization
            if (Math.abs(speakerL) < 1e-10f) speakerL = 0;
            if (Math.abs(speakerR) < 1e-10f) speakerR = 0;

            left[i] = speakerL;
            right[i] = speakerR;
        }
        
        while (currentTimeUs > 1000000.0) {
            currentTimeUs -= 1000000.0;
            nextMacSampleTimeUs -= 1000000.0;
            transitionTimeLUs -= 1000000.0;
            transitionTimeRUs -= 1000000.0;
        }
        next.process(left, right, frames);
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

            interleavedPcm[leftIdx] = (short) Math.max(-32768, Math.min(32767, speakerL * 32768.0));
            if (channels > 1) {
                interleavedPcm[rightIdx] = (short) Math.max(-32768, Math.min(32767, speakerR * 32768.0));
            }
        }
        
        while (currentTimeUs > 1000000.0) {
            currentTimeUs -= 1000000.0;
            nextMacSampleTimeUs -= 1000000.0;
            transitionTimeLUs -= 1000000.0;
            transitionTimeRUs -= 1000000.0;
        }
        next.processInterleaved(interleavedPcm, frames, channels);
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

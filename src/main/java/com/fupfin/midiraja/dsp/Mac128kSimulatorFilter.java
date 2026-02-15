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
    private final double outputSampleTimeUs = 1000000.0 / 44100.0; // ~22.6757 us per 44.1k sample
    private final double macSampleTimeUs = 1000000.0 / 22254.5;    // ~44.9347 us per Mac sample
    
    // RC Filter time constant (Tau)
    // Cutoff frequency Fc = 1 / (2 * PI * Tau)
    // If we want Fc = 7000 Hz, Tau = 1 / (2 * PI * 7000) = 22.7 us
    private final double tauUs = 22.7; 

    // Simulation state
    private double currentTimeUs = 0.0;
    private double nextMacSampleTimeUs = 0.0;
    
    // Analog filter state (voltage across capacitor)
    private double xL = 0.0;
    private double xR = 0.0;
    
    // Current Duty Cycle state (0.0 to 1.0)
    private double dutyL = 0.5;
    private double dutyR = 0.5;
    
    // Are we currently in the "HIGH" (1) or "LOW" (-1) part of the PWM pulse?
    private boolean isHighL = false;
    private boolean isHighR = false;
    
    // When does the PWM transition happen for the CURRENT Mac sample?
    private double transitionTimeLUs = 0.0;
    private double transitionTimeRUs = 0.0;

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
            
            // Advance physical simulation until we reach the target output time
            while (currentTimeUs < targetOutputTimeUs) {
                
                // 1. Is it time for a new Mac sample?
                if (currentTimeUs >= nextMacSampleTimeUs) {
                    // Fetch new 8-bit sample from input buffer
                    // Map -1.0..1.0 to 0.0..1.0 duty cycle (int 0..255)
                    int intL = (int) (left[i] * 127.0f);
                    int intR = (int) (right[i] * 127.0f);
                    
                    dutyL = (intL + 128) / 255.0; 
                    dutyR = (intR + 128) / 255.0;
                    
                    // PWM cycle starts HIGH
                    isHighL = true;
                    isHighR = true;
                    
                    // Calculate when the pulse drops LOW within this Mac cycle
                    transitionTimeLUs = nextMacSampleTimeUs + (dutyL * macSampleTimeUs);
                    transitionTimeRUs = nextMacSampleTimeUs + (dutyR * macSampleTimeUs);
                    
                    nextMacSampleTimeUs += macSampleTimeUs;
                }
                
                // 2. Find the next physical event that will happen FIRST
                // It could be: Target Output time, L transition, R transition, or Next Mac Sample
                double nextEventUs = targetOutputTimeUs;
                if (nextEventUs > nextMacSampleTimeUs) {
                    nextEventUs = nextMacSampleTimeUs;
                }
                
                if (isHighL && nextEventUs > transitionTimeLUs) {
                    nextEventUs = transitionTimeLUs;
                }
                if (isHighR && nextEventUs > transitionTimeRUs) {
                    nextEventUs = transitionTimeRUs;
                }
                
                // 3. Evolve the analog RC circuit analytically to the next event
                double deltaT = nextEventUs - currentTimeUs;
                if (deltaT > 0) {
                    double expDecay = Math.exp(-deltaT / tauUs);
                    
                    // Input voltage: +1.0 for High, -1.0 for Low
                    double uL = isHighL ? 1.0 : -1.0;
                    double uR = isHighR ? 1.0 : -1.0;
                    
                    // Analytical solution: x(t2) = u + (x(t1) - u) * exp(-dt/Tau)
                    xL = uL + (xL - uL) * expDecay;
                    xR = uR + (xR - uR) * expDecay;
                    
                    currentTimeUs = nextEventUs;
                }
                
                // 4. If we hit a transition point, apply the state change
                if (isHighL && currentTimeUs >= transitionTimeLUs) {
                    isHighL = false;
                }
                if (isHighR && currentTimeUs >= transitionTimeRUs) {
                    isHighR = false;
                }
            }
            
            // Output the continuous analog voltage sampled at this 44.1kHz point
            left[i] = (float) xL;
            right[i] = (float) xR;
        }
        
        // Prevent currentTimeUs from growing to infinity and losing float precision
        if (currentTimeUs > 1000000.0) {
            currentTimeUs -= 1000000.0;
            nextMacSampleTimeUs -= 1000000.0;
            transitionTimeLUs -= 1000000.0;
            transitionTimeRUs -= 1000000.0;
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
        currentTimeUs = 0.0;
        nextMacSampleTimeUs = 0.0;
        xL = 0.0;
        xR = 0.0;
        isHighL = false;
        isHighR = false;
    }
}

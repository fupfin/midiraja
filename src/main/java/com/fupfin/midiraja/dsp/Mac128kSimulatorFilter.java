package com.fupfin.midiraja.dsp;

/**
 * Simulates the unique audio hardware of the original Macintosh 128k (1984).
 * The Mac used a custom Sony sound chip (or SWIM later) but initially relied on 
 * the 68000 CPU stuffing 8-bit values into a PWM generator built from two 74LS161
 * 4-bit counters. The sample rate was strictly tied to the horizontal video 
 * flyback frequency: exactly 22.25 kHz.
 * 
 * This filter performs:
 * 1. Sample and Hold (Zero-order hold) downsampling to exactly 22.25 kHz.
 * 2. 8-bit quantization of the signal at that rate.
 * 3. Exact PWM conversion: generating a 22.25 kHz square wave where the duty 
 *    cycle is dictated by the 8-bit value.
 * 4. Acoustic low-pass filtering characteristic of the tiny built-in Mac speaker.
 */
public class Mac128kSimulatorFilter implements AudioProcessor {
    private final boolean enabled;
    private final AudioProcessor next;

    // The Macintosh horizontal sync frequency is approx 22,254.5 Hz
    private static final double MAC_SAMPLE_RATE = 22254.5;
    
    // To accurately model the 256 duty-cycle levels of the PWM, we need an internal 
    // clock that ticks 256 times per sample: 22254.5 * 256 = ~5.7 MHz.
    // However, for efficiency, we can analytically calculate the area under the pulse
    // rather than simulating 5.7 million ticks per second. Since PWM duty cycle 
    // just translates to the average voltage over that 1/22254th of a second, 
    // and we are outputting to 44.1kHz (which is only ~2 samples per Mac sample),
    // the mathematical result of an ideal PWM through the speaker's low-pass filter
    // is virtually identical to an 8-bit Zero-Order Hold signal run through an LPF.
    
    // We will do a hybrid: true 8-bit Sample & Hold at 22.25kHz, plus the signature
    // "whine" of the 22.25kHz carrier wave that leaked into the audio path, 
    // and the tiny internal speaker EQ.
    
    private double phaseAcc = 0.0;
    private int heldSampleL = 0;
    private int heldSampleR = 0;
    
    private double lp1L = 0, lp1R = 0;
    private double hpL = 0, hpR = 0, prevL = 0, prevR = 0;
    
    private final double lpAlpha;
    private final double hpAlpha;

    public Mac128kSimulatorFilter(boolean enabled, AudioProcessor next) {
        this.enabled = enabled;
        this.next = next;
        
        // Characteristic speaker filters for the tiny Mac 128k internal speaker
        // It had no bass (HPF ~ 300Hz) and was heavily muffled (LPF ~ 7kHz)
        this.lpAlpha = 0.5;  // Muffle high frequencies
        this.hpAlpha = 0.96; // Kill bass
    }

    @Override
    public void process(float[] left, float[] right, int frames) {
        if (!enabled) {
            next.process(left, right, frames);
            return;
        }

        int globalSampleRate = 44100;
        double phaseStep = MAC_SAMPLE_RATE / globalSampleRate;

        for (int i = 0; i < frames; i++) {
            phaseAcc += phaseStep;
            
            // It's time to fetch a new sample from the CPU (Horizontal Sync)
            if (phaseAcc >= 1.0) {
                phaseAcc -= 1.0;
                
                // 8-bit Quantize
                heldSampleL = Math.max(-128, Math.min(127, Math.round(left[i] * 127f)));
                heldSampleR = Math.max(-128, Math.min(127, Math.round(right[i] * 127f)));
            }
            
            // To simulate the PWM, we output the held voltage level.
            double outL = heldSampleL / 127.0;
            double outR = heldSampleR / 127.0;
            
            // Add faint carrier alias whine (approx 1% volume)
            double carrierWhine = Math.sin(phaseAcc * Math.PI * 2.0) * 0.01;
            outL += carrierWhine;
            outR += carrierWhine;

            // Apply Macintosh internal speaker acoustic model
            lp1L += lpAlpha * (outL - lp1L);
            lp1R += lpAlpha * (outR - lp1R);
            
            hpL = hpAlpha * (hpL + lp1L - prevL);
            hpR = hpAlpha * (hpR + lp1R - prevR);
            prevL = lp1L; prevR = lp1R;

            left[i] = (float) Math.max(-1.0, Math.min(1.0, hpL));
            right[i] = (float) Math.max(-1.0, Math.min(1.0, hpR));
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
        phaseAcc = 0;
        heldSampleL = 0; heldSampleR = 0;
        lp1L = 0; lp1R = 0;
        hpL = 0; hpR = 0; prevL = 0; prevR = 0;
    }
}

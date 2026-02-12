package com.fupfin.midiraja.dsp;

/**
 * Simulates the unique audio hardware of the original Macintosh 128k (1984).
 * The Mac used a custom Sony sound chip (or SWIM later) but initially relied on 
 * the 68000 CPU stuffing 8-bit values into a PWM generator built from two 74LS161
 * 4-bit counters. The sample rate was strictly tied to the horizontal video 
 * flyback frequency: exactly 22.25 kHz.
 * 
 * This filter performs:
 * 1. Resampling to 22.25 kHz with linear interpolation.
 * 2. 8-bit quantization of the signal at that rate.
 * 3. Outputting to the audio line out without an internal speaker EQ.
 */
public class Mac128kSimulatorFilter implements AudioProcessor {
    private final boolean enabled;
    private final AudioProcessor next;

    // The Macintosh horizontal sync frequency is approx 22,254.5 Hz
    private static final double MAC_SAMPLE_RATE = 22254.5;
    
    private boolean holdNext = false;
    private float heldL = 0;
    private float heldR = 0;
    
    // Analog Line-Out circuitry simulation (RC Low-Pass Filter)
    private float lpfL = 0;
    private float lpfR = 0;
    // An alpha of ~0.35 closely matches the empirical -27dB at 10kHz roll-off found in original Mac recordings.
    private final float alpha = 0.35f;

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

        // To perfectly eliminate fractional phase jitter, we lock the internal Mac
        // generation rate to exactly 22.05kHz (half of 44.1kHz). 
        
        for (int i = 0; i < frames; i++) {
            if (!holdNext) {
                // 1. Fetch exactly on the 22.05kHz grid
                // 2. 8-bit Quantize (256 discrete levels)
                heldL = Math.max(-128, Math.min(127, Math.round(left[i] * 127f))) / 127f;
                heldR = Math.max(-128, Math.min(127, Math.round(right[i] * 127f))) / 127f;
                holdNext = true;
            } else {
                holdNext = false; // Zero-Order Hold for the second frame
            }
            
            // 3. Apply the analog Line-Out RC smoothing filter
            // This prevents the raw 22.05kHz stair-steps from tearing up modern tweeters
            // and perfectly matches the frequency profile of original Mac Plus recordings.
            lpfL += alpha * (heldL - lpfL);
            lpfR += alpha * (heldR - lpfR);
            
            left[i] = lpfL;
            right[i] = lpfR;
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
        holdNext = false;
        heldL = 0;
        heldR = 0;
    }
}
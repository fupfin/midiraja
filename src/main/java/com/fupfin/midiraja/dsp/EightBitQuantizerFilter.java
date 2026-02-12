package com.fupfin.midiraja.dsp;

/**
 * Simulates the sound of early 8-bit computer DACs (e.g., Mac 128K, Amiga, Covox).
 * This processor intentionally degrades the high-resolution internal float audio down
 * to an 8-bit resolution (256 discrete voltage levels) before converting it back to float.
 * It also applies a slight non-linear distortion characteristic of early R-2R ladders.
 */
public class EightBitQuantizerFilter implements AudioProcessor {
    private final boolean enabled;

    private final AudioProcessor next;
    
    public EightBitQuantizerFilter(boolean enabled, AudioProcessor next) {
        this.enabled = enabled;
        this.next = next;
    }

    @Override
    public void process(float[] left, float[] right, int frames) {
        if (!enabled) return;

        // 8-bit has 256 discrete levels, ranging from -128 to 127
        for (int i = 0; i < frames; i++) {
            // Quantize Left Channel
            float l = left[i];
            int quantizedL = Math.round(l * 127f);
            quantizedL = Math.max(-128, Math.min(127, quantizedL));
            
            // Covox-style non-linear R-2R jitter (slightly imperfect resistor ladders)
            // A tiny amount of harmonic distortion added back in based on the level.
            float outL = quantizedL / 127f;
            outL += (float)(Math.sin(outL * Math.PI) * 0.02); 
            left[i] = Math.max(-1.0f, Math.min(1.0f, outL));

            // Quantize Right Channel
            float r = right[i];
            int quantizedR = Math.round(r * 127f);
            quantizedR = Math.max(-128, Math.min(127, quantizedR));
            
            float outR = quantizedR / 127f;
            outR += (float)(Math.sin(outR * Math.PI) * 0.02);
            right[i] = Math.max(-1.0f, Math.min(1.0f, outR));
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
    }
}

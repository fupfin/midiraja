package com.midiraja.dsp;

public interface AudioProcessor {
    void process(float[] left, float[] right, int frames);
    void reset();
}

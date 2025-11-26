package com.midiraja.dsp;

import java.util.ArrayList;
import java.util.List;

public class AutoFlushGate implements AudioProcessor {
    private final List<AudioProcessor> upstreamProcessors;
    private final float epsilon = 1e-5f;

    public AutoFlushGate(List<AudioProcessor> upstreamProcessors) {
        this.upstreamProcessors = new ArrayList<>(upstreamProcessors);
    }

    @Override
    public void process(float[] left, float[] right, int frames) {
        boolean allZero = true;
        for (int i = 0; i < frames; i++) {
            if (Math.abs(left[i]) < epsilon && Math.abs(right[i]) < epsilon) {
                left[i] = 0.0f;
                right[i] = 0.0f;
            } else {
                allZero = false;
            }
        }
        
        // If the buffer is completely silent, flush the history of previous filters
        // to prevent floating-point asymptotes from keeping them alive indefinitely.
        if (allZero) {
            for (AudioProcessor p : upstreamProcessors) {
                p.reset();
            }
        }
    }

    @Override
    public void reset() {}
}

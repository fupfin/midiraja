package com.midiraja.dsp;

public class ReconstructionFilter implements AudioProcessor {
    private final double alpha;
    private double dac1L = 0, dac1R = 0, dac2L = 0, dac2R = 0;

    public ReconstructionFilter(double alpha) {
        this.alpha = alpha;
    }

    @Override
    public void process(float[] left, float[] right, int frames) {
        for (int i = 0; i < frames; i++) {
            dac1L += alpha * (left[i] - dac1L);
            dac1R += alpha * (right[i] - dac1R);
            dac2L += alpha * (dac1L - dac2L);
            dac2R += alpha * (dac1R - dac2R);

            left[i] = (float) dac2L;
            right[i] = (float) dac2R;
        }
    }

    @Override
    public void reset() {
        dac1L = 0; dac1R = 0; dac2L = 0; dac2R = 0;
    }
}

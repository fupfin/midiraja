package com.midiraja.dsp;

public class NoiseShapedQuantizer implements AudioProcessor {
    private final double qSteps;
    private double qErrL = 0.0;
    private double qErrR = 0.0;

    public NoiseShapedQuantizer(int bitDepth) {
        this.qSteps = Math.pow(2, bitDepth - 1) - 1;
    }

    @Override
    public void process(float[] left, float[] right, int frames) {
        for (int i = 0; i < frames; i++) {
            double l = Math.max(-1.0, Math.min(1.0, left[i]));
            double r = Math.max(-1.0, Math.min(1.0, right[i]));

            double targetL = l + (qErrL * 0.95);
            double targetR = r + (qErrR * 0.95);

            double qL = Math.round(targetL * qSteps) / qSteps;
            double qR = Math.round(targetR * qSteps) / qSteps;

            qErrL = targetL - qL;
            qErrR = targetR - qR;

            left[i] = (float) qL;
            right[i] = (float) qR;
        }
    }

    @Override
    public void reset() {
        qErrL = 0.0;
        qErrR = 0.0;
    }
}

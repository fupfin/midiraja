package com.midiraja.midi.gus;

import java.io.FileOutputStream;

public class PwmDspTest {
    public static void main(String[] args) throws Exception {
        int sampleRate = 44100;
        int durationSeconds = 2;
        int frames = sampleRate * durationSeconds;
        float[] left = new float[frames];
        for (int i = 0; i < frames; i++) {
            left[i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 440.0 * i / sampleRate));
        }

        short[] pcmBuffer = new short[frames * 2];
        int bitDepth = 6;
        boolean pwmMode = true;
        
        double carrierPhase = -1.0;
        final double carrierStep = (18600.0 / 44100.0) * 2.0; 
        
        double lp1L = 0, lp2L = 0, hpL = 0, prevL = 0;
        final double lpAlpha = 0.20; 
        final double hpAlpha = 0.98; 
        final double qSteps = Math.pow(2, bitDepth - 1) - 1;

        for (int i = 0; i < frames; i++) {
            double l = Math.max(-1.0, Math.min(1.0, left[i]));
            if (bitDepth < 16) l = Math.round(l * qSteps) / qSteps;

            if (pwmMode) {
                // 32x Oversampling with Boxcar Filter (Moving Average)
                double sumL = 0.0;
                for (int over = 0; over < 32; over++) {
                    carrierPhase += carrierStep / 32.0;
                    if (carrierPhase > 1.0) carrierPhase -= 2.0;
                    sumL += (l > carrierPhase ? 1.0 : -1.0);
                }
                double bitL = sumL / 32.0; // This IS an anti-aliasing FIR filter!

                lp1L += lpAlpha * (bitL - lp1L);
                lp2L += lpAlpha * (lp1L - lp2L);
                hpL = hpAlpha * (hpL + lp2L - prevL);
                prevL = lp2L;
                l = Math.max(-1.0, Math.min(1.0, hpL * 1.5));
            }
            pcmBuffer[i * 2] = (short) (l * 32767);
            pcmBuffer[i * 2 + 1] = (short) (l * 32767);
        }

        try (FileOutputStream fos = new FileOutputStream("pwm_output_32x.raw")) {
            for (short s : pcmBuffer) {
                fos.write(s & 0xff);
                fos.write((short)((s >> 8) & 0xff));
            }
        }
        System.out.println("Saved to pwm_output_32x.raw");
    }
}

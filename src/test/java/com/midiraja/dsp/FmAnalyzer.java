package com.midiraja.dsp;

import java.io.FileOutputStream;
import java.io.IOException;

public class FmAnalyzer {
    public static void main(String[] args) throws IOException {
        PwmAcousticSimulator dsp = new PwmAcousticSimulator(44100, 32);
        
        int durationFrames = 44100; // 1 second
        float[] left = new float[durationFrames];
        float[] right = new float[durationFrames];
        
        double phaseC = 0.0;
        double phaseE = 0.0;
        double modPhaseC = 0.0;
        double modPhaseE = 0.0;
        
        double freqC = 261.63; // C4
        double freqE = 329.63; // E4
        
        int arpeggioIndex = 0;
        int framesSinceSwitch = 0;
        int framesPerSwitch = 44100 / 10; // 10 Hz
        
        for (int i = 0; i < durationFrames; i++) {
            // Advance C4
            modPhaseC += (freqC * 1.0) / 44100.0;
            if (modPhaseC >= 1.0) modPhaseC -= 1.0;
            double modC = Math.sin(modPhaseC * 2.0 * Math.PI);
            double decay = Math.max(0.0, 1.0 - (i / 22050.0));
            double modIdx = 0.1 + (1.1 * decay);
            double instFreqC = freqC + (modC * modIdx * freqC);
            phaseC += instFreqC / 44100.0;
            if (phaseC >= 1.0) phaseC -= 1.0;
            double outC = Math.sin(phaseC * 2.0 * Math.PI);
            
            // Advance E4
            modPhaseE += (freqE * 1.0) / 44100.0;
            if (modPhaseE >= 1.0) modPhaseE -= 1.0;
            double modE = Math.sin(modPhaseE * 2.0 * Math.PI);
            double instFreqE = freqE + (modE * modIdx * freqE);
            phaseE += instFreqE / 44100.0;
            if (phaseE >= 1.0) phaseE -= 1.0;
            double outE = Math.sin(phaseE * 2.0 * Math.PI);
            
            // Multiplex
            double mixed = (arpeggioIndex == 0) ? outC : outE;
            left[i] = (float) mixed;
            right[i] = (float) mixed;
            
            framesSinceSwitch++;
            if (framesSinceSwitch >= framesPerSwitch) {
                framesSinceSwitch = 0;
                arpeggioIndex = (arpeggioIndex + 1) % 2;
            }
        }
        
        // Process through 32x PWM
        dsp.process(left, right, durationFrames);
        
        try (FileOutputStream fos = new FileOutputStream("fm_test.raw")) {
            for (int i = 0; i < durationFrames; i++) {
                short sample = (short)(left[i] * 32767);
                fos.write(sample & 0xFF);
                fos.write((sample >> 8) & 0xFF);
            }
        }
        System.out.println("Generated fm_test.raw");
    }
}

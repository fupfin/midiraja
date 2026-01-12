package com.midiraja.dsp;

import java.io.FileOutputStream;
import java.util.List;

/**
 * A universal Test Harness for digital signal processing (DSP) components.
 * Feeds a pure mathematically generated waveform through a chain of AudioProcessors
 * and dumps the output to a raw PCM file for spectral analysis via Python/NumPy.
 */
public class DspAnalyzer {

    /**
     * Runs a 440Hz Sine wave through the provided DSP pipeline and saves it to a file.
     * 
     * @param processors The pipeline of AudioProcessor components to test.
     * @param outputFile The destination raw PCM file (e.g. "dsp_output.raw").
     */
    public static void runSineWaveTest(List<AudioProcessor> processors, String outputFile) throws Exception {
        int sampleRate = 44100;
        int durationSeconds = 2;
        int frames = sampleRate * durationSeconds;
        
        float[] left = new float[frames];
        float[] right = new float[frames];
        
        // Generate pure 440Hz Sine Wave (A4)
        for (int i = 0; i < frames; i++) {
            float val = (float) (0.5 * Math.sin(2.0 * Math.PI * 440.0 * i / sampleRate));
            left[i] = val;
            right[i] = val;
        }

        // Pass through the pipeline
        for (AudioProcessor processor : processors) {
            processor.process(left, right, frames);
        }

        // Convert back to 16-bit LE PCM
        short[] pcmBuffer = new short[frames * 2];
        for (int i = 0; i < frames; i++) {
            double l = Math.max(-1.0, Math.min(1.0, left[i]));
            double r = Math.max(-1.0, Math.min(1.0, right[i]));
            pcmBuffer[i * 2] = (short) (l * 32767);
            pcmBuffer[i * 2 + 1] = (short) (r * 32767);
        }

        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
            for (short s : pcmBuffer) {
                fos.write(s & 0xff);
                fos.write((s >> 8) & 0xff);
            }
        }
        System.out.println("[DSP Analyzer] Successfully exported test data to: " + outputFile);
    }
    
    // Example Entry Point
    public static void main(String[] args) throws Exception {
        System.out.println("Testing the RealSound (Pure PWM) Pipeline...");
        List<AudioProcessor> realSoundPipeline = List.of(
            // Pre-quantization is removed because 1.4MHz / 15.2kHz is inherently a 6.5-bit quantizer.
            // Adding artificial noise shaping ruins the PWM phase stability.
            new OneBitAcousticSimulator(44100, "pwm")
        );
        runSineWaveTest(realSoundPipeline, "realsound_test.raw");

        System.out.println("Testing the Hi-Fi DSD Pipeline...");
        List<AudioProcessor> dsdPipeline = List.of(
            new OneBitAcousticSimulator(44100, "dsd")
        );
        runSineWaveTest(dsdPipeline, "dsd_test.raw");

        System.out.println("Testing the TDM Pipeline...");
        List<AudioProcessor> tdmPipeline = List.of(
            new OneBitAcousticSimulator(44100, "tdm")
        );
        runSineWaveTest(tdmPipeline, "tdm_test.raw");
    }
}

package com.midiraja.dsp;

import com.midiraja.midi.AdlMidiNativeBridge;
import com.midiraja.midi.FFMAdlMidiNativeBridge;
import com.midiraja.midi.OpnMidiNativeBridge;
import com.midiraja.midi.FFMOpnMidiNativeBridge;

public class VolumeAnalyzer {
    public static void main(String[] args) throws Exception {
        System.out.println("Starting Volume Analysis...");
        
        // 1. Analyze OPL
        FFMAdlMidiNativeBridge adl = new FFMAdlMidiNativeBridge();
        adl.init(44100);
        adl.switchEmulator(0); // Nuked
        adl.setBank(0);
        
        // Play 10 notes to get a good read
        for (int i=0; i<10; i++) adl.noteOn(i, 60, 127);
        short[] adlBuf = new short[44100 * 2];
        adl.generate(adlBuf, 44100);
        double adlMax = 0;
        double adlRms = 0;
        for (int i=0; i<adlBuf.length; i++) {
            double val = adlBuf[i] / 32768.0;
            if (Math.abs(val) > adlMax) adlMax = Math.abs(val);
            adlRms += val * val;
        }
        adlRms = Math.sqrt(adlRms / adlBuf.length);
        System.out.printf("OPL (AdLib) - Max: %.4f, RMS: %.4f%n", adlMax, adlRms);
        adl.close();

        // 2. Analyze OPN (Wait, OPN needs a patch change to play anything)
        FFMOpnMidiNativeBridge opn = new FFMOpnMidiNativeBridge();
        opn.init(44100);
        opn.switchEmulator(0); // MAME
        
        // Send a standard piano patch (0) and max volume to 10 channels
        for (int i=0; i<10; i++) {
            opn.patchChange(i, 0);
            opn.controlChange(i, 7, 127);
            opn.noteOn(i, 60, 127);
        }
        short[] opnBuf = new short[44100 * 2];
        opn.generate(opnBuf, 44100);
        double opnMax = 0;
        double opnRms = 0;
        for (int i=0; i<opnBuf.length; i++) {
            double val = opnBuf[i] / 32768.0;
            if (Math.abs(val) > opnMax) opnMax = Math.abs(val);
            opnRms += val * val;
        }
        opnRms = Math.sqrt(opnRms / opnBuf.length);
        System.out.printf("OPN (Genesis) - Max: %.4f, RMS: %.4f%n", opnMax, opnRms);
        opn.close();
        
        System.out.println("Analysis Complete.");
    }
}

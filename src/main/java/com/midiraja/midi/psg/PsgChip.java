package com.midiraja.midi.psg;

/**
 * Represents a single physical AY-3-8910 / YM2149F hardware chip.
 * Contains 3 Tone Channels, 1 Noise Generator, and 1 Hardware Envelope Generator.
 */
class PsgChip
{
    private static final int NUM_CHANNELS = 3;
    private final int sampleRate;
    
    private static class PsgChannel
    {
        int midiChannel = -1;
        int midiNote = -1;
        boolean active = false;
        long activeFrames = 0; 
        
        int volume15 = 0;
        int[] arpNotes = new int[4];
        int arpSize = 0;
        int arpIndex = 0;
        
        double baseFrequency = 0.0;
        int dutyCycle16 = 32767; // Default 50% square wave
        int phase16 = 0;
        int phaseStep16 = 0;
        boolean isNoise = false; 
        
        void reset()
        {
            active = false;
            volume15 = 0;
            arpSize = 0;
            arpIndex = 0;
            activeFrames = 0;
            isNoise = false;
            midiChannel = -1;
            midiNote = -1;
            baseFrequency = 0.0;
            dutyCycle16 = 32767;
        }
    }
    
    private final PsgChannel[] channels = new PsgChannel[NUM_CHANNELS];
    private int hwEnvPhase16 = 0;
    private int hwEnvStep16 = 0;
    private boolean hwEnvActive = false;
    
    private int lfsr = 1;
    private int noisePhase16 = 0;
    private int noiseStep16 = 0; 
    
    private final double[] dacTable = new double[16];
    
    PsgChip(int sampleRate)
    {
        this.sampleRate = sampleRate;
        for (int i = 0; i < NUM_CHANNELS; i++) channels[i] = new PsgChannel();
        
        for (int i = 0; i < 16; i++) {
            dacTable[i] = Math.pow(10.0, (i - 15) * 1.5 / 20.0);
        }
        dacTable[0] = 0.0;
    }
    
    void reset() {
        for (int i = 0; i < NUM_CHANNELS; i++) channels[i].reset();
        hwEnvActive = false;
    }
    
    double render()
    {
        double sumOutput = 0.0;
        
        noisePhase16 = (noisePhase16 + noiseStep16) & 0xFFFF;
        if (noisePhase16 < noiseStep16) { 
            int bit0 = lfsr & 1;
            int bit3 = (lfsr >> 3) & 1;
            lfsr = (lfsr >> 1) | ((bit0 ^ bit3) << 16);
        }
        boolean noiseBit = (lfsr & 1) == 1;
        
        if (hwEnvActive) {
            hwEnvPhase16 = (hwEnvPhase16 + hwEnvStep16) & 0xFFFF;
        }
        int hwEnvVal15 = 15 - (hwEnvPhase16 >> 12);
        
        for (int ch = 0; ch < NUM_CHANNELS; ch++)
        {
            PsgChannel c = channels[ch];
            if (!c.active) continue;
            
            if (c.activeFrames % 882 == 0) {
                // If Arpeggio is active, do NOT apply vibrato or duty sweeps (too chaotic!)
                if (c.arpSize > 1) {
                    c.arpIndex = (c.arpIndex + 1) % c.arpSize;
                    c.baseFrequency = 440.0 * Math.pow(2.0, (c.arpNotes[c.arpIndex] - 69) / 12.0);
                    c.phaseStep16 = (int) ((c.baseFrequency * 65536.0) / sampleRate);
                    c.dutyCycle16 = 32767; // Lock to pure 50% square for crisp chords
                } else if (c.baseFrequency > 0.0) {
                    // --- HACK 6: DUTY CYCLE SWEEP (FAKE FM) & DEEP VIBRATO ---
                    // By sweeping the duty cycle from 10% to 50%, we create a harsh, nasal 
                    // "Phaser/Wah" effect that tricks the ear into hearing FM synthesis on a 
                    // pure square wave chip.
                    double timeSec = (c.activeFrames / (double) sampleRate);
                    
                    // 1. Sweep Duty Cycle (LFO at 2Hz) -> Gives that "Waaaaow" FM timbre
                    double dutyLfo = Math.sin(timeSec * 2.0 * 2.0 * Math.PI); 
                    double dutyPercent = 0.5 + (0.35 * dutyLfo); // Sweeps between 15% and 85%
                    c.dutyCycle16 = (int) (65535.0 * dutyPercent);
                    
                    // 2. Delayed, Slower, Deeper Vibrato (Pitch bend)
                    if (c.activeFrames > 15 * 882) { // Wait ~0.3 seconds before vibrato hits
                        // 5Hz LFO, 3% depth (almost a semitone) to prevent phase-cancellation chorusing 
                        // and enforce a true solo performance wobble.
                        double pitchLfo = Math.sin(timeSec * 5.0 * 2.0 * Math.PI);
                        double vibratoFreq = c.baseFrequency * (1.0 + (0.03 * pitchLfo));
                        c.phaseStep16 = (int) ((vibratoFreq * 65536.0) / sampleRate);
                    }
                }
                
                if (c.activeFrames % (882 * 4) == 0) {
                    if (c.volume15 > 0) c.volume15--;
                }
                
                if (c.volume15 == 0) {
                    c.active = false;
                    continue;
                }
                
                if (c.midiChannel == 9) {
                    c.isNoise = !c.isNoise;
                }
            }
            
            c.phase16 = (c.phase16 + c.phaseStep16) & 0xFFFF;
            // Use the dynamically sweeping duty cycle instead of hardcoded 50% (32767)
            boolean toneBit = c.phase16 > c.dutyCycle16;
            boolean outBit = c.isNoise ? noiseBit : toneBit;
            
            int finalVol15 = c.volume15;
            if (ch == 2 && hwEnvActive) {
                finalVol15 = hwEnvVal15;
                outBit = true;
            }
            
            double amplitude = dacTable[finalVol15] / 3.0;
            sumOutput += outBit ? amplitude : -amplitude;
            
            c.activeFrames++;
        }
        
        return sumOutput;
    }
    
    boolean updateNote(int ch, int note, int velocity) {
        for (int i = 0; i < NUM_CHANNELS; i++) {
            if (channels[i].active && channels[i].midiChannel == ch && channels[i].midiNote == note) {
                channels[i].volume15 = (int) ((velocity / 127.0) * 15.0);
                return true;
            }
        }
        return false;
    }
    
    boolean tryAllocateFree(int ch, int note, int velocity) {
        int targetCh = -1;
        for (int i = 0; i < NUM_CHANNELS; i++) {
            if (!channels[i].active) {
                targetCh = i;
                break;
            }
        }
        
        if (targetCh == -1) return false;
        
        if (ch == 9) {
            targetCh = 0; // Force to channel 0 for drums if possible
            PsgChannel c = channels[targetCh];
            c.reset();
            c.active = true;
            c.midiChannel = 9;
            c.volume15 = 15;
            c.isNoise = true;
            if (note == 35 || note == 36) noiseStep16 = 500; 
            else if (note == 38 || note == 40) noiseStep16 = 3000;
            else noiseStep16 = 6000;
            return true;
        }
        
        if (note < 45 && targetCh == -1) {
            targetCh = 2; // Prefer ch 2 for bass
        }
        
        PsgChannel c = channels[targetCh];
        c.reset();
        c.active = true;
        c.midiChannel = ch;
        c.midiNote = note;
        c.volume15 = (int) ((velocity / 127.0) * 15.0);
        c.arpNotes[0] = note;
        c.arpSize = 1;
        
        c.baseFrequency = 440.0 * Math.pow(2.0, (note - 69) / 12.0);
        c.phaseStep16 = (int) ((c.baseFrequency * 65536.0) / sampleRate);
        
        if (note < 45) {
            hwEnvStep16 = c.phaseStep16;
            hwEnvActive = true;
        }
        return true;
    }
    
    void forceArpeggioFallback(int ch, int note, int velocity) {
        PsgChannel melCh = channels[1];
        if (melCh.active && melCh.arpSize < 4) {
            melCh.arpNotes[melCh.arpSize++] = note;
        } else {
            // Hard steal
            melCh.reset();
            melCh.active = true;
            melCh.midiChannel = ch;
            melCh.midiNote = note;
            melCh.volume15 = (int) ((velocity / 127.0) * 15.0);
            melCh.arpNotes[0] = note;
            melCh.arpSize = 1;
            melCh.baseFrequency = 440.0 * Math.pow(2.0, (note - 69) / 12.0);
            melCh.phaseStep16 = (int) ((melCh.baseFrequency * 65536.0) / sampleRate);
        }
    }
    
    void handleNoteOff(int ch, int note) {
        for (int i = 0; i < NUM_CHANNELS; i++) {
            if (channels[i].active && channels[i].midiChannel == ch && channels[i].midiNote == note) {
                channels[i].active = false;
                if (i == 2) hwEnvActive = false;
            }
        }
    }
}

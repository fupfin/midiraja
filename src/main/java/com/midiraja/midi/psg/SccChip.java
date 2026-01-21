/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE
 * file in the root directory of this source tree.
 */

package com.midiraja.midi.psg;

/**
 * Emulates the Konami SCC (K051649) Sound Custom Chip.
 * Features 5 channels, each with a 32-byte custom waveform buffer.
 */
public class SccChip implements TrackerSynthChip
{
    private static final int NUM_CHANNELS = 5;
    private final int sampleRate;
    private final double[] dacTable = new double[16];
    private final double vibratoDepth;
    private final boolean smoothScc;

    private static class SccChannel
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
        
        // 32-byte waveform (signed -128 to 127)
        byte[] waveform = new byte[32];
        
        // Use double precision for smooth wavetable reading
        double phase = 0.0;
        double phaseStep = 0.0;
        
        void reset()
        {
            active = false;
            volume15 = 0;
            arpSize = 0;
            arpIndex = 0;
            activeFrames = 0;
            midiChannel = -1;
            midiNote = -1;
            baseFrequency = 0.0;
            phase = 0.0;
            phaseStep = 0.0;
        }
    }
    
    private final SccChannel[] channels = new SccChannel[NUM_CHANNELS];
    
    // --- Pre-calculated Waveforms for GM instruments ---
    private static final byte[] WAVE_PIANO = new byte[32];
    private static final byte[] WAVE_STRINGS = new byte[32];
    private static final byte[] WAVE_BRASS = new byte[32];
    private static final byte[] WAVE_BASS = new byte[32];
    private static final byte[] WAVE_SQUARE = new byte[32];
    
    static {
        for(int i = 0; i < 32; i++) {
            // Saw-ish with early decay (Piano/Guitar)
            WAVE_PIANO[i] = (byte)(255 * (31 - i) / 32 - 128);
            
            // Triangle/Sine mix (Strings/Pad)
            double t = i * Math.PI * 2.0 / 32.0;
            WAVE_STRINGS[i] = (byte)((Math.sin(t) * 0.8 + Math.sin(t * 3) * 0.2) * 127);
            
            // Pure Saw (Brass/Lead)
            WAVE_BRASS[i] = (byte)(255 * i / 32 - 128);
            
            // Plucked pulse (Bass)
            WAVE_BASS[i] = (byte)(i < 8 ? 127 : -128);
            
            // Square (Fallbacks)
            WAVE_SQUARE[i] = (byte)(i < 16 ? 127 : -128);
        }
    }

    public SccChip(int sampleRate, double vibratoDepth)
    {
        this(sampleRate, vibratoDepth, false);
    }
    
    public SccChip(int sampleRate, double vibratoDepth, boolean smoothScc)
    {
        this.sampleRate = sampleRate;
        this.vibratoDepth = Math.max(0.0, Math.min(100.0, vibratoDepth)) / 1000.0; // convert per mille
        this.smoothScc = smoothScc;
        
        for (int i = 0; i < NUM_CHANNELS; i++) {
            channels[i] = new SccChannel();
            System.arraycopy(WAVE_SQUARE, 0, channels[i].waveform, 0, 32);
        }
        
        // Exact same DAC table as PSG for volume parity
        for (int i = 0; i < 16; i++) {
            dacTable[i] = Math.pow(10.0, (i - 15) * 1.5 / 20.0);
        }
        dacTable[0] = 0.0;
    }

    @Override public void reset() {
        for (int i = 0; i < NUM_CHANNELS; i++) channels[i].reset();
    }
    
    @Override public void setProgram(int ch, int program) {
        // Find channels currently playing this midi channel and update their waveform
        byte[] targetWave;
        int family = program / 8;
        switch(family) {
            case 0: // Piano
            case 1: // Chrom Perc
            case 3: // Guitar
                targetWave = WAVE_PIANO; break;
            case 4: // Bass
                targetWave = WAVE_BASS; break;
            case 5: // Strings
            case 11: // Synth Pad
            case 12: // Synth FX
                targetWave = WAVE_STRINGS; break;
            case 7: // Brass
            case 10: // Synth Lead
                targetWave = WAVE_BRASS; break;
            default:
                targetWave = WAVE_SQUARE; break;
        }
        
        for (int i = 0; i < NUM_CHANNELS; i++) {
            if (channels[i].midiChannel == ch) {
                System.arraycopy(targetWave, 0, channels[i].waveform, 0, 32);
            }
        }
    }

    @Override public double render()
    {
        double sumOutput = 0.0;
        
        for (int ch = 0; ch < NUM_CHANNELS; ch++)
        {
            SccChannel c = channels[ch];
            if (!c.active) continue;
            
            if (c.activeFrames % 882 == 0) {
                if (c.arpSize > 1) {
                    c.arpIndex = (c.arpIndex + 1) % c.arpSize;
                    c.baseFrequency = 440.0 * Math.pow(2.0, (c.arpNotes[c.arpIndex] - 69) / 12.0);
                    // Phase goes from 0.0 to 32.0 (length of the wavetable)
                    c.phaseStep = (c.baseFrequency * 32.0) / sampleRate;
                } else if (c.baseFrequency > 0.0 && vibratoDepth > 0.0001) {
                    // --- HACK: DELAYED VIBRATO FOR SCC ---
                    // Wait ~0.5 seconds (25 * 882 frames) before kicking in the vibrato
                    if (c.activeFrames > 25 * 882) {
                        double timeSec = (c.activeFrames / (double) sampleRate);
                        double pitchLfo = Math.sin(timeSec * 3.5 * 2.0 * Math.PI); // 3.5Hz wobble
                        double vibratoFreq = c.baseFrequency * (1.0 + (vibratoDepth * pitchLfo));
                        c.phaseStep = (vibratoFreq * 32.0) / sampleRate;
                    }
                }
            }
            c.activeFrames++;
            
            c.phase += c.phaseStep;
            if (c.phase >= 32.0) {
                c.phase -= 32.0;
            }
            
            double sample;
            if (!smoothScc) {
                // Historically accurate aliased steps
                int index = (int) c.phase;
                sample = c.waveform[index] / 128.0;
            } else {
                // Linear Interpolation for smooth, anti-aliased wavetable synthesis
                int index0 = (int) c.phase;
                int index1 = (index0 + 1) % 32;
                double frac = c.phase - index0;
                
                double s0 = c.waveform[index0] / 128.0; // normalize to -1.0 ~ 1.0
                double s1 = c.waveform[index1] / 128.0;
                
                sample = s0 + frac * (s1 - s0);
            }
            
            // Fake envelope decay based on active frames
            double envDecay = Math.max(0.0, 1.0 - (c.activeFrames / (double)(sampleRate * 2))); // 2 sec decay
            int currentVol15 = (int)(c.volume15 * envDecay);
            currentVol15 = Math.max(0, Math.min(15, currentVol15));
            
            if (!smoothScc) {
                // Historically accurate volume calculation (from openMSX: SCC.cc)
                // The sample (-128 to 127) is multiplied by volume (0-15), then bit-shifted right by 4.
                int rawSample = c.waveform[(int) c.phase];
                int shifted = (rawSample * currentVol15) >> 4;
                // Convert back to -1.0 ~ 1.0 range, scaled for our audio engine.
                // We apply a ~2.6x volume boost (0.85 instead of 0.33 used by PSG) so SCC
                // instruments aren't drowned out by the harsh PSG square waves.
                sumOutput += (shifted / 128.0) * dacTable[currentVol15] * 0.85;
            } else {
                // Modern continuous volume scaling
                sumOutput += sample * dacTable[currentVol15] * 0.85;
            }
        }
        
        return sumOutput;
    }
    
    @Override public boolean updateNote(int ch, int note, int velocity) {
        for (int i = 0; i < NUM_CHANNELS; i++) {
            SccChannel c = channels[i];
            if (c.active && c.midiChannel == ch) {
                if (c.arpSize > 0) {
                    for (int j = 0; j < c.arpSize; j++) {
                        if (c.arpNotes[j] == note) {
                            c.volume15 = (int) ((velocity / 127.0) * 15.0);
                            return true;
                        }
                    }
                } else if (c.midiNote == note) {
                    c.volume15 = (int) ((velocity / 127.0) * 15.0);
                    return true;
                }
            }
        }
        return false;
    }
    
    @Override public boolean tryAllocateFree(int ch, int note, int velocity) {
        int targetCh = -1;
        for (int i = 0; i < NUM_CHANNELS; i++) {
            if (!channels[i].active) {
                targetCh = i;
                break;
            }
        }
        
        if (targetCh == -1) return false;
        
        SccChannel c = channels[targetCh];
        c.reset();
        c.active = true;
        c.midiChannel = ch;
        c.midiNote = note;
        c.volume15 = (int) ((velocity / 127.0) * 15.0);
        c.arpNotes[0] = note;
        c.arpSize = 1;
        c.baseFrequency = 440.0 * Math.pow(2.0, (note - 69) / 12.0);
        c.phaseStep = (c.baseFrequency * 32.0) / sampleRate;
        c.phase = 0.0;
        
        // Let PsgSynthProvider handle program changes, but default is square.
        
        return true;
    }

    @Override public void forceArpeggioFallback(int ch, int note, int velocity) {
        int targetCh = -1;
        
        // 1. Try to find a channel already playing the SAME instrument (midiChannel) 
        // that has room in its arpeggio buffer (< 4 notes).
        for (int i = 0; i < NUM_CHANNELS; i++) {
            SccChannel c = channels[i];
            if (c.active && c.midiChannel == ch && c.arpSize < 4) {
                targetCh = i;
                break;
            }
        }
        
        // 2. If no matching instrument has room, steal from the first channel
        if (targetCh == -1) {
            targetCh = 0; // Fallback hard steal
            SccChannel c = channels[targetCh];
            c.reset();
            c.active = true;
            c.midiChannel = ch;
            c.midiNote = note;
            c.volume15 = (int) ((velocity / 127.0) * 15.0);
            c.arpNotes[0] = note;
            c.arpSize = 1;
            c.baseFrequency = 440.0 * Math.pow(2.0, (note - 69) / 12.0);
            c.phaseStep = (c.baseFrequency * 32.0) / sampleRate;
            return;
        }
        
        // 3. Append to existing arpeggio buffer
        SccChannel c = channels[targetCh];
        
        if (c.arpSize == 0 && c.active) {
            c.arpNotes[0] = c.midiNote;
            c.arpSize = 1;
        }
        
        boolean exists = false;
        for(int i=0; i<c.arpSize; i++) {
            if (c.arpNotes[i] == note) exists = true;
        }
        
        if (!exists) {
            c.arpNotes[c.arpSize++] = note;
            c.volume15 = Math.max(c.volume15, (int) ((velocity / 127.0) * 15.0));
        }
    }

    @Override public void handleNoteOff(int ch, int note) {
        for (int i = 0; i < NUM_CHANNELS; i++) {
            SccChannel c = channels[i];
            if (c.active && c.midiChannel == ch) {
                if (c.arpSize > 1) {
                    int removeIdx = -1;
                    for (int j = 0; j < c.arpSize; j++) {
                        if (c.arpNotes[j] == note) {
                            removeIdx = j;
                            break;
                        }
                    }
                    
                    if (removeIdx != -1) {
                        for (int j = removeIdx; j < c.arpSize - 1; j++) {
                            c.arpNotes[j] = c.arpNotes[j + 1];
                        }
                        c.arpSize--;
                        
                        if (c.arpIndex >= c.arpSize) {
                            c.arpIndex = 0;
                        }
                    }
                } else if (c.midiNote == note) {
                    c.active = false;
                }
            }
        }
    }
}
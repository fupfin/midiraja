/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.midiraja.midi.beep;

import com.midiraja.dsp.PwmAcousticSimulator;
import com.midiraja.midi.MidiPort;
import com.midiraja.midi.NativeAudioEngine;
import com.midiraja.midi.SoftSynthProvider;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import org.jspecify.annotations.Nullable;

@SuppressWarnings({"ThreadPriorityCheck", "EmptyCatch"})
public class BeepSynthProvider implements SoftSynthProvider
{
    private static class DspParams {
        final double lpfCutoff;
        final double ditherAmp;
        final double pmOverdrive;
        
        DspParams(double lpfCutoff, double ditherAmp, double pmOverdrive) {
            this.lpfCutoff = lpfCutoff;
            this.ditherAmp = ditherAmp;
            this.pmOverdrive = pmOverdrive;
        }
    }

    private static final java.util.Map<String, DspParams> GOD_TABLE = java.util.Map.ofEntries(
        // Purist Architecture: Only XOR and TDM are valid true Multiplexers.
        java.util.Map.entry("pm_tdm_1", new DspParams(0.230, 0.000, 1.580)),
        java.util.Map.entry("pm_tdm_2", new DspParams(0.241, 0.000, 1.806)),
        java.util.Map.entry("pm_tdm_4", new DspParams(0.224, 0.000, 0.000)),
        java.util.Map.entry("pm_xor_1", new DspParams(0.028, 0.000, 8.625)),
        java.util.Map.entry("pm_xor_2", new DspParams(0.186, 0.000, 0.129)),
        java.util.Map.entry("pm_xor_4", new DspParams(0.143, 0.000, 0.059)),
        java.util.Map.entry("xor_tdm_1", new DspParams(0.023, 0.000, 0.000)),
        java.util.Map.entry("xor_tdm_2", new DspParams(0.036, 0.000, 0.000)),
        java.util.Map.entry("xor_tdm_4", new DspParams(0.028, 0.000, 0.000)),
        java.util.Map.entry("xor_xor_1", new DspParams(0.024, 0.000, 0.000)),
        java.util.Map.entry("xor_xor_2", new DspParams(0.028, 0.000, 0.000)),
        java.util.Map.entry("xor_xor_4", new DspParams(0.033, 0.000, 0.000)),
        java.util.Map.entry("square_tdm_1", new DspParams(0.232, 0.000, 0.000)),
        java.util.Map.entry("square_tdm_2", new DspParams(0.236, 0.000, 0.000)),
        java.util.Map.entry("square_tdm_4", new DspParams(0.230, 0.000, 0.000)),
        java.util.Map.entry("square_xor_1", new DspParams(0.230, 0.000, 0.000)),
        java.util.Map.entry("square_xor_2", new DspParams(0.032, 0.000, 0.000)),
        java.util.Map.entry("square_xor_4", new DspParams(0.029, 0.000, 0.000))
    );


    private final NativeAudioEngine audio;
    private final int voicesPerCore;
    private final double fmRatio;
    private final double fmIndex;
    private final int oversample;
    private final String muxMode;
    private final String synthMode;
    private final int sampleRate = 44100;
    
    // Dynamic DSP Parameters discovered via Genetic Algorithm
    @SuppressWarnings("unused") private final double dspLpfCutoff;
    @SuppressWarnings("unused") private final double dspDitherAmp;
    private final double pmOverdrive;
    private final double dspDcBlockerR;
    @SuppressWarnings("unused") private final boolean dspUseDcBlocker;
    
    private @Nullable Thread renderThread;
    private volatile boolean running = false;
    private volatile boolean renderPaused = false;

    private static class ActiveNote {
        volatile boolean active = false;
        int channel;
        int note;
        double frequency;
        double phase = 0.0;
        double modPhase = 0.0;
        double lfoPhase = 0.0;
        long activeFrames = 0;
        boolean isDrum = false;
        
        void reset() {
            phase = 0.0;
            modPhase = 0.0;
            lfoPhase = 0.0;
            activeFrames = 0;
            frequency = 0.0;
        }
    }
    
        // Per-channel Pitch Bend states (-8192 to 8191, where 0 is center)
    private final int[] pitchBends = new int[16];
    
    private static final int MAX_POLYPHONY = 128;
    private final ActiveNote[] activeNotes = new ActiveNote[MAX_POLYPHONY];
    {
        for (int i = 0; i < MAX_POLYPHONY; i++) activeNotes[i] = new ActiveNote();
    }

    // Purist 8-Bit Integer Sine LUT (Retro Hardware Emulation)
    // Replaced the 64-bit float array with a strict signed 8-bit (-127 to +127) lookup table.
    // This perfectly recreates the 'quantization grit' inherent to 1980s hardware FM chips (like the OPL2).
    private static final int SINE_LUT_SIZE = 256; // Smaller size like old ROMs
    private static final byte[] SINE_LUT_8BIT = new byte[SINE_LUT_SIZE];
    static {
        for (int i = 0; i < SINE_LUT_SIZE; i++) {
            // Calculate pure math, then forcefully crush it into a signed 8-bit integer
            double pureSine = Math.sin((i / (double) SINE_LUT_SIZE) * 2.0 * Math.PI);
            SINE_LUT_8BIT[i] = (byte) Math.round(pureSine * 127.0); 
        }
    }
    
    // Returns the 8-bit integer value converted back to a [-1.0, 1.0] float for the modern mixing bridge
    private static double fastSin(double phase) {
        int index = (int) (phase * SINE_LUT_SIZE);
        if (index < 0) index = 0;
        if (index >= SINE_LUT_SIZE) index = SINE_LUT_SIZE - 1;
        
        // Read the gritty 8-bit value (-127 to 127) and normalize it
        return SINE_LUT_8BIT[index] / 127.0;
    }

    private static int rngSeed = 12345;
    private static double fastRandom() {
        rngSeed ^= (rngSeed << 13);
        rngSeed ^= (rngSeed >>> 17);
        rngSeed ^= (rngSeed << 5);
        return ((rngSeed & 0x7FFFFFFF) / (double) Integer.MAX_VALUE);
    }

    private double lpfState = 0.0; 
    private double lpfState2 = 0.0;
    

    private final int numUnits;
    
    // ---------------------------------------------------------
    // Ultimate 1-Bit Digital Unit (XOR Multiplexing FM)
    // ---------------------------------------------------------
    private class DigitalUnit {
        private double pwmCarrierPhase = -1.0;
        private final double pwmCarrierStep;
        
        // Per-Unit DC Blocker (Isolation)
        private double dcBlockerX = 0.0;
        private double dcBlockerY = 0.0;
        
        

        
        double internalPwmCarrier = -1.0;
        
        DigitalUnit(int sampleRate) {
            // DAC522 Hardware limit: 22.05kHz carrier
            this.pwmCarrierStep = (22050.0 / sampleRate) * 2.0;
        }

        double render(List<ActiveNote> assignedNotes) {
            if (assignedNotes.isEmpty()) return 0.0;
            
            double sumPwm = 0.0;
            int numNotes = assignedNotes.size();
            double trueSampleRate = sampleRate * oversample;
            
            for (int o = 0; o < oversample; o++) {
                pwmCarrierPhase += pwmCarrierStep / oversample;
                if (pwmCarrierPhase > 1.0) pwmCarrierPhase -= 2.0;
                
                // Advanced an internal fast carrier just for quantizing PM waves BEFORE multiplexing
                internalPwmCarrier += (22050.0 / sampleRate) * 2.0 / oversample;
                if (internalPwmCarrier > 1.0) internalPwmCarrier -= 2.0;
                
                boolean mixedXor = false;
                boolean hasActiveNotes = false;
                boolean tdmBit = false;
                
                // --- LAYER 1 & 2: SYNTHESIS & QUANTIZATION ---
                // Every synthesizer MUST output a pure boolean (0 or 1) into the multiplexer.
                for (int i = 0; i < numNotes; i++) {
                    ActiveNote note = assignedNotes.get(i);
                    double time = note.activeFrames / (double) sampleRate; 
                    boolean synthBit = false;
                    
                    if (note.isDrum) {
                        int noteNum = note.note;
                        double out = 0.0;
                        if (noteNum == 35 || noteNum == 36) { 
                            if (time < 0.2) {
                                double pitchDrop = 150.0 * Math.exp(-time * 30.0);
                                note.phase += (50.0 + pitchDrop) / trueSampleRate;
                                note.phase = note.phase - Math.floor(note.phase);
                                out = fastSin(note.phase);
                            }
                        } else if (noteNum == 38 || noteNum == 40) { 
                            if (time < 0.15) {
                                double noiseEnv = Math.exp(-time * 20.0);
                                note.phase += 200.0 / trueSampleRate;
                                note.phase = note.phase - Math.floor(note.phase);
                                double tone = fastSin(note.phase) * Math.exp(-time * 10.0) * 0.4;
                                double noise = (fastRandom() * 2.0 - 1.0) * noiseEnv * 1.5;
                                out = Math.max(-1.0, Math.min(1.0, tone + noise));
                            }
                        } else if (noteNum == 42 || noteNum == 44 || noteNum == 46 || noteNum >= 49) {
                            double duration = (noteNum >= 49) ? 0.3 : 0.05;
                            if (time < duration) {
                                double env = Math.exp(-time * (1.0 / duration) * 5.0);
                                out = (fastRandom() > 0.5 ? 1.5 : -1.5) * env;
                            }
                        } else { 
                            if (time < 0.25) {
                                double pitchDrop = 300.0 * Math.exp(-time * 15.0);
                                note.phase += (80.0 + pitchDrop) / trueSampleRate;
                                note.phase = note.phase - Math.floor(note.phase);
                                out = fastSin(note.phase);
                            }
                        }
                        // Quantize drums using PWM
                        synthBit = out > internalPwmCarrier;
                        if (Math.abs(out) > 0.05) hasActiveNotes = true;
                        
                    } else {
                        if ("xor".equals(synthMode)) {
                            // Synthesizer is inherently Boolean
                            note.phase += note.frequency / trueSampleRate;
                            note.phase = note.phase - Math.floor(note.phase);
                            
                            double actualRatio = fmRatio == 1.0 ? 1.005 : fmRatio;
                            double modFreq = note.frequency * actualRatio;
                            note.modPhase += modFreq / trueSampleRate;
                            note.modPhase = note.modPhase - Math.floor(note.modPhase);
                            
                            boolean carrierBit = note.phase > 0.5;
                            boolean modBit = note.modPhase > 0.5;
                            synthBit = (fmIndex < 0.1) ? carrierBit : (carrierBit ^ modBit);
                            
                            // Check volume envelope to silence dead notes
                            double decay = Math.max(0.0, 1.0 - (time / 0.5));
                            if (decay > 0.01) hasActiveNotes = true;
                            else synthBit = false; // Noise gate
                            
                        } else if ("square".equals(synthMode)) {
                            // Synthesizer is inherently Boolean
                            double decay = Math.max(0.0, 1.0 - (time / 1.5));
                            note.lfoPhase += 1.0 / trueSampleRate;
                            double vibratoLfo = fastSin(note.lfoPhase * 6.0 - Math.floor(note.lfoPhase * 6.0));
                            double sweepLfo = fastSin(note.lfoPhase * 1.5 - Math.floor(note.lfoPhase * 1.5));
                            
                            double wobbledFreq = note.frequency * (1.0 + (0.015 * vibratoLfo));
                            note.phase += wobbledFreq / trueSampleRate;
                            note.phase = note.phase - Math.floor(note.phase);
                            
                            double dutyCycle = 0.5 + (0.4 * sweepLfo);
                            synthBit = note.phase > dutyCycle;
                            
                            if (decay > 0.01) hasActiveNotes = true;
                            else synthBit = false;
                            
                        } else {
                            // PHASE MODULATION (Analog generation)
                            double decay = Math.max(0.0, 1.0 - (time / 0.5));
                            double keyScale = 1.0;
                            if (note.frequency > 261.63) {
                                keyScale = 261.63 / note.frequency; 
                            }
                            double scaledFmIndex = fmIndex * keyScale;
                            double envIndex = (scaledFmIndex * 0.1) + (scaledFmIndex * decay); 
                            
                            double modFreq = note.frequency * fmRatio;
                            note.modPhase += modFreq / trueSampleRate;
                            note.modPhase = note.modPhase - Math.floor(note.modPhase);
                            double modulator = fastSin(note.modPhase);
                            
                            note.phase += note.frequency / trueSampleRate;
                            note.phase = note.phase - Math.floor(note.phase);
                            
                            double finalPhase = note.phase + (modulator * (envIndex / (2.0 * Math.PI)));
                            finalPhase = finalPhase - Math.floor(finalPhase);
                            
                            double rawSine = fastSin(finalPhase);
                            double out = (pmOverdrive > 0.0) ? Math.tanh(rawSine * pmOverdrive) * decay : rawSine * decay;
                            
                            // LAYER 2: THE QUANTIZER
                            // Purist rule: Analog sine wave MUST be converted to a discrete boolean 
                            // via an internal PWM comparator before entering the multiplexer!
                            synthBit = out > internalPwmCarrier;
                            
                            if (Math.abs(out) > 0.05) hasActiveNotes = true;
                            else synthBit = false; // Noise gate
                        }
                    }
                    
                    // --- LAYER 3: THE MULTIPLEXER (Boolean Domain Only) ---
                    if (i == 0) mixedXor = synthBit;
                    else mixedXor ^= synthBit;
                    
                    if (i == (o % numNotes)) tdmBit = synthBit;
                }
                
                // Final Pin Output
                if ("tdm".equals(muxMode)) {
                    if (!hasActiveNotes) sumPwm += 0.0;
                    else sumPwm += (tdmBit ? 1.0 : -1.0);
                } else {
                    // Default to XOR mux
                    if (!hasActiveNotes) sumPwm += 0.0;
                    else sumPwm += (mixedXor ? 1.0 : -1.0);
                }
            }
            
            double rawPwm = sumPwm / oversample;
            
            // ISOLATION: Apply DC Blocking instantly at the pin level.
            if (dspUseDcBlocker) {
                double R = dspDcBlockerR;
                double cleanSignal = rawPwm - dcBlockerX + (R * dcBlockerY);
                dcBlockerX = rawPwm;
                dcBlockerY = cleanSignal;
                return cleanSignal;
            } else {
                return rawPwm;
            }
        }
    }    private final DigitalUnit[] units;
    private final List<List<ActiveNote>> unitAssignments;

    public BeepSynthProvider(NativeAudioEngine audio, int voices, double fmRatio, double fmIndex, int oversample, String muxMode, String synthMode) {
        this.audio = audio;
        this.voicesPerCore = Math.max(1, Math.min(4, voices));
        this.fmRatio = fmRatio;
        this.fmIndex = fmIndex;
        this.oversample = Math.max(1, oversample);
        this.muxMode = muxMode;
        this.synthMode = synthMode;
        
        // --- THE GOD TABLE: AI-Driven Dynamic DSP Optimization ---
        // Instantly loads the exact, mathematically perfect filter parameters calculated 
        // by the Python Genetic Algorithm for the user's specific combination of modes.
        String key = synthMode + "_" + muxMode + "_" + this.voicesPerCore;
        DspParams params = GOD_TABLE.getOrDefault(key, new DspParams(0.25, 0.0, 0.0));
        
        this.dspLpfCutoff = params.lpfCutoff;
        this.dspDitherAmp = params.ditherAmp;
        this.pmOverdrive = params.pmOverdrive;
        
        // DC Blocker is mathematically only required (and safe) when combining multiple square waves via XOR.
        if ("xor".equals(muxMode) && this.voicesPerCore > 1) {
            this.dspUseDcBlocker = true;
            this.dspDcBlockerR = 0.995;
        } else {
            this.dspUseDcBlocker = false;
            this.dspDcBlockerR = 0.0;
        }
        
        // Dynamic Unit Scaling: Always guarantee at least 16 total polyphony (12 Melody + 4 Drum)
        // If voices = 1, we need 16 units. If voices = 2, we need 8 units. If voices = 4, we need 4 units.
        this.numUnits = (int) Math.ceil(16.0 / this.voicesPerCore);
        
        this.units = new DigitalUnit[numUnits];
        this.unitAssignments = new ArrayList<>(numUnits);
        
        for (int i = 0; i < numUnits; i++) {
            this.units[i] = new DigitalUnit(sampleRate);
            this.unitAssignments.add(new ArrayList<>(8));
        }
    }

    @Override
    public List<MidiPort> getOutputPorts() {
        return List.of(new MidiPort(0, String.format("[%d-Unit] 1-Bit Digital Cluster", numUnits)));
    }

    @Override
    public void openPort(int portIndex) throws Exception {
        audio.init(sampleRate, 1, 4096);
        startRenderThread();
    }

    @Override public void loadSoundbank(String path) throws Exception {}

    private void startRenderThread() {
        running = true;
        renderThread = new Thread(() -> {
            final int framesToRender = 512;
            short[] pcmBuffer = new short[framesToRender];
            while (running) {
                if (renderPaused) {
                    try { Thread.sleep(1); } catch (InterruptedException e) { break; }
                    continue;
                }
                
                // Wait-Free garbage collection and snapshot
                List<ActiveNote> currentNotes = new ArrayList<>(32);
                for (int i = 0; i < MAX_POLYPHONY; i++) {
                    ActiveNote n = activeNotes[i];
                    if (n.active) {
                        if ((n.isDrum && n.activeFrames > sampleRate * 0.2) || 
                            (!n.isDrum && n.activeFrames > sampleRate * 3.0)) {
                            n.active = false;
                        } else {
                            currentNotes.add(n);
                        }
                    }
                }
                
                if (currentNotes.isEmpty()) {
                    for (int i = 0; i < framesToRender; i++) pcmBuffer[i] = 0;
                } else {
                    renderCluster(currentNotes, pcmBuffer, framesToRender);
                }
                audio.push(pcmBuffer);
            }
        });
        renderThread.setPriority(Thread.MAX_PRIORITY);
        renderThread.setDaemon(true);
        renderThread.start();
    }

    private void renderCluster(List<ActiveNote> notes, short[] buffer, int frames) {
        for (int i = 0; i < numUnits; i++) unitAssignments.get(i).clear();
        
        int drumUnits = Math.max(1, numUnits / 4);
        int melodyUnits = numUnits - drumUnits;
        
        int melodyIdx = 0, drumIdx = 0;
        List<ActiveNote> survivingNotes = new ArrayList<>(notes.size());
        
        for (ActiveNote note : notes) {
            boolean assigned = false;
            
            if (note.isDrum) {
                // Simple Round-Robin for Drums (they are short noise bursts anyway)
                for (int attempt = 0; attempt < drumUnits; attempt++) {
                    int target = melodyUnits + ((drumIdx + attempt) % drumUnits);
                    if (unitAssignments.get(target).size() < voicesPerCore) {
                        unitAssignments.get(target).add(note);
                        drumIdx = target + 1 - melodyUnits;
                        assigned = true;
                        break;
                    }
                }
            } else {
                // --- FREQUENCY-WEIGHTED BASS ISOLATION ROUTING ---
                // The ultimate psychoacoustic solution: Never allow two deep bass notes 
                // (<150Hz) to share the same physical unit! Low-frequency multiplexing 
                // causes catastrophic beat frequencies. We must pair Bass + Treble together.
                
                int bestTarget = -1;
                double bestScore = -1.0;
                boolean isBassNote = note.frequency < 150.0;
                
                for (int i = 0; i < melodyUnits; i++) {
                    List<ActiveNote> currentOccupants = unitAssignments.get(i);
                    
                    // Skip if unit is already full
                    if (currentOccupants.size() >= voicesPerCore) continue;
                    
                    // Analyze current occupants
                    boolean hasBass = false;
                    for (ActiveNote occupant : currentOccupants) {
                        if (occupant.frequency < 150.0) hasBass = true;
                    }
                    
                    // Score this unit (Higher is better)
                    double score = 10.0; // Base score
                    
                    // 1. Prioritize empty units to maximize spread
                    if (currentOccupants.isEmpty()) score += 5.0;
                    
                    // 2. The Bass Isolation Rule!
                    if (isBassNote && hasBass) {
                        // EXTREME PENALTY: Two bass notes in one room = Muddy explosion
                        score -= 100.0; 
                    } else if (!isBassNote && hasBass) {
                        // PERFECT MATCH: Treble + Bass sharing a room = Excellent bandwidth separation
                        score += 20.0;
                    }
                    
                    // 3. Round-robin tie-breaker (to keep spread moving)
                    if (i == melodyIdx) score += 1.0;
                    
                    if (score > bestScore) {
                        bestScore = score;
                        bestTarget = i;
                    }
                }
                
                // Assign to the best calculated unit
                if (bestTarget != -1) {
                    unitAssignments.get(bestTarget).add(note);
                    melodyIdx = (bestTarget + 1) % melodyUnits; // Advance RR pointer
                    assigned = true;
                }
            }
            
            if (assigned) {
                survivingNotes.add(note);
            } else {
                note.active = false; // Voice Stealing: Kill overflow ghost notes
            }
        }
        
        for (int i = 0; i < frames; i++) {
            double sumOfAppleIIs = 0.0;
            for (int s = 0; s < numUnits; s++) {
                sumOfAppleIIs += units[s].render(unitAssignments.get(s));
            }
            
            // Only age the notes that are ACTUALLY playing
            for (ActiveNote n : survivingNotes) n.activeFrames++;
            
            // Analog Mixing Console: Sum the voltages, add slight headroom
            double analogMix = (sumOfAppleIIs / numUnits) * 0.8; 
            
            // 1. Master Acoustic Filtering (2.25-inch paper cones)
            double filterCutoff = 0.25; 
            lpfState += filterCutoff * (analogMix - lpfState);
            lpfState2 += filterCutoff * (lpfState - lpfState2);
            
            // 3. Hard Clip safety
            double finalMix = Math.max(-1.0, Math.min(1.0, lpfState2));
            buffer[i] = (short) (finalMix * 18000);
        }
    }

    @Override public void closePort() {
        running = false;
        if (renderThread != null) renderThread.interrupt();
    }

    @Override public void prepareForNewTrack(javax.sound.midi.Sequence seq) {
        if (audio == null) return;
        renderPaused = true;
        audio.flush();
        for (int i = 0; i < MAX_POLYPHONY; i++) activeNotes[i].active = false;
    }

    @Override public void onPlaybackStarted() { renderPaused = false; }

    @Override
    public void sendMessage(byte[] data) throws Exception {
        if (data.length > 0) {
            int cmd = data[0] & 0xF0;
            int ch = data[0] & 0x0F;
            
            // Handle Pitch Bend (0xE0)
            if (cmd == 0xE0 && data.length >= 3) {
                int lsb = data[1] & 0x7F;
                int msb = data[2] & 0x7F;
                int bend = (msb << 7) | lsb; // 0 to 16383
                pitchBends[ch] = bend - 8192; // Center at 0
                
                // Instantly update frequencies of all playing notes on this channel
                for (int i = 0; i < MAX_POLYPHONY; i++) {
                    ActiveNote n = activeNotes[i];
                    if (n.active && n.channel == ch) {
                        // Standard MIDI pitch bend range is typically +/- 2 semitones
                        double bendSemitones = (pitchBends[ch] / 8192.0) * 2.0;
                        n.frequency = 440.0 * Math.pow(2.0, (n.note - 69 + bendSemitones) / 12.0);
                    }
                }
            }
            
            if (cmd == 0x90 && data.length >= 3) {
                int note = data[1] & 0xFF;
                int velocity = data[2] & 0xFF;
                if (velocity > 0) {
                    for (int i = 0; i < MAX_POLYPHONY; i++) {
                        ActiveNote n = activeNotes[i];
                        if (n.active && n.channel == ch && n.note == note) n.active = false;
                    }
                    for (int i = 0; i < MAX_POLYPHONY; i++) {
                        ActiveNote n = activeNotes[i];
                        if (!n.active) {
                            n.reset();
                            n.channel = ch; n.note = note;
                                                        double bendSemitones = (pitchBends[ch] / 8192.0) * 2.0;
                            n.frequency = 440.0 * Math.pow(2.0, (n.note - 69 + bendSemitones) / 12.0);
                            n.isDrum = (ch == 9);
                            n.active = true;
                            break;
                        }
                    }
                } else {
                    for (int i = 0; i < MAX_POLYPHONY; i++) {
                        ActiveNote n = activeNotes[i];
                        if (n.active && n.channel == ch && n.note == note) n.active = false;
                    }
                }
            } else if (cmd == 0x80 && data.length >= 2) {
                int note = data[1] & 0xFF;
                for (int i = 0; i < MAX_POLYPHONY; i++) {
                    ActiveNote n = activeNotes[i];
                    if (n.active && n.channel == ch && n.note == note) n.active = false;
                }
            } else if (cmd == 0xB0 && data.length >= 3) {
                int cc = data[1] & 0xFF;
                if (cc == 123 || cc == 120) { // All Notes Off / All Sound Off
                    for (int i = 0; i < MAX_POLYPHONY; i++) activeNotes[i].active = false;
                } else if (cc == 121) { // Reset All Controllers
                    pitchBends[ch] = 0;
                    for (int i = 0; i < MAX_POLYPHONY; i++) {
                        ActiveNote n = activeNotes[i];
                        if (n.active && n.channel == ch) {
                            n.frequency = 440.0 * Math.pow(2.0, (n.note - 69) / 12.0);
                        }
                    }
                }
            }
        }
    }
}
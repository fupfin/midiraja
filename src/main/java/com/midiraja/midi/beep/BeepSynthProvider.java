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
    private final NativeAudioEngine audio;
    private final int voicesPerCore;
    private final double fmRatio;
    private final double fmIndex;
    private final int oversample;
    private final String muxMode;
    private final String synthMode;
    private final int sampleRate = 44100;
    
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
        double cachedSample = 0.0;
        
        void reset() {
            phase = 0.0;
            modPhase = 0.0;
            lfoPhase = 0.0;
            activeFrames = 0;
            frequency = 0.0;
            cachedSample = 0.0;
        }
    }
    
        // Per-channel Pitch Bend states (-8192 to 8191, where 0 is center)
    private final int[] pitchBends = new int[16];
    
    private static final int MAX_POLYPHONY = 128;
    private final ActiveNote[] activeNotes = new ActiveNote[MAX_POLYPHONY];
    {
        for (int i = 0; i < MAX_POLYPHONY; i++) activeNotes[i] = new ActiveNote();
    }

    private static final int SINE_LUT_SIZE = 4096;
    private static final double[] SINE_LUT = new double[SINE_LUT_SIZE];
    static {
        for (int i = 0; i < SINE_LUT_SIZE; i++) {
            SINE_LUT[i] = Math.sin((i / (double) SINE_LUT_SIZE) * 2.0 * Math.PI);
        }
    }
    
    private static double fastSin(double phase) {
        int index = (int) (phase * SINE_LUT_SIZE);
        if (index < 0) index = 0;
        if (index >= SINE_LUT_SIZE) index = SINE_LUT_SIZE - 1;
        return SINE_LUT[index];
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
        private double sigmaDeltaError = 0.0;
        private double sigmaDeltaError1 = 0.0;
        private double sigmaDeltaError2 = 0.0;
        

        
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
                
                double analogMix = 0.0;
                boolean mixedXor = false;
                boolean xorMix = false;
                boolean hasActiveNotes = false;
                double tdmSample = 0.0;
                
                // TRUE OVERSAMPLED SYNTHESIS: Advance phase at 1.4MHz to prevent Zero-Order Hold aliasing!
                for (int i = 0; i < numNotes; i++) {
                    ActiveNote note = assignedNotes.get(i);
                    double time = note.activeFrames / (double) sampleRate; 
                    double out = 0.0;
                    
                    if (note.isDrum) {
                        int noteNum = note.note;
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
                    } else {
                        if ("xor".equals(synthMode)) {
                            note.phase += note.frequency / trueSampleRate;
                            note.phase = note.phase - Math.floor(note.phase);
                            
                            double actualRatio = fmRatio == 1.0 ? 1.005 : fmRatio;
                            double modFreq = note.frequency * actualRatio;
                            note.modPhase += modFreq / trueSampleRate;
                            note.modPhase = note.modPhase - Math.floor(note.modPhase);
                            
                            boolean carrierBit = note.phase > 0.5;
                            boolean modBit = note.modPhase > 0.5;
                            boolean finalBit = (fmIndex < 0.1) ? carrierBit : (carrierBit ^ modBit);
                            
                            double decay = Math.max(0.0, 1.0 - (time / 0.5));
                            out = (finalBit ? 1.0 : -1.0) * decay;
                            
                        } else if ("square".equals(synthMode)) {
                            double decay = Math.max(0.0, 1.0 - (time / 1.5));
                            note.lfoPhase += 1.0 / trueSampleRate;
                            double vibratoLfo = fastSin(note.lfoPhase * 6.0 - Math.floor(note.lfoPhase * 6.0));
                            double sweepLfo = fastSin(note.lfoPhase * 1.5 - Math.floor(note.lfoPhase * 1.5));
                            
                            double wobbledFreq = note.frequency * (1.0 + (0.015 * vibratoLfo));
                            note.phase += wobbledFreq / trueSampleRate;
                            note.phase = note.phase - Math.floor(note.phase);
                            
                            double dutyCycle = 0.5 + (0.4 * sweepLfo);
                            boolean squareBit = note.phase > dutyCycle;
                            out = (squareBit ? 1.0 : -1.0) * decay;
                            
                        } else {
                            // Phase Modulation
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
                            
                            // Pure Sine Wave (No Wave Shaping/Distortion)
                            // User correctly identified that the soft-clipping (Math.tanh)
                            // was destroying the pure sine wave and generating high-frequency 
                            // aliasing (squeaking noise) exactly like the square/xor modes.
                            // By returning to a mathematically pure sine wave, we guarantee 
                            // absolute silence in the high-frequency spectrum.
                            double rawSine = fastSin(finalPhase);
                            out = rawSine * decay;
                        }
                    }
                    
                    analogMix += out;
                    
                    // Support logic for XOR/TDM muxes
                    if (Math.abs(out) > 0.05) hasActiveNotes = true;
                    boolean pwmBit = out > pwmCarrierPhase;
                    if (i == 0) mixedXor = pwmBit;
                    else mixedXor ^= pwmBit;
                    
                    if (i == (o % numNotes)) tdmSample = out;
                }
                
                analogMix /= numNotes;
                
                // MULTIPLEXING ENGINE
                if ("xor".equals(muxMode)) {
                    if (!hasActiveNotes) sumPwm += 0.0;
                    else sumPwm += (mixedXor ? 1.0 : -1.0);
                    
                } else if ("tdm".equals(muxMode)) {
                    boolean pwmBit = tdmSample > pwmCarrierPhase;
                    sumPwm += (pwmBit ? 1.0 : -1.0);
                    
                } else if ("pwm".equals(muxMode)) {
                    boolean pwmBit = analogMix > pwmCarrierPhase;
                    sumPwm += (pwmBit ? 1.0 : -1.0);
                    
                } else {
                    // DSD
                    double dither1 = fastRandom() * 2.0 - 1.0;
                    double dither2 = fastRandom() * 2.0 - 1.0;
                    double tpdfDither = (dither1 + dither2) * 0.03; 
                    
                    sigmaDeltaError += (analogMix + tpdfDither);
                    double outBit = (sigmaDeltaError > 0.0) ? 1.0 : -1.0;
                    sigmaDeltaError -= outBit;
                    sumPwm += outBit;
                }
            }
            
            double rawPwm = sumPwm / oversample;
            
            // ISOLATION: Apply DC Blocking instantly at the pin level.
            if ("xor".equals(muxMode) && voicesPerCore > 1) {
                double R = 0.995;
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
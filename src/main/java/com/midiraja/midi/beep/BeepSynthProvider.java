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
        long activeFrames = 0;
        boolean isDrum = false;
        double cachedSample = 0.0;
        
        void reset() {
            phase = 0.0;
            modPhase = 0.0;
            activeFrames = 0;
        }
    }
    
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

    private static final int NUM_SPEAKERS = 8;
    
    // ---------------------------------------------------------
    // Ultimate Apple II Core (XOR Multiplexing FM)
    // ---------------------------------------------------------
    private class AppleIICore {
        private double pwmCarrierPhase = -1.0;
        private final double pwmCarrierStep;
        
        AppleIICore(int sampleRate) {
            // DAC522 Hardware limit: 22.05kHz carrier
            this.pwmCarrierStep = (22050.0 / sampleRate) * 2.0;
        }

        double render(List<ActiveNote> assignedNotes) {
            if (assignedNotes.isEmpty()) return 0.0;
            
            // 1. Calculate pure analog FM output for all notes (once per 44.1kHz frame)
            for (int i = 0; i < assignedNotes.size(); i++) {
                ActiveNote note = assignedNotes.get(i);
                double time = note.activeFrames / (double) sampleRate;
                double out = 0.0;
                
                if (note.isDrum) {
                    int noteNum = note.note;
                    if (noteNum == 35 || noteNum == 36) { // Kick
                        if (time < 0.2) {
                            double pitchDrop = 150.0 * Math.exp(-time * 30.0);
                            note.phase += (50.0 + pitchDrop) / sampleRate;
                            if (note.phase >= 1.0) note.phase -= 1.0;
                            out = fastSin(note.phase);
                        }
                    } else if (noteNum == 38 || noteNum == 40) { // Snare
                        if (time < 0.15) {
                            double noiseEnv = Math.exp(-time * 20.0);
                            note.phase += 200.0 / sampleRate;
                            if (note.phase >= 1.0) note.phase -= 1.0;
                            double tone = fastSin(note.phase) * Math.exp(-time * 10.0) * 0.4;
                            double noise = (fastRandom() * 2.0 - 1.0) * noiseEnv * 1.5;
                            out = Math.max(-1.0, Math.min(1.0, tone + noise));
                        }
                    } else if (noteNum == 42 || noteNum == 44 || noteNum == 46 || noteNum >= 49) { // Hi-Hat / Cymbal
                        double duration = (noteNum >= 49) ? 0.3 : 0.05;
                        if (time < duration) {
                            double env = Math.exp(-time * (1.0 / duration) * 5.0);
                            out = (fastRandom() > 0.5 ? 1.5 : -1.5) * env;
                        }
                    } else { // Toms
                        if (time < 0.25) {
                            double pitchDrop = 300.0 * Math.exp(-time * 15.0);
                            note.phase += (80.0 + pitchDrop) / sampleRate;
                            if (note.phase >= 1.0) note.phase -= 1.0;
                            out = fastSin(note.phase);
                        }
                    }
                } else {
                    // Pure 2-OP FM Synthesis (Melody)
                    double decay = Math.max(0.0, 1.0 - (time / 0.5));
                    double modFreq = note.frequency * fmRatio;
                    note.modPhase += modFreq / sampleRate;
                    if (note.modPhase >= 1.0) note.modPhase -= 1.0;
                    double modulator = fastSin(note.modPhase);
                    
                    double envIndex = (fmIndex * 0.1) + (fmIndex * decay); 
                    double instFreq = note.frequency + (modulator * envIndex * note.frequency);
                    
                    note.phase += instFreq / sampleRate;
                    if (note.phase >= 1.0) note.phase -= 1.0;
                    out = fastSin(note.phase);
                }
                note.cachedSample = out;
            }
            
            // 2. The True Hardware Loop: PWM Conversion FIRST, then XOR Multiplexing
            // This perfectly preserves the volume envelope of the FM signal inside the 1-bit duty cycle,
            // avoiding the "white noise crush" that happens when you XOR pure square/delta waves.
            double sumPwm = 0.0;
            for (int o = 0; o < oversample; o++) {
                pwmCarrierPhase += pwmCarrierStep / oversample;
                if (pwmCarrierPhase > 1.0) pwmCarrierPhase -= 2.0;
                
                boolean mixedXor = false;
                boolean firstNote = true;
                
                for (int i = 0; i < assignedNotes.size(); i++) {
                    // TRUE HARDWARE FLOW: Convert Analog to PWM first!
                    boolean pwmBit = assignedNotes.get(i).cachedSample > pwmCarrierPhase;
                    
                    // Then XOR the PWM streams together
                    if (firstNote) {
                        mixedXor = pwmBit;
                        firstNote = false;
                    } else {
                        mixedXor ^= pwmBit;
                    }
                }
                
                // The final output of the physical Apple II pin (-1.0 or 1.0)
                sumPwm += (mixedXor ? 1.0 : -1.0);
            }
            
            return sumPwm / oversample;
        }
    }

    private final AppleIICore[] cores = new AppleIICore[NUM_SPEAKERS];
    private final List<List<ActiveNote>> coreAssignments;
    {
        coreAssignments = new ArrayList<>(NUM_SPEAKERS);
        for (int i = 0; i < NUM_SPEAKERS; i++) {
            coreAssignments.add(new ArrayList<>(8)); // Allocate enough capacity
        }
    }

    public BeepSynthProvider(NativeAudioEngine audio, int voices, double fmRatio, double fmIndex, int oversample) {
        this.audio = audio;
        this.voicesPerCore = Math.max(1, Math.min(4, voices));
        this.fmRatio = fmRatio;
        this.fmIndex = fmIndex;
        this.oversample = Math.max(1, oversample);
        for (int i = 0; i < NUM_SPEAKERS; i++) {
            cores[i] = new AppleIICore(sampleRate);
        }
    }

    @Override
    public List<MidiPort> getOutputPorts() {
        int maxPolyphony = NUM_SPEAKERS * voicesPerCore;
        return List.of(new MidiPort(0, String.format("[%d-Core, %d-Voice] Apple II 1-Bit FM Cluster", NUM_SPEAKERS, maxPolyphony)));
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
        for (int i = 0; i < NUM_SPEAKERS; i++) coreAssignments.get(i).clear();
        
        // Route notes to cores. Cores 6 & 7 are reserved for Rhythm/Drums.
        int melodyIdx = 0, drumIdx = 0;
        for (ActiveNote note : notes) {
            if (note.isDrum) {
                int target = 6 + (drumIdx % 2);
                if (coreAssignments.get(target).size() < voicesPerCore) coreAssignments.get(target).add(note);
                drumIdx++;
            } else {
                int target = melodyIdx % 6;
                if (coreAssignments.get(target).size() < voicesPerCore) coreAssignments.get(target).add(note);
                melodyIdx++;
            }
        }
        
        for (int i = 0; i < frames; i++) {
            // Hardware Gathering: Collect 1-bit outputs from 8 machines
            double sumOfAppleIIs = 0.0;
            for (int s = 0; s < NUM_SPEAKERS; s++) {
                sumOfAppleIIs += cores[s].render(coreAssignments.get(s));
            }
            for (ActiveNote n : notes) n.activeFrames++;
            
            // Analog Mixing Console: Sum the voltages, add slight headroom
            double analogMix = (sumOfAppleIIs / NUM_SPEAKERS) * 0.8; 
            
            // Master Acoustic Filtering (2.25-inch paper cones)
            double filterCutoff = 0.25; 
            lpfState += filterCutoff * (analogMix - lpfState);
            lpfState2 += filterCutoff * (lpfState - lpfState2);
            
            buffer[i] = (short) (lpfState2 * 15000);
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
                            n.frequency = 440.0 * Math.pow(2.0, (note - 69) / 12.0);
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
                if (cc == 123 || cc == 120) { 
                    for (int i = 0; i < MAX_POLYPHONY; i++) activeNotes[i].active = false;
                }
            }
        }
    }
}
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
    private final String mode;
    private final int sampleRate = 44100;
    private final int oversample;
    
    private @Nullable Thread renderThread;
    private volatile boolean running = false;
    private volatile boolean renderPaused = false;

    // Universal DAC522 / PC Speaker output filter
    private static class ActiveNote {
        int channel;
        int note;
        double frequency;
        double phase = 0.0;     // Used as Carrier Phase
        double modPhase = 0.0;  // Used as Modulator Phase
        long activeFrames = 0; // For envelope/decay tracking
        boolean isDrum = false;
    }
    private final List<ActiveNote> activeNotes = new ArrayList<>(64); // Pre-allocate capacity to avoid resizing

    private double errorAccumulator = 0.0;

    // --- FAST SINE WAVE LOOKUP TABLE (To fix CPU Bottleneck & Tempo Desync) ---
    private static final int SINE_LUT_SIZE = 4096;
    private static final double[] SINE_LUT = new double[SINE_LUT_SIZE];
    static {
        for (int i = 0; i < SINE_LUT_SIZE; i++) {
            SINE_LUT[i] = Math.sin((i / (double) SINE_LUT_SIZE) * 2.0 * Math.PI);
        }
    }
    
    // Lightning-fast sine approximation using pre-computed table
    // phase must be in range [0.0, 1.0)
    private static double fastSin(double phase) {
        int index = (int) (phase * SINE_LUT_SIZE);
        // Safety bound check
        if (index < 0) index = 0;
        if (index >= SINE_LUT_SIZE) index = SINE_LUT_SIZE - 1;
        return SINE_LUT[index];
    }

    private double lpfState = 0.0; private double lpfState2 = 0.0; // Acoustic filter state
    private static final int NUM_SPEAKERS = 8;
    private static final int MAX_NOTES_PER_SPEAKER = 2;
    
    // ---------------------------------------------------------
    // Electric Sixteentet (XOR Ring Modulation)
    // ---------------------------------------------------------
    private class SixteentetSpeaker {
        double phase1 = 0.0;
        double phase2 = 0.0;
        
        double render(List<ActiveNote> assignedNotes) {
            if (assignedNotes.isEmpty()) return 0.0;
            
            ActiveNote n1 = assignedNotes.get(0);
            double lfo1 = fastSin((n1.activeFrames * 6.0 / sampleRate) % 1.0);
            double modFreq1 = n1.frequency * (1.0 + lfo1 * 0.015);
            double sweep1 = fastSin((n1.activeFrames * 1.5 / sampleRate) % 1.0);
            double duty1 = 0.5 + (sweep1 * 0.4);
            
            phase1 += modFreq1 / sampleRate;
            if (phase1 >= 1.0) phase1 -= 1.0;
            boolean sq1 = phase1 < duty1;
            
            if (assignedNotes.size() > 1) {
                ActiveNote n2 = assignedNotes.get(1);
                double lfo2 = fastSin(((n2.activeFrames * 6.2 / sampleRate) + (1.0 / (2.0 * Math.PI))) % 1.0);
                double modFreq2 = n2.frequency * (1.0 + lfo2 * 0.015);
                double sweep2 = fastSin(((n2.activeFrames * 1.1 / sampleRate) + 0.25) % 1.0); 
                double duty2 = 0.5 + (sweep2 * 0.35);
                
                phase2 += modFreq2 / sampleRate;
                if (phase2 >= 1.0) phase2 -= 1.0;
                boolean sq2 = phase2 < duty2;
                
                return (sq1 ^ sq2) ? 1.0 : -1.0;
            } else {
                return sq1 ? 1.0 : -1.0;
            }
        }
    }

    // ---------------------------------------------------------
    // 1-Bit FM Arpeggiator (DAC522 Reality Mode)
    // ---------------------------------------------------------
    private class FmArpeggiatorSpeaker {
        int arpeggioIndex = 0;
        int framesSinceSwitch = 0;

        double render(List<ActiveNote> assignedNotes, int framesPerSwitch) {
            if (assignedNotes.isEmpty()) return 0.0;
            if (arpeggioIndex >= assignedNotes.size()) arpeggioIndex = 0;
            
            // WE MUST ADVANCE THE PHASE OF ALL NOTES CONTINUOUSLY!
            // If we only advance the phase of the currently sounding note, its effective 
            // frequency is divided by the number of notes (because it freezes when not selected).
            double analogFm = 0.0;
            
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
                            out = fastSin(note.phase);
                        }
                    } else if (noteNum == 38 || noteNum == 40) { // Snare
                        if (time < 0.15) {
                            double noiseEnv = Math.exp(-time * 20.0);
                            note.phase += 200.0 / sampleRate;
                            double tone = fastSin(note.phase) * Math.exp(-time * 10.0) * 0.4;
                            double noise = (Math.random() * 2.0 - 1.0) * noiseEnv * 1.5;
                            out = Math.max(-1.0, Math.min(1.0, tone + noise));
                        }
                    } else if (noteNum == 42 || noteNum == 44 || noteNum == 46 || noteNum >= 49) { // Hi-Hat / Cymbal
                        double duration = (noteNum >= 49) ? 0.3 : 0.05;
                        if (time < duration) {
                            double env = Math.exp(-time * (1.0 / duration) * 5.0);
                            out = (Math.random() > 0.5 ? 1.5 : -1.5) * env;
                        }
                    } else { // Toms
                        if (time < 0.25) {
                            double pitchDrop = 300.0 * Math.exp(-time * 15.0);
                            note.phase += (80.0 + pitchDrop) / sampleRate;
                            out = fastSin(note.phase);
                        }
                    }
                } else {
                    // 2-OP FM Synthesis (Melody)
                    double decay = Math.max(0.0, 1.0 - (time / 0.5));
                    double modFreq = note.frequency * 1.0;
                    
                    note.modPhase += modFreq / sampleRate;
                    if (note.modPhase >= 1.0) note.modPhase -= 1.0;
                    double modulator = fastSin(note.modPhase);
                    
                    double modIndex = 0.1 + (1.1 * decay); 
                    double instFreq = note.frequency + (modulator * modIndex * note.frequency);
                    
                    note.phase += instFreq / sampleRate;
                    if (note.phase >= 1.0) note.phase -= 1.0;
                    out = fastSin(note.phase);
                }
                
                // Only output the sound of the currently selected arpeggiator slot
                if (i == arpeggioIndex) {
                    analogFm = out;
                }
            }

            // If there's only 1 note, DO NOT trigger the arpeggiator switching logic.
            // This prevents the 50Hz LFO/Tremolo artifacts on single pure notes.
            if (assignedNotes.size() > 1) {
                framesSinceSwitch++;
                if (framesSinceSwitch >= framesPerSwitch) {
                    framesSinceSwitch = 0;
                    arpeggioIndex++;
                }
            } else {
                arpeggioIndex = 0; // Lock to the first note
                framesSinceSwitch = 0;
            }
            
            return analogFm;
        }
    }

    private final SixteentetSpeaker[] speakers = new SixteentetSpeaker[NUM_SPEAKERS];
    private final FmArpeggiatorSpeaker[] fmSpeakers = new FmArpeggiatorSpeaker[NUM_SPEAKERS];

    // Pre-allocated routing buffers to prevent GC stutter
    private final List<List<ActiveNote>> fmSpeakerAssignments;
    private final List<List<ActiveNote>> duetSpeakerAssignments;
    {
        fmSpeakerAssignments = new ArrayList<>(NUM_SPEAKERS);
        duetSpeakerAssignments = new ArrayList<>(NUM_SPEAKERS);
        for (int i = 0; i < NUM_SPEAKERS; i++) {
            fmSpeakerAssignments.add(new ArrayList<>(4));
            duetSpeakerAssignments.add(new ArrayList<>(4));
        }
    }
    private final int framesPerSwitch = sampleRate / 10; // 10 Hz slow arpeggio (Diagnostic speed)

    public BeepSynthProvider(NativeAudioEngine audio, String mode, int oversample) {
        this.audio = audio;
        this.mode = mode.toLowerCase(java.util.Locale.ROOT);
        this.oversample = oversample;
        for (int i = 0; i < NUM_SPEAKERS; i++) {
            speakers[i] = new SixteentetSpeaker();
            fmSpeakers[i] = new FmArpeggiatorSpeaker();
        }
    }

    @Override
    public List<MidiPort> getOutputPorts() {
        String name = "Electric Sixteentet";
        if (mode.equals("pwm")) name = "PWM";
        else if (mode.equals("fm")) name = "FM Arpeggiator (DAC522 Reality)";
        return List.of(new MidiPort(0, "Midiraja 1-Bit " + name));
    }

    @Override
    public void openPort(int portIndex) throws Exception {
        audio.init(sampleRate, 1, 4096);
        startRenderThread();
    }

    @Override
    public void loadSoundbank(String path) throws Exception {}

    @Override
    public void sendMessage(byte[] data) throws Exception {
        if (data.length > 0) {
            int cmd = data[0] & 0xF0;
            int ch = data[0] & 0x0F;
            handleMessage(ch, cmd, data);
        }
    }

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
                List<ActiveNote> currentNotes = new ArrayList<>(32);
                
                // Extremely fast, synchronized snapshot of notes to prevent sequencer blockage
                synchronized (activeNotes) {
                    // Aggressive Garbage Collection done IN PLACE
                    activeNotes.removeIf(n -> (n.isDrum && n.activeFrames > sampleRate * 0.2) || 
                                              (!n.isDrum && n.activeFrames > sampleRate * 3.0));
                                              
                    // Copy references for thread-safe rendering
                    currentNotes.addAll(activeNotes);
                }

                if (currentNotes.isEmpty()) {
                    for (int i = 0; i < framesToRender; i++) pcmBuffer[i] = 0;
                } else if (mode.equals("pwm")) {
                    renderPwm(currentNotes, pcmBuffer, framesToRender);
                } else if (mode.equals("fm")) {
                    renderFm(currentNotes, pcmBuffer, framesToRender);
                } else {
                    renderDuet(currentNotes, pcmBuffer, framesToRender);
                }
                audio.push(pcmBuffer);
            }
        });
        renderThread.setPriority(Thread.MAX_PRIORITY);
        renderThread.setDaemon(true);
        renderThread.start();
    }

    private void renderFm(List<ActiveNote> notes, short[] buffer, int frames) {
        // Clear pre-allocated routing buffers to avoid GC pauses
        for (int i = 0; i < NUM_SPEAKERS; i++) fmSpeakerAssignments.get(i).clear();
        
        int melodyIdx = 0, drumIdx = 0;
        for (ActiveNote note : notes) {
            if (note.isDrum) {
                int target = 6 + (drumIdx % 2);
                if (fmSpeakerAssignments.get(target).size() < 2) fmSpeakerAssignments.get(target).add(note);
                drumIdx++;
            } else {
                int target = melodyIdx % 6;
                if (fmSpeakerAssignments.get(target).size() < 2) fmSpeakerAssignments.get(target).add(note);
                melodyIdx++;
            }
        }

        for (int i = 0; i < frames; i++) {
            double analogSum = 0.0;
            for (int s = 0; s < NUM_SPEAKERS; s++) {
                analogSum += fmSpeakers[s].render(fmSpeakerAssignments.get(s), framesPerSwitch);
            }
            // Aggressive Volume scaling to prevent clipping
            double safeMix = (analogSum / NUM_SPEAKERS) * 0.7;
            safeMix = Math.max(-0.95, Math.min(0.95, safeMix));
            
            // Age all notes perfectly smoothly
            for (ActiveNote n : notes) n.activeFrames++;
            
            // --- TRUE 1-BIT CONVERSION (First-Order Delta-Sigma) ---
            // Instead of comparing against an 18.6kHz sawtooth carrier (which causes 
            // massive intermodulation distortion when paired with complex FM harmonics),
            // we use a mathematically pure Error Accumulator. This perfectly preserves 
            // the analog volume/timbre without generating harsh high-frequency sizzle.
            
            // --- OVERSAMPLED 1-BIT DELTA-SIGMA MODULATION ---
            // We run the error accumulator N times per sample. 
            // High oversampling (32x) pushes switching noise into ultrasonic frequencies.
            // Low oversampling (1x) leaves the noise in the audible range for retro authenticity.
            double target = safeMix;
            double sumOutput = 0.0;
            for (int o = 0; o < oversample; o++) {
                double outputBit = (target + errorAccumulator) > 0.0 ? 1.0 : -1.0;
                errorAccumulator += (target - outputBit);
                sumOutput += outputBit;
            }
            double finalOutput = sumOutput / oversample;
            
            // 2-Pole Acoustic Filtering (Steep Low-Pass)
            double filterCutoff = 0.25; 
            lpfState += filterCutoff * (finalOutput - lpfState);
            lpfState2 += filterCutoff * (lpfState - lpfState2);
            
            // Output to 16-bit PCM buffer
            buffer[i] = (short) (lpfState2 * 9000);
        }
    }

    private void renderPwm(List<ActiveNote> notes, short[] buffer, int frames) {
        double volumeScale = 1.0 / Math.max(1, notes.size());
        for (int i = 0; i < frames; i++) {
            double mixedSample = 0.0;
            for (ActiveNote n : notes) {
                n.phase += n.frequency / sampleRate;
                if (n.phase >= 1.0) n.phase -= 1.0;
                mixedSample += (n.phase < 0.5 ? 1.0 : -1.0);
                n.activeFrames++;
            }
            double target = mixedSample * volumeScale;
            double outputBit = (target + errorAccumulator) > 0.0 ? 1.0 : -1.0;
            errorAccumulator += (target - outputBit);
            buffer[i] = (short) (outputBit * 8000);
        }
    }

    private void renderDuet(List<ActiveNote> notes, short[] buffer, int frames) {
        // Clear pre-allocated routing buffers
        for (int i = 0; i < NUM_SPEAKERS; i++) duetSpeakerAssignments.get(i).clear();
        
        int noteIdx = 0;
        for (ActiveNote note : notes) {
            int targetSpeaker = noteIdx % NUM_SPEAKERS;
            if (duetSpeakerAssignments.get(targetSpeaker).size() < MAX_NOTES_PER_SPEAKER) {
                duetSpeakerAssignments.get(targetSpeaker).add(note);
            }
            noteIdx++;
        }
        for (int i = 0; i < frames; i++) {
            double analogSum = 0.0;
            for (int s = 0; s < NUM_SPEAKERS; s++) {
                analogSum += speakers[s].render(duetSpeakerAssignments.get(s));
            }
            buffer[i] = (short) ((analogSum / NUM_SPEAKERS) * 8000);
            for (ActiveNote n : notes) n.activeFrames++;
        }
    }

    @Override
    public void closePort() {
        running = false;
        if (renderThread != null) renderThread.interrupt();
    }

    @Override
    public void prepareForNewTrack(javax.sound.midi.Sequence seq) {
        if (audio == null) return;
        renderPaused = true;
        audio.flush();
        synchronized(activeNotes) { activeNotes.clear(); }
        errorAccumulator = 0.0;
    }

    @Override public void onPlaybackStarted() { renderPaused = false; }

    private void handleMessage(int ch, int cmd, byte[] data) {
        if (cmd == 0x90 && data.length >= 3) {
            int note = data[1] & 0xFF;
            int velocity = data[2] & 0xFF;
            if (velocity > 0) {
                ActiveNote an = new ActiveNote();
                an.channel = ch; an.note = note;
                an.frequency = 440.0 * Math.pow(2.0, (note - 69) / 12.0);
                an.isDrum = (ch == 9);
                activeNotes.add(an);
            } else {
                activeNotes.removeIf(n -> n.channel == ch && n.note == note);
            }
        } else if (cmd == 0x80) {
            int note = data[1] & 0xFF;
            activeNotes.removeIf(n -> n.channel == ch && n.note == note);
        }
    }
}

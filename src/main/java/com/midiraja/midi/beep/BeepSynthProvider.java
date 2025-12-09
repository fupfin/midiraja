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

    private static class ActiveNote {
        volatile boolean active = false;
        int channel;
        int note;
        double frequency;
        double phase = 0.0;
        double modPhase = 0.0;
        long activeFrames = 0;
        boolean isDrum = false;
        
        void reset() {
            phase = 0.0;
            modPhase = 0.0;
            activeFrames = 0;
        }
    }
    
    private static final int MAX_POLYPHONY = 128;
    private final ActiveNote[] activeNotes = new ActiveNote[MAX_POLYPHONY];
    {
        for (int i = 0; i < MAX_POLYPHONY; i++) {
            activeNotes[i] = new ActiveNote();
        }
    }

    // --- FAST SINE WAVE LOOKUP TABLE ---
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

    // --- FAST RANDOM NUMBER GENERATOR ---
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
    private static final int MAX_NOTES_PER_SPEAKER = 2;
    
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

    private class FmArpeggiatorSpeaker {
        private int arpeggioIndex = 0;
        private int framesSinceSwitch = 0;
        private final int framesPerSwitch;
        private double pwmCarrierPhase = -1.0;
        private final double pwmCarrierStep;
        
        FmArpeggiatorSpeaker(int sampleRate) {
            this.framesPerSwitch = sampleRate / 50;
            this.pwmCarrierStep = (22050.0 / sampleRate) * 2.0;
        }

        double render(List<ActiveNote> assignedNotes) {
            if (assignedNotes.isEmpty()) return 0.0;
            
            // 1. Arpeggiator Logic: Pick which note to play
            if (assignedNotes.size() > 1) {
                framesSinceSwitch++;
                if (framesSinceSwitch >= framesPerSwitch) {
                    framesSinceSwitch = 0;
                    arpeggioIndex = (arpeggioIndex + 1) % assignedNotes.size();
                }
            } else {
                arpeggioIndex = 0;
            }
            
            double targetAnalogValue = 0.0;
            
            // 2. Continuous Phase Advancement for all notes in this core
            for (int i = 0; i < assignedNotes.size(); i++) {
                ActiveNote note = assignedNotes.get(i);
                double time = note.activeFrames / (double) sampleRate;
                double out = 0.0;
                if (note.isDrum) {
                    int noteNum = note.note;
                    if (noteNum == 35 || noteNum == 36) {
                        if (time < 0.2) {
                            double pitchDrop = 150.0 * Math.exp(-time * 30.0);
                            note.phase += (50.0 + pitchDrop) / sampleRate;
                            if (note.phase >= 1.0) note.phase -= 1.0;
                            out = fastSin(note.phase);
                        }
                    } else if (noteNum == 38 || noteNum == 40) {
                        if (time < 0.15) {
                            double noiseEnv = Math.exp(-time * 20.0);
                            note.phase += 200.0 / sampleRate;
                            if (note.phase >= 1.0) note.phase -= 1.0;
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
                            note.phase += (80.0 + pitchDrop) / sampleRate;
                            if (note.phase >= 1.0) note.phase -= 1.0;
                            out = fastSin(note.phase);
                        }
                    }
                } else {
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
                
                // Select the note for PWM conversion
                if (i == arpeggioIndex) targetAnalogValue = out;
            }
            
            // 3. DAC522 1-Bit PWM Simulation per Speaker
            // Use the global oversample factor to simulate different levels of CPU precision.
            // 1 = Original harsh aliasing, 32 = Modern clean emulation.
            double sumPwm = 0.0;
            for (int o = 0; o < oversample; o++) {
                pwmCarrierPhase += pwmCarrierStep / oversample;
                if (pwmCarrierPhase > 1.0) pwmCarrierPhase -= 2.0;
                sumPwm += (targetAnalogValue > pwmCarrierPhase ? 1.0 : -1.0);
            }
            return sumPwm / oversample;
        }
    }

    private final SixteentetSpeaker[] speakers = new SixteentetSpeaker[NUM_SPEAKERS];
    private final FmArpeggiatorSpeaker[] fmSpeakers = new FmArpeggiatorSpeaker[NUM_SPEAKERS];
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

    public BeepSynthProvider(NativeAudioEngine audio, String mode, int oversample) {
        this.audio = audio;
        this.mode = mode.toLowerCase(java.util.Locale.ROOT);
        this.oversample = Math.max(1, oversample);
        for (int i = 0; i < NUM_SPEAKERS; i++) {
            speakers[i] = new SixteentetSpeaker();
            fmSpeakers[i] = new FmArpeggiatorSpeaker(sampleRate);
        }
    }

    @Override
    public List<MidiPort> getOutputPorts() {
        String name = "Electric Sixteentet";
        if (mode.equals("pwm")) name = "PWM";
        else if (mode.equals("fm")) name = "FM Arpeggiator (DAC522 Hardware Mix)";
        return List.of(new MidiPort(0, "Midiraja 1-Bit " + name));
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
        for (int i = 0; i < NUM_SPEAKERS; i++) fmSpeakerAssignments.get(i).clear();
        int melodyIdx = 0, drumIdx = 0;
        for (ActiveNote note : notes) {
            if (note.isDrum) {
                int target = 6 + (drumIdx % 2);
                if (fmSpeakerAssignments.get(target).size() < 3) fmSpeakerAssignments.get(target).add(note);
                drumIdx++;
            } else {
                int target = melodyIdx % 6;
                if (fmSpeakerAssignments.get(target).size() < 3) fmSpeakerAssignments.get(target).add(note);
                melodyIdx++;
            }
        }
        for (int i = 0; i < frames; i++) {
            double sumOfAppleIIs = 0.0;
            for (int s = 0; s < NUM_SPEAKERS; s++) {
                sumOfAppleIIs += fmSpeakers[s].render(fmSpeakerAssignments.get(s));
            }
            for (ActiveNote n : notes) n.activeFrames++;
            double analogMix = (sumOfAppleIIs / NUM_SPEAKERS) * 0.8; 
            double filterCutoff = 0.25; 
            lpfState += filterCutoff * (analogMix - lpfState);
            lpfState2 += filterCutoff * (lpfState - lpfState2);
            buffer[i] = (short) (lpfState2 * 15000);
        }
    }

    private void renderPwm(List<ActiveNote> notes, short[] buffer, int frames) {
        double errorAccum = 0.0;
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
            double outputBit = (target + errorAccum) > 0.0 ? 1.0 : -1.0;
            errorAccum += (target - outputBit);
            buffer[i] = (short) (outputBit * 8000);
        }
    }

    private void renderDuet(List<ActiveNote> notes, short[] buffer, int frames) {
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

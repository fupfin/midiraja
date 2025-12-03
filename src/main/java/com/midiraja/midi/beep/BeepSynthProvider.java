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
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.Nullable;

@SuppressWarnings({"ThreadPriorityCheck", "EmptyCatch"})
public class BeepSynthProvider implements SoftSynthProvider
{
    private final NativeAudioEngine audio;
    private final String mode;
    private final int sampleRate = 44100;
    
    private @Nullable Thread renderThread;
    private volatile boolean running = false;
    private volatile boolean renderPaused = false;

    // Universal DAC522 / PC Speaker output filter
    private PwmAcousticSimulator dspSimulator;

    private static class ActiveNote {
        int channel;
        int note;
        double frequency;
        double phase = 0.0;     // Used as Carrier Phase
        double modPhase = 0.0;  // Used as Modulator Phase
        long activeFrames = 0; // For envelope/decay tracking
        boolean isDrum = false;
    }
    private final List<ActiveNote> activeNotes = new CopyOnWriteArrayList<>();

    private double errorAccumulator = 0.0;
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
            double lfo1 = Math.sin(n1.activeFrames * 2.0 * Math.PI * 6.0 / sampleRate);
            double modFreq1 = n1.frequency * (1.0 + lfo1 * 0.015);
            double sweep1 = Math.sin(n1.activeFrames * 2.0 * Math.PI * 1.5 / sampleRate);
            double duty1 = 0.5 + (sweep1 * 0.4);
            
            phase1 += modFreq1 / sampleRate;
            if (phase1 >= 1.0) phase1 -= 1.0;
            boolean sq1 = phase1 < duty1;
            
            if (assignedNotes.size() > 1) {
                ActiveNote n2 = assignedNotes.get(1);
                double lfo2 = Math.sin((n2.activeFrames * 2.0 * Math.PI * 6.2 / sampleRate) + 1.0);
                double modFreq2 = n2.frequency * (1.0 + lfo2 * 0.015);
                double sweep2 = Math.cos(n2.activeFrames * 2.0 * Math.PI * 1.1 / sampleRate); 
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
            
            ActiveNote currentNote = assignedNotes.get(arpeggioIndex);
            
            double analogFm = 0.0;
            double time = currentNote.activeFrames / (double) sampleRate;

            if (currentNote.isDrum) {
                int noteNum = currentNote.note;
                if (noteNum == 35 || noteNum == 36) { // Kick
                    if (time < 0.2) {
                        double pitchDrop = 150.0 * Math.exp(-time * 30.0);
                        currentNote.phase += (50.0 + pitchDrop) / sampleRate;
                        analogFm = Math.sin(currentNote.phase * 2.0 * Math.PI);
                    }
                } else if (noteNum == 38 || noteNum == 40) { // Snare
                    if (time < 0.15) {
                        double noiseEnv = Math.exp(-time * 20.0);
                        currentNote.phase += 200.0 / sampleRate;
                        double tone = Math.sin(currentNote.phase * 2.0 * Math.PI) * Math.exp(-time * 10.0) * 0.4;
                        double noise = (Math.random() * 2.0 - 1.0) * noiseEnv * 1.5;
                        analogFm = Math.max(-1.0, Math.min(1.0, tone + noise));
                    }
                } else if (noteNum == 42 || noteNum == 44 || noteNum == 46 || noteNum >= 49) { // Hi-Hat / Cymbal
                    double duration = (noteNum >= 49) ? 0.3 : 0.05;
                    if (time < duration) {
                        double env = Math.exp(-time * (1.0 / duration) * 5.0);
                        analogFm = (Math.random() > 0.5 ? 1.5 : -1.5) * env;
                    }
                } else { // Toms
                    if (time < 0.25) {
                        double pitchDrop = 300.0 * Math.exp(-time * 15.0);
                        currentNote.phase += (80.0 + pitchDrop) / sampleRate;
                        analogFm = Math.sin(currentNote.phase * 2.0 * Math.PI);
                    }
                }
            } else {
                // 2-OP FM Synthesis (Melody)
                double decay = Math.max(0.0, 1.0 - (time / 0.5));
                double modFreq = currentNote.frequency * 1.0;
                
                // Track modulator phase per-note
                currentNote.modPhase += modFreq / sampleRate;
                if (currentNote.modPhase >= 1.0) currentNote.modPhase -= 1.0;
                double modulator = Math.sin(currentNote.modPhase * 2.0 * Math.PI);
                
                double modIndex = 0.1 + (1.1 * decay); 
                double instFreq = currentNote.frequency + (modulator * modIndex * currentNote.frequency);
                
                // Track carrier phase per-note! This prevents the clicking/popping when arpeggiating!
                currentNote.phase += instFreq / sampleRate;
                if (currentNote.phase >= 1.0) currentNote.phase -= 1.0;
                analogFm = Math.sin(currentNote.phase * 2.0 * Math.PI);
            }

            framesSinceSwitch++;
            if (framesSinceSwitch >= framesPerSwitch) {
                framesSinceSwitch = 0;
                arpeggioIndex++;
            }
            return analogFm;
        }
    }

    private final SixteentetSpeaker[] speakers = new SixteentetSpeaker[NUM_SPEAKERS];
    private final FmArpeggiatorSpeaker[] fmSpeakers = new FmArpeggiatorSpeaker[NUM_SPEAKERS];
    private final int framesPerSwitch = sampleRate / 40;

    public BeepSynthProvider(NativeAudioEngine audio, String mode, int oversample) {
        this.audio = audio;
        this.mode = mode.toLowerCase(java.util.Locale.ROOT);
        this.dspSimulator = new PwmAcousticSimulator(sampleRate, oversample);
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
                List<ActiveNote> currentNotes = new ArrayList<>(activeNotes);
                activeNotes.removeIf(n -> n.activeFrames > sampleRate * 2.0);

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
        List<List<ActiveNote>> speakerAssignments = new ArrayList<>(NUM_SPEAKERS);
        for (int i = 0; i < NUM_SPEAKERS; i++) speakerAssignments.add(new ArrayList<>());
        
        int melodyIdx = 0, drumIdx = 0;
        for (ActiveNote note : notes) {
            if (note.isDrum) {
                int target = 6 + (drumIdx % 2);
                if (speakerAssignments.get(target).size() < 2) speakerAssignments.get(target).add(note);
                drumIdx++;
            } else {
                int target = melodyIdx % 6;
                if (speakerAssignments.get(target).size() < 2) speakerAssignments.get(target).add(note);
                melodyIdx++;
            }
        }

        float[] analogL = new float[frames];
        float[] analogR = new float[frames];
        for (int i = 0; i < frames; i++) {
            double analogSum = 0.0;
            for (int s = 0; s < NUM_SPEAKERS; s++) {
                analogSum += fmSpeakers[s].render(speakerAssignments.get(s), framesPerSwitch);
            }
            float mixed = (float) (analogSum / NUM_SPEAKERS);
            analogL[i] = mixed; analogR[i] = mixed;
            for (ActiveNote n : notes) n.activeFrames++;
        }
        dspSimulator.process(analogL, analogR, frames);
        for (int i = 0; i < frames; i++) buffer[i] = (short) (analogL[i] * 32767);
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
        List<List<ActiveNote>> speakerAssignments = new ArrayList<>(NUM_SPEAKERS);
        for (int i = 0; i < NUM_SPEAKERS; i++) speakerAssignments.add(new ArrayList<>());
        int noteIdx = 0;
        for (ActiveNote note : notes) {
            int targetSpeaker = noteIdx % NUM_SPEAKERS;
            if (speakerAssignments.get(targetSpeaker).size() < MAX_NOTES_PER_SPEAKER) {
                speakerAssignments.get(targetSpeaker).add(note);
            }
            noteIdx++;
        }
        for (int i = 0; i < frames; i++) {
            double analogSum = 0.0;
            for (int s = 0; s < NUM_SPEAKERS; s++) {
                analogSum += speakers[s].render(speakerAssignments.get(s));
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
        activeNotes.clear();
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

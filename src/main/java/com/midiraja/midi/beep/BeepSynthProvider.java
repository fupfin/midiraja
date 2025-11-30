/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.midiraja.midi.beep;

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

    // Track active notes across all channels
    private static class ActiveNote {
        int channel;
        int note;
        double frequency;
        double phase = 0.0;
        long activeFrames = 0; // For envelope/decay tracking
        boolean isDrum = false;
    }
    private final List<ActiveNote> activeNotes = new CopyOnWriteArrayList<>();

    // 1-bit Delta-Sigma state
    private double errorAccumulator = 0.0;

    // 8-Core "Electric Sixteentet" Apple II cluster
    // Each virtual Apple II uses XOR Ring Modulation to play 2 notes simultaneously on a 1-bit pin.
    private static final int NUM_SPEAKERS = 8;
    private static final int MAX_NOTES_PER_SPEAKER = 2;
    
    private class SixteentetSpeaker {
        double phase1 = 0.0;
        double phase2 = 0.0;
        
        // Duty cycle creates the unique "Timbre" of Electric Sixteentet
        // A slight offset from 0.5 (perfect square) adds harmonic richness
        
        // Output purely -1.0, 0.0 (silent), or 1.0
        double render(List<ActiveNote> assignedNotes) {
            if (assignedNotes.isEmpty()) return 0.0;
            
            // Karateka / Prince of Persia Style Enhancements
            // 1. Vibrato (LFO): Modulate the frequency slightly
            // 2. Duty Sweep (FM-like Timbre): Move duty cycle from 0.1 to 0.9 based on active frames
            
            ActiveNote n1 = assignedNotes.get(0);
            
            // Vibrato LFO: ~6 Hz sine wave, +/- 1.5% frequency modulation
            double lfo1 = Math.sin(n1.activeFrames * 2.0 * Math.PI * 6.0 / sampleRate);
            double modFreq1 = n1.frequency * (1.0 + lfo1 * 0.015);
            
            // Duty Sweep: Sweeps back and forth to create an FM-like "Wah" or shifting timbre
            double sweep1 = Math.sin(n1.activeFrames * 2.0 * Math.PI * 1.5 / sampleRate); // 1.5 Hz sweep
            double duty1 = 0.5 + (sweep1 * 0.4); // Sweeps between 0.1 and 0.9
            
            phase1 += modFreq1 / sampleRate;
            if (phase1 >= 1.0) phase1 -= 1.0;
            boolean sq1 = phase1 < duty1;
            
            if (assignedNotes.size() > 1) {
                ActiveNote n2 = assignedNotes.get(1);
                
                // Vibrato for voice 2 (slightly out of phase for chorus effect)
                double lfo2 = Math.sin((n2.activeFrames * 2.0 * Math.PI * 6.2 / sampleRate) + 1.0);
                double modFreq2 = n2.frequency * (1.0 + lfo2 * 0.015);
                
                // Duty Sweep for voice 2
                double sweep2 = Math.cos(n2.activeFrames * 2.0 * Math.PI * 1.1 / sampleRate); 
                double duty2 = 0.5 + (sweep2 * 0.35); // Sweeps between 0.15 and 0.85
                
                phase2 += modFreq2 / sampleRate;
                if (phase2 >= 1.0) phase2 -= 1.0;
                boolean sq2 = phase2 < duty2;
                
                // XOR Mixing of the two modulated duty-cycle waves!
                boolean output = sq1 ^ sq2; 
                return output ? 1.0 : -1.0;
            } else {
                return sq1 ? 1.0 : -1.0;
            }
        }
        
    }
    

    // ---------------------------------------------------------
    // 1-Bit FM Arpeggiator (DAC522 Style PWM + 2-OP FM Synth)
    // ---------------------------------------------------------
    private class FmArpeggiatorSpeaker {
        int arpeggioIndex = 0;
        int framesSinceSwitch = 0;
        
        // FM Synthesis State
        double carrierPhase = 0.0;
        double modPhase = 0.0;
        long internalFrames = 0;
        @org.jspecify.annotations.Nullable ActiveNote lastNote = null;
        
        // The DAC522 PWM Carrier (e.g. 22.05kHz)
        double pwmCarrierPhase = -1.0;
        final double pwmCarrierStep = (22050.0 / sampleRate) * 2.0;

        // Output -1.0, 0.0, or 1.0 (True PWM Pulse Train)
        double render(List<ActiveNote> assignedNotes, int framesPerSwitch) {
            if (assignedNotes.isEmpty()) {
                // Decay phases to zero to prevent DC offset
                return 0.0; 
            }
            
            if (arpeggioIndex >= assignedNotes.size()) {
                arpeggioIndex = 0;
            }
            
            ActiveNote currentNote = assignedNotes.get(arpeggioIndex);
            if (currentNote != lastNote) {
                lastNote = currentNote;
                internalFrames = currentNote.activeFrames; // Sync
            }
            internalFrames++;
            
            double analogFm = 0.0;
            double time = internalFrames / (double) sampleRate;
            
            if (currentNote.isDrum) {
                // --- OPL-Style Drum Synthesis ---
                int noteNum = currentNote.note;
                
                if (noteNum == 35 || noteNum == 36) {
                    // Kick Drum: Fast pitch drop (Pitch envelope)
                    if (time < 0.2) {
                        double pitchDrop = 150.0 * Math.exp(-time * 30.0); // 150Hz drops quickly to 0
                        double kickFreq = 50.0 + pitchDrop;
                        carrierPhase += kickFreq / sampleRate;
                        analogFm = Math.sin(carrierPhase * 2.0 * Math.PI);
                    }
                } 
                else if (noteNum == 38 || noteNum == 40) {
                    // Snare Drum: Burst of noise + a slight tone
                    
                    if (time < 0.15) {
                        double noiseEnv = Math.exp(-time * 20.0);
                        double toneEnv = Math.exp(-time * 10.0);
                        carrierPhase += 200.0 / sampleRate;
                        double tone = Math.sin(carrierPhase * 2.0 * Math.PI) * toneEnv * 0.4;
                        double noise = (Math.random() * 2.0 - 1.0) * noiseEnv * 1.5; // Boosted noise
                        analogFm = Math.max(-1.0, Math.min(1.0, tone + noise));
                    }
                }
                else if (noteNum == 42 || noteNum == 44 || noteNum == 46 || noteNum == 49 || noteNum == 51 || noteNum == 53) {
                    // Hi-Hat / Cymbal: Very short, high-frequency metallic noise
                    
                    double duration = (noteNum >= 49) ? 0.3 : 0.05; // Cymbals last longer
                    if (time < duration) {
                        double env = Math.exp(-time * (1.0 / duration) * 5.0);
                        // FM metallic noise (high index FM with random phase)
                        analogFm = (Math.random() > 0.5 ? 1.5 : -1.5) * env; // Boosted
                        analogFm = Math.max(-1.0, Math.min(1.0, analogFm));
                    }
                }
                else {
                    // Toms and other percussions: Pitch sweep down
                    
                    if (time < 0.25) {
                        double pitchDrop = 300.0 * Math.exp(-time * 15.0);
                        double tomFreq = 80.0 + pitchDrop;
                        carrierPhase += tomFreq / sampleRate;
                        analogFm = Math.sin(carrierPhase * 2.0 * Math.PI);
                    }
                }
            } else {
                // --- 2-OP FM Synthesis (Melody/Chords) ---
                double decay = Math.max(0.0, 1.0 - (currentNote.activeFrames / (sampleRate * 0.5))); // 0.5s decay
                
                // Modulator: Frequency is 3.5x the carrier (Inharmonic ratio for metallic bell sound)
                double modFreq = currentNote.frequency * 3.5;
                modPhase += modFreq / sampleRate;
                if (modPhase >= 1.0) modPhase -= 1.0;
                double modulator = Math.sin(modPhase * 2.0 * Math.PI);
                
                // Carrier: Modulated by the Modulator. Index sweeps from 6.0 down to 0.5!
                double modulationIndex = 0.5 + (5.5 * decay); 
                double instFreq = currentNote.frequency + (modulator * modulationIndex * currentNote.frequency);
                
                carrierPhase += instFreq / sampleRate;
                
                // The pure analog FM Sine Wave [-1.0 to 1.0]
                analogFm = Math.sin(carrierPhase * 2.0 * Math.PI);
            }
            
            // 2. DAC522 Style True PWM Conversion
            // Compare the analog wave against a high-frequency sawtooth carrier
            pwmCarrierPhase += pwmCarrierStep;
            if (pwmCarrierPhase > 1.0) pwmCarrierPhase -= 2.0;
            
            double pwmOutput = analogFm > pwmCarrierPhase ? 1.0 : -1.0;
            
            // 3. Arpeggiator Advance
            framesSinceSwitch++;
            if (framesSinceSwitch >= framesPerSwitch) {
                framesSinceSwitch = 0;
                arpeggioIndex++;
            }
            
            return pwmOutput;
        }
    }
    private final FmArpeggiatorSpeaker[] fmSpeakers = new FmArpeggiatorSpeaker[NUM_SPEAKERS];
    private final int framesPerSwitch = sampleRate / 40; // 40 Hz fast arpeggio
    private final SixteentetSpeaker[] speakers = new SixteentetSpeaker[NUM_SPEAKERS];
    

    // Pitches for MIDI note numbers
    private static final double[] PITCHES = new double[128];
    static {
        for (int i = 0; i < 128; i++) {
            PITCHES[i] = 440.0 * Math.pow(2.0, (i - 69) / 12.0);
        }
    }

    public BeepSynthProvider(NativeAudioEngine audio, String mode)
    {
        this.audio = audio;
        this.mode = mode.toLowerCase(java.util.Locale.ROOT);
        for (int i = 0; i < NUM_SPEAKERS; i++) {
            speakers[i] = new SixteentetSpeaker();
            fmSpeakers[i] = new FmArpeggiatorSpeaker();
        }
    }

    @Override
    public List<MidiPort> getOutputPorts()
    {
        
        String name = "Electric Sixteentet";
        if (mode.equals("pwm")) name = "PWM";
        else if (mode.equals("fm")) name = "FM Arpeggiator (DAC522)";
        return List.of(new MidiPort(0, "Midiraja 1-Bit " + name));

    }

    @Override
    public void openPort(int portIndex) throws Exception
    {
        audio.init(sampleRate, 1, 4096); // Mono output
        startRenderThread();
    }

    @Override
    public void loadSoundbank(String path) throws Exception
    {
        // Not applicable for mathematical synthesis
    }

    private void startRenderThread()
    {
        running = true;
        renderThread = new Thread(() -> {
            final int framesToRender = 512;
            short[] pcmBuffer = new short[framesToRender]; // Mono

            while (running)
            {
                if (renderPaused)
                {
                    try { Thread.sleep(1); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                    continue;
                }

                // Snap active notes to a local array to prevent modification during loop
                List<ActiveNote> currentNotes = new ArrayList<>(activeNotes);
                // Simple decay: remove notes that have been playing too long to prevent infinite drones
                // (PC speaker has no real velocity envelope, so we just cut them off after ~2 seconds)
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


    private void renderFm(List<ActiveNote> notes, short[] buffer, int frames)
    {
        List<List<ActiveNote>> speakerAssignments = new ArrayList<>(NUM_SPEAKERS);
        for (int i = 0; i < NUM_SPEAKERS; i++) {
            speakerAssignments.add(new ArrayList<>());
        }
        
        // Dedicated Drum Routing (Like AdLib Rhythm Mode)
        // Speakers 0~5: Melody (Arpeggiated)
        // Speakers 6~7: Drums Only (Strict)
        int melodyIdx = 0;
        int drumIdx = 0;
        
        for (ActiveNote note : notes) {
            if (note.isDrum) {
                // Route to Speakers 6 and 7
                int target = 6 + (drumIdx % 2);
                if (speakerAssignments.get(target).size() < 2) {
                    speakerAssignments.get(target).add(note);
                }
                drumIdx++;
            } else {
                // Route to Speakers 0 to 5
                int target = melodyIdx % 6;
                if (speakerAssignments.get(target).size() < 2) {
                    speakerAssignments.get(target).add(note);
                }
                melodyIdx++;
            }
        }

        for (int i = 0; i < frames; i++) {
            double analogSum = 0.0;
            for (int s = 0; s < NUM_SPEAKERS; s++) {
                analogSum += fmSpeakers[s].render(speakerAssignments.get(s), framesPerSwitch);
            }
            double mixed = analogSum / NUM_SPEAKERS;
            buffer[i] = (short) (mixed * 8000); 
        }
        for (ActiveNote n : notes) n.activeFrames += frames;
    }
    private void renderPwm(List<ActiveNote> notes, short[] buffer, int frames)
    {
        double volumeScale = 1.0 / Math.max(1, notes.size());
        
        for (int i = 0; i < frames; i++) {
            double mixedSample = 0.0;
            
            for (ActiveNote n : notes) {
                // Generate square wave
                n.phase += n.frequency / sampleRate;
                if (n.phase >= 1.0) n.phase -= 1.0;
                double square = n.phase < 0.5 ? 1.0 : -1.0;
                mixedSample += square;
                n.activeFrames++;
            }
            
            // Normalize sum to roughly [-1.0, 1.0]
            mixedSample *= volumeScale;

            // 1-Bit Quantization via First-Order Delta-Sigma Modulator
            double target = mixedSample;
            double outputBit = (target + errorAccumulator) > 0.0 ? 1.0 : -1.0;
            errorAccumulator += (target - outputBit);

            // Output purely 1 or -1 (scaled to 16-bit max amplitude)
            buffer[i] = (short) (outputBit * 8000); // 8000 instead of 32767 to save ears!
        }
    }

    private void renderDuet(List<ActiveNote> notes, short[] buffer, int frames)
    {
        List<List<ActiveNote>> speakerAssignments = new ArrayList<>(NUM_SPEAKERS);
        for (int i = 0; i < NUM_SPEAKERS; i++) {
            speakerAssignments.add(new ArrayList<>());
        }
        
        // Distribute notes across the 8 Apple IIs (Max 2 notes per machine)
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
            
            // Normalize the analog sum of 8 speakers [-8.0, 8.0]
            double mixed = analogSum / NUM_SPEAKERS;
            
            buffer[i] = (short) (mixed * 8000); // Master volume scaled safely
        }
        
        for (ActiveNote n : notes) n.activeFrames += frames;
    }
    @Override
    public void prepareForNewTrack(javax.sound.midi.Sequence sequence)
    {
        if (audio == null) return;
        renderPaused = true;
        try { Thread.sleep(20); } catch (InterruptedException ignored) {}
        audio.flush();
        activeNotes.clear();
        errorAccumulator = 0.0;
    }

    @Override
    public void onPlaybackStarted() { renderPaused = false; }

    @Override
    public void sendMessage(byte[] data)
    {
        if (data == null || data.length < 1) return;
        int status = data[0] & 0xFF;
        int cmd = status & 0xF0;
        int ch = status & 0x0F;

        if (cmd == 0x90 && data.length >= 3)
        {
            int note = data[1] & 0xFF;
            int velocity = data[2] & 0xFF;
            if (velocity > 0) noteOn(ch, note);
            else noteOff(ch, note);
        }
        else if (cmd == 0x80 && data.length >= 3)
        {
            noteOff(ch, data[1] & 0xFF);
        }
    }

    private void noteOn(int channel, int note)
    {
        if (channel == 9) return; // Ignore drums for pure 1-bit beep

        // Remove existing note to re-trigger
        activeNotes.removeIf(n -> n.channel == channel && n.note == note);

        ActiveNote an = new ActiveNote();
        an.channel = channel;
        an.note = note;
        an.frequency = PITCHES[note];
        activeNotes.add(an);
        
        // Limit total active notes to 8 to prevent complete chaos
        if (activeNotes.size() > 18) {
            activeNotes.remove(0);
        }
    }

    private void noteOff(int channel, int note)
    {
        activeNotes.removeIf(n -> n.channel == channel && n.note == note);
    }

    @Override
    public void panic() { activeNotes.clear(); if (audio != null) audio.flush(); }

    @Override
    public void closePort()
    {
        running = false;
        if (renderThread != null)
        {
            renderThread.interrupt();
            try { renderThread.join(500); } catch (InterruptedException ignored) {}
        }
        if (audio != null) audio.close();
        activeNotes.clear();
    }
}

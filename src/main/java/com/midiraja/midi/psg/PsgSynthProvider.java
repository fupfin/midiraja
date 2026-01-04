/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE
 * file in the root directory of this source tree.
 */

package com.midiraja.midi.psg;

import com.midiraja.midi.AudioEngine;
import com.midiraja.midi.MidiPort;
import com.midiraja.midi.SoftSynthProvider;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A Programmable Sound Generator (PSG) emulator based on the AY-3-8910 / YM2149F.
 * It implements a "Tracker-Driven Interception Layer" that applies 1980s demoscene
 * software hacks (Fast Arpeggios, Audio-Rate Hardware Envelopes) to modern MIDI files.
 */
@SuppressWarnings({"ThreadPriorityCheck", "EmptyCatch"})
public class PsgSynthProvider implements SoftSynthProvider
{
    private final AudioEngine audio;
    private final int sampleRate = 44100;
    
    private @Nullable Thread renderThread;
    private volatile boolean running = false;
    private volatile boolean renderPaused = false;
    
    // --- TRACKER STATE ---
    private static final int NUM_CHANNELS = 3;
    
    private static class PsgChannel
    {
        // Tracker Logic State
        int midiChannel = -1;
        int midiNote = -1;
        boolean active = false;
        long activeFrames = 0; // To track 50Hz ticks
        
        // 4-Bit Software Envelope State (0 to 15)
        int volume15 = 0;
        
        // Arpeggio Queue (up to 4 notes for a fake chord)
        int[] arpNotes = new int[4];
        int arpSize = 0;
        int arpIndex = 0;
        
        // Hardware State
        double baseFrequency = 0.0;
        int phase16 = 0;
        int phaseStep16 = 0;
        boolean isNoise = false; // Interleaved Noise flag
        
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
        }
    }
    
    private final PsgChannel[] channels = new PsgChannel[NUM_CHANNELS];
    {
        for (int i = 0; i < NUM_CHANNELS; i++) channels[i] = new PsgChannel();
    }
    
    // Global Hardware Envelope (Buzzer)
    private int hwEnvPhase16 = 0;
    private int hwEnvStep16 = 0;
    private boolean hwEnvActive = false;
    
    // Noise Generator (LFSR)
    private int lfsr = 1;
    private int noisePhase16 = 0;
    private int noiseStep16 = 0; // Noise pitch
    
    public PsgSynthProvider(AudioEngine audio)
    {
        this.audio = audio;
    }

    @Override
    public List<MidiPort> getOutputPorts()
    {
        return List.of(new MidiPort(0, "AY-3-8910 (Tracker Hacks Mode)"));
    }

    @Override
    public void openPort(int portIndex) throws Exception
    {
        audio.init(sampleRate, 1, 4096);
        startRenderThread();
    }

    @Override public void loadSoundbank(String path) throws Exception {}

    private void startRenderThread()
    {
        running = true;
        renderThread = new Thread(() ->
        {
            final int framesToRender = 512;
            short[] pcmBuffer = new short[framesToRender];
            
            // 4-Bit DAC translation table (Non-linear logarithmic output like real hardware)
            double[] dacTable = new double[16];
            for (int i = 0; i < 16; i++) {
                dacTable[i] = Math.pow(10.0, (i - 15) * 1.5 / 20.0);
            }
            dacTable[0] = 0.0;

            while (running)
            {
                if (renderPaused)
                {
                    try { Thread.sleep(1); } catch (InterruptedException e) { break; }
                    continue;
                }
                
                for (int i = 0; i < framesToRender; i++)
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
                            if (c.arpSize > 1) {
                                c.arpIndex = (c.arpIndex + 1) % c.arpSize;
                                c.baseFrequency = 440.0 * Math.pow(2.0, (c.arpNotes[c.arpIndex] - 69) / 12.0);
                                c.phaseStep16 = (int) ((c.baseFrequency * 65536.0) / sampleRate);
                            } else if (c.baseFrequency > 0.0) {
                                // --- HACK 5: SOFTWARE VIBRATO (LFO) ---
                                // If it's a sustained single note, the tracker wiggles the pitch!
                                // 6Hz vibrato frequency, starting after a slight delay (approx 10 ticks = 0.2s)
                                if (c.activeFrames > 10 * 882) {
                                    double lfoTime = (c.activeFrames / (double) sampleRate);
                                    // Math.sin is a heavy float operation, but we only do it at 50Hz, so it's extremely cheap!
                                    double lfo = Math.sin(lfoTime * 6.0 * 2.0 * Math.PI);
                                    double vibratoFreq = c.baseFrequency * (1.0 + (0.01 * lfo)); // ~17 cents depth
                                    c.phaseStep16 = (int) ((vibratoFreq * 65536.0) / sampleRate);
                                }
                            }
                            
                            // Decrement volume every 4 tracker ticks to extend sustain
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
                        boolean toneBit = c.phase16 > 32767;
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
                    pcmBuffer[i] = (short) (Math.max(-1.0, Math.min(1.0, sumOutput)) * 32767);
                }
                audio.push(pcmBuffer);
            }
        });
        renderThread.setPriority(Thread.MAX_PRIORITY);
        renderThread.setDaemon(true);
        renderThread.start();
    }

    @Override public void closePort()
    {
        running = false;
        if (renderThread != null) renderThread.interrupt();
    }

    @Override public void prepareForNewTrack(javax.sound.midi.Sequence seq)
    {
        renderPaused = true;
        if (audio != null) audio.flush();
        for (int i = 0; i < NUM_CHANNELS; i++) channels[i].reset();
        hwEnvActive = false;
    }

    @Override public void onPlaybackStarted() { renderPaused = false; }

    @Override
    public void sendMessage(byte[] data) throws Exception
    {
        if (data.length < 1) return;
        int cmd = data[0] & 0xF0;
        int ch = data[0] & 0x0F;
        
        if (cmd == 0x90 && data.length >= 3)
        {
            int note = data[1] & 0xFF;
            int velocity = data[2] & 0xFF;
            
            if (velocity > 0)
            {
                for (int i = 0; i < NUM_CHANNELS; i++) {
                    if (channels[i].active && channels[i].midiChannel == ch && channels[i].midiNote == note) {
                        channels[i].volume15 = (int) ((velocity / 127.0) * 15.0);
                        return;
                    }
                }
                
                int targetCh = -1;
                for (int i = 0; i < NUM_CHANNELS; i++) {
                    if (!channels[i].active) {
                        targetCh = i;
                        break;
                    }
                }
                
                if (ch == 9) {
                    if (targetCh == -1) targetCh = 0;
                    PsgChannel c = channels[targetCh];
                    c.reset();
                    c.active = true;
                    c.midiChannel = 9;
                    c.volume15 = 15;
                    c.isNoise = true;
                    if (note == 35 || note == 36) noiseStep16 = 500; 
                    else if (note == 38 || note == 40) noiseStep16 = 3000;
                    else noiseStep16 = 6000;
                    return;
                }
                
                if (note < 45 && targetCh == -1) {
                    targetCh = 2;
                }
                
                if (targetCh == -1) {
                    PsgChannel melCh = channels[1];
                    if (melCh.active && melCh.arpSize < 4) {
                        melCh.arpNotes[melCh.arpSize++] = note;
                        return;
                    }
                    targetCh = 1;
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
                
            } else {
                handleNoteOff(ch, note);
            }
        } else if (cmd == 0x80 && data.length >= 2) {
            handleNoteOff(ch, data[1] & 0xFF);
        } else if (cmd == 0xB0 && data.length >= 3) {
            int cc = data[1] & 0xFF;
            if (cc == 123 || cc == 120) {
                for (int i = 0; i < NUM_CHANNELS; i++) channels[i].active = false;
                hwEnvActive = false;
            }
        }
    }

    private void handleNoteOff(int ch, int note) {
        for (int i = 0; i < NUM_CHANNELS; i++) {
            if (channels[i].active && channels[i].midiChannel == ch && channels[i].midiNote == note) {
                channels[i].active = false;
                if (i == 2) hwEnvActive = false;
            }
        }
    }

    @Override public void panic() { 
        for (int i = 0; i < NUM_CHANNELS; i++) channels[i].reset();
        hwEnvActive = false;
        if (audio != null) audio.flush(); 
    }
}

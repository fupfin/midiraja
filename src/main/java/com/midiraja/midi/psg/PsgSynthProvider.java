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
 * A Multi-Chip Programmable Sound Generator (PSG) emulator.
 * Simulates multiple AY-3-8910 / YM2149F chips running in parallel (e.g., Apple II Mockingboard).
 * Implements a "Tracker-Driven Interception Layer" that applies 1980s demoscene software hacks.
 */
@SuppressWarnings({"ThreadPriorityCheck", "EmptyCatch"})
public class PsgSynthProvider implements SoftSynthProvider
{
    private final AudioEngine audio;
    private final int sampleRate = 44100;
    
    private @Nullable Thread renderThread;
    private volatile boolean running = false;
    private volatile boolean renderPaused = false;
    
    private final int numChips;
    private final PsgChip[] chips;
    
    public PsgSynthProvider(AudioEngine audio)
    {
        this(audio, 4, 0.5, 0.25); // Default setup
    }
    
    public PsgSynthProvider(AudioEngine audio, int numChips, double vibratoDepth, double dutySweep)
    {
        this.audio = audio;
        this.numChips = Math.max(1, Math.min(16, numChips));
        this.chips = new PsgChip[this.numChips];
        for (int i = 0; i < this.numChips; i++) {
            this.chips[i] = new PsgChip(sampleRate, vibratoDepth, dutySweep);
        }
    }

    @Override
    public List<MidiPort> getOutputPorts()
    {
        return List.of(new MidiPort(0, String.format("[%d-Chip] AY-3-8910 Array (Tracker Hacks Mode)", numChips)));
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
                    
                    // Sum the output of all hardware chips
                    for (int c = 0; c < numChips; c++) {
                        sumOutput += chips[c].render();
                    }
                    
                    // Normalize by the number of chips to prevent clipping
                    sumOutput /= (numChips * 0.7);
                    
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
        for (int i = 0; i < numChips; i++) chips[i].reset();
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
                // 1. Update existing note if it's already playing on any chip
                for (int c = 0; c < numChips; c++) {
                    if (chips[c].updateNote(ch, note, velocity)) return;
                }
                
                // 2. Try to find a FREE channel across all chips
                // We route drums mostly to the first chip to centralize the noise generator
                int startChip = (ch == 9) ? 0 : (numChips > 1 ? 1 : 0);
                
                for (int offset = 0; offset < numChips; offset++) {
                    int c = (startChip + offset) % numChips;
                    if (chips[c].tryAllocateFree(ch, note, velocity)) return;
                }
                
                // 3. If all physical channels are full, force an Arpeggio fallback on the last chip
                chips[numChips - 1].forceArpeggioFallback(ch, note, velocity);
                
            } else {
                handleNoteOff(ch, note);
            }
        } else if (cmd == 0x80 && data.length >= 2) {
            handleNoteOff(ch, data[1] & 0xFF);
        } else if (cmd == 0xB0 && data.length >= 3) {
            int cc = data[1] & 0xFF;
            if (cc == 123 || cc == 120) {
                for (int c = 0; c < numChips; c++) chips[c].reset();
            }
        }
    }

    private void handleNoteOff(int ch, int note) {
        for (int c = 0; c < numChips; c++) {
            chips[c].handleNoteOff(ch, note);
        }
    }

    @Override public void panic() { 
        for (int c = 0; c < numChips; c++) chips[c].reset();
        if (audio != null) audio.flush(); 
    }
}

/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.midi.psg;

import com.fupfin.midiraja.dsp.AudioProcessor;
import com.fupfin.midiraja.midi.AbstractOneBitSynthProvider;
import com.fupfin.midiraja.midi.MidiPort;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A Multi-Chip Programmable Sound Generator (PSG) emulator. Simulates multiple AY-3-8910 / YM2149F
 * chips running in parallel (e.g., Apple II Mockingboard). Implements a "Tracker-Driven
 * Interception Layer" that applies 1980s demoscene software hacks.
 */
@SuppressWarnings({"ThreadPriorityCheck", "EmptyCatch"})
public class PsgSynthProvider extends AbstractOneBitSynthProvider
{
    private final int sampleRate = 44100;

    private final int systems; // The number of pairs (or just single PSGs if no SCC)
    private final TrackerSynthChip[] chips;
    private final int totalPhysicalChips;
    private final int[] channelPrograms = new int[16];
    private final boolean useScc;

    public PsgSynthProvider(@Nullable AudioProcessor audioOut, int systems, double vibratoDepth,
            double dutySweep, boolean useScc, boolean smoothScc)
    {
        super(audioOut);
        this.systems = Math.max(1, Math.min(16, systems));
        this.useScc = useScc;
        this.totalPhysicalChips = useScc ? this.systems * 2 : this.systems;
        this.chips = new TrackerSynthChip[this.totalPhysicalChips];

        if (useScc)
        {
            // Pair Architecture: Each system is [1 PSG + 1 SCC]
            for (int i = 0; i < this.systems; i++)
            {
                this.chips[i * 2] = new PsgChip(sampleRate, vibratoDepth, dutySweep); // Even: PSG
                this.chips[i * 2 + 1] = new SccChip(sampleRate, vibratoDepth, smoothScc); // Odd:
                                                                                          // SCC
            }
        }
        else
        {
            // Standard Mode: N PSGs
            for (int i = 0; i < this.systems; i++)
            {
                this.chips[i] = new PsgChip(sampleRate, vibratoDepth, dutySweep);
            }
        }
    }

    @Override
    public List<MidiPort> getOutputPorts()
    {
        String desc;
        if (useScc)
        {
            if (systems == 1)
            {
                desc = "MSX System (1x AY-3-8910 + 1x Konami SCC)";
            }
            else
            {
                desc = String.format("[%d-System] MSX Array (%dx AY-3-8910 + %dx Konami SCC)",
                        systems, systems, systems);
            }
        }
        else
        {
            desc = String.format("[%d-Chip] AY-3-8910 Array (Tracker Hacks Mode)", systems);
        }
        return List.of(new MidiPort(0, desc));
    }

    @Override
    protected void renderFrames(short[] pcmBuffer, int framesToRender)
    {
        for (int i = 0; i < framesToRender; i++)
        {
            double sumOutput = 0.0;

            // Sum the output of all hardware chips
            for (int c = 0; c < totalPhysicalChips; c++)
            {
                sumOutput += chips[c].render();
            }

            // Normalize to ~−9 dBFS peak (project-wide target, matches TSF/GUS/Beep).
            // 3 PSG channels at max each contribute 1/3, summing to 1.0; dividing by 2.8 scales
            // the peak to ≈0.357 (−8.9 dBFS) before the 32767 conversion.
            sumOutput /= (totalPhysicalChips * 2.8);

            pcmBuffer[i] = (short) (Math.max(-1.0, Math.min(1.0, sumOutput)) * 32767);
        }
    }

    @Override
    protected void resetState()
    {
        for (int i = 0; i < totalPhysicalChips; i++)
            chips[i].reset();
    }

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
                for (int c = 0; c < totalPhysicalChips; c++)
                {
                    if (chips[c].updateNote(ch, note, velocity)) return;
                }

                // 2. Try to find a FREE channel across all chips
                // In Pair Architecture:
                // Drums (ch == 9) should prioritize PSGs (even indices 0, 2, 4...) to use hardware
                // noise.
                // Melodies should prioritize SCCs (odd indices 1, 3, 5...) for richer wavetables.
                int[] searchOrder = new int[totalPhysicalChips];
                if (useScc)
                {
                    if (ch == 9)
                    {
                        // Drums: Strict PSG allocation. Drums never spill to SCC.
                        // (We leave the second half of searchOrder as -1, which we will skip)
                        for (int i = 0; i < totalPhysicalChips; i++)
                            searchOrder[i] = -1;
                        for (int i = 0; i < systems; i++)
                            searchOrder[i] = i * 2; // PSGs
                    }
                    else
                    {
                        // Melody: Strict SCC allocation. Melodies never spill to PSG.
                        for (int i = 0; i < totalPhysicalChips; i++)
                            searchOrder[i] = -1;
                        for (int i = 0; i < systems; i++)
                            searchOrder[i] = i * 2 + 1; // SCCs
                    }
                }
                else
                {
                    // Standard PSG-only mode: just search linearly
                    for (int i = 0; i < totalPhysicalChips; i++)
                        searchOrder[i] = i;
                }

                for (int i = 0; i < totalPhysicalChips; i++)
                {
                    int c = searchOrder[i];
                    if (c == -1) continue; // Isolation barrier

                    if (chips[c].tryAllocateFree(ch, note, velocity))
                    {
                        // Ensure the chip knows what instrument is playing on this channel
                        chips[c].setProgram(ch, channelPrograms[ch]);
                        return;
                    }
                }

                // 3. Voice Stealing (Eviction): All channels are full.
                // If this is a Melody (typically low MIDI channel like 0, 1, 2),
                // it shouldn't be forced into an Arpeggio or pushed to a harsh PSG while
                // background chords (channels 4, 5, 6) hog the premium SCC chips.
                // We will try to find a less important note on the primary chip and steal it.
                int primaryChip = searchOrder[0]; // The most desired chip for this note

                // Simple heuristic: Try to steal a note from a higher MIDI channel number
                // (assuming ch 0-3 = Lead/Melody, ch 4-8 = Accompaniment)
                boolean stolen = false;
                if (ch < 4)
                { // High priority melody
                    stolen = chips[primaryChip].tryStealChannel(ch, note, velocity);
                    if (stolen)
                    {
                        chips[primaryChip].setProgram(ch, channelPrograms[ch]);
                        return;
                    }
                }

                // 4. If all physical channels are full and we couldn't steal, force an Arpeggio
                // fallback.
                // Target the most appropriate chip type based on the instrument
                int fallbackChip = useScc ? (ch == 9 ? 0 : 1) : (totalPhysicalChips - 1);
                chips[fallbackChip].forceArpeggioFallback(ch, note, velocity);

            }
            else
            {
                handleNoteOff(ch, note);
            }
        }
        else if (cmd == 0x80 && data.length >= 2)
        {
            handleNoteOff(ch, data[1] & 0xFF);
        }
        else if (cmd == 0xB0 && data.length >= 3)
        {
            int cc = data[1] & 0xFF;
            if (cc == 123 || cc == 120)
            {
                for (int c = 0; c < totalPhysicalChips; c++)
                    chips[c].reset();
            }
        }
        else if (cmd == 0xC0 && data.length >= 2)
        {
            int program = data[1] & 0xFF;
            channelPrograms[ch] = program;
            for (int c = 0; c < totalPhysicalChips; c++)
            {
                chips[c].setProgram(ch, program);
            }
        }
    }

    private void handleNoteOff(int ch, int note)
    {
        for (int c = 0; c < totalPhysicalChips; c++)
        {
            chips[c].handleNoteOff(ch, note);
        }
    }

    @Override
    public void panic()
    {
        for (int c = 0; c < totalPhysicalChips; c++)
            chips[c].reset();
        if (audioOut != null) audioOut.reset();
    }
}

/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.midi.psg;

/**
 * Emulates the Konami SCC (K051649) Sound Custom Chip. Features 5 channels, each with a 32-byte
 * custom waveform buffer.
 *
 * NOTE ON HARDWARE ACCURACY: The original K051649 SCC forced channels 4 and 5 to share the same
 * waveform memory. This engine deliberately ignores that limitation and provides 5 strictly
 * independent channels, effectively emulating the upgraded "SCC+" (Sound Cartridge) hardware. This
 * prevents catastrophic instrument clashing during complex MIDI polyphony.
 */
public class SccChip extends AbstractTrackerChip
{
    private static final int NUM_CHANNELS = 5;
    private final int sampleRate;
    private final double[] dacTable = new double[16];
    private final double vibratoDepth;
    private final boolean smoothScc;

    private static class SccChannel extends AbstractTrackerChip.Channel
    {
        // 32-byte waveform (signed -128 to 127)
        byte[] waveform = new byte[32];

        // Use double precision for smooth wavetable reading
        double phase = 0.0;
        double phaseStep = 0.0;

        @Override
        void resetCommon()
        {
            super.resetCommon();
            phase = 0.0;
            phaseStep = 0.0;
        }
    }

    private final SccChannel[] channels = new SccChannel[NUM_CHANNELS];


    public SccChip(int sampleRate, double vibratoDepth)
    {
        this(sampleRate, vibratoDepth, false);
    }

    public SccChip(int sampleRate, double vibratoDepth, boolean smoothScc)
    {
        this.sampleRate = sampleRate;
        this.vibratoDepth = Math.max(0.0, Math.min(100.0, vibratoDepth)) / 1000.0; // convert per
                                                                                   // mille
        this.smoothScc = smoothScc;

        for (int i = 0; i < NUM_CHANNELS; i++)
        {
            channels[i] = new SccChannel();
            System.arraycopy(SccWaveforms.SQUARE, 0, channels[i].waveform, 0, 32);
        }

        // Exact same DAC table as PSG for volume parity
        for (int i = 0; i < 16; i++)
        {
            dacTable[i] = Math.pow(10.0, (i - 15) * 1.5 / 20.0);
        }
        dacTable[0] = 0.0;
    }

    // --- AbstractTrackerChip hooks ---

    @Override
    protected int getNumChannels()
    {
        return NUM_CHANNELS;
    }

    @Override
    protected Channel getChannel(int index)
    {
        return channels[index];
    }

    @Override
    protected void resetChannelSpecific(int index)
    {
        // phase/phaseStep are reset via resetCommon() in SccChannel; waveform is preserved
    }

    @Override
    protected void onNoteActivated(int index, int ch, int note, int velocity)
    {
        SccChannel c = channels[index];
        c.baseFrequency = 440.0 * Math.pow(2.0, (note - 69) / 12.0);
        c.phaseStep = (c.baseFrequency * 32.0) / sampleRate;
        c.phase = 0.0;
    }

    @Override
    protected int getArpeggioFallbackChannel()
    {
        return 0;
    }

    // --- TrackerSynthChip implementations ---

    @Override
    public void reset()
    {
        for (int i = 0; i < NUM_CHANNELS; i++)
            channels[i].resetCommon();
    }

    @Override
    public void setProgram(int ch, int program)
    {
        // Find channels currently playing this midi channel and update their waveform
        byte[] targetWave = SccWaveforms.forProgram(program);

        for (int i = 0; i < NUM_CHANNELS; i++)
        {
            if (channels[i].midiChannel == ch)
            {
                System.arraycopy(targetWave, 0, channels[i].waveform, 0, 32);
            }
        }
    }

    @Override
    public double render()
    {
        double sumOutput = 0.0;

        for (int ch = 0; ch < NUM_CHANNELS; ch++)
        {
            SccChannel c = channels[ch];
            if (!c.active)
                continue;

            if (c.activeFrames % 882 == 0)
            {
                if (c.arpSize > 1)
                {
                    c.arpIndex = (c.arpIndex + 1) % c.arpSize;
                    c.baseFrequency = 440.0 * Math.pow(2.0, (c.arpNotes[c.arpIndex] - 69) / 12.0);
                    // Phase goes from 0.0 to 32.0 (length of the wavetable)
                    c.phaseStep = (c.baseFrequency * 32.0) / sampleRate;
                }
                else if (c.baseFrequency > 0.0 && vibratoDepth > 0.0001)
                {
                    // --- HACK: DELAYED VIBRATO FOR SCC ---
                    // Wait ~0.5 seconds (25 * 882 frames) before kicking in the vibrato
                    if (c.activeFrames > 25 * 882)
                    {
                        double timeSec = (c.activeFrames / (double) sampleRate);
                        double pitchLfo = Math.sin(timeSec * 3.5 * 2.0 * Math.PI); // 3.5Hz wobble
                        double vibratoFreq = c.baseFrequency * (1.0 + (vibratoDepth * pitchLfo));
                        c.phaseStep = (vibratoFreq * 32.0) / sampleRate;
                    }
                }
            }
            c.activeFrames++;

            c.phase += c.phaseStep;
            if (c.phase >= 32.0)
            {
                c.phase -= 32.0;
            }

            // Fake envelope decay based on active frames
            double envDecay = Math.max(0.0, 1.0 - (c.activeFrames / (double) (sampleRate * 2)));
            int currentVol15 = Math.max(0, Math.min(15, (int) (c.volume15 * envDecay)));

            if (!smoothScc)
            {
                // Historically accurate aliased steps + openMSX volume formula (SCC.cc):
                // sample × volume >> 4, then scale back. We apply a ~2.6× boost so SCC
                // isn't drowned out by PSG square waves.
                int rawSample = c.waveform[(int) c.phase];
                int shifted = (rawSample * currentVol15) >> 4;
                sumOutput += (shifted / 128.0) * dacTable[currentVol15] * 0.85;
            }
            else
            {
                // Linear interpolation + continuous volume scaling
                int index0 = (int) c.phase;
                int index1 = (index0 + 1) % 32;
                double frac = c.phase - index0;
                double s0 = c.waveform[index0] / 128.0;
                double s1 = c.waveform[index1] / 128.0;
                double sample = s0 + frac * (s1 - s0);
                sumOutput += sample * dacTable[currentVol15] * 0.85;
            }
        }

        return sumOutput;
    }

    @Override
    public boolean tryAllocateFree(int ch, int note, int velocity)
    {
        int targetCh = -1;
        for (int i = 0; i < NUM_CHANNELS; i++)
        {
            if (!channels[i].active)
            {
                targetCh = i;
                break;
            }
        }

        if (targetCh == -1)
            return false;

        SccChannel c = channels[targetCh];
        c.resetCommon();
        c.active = true;
        c.midiChannel = ch;
        c.midiNote = note;
        c.volume15 = (int) ((velocity / 127.0) * 15.0);
        c.arpNotes[0] = note;
        c.arpSize = 1;
        c.baseFrequency = 440.0 * Math.pow(2.0, (note - 69) / 12.0);
        c.phaseStep = (c.baseFrequency * 32.0) / sampleRate;
        c.phase = 0.0;

        // Let PsgSynthProvider handle program changes, but default is square.

        return true;
    }
}

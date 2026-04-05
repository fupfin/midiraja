/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.midi.gus;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class GusEngine
{
    private final int outputSampleRate;
    private final Map<Integer, GusPatch> patchMap = new HashMap<>();
    private final List<Voice> activeVoices = new CopyOnWriteArrayList<>();

    public int getOutputSampleRate()
    {
        return outputSampleRate;
    }

    private final Map<Integer, Integer> channelPrograms = new HashMap<>();

    public GusEngine(int outputSampleRate)
    {
        this.outputSampleRate = outputSampleRate;
    }

    public void setProgram(int channel, int program)
    {
        channelPrograms.put(channel, program);
    }

    public void loadPatch(int program, GusPatch patch)
    {
        patchMap.put(program, patch);
    }

    public boolean hasPatch(int program)
    {
        return patchMap.containsKey(program);
    }

    public void noteOn(int channel, int note, int velocity)
    {
        int program = (channel == 9) ? note + 128 : channelPrograms.getOrDefault(channel, 0);

        GusPatch patch = patchMap.get(program);
        if (patch == null || patch.instruments().isEmpty())
        {
            return;
        }

        GusPatch.Instrument inst = patch.instruments().getFirst();
        if (inst.samples().isEmpty())
            return;

        // Calculate frequency of the requested MIDI note
        // MIDI note 69 is A4 (440 Hz). We multiply by 1000 for millihertz to match GUS specs
        double targetFreq = 440.0 * Math.pow(2.0, (note - 69) / 12.0);
        double targetMilliHz = targetFreq * 1000.0;

        // 1. Find a sample where target is within lowFreq and highFreq
        GusPatch.Sample bestSample = null;
        for (GusPatch.Sample s : inst.samples())
        {
            if (targetMilliHz >= s.lowFrequency() && targetMilliHz <= s.highFrequency())
            {
                bestSample = s;
                break;
            }
        }

        // 2. If no exact match, find the sample with the closest root frequency
        if (bestSample == null)
        {
            double minDiff = Double.MAX_VALUE;
            for (GusPatch.Sample s : inst.samples())
            {
                double diff = Math.abs(s.rootFrequency() - targetMilliHz);
                if (diff < minDiff)
                {
                    minDiff = diff;
                    bestSample = s;
                }
            }
        }

        if (bestSample == null)
            return;

        double ratio;
        if (channel == 9)
        { // Drum channel
          // Drums always play at their natural recorded pitch
            ratio = 1.0;
        }
        else
        {
            double rootFreq = bestSample.rootFrequency() / 1000.0;
            ratio = targetFreq / rootFreq;
        }

        Voice voice = new Voice(patch, bestSample, note, velocity, ratio);
        activeVoices.add(voice);
    }

    public void render(float[] left, float[] right, int frames)
    {
        for (Voice v : activeVoices)
        {
            if (v.isActive())
            {
                v.render(left, right, frames, outputSampleRate);
            }
        }

        // Remove dead voices to prevent memory leak
        activeVoices.removeIf(v -> !v.isActive());
    }

    public void noteOff(int channel, int note)
    {
        for (Voice v : activeVoices)
        {
            if (v.getNote() == note && v.isActive())
            {
                v.release();
            }
        }
    }

    public List<Voice> getActiveVoices()
    {
        return activeVoices;
    }
}

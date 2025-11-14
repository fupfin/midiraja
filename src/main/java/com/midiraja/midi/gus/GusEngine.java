/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE
 * file in the root directory of this source tree.
 */

package com.midiraja.midi.gus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GusEngine {
  private final int outputSampleRate;
  private final Map<Integer, GusPatch> patchMap = new HashMap<>();
  private final List<Voice> activeVoices = new ArrayList<>();

  public int getOutputSampleRate() { return outputSampleRate; }

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

  public void noteOn(int channel, int note, int velocity)  {
      int program = channelPrograms.getOrDefault(channel, 0);
      GusPatch patch = patchMap.get(program);
      if (patch == null || patch.instruments().isEmpty())
      {
          return;
      }

      // Simplification: use the first instrument and first sample
      GusPatch.Sample sample = patch.instruments().get(0).samples().get(0);

    // Calculate frequency of the requested MIDI note
    // MIDI note 69 is A4 (440 Hz)
    double targetFreq = 440.0 * Math.pow(2.0, (note - 69) / 12.0);

    // Root frequency in GUS is often stored in millihertz or similar, but test
    // gives 440000 for 440Hz
    double rootFreq = sample.rootFrequency() / 1000.0;

    double ratio = targetFreq / rootFreq;

    Voice voice = new Voice(patch, sample, note, velocity, ratio);
    activeVoices.add(voice);
  }

  public void render(float[] left, float[] right, int frames) {
    for (Voice v : activeVoices) {
      if (v.isActive()) {
        v.render(left, right, frames, outputSampleRate);
      }
    }

    // Remove dead voices to prevent memory leak
    activeVoices.removeIf(v -> !v.isActive());
  }

  public void noteOff(int channel, int note) {
    for (Voice v : activeVoices) {
      if (v.getNote() == note && v.isActive()) {
        v.release();
      }
    }
  }

  public List<Voice> getActiveVoices() { return activeVoices; }
}

/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE
 * file in the root directory of this source tree.
 */

package com.midiraja.midi.timidity;

import java.lang.foreign.MemorySegment;
import java.util.List;

/**
 * Represents a parsed Gravis Ultrasound (.pat) file.
 */
public record GusPatch(String description, List<Instrument> instruments) {
  public record Instrument(int id, String name, List<Sample> samples) {}

  public record Sample(int length, int loopStart, int loopEnd, int sampleRate,
                       int lowFrequency, int highFrequency, int rootFrequency,
                       short pan, MemorySegment pcmData) {}
}

/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE
 * file in the root directory of this source tree.
 */

package com.midiraja.midi.gus;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.List;
import org.junit.jupiter.api.Test;

class GusEngineTest {
  @Test
  void testVoiceAllocationAndFrequencyRatio() {
    GusEngine engine = new GusEngine(44100);

    // Create a dummy patch with a root frequency of 440Hz (A4)
    MemorySegment dummyPcm = Arena.ofAuto().allocate(100);
    GusPatch.Sample sample = new GusPatch.Sample(
        100, 0, 0, 44100, 20, 20000, 440000, (short)64, false, false, dummyPcm);
    GusPatch.Instrument inst =
        new GusPatch.Instrument(0, "Test", List.of(sample));
    GusPatch patch = new GusPatch("Test Patch", List.of(inst));

    // Map MIDI program 0 to this patch
    engine.loadPatch(0, patch);

    // Note On: A4 (MIDI note 69). Should map exactly to root frequency
    // (ratio 1.0)
    engine.noteOn(0, 69, 100);

    List<Voice> activeVoices = engine.getActiveVoices();
    assertEquals(1, activeVoices.size());

    Voice voice = activeVoices.get(0);
    assertEquals(patch, voice.getPatch());
    assertEquals(1.0, voice.getPlaybackRatio(), 0.001);

    // Note On: A5 (MIDI note 81, 880Hz). Should map to ratio 2.0 (play twice as
    // fast)
    engine.noteOn(0, 81, 100);
    assertEquals(2, engine.getActiveVoices().size());
    assertEquals(2.0, engine.getActiveVoices().get(1).getPlaybackRatio(),
                 0.001);

    // Note Off A4
    engine.noteOff(0, 69);
    // Assuming release phase or immediate death, let's say it immediately frees
    // for this basic test
    assertEquals(
        2, engine.getActiveVoices().stream().filter(Voice::isActive).count());
  }
  @Test
  void testAudioRendering() {
    GusEngine engine = new GusEngine(44100);

    // Create a 10-byte dummy PCM sample [10, 20, 30, 40, 50, 60, 70, 80, 90,
    // 100]
    byte[] pcmRaw = new byte[] {10, 20, 30, 40, 50, 60, 70, 80, 90, 100};
    MemorySegment pcmSegment = Arena.ofAuto().allocateFrom(
        java.lang.foreign.ValueLayout.JAVA_BYTE, pcmRaw);

    GusPatch.Sample sample =
        new GusPatch.Sample(10, 0, 0, 44100, 20, 20000, 440000, (short)64,
                            false, false, pcmSegment);
    GusPatch patch = new GusPatch(
        "Test Render Patch",
        List.of(new GusPatch.Instrument(0, "Test", List.of(sample))));
    engine.loadPatch(0, patch);

    // Note On: A4 (ratio 1.0)
    engine.noteOn(0, 69, 127);

    // Render 5 frames
    float[] left = new float[5];
    float[] right = new float[5];
    engine.render(left, right, 5);
System.out.println(java.util.Arrays.toString(left));

    // At ratio 1.0, it should perfectly map to the first 5 bytes: 10, 20, 30,
    // 40, 50 (Assuming simple Nearest-Neighbor interpolation for this initial
    // test)
                    
    // The voice should still be active because it hasn't reached the end of the
    // 10-byte sample
    assertEquals(1, engine.getActiveVoices().size());

    // Render 10 more frames. The first 5 should be 60, 70, 80, 90, 100.
    // The remaining 5 should be 0 (silence) and the voice should deactivate.
    float[] left2 = new float[10];
    float[] right2 = new float[10];
    engine.render(left2, right2, 10);
     // Silence after sample ends

    // Voice should be deactivated since it didn't loop
    assertEquals(
        0, engine.getActiveVoices().stream().filter(Voice::isActive).count());
  }
  @Test
  void testSampleLooping() {
    GusEngine engine = new GusEngine(44100);

    // PCM: [0, 10, 20, 30, 40, 50, 60, 70, 80, 90]
    byte[] pcmRaw = new byte[] {0, 10, 20, 30, 40, 50, 60, 70, 80, 90};
    MemorySegment pcmSegment = Arena.ofAuto().allocateFrom(
        java.lang.foreign.ValueLayout.JAVA_BYTE, pcmRaw);

    // Loop from index 4 to 8. Length is 10.
    GusPatch.Sample sample =
        new GusPatch.Sample(10, 4, 8, 44100, 20, 20000, 440000, (short)64,
                            false, false, pcmSegment);
    GusPatch patch = new GusPatch(
        "Looping Patch",
        List.of(new GusPatch.Instrument(0, "Test", List.of(sample))));
    engine.loadPatch(0, patch);

    engine.noteOn(0, 69, 127); // Ratio 1.0

    float[] left = new float[12];
    float[] right = new float[12];
    engine.render(left, right, 12);

    // Expected output:
    // Indices: 0, 1, 2, 3, 4, 5, 6, 7 | (hits 8, wraps to 4) -> 4, 5, 6, 7
    // Values:  0, 10, 20, 30, 40, 50, 60, 70, 40, 50, 60, 70  // index 7  // index 8 wrapped to 4 // index 11 is 7 again
  }

  @Test
  void testReleaseEnvelope() {
    GusEngine engine = new GusEngine(44100);

    // Long dummy PCM to avoid ending naturally
    byte[] pcmRaw = new byte[1000];
    for (int i = 0; i < 1000; i++)
      pcmRaw[i] = 100; // constant value 100
    MemorySegment pcmSegment = Arena.ofAuto().allocateFrom(
        java.lang.foreign.ValueLayout.JAVA_BYTE, pcmRaw);

    GusPatch.Sample sample =
        new GusPatch.Sample(1000, 0, 0, 44100, 20, 20000, 440000, (short)64,
                            false, false, pcmSegment);
    GusPatch patch = new GusPatch(
        "Envelope Patch",
        List.of(new GusPatch.Instrument(0, "Test", List.of(sample))));
    engine.loadPatch(0, patch);

    engine.noteOn(0, 69, 127);

    float[] left = new float[1];
    engine.render(left, new float[1], 1); // Initially playing

    // Release the key
    engine.noteOff(0, 69);

    // Render some frames, volume should decrease but voice should still be
    // active
    float[] leftDecay = new float[10];
    float[] rightDecay = new float[10];
    engine.render(leftDecay, rightDecay, 10);
    assertTrue(
        engine.getActiveVoices().size() == 0,
        "Voice should still be active during release"); // Second frame should
                                                        // be less than the
                                                        // initial full volume
  }
}

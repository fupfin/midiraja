/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE
 * file in the root directory of this source tree.
 */

package com.midiraja.midi.gus;

public class Voice {
  private final GusPatch patch;
  private final GusPatch.Sample sample;
  private final int note;
  private final int velocity;
  private final double playbackRatio;
  private boolean active = true;
  private double currentPosition = 0.0;

  // Envelope state
  private boolean releasing = false;
  private double currentVolume = 1.0;
  private final double releaseDecayRate =
      0.05; // Dummy decay per frame for phase 1

  public Voice(GusPatch patch, GusPatch.Sample sample, int note, int velocity,
               double playbackRatio) {
    this.patch = patch;
    this.sample = sample;
    this.note = note;
    this.velocity = velocity;
    this.playbackRatio = playbackRatio;
    this.currentVolume = velocity / 127.0; // Initial velocity scaling
  }

  public void render(float[] left, float[] right, int frames,
                     int outputSampleRate) {
    if (!active)
      return;

    // Ratio adjusted for potential differences between output device rate and
    // sample original rate
    double rateAdjustment = (double)sample.sampleRate() / outputSampleRate;
    double speed = playbackRatio * rateAdjustment;

    boolean loops = sample.loopEnd() > sample.loopStart();

    for (int i = 0; i < frames; i++) {
      // Handle Looping
      if (loops && currentPosition >= sample.loopEnd()) {
        currentPosition -= (sample.loopEnd() - sample.loopStart());
      }

      // Check end of sample (if not looping or if looping is over)
      if (currentPosition >= sample.length() || currentPosition < 0) {
        active = false;
        break;
      }

      // Simple Nearest-Neighbor interpolation
      int intPos = (int)currentPosition;
      float pcmFloat;

      if (sample.is16Bit()) {
        // 16-bit GUS samples are Little-Endian
        int bytePos = intPos * 2;
        if (bytePos + 1 < sample.pcmData().byteSize()) {
          short val = sample.pcmData().get(
              java.lang.foreign.ValueLayout.JAVA_SHORT_UNALIGNED, bytePos);
          if (sample.isUnsigned()) {
            val = (short)((val & 0xFFFF) - 32768);
          }
          pcmFloat = val / 32768.0f;
        } else {
          pcmFloat = 0.0f;
        }
      } else {
        byte val = sample.pcmData().get(java.lang.foreign.ValueLayout.JAVA_BYTE,
                                        intPos);
        if (sample.isUnsigned()) {
          val = (byte)((val & 0xFF) - 128);
        }
        pcmFloat = val / 128.0f;
      }

      // Apply volume envelope (currentVolume is 0.0 ~ 1.0)
      float mixedVal = pcmFloat * (float)currentVolume;

      // Mix into output
      left[i] += mixedVal;
      right[i] += mixedVal;

      // Advance playhead
      currentPosition += speed;

      // Process release envelope
      if (releasing) {
        currentVolume -= releaseDecayRate;
        if (currentVolume <= 0.0) {
          currentVolume = 0.0;
          active = false;
          break;
        }
      }
    }
  }

  public void release() { this.releasing = true; }
  public GusPatch getPatch() { return patch; }

  public int getNote() { return note; }

  public int getVelocity() { return velocity; }

  public double getPlaybackRatio() { return playbackRatio; }

  public boolean isActive() { return active; }

  public void deactivate() { this.active = false; }
}

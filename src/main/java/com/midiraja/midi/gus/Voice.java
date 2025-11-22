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
  private double targetVolume = 1.0;
  private double currentVolume = 0.0; // Start at 0 to prevent clicking
  private final double attackRate = 0.005; // Fast ~5ms attack
  private final double releaseDecayRate = 0.0005;

  public Voice(GusPatch patch, GusPatch.Sample sample, int note, int velocity, double playbackRatio)
  {
      this.patch = patch;
      this.sample = sample;
      this.note = note;
      this.velocity = velocity;
      this.playbackRatio = playbackRatio;
      // Scale down individual voice volume to prevent master bus clipping
      this.targetVolume = (velocity / 127.0) * 0.15;
  }

  private float readSample(int index) {
      if (index < 0 || index >= sample.length()) return 0.0f;
      if (sample.is16Bit()) {
          int bytePos = index * 2;
          if (bytePos + 1 < sample.pcmData().byteSize()) {
              short val = sample.pcmData().get(java.lang.foreign.ValueLayout.JAVA_SHORT_UNALIGNED, bytePos);
              if (sample.isUnsigned()) val = (short) ((val & 0xFFFF) - 32768);
              return val / 32768.0f;
          }
      } else {
          int bytePos = index;
          if (bytePos < sample.pcmData().byteSize()) {
              byte val = sample.pcmData().get(java.lang.foreign.ValueLayout.JAVA_BYTE, bytePos);
              if (sample.isUnsigned()) val = (byte) ((val & 0xFF) - 128);
              return val / 128.0f;
          }
      }
      return 0.0f;
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

      // Linear Interpolation
      int intPos1 = (int) currentPosition;
      int intPos2 = intPos1 + 1;
      
      if (loops && intPos2 >= sample.loopEnd()) {
          intPos2 = sample.loopStart() + (intPos2 - sample.loopEnd());
      } else if (intPos2 >= sample.length()) {
          intPos2 = intPos1; // Clamp to end
      }
      
      double frac = currentPosition - intPos1;
      float pcm1 = readSample(intPos1);
      float pcm2 = readSample(intPos2);
      float pcmFloat = (float) (pcm1 + frac * (pcm2 - pcm1));
      // Process Envelope
      if (releasing) {
        currentVolume -= releaseDecayRate;
        if (currentVolume <= 0.0) {
          currentVolume = 0.0;
          active = false;
          break;
        }
      } else {
        if (currentVolume < targetVolume) {
            currentVolume += attackRate;
            if (currentVolume > targetVolume) currentVolume = targetVolume;
        }
      }

      // Apply volume envelope (currentVolume is 0.0 ~ 1.0)
      float mixedVal = pcmFloat * (float)currentVolume;

      // Mix into output
      left[i] += mixedVal;
      right[i] += mixedVal;

      // Advance playhead
      currentPosition += speed;
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

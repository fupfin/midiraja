/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE
 * file in the root directory of this source tree.
 */

package com.midiraja.midi.gus;

import com.midiraja.midi.MidiPort;
import com.midiraja.midi.NativeAudioEngine;
import com.midiraja.midi.SoftSynthProvider;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

@SuppressWarnings("ThreadPriorityCheck")
public class GusSynthProvider implements SoftSynthProvider {
  private final NativeAudioEngine audio;

  private final GusEngine engine;
  private final @Nullable GusBank bank;

  private @Nullable Thread renderThread;
  private volatile boolean running = false;
  private volatile boolean renderPaused = false;

  public GusSynthProvider(NativeAudioEngine audio, @Nullable String patchDir) {
    this.audio = audio;

    this.engine = new GusEngine(44100);
    this.bank = patchDir != null ? new GusBank(Path.of(patchDir)) : null;
  }

  @Override
  public List<MidiPort> getOutputPorts() {
    return List.of(new MidiPort(0, "Midiraja Pure Java Gravis Ultrasound"));
  }

  @Override
  public void openPort(int portIndex) throws Exception {
    if (bank != null) {
      Path cfgPath = bank.getRootDir().resolve("gus.cfg");
      if (Files.exists(cfgPath)) {
        bank.loadConfig(Files.readString(cfgPath, StandardCharsets.US_ASCII));
      }

      // Auto-load bank 0, program 0 (Piano) as a default fallback if possible
      bank.getPatchPath(0, 0).ifPresent(path -> {
        try {
          File patFile = bank.getRootDir().resolve(path).toFile();
          if (patFile.exists()) {
            try (FileInputStream in = new FileInputStream(patFile)) {
              engine.loadPatch(0, GusPatchReader.read(in));
            }
          }
        } catch (Exception ignored) {
          // Ignore failure to load default patch
        }
      });
    }

    if (audio != null) {
      audio.init(44100, 2, 4096);
      startRenderThread();
    }
  }

  @Override
  public void loadSoundbank(String path) throws Exception {
    // Path can be a .cfg file or a directory
    File f = new File(path);
    if (f.isFile() && bank != null) {
      bank.loadConfig(Files.readString(f.toPath(), StandardCharsets.US_ASCII));
    }
  }

  private void startRenderThread() {
    running = true;
    renderThread = new Thread(() -> {
      final int framesToRender = 512;
      short[] pcmBuffer = new short[framesToRender * 2]; // Stereo
      float[] left = new float[framesToRender];
      float[] right = new float[framesToRender];

      while (running) {
        if (renderPaused) {
          try {
            Thread.sleep(1);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            break;
          }
          continue;
        }

        for (int i = 0; i < framesToRender; i++) {
          left[i] = 0;
          right[i] = 0;
        }

        engine.render(left, right, framesToRender);

        for (int i = 0; i < framesToRender; i++) {
          float l = Math.max(-1.0f, Math.min(1.0f, left[i] / 128.0f));
          float r = Math.max(-1.0f, Math.min(1.0f, right[i] / 128.0f));
          pcmBuffer[i * 2] = (short)(l * 32767);
          pcmBuffer[i * 2 + 1] = (short)(r * 32767);
        }

        if (audio != null) {
          audio.push(pcmBuffer);
        }
      }
    });
    renderThread.setPriority(Thread.MAX_PRIORITY);
    renderThread.setDaemon(true);
    renderThread.start();
  }

  @Override
  public void prepareForNewTrack() {
    if (audio == null)
      return;
    renderPaused = true;
    try {
      Thread.sleep(20);
    } catch (InterruptedException ignored) {
      // Expected
    }
    audio.flush();
    engine.getActiveVoices().clear();
  }

  @Override
  public void onPlaybackStarted() {
    renderPaused = false;
  }

  @Override
  public void sendMessage(byte[] data) {
    if (data == null || data.length < 1)
      return;

    int status = data[0] & 0xFF;
    int cmd = status & 0xF0;
    int ch = status & 0x0F;

    if (cmd == 0x90 && data.length >= 3) {
      int note = data[1] & 0xFF;
      int velocity = data[2] & 0xFF;

      // Check for Program Change if we haven't loaded the patch yet
      // (Real-time patch loading logic could go here)

      if (velocity > 0) {
        engine.noteOn(ch, note, velocity);
      } else {
        engine.noteOff(ch, note);
      }
    } else if (cmd == 0x80 && data.length >= 3) {
      int note = data[1] & 0xFF;
      engine.noteOff(ch, note);
    } else if (cmd == 0xC0 && data.length >= 2) {
      // Program Change!
      int program = data[1] & 0xFF;
      engine.setProgram(ch, program);
      loadPatchOnDemand(program);
    }
  }

  private void loadPatchOnDemand(int program) {
    if (bank != null) {
      bank.getPatchPath(0, program).ifPresent(path -> {
        try {
          File patFile = bank.getRootDir().resolve(path).toFile();
          if (patFile.exists()) {
            try (FileInputStream in = new FileInputStream(patFile)) {
              engine.loadPatch(program, GusPatchReader.read(in));
            }
          }
        } catch (Exception ignored) {
          // Ignore failure to load dynamic patch
        }
      });
    }
  }

  @Override
  public void panic() {
    engine.getActiveVoices().clear();
    if (audio != null)
      audio.flush();
  }

  @Override
  public void closePort() {
    running = false;
    if (renderThread != null) {
      renderThread.interrupt();
      try {
        renderThread.join(500);
      } catch (InterruptedException ignored) {
        // Expected during shutdown
      }
    }
    if (audio != null)
      audio.close();
    engine.getActiveVoices().clear();
  }
}

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
    private final java.util.Set<Integer> failedPatches = java.util.Collections.synchronizedSet(new java.util.HashSet<>());


  private @Nullable Thread renderThread;
  private volatile boolean running = false;
  private volatile boolean renderPaused = false;

  public GusSynthProvider(NativeAudioEngine audio, @Nullable String patchDir)
  {
      this.audio = audio;
      this.engine = new GusEngine(44100);

      Path resolvedPath = resolvePatchDir(patchDir);
      this.bank = resolvedPath != null ? new GusBank(resolvedPath) : null;
  }

  private @Nullable Path resolvePatchDir(@Nullable String userPath)
  {
      if (userPath != null) {
          return Path.of(userPath);
      }

      String homeDir = System.getProperty("user.home");
      String[] baseDirs = {
          ".",                            // Current Dir (Most specific)
          homeDir + "/.midiraja",         // User Override (Legacy style)
          homeDir + "/.config/midiraja",  // XDG Standard Config
          homeDir + "/.local/share/midiraja", // XDG Standard Data
          "/opt/homebrew/share/midiraja", // Homebrew (Apple Silicon)
          "/usr/local/share/midiraja",    // Homebrew (Intel Mac)
          "/usr/share/midiraja"           // Linux FHS
      };      // Search order preference: user might drop eawpats, otherwise we fallback to our bundled freepats
      String[] patchSetNames = { "eawpats", "dgguspat", "freepats", "gus", "" };

      for (String baseDir : baseDirs) {
          for (String patchName : patchSetNames) {
              Path p = patchName.isEmpty() ? Path.of(baseDir) : Path.of(baseDir, patchName);
              if (Files.isDirectory(p) && (Files.exists(p.resolve("gus.cfg")) || Files.exists(p.resolve("timidity.cfg")))) {
                  return p;
              }
          }
      }

      return null;
  }
  @Override
  public List<MidiPort> getOutputPorts() {
    return List.of(new MidiPort(0, "Midiraja Pure Java Gravis Ultrasound"));
  }

  @Override
  public void openPort(int portIndex) throws Exception {
    System.err.println("[DEBUG] GusSynthProvider.openPort() called!");
    if (bank != null) {
      Path cfgPath = bank.getRootDir().resolve("gus.cfg");
      if (!Files.exists(cfgPath)) {
        // Fallback to legacy TiMidity++ config name for maximum retro
        // compatibility
        cfgPath = bank.getRootDir().resolve("timidity.cfg");
      }

      System.err.println("[DEBUG] Looking for config at: " +
                         cfgPath.toAbsolutePath());

      if (Files.exists(cfgPath)) {
        System.err.println("[DEBUG] Config found! Loading...");
        bank.loadConfig(Files.readString(cfgPath, StandardCharsets.US_ASCII));
      } else {
        System.err.println("[DEBUG] No config file found!");
      }

      // Auto-load bank 0, program 0 (Piano) as a default fallback if possible
      bank.getPatchPath(0, 0).ifPresent(path -> {
        try {
          File patFile = bank.getRootDir().resolve(path).toFile();
          if (patFile.exists()) {
            try (FileInputStream in = new FileInputStream(patFile)) {
              engine.loadPatch(0, GusPatchReader.read(in));
              System.err.println("[DEBUG] Default Piano patch loaded from: " +
                                 patFile.getAbsolutePath());
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
      System.err.println(
          "[DEBUG] NativeAudioEngine initialized and render thread started.");
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
          float l = Math.max(-1.0f, Math.min(1.0f, left[i]));
          float r = Math.max(-1.0f, Math.min(1.0f, right[i]));
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

  @SuppressWarnings("EmptyCatch")
  @Override
  public void prepareForNewTrack(javax.sound.midi.Sequence sequence)
  {
      if (bank != null)
      {
          boolean[] loadedPrograms = new boolean[128];
          boolean[] loadedDrums = new boolean[128];

          for (javax.sound.midi.Track track : sequence.getTracks())
          {
              for (int i = 0; i < track.size(); i++)
              {
                  javax.sound.midi.MidiMessage msg = track.get(i).getMessage();
                  byte[] data = msg.getMessage();
                  if (data == null || data.length < 1) continue;

                  int status = data[0] & 0xFF;
                  int cmd = status & 0xF0;
                  int ch = status & 0x0F;

                  if (cmd == 0xC0 && data.length >= 2) // Program Change
                  {
                      if (ch != 9) // Not drum channel
                      {
                          int program = data[1] & 0xFF;
                          if (!loadedPrograms[program])
                          {
                              loadedPrograms[program] = true;
                              loadPatchOnDemand(0, program);
                          }
                      }
                  }
                  else if (cmd == 0x90 && data.length >= 3) // Note On
                  {
                      if (ch == 9) // Drum channel
                      {
                          int note = data[1] & 0xFF;
                          if (!loadedDrums[note])
                          {
                              loadedDrums[note] = true;
                              loadPatchOnDemand(128, note);
                          }
                      }
                  }
              }
          }
      }

      if (audio == null) return;
      renderPaused = true;
      try { Thread.sleep(20); } catch (InterruptedException ignored) {
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
      int bankNum = (ch == 9) ? 128 : 0; // Channel 10 is drums
      engine.setProgram(ch, program);
      loadPatchOnDemand(bankNum, program);
    }
  }

    private void loadPatchOnDemand(int bankNum, int program) {
        int engineProgramId = (bankNum == 128) ? program + 128 : program;
        if (engine.hasPatch(engineProgramId) || failedPatches.contains(engineProgramId)) return;
        if (bank != null) {
            bank.getPatchPath(bankNum, program).ifPresentOrElse(path -> {
                try {
                    String filename = path.toLowerCase(java.util.Locale.ROOT).endsWith(".pat") ? path : path + ".pat";
                    File patFile = bank.getRootDir().resolve(filename).toFile();
                    if (patFile.exists()) {
                        try (java.io.FileInputStream in = new java.io.FileInputStream(patFile)) {
                            engine.loadPatch(engineProgramId, GusPatchReader.read(in));
                        }
                    } else {
                        failedPatches.add(engineProgramId);
                    }
                } catch (Exception e) {
                    failedPatches.add(engineProgramId);
                }
            }, () -> failedPatches.add(engineProgramId));
        }
    }
  @Override
  public void panic() {
    engine.getActiveVoices().clear();
    if (audio != null)
      audio.flush();
  }

  @SuppressWarnings("EmptyCatch")
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

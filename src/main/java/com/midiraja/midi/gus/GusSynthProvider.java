/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.midiraja.midi.gus;

import com.midiraja.midi.MidiPort;
import com.midiraja.midi.NativeAudioEngine;
import com.midiraja.midi.SoftSynthProvider;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

@SuppressWarnings("ThreadPriorityCheck")
public class GusSynthProvider implements SoftSynthProvider
{
    private final NativeAudioEngine audio;
    private final GusEngine engine;
    private final @Nullable GusBank bank;
    private final Set<Integer> failedPatches = Collections.synchronizedSet(new HashSet<>());
    private final int bitDepth;
    private final boolean pwmMode;
    
    private @Nullable Thread renderThread;
    private volatile boolean running = false;
    private volatile boolean renderPaused = false;

    public GusSynthProvider(NativeAudioEngine audio, @Nullable String patchDir)
    {
        this(audio, patchDir, 16, false);
    }

    public GusSynthProvider(NativeAudioEngine audio, @Nullable String patchDir, int bitDepth, boolean pwmMode)
    {
        this.audio = audio;
        this.engine = new GusEngine(44100);
        this.bank = resolveBank(patchDir);
        this.bitDepth = Math.max(1, Math.min(16, bitDepth));
        this.pwmMode = pwmMode;
    }

    private @Nullable GusBank resolveBank(@Nullable String userPath)
    {
        // 1. If user provided a path, use it.
        if (userPath != null)
        {
            return new GusBank(Path.of(userPath));
        }

        // 2. Search standard system paths
        String homeDir = System.getProperty("user.home");
        String[] baseDirs = {
            ".",
            homeDir + "/.midiraja",
            homeDir + "/.config/midiraja",
            homeDir + "/.local/share/midiraja",
            "/opt/homebrew/share/midra",
            "/opt/homebrew/share/midiraja",
            "/usr/local/share/midra",
            "/usr/local/share/midiraja",
            "/usr/share/midra",
            "/usr/share/midiraja"
        };
        String[] patchSetNames = {"eawpats", "dgguspat", "freepats", "gus", ""};

        for (String baseDir : baseDirs)
        {
            for (String patchName : patchSetNames)
            {
                Path p = patchName.isEmpty() ? Path.of(baseDir) : Path.of(baseDir, patchName);
                if (Files.isDirectory(p) && (Files.exists(p.resolve("gus.cfg")) || Files.exists(p.resolve("timidity.cfg"))))
                {
                    return new GusBank(p);
                }
            }
        }

        // 3. Last fallback: Check JAR resources (embedded freepats)
        // We look for 'gus/freepats/timidity.cfg' inside the classpath
        InputStream embeddedCfg = getClass().getResourceAsStream("/gus/freepats/timidity.cfg");
        if (embeddedCfg != null)
        {
            try { embeddedCfg.close(); } catch (IOException e) { /* ignored */ }
            return new GusBank(getClass().getClassLoader(), "gus/freepats");
        }

        return null;
    }

    @Override
    public List<MidiPort> getOutputPorts()
    {
        String name = bank != null ? "GUS (" + bank.getPatchSetName() + ")" : "GUS (No patches found)";
        if (bitDepth < 16) {
            name += " [" + bitDepth + "-Bit Quantized]";
        }
        if (pwmMode) {
            name += " [over 1-Bit PWM]";
        }
        return List.of(new MidiPort(0, "Midiraja Pure Java " + name));
    }

    @Override
    public void openPort(int portIndex) throws Exception
    {
        if (bank != null)
        {
            if (bank.getRootDir() != null)
            {
                Path cfgPath = bank.getRootDir().resolve("gus.cfg");
                if (!Files.exists(cfgPath))
                {
                    cfgPath = bank.getRootDir().resolve("timidity.cfg");
                }
                if (Files.exists(cfgPath))
                {
                    bank.loadConfig(Files.readString(cfgPath, StandardCharsets.US_ASCII));
                }
            }
            else
            {
                // Resources mode
                try (InputStream in = getClass().getResourceAsStream("/gus/freepats/timidity.cfg"))
                {
                    if (in != null) bank.loadConfig(in);
                }
            }
            
            // Auto-load bank 0, program 0 (Piano) as fallback
            preloadPatch(0, 0);
        }
        
        if (audio != null)
        {
            audio.init(44100, 2, 4096);
            startRenderThread();
        }
    }

    private void preloadPatch(int bankNum, int program)
    {
        int engineId = (bankNum == 128) ? program + 128 : program;
        if (bank == null || engine.hasPatch(engineId)) return;
        
        bank.getPatchMapping(bankNum, program).ifPresent(path -> {
            try (InputStream in = bank.openPatchStream(path).orElse(null))
            {
                if (in != null)
                {
                    engine.loadPatch(engineId, GusPatchReader.read(in));
                }
            }
            catch (Exception e) { /* ignored */ }
        });
    }

    @Override
    public void loadSoundbank(String path) throws Exception
    {
        if (bank != null)
        {
            File f = new File(path);
            if (f.isFile())
            {
                try (InputStream in = new FileInputStream(f)) { bank.loadConfig(in); }
            }
        }
    }

    private void startRenderThread()
    {
        running = true;
        renderThread = new Thread(() -> {
            final int framesToRender = 512;
            short[] pcmBuffer = new short[framesToRender * 2];
            float[] left = new float[framesToRender];
            float[] right = new float[framesToRender];
            
            double errorAccumulatorLeft = 0.0;
            double errorAccumulatorRight = 0.0;
            final double qSteps = Math.pow(2, bitDepth - 1) - 1; // Creates odd number of levels to perfectly preserve 0.0

            while (running)
            {
                if (renderPaused)
                {
                    try { Thread.sleep(1); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                    continue;
                }

                for (int i = 0; i < framesToRender; i++) { left[i] = 0; right[i] = 0; }
                engine.render(left, right, framesToRender);

                for (int i = 0; i < framesToRender; i++)
                {
                    float l = Math.max(-1.0f, Math.min(1.0f, left[i]));
                    float r = Math.max(-1.0f, Math.min(1.0f, right[i]));

                    // Stage 1: N-Bit Data Quantization (Bitcrusher)
                    if (bitDepth < 16) {
                        l = (float) (Math.round(l * qSteps) / qSteps);
                        r = (float) (Math.round(r * qSteps) / qSteps);
                    }

                    // Stage 2: 1-Bit PWM Modulator (Hardware delivery simulation)
                    if (pwmMode) {
                        // Noise Gate: Stop toggling during absolute silence
                        if (l == 0.0f && r == 0.0f && Math.abs(errorAccumulatorLeft) < 0.01 && Math.abs(errorAccumulatorRight) < 0.01) {
                            errorAccumulatorLeft = 0.0;
                            errorAccumulatorRight = 0.0;
                            pcmBuffer[i * 2] = 0;
                            pcmBuffer[i * 2 + 1] = 0;
                            continue;
                        }

                        // Leaky accumulator + Dither
                        double targetL = l + (errorAccumulatorLeft * 0.99) + ((Math.random() - 0.5) * 0.01);
                        double targetR = r + (errorAccumulatorRight * 0.99) + ((Math.random() - 0.5) * 0.01);
                        
                        double outL = targetL > 0.0 ? 1.0 : -1.0;
                        double outR = targetR > 0.0 ? 1.0 : -1.0;
                        
                        errorAccumulatorLeft = targetL - outL;
                        errorAccumulatorRight = targetR - outR;

                        // Output pure 1 or -1 (scaled to 12000 so it's not deafening)
                        pcmBuffer[i * 2] = (short) (outL * 12000);
                        pcmBuffer[i * 2 + 1] = (short) (outR * 12000);
                    } else {
                        // Standard DAC analog output
                        pcmBuffer[i * 2] = (short) (l * 32767);
                        pcmBuffer[i * 2 + 1] = (short) (r * 32767);
                    }
                }

                if (audio != null) audio.push(pcmBuffer);
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
                    byte[] data = track.get(i).getMessage().getMessage();
                    if (data == null || data.length < 1) continue;
                    int status = data[0] & 0xFF;
                    if ((status & 0xF0) == 0xC0 && data.length >= 2)
                    {
                        int ch = status & 0x0F;
                        int prog = data[1] & 0xFF;
                        if (ch != 9 && !loadedPrograms[prog]) { loadedPrograms[prog] = true; loadPatchOnDemand(0, prog); }
                    }
                    else if ((status & 0xF0) == 0x90 && data.length >= 3 && (status & 0x0F) == 9)
                    {
                        int note = data[1] & 0xFF;
                        if (!loadedDrums[note]) { loadedDrums[note] = true; loadPatchOnDemand(128, note); }
                    }
                }
            }
        }

        if (audio == null) return;
        renderPaused = true;
        try { Thread.sleep(20); } catch (InterruptedException ignored) {}
        audio.flush();
        engine.getActiveVoices().clear();
    }

    @Override
    public void onPlaybackStarted() { renderPaused = false; }

    @Override
    public void sendMessage(byte[] data)
    {
        if (data == null || data.length < 1) return;
        int status = data[0] & 0xFF;
        int cmd = status & 0xF0;
        int ch = status & 0x0F;

        if (cmd == 0x90 && data.length >= 3)
        {
            int note = data[1] & 0xFF;
            int velocity = data[2] & 0xFF;
            if (velocity > 0) engine.noteOn(ch, note, velocity);
            else engine.noteOff(ch, note);
        }
        else if (cmd == 0x80 && data.length >= 3) engine.noteOff(ch, data[1] & 0xFF);
        else if (cmd == 0xC0 && data.length >= 2)
        {
            int prog = data[1] & 0xFF;
            int bankNum = (ch == 9) ? 128 : 0;
            engine.setProgram(ch, prog);
            loadPatchOnDemand(bankNum, prog);
        }
    }

    private void loadPatchOnDemand(int bankNum, int program)
    {
        int engineId = (bankNum == 128) ? program + 128 : program;
        if (engine.hasPatch(engineId) || failedPatches.contains(engineId)) return;
        
        if (bank != null)
        {
            bank.getPatchMapping(bankNum, program).ifPresentOrElse(path -> {
                try (InputStream in = bank.openPatchStream(path).orElse(null))
                {
                    if (in != null) engine.loadPatch(engineId, GusPatchReader.read(in));
                    else failedPatches.add(engineId);
                }
                catch (Exception e) { failedPatches.add(engineId); }
            }, () -> failedPatches.add(engineId));
        }
    }

    @Override
    public void panic() { engine.getActiveVoices().clear(); if (audio != null) audio.flush(); }

    @SuppressWarnings("EmptyCatch")
    @Override
    public void closePort()
    {
        running = false;
        if (renderThread != null)
        {
            renderThread.interrupt();
            try { renderThread.join(500); } catch (InterruptedException ignored) {}
        }
        if (audio != null) audio.close();
        engine.getActiveVoices().clear();
    }
}

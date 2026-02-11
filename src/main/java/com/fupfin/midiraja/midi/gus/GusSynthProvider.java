/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.midi.gus;

import com.fupfin.midiraja.midi.AudioEngine;

import com.fupfin.midiraja.dsp.AudioProcessor;
import com.fupfin.midiraja.dsp.OneBitAcousticSimulator;
import com.fupfin.midiraja.midi.MidiPort;
import com.fupfin.midiraja.midi.NativeAudioEngine;
import com.fupfin.midiraja.midi.SoftSynthProvider;
import java.util.ArrayList;
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
/**
 * A Gravis Ultrasound (GUS) soft-synth provider.
 * 
 * <p>This engine emulates the classic GF1 wavetable synthesis and supports
 * authentic retro audio features like RealSound (6-bit PWM) and modular DSP filters.
 */
public class GusSynthProvider implements SoftSynthProvider
{
    private final com.fupfin.midiraja.dsp.@org.jspecify.annotations.Nullable AudioProcessor audioOut;
    private final GusEngine engine;
    private final @Nullable GusBank bank;
    private final Set<Integer> failedPatches = Collections.synchronizedSet(new HashSet<>());
    private final @Nullable String oneBitMode;
    private final List<AudioProcessor> dspPipeline = new ArrayList<>();
    
    private @Nullable Thread renderThread;
    private volatile boolean running = false;
    private volatile boolean renderPaused = false;

    /**
     * Constructs a customizable GUS provider with 1-Bit acoustic options.
     * @param audioOut The audio processor pipeline.
     * @param patchDir Path to the GUS patch directory.
     * @param oneBitMode 1-Bit acoustic simulation mode ("pwm", "dsd", or null to disable).
     */
    public GusSynthProvider(com.fupfin.midiraja.dsp.@org.jspecify.annotations.Nullable AudioProcessor audioOut, @Nullable String patchDir, @Nullable String oneBitMode)
    {
        this.audioOut = audioOut;
        this.engine = new GusEngine(44100);
        this.bank = resolveBank(patchDir);
        this.oneBitMode = oneBitMode != null ? oneBitMode.toLowerCase(java.util.Locale.ROOT) : null;
        
        // Assemble the modular DSP pipeline
        if (this.oneBitMode != null) {
            dspPipeline.add(new OneBitAcousticSimulator(44100, this.oneBitMode));
        }
    }

    public GusSynthProvider(com.fupfin.midiraja.dsp.@org.jspecify.annotations.Nullable AudioProcessor audioOut, @Nullable String patchDir)
    {
        this(audioOut, patchDir, null);
    }

    private @Nullable GusBank resolveBank(@Nullable String userPath)
    {
        if (userPath != null) return new GusBank(Path.of(userPath));

        String homeDir = System.getProperty("user.home");
        String[] baseDirs = {
            ".", homeDir + "/.midiraja", homeDir + "/.config/midiraja", homeDir + "/.local/share/midiraja",
            "/opt/homebrew/share/midra", "/opt/homebrew/share/midiraja", "/usr/local/share/midra",
            "/usr/local/share/midiraja", "/usr/share/midra", "/usr/share/midiraja"
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
        String name = bank != null ? "GUS (" + bank.getPatchSetName() + ")" : "GUS (No patches)";
        
        if ("pwm".equals(oneBitMode)) {
            name += " [RealSound]";
        } else if (oneBitMode != null) {
            name += " [" + oneBitMode.toUpperCase(java.util.Locale.ROOT) + "]";
        }
        
        return List.of(new MidiPort(0, name));
    }

    @Override
    public void openPort(int portIndex) throws Exception
    {
        if (bank != null)
        {
            if (bank.getRootDir() != null)
            {
                Path cfgPath = bank.getRootDir().resolve("gus.cfg");
                if (!Files.exists(cfgPath)) cfgPath = bank.getRootDir().resolve("timidity.cfg");
                if (Files.exists(cfgPath)) bank.loadConfig(Files.readString(cfgPath, StandardCharsets.US_ASCII));
            }
            else
            {
                try (InputStream in = getClass().getResourceAsStream("/gus/freepats/timidity.cfg"))
                {
                    if (in != null) bank.loadConfig(in);
                }
            }
            preloadPatch(0, 0);
        }
        if (audioOut != null) startRenderThread();
    }

    private void preloadPatch(int bankNum, int program)
    {
        int engineId = (bankNum == 128) ? program + 128 : program;
        if (bank == null || engine.hasPatch(engineId)) return;
        bank.getPatchMapping(bankNum, program).ifPresent(path -> {
            try (InputStream in = bank.openPatchStream(path).orElse(null))
            {
                if (in != null) engine.loadPatch(engineId, GusPatchReader.read(in));
            }
            catch (Exception e) {
            System.err.println("[NativeBridge Error] " + e.getMessage()); /* ignored */ }
        });
    }

    @Override public void loadSoundbank(String path) throws Exception { /* not used directly */ }

    private void startRenderThread()
    {
        running = true;
        renderThread = new Thread(() -> {
            final int framesToRender = 512;
            short[] pcmBuffer = new short[framesToRender * 2];
            float[] left = new float[framesToRender];
            float[] right = new float[framesToRender];

            while (running)
            {
                if (renderPaused)
                {
                    try { Thread.sleep(1); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                    continue;
                }

                for (int i = 0; i < framesToRender; i++) { left[i] = 0; right[i] = 0; }
                engine.render(left, right, framesToRender);
                
                // Pass through modular DSP pipeline
                for (AudioProcessor proc : dspPipeline) {
                    proc.process(left, right, framesToRender);
                }

                for (int i = 0; i < framesToRender; i++)
                {
                    // GUS is already loud, no additional gain needed. 
                    // Tanh ensures we never clip if polyphony gets crazy.
                    pcmBuffer[i * 2] = (short) (Math.tanh(left[i]) * 32767);
                    pcmBuffer[i * 2 + 1] = (short) (Math.tanh(right[i]) * 32767);
                }
                if (audioOut != null) audioOut.processInterleaved(pcmBuffer, framesToRender, 2);
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
        
        renderPaused = true;
        try { Thread.sleep(20); } catch (InterruptedException ignored) {}
        if (audioOut != null) audioOut.reset();
        engine.getActiveVoices().clear();
    }

    @Override public void onPlaybackStarted() { renderPaused = false; }

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
                catch (Exception e) {
            System.err.println("[NativeBridge Error] " + e.getMessage()); failedPatches.add(engineId); }
            }, () -> failedPatches.add(engineId));
        }
    }

    @Override public void panic() { 
        engine.getActiveVoices().clear(); 
        if (audioOut != null) audioOut.reset();
        renderPaused = true; 
        for (com.fupfin.midiraja.dsp.AudioProcessor proc : dspPipeline) proc.reset(); 
    }

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
        
        engine.getActiveVoices().clear();
    }
}

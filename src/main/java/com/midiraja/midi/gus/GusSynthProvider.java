/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.midiraja.midi.gus;

import com.midiraja.dsp.AudioProcessor;
import com.midiraja.dsp.AutoFlushGate;
import com.midiraja.dsp.NoiseShapedQuantizer;
import com.midiraja.dsp.PwmAcousticSimulator;
import com.midiraja.dsp.ReconstructionFilter;
import com.midiraja.midi.MidiPort;
import com.midiraja.midi.NativeAudioEngine;
import com.midiraja.midi.SoftSynthProvider;
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
public class GusSynthProvider implements SoftSynthProvider
{
    private final NativeAudioEngine audio;
    private final GusEngine engine;
    private final @Nullable GusBank bank;
    private final Set<Integer> failedPatches = Collections.synchronizedSet(new HashSet<>());
    private final int bitDepth;
    private final boolean pwmMode;
    private final List<AudioProcessor> dspPipeline = new ArrayList<>();
    
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
        
        // Assemble the modular DSP pipeline
        if (this.bitDepth < 16) {
            dspPipeline.add(new NoiseShapedQuantizer(this.bitDepth));
            dspPipeline.add(new ReconstructionFilter(0.45));
            dspPipeline.add(new AutoFlushGate(dspPipeline));
        }
        
        if (this.pwmMode) {
            dspPipeline.add(new PwmAcousticSimulator(44100));
        }
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
        String name = bank != null ? "GUS (" + bank.getPatchSetName() + ")" : "GUS (No patches found)";
        if (bitDepth < 16) name += " [" + bitDepth + "-Bit Quantized]";
        if (pwmMode) name += " [over 1-Bit PWM Speaker]";
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
        if (audio != null) { audio.init(44100, 2, 4096); startRenderThread(); }
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
            catch (Exception e) { /* ignored */ }
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
            
            double carrierPhase = -1.0;
            final double carrierStep = (18600.0 / 44100.0) * 2.0; // Original RealSound PIT timer carrier
            
            double lp1L = 0, lp1R = 0, lp2L = 0, lp2R = 0;
            double hpL = 0, hpR = 0, prevL = 0, prevR = 0;
            
            // Inter-stage DAC Reconstruction Filter states
            double dac1L = 0, dac1R = 0, dac2L = 0, dac2R = 0;
            final double dacAlpha = 0.45;
            
            // Quantization Noise Shaper states
            double qErrL = 0, qErrR = 0;
            
            final double lpAlpha = 0.20; // Warm treble cut (Cuts PWM carrier completely)
            final double hpAlpha = 0.98; // Gentle bass cut (Removes deep sub-bass)
            final double qSteps = Math.pow(2, bitDepth - 1) - 1;

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
                    double l = Math.max(-1.0, Math.min(1.0, left[i]));
                    double r = Math.max(-1.0, Math.min(1.0, right[i]));

                    // Stage 1: Quantization (Zero-centered Bitcrusher with Noise Shaping)
                    if (bitDepth < 16)
                    {
                        // Add accumulated quantization error (Leaky Integrator to prevent idle tones)
                        double targetL = l + (qErrL * 0.95);
                        double targetR = r + (qErrR * 0.95);
                        
                        // Quantize
                        double qL = Math.round(targetL * qSteps) / qSteps;
                        double qR = Math.round(targetR * qSteps) / qSteps;
                        
                        // Feedback error
                        qErrL = targetL - qL;
                        qErrR = targetR - qR;
                        
                        l = qL;
                        r = qR;
                        
                        // Stage 1.5: Inter-stage DAC Reconstruction Filter
                        // We must smooth the harsh staircases before they hit the PWM carrier,
                        // otherwise the sharp edges interact with the PWM carrier causing
                        // severe intermodulation distortion (Aliasing Sizzle).
                        dac1L += dacAlpha * (l - dac1L);
                        dac1R += dacAlpha * (r - dac1R);
                        dac2L += dacAlpha * (dac1L - dac2L);
                        dac2R += dacAlpha * (dac1R - dac2R);
                        
                        // Force flush to absolute zero to prevent floating-point asymptotes
                        // from keeping the Noise Gate open forever.
                        if (l == 0.0 && r == 0.0 && Math.abs(dac2L) < 1e-5 && Math.abs(dac2R) < 1e-5) {
                            dac1L = 0; dac2L = 0;
                            dac1R = 0; dac2R = 0;
                            qErrL = 0; qErrR = 0;
                        }
                        
                        l = dac2L;
                        r = dac2R;
                    }

                    // Stage 2: Hardware Delivery Simulation (PWM or Standard DAC)
                    if (pwmMode)
                    {
                        // 32x Oversampling with Boxcar FIR Filter to eliminate Nyquist Aliasing.
                        double sumL = 0.0;
                        double sumR = 0.0;
                        
                        for (int over = 0; over < 32; over++) {
                            carrierPhase += carrierStep / 32.0;
                            if (carrierPhase > 1.0) carrierPhase -= 2.0;
                            sumL += (l > carrierPhase ? 1.0 : -1.0);
                            sumR += (r > carrierPhase ? 1.0 : -1.0);
                        }
                        
                        double bitL = sumL / 32.0;
                        double bitR = sumR / 32.0;

                        if (l == 0.0 && r == 0.0) {
                            bitL = 0; bitR = 0;
                            lp1L *= 0.9; lp1R *= 0.9; lp2L *= 0.9; lp2R *= 0.9;
                            hpL = 0; hpR = 0;
                        }

                        // 2-Stage Low-Pass (Simulates physical cone inertia)
                        lp1L += lpAlpha * (bitL - lp1L);
                        lp1R += lpAlpha * (bitR - lp1R);
                        lp2L += lpAlpha * (lp1L - lp2L);
                        lp2R += lpAlpha * (lp1R - lp2R);

                        // 1-Stage High-Pass (Simulates small speaker diameter)
                        hpL = hpAlpha * (hpL + lp2L - prevL);
                        hpR = hpAlpha * (hpR + lp2R - prevR);
                        prevL = lp2L; prevR = lp2R;

                        l = Math.max(-1.0, Math.min(1.0, hpL * 1.5));
                        r = Math.max(-1.0, Math.min(1.0, hpR * 1.5));
                    }
                    // (DAC Filtering is now handled in Stage 1.5 for all modes)
                    
                    pcmBuffer[i * 2] = (short) (l * 32767);
                    pcmBuffer[i * 2 + 1] = (short) (r * 32767);
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
                catch (Exception e) { failedPatches.add(engineId); }
            }, () -> failedPatches.add(engineId));
        }
    }

    @Override public void panic() { engine.getActiveVoices().clear(); if (audio != null) audio.flush(); }

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

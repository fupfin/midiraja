/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import com.fupfin.midiraja.MidirajaCommand;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import com.fupfin.midiraja.midi.NativeAudioEngine;
import com.fupfin.midiraja.cli.AudioLibResolver;
import java.util.Optional;

/**
 * Plays MIDI files using a 1-bit PC Speaker / Apple II style synthesizer.
 */
@Command(name = "beep", aliases = {"1bit"}, mixinStandardHelpOptions = true,
    description = "Play with a 1-bit digital logic synthesizer.",
    footer = {"", "Experience the extreme limitations of 1980s computer audio via 1-bit logic gates."})
public class BeepCommand implements Callable<Integer>
{
    @ParentCommand private @org.jspecify.annotations.Nullable MidirajaCommand parent;

    @Mixin @org.jspecify.annotations.Nullable private CommonOptions common;

    @Option(names = {"--synth"}, defaultValue = "square", description = "Synthesis generation algorithm:\n" +
        "  'fm'     (Yamaha-like Phase/Frequency Modulation using smooth sine waves)\n" +
        "  'xor'    (Historical Tim Follin-style Ring Modulation using intersecting square waves)\n" +
        "  'square' (Classic 8-bit Square Wave with LFO Vibrato and Duty Sweep)")
    private String synth = "square";

    @Option(names = {"--mux"}, defaultValue = "xor", description = "Multiplexing algorithm:\n" +
        "  'dsd' (Default, Delta-Sigma Modulation, highest modern fidelity)\n" +
        "  'pwm' (Analog Summing -> PWM, clean with 22kHz retro carrier whine)\n" +
        "  'tdm' (Time-Division Multiplexing, micro-ticking)\n" +
        "  'xor' (Historical 1981 Apple II logic, gritty Ring Modulation)")
    private String mux = "dsd";

    @Option(names = {"--voices"}, defaultValue = "2", description = "Polyphony per virtual unit (1-4). Default: 2")
    private int voices = 2;

    @Option(names = {"--fm-ratio"}, defaultValue = "1.0", description = "Modulator frequency ratio (e.g., 1.0 for clean, 3.5 for metallic). Default: 1.0")
    private double fmRatio = 2.0;

    @Option(names = {"--fm-index"}, defaultValue = "1.1", description = "Modulation intensity peak. Default: 1.1")
    private double fmIndex = 2.0;

    @Option(names = {"-q", "--quality"}, defaultValue = "1",
        description = "Audio quality level from 1 to 6. (1 = Authentic hardware noise, 6 = Modern studio pristine).")
    private int qualityLevel = 1;

    @Parameters(paramLabel = "FILE", description = "One or more MIDI files to play")
    private List<File> files = new ArrayList<>();

    @Override public Integer call() throws Exception
    {
        if (files.isEmpty())
        {
            System.err.println("Error: No MIDI files specified.");
            return 1;
        }

        var p = java.util.Objects.requireNonNull(parent);
        String audioLib = AudioLibResolver.resolve();
        NativeAudioEngine audio = new NativeAudioEngine(audioLib);
        audio.init(44100, 1, 4096);
        com.fupfin.midiraja.dsp.AudioProcessor pipeline = new com.fupfin.midiraja.dsp.FloatToShortSink(audio, 1);
        if (common != null && common.oneBitMode.isPresent()) {
            pipeline = new com.fupfin.midiraja.dsp.OneBitAcousticSimulatorFilter(true, common.oneBitMode.get(), pipeline);
        }
        if (common != null && common.eightBitMode) {
            pipeline = new com.fupfin.midiraja.dsp.EightBitQuantizerFilter(true, pipeline);
        }
        
        
        // Map user's 1~6 quality level exponentially (1 -> 1x, 2 -> 2x, 3 -> 4x, ..., 6 -> 32x)
        int clampedLevel = Math.max(1, Math.min(6, qualityLevel));
        int actualOversample = 1 << (clampedLevel - 1);
        
        var provider = new com.fupfin.midiraja.midi.beep.BeepSynthProvider(pipeline, voices, fmRatio, fmIndex, actualOversample, mux.toLowerCase(java.util.Locale.ROOT), synth.toLowerCase(java.util.Locale.ROOT));

        var runner = new PlaybackRunner(p.getOut(), p.getErr(), p.getTerminalIO(),
                                        p.isInTestMode());
        int result = runner.run(provider, true, Optional.empty(),
                          Optional.empty(), files,
                          java.util.Objects.requireNonNull(common));
        
        provider.closePort();
        return result;
    }
}

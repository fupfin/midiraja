/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.util.Locale.ROOT;
import static java.util.Objects.requireNonNull;

import com.fupfin.midiraja.MidirajaCommand;
import com.fupfin.midiraja.io.AppLogger;
import com.fupfin.midiraja.dsp.AudioProcessor;

import com.fupfin.midiraja.dsp.FloatToShortSink;
import com.fupfin.midiraja.midi.NativeAudioEngine;
import com.fupfin.midiraja.midi.beep.BeepSynthProvider;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/**
 * Plays MIDI files using a 1-bit PC Speaker / Apple II style synthesizer.
 */
@Command(name = "beep", aliases = {"1bit"}, mixinStandardHelpOptions = true,
        description = "1-bit digital logic synthesizer (Apple II / PC Speaker style).", footer = {"",
                "Experience the extreme limitations of 1980s computer audio via 1-bit logic gates."})
public class BeepCommand implements Callable<Integer>
{
    @ParentCommand
    private @Nullable MidirajaCommand parent;

    @Mixin
    private final CommonOptions common = new CommonOptions();

    @Option(names = {"--synth"}, defaultValue = "square",
            description = "Synthesis generation algorithm:\n"
                    + "  'fm'     (Yamaha-like Phase/Frequency Modulation using smooth sine waves)\n"
                    + "  'xor'    (Historical Tim Follin-style Ring Modulation using intersecting square waves)\n"
                    + "  'square' (Classic 8-bit Square Wave with LFO Vibrato and Duty Sweep)")
    private String synth = "square";

    @Option(names = {"--mux"}, defaultValue = "xor",
            description = "Multiplexing algorithm:\n"
                    + "  'dsd' (Default, Delta-Sigma Modulation, highest modern fidelity)\n"
                    + "  'pwm' (Analog Summing -> PWM, clean with 22kHz retro carrier whine)\n"
                    + "  'tdm' (Time-Division Multiplexing, micro-ticking)\n"
                    + "  'xor' (Historical 1981 Apple II logic, gritty Ring Modulation)")
    private String mux = "dsd";

    @Option(names = {"--voices"}, defaultValue = "2",
            description = "Polyphony per virtual unit (1-4). Default: 2")
    private int voices = 2;

    @Option(names = {"--fm-ratio"}, defaultValue = "1.0",
            description = "Modulator frequency ratio (e.g., 1.0 for clean, 3.5 for metallic). Default: 1.0")
    private double fmRatio = 2.0;

    @Option(names = {"--fm-index"}, defaultValue = "1.1",
            description = "Modulation intensity peak. Default: 1.1")
    private double fmIndex = 2.0;

    @Option(names = {"-q", "--quality"}, defaultValue = "1",
            description = "Audio quality level from 1 to 6. (1 = Authentic hardware noise, 6 = Modern studio pristine).")
    private int qualityLevel = 1;

    @Parameters(paramLabel = "FILE", description = "One or more MIDI files to play")
    private List<File> files = new ArrayList<>();

    @Override
    public Integer call() throws Exception
    {
        AppLogger.configure(common.verbose, common.debug);
        if (files.isEmpty())
        {
            System.err.println("Error: No MIDI files specified.");
            return 1;
        }

        var p = requireNonNull(parent);
        String audioLib = AudioLibResolver.resolve();
        NativeAudioEngine audio = new NativeAudioEngine(audioLib);
        audio.init(44100, 1, 4096);
        if (common.dumpWav.isPresent())
        {
            audio.enableDump(common.dumpWav.get());
        }
        AudioProcessor pipeline = new FloatToShortSink(audio, 1);
        pipeline = common.wrapRetroPipeline(pipeline);

        // Map user's 1~6 quality level exponentially (1 -> 1x, 2 -> 2x, 3 -> 4x, ..., 6 -> 32x)
        int clampedLevel = max(1, min(6, qualityLevel));
        int actualOversample = 1 << (clampedLevel - 1);

        var provider = new BeepSynthProvider(pipeline, voices,
                fmRatio, fmIndex, actualOversample, mux.toLowerCase(ROOT),
                synth.toLowerCase(ROOT));

        var runner =
                new PlaybackRunner(p.getOut(), p.getErr(), p.getTerminalIO(), p.isInTestMode());
        int result = runner.run(provider, true, Optional.empty(), Optional.empty(), files,
                requireNonNull(common));

        provider.closePort();
        return result;
    }
}

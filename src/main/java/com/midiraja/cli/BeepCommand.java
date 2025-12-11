/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.midiraja.cli;

import com.midiraja.MidirajaCommand;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import com.midiraja.midi.NativeAudioEngine;
import java.util.Optional;

/**
 * Plays MIDI files using a 1-bit PC Speaker / Apple II style synthesizer.
 */
@Command(name = "beep", mixinStandardHelpOptions = true,
    description = "Play with a 1-bit PC Speaker / Apple II style synthesizer.",
    footer = {"",
        "Experience the extreme limitations of 1980s computer audio.",
        "Modes:",
        "  sixteentet : 8-Core synchronized Apple II cluster (16-note polyphony with Karateka-style modulation) (16-note polyphony with Ring Modulation). to simulate chords (Classic 8-bit style).",
        "  pwm      : Uses 1-bit Pulse Width Modulation to forcefully mix multiple channels."})
public class BeepCommand implements Callable<Integer>
{
    @ParentCommand private @org.jspecify.annotations.Nullable MidirajaCommand parent;

    @Mixin @org.jspecify.annotations.Nullable private CommonOptions common;

    @Option(names = {"--voices"}, defaultValue = "2", description = "Polyphony per virtual Apple II unit (1-4). Default: 2")
    private int voices = 2;

    @Option(names = {"--fm-ratio"}, defaultValue = "1.0", description = "FM Modulator frequency ratio (e.g., 1.0 for clean, 3.5 for metallic). Default: 1.0")
    private double fmRatio = 1.0;

    @Option(names = {"--fm-index"}, defaultValue = "1.1", description = "FM Modulation intensity peak. Default: 1.1")
    private double fmIndex = 1.1;

    @Option(names = {"-o", "--oversample"}, defaultValue = "32",
        description = "Internal DSP oversampling factor (1 to simulate original hardware aliasing, 32 for studio quality).")
    private int oversample = 32;

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
        
        var provider = new com.midiraja.midi.beep.BeepSynthProvider(audio, voices, fmRatio, fmIndex, oversample);

        var runner = new PlaybackRunner(p.getOut(), p.getErr(), p.getTerminalIO(),
                                        p.isInTestMode());
        int result = runner.run(provider, true, Optional.empty(),
                          Optional.empty(), files,
                          java.util.Objects.requireNonNull(common));
        
        provider.closePort();
        return result;
    }
}

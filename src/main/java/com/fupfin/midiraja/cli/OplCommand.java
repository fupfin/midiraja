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
import java.util.Optional;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/**
 * Plays MIDI files using the built-in libADLMIDI OPL2/OPL3 FM synthesizer.
 */
@Command(name = "opl", mixinStandardHelpOptions = true,
        description = "Play with OPL2/OPL3 FM synthesis (AdLib / Sound Blaster).",
        footer = {"",
                "Emulator IDs: 0=Nuked OPL3 v1.8, 1=Nuked v1.7.4, 5=ESFMu, 6=MAME OPL2, 7=YMFM OPL2, "
                        + "8=YMFM OPL3"})
public class OplCommand implements Callable<Integer>
{
    @ParentCommand
    @Nullable
    private MidirajaCommand parent;

    @Parameters(arity = "1..*", description = "MIDI files, directories, or .m3u playlists to play.")
    private List<File> files = new ArrayList<>();

    @Option(names = {"-b", "--bank"}, arity = "0..1", fallbackValue = "",
            description = "Embedded bank number (0-75) or path to a .wopl file. Default: bank 0 (General MIDI).")
    private Optional<String> bank = Optional.empty();

    @Mixin
    private FmSynthOptions fmOptions = new FmSynthOptions();



    @Mixin
    private FxOptions fxOptions = new FxOptions();

    @Mixin
    private CommonOptions common = new CommonOptions();

    @Override
    public Integer call() throws Exception
    {
        var p = java.util.Objects.requireNonNull(parent);

        String audioLib = AudioLibResolver.resolve();
        var audio = new com.fupfin.midiraja.midi.NativeAudioEngine(audioLib);
        audio.init(44100, 2, 4096);
        if (common != null && common.dumpWav.isPresent())
        {
            audio.enableDump(common.dumpWav.get());
        }

        com.fupfin.midiraja.dsp.AudioProcessor pipeline =
                new com.fupfin.midiraja.dsp.FloatToShortSink(audio);
        pipeline = common.wrapRetroPipeline(pipeline);
        pipeline = fxOptions.wrapFxPipeline(pipeline);
        if (fxOptions.needsFloatConversion(common))
        {
            pipeline = new com.fupfin.midiraja.dsp.ShortToFloatFilter(pipeline);
        }

        var bridge = new com.fupfin.midiraja.midi.FFMAdlMidiNativeBridge();
        var provider = new com.fupfin.midiraja.midi.AdlMidiSynthProvider(bridge, pipeline,
                fmOptions.emulator, fmOptions.chips, common.retroMode.orElse(null));

        // Resolve bank argument: "" → "bank:0", "14" → "bank:14", path → path
        String soundbankArg =
                bank.map(v -> v.isEmpty() ? "bank:0" : (v.matches("\\d+") ? "bank:" + v : v))
                        .orElse("bank:0");

        var runner =
                new PlaybackRunner(p.getOut(), p.getErr(), p.getTerminalIO(), p.isInTestMode());
        return runner.run(provider, true, Optional.empty(), Optional.of(soundbankArg), files,
                common);
    }
}

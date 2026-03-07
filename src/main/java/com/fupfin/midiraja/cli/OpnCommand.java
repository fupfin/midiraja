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
 * Plays MIDI files using the built-in libOPNMIDI OPN2/OPNA FM synthesizer.
 */
@Command(name = "opn", mixinStandardHelpOptions = true,
        description = "Play with OPN2 FM synthesis (Sega Genesis / PC-98).",
        footer = {"",
                "Emulator IDs: 0=MAME YM2612, 1=Nuked YM3438, 2=GENS, 3=YMFM OPN2, 4=NP2 OPNA, 5=MAME "
                        + "YM2608, 6=YMFM OPNA"})
public class OpnCommand implements Callable<Integer>
{
    @ParentCommand
    @Nullable
    private MidirajaCommand parent;

    @Parameters(arity = "1..*", description = "MIDI files, directories, or .m3u playlists to play.")
    private List<File> files = new ArrayList<>();

    @Option(names = {"-b", "--bank"}, arity = "0..1", fallbackValue = "",
            description = "Path to a .wopn bank file. Default: built-in GM bank.")
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

        var bridge = new com.fupfin.midiraja.midi.FFMOpnMidiNativeBridge();
        var provider = new com.fupfin.midiraja.midi.OpnMidiSynthProvider(bridge, pipeline,
                fmOptions.emulator, fmOptions.chips, common.retroMode.orElse(null));

        // bank: empty string = default built-in GM bank; otherwise WOPN file path
        String soundbankArg = bank.orElse("");

        var runner =
                new PlaybackRunner(p.getOut(), p.getErr(), p.getTerminalIO(), p.isInTestMode());
        return runner.run(provider, true, Optional.empty(), Optional.of(soundbankArg), files,
                common);
    }
}

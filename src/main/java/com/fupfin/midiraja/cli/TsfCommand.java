/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import static java.util.Objects.requireNonNull;

import com.fupfin.midiraja.MidirajaCommand;
import com.fupfin.midiraja.midi.FFMTsfNativeBridge;
import com.fupfin.midiraja.midi.TsfSynthProvider;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/**
 * Plays MIDI files using the built-in TinySoundFont SF2 synthesizer.
 */
@Command(name = "soundfont", aliases = {"tsf", "sf2", "sf"}, mixinStandardHelpOptions = true,
        description = "Play with the built-in SoundFont synthesizer (SF2/SF3, no install needed).")
public class TsfCommand implements Callable<Integer>
{
    @ParentCommand
    @Nullable
    private MidirajaCommand parent;

    @Parameters(index = "0", description = "Path to the SoundFont (.sf2 or .sf3) file.")
    private final File soundfont = new File("");

    @Parameters(index = "1..*", arity = "1..*",
            description = "MIDI files, directories, or .m3u playlists to play.")
    private List<File> files = new ArrayList<>();

    @Mixin
    private final FxOptions fxOptions = new FxOptions();

    @Mixin
    private final CommonOptions common = new CommonOptions();

    @Override
    public Integer call() throws Exception
    {
        var p = requireNonNull(parent);

        var pipeline = FmSynthOptions.buildStereoFmPipeline(common, fxOptions);

        var bridge = new FFMTsfNativeBridge();
        var provider = new TsfSynthProvider(bridge, pipeline);

        var runner =
                new PlaybackRunner(p.getOut(), p.getErr(), p.getTerminalIO(), p.isInTestMode());
        return runner.run(provider, true, Optional.empty(), Optional.of(soundfont.getPath()), files,
                common);
    }
}

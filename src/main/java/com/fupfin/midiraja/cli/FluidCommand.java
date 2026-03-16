/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import static java.util.Objects.requireNonNull;

import com.fupfin.midiraja.MidirajaCommand;
import com.fupfin.midiraja.midi.FluidSynthProvider;
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
 * Plays MIDI files through the built-in FluidSynth SoundFont synthesizer.
 */
@Command(name = "fluid", aliases = {"fluidsynth"}, mixinStandardHelpOptions = true,
        description = "FluidSynth SoundFont playback.")
public class FluidCommand implements Callable<Integer>
{
    @ParentCommand
    @Nullable
    private MidirajaCommand parent;

    @Parameters(index = "0", description = "Path to the SoundFont (.sf2) file.")
    private final File soundfont = new File("");

    @Parameters(index = "1..*", arity = "1..*",
            description = "MIDI files, directories, or .m3u playlists to play.")
    private List<File> files = new ArrayList<>();

    @Option(names = {"--driver"},
            description = "Override the audio driver (e.g. coreaudio, dsound, alsa).")
    private Optional<String> driver = Optional.empty();

    @Mixin
    private final CommonOptions common = new CommonOptions();

    @Override
    public Integer call() throws Exception
    {
        var p = requireNonNull(parent);

        var provider = new FluidSynthProvider(driver.orElse(null));

        var runner =
                new PlaybackRunner(p.getOut(), p.getErr(), p.getTerminalIO(), p.isInTestMode());
        return runner.run(provider, true, Optional.empty(), Optional.of(soundfont.getPath()), files,
                common);
    }
}

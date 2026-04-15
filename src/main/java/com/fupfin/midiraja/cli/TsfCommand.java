/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import static java.util.Objects.requireNonNull;

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

import com.fupfin.midiraja.MidirajaCommand;
import com.fupfin.midiraja.io.AppLogger;
import com.fupfin.midiraja.midi.FFMTsfNativeBridge;
import com.fupfin.midiraja.midi.TsfSynthProvider;

/**
 * Plays MIDI files using the built-in TinySoundFont SF2 synthesizer.
 */
@Command(name = "soundfont", aliases = { "tsf", "sf2",
        "sf" }, mixinStandardHelpOptions = true, description = "Built-in SoundFont synthesizer (SF2/SF3), no install needed.", footer = {
                "",
                "The SoundFont path is optional. If omitted, the bundled FluidR3 GM SF3 is used.",
                "  midra soundfont song.mid",
                "  midra soundfont ~/soundfonts/FluidR3_GM.sf2 song.mid" })
public class TsfCommand extends PcmAudioSubcommand implements Callable<Integer>
{
    @ParentCommand
    @Nullable
    private MidirajaCommand parent;

    @Parameters(index = "0", arity = "0..1", description = "Optional: path to a SoundFont (.sf2 or .sf3) file. If a MIDI file is given here, the bundled SF3 is used.")
    @Nullable
    private File firstArg = null;

    @Parameters(index = "1..*", arity = "0..*", description = "MIDI files, directories, or .m3u playlists to play.")
    private List<File> moreFiles = new ArrayList<>();

    @Mixin
    private final CommonOptions common = new CommonOptions();

    private static boolean isSoundFontFile(File f)
    {
        String name = f.getName().toLowerCase();
        return name.endsWith(".sf2") || name.endsWith(".sf3");
    }

    private @Nullable String soundfontPath()
    {
        if (firstArg != null && isSoundFontFile(firstArg))
        {
            return firstArg.getAbsolutePath();
        }
        return findBundledSf3();
    }

    private List<File> files()
    {
        if (firstArg == null || isSoundFontFile(firstArg))
        {
            return moreFiles;
        }
        var all = new ArrayList<File>();
        all.add(firstArg);
        all.addAll(moreFiles);
        return all;
    }

    private static final String BUNDLED_SF3_NAME = "FluidR3_GM.sf3";

    public static @Nullable String findBundledSf3()
    {
        String homeDir = System.getProperty("user.home");
        var locator = ResourceLocator.withMidraDataFirst(
                homeDir + "/.local/share/midra",
                homeDir + "/.local/share/midiraja",
                homeDir + "/.config/midiraja",
                "/opt/homebrew/share/midra",
                "/opt/homebrew/share/midiraja",
                "/usr/local/share/midra",
                "/usr/local/share/midiraja",
                "/usr/share/midra",
                "/usr/share/midiraja",
                ".",
                "build/soundfonts");
        return locator.findFile("soundfonts/" + BUNDLED_SF3_NAME)
                .map(p -> p.toString())
                .orElse(null);
    }

    @Override
    public Integer call() throws Exception
    {
        AppLogger.configure(common.logLevel.orElse(null));
        var p = requireNonNull(parent);

        String sfPath = soundfontPath();
        if (sfPath == null)
        {
            p.getErr().println("Error: No SoundFont file found. Provide an .sf2/.sf3 path, "
                    + "or install midra to get the bundled FluidR3 GM SF3.");
            p.getErr().println("  midra soundfont ~/soundfonts/FluidR3_GM.sf2 song.mid");
            return 1;
        }

        var fmPipeline = FmSynthOptions.buildStereoFmPipeline(common, fxOptions);

        var bridge = new FFMTsfNativeBridge();
        var provider = new TsfSynthProvider(bridge, fmPipeline.pipeline(), common.retroMode.orElse(null));
        if (fxOptions.masterGain != null)
            provider.setMasterGain(fxOptions.masterGain);

        var runner = new PlaybackRunner(p.getOut(), p.getErr(), p.getTerminalIO(), p.isInTestMode(), fxOptions);
        runner.setSpectrumFilter(fmPipeline.spectrumFilter());
        return runner.run(provider, true, Optional.empty(), Optional.of(sfPath), files(), common, originalArgs());
    }

}

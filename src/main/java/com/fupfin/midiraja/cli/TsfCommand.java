/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import static java.util.Objects.requireNonNull;

import com.fupfin.midiraja.MidirajaCommand;
import com.fupfin.midiraja.io.AppLogger;
import com.fupfin.midiraja.midi.FFMTsfNativeBridge;
import com.fupfin.midiraja.midi.TsfSynthProvider;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

/**
 * Plays MIDI files using the built-in TinySoundFont SF2 synthesizer.
 */
@Command(name = "soundfont", aliases = {"tsf", "sf2", "sf"}, mixinStandardHelpOptions = true,
        description = "Built-in SoundFont synthesizer (SF2/SF3), no install needed.",
        footer = {"",
                "The SoundFont path is optional. If omitted, the bundled FluidR3 GM SF3 is used.",
                "  midra soundfont song.mid",
                "  midra soundfont ~/soundfonts/FluidR3_GM.sf2 song.mid"})
public class TsfCommand implements Callable<Integer>
{
    @Spec
    @Nullable
    private CommandSpec spec;

    @ParentCommand
    @Nullable
    private MidirajaCommand parent;

    @Parameters(index = "0", arity = "0..1",
            description = "Optional: path to a SoundFont (.sf2 or .sf3) file. If a MIDI file is given here, the bundled SF3 is used.")
    @Nullable
    private File firstArg = null;

    @Parameters(index = "1..*", arity = "0..*",
            description = "MIDI files, directories, or .m3u playlists to play.")
    private List<File> moreFiles = new ArrayList<>();

    @Mixin
    private final FxOptions fxOptions = new FxOptions();

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
        List<String> baseDirs = new ArrayList<>();

        String midraData = System.getenv("MIDRA_DATA");
        if (midraData != null) baseDirs.add(midraData);

        baseDirs.addAll(Arrays.asList(
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
                "build/soundfonts"));

        for (String base : baseDirs)
        {
            Path candidate = Path.of(base, "soundfonts", BUNDLED_SF3_NAME);
            if (Files.isRegularFile(candidate))
            {
                return candidate.toAbsolutePath().toString();
            }
        }
        return null;
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

        var pipeline = FmSynthOptions.buildStereoFmPipeline(common, fxOptions);

        var bridge = new FFMTsfNativeBridge();
        var provider = new TsfSynthProvider(bridge, pipeline, common.retroMode.orElse(null));
        if (fxOptions.masterGain != null) provider.setMasterGain(fxOptions.masterGain);

        var runner =
                new PlaybackRunner(p.getOut(), p.getErr(), p.getTerminalIO(), p.isInTestMode());
        runner.setFxOptions(fxOptions);
        return runner.run(provider, true, Optional.empty(), Optional.of(sfPath), files(), common, originalArgs());
    }

    private List<String> originalArgs()
    {
        var rawArgs = java.util.Objects.requireNonNull(spec).commandLine().getParseResult().originalArgs();
        return rawArgs.stream().map(token -> {
            if (!token.startsWith("-")) {
                var f = new java.io.File(token);
                if (f.exists()) return f.getAbsolutePath();
            }
            return token;
        }).collect(java.util.stream.Collectors.toList());
    }
}

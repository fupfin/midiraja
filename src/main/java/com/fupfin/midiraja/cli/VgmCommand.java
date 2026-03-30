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
import javax.sound.midi.MidiSystem;

import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import com.fupfin.midiraja.MidirajaCommand;
import com.fupfin.midiraja.io.AppLogger;
import com.fupfin.midiraja.midi.FFMTsfNativeBridge;
import com.fupfin.midiraja.midi.MidiProviderFactory;
import com.fupfin.midiraja.midi.TsfSynthProvider;
import com.fupfin.midiraja.cli.FmSynthOptions;
import com.fupfin.midiraja.vgm.VgmFileDetector;
import com.fupfin.midiraja.vgm.VgmParser;
import com.fupfin.midiraja.vgm.VgmToMidiConverter;

/**
 * Plays VGM chiptune files (SN76489 / YM2612) by converting them on the fly to MIDI sequences.
 */
@Command(name = "vgm", mixinStandardHelpOptions = true,
        description = "VGM chiptune playback (SN76489 / YM2612 \u2192 MIDI conversion).")
public class VgmCommand implements Callable<Integer>
{
    @Spec
    @Nullable
    private CommandSpec spec;

    @ParentCommand
    @Nullable
    private MidirajaCommand parent;

    @Parameters(index = "0..*", arity = "1..*",
            description = "VGM/VGZ files, directories, or .m3u playlists to play.")
    private List<File> files = new ArrayList<>();

    @Option(names = {"-p", "--port"}, description = "MIDI output port index or partial name. "
            + "If omitted, the built-in SoundFont synthesizer is used.")
    private Optional<String> port = Optional.empty();

    @Option(names = {"--export-midi"}, paramLabel = "FILE",
            description = "Convert the VGM file to MIDI and write to FILE without playing.")
    private Optional<File> exportMidi = Optional.empty();

    @Mixin
    private final FxOptions fxOptions = new FxOptions();

    @Mixin
    private final CommonOptions common = new CommonOptions();

    @Override
    public Integer call() throws Exception
    {
        AppLogger.configure(common.logLevel.orElse(null));
        var p = requireNonNull(parent);
        var mutedChannels = common.parsedMutedChannels(p.getErr());

        // --export-midi: convert the first VGM file to MIDI and write to disk, no playback.
        if (exportMidi.isPresent())
        {
            var input = files.isEmpty() ? null : files.get(0);
            if (input == null || !VgmFileDetector.isVgmFile(input))
            {
                p.getErr().println("Error: --export-midi requires a VGM/VGZ file as input.");
                return 1;
            }
            var sequence = new VgmToMidiConverter(mutedChannels).convert(new VgmParser().parse(input));
            MidiSystem.write(sequence, 1, exportMidi.get());
            p.getOut().println("Exported: " + exportMidi.get());
            return 0;
        }

        if (port.isPresent())
        {
            var provider = MidiProviderFactory.createProvider();
            var runner = new PlaybackRunner(p.getOut(), p.getErr(), p.getTerminalIO(), p.isInTestMode());
            return runner.run(provider, false, port, Optional.empty(), files, common, originalArgs());
        }

        String sfPath = TsfCommand.findBundledSf3();
        if (sfPath == null)
        {
            p.getErr().println("Error: No SoundFont file found. Use -p to select a MIDI port, "
                    + "or install midra to get the bundled FluidR3 GM SF3.");
            return 1;
        }
        var pipeline = FmSynthOptions.buildStereoFmPipeline(common, fxOptions);
        var provider = new TsfSynthProvider(new FFMTsfNativeBridge(), pipeline, common.retroMode.orElse(null));
        var runner = new PlaybackRunner(p.getOut(), p.getErr(), p.getTerminalIO(), p.isInTestMode());
        runner.setFxOptions(fxOptions);
        return runner.run(provider, true, Optional.empty(), Optional.of(sfPath), files, common, originalArgs());
    }

    private List<String> originalArgs()
    {
        var rawArgs = requireNonNull(spec).commandLine().getParseResult().originalArgs();
        return rawArgs.stream().map(token -> {
            if (!token.startsWith("-")) {
                var f = new File(token);
                if (f.exists()) return f.getAbsolutePath();
            }
            return token;
        }).collect(java.util.stream.Collectors.toList());
    }
}

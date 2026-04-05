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
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import com.fupfin.midiraja.MidirajaCommand;
import com.fupfin.midiraja.dsp.AudioProcessor;
import com.fupfin.midiraja.dsp.FloatToShortSink;
import com.fupfin.midiraja.io.AppLogger;
import com.fupfin.midiraja.midi.FFMMuntNativeBridge;
import com.fupfin.midiraja.midi.MuntSynthProvider;
import com.fupfin.midiraja.midi.NativeAudioEngine;

/**
 * Plays MIDI files through the built-in Munt MT-32 emulator.
 */
@Command(name = "munt", aliases = { "mt32", "mt32emu", "lapc1",
        "cm32l" }, mixinStandardHelpOptions = true, description = "MT-32 emulation (Roland MT-32/CM-32L).", footer = {
                "", "Requires MT32_CONTROL.ROM and MT32_PCM.ROM in the specified ROM directory." })
public class MuntCommand implements Callable<Integer>
{
    @Spec
    @Nullable
    private CommandSpec spec;

    @ParentCommand
    @Nullable
    private MidirajaCommand parent;

    @Parameters(index = "0", description = "Directory containing MT32_CONTROL.ROM and MT32_PCM.ROM.")
    private final File romDir = new File("");

    @Parameters(index = "1..*", arity = "1..*", description = "MIDI files, directories, or .m3u playlists to play.")
    private List<File> files = new ArrayList<>();

    @Mixin
    private final CommonOptions common = new CommonOptions();

    @Override
    public Integer call() throws Exception
    {
        AppLogger.configure(common.logLevel.orElse(null));
        var p = requireNonNull(parent);

        String audioLib = AudioLibResolver.resolve();
        var audio = new NativeAudioEngine(audioLib);
        audio.init(32000, 2, 4096);
        if (common.dumpWav.isPresent())
        {
            audio.enableDump(common.dumpWav.get());
        }
        AudioProcessor pipeline = new FloatToShortSink(audio);
        pipeline = common.buildDspChain(pipeline);

        var bridge = new FFMMuntNativeBridge();
        var provider = new MuntSynthProvider(bridge, pipeline);

        var runner = new PlaybackRunner(p.getOut(), p.getErr(), p.getTerminalIO(), p.isInTestMode());
        return runner.run(provider, true, Optional.empty(), Optional.of(romDir.getPath()), files,
                common, originalArgs());
    }

    private List<String> originalArgs()
    {
        var rawArgs = requireNonNull(spec).commandLine().getParseResult().originalArgs();
        return rawArgs.stream().map(this::resolveToken).collect(java.util.stream.Collectors.toList());
    }

    private String resolveToken(String token)
    {
        if (!token.startsWith("-"))
        {
            var f = new java.io.File(token);
            if (f.exists())
                return f.getAbsolutePath();
        }
        return token;
    }
}

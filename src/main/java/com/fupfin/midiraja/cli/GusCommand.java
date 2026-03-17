/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import com.fupfin.midiraja.MidirajaCommand;
import com.fupfin.midiraja.dsp.*;
import com.fupfin.midiraja.midi.NativeAudioEngine;
import com.fupfin.midiraja.midi.gus.GusSynthProvider;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

@Command(name = "patch", aliases = {"gus", "pat", "guspatch"}, mixinStandardHelpOptions = true,
        description = "GUS wavetable patches (.pat), FreePats bundled.",
        footer = {"",
                "The patch directory is optional. If omitted, FreePats is downloaded automatically.",
                "  midra patch song.mid",
                "  midra patch ~/patches/eawpats song.mid"})
public class GusCommand implements Callable<Integer>
{
    @ParentCommand
    @Nullable
    private MidirajaCommand parent;

    @Mixin
    private FxOptions fxOptions = new FxOptions();

    @Mixin
    private CommonOptions common = new CommonOptions();

    @Parameters(index = "0", arity = "0..1",
            description = "Optional: directory containing GUS .pat files. If a MIDI file is given here, FreePats is used.")
    @Nullable
    private File firstArg = null;

    @Parameters(index = "1..*", arity = "0..*",
            description = "MIDI files, directories, or .m3u playlists to play.")
    private List<File> moreFiles = new ArrayList<>();

    private @Nullable File patchDir()
    {
        if (firstArg == null || moreFiles.isEmpty()) return null;
        File f = PlaylistParser.normalize(firstArg);
        return java.nio.file.Files.isDirectory(f.toPath()) ? f : null;
    }

    private List<File> files()
    {
        if (firstArg == null) return moreFiles;
        File f = PlaylistParser.normalize(firstArg);
        // Directory-only arg: MIDI source, not patch dir.
        if (java.nio.file.Files.isDirectory(f.toPath()) && moreFiles.isEmpty())
        {
            return List.of(f);
        }
        // Directory with MIDI files: patch dir (handled by patchDir()), return only moreFiles.
        if (java.nio.file.Files.isDirectory(f.toPath()))
        {
            return moreFiles;
        }
        var all = new ArrayList<File>();
        all.add(f);
        all.addAll(moreFiles);
        return all;
    }

    @Override
    public Integer call() throws Exception
    {
        var p = Objects.requireNonNull(parent);
        String audioLib = AudioLibResolver.resolve();
        NativeAudioEngine audio = new NativeAudioEngine(audioLib);
        audio.init(44100, 2, 4096);
        if (common != null && common.dumpWav.isPresent())
        {
            audio.enableDump(common.dumpWav.get());
        }

        AudioProcessor pipeline = new FloatToShortSink(audio);
        pipeline = common.wrapRetroPipeline(pipeline);
        pipeline = Objects.requireNonNull(fxOptions).wrapFxPipeline(pipeline);
        if (Objects.requireNonNull(fxOptions).needsFloatConversion(common))
            pipeline = new ShortToFloatFilter(pipeline);

        var patchDir = patchDir();
        var provider = new GusSynthProvider(pipeline,
                patchDir != null ? patchDir.getAbsolutePath() : null);

        var runner = new PlaybackRunner(p.getOut(), p.getErr(), p.getTerminalIO(), p.isInTestMode());
        return runner.run(provider, true, Optional.empty(),
                Optional.ofNullable(patchDir).map(File::getPath), files(),
                Objects.requireNonNull(common));
    }
}

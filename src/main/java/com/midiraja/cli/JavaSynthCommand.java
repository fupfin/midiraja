/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.midiraja.cli;

import com.midiraja.MidirajaCommand;

import org.jspecify.annotations.Nullable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Plays MIDI files through Java's built-in software synthesizer (Gervill).
 * Experimental: no native dependencies, but lower quality than other backends.
 */
@Command(name = "java",
        mixinStandardHelpOptions = true,
        description = "Play with Java's built-in software synthesizer (experimental, no native deps).")
public class JavaSynthCommand implements Callable<Integer> {

    @ParentCommand
    @Nullable
    private MidirajaCommand parent;

    @Parameters(arity = "1..*", description = "MIDI files, directories, or .m3u playlists to play.")
    private List<File> files = new ArrayList<>();

    @Mixin
    private CommonOptions common = new CommonOptions();

    @Override
    public Integer call() throws Exception {
        var p = java.util.Objects.requireNonNull(parent);

        var provider = new com.midiraja.midi.JavaSynthProvider();

        var runner = new PlaybackRunner(p.getOut(), p.getErr(), p.getTerminalIO(), p.isInTestMode());
        return runner.run(provider, true, Optional.empty(), Optional.empty(), files, common);
    }
}

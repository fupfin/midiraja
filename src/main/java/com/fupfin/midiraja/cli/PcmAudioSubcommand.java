/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import java.io.File;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Abstract base for subcommands that build a native PCM audio pipeline.
 * Provides the shared {@link FxOptions} mixin, {@link CommandSpec}, and the
 * {@link #originalArgs()} helper so concrete subcommands need not repeat them.
 */
abstract class PcmAudioSubcommand
{
    @Mixin
    protected FxOptions fxOptions = new FxOptions();

    @Spec
    @Nullable
    protected CommandSpec spec;

    protected List<String> originalArgs()
    {
        var rawArgs = Objects.requireNonNull(spec).commandLine().getParseResult().originalArgs();
        return rawArgs.stream().map(this::absoluteIfExists).collect(Collectors.toList());
    }

    protected String absoluteIfExists(String token)
    {
        if (!token.startsWith("-"))
        {
            var f = new File(token);
            if (f.exists())
                return f.getAbsolutePath();
        }
        return token;
    }
}

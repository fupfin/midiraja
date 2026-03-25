/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import java.io.File;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import com.fupfin.midiraja.MidirajaCommand;
import com.fupfin.midiraja.cli.MidiMetaExtractor.MidiMeta;
import com.fupfin.midiraja.io.AppLogger;

@Command(name = "midi-info",
        mixinStandardHelpOptions = true,
        description = "Print MIDI file metadata (duration, title, copyright, lyrics) without playing.")
public class MidiInfoCommand implements Callable<Integer>
{
    @ParentCommand
    @Nullable
    private MidirajaCommand parent;

    @Spec
    @Nullable
    private CommandSpec spec;

    @Parameters(paramLabel = "FILE", arity = "1..*",
            description = "MIDI files, directories, or .m3u playlists.")
    private List<File> rawFiles = new ArrayList<>();

    @Option(names = {"-R", "--recursive"},
            description = "Recurse into directories.")
    private boolean recursive;

    @Option(names = {"--format"}, paramLabel = "FORMAT",
            description = "Output format: text (default), csv, tsv.",
            defaultValue = "text")
    private String format = "text";

    @Option(names = {"--log"}, paramLabel = "LEVEL",
            description = "Enable logging (error, warn, info, debug).")
    private Optional<String> logLevel = Optional.empty();

    @Override
    public Integer call()
    {
        AppLogger.configure(logLevel.orElse(null));

        // picocli-routed output (supports setOut/setErr in tests)
        PrintWriter out = spec != null
                ? spec.commandLine().getOut()
                : new PrintWriter(System.out, true); // NOSONAR fallback
        PrintWriter errWriter = spec != null
                ? spec.commandLine().getErr()
                : new PrintWriter(System.err, true); // NOSONAR fallback

        // PlaylistParser requires PrintStream — use parent.getErr() (same stream MidirajaCommand exposes)
        PrintStream errStream = parent != null ? parent.getErr() : System.err;

        var common = new CommonOptions();
        common.recursive = recursive;

        var parser = new PlaylistParser(errStream, false);
        List<File> files = parser.parse(rawFiles, common).files();

        if (files.isEmpty())
        {
            errWriter.println("No MIDI files found.");
            errWriter.flush();
            return 1;
        }

        var extractor = new MidiMetaExtractor();
        boolean hasError = false;
        long totalMicroseconds = 0;
        int successCount = 0;
        boolean isCsv = format.equals("csv") || format.equals("tsv");
        char sep = format.equals("tsv") ? '\t' : ',';

        if (isCsv)
            out.println("path" + sep + "duration_sec" + sep + "title" + sep
                    + "copyright" + sep + "instruments" + sep + "lyrics");

        boolean first = true;
        for (File file : files)
        {
            MidiMeta meta;
            try
            {
                meta = extractor.extract(file);
            }
            catch (Exception e)
            {
                errWriter.println("Warning: cannot read " + file + ": " + e.getMessage());
                errWriter.flush();
                hasError = true;
                continue;
            }

            totalMicroseconds += meta.durationMicroseconds();
            successCount++;

            if (isCsv)
            {
                String instruments = String.join(";", meta.instrumentNames());
                String durationSec = String.format("%.1f", meta.durationMicroseconds() / 1_000_000.0);
                out.println(csvField(file.getPath(), sep) + sep
                        + csvField(durationSec, sep) + sep
                        + csvField(meta.title(), sep) + sep
                        + csvField(meta.copyright(), sep) + sep
                        + csvField(instruments, sep) + sep
                        + csvField(meta.lyrics(), sep));
            }
            else
            {
                if (!first) out.println();
                first = false;
                out.println("=== " + file.getName() + " ===");
                out.println("Duration:    " + formatDuration(meta.durationMicroseconds()));
                boolean hasAny = false;
                if (!meta.title().isEmpty())
                {
                    out.println("Title:       " + meta.title());
                    hasAny = true;
                }
                if (!meta.copyright().isEmpty())
                {
                    out.println("Copyright:   " + meta.copyright());
                    hasAny = true;
                }
                if (!meta.instrumentNames().isEmpty())
                {
                    out.println("Instruments: " + String.join(", ", meta.instrumentNames()));
                    hasAny = true;
                }
                if (!meta.lyrics().isEmpty())
                {
                    out.println("Lyrics:");
                    for (String line : meta.lyrics().split("\n", -1))
                        out.println("  " + line);
                    hasAny = true;
                }
                if (!hasAny) out.println("(no metadata)");
            }
        }

        if (!isCsv)
        {
            out.println();
            out.printf("Total: %s  (%d file%s)%n",
                    formatDuration(totalMicroseconds), successCount, successCount == 1 ? "" : "s");
        }
        out.flush();
        return hasError ? 1 : 0;
    }

    static String formatDuration(long microseconds)
    {
        long totalSeconds = microseconds / 1_000_000L;
        return (totalSeconds / 60) + ":" + String.format("%02d", totalSeconds % 60);
    }

    /** RFC 4180 CSV field quoting. Works for both CSV (sep=',') and TSV (sep='\t'). */
    static String csvField(String value, char sep)
    {
        if (value == null || value.isEmpty()) return "";
        if (value.indexOf(sep) < 0 && value.indexOf('"') < 0
                && value.indexOf('\n') < 0 && value.indexOf('\r') < 0)
            return value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}

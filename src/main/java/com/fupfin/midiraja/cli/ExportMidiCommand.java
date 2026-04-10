/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import com.fupfin.midiraja.export.midi.MidiExporter;

@Command(name = "midi", mixinStandardHelpOptions = true, description = "Convert a music file to MIDI format.")
public class ExportMidiCommand implements Callable<Integer>
{
    @Parameters(index = "0", description = "Input file (MIDI, VGM, MOD, etc.)")
    private final File input = new File("");

    @Parameters(index = "1", arity = "0..1", description = "Output MIDI file. Omit to write to stdout.")
    @org.jspecify.annotations.Nullable
    private File output;

    @Override
    public Integer call()
    {
        var exporter = new MidiExporter();
        if (output != null)
        {
            exporter.export(input, output, java.util.Set.of());
            System.out.println("Exported: " + output);
        }
        else
        {
            try (var out = new java.io.BufferedOutputStream(System.out))
            {
                exporter.export(input, out, java.util.Set.of());
            }
            catch (IOException e)
            {
                throw new UncheckedIOException(e);
            }
        }
        return 0;
    }
}

/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.concurrent.Callable;
import javax.sound.midi.Sequence;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import com.fupfin.midiraja.export.vgm.Ay8910VgmExporter;
import com.fupfin.midiraja.export.vgm.MsxVgmExporter;
import com.fupfin.midiraja.export.vgm.Opl3VgmExporter;
import com.fupfin.midiraja.export.vgm.Ym2413VgmExporter;
import com.fupfin.midiraja.format.MusicFormatLoader;

@Command(name = "vgm", mixinStandardHelpOptions = true, description = "Convert a music file to VGM format.")
public class ExportVgmCommand implements Callable<Integer>
{
    @Option(names = "--system", required = true, description = "Target chip system: ay8910, ym2413, msx, opl3")
    private String system = "";

    @Parameters(index = "0", description = "Input file (MIDI, VGM, MOD, etc.)")
    private final File input = new File("");

    @Parameters(index = "1", arity = "0..1", description = "Output VGM file. Omit to write to stdout.")
    @org.jspecify.annotations.Nullable
    private File output;

    @Override
    public Integer call()
    {
        Sequence sequence;
        try
        {
            sequence = MusicFormatLoader.load(input, java.util.Set.of());
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to load: " + input, e);
        }

        if (output != null)
        {
            try (var out = new FileOutputStream(output))
            {
                exportTo(sequence, out);
            }
            catch (IOException e)
            {
                throw new UncheckedIOException(e);
            }
            System.out.println("Exported: " + output);
        }
        else
        {
            exportTo(sequence, System.out);
        }
        return 0;
    }

    private void exportTo(Sequence sequence, OutputStream out)
    {
        switch (system.toLowerCase(java.util.Locale.ROOT))
        {
            case "ay8910" -> new Ay8910VgmExporter().export(sequence, out);
            case "ym2413" -> new Ym2413VgmExporter().export(sequence, out);
            case "msx" -> new MsxVgmExporter().export(sequence, out);
            case "opl3" -> new Opl3VgmExporter().export(sequence, out);
            default -> throw new IllegalArgumentException(
                    "Unknown system: '" + system + "'. Valid values: ay8910, ym2413, msx, opl3");
        }
    }
}

/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.Callable;
import javax.sound.midi.Sequence;

import org.jspecify.annotations.Nullable;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import com.fupfin.midiraja.export.vgm.ChipHandlers;
import com.fupfin.midiraja.export.vgm.ChipSpec;
import com.fupfin.midiraja.export.vgm.CompositeVgmExporter;
import com.fupfin.midiraja.export.vgm.RoutingMode;
import com.fupfin.midiraja.format.MusicFormatLoader;

@Command(name = "vgm", mixinStandardHelpOptions = true, description = "Convert a music file to VGM format.")
public class ExportVgmCommand implements Callable<Integer>
{
    static final class ChipSpecOptions
    {
        @Option(names = "--system", description = "Named chip preset: zxspectrum, fmpac, msx, msx-scc, sb16, genesis, megadrive, adlib, pc98, pc88, x68000, neogeo, neogeo-b, gameboy, dmg, pce, huc6280, nes, nesapu")
        @Nullable
        String system;

        @Option(names = "--chips", description = "Chip combination, e.g. ay8910+ym2413, ay8910,ym2413, scc>ay8910 or opl3. Separators: '+' or ',' = CHANNEL mode (round-robin by MIDI channel); '>' = SEQUENTIAL mode (fill first chip before second).")
        @Nullable
        String chips;
    }

    @ArgGroup(exclusive = true, multiplicity = "1")
    private ChipSpecOptions chipSpec = new ChipSpecOptions();

    @Parameters(index = "0", description = "Input file (MIDI, VGM, MOD, etc.)")
    private final File input = new File("");

    @Parameters(index = "1", arity = "0..1", description = "Output VGM file. Omit to write to stdout.")
    @Nullable
    private File output;

    @Override
    public Integer call() throws Exception
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
            System.out.println("Exported: " + output);
        }
        else
        {
            exportTo(sequence, System.out);
        }
        return 0;
    }

    private void exportTo(Sequence sequence, OutputStream out) throws Exception
    {
        var spec = resolveChipSpec();
        new CompositeVgmExporter(ChipHandlers.create(spec), spec.mode()).export(sequence, out);
    }

    ChipSpec resolveChipSpec()
    {
        if (chipSpec.system != null)
        {
            var chips = ChipHandlers.PRESETS.get(chipSpec.system.toLowerCase(Locale.ROOT));
            if (chips == null)
                throw new IllegalArgumentException(
                    "Unknown --system value: '" + chipSpec.system
                            + "'. Valid values: " + ChipHandlers.PRESETS.keySet());
            return new ChipSpec(chips, RoutingMode.SEQUENTIAL);
        }
        return ChipHandlers.parseChips(java.util.Objects.requireNonNull(chipSpec.chips));
    }
}

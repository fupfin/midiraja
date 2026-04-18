/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import static java.util.Objects.requireNonNull;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import javax.sound.midi.Sequence;

import org.jspecify.annotations.Nullable;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import com.fupfin.midiraja.MidirajaCommand;
import com.fupfin.midiraja.dsp.SpectrumAnalyzerFilter;
import com.fupfin.midiraja.engine.PlaylistContext;
import com.fupfin.midiraja.export.vgm.ChipHandlers;
import com.fupfin.midiraja.export.vgm.ChipSpec;
import com.fupfin.midiraja.export.vgm.ChipType;
import com.fupfin.midiraja.export.vgm.CompositeVgmExporter;
import com.fupfin.midiraja.export.vgm.RoutingMode;
import com.fupfin.midiraja.format.MusicFormatLoader;
import com.fupfin.midiraja.io.AppLogger;
import com.fupfin.midiraja.midi.vgm.FFMLibvgmBridge;
import com.fupfin.midiraja.midi.vgm.LibvgmPlaybackEngine;
import com.fupfin.midiraja.midi.vgm.LibvgmSynthProvider;

@Command(name = "vgm", aliases = {
        "chiptune" }, mixinStandardHelpOptions = true, description = "VGM/VGZ direct playback or MIDI → VGM → libvgm synthesis.")
public class VgmCommand extends PcmAudioSubcommand implements Callable<Integer>
{
    static final class ChipSpecOptions
    {
        @Option(names = "--system", description = "System preset: zxspectrum, msx, msx-scc, sb16, megadrive, adlib, pc98, pc88, x68000, neogeo, neogeo-b, gameboy, pc-engine, nes, fm-towns. Default: megadrive.")
        @Nullable
        String system;

        @Option(names = "--chips", description = "Chip combination. Available chips: ay8910, ym2413, scc, scci, opl3, sn76489, ym2612, ym3812, ym2608, ym2151, ym2610, ym2610b, ym2203, dmg, huc6280, nes_apu, msm6258, rf5c68. Separators: '+' or ',' = CHANNEL mode (round-robin by MIDI channel); '>' = SEQUENTIAL mode (fill first chip before second). E.g. ay8910+ym2413 or scc>ay8910.")
        @Nullable
        String chips;
    }

    @ParentCommand
    @Nullable
    private MidirajaCommand parent;

    @Parameters(index = "0..*", arity = "1..*", description = "VGM, VGZ, MIDI, MOD, or other music files to play.")
    private List<File> files = new ArrayList<>();

    @ArgGroup(exclusive = true, multiplicity = "0..1")
    ChipSpecOptions chipSpec = new ChipSpecOptions();

    @Mixin
    private final CommonOptions common = new CommonOptions();

    // ── State ─────────────────────────────────────────────────────────────────

    /** Chip label resolved per-file; used to set portSuffix on each engine instance. */
    private volatile String chipLabel = "";

    // ── Test-accessible setters ───────────────────────────────────────────────

    public void setFiles(List<File> files)
    {
        this.files = files;
    }

    // ── Chip spec resolution ──────────────────────────────────────────────────

    ChipSpec resolveChipSpec()
    {
        if (chipSpec.chips != null)
            return ChipHandlers.parseChips(chipSpec.chips);
        String sys = chipSpec.system != null ? chipSpec.system.toLowerCase(Locale.ROOT) : "megadrive";
        var chips = ChipHandlers.PRESETS.get(sys);
        if (chips == null)
            throw new IllegalArgumentException(
                "Unknown --system value: '" + sys + "'. Valid values: " + ChipHandlers.PRESETS.keySet());
        return new ChipSpec(chips, RoutingMode.SEQUENTIAL);
    }

    // ── Validation ────────────────────────────────────────────────────────────

    public void validateFiles()
    {
        if (files == null || files.isEmpty())
            throw new IllegalArgumentException("At least one input file is required.");
    }

    // ── File type detection ───────────────────────────────────────────────────

    public static boolean isVgmFile(File file)
    {
        String name = file.getName().toLowerCase(Locale.ROOT);
        return name.endsWith(".vgm") || name.endsWith(".vgz");
    }

    // ── CLI entry point ───────────────────────────────────────────────────────

    @Override
    public Integer call() throws Exception
    {
        AppLogger.configure(common.logLevel.orElse(null));
        var p = requireNonNull(parent);

        var np = NativeAudioPipeline.build(2, common, fxOptions);
        var spectrumFilter = np.spectrumFilter();

        var bridge = new FFMLibvgmBridge();
        var provider = new LibvgmSynthProvider(bridge, np.pipeline());
        if (fxOptions.masterGain != null)
            provider.setMasterGain(fxOptions.masterGain);

        var runner = new PlaybackRunner(p.getOut(), p.getErr(), p.getTerminalIO(), false, fxOptions,
                (seq, prov, ctx, pip, shutdown, speed, startTime) ->
                        buildEngine(seq, provider, ctx, spectrumFilter));
        runner.setSpectrumFilter(spectrumFilter);
        runner.setSequenceLoader(file -> loadSequenceAndPrimeProvider(file, provider));

        return runner.run(provider, true, Optional.empty(), Optional.empty(), files, common,
                originalArgs());
    }

    private LibvgmPlaybackEngine buildEngine(Sequence seq, LibvgmSynthProvider provider,
            PlaylistContext ctx, SpectrumAnalyzerFilter spectrumFilter)
    {
        var engine = new LibvgmPlaybackEngine(seq, provider, ctx);
        if (!chipLabel.isEmpty())
            engine.setChipSuffix("(" + chipLabel + ")");
        engine.setSpectrumFilter(spectrumFilter);
        return engine;
    }

    private Sequence loadSequenceAndPrimeProvider(File file, LibvgmSynthProvider provider)
            throws Exception
    {
        if (isVgmFile(file))
        {
            chipLabel = "";
            provider.loadVgmFile(file.getAbsolutePath());
            return new Sequence(Sequence.PPQ, 480); // unknown duration
        }

        var sequence = MusicFormatLoader.load(file, Set.of());
        var spec = resolveChipSpec();
        chipLabel = spec.chips().stream()
                .map(ChipType::name)
                .collect(Collectors.joining(", "));
        var vgmBytes = new ByteArrayOutputStream();
        new CompositeVgmExporter(ChipHandlers.create(spec), spec.mode()).export(sequence, vgmBytes);
        provider.loadVgmData(vgmBytes.toByteArray());
        return sequence;
    }

}
